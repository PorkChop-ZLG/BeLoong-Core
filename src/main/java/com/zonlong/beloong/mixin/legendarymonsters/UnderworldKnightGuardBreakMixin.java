package com.zonlong.beloong.mixin.legendarymonsters;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.registry.ModDamageTypeTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import net.unusual.block_factorys_bosses.entity.boss.knight.UnderworldKnightEntity;
import net.unusual.block_factorys_bosses.init.BossesRiseParticleTypes;
import net.unusual.block_factorys_bosses.init.BossesRiseSounds;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;

/**
 * 让「可破防」标签里的伤害击穿冥界骑士（赫尔瓦）的护盾，并按设计每次命中扣 1 层免疫层数。
 *
 * <p><b>为什么必须注入而不是靠数据包</b>：骑士的"无敌"完全实现在它自己的
 * {@code hurt}/{@code processHurt} 里（{@code isInvulnerable() = 免疫层数 > 0}），
 * 而"破防"这件事在模组里只有一条内部通道 —— {@code processHurt(source, amount, true)}
 * （{@code fromMark = true}，即"打中冥界印记"那条路）。纯数据包（包括把类型加进原版
 * {@code #bypasses_invulnerability}）只能让伤害落地，<b>无法消耗护盾层数、也无法走闸门逻辑</b>。
 *
 * <p><b>为什么注入 {@code hurt} 的 HEAD 而不是 {@code processHurt} 内部</b>：
 * <ul>
 *   <li>单点注入最稳；</li>
 *   <li>{@code processHurt(..., true)} 的 {@code flag} 分支会<b>跳过 {@code stuck} 分支</b>，
 *       因此不会出现"二次扣层"或误播 {@code KNIGHT_BLOCK}；</li>
 *   <li>本 Mixin 不调用 {@code setState}，所以不会踩到已知的
 *       "在 {@code fake_dead}（阶段 1 假死）期间强制 {@code knocked_down} ⇒ 永久死锁"（调研 §六 B2）——
 *       破防语义被刻意收窄为"只扣 1 层"，不倒地、不双倍伤害。</li>
 * </ul>
 *
 * <p><b>扣层时机（三个条件缺一不可）</b>：先调 {@code processHurt} 拿返回值与结算后的层数，只有同时满足
 * <ol>
 *   <li>{@code dealt == true} —— {@code processHurt} 返回真（等价于它的 {@code super.hurt} 被调到）；</li>
 *   <li>{@code amount > 0} —— <b>必需</b>：{@code LivingEntity.hurt} 在 <b>0 伤害</b>时也会返回 {@code true}
 *       （其内部 {@code flag2 = !flag || amount > 0.0F}），只看返回值会出现"没掉血却扣层、还播护盾破碎反馈"；</li>
 *   <li>{@code stacksAfter > 0 && stacksAfter <= stacksBefore} —— <b>必需</b>：50% 血量闸门会在
 *       {@code processHurt} 内部执行 {@code setImmuneStacks(2|1)} 把护盾<b>重新装上</b>；若
 *       {@code stacksAfter > stacksBefore}，说明这一层是刚被闸门加回来的，不能再被同一击吃掉
 *       （模组自带的印记路径是"先扣后结算"，我们顺序相反，必须靠这条判据）。</li>
 * </ol>
 * 第三条同时规避了 {@code removeOneImmuneStack()} 没有下限保护的坑（判 {@code > 0} 才扣）。
 * 过场（{@code isCinematic()}）时 {@code processHurt} 直接返回 {@code false} ⇒ 第一条即拦住。
 *
 * <p><b>破防反馈</b>：真的消耗掉一层时，复刻模组"打中冥界印记"的视听反馈
 * （{@code KnightMarkEntity.hurt:126-130} 的原样两件套：{@code KNIGHT_STACK_REMOVE} +
 * {@code KNIGHT_HURT} 两个音效、{@code MARK_GLINT_EXP} + {@code MARK_GLINT_EXP_2} 两个粒子）。
 * <b>只在真正扣层时播</b>：印记是"命中即碎"的一次性实体，天然不会连播；我们的标签伤害
 * （射线是持续伤害）会高频命中，若每次都播会变成机关枪，而且层数为 0 之后再播"护盾破碎"
 * 音效也是误导。
 *
 * <p><b>只在服务端动作</b>：与模组自身的冥界印记路径一致（{@code KnightMarkEntity.hurt} 只在
 * {@code ServerLevel} 结算），否则客户端会改本地状态并出现表现不同步。
 *
 * <p>「首领崛起」在本模组是<b>可选</b>依赖（{@code compileOnly} + {@code localRuntime}），
 * 因此这里用 {@code @Pseudo} + {@code require = 0}：未安装时本 Mixin 整体跳过，
 * 表现为"太阳伤害被护盾挡下"的原状（日志锚点也不会出现）。
 */
