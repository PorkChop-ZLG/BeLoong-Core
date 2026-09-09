package com.zonlong.beloong.client.sky;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * 简单的六面天空盒渲染器。
 *
 * <p>参考原版 {@code LevelRenderer#renderEndSky()} 的立方体绘制方式，
 * 在相机周围用六个 quad 分别绘制上/下/北/南/东/西六张贴图。</p>
 */
public final class CubeSkyRenderer {

    private static final float SIZE = 100.0F;

    private CubeSkyRenderer() {
    }

    /**
     * 绘制六面天空盒。
     *
     * @param modelViewMatrix 当前模型视图矩阵
     * @param projectionMatrix 投影矩阵
     * @param up 顶面贴图
     * @param down 底面贴图
     * @param north 北面贴图
     * @param south 南面贴图
     * @param east 东面贴图
     * @param west 西面贴图
     */
    public static void render(Matrix4f modelViewMatrix,
                              Matrix4f projectionMatrix,
                              ResourceLocation up,
                              ResourceLocation down,
                              ResourceLocation north,
                              ResourceLocation south,
                              ResourceLocation east,
                              ResourceLocation west) {
        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(modelViewMatrix);

        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);

        // 顺序与原版 renderEndSky 的六次绘制一致：
        // 0 = 底面, 1 = 北面, 2 = 南面, 3 = 顶面, 4 = 东面, 5 = 西面
        ResourceLocation[] faces = {down, north, south, up, east, west};

        // 每个面四个顶点的 UV。
        // 顶点顺序固定为：
        //   A(-S,-S,-S), B(-S,-S,S), C(S,-S,S), D(S,-S,-S)
        // 由于 skybox 是有方向的，南/东/西三个面需要旋转 UV 才能与相邻面正确衔接。
        float[][][] uvs = {
                // down
                {{0.0F, 0.0F}, {0.0F, 1.0F}, {1.0F, 1.0F}, {1.0F, 0.0F}},
                // north
                {{0.0F, 0.0F}, {0.0F, 1.0F}, {1.0F, 1.0F}, {1.0F, 0.0F}},
                // south
                {{1.0F, 1.0F}, {1.0F, 0.0F}, {0.0F, 0.0F}, {0.0F, 1.0F}},
                // up
                {{0.0F, 0.0F}, {0.0F, 1.0F}, {1.0F, 1.0F}, {1.0F, 0.0F}},
                // east
                {{0.0F, 1.0F}, {1.0F, 1.0F}, {1.0F, 0.0F}, {0.0F, 0.0F}},
                // west
                {{1.0F, 0.0F}, {0.0F, 0.0F}, {0.0F, 1.0F}, {1.0F, 1.0F}}
        };

        float[] vertices = {
                -SIZE, -SIZE, -SIZE,
                -SIZE, -SIZE, SIZE,
                SIZE, -SIZE, SIZE,
                SIZE, -SIZE, -SIZE
        };

        for (int i = 0; i < 6; i++) {
            poseStack.pushPose();
            if (i == 1) {
                poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            }
            if (i == 2) {
                poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
            }
            if (i == 3) {
                poseStack.mulPose(Axis.XP.rotationDegrees(180.0F));
            }
            if (i == 4) {
                poseStack.mulPose(Axis.ZP.rotationDegrees(90.0F));
            }
            if (i == 5) {
                poseStack.mulPose(Axis.ZP.rotationDegrees(-90.0F));
            }

            RenderSystem.setShaderTexture(0, faces[i]);

            Matrix4f matrix4f = poseStack.last().pose();
            BufferBuilder bufferBuilder = Tesselator.getInstance()
                    .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);

            for (int v = 0; v < 4; v++) {
                float x = vertices[v * 3];
                float y = vertices[v * 3 + 1];
                float z = vertices[v * 3 + 2];
                bufferBuilder.addVertex(matrix4f, x, y, z)
                        .setUv(uvs[i][v][0], uvs[i][v][1]);
            }

            BufferUploader.drawWithShader(bufferBuilder.buildOrThrow());
            poseStack.popPose();
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
}
