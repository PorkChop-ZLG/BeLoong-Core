package com.zonlong.beloong.client;

import com.zonlong.beloong.client.model.MoModel;
import com.zonlong.beloong.entity.MoEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 末（Mo）渲染器 —— GeckoLib 的标准实体渲染器，**刻意保持最小实现**。
 * <p>
 * 与地黄龙渲染器同款：不缩放、不偏移、不覆写 {@code preRender}。
 * 模型资产（211 骨骼 / 752 立方体）自带正确的落地高度 —— 脚底在 y ≈ 0，
 * 因此**不需要**像地黄龙当初担心的"陷地 / 悬空"补偿（风险 R9 已实测结案）。
 *
 * <h2>剔除盒为什么不在本类</h2>
 * 本模型的翅膀向后伸出约 7.4 格，远超 0.6 × 1.8 的碰撞箱，必须扩大剔除盒；
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

    public MoRenderer(EntityRendererProvider.Context context) {
        super(context, new MoModel());
    }
}
