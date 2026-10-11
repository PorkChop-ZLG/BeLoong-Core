package com.zonlong.beloong.mixin.healthbars;

import com.zonlong.beloong.BeLoongCore;
import fuzs.healthbars.client.handler.HealthTrackerHandler;
import fuzs.healthbars.client.helper.HealthTracker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 彻底禁用「本地玩家自身」的血量飘字：<b>回血与扣血都不再出现数字</b>；怪物与其它玩家完全不受影响。
 *
 * <p><b>背景（为什么会做这件事）</b>：
 * <ol>
 *   <li>龙之生存的成长会<b>按比例重设血量</b>：{@code DSModifiers.updateGrowthModifiers}
 *       （{@code DSModifiers.java:51-64}）先取 {@code healthPercentage = getHealth() / getMaxHealth()}，
 *       再改最大生命，最后 {@code player.setHealth(getMaxHealth() * healthPercentage)}
 *       ⇒ 血量绝对值抬高、百分比不变（玩家其实既没受伤也没被治疗）；</li>
 *   <li>Health Bars 只看血量<b>绝对差</b>，并把<b>任何非零</b>差值经
 *       {@code Mth.ceil(Math.abs(delta)) * Mth.sign(delta)} 变成 ±1（{@code HealthTracker.java:37-39}）
 *       ⇒ 成长期间出现连续的绿色 {@code +1}；粒子生存期 50 tick（2.5 秒），所以观感是"连刷"。</li>
 * </ol>
 *
 * <p><b>为什么最终放弃"来源识别"</b>（两轮失败留痕，供后来者参考）：
 * <ul>
 *   <li><b>第一轮</b>：只在"最大生命发生变化的那一 tick"做等比判定 ⇒ 实测无效
 *       （血量包与属性包常落在不同 tick；"再对齐"产生的微小残差照样冒字）。</li>
 *   <li><b>第二轮</b>：加"不足 1 点不显示"的噪声地板 ⇒ 实测仍无效。而"永远是 +1"这一事实，
 *       在 {@code Mth.ceil} 下等价于原始差值<b>恰好 1.0</b>，据此推断来源是原版自然回血
 *       （1.21.1 {@code FoodData.java:52-63} 的 {@code player.heal(1.0F)}）—— 委托方用
 *       {@code /gamerule naturalRegeneration false} 实测<b>直接否掉</b>了该推断，来源至今未能定位。</li>
 *   <li><b>结论</b>：任何"分类"策略都依赖对来源的正确识别，而这条路已被实测证伪。
 *       本地玩家自身的数字本来就是冗余信息（自己头顶与界面上就有血条：Health Bars 名牌、AsteorBar、
 *       原版心形），于是改为<b>一刀切</b>：不做分类，也就免疫包序、跨 tick 与浮点噪声。</li>
 * </ul>
 *
 * <p><b>实现（唯一规则）</b>：在 Health Bars 每实体 tick 末的钩子
 * {@code HealthTrackerHandler#onEndEntityTick} 上做两处注入，均<b>不写 Health Bars 任何状态</b>：
 * <ul>
 *   <li>{@code HEAD}：该实体是<b>本地玩家</b>（{@code LocalPlayer}）⇒ 置"本 tick 抑制"标志；</li>
 *   <li>{@code @Redirect}：把该方法里<b>唯一一处</b> {@code HealthTracker#getLastHealthDelta()} 的返回值改成 {@code 0}
 *       （对实际依赖 jar 做 {@code javap} 实测：{@code onEndEntityTick} 内该调用恰好 1 次，偏移 34）
 *       ⇒ Health Bars 自己的 {@code healthDelta != 0} 判断不成立 ⇒ <b>不生成飘字</b>。
 *       它的 {@code lastHealthDelta} / {@code health} / {@code maxHealth} / {@code lastHealth} 与血条动画全不受影响；
 *       GUI 血条数字走另一个方法 {@code getHealthDelta()}，且原版 {@code Level.java:906-907} 会在准星拾取时
 *       排除发起者自身 ⇒ 那条路本来也不会显示玩家自己的变化。</li>
 * </ul>
 *
 * <p><b>生效范围</b>：只管<b>本地玩家自己</b>（人形与龙形一致，所有血量变化都不显示）；
 * 怪物、其它玩家（含其它龙玩家）的飘字规则<b>保持原样</b>。
 * <b>代价（知情接受）</b>：本地玩家失去自身受伤/回血的数字反馈（血条仍可见）；
 * 且这是"消音"而非"治病"—— 服务端那次回血照旧发生、饱食度照旧消耗。
 *
 * <p><b>性能</b>：被抑制的数字不会创建 {@code DamageValueParticle}（生存期 50 tick），
 * 而该粒子的 {@code render()} 每帧都会 {@code bufferSource.endBatch()}
 * （<b>每个粒子每帧一次批次刷新</b>），文字默认走 8 遍描边
 * （{@code strongTextOutline} 为 true 时走 {@code Font#drawInBatch8xOutline}）
 * ⇒ 刷屏场景下可省掉一批每帧批次刷新与描边字形绘制；逻辑层的收益则可忽略。
 *
 * <p><b>可选依赖处理</b>：Health Bars 是可选模组（{@code implementation "curse.maven:health-bars-1367734:7122333"}），
 * 因此本 Mixin 用 {@code @Pseudo} + 全部注入点 {@code require = 0}：未安装时整体跳过；
 * 上游改了方法名/签名时<b>静默失效</b>（表现就是数字又出现）而不会在启动期硬崩。
 * {@code neoforge.mods.toml} 里已声明 {@code healthbars} 的
 * {@code optional + versionRange="[21.1.0,)" ordering="AFTER" side="CLIENT"}，让版本偏差在启动日志可见。
 */
@Pseudo
@Mixin(value = HealthTrackerHandler.class, remap = false)
public abstract class HealthTrackerHandlerMixin {

    /** 本 tick 是否抑制飘字（HEAD 置位、{@code @Redirect} 消费并清零，仅一次方法调用内有效）。 */
    @Unique
    private static boolean beloong$suppressThisTick;

    /** 锚点日志是否已打过（进程内一次）。 */
    @Unique
    private static boolean beloong$anchorLogged;

    /**
     * 判定本 tick 是否抑制飘字：只对<b>本地玩家</b>生效（一刀切，不做任何分类）。
     *
     * @param entity 本 tick 结束的实体（Health Bars 对每个实体都会调用）
     */
    @Inject(
            method = "onEndEntityTick(Lnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"),
            require = 0,
            remap = false
    )
    private static void beloong$hideSelfNumbers(Entity entity, CallbackInfo ci) {
        // 注意：最后一个 CallbackInfo 参数是 @Inject 处理器描述符的**必需**部分，
        // 即使本处不使用也不能删（删掉会在 runClient 时抛 InvalidInjectionException: Invalid descriptor，
        // 导致"模组加载失败"、客户端起不来）。
        beloong$suppressThisTick = entity instanceof LocalPlayer;
    }

    /**
     * 把"本次飘字数值"在被抑制时改成 0，从而让 Health Bars 不生成飘字。
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
                    "[BeLoong] healthbars: damage number suppressed (first hit this session)");
        }
        return 0;
    }
}
