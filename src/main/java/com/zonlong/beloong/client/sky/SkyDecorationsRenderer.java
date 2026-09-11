package com.zonlong.beloong.client.sky;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL14;

/**
 * 太阳与月亮本体绘制器。
 *
 * <p>使用资源包提供的 sun.png / moon_phases.png，
 * 旋转方式参考 NeoForgeSkyboxes 默认 Decorations.rotation。</p>
 *
 * <p><b>几何缓存：</b>太阳的 UV 恒定，缓存 1 个 {@link VertexBuffer}；
 * 月亮的 UV 随 {@code level.getMoonPhase()} 变化（{@code moon_phases.png} 为 4×2 图集，
 * 共 8 种相位），故按相位惰性缓存 8 个。缓冲存的都是<strong>局部坐标</strong>，
 * 旋转与朝向由 {@code drawWithShader} 的矩阵参数每帧施加。</p>
 */
public final class SkyDecorationsRenderer {

    private static final ResourceLocation SUN_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            BeLoongCore.MODID, "textures/environment/sun.png");
    private static final ResourceLocation MOON_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            BeLoongCore.MODID, "textures/environment/moon_phases.png");

    /** 太阳几何，懒建。 */
    private static VertexBuffer sunBuffer;
    /** 月亮几何，按相位惰性缓存（{@code getMoonPhase()} 取值 0..7）。 */
    private static final VertexBuffer[] MOON_BUFFERS = new VertexBuffer[8];

    private SkyDecorationsRenderer() {
    }

    public static void render(ClientLevel level,
                              Matrix4f modelViewMatrix,
                              Matrix4f projectionMatrix) {
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
        RenderSystem.blendEquation(GL14.GL_FUNC_ADD);

        poseStack.pushPose();
        SkyRotation.DECORATION_ROTATION.apply(poseStack, level);

        drawSun(poseStack, projectionMatrix);
        drawMoon(poseStack, level, projectionMatrix);

        poseStack.popPose();

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
        RenderSystem.defaultBlendFunc();
    }

    private static void ensureSunBuffer() {
        if (sunBuffer != null && !sunBuffer.isInvalid()) {
            return;
        }

        sunBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        sunBuffer.bind();

        // 局部坐标：此处不可传入 pose 矩阵
        BufferBuilder bufferBuilder = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bufferBuilder.addVertex(-30.0F, 100.0F, -30.0F).setUv(0.0F, 0.0F);
        bufferBuilder.addVertex(30.0F, 100.0F, -30.0F).setUv(1.0F, 0.0F);
        bufferBuilder.addVertex(30.0F, 100.0F, 30.0F).setUv(1.0F, 1.0F);
        bufferBuilder.addVertex(-30.0F, 100.0F, 30.0F).setUv(0.0F, 1.0F);

        // upload 内部会关闭 MeshData，无需也不应额外 close
        sunBuffer.upload(bufferBuilder.buildOrThrow());
        VertexBuffer.unbind();
    }

    private static void ensureMoonBuffer(int phase) {
        VertexBuffer buffer = MOON_BUFFERS[phase];
        if (buffer != null && !buffer.isInvalid()) {
            return;
        }

        int xCoord = phase % 4;
        int yCoord = phase / 4 % 2;
        float startX = xCoord / 4.0F;
        float startY = yCoord / 2.0F;
        float endX = (xCoord + 1) / 4.0F;
        float endY = (yCoord + 1) / 2.0F;

        buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();

        // 局部坐标：此处不可传入 pose 矩阵。
        // 顶点顺序与 UV 必须与既有实现逐字一致，否则月相会镜像/翻转。
        BufferBuilder bufferBuilder = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bufferBuilder.addVertex(-20.0F, -100.0F, 20.0F).setUv(endX, endY);
        bufferBuilder.addVertex(20.0F, -100.0F, 20.0F).setUv(startX, endY);
        bufferBuilder.addVertex(20.0F, -100.0F, -20.0F).setUv(startX, startY);
        bufferBuilder.addVertex(-20.0F, -100.0F, -20.0F).setUv(endX, startY);

        buffer.upload(bufferBuilder.buildOrThrow());
        VertexBuffer.unbind();

        MOON_BUFFERS[phase] = buffer;
    }

    private static void drawSun(PoseStack poseStack, Matrix4f projectionMatrix) {
        RenderSystem.setShaderTexture(0, SUN_TEXTURE);
        ensureSunBuffer();

        sunBuffer.bind();
        sunBuffer.drawWithShader(poseStack.last().pose(), projectionMatrix, RenderSystem.getShader());
        VertexBuffer.unbind();
    }

    private static void drawMoon(PoseStack poseStack, ClientLevel level, Matrix4f projectionMatrix) {
        RenderSystem.setShaderTexture(0, MOON_TEXTURE);

        // & 7 既是防御也是数组下标所必需：getMoonPhase() 约定返回 0..7
        int phase = level.getMoonPhase() & 7;
        ensureMoonBuffer(phase);

        MOON_BUFFERS[phase].bind();
        MOON_BUFFERS[phase].drawWithShader(
                poseStack.last().pose(), projectionMatrix, RenderSystem.getShader());
        VertexBuffer.unbind();
    }
}
