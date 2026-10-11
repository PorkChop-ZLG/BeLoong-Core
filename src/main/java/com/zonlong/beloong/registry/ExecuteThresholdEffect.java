package com.zonlong.beloong.registry;

import by.dragonsurvivalteam.dragonsurvival.util.AdditionalEffectData;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.ability.ExecuteAbility;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * 「斩杀线」状态效果——斩杀被动的标记与结算核心。
 *
 * <h3>它标记什么</h3>
 * <ul>
 *   <li><b>斩杀线</b>：由 {@code amplifier} 编码，{@code 斩杀线 = (amplifier + 1) × 0.5%}。
 *       技能等级 L 对应 {@code amplifier = 2L + 7}（L1 → 9 → 5.0%；L16 → 39 → 20.0%）。
 *       <p><b>⚠️ 2026-10-11 更正（数值）</b>：此处原写「{@code amplifier = L + 4}
 *       （L1 → 5 → 3.0%；L15 → 19 → 10.0%）」—— 那是 16 级 / 5%→20% 改版<b>之前</b>的 15 级口径。
 *       真值由 {@link #amplifierForThreshold(float)} 与 {@code execute.json} 决定：
 *       百分比 = {@code L + 4}（L1 → 5.0%，L16 → 20.0%），amplifier = 该百分比 / 0.5% − 1 = {@code 2L + 7}。</p>
 *       <p><b>⚠️ 2026-10-11 更正</b>：这里原写「amplifier 是 {@link MobEffectInstance} 里
 *       <b>唯一会被原版自动同步到客户端</b>的数值」—— <b>该说法对旁观者是错的</b>。
 *       原版只把效果的<b>粒子外观</b>放进实体元数据
 *       （{@code LivingEntity.DATA_EFFECT_PARTICLES}），{@code MobEffectInstance} 本体
 *       <b>不发给旁观者</b>；{@code ClientboundUpdateMobEffectPacket} 仅发给效果持有者本人、
 *       其乘客、以及登录/骑乘时（{@code LivingEntity:1057}、{@code ServerPlayer:1493/1505}、
 *       {@code PlayerList:535}）。因此客户端对一只怪调 {@code getEffect(...)} 永远是 null,
 *       要做客户端表现<b>必须自己发一条包</b> —— 本模组用
 *       {@code compat.healthbars.ExecuteMarkSyncPayload}（发送方
 *       {@code registry.ExecuteMarkSyncHandler}，触发点 {@code LivingEntityExecuteMarkSyncMixin}）。
 *       amplifier 这个编码方式本身仍然成立（服务端结算照常读它），只是不能指望它自动到达旁观者。</p></li>
 *   <li><b>斩杀者</b>：由 DS 自己的 {@code AdditionalEffectData} 机制记录。DS 的
 *       {@code EffectHandler#handleEffectApplication} 会把 {@code MobEffectEvent.Added}
 *       的 {@code effectSource} 写进 effect 实例，而 {@code LivingEntity#addEffect(instance, source)}
 *       的第 2 个参数正是它 ⇒ 施加时传 {@code player} 即可，本模组不需要再造一份附件。
 *       该字段只存在服务端（DS 只做了 NBT 存档，没有网络同步），结算全部在服务端完成，因此够用。</li>
 * </ul>
 *
 * <h3>为什么结算放在 effect tick</h3>
 * 需求是「敌人<b>血量低于</b>斩杀线时触发」——这是一个<b>状态</b>而不是某个瞬时事件：
 * 掉血可能来自别的玩家、火焰、摔落或 DoT。放在受害者身上的 tick 里判定，
 * 上述所有来源都能覆盖；斩杀者离线/掉技能时自然失效，不需要清理逻辑。
 *
 * <p><b>⚠️ 2026-10-11 更新（本段原结论已部分作废）</b>：原写「斩杀者正在冷却时，目标会卡在斩杀线
 * 以下，冷却结束的瞬间立刻被斩杀」。自冷却语义**反转为「冷却期间不得挂线」**后（设计文档 §十七），
 * 这条只在「标记仍在目标身上」时成立：斩杀一旦触发就会**消耗标记并进冷却**，而冷却期间无法重新挂线
 * ⇒ 「卡在线上等着被斩」通常需要**另一位不在冷却的龙**来挂线。
 * 判据本身（血量低于线即触发）没有变。</p>
 *
 * @see ExecuteAbility 能力等级与数值来源
 * @see ExecuteCooldown 冷却记账
 * @see com.zonlong.beloong.registry.ExecuteMarkHandler 标记的施加方
 */
public class ExecuteThresholdEffect extends MobEffect {

    /** 斩杀线伤害类型：{@code data/beloong/damage_type/execute.json}。 */
    public static final ResourceKey<DamageType> DAMAGE_TYPE = ResourceKey.create(
            Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "execute"));

    /** 每级（amplifier + 1）提升的斩杀线，0.5%。 */
    public static final float THRESHOLD_PER_LEVEL = 0.005F;

    /** 每级提升的斩杀线，以「百分比数值」计：0.5 表示 0.5%。 */
    public static final float THRESHOLD_STEP_PERCENT = 0.5F;

    /** 斩杀触发时打给玩家的提示语言键。 */
    public static final String MESSAGE_TRIGGERED = "message.beloong.execute.triggered";

    /**
     * 斩杀被目标挡下时的提示键（{@code message.beloong.execute.refused}）。
     *
     * <p>存在的理由：有些生物的 {@code hurt} 会<b>无条件</b>拒绝我们的伤害（见 {@code execute} 里的 (a)）。
     * 那种情况下标记被消耗、冷却已开始，但<b>伤害没有造成</b> —— 此时若仍显示「已斩杀」就是撒谎，
     * 玩家也无从知道发生了什么（此前这类失败完全静默）。</p>
     */
    public static final String MESSAGE_REFUSED = "message.beloong.execute.refused";

    /** 标记方块（mob effect）的颜色：暗红 {@code RGB(139,0,0)}（因 {@code visible} / {@code showIcon} 全关，仅登记语义）。 */
    private static final int COLOR = 0x8B0000;

    public ExecuteThresholdEffect() {
        super(MobEffectCategory.HARMFUL, COLOR);
    }

    /**
     * 斩杀线比例（0.005 = 0.5%）。
     *
     * <p><b>刻意不做上限夹取</b>（用户 2026-10-11 裁定）：{@code amplifier} 越大斩杀线越高，
     * 超过 100% 也允许 —— 那是「几乎无条件斩杀」的合法极端，不作为缺陷处理。
     * 正常情况下 amplifier 由本模组自己编码（L1 → 9，L16 → 39），不会越界；
     * 外部（指令 / 数据包）给超大 amplifier 也走同一条路，只是此时通常没有 applier，结算闸门②会拦住。</p>
     */
    public static float thresholdFraction(int amplifier) {
        return (amplifier + 1) * THRESHOLD_PER_LEVEL;
    }

    /** 由斩杀线比例反解 amplifier；技能等级 L 的斩杀线落在 {@code L + 4}。 */
    public static int amplifierForThreshold(float percent) {
        return Math.max(0, Math.round(percent / THRESHOLD_STEP_PERCENT) - 1);
    }

    /** 每 tick 都要判定一次血量，因此恒定返回 true。 */
    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return true;
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        // 原版在双端都会调 tick；结算只在服务端做。
        if (entity.level().isClientSide() || !(entity.level() instanceof ServerLevel serverLevel)) {
            return true;
        }

        MobEffectInstance instance = entity.getEffect(ModMobEffects.EXECUTE_THRESHOLD);
        if (instance == null) {
            return true;
        }

        // ① 血量是否已跌破斩杀线（严格小于）
        float line = entity.getMaxHealth() * thresholdFraction(instance.getAmplifier());
        if (entity.getHealth() >= line) {
            return true;
        }

        // ② 斩杀者是否还在（DS 存在 effect 实例上的 applier）
        Entity applier = ((AdditionalEffectData) instance).dragonSurvival$getApplier(serverLevel);
        if (!(applier instanceof ServerPlayer player) || player.serverLevel() != serverLevel) {
            return true;
        }

        // ③ 斩杀者是否还持有技能（等级 ≥ 1、未被禁用）
        ExecuteAbility.Config config = ExecuteAbility.config(player);
        if (config == null) {
            return true;
        }

        // ④ 冷却是否走完
        if (!ExecuteCooldown.isReady(player)) {
            return true;
        }

        execute(player, entity, config);
        return true;
    }

    /**
     * 结算一次斩杀：真实伤害 → 清理标记 → 记账冷却 → 粒子 / 音效 / 提示。
     *
     * <p>伤害来源用 {@link ExecuteDamageSource}，并且<b>把击杀者挂上去</b>
     * （{@code new ExecuteDamageSource(damageType, player)}）——原版 {@code LivingEntity#die}
     * 里所有「算不算玩家击杀」的分支都读 {@code damageSource.getEntity()}：
     * 击杀统计（{@code Player#killedEntity}）、掉落表的 {@code ATTACKING_ENTITY}、
     * {@code PLAYER_KILLED_ENTITY} 进度判据、{@code dropExperience(...)}。
     * 该伤害来源不带实体的话，死亡消息里虽然有玩家的名字（走 {@code getKillCredit()}），
     * 但击杀不会算在他头上。</p>
     *
     * <p>另外在这之前显式 {@code setLastHurtByPlayer}：它同时喂给掉落表的
     * {@code LAST_DAMAGE_PLAYER}、经验值计算与 {@code getKillCredit()}，与伤害来源里的实体互为兜底。</p>
     */
    private static void execute(ServerPlayer player, LivingEntity victim, ExecuteAbility.Config config) {
        ServerLevel level = player.serverLevel();
        Holder<DamageType> damageType = level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(DAMAGE_TYPE);

        float damage = config.damage().calculate(config.level());

        // 先钉死击杀归因，再结算伤害：死亡消息与掉落都在 hurt() 内部就会被算出来。
        victim.setLastHurtByPlayer(player);

        // (a) **无论这一下有没有造成伤害，都必须消耗标记并进入冷却**（用户 2026-10-11 裁定）。
        //
        // 为什么：有些生物会用「无条件 return false」拒绝我们的伤害，而且**不看任何标签** ——
        // 钢铁守护者在正面 / 非破防窗口（EntityWroughtnaut.hurt:194-207，它的
        // BYPASSES_INVULNERABILITY 判断在「伤害源无实体」的 else 分支里，我们的伤害带玩家，够不到）；
        // 云筑魔像在垂直 4 格外或受击后 10 tick 内（Cloud_GolemEntity.hurt:1181/1184）。
        // 通用手段（forceAddEffect / Result.APPLY / 伤害类型标签）都进不到目标的 hurt 内部，
        // 所以那是**作者刻意的拒绝**，我们承认它挡得住。
        //
        // ⚠️ 曾经试过给 beloong:execute 加 minecraft:bypasses_invulnerability，以绕过伤害钳制
        // （灾变 IABoss_monster.hurt:137-140 会因该标签跳过 Math.min(DamageCap(), amount)）。
        // 但用户实测确认它「类似 /kill」：该标签被凋灵出生无敌（WitherBoss:483）、灾变各 Boss 的
        // 转阶段无敌、ArmorStand / ItemFrame / Ghast / Warden 的无敌、以及不死图腾判定
        // （LivingEntity:1306-1308 checkTotemDeathProtection）**共同读取** —— 列入它等于对所有这些
        // 读取方同时宣布「这是 kill 级伤害」，**会让部分 Boss 的动画失效**，故 2026-10-11 已撤回。
        // 初版设计文档 §5.4 也早就写明「刻意不加」（那是 /kill 级别的口子）。
        // 静态门 validate_execute.py [5] 与 validate_execute_line.py [9b] 都钉住「不得存在」。
        //
        // 但若此时不消耗标记，就会每 tick 重试一次：目标自己的受击音效刷屏（钢铁守护者尤其明显），
        // 且标记永远耗不掉。冷却的语义由此明确为「触发过就进冷却，成败不论」。
        boolean damaged = victim.hurt(new ExecuteDamageSource(damageType, player), damage);
        // 以「目标是否真的死了」作为「斩杀成功」的判据，而**不是** hurt() 的布尔返回值（2026-10-11 审查 I-1）：
        //   · 灾变系在伤害上限下 hurt 返回 true（伤害落地但只掉 20~25，IABoss_monster.java:140）⇒ 布尔会谎报成功；
        //   · 目标早已死亡时 hurt 返回 false（LivingEntity:1147-1148），但那是「我们没介入」，
        //     既不是成功、也不该报「被它挡下」。
        // 判据用 isDeadOrDying()，与 hurt 自己拒绝死亡实体的那一条**同一个谓词**。
        boolean killed = victim.isDeadOrDying();

        // 标记用完即弃，避免死亡动画期间重复结算。
        //
        // 注意：这里是在 LivingEntity#tickEffects 的迭代过程中移除效果，理论上会让该迭代器在下一次
        // next() 时抛 ConcurrentModificationException——但原版自己就是这么容忍的
        // （tickEffects 用空 catch 吞掉了它，见 LivingEntity.java:816-817，而
        // MobEffectInstance.tick 在 applyEffectTick 返回 false 时也会走同一路径）。
        // 后果仅是「该生物本 tick 剩下的状态效果少 tick 一次」，可以接受。
        //
        // ⚠️ 这一句**必须无条件执行**：它是「不会每 tick 重试」的唯一保证。
        // 门第 ⑩ 组会断言 hurt 调用与这一句之间不存在任何 return。
        victim.removeEffect(ModMobEffects.EXECUTE_THRESHOLD);

        if (killed && !damaged) {
            // 目标在我们出手前就已经死了 —— 典型场景：一击把它从斩杀线【之上】直接打死，
            // 而标记是由那一击的 LivingDamageEvent.Post 挂上的（于是挂在了尸体上）。
            // 用户 2026-10-11 裁定：**消耗标记**（否则死亡动画期间会每 tick 重试），
            // 但**不进冷却** —— 我们的斩杀从未真正介入；对玩家静默，只留一条 DEBUG。
            BeLoongCore.LOGGER.debug(
                    "[BeLoong] execute: {} ({}) was already dying; mark consumed, no cooldown",
                    victim.getDisplayName().getString(), victim.getType());
            return;
        }

        ExecuteCooldown.mark(player, Math.max(0, (int) config.cooldown().calculate(config.level())));

        if (!damaged || !killed) {
            // (d) 被目标挡下：**不能**谎报「已斩杀」（伤害没造成，或造成了但没打死）。
            // 给玩家明确反馈 + 留一条日志 —— 这一类失败此前完全静默（云筑魔像那次连音效都没有），
            // 只能靠实机现象反推。
            BeLoongCore.LOGGER.info(
                    "[BeLoong] execute: {} ({}) survived our damage; mark consumed, cooldown started",
                    victim.getDisplayName().getString(), victim.getType());
            player.displayClientMessage(
                    Component.translatable(MESSAGE_REFUSED, victim.getDisplayName()),
                    /* actionbar = */ true);
            return;
        }

        playTriggerEffects(level, player, victim);

        player.displayClientMessage(
                Component.translatable(MESSAGE_TRIGGERED, victim.getDisplayName()),
                /* actionbar = */ true);
    }

    /**
     * 触发表现：粒子缠在<b>受害者</b>身上，音效挂在<b>施法者</b>身上。
     *
     * <p>音效的发声点自 2026-10-10 起由受害者改为施法者（用户需求）。第 1 个参数仍传 {@code null}，
     * 即广播给附近所有玩家（含施法者自己）——变的只是发声位置。</p>
     *
     * <p>音效类别同日由 {@link SoundSource#HOSTILE} 改为 {@link SoundSource#PLAYERS}：发声点已经在
     * 玩家身上，归到「玩家」滑条更符合直觉（代价：调「敌对生物」滑条不再影响它）。</p>
     *
     * <p><b>音量</b>是本音效唯一的旋钮：2026-10-10 实机反馈偏响，由 {@code 1.0F} 降到 {@code 0.5F}。
     * 它只改变响度，<b>不改变可听距离</b>——距离由 {@code sounds.json} 的 {@code attenuation_distance}
     * 决定，本音效未设置，走原版默认衰减。</p>
     */
    private static void playTriggerEffects(ServerLevel level, ServerPlayer player, LivingEntity victim) {
        double spread = Math.max(0.3D, victim.getBbWidth() * 0.8D);

        level.sendParticles(
                ParticleTypes.DRAGON_BREATH,
                victim.getX(),
                victim.getY() + victim.getBbHeight() * 0.5D,
                victim.getZ(),
                60,
                spread,
                Math.max(0.3D, victim.getBbHeight() * 0.6D),
                spread,
                0.02D);

        level.playSound(
                null,
                player.getX(),
                player.getY(),
                player.getZ(),
                ModSounds.EXECUTE.get(),
                SoundSource.PLAYERS,
                0.5F,   // 音量：2026-10-10 实机反馈偏响，由 1.0F 降半——这是本音效唯一的音量旋钮
                1.0F);  // 音高
    }
}
