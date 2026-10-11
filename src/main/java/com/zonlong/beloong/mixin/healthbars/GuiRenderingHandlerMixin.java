package com.zonlong.beloong.mixin.healthbars;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.compat.healthbars.ExecuteLineOverlay;
import fuzs.healthbars.client.handler.GuiRenderingHandler;
import fuzs.healthbars.client.helper.HealthTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.LivingEntity;
import org.apache.commons.lang3.mutable.MutableInt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把 Health Bars **左上角那条血条**的几何与实体转交给 {@link ExecuteLineOverlay}，
 * 由后者画出斩杀线覆盖层。本类<b>不含任何几何或颜色逻辑</b>（与同目录的
 * {@code HealthTrackerHandlerMixin} 同一风格）。
 *
 * <h3>为什么注入 GUI 专属的私有方法</h3>
 * 上游有两条血条路径：{@code GuiRenderingHandler}（准星拾取怪物的左上角血条，本类要的）
 * 与 {@code InLevelRenderingHandler}（生物头顶的世界血条，**不要**碰）。二者最终都调用同一个
 * {@code HealthBarRenderHelper.renderHealthBar}，所以只有注入 GUI 侧这个**私有**方法
 * 才能只影响左上角那一条。而它的参数里已经带齐了我们需要的全部信息
 * （{@code posX} / {@code posY} / {@code barWidth} / {@code entity}），
 * 锚点、右对齐、立绘偏移、配置等上游都算好了。
 *
 * <h3>为什么是 INVOKE + shift AFTER 而不是 {@code @At("TAIL")}</h3>
 * 该方法顺序是「画条 → 画伤害飘字 → {@code flush()}」。注在 TAIL 会让橙色**盖住伤害飘字**；
 * 注在 {@code HealthBarRenderHelper.renderHealthBar} 调用**之后**，遮罩就夹在
 * 「条之上、飘字之下」。此时 pose 已累积 {@code +0.03} 的 z 位移（上游每次 blit 都
 * {@code pose().translate(0,0,0.03F)}），遮罩天然压在条上面。
 *
 * <h3>可选依赖处理（照抄同目录既有 Mixin 的三件套）</h3>
 * <ul>
 *   <li>{@code @Pseudo}：未装 Health Bars 时整体跳过；</li>
 *   <li>注入点 {@code require = 0}：上游改了私有方法名/签名时<b>静默失效</b>（遮罩消失）
 *       而不会在启动期硬崩；</li>
 *   <li>「首次生效」锚点日志：把「注入点失效」与「这次准星指的怪没挂斩杀线」区分开。</li>
 * </ul>
 * <b>注册位置必须是 {@code beloong.mixins.json} 的 {@code client} 数组</b>——本类引用了
 * 纯客户端模组的类，专用服务端不得加载它。
 *
 * <p>方法描述符来自**实际依赖 jar** 的 {@code javap}（不是读源码猜的）：
 * {@code renderHealthBar} 确为 {@code private static}、8 参。</p>
 */
@Pseudo
@Mixin(value = GuiRenderingHandler.class, remap = false)
public abstract class GuiRenderingHandlerMixin {

    /** 锚点日志是否已打过（进程内一次）。 */
    @Unique
    private static boolean beloong$anchorLogged;

    /**
     * 在血条画完、伤害飘字画之前，插入斩杀线覆盖层。
     *
     * <p>参数必须与目标方法逐个对齐（含最后的 {@link CallbackInfo}——它是 {@code @Inject}
     * 处理器描述符的<b>必需</b>部分，删掉会在 runClient 时抛
     * {@code InvalidInjectionException: Invalid descriptor} 导致模组加载失败）。</p>
     */
    @Inject(
            method = "renderHealthBar(Lnet/minecraft/client/gui/GuiGraphics;"
                    + "Lorg/apache/commons/lang3/mutable/MutableInt;"
                    + "Lorg/apache/commons/lang3/mutable/MutableInt;"
                    + "FLnet/minecraft/client/gui/Font;"
                    + "Lfuzs/healthbars/client/helper/HealthTracker;"
                    + "Lnet/minecraft/world/entity/LivingEntity;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lfuzs/healthbars/client/helper/HealthBarRenderHelper;renderHealthBar("
                            + "Lnet/minecraft/client/gui/GuiGraphics;IIF"
                            + "Lfuzs/healthbars/client/helper/HealthTracker;"
                            + "Lnet/minecraft/world/entity/LivingEntity;I"
                            + "Lfuzs/healthbars/config/ClientConfig$BarColors;)V",
                    shift = At.Shift.AFTER),
            require = 0,
            remap = false)
    private static void beloong$drawExecuteLine(final GuiGraphics graphics, final MutableInt posX,
                                                final MutableInt posY, final float partialTick,
                                                final Font font, final HealthTracker tracker,
                                                final LivingEntity entity, final int barWidth,
                                                final CallbackInfo ci) {
        // posX 传的是**上游的 posX**，它比血条左边界大 1（上游把 posX-1+barWidth/2 当中心传下去）。
        // 「减 1」这一步刻意留在 ExecuteLineOverlay 里，以保住「本 mixin 不含几何」的分层。
        // 注意「&&」的左操作数一定会求值：draw 总是被执行，日志只打一次。
        if (ExecuteLineOverlay.draw(graphics, posX.intValue(), posY.intValue(), barWidth, entity)
                && !beloong$anchorLogged) {
            beloong$anchorLogged = true;
            BeLoongCore.LOGGER.info("[BeLoong] healthbars: execute line overlay active");
        }
    }
}
