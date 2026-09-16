package com.zonlong.beloong.block;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import com.mojang.logging.LogUtils;
import com.zonlong.beloong.registry.ModParticles;
import com.zonlong.beloong.registry.ModSounds;
import com.zonlong.beloong.teleport.DimensionTeleport;
import com.zonlong.beloong.teleport.TeleportCooldown;
import com.zonlong.beloong.teleport.TeleportTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 龙宫传送门方块：「荧石框架 + 框内注水」点亮，走进即传送。
 * <p>
 * <b>观感是"换色的下界门"</b>：门面走原版下界门模型（父模型 {@code minecraft:block/nether_portal_ns/ew}）
 * + 天境的动态贴图，见 {@code assets/beloong/models/block/loong_palace_portal_*.json}；
 * 环境音与粒子照抄原版下界门的 {@code animateTick}（环境音改用我们的音效）。
 * <p>
 * <b>本类只做四件事</b>（落点、冷却、扩散全部交给 {@code teleport} 包）：
 * <ol>
 *   <li>{@link #entityInside}：登记「在门内」（客户端也登记，为原版扭曲过渡供数）；</li>
 *   <li>{@link #getPortalTransitionTime}：返回 {@value #PORTAL_TRANSITION_TICKS}（接触即尝试）；</li>
 *   <li>{@link #getPortalDestination}：三道闸（非玩家 / <b>非龙</b> / 维度不允许）后调
 *       {@link DimensionTeleport#toTarget}；</li>
 *   <li>{@link #updateShape}：框架被破坏则门消失。</li>
 * </ol>
 * <p>
 * <b>方向规则</b>：当前维度是龙宫 → 主世界（世界出生点 + 扩散）；否则 → 龙宫（配置固定坐标）。
 * <b>仅主世界 ↔ 龙宫</b>：其他维度既不点亮（见 {@link LoongPalacePortalActivation}）也不传送（本类再判一次）。
 * <p>
 * <b>不生成返回门</b>：原版下界门的返回门来自它自己的 {@code getExitPortal → createPortal}；
 * 我们根本不调它，只返回自己算好的 {@link DimensionTransition}。
 * <p>
 * <b>禁止</b>在本类中调用 {@code Level#getChunk} / {@code Level#getHeight}（阻塞版）——
 * 见 {@code docs/天灾传送门主线程死锁-根因与修复复盘.md}。
 */
public class LoongPalacePortalBlock extends Block implements Portal {

    /** 水平轴，与下界门同名同语义。 */
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;

    /**
     * 进门倒计时（ticks）：<b>0</b> ⇒ 接触即尝试传送，与天灾门一致。
     * <p>
     * 不用原版下界门的 80 tick：那 4 秒死等待在落点已就绪时也会发生（天灾门当年的实机回归结论）。
     */
    public static final int PORTAL_TRANSITION_TICKS = 0;

    /** 环境音播放概率的倒数（1/100，取自天境；原版下界门是 1/2000）。 */
    private static final int AMBIENT_SOUND_CHANCE = 100;

    /** 环境音音量（天境的原值为 0.5，此处照抄）。 */
    private static final float AMBIENT_VOLUME = 0.5F;

    /** 点亮与传送音效的音量（天境的原值为 0.25，原实现误用 1.0 ⇒ 偏响 4 倍）。 */
    private static final float EVENT_SOUND_VOLUME = 0.25F;

    /** 碰撞箱：与下界门逐字相同（X 轴薄板在 z=6..10，Z 轴薄板在 x=6..10）。 */
    private static final VoxelShape X_AXIS_SHAPE = Block.box(0.0D, 0.0D, 6.0D, 16.0D, 16.0D, 10.0D);
    private static final VoxelShape Z_AXIS_SHAPE = Block.box(6.0D, 0.0D, 0.0D, 10.0D, 16.0D, 16.0D);

    private static final Logger LOGGER = LogUtils.getLogger();

    public LoongPalacePortalBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .noCollission()
                .strength(-1.0F, 3600000.0F)      // 不可破坏
                .noLootTable()
                .lightLevel(s -> 11)
                .sound(SoundType.GLASS)
                .pushReaction(PushReaction.BLOCK)
                .forceSolidOn());                  // 与天境一致：虽无碰撞，但要参与实体遮挡判定
        this.registerDefaultState(this.getStateDefinition().any().setValue(AXIS, Direction.Axis.X));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    /** 当前维度是否允许门工作（唯一的"允许集合"判据，激活器与本类共用）。 */
    static boolean isAllowedDimension(ResourceKey<Level> dimension) {
        return Level.OVERWORLD.equals(dimension) || TeleportTarget.LOONG_PALACE_LEVEL.equals(dimension);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return state.getValue(AXIS) == Direction.Axis.Z ? Z_AXIS_SHAPE : X_AXIS_SHAPE;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return switch (rotation) {
            case COUNTERCLOCKWISE_90, CLOCKWISE_90 -> switch (state.getValue(AXIS)) {
                case Z -> state.setValue(AXIS, Direction.Axis.X);
                case X -> state.setValue(AXIS, Direction.Axis.Z);
                default -> state;
            };
            default -> state;
        };
    }

    /**
     * 接触门方块时触发。<b>只登记，不传送、不加载区块</b>。
     * <p>
     * 客户端也登记（与原版 {@code NetherPortalBlock} 同构），否则
     * {@link #getLocalTransition()} 的扭曲过渡不会有任何视觉效果。
     */
    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (level.isClientSide) {
            if (entity.canUsePortal(false)) entity.setAsInsidePortal(this, pos);
            return;
        }

        if (!(entity instanceof ServerPlayer player)) return;

        // 冷却期：拦下、不登记、不提示（持续接触时提示无意义）。
        // 同时顶住原版冷却，使"冷却期内不再重试"这一承诺成立（见 TeleportCooldown 的说明）。
        if (TeleportCooldown.isOnCooldown(player)) {
            TeleportCooldown.refreshVanillaToOutlastNbt(player, level);
            return;
        }

        // 失败预检：只为"给玩家一个说法"，不阻断登记（登记后 getPortalDestination 仍会再判一次）。
        // 放在这里而不是 getPortalDestination，是为了避免每 tick 反复触发（那里没有节流位置）。
        if (!isAllowedDimension(level.dimension())) {
            LoongPalacePortalNotifier.notify(player, LoongPalacePortalNotifier.MSG_WRONG_DIMENSION,
                    LoongPalacePortalNotifier.Failure.WRONG_DIMENSION);
        } else if (!DragonStateProvider.isDragon(player)) {
            LoongPalacePortalNotifier.notify(player, LoongPalacePortalNotifier.MSG_NOT_A_DRAGON,
                    LoongPalacePortalNotifier.Failure.NON_DRAGON);
        }

        if (player.isPassenger()) player.stopRiding();
        entity.setAsInsidePortal(this, pos);
    }

    /** 接触即尝试传送（{@value #PORTAL_TRANSITION_TICKS}）。 */
    @Override
    public int getPortalTransitionTime(ServerLevel level, Entity entity) {
        return PORTAL_TRANSITION_TICKS;
    }

    /** 原版「视角扭曲」局部过渡（与下界门、天灾门同款）。 */
    @Override
    public Portal.Transition getLocalTransition() {
        return Portal.Transition.CONFUSION;
    }

    /**
     * 由 {@code PortalProcessor} 回调，返回本次传送的目标描述；{@code null} = 本 tick 不传送。
     * <p>
     * 三道闸：① 非玩家 ② <b>非龙</b> ③ 维度不允许；之后交给 {@link DimensionTeleport}。
     */
    @Nullable
    @Override
    public DimensionTransition getPortalDestination(ServerLevel level, Entity entity, BlockPos pos) {
        if (!(entity instanceof ServerPlayer player)) return null;

        if (!DragonStateProvider.isDragon(player)) {
            LoongPalacePortalNotifier.notify(player, LoongPalacePortalNotifier.MSG_NOT_A_DRAGON,
                    LoongPalacePortalNotifier.Failure.NON_DRAGON);
            return null;
        }
        if (!isAllowedDimension(level.dimension())) {
            LoongPalacePortalNotifier.notify(player, LoongPalacePortalNotifier.MSG_WRONG_DIMENSION,
                    LoongPalacePortalNotifier.Failure.WRONG_DIMENSION);
            return null;
        }

        boolean fromLoongPalace = TeleportTarget.LOONG_PALACE_LEVEL.equals(level.dimension());

        TeleportTarget target;
        if (fromLoongPalace) {
            ServerLevel overworld = player.server.getLevel(Level.OVERWORLD);
            if (overworld == null) {
                LOGGER.error("[BeLoongCore] overworld not found, cannot leave loong palace (player {})",
                        player.getName().getString());
                LoongPalacePortalNotifier.notify(player, LoongPalacePortalNotifier.MSG_TELEPORT_FAILED,
                        LoongPalacePortalNotifier.Failure.TELEPORT_FAILED);
                return null;
            }
            target = new TeleportTarget.Spawn(overworld);
        } else {
            ServerLevel loongPalace = player.server.getLevel(TeleportTarget.LOONG_PALACE_LEVEL);
            if (loongPalace == null) {
                LOGGER.error("[BeLoongCore] destination dimension {} not found (player {})",
                        TeleportTarget.LOONG_PALACE_LEVEL.location(), player.getName().getString());
                LoongPalacePortalNotifier.notify(player, LoongPalacePortalNotifier.MSG_TELEPORT_FAILED,
                        LoongPalacePortalNotifier.Failure.TELEPORT_FAILED);
                return null;
            }
            target = TeleportTarget.toLoongPalace(loongPalace);
        }

        DimensionTransition transition = DimensionTeleport.toTarget(player, target, postTransition());
        if (transition == null) {
            // 防御分支：At 目标有配置兜底、Spawn 目标不查高度图 ⇒ 实际不会发生
            LOGGER.error("[BeLoongCore] teleport to {} is not possible right now (player {})",
                    target.level().dimension().location(), player.getName().getString());
            LoongPalacePortalNotifier.notify(player, LoongPalacePortalNotifier.MSG_TELEPORT_FAILED,
                    LoongPalacePortalNotifier.Failure.TELEPORT_FAILED);
            return null;
        }
        return transition;
    }

    /** 传送完成后的收尾：摔落清零 + 记统一冷却 + 播 travel 音效（在目标维度播放）。 */
    private static DimensionTransition.PostDimensionTransition postTransition() {
        return entity -> {
            entity.fallDistance = 0;
            if (entity instanceof ServerPlayer player) {
                TeleportCooldown.mark(player);
                player.level().playSound(null, player.blockPosition(),
                        ModSounds.LOONG_PALACE_PORTAL_TRAVEL.get(), SoundSource.BLOCKS,
                        EVENT_SOUND_VOLUME, 1.0F);
            }
        };
    }

    /**
     * 形状不再完整（框架被拆）则门消失；否则保持。
     * <p>
     * 判据照抄天境 {@code AetherPortalBlock#updateShape}：只有当"该方向既不是同轴的水平相邻、
     * 相邻方块也不是本门方块、且当前形状已不完整"三者同时成立时才熄灭。
     * 前两个条件是短路：门方块之间的水平传递、以及与同轴邻块的更新，都不做昂贵的形状重算。
     */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState facingState,
                                     LevelAccessor level, BlockPos currentPos, BlockPos facingPos) {
        Direction.Axis blockAxis = state.getValue(AXIS);
        Direction.Axis neighborAxis = direction.getAxis();
        boolean crossAxisHorizontal = blockAxis != neighborAxis && neighborAxis.isHorizontal();
        return !crossAxisHorizontal
                && !facingState.is(this)
                && !new LoongPalacePortalShape(level, currentPos, blockAxis).isComplete()
                ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, direction, facingState, level, currentPos, facingPos);
    }

    /**
     * 客户端效果：低频环境音 + 粒子。
     * <p>
     * 粒子改用本模组自己的 {@link ModParticles#LOONG_PALACE_PORTAL}（贴图与原版下界门相同，
     * 但配色复刻天境：红绿压到 20%，偏冷蓝青——见 {@code client/particle/LoongPalacePortalParticle}）。
     * <p>
     * <b>环境音为什么不用 {@code playLocalSound}</b>：那条路径是原版下界门用的"本地环境音"
     * （{@code Attenuation.NONE}），贴着门听会明显偏响；而且它只接受 {@code SoundEvent}、
     * 无法传入自定义的 {@link SoundInstance}。这里改为客户端直接播放
     * （与天境 {@code AetherPortalBlock#animateTick} 同一写法）：
     * {@link SimpleSoundInstance} 的公开构造器默认<b>线性衰减</b>，音量取 {@value #AMBIENT_VOLUME}（与天境一致）。
     */
    @OnlyIn(Dist.CLIENT)
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(AMBIENT_SOUND_CHANCE) == 0) {
            Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(
                    ModSounds.LOONG_PALACE_PORTAL_AMBIENT.get(), SoundSource.BLOCKS,
                    AMBIENT_VOLUME, random.nextFloat() * 0.4F + 0.8F, random,
                    pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D));
        }

        for (int i = 0; i < 4; i++) {
            double x = pos.getX() + random.nextDouble();
            double y = pos.getY() + random.nextDouble();
            double z = pos.getZ() + random.nextDouble();
            double dx = (random.nextFloat() - 0.5) * 0.5;
            double dy = (random.nextFloat() - 0.5) * 0.5;
            double dz = (random.nextFloat() - 0.5) * 0.5;
            int side = random.nextInt(2) * 2 - 1;
            if (!level.getBlockState(pos.west()).is(this) && !level.getBlockState(pos.east()).is(this)) {
                x = pos.getX() + 0.5D + 0.25D * side;
                dx = random.nextFloat() * 2.0F * side;
            } else {
                z = pos.getZ() + 0.5D + 0.25D * side;
                dz = random.nextFloat() * 2.0F * side;
            }
            level.addParticle(ModParticles.LOONG_PALACE_PORTAL.get(), x, y, z, dx, dy, dz);
        }
    }

    /** 不可通过拾取方块获得（只能由框架点亮生成），与下界门/天境一致。 */
    @Override
    public net.minecraft.world.item.ItemStack getCloneItemStack(
            net.minecraft.world.level.LevelReader level, BlockPos pos, BlockState state) {
        return net.minecraft.world.item.ItemStack.EMPTY;
    }
}
