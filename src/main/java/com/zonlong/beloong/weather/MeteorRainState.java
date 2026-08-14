package com.zonlong.beloong.weather;

/**
 * 单个维度的流星火雨天气状态。
 * <p>
 * 相位迁移：{@code INACTIVE → ACTIVE → COOLDOWN → INACTIVE}。
 * 命令 {@code start} 可强制进入 {@code ACTIVE}，{@code stop} 可强制回到 {@code INACTIVE}。
 */
public class MeteorRainState {

    public enum Phase { INACTIVE, ACTIVE, COOLDOWN }

    private Phase phase = Phase.INACTIVE;
    private int ticksRemaining;

    public Phase phase() {
        return phase;
    }

    public int ticksRemaining() {
        return ticksRemaining;
    }

    public boolean isActive() {
        return phase == Phase.ACTIVE;
    }

    public void start(int durationTicks) {
        this.phase = Phase.ACTIVE;
        this.ticksRemaining = durationTicks;
    }

    public void enterCooldown(int cooldownTicks) {
        this.phase = Phase.COOLDOWN;
        this.ticksRemaining = cooldownTicks;
    }

    public void stop() {
        this.phase = Phase.INACTIVE;
        this.ticksRemaining = 0;
    }

    /**
     * 递减计时；若计时归零则迁移到下一相位。
     *
     * @return true 表示本 tick 发生了相位迁移（需要向客户端广播）
     */
    public boolean decrementAndMaybeTransition(int cooldownTicks) {
        if (phase == Phase.INACTIVE) {
            return false;
        }
        if (--ticksRemaining > 0) {
            return false;
        }
        if (phase == Phase.ACTIVE) {
            enterCooldown(cooldownTicks);
        } else {
            stop();
        }
        return true;
    }
}
