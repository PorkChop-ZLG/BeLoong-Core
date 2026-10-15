package com.zonlong.beloong.npcstory;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.dialogue.NpcDialogueStage;
import com.zonlong.beloong.cg.CgCompletionHandler;
import com.zonlong.beloong.cg.CgRegistry;
import com.zonlong.beloong.entity.NpcEntity;
import com.zonlong.beloong.teleport.DimensionTeleport;
import com.zonlong.beloong.teleport.TeleportTarget;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.portal.DimensionTransition;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * <b>序章</b>（2026-10-14）：把"开场"从环境副作用改造为受控流程。
 *
 * <h2>流程</h2>
 * <pre>
 * 玩家进入游戏 →（等 {@code npc_story.prologueDelaySeconds} 秒）→ **玩家本就在剧情声明的维度里**
 *   → 若不在起点附近 ⇒ 送回**同维度内的入场落点**（本 tick 结束，下一轮继续）
 *   → 同一 tick 确保锚点处分身存在 → 授予 {@code beloong:npc/root} → 播登场 CG（闸门：尚未获得 1_0）
 * </pre>
 * CG 播完由 CG 自己授予 {@code 1_0}（见 {@code CgCompletionHandler}）⇒ 第一段对话的窗口随即可用。
 *
 * <h2>★ 两条位置规则（2026-10-14 实机后两次改判的结果）</h2>
 * <ol>
 *   <li><b>绝不把玩家从别的维度拉回来</b>：不在剧情声明的维度 ⇒ <b>什么都不做</b>
 *       （不授予、不传送、不生成分身）。<b>剧情可跳过</b> —— 离开龙宫等于跳过；宽限期内短暂离开不受影响；
 *       超过宽限由维度规则清理（删分身 + 撤整条链，含 {@code root}/{@code 1_0}）；
 *       玩家**自己走回来**才会重新开场（CG 重播 —— 设计 D1 的既有语义）。
 *       ⚠️ 结构上保证：目标维度取 {@code player.serverLevel()}（玩家当前所在维度），
 *       全程**不出现**跨维度查找 ⇒ 不可能把别的维度的玩家拉过来。</li>
 *   <li><b>但同维度内必须把玩家送回起点</b>：剧情被重置后玩家可能停在半路（例如化龙池），
 *       而新分身生成在**锚点**处、CG 的机位也是围着它算的 ⇒ 不送回起点就会出现
 *       "人在半路看锚点的过场""CG 结束够不到分身"✗ ⇒ 故从头开场时先送回**入场落点**。</li>
 * </ol>
 * ⚠️ 落点**不是锚点本身**：CG 有一条预检"观察者水平方向压在目标头顶 ⇒ 放弃"
 * （{@code CgAnimation} 的 ①）⇒ 传送到锚点上会让登场 CG 直接不播。
 *
 * <h2>为什么"先分身、后授予"</h2>
 * 对账器把"已获得 {@code root} 却没有分身"判为"剧情该重新开始"并**撤回整条链**
 * （该分支已于 2026-10-14 重写时删除，见重写设计 D2）⇒ 若先授予、分身还没建出来，
 * 就会出现**授予/撤回抖动**（状态乱跳 + 日志刷屏）。故两件事必须在同一 tick 内、按此顺序完成。
 *
 * <h2>性能（2026-10-14）</h2>
 * 本处理器挂在 {@code ServerTickEvent.Post}（每 tick 都触发）上，但：
 * <ul>
 *   <li><b>每 20 tick（1 秒）才真正扫一次</b> —— 配置项是**秒**级，±1 秒不可感知，开销直接 ÷20；</li>
 *   <li>判据**先便宜后贵**：状态（字段读）⇒ <b>维度</b>（一次比较）⇒ 起点距离 ⇒ 进度（Map 查）。
 *       "不在剧情维度"的玩家（绝大多数时间）只付一次比较就返回；</li>
 *   <li>每玩家每 tick 的期望成本≈0，远低于对账器（每 100 tick 一次 {@code getAllEntities()}）。</li>
 * </ul>
 *
 * <h2>服务器关停的兜底</h2>
 * 若在"已授予 {@code root}、{@code 1_0} 的延迟授予项还没到点"时关服，待授予项会随内存丢失 ⇒
 * 下次登录会出现"有 {@code root}、却没有 {@code 1_0}"（第一段对话打不开）⇒ 本类在
 * **玩家位于剧情声明的维度里**时按"有 root 却没有 1_0"**补授 {@code 1_0}**
 * （视作那次登场已经发生过；用户裁定：只要 CG 开始过就一定拿到）。
 */
