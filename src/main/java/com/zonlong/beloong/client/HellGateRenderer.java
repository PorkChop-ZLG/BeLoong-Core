package com.zonlong.beloong.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.block.HellGateBlock;
import com.zonlong.beloong.block.HellGateBlockEntity;
import com.zonlong.beloong.client.model.HellGateModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 地狱之门的渲染器 —— 灾变 {@code Door_Of_Seal_Renderer} 的移植件。
 *
 * <p><b>关键点：整扇门只在基准格渲染一次。</b>只有 {@code PART == CENTER && Y_OFFSET == 0} 那一格
 * 会画模型，而模型本身就含 5×8 格的几何（见 {@link HellGateModel}）；其余 39 格只是状态与碰撞载体，
 * 方块自身不产生模型（{@code RenderShape.ENTITYBLOCK_ANIMATED}）。
 *
 * <p>可视距离 {@link #getViewDistance()} = 256 且 {@link #shouldRenderOffScreen()} = true
 * ⇒ 不做视锥剔除，远处也常驻渲染（8 格高的门在远处被剔掉会很突兀）。
 *
 * <p>贴图已换成本模组自己的 {@code beloong:textures/block/hell_gate.png}（与灾变那张逐字节相同）。
 *
 * @see HellGateModel
 */
public class HellGateRenderer implements BlockEntityRenderer<HellGateBlockEntity> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "textures/block/hell_gate.png");

    private static final HellGateModel MODEL = new HellGateModel();

    public HellGateRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public boolean shouldRenderOffScreen(HellGateBlockEntity entity) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }

    @Override
    public boolean shouldRender(HellGateBlockEntity entity, Vec3 cameraPos) {
        return Vec3.atCenterOf(entity.getBlockPos()).multiply(1.0D, 0.0D, 1.0D)
                .closerThan(cameraPos.multiply(1.0D, 0.0D, 1.0D), (double) this.getViewDistance());
    }

    @Override
    public AABB getRenderBoundingBox(HellGateBlockEntity entity) {
        BlockPos pos = entity.getBlockPos();
        return new AABB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 3.0, pos.getY() + HellGateBlock.GATE_HEIGHT, pos.getZ() + 3.0);
    }

    @Override
    public void render(HellGateBlockEntity entity, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        HellGateBlock.HellGatePart part = entity.getBlockState().getValue(HellGateBlock.PART);
        int yOffset = entity.getBlockState().getValue(HellGateBlock.Y_OFFSET);
        if (part != HellGateBlock.HellGatePart.CENTER || yOffset != 0) {
            return;
        }

        Direction dir = entity.getBlockState().getValue(HellGateBlock.FACING);
        poseStack.pushPose();
        // 原版此处按 FACING 分了四个分支，但四支的位移完全相同 —— 收敛成一句；朝向差异由下一行的旋转承担。
        poseStack.translate(0.5D, 1.501F, 0.5D);
        poseStack.mulPose(dir.getOpposite().getRotation());
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
        MODEL.animate(entity, partialTick);
        MODEL.renderToBuffer(poseStack, buffer.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)),
                packedLight, packedOverlay);
        poseStack.popPose();
    }
}