@Pseudo
@Mixin(value = UnderworldKnightEntity.class, remap = false)
public abstract class UnderworldKnightGuardBreakMixin {

    /** 计数行的最小间隔（毫秒）：太阳射线是持续伤害，逐条打印会刷屏；**层数变化行不受此限**。 */
    private static final long COUNT_LOG_MIN_INTERVAL_MS = 1000L;

    /** 累计命中次数（进程内；服务端主线程单线程读写，无需同步）。 */
    private static int totalHits = 0;

    /** 累计"真的造成了伤害"的命中次数。 */
    private static int dealtHits = 0;

    /** 按伤害类型 id 分别累计的命中次数。 */
    private static final Map<String, Integer> HITS_BY_SOURCE = new HashMap<>();

    /** 上次输出计数行的时间戳（毫秒）。 */
    private static long lastCountLogMillis = 0L;

    /**
     * 命中破防标签时，改走"无视护盾 + 扣 1 层"的结算路径，并在真的扣层时播放破防反馈。
     *
     * @param source 伤害来源（由 Mowzie 三招转换而来的太阳伤害类型）
     * @param amount 伤害量
     * @param cir    返回值回调：以 {@code processHurt} 的结果作为 {@code hurt} 的结果
     */
    @Inject(
            method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void beloong$solarGuardBreak(
            DamageSource source,
            float amount,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!Config.SolarGuardBreak.enabled.get()) {
            return;
        }

        UnderworldKnightEntity self = (UnderworldKnightEntity) (Object) this;
        if (self.level().isClientSide()) {
            return;
        }

        // 不在标签内 ⇒ 不 cancel，原 hurt 逻辑（护盾格挡、stuck 破防、印记等）完整保留
        if (!source.is(ModDamageTypeTags.UNDERWORLD_KNIGHT_GUARD_BREAK)) {
            return;
        }

        int stacksBefore = self.getImmuneStacks();
        float healthBefore = self.getHealth();

        // 走"打中冥界印记"那条通道：无视护盾全额结算，保留血量闸门与动画表现
        boolean dealt = self.processHurt(source, amount, true);

        int stacksAfter = self.getImmuneStacks();

        // 只扣"真的打掉的那一层"（判据见类注释）：
        //   amount > 0                    —— 0 伤害时 LivingEntity.hurt 也会返回 true；
        //   stacksAfter <= stacksBefore   —— 闸门可能在 processHurt 内部把盾装回来，装回来的不能再吃；
        //   stacksAfter > 0               —— removeOneImmuneStack 没有下限保护，扣之前必须判正。
        boolean consumed = false;
        if (dealt && amount > 0.0F && stacksAfter > 0 && stacksAfter <= stacksBefore) {
            self.removeOneImmuneStack();
            stacksAfter = self.getImmuneStacks();
            consumed = true;
        }

        // 真的扣掉一层才播反馈（与"击中冥界印记"同一套音效 + 粒子）
        if (consumed) {
            playMarkFeedback(self);
        }

        logHit(source, stacksBefore, stacksAfter, dealt, healthBefore, self.getHealth(), consumed);
        cir.setReturnValue(dealt);
    }

