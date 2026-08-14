package com.zonlong.beloong.weather;

import com.zonlong.beloong.Config;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流星火雨天气状态机（单例，服务端权威）。
 * <p>
 * 按维度维护 {@link MeteorRainState}，状态为内存态、不跨重启持久化。
 * 随机触发与命令触发都通过本类统一管理。
 */
public class MeteorRainManager {

    public static final MeteorRainManager INSTANCE = new MeteorRainManager();

    private final Map<ResourceKey<Level>, MeteorRainState> states = new ConcurrentHashMap<>();

    private MeteorRainManager() {}

    /** 获取（不存在则创建）某维度的天气状态。 */
    public MeteorRainState stateFor(ResourceKey<Level> dimension) {
        return states.computeIfAbsent(dimension, k -> new MeteorRainState());
    }

    /** 强制开始：重置为 ACTIVE，随机取 [minDuration, maxDuration] 时长。 */
    public void start(ServerLevel level) {
        MeteorRainState state = stateFor(level.dimension());
        int duration = randomBetween(
                Config.MeteorRain.minDurationTicks.get(),
                Config.MeteorRain.maxDurationTicks.get(),
                level);
        state.start(duration);
    }

    /** 强制停止：回到 INACTIVE。 */
    public void stop(ServerLevel level) {
        stateFor(level.dimension()).stop();
    }

    public boolean isActive(ResourceKey<Level> dimension) {
        return stateFor(dimension).isActive();
    }

    /**
     * 推进一次状态机（由 {@code ServerTickEvent.Post} 节流后调用）。
     *
     * @return true 表示相位发生迁移（需要向客户端广播）
     */
    public boolean tick(ServerLevel level) {
        MeteorRainState state = stateFor(level.dimension());
        switch (state.phase()) {
            case INACTIVE -> {
                if (level.getRandom().nextDouble() < Config.MeteorRain.triggerChance.get()) {
                    start(level);
                    return true;
                }
                return false;
            }
            case ACTIVE, COOLDOWN -> {
                return state.decrementAndMaybeTransition(Config.MeteorRain.cooldownTicks.get());
            }
            default -> {
                return false;
            }
        }
    }

    private static int randomBetween(int min, int max, ServerLevel level) {
        if (max <= min) {
            return min;
        }
        return min + level.getRandom().nextInt(max - min + 1);
    }
}
