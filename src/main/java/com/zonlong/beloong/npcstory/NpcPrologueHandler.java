package com.zonlong.beloong.npcstory;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.cg.CgRegistry;
import com.zonlong.beloong.entity.NpcEntity;
import com.zonlong.beloong.teleport.DimensionTeleport;
import com.zonlong.beloong.teleport.TeleportTarget;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;

/**
 * <b>序章</b>（2026-10-14）：把"开场"从环境副作用改造为受控流程。
 *
 * <h2>流程</h2>
 * <pre>
 * 玩家进入游戏 →（等 {@code npc_story.prologueDelaySeconds} 秒）
 *   → 不在龙宫 ⇒ 用 teleport 包送回龙宫 → 同一 tick 确保锚点处分身存在
 *   → 授予 {@code beloong:npc/root} → 播登场 CG（闸门：尚未获得 {@code beloong:npc/1_0}）
 * </pre>
 * CG 播完由 CG 自己授予 {@code 1_0}（见 {@code CgCompletionHandler}）⇒ 第一段对话的窗口随即可用。
 *
 * <h2>为什么"先分身、后授予"</h2>
 * 对账器把"已获得 {@code root} 却没有分身"判为"剧情该重新开始"并**撤回整条链**
 * （{@code NpcStoryHandler#reconcilePlayer} 的 D21 分支）⇒ 若先授予、分身还没建出来，
 * 就会出现**授予/撤回抖动**（状态乱跳 + 日志刷屏）。故两件事必须在同一 tick 内、按此顺序完成。
 *
 * <h2>为什么"新人要等、重置后不等"</h2>
 * 新人等待是为了让客户端把世界加载完（否则开场 CG 会在加载画面里白白播掉）；
 * 而"剧情被完整重置后回龙宫"是**看完又回来**，应当立刻重开场。
 * 判据用 {@link ServerPlayer#tickCount}（登录时从 0 起算）—— 与参考实现（龙之生存的开场弹窗）同款，
 * 不引入任何额外持久状态。
 *
 * <h2>服务器关停的兜底</h2>
 * 若在"已授予 {@code root}、{@code 1_0} 的延迟授予项还没到点"时关服，那条待授予项会随内存丢失
 * ⇒ 下次登录会出现"有 {@code root}、却没有 {@code 1_0}"（第一段对话打不开）⇒ 本类按
 * "有 root 却没有 1_0" **补授 {@code 1_0}**（视作那次登场已经发生过；用户裁定：只要 CG 开始过就一定拿到）。
 * 见 {@code CgCompletionHandler} 的类注释。
 */
public class NpcPrologueHandler {

    /** 剧情的起点进度：由本类授予（{@code root.json} 的触发已改为 {@code minecraft:impossible}）。 */
    private static final ResourceLocation ROOT = ResourceLocation.parse("beloong:npc/root");