public class NpcPrologueHandler {

    /** 剧情的起点进度：由本类授予（{@code root.json} 的触发已改为 {@code minecraft:impossible}）。 */
    private static final ResourceLocation ROOT = ResourceLocation.parse("beloong:npc/root");

    /** 登场 CG 已放完的标志：有它就不再开场、也不再播 CG。 */
    private static final ResourceLocation ENTRANCE_DONE = ResourceLocation.parse("beloong:npc/1_0");

    /** 巡检间隔（tick）。20 tick = 1 秒，见类注释"性能"一节。 */
    private static final int SCAN_INTERVAL_TICKS = 20;

    /** 距入场落点这么近（XZ，格）就认为"已经在起点"，不再传送（避免传送循环）。 */
    private static final double ALREADY_AT_START_RADIUS = 4.0D;

    private int tickCounter;

    /**
     * 客户端"已就绪"的时刻（gameTime）；**缺失 = 还没收到信号**。
     * <p>
     * ⚠️ 只在内存：它是"本次会话客户端什么时候能看见世界"的瞬时事实，不落盘、登出即清
     * （见 {@link #onPlayerLoggedOut}）。用途见 {@code NpcClientReadyPayload} 的类注释。
     */
    private static final Map<UUID, Long> CLIENT_READY_AT = new HashMap<>();

    /**
     * 客户端上报"我已真正进入游戏"（载荷入口，见 {@link NpcClientReadyPayload}）。
     * <p>
     * 重复上报忽略（{@code putIfAbsent} 保留**第一次**）：重新追踪、换维度都不该把就绪时刻往后推。
     */
    public static void markClientReady(ServerPlayer player) {
        Long previous = CLIENT_READY_AT.putIfAbsent(player.getUUID(), player.serverLevel().getGameTime());
        if (previous == null) {
            BeLoongCore.LOGGER.info(
                    "[BeLoong] npc prologue: client ready for player '{}' at gameTime {}",
                    player.getGameProfile().getName(), CLIENT_READY_AT.get(player.getUUID()));
        }
    }

    /**
     * 收不到就绪信号时的兜底宽限（tick）—— 取自 SERVER 配置 {@code readyFallbackGraceSeconds}（缺省 60 秒）。
     * <p>
     * ⚠️ 必须有：老客户端 / 模组冲突 / 被改的客户端都可能永远不报 ⇒ 没有兜底玩家就**永远没有剧情**。
     * 到点照旧开场，并留一条 WARN。**只有收到就绪信号时**，等待才是纯粹的
     * {@code prologueDelaySeconds}（这是正常路径）。
     */
    private static int readyFallbackGraceTicks() {
        return Math.max(1, Config.NpcStory.readyFallbackGraceSeconds.get()) * 20;
    }

    /** 已经为"没收到就绪信号"报过 WARN 的玩家（每人一条，避免刷屏）。 */
    private static final Set<UUID> WARNED_READY_FALLBACK = new HashSet<>();

    /** 该玩家是否已上报就绪（未上报时 {@code null}）。 */
    static Long clientReadyAt(ServerPlayer player) {
        return CLIENT_READY_AT.get(player.getUUID());
    }

