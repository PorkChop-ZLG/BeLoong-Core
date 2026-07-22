package com.zonlong.beloong.fluid;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class BeloongWaterContactTracker {

    private final Set<UUID> touchingPlayers = new HashSet<>();

    boolean update(UUID playerId, boolean touching) {
        if (!touching) {
            touchingPlayers.remove(playerId);
            return false;
        }

        return touchingPlayers.add(playerId);
    }
}
