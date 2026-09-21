package com.zonlong.beloong.block;

import com.mojang.serialization.MapCodec;
import com.zonlong.beloong.registry.ModBlocks;
import com.zonlong.beloong.registry.ModItemTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 地狱之门（{@code beloong:hell_gate}）—— 灾变「封印之门」的移植件。
 *
 * <h2>它是什么</h2>
 * 一个 <b>5 宽 × 8 高</b>的多方块门（放置 1 格后由 {@link #setPlacedBy} 铺满 40 格）。
 * 手持 <b>{@code #beloong:hell_gate_keys} 里的钥匙</b>（默认是铁魔法的骸骨钥匙
 * {@code irons_spellbooks:bone_key}）右键任意一格即触发开门流程：
 * <ol>
 *   <li>该格 {@code LIT = true}（并触发方块事件 1 启动开启动画）</li>
 *   <li>{@link HellGateBlockEntity#tick} 推进：第 1 tick 屏震 → 第 28 tick 播开门音效 + 一次
 *       {@code TRIGGER} 型爆炸（不破坏方块）→ 第 145 tick 把全部 40 格置 {@code OPEN = true}（碰撞箱清空＝放行）</li>
 * </ol>
 * 开门<b>只放行，不传送</b>。钥匙<b>不消耗</b>（与原版一致，只做 {@code is(...)} 判定）。
 *
 * <h2>⚠️ 这是移植件：改之前先读这里</h2>
 * 本类与 {@link HellGateBlockEntity}、{@code client/model/HellGateModel}、
 * {@code client/animation/HellGateAnimation}、{@code client/HellGateRenderer} 五件套
 * <b>逐行移植自灾变（Cataclysm 3.26）的封印之门</b>，只把注册表引用与命名空间换成本模组的：
 *
 * <table border="1">
 *   <caption>移植映射</caption>
 *   <tr><th>灾变</th><th>本模组</th></tr>
 *   <tr><td>{@code ModBlocks.DOOR_OF_SEAL}</td><td>{@link ModBlocks#HELL_GATE}</td></tr>
 *   <tr><td>{@code ModTileentites.DOOR_OF_SEAL}</td><td>{@link ModBlocks#HELL_GATE_BLOCK_ENTITY}</td></tr>
 *   <tr><td>{@code ModItems.STRANGE_KEY}（怪奇之钥，写死）</td><td><b>{@link ModItemTags#HELL_GATE_KEYS} 物品 tag</b>（默认骸骨钥匙）——见 §七 偏离 4</td></tr>
 *   <tr><td>{@code ModSounds.DOOR_OF_SEAL_OPEN}</td><td>{@link ModSounds#HELL_GATE_OPEN}（自建音效，字幕「地狱之门：敞开」）</td></tr>
 *   <tr><td>{@code ScreenShake_Entity.ScreenShake(...)}</td><td><b>未移植</b>——仍直接调用灾变的静态 helper（灾变是 required 依赖）</td></tr>
 * </table>
 * 因此：<b>不要</b>试图把本类改成 {@code extends} 灾变的方块类。灾变那套类把
 * {@code ModBlocks/MODTileentites/ModItems/ModSounds} 全部写死，且方块实体类型受
 * {@code LevelChunk} 的 {@code type.isValid(state)} 强制校验（失败只会静默不登记 BE），
 * 继承路线无法成立。这是 2026-09-20 逐条核对源码后的结论。
 *
 * @see HellGateBlockEntity
 * @see com.zonlong.beloong.client.HellGateRenderer
 */
public class HellGateBlock extends BaseEntityBlock {
    public static final MapCodec<HellGateBlock> CODEC = simpleCodec(HellGateBlock::new);

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    /** 本格在多方块里的角色。序列化名与灾变逐一对应。 */
    public static final EnumProperty<HellGatePart> PART =
            EnumProperty.create("door_part", HellGatePart.class);

    /** 本格相对于基准格（{@code CENTER} + {@code Y_OFFSET == 0}）的垂直偏移。 */
    public static final IntegerProperty Y_OFFSET = IntegerProperty.create("y_offset", 0, 7);

    /** 门高 8 格。 */
    public static final int GATE_HEIGHT = 8;

    private static final VoxelShape CLOSED_SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);

    @Override
    public MapCodec<HellGateBlock> codec() {
        return CODEC;
    }

    public HellGateBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(LIT, Boolean.FALSE)
                .setValue(OPEN, Boolean.FALSE)
                .setValue(PART, HellGatePart.CENTER)
                .setValue(Y_OFFSET, 0));
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, OPEN, LIT, PART, Y_OFFSET);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HellGateBlockEntity(pos, state);
    }

    /**
     * 手持钥匙 tag 里的任一物品右键任意一格 ⇒ 换算到基准格并触发开门流程。
     * <p>
     * 判定用 {@code stack.is(ModItemTags.HELL_GATE_KEYS)}（物品 tag），因此整合包增删钥匙不需要改代码；
     * 物品 tag 是双端同步的，所以客户端预测结果与服务端判定一致。
     * <p>
     * 注意命中点坐标要一起换算：{@code onHit} 用的是基准格的位置，若直接把当前格的
     * {@link BlockHitResult} 传下去，音效/爆炸/动画会发生在被点的那一格而不是门中心。
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        BlockPos basePos = getBasePos(state, pos);
        BlockState baseState = level.getBlockState(basePos);

        if (player.getItemInHand(hand).is(ModItemTags.HELL_GATE_KEYS)) {
            return this.onHit(level, baseState, new BlockHitResult(
                    hit.getLocation().add(basePos.getX() - pos.getX(),
                            basePos.getY() - pos.getY(), basePos.getZ() - pos.getZ()),
                    hit.getDirection(), basePos, hit.isInside()
            ), player, hand, true)
                    ? ItemInteractionResult.sidedSuccess(level.isClientSide)
                    : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    public boolean onHit(Level level, BlockState state, BlockHitResult hit, Player player,
                         InteractionHand hand, boolean ring) {
        BlockPos pos = hit.getBlockPos();
        if (ring) {
            this.attemptToRing(player, level, hand, state, pos);
            return true;
        }
        return false;
    }

    public boolean attemptToRing(Player player, Level level, InteractionHand hand, BlockState state, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!level.isClientSide && blockEntity instanceof HellGateBlockEntity && !state.getValue(LIT)) {
            ((HellGateBlockEntity) blockEntity).onHit(level);
            level.setBlock(pos, state.setValue(LIT, Boolean.TRUE), 3);
            level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
            return true;
        }
        return false;
    }

    /** 由任意一格反推基准格（{@code CENTER} + {@code Y_OFFSET == 0} 那一格）。 */
    private BlockPos getBasePos(BlockState state, BlockPos pos) {
        BlockPos toReturn = pos.below(state.getValue(Y_OFFSET));
        if (state.getValue(PART) == HellGatePart.SIDE_LEFT) {
            toReturn = toReturn.relative(state.getValue(FACING).getCounterClockWise());
        } else if (state.getValue(PART) == HellGatePart.SIDE_RIGHT) {
            toReturn = toReturn.relative(state.getValue(FACING).getClockWise());
        }
        if (state.getValue(PART) == HellGatePart.END_LEFT) {
            toReturn = toReturn.relative(state.getValue(FACING).getCounterClockWise(), 2);
        } else if (state.getValue(PART) == HellGatePart.END_RIGHT) {
            toReturn = toReturn.relative(state.getValue(FACING).getClockWise(), 2);
        }
        return toReturn;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlocks.HELL_GATE_BLOCK_ENTITY.get(), HellGateBlockEntity::tick);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return CLOSED_SHAPE;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        // 几何由 HellGateRenderer 在基准格一次性绘制（模型高达 8 格），本方块自身不出模型。
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    public VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        return state.getValue(OPEN) ? Shapes.empty() : CLOSED_SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext context) {
        return state.getValue(OPEN) ? Shapes.empty() : CLOSED_SHAPE;
    }

    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    /** 5 宽 × 8 高范围内是否都放得下（要求全部可替换）。 */
    private boolean doesGateFitInDirection(BlockPos pos, Direction direction, Level level) {
        for (int i = 0; i < GATE_HEIGHT; i++) {
            BlockPos abovePos = pos.above(i);
            BlockPos[] toBreakPoses = {
                    abovePos.relative(direction.getClockWise()),
                    abovePos,
                    abovePos.relative(direction.getCounterClockWise()),
                    abovePos.relative(direction.getClockWise(), 2),
                    abovePos.relative(direction.getCounterClockWise(), 2)
            };
            for (BlockPos toBreakPos : toBreakPoses) {
                if (!level.getBlockState(toBreakPos).canBeReplaced()) {
                    return false;
                }
            }
        }
        return true;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction direction = context.getClickedFace();
        BlockPos pos = context.getClickedPos();
        if (direction.getAxis() == Direction.Axis.Y) {
            Direction dir = context.getHorizontalDirection();
            BlockState state = this.defaultBlockState().setValue(FACING, dir);
            if (state.canSurvive(context.getLevel(), pos)
                    && doesGateFitInDirection(pos, dir, context.getLevel())) {
                return state;
            }
        } else {
            Direction dir = direction.getOpposite();
            BlockState state = this.defaultBlockState().setValue(FACING, dir);
            if (state.canSurvive(context.getLevel(), pos)
                    && doesGateFitInDirection(pos, dir, context.getLevel())) {
                return state;
            }
        }
        return null;
    }

    /** 放置 1 格即铺满整个 5×8 多方块。 */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) {
            return;
        }
        for (int i = 0; i < GATE_HEIGHT; i++) {
            BlockPos abovePos = pos.above(i);
            BlockPos sideLeft = abovePos.relative(state.getValue(FACING).getCounterClockWise());
            BlockPos center = abovePos;
            BlockPos sideRight = abovePos.relative(state.getValue(FACING).getClockWise());
            BlockPos endLeft = abovePos.relative(state.getValue(FACING).getCounterClockWise(), 2);
            BlockPos endRight = abovePos.relative(state.getValue(FACING).getClockWise(), 2);

            BlockState part = ModBlocks.HELL_GATE.get().defaultBlockState()
                    .setValue(FACING, state.getValue(FACING))
                    .setValue(Y_OFFSET, i);
            level.setBlock(sideLeft, part.setValue(PART, HellGatePart.SIDE_LEFT), 3);
            level.setBlock(sideRight, part.setValue(PART, HellGatePart.SIDE_RIGHT), 3);
            level.setBlock(endLeft, part.setValue(PART, HellGatePart.END_LEFT), 3);
            level.setBlock(endRight, part.setValue(PART, HellGatePart.END_RIGHT), 3);
            if (center != pos) {
                level.setBlock(center, part.setValue(PART, HellGatePart.CENTER), 3);
            }

            level.blockUpdated(abovePos, Blocks.AIR);
            state.updateNeighbourShapes(level, abovePos, 3);
        }
    }

    /** 创造模式破坏任意一格 ⇒ 整扇门一起拆掉（否则会留下 39 格拆不掉的残骸）。 */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative()) {
            BlockPos basePos = getBasePos(state, pos);
            BlockState baseState = level.getBlockState(basePos);

            if (baseState.is(ModBlocks.HELL_GATE.get())) {
                for (int i = 0; i < GATE_HEIGHT; i++) {
                    BlockPos abovePos = basePos.above(i);
                    BlockPos[] toBreakPoses = {
                            abovePos.relative(baseState.getValue(FACING).getClockWise()),
                            abovePos,
                            abovePos.relative(baseState.getValue(FACING).getCounterClockWise()),
                            abovePos.relative(baseState.getValue(FACING).getClockWise(), 2),
                            abovePos.relative(baseState.getValue(FACING).getCounterClockWise(), 2)
                    };
                    for (BlockPos toBreakPos : toBreakPoses) {
                        BlockState blockstate = level.getBlockState(toBreakPos);
                        if (blockstate.is(ModBlocks.HELL_GATE.get())) {
                            level.setBlock(toBreakPos, Blocks.AIR.defaultBlockState(), 35);
                            level.levelEvent(player, 2001, toBreakPos, Block.getId(blockstate));
                        }
                    }
                }
                level.setBlock(basePos, Blocks.AIR.defaultBlockState(), 35);
                level.levelEvent(player, 2001, basePos, Block.getId(baseState));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** 多方块角色。序列化名与灾变一致（{@code side_left} 等）。 */
    public enum HellGatePart implements StringRepresentable {
        SIDE_LEFT("side_left"),
        SIDE_RIGHT("side_right"),
        END_LEFT("end_left"),
        END_RIGHT("end_right"),
        CENTER("center");

        private final String name;

        HellGatePart(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return this.name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }
}
