package com.zonlong.beloong.structure;

import com.zonlong.beloong.Config;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 数据驱动的「结构药水效果」处理器：玩家进入已配置的结构时施加对应效果；
 * 条目可选带一个 {@code advancement} 门禁，玩家完成该进度后**不再施加**。
 *
 * <h2>施加与"续期"是两件事</h2>
 * 配置里的 {@code duration} 很短（出厂 100 tick = 5 秒），所以效果必须被**持续续期**才留得住。
 * 续期由两条路径完成，二者都只是「再调一次 {@code addEffect}」：
 * <ol>
 *   <li><b>区块变化</b>（{@link #onServerTick}）—— 玩家移动时不断刷新；</li>
 *   <li><b>自然到期</b>（{@link #onEffectExpired}）—— 站立不动时唯一的续期手段。
 *       NeoForge 在 {@code LivingEntity.tickEffects} 里发出 {@code MobEffectEvent.Expired}
 *       <b>之后</b>才执行 {@code iterator.remove()}，所以取消该事件就能把效果留住。</li>
 * </ol>
 *
 * <h2>⚠️ 关键：续期判定必须"按效果"，不能"按本次有没有施加过东西"</h2>
 * 曾经这里用 {@code boolean} 表示「本次检查是否施加过**任意**效果」，并据此决定要不要取消
 * <b>当前到期那一个效果</b>的移除。当同一结构里配了多条效果、而其中**只有一部分**被门禁挡住时，
 * 这个全局标志必然被污染，产生一个**自持的死循环**：
 * <pre>
 *   A 被门禁挡住（不该续期）→ 到期 → 事件触发
 *     → 重检：A 跳过，但未门禁的 B 被续到满 ⇒ "施加过东西" = true
 *       → 取消 A 的到期 ⇒ A 变成 duration = 0 的僵尸（属性修饰符仍在！）
 *         → 下一 tick 僵尸的 tick() 立刻返回 false，再次触发到期
 *           → 而 B 恰好又递减了 1 ⇒ 续期 B 再次成功 ⇒ 又取消 A 的到期
 *             → 无限循环（表现为 HUD 显示 0s 但永不消失，出结构才消失）
 * </pre>
 * 之所以"每 tick 都能续期成功"：{@code MobEffectInstance.update()} 只要新实例的持续时间**更长**
 * 就返回 {@code true}，而未门禁效果每 tick 递减 1，所以每次补满都是"升级"。
 * <p>
 * ⇒ 因此 {@link #doCheckAndApply} 返回的是「本次**成功施加了哪些**效果」的集合，
 * 两个移除类事件只问「**这一个**效果在不在集合里」。
 *
 * <h2>NeoForge 侧的事实（对着 21.1.236 的补丁核对过）</h2>
 * <ul>
 *   <li>{@code MobEffectEvent.Expired} <b>只有</b> {@code LivingEntity.tickEffects} 一处发；
 *       取消它可跳过 {@code iterator.remove()} 与 {@code onEffectRemoved()}。</li>
 *   <li>{@code MobEffectEvent.Remove} <b>在自然到期时不会发</b>，只在
 *       {@code removeAllEffects()}（{@code /effect clear}）、{@code removeEffect(Holder)} 与
 *       {@code removeEffectsCuredBy(EffectCure)}（牛奶 / 图腾，NeoForge 新增）三处发。
 *       ⇒ 到期只有 {@link #onEffectExpired} 一条路可守。</li>
 *   <li>{@code addEffect} 会先过 {@code MobEffectEvent.Applicable}；被拒时它<b>直接返回 false 且不施加</b>
 *       ⇒ 判定一律以返回值为准。</li>
 * </ul>
 *
 * <h2>门禁的边界</h2>
 * 进度 id 解析不到时**按"未完成"处理（fail-open）并只告警一次** —— 不会因为写错 id 就静默失效，
 * 也不会每 tick 刷屏。另见 {@link #onAdvancementEarned}：门禁刚被满足时立刻撤掉效果，
 * 而不是等它自然到期（最多一个 {@code duration}）。
 */
public class StructureEffectHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(StructureEffectHandler.class);

    private Set<ResourceKey<MobEffect>> watchedEffects = Set.of();
    private int lastWatchedHash;
    private final Map<UUID, ChunkPos> playerLastChunk = new HashMap<>();
    private boolean refreshing;

    /** 已告警过的未知进度 id —— fail-open 是静默失败面，至少要留一条线索，但不重复刷屏。 */
    private final Set<ResourceLocation> warnedUnknownAdvancements = new HashSet<>();

    private void refreshWatchedEffects() {
        int currentHash = Config.StructureEffects.watchedEffects.get().hashCode();
        if (currentHash == lastWatchedHash) return;

        Set<ResourceKey<MobEffect>> newWatched = new HashSet<>();
        for (String effectId : Config.StructureEffects.watchedEffects.get()) {
            try {
                ResourceLocation loc = ResourceLocation.parse(effectId.trim());
                newWatched.add(ResourceKey.create(Registries.MOB_EFFECT, loc));
            } catch (Exception e) {
                LOGGER.warn("[BeLoong] structure_effects: invalid watched effect ID: {}", effectId.trim());
            }
        }
        this.watchedEffects = Collections.unmodifiableSet(newWatched);
        this.lastWatchedHash = currentHash;
    }

    // ==================== 查询层：玩家当前所在结构配了哪些效果 ====================

    /**
     * 收集玩家**当前所在结构**里配置的全部条目，<b>不看进度门禁</b>。
     *
     * <p>只看"玩家真的在这个结构里"的条目：{@code getStructureAt} 已保证坐标落在该结构的
     * {@code StructureStart} 内，后面的 AABB 相交判断是与之等价的二次确认（保留原逻辑）。
     */
    private List<EffectEntry> collectPresentEntries(ServerPlayer player) {
        var configMap = StructureEffectLoader.INSTANCE.getConfigMap();
        if (configMap.isEmpty()) return List.of();

        var structureManager = player.serverLevel().structureManager();
        var structureRegistry = player.serverLevel().registryAccess()
                .registryOrThrow(Registries.STRUCTURE);
        List<EffectEntry> present = new ArrayList<>();

        for (var configEntry : configMap.entrySet()) {
            Structure structure = structureRegistry.get(configEntry.getKey());
            if (structure == null) continue;

            StructureStart start = structureManager.getStructureAt(player.blockPosition(), structure);
            if (start == null || !start.isValid()) continue;

            BoundingBox bb = start.getBoundingBox();
            AABB aabb = new AABB(bb.minX(), bb.minY(), bb.minZ(),
                    bb.maxX() + 1, bb.maxY() + 1, bb.maxZ() + 1);
            if (!player.getBoundingBox().intersects(aabb)) continue;

            present.addAll(configEntry.getValue());
        }
        return present;
    }

    /** 收集玩家当前**应得**的条目：在结构内<b>且</b>门禁已通过。 */
    private List<EffectEntry> collectWantedEntries(ServerPlayer player) {
        List<EffectEntry> wanted = new ArrayList<>();
        for (EffectEntry ee : collectPresentEntries(player)) {
            if (ee.advancement().isPresent() && isAdvancementDone(player, ee.advancement().get())) {
                continue;
            }
            wanted.add(ee);
        }
        return wanted;
    }

    private static Set<ResourceKey<MobEffect>> effectKeys(List<EffectEntry> entries) {
        Set<ResourceKey<MobEffect>> keys = new HashSet<>();
        for (EffectEntry ee : entries) {
            ResourceKey<MobEffect> key = ee.effect().getKey();
            if (key != null) keys.add(key);
        }
        return keys;
    }

    /**
     * 进度是否已完成。
     *
     * <p>进度 id 解析不到（写错、或该进度不在当前数据包里）时按<b>未完成</b>处理 —— 即门禁失效、
     * 照常施加，并只告警一次。这是有意的 fail-open：宁可效果多给，也不要因为一个拼写错误
     * 让整条配置静默地什么都不做。
     */
    private boolean isAdvancementDone(ServerPlayer player, ResourceLocation advancementId) {
        var holder = player.server.getAdvancements().get(advancementId);
        if (holder == null) {
            if (warnedUnknownAdvancements.add(advancementId)) {
                LOGGER.warn("[BeLoong] structure_effects: unknown advancement '{}' used as a gate; "
                        + "treating it as not done (the effect will keep being applied)", advancementId);
            }
            return false;
        }
        return player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    // ==================== 施加层 ====================

    /**
     * 重入保护下的结构重检。
     *
     * @return 本次<b>成功施加</b>的效果集合。判断"某个到期效果要不要续期"必须用它做成员判断，
     *         <b>不能</b>用"集合是否为空" —— 见类文档里的自持死循环。
     */
    private Set<ResourceKey<MobEffect>> checkAndApply(ServerPlayer player) {
        if (refreshing) return Set.of();
        refreshing = true;
        try {
            return doCheckAndApply(player);
        } finally {
            refreshing = false;
        }
    }

    /** 施加玩家当前应得的全部效果；{@code addEffect} 返回 {@code true} 才算施加成功。 */
    private Set<ResourceKey<MobEffect>> doCheckAndApply(ServerPlayer player) {
        Set<ResourceKey<MobEffect>> applied = new HashSet<>();

        for (EffectEntry ee : collectWantedEntries(player)) {
            if (player.addEffect(new MobEffectInstance(
                    ee.effect(), ee.durationTicks(), ee.amplifier(),
                    false, ee.showParticles(), true
            ))) {
                ResourceKey<MobEffect> key = ee.effect().getKey();
                if (key != null) applied.add(key);
            }
        }
        return applied;
    }

    // ==================== 事件层 ====================

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ChunkPos currentChunk = player.chunkPosition();
            ChunkPos lastChunk = playerLastChunk.get(player.getUUID());

            if (lastChunk == null || !currentChunk.equals(lastChunk)) {
                playerLastChunk.put(player.getUUID(), currentChunk);
                checkAndApply(player);
            }
        }
    }

    /**
     * 自然到期：玩家仍在结构内且这个效果**应得**时，续期并取消移除。
     *
     * <p>判定是<b>按效果</b>的（{@code contains(effectKey)}）。若写成"本次施加过任意效果就取消"，
     * 被门禁挡住的那个效果会以 duration = 0 的僵尸形态永久留下（且属性修饰符仍在）——
     * 详见类文档。
     */
    @SubscribeEvent
    public void onEffectExpired(MobEffectEvent.Expired event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        refreshWatchedEffects();
        ResourceKey<MobEffect> effectKey = event.getEffectInstance().getEffect().getKey();
        if (effectKey != null && watchedEffects.contains(effectKey)) {
            if (checkAndApply(player).contains(effectKey)) {
                event.setCanceled(true);
            }
        }
    }

    /**
     * 被显式移除（牛奶、{@code /effect clear}、指令）：玩家仍在结构内且这个效果应得时撤销移除。
     *
     * <p>⚠️ NeoForge <b>不会</b>在自然到期时发 {@code Remove}（只有 {@code Expired}），
     * 所以这里不是到期的第二条保险，只覆盖"主动移除"。
     *
     * <p>与 {@link #onEffectExpired} 的判据**故意不同**：这里问的是「这个效果<b>该不该在</b>」，
     * 而不是「刚刚有没有被重新施加」。差别在效果正好处于满时长时：那种情况下 {@code addEffect}
     * 不会产生任何变化、返回 {@code false}，若照到期那条路的判据就会放行牛奶把它清掉。
     * 移除路径不需要恢复时长（效果还在），所以用"应得"这个更直接的判据即可。
     */
    @SubscribeEvent
    public void onEffectRemoved(MobEffectEvent.Remove event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        refreshWatchedEffects();
        ResourceKey<MobEffect> effectKey = event.getEffect().getKey();
        if (effectKey != null && watchedEffects.contains(effectKey)) {
            // 前一个条件短路掉绝大多数情况；只有它不成立时才算一次"应得"（会再扫一遍结构配置）
            if (checkAndApply(player).contains(effectKey) || isWanted(player, effectKey)) {
                event.setCanceled(true);
            }
        }
    }

    /** 这个效果是否属于玩家当前**应得**的集合（在结构内且门禁已通过）。 */
    private boolean isWanted(ServerPlayer player, ResourceKey<MobEffect> effectKey) {
        return effectKeys(collectWantedEntries(player)).contains(effectKey);
    }

    /**
     * 进度**刚好完成** ⇒ <b>立刻</b>撤掉"因为这个门禁而不再应得"的效果。
     *
     * <p>没有这一步就只能等效果自然到期（最多一个 {@code duration}），语义上不算错，
     * 但玩家已经完成了进度却还要挂着惩罚，体验上说不通。
     *
     * <p><b>为什么盯 {@code AdvancementProgressEvent}，而不是语义更正、只发一次的
     * {@code AdvancementEarnEvent}</b>：NeoForge 把后者 post 在 {@code PlayerAdvancements.award}
     * 的 {@code display().ifPresent(...)} lambda <b>内部</b>
     * （见 {@code patches/net/minecraft/server/PlayerAdvancements.java.patch}）⇒
     * <b>省略了 {@code display} 的隐形进度永远不会触发它</b>。而整合包的"剧情门禁"进度往往正是
     * 隐形的，那种情况下这个增强会静默失效（效果要等最多一个 duration 才自然消失）。
     * {@code AdvancementProgressEvent} 在同一个方法里、{@code display} 之外发出 ⇒ 两类进度都覆盖。
     *
     * <p>代价：它**每次判据达成都发**、REVOKE 也发 ⇒ 自己筛 {@code ProgressType.GRANT} +
     * {@code isDone()}，并用 {@link #isUsedAsGate} 先做廉价短路。
     */
    @SubscribeEvent
    public void onAdvancementProgressed(AdvancementEvent.AdvancementProgressEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getProgressType() != AdvancementEvent.AdvancementProgressEvent.ProgressType.GRANT) return;
        // 每次判据达成都发 ⇒ 只在"这一步刚好让它完成"时才动手
        if (!event.getAdvancementProgress().isDone()) return;

        ResourceLocation earned = event.getAdvancement().id();
        // 廉价预筛：绝大多数进度与门禁无关，不必为它们去查结构（那要遍历全部结构配置 + 查 StructureStart）
        if (!isUsedAsGate(earned)) return;

        revokeGatedEffects(player, earned);
    }

    /** 这个进度 id 是否被任何条目当作门禁用过。只看配置、不查世界，成本极低。 */
    private boolean isUsedAsGate(ResourceLocation advancementId) {
        for (List<EffectEntry> entries : StructureEffectLoader.INSTANCE.getConfigMap().values()) {
            for (EffectEntry ee : entries) {
                if (ee.advancement().filter(advancementId::equals).isPresent()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 撤掉"门禁恰好是 {@code earned}、且当前已不再应得"的效果。
     *
     * <p>只处理**门禁恰好是这个进度**的条目；用"当前应得集合"判断，所以同一个效果若还有别的
     * （门禁已通过的）条目供着它，就不会被误删。
     */
    private void revokeGatedEffects(ServerPlayer player, ResourceLocation earned) {
        Set<ResourceKey<MobEffect>> wanted = effectKeys(collectWantedEntries(player));

        for (EffectEntry ee : collectPresentEntries(player)) {
            if (ee.advancement().filter(earned::equals).isEmpty()) continue;

            ResourceKey<MobEffect> key = ee.effect().getKey();
            if (key == null || wanted.contains(key)) continue;

            // 走常规移除路径：onEffectRemoved 会重检一次，此时门禁已通过 ⇒ 不会把它留下
            if (player.removeEffect(ee.effect())) {
                LOGGER.debug("[BeLoong] structure_effects: removed effect '{}' revoked by advancement '{}'",
                        key.location(), earned);
            }
        }
    }

    @SubscribeEvent
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        playerLastChunk.remove(player.getUUID());
        checkAndApply(player);
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        playerLastChunk.remove(player.getUUID());
        checkAndApply(player);
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        playerLastChunk.remove(player.getUUID());
        checkAndApply(player);
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        playerLastChunk.remove(event.getEntity().getUUID());
    }
}
