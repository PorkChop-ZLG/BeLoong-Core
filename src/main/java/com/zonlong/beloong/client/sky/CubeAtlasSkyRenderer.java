package com.zonlong.beloong.client.sky;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * 使用一张 3×2 六面图集绘制天空盒的渲染器。
 *
 * <p>顶点与 UV 直接采用 Dramatic Skys / Celestial JSON 中的 24 顶点表，
 * 避免将图集切成独立贴图后再旋转/翻转导致的接缝问题。</p>
 *
 * <p>调用方负责设置 shader、混合、裁剪、深度遮罩等 RenderSystem 状态；
 * 本类只负责把 6 个面合并为一次 buffer 提交。</p>
 */
public final class CubeAtlasSkyRenderer {

    private static final float SIZE = 100.0F;

    /**
     * 每个面 4 个顶点：{x, y, z, u, v}。
     * 顺序：West, South, East, North, Top, Bottom。
     */
    private static final float[][] FACES = {
            // West
            {
                    -SIZE, SIZE, -SIZE, 0.3333333333F, 0.5F,
                    -SIZE, SIZE, SIZE, 0.0F, 0.5F,
                    -SIZE, -SIZE, SIZE, 0.0F, 1.0F,
                    -SIZE, -SIZE, -SIZE, 0.3333333333F, 1.0F
            },
            // South
            {
                    -SIZE, SIZE, SIZE, 1.0F, 0.0F,
                    SIZE, SIZE, SIZE, 0.6666666666F, 0.0F,
                    SIZE, -SIZE, SIZE, 0.6666666666F, 0.5F,
                    -SIZE, -SIZE, SIZE, 1.0F, 0.5F
            },
            // East
            {
                    SIZE, SIZE, SIZE, 1.0F, 0.5F,
                    SIZE, SIZE, -SIZE, 0.6666666666F, 0.5F,
                    SIZE, -SIZE, -SIZE, 0.6666666666F, 1.0F,
                    SIZE, -SIZE, SIZE, 1.0F, 1.0F
            },
            // North
            {
                    SIZE, SIZE, -SIZE, 0.6666666666F, 0.5F,
                    -SIZE, SIZE, -SIZE, 0.3333333333F, 0.5F,
                    -SIZE, -SIZE, -SIZE, 0.3333333333F, 1.0F,
                    SIZE, -SIZE, -SIZE, 0.6666666666F, 1.0F
            },
            // Top
            {
                    SIZE, SIZE, SIZE, 0.6666666666F, 0.0F,
                    -SIZE, SIZE, SIZE, 0.3333333333F, 0.0F,
                    -SIZE, SIZE, -SIZE, 0.3333333333F, 0.5F,
                    SIZE, SIZE, -SIZE, 0.6666666666F, 0.5F
            },
            // Bottom
            {
                    SIZE, -SIZE, -SIZE, 0.3333333333F, 0.0F,
                    -SIZE, -SIZE, -SIZE, 0.0F, 0.0F,
                    -SIZE, -SIZE, SIZE, 0.0F, 0.5F,
                    SIZE, -SIZE, SIZE, 0.3333333333F, 0.5F
            }
    };

    private CubeAtlasSkyRenderer() {
    }

    /**
     * 使用当前 RenderSystem 状态绘制六面天空盒。
     *
     * <p>调用前应已完成：设置 shader、启用混合、关闭面剔除、关闭深度写入。
     * 本方法只绑定纹理并一次性提交全部 6 个面。</p>
     *
     * @param poseStack      当前模型视图矩阵
     * @param projectionMatrix 投影矩阵（保留参数，当前提交方式不直接使用）
     * @param texture        3×2 六面图集
     */
    public static void render(PoseStack poseStack,
                              Matrix4f projectionMatrix,
                              ResourceLocation texture) {
        RenderSystem.setShaderTexture(0, texture);

        Matrix4f matrix4f = poseStack.last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.begin(
                VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_TEX
        );

        for (float[] face : FACES) {
            for (int i = 0; i < 4; i++) {
                int offset = i * 5;
                bufferBuilder.addVertex(matrix4f,
                                face[offset],
                                face[offset + 1],
                                face[offset + 2])
                        .setUv(face[offset + 3], face[offset + 4]);
            }
        }

        BufferUploader.drawWithShader(bufferBuilder.buildOrThrow());
    }
}
