package com.zonlong.beloong.mixin.asteorbar;

import com.afoxxvi.asteorbar.overlay.parts.PlayerHealthOverlay;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修复 AsteorBar 在「最大生命 &gt; 20 的玩家换维度 / 重生后」血量显示被压小的 BUG。
 *
 * <p><b>症状</b>：龙之生存的成长会把玩家最大生命抬到 20 以上；此时换维度或重生后，
 * AsteorBar（以及任何读 {@code getHealth()} 的 HUD）会显示被压小后的血量（如真实 40 显示成 20），
 * 且不会自行恢复。
 *
 * <p><b>根因</b>（原版行为 + 换维度时的包序）：
 * <ol>
 *   <li>{@code LivingEntity.setHealth} 会把血量钳到<b>当前</b> {@code getMaxHealth()}
 *       （1.21.1 源码 {@code LivingEntity.java:1130-1132}）；</li>
 *   <li>原版在<b>最大生命属性变化</b>时会用新上限回写血量
 *       （{@code onAttributeUpdated}，{@code LivingEntity.java:1101-1104}）
 *       ⇒ 只要某一瞬间客户端看到的最大生命还只是基础值（20），血量就会被压到 20；</li>
 *   <li>换维度 / 重生时客户端会<b>新建 LocalPlayer</b>，属性包与实体数据包可能分先后到达；
 *       龙之生存改最大生命时同样是「先 {@code removeModifiers} 再 {@code applyModifiers}」
 *       （{@code DSModifiers.updateGrowthModifiers}），中间必然存在最大生命掉回基础值的窗口；</li>
 *   <li>等真正的最大生命（如 40）到达时，<b>没有任何机制把已被压低的血量抬回来</b>
 *       （服务端不会因为属性变化而重发血量）⇒ 客户端长期显示错值。</li>
 * </ol>
 *
 * <p><b>修复思路</b>（客户端侧补偿：不改服务端、也不改 AsteorBar 源码）：
 * <ul>
 *   <li>用 {@code player.tickCount} <b>回退</b>判断「实体被重建」（新实体 ⇒ 计数归零）；</li>
 *   <li>命中后进入 {@link #BELOONG_FREEZE_TICKS} 帧冻结：冻结期间显示<b>上一个实体缓存的正确血量</b>，
 *       视觉上不跳变；</li>
 *   <li>冻结结束时属性包应已到达（{@code getMaxHealth()} 已是正确值），此时调用
 *       {@code player.setHealth(min(缓存血量, 当前上限))} 把客户端血量<b>修回正确值</b>，
 *       之后恢复正常读取；</li>
 *   <li>阈值 {@link #BELOONG_FIX_THRESHOLD} 避免把正常掉血误判成「被钳位」。</li>
 * </ul>
 *
 * <p><b>为什么注入 {@code getParameters} 里那一次 {@code Player.getHealth()}</b>：
 * 那是 AsteorBar 读玩家血量的唯一入口（对实际依赖 jar 做 {@code javap} 实测：
 * 该方法内 {@code invokevirtual Player.getHealth:()F} 恰好 1 次），用 {@code @Redirect} 做
 * <b>语义锚点</b>比按「第 N 个局部变量存值」定位更稳（后者在 AsteorBar 前面多一个浮点存值时会打错位置）。
 * <b>已知代价</b>：这是「症状层」补偿 —— 它在 HUD 渲染路径里写实体状态；若将来要根治，
 * 应改成「在属性包到达后修一次」或让服务端在属性变化后重发血量。
 *
 * <p><b>可选依赖处理</b>：AsteorBar 是可选模组（{@code compileOnly + localRuntime}），
 * 因此本 Mixin 用 {@code @Pseudo} + {@code require = 0}：未安装时整体跳过，
 * 上游改了方法名时静默失效（表现为旧 BUG 复现）而不会在启动期硬崩；
 * 同时在 {@code neoforge.mods.toml} 里声明为 {@code optional + versionRange}，
 * 让「版本不匹配」在启动日志里可见。
 */
@Pseudo
@Mixin(value = PlayerHealthOverlay.class, remap = false)
public abstract class AsteorBarHealthFixMixin {

    /**
     * 冻结帧数：等待属性包把最大生命纠正过来。
     *
     * <p>历史实现取 10 帧（设计文档写的是 20 帧 —— 两者不一致，这里以实测可用的 10 为准）。
     */
    @Unique
    private static final int BELOONG_FREEZE_TICKS = 10;

    /** 修正阈值：只有「缓存血量比当前读数高出这么多」才认为是钳位造成的，避免误修正常掉血。 */
    @Unique
    private static final float BELOONG_FIX_THRESHOLD = 1.0F;

    /** 上一次见到的 {@code tickCount}（回退即代表实体被重建）。 */
    @Unique
    private static int beloong$lastTickCount = -1;

    /** 重建前那个实体的血量（冻结期间用它显示）。 */
    @Unique
    private static float beloong$lastGoodHealth;

    /** 剩余冻结帧数。 */
    @Unique
    private static int beloong$freezeTicksRemaining;

    /** 冻结结束后要修回的客户端血量（&gt; 0 表示待修）。 */
    @Unique
    private static float beloong$healthFixTarget;

    /**
     * 顶替 {@code PlayerHealthOverlay#getParameters} 里的 {@code player.getHealth()} 调用：
     * 正常路径原样返回，实体重建后的冻结期返回旧实体的血量，冻结结束时顺手把被钳位的血量修回。
     *
     * @param player 被渲染血条的玩家（原调用点的方法接收者）
     * @return 本次显示应当使用的血量
     */
    @Redirect(
            method = "getParameters",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;getHealth()F"
            ),
            require = 0,
            remap = false
    )
    private float beloong$fixHealth(Player player) {
        float health = player.getHealth();

        // tickCount 回退 ⇒ 实体被重建（换维度 / 重生）：进入冻结并记住要修回的血量
        if (player.tickCount < beloong$lastTickCount) {
            beloong$freezeTicksRemaining = BELOONG_FREEZE_TICKS;
            beloong$healthFixTarget = beloong$lastGoodHealth;
        }

        beloong$lastTickCount = player.tickCount;

        // 冻结期内显示旧实体的血量，等属性包把最大生命纠正过来
        if (beloong$freezeTicksRemaining > 0) {
            beloong$freezeTicksRemaining--;
            return beloong$lastGoodHealth;
        }

        // 冻结结束：把被钳位的客户端血量修回正确值（min 防溢出治疗）
        if (beloong$healthFixTarget > 0) {
            if (beloong$healthFixTarget > health + BELOONG_FIX_THRESHOLD) {
                player.setHealth(Math.min(beloong$healthFixTarget, player.getMaxHealth()));
            }
            beloong$healthFixTarget = 0;
            health = player.getHealth();
        }

        beloong$lastGoodHealth = health;
        return health;
    }
}
