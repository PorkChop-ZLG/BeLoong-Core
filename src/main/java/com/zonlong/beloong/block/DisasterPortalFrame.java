package com.zonlong.beloong.block;

import com.google.common.base.Predicates;
import com.zonlong.beloong.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.level.block.state.pattern.BlockPattern;
import net.minecraft.world.level.block.state.pattern.BlockPatternBuilder;
import net.minecraft.world.level.block.state.predicate.BlockStatePredicate;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

public class DisasterPortalFrame extends Block implements EntityBlock {
    public static final BooleanProperty HAS_EYE = BlockStateProperties.EYE;
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<EyeType> EYE_TYPE =
            EnumProperty.create("eye_type", EyeType.class);

    protected static final VoxelShape BASE_SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 13.0D, 16.0D);
    protected static final VoxelShape EYE_SHAPE = Block.box(4.0D, 13.0D, 4.0D, 12.0D, 16.0D, 12.0D);
    protected static final VoxelShape FULL_SHAPE = Shapes.or(BASE_SHAPE, EYE_SHAPE);

    public static BlockPattern getCompletedPortalShape(boolean requireEyes) {
        Predicate<Object> hasEyePredicate = (eyeType) -> requireEyes ? eyeType != EyeType.EMPTY : true;
        return BlockPatternBuilder.start()
                .aisle("?vvv?", ">???<", ">???<", ">???<", "?^^^?")
                .where('?', BlockInWorld.hasState(BlockStatePredicate.ANY))
                .where('^', BlockInWorld.hasState(
                        BlockStatePredicate.forBlock(ModBlocks.DISASTER_PORTAL_FRAME.get())
                                .where(HAS_EYE, Predicates.alwaysTrue())
                                .where(EYE_TYPE, hasEyePredicate)
                                .where(FACING, Predicates.equalTo(Direction.SOUTH))))
                .where('>', BlockInWorld.hasState(
                        BlockStatePredicate.forBlock(ModBlocks.DISASTER_PORTAL_FRAME.get())
                                .where(HAS_EYE, Predicates.alwaysTrue())
                                .where(EYE_TYPE, hasEyePredicate)
                                .where(FACING, Predicates.equalTo(Direction.WEST))))
                .where('v', BlockInWorld.hasState(
                        BlockStatePredicate.forBlock(ModBlocks.DISASTER_PORTAL_FRAME.get())
                                .where(HAS_EYE, Predicates.alwaysTrue())
                                .where(EYE_TYPE, hasEyePredicate)
                                .where(FACING, Predicates.equalTo(Direction.NORTH))))
                .where('<', BlockInWorld.hasState(
                        BlockStatePredicate.forBlock(ModBlocks.DISASTER_PORTAL_FRAME.get())
                                .where(HAS_EYE, Predicates.alwaysTrue())
                                .where(EYE_TYPE, hasEyePredicate)
                                .where(FACING, Predicates.equalTo(Direction.EAST))))
                .build();
    }

    public DisasterPortalFrame() {
        super(Properties.of()
                .mapColor(MapColor.COLOR_GREEN)
                .instrument(NoteBlockInstrument.BASEDRUM)
                .sound(SoundType.GLASS)
                .lightLevel(s -> 1)
                .strength(-1.0F, 3600000.0F)
                .noLootTable()
                .pushReaction(PushReaction.BLOCK));
        registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(HAS_EYE, false)
                .setValue(EYE_TYPE, EyeType.EMPTY));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return state.getValue(HAS_EYE) ? FULL_SHAPE : BASE_SHAPE;
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return this.defaultBlockState()
                .setValue(FACING, ctx.getHorizontalDirection().getOpposite())
                .setValue(HAS_EYE, false)
                .setValue(EYE_TYPE, EyeType.EMPTY);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HAS_EYE, FACING, EYE_TYPE);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DisasterPortalFrameEntity(pos, state);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player,
                                              InteractionHand hand,
                                              BlockHitResult hitResult) {
        if (state.getValue(HAS_EYE)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        String heldItemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
        String eyeKey = ModBlocks.FULL_ID_TO_EYE_KEY.get(heldItemId);

        if (eyeKey == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        EyeType eyeType = EyeType.fromKey(eyeKey);

        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }

        // 去重检查
        if (!isEyeAbsent(level, pos, eyeType)) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("block.beloong.disaster_portal_frame.eye_duplicate"),
                    true);
            return ItemInteractionResult.FAIL;
        }

        // 设置方块状态
        BlockState newState = state.setValue(HAS_EYE, true).setValue(EYE_TYPE, eyeType);
        level.setBlock(pos, newState, 3);
        level.updateNeighbourForOutputSignal(pos, this);

        // 同步 BlockEntity
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof DisasterPortalFrameEntity frameEntity) {
            frameEntity.setEyeId(ModBlocks.EYE_KEY_TO_FULL_ID.get(eyeKey));
            frameEntity.setChanged();
        }

        stack.consume(1, player);
        level.playSound(null, pos, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);

        // 检查是否全部激活
        BlockPattern.BlockPatternMatch match = getCompletedPortalShape(true).find(level, pos);
        if (match != null) {
            BlockPos frontTopLeft = match.getFrontTopLeft().offset(-3, 0, -3);
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    level.setBlock(frontTopLeft.offset(i, 0, j),
                            ModBlocks.DISASTER_PORTAL_BLOCK.get().defaultBlockState(), 3);
                }
            }
            level.globalLevelEvent(1038, frontTopLeft.offset(1, 0, 1), 0);
        }

        return ItemInteractionResult.CONSUME;
    }

    /**
     * 检查 portal 中是否已存在相同的眼球类型。
     * @return true 表示该眼球尚未被使用，可以放置
     */
    private static boolean isEyeAbsent(Level level, BlockPos clickedPos, EyeType eyeTypeToPlace) {
        BlockPattern.BlockPatternMatch match = getCompletedPortalShape(false).find(level, clickedPos);
        if (match == null) return true;

        BlockPos frontTopLeft = match.getFrontTopLeft().offset(-4, 0, -4);
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                BlockPos checkPos = frontTopLeft.offset(i, 0, j);
                BlockState checkState = level.getBlockState(checkPos);
                if (checkState.is(ModBlocks.DISASTER_PORTAL_FRAME.get())
                        && eyeTypeToPlace == checkState.getValue(EYE_TYPE)) {
                    return false;
                }
            }
        }
        return true;
    }
}
