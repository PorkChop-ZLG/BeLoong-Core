package com.zonlong.beloong.client;

import com.zonlong.beloong.entity.NpcEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * <b>通用 NPC 渲染器基类</b> —— {@link GeoEntityRenderer} 之上只做一件事：
 * 把脚下阴影半径接到实体侧的默认值上。
 *
 * <h2>为什么需要这么一个基类</h2>
 * 阴影半径是 {@code EntityRenderer} 的<b>渲染器侧</b>字段，但"这个 NPC 看起来该多大"
 * 是<b>实体侧</b>的语义。用户裁定：默认影子应当是"玩家大小的影子"，并且这个默认
 * 属于**通用 NPC 类** —— 于是默认值定义在 {@link NpcEntity#shadowRadius()}，
 * 由本类读取，各 NPC 的渲染器不必各写一遍。
 * <p>
 * 这样分层后，子类要例外只需在**实体**里覆写 {@code shadowRadius()}，
 * 渲染器一行都不用动（例：地黄龙，见 {@code DihuangLoongEntity#shadowRadius()}）。
 *
 * <h2>为什么可以走"覆写 getShadowRadius"这条路</h2>
 * 阴影绘制用的是**方法**而不是字段（{@code EntityRenderDispatcher.java:168-169}）：
 * <pre>
 *   float f = entityrenderer.getShadowRadius(entity);
 *   if (f &gt; 0.0F) { ... renderShadow(...) }
 * </pre>
 * 所以覆写它就能逐实体决定半径。
 * <p>
 * 另外注意 {@code GeoEntityRenderer} 继承的是 {@code EntityRenderer} 而<b>不是</b>
 * {@code LivingEntityRenderer}，因此这里的返回值不会像原版那样再乘
 * {@code entity.getScale()}（后者在 {@code LivingEntityRenderer.java:283}）——
 * 实体侧给多少就是多少。
 *
 * <h2>为什么必须给默认值</h2>
 * {@code EntityRenderer.java:31} 的 {@code shadowRadius} 字段<b>没有初值</b>（0.0F），
 * 而 GeckoLib 的渲染器从不设它 ⇒ <b>所有 GeckoLib 实体默认都没有影子</b>。
 * 地黄龙与末都踩过这一条（代码审查 P1-7）。
 *
 * @param <T> 该渲染器服务的 NPC 类型
 */
public abstract class NpcRenderer<T extends NpcEntity> extends GeoEntityRenderer<T> {

    protected NpcRenderer(EntityRendererProvider.Context context, GeoModel<T> model) {
        super(context, model);
    }

    /** 阴影半径取自实体侧的可覆写默认值，见 {@link NpcEntity#shadowRadius()}。 */
    @Override
    protected float getShadowRadius(T entity) {
        return entity.shadowRadius();
    }
}
