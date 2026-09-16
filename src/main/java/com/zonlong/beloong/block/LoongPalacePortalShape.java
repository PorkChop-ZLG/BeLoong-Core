package com.zonlong.beloong.block;

import com.mojang.logging.LogUtils;
import com.zonlong.beloong.registry.ModBlockTags;
import com.zonlong.beloong.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * 龙宫传送门的<b>框架探测</b>：把"荧石框架 + 框内注水"识别为一扇门，并把框内替换成门方块。
 * <p>
 * <b>为什么单独成类</b>：点亮发生在"水被放置"的那一刻，而那一刻<b>门方块还不存在</b>——
 * 激活器（{@link LoongPalacePortalActivation}）必须能独立调用本类；门方块自身的
 * {@code updateShape} 也用本类判断"形状是否还完整"。
 * <p>
 * <b>复刻自天境</b>（{@code The-Aether/.../block/portal/AetherPortalShape.java}，1.21.1 分支），
 * 三处关键语义与原版 {@code PortalShape} 不同：
 * <ul>
 *   <li>框架由<b>方块 tag</b>判定（{@link ModBlockTags#LOONG_PALACE_PORTAL_FRAME}），
 *       而不是 NeoForge 的 {@code isPortalFrame} 谓词（后者默认只认黑曜石，荧石不是）；</li>
 *   <li><b>"空"包含水</b>（{@code 空气 ∨ 水 ∨ 本门方块}）——这是"注水能点亮"的前提：
 *       水在框内时形状仍算"可建造"，{@link #createPortalBlocks()} 才能把水格换成门方块；</li>
 *   <li>{@link #createPortalBlocks()} 放的是<b>我们的门方块</b>，不是原版 {@code NETHER_PORTAL}。</li>
 * </ul>
 * 尺寸沿用原版下界门的约定：宽 2–21、高 3–21。
 * <p>
 * <b>仅服务端主线程调用</b>（方块事件与方块更新都在主线程）；本类<b>不做</b>区块加载，
 * 只读已加载方块（{@code getBlockState}）。
 */
final class LoongPalacePortalShape {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 框架谓词：方块 tag。 */
    private static final BlockBehaviour.StatePredicate FRAME =
            (state, level, pos) -> state.is(ModBlockTags.LOONG_PALACE_PORTAL_FRAME);

    private static final int MIN_WIDTH = 2;
    private static final int MAX_WIDTH = 21;
    private static final int MIN_HEIGHT = 3;
    private static final int MAX_HEIGHT = 21;

    private final LevelAccessor level;
    private final Direction.Axis axis;
    private final Direction rightDir;
    private final int width;
    private int height;
    private int numPortalBlocks;
    @Nullable
    private BlockPos bottomLeft;

    /**
     * 查找一个<b>框内还没有门方块</b>的空形状（点亮用）。
     *
     * @param pos  框内任意一格（通常是刚被放进水的那一格）
     * @param axis 优先尝试的轴；失败会自动换另一水平轴再试
     */
    static Optional<LoongPalacePortalShape> findEmpty(LevelAccessor level, BlockPos pos, Direction.Axis axis) {
        return findPortalShape(level, pos, shape -> shape.isValid() && shape.numPortalBlocks == 0, axis);
    }

    /**
     * 按给定谓词查找形状。
     * <p>
     * 先按 {@code axis} 试，失败则换另一水平轴再试（原版与天境都这么做：玩家搭的框朝向未知）。
     */
    static Optional<LoongPalacePortalShape> findPortalShape(LevelAccessor level, BlockPos pos,
                                                            Predicate<LoongPalacePortalShape> predicate,
                                                            Direction.Axis axis) {
        LoongPalacePortalShape shape = new LoongPalacePortalShape(level, pos, axis);
        if (predicate.test(shape)) {
            return Optional.of(shape);
        }
        Direction.Axis other = axis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        LoongPalacePortalShape otherShape = new LoongPalacePortalShape(level, pos, other);
        return predicate.test(otherShape) ? Optional.of(otherShape) : Optional.empty();
    }

    /**
     * 构造并立即结算形状（包内可见：门方块的 {@code updateShape} 需要它判断"框架还在不在"）。
     * <p>
     * 非法形状不会被拒绝，而是退化为 {@code width = height = 1} ⇒ {@link #isValid()} 返回 false。
     */
    LoongPalacePortalShape(LevelAccessor level, BlockPos pos, Direction.Axis axis) {
        this.level = level;
        this.axis = axis;
        // 与天境一致：X 轴的门朝 WEST 方向展开，Z 轴朝 SOUTH
        this.rightDir = axis == Direction.Axis.X ? Direction.WEST : Direction.SOUTH;

        BlockPos left = this.calculateBottomLeft(pos);
        if (left == null) {
            this.bottomLeft = pos;
            this.width = 1;
            this.height = 1;
        } else {
            this.bottomLeft = left;
            this.width = this.calculateWidth();
            if (this.width > 0) {
                this.height = this.calculateHeight();
            }
        }
    }

    /** 从 {@code pos} 向下走到框内最底行，再向左走到最左列。 */
    @Nullable
    private BlockPos calculateBottomLeft(BlockPos pos) {
        int minY = Math.max(this.level.getMinBuildHeight(), pos.getY() - MAX_HEIGHT);
        BlockPos cursor = pos;
        while (cursor.getY() > minY && isEmpty(this.level.getBlockState(cursor.below()))) {
            cursor = cursor.below();
        }
        Direction left = this.rightDir.getOpposite();
        int distance = this.getDistanceUntilEdgeAboveFrame(cursor, left) - 1;
        return distance < 0 ? null : cursor.relative(left, distance);
    }

    private int calculateWidth() {
        int distance = this.getDistanceUntilEdgeAboveFrame(this.bottomLeft, this.rightDir);
        return distance >= MIN_WIDTH && distance <= MAX_WIDTH ? distance : 0;
    }

    /**
     * 沿 {@code direction} 数"空"格，直到撞上框架。
     * <p>
     * 同时要求每一格的<b>下方</b>也是框架（否则那是"框没搭在底边上"，不算门）。
     */
    private int getDistanceUntilEdgeAboveFrame(BlockPos pos, Direction direction) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= MAX_WIDTH; i++) {
            cursor.set(pos).move(direction, i);
            BlockState state = this.level.getBlockState(cursor);
            if (!isEmpty(state)) {
                if (FRAME.test(state, this.level, cursor)) {
                    return i;
                }
                break;
            }
            BlockState below = this.level.getBlockState(cursor.move(Direction.DOWN));
            if (!FRAME.test(below, this.level, cursor)) {
                break;
            }
        }
        return 0;
    }

    private int calculateHeight() {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int distance = this.getDistanceUntilTop(cursor);
        return distance >= MIN_HEIGHT && distance <= MAX_HEIGHT && this.hasTopFrame(cursor, distance)
                ? distance
                : 0;
    }

    /** 顶边整条都必须是框架。 */
    private boolean hasTopFrame(BlockPos.MutableBlockPos cursor, int amount) {
        for (int i = 0; i < this.width; i++) {
            BlockPos pos = cursor.set(this.bottomLeft).move(Direction.UP, amount).move(this.rightDir, i);
            if (!FRAME.test(this.level.getBlockState(pos), this.level, pos)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 逐行向上检查：左右两侧必须是框架，中间必须是空；返回"第一处不满足"的高度。
     */
    private int getDistanceUntilTop(BlockPos.MutableBlockPos cursor) {
        for (int i = 0; i < MAX_HEIGHT; i++) {
            cursor.set(this.bottomLeft).move(Direction.UP, i).move(this.rightDir, -1);
            if (!FRAME.test(this.level.getBlockState(cursor), this.level, cursor)) {
                return i;
            }
            cursor.set(this.bottomLeft).move(Direction.UP, i).move(this.rightDir, this.width);
            if (!FRAME.test(this.level.getBlockState(cursor), this.level, cursor)) {
                return i;
            }
            for (int j = 0; j < this.width; j++) {
                cursor.set(this.bottomLeft).move(Direction.UP, i).move(this.rightDir, j);
                BlockState state = this.level.getBlockState(cursor);
                if (!isEmpty(state)) {
                    return i;
                }
                if (state.is(ModBlocks.LOONG_PALACE_PORTAL.get())) {
                    this.numPortalBlocks++;
                }
            }
        }
        return MAX_HEIGHT;
    }

    /**
     * "框内为空"的判定 = <b>空气 ∨ 水 ∨ 本门方块</b>。
     * <p>
     * ⚠️ <b>水必须算空</b>：否则"注水激活"这条链路在第一步就走不通
     * （水格会被当成"非空"，形状判定直接失败）。
     */
    private static boolean isEmpty(BlockState state) {
        return state.isAir()
                || state.getFluidState().is(FluidTags.WATER)
                || state.is(Blocks.WATER)
                || state.is(ModBlocks.LOONG_PALACE_PORTAL.get());
    }

    boolean isValid() {
        return this.bottomLeft != null
                && this.width >= MIN_WIDTH && this.width <= MAX_WIDTH
                && this.height >= MIN_HEIGHT && this.height <= MAX_HEIGHT;
    }

    /** 形状有效且框内每一格都已是本门方块（门方块 {@code updateShape} 用它判断"框架还在不在"）。 */
    boolean isComplete() {
        return this.isValid() && this.numPortalBlocks == this.width * this.height;
    }

    /**
     * 把框内每一格替换成门方块（水/空气都会被替换，与天境一致）。
     * <p>
     * flags = {@code 2 | 16}：发送给客户端、且<b>不触发邻居更新</b>
     * （避免替换过程本身又引发一轮方块更新风暴）。
     */
    void createPortalBlocks() {
        BlockState portal = ModBlocks.LOONG_PALACE_PORTAL.get().defaultBlockState()
                .setValue(LoongPalacePortalBlock.AXIS, this.axis);
        BlockPos.betweenClosed(this.bottomLeft,
                        this.bottomLeft.relative(Direction.UP, this.height - 1).relative(this.rightDir, this.width - 1))
                .forEach(pos -> this.level.setBlock(pos, portal, 2 | 16));
        LOGGER.debug("[BeLoongCore] loong palace portal created: axis={}, width={}, height={}, bottomLeft={}",
                this.axis, this.width, this.height, this.bottomLeft);
    }
}
