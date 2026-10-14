package com.zonlong.beloong.npcstory;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.cg.CgRegistry;
import com.zonlong.beloong.entity.NpcEntity;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;

/**
 * <b>序章</b>（2026-10-14）：把"开场"从环境副作用改造为受控流程。
 *
 * <h2>流程</h2>
 * <pre>
 * 玩家进入游戏 →（等 {@code npc_story.prologueDelaySeconds} 秒）→ **玩家本就在剧情声明的维度里**
 *   → 同一 tick 确保锚点处分身存在 → 授予 {@code beloong:npc/root} → 播登场 CG（闸门：尚未获得 1_0）
 * </pre>
 * CG 播完由 CG 自己授予 {@code 1_0}（见 {@code CgCompletionHandler}）⇒ 第一段对话的窗口随即可用。
 *
 * <h2>★ 本处理器**绝不移动玩家**（2026-10-14 实机后裁定）</h2>
 * 最初它还负责"玩家不在龙宫就用 {@code teleport} 包送回去"，实机后用户改判：
 * <b>剧情应当可以跳过</b> —— 玩家离开龙宫就等于跳过这段剧情，不该把他强行拉回来。
 * 于是"不在剧情声明的维度"⇒ <b>什么都不做</b>（不授予、不传送、不生成分身）：
 * <ul>
 *   <li>宽限期内的短暂离开：什么都不发生（进度与分身都在）⇒ 回来继续；</li>
 *   <li>超过宽限：由维度规则清理（删分身 + 撤整条链，含 {@code root}/{@code 1_0}）⇒ 这一步就是"跳过并清干净"；</li>
 *   <li>玩家**自己走回来**才会重新开场（CG 重播 —— 这是设计 D1：完整重置后允许重播）。</li>
 * </ul>
 * 守卫 {@code stage_invariants.py} 里有一条断言钉住这件事（"序章不得出现传送调用"）。
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
 *   <li>判据**先便宜后贵**：状态（字段读）⇒ <b>维度</b>（一次比较）⇒ 进度（Map 查）。
 *       于是"不在剧情维度"的玩家（绝大多数时间）只付一次比较就返回；</li>
 *   <li>每玩家每 tick 的期望成本因此≈ 0，远低于对账器（每 100 tick 一次 {@code getAllEntities()}）。</li>
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
                // 一个玩家出错不该拖垮其他人
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

            // ② 关键闸门（先于任何进度查询，也是本处理器唯一的"位置"判据）：
            //    玩家必须**本就在**该剧情声明的维度里。不在这里 ⇒ 什么都不做
            //    —— 不授予、不传送、不生成分身。剧情可跳过，见类注释。
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

            // ⑥ 同一 tick：先确保分身，成功了才授予 root（顺序不能反，见类注释）
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

            // ⑦ 播登场 CG（闸门：此刻必然还没有 1_0 —— ③ 已经挡掉了有的情况）
            story.cg().ifPresent(name -> CgRegistry.byName(name).ifPresentOrElse(
                    cg -> cg.play(player, npc),
                    () -> BeLoongCore.LOGGER.warn(
                            "[BeLoong] npc prologue: story for '{}' names unknown cg '{}' — the double exists, "
                                    + "but no entrance cg was played", entry.getKey(), name)));
            return;
        }
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
