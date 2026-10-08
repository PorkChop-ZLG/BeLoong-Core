package com.zonlong.beloong.npcstory;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.cg.CgRegistry;
import com.zonlong.beloong.entity.NpcEntity;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.dialogue.NpcDialogueStage;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * <b>私有分身的管家</b> —— 让世界与 {@link NpcStory} 的声明保持一致。
 * <p>
 * 本类目前只负责**事件路径**：玩家获得 {@code start_advancement} 的那一刻
 * ⇒ 幂等确保"只属于他"的分身存在 ⇒ 再由**分身**播放该剧情声明的 CG。
 * （声明路径的对账器 —— 登录/进度变化/低频巡检 —— 是计划 T6，尚未落地。）
 *
 * <h2>为什么生成时机是"获得起点进度那一刻"</h2>
 * 路线指派是靠 {@code LastDialogueNpc}（玩家**最近对话过的那个实体**）解析目标的
 * ⇒ 必须保证"玩家将来对话的那个实体就是他的分身" ⇒ 分身的生成必须**早于**第一次对话。
 * 而起点进度（{@code beloong:npc/root}）由位置触发自动发放 ⇒ 它正好是"玩家走进场景、
 * 还没开口"的那个时刻（设计 D5/D21 的补充）。
 *
 * <h2>为什么在锚点的位置与朝向生成</h2>
 * 玩家视角里那只 mo **一直在原地没动** ⇒ 从"看到公共锚点"切到"看到自己的分身"毫无感知。
 * 这也是"不变量：任一玩家眼里恰好一个"能成立的关键。
 * <p>
 * ⚠️ <b>兜底：找不到锚点时生成在玩家身前 4 格，而不是玩家脚下</b> ——
 * {@code CgContext.of} 在"观察者与锚点水平重合"时判方向退化并返回 {@code null}
 * ⇒ 那样 CG 会直接中止（{@code CgContext} 的退化分支）。
 *
 * <h2>为什么删掉了旧的"±48 就近搜索 {@code MoEntity}"</h2>
 * 旧实现（{@code npcstory/NpcStoryHandler}）是"在世界里碰运气找一个演员"：找不到就只打 WARN 不播，
 * 而且分身出现后还可能挑到**别人的**分身。现在演员是**按需构造**的、引用就在手里
 * ⇒ 那两类问题一起消失（设计 D9）。
 *
 * <h2>对账器的判定表（设计 §3.3 + D21）</h2>
 * <pre>
 *   start 未获得 ∧ 有分身                    ⇒ 孤儿 ⇒ 删除 + WARN
 *   start 已获得 ∧ 无分身                    ⇒ **D21 重置**：撤回整条链 + WARN（不生成、不播 CG）
 *   start 已获得 ∧ 有 ∧ 已过期              ⇒ 清理；**未完成才撤回**，已完成只清理
 *   start 已获得 ∧ 有 ∧ 离开有效维度超过宽限 ⇒ 清理 + 未完成则撤回
 *   已完成（有 end）∧ 有                     ⇒ 保留（D6：结尾"它坐在那里"对主人有意义）
 *   同一玩家有多个分身                        ⇒ 保留最近出生的、其余删除 + WARN
 * </pre>
 * ⚠️ <b>"无分身"与"刚获得起点"不会打架</b>：起点进度由位置触发器在玩家 tick 内发放，
 * {@code AdvancementEarnEvent} 是**同步**派发的 ⇒ 等本对账器所在的 {@code ServerTickEvent.Post}
 * 跑起来时，分身已经生成完了。若生成真的失败，对账器就走 D21 重置 —— 这正是想要的失败模式。
 */
public class NpcStoryHandler {

    /** 找不到锚点时，在玩家身前多少格生成（水平方向，保持 y 不变）。 */
    private static final double FALLBACK_FORWARD = 4.0D;

    /** 防呆：父链异常长时不无限走（正常链只有 3~4 跳）。 */
    private static final int MAX_CHAIN_STEPS = 64;

    /** 巡检计数器（事件处理器是单例，只注册一次）。 */
    private int tickCounter;

    @SubscribeEvent
    public void onAdvancementEarned(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ResourceLocation earned = event.getAdvancement().id();
        ServerLevel level = player.serverLevel();

        for (Map.Entry<EntityType<?>, NpcStory> entry : NpcStoryLoader.INSTANCE.all().entrySet()) {
            NpcStory story = entry.getValue();
            if (!story.startAdvancement().equals(earned)) {
                continue;
            }

            NpcEntity npc = ensureDouble(player, level, entry.getKey(), story);
            if (npc == null) {
                continue;
            }

            // CG 与"生成"解耦：名字解析不到只留 WARN，分身照常存在（设计 D12）。
            story.cg().ifPresent(name -> CgRegistry.byName(name).ifPresentOrElse(
                    cg -> cg.play(player, npc),
                    () -> BeLoongCore.LOGGER.warn(
                            "[BeLoong] npc story for '{}' names unknown cg '{}' — the double was spawned"
                                    + " but no cg was played", entry.getKey(), name)));
        }
    }

