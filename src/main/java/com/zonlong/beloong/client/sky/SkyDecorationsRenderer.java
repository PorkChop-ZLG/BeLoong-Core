package com.zonlong.beloong.client.sky;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * 太阳与月亮本体绘制器。
 *
 * <p>使用资源包提供的 sun.png / moon_phases.png，
 * 太阳和月亮路径参考原版渲染方式绕 X 轴旋转。</p>
 */
public final class SkyDecorationsRenderer {

    private static final ResourceLocation SUN_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            BeLoongCore.MODID, "textures/environment/sun.png");
    private static final ResourceLocation MOON_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            BeLoongCore.MODID, "textures/environment/moon_phases.png");

    private SkyDecorationsRenderer() {
    }

    public static void render(ClientLevel level,
                              Matrix4f modelViewMatrix,
                              Matrix4f projectionMatrix,
                              float partialTick) {
        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(modelViewMatrix);

        RenderSystem.enableBlend();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO
        );

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(-90.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(level.getTimeOfDay(partialTick) * 360.0F));

        drawSun(poseStack);
        drawMoon(poseStack, level);

        poseStack.popPose();

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
    }

    private static void drawSun(PoseStack poseStack) {
        RenderSystem.setShaderTexture(0, SUN_TEXTURE);
        Matrix4f matrix4f = poseStack.last().pose();
        BufferBuilder bufferBuilder = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bufferBuilder.addVertex(matrix4f, -30.0F, 100.0F, -30.0F).setUv(0.0F, 0.0F);
        bufferBuilder.addVertex(matrix4f, 30.0F, 100.0F, -30.0F).setUv(1.0F, 0.0F);
        bufferBuilder.addVertex(matrix4f, 30.0F, 100.0F, 30.0F).setUv(1.0F, 1.0F);
        bufferBuilder.addVertex(matrix4f, -30.0F, 100.0F, 30.0F).setUv(0.0F, 1.0F);
        BufferUploader.drawWithShader(bufferBuilder.buildOrThrow());
    }

    private static void drawMoon(PoseStack poseStack, ClientLevel level) {
        RenderSystem.setShaderTexture(0, MOON_TEXTURE);
        int moonPhase = level.getMoonPhase();
        int xCoord = moonPhase % 4;
        int yCoord = moonPhase / 4 % 2;
        float startX = xCoord / 4.0F;
        float startY = yCoord / 2.0F;
        float endX = (xCoord + 1) / 4.0F;
        float endY = (yCoord + 1) / 2.0F;

        Matrix4f matrix4f = poseStack.last().pose();
        BufferBuilder bufferBuilder = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bufferBuilder.addVertex(matrix4f, -20.0F, -100.0F, 20.0F).setUv(endX, endY);
        bufferBuilder.addVertex(matrix4f, 20.0F, -100.0F, 20.0F).setUv(startX, endY);
        bufferBuilder.addVertex(matrix4f, 20.0F, -100.0F, -20.0F).setUv(startX, startY);
        bufferBuilder.addVertex(matrix4f, -20.0F, -100.0F, -20.0F).setUv(endX, startY);
        BufferUploader.drawWithShader(bufferBuilder.buildOrThrow());
    }
}
