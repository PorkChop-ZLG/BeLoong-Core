package com.zonlong.beloong.fluid;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class BeloongWaterTriggerCooldown {

    private final Map<UUID, Long> lastTriggerTicks = new HashMap<>();

    boolean isReady(UUID playerId, long currentTick, int cooldownTicks) {
        Long lastTriggerTick = lastTriggerTicks.get(playerId);
        return cooldownTicks <= 0
                || lastTriggerTick == null
                || currentTick - lastTriggerTick >= cooldownTicks;
    }

    void recordTrigger(UUID playerId, long currentTick) {
        lastTriggerTicks.put(playerId, currentTick);
    }

    void forget(UUID playerId) {
        lastTriggerTicks.remove(playerId);
    }
}