    /** 登出即清：就绪是"本次会话"的事实，重登必须重新上报。 */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        CLIENT_READY_AT.remove(event.getEntity().getUUID());
        WARNED_READY_FALLBACK.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!Config.NpcStory.enabled.get()) {
            return;
        }
        if (++this.tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        this.tickCounter = 0;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            try {
                this.check(player);
            } catch (Throwable t) {
                BeLoongCore.LOGGER.error(
                        "[BeLoong] npc prologue: failed to check player '{}': {}",
                        player.getGameProfile().getName(), t.toString());
            }
        }
    }

    private void check(ServerPlayer player) {
        // ① 最便宜的一批：状态不合适就什么都不做（下一轮再看）
        if (player.isDeadOrDying() || player.isSpectator() || player.isPassenger()) {
            return;
        }
        // ★ 方案"乙"的消费点（用户 2026-10-14 裁定）：主人离线期间到期、分身已被删掉的那些，
        //   在这里补做"撤回进度"。**必须在任何"开场"动作之前**（否则会先授予 root 再撤，白折腾一轮）。
        //   消费口径：再判一次"该剧情已开始 ∧ 未通关"才撤 —— 通关玩家的进度永不被误撤。
        if (PendingStoryRevoke.consume(player.server, player.getUUID())) {
            for (Map.Entry<EntityType<?>, NpcStory> entry : NpcStoryLoader.INSTANCE.all().entrySet()) {
                NpcStory story = entry.getValue();
                if (isEarned(player, story.startAdvancement()) && !isEarned(player, story.endAdvancement())) {
                    NpcStoryHandler.revokeStoryFor(player, story,
                            "the double expired while the player was offline (server-side pending revoke)");
                }
            }
        }

        int delayTicks = Math.max(0, Config.NpcStory.prologueDelaySeconds.get()) * 20;

        for (Map.Entry<EntityType<?>, NpcStory> entry : NpcStoryLoader.INSTANCE.all().entrySet()) {
            NpcStory story = entry.getValue();
            ResourceLocation host = story.requiredDimension().orElse(null);

            // ② 关键闸门（先于任何进度查询）：玩家必须**本就在**该剧情声明的维度里。
            //    不在这里 ⇒ 什么都不做 —— 不授予、**不传送**、不生成分身。剧情可跳过，见类注释第 1 条。
            if (host == null || !player.level().dimension().location().equals(host)) {
                continue;
            }

            // ③ ⚠️ fail-closed（与对账器一致）：这条剧情引用的进度 id 不存在 ⇒ 它是 dead on arrival ⇒
            //    既不生成分身也不播 CG。否则坏数据下这里每 20 tick 就会造一只实体并重播一次 CG
            //    （root 与 1_0 同时缺失时是**无限循环**）—— 审查 I5。
            if (player.server.getAdvancements().get(ROOT) == null
                    || player.server.getAdvancements().get(ENTRANCE_DONE) == null) {
                continue;
            }

            // ④ 已经**完整**开场过（root 与 1_0 都在）⇒ 这一轮序章结束。
            //    ⚠️ 只认"1_0 在"是不够的：手动只撤 root（或撤到一半）会留下"有 1_0、无 root"，
            //    那时必须让它**从头开场**，否则玩家停在"无 root、无分身、锚点又不显示"的状态里永不自愈
            //    （审查 I6；同理也兜住了"租约比登场 CG 还短"这种极端配置 —— 审查 S6）。
            if (isEarned(player, ENTRANCE_DONE) && isEarned(player, ROOT)) {
                return;
            }

            // ⑤ 等客户端**真正进入游戏**（F1，2026-10-14）。
            //    判据不再用 tickCount（它从"服务端收下玩家"起算，远程玩家那几秒全耗在加载地形上），
            //    而是用客户端上报的就绪时刻（见 NpcClientReadyPayload）。
            Long readyAt = clientReadyAt(player);
            if (readyAt == null) {
                // ⚠️ 兜底：一直收不到就绪信号也要开场，否则玩家永远没有剧情。
                if (player.tickCount < delayTicks + readyFallbackGraceTicks()) {
                    return;
                }
                if (WARNED_READY_FALLBACK.add(player.getUUID())) {
                    BeLoongCore.LOGGER.warn(
                            "[BeLoong] npc prologue: player '{}' never reported the client-ready signal"
                                    + " after {} ticks — starting the prologue anyway (fallback;"
                                    + " an old/modified client or a mod conflict can cause this)",
                            player.getGameProfile().getName(), player.tickCount);
                }
            } else if (player.serverLevel().getGameTime() - readyAt < delayTicks) {
                return;
            }

            // ⑥ 有 root 却没有 1_0 ⇒ 上次那条"CG 播完授予 1_0"没走完（多半是关服丢了待授予项）。
            //    ⚠️ 用户裁定：补授**也必须在剧情声明的维度里**（与"离开即跳过"一致）。
            if (isEarned(player, ROOT)) {
                // ⚠️ 若这条 1_0 的"完成结算"还挂在待授予表里，说明**登场 CG 正在播**（正常播完会按时长授予）
                // ⇒ 这一刻绝不能补授，否则 CG 才开一秒 1_0 就到手了（实机日志 5 次）。
                // 本分支只该救"CG 被服务器关停打断"的情形。
                if (CgCompletionHandler.hasPending(player, ENTRANCE_DONE)) {
                    return;
                }
                award(player, ENTRANCE_DONE);
                BeLoongCore.LOGGER.info(
                        "[BeLoong] npc prologue: player '{}' had root but not 1_0 while in {} — granted 1_0 "
                                + "(the entrance CG must have been interrupted; the story can continue)",
                        player.getGameProfile().getName(), host);
                return;
            }

            // ⑦ 从头开场的**清场**：走到这里说明玩家**没有起点进度**（含补授分支在内的上面几条都已排除），
            //    因此任何 owner == 我、类型就是本剧情的分身**必然是上一轮留下的孤儿**（撤进度后没被删掉）。
            //    必须先删掉它 —— 否则下面的 ensureDoubleFor 会**复用**它，于是"玩家被送回原点、CG 却拍在
            //    留在半路的那只分身身上"⇒ 不同步（实机 BUG 的第二种形态）。这个竞态是真实存在的：
            //    序章 20 tick 一次、对账器 100 tick 一次 ⇒ 序章可能抢在对账器的孤儿清理之前。
            discardOrphanDoubles(player, entry.getKey());
            // ⑧ ★ 从头开场 ⇒ 先把玩家送回**同维度内的入场落点**（类注释第 2 条）。
            //    目标维度取 player.serverLevel()（玩家当前维度）⇒ 结构上不可能跨维度拉人。
            TeleportTarget landing = TeleportTarget.toLoongPalace(player.serverLevel());
            if (!isAtStart(player, landing)) {
                if (teleportTo(player, landing)) {
                    BeLoongCore.LOGGER.info(
                            "[BeLoong] npc prologue: moved player '{}' to the story start in {} "
                                    + "(so the story restarts from the beginning)",
                            player.getGameProfile().getName(), host);
                }
                // 本 tick 到此为止：位置要等下一轮扫描时才是新的（那时 isAtStart 为真，继续往下走）
                return;
            }

            // ⑨ 同一 tick：先确保分身，成功了才授予 root（顺序不能反，见类注释）
            NpcEntity npc = NpcStoryHandler.ensureDoubleFor(player, entry.getKey(), story);
            if (npc == null) {
                BeLoongCore.LOGGER.error(
                        "[BeLoong] npc prologue: could not create the double of '{}' for player '{}' — "
                                + "root was NOT granted (the flow stays 'not started' rather than flapping)",
                        entry.getKey(), player.getGameProfile().getName());
                continue;
            }

            award(player, ROOT);
            BeLoongCore.LOGGER.info(
                    "[BeLoong] npc prologue: granted root to '{}' and ensured their double of '{}' at {}",
                    player.getGameProfile().getName(), entry.getKey(), npc.blockPosition());

            // ⑩ 播登场 CG 前**钉朝向**（用户 2026-10-14 要求）：这条路径可能没走传送
            //    （玩家本来就站在落点上）⇒ 这里补一次，保证任何路径下 CG 的机位都一致。
            faceNorth(player);
            // ⑩ 播登场 CG（闸门：此刻必然还没有 1_0 —— ④ 已经挡掉了"完整开场过"的情况）
            story.cg().ifPresent(name -> CgRegistry.byName(name).ifPresentOrElse(
                    cg -> cg.play(player, npc),
                    () -> BeLoongCore.LOGGER.warn(
                            "[BeLoong] npc prologue: story for '{}' names unknown cg '{}' — the double exists, "
                                    + "but no entrance cg was played", entry.getKey(), name)));
            return;
        }
    }

    /**
     * 删掉"属于该玩家、且类型就是本剧情"的分身（从头开场前的清场，见调用点注释）。
     * <p>
     * ⚠️ 判据是"玩家**没有**起点进度"（调用点已保证）⇒ 存在的必然是孤儿 ⇒ 这里只是"删"、
     * 不涉及撤回进度 ⇒ 与"撤与删必须成对"不冲突（撤回只发生在租约到期那条路径里）。
     * <p>
     * ⚠️ 先**快照**再删：直接边遍历 {@code getAllEntities()} 边 {@code discard()} 会踩
     * ConcurrentModificationException（对账器里同样的坑有注释记着）。
     */
    private static void discardOrphanDoubles(ServerPlayer player, EntityType<?> type) {
        List<NpcEntity> orphans = new ArrayList<>();
        // 性能 S1（2026-10-14）：走"已加载 NPC 索引"，不再遍历该维度全部已加载实体。
        // 语义等价：索引与 getAllEntities() 一样只含已加载实体（卸载中的孤儿本来也删不到，不必回退）。
        for (NpcEntity npc : LoadedNpcIndex.loadedIn(player.serverLevel())) {
            if (npc.getType() == type && player.getUUID().equals(npc.owner())) {
                orphans.add(npc);
            }
        }
        for (NpcEntity orphan : orphans) {
            orphan.discard();
            BeLoongCore.LOGGER.info(
                    "[BeLoong] npc prologue: discarded a leftover double of '{}' for player '{}'"
                            + " (leftover of a revoked story — a fresh one will be created at the anchor)",
                    type, player.getGameProfile().getName());
        }
    }

    /** 玩家是否已经在入场落点附近（只比 XZ —— Y 由落点自己解析，比较它没有意义）。 */
    private static boolean isAtStart(ServerPlayer player, TeleportTarget landing) {
        if (!(landing instanceof TeleportTarget.At at)) {
            return false;       // 非坐标型落点（理论上到不了这里）⇒ 一律走传送分支
        }
        double dx = player.getX() - at.x();
        double dz = player.getZ() - at.z();
        return dx * dx + dz * dz <= ALREADY_AT_START_RADIUS * ALREADY_AT_START_RADIUS;
    }

    /**
     * 把玩家强制转向<b>正北</b>（=-Z ⇒ yaw 180）。
     * <p>
     * 为什么需要：登场 CG 的机位是"<b>从玩家指向分身</b>"算出来的（{@code CgContext.of} 的 anchorToViewer），
     * 玩家朝向不定 ⇒ 同一段 CG 每次的机位都不同（实机日志里正常那几次恒为 {@code (0,0,1)}，
     * 即玩家在分身南侧朝北看 —— 正是这里要钉住的姿态）。
     * <p>
     * ⚠️ <b>只调 {@code setYRot} 是不够的</b>：玩家自身的朝向在客户端属于<b>本地权威</b>，
     * 服务端改字段客户端不会照做 ⇒ 必须用 {@code connection.teleport(...)} 发一次位置包把朝向带过去。
     * 坐标原样传（不移动玩家，只钉朝向）。
     */
    private static void faceNorth(ServerPlayer player) {
        player.setYRot(180.0F);
        player.setXRot(0.0F);
        player.setYHeadRot(180.0F);
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), 180.0F, 0.0F);
    }

    /**
     * 用 {@code teleport} 包把玩家送到落点（{@code docs/龙宫维度总设计.md} 的硬约束：跨维度传送必须走它；
     * 这里虽然同维度，也复用同一套以避免第二份实现）。
     * <p>
     * API 契约：{@code null} = 本 tick 不传送（调用方下轮再试，本类天然如此）。
     */
    private static boolean teleportTo(ServerPlayer player, TeleportTarget landing) {
        DimensionTransition transition = DimensionTeleport.toTarget(
                player, landing, entity -> entity.fallDistance = 0.0F);
        if (transition == null) {
            return false;
        }
        player.changeDimension(transition);
        // 落地即朝正北（用户 2026-10-14 要求）：CG 的机位由"玩家→分身"的方向决定，
        // 这里先钉住姿态，免得落点与开场之间隔着一轮巡检时朝向还是旧的。
        faceNorth(player);
        return true;
    }

    private static boolean isEarned(ServerPlayer player, ResourceLocation id) {
        AdvancementHolder holder = player.server.getAdvancements().get(id);
        return holder != null && player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    private static void award(ServerPlayer player, ResourceLocation id) {
        AdvancementHolder holder = player.server.getAdvancements().get(id);
        if (holder == null) {
            BeLoongCore.LOGGER.error("[BeLoong] npc prologue: advancement '{}' does not exist", id);
            return;
        }
        for (String criterion : holder.value().criteria().keySet()) {
            player.getAdvancements().award(holder, criterion);
        }
        // 性能 S2：我们自己发放的进度必须让状态缓存立刻失效，否则下一轮扫到的是过期值
        //（那会导致序章重复清场、重复传送）。
        NpcDialogueStage.invalidate(player);
    }
}
