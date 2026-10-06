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
 * 特效实体"每维度在存量"上限护栏。
 *
 * <p><b>为什么需要它（2026-10-06 悚域卡死事故）</b>：传奇怪物的
 * {@code legendary_monsters:camera_shake} 是"裸 {@code Entity} + {@code MobCategory.MISC} +
 * 唯一销毁路径是 {@code tick()} 自毁"的纯视觉特效实体。而 1.21.1 里
 * {@code Entity#tickCount} 是<b>关卡</b>写的（{@code ServerLevel#tickNonPassenger} 里
 * {@code p_entity.tickCount++} 后才是 {@code p_entity.tick()}），且实体 tick 还有一道
 * {@code inEntityTickingRange} 硬门 —— 于是"不在实体刻范围内"的实例：<b>永不被 tick、
 * tickCount 永不增长、discard() 永不执行</b>，却仍然被区块跟踪（{@code ChunkMap.entityMap}），
 * 因此既占内存、又被"每个移动包跑一次"的 {@code ChunkMap.move} 全量遍历。
 * 实例上累积到 1,830,969 个后，玩家登录时单 tick 直接超过 60 秒被 ServerHangWatchdog 强杀。</p>
 *
 * <p><b>本类的做法</b>：在生成入口（LM 的静态工厂，见
 * {@code mixin/legendarymonsters/CameraShakeCapMixin} 与
 * {@code mixin/legendarymonsters/DynamicCameraZoomCapMixin}）按维度、按类型做硬上限：
 * 当"<i>已采样存量</i> + <i>本采样周期内已放行数</i> ≥ 上限"时拒绝召唤。
 * 由于每个允许的召唤都会记账、且记账每 {@code rescanTicks} 清零，
 * <b>任意时刻该维度该类型的已加载总数 ≤ 上限</b>（硬界，不是近似）。</p>
 *
 * <p><b>为什么不做"生成 +1 / 销毁 -1"的增量计数</b>：这种实体的销毁路径只有 {@code tick()} 一条，
 * 而冻结实例永不 tick ⇒ 计数只增不减，上限会永久饱和、抖动效果彻底消失且难以察觉。
 * 本类改为"低频重采样"，只可能在采样窗口内少计，不会累积泄漏。</p>
 *
 * <p><b>开销</b>：采样是 O(该维度已加载实体数)，但只在"确实有召唤尝试"且"采样已过期"时发生
 * （正常战斗约每 20 tick 一次、几千个实体的遍历量级）；上限生效后规模立刻被压回上限值。</p>
 *
 * <p>判定只在服务端发生：非 {@link ServerLevel}（含集成服客户端）一律放行，
 * 因此 COMMON 配置在客户端那份值不参与判定，不需要 SERVER_SPEC 的同步待遇。</p>
 */
public final class EffectEntityCap {

    private EffectEntityCap() {}

    /** {@code sampledAt} / {@code lastLogAt} 的"从未发生"哨兵值。 */
    private static final long NEVER = Long.MIN_VALUE;

    /** 全部状态只在服务端主线程访问；加锁是为了兼容极少数（区块生成等）非主线程的召唤路径。 */
    private static final Object LOCK = new Object();

    /** 弱键：维度卸载后状态可被回收（{@code Level} 用默认的 identity equals/hashCode）。 */
    private static final Map<ServerLevel, LevelState> STATES = new WeakHashMap<>();

    /** 配置列表 → 已解析的 {@link EntityType} 集合缓存（配置列表内容变化时失效）。 */
    private static List<? extends String> cachedConfigured = List.of();
    private static Set<EntityType<?>> cachedWatched = Set.of();

    /**
     * 是否应当拒绝这一次特效实体的召唤。
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

        synchronized (LOCK) {
            LevelState state = STATES.computeIfAbsent(serverLevel, key -> new LevelState());

            // 采样过期就先重采样：因此判定所用的存量最多 rescanTicks 旧，
            // 且每次重采样会把"本周期已放行数"清零 ⇒ 上限是硬界。
            if (state.sampledAt == NEVER
                    || now - state.sampledAt >= Config.EffectEntityCap.rescanTicks.get()) {
                resample(serverLevel, state, now);
            }

            int known = state.counts.getOrDefault(type, 0);
            int allowed = state.allowedSinceSample.getOrDefault(type, 0);
            if (known + allowed >= cap) {
                state.suppressed.merge(type, 1L, Long::sum);
                logSuppressed(serverLevel, state, type, known, cap, now);
                return true;
            }
            state.allowedSinceSample.put(type, allowed + 1);
            return false;
        }
    }

    /** 重新统计该维度所有被监视类型的已加载实体数，并开启新的放行窗口。 */
    private static void resample(ServerLevel level, LevelState state, long now) {
        Set<EntityType<?>> watched = watchedTypes();
        state.counts.clear();
        state.allowedSinceSample.clear();
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

    /** 输出 ASCII 锚点日志（本项目约定：日志一律英文 ASCII，中文只进注释）。 */
    private static void logSuppressed(ServerLevel level, LevelState state, EntityType<?> type,
                                      int known, int cap, long now) {
        if (state.lastLogAt != NEVER
                && now - state.lastLogAt < Config.EffectEntityCap.logIntervalTicks.get()) {
            return;
        }
        state.lastLogAt = now;
        BeLoongCore.LOGGER.warn("[BeLoong] effect-entity-cap: dim={} type={} count={} cap={} suppressed={}",
                level.dimension().location(),
                BuiltInRegistries.ENTITY_TYPE.getKey(type),
                known, cap, state.suppressed.getOrDefault(type, 0L));
    }

    /** 单个维度的护栏状态。 */
    private static final class LevelState {
        /** 最近一次采样的存量（按类型）。 */
        private final Map<EntityType<?>, Integer> counts = new HashMap<>();
        /** 本采样窗口内已放行的召唤数（按类型）；每次重采样清零，故不会泄漏。 */
        private final Map<EntityType<?>, Integer> allowedSinceSample = new HashMap<>();
        /** 累计被抑制的召唤数（按类型），仅用于日志诊断。 */
        private final Map<EntityType<?>, Long> suppressed = new HashMap<>();
        private long sampledAt = NEVER;
        private long lastLogAt = NEVER;
    }
}
