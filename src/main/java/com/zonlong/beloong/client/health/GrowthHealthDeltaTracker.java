package com.zonlong.beloong.client.health;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Separates growth-related proportional max-health changes from damage and healing.
 * Health and attribute packets can arrive on adjacent client ticks, so samples
 * are collected for two ticks before producing a number. This never writes health.
 */
public final class GrowthHealthDeltaTracker {
    private static final int SYNC_WINDOW_TICKS = 2;

    private final ArrayDeque<Float> ready = new ArrayDeque<>();
    private final List<Float> rawChanges = new ArrayList<>(3);
    private boolean initialized;
    private boolean pending;
    private boolean capacityChanged;
    private boolean growthChanged;
    private int lastTick;
    private int firstChangeTick;
    private float lastHealth;
    private float lastMaxHealth;
    private double lastGrowth;
    private float startHealth;
    private float startMaxHealth;

    public void reset() {
        initialized = false;
        pending = false;
        ready.clear();
        rawChanges.clear();
    }

    /** Returns one signed, unrounded number; Health Bars retains its own rounding. */
    public float sample(int tick, float health, float maxHealth, double growth) {
        if (!Float.isFinite(health) || !Float.isFinite(maxHealth) || !Double.isFinite(growth) || maxHealth <= 0) {
            reset();
            return 0;
        }
        health = Math.clamp(health, 0, maxHealth);
        if (!initialized || tick < lastTick) {
            reset();
            initialized = true;
            lastTick = tick;
            lastHealth = health;
            lastMaxHealth = maxHealth;
            lastGrowth = growth;
            return 0;
        }
        if (tick == lastTick) {
            return 0;
        }

        if (!pending && (health != lastHealth || maxHealth != lastMaxHealth || growth != lastGrowth)) {
            pending = true;
            capacityChanged = false;
            growthChanged = false;
            firstChangeTick = tick;
            startHealth = lastHealth;
            startMaxHealth = lastMaxHealth;
        }
        if (pending) {
            capacityChanged |= maxHealth != lastMaxHealth;
            growthChanged |= growth != lastGrowth;
            if (health != lastHealth) {
                rawChanges.add(health - lastHealth);
            }
            if (tick - firstChangeTick >= SYNC_WINDOW_TICKS || health == 0) {
                float tolerance = Math.max(1.0e-6F,
                        4 * Math.max(Math.ulp(startHealth), Math.ulp(health)));
                if (capacityChanged && growthChanged) {
                    double expectedHealth = (double) startHealth * maxHealth / startMaxHealth;
                    enqueue((float) (health - expectedHealth), tolerance);
                } else {
                    // Keep opposite changes separate: a heal followed by a hit
                    // must not disappear just because their net change is zero.
                    for (float change : rawChanges) {
                        enqueue(change, tolerance);
                    }
                }
                pending = false;
                rawChanges.clear();
            }
        }
        lastTick = tick;
        lastHealth = health;
        lastMaxHealth = maxHealth;
        lastGrowth = growth;
        return ready.isEmpty() ? 0 : ready.removeFirst();
    }

    private void enqueue(float delta, float tolerance) {
        if (Math.abs(delta) <= tolerance) {
            return;
        }
        // Proportional rescaling can leave a few ULPs above an integer. Do not
        // let Health Bars turn a genuine +2 into +3 with ceil().
        float nearestInteger = (float) Math.rint(delta);
        if (Math.abs(delta - nearestInteger) <= tolerance) {
            delta = nearestInteger;
        }
        ready.addLast(delta);
    }
}
