package com.zonlong.beloong.client;

import com.zonlong.beloong.client.model.MoModel;
import com.zonlong.beloong.entity.MoEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 末（Mo）渲染器 —— GeckoLib 标准实体渲染器 + 一个模型缩放。
 * <p>
 * 不覆写 {@code preRender}、不做平移。模型资产自带正确的落地高度 —— 脚底在 y ≈ 0，
 * 因此**不需要**像地黄龙当初担心的"陷地 / 悬空"补偿（风险 R9 已实测结案）。
 *
 * <h2>为什么需要缩放</h2>
 * 该模型是给 Yes Steve Model（YSM）用的玩家模型，但**不是按原版玩家的骨架尺寸导出**的。
 * 实测（16 单位 = 1 格）：
 * <pre>
 *   本模型：脚 y = 0  →  头部立方体顶端 y = 39.82 单位 = 2.49 格
 *   原版玩家：脚 y = 0  →  头顶 y = 32 单位 = 2.00 格（碰撞箱 1.8，模型比箱高 0.2 是原版惯例）
 *   ⇒ 缩放 = 32 / 39.82 ≈ 0.804，取 0.80
 * </pre>
 * 缩放后头顶落在 31.86 单位 ≈ 1.99 格，与原版玩家一致；碰撞箱保持 {@code 0.6 × 1.8}
 * （见 {@code registry/ModEntities}），于是"模型比碰撞箱高 0.2 格"这一点也与原版同构。
 * <p>
 * <b>缩放为什么走 {@code withScale} 而不是 {@code preRender} 里手写 {@code poseStack.scale}</b>：
 * {@code withScale} 是 GeckoLib 的官方开关
 * （{@code GeoEntityRenderer.java:131-143} 设 {@code scaleWidth/scaleHeight} 字段，
 * 由 {@code :192} 传给 {@code scaleModelForRender}，其默认实现即 {@code poseStack.scale(...)}，
 * 见 {@code GeoRenderer.java:367-370}）。用它就不必自己插进渲染管线，
 * 也不会与 GeckoLib 自己的 {@code isReRender} 分支（用于渲染层重绘）打架。
 *
 * <h2>剔除盒为什么不在本类</h2>
 * 本模型的翅膀向后伸出约 6 格，远超 {@code 0.6 × 1.8} 的碰撞箱，必须扩大剔除盒；
 * 但原版取的是 **{@code Entity#getBoundingBoxForCulling()}**
 * （{@code EntityRenderer.java:58/76}），是**实体上的方法**，不是渲染器上的
 * —— 所以那个覆写写在 {@link MoEntity#getBoundingBoxForCulling()} 里。
 * 放在本类会是一个不覆盖任何东西的"孤儿方法"（{@code @Override} 直接编译失败）。
 *
 * <h2>已知观感缺口（不修，记录备查）</h2>
 * <ul>
 *   <li><b>没有影子</b>：{@code shadowRadius} 默认 {@code 0.0F}
 *       （{@code EntityRenderer.java:31}），而只有半径 > 0 时才绘制
 *       （{@code EntityRenderDispatcher.java:168-176}）。这是**全仓既有现象**
 *       （地黄龙同样没有），属代码审查 P1-7；要修是构造器里一行
 *       （参考量级：原版末影龙 0.5F、不死马 1.0F）。</li>
 *   <li><b>发光件不发光</b>：资产里 7 根带几何体的 {@code ysmGlow*} 骨骼
 *       （瞳孔 / 虹膜 / 高光）在 YSM 里走独立的自发光通道，GeckoLib 无此概念，
 *       会按普通不透明几何体渲染 ⇒ 眼睛不亮。详见分析报告问题 5。</li>
 * </ul>
 */
public class MoRenderer extends GeoEntityRenderer<MoEntity> {

    /**
     * 模型缩放。取值依据见类注释：{@code 32 / 39.82 ≈ 0.804}。
     * <p>
     * 这是**唯一**影响模型大小的开关 —— 2026-09-27 实机反馈"模型比碰撞箱大太多"，
     * 主因其实不是这里，而是翅膀层动画泄露了 {@code Root.scale = 1.8}
     * （渲染放大 1.8 倍）；那一条已在资产里修掉，详见
     * {@link MoEntity#registerControllers()} 与 {@code docs/models/末/mo-模型分析.md}。
     */
    private static final float MODEL_SCALE = 0.80F;

    public MoRenderer(EntityRendererProvider.Context context) {
        super(context, new MoModel());
        // 官方缩放开关：GeckoLib 会在渲染前对模型做一次等比缩放
        this.withScale(MODEL_SCALE);
    }
}
