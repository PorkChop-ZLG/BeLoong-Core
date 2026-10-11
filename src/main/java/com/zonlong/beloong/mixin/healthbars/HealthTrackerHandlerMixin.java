package com.zonlong.beloong.mixin.healthbars;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import com.zonlong.beloong.BeLoongCore;
import fuzs.healthbars.client.handler.HealthTrackerHandler;
import fuzs.healthbars.client.helper.HealthTracker;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让 Health Bars 不再把「龙之生存成长造成的等比血量抬升」显示成绿色治疗数字。
 *
 * <p><b>症状</b>：龙在成长期间，Health Bars 会持续冒绿色 {@code +1} 飘字，而玩家既没受伤也没被治疗。
 *
 * <p><b>根因</b>：
 * <ol>
 *   <li>成长时龙之生存<b>按比例重设血量</b>：{@code DSModifiers.updateGrowthModifiers}
 *       （{@code DSModifiers.java:51-64}）先算 {@code healthPercentage = health / maxHealth}，
 *       再 {@code removeModifiers} / {@code applyModifiers} 改最大生命，最后
 *       {@code player.setHealth(getMaxHealth() * healthPercentage)} ⇒ <b>血量绝对值变大、百分比不变</b>；</li>
 *   <li>Health Bars 只看<b>绝对差</b>：{@code HealthTracker.tick}（{@code HealthTracker.java:55-61}）
 *       把 {@code lastHealthDelta = health - this.health} 记为"这 tick 涨了血"，
 *       该类只额外记录 {@code maxHealth} 供血条动画用，<b>没有"最大生命变化引起的等比抬升"判定</b>；</li>
 *   <li>该差值被取整<b>放大</b>：{@code getLastHealthDelta()}（{@code HealthTracker.java:37-39}）
 *       {@code return Mth.ceil(Math.abs(x)) * Mth.sign(x)} ⇒ 哪怕成长只带来 <b>+0.0004</b> 也显示成 <b>+1</b>；</li>
 *   <li>每 tick 生成飘字：{@code HealthTrackerHandler.onEndEntityTick}（{@code HealthTrackerHandler.java:17-26}）
 *       检查 {@code healthDelta != 0 && renderDamageValues} 就 {@code addDamageValueParticle}。</li>
 * </ol>
 * Health Bars 的配置里只有 {@code renderDamageValues} / 颜色 / 描边（{@code ClientConfig.java:163-175}）
 * ⇒ <b>没有阈值、也没有"隐藏治疗"开关</b>，配置层压不下去。
 *
 * <p><b>修复思路（几何不变量 + 取值处抑制，不写第三方任何状态）</b>：
 * <ul>
 *   <li><b>判定</b>（本类 HEAD 注入）：只在<b>最大生命发生变化的那一 tick</b>判定 ——
 *       若 {@code maxHealth} 变了而血量<b>百分比</b>不变（容差 {@link #BELOONG_RATIO_TOLERANCE}），
 *       说明这次变化是等比抬升/缩小（成长，或被偷成长），置"本 tick 抑制"标志。
 *       对称判定是必须的：龙之生存存在偷成长（{@code EnchantmentEffectHandler.java:89-94}），
 *       等比缩小时若不抑制会冒<b>假伤害红字</b>。</li>
 *   <li><b>抑制</b>（本类 {@code @Redirect}）：把该方法里<b>唯一一处</b>
 *       {@code HealthTracker#getLastHealthDelta()} 的返回值改成 {@code 0}
 *       （对实际依赖 jar 做 {@code javap} 实测：{@code onEndEntityTick} 内该调用恰好 1 次）
 *       ⇒ Health Bars 自己的 {@code healthDelta != 0} 判断不成立 ⇒ <b>不生成飘字</b>。
 *       不改它的 {@code lastHealthDelta}/{@code health}/{@code maxHealth}/{@code lastHealth}，
 *       血条比例、动画与 GUI 数字（走另一个方法 {@code getHealthDelta()}）完全不受影响。</li>
 * </ul>
 * <b>为什么不需要采样窗口</b>：服务端那次重算的顺序固定（改属性 → {@code setHealth}），
 * 同一连接顺序送达 ⇒ "max 变了 + 百分比没变"在同一 tick 即可判定
 * （这也是与旧方案——采样 2 tick + 覆盖私有字段——的本质差别）。
 *
 * <p><b>生效范围与边界</b>：只对<b>龙玩家</b>、只在客户端生效（与 AsteorBar 修复同口径，
 * 且不影响其它模组自己的等比重算）；<b>负增量（真实伤害）永不抑制</b>。
 * <b>已知漏网</b>：① AsteorBar 修复在冻结结束写回血量那一刻，比例会变化 ⇒ 不抑制 ⇒
 * Health Bars 可能冒一个假治疗大数字（既有相互作用的残留）；② 同一 tick 内既有成长又有真实变化时，
 * 那一次数字会被吞掉（本修复的目标口径即"只保证纯成长不冒数字"）。
 *
 * <p><b>可选依赖处理</b>：Health Bars 是可选模组（{@code implementation "curse.maven:health-bars-1367734:7122333"}），
 * 因此本 Mixin 用 {@code @Pseudo} + 全部注入点 {@code require = 0}：未安装时整体跳过；
 * 上游改了方法名/签名时<b>静默失效</b>（表现就是"+1 又回来了"）而不会在启动期硬崩。
 * {@code neoforge.mods.toml} 里已声明 {@code healthbars} 的 {@code optional + versionRange="[21.1.0,)"}，
 * 让版本不匹配在启动日志里可见。
 *
 * <p><b>可观测性</b>：抑制的效果是"什么都不出现"，因此每次进程内<b>首次抑制</b>时打一条
 * INFO 锚点，便于实机区分"修好了"与"这次恰好没成长"。
 */
@Pseudo
@Mixin(value = HealthTrackerHandler.class, remap = false)
public abstract class HealthTrackerHandlerMixin {

    /**
     * 血量百分比比较的容差。
     *
     * <p>噪声量级：龙之生存用 {@code float} 算百分比（{@code DSModifiers.java:56,63}），
     * 比例本身相对误差 ~1e-7；再经实体数据包(float) + 属性包(double) 同步仍 ~1e-7。
     * 而 Health Bars 的 {@code ceil} 决定最小可显示变化是 <b>1 点血</b> ⇒ 比例变化 = {@code 1/max}
     * （max=40 ⇒ 0.025，max=1000 ⇒ 0.001），比本容差大 1~2 个数量级。
     * ⇒ {@code 1e-4} 有约 1000 倍噪声余量，同时不会吞掉任何"1 点血"级的真实变化。
     * 想更保守可调小到 {@code 1e-5}。
     */
    @Unique
    private static final double BELOONG_RATIO_TOLERANCE = 1.0e-4;

    /** 上一 tick 观察到的血量（只对龙玩家建条目；非龙/离开即移除，至多等于玩家数）。 */
    @Unique
    private static final Map<UUID, Float> beloong$prevHealth = new HashMap<>();

    /** 上一 tick 观察到的最大生命（同上）。 */
    @Unique
    private static final Map<UUID, Float> beloong$prevMaxHealth = new HashMap<>();

    /** 本 tick 是否抑制飘字（HEAD 置位、{@code @Redirect} 消费并清零，仅一次方法调用内有效）。 */
    @Unique
    private static boolean beloong$suppressThisTick;

    /** 锚点日志是否已打过（进程内一次）。 */
    @Unique
    private static boolean beloong$anchorLogged;

    /**
     * 判定"这一次血量变化是不是等比抬升/缩小"。
     *
     * @param entity 本 tick 结束的实体（Health Bars 对每个实体都会调用）
     */
    @Inject(
            method = "onEndEntityTick(Lnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"),
            require = 0,
            remap = false
    )
    private static void beloong$evaluateRescale(Entity entity, CallbackInfo ci) {
        // 注意：最后一个 CallbackInfo 参数是 @Inject 处理器描述符的**必需**部分，
        // 即使本处不需要取消注入也不能删（删掉会在 runClient 时抛
        // InvalidInjectionException: Invalid descriptor，导致模组加载失败）。
        beloong$suppressThisTick = false;

        // 只在客户端、只对龙玩家判定；其它一律清条目、不抑制
        if (!(entity instanceof Player player) || !entity.level().isClientSide()
                || !DragonStateProvider.isDragon(player)) {
            beloong$prevHealth.remove(entity.getUUID());
            beloong$prevMaxHealth.remove(entity.getUUID());
            return;
        }

        UUID id = entity.getUUID();
        float health = player.getHealth();
        float maxHealth = player.getMaxHealth();
        if (!Float.isFinite(health) || !Float.isFinite(maxHealth) || maxHealth <= 0.0F) {
            beloong$prevHealth.remove(id);
            beloong$prevMaxHealth.remove(id);
            return;
        }

        Float lastHealth = beloong$prevHealth.put(id, health);
        Float lastMaxHealth = beloong$prevMaxHealth.put(id, maxHealth);

        // 首次观测，或"最大生命没变" ⇒ 本 tick 不判定（保证恒定最大生命下的真实治疗/伤害永不被抑制）
        if (lastHealth == null || lastMaxHealth == null || lastMaxHealth <= 0.0F || maxHealth == lastMaxHealth) {
            return;
        }

        double before = lastHealth / (double) lastMaxHealth;
        double after = health / (double) maxHealth;
        beloong$suppressThisTick = Math.abs(after - before) <= BELOONG_RATIO_TOLERANCE;
    }

    /**
     * 把"本次飘字数值"在判定成立时改成 0，从而让 Health Bars 不生成飘字。
     *
     * @param tracker Health Bars 的每实体血量跟踪器（原调用点的方法接收者）
     * @return 抑制时 0；否则原值（行为与未装本 Mixin 时完全一致）
     */
    @Redirect(
            method = "onEndEntityTick(Lnet/minecraft/world/entity/Entity;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lfuzs/healthbars/client/helper/HealthTracker;getLastHealthDelta()I"
            ),
            require = 0,
            remap = false
    )
    private static int beloong$filterDelta(HealthTracker tracker) {
        boolean suppress = beloong$suppressThisTick;
        beloong$suppressThisTick = false; // 当次消费即清零，避免影响下一个实体

        if (!suppress) {
            return tracker.getLastHealthDelta();
        }

        if (!beloong$anchorLogged) {
            beloong$anchorLogged = true;
            BeLoongCore.LOGGER.info(
                    "[BeLoong] healthbars: growth-rescale number suppressed (first hit this session)");
        }
        return 0;
    }
}
