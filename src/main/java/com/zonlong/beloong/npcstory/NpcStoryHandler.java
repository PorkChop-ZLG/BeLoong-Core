package com.zonlong.beloong.npcstory;

import com.zonlong.beloong.BeLoongCore;
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
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
 * 还没开口"的那个时刻（设计 D5；其中的 D21 已于 2026-10-14 重写时删除，见重写设计 D2）。
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
 * <h2>对账器的判定表（2026-10-14 重写后：重写设计 D1–D3）</h2>
 * <pre>
 *   start 未获得 ∧ 有分身                    ⇒ 孤儿 ⇒ 删除 + WARN
 *   start 已获得 ∧ 无分身                    ⇒ **什么都不做**（能撤进度的调用点**全部**位于
 *                                              {@code removeDouble} 之内，而它同时删分身 ⇒ 撤与删永远成对）
 *   start 已获得 ∧ 有 ∧ 已过期              ⇒ 清理；**未完成才撤回**，已完成只清理
 *   离开有效维度                            ⇒ **没有任何行为**（该规则已随设计 D1 于 2026-10-14 删除）
 *   clear_on_logout ∧ 玩家退出             ⇒ 清理 + 未完成则撤回
 *   已完成 ∧ keep_after_finish=false        ⇒ **只删不撤**（通关即回收；玩家此后在同一位置看到**空场**，
 *                                             因为锚点对有剧情声明的实体恒不可见 —— 见下方 ⚠️）
 *   已完成 ∧ keep_after_finish=true         ⇒ **一律保留、不清理**（D6：结尾"它坐在那里"对主人有意义）
 *   同一玩家有多个分身                        ⇒ 保留最近出生（并列时最近）的、其余删除 + WARN
 * </pre>
 * ⚠️ <b>绝不拿"扫不到"当"不存在"</b>：实体查询只覆盖**已加载区块**
 * （{@code getAllEntities()} 只看已追踪实体，被区块卸载的实体不在其中）。
 * 本对账器因此**不对"缺失"做任何推断**（设计 D2）—— 一切清理与撤回都只在**手里真有那只实体**时发生。
 * ⚠️ 同理：{@code NpcEntity#visibleTo} 对"有剧情声明的无主实体"**恒返回 false**（设计 D8"锚点永不显示"），
 * 所以"删掉分身之后玩家会看到锚点"是**错的** —— 他看到的是空场。
 * ⚠️ <b>"无分身"不再触发任何重置</b>（设计 D2，2026-10-14）：起点进度由**序章**
 * （{@code NpcPrologueHandler}）在"玩家已在剧情维度内"时授予，并**同一 tick 先确保分身、再授予** ⇒
 * 对账器不会看到"已开始却没有分身"的窗口；即便看到，本类也**什么都不做** ——
 * 撤进度必须与删分身成对（见 {@code reconcilePlayer} 的注释与设计 D2）。
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

    @SubscribeEvent
    public void onAdvancementEarned(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ResourceLocation earned = event.getAdvancement().id();
        ServerLevel level = player.serverLevel();

        // ★ 审查 3：进度一被授予就让 S2 状态缓存立刻失效。这里是**最全**的一处失效点 ——
        // ChatBox 的 `advancement grant`（1_1 / 2_1 就是数据授予的）与原版触发器等**不经过我们的包装**，
        // 若只靠那三处显式失效，`reconcilePlayer` 读到的 finished(=2_1) 最长会陈旧 20 tick；
        // 而那一刻若恰好租约到期，就会把**刚刚通关**的记录整条撤掉（正是要避免的事）。
        NpcDialogueStage.invalidate(player);

        for (Map.Entry<EntityType<?>, NpcStory> entry : NpcStoryLoader.INSTANCE.all().entrySet()) {
            NpcStory story = entry.getValue();
            // ★ 租约续约（D10/D11，2026-10-14）：这条进度若算"里程碑"，就把**该剧情那只分身**的租约
            // 重置为完整一段。它在"是不是起点"的判断**之前** —— 里程碑与起点是两回事。
            renewLeaseIfMilestone(player, entry.getKey(), story, earned);
            if (!story.startAdvancement().equals(earned)) {
                continue;
            }
            // fail-closed：进度 id 不存在的剧情不生成分身（见 storiesWithMissingIds 的注释）
            if (this.storiesWithMissingIds.contains(entry.getKey())) {
                continue;
            }

            Ensured ensured = ensureDouble(player, level, entry.getKey(), story);
            if (ensured == null) {
                continue;
            }

            // ⚠️ **只有这一次真的生成了分身**才播 CG。复用了既有分身 ⇒ 剧情并没有重新开始
            // （起点进度被重新获得、但分身还在），登场演出不该重演。
            // 这是 ensureDouble 那句"幂等：…不重播 CG"承诺的另一半 —— 原先只写了注释没有实现。
            if (!ensured.spawned()) {
                continue;
            }

            // ⚠️ 2026-10-14 起**这里不再播 CG** —— 唯一播放者是 NpcPrologueHandler（序章）。
            // 原因：进入游戏/剧情被完整重置后的"开场"是**受控流程**（等配置秒数 + 人在龙宫 + 分身已建成
            // 才开场），而事件路径还会被"管理员手动授予 root"等旁路触发 ⇒ 两条路各播一次就会重播。
            // 本路径只负责"确保分身存在"（旁路授予 root 时也不至于让玩家没有分身可看）。
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
        //      刚被修好的剧情仍被当成缺失 ⇒ 玩家拿到起点却不生成分身（过去还会被 D21 误撤，该机制已删）。
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
            // 通关闭环：未通关才撤回（与"租约到期"同款，D16；维度规则已于 2026-10-14 删除）。
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
        // 若只挡生成，对账器会每轮都去走一条**走不完的撤回链**（终点进度根本不存在
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

        // 性能 S1（2026-10-14）：实体来源改为"已加载 NPC 索引"，不再遍历该维度的**全部**已加载实体
        // （掉落物、弹射物、成群怪物都不在索引里）。顺带达成 A1：一次遍历覆盖所有剧情，
        // 不再"每条剧情各扫一遍"（原来两条同维度剧情就是两次全维度扫描）。
        // ⚠️ 索引与 getAllEntities() 一样只含**已加载**实体 ⇒ "没扫到"依旧不等于"不存在"（设计 D2）。
        Map<UUID, List<NpcEntity>> byOwner = new LinkedHashMap<>();
        for (NpcEntity npc : LoadedNpcIndex.loaded()) {
            if (npc.getType() != type || npc.owner() == null || !levels.contains(npc.level())) {
                continue;
            }
            byOwner.computeIfAbsent(npc.owner(), key -> new ArrayList<>()).add(npc);
        }

        // ⚠️ 先**快照** owner 集合：下面会删实体，边遍历边改会踩 ConcurrentModificationException。
        for (UUID owner : List.copyOf(byOwner.keySet())) {
            ServerPlayer online = server.getPlayerList().getPlayer(owner);
            if (online != null) {
                reconcilePlayer(online, story, byOwner.get(owner));
            }
            // ⚠️ 主人**离线** ⇒ 什么都不做（设计 D6"纯乙"，2026-10-14）。
            // 理由：撤回进度需要在线玩家（PlayerAdvancements 只存在于 ServerPlayer 上），而删分身不需要 ——
            // 若趁离线把分身删掉，就会**只有删除、没有撤回** ⇒ 玩家下次登录停在"已开始、无分身、
            // 锚点又不显示"的卡死态，正是被删掉的 D21 要救的那种状态。
            // 所以离线一律不动：等他下次上线、且分身被加载时，由"租约到期"这条路径一次性完成"删 + 撤"（成对）。
            // 已知取舍：玩家永不再上线时，那只过期分身会一直留着 —— 但它**只对主人可见**，
            // 不影响其他玩家；管理员可用 /kill 收尾。见设计 §三与 §九。
        }
        // ⚠️ 审查 I1：这里原来还有一段"在线玩家**没有**分身时也要巡检"——
        // 它对应的是已删除的 D21 分支，而 {@code mine.isEmpty()} 现在什么都不做，
        // 于是它每 100 tick × 每条剧情 × 每个无分身玩家白跑两次进度查询，
        // 还会因为 {@code getOrStartProgress} 的懒建行为在这些玩家存档里写下**空进度记录**（隐藏死状态）。
        // 整段已删除：本对账器只处理"此刻**有**分身的主人"。
    }

    private void reconcilePlayer(ServerPlayer player, NpcStory story, List<NpcEntity> mine) {
        boolean started = NpcDialogueStage.isEarned(player, story.startAdvancement());
        boolean finished = NpcDialogueStage.isEarned(player, story.endAdvancement());

        if (mine.isEmpty()) {
            // ⚠️ **这里刻意什么都不做**（设计 D2，2026-10-14）。曾有一条"扫不到分身 ⇒ 撤回整条链"的
            // D21 分支，实机证明它必然造成"进度与分身分家"：区块未加载时 {@code getAllEntities()}
            // 看不到那只实体，于是它**撤得掉进度、却删不掉分身**（日志里 6 次误撤的直接成因）。
            // 现在唯一会撤回进度的路径是"租约到期"，而它**手里一定有实体** ⇒ 撤与删永远成对。
            // 代价：分身若真的消失（例如被 /kill），玩家会停在"已开始、无分身、锚点不显示"的状态，
            // 需人工处理（撤回进度让他重新开场）—— 见设计 §九"保留风险"。
            return;
        }

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
        //     ⚠️ 审查 I4：删掉之后玩家在该位置看到的是**空场** —— {@code NpcEntity#visibleTo} 对
        //     "有剧情声明的无主实体"**恒返回 false**（设计 D8"锚点永不显示"），不存在"公共锚点补上来、
        //     观感连续"这回事（那句旧注释描述的是 D8 之前的行为）。若希望通关后那里仍站着它，
        //     正确做法是 keep_after_finish=true（下一分支）。
        //   · true  ⇒ 保留（设计 D6：短剧情结尾"它坐在那里"对主人有意义），此后不再按租约清理它。
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

        warnBeforeExpiry(player, story, npc, now);
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
        if (revoke && !revokeStory(player, story)) {
            // ★ 审查 C1：撤回**失败**（父链走不到起点，通常意味着数据被改坏）时绝不能继续删分身。
            // revokeStory 失败前已经撤掉了链尾那一半 ⇒ 此时再删分身，玩家就留下半截进度 + 没有分身，
            // 而"有 1_0、无 root"又会让序章以为"已经开场过" ⇒ 永不自愈 —— 正是本次重写要消灭的分家。
            // 因此 fail-closed：不删、留 ERROR 让人去修数据（改好后租约到期会重新走到这里）。
            BeLoongCore.LOGGER.error(
                    "[BeLoong] npc story '{}': could not revoke the story for player '{}' (reason: {})"
                            + " — the double was KEPT so the player is not left half-revoked without an NPC;"
                            + " this almost always means the advancement chain is broken — fix the data",
                    story.startAdvancement(), player.getGameProfile().getName(), reason);
            return;
        }
        npc.discard();
        BeLoongCore.LOGGER.info(
                "[BeLoong] npc story '{}': removed double of player '{}' (reason: {}, progress revoked: {})",
                story.startAdvancement(), player.getGameProfile().getName(), reason, revoke);
    }

    /**
     * 一次"找自己的分身 / 找最近锚点"的扫描结果（S1：候选可由索引或全量实体提供）。
     *
     * @param existing 该玩家已有的分身（幂等复用；非 null 时优先）
     * @param anchor   最近的无主锚点（新分身生成在它处）
     * @param anchors  锚点个数（&gt;1 说明世界被摆错，调用方会 WARN）
     */
    private record AnchorScan(NpcEntity existing, NpcEntity anchor, int anchors) {
    }

    /** 在给定候选里找"我的分身"（优先）与"最近的锚点"。 */
    private static AnchorScan scanForDouble(ServerPlayer player, EntityType<?> type,
                                            Iterable<? extends Entity> candidates) {
        NpcEntity existing = null;
        NpcEntity anchor = null;
        double anchorDistance = Double.MAX_VALUE;
        int anchors = 0;
        for (Entity entity : candidates) {
            if (entity.getType() != type || !(entity instanceof NpcEntity npc)) {
                continue;
            }
            if (player.getUUID().equals(npc.owner())) {
                existing = npc;
                break;      // 幂等优先：找到自己的分身就不必再找锚点了
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
        return new AnchorScan(existing, anchor, anchors);
    }

    // ===================== 租约续约（设计 D10 / D11，2026-10-14）=====================

    /** 里程碑集合的惰性缓存（键 = 剧情 start id）；随 loader 的 reload stamp 失效。 */
    private static int milestonesStamp = Integer.MIN_VALUE;
    private static final Map<ResourceLocation, Set<ResourceLocation>> MILESTONES = new HashMap<>();

    /**
     * 该进度若算"里程碑"⇒ 把**该剧情那只分身**的租约重置为完整一段。
     * <p>
     * 时长取自该剧情自己的 {@code lease.ticks}（{@code story.expiryAt(now)}）—— **绝不硬编码**。
     * 只续"该剧情那只分身"（不得给该玩家的所有分身续约：那会掩盖其它剧情的超期）。
     */
    private static void renewLeaseIfMilestone(ServerPlayer player, EntityType<?> type, NpcStory story,
                                               ResourceLocation earned) {
        if (!story.lease().renewal()) {
            return;
        }
        if (!milestonesOf(player.server, story).contains(earned)) {
            return;
        }
        // 里程碑只可能通过与分身交互拿到 ⇒ 它就在玩家当前维度里。先查索引（S1），再回退一次全量扫描。
        NpcEntity target = findOwnDouble(player, type, player.serverLevel());
        if (target == null) {
            target = findOwnDoubleInScan(player, type);
        }
        if (target == null) {
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] npc story '{}': milestone '{}' earned by '{}' but their double was not found"
                            + " — the lease was NOT renewed (it may expire while the story is still going)",
                    story.startAdvancement(), earned, player.getGameProfile().getName());
            return;
        }
        long now = player.serverLevel().getGameTime();
        long before = target.expireAt();
        target.setLease(now, story.expiryAt(now));
        BeLoongCore.LOGGER.info(
                "[BeLoong] npc story '{}': lease renewed for player '{}' after milestone '{}' ({} -> {})",
                story.startAdvancement(), player.getGameProfile().getName(), earned, before,
                target.expireAt());
    }

    /** 该玩家在该维度里、该类型的私有分身（索引优先，S1）。 */
    private static NpcEntity findOwnDouble(ServerPlayer player, EntityType<?> type, ServerLevel level) {
        for (NpcEntity npc : LoadedNpcIndex.loadedIn(level)) {
            if (npc.getType() == type && player.getUUID().equals(npc.owner())) {
                return npc;
            }
        }
        return null;
    }

    /** 索引没命中时的回退：扫玩家当前维度（罕用，见调用点注释）。 */
    private static NpcEntity findOwnDoubleInScan(ServerPlayer player, EntityType<?> type) {
        for (Entity entity : player.serverLevel().getAllEntities()) {
            if (entity.getType() == type && entity instanceof NpcEntity npc
                    && player.getUUID().equals(npc.owner())) {
                return npc;
            }
        }
        return null;
    }

    /**
     * 这条剧情的"里程碑"集合：显式列表优先；否则推导为**剧情链上 start 与 end 之间**的全部进度。
     * <p>
     * ⚠️ 端点（{@code start} / {@code end}）**都不算**里程碑（用户裁定）：只有"推进"才续约。
     * 推导要走 {@code parent()} 链，故惰性做一次并按 reload stamp 缓存。
     * ⚠️ 链走不通（缺环/父关系被改）⇒ **返回空集**（fail-closed，宁可漏续约也不误续）。
     */
    private static Set<ResourceLocation> milestonesOf(MinecraftServer server, NpcStory story) {
        if (story.lease().renewAdvancements().isPresent()) {
            Set<ResourceLocation> chain = chainBetween(server, story);
            Set<ResourceLocation> kept = new LinkedHashSet<>();
            for (ResourceLocation id : story.lease().renewAdvancements().get()) {
                if (chain.contains(id)) {
                    kept.add(id);
                } else {
                    BeLoongCore.LOGGER.warn(
                            "[BeLoong] npc story '{}': renew_advancements lists '{}' which is not on the chain"
                                    + " between start and end — ignored", story.startAdvancement(), id);
                }
            }
            return kept;
        }
        int stamp = NpcStoryLoader.INSTANCE.reloadStamp();
        if (stamp != milestonesStamp) {
            MILESTONES.clear();
            milestonesStamp = stamp;
        }
        Set<ResourceLocation> cached = MILESTONES.get(story.startAdvancement());
        if (cached != null) {
            return cached;
        }
        Set<ResourceLocation> computed = chainBetween(server, story);
        MILESTONES.put(story.startAdvancement(), computed);
        return computed;
    }

    /**
     * 剧情链上 {@code start} 与 {@code end} **之间**的全部进度 id（两端点**不含**）。
     * <p>
     * 从 {@code end} 的父进度开始沿 {@code parent()} 上溯，直到遇到 {@code start}；走不到 ⇒ 返回空集。
     * 这正是 {@link #revokeStory} 走的那条链，只是方向相反（撤回是 end→start，这里是 end.parent→start）。
     */
    private static Set<ResourceLocation> chainBetween(MinecraftServer server, NpcStory story) {
        Set<ResourceLocation> between = new LinkedHashSet<>();
        AdvancementHolder holder = server.getAdvancements().get(story.endAdvancement());
        if (holder == null) {
            return between;
        }
        holder = holder.value().parent().map(server.getAdvancements()::get).orElse(null);
        while (holder != null && between.size() < MAX_CHAIN_STEPS) {
            if (holder.id().equals(story.startAdvancement())) {
                return between;         // 正常走通：端点被排除在外
            }
            between.add(holder.id());
            holder = holder.value().parent().map(server.getAdvancements()::get).orElse(null);
        }
        BeLoongCore.LOGGER.warn(
                "[BeLoong] npc story '{}': could not walk the parent chain from '{}' back to '{}'"
                        + " — offering NO lease-renewal milestones (fail-closed)",
                story.startAdvancement(), story.endAdvancement(), story.startAdvancement());
        return new LinkedHashSet<>();
    }

    /** 某玩家在某维度里、某类型的私有分身（退出清理用）。 */
    private List<NpcEntity> doublesOf(ServerPlayer player, ServerLevel level, EntityType<?> type) {
        List<NpcEntity> found = new ArrayList<>();
        // 性能 S1：走索引（原来遍历该维度全部已加载实体）。
        for (NpcEntity npc : LoadedNpcIndex.loadedIn(level)) {
            if (npc.getType() == type && player.getUUID().equals(npc.owner())) {
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
        // 性能 S2：撤回后必须让进度状态缓存立刻失效（同 reason 见 NpcDialogueStage#invalidate）。
        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(holder);
        if (!progress.hasProgress()) {
            return;
        }
        for (String criterion : progress.getCompletedCriteria()) {
            player.getAdvancements().revoke(holder, criterion);
        }
        NpcDialogueStage.invalidate(player);
    }


    /**
     * {@link #ensureDouble} 的结果：拿到的分身 + **它是不是这一次刚生成的**。
     * <p>
     * 调用方需要区分这两种情况 —— 只有"刚生成"才该播登场 CG（见 {@code onAdvancementEarned}）。
     */
    private record Ensured(NpcEntity npc, boolean spawned) {
    }

    /**
     * 供<b>序章处理器</b>用：确保该玩家的私有分身存在并返回它（建不出来时 {@code null}）。
     * <p>
     * ⚠️ 序章必须在**同一 tick 内**先确保分身、再授予 {@code root} —— 否则"已开始却没有分身"
     * 会被短暂置于"剧情已开始、却没有分身可看"的空窗（曾由已删除的 D21 放大成授予/撤回抖动；
     * 现在不再抖动，但这个空窗本身仍应避免 —— 玩家会看到锚点被隐藏而场上什么都没有）。
     */
    @Nullable
    static NpcEntity ensureDoubleFor(ServerPlayer player, EntityType<?> type, NpcStory story) {
        Ensured ensured = ensureDouble(player, player.serverLevel(), type, story);
        return ensured == null ? null : ensured.npc();
    }

    /**
     * 幂等确保"该玩家的私有分身"存在。
     * <p>
     * 一次 {@code getAllEntities()} 同时完成两件事：查重（已有 ⇒ 直接复用）与找锚点
     * （{@code owner == null} 的同类实例，多个取离玩家最近的那个）。事件路径很少触发，
     * 这里 O(实体数) 是可接受的；**每个追踪周期都会跑的 {@code visibleTo} 里绝不能这么做**。
     *
     * @return 分身 + 是否**这一次**刚生成（{@code spawned == false} 表示复用了既有的
     *         ⇒ 调用方**不要**重播 CG）；建不出来时返回 {@code null}
     */
    @Nullable
    private static Ensured ensureDouble(ServerPlayer player, ServerLevel level, EntityType<?> type, NpcStory story) {
        // 性能 S1（2026-10-14）：先用"已加载 NPC 索引"（通常个位数）——
        // 这里原来一次遍历该维度**全部**已加载实体，而只为找两只 NPC。
        AnchorScan scan = scanForDouble(player, type, LoadedNpcIndex.loadedIn(level));
        if (scan.existing() != null) {
            // 幂等：已经有自己的分身了 ⇒ 复用它。spawned=false 让调用方**不重播 CG**
            //（"重新获得起点进度但分身仍在"时剧情并没有重新开始，登场演出不该重演）。
            return new Ensured(scan.existing(), false);
        }
        if (scan.anchor() == null) {
            // ⚠️ 回退：索引只含**已加载**实体，而"锚点"决定新分身生成在哪里 ⇒ 宁可慢一次，也不许漏锚点。
            // 只在"索引里一个锚点都没找到"时执行（正常世界锚点就在入口，索引里一定有）。
            BeLoongCore.LOGGER.info(
                    "[BeLoong] npc story: no anchor for '{}' in the loaded-npc index — falling back to a full"
                            + " entity scan of {} (expected only right after a world/chunk reload)",
                    type, level.dimension().location());
            scan = scanForDouble(player, type, level.getAllEntities());
        }
        NpcEntity anchor = scan.anchor();
        int anchors = scan.anchors();

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
        return new Ensured(npc, true);
    }
}
