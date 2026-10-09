package com.zonlong.beloong.mixin.legendarymonsters;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.registry.ModDamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.unusual.block_factorys_bosses.entity.boss.knight.UnderworldKnightEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
 * <p><b>扣层时机</b>：先调 {@code processHurt} 并以其<b>返回值</b>为准 —— 返回 {@code true} 才扣层。
 * 这样天然覆盖两个边界：过场（{@code isCinematic()} 时 {@code processHurt} 直接返回 {@code false}）
 * 不掉层；0 伤害不掉层。也顺手规避了 {@code removeOneImmuneStack()} 没有下限保护的坑
 * （扣之前判 {@code > 0}）。
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

    /** 日志锚点节流间隔（毫秒）：太阳射线是持续伤害，不节流会刷屏。 */
    private static final long LOG_THROTTLE_MS = 5000L;

    /** 上次打印锚点的时间戳（毫秒）；只在服务端主线程读写，无需同步。 */
    private static long lastLogMillis = 0L;

    /**
     * 命中破防标签时，改走"无视护盾 + 扣 1 层"的结算路径。
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

        // 走"打中冥界印记"那条通道：无视护盾全额结算，保留血量闸门与动画表现
        boolean dealt = self.processHurt(source, amount, true);

        // 只有真的造成了伤害才扣层：过场(cinematic) 与 0 伤害都不会消耗护盾
        if (dealt && self.getImmuneStacks() > 0) {
            self.removeOneImmuneStack();
        }

        logAnchor(source, stacksBefore, self.getImmuneStacks(), dealt);
        cir.setReturnValue(dealt);
    }

    /**
     * 打印锚点日志（节流），用于确认注入真的生效。
     *
     * <p>日志一律英文 ASCII；缺了这行就说明骑士侧注入没生效（未装「首领崛起」或上游改了签名）。
     */
    private static void logAnchor(DamageSource source, int stacksBefore, int stacksAfter, boolean dealt) {
        long now = System.currentTimeMillis();
        if (now - lastLogMillis < LOG_THROTTLE_MS) {
            return;
        }
        lastLogMillis = now;

        String typeId = source.typeHolder()
                .unwrapKey()
                .map(key -> key.location().toString())
                .orElse(source.getMsgId());
        BeLoongCore.LOGGER.info(
                "[BeLoong] solar-guard-break: source={} stacks={}->{} dealt={}",
                typeId, stacksBefore, stacksAfter, dealt);
    }
}
