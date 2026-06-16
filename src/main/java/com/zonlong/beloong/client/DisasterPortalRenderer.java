package com.zonlong.beloong.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zonlong.beloong.block.DisasterPortalBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.zonlong.beloong.BeLoongCoreClient;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Supplier;

/**
 * 天灾传送门方块的自定义 BlockEntity 渲染器。
 * <p>
 * 该渲染器是原版 {@code TheEndPortalRenderer} 的完全重新实现，
 * 因为原版渲染器在 {@code render()} 方法内部将 BlockEntity 硬转型为
 * {@code TheEndPortalBlockEntity}，导致普通的自定义 BlockEntity 无法直接使用。
 * <p>
 * <b>渲染效果：</b>
 * 使用原版末地传送门的 {@link RenderType#endPortal()} 着色器，
 * 产生旋转的星空隧道视觉效果。
 * <p>
 * <b>渲染逻辑：</b>
 * <ol>
 *   <li>获取当前帧的变换矩阵（{@link PoseStack}）</li>
 *   <li>调用 {@link #renderCube} 渲染 6 个面的立方体</li>
 *   <li>每个面通过 {@link DisasterPortalBlockEntity#shouldRenderFace} 判断是否需要渲染</li>
 *   <li>使用 {@link #renderType()} 返回的 {@code endPortal} 着色器类型</li>
 * </ol>
 * <p>
 * <b>和原版的差异：</b>
 * 原版 {@code TheEndPortalRenderer} 仅渲染 Y 轴方向的面（上/下），
 * 即只给上/下面应用传送门着色器。其余四个侧面不渲染——这使得从侧面看传送门是隐形的。
 * 本实现通过 {@link DisasterPortalBlockEntity#shouldRenderFace} 保持相同行为。
 * <p>
 * 渲染器在 {@link com.zonlong.beloong.BeLoongCoreClient#registerRenderers} 中注册。
 *
 * @see DisasterPortalBlockEntity
 * @see com.zonlong.beloong.block.DisasterPortalBlock
 */
@OnlyIn(Dist.CLIENT)
public class DisasterPortalRenderer implements BlockEntityRenderer<DisasterPortalBlockEntity> {

    /** 自定义贴图路径：Sampler0 和 Sampler1 共用同一贴图。 */
    private static final ResourceLocation DISASTER_PORTAL_LOCATION =
            ResourceLocation.fromNamespaceAndPath("beloong", "textures/entity/disaster_portal");

