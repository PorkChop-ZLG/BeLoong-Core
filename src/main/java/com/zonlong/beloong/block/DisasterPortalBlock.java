package com.zonlong.beloong.block;

import com.mojang.logging.LogUtils;
import com.zonlong.beloong.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 天灾传送门方块。
 * <p>
 * 采用<b>原版传送门范式</b>（{@link Portal} + {@link net.minecraft.world.entity.PortalProcessor}）：
 * <ol>
 *   <li>{@link #entityInside} <b>只登记</b>（{@code entity.setAsInsidePortal}），不做任何区块加载；</li>
 *   <li>原版 {@code Entity.handlePortal()} 在后续 tick 回调 {@link #getPortalDestination}；</li>
 *   <li>落点用<b>非阻塞</b>方式确定：{@code ServerChunkCache#getChunkNow} 探测内存中的区块，
 *       未就绪则申领 {@link TicketType#PORTAL} 区域票据后返回 {@code null}，下一 tick 重试；</li>
 *   <li>换维度由 {@link DimensionTransition} + {@code postDimensionTransition} 回调完成。</li>
 * </ol>
 * <p>
 * <b>禁止</b>在本类中调用 {@code Level#getChunk} / {@code Level#getHeight}（阻塞版）：
 * 旧实现在 {@code entityInside} 里同步加载<b>另一个维度</b>的区块，当该区块生成失败
 * （例如结构排除区互递归导致工作线程 StackOverflowError）时，主线程会在
 * {@code managedBlock} 中<b>永久自旋</b>且不产生 crash report
 * （2026-09-12 实机取证，见 {@code docs/天灾传送门主线程死锁-根因与修复复盘.md}）。
 * <p>
 * 双向行为：
 * <ul>
 *   <li><b>下行（任意维度 → 天灾维度）</b>：1:1 坐标（依赖维度定义 {@code coordinate_scale: 1.0}），
 *       Y 取目标点 MOTION_BLOCKING 高度 + 1 格；</li>
 *   <li><b>上行（天灾维度 → 主世界）</b>：照搬原版末地返回逻辑，回到玩家重生点，
 *       无重生点回退世界出生点，再回退 {@code (0,64,0)}。</li>
 * </ul>
 */
public class DisasterPortalBlock extends Block implements EntityBlock, Portal {

    /** 天灾维度的硬编码 ID，所有非天灾维度的传送目标。 */
    private static final String DISASTER_DIM = "beloong:disaster";

    /**
     * 天灾维度的 {@link ResourceKey}。
     * <p>
     * 本类内部与客户端「维度过渡界面」注册（{@code RegisterDimensionTransitionScreenEvent}，
     * 见 {@code BeLoongCoreClient}）共用，保证维度 ID 只有一处定义。
     */
    public static final ResourceKey<Level> DISASTER_LEVEL =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(DISASTER_DIM));

    /** 传送冷却在玩家持久化 NBT 中的键名。<b>不得更改</b>（跨维度/重登有效）。 */
    private static final String COOLDOWN_KEY = "beloong_portal_cooldown";

    /**
     * 落点区块等待上限（ticks）。
     * <p>
     * 超过该时长仍未就绪即放弃本轮传送并报错——这是替代「主线程永久卡死」的<b>有界失败</b>。
     * 正常首次加载远小于 200 tick（10 s）；本阈值只用于兜住「生成永久失败」的场景
     * （实机故障为即时的结构递归 StackOverflowError）。
     */
    private static final int DESTINATION_WAIT_TIMEOUT_TICKS = 200;

    /** 落点预热票据的半径：0 = 只加载落点所在的那一个区块（原版下界门落点用 3，我们只需要 1 个区块的高度图）。 */
    private static final int DESTINATION_TICKET_RADIUS = 0;

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 碰撞箱：16×12×16 像素，和原版末地传送门一致。 */
    protected static final VoxelShape SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 12.0D, 16.0D);

    public DisasterPortalBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .noCollission()                          // 无碰撞，玩家可以穿过
                .lightLevel(s -> 11)                     // 发光等级 11，和原版末地传送门一致
                .strength(-1.0F, 3600000.0F)             // 不可破坏（基岩级别）
                .noLootTable()                            // 无掉落物
                .pushReaction(PushReaction.BLOCK)         // 不能被活塞推动
                .sound(SoundType.GLASS));                 // 玻璃音效
    }

    /**
     * 返回 {@link RenderShape#INVISIBLE}，禁止渲染方块模型。
     * 传送门的视觉效果完全由 {@link com.zonlong.beloong.client.DisasterPortalRenderer} 负责。
     */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level,
                                  BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext ctx) {
        return SHAPE;
    }

    /**
     * 为该方块创建对应的 BlockEntity。
     * 虽然方块本身不存储数据，但 BlockEntity 是渲染器（{@code DisasterPortalRenderer}）的载体。
     */
    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DisasterPortalBlockEntity(pos, state);
    }

    /**
     * 玩家接触到传送门方块时触发。<b>只登记，不传送、不加载区块</b>。
     * <p>
     * 真正的落点计算与换维度由原版 {@code Entity.handlePortal()} 在后续 tick 回调
     * {@link #getPortalDestination} 完成。原版范式要求实体不再是乘客
     * （{@code handlePortal} 用 {@code canUsePortal(false)} 作闸门），因此这里先 {@code stopRiding()}，
     * 与旧实现「先下车再传送」等价。
     */
    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        // 客户端：只登记「在门内」，供原版 CONFUSION 局部过渡使用（LocalPlayer 每 tick 读 portalProcess）。
        // 与原版 NetherPortalBlock 同构：登记在两侧都做；真正传送的 Entity.handlePortal() 内含
        // ServerLevel 判断，因此客户端不会传送。冷却、下车、票据预热、落点解析全部仅服务端。
        if (level.isClientSide) {
            if (entity.canUsePortal(false)) entity.setAsInsidePortal(this, pos);
            return;
        }

        // 以下全部仅服务端处理
        // 仅玩家可传送（保持既有行为：物品/生物穿过传送门不传送）
        if (!(entity instanceof ServerPlayer player)) return;

        // 冷却检查：NBT 中存储冷却结束的游戏刻，当前游戏刻小于它则跳过
        long cooldownEnd = player.getPersistentData().getLong(COOLDOWN_KEY);
        if (cooldownEnd > level.getGameTime()) return;

        // 骑乘时先下车：原版 handlePortal 用 canUsePortal(false) 判定，仍骑乘的玩家永远不会被登记为可传送
        if (player.isPassenger()) player.stopRiding();

        // 原版范式：这里只登记「玩家在传送门内」。
        // 禁止在此处做任何跨维度同步区块加载（旧实现即在此 getChunk 导致主线程永久卡死）。
        entity.setAsInsidePortal(this, pos);

        // 下行方向：非阻塞地预热落点区块（只申领区域票据，不等待、不 join、不看返回值）
        if (!isDisasterDimension(level)) requestDestinationChunk(player, pos);
    }

    /**
     * 进入传送门后的倒计时长度（ticks）。
     * <p>
     * 取 {@code 0}：保留旧实现的「即时传送」手感——进入后同一/下一 tick 即尝试传送。
     * 落点区块未就绪时不需要固定延迟：由 {@link #getPortalDestination} 返回 {@code null}
     * 并清除原版传送冷却来实现「下一 tick 重试」，因此无需引入额外等待时间。
     */
    @Override
    public int getPortalTransitionTime(ServerLevel level, Entity entity) {
        return 0;
    }

    /**
     * 使用原版「视角扭曲」局部过渡（与下界传送门同款）。
     * <p>
     * 该值只对客户端有意义：{@code LocalPlayer} 每 tick 读 {@code portalProcess.getPortalLocalTransition()}，
     * 为 {@code CONFUSION} 时把 {@code spinningEffectIntensity} 渐增至 1 —— 由 {@code GameRenderer}
     * 旋转投影矩阵产生画面扭曲、{@code Gui} 叠加全屏传送门紫幕，并在刚进门时播放
     * {@code SoundEvents.PORTAL_TRIGGER} 环境音，离开后渐出；门内开着容器界面也会被自动关闭。
     * <p>
     * <b>前置条件</b>：客户端也必须登记 portalProcess（见 {@link #entityInside} 的客户端分支，
     * 与原版 {@code NetherPortalBlock} 同构），否则本覆写不会产生任何视觉效果。
     */
    @Override
    public Portal.Transition getLocalTransition() {
        return Portal.Transition.CONFUSION;
    }

    /**
     * 由 {@code PortalProcessor} 在原版流程中回调，返回本次传送的目标描述。
     * <p>
     * 返回 {@code null} 表示「本 tick 不传送」。下行落点区块未就绪时会返回 {@code null}，
     * 此时必须清除原版在询问落点前设置的传送冷却，否则
     * {@code setAsInsidePortal} 会在冷却期内每 tick 刷新冷却且不登记 {@code insidePortalThisTick}，
     * 玩家站在门里将<b>永远拿不到重试机会</b>。
     */
    @Nullable
    @Override
    public DimensionTransition getPortalDestination(ServerLevel level, Entity entity, BlockPos pos) {
        if (!(entity instanceof ServerPlayer player)) return null;
        // 门内已等待刻数：用于超时判定与日志（PortalProcessor 的公开状态，无需自建状态）
        int waitedTicks = player.portalProcess != null ? player.portalProcess.getPortalTime() : 0;
        return isDisasterDimension(level)
                ? createUpwardTransition(player, waitedTicks)
                : createDownwardTransition(player, pos, waitedTicks);
    }

    // ==================================================================
    // 内部实现
    // ==================================================================

    /** 当前维度是否为天灾维度（维度 ID 是编译期常量，不受配置控制）。 */
    private static boolean isDisasterDimension(Level level) {
        return level.dimension().location().toString().equals(DISASTER_DIM);
    }

    // ServerLevel 实现 AutoCloseable；此处仅作世界引用，不可关闭（close() 会关闭区块源），抑制 resource 检查
    @SuppressWarnings("resource")
    @Nullable
    private static ServerLevel disasterLevel(ServerPlayer player) {
        return player.server.getLevel(DISASTER_LEVEL);
    }

    /**
     * 下行落点区块的<b>非阻塞</b>预热。
     * <p>
     * 落点区块已在内存中则什么都不做；否则申领一张原版传送门区域票据（{@link TicketType#PORTAL}，
     * 寿命 300 tick、重复申领幂等并刷新寿命），由区块系统在后台完成生成/加载。
     * 本方法不等待、不 join、不检查返回值，绝不会阻塞主线程。
     */
    @SuppressWarnings("resource")
    private static void requestDestinationChunk(ServerPlayer player, BlockPos portalPos) {
        ServerLevel target = disasterLevel(player);
        if (target == null) return;

        int chunkX = Mth.floor(player.getX()) >> 4;
        int chunkZ = Mth.floor(player.getZ()) >> 4;
        // getChunkNow 只查内存/缓存，不触发加载（ServerChunkCache#getChunkNow 不阻塞）
        if (target.getChunkSource().getChunkNow(chunkX, chunkZ) != null) return;
        target.getChunkSource().addRegionTicket(TicketType.PORTAL,
                new ChunkPos(chunkX, chunkZ), DESTINATION_TICKET_RADIUS, portalPos);
    }

    /**
     * 下行：任意维度 → 天灾维度。X/Z 1:1 保留，Y = 目标点地表上方 1 格。
     */
    @SuppressWarnings("resource")
    @Nullable
    private static DimensionTransition createDownwardTransition(ServerPlayer player, BlockPos portalPos, int waitedTicks) {
        ServerLevel target = disasterLevel(player);
        if (target == null) {
            LOGGER.error("[BeLoongCore][DisasterPortal:error] 找不到维度 {}，无法下行传送（玩家 {}）",
                    DISASTER_DIM, player.getName().getString());
            return null;
        }

        int blockX = Mth.floor(player.getX());
        int blockZ = Mth.floor(player.getZ());
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;

        LevelChunk chunk = target.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            // 落点区块尚未就绪：有界等待 + 预热 + 下一 tick 重试（全程不阻塞主线程）
            if (waitedTicks > DESTINATION_WAIT_TIMEOUT_TICKS) {
                return failDestination(player, blockX, blockZ, waitedTicks);
            }
            target.getChunkSource().addRegionTicket(TicketType.PORTAL,
                    new ChunkPos(chunkX, chunkZ), DESTINATION_TICKET_RADIUS, portalPos);
            // 关键：清掉原版在询问落点前设置的传送冷却，否则拿不到重试机会（见 getPortalDestination 注释）
            player.setPortalCooldown(0);
            if (waitedTicks <= 1) {
                LOGGER.info("[BeLoongCore][DisasterPortal:wait] 落点区块 {} ({}, {}) 尚未就绪，已申领 ticket 预热，等待加载",
                        DISASTER_DIM, chunkX, chunkZ);
            } else {
                LOGGER.debug("[BeLoongCore][DisasterPortal:wait] 落点区块 {} ({}, {}) 仍未就绪（已等待 {} tick）",
                        DISASTER_DIM, chunkX, chunkZ, waitedTicks);
            }
            return null;
        }

        // 区块已在内存中：直读高度图，等价于 Level#getHeight(MOTION_BLOCKING, x, z)（Level 版本 = chunk 版本 + 1），
        // 但不会触发任何区块加载
        double targetY;
        if (blockX >= -30000000 && blockZ >= -30000000 && blockX < 30000000 && blockZ < 30000000) {
            int topY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX & 15, blockZ & 15) + 1;
            targetY = topY + 1.0D;   // 地表上方 1 格
        } else {
            // 与旧实现（Level#getHeight 越界分支返回 seaLevel + 1，再 +1）保持一致
            targetY = target.getSeaLevel() + 2.0D;
        }

        // X/Z 精确保留（1:1）；速度清零、朝向保留，等价旧 teleportTo(..., Set.of(), yRot, xRot)
        Vec3 destination = new Vec3(player.getX(), targetY, player.getZ());
        LOGGER.info("[BeLoongCore][DisasterPortal:teleport] {} 下行 {} -> {} ({}, {}, {}) waited={} tick",
                player.getName().getString(), player.level().dimension().location(), DISASTER_DIM,
                destination.x, destination.y, destination.z, waitedTicks);
        return new DimensionTransition(target, destination, Vec3.ZERO,
                player.getYRot(), player.getXRot(), postTransition());
    }

    /**
     * 落点区块超时未就绪：放弃本轮传送、报错并通知玩家（<b>有界失败</b>，绝不阻塞主线程）。
     * <p>
     * 会写入既有 NBT 冷却做限流；原版自身的 10 tick 传送冷却配合
     * {@code setAsInsidePortal} 的冷却刷新语义，会让本轮停止重试，直到玩家离开传送门。
     */
    private static DimensionTransition failDestination(ServerPlayer player, int blockX, int blockZ, int waitedTicks) {
        LOGGER.error("[BeLoongCore][DisasterPortal:timeout] 落点区块 {} ({}, {}) 等待 {} tick 仍未就绪，放弃本次传送（玩家 {}，目标 {}({}, {})）",
                DISASTER_DIM, blockX >> 4, blockZ >> 4, waitedTicks,
                player.getName().getString(), DISASTER_DIM, blockX, blockZ);
        player.sendSystemMessage(Component.translatable(
                "message.beloong.disaster_portal.destination_timeout", blockX, blockZ));
        player.getPersistentData().putLong(COOLDOWN_KEY,
                player.level().getGameTime() + Config.DisasterPortal.teleportCooldownTicks.get());
        return null;
    }

    /**
     * 上行：天灾维度 → 主世界。照搬原版末地返回逻辑——重生点（床/重生锚）→
     * 无重生点回退世界出生点 → 最终兜底 {@code (0, 64, 0)}；坐标中心对齐 +0.5。
     */
    // ServerLevel 实现 AutoCloseable；此处仅作世界引用，不可关闭（close() 会关闭区块源），抑制 resource 检查
    @SuppressWarnings("resource")
    @Nullable
    private static DimensionTransition createUpwardTransition(ServerPlayer player, int waitedTicks) {
        BlockPos respawnPos = player.getRespawnPosition();            // 床或重生锚位置
        ResourceKey<Level> respawnDim = player.getRespawnDimension(); // 重生维度
        ServerLevel overworld = player.server.getLevel(Level.OVERWORLD);

        // 如果玩家没有设置重生点（例如从未睡过觉），回退到世界出生点
        if (respawnPos == null || respawnDim == null) {
            respawnPos = overworld != null ? overworld.getSharedSpawnPos() : new BlockPos(0, 64, 0);
            respawnDim = Level.OVERWORLD;
        }

        ServerLevel target = player.server.getLevel(respawnDim);
        if (target == null) target = overworld;   // 回退到主世界
        if (target == null) {
            LOGGER.error("[BeLoongCore][DisasterPortal:error] 找不到重生维度 {}，无法上行传送（玩家 {}）",
                    respawnDim.location(), player.getName().getString());
            return null;
        }

        // 传送到重生点（中心对齐 +0.5），保留朝向；不做任何高度图查询（与原行为一致）
        Vec3 destination = new Vec3(respawnPos.getX() + 0.5, respawnPos.getY(), respawnPos.getZ() + 0.5);
        LOGGER.info("[BeLoongCore][DisasterPortal:teleport] {} 上行 {} -> {} ({}, {}, {}) waited={} tick",
                player.getName().getString(), DISASTER_DIM, target.dimension().location(),
                destination.x, destination.y, destination.z, waitedTicks);
        return new DimensionTransition(target, destination, Vec3.ZERO,
                player.getYRot(), player.getXRot(), postTransition());
    }

    /**
     * 传送完成后的收尾回调（跨维度与同维度路径都会触发）。
     * 保持既有行为：摔落距离清零；冷却写入玩家持久化 NBT（键名与语义不变）。
     */
    private static DimensionTransition.PostDimensionTransition postTransition() {
        return entity -> {
            entity.fallDistance = 0;   // 防止传送前的坠落伤害带到目标维度
            if (entity instanceof ServerPlayer player) {
                player.getPersistentData().putLong(COOLDOWN_KEY,
                        player.level().getGameTime() + Config.DisasterPortal.teleportCooldownTicks.get());
            }
        };
    }
}
