package com.zonlong.beloong.block;

import com.zonlong.beloong.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 天灾传送门方块的 BlockEntity。
 * <p>
 * 该 BlockEntity 本身不持久化任何数据（传送门方块不需要存储状态），
 * 其存在目的是：
 * <ul>
 *   <li>作为 {@link com.zonlong.beloong.client.DisasterPortalRenderer} 的渲染载体。</li>
 *   <li>提供 {@link #shouldRenderFace(Direction)} 方法，
 *       控制传送门着色器在哪些方向面上绘制。
 *       和原版 {@code TheEndPortalBlockEntity} 一致，仅渲染 Y 轴方向（上/下面），
 *       即玩家从上方或下方看时能看到传送门效果。</li>
 * </ul>
 * <p>
 * 该 BlockEntity 通过 {@link ModBlocks#DISASTER_PORTAL_BLOCK_ENTITY} 注册，
 * 并在 {@link com.zonlong.beloong.BeLoongCoreClient} 中绑定自定义渲染器。
 *
 * @see DisasterPortalBlock
 * @see com.zonlong.beloong.client.DisasterPortalRenderer
 */
public class DisasterPortalBlockEntity extends BlockEntity {

    public DisasterPortalBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.DISASTER_PORTAL_BLOCK_ENTITY.get(), pos, state);
    }

    /**
     * 判断传送门着色器是否应该在指定方向的面渲染。
     * <p>
     * 和原版 {@code TheEndPortalBlockEntity#shouldRenderFace} 逻辑完全一致：
     * 仅对 Y 轴方向返回 {@code true}。
     * 这意味着传送门的星空效果只在顶部和底部可见，
     * 侧面不会渲染传送门纹理（从侧面看传送门是透明的）。
     *
     * @param direction 要检查的方块面方向
     * @return 仅当方向轴为 Y（上/下）时返回 true
     */
    public boolean shouldRenderFace(Direction direction) {
        return direction.getAxis() == Direction.Axis.Y;
    }
}