    // ===================== 声明路径：对账 =====================

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        MinecraftServer server = event.getEntity().getServer();
        if (server != null) {
            reconcileAll(server);
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!Config.NpcStory.enabled.get()) {
            return;
        }
        int interval = Math.max(1, Config.NpcStory.reconcileIntervalTicks.get());
        if (++this.tickCounter < interval) {
            return;
        }
        this.tickCounter = 0;
        reconcileAll(event.getServer());
    }

    /**
     * {@code clear_on_logout = true} 的剧情：玩家退出即清理。
     * <p>
     * 名字就是这个意思 ⇒ 不做"离线宽限"（那需要额外记录退出时刻，等于为一个小开关引入新状态）。
     * 我们的数据用的是 {@code false}（意外断线可续），真正的兜底是租约的时长。
     */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!Config.NpcStory.enabled.get() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        for (Map.Entry<EntityType<?>, NpcStory> entry : NpcStoryLoader.INSTANCE.all().entrySet()) {
            NpcStory story = entry.getValue();
            if (!story.clearOnLogout()) {
                continue;
            }
            for (NpcEntity npc : doublesOf(player, player.serverLevel(), entry.getKey())) {
                removeDouble(npc, story, player, "logout", false);
            }
        }
    }

    private void reconcileAll(MinecraftServer server) {
        if (!Config.NpcStory.enabled.get()) {
            return;
        }
        for (Map.Entry<EntityType<?>, NpcStory> entry : NpcStoryLoader.INSTANCE.all().entrySet()) {
            reconcileStory(server, entry.getKey(), entry.getValue());
        }
    }

    /**
     * 对一条剧情做一次全量对账。
     * <p>
     * 效率：**每个相关维度只遍历一次实体**建 {@code owner → 分身} 映射，再逐个在线玩家比对
     * （O(实体 + 玩家)）。绝不在每个追踪周期都跑的代码里这么干。
     */
    private void reconcileStory(MinecraftServer server, EntityType<?> type, NpcStory story) {
        Set<ServerLevel> levels = new LinkedHashSet<>();
        story.requiredDimension().ifPresent(id -> {
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
            if (level != null) {
                levels.add(level);
            }
        });
        // 没有 required_dimension 的剧情：退化为"扫在线玩家当前所在维度"（分身通常就在那儿）。
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            levels.add(player.serverLevel());
        }
        if (levels.isEmpty()) {
            return;
        }

        Map<UUID, List<NpcEntity>> byOwner = new LinkedHashMap<>();
        for (ServerLevel level : levels) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.getType() == type && entity instanceof NpcEntity npc && npc.owner() != null) {
                    byOwner.computeIfAbsent(npc.owner(), key -> new ArrayList<>()).add(npc);
                }
            }
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            reconcilePlayer(player, story, byOwner.getOrDefault(player.getUUID(), List.of()));
        }
    }

    private void reconcilePlayer(ServerPlayer player, NpcStory story, List<NpcEntity> mine) {
        boolean started = NpcDialogueStage.isEarned(player, story.startAdvancement());
        boolean finished = NpcDialogueStage.isEarned(player, story.endAdvancement());

        if (mine.isEmpty()) {
            if (started) {
                // D21：已开始却没有分身 ⇒ 统一按"剧情重新开始"（撤回整条链，回入口重获起点进度）。
                // ⚠️ 刻意**不**就地补生成：中期阶段补一只站在入口的分身，会让两条回复都不显示 ⇒ 玩家卡死。
                BeLoongCore.LOGGER.warn(
                        "[BeLoong] npc story '{}': player '{}' has the start advancement but no double"
                                + " — revoking the story so it can be restarted from the beginning",
                        story.startAdvancement(), player.getGameProfile().getName());
                revokeStory(player, story);
            }
            return;
        }

        // 重复分身：保留最近出生的那个（bornAt 为 0 的旧实体排在最后），其余删除。
        if (mine.size() > 1) {
            List<NpcEntity> sorted = new ArrayList<>(mine);
            sorted.sort(Comparator.comparingLong(NpcEntity::bornAt).reversed());
            NpcEntity keep = sorted.get(0);
            for (NpcEntity extra : sorted.subList(1, sorted.size())) {
                BeLoongCore.LOGGER.warn(
                        "[BeLoong] npc story '{}': player '{}' has {} doubles — keeping the newest,"
                                + " removing the rest",
                        story.startAdvancement(), player.getGameProfile().getName(), mine.size());
                removeDouble(extra, story, player, "duplicate", false);
            }
            mine = List.of(keep);
        }

        NpcEntity npc = mine.get(0);

        if (!started) {
            // 孤儿：剧情已回到"未开始"（手动撤回 / 存档回档）却还留着分身 ⇒ 删掉，别让它继续可见。
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] npc story '{}': player '{}' has a double but has not started — removing it",
                    story.startAdvancement(), player.getGameProfile().getName());
            removeDouble(npc, story, player, "orphan", false);
            return;
        }

        long now = npc.level() instanceof ServerLevel level
                ? level.getGameTime()
                : player.serverLevel().getGameTime();

        // 租约到期
        if (npc.hasExpired(now)) {
            removeDouble(npc, story, player, "timeout", !finished);
            return;
        }

        // 有效维度（宽限期内不动它 —— 在龙宫死一次重生的那种短暂离开不该被判成弃坑）
        if (story.requiredDimension().isPresent()) {
            boolean inside = player.level().dimension().location().equals(story.requiredDimension().get());
            if (inside) {
                if (npc.outsideSince() != 0L) {
                    npc.setOutsideSince(0L);
                }
            } else if (npc.outsideSince() == 0L) {
                npc.setOutsideSince(now);
            } else if (now - npc.outsideSince() > story.dimensionGraceTicks()) {
                removeDouble(npc, story, player, "outside_dimension", !finished);
                return;
            }
        }

        warnBeforeExpiry(player, story, npc, now);
    }

    /** 到期前的可见提示（actionbar）。它同时充当倒计时，所以窗口内每次巡检都发。 */
    private void warnBeforeExpiry(ServerPlayer player, NpcStory story, NpcEntity npc, long now) {
        int warning = Config.NpcStory.expiryWarningTicks.get();
        if (warning <= 0 || story.isPermanent() || npc.expireAt() <= 0L) {
            return;
        }
        long remaining = npc.expireAt() - now;
        if (remaining <= 0L || remaining > warning) {
            return;
        }
        player.displayClientMessage(
                Component.translatable("beloong.npc.story.expiring", Math.max(1L, remaining / 20L)), true);
    }

    // ===================== 清理与撤回 =====================

    /**
     * 删除分身（并按需撤回进度）—— <b>清理与撤回必须原子</b>：
     * 只做一半就会留下"有进度、没分身"（软锁）或"没进度、有分身"（同时看到两个）。
     *
     * @param revoke 是否同时撤回整条剧情链
     */
    private void removeDouble(NpcEntity npc, NpcStory story, ServerPlayer player, String reason, boolean revoke) {
        if (revoke) {
            revokeStory(player, story);
        }
        npc.discard();
        BeLoongCore.LOGGER.info(
                "[BeLoong] npc story '{}': removed double of player '{}' (reason: {}, progress revoked: {})",
                story.startAdvancement(), player.getGameProfile().getName(), reason, revoke);
    }

    /** 某玩家在某维度里、某类型的私有分身（退出清理用）。 */
    private List<NpcEntity> doublesOf(ServerPlayer player, ServerLevel level, EntityType<?> type) {
        List<NpcEntity> found = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity.getType() == type && entity instanceof NpcEntity npc
                    && player.getUUID().equals(npc.owner())) {
                found.add(npc);
            }
        }
        return found;
    }

    /**
     * 撤回整条剧情链：从 {@code end_advancement} 沿 {@code parent()} 一路走到 {@code start_advancement}。
     * <p>
     * 撤回一个进度的正确做法照原版 {@code /advancement revoke}（{@code AdvancementCommands.java:448-458}）：
     * 逐个 criterion 撤 —— {@code PlayerAdvancements} 只提供按 criterion 的 {@code revoke}。
     * <p>
     * ⚠️ 走不到 {@code start_advancement} 就说明链被改坏了（父关系被改），留一条 WARN 而不是静默少撤。
     */
    private boolean revokeStory(ServerPlayer player, NpcStory story) {
        AdvancementHolder holder = player.server.getAdvancements().get(story.endAdvancement());
        if (holder == null) {
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] npc story '{}': advancement '{}' does not exist — nothing was revoked",
                    story.startAdvancement(), story.endAdvancement());
            return false;
        }

        for (int step = 0; holder != null && step < MAX_CHAIN_STEPS; step++) {
            revokeWholeAdvancement(player, holder);
            if (holder.id().equals(story.startAdvancement())) {
                return true;
            }
            Optional<ResourceLocation> parent = holder.value().parent();
            if (parent.isEmpty()) {
                break;
            }
            holder = player.server.getAdvancements().get(parent.get());
        }

        BeLoongCore.LOGGER.warn(
                "[BeLoong] npc story '{}': could not walk the parent chain from '{}' back to '{}'"
                        + " — revocation may be incomplete",
                story.startAdvancement(), story.endAdvancement(), story.startAdvancement());
        return false;
    }

    /** 撤回一个进度的全部已完成 criterion（原版 {@code /advancement revoke} 同款）。 */
    private static void revokeWholeAdvancement(ServerPlayer player, AdvancementHolder holder) {
        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(holder);
        if (!progress.hasProgress()) {
            return;
        }
        for (String criterion : progress.getCompletedCriteria()) {
            player.getAdvancements().revoke(holder, criterion);
        }
    }


    /**
     * 幂等确保"该玩家的私有分身"存在，返回它。
     * <p>
     * 一次 {@code getAllEntities()} 同时完成两件事：查重（已有 ⇒ 直接复用）与找锚点
     * （{@code owner == null} 的同类实例，多个取离玩家最近的那个）。事件路径很少触发，
     * 这里 O(实体数) 是可接受的；**每个追踪周期都会跑的 {@code visibleTo} 里绝不能这么做**。
     */
    @Nullable
    private NpcEntity ensureDouble(ServerPlayer player, ServerLevel level, EntityType<?> type, NpcStory story) {
        NpcEntity anchor = null;
        double anchorDistance = Double.MAX_VALUE;
        int anchors = 0;

        for (Entity entity : level.getAllEntities()) {
            if (entity.getType() != type || !(entity instanceof NpcEntity npc)) {
                continue;
            }
            if (player.getUUID().equals(npc.owner())) {
                return npc;                     // 幂等：已经有自己的分身了，复用它（不重复生成、不重播 CG）
            }
            if (npc.owner() == null) {
                anchors++;
                double distance = player.distanceToSqr(npc);
                if (distance < anchorDistance) {
                    anchorDistance = distance;
                    anchor = npc;
                }
            }
        }

        Entity created = type.create(level);
        if (!(created instanceof NpcEntity npc)) {
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] npc story: entity type '{}' could not be created as an NpcEntity"
                            + " — no double was spawned", type);
            return null;
        }

        if (anchor != null) {
            if (anchors > 1) {
                // 设计 §2.4：锚点应当唯一。多于一个说明世界被摆错了（或旧分身丢了归属）
                // ⇒ 取离玩家最近的那个，并把这件事留在日志里。
                BeLoongCore.LOGGER.warn(
                        "[BeLoong] npc story: {} ownerless '{}' found in {} — using the nearest to player '{}'",
                        anchors, type, level.dimension().location(), player.getGameProfile().getName());
            }
            npc.moveTo(anchor.getX(), anchor.getY(), anchor.getZ(), anchor.getYRot(), anchor.getXRot());
            npc.setYHeadRot(anchor.getYRot());
        } else {
            // 兜底：身前 4 格（水平方向）。刻意不用"玩家脚下" —— 见类注释。
            Vec3 look = player.getLookAngle();
            Vec3 forward = new Vec3(look.x, 0.0D, look.z).normalize().scale(FALLBACK_FORWARD);
            Vec3 spot = player.position().add(forward);
            npc.moveTo(spot.x, player.getY(), spot.z, player.getYRot(), 0.0F);
            npc.setYHeadRot(player.getYRot());
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] npc story: no anchor (ownerless '{}') was found in {}"
                            + " — spawned the double {} blocks in front of player '{}' instead",
                    type, level.dimension().location(), (int) FALLBACK_FORWARD,
                    player.getGameProfile().getName());
        }

        long now = level.getGameTime();
        npc.setOwner(player.getUUID());
        // 租约在**生成那一刻**折算成绝对时刻（D14：改数据里的时长不影响已存在的分身）。
        npc.setLease(now, story.expiryAt(now));
        level.addFreshEntity(npc);
        BeLoongCore.LOGGER.info(
                "[BeLoong] npc story: spawned private double of '{}' for player '{}' at {}"
                        + " (lifetime: {}, expires at {})",
                type, player.getGameProfile().getName(), npc.blockPosition(),
                story.describeLifetime(), story.isPermanent() ? "never" : npc.expireAt());
        return npc;
    }
}