    /** 登场 CG 已放完的标志：有它就不再开场、也不再播 CG。 */
    private static final ResourceLocation ENTRANCE_DONE = ResourceLocation.parse("beloong:npc/1_0");

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!Config.NpcStory.enabled.get()) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            try {
                this.tickPlayer(player);
            } catch (Throwable t) {
                // 一个玩家出错不该拖垮其他人（本处理器每 tick 跑）
                BeLoongCore.LOGGER.error(
                        "[BeLoong] npc prologue: failed to tick player '{}': {}",
                        player.getGameProfile().getName(), t.toString());
            }
        }
    }

    private void tickPlayer(ServerPlayer player) {
        // ① 前置条件：不合适就本 tick 什么都不做（下一 tick 再看）
        if (player.isDeadOrDying() || player.isSpectator() || player.isPassenger()) {
            return;
        }
        // ② 已经看过登场 ⇒ 这一轮序章结束（剧情重置会把 root/1_0 一起撤掉 ⇒ 到时自然会重新开场）
        if (isEarned(player, ENTRANCE_DONE)) {
            return;
        }
        // ③ 新人先等（让客户端加载完）；判据是玩家自身的 tickCount
        int delayTicks = Math.max(0, Config.NpcStory.prologueDelaySeconds.get()) * 20;
        if (player.tickCount < delayTicks) {
            return;
        }
        // ④ 有 root 却没有 1_0 ⇒ 说明上次那条"CG 播完授予 1_0"没走完（多半是关服丢了待授予项）
        //    ⇒ 按用户裁定补授，视作那次登场已经发生过（否则第一段对话永远打不开）。
        if (isEarned(player, ROOT)) {
            award(player, ENTRANCE_DONE);
            BeLoongCore.LOGGER.info(
                    "[BeLoong] npc prologue: player '{}' had root but not 1_0 — granted 1_0 "
                            + "(the entrance CG must have been interrupted; the story can continue)",
                    player.getGameProfile().getName());
            return;
        }

        for (Map.Entry<EntityType<?>, NpcStory> entry : NpcStoryLoader.INSTANCE.all().entrySet()) {
            EntityType<?> type = entry.getKey();
            NpcStory story = entry.getValue();

            // ⑤ 剧情声明了所在维度、而玩家不在那儿 ⇒ 先送回龙宫（用现成 API；本 tick 到此为止，
            //    下一 tick 再来确保分身并授予 —— 传送后 serverLevel() 才是龙宫）。
            ResourceLocation host = story.requiredDimension().orElse(null);
            if (story.dimension().enforced() && host != null
                    && !player.level().dimension().location().equals(host)) {
                if (teleportTo(palaceLevel(player, host), player)) {
                    BeLoongCore.LOGGER.info(
                            "[BeLoong] npc prologue: sent player '{}' back to {} to start the story",
                            player.getGameProfile().getName(), host);
                }
                return;
            }

            // ⑥ 同一 tick：先确保分身，成功了才授予 root（顺序不能反，见类注释）
            NpcEntity npc = NpcStoryHandler.ensureDoubleFor(player, type, story);
            if (npc == null) {
                BeLoongCore.LOGGER.error(
                        "[BeLoong] npc prologue: could not create the double of '{}' for player '{}' — "
                                + "root was NOT granted (the flow stays 'not started' rather than flapping)",
                        type, player.getGameProfile().getName());
                continue;
            }

            award(player, ROOT);
            BeLoongCore.LOGGER.info(
                    "[BeLoong] npc prologue: granted root to '{}' and ensured their double of '{}' at {}",
                    player.getGameProfile().getName(), type, npc.blockPosition());

            // ⑦ 播登场 CG（闸门：此刻必然还没有 1_0 —— ②已经挡掉了有的情况）
            story.cg().ifPresent(name -> CgRegistry.byName(name).ifPresentOrElse(
                    cg -> cg.play(player, npc),
                    () -> BeLoongCore.LOGGER.warn(
                            "[BeLoong] npc prologue: story for '{}' names unknown cg '{}' — the double exists, "
                                    + "but no entrance cg was played", type, name)));
            return;
        }
    }

    /** 解析该维度的 {@link ServerLevel}；拿不到时返回 {@code null}。 */
    private static ServerLevel palaceLevel(ServerPlayer player, ResourceLocation host) {
        ServerLevel level = player.server.getLevel(
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, host));
        if (level == null) {
            BeLoongCore.LOGGER.error("[BeLoong] npc prologue: dimension '{}' is not loaded — teleport skipped", host);
        }
        return level;
    }

    /**
     * 用 {@code teleport} 包把人送回龙宫（{@code docs/龙宫维度总设计.md} 的硬约束：所有跨维度传送都走它）。
     * <p>
     * 落点用 {@link TeleportTarget#toLoongPalace}（配置坐标）—— 龙宫的**实际地图由 LockDown 加载**
     * ⇒ 落点是实地，不会掉虚空。APi 契约：{@code null} = 本 tick 不传送（调用方下 tick 重试，本类天然如此）。
     */
    private static boolean teleportTo(ServerLevel palace, ServerPlayer player) {
        if (palace == null) {
            return false;
        }
        DimensionTransition transition = DimensionTeleport.toTarget(
                player, TeleportTarget.toLoongPalace(palace), entity -> entity.fallDistance = 0.0F);
        if (transition == null) {
            return false;       // 本 tick 落点解析不出来 ⇒ 下一 tick 会再试
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
