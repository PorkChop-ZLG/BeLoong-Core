package com.zonlong.beloong.block;

import com.mojang.logging.LogUtils;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.teleport.CoordinateLanding;
import com.zonlong.beloong.teleport.DimensionTeleport;
import com.zonlong.beloong.teleport.TeleportCooldown;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
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
 *   <li>紧接着（下一 tick）原版 {@code Entity.handlePortal()} 回调 {@link #getPortalDestination}——
 *       倒计时取 {@value #PORTAL_TRANSITION_TICKS}（即"接触即开始"，见 {@link #getPortalTransitionTime}）；</li>
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
 * 双向行为（两端都是 <b>1:1 坐标 + 高度图落点</b>，见 {@code teleport/DimensionTeleport}）：
 * <ul>
 *   <li><b>下行（任意非天灾维度 → 天灾维度）</b>：1:1 坐标（与维度定义 {@code coordinate_scale: 1.0} 一致；
 *       <b>本类自行保留 X/Z，不读该字段</b>），Y 取目标点 MOTION_BLOCKING 高度 + 1 格；</li>
 *   <li><b>上行（天灾维度 → 主世界）</b>：同样 1:1 坐标 + 高度图。
 *       <b>不回玩家重生点、不回世界出生点，也不生成任何返回门</b>；
 *       与下行的唯一差别是"等满上限仍拿不到高度图时用玩家当前 Y 兜底"（下行则报错放弃）。</li>
 * </ul>
 * 方向只由"当前维度是否是天灾维度"决定，目标维度是编译期常量，无配置项。
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

    /**
     * 进门倒计时长度（ticks），取 <b>0</b> = 接触即开始传送尝试。
     * <p>
     * <b>为什么是 0（2026-09-13 实机回归后的结论）</b>：本维度首次生成地形很慢（实测 122–130 tick，
     * 约 6–6.5 s），因此<b>响应速度优先</b>——玩家一碰到门就开始「非阻塞探测 + 票据预热 + 重试」，
     * 落点一就绪立刻传送；此时落点票据也会从进门第一 tick 起被 {@link #entityInside} 持续刷新，
     * 生成越早开始越好。
     * <p>
     * 曾一度取 80（= 原版下界门默认 {@code playersNetherPortalDefaultDelay}），目的是让客户端
     * {@code spinningEffectIntensity}（每 tick +0.0125，{@code LocalPlayer.java:925}）在换维度前涨满，
     * 但实机证明代价不可接受：<b>落点已就绪时也要白等 81 tick（≈4 s）</b>
     * （日志：`debug-2.log` 连续 4 次 `waited=81 tick`），而落点未就绪时它又并不能让传送提前。
     * <p>
     * 取舍：回到 0 后，落点<b>立即就绪</b>时玩家几乎看不到扭曲过渡（加载屏会马上接管）；
     * 但只要落点未就绪（本维度的常态），玩家会在门里停留足够久，扭曲仍会涨满——
     * 即"扭曲可见性"交给了慢路径，"响应速度"得到了保证。
     */
    public static final int PORTAL_TRANSITION_TICKS = 0;

    /**
     * 落点区块等待上限（ticks），与 {@link #PORTAL_TRANSITION_TICKS} 一样<b>自进门起算</b>
     * （{@code waitedTicks} 取自 {@code portalProcess.getPortalTime()}，该计数器从进门第一 tick 开始累加，
     * 与倒计时长度无关）。
     * <p>
     * 超过该时长仍未就绪即放弃本轮传送并报错。取 <b>600</b>（≈30 s）的依据：本维度地形生成实测
     * 122–130 tick（约 6–6.5 s，见 `run/logs/debug-4.log` 的 `waited=122`、`latest.log` 的 `waited=130`），
     * 原值 200 只剩约 1.5 倍余量，稍慢一次即失败；600 给出约 4.6 倍余量。
     * <p>
     * <b>注意</b>：单轮等待有界，且失败后会<b>在 NBT 冷却期内停止重试</b>
     * （见 {@link #failDestination} 的说明）——"有界失败"指的是不会永久阻塞主线程。
     * <p>
     * <b>本上限现为双向共用</b>（原为仅下行；上行改为 1:1 坐标后同样需要等落点区块）。
     */
    private static final int DESTINATION_WAIT_TIMEOUT_TICKS = 600;

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

        // 冷却检查：与龙宫传送共用同一份 NBT 冷却（见 TeleportCooldown）。
        // 同时把原版冷却顶住——否则 NBT 冷却一到期就会立刻重新登记并重试。
        if (TeleportCooldown.isOnCooldown(player)) {
            // 原版玩家冷却只有 10 tick（Player#getDimensionChangingDelay 覆写），会在 NBT 冷却走完前归零；
            // 而本分支不调用 setAsInsidePortal，原版冷却得不到刷新。这里显式顶住，
            // 使 NBT 到期那一 tick setAsInsidePortal 仍被 isOnPortalCooldown() 拦住（只刷新、不登记），
            // 从而达到 failDestination javadoc 承诺的语义：在 NBT 冷却期内不再重试。
            TeleportCooldown.refreshVanillaToOutlastNbt(player, level);
            return;
        }

        // 骑乘时先下车。这是原版范式的必然要求：handlePortal 用 canUsePortal(false) 作闸门
        // （Entity#canUsePortal 要求 !isPassenger()），玩家仍在骑乘时根本不会调用 getPortalDestination，
        // 因此无法"等确定能传送了再下车"。副作用：本轮传送若失败，玩家已经被放下坐骑（坐骑留在原地）。
        if (player.isPassenger()) player.stopRiding();

        // 原版范式：这里只登记「玩家在传送门内」。
        // 禁止在此处做任何跨维度同步区块加载（旧实现即在此 getChunk 导致主线程永久卡死）。
        entity.setAsInsidePortal(this, pos);

        // 双向都做非阻塞预热：只申领区域票据，不等待、不 join、不看返回值
        ServerLevel target = isDisasterDimension(level)
                ? player.server.getLevel(Level.OVERWORLD)
                : player.server.getLevel(DISASTER_LEVEL);
        if (target != null) {
            CoordinateLanding.requestChunk(target, Mth.floor(player.getX()), Mth.floor(player.getZ()), pos);
        }
    }

    /**
     * 进门倒计时长度（ticks）：{@value #PORTAL_TRANSITION_TICKS}（0 ⇒ 接触即尝试传送）。
     * <p>
     * 判定在 {@code PortalProcessor.processPortalTeleportation}（{@code portalTime++ >= transitionTime}）：
     * 取 0 时进门第 1 tick 就成立，落点一就绪立刻换维度；本来就没就绪时不会因此损失任何等待时间
     * （等待上限自进门起算，见 {@link #DESTINATION_WAIT_TIMEOUT_TICKS}）。
     * <p>
     * 这里<b>不</b>用原版下界门的 80 tick：本维度首次生成地形慢（6–6.5 s），那 4 秒死等待
     * 在落点已就绪时也会发生，实机不可接受（详见 {@link #PORTAL_TRANSITION_TICKS}）。
     */
    @Override
    public int getPortalTransitionTime(ServerLevel level, Entity entity) {
        return PORTAL_TRANSITION_TICKS;
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
     * 返回 {@code null} 表示「本 tick 不传送」，由本类决定下一 tick 重试还是放弃：
     * <ol>
     *   <li>落点未就绪（区块不在内存，或该列为虚空）→ 见 {@link #waitForDestination}；</li>
     *   <li>已等满 {@link #DESTINATION_WAIT_TIMEOUT_TICKS} → 见 {@link #failDestination}。</li>
     * </ol>
     * 返回 {@code null} 前<b>必须</b>清除原版在询问落点前设置的传送冷却，否则
     * {@code setAsInsidePortal} 会在冷却期内每 tick 刷新冷却且不登记 {@code insidePortalThisTick}，
     * 玩家站在门里将<b>永远拿不到重试机会</b>。
     * <p>
     * 落点解析与 transition 构造全部委托给 {@code teleport/DimensionTeleport}；
     * 本类只负责方向、登记、冷却、超时与提示。
     */
    @Nullable
    @Override
    public DimensionTransition getPortalDestination(ServerLevel level, Entity entity, BlockPos pos) {
        if (!(entity instanceof ServerPlayer player)) return null;
        // 门内已等待刻数：用于超时判定与日志（PortalProcessor 的公开状态，无需自建状态）
        int waitedTicks = player.portalProcess != null ? player.portalProcess.getPortalTime() : 0;

        // 方向与目标维度：门是「非天灾 ⇄ 天灾」的双向通道（维度 ID 是编译期常量，不受配置控制）。
        // 上行目标是主世界（不是"上一个维度"、也不是玩家重生点）。
        boolean upward = isDisasterDimension(level);
        ServerLevel target = upward
                ? player.server.getLevel(Level.OVERWORLD)
                : player.server.getLevel(DISASTER_LEVEL);
        String direction = upward ? "upward" : "downward";

        int blockX = Mth.floor(player.getX());
        int blockZ = Mth.floor(player.getZ());

        if (target == null) {
            // 目标维度不在（本模组里不可能发生：天灾随数据包强绑、主世界必然存在）。
            // 仍走统一的"等待 → 超时"路径：不申领票据，只在进门首轮记一条 ERROR 免得刷屏。
            if (waitedTicks <= PORTAL_TRANSITION_TICKS + 1) {
                LOGGER.error("[BeLoongCore][DisasterPortal:error] target dimension not found,"
                                + " cannot teleport {} (player {})",
                        direction, player.getName().getString());
            }
            return waitedTicks > DESTINATION_WAIT_TIMEOUT_TICKS
                    ? failDestination(player, blockX, blockZ, waitedTicks, direction)
                    : null;
        }

        // 兜底 Y：下行不用兜底（保持既有"必须拿到高度图"的行为）；上行用玩家当前 Y
        // （即传送所依据的那个 Y；等满上限仍拿不到高度图时直接用它过去，用户已确认接受）。
        @Nullable Double fallbackY = upward ? player.getY() : null;
        DimensionTransition transition = DimensionTeleport.toCurrentCoords(player, target, fallbackY,
                postTransition());
        if (transition != null) {
            LOGGER.info("[BeLoongCore][DisasterPortal:teleport] {} {} {} -> {} ({}, {}, {}) waited={} ticks",
                    player.getName().getString(), direction, player.level().dimension().location(),
                    target.dimension().location(), transition.pos().x, transition.pos().y, transition.pos().z,
                    waitedTicks);
            return transition;
        }

        if (waitedTicks > DESTINATION_WAIT_TIMEOUT_TICKS) {
            return failDestination(player, blockX, blockZ, waitedTicks, direction);
        }
        return waitForDestination(player, target, blockX, blockZ, pos, waitedTicks, direction);
    }

    /**
     * 落点尚未就绪：刷新票据 + 清掉原版在询问落点前设置的冷却 + 返回 {@code null}（下一 tick 重试）。
     * <p>
     * <b>为什么必须清冷却</b>：原版 {@code handlePortal} 先设传送冷却再问落点，而
     * {@code setAsInsidePortal} 在冷却期只刷新冷却、不登记 {@code insidePortalThisTick}——
     * 不清掉就永远拿不到重试机会。
     *
     * @return 恒为 {@code null}（便于在 {@code getPortalDestination} 里直接 return）
     */
    @Nullable
    private static DimensionTransition waitForDestination(ServerPlayer player, ServerLevel target,
                                                          int blockX, int blockZ, BlockPos portalPos,
                                                          int waitedTicks, String direction) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        CoordinateLanding.requestChunk(target, blockX, blockZ, portalPos);
        player.setPortalCooldown(0);
        // "首轮"判定＝进门后第一次询问落点（第 PORTAL_TRANSITION_TICKS + 1 tick）；余下走 debug 免得刷屏
        if (waitedTicks <= PORTAL_TRANSITION_TICKS + 1) {
            LOGGER.info("[BeLoongCore][DisasterPortal:wait] destination chunk {} ({}, {}) not ready yet,"
                            + " PORTAL ticket claimed, waiting for load",
                    target.dimension().location(), chunkX, chunkZ);
        } else {
            LOGGER.debug("[BeLoongCore][DisasterPortal:wait] destination chunk {} ({}, {}) still not ready"
                            + " in direction {} (waited {} ticks)",
                    target.dimension().location(), chunkX, chunkZ, direction, waitedTicks);
        }
        return null;
    }

    // ==================================================================
    // 内部实现
    // ==================================================================

    /** 当前维度是否为天灾维度（维度 ID 是编译期常量，不受配置控制）。 */
    private static boolean isDisasterDimension(Level level) {
        return level.dimension().location().toString().equals(DISASTER_DIM);
    }

    /**
     * 落点区块超时未就绪：放弃本轮传送、报错并通知玩家（绝不阻塞主线程）。
     * <p>
     * 会写入 NBT 冷却（{@link TeleportCooldown#TICKS} tick，硬编码）做限流，
     * 并在该冷却期内由 {@link #entityInside} 把原版冷却顶住不归零，因此真实语义是
     * <b>"在 NBT 冷却期内不再重试"</b>——玩家若一直站在门里，每约（冷却 + 等待上限）tick 会再失败一次；
     * 离开传送门后约 10 tick 即可重新进门再试。"有界失败"指的是不会永久阻塞主线程。
     * <p>
     * 为什么必须顶住：原版<b>玩家</b>冷却只有 10 tick（{@code Player#getDimensionChangingDelay} 覆写
     * {@code Entity} 的 300），远短于 NBT 冷却。若不管它，NBT 一到期 {@code setAsInsidePortal} 就会
     * 重新登记 → 玩家站在门里时会无界重试（每轮 = 等待至多 {@link #DESTINATION_WAIT_TIMEOUT_TICKS} tick
     * + NBT 冷却），每轮都刷一次 error 日志与玩家消息。
     * <p>
     * 另注：客户端在这段时间里仍会显示完整的 CONFUSION 扭曲——原版冷却只在换维度时随
     * {@code ClientboundRespawnPacket} 同步给客户端，而"不传送"没有这条包。
     *
     * @param direction 日志用的方向词（{@code "upward"} / {@code "downward"}）；玩家提示文案不含方向，故不变
     */
    private static DimensionTransition failDestination(ServerPlayer player, int blockX, int blockZ,
                                                       int waitedTicks, String direction) {
        LOGGER.error("[BeLoongCore][DisasterPortal:timeout] destination chunk {} ({}, {}) still not ready after"
                        + " {} ticks, giving up this attempt (player {}, direction {}, at {}({}, {}))",
                DISASTER_DIM, blockX >> 4, blockZ >> 4, waitedTicks,
                player.getName().getString(), direction, DISASTER_DIM, blockX, blockZ);
        // 提示文案说的是"目标区块"，因此必须传区块坐标（blockX/Z 是方块坐标，>> 4 才是区块）
        player.sendSystemMessage(Component.translatable(
                "message.beloong.disaster_portal.destination_timeout", blockX >> 4, blockZ >> 4));
        TeleportCooldown.mark(player);
        return null;
    }

    /**
     * 传送完成后的收尾回调（跨维度与同维度路径都会触发）。
     * 保持既有行为：摔落距离清零；冷却写入玩家持久化 NBT（键名与语义不变）。
     */
    private static DimensionTransition.PostDimensionTransition postTransition() {
        return entity -> {
            entity.fallDistance = 0;   // 防止传送前的坠落伤害带到目标维度
            if (entity instanceof ServerPlayer player) {
                TeleportCooldown.mark(player);
            }
        };
    }
}
