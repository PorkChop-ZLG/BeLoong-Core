package com.zonlong.beloong.client.sky;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
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
 *
 * <p><b>几何缓存：</b>顶点只依赖本类的静态 {@link #FACES} 表与其中的字面量 UV，
 * 与贴图、旋转、混合、维度、时间全部无关，因此 {@link DramaticSkyRenderer}
 * 的 9 个图层<strong>共用同一个</strong> {@link VertexBuffer}。缓冲只在首次渲染时
 * 构建一次，之后每帧仅绑定、并按 {@code drawWithShader} 传入当前矩阵——
 * 旋转由 uniform 施加，不烘进顶点。</p>
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

    /** 缓存的天空盒几何；9 个图层共用，懒建于首次渲染。 */
    private static VertexBuffer skyBuffer;

    private CubeAtlasSkyRenderer() {
    }

    /**
     * 确保天空盒几何已上传到 GPU。
     *
     * <p><b>只能在渲染线程调用。</b>顶点必须用<strong>不带矩阵</strong>的
     * {@code addVertex(x, y, z)} 写入——存的是局部坐标，旋转在绘制时由
     * {@code drawWithShader} 的矩阵参数施加。若在此处烘入矩阵，
     * 旋转会被冻结在首次上传时的角度。</p>
     */
    private static void ensureBuffer() {
        if (skyBuffer != null && !skyBuffer.isInvalid()) {
            return;
        }

        skyBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        skyBuffer.bind();

        BufferBuilder bufferBuilder = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_TEX
        );

        for (float[] face : FACES) {
            for (int i = 0; i < 4; i++) {
                int offset = i * 5;
                // 局部坐标：此处不可传入 pose 矩阵
                bufferBuilder.addVertex(face[offset], face[offset + 1], face[offset + 2])
                        .setUv(face[offset + 3], face[offset + 4]);
            }
        }

        // upload 内部会关闭 MeshData，无需也不应额外 close
        skyBuffer.upload(bufferBuilder.buildOrThrow());
        VertexBuffer.unbind();
    }

    /**
     * 使用当前 RenderSystem 状态绘制六面天空盒。
     *
     * <p>调用前应已完成：设置 shader、启用混合、关闭面剔除、关闭深度写入。
     * 本方法绑定纹理，并把缓存的几何按当前矩阵提交。</p>
     *
     * @param poseStack        当前模型视图矩阵；旋转由其提供
     * @param projectionMatrix 投影矩阵，经 {@code drawWithShader} 传给 shader
     * @param texture          3×2 六面图集
     */
    public static void render(PoseStack poseStack,
                              Matrix4f projectionMatrix,
                              ResourceLocation texture) {
        RenderSystem.setShaderTexture(0, texture);
        ensureBuffer();

        skyBuffer.bind();
        skyBuffer.drawWithShader(poseStack.last().pose(), projectionMatrix, RenderSystem.getShader());
        VertexBuffer.unbind();
    }
}
