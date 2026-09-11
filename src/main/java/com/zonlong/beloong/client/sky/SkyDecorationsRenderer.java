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
 * <p>使用资源包提供的 sun.png，以及 <strong>8 张独立的月相贴图</strong>
 * {@code moon_phases_0.png} … {@code moon_phases_7.png}（下标 = {@code level.getMoonPhase()}）。
 * 旋转方式参考 NeoForgeSkyboxes 默认 Decorations.rotation。</p>
 *
 * <p>月相贴图派生自原始 4×2 图集（4000×2000）：每格<strong>居中裁 560×560</strong> 后缩放到
 * <strong>320×320</strong>（8-bit RGB），即裁掉 76.9% 的纯黑边距并降采样。
 * 裁剪只缩小画布范围，故 quad 由 40 同步缩为 22.4 世界单位，月亮在屏幕上的角尺寸不变。
 * 裁剪框必须**居中且 8 张一致**：每格除亮部外还有一个固定尺寸的暗盘（earthshine），
 * 它才是相位间位置一致性与新月可见性的来源。</p>
 *
 * <p><b>几何缓存：</b>8 张月相各自覆盖 UV {@code (0,0)-(1,1)}，几何完全相同，
 * 因此太阳与月亮<strong>各只需 1 个</strong> {@link VertexBuffer}。缓冲存的都是
 * <strong>局部坐标</strong>，旋转与朝向由 {@code drawWithShader} 的矩阵参数每帧施加。</p>
 */
public final class SkyDecorationsRenderer {

    private static final ResourceLocation SUN_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            BeLoongCore.MODID, "textures/environment/sun.png");

    /** 8 张独立月相贴图；下标即 {@code level.getMoonPhase()} 的取值（0..7）。 */
    private static final ResourceLocation[] MOON_PHASES = new ResourceLocation[8];

    static {
        for (int i = 0; i < MOON_PHASES.length; i++) {
            MOON_PHASES[i] = ResourceLocation.fromNamespaceAndPath(
                    BeLoongCore.MODID, "textures/environment/moon_phases_" + i + ".png");
        }
    }

    /**
     * 月亮 quad 的半边长（世界单位）。
     *
     * <p>原始图集每格 1000 texel 映射 40 世界单位；裁到 560 后画布只覆盖原格的 56%，
     * 故 quad 同步缩为 {@code 20 × 0.56 = 11.2}，使月亮的角尺寸保持不变。</p>
     */
    private static final float MOON_HALF_SIZE = 11.2F;

    /** 太阳几何，懒建。 */
    private static VertexBuffer sunBuffer;
    /** 月亮几何，懒建；8 张相位共用一个缓冲（几何相同）。 */
    private static VertexBuffer moonBuffer;

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

    private static void ensureMoonBuffer() {
        if (moonBuffer != null && !moonBuffer.isInvalid()) {
            return;
        }

        moonBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        moonBuffer.bind();

        // 局部坐标：此处不可传入 pose 矩阵。
        // 顶点顺序与图集版本一致；UV 由原「格子区间」换算为整张贴图的 0..1。
        BufferBuilder bufferBuilder = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bufferBuilder.addVertex(-MOON_HALF_SIZE, -100.0F, MOON_HALF_SIZE).setUv(1.0F, 1.0F);
        bufferBuilder.addVertex(MOON_HALF_SIZE, -100.0F, MOON_HALF_SIZE).setUv(0.0F, 1.0F);
        bufferBuilder.addVertex(MOON_HALF_SIZE, -100.0F, -MOON_HALF_SIZE).setUv(0.0F, 0.0F);
        bufferBuilder.addVertex(-MOON_HALF_SIZE, -100.0F, -MOON_HALF_SIZE).setUv(1.0F, 0.0F);

        moonBuffer.upload(bufferBuilder.buildOrThrow());
        VertexBuffer.unbind();
    }

    private static void drawSun(PoseStack poseStack, Matrix4f projectionMatrix) {
        RenderSystem.setShaderTexture(0, SUN_TEXTURE);
        ensureSunBuffer();

        sunBuffer.bind();
        sunBuffer.drawWithShader(poseStack.last().pose(), projectionMatrix, RenderSystem.getShader());
        VertexBuffer.unbind();
    }

    private static void drawMoon(PoseStack poseStack, ClientLevel level, Matrix4f projectionMatrix) {
        // & 7 既是防御也是数组下标所必需：getMoonPhase() 约定返回 0..7
        int phase = level.getMoonPhase() & 7;
        RenderSystem.setShaderTexture(0, MOON_PHASES[phase]);

        ensureMoonBuffer();
        moonBuffer.bind();
        moonBuffer.drawWithShader(poseStack.last().pose(), projectionMatrix, RenderSystem.getShader());
        VertexBuffer.unbind();
    }
}
