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
 * 两条路径分工明确（设计 D10）：
 * <ul>
 *   <li><b>事件路径</b>：玩家获得 {@code start_advancement} 的那一刻 ⇒ 幂等确保"只属于他"的分身存在
 *       ⇒ 再由**分身**播放该剧情声明的 CG（一次性演出只能由事件驱动）；</li>
 *   <li><b>声明路径（对账器）</b>：登录 / 低频巡检 / 退出清理 ⇒ 按声明补齐或清除分身。
 *       声明式 ⇒ 幂等、可反复跑、自愈（掉线、重启、数据热重载都不会留下坏状态）。</li>
 * </ul>
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
 *   start 已获得 ∧ 有 ∧ 离开有效维度超过宽限 ⇒ 清理 + 未完成则撤回（宽限内给倒计时提示）
 *   clear_on_logout ∧ 玩家退出             ⇒ 清理 + 未完成则撤回
 *   已完成 ∧ keep_after_finish=false        ⇒ **只删不撤**（通关即回收；锚点随即重新可见，观感连续）
 *   已完成 ∧ keep_after_finish=true         ⇒ **一律保留、不清理**（D6：结尾"它坐在那里"对主人有意义）
 *   同一玩家有多个分身                        ⇒ 保留最近出生（并列时最近）的、其余删除 + WARN
 * </pre>
 * ⚠️ <b>"扫不到分身"不等于"分身丢了"</b>：实体查询只覆盖**已加载区块**。
 * 因此只有在"**那一轮扫到了无主锚点**"（= 那片区块确实加载着）时才把缺失当真，
 * 且要**连续若干轮**都缺才动手 —— 否则会出现"登录就被撤回进度"这类事故（D19 的宽限在登录路径上失效）。
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

    /** 已经做过"进度 id 自检"的那次加载代号；-1 = 还没做过（见 {@link #checkStoryIdsOnce}）。 */
    private int checkedReloadStamp = -1;

    /**
     * 引用了**不存在的进度 id** 的剧情（实体类型）。这些剧情是 dead on arrival ——
     * 永远不可能触发（起点 id 错了）或永远走不完撤回链（终点 id 错了）⇒
     * **不给它们生成分身**（计划 T9 要求的 fail-closed），只留那条 ERROR 让人去修数据。
     */
    private final Set<EntityType<?>> storiesWithMissingIds = new java.util.HashSet<>();

    /** 连续多少轮"扫不到分身"才认定它真的丢了。防的是区块未加载造成的误判。 */
    private static final int MISSES_BEFORE_RESET = 3;

    /**
     * "疑似缺失"计数（键 = 玩家 + 剧情）。
     * <p>
     * ⚠️ **只在内存里**：它是"连续观察到几次"的瞬时判断，不是状态 ⇒ 不落盘、不违反设计 D4
     * （"事实来源是原版进度，不新增每玩家持久状态"）。重启后重新数即可。
     */
    private final Map<String, Integer> missingCounts = new java.util.HashMap<>();

    /** 玩家 + 剧情 的复合键（内存计数的键）。 */
    private static String missingKey(java.util.UUID player, NpcStory story) {
        return player + "@" + story.startAdvancement();
    }

    private int missesOf(java.util.UUID player, NpcStory story) {
        return this.missingCounts.getOrDefault(missingKey(player, story), 0);
    }

    private void addMiss(java.util.UUID player, NpcStory story) {
        this.missingCounts.put(missingKey(player, story), missesOf(player, story) + 1);
    }

    private void clearMiss(java.util.UUID player, NpcStory story) {
        this.missingCounts.remove(missingKey(player, story));
    }

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
            // fail-closed：进度 id 不存在的剧情不生成分身（见 storiesWithMissingIds 的注释）
            if (this.storiesWithMissingIds.contains(entry.getKey())) {
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

    /**
     * 加载后**一次性**自检：每条剧情的起点/终点进度是否真实存在。
     * <p>
     * ⚠️ 为什么不在加载期查：进度由 {@code ServerAdvancementManager} 在**同一次重载**里加载，
     * 而重载是并行的监听器图 ⇒ 我们的监听器不保证在它之后跑。所以退一步：
     * 在重载后的第一轮巡检做一次，ERROR 点名到"哪条剧情、缺哪个 id"。
     * <p>
     * 这两个 id 错了就是 dead on arrival：剧情永远不可能触发，撤回时也走不完父链 ⇒ 必须报出来。
     */
    private void checkStoryIdsOnce(MinecraftServer server) {
        int stamp = NpcStoryLoader.INSTANCE.reloadStamp();
        if (stamp == this.checkedReloadStamp) {
            return;
        }
        this.checkedReloadStamp = stamp;
        this.storiesWithMissingIds.clear();
        for (Map.Entry<EntityType<?>, NpcStory> entry : NpcStoryLoader.INSTANCE.all().entrySet()) {
            NpcStory story = entry.getValue();
            for (ResourceLocation id : List.of(story.startAdvancement(), story.endAdvancement())) {
                if (server.getAdvancements().get(id) == null) {
                    BeLoongCore.LOGGER.error(
                            "[BeLoong] npc story for '{}' references advancement '{}' which does not exist"
                                    + " — this story is disabled (no double will be spawned) until the data"
                                    + " pack is fixed",
                            entry.getKey(), id);
                    this.storiesWithMissingIds.add(entry.getKey());
                }
            }
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
        // ⚠️ 自检放在**这里**（enabled 闸门之后、巡检间隔之前，每 tick 一次 O(1) 判 stamp）：
        //   ① 它是数据诊断 ⇒ 总开关关着时也该把"引用了不存在的进度"报出来（否则作者永远看不到）；
        //   ② 重载后 **1 tick 内**就会重算 storiesWithMissingIds —— 否则在下一轮巡检（≤100 tick）之前，
        //      刚被修好的剧情仍被当成缺失 ⇒ 玩家拿到起点却不生成分身，接着被 D21 误撤。
        checkStoryIdsOnce(event.getServer());
        int interval = Math.max(1, Config.NpcStory.reconcileIntervalTicks.get());
        if (++this.tickCounter < interval) {
            return;
        }
        this.tickCounter = 0;
        reconcileAll(event.getServer());
    }

    /**
     * {@code clear_on_logout = true} 的剧情：玩家退出即清理（未通关则同时撤回，D16）。
     * <p>
     * ⚠️ 按**要求维度**找分身，而不是"玩家退出时所在维度" —— 分身可能留在龙宫里，
     * 而玩家是在主世界退出的。
     * <p>
     * 名字就是"退出即清"的意思 ⇒ 不做"离线宽限"（那需要额外记录退出时刻，等于为一个小开关引入新状态）。
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
            ServerLevel level = story.requiredDimension()
                    .map(id -> player.server.getLevel(ResourceKey.create(Registries.DIMENSION, id)))
                    .orElse(player.serverLevel());
            if (level == null) {
                continue;
            }
            // 通关闭环：未通关才撤回（与超时/维度规则同款，D16）。
            boolean finished = NpcDialogueStage.isEarned(player, story.endAdvancement());
            for (NpcEntity npc : doublesOf(player, level, entry.getKey())) {
                removeDouble(npc, story, player, "logout", !finished);
            }
        }
        // ⚠️ 退出时也要做一次"通关即回收"：对账器只遍历**在线**玩家，
        // 若玩家在通关后的那一轮巡检之前下线（clear_on_logout 又是 false），
        // 他的分身就会隐身滞留到下次登录 —— 那正是 keep_after_finish=false 要消除的东西。
        for (Map.Entry<EntityType<?>, NpcStory> entry : NpcStoryLoader.INSTANCE.all().entrySet()) {
            NpcStory story = entry.getValue();
            if (story.keepAfterFinish() || !NpcDialogueStage.isEarned(player, story.endAdvancement())) {
                continue;
            }
            ServerLevel level = story.requiredDimension()
                    .map(id -> player.server.getLevel(ResourceKey.create(Registries.DIMENSION, id)))
                    .orElse(player.serverLevel());
            if (level == null) {
                continue;
            }
            for (NpcEntity npc : doublesOf(player, level, entry.getKey())) {
                removeDouble(npc, story, player, "finished_on_logout", false);   // 只删不撤
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
     * 效率：**每个相关维度只遍历一次实体**，同时建 {@code owner → 分身} 映射与锚点计数；
     * 再逐个在线玩家比对（O(实体 + 玩家)）。绝不在每个追踪周期都跑的代码里这么干。
     * <p>
     * ⚠️ <b>只在"要求维度"里扫</b>（有 dimension.host 时）：
     * 退而求其次去扫"每个在线玩家所在维度"会顺带扫主世界，5 秒一次，代价与收益不成比例。
     */
    private void reconcileStory(MinecraftServer server, EntityType<?> type, NpcStory story) {
        // 进度 id 缺失的剧情**整条跳过**：它已是 dead on arrival（见 storiesWithMissingIds 的注释）。
        // 若只挡生成，D21 分支会每轮都打"已撤回"WARN 并去走一条**走不完的撤回链**（终点进度根本不存在
        // ⇒ 必然再报 ERROR），玩家则停在"已开始 + 无分身"里看着空场景 —— 什么都不做反而更干净。
        if (this.storiesWithMissingIds.contains(type)) {
            return;
        }
        Set<ServerLevel> levels = new LinkedHashSet<>();
        if (story.requiredDimension().isPresent()) {
            ServerLevel level = server.getLevel(
                    ResourceKey.create(Registries.DIMENSION, story.requiredDimension().get()));
            if (level == null) {
                return;
            }
            levels.add(level);
        } else {
            // 没有 dimension.host 的剧情：退化为"扫在线玩家当前所在维度"（分身通常就在那儿）。
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                levels.add(player.serverLevel());
            }
        }
        if (levels.isEmpty()) {
            return;
        }

        Map<UUID, List<NpcEntity>> byOwner = new LinkedHashMap<>();
        int anchors = 0;
        for (ServerLevel level : levels) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.getType() != type || !(entity instanceof NpcEntity npc)) {
                    continue;
                }
                if (npc.owner() == null) {
                    anchors++;
                } else {
                    byOwner.computeIfAbsent(npc.owner(), key -> new ArrayList<>()).add(npc);
                }
            }
        }

        // ⚠️ "搜索可信"的判据：**扫到了无主锚点**。锚点是分身出生的地方，它扫不到就说明那片区块
        // 根本没加载（玩家在别的维度、或离得太远）—— 此时"没找到分身"不代表"分身丢了"，
        // 任何清理/重置都不许做（否则会出现"登录就被撤回进度"这种事故）。
        boolean searchTrustworthy = anchors > 0;

        // ⚠️ keep_after_finish=true 的剧情**整条跳过离线结算**（设计 D6）：离线判不出"主人是否已通关"，
        // 而 true 的意义正是"通关后永久保留" ⇒ 宁可不清理，也不误删。这类剧情的中途分身
        // 仍会在主人**下次登录**时被正常判定并清理。
        boolean skipOfflineSettlement = story.keepAfterFinish();

        // ⚠️ 先**快照** owner 集合：下面会删实体，边遍历边改会踩 ConcurrentModificationException。
        for (UUID owner : List.copyOf(byOwner.keySet())) {
            ServerPlayer online = server.getPlayerList().getPlayer(owner);
            if (online != null) {
                reconcilePlayer(online, story, byOwner.get(owner), searchTrustworthy);
            } else if (!skipOfflineSettlement) {
                settleOffline(story, owner, byOwner.get(owner));
            }
        }
        // 在线玩家**没有**分身时也要巡检（走 mine.isEmpty() 分支：D21 重置）——
        // 上一循环只覆盖了"表里有的 owner"。
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!byOwner.containsKey(player.getUUID())) {
                reconcilePlayer(player, story, List.of(), searchTrustworthy);
            }
        }
    }

    /**
     * <b>离线结算</b>：主人不在线时，只做"租约到期"这一件事 —— 删分身，**不撤进度**。
     * <p>
     * 为什么不撤：离线读不到玩家进度（{@code PlayerAdvancements} 只存在于在线的 {@code ServerPlayer} 上）。
     * 撤进度留给该玩家**下次登录**：那时 {@link #reconcilePlayer} 会看到"已开始 ∧ 无分身"，
     * 走 D21 撤回整条链；而"已通关者永不重置"（上轮 C1 的修复）保证通关玩家的进度不会被误撤。
     * <p>
     * 为什么不判其它四项（维度 / 孤儿 / 通关回收 / 重复）：它们分别需要"玩家此刻在哪"或"进度"，离线都没有。
     */
    private void settleOffline(NpcStory story, UUID owner, List<NpcEntity> mine) {
        for (NpcEntity npc : mine) {
            long now = npc.level() instanceof ServerLevel level ? level.getGameTime() : 0L;
            if (npc.hasExpired(now)) {
                npc.discard();
                BeLoongCore.LOGGER.info(
                        "[BeLoong] npc story '{}': removed double of OFFLINE player '{}'"
                                + " (reason: timeout_offline)",
                        story.startAdvancement(), owner);
            }
        }
    }

    private void reconcilePlayer(ServerPlayer player, NpcStory story, List<NpcEntity> mine,
                                 boolean searchTrustworthy) {
        boolean started = NpcDialogueStage.isEarned(player, story.startAdvancement());
        boolean finished = NpcDialogueStage.isEarned(player, story.endAdvancement());

        if (mine.isEmpty()) {
            // ⚠️ 通关玩家**永不**在这里被重置（D6 终态保留 / D16）：他们的分身到期或被清掉之后，
            // 剧情就是"已经结束了"，不该再被撤回。
            if (!started || finished || !searchTrustworthy) {
                if (started && !finished && !searchTrustworthy) {
                    // 搜索不可信 ⇒ 记一次"疑似缺失"，连续若干轮才动手（避免区块加载造成的误撤）。
                    this.addMiss(player.getUUID(), story);
                    int misses = this.missesOf(player.getUUID(), story);
                    if (misses < MISSES_BEFORE_RESET) {
                        return;
                    }
                } else {
                    return;
                }
            }
            this.clearMiss(player.getUUID(), story);
            // D21：已开始却没有分身 ⇒ 统一按"剧情重新开始"（撤回整条链，回入口重获起点进度）。
            // ⚠️ 刻意**不**就地补生成：中期阶段补一只站在入口的分身，会让两条回复都不显示 ⇒ 玩家卡死。
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] npc story '{}': player '{}' has the start advancement but no double"
                            + " — revoking the story so it can be restarted from the beginning",
                    story.startAdvancement(), player.getGameProfile().getName());
            revokeStory(player, story);
            return;
        }
        this.clearMiss(player.getUUID(), story);

        // 重复分身：保留最近出生的那个；bornAt 并列（含旧实体的 0）时保留**离玩家最近**的那个，
        // 避免把玩家真正在用的那一只删掉。
        if (mine.size() > 1) {
            List<NpcEntity> sorted = new ArrayList<>(mine);
            sorted.sort(Comparator
                    .comparingLong(NpcEntity::bornAt).reversed()
                    .thenComparingDouble(player::distanceToSqr));
            NpcEntity keep = sorted.get(0);
            for (NpcEntity extra : sorted.subList(1, sorted.size())) {
                BeLoongCore.LOGGER.warn(
                        "[BeLoong] npc story '{}': player '{}' has {} doubles — keeping the newest"
                                + " (and nearest), removing the rest",
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

        // 已通关 ⇒ 是否保留由声明决定（{@code keep_after_finish}，缺省 false = 通关即回收）：
        //   · false ⇒ 删掉分身。**只删不撤** —— 剧情已完成，撤回会毁掉玩家的通关记录。
        //     删掉之后 {@code NpcEntity#visibleTo} 会让公共锚点重新对他可见
        //     ⇒ 玩家看到的仍是"同一位置的同一只"（整合包的结尾正好把 NPC 送回锚点处）⇒ 观感连续；
        //   · true  ⇒ 保留（设计 D6：短剧情结尾"它坐在那里"对主人有意义），此后不再按租约/维度清理它。
        // 代价：true 时通关玩家的分身会一直留着 —— 数量被"通关人数"限住，且只对本人可见。
        if (finished) {
            if (!story.keepAfterFinish()) {
                removeDouble(npc, story, player, "finished", false);
            }
            return;
        }

        long now = npc.level() instanceof ServerLevel level
                ? level.getGameTime()
                : player.serverLevel().getGameTime();

        // 租约到期
        if (npc.hasExpired(now)) {
            removeDouble(npc, story, player, "timeout", true);
            return;
        }

        // 有效维度（宽限期内不动它 —— 在龙宫死一次重生的那种短暂离开不该被判成弃坑）
        // ⚠️ 闸门是 **dimension.enforced()**（= enforce && host 存在），**不是**"有 host 就清"：
        // host 的职责只是"告诉对账器去哪个维度巡检"（离线结算靠它），要不要"离开就清"
        // 由 enforce 单独决定（设计 D4）—— 两件事合成一个字段会让长流程被迫二选一。
        if (story.dimension().enforced()) {
            boolean inside = player.level().dimension().location().equals(story.dimension().host().get());
            if (inside) {
                if (npc.outsideSince() != 0L) {
                    npc.setOutsideSince(0L);
                }
            } else if (npc.outsideSince() == 0L) {
                npc.setOutsideSince(now);
            } else if (now - npc.outsideSince() > story.dimensionGraceTicks()) {
                removeDouble(npc, story, player, "outside_dimension", true);
                return;
            } else {
                warnDimensionGrace(player, story, npc, now);
            }
        } else if (npc.outsideSince() != 0L) {
            // 规则被关掉（enforce=false）时把计时清零：否则玩家在规则关闭期间一直待在维度外，
            // 等哪天重新启用时会"立即到期"、不给任何宽限 —— 那是很难理解的行为。
            npc.setOutsideSince(0L);
        }

        warnBeforeExpiry(player, story, npc, now);
    }

    /** 离开有效维度期间的提示（进入提醒窗口后每次巡检都发，充当倒计时）。 */
    private void warnDimensionGrace(ServerPlayer player, NpcStory story, NpcEntity npc, long now) {
        long remaining = story.dimensionGraceTicks() - (now - npc.outsideSince());
        if (remaining <= 0L) {
            return;
        }
        long warn = story.dimension().warnBeforeTicks().orElse(NpcStory.DEFAULT_WARN_BEFORE_TICKS);
        if (warn <= 0L || remaining > warn) {
            return;
        }
        remind(player, story.dimension().warnText(), story.dimension().warnKey(),
                remaining, "beloong.npc.story.outside_dimension");
    }

    /** 到期前的提示（actionbar）。进入提醒窗口后每次巡检都发，充当倒计时。 */
    private void warnBeforeExpiry(ServerPlayer player, NpcStory story, NpcEntity npc, long now) {
        if (story.isPermanent() || npc.expireAt() <= 0L) {
            return;
        }
        long remaining = npc.expireAt() - now;
        if (remaining <= 0L) {
            return;
        }
        // 数据里没写（Optional 空）⇒ 用**全局配置**兜底（设计 D11）；显式写 0 = 不提醒。
        long warn = story.lease().warnBeforeTicks()
                .orElse((long) Config.NpcStory.expiryWarningTicks.get());
        if (warn <= 0L || remaining > warn) {
            return;
        }
        remind(player, story.lease().warnText(), story.lease().warnKey(),
                remaining, "beloong.npc.story.expiring");
    }

    /**
     * 发一条提醒。文案来源**按优先级**：数据里的 {@code warn_text}（字面，优先）⇒
     * {@code warn_key}（翻译键）⇒ 模组内置默认键。
     * <p>
     * ⚠️ 只有"键是否存在"是服务端**无法校验**的一类（{@code lang} 是客户端资源）——
     * 写错时玩家屏幕上会出现原始键名，这是刻意保留的暴露方式（设计 D10）。
     * ⚠️ {@code warn_text} 里写 {@code %s} 才填剩余秒数；不写就是固定文案。
     */
    private void remind(ServerPlayer player, Optional<String> text, Optional<String> key,
                        long remainingTicks, String builtinKey) {
        long seconds = Math.max(1L, remainingTicks / 20L);
        Component message = text
                .map(t -> Component.literal(t.contains("%s") ? t.replace("%s", Long.toString(seconds)) : t))
                .orElseGet(() -> Component.translatable(key.orElse(builtinKey), seconds));
        player.displayClientMessage(message, true);
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
            BeLoongCore.LOGGER.error(
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