    /**
     * 自定义 {@link RenderType}，照搬原版 {@code RenderType.endPortal()} 的结构：
     * <ul>
     *   <li>Shader: 自定义注册的 {@code rendertype_disaster_portal} 着色器程序</li>
     *   <li>Texture: 自定义贴图（Sampler0 + Sampler1 指向同一张图）</li>
     *   <li>其余渲染状态与原版 END_PORTAL 完全一致</li>
     * </ul>
     * <p>
     * 使用 lazy 初始化：ShaderInstance 由 {@code RegisterShadersEvent} 回调设置，
     * 发生在渲染首帧之前，因此首次渲染时 shader 已就绪。
     */
    private static final Supplier<RenderType> DISASTER_PORTAL = () -> RenderType.create(
            "disaster_portal",
            DefaultVertexFormat.POSITION,
            VertexFormat.Mode.QUADS,
            1536,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(
                            () -> BeLoongCoreClient.disasterPortalShader))
                    .setTextureState(
                            RenderStateShard.MultiTextureStateShard.builder()
                                    .add(DISASTER_PORTAL_LOCATION, false, false)
                                    .add(DISASTER_PORTAL_LOCATION, false, false)
                                    .build())
                    .createCompositeState(false)
    );

    /** 缓存的 RenderType 实例，避免每次调用 renderType() 都重新创建。 */
    private static RenderType disasterPortalRenderType;

    public DisasterPortalRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    /**
     * 每帧调用，渲染传送门的星空效果。
     *
     * @param blockEntity   传送门 BlockEntity（用于查询 shouldRenderFace）
     * @param partialTick   当前帧的部分 tick 插值（用于平滑动画）
     * @param poseStack     变换矩阵栈（用于旋转、缩放、位移）
     * @param bufferSource  渲染缓冲区源（用于获取 VertexConsumer）
     * @param packedLight   打包的亮度信息（天空光 + 方块光）
     * @param packedOverlay 打包的叠加层信息（破坏动画等）
     */
    @Override
    public void render(DisasterPortalBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        // 获取当前帧的模型视图投影矩阵
        Matrix4f matrix4f = poseStack.last().pose();
        // 获取 endPortal 着色器对应的顶点消费者，开始绘制
        this.renderCube(blockEntity, matrix4f, bufferSource.getBuffer(this.renderType()));
    }

    /**
     * 渲染传送门效果的 6 面立方体。
     * <p>
     * 每个面使用两组坐标为四边形的四个顶点，通过 {@link VertexConsumer} 写入 GPU。
     * 实际是否渲染取决于 {@link DisasterPortalBlockEntity#shouldRenderFace} 的结果。
     * <p>
     * <b>凹凸参数（上下面的 Y 偏移）：</b>
     * 上表面向内收进 0.75 格，下表面提高 0.375 格，
     * 使传送门效果在方块内部形成一个略微收窄的立方体视觉效果。
     */
    private void renderCube(DisasterPortalBlockEntity blockEntity, Matrix4f pose, VertexConsumer consumer) {
        float down = getOffsetDown();  // 0.375F — 下表面距方块底部的距离
        float up = getOffsetUp();      // 0.75F  — 上表面距方块顶部的距离
        this.renderFace(blockEntity, pose, consumer, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, Direction.SOUTH);
        this.renderFace(blockEntity, pose, consumer, 0.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, Direction.NORTH);
        this.renderFace(blockEntity, pose, consumer, 1.0F, 1.0F, 1.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F, Direction.EAST);
        this.renderFace(blockEntity, pose, consumer, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 0.0F, Direction.WEST);
        this.renderFace(blockEntity, pose, consumer, 0.0F, 1.0F, down, down, 0.0F, 0.0F, 1.0F, 1.0F, Direction.DOWN);
        this.renderFace(blockEntity, pose, consumer, 0.0F, 1.0F, up, up, 1.0F, 1.0F, 0.0F, 0.0F, Direction.UP);
    }

    /**
     * 渲染立方体的一个面（四边形）。
     * <p>
     * 参数中的 8 个浮点数定义了四边形在三维空间中的 4 个顶点位置。
     * 参数顺序为顶点 0 (x0, y0, z0) → 顶点 1 (x1, y0, z1)
     * → 顶点 2 (x1, y1, z2) → 顶点 3 (x0, y1, z3)。
     */
    private void renderFace(DisasterPortalBlockEntity blockEntity, Matrix4f pose, VertexConsumer consumer,
                            float x0, float x1, float y0, float y1,
                            float z0, float z1, float z2, float z3,
                            Direction direction) {
        if (blockEntity.shouldRenderFace(direction)) {
            consumer.addVertex(pose, x0, y0, z0);  // 左下角
            consumer.addVertex(pose, x1, y0, z1);  // 右下角
            consumer.addVertex(pose, x1, y1, z2);  // 右上角
            consumer.addVertex(pose, x0, y1, z3);  // 左上角
        }
    }

    /** 上表面的 Y 偏移因子。值越大，上表面越靠近方块中心（越收窄）。 */
    protected float getOffsetUp() {
        return 0.75F;
    }

    /** 下表面的 Y 偏移因子。值越大，下表面越远离方块底部（越收窄）。 */
    protected float getOffsetDown() {
        return 0.375F;
    }

    /**
     * 获取渲染类型。
     * <p>
     * 使用自定义 {@link RenderType}，绑定天灾传送门专用着色器
     * 和自定义贴图（{@code textures/entity/disaster_portal.png}），
     * 产生旋转隧道视觉效果。
     * <p>
     * 首次调用时通过 {@link #DISASTER_PORTAL} lambda 创建 RenderType 实例并缓存，
     * 后续调用直接返回缓存实例。
     */
    protected RenderType renderType() {
        if (disasterPortalRenderType == null) {
            disasterPortalRenderType = DISASTER_PORTAL.get();
        }
        return disasterPortalRenderType;
    }
}
