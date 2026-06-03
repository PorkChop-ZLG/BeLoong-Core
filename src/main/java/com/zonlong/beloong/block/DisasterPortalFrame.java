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

/**
 * 天灾传送门框架方块。
 * <p>
 * 外观和行为模仿原版 {@link net.minecraft.world.level.block.EndPortalFrameBlock}（末地传送门框架）：
 * <ul>
 *   <li>玩家手持 12 种配置的眼球物品右键框架时，眼球被嵌入框架。</li>
 *   <li>每个眼球必须互不相同（通过 {@link #isEyeAbsent} 去重）。</li>
 *   <li>当 5×5 环形排列的 12 个框架全部嵌入眼球后，
 *       中间的 3×3 区域自动填充为 {@link DisasterPortalBlock}，激活传送门。</li>
 * </ul>
 * <p>
 * <b>BlockState 属性：</b>
 * <ul>
 *   <li>{@link #FACING} — 水平朝向（北/东/南/西），决定玩家面向哪一侧放置框架</li>
 *   <li>{@link #HAS_EYE} — 是否有眼球嵌入（true/false）</li>
 *   <li>{@link #EYE_TYPE} — 嵌入的眼球类型（{@link EyeType} 枚举，共 13 个值：EMPTY + 12 种眼球）</li>
 * </ul>
 * <p>
 * <b>传送门形状（5×5 环形，12 个框架）：</b>
 * <pre>
 *     v   v   v
 *   >           <
 *   >    3x3    <
 *   >   传送门   <
 *     ^   ^   ^
 * </pre>
 * 每个符号 v/^/>/< 代表一个带指定朝向的框架方块。
 * <p>
 * 该方块通过 {@link ModBlocks#DISASTER_PORTAL_FRAME} 注册，
 * 对应 BlockEntity 为 {@link DisasterPortalFrameEntity}（存储眼球 ID）。
 */
public class DisasterPortalFrame extends Block implements EntityBlock {

    /** 是否有眼球嵌入（和原版末地传送门框架一致，使用 {@link BlockStateProperties#EYE}）。 */
    public static final BooleanProperty HAS_EYE = BlockStateProperties.EYE;

    /** 框架的水平朝向，4 个水平方向。玩家面对的反方向为框架正面。 */
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

    /**
     * 嵌入的眼球类型。
     * 使用 {@link EnumProperty} 配合 {@link EyeType} 枚举（实现 {@link net.minecraft.util.StringRepresentable}），
     * 在 BlockState JSON 中通过 {@code eye_type=xxx} 切换纹理变体。
     */
    public static final EnumProperty<EyeType> EYE_TYPE =
            EnumProperty.create("eye_type", EyeType.class);

