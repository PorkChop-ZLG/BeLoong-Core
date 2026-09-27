package com.zonlong.beloong.client;

import com.zonlong.beloong.client.model.DihuangLoongModel;
import com.zonlong.beloong.entity.DihuangLoongEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 地黄龙渲染器。
 * <p>
 * 刻意保持最小：**不缩放**（用户裁定：按模型默认尺寸渲染），也不加渲染层。
 * <p>
 * 将来若实机发现模型"陷地"或"悬空"，在此覆写 {@code preRender} 加一次平移即可 ——
 * 那是纯渲染层修正，**不动模型文件、也不动碰撞箱**（设计文档风险 R9）。
 * <p>
 * <b>2026-09-25 实机结论：不需要偏移</b>（模型落地后无陷地/悬空），故此处保持最小实现。
 * 若将来换了模型或碰撞箱，需重新确认这一条。
 */
@OnlyIn(Dist.CLIENT)
public class DihuangLoongRenderer extends GeoEntityRenderer<DihuangLoongEntity> {

    /**
     * 脚下阴影半径。
     * <p>
     * <b>为什么必须显式设</b>：{@code EntityRenderer.java:31} 的 {@code shadowRadius} 字段
     * <b>没有初值</b>（默认 0.0F），而阴影只在半径 &gt; 0 时才画
     * （{@code EntityRenderDispatcher.java:168-176}）。GeckoLib 全仓不设这个值，
     * 所以"GeckoLib 实体没有影子"是默认现象，不是本模型的问题 —— 地黄龙与末都中招。
     * <p>
     * <b>0.8 的依据</b>：{@code shadowRadius} 大致跟生物的碰撞箱宽度走。原版实测取值：
     * 玩家/僵尸/骷髅（宽 0.6）是 {@code 0.5}（{@code PlayerRenderer.java:49}），
     * 马（宽 1.4）是 {@code 0.75}（{@code AbstractHorseRenderer}），
     * <b>蜘蛛（宽 1.4）是 {@code 0.8}</b>（{@code SpiderRenderer}），不死马是 {@code 1.0}。
     * 地黄龙的碰撞箱宽 1.5，取蜘蛛那一档 {@code 0.8}。
     * <p>
     * 注：{@code GeoEntityRenderer} 继承的是 {@code EntityRenderer} 而非
     * {@code LivingEntityRenderer}，所以这里**不会**被 {@code getShadowRadius} 乘以
     * {@code entity.getScale()}（后者在 {@code LivingEntityRenderer.java:283}），
     * 直接赋字段就是最终值。
     */
    private static final float SHADOW_RADIUS = 0.8F;

    public DihuangLoongRenderer(EntityRendererProvider.Context context) {
        super(context, new DihuangLoongModel());
        this.shadowRadius = SHADOW_RADIUS;
    }
}
