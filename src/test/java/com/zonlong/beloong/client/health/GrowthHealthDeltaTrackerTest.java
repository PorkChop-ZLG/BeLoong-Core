package com.zonlong.beloong.client.health;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GrowthHealthDeltaTrackerTest {
    @ParameterizedTest
    @ValueSource(floats = {1.0F, 0.5F})
    void steadyGrowthDoesNotCreateHealingNumbers(float fraction) {
        var tracker = new GrowthHealthDeltaTracker();
        tracker.sample(0, 100 * fraction, 100, 0);
        for (int tick = 1; tick <= 240; tick++) {
            float maxHealth = 100 + tick * 0.0001F;
            assertEquals(0, tracker.sample(tick, maxHealth * fraction, maxHealth, tick));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void healthBeforeAttributesIsNotHealing(int separation) {
        var tracker = new GrowthHealthDeltaTracker();
        tracker.sample(0, 50, 100, 0);
        for (int tick = 1; tick <= 5; tick++) {
            boolean synced = tick >= 1 + separation;
            assertEquals(0, tracker.sample(tick, 51, synced ? 102 : 100, synced ? 1 : 0));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void attributesBeforeHealthIsNotDamageOrHealing(int separation) {
        var tracker = new GrowthHealthDeltaTracker();
        tracker.sample(0, 50, 100, 0);
        for (int tick = 1; tick <= 5; tick++) {
            assertEquals(0, tracker.sample(tick, tick >= 1 + separation ? 51 : 50, 102, 1));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "0, true, 2", "1, true, 2", "2, true, 2",
            "0, false, 2", "1, false, 2", "2, false, 2",
            "0, true, -2", "1, true, -2", "2, true, -2",
            "0, false, -2", "1, false, -2", "2, false, -2"
    })
    void realChangesSurviveEitherPacketOrder(int separation, boolean healthFirst, float delta) {
        var tracker = new GrowthHealthDeltaTracker();
        tracker.sample(0, 50, 100, 0);
        for (int tick = 1; tick <= 5; tick++) {
            boolean hasHealth = tick >= (healthFirst ? 1 : 1 + separation);
            boolean hasAttributes = tick >= (healthFirst ? 1 + separation : 1);
            assertEquals(tick == 3 ? delta : 0,
                    tracker.sample(tick, hasHealth ? 51 + delta : 50, hasAttributes ? 102 : 100,
                            hasAttributes ? 1 : 0));
        }
    }

    @ParameterizedTest
    @ValueSource(floats = {0.37123F, 0.827F})
    void repeatedFloatRescalingDoesNotAccumulateHealingNumbers(float fraction) {
        var tracker = new GrowthHealthDeltaTracker();
        float health = 100 * fraction;
        float maxHealth = 100;
        tracker.sample(0, health, maxHealth, 0);
        for (int tick = 1; tick <= 240; tick++) {
            float newMaxHealth = 100 + tick * 0.0001F;
            health = newMaxHealth * (health / maxHealth);
            maxHealth = newMaxHealth;
            assertEquals(0, tracker.sample(tick, health, maxHealth, tick));
        }
    }

    @Test
    void realHealingDuringGrowthIsPreserved() {
        assertEquals(2, afterWindow(50, 100, 53, 102));
    }

    @Test
    void realDamageDuringGrowthIsPreserved() {
        assertEquals(-2, afterWindow(50, 100, 49, 102));
    }

    @Test
    void fractionalHealingIsNotDiscarded() {
        assertEquals(0.25F, afterWindow(50, 100, 51.25F, 102));
    }

    @ParameterizedTest
    @CsvSource({"120, 50, 0", "120, 52, 2", "80, 50, 0", "80, 48, -2"})
    void nonGrowthMaxHealthChangesDoNotCreateOrSuppressNumbers(float maxHealth, float health, float delta) {
        var tracker = new GrowthHealthDeltaTracker();
        tracker.sample(0, 50, 100, 42);
        assertEquals(0, tracker.sample(1, health, maxHealth, 42));
        assertEquals(0, tracker.sample(2, health, maxHealth, 42));
        assertEquals(delta, tracker.sample(3, health, maxHealth, 42));
        assertEquals(0, tracker.sample(4, health, maxHealth, 42));
    }

    @Test
    void healingThenDamageWithoutGrowthRemainSeparate() {
        var tracker = new GrowthHealthDeltaTracker();
        tracker.sample(0, 50, 100, 0);
        assertEquals(0, tracker.sample(1, 52, 100, 0));
        assertEquals(0, tracker.sample(2, 50, 100, 0));
        assertEquals(2, tracker.sample(3, 50, 100, 0));
        assertEquals(-2, tracker.sample(4, 50, 100, 0));
        assertEquals(0, tracker.sample(5, 50, 100, 0));
    }

    @Test
    void integerHealingIsNotRoundedUpByFloatRescalingError() {
        assertEquals(2, afterWindow(500.123F, 1000, 502.12802F, 1000.01F));
    }

    @Test
    void shrinkingPreservesTheSameHealthFraction() {
        assertEquals(0, afterWindow(50, 100, 25, 50));
    }

    @Test
    void deathIsReportedWithoutWaitingForTheWindow() {
        var tracker = new GrowthHealthDeltaTracker();
        tracker.sample(0, 50, 100, 0);
        assertEquals(-50, tracker.sample(1, 0, 100, 0));
    }

    @Test
    void respawnTickResetDiscardsPendingAndQueuedValues() {
        var tracker = new GrowthHealthDeltaTracker();
        tracker.sample(100, 50, 100, 0);
        tracker.sample(101, 51, 100, 0);
        assertEquals(0, tracker.sample(0, 20, 20, 0));
        for (int tick = 1; tick <= 4; tick++) {
            assertEquals(0, tracker.sample(tick, 20, 20, 0));
        }
    }

    @Test
    void explicitEntityOrWorldResetDoesNotLeakOldValues() {
        var tracker = new GrowthHealthDeltaTracker();
        tracker.sample(0, 50, 100, 0);
        tracker.sample(1, 52, 100, 0);
        tracker.reset();
        for (int tick = 2; tick <= 7; tick++) {
            assertEquals(0, tracker.sample(tick, 1000, 1000, 0));
        }
    }

    @Test
    void duplicateTickDoesNotEmitTwice() {
        var tracker = new GrowthHealthDeltaTracker();
        tracker.sample(0, 50, 100, 0);
        tracker.sample(1, 52, 100, 0);
        tracker.sample(2, 50, 100, 0);
        assertEquals(2, tracker.sample(3, 50, 100, 0));
        assertEquals(0, tracker.sample(3, 50, 100, 0));
        assertEquals(-2, tracker.sample(4, 50, 100, 0));
    }

    @Test
    void invalidAttributesDoNotProduceSpuriousNumbers() {
        var tracker = new GrowthHealthDeltaTracker();
        tracker.sample(0, 50, 100, 0);
        assertEquals(0, tracker.sample(1, Float.NaN, 100, 0));
        assertEquals(0, tracker.sample(2, 0, 0, 0));
        assertEquals(0, tracker.sample(3, 50, 100, 0));
    }

    private static float afterWindow(float oldHealth, float oldMax, float health, float maxHealth) {
        var tracker = new GrowthHealthDeltaTracker();
        assertEquals(0, tracker.sample(0, oldHealth, oldMax, 0));
        assertEquals(0, tracker.sample(1, health, maxHealth, 1));
        assertEquals(0, tracker.sample(2, health, maxHealth, 1));
        float result = tracker.sample(3, health, maxHealth, 1);
        assertEquals(0, tracker.sample(4, health, maxHealth, 1));
        return result;
    }
}