    /** 无眼球时的碰撞箱：16×13×16（框架底座，上表面凹槽以下） */
    protected static final VoxelShape BASE_SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 13.0D, 16.0D);

    /** 眼球凸起碰撞箱：4×3×4（仅 Y=13~16 的中央区域） */
    protected static final VoxelShape EYE_SHAPE = Block.box(4.0D, 13.0D, 4.0D, 12.0D, 16.0D, 12.0D);

    /** 嵌入眼球后的完整碰撞箱 = 底座 + 眼球凸起 */
    protected static final VoxelShape FULL_SHAPE = Shapes.or(BASE_SHAPE, EYE_SHAPE);

    /**
     * 获取传送门框架的 BlockPattern 模板。
     * <p>
     * 定义了一个 5×5 的环形图案，12 个框架分布在四条边上（4×3=12），
     * 中间 3×3 为空（预留传送门方块位置）。
     * <p>
     * 图案符号说明：
     * <ul>
     *   <li><b>?</b> — 任意方块（角位置，不属于传送门框架）</li>
     *   <li><b>v</b> — 朝南的框架（顶边）</li>
     *   <li><b>^</b> — 朝北的框架（底边）</li>
     *   <li><b>&lt;</b> — 朝西的框架（右边）</li>
     *   <li><b>&lt;</b> — 朝东的框架（左边）</li>
     * </ul>
     *
     * @param requireEyes true 时仅匹配已嵌入眼球（非 EMPTY）的框架，用于激活检测
     *                    false 时匹配所有框架（无论是否嵌入眼球），用于去重检测
     */
    public static BlockPattern getCompletedPortalShape(boolean requireEyes) {
        // 当 requireEyes=true 时，仅 EYE_TYPE != EMPTY 的框架匹配（即所有 12 帧都有眼球）
        // 当 requireEyes=false 时，所有框架都匹配（用于检查某个眼球是否已被占用）
        Predicate<Object> hasEyePredicate = (eyeType) -> requireEyes ? eyeType != EyeType.EMPTY : true;

        return BlockPatternBuilder.start()
                // 5 行 Pattern，每行 5 列
                // ? v v v ?    顶边：角落空 + 3 个向南框架
                // > ? ? ? <    左边：1 个向东框架，3 个空，1 个向西框架
                // > ? ? ? <    中间同上
                // > ? ? ? <
                // ? ^ ^ ^ ?    底边：角落空 + 3 个向北框架
                .aisle("?vvv?", ">???<", ">???<", ">???<", "?^^^?")
                .where('?', BlockInWorld.hasState(BlockStatePredicate.ANY))
                // 顶边 — 朝南（面向玩家方向）
                .where('^', BlockInWorld.hasState(
                        BlockStatePredicate.forBlock(ModBlocks.DISASTER_PORTAL_FRAME.get())
                                .where(HAS_EYE, Predicates.alwaysTrue())
                                .where(EYE_TYPE, hasEyePredicate)
                                .where(FACING, Predicates.equalTo(Direction.SOUTH))))
                // 右边 — 朝西
                .where('>', BlockInWorld.hasState(
                        BlockStatePredicate.forBlock(ModBlocks.DISASTER_PORTAL_FRAME.get())
                                .where(HAS_EYE, Predicates.alwaysTrue())
                                .where(EYE_TYPE, hasEyePredicate)
                                .where(FACING, Predicates.equalTo(Direction.WEST))))
                // 底边 — 朝北
                .where('v', BlockInWorld.hasState(
                        BlockStatePredicate.forBlock(ModBlocks.DISASTER_PORTAL_FRAME.get())
                                .where(HAS_EYE, Predicates.alwaysTrue())
                                .where(EYE_TYPE, hasEyePredicate)
                                .where(FACING, Predicates.equalTo(Direction.NORTH))))
                // 左边 — 朝东
                .where('<', BlockInWorld.hasState(
                        BlockStatePredicate.forBlock(ModBlocks.DISASTER_PORTAL_FRAME.get())
                                .where(HAS_EYE, Predicates.alwaysTrue())
                                .where(EYE_TYPE, hasEyePredicate)
                                .where(FACING, Predicates.equalTo(Direction.EAST))))
                .build();
    }

    public DisasterPortalFrame() {
        // 方块属性：
        // - 绿色地图色，和原版末地传送门框架一样的外观基调
        // - 玻璃音效
        // - 黑曜石级别硬度（50F）+ 下界合金工具需求，可挖掘，掉落自身
        // - pushReaction BLOCK 防止被活塞推动
        super(Properties.of()
                .mapColor(MapColor.COLOR_GREEN)
                .instrument(NoteBlockInstrument.BASEDRUM)
                .sound(SoundType.GLASS)
                .lightLevel(s -> 1)
                .strength(50.0F, 1200.0F)
                .requiresCorrectToolForDrops()
                .pushReaction(PushReaction.BLOCK));

        // 默认状态：朝北，无眼球
        registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(HAS_EYE, false)
                .setValue(EYE_TYPE, EyeType.EMPTY));
    }

    /**
     * 碰撞箱形状：无眼球时仅底座（16×13×16），有眼球时底座 + 眼球凸起。
     */
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

    /**
     * 放置时的初始状态：朝向玩家的反方向（玩家面向的方向 = 框架正面朝向）。
     */
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

    /**
     * 为每个框架方块创建对应的 BlockEntity。
     * BlockEntity 存储眼球 ID 字符串（用于持久化和去重校验），
     * 同时为后续可能的客户端渲染（眼球纹理叠加）提供数据来源。
     */
    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DisasterPortalFrameEntity(pos, state);
    }

    /**
     * 框架被移除（挖掘/爆炸/TNT等）时触发。
     * <p>
     * 扫描周围的传送门方块（5×5 范围，覆盖整个传送门结构），
     * 将其全部移除，模拟原版下界传送门在框架破坏后破碎的行为。
     * 传送门方块本身不掉落任何物品。
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos,
                            BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            // 框架被替换为不同方块 → 清除周围传送门方块
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos scanPos = pos.offset(dx, 0, dz);
                    if (level.getBlockState(scanPos).is(ModBlocks.DISASTER_PORTAL_BLOCK.get())) {
                        level.removeBlock(scanPos, false);
                    }
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /**
     * 玩家右键框架时的交互逻辑。
     * <p>
     * <b>流程：</b>
     * <ol>
     *   <li>已嵌入眼球的框架 → PASS（不做任何事）</li>
     *   <li>手持物品不在 12 眼球列表中 → PASS</li>
     *   <li>客户端 → 返回 SUCCESS（发送交互包给服务端）</li>
     *   <li>去重检查：同种眼球已在传送门中 → 提示"已放置"</li>
     *   <li>消耗物品、播放音效、更新 BlockState 和 BlockEntity</li>
     *   <li>若所有 12 帧都已嵌入眼球 → 中间 3×3 填充 {@link DisasterPortalBlock}</li>
     * </ol>
     *
     * @param stack      玩家手持的物品堆
     * @param state      当前方块状态
     * @param level      当前维度
     * @param pos        被点击的方块位置
     * @param player     交互的玩家
     * @param hand       交互的手（主手/副手）
     * @param hitResult  射线命中结果
     * @return 交互结果
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player,
                                              InteractionHand hand,
                                              BlockHitResult hitResult) {
        // 已有眼球 → 拒绝
        if (state.getValue(HAS_EYE)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // 手持物品是否在允许的眼球列表中？
        // 通过 BuiltInRegistries 获取物品的完整 ResourceLocation ID（如 "cataclysm:mech_eye"）
        // 然后在 ModBlocks.FULL_ID_TO_EYE_KEY 中查找短键（如 "mech_eye"）
        String heldItemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
        String eyeKey = ModBlocks.FULL_ID_TO_EYE_KEY.get(heldItemId);

        if (eyeKey == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        EyeType eyeType = EyeType.fromKey(eyeKey);

        // 客户端提前返回 SUCCESS，触发交互包发送到服务端
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }

        // 去重：检查同种眼球是否已被放置在此传送门中
        if (!isEyeAbsent(level, pos, eyeType)) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(
                            "block.beloong.disaster_portal_frame.eye_duplicate"),
                    true);
            return ItemInteractionResult.FAIL;
        }

        // 更新方块状态：HAS_EYE=true, EYE_TYPE=对应值
        BlockState newState = state.setValue(HAS_EYE, true).setValue(EYE_TYPE, eyeType);
        level.setBlock(pos, newState, 3);
        level.updateNeighbourForOutputSignal(pos, this);

        // 同步 BlockEntity：存储完整眼球物品 ID（如 "cataclysm:mech_eye"），用于持久化和后续查询
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof DisasterPortalFrameEntity frameEntity) {
            frameEntity.setEyeId(ModBlocks.EYE_KEY_TO_FULL_ID.get(eyeKey));
            frameEntity.setChanged();
        }

        // 消耗 1 个物品，播放末地传送门填充音效
        stack.consume(1, player);
        level.playSound(null, pos, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);

        // 激活检测：所有 12 帧都有眼球 → 中间 3×3 填充传送门方块
        BlockPattern.BlockPatternMatch match = getCompletedPortalShape(true).find(level, pos);
        if (match != null) {
            // 从 Pattern 匹配结果推算中间 3×3 的左上角
            // frontTopLeft 指向匹配区域的左上角（含外围），-3 偏移到中间空心区域
            BlockPos frontTopLeft = match.getFrontTopLeft().offset(-3, 0, -3);
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    level.setBlock(frontTopLeft.offset(i, 0, j),
                            ModBlocks.DISASTER_PORTAL_BLOCK.get().defaultBlockState(), 3);
                }
            }
            // 播放传送门激活特效（声音 + 粒子）
            level.globalLevelEvent(1038, frontTopLeft.offset(1, 0, 1), 0);
        }

        return ItemInteractionResult.CONSUME;
    }

    /**
     * 去重检查：该眼球类型是否已经存在于传送门的其他框架上。
     * <p>
     * 使用 {@link #getCompletedPortalShape(boolean)} 定位已搭建的 5×5 传送门框架结构，
     * 然后遍历所有 12 个框架的 EYE_TYPE，检查是否已有相同类型的眼球。
     * <p>
     * 注意：此处调用 {@code getCompletedPortalShape(false)}（不要求嵌入眼球），
     * 因为框架可能在眼球未全部嵌入时就已经搭建好了。
     *
     * @param level          当前维度
     * @param clickedPos     被点击的框架方块位置
     * @param eyeTypeToPlace 要放置的眼球类型
     * @return true 表示该眼球未被使用，可以安全放置
     */
    private static boolean isEyeAbsent(Level level, BlockPos clickedPos, EyeType eyeTypeToPlace) {
        BlockPattern.BlockPatternMatch match = getCompletedPortalShape(false).find(level, clickedPos);
        if (match == null) return true;  // 没有找到完整的框架结构 → 可以放置

        // 遍历 5×5 的所有格子，检查每个框架方块的眼球类型
        BlockPos frontTopLeft = match.getFrontTopLeft().offset(-4, 0, -4);
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                BlockPos checkPos = frontTopLeft.offset(i, 0, j);
                BlockState checkState = level.getBlockState(checkPos);
                if (checkState.is(ModBlocks.DISASTER_PORTAL_FRAME.get())
                        && eyeTypeToPlace == checkState.getValue(EYE_TYPE)) {
                    return false;  // 找到相同眼球 → 拒绝放置
                }
            }
        }
        return true;
    }
}
