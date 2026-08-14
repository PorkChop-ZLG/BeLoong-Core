package com.zonlong.beloong.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.entity.MeteorEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/**
 * 陨石实体的客户端渲染器。
 * <p>
 * 将陨石渲染为一个始终面向摄像机的发光岩石贴图（billboard），
 * 使用满亮度（{@link LightTexture#FULL_BRIGHT}）实现自发光效果。
 * 火焰尾焰粒子由服务端下发（{@link MeteorEntity#tick}），此处仅渲染本体。
 */
public class MeteorEntityRenderer extends EntityRenderer<MeteorEntity> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "textures/entity/meteor.png");

    public MeteorEntityRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public ResourceLocation getTextureLocation(MeteorEntity entity) {
        return TEXTURE;
    }

    @Override
    public void render(MeteorEntity entity, float yaw, float partialTicks,
                       PoseStack pose, MultiBufferSource buffer, int light) {
        pose.pushPose();
        pose.translate(0.0F, 0.5F, 0.0F);
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));

        PoseStack.Pose poseEntry = pose.last();
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        int fullBright = LightTexture.FULL_BRIGHT;

        float half = 0.5F;
        // 面向摄像机的四边形，UV 原点在左上
        addVertex(consumer, poseEntry, -half, -half, 0.0F, 0.0F, 1.0F, fullBright);
        addVertex(consumer, poseEntry,  half, -half, 0.0F, 1.0F, 1.0F, fullBright);
        addVertex(consumer, poseEntry,  half,  half, 0.0F, 1.0F, 0.0F, fullBright);
        addVertex(consumer, poseEntry, -half,  half, 0.0F, 0.0F, 0.0F, fullBright);

        pose.popPose();
        super.render(entity, yaw, partialTicks, pose, buffer, light);
    }

    private static void addVertex(VertexConsumer consumer, PoseStack.Pose pose,
                                  float x, float y, float z, float u, float v, int light) {
        consumer.addVertex(pose.pose(), x, y, z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setLight(light)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);
    }
}
