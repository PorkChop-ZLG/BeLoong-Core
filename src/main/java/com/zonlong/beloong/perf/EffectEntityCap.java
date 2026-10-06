package com.zonlong.beloong.perf;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 特效实体"每维度在存量"上限护栏。上限为 {@code effect_entity_cap.maxPerDimension}（默认 200）。
 *
 * <p><b>为什么需要它（2026-10-06 悚域卡死事故）</b>：传奇怪物的
 * {@code legendary_monsters:camera_shake} 是"裸 {@code Entity} + {@code MobCategory.MISC} +
 * 唯一销毁路径是 {@code tick()} 自毁"的纯视觉特效实体。而 1.21.1 里
 * {@code Entity#tickCount} 是<b>关卡</b>写的（{@code ServerLevel#tickNonPassenger} 里
 * {@code p_entity.tickCount++} 后才是 {@code p_entity.tick()}），且实体 tick 还有一道
 * {@code inEntityTickingRange} 硬门 —— 于是"不在实体刻范围内"的实例：<b>永不被 tick、
 * tickCount 永不增长、discard() 永不执行</b>，却仍然被区块跟踪（{@code ChunkMap.entityMap}），
 * 因此既占内存、又被"每个移动包跑一次"的 {@code ChunkMap.move} 全量遍历。
 * 线上实例累积到 1,830,969 个后，玩家登录时单 tick 直接超过 60 秒被 ServerHangWatchdog 强杀。</p>
 *
 * <p><b>三条机制</b>：</p>
 * <ol>
 *   <li><b>唯一记账点</b>：所有实体的入世界都经过 NeoForge {@code EntityJoinLevelEvent}
 *       （挂在 {@code PersistentEntitySectionManager#addEntity} 的第一条语句），
 *       {@link EffectEntityJoinGate} 在这里判定并记账。模组静态工厂上的两个 Mixin
 *       （{@code mixin/legendarymonsters/CameraShakeCapMixin}、{@code DynamicCameraZoomCapMixin}）
 *       只是<b>预筛</b>：超限时提前取消，省掉实体构造，<b>不记账</b>（同一实体记两次会让放行速率减半）。</li>
 *   <li><b>读盘残骸闸门</b>：{@code loadedFromDisk()==true} 的实体除上限外还多一条
 *       {@code not-ticking} 规则——所在区块不在实体刻范围 ⇒ 永远不会 {@code tickCount++}、
 *       永不自毁，是纯死重，直接丢弃。实测某个旧存档单个区块里冻着 <b>310,530</b> 个该实体
 *       （外置实体文件 {@code c.3.1.mcc} 解压后 108 MB NBT），一进世界就卡死。取消 join 的语义是
 *       "从未入世界"（UUID/区块段/{@code entityMap}/tick 表都不登记），因此它也不会在下次存盘时
 *       被写回 ⇒ <b>旧存档自愈</b>。</li>
 *   <li><b>自愈清扫（{@code resample} 内）</b>：被放行的实体若事后冻结（玩家 {@code /tp}、掉线、
 *       死亡离开），它会永久占用预算、把该维度的抖动彻底堵死。因此在每次重采样遍历中顺带清扫
 *       本维度"不在实体刻范围"的受监视实体（每趟预算 = 上限）。由于判定式保证"冻结数 ≤ 上限"，
 *       一趟即可清空；清扫后立刻把计数扣除，使<b>当次召唤</b>就能拿到预算。</li>
 * </ol>
 *
 * <p><b>为什么不做"生成 +1 / 销毁 -1"的增量计数</b>：这种实体的销毁路径只有 {@code tick()} 一条，
 * 而冻结实例永不 tick ⇒ 计数只增不减，上限会永久饱和、抖动效果彻底消失且难以察觉。
 * 本类改为"低频重采样 + 各入口的保守预留"，只可能在采样窗口内少计，不会累积泄漏。</p>
 *
 * <p><b>硬界</b>：判定式恒为
 * {@code 采样存量 + 本窗口已放行(入世界) ≥ 上限 ⇒ 拒绝}，两个计数都在每次重采样时清零 ⇒
 * 任意时刻每维度每类型的<b>已加载数 ≤ 上限</b>成立范围是"<b>所有经实体管理器的入世界路径</b>"：
 * 读盘、模组工厂、{@code /summon}、数据包、其它模组 {@code addFreshEntity}、世界生成放置的实体
 * 全部计入（vanilla 里唯一不经过该事件的入世界路径是玩家，而玩家不在名单内）。</p>
 *
 * <p><b>开销</b>：重采样是 O(该维度已加载实体数) + 每趟最多"上限"次 {@code discard()}，
 * 但只在"确实有召唤/入世界"且"采样已过期"时发生；在非服务端主线程上自动降级为
 * "只用缓存计数、不做世界查询、不清扫"。</p>
 *
 * <p>判定只在服务端发生：非 {@link ServerLevel}（含集成服客户端）一律放行，
 * 因此 COMMON 配置在客户端那份值不参与判定，不需要 SERVER_SPEC 的同步待遇。</p>
 */
public final class EffectEntityCap {

    private EffectEntityCap() {}

    /** {@code sampledAt} / 日志时间戳的"从未发生"哨兵值。 */
    private static final long NEVER = Long.MIN_VALUE;

    /** 全部状态只在服务端主线程访问；加锁是为了兼容极少数非主线程的入世界路径。 */
    private static final Object LOCK = new Object();

    /** 弱键：维度卸载后状态可被回收（{@code Level} 用默认的 identity equals/hashCode）。 */
    private static final Map<ServerLevel, LevelState> STATES = new WeakHashMap<>();

    /** 配置列表 → 已解析的 {@link EntityType} 集合缓存（配置列表内容变化时失效）。 */
    private static List<? extends String> cachedConfigured = List.of();
    private static Set<EntityType<?>> cachedWatched = Set.of();

    /**
     * 入口一（<b>预筛，只判定不记账</b>）：模组静态工厂是否应取消这次新召唤。
     *
     * <p>它只是性能优化——超限时避免构造注定被丢弃的实体（实测 LM 会以约 28 次/tick 的频率刷）。
     * 真正的放行记账发生在 {@link #shouldRefuseJoin}。</p>
     *
     * @return {@code true} = 上限已满，调用方应取消召唤
     */
    public static boolean shouldRefuseSpawn(Level level, EntityType<?> type) {
        if (!(level instanceof ServerLevel serverLevel) || type == null) {
            return false;
        }
        if (!Config.EffectEntityCap.enabled.get()) {
            return false;
        }
        if (!watchedTypes().contains(type)) {
            return false;
        }

        final int cap = Config.EffectEntityCap.maxPerDimension.get();
        final long now = serverLevel.getGameTime();
        final boolean canQueryWorld = isServerThread(serverLevel);

        synchronized (LOCK) {
            LevelState state = STATES.computeIfAbsent(serverLevel, key -> new LevelState());

            // 采样过期就先重采样（含清扫）：因此判定所用的存量最多 rescanTicks 旧，
            // 且每次重采样会把"本周期已放行数"清零 ⇒ 上限是硬界。
            if (canQueryWorld && isStale(state, now)) {
                resample(serverLevel, state, now);
            }

            int sampled = state.counts.getOrDefault(type, 0);
            int used = sampled + state.joinsSinceSample.getOrDefault(type, 0);
            if (used >= cap) {
                state.suppressed.merge(type, 1L, Long::sum);
                logSpawnRefused(serverLevel, state, type, sampled, used, cap, now);
                return true;
            }
            // 刻意不记账：放行记账由入世界闸门统一负责（见类注释第 1 条）。
            return false;
        }
    }

    /**
     * 入口二（<b>唯一记账点</b>）：这个实体是否应被拒绝加入世界。
     *
     * <p>由 {@link EffectEntityJoinGate} 挂在 {@code EntityJoinLevelEvent} 上调用，覆盖
     * {@code loadedFromDisk()==true}（读盘）与 {@code false}（任何来源的新生成）两支。</p>
     *
     * <p>拒绝理由：</p>
     * <ol>
     *   <li>{@code over-cap}：本维度该类型已达上限。</li>
     *   <li>{@code not-ticking}（<b>只对读盘实体</b>）：所在区块不在实体刻范围 ⇒ 永不自毁的死重。
     *       新生成实体不做这条判定：它刚由 ticking 的实体创造，且世界加载瞬间的票据状态可能瞬时
     *       不准，误判会直接吃掉玩家眼前的抖动（万一真的落到非实体刻范围，随后的清扫会处理它）。</li>
     * </ol>
     *
     * @return {@code true} = 应取消这次 join（{@code EntityJoinLevelEvent#setCanceled}）
     */
    public static boolean shouldRefuseJoin(ServerLevel level, Entity entity, boolean loadedFromDisk) {
        if (entity == null) {
            return false;
        }
        if (!Config.EffectEntityCap.enabled.get()) {
            return false;
        }
        EntityType<?> type = entity.getType();
        if (type == null || !watchedTypes().contains(type)) {
            return false;
        }

        final int cap = Config.EffectEntityCap.maxPerDimension.get();
        final long now = level.getGameTime();
        final boolean canQueryWorld = isServerThread(level);
        final String source = loadedFromDisk ? "disk" : "new";

        synchronized (LOCK) {
            LevelState state = STATES.computeIfAbsent(level, key -> new LevelState());

            if (canQueryWorld && isStale(state, now)) {
                resample(level, state, now);
            }

            int sampled = state.counts.getOrDefault(type, 0);
            int used = sampled + state.joinsSinceSample.getOrDefault(type, 0);
            if (used >= cap) {
                state.refusedJoin.merge(type, 1L, Long::sum);
                logJoinRefused(level, state, type, sampled, used, cap, now, source, "over-cap");
                return true;
            }

            if (loadedFromDisk && canQueryWorld && !isEntityTicking(level, entity)) {
                state.refusedJoin.merge(type, 1L, Long::sum);
                state.refusedJoinNotTicking.merge(type, 1L, Long::sum);
                logJoinRefused(level, state, type, sampled, used, cap, now, source, "not-ticking");
                return true;
            }

            state.joinsSinceSample.merge(type, 1, Integer::sum);
            return false;
        }
    }

    /** 采样是否已过期（需要重采样）。 */
    private static boolean isStale(LevelState state, long now) {
        return state.sampledAt == NEVER
                || now - state.sampledAt >= Config.EffectEntityCap.rescanTicks.get();
    }

    /** 是否在服务端主线程（决定能不能做世界查询/清扫；见类注释的降级说明）。 */
    private static boolean isServerThread(ServerLevel level) {
        var server = level.getServer();
        return server != null && server.isSameThread();
    }

    /** 该实体所在区块当前是否处于"实体刻"范围（与 {@code ServerLevel#tick} 的门控同源）。 */
    private static boolean isEntityTicking(ServerLevel level, Entity entity) {
        return level.getChunkSource().chunkMap
                .getDistanceManager()
                .inEntityTickingRange(entity.chunkPosition().toLong());
    }

    /**
     * 重新统计该维度所有被监视类型的已加载实体数、开启新的放行窗口，并顺带清扫"冻结残骸"。
     *
     * <p>清扫为什么安全且够用（对应设计文档 D9）：判据与读盘闸门同一谓词
     * （不在实体刻范围 ⇒ 永远不会 {@code tickCount++}、永不自毁）；而判定式保证
     * "冻结数 ≤ 上限"，所以每趟预算取上限即可一趟清空。被清扫的实体是在
     * {@code resample} 完成遍历<b>之后</b>才 {@code discard()} 的——{@code getAllEntities()}
     * 是活视图，边遍历边删会 ConcurrentModificationException；也不可能误删"正在被 tick 的实体"
    * （正在 tick 的必然在实体刻范围内）。</p>
     */
    private static void resample(ServerLevel level, LevelState state, long now) {
        Set<EntityType<?>> watched = watchedTypes();
        int budget = Config.EffectEntityCap.maxPerDimension.get();
        List<Entity> sweep = null;

        state.counts.clear();
        state.joinsSinceSample.clear();
        for (Entity entity : level.getAllEntities()) {
            EntityType<?> entityType = entity.getType();
            if (!watched.contains(entityType)) {
                continue;
            }
            state.counts.merge(entityType, 1, Integer::sum);
            if (budget > 0 && !isEntityTicking(level, entity)) {
                if (sweep == null) {
                    sweep = new ArrayList<>();
                }
                sweep.add(entity);
                budget--;
            }
        }
        state.sampledAt = now;

        if (sweep == null) {
            return;
        }
        Set<EntityType<?>> sweptTypes = new HashSet<>();
        for (Entity entity : sweep) {
            EntityType<?> entityType = entity.getType();
            entity.discard();
            // 立刻扣减，使"当次召唤"就能拿到被释放的预算（否则要等下一个窗口）。
            state.counts.computeIfPresent(entityType, (key, value) -> value > 1 ? value - 1 : 0);
            state.swept.merge(entityType, 1L, Long::sum);
            sweptTypes.add(entityType);
        }
        for (EntityType<?> entityType : sweptTypes) {
            logSweep(level, state, entityType, now);
        }
    }

    /** 把配置里的实体类型 ID 列表解析成 {@link EntityType} 集合（带缓存）。 */
    private static Set<EntityType<?>> watchedTypes() {
        List<? extends String> configured = Config.EffectEntityCap.types.get();
        if (configured.equals(cachedConfigured)) {
            return cachedWatched;
        }
        Set<EntityType<?>> resolved = new HashSet<>();
        for (String id : configured) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key == null) {
                continue;
            }
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(key);
            if (type != null) {
                resolved.add(type);
            }
        }
        Set<EntityType<?>> frozen = Set.copyOf(resolved);
        cachedConfigured = List.copyOf(configured);
        cachedWatched = frozen;
        return frozen;
    }

    /** 输出"工厂预筛拦下新召唤"的 ASCII 锚点日志（本项目约定：日志一律英文 ASCII，中文只进注释）。 */
    private static void logSpawnRefused(ServerLevel level, LevelState state, EntityType<?> type,
                                        int sampled, int used, int cap, long now) {
        if (state.lastLogAt != NEVER
                && now - state.lastLogAt < Config.EffectEntityCap.logIntervalTicks.get()) {
            return;
        }
        state.lastLogAt = now;
        BeLoongCore.LOGGER.warn("[BeLoong] effect-entity-cap: dim={} type={} count={} used={} cap={} suppressed={}",
                level.dimension().location(),
                BuiltInRegistries.ENTITY_TYPE.getKey(type),
                sampled, used, cap, state.suppressed.getOrDefault(type, 0L));
    }

    /**
     * 输出"入世界被拒"的 ASCII 锚点日志。
     *
     * <p><b>为什么用对数里程碑而不是纯时间节流</b>：实测一次修复会在几秒内丢出 31 万个实体，
     * 而纯按 {@code logIntervalTicks}（默认 1200 = 60 秒）节流时，整个 burst 只会留下
     * <b>第一条</b>（{@code refused=1}）——运维最想看的"到底丢了多少"反而看不到。
     * 现在改为：累计拒绝数每跨过一个十倍数（1/10/100/1k/10k/100k/…）就打一条，
     * 因此一次 31 万的修复会留下约 6 行、量级一目了然；同时保留原有的时间节流，
     * 用于"长期零星拒绝"的场景。两者的计数都是累计值，不会因窗口重置而失真。</p>
     */
    private static void logJoinRefused(ServerLevel level, LevelState state, EntityType<?> type,
                                       int sampled, int used, int cap, long now,
                                       String source, String reason) {
        long total = state.refusedJoin.getOrDefault(type, 0L);
        long milestone = state.nextLogMilestone.getOrDefault(type, 1L);
        boolean milestoneHit = total >= milestone;
        boolean throttlePassed = state.lastJoinLogAt == NEVER
                || now - state.lastJoinLogAt >= Config.EffectEntityCap.logIntervalTicks.get();
        if (!milestoneHit && !throttlePassed) {
            return;
        }
        if (milestoneHit) {
            // 防御性上限：理论上到不了，但避免极端情况下乘 10 溢出。
            state.nextLogMilestone.put(type,
                    milestone > Long.MAX_VALUE / 10L ? Long.MAX_VALUE : milestone * 10L);
        }
        state.lastJoinLogAt = now;
        BeLoongCore.LOGGER.warn("[BeLoong] effect-entity-cap-join: dim={} type={} source={} count={} used={} cap={}"
                        + " refused={} not_ticking={} reason={}",
                level.dimension().location(),
                BuiltInRegistries.ENTITY_TYPE.getKey(type),
                source, sampled, used, cap,
                total,
                state.refusedJoinNotTicking.getOrDefault(type, 0L),
                reason);
    }

    /** 输出"冻结残骸被清扫"的 ASCII 锚点日志（自愈是否发生的观测量）。 */
    private static void logSweep(ServerLevel level, LevelState state, EntityType<?> type, long now) {
        if (state.lastSweepLogAt != NEVER
                && now - state.lastSweepLogAt < Config.EffectEntityCap.logIntervalTicks.get()) {
            return;
        }
        state.lastSweepLogAt = now;
        BeLoongCore.LOGGER.warn("[BeLoong] effect-entity-cap-sweep: dim={} type={} dropped={} reason=not-ticking",
                level.dimension().location(),
                BuiltInRegistries.ENTITY_TYPE.getKey(type),
                state.swept.getOrDefault(type, 0L));
    }

    /** 单个维度的护栏状态。 */
    private static final class LevelState {
        /** 最近一次采样的存量（按类型）。 */
        private final Map<EntityType<?>, Integer> counts = new HashMap<>();
        /** 本采样窗口内已放行的"入世界"数（按类型，两个入口共用）；每次重采样清零，故不会泄漏。 */
        private final Map<EntityType<?>, Integer> joinsSinceSample = new HashMap<>();
        /** 累计被工厂预筛拦下的召唤数（按类型），仅用于日志诊断。 */
        private final Map<EntityType<?>, Long> suppressed = new HashMap<>();
        /** 累计被入世界闸门拒绝的实体数（按类型），仅用于日志诊断。 */
        private final Map<EntityType<?>, Long> refusedJoin = new HashMap<>();
        /** 其中因"所在区块不在实体刻范围"被拒绝的数量（按类型）。 */
        private final Map<EntityType<?>, Long> refusedJoinNotTicking = new HashMap<>();
        /** 累计被清扫（事后冻结）的实体数（按类型），仅用于日志诊断。 */
        private final Map<EntityType<?>, Long> swept = new HashMap<>();
        /** 下一个日志里程碑（按类型，1/10/100/…）；见 {@code logJoinRefused} 的说明。 */
        private final Map<EntityType<?>, Long> nextLogMilestone = new HashMap<>();
        private long sampledAt = NEVER;
        private long lastLogAt = NEVER;
        private long lastJoinLogAt = NEVER;
        private long lastSweepLogAt = NEVER;
    }
}