    /**
     * 复刻"击中冥界印记"的视听反馈。
     *
     * <p>逐项对齐 {@code KnightMarkEntity.hurt}：
     * <pre>
     * this.level().playSound(null, x, y + h/2, z, BossesRiseSounds.KNIGHT_STACK_REMOVE.value(), SoundSource.HOSTILE, 6.0F, 2.0F);
     * this.level().playSound(null, x, y + h/2, z, BossesRiseSounds.KNIGHT_HURT.value(),         SoundSource.HOSTILE, 6.0F, 2.0F);
     * serverLevel.sendParticles(BossesRiseParticleTypes.MARK_GLINT_EXP.get(),   x, y + h/2, z, 1, 0, 0, 0, 1.0);
     * serverLevel.sendParticles(BossesRiseParticleTypes.MARK_GLINT_EXP_2.get(), x, y + h/2, z, 1, 0, 0, 0, 1.0);
     * </pre>
     * 与印记唯一的差别是位置：印记用它自己的坐标，这里用骑士身上（脚底 + 半身高）——因为我们的
     * 路径没有印记实体可打。
     *
     * @param knight 被破防的冥界骑士
     */
    private static void playMarkFeedback(UnderworldKnightEntity knight) {
        Level level = knight.level();
        double x = knight.getX();
        double y = knight.getY() + (double) knight.getBbHeight() * 0.5;
        double z = knight.getZ();

        level.playSound(null, x, y, z, BossesRiseSounds.KNIGHT_STACK_REMOVE.value(),
                SoundSource.HOSTILE, 6.0F, 2.0F);
        level.playSound(null, x, y, z, BossesRiseSounds.KNIGHT_HURT.value(),
                SoundSource.HOSTILE, 6.0F, 2.0F);

        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(BossesRiseParticleTypes.MARK_GLINT_EXP.get(),
                    x, y, z, 1, 0.0, 0.0, 0.0, 1.0);
            serverLevel.sendParticles(BossesRiseParticleTypes.MARK_GLINT_EXP_2.get(),
                    x, y, z, 1, 0.0, 0.0, 0.0, 1.0);
        }
    }

    /**
     * 计数式锚点：每次命中都累加计数，日志里带上"第几次命中 / 本类型累计 / 层数 / 是否真的掉血 /
     * 血量变化 / 全场累计统计"。
     *
     * <p><b>输出策略</b>：层数发生变化（真正的破防）**必定记录**；其余命中按
     * {@value #COUNT_LOG_MIN_INTERVAL_MS} 毫秒聚合，避免太阳射线持续命中时刷屏。
     * 之前是"5 秒内只留一条、且不带计数"，导致一次连打只看得到 1 行、无法判断真实效果。
     *
     * <p><b>为什么记 {@code hp=before->after}</b>：{@code dealt=true} 只说明
     * {@code super.hurt} 被调到了，最终数值仍可能被护甲 / 抗性 / 无敌帧削减到 0；
     * 血量前后对比才能回答"到底有没有掉血"。
     *
     * <p>日志一律英文 ASCII；锚点整体缺失说明骑士侧注入没生效（未装「首领崛起」或上游改了签名）。
     */
    private static void logHit(DamageSource source, int stacksBefore, int stacksAfter, boolean dealt,
            float healthBefore, float healthAfter, boolean consumed) {
        String typeId = source.typeHolder()
                .unwrapKey()
                .map(key -> key.location().toString())
                .orElse(source.getMsgId());

        totalHits++;
        if (dealt) {
            dealtHits++;
        }
        int sourceHits = HITS_BY_SOURCE.merge(typeId, 1, Integer::sum);

        boolean stacksChanged = stacksBefore != stacksAfter;
        long now = System.currentTimeMillis();
        if (!stacksChanged && now - lastCountLogMillis < COUNT_LOG_MIN_INTERVAL_MS) {
            return;
        }
        lastCountLogMillis = now;

        BeLoongCore.LOGGER.info(
                "[BeLoong] solar-guard-break: #{} source={} (this-source={}) stacks={}->{} consumed={} dealt={} hp={}->{} | totals: hits={} dealt={} bySource={}",
                totalHits, typeId, sourceHits, stacksBefore, stacksAfter, consumed, dealt,
                healthBefore, healthAfter, totalHits, dealtHits, HITS_BY_SOURCE);
    }
}
