package com.zonlong.beloong.mixin;

import com.afoxxvi.asteorbar.overlay.parts.PlayerHealthOverlay;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = PlayerHealthOverlay.class, remap = false)
public abstract class AsteorBarHealthFixMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("BeLoong-AsteorBarHealthFix");

    @Unique
    private static int beloong$lastTickCount = -1;

    @Unique
    private static float beloong$lastGoodHealth = 0;

    @Unique
    private static int beloong$freezeTicksRemaining = 0;

    @Unique
    private static float beloong$healthFixTarget = 0;

    @Unique
    private static int beloong$debugCallCount = 0;

    @ModifyVariable(
            method = "getParameters",
            at = @At(value = "STORE", ordinal = 0)
    )
    private float beloong$fixHealth(float health, Player player) {
        beloong$debugCallCount++;
        if (beloong$debugCallCount % 100 == 1) {
            LOGGER.info("ALIVE callCount={} tickCount={} health={} maxHealth={}",
                    beloong$debugCallCount, player.tickCount, health, player.getMaxHealth());
        }

        int currentTickCount = player.tickCount;

        // tickCount resets when entity is recreated (dimension change / respawn).
        // lastTickCount > 100 guards against false trigger on first world join.
        if (currentTickCount < beloong$lastTickCount && beloong$lastTickCount > 100) {
            LOGGER.info("TICK RESET DETECTED: oldTick={} newTick={} oldHealth={} newHealth={}",
                    beloong$lastTickCount, currentTickCount, beloong$lastGoodHealth, health);
            beloong$freezeTicksRemaining = 20;
            beloong$healthFixTarget = beloong$lastGoodHealth;
        }

        beloong$lastTickCount = currentTickCount;

        if (beloong$freezeTicksRemaining > 0) {
            if (beloong$freezeTicksRemaining == 20) {
                LOGGER.info("FREEZE START: returning oldHealth={}", beloong$lastGoodHealth);
            }
            beloong$freezeTicksRemaining--;
            if (beloong$freezeTicksRemaining == 0) {
                LOGGER.info("FREEZE END: fixTarget={} currentHealth={} maxHealth={}",
                        beloong$healthFixTarget, health, player.getMaxHealth());
            }
            return beloong$lastGoodHealth;
        }

        if (beloong$healthFixTarget > 0) {
            if (beloong$healthFixTarget > health + 1.0F) {
                float corrected = Math.min(beloong$healthFixTarget, player.getMaxHealth());
                LOGGER.info("FIX: setHealth({}) maxHealth={}", corrected, player.getMaxHealth());
                player.setHealth(corrected);
            } else {
                LOGGER.info("SKIP FIX: diff={}", beloong$healthFixTarget - health);
            }
            beloong$healthFixTarget = 0;
            health = player.getHealth();
        }

        beloong$lastGoodHealth = health;
        return health;
    }
}
