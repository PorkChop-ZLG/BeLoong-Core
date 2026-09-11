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
     * 天空盒图集的像素尺寸与格子布局。
     *
     * <p><b>这两个常量与贴图尺寸强耦合</b>：{@link #FACES} 的半纹素内缩量由它们算出
     * （见 {@code static} 块）。**改变任何一张天空盒贴图的尺寸，必须同步改这里**，
     * 否则内缩量会算错、面边缘会重新出现跨格渗色的暗缝。</p>
     *
     * <p>当前 7 张天空盒图集（{@code day} / {@code night} / {@code stars} / {@code mask} /
     * {@code mask_moon} / {@code sun} / {@code sunflare}）统一为 1536×1024、3 列 × 2 行，
     * 每格 512×512。</p>
     */
    private static final int ATLAS_WIDTH = 1536;
    private static final int ATLAS_HEIGHT = 1024;

    /**
     * 半纹素内缩量（归一化 UV）。
     *
     * <p><b>为什么必须内缩：</b>本表每个面的 UV 都精确落在图集的格子边界上
     * （u 为 {@code 0 / 1/3 / 2/3 / 1}，v 为 {@code 0 / 1/2 / 1}）。在
     * {@code GL_LINEAR} 下，纹理坐标 {@code u = 1/3} 对应纹素坐标
     * {@code 1536/3 - 0.5 = 511.5}，于是双线性取样会取「左邻格最后一个纹素」与
     * 「本格第一个纹素」<b>各一半</b>——而相邻格子是天空的另一面，颜色完全不同
     * （实测 {@code mask.png} 跨 v=1/2 两侧为 188.8 与 28.9，相差 6.5 倍）。
     * 结果就是每条立方体棱上出现一条半纹素宽的暗缝；因为 {@code mask.png} 是
     * 「alpha 越大越暗」的遮罩，这条缝表现为明显的黑线。</p>
     *
     * <p>把 UV 四边各内缩半个纹素后，取样点正好落在本格第一个/最后一个纹素的
     * <b>中心</b>（{@code 511.5 + 0.5 = 512.0}，权重 0），不再与邻格混合。
     * 代价是每个面的内容缩小 {@code 1/512 ≈ 0.2%}，肉眼不可见。</p>
     */
    private static final float INSET_U = 0.5F / ATLAS_WIDTH;
    private static final float INSET_V = 0.5F / ATLAS_HEIGHT;

    /**
     * 每个面 4 个顶点：{x, y, z, u, v}。
     * 顺序：West, South, East, North, Top, Bottom。
     *
     * <p>表中的 UV 是<b>未内缩</b>的格子边界值；下面的 {@code static} 块会在类初始化时
     * 按每个面自己占用的格子区间自动内缩。这样写是为了让「哪条边是区间下界、哪条是上界」
     * 由数据自身推出，避免手工改 48 个字面量出错。</p>
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

    static {
        // 把每个面的 UV 四边各内缩半个纹素：从「格子边界」挪到「本格边缘纹素的中心」，
        // 使 GL_LINEAR 的取样权重落在单一纹素上，消除跨格渗色造成的暗缝。
        // 每个面占用的格子区间由它自己的 4 个 UV 极值推出，无需知道面名或格子索引。
        for (float[] face : FACES) {
            float uMin = Float.MAX_VALUE;
            float uMax = -Float.MAX_VALUE;
            float vMin = Float.MAX_VALUE;
            float vMax = -Float.MAX_VALUE;
            for (int v = 0; v < 4; v++) {
                float u = face[v * 5 + 3];
                float w = face[v * 5 + 4];
                uMin = Math.min(uMin, u);
                uMax = Math.max(uMax, u);
                vMin = Math.min(vMin, w);
                vMax = Math.max(vMax, w);
            }
            for (int v = 0; v < 4; v++) {
                float u = face[v * 5 + 3];
                float w = face[v * 5 + 4];
                face[v * 5 + 3] = (u == uMin) ? uMin + INSET_U : uMax - INSET_U;
                face[v * 5 + 4] = (w == vMin) ? vMin + INSET_V : vMax - INSET_V;
            }
        }
    }

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
