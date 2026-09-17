package com.zonlong.beloong.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.client.model.TornadoModel;
import com.zonlong.beloong.entity.TornadoEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * 龙卷风渲染器。
 * <p>
 * <b>缩放与锚点</b>：灾变原本是 {@code scale(-0.5,-0.5,0.5)} + {@code translate(0,-1.5,0)}。
 * 放大到 3x 时不能再照抄平移量——PoseStack 后调用的 translate 会被先调用的 scale 一起放大，
 * 照抄会让龙卷风飘走。这里改成「把模型底面（模型 y=24，即模型空间的地面平面）
 * 对齐到实体位置」，语义明确且与缩放无关。
 * <p>
 * <b>RenderType</b>：{@code entityCutoutNoCull}。贴图 alpha 是二值的（实测 3030 个不透明、
 * 13354 个全透明、无半透明），cutout 既保住镂空又不会进半透明排序队列；{@code NoCull}
 * 必须有——风柱是空心的，需要看到内壁。
 */
public class TornadoRenderer extends EntityRenderer<TornadoEntity> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "textures/entity/tornado.png");

    /** 在灾变原尺寸基础上再放大的倍数 */
    private static final float MODEL_SCALE = 3.0F;
    /** 模型根枢轴 y，即模型空间的地面平面 */
    private static final float MODEL_ROOT_Y = 24.0F;
    /** 动画时间取模上限，避免长时间累加导致浮点精度下降 */
    private static final float ANIM_MODULO = 3600.0F;

    private final TornadoModel model;

    public TornadoRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.model = new TornadoModel(context.bakeLayer(TornadoModel.LAYER));
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(TornadoEntity entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int packedLight) {
        pose.pushPose();
        pose.scale(-0.5F * MODEL_SCALE, -0.5F * MODEL_SCALE, 0.5F * MODEL_SCALE);
        pose.translate(0.0F, -MODEL_ROOT_Y, 0.0F);

        float ageInTicks = (entity.tickCount + partialTick) % ANIM_MODULO;
        this.model.setupAnim(ageInTicks);

        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        this.model.renderToBuffer(pose, vertexConsumer, packedLight, OverlayTexture.NO_OVERLAY);
        pose.popPose();

        super.render(entity, entityYaw, partialTick, pose, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(TornadoEntity entity) {
        return TEXTURE;
    }
}
