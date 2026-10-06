package com.zonlong.beloong.perf;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 特效实体"每维度在存量"上限护栏。两条入世界路径共用同一份账，硬上限为
 * {@code effect_entity_cap.maxPerDimension}（默认 200）。
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
 * <p><b>两条入口</b>：</p>
 * <ol>
 *   <li>{@link #shouldSuppress} —— 模组静态工厂的新召唤（{@code CameraShakeEntity#cameraShake}、
 *       {@code DynamicCameraZoomEntity#dynamicCameraZoom} 的两个重载），由
 *       {@code mixin/legendarymonsters/} 下的两个 Mixin 调用。</li>
 *   <li>{@link #shouldRefuseLoadedEntity} —— <b>存档读盘</b>，由 {@link EffectEntityJoinGate}
 *       挂在 NeoForge {@code EntityJoinLevelEvent} 上调用。这条路径专门处理"修复前就已堆积在
 *       旧存档里的存量"：实测某个存档单个区块里冻着 <b>310,530</b> 个该实体
 *       （外置实体文件 {@code c.3.1.mcc} 解压后 108 MB NBT），一进世界就卡死到必须强杀。</li>
 * </ol>
 *
 * <p><b>为什么不做"生成 +1 / 销毁 -1"的增量计数</b>：这种实体的销毁路径只有 {@code tick()} 一条，
 * 而冻结实例永不 tick ⇒ 计数只增不减，上限会永久饱和、抖动效果彻底消失且难以察觉。
 * 本类改为"低频重采样 + 各入口的保守预留"，只可能在采样窗口内少计，不会累积泄漏。</p>
 *
 * <p><b>硬界</b>：判定式恒为
 * {@code 采样存量 + 本窗口已放行(新召唤) + 本窗口已放行(读盘) ≥ 上限 ⇒ 拒绝}，
 * 三个计数都在每次重采样时清零 ⇒ 任意时刻每维度每类型的<b>已加载数 ≤ 上限</b>。</p>
 *
 * <p><b>开销</b>：重采样是 O(该维度已加载实体数)，但只在"确实有召唤/读盘"且"采样已过期"时发生
 * （正常战斗约每 20 tick 一次、几千个实体的遍历量级）；上限生效后规模立刻被压回上限值。
 * 在非服务端主线程（区块反序列化等）上自动降级：只用缓存计数、不做世界查询。</p>
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
     * 入口一：是否应当拒绝这一次<b>新召唤</b>（模组静态工厂）。
     *
     * @param level 召唤发生的维度（客户端 {@link Level} 直接放行）
     * @param type  被召唤的实体类型；不在配置名单内则放行
     * @return {@code true} = 上限已满，调用方应取消召唤
     */
    public static boolean shouldSuppress(Level level, EntityType<?> type) {
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

            // 采样过期就先重采样：因此判定所用的存量最多 rescanTicks 旧，
            // 且每次重采样会把两个"本周期已放行数"清零 ⇒ 上限是硬界。
            if (canQueryWorld && isStale(state, now)) {
                resample(serverLevel, state, now);
            }

            int sampled = state.counts.getOrDefault(type, 0);
            int used = sampled
                    + state.allowedSinceSample.getOrDefault(type, 0)
                    + state.loadedSinceSample.getOrDefault(type, 0);
            if (used >= cap) {
                state.suppressed.merge(type, 1L, Long::sum);
                logSuppressed(serverLevel, state, type, sampled, used, cap, now);
                return true;
            }
            state.allowedSinceSample.merge(type, 1, Integer::sum);
            return false;
        }
    }

    /**
     * 入口二：是否应当拒绝这个<b>从存档读盘</b>的实体加入世界。
     *
     * <p>两条拒绝理由：</p>
     * <ol>
     *   <li>{@code over-cap}：本维度该类型已达上限 ⇒ 直接丢弃。这是让"31 万存量"的旧存档
     *       能进得去的关键：只保留上限以内的量，其余一律不入世界。</li>
     *   <li>{@code not-ticking}：所在区块不在实体刻范围 ⇒ 它永远不会 {@code tickCount++}，
     *       即永远不会自毁，是纯死重（却仍占内存、仍参与配对遍历）。丢弃它既清存量，
     *       又避免这些残骸长期占满上限、导致该维度再也放不出新的抖动。</li>
     * </ol>
     *
     * <p>这项世界查询只在服务端主线程执行；若事件来自区块反序列化等后台线程，则跳过
     * {@code not-ticking} 判定（只保留 {@code over-cap} 判定），避免跨线程访问世界状态。</p>
     *
     * @return {@code true} = 应取消这次 join（{@code EntityJoinLevelEvent#setCanceled}）
     */
    public static boolean shouldRefuseLoadedEntity(ServerLevel level, Entity entity) {
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

        synchronized (LOCK) {
            LevelState state = STATES.computeIfAbsent(level, key -> new LevelState());

            if (canQueryWorld && isStale(state, now)) {
                resample(level, state, now);
            }

            int sampled = state.counts.getOrDefault(type, 0);
            int used = sampled
                    + state.allowedSinceSample.getOrDefault(type, 0)
                    + state.loadedSinceSample.getOrDefault(type, 0);
            if (used >= cap) {
                state.refusedLoaded.merge(type, 1L, Long::sum);
                logRefusedLoad(level, state, type, sampled, used, cap, now, "over-cap");
                return true;
            }

            if (canQueryWorld && !isEntityTicking(level, entity)) {
                state.refusedLoaded.merge(type, 1L, Long::sum);
                state.refusedLoadedNotTicking.merge(type, 1L, Long::sum);
                logRefusedLoad(level, state, type, sampled, used, cap, now, "not-ticking");
                return true;
            }

            state.loadedSinceSample.merge(type, 1, Integer::sum);
            return false;
        }
    }

    /** 采样是否已过期（需要重采样）。 */
    private static boolean isStale(LevelState state, long now) {
        return state.sampledAt == NEVER
                || now - state.sampledAt >= Config.EffectEntityCap.rescanTicks.get();
    }

    /** 是否在服务端主线程（决定能不能做世界查询；见类注释的降级说明）。 */
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

    /** 重新统计该维度所有被监视类型的已加载实体数，并开启新的放行窗口。 */
    private static void resample(ServerLevel level, LevelState state, long now) {
        Set<EntityType<?>> watched = watchedTypes();
        state.counts.clear();
        state.allowedSinceSample.clear();
        state.loadedSinceSample.clear();
        for (Entity entity : level.getAllEntities()) {
            EntityType<?> entityType = entity.getType();
            if (watched.contains(entityType)) {
                state.counts.merge(entityType, 1, Integer::sum);
            }
        }
        state.sampledAt = now;
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

    /** 输出"新召唤被拦"的 ASCII 锚点日志（本项目约定：日志一律英文 ASCII，中文只进注释）。 */
    private static void logSuppressed(ServerLevel level, LevelState state, EntityType<?> type,
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

    /** 输出"读盘存量被丢弃"的 ASCII 锚点日志（修复旧存档时的观测量）。 */
    private static void logRefusedLoad(ServerLevel level, LevelState state, EntityType<?> type,
                                       int sampled, int used, int cap, long now, String reason) {
        if (state.lastLoadLogAt != NEVER
                && now - state.lastLoadLogAt < Config.EffectEntityCap.logIntervalTicks.get()) {
            return;
        }
        state.lastLoadLogAt = now;
        BeLoongCore.LOGGER.warn("[BeLoong] effect-entity-cap-load: dim={} type={} count={} used={} cap={}"
                        + " refused={} not_ticking={} reason={}",
                level.dimension().location(),
                BuiltInRegistries.ENTITY_TYPE.getKey(type),
                sampled, used, cap,
                state.refusedLoaded.getOrDefault(type, 0L),
                state.refusedLoadedNotTicking.getOrDefault(type, 0L),
                reason);
    }

    /** 单个维度的护栏状态。 */
    private static final class LevelState {
        /** 最近一次采样的存量（按类型）。 */
        private final Map<EntityType<?>, Integer> counts = new HashMap<>();
        /** 本采样窗口内已放行的"新召唤"数（按类型）；每次重采样清零，故不会泄漏。 */
        private final Map<EntityType<?>, Integer> allowedSinceSample = new HashMap<>();
        /** 本采样窗口内已放行的"读盘加入"数（按类型）；每次重采样清零。 */
        private final Map<EntityType<?>, Integer> loadedSinceSample = new HashMap<>();
        /** 累计被丢弃的读盘实体数（按类型），仅用于日志诊断。 */
        private final Map<EntityType<?>, Long> refusedLoaded = new HashMap<>();
        /** 其中因"所在区块不在实体刻范围"被丢弃的数量（按类型）。 */
        private final Map<EntityType<?>, Long> refusedLoadedNotTicking = new HashMap<>();
        /** 累计被抑制的新召唤数（按类型），仅用于日志诊断。 */
        private final Map<EntityType<?>, Long> suppressed = new HashMap<>();
        private long sampledAt = NEVER;
        private long lastLogAt = NEVER;
        private long lastLoadLogAt = NEVER;
    }
}
