package com.zonlong.beloong.npcstory;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.cg.CgRegistry;
import com.zonlong.beloong.entity.NpcEntity;
import com.zonlong.beloong.teleport.DimensionTeleport;
import com.zonlong.beloong.teleport.TeleportTarget;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.portal.DimensionTransition;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;

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
 * （{@code NpcStoryHandler#reconcilePlayer} 的 D21 分支）⇒ 若先授予、分身还没建出来，
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
        int delayTicks = Math.max(0, Config.NpcStory.prologueDelaySeconds.get()) * 20;

        for (Map.Entry<EntityType<?>, NpcStory> entry : NpcStoryLoader.INSTANCE.all().entrySet()) {
            NpcStory story = entry.getValue();
            ResourceLocation host = story.requiredDimension().orElse(null);

            // ② 关键闸门（先于任何进度查询）：玩家必须**本就在**该剧情声明的维度里。
            //    不在这里 ⇒ 什么都不做 —— 不授予、**不传送**、不生成分身。剧情可跳过，见类注释第 1 条。
            if (host == null || !player.level().dimension().location().equals(host)) {
                continue;
            }

            // ③ 已经看过登场 ⇒ 这一轮序章结束（剧情被完整重置会把 root/1_0 一起撤掉 ⇒ 到时自然会重新开场）
            if (isEarned(player, ENTRANCE_DONE)) {
                return;
            }

            // ④ 新人先等（让客户端把世界加载完）；判据是玩家自身的 tickCount
            if (player.tickCount < delayTicks) {
                return;
            }

            // ⑤ 有 root 却没有 1_0 ⇒ 上次那条"CG 播完授予 1_0"没走完（多半是关服丢了待授予项）。
            //    ⚠️ 用户裁定：补授**也必须在剧情声明的维度里**（与"离开即跳过"一致）。
            if (isEarned(player, ROOT)) {
                award(player, ENTRANCE_DONE);
                BeLoongCore.LOGGER.info(
                        "[BeLoong] npc prologue: player '{}' had root but not 1_0 while in {} — granted 1_0 "
                                + "(the entrance CG must have been interrupted; the story can continue)",
                        player.getGameProfile().getName(), host);
                return;
            }

            // ⑥ ★ 从头开场 ⇒ 先把玩家送回**同维度内的入场落点**（类注释第 2 条）。
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

            // ⑦ 同一 tick：先确保分身，成功了才授予 root（顺序不能反，见类注释）
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

            // ⑧ 播登场 CG（闸门：此刻必然还没有 1_0 —— ③ 已经挡掉了有的情况）
            story.cg().ifPresent(name -> CgRegistry.byName(name).ifPresentOrElse(
                    cg -> cg.play(player, npc),
                    () -> BeLoongCore.LOGGER.warn(
                            "[BeLoong] npc prologue: story for '{}' names unknown cg '{}' — the double exists, "
                                    + "but no entrance cg was played", entry.getKey(), name)));
            return;
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
    }
}
