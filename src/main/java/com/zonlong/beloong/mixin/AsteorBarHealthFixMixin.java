package com.zonlong.beloong.mixin;

import com.afoxxvi.asteorbar.overlay.parts.PlayerHealthOverlay;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fixes AsteorBar health bar showing wrong value after dimension change
 * when player's max health exceeds 20.
 *
 * <p>Uses {@code @Redirect} on the single {@code Player.getHealth()} call
 * in {@code PlayerHealthOverlay.getParameters()} to freeze display during
 * dimension changes and restore correct health after attribute sync.</p>
 */
@Mixin(value = PlayerHealthOverlay.class, remap = false)
public abstract class AsteorBarHealthFixMixin {

    @Unique
    private static int beloong$lastEntityId = -1;

    @Unique
    private static float beloong$lastGoodHealth = 0;

    @Unique
    private static int beloong$freezeTicksRemaining = 0;

    @Unique
    private static float beloong$healthFixTarget = 0;

    @Unique
    private static int beloong$debugCallCount = 0;

    @Redirect(
            method = "getParameters",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;getHealth()F"
            )
    )
    private float beloong$redirectGetHealth(Player player) {
        // DEBUG: log every 100th call to confirm Mixin is executing
        beloong$debugCallCount++;
        if (beloong$debugCallCount % 100 == 1) {
            System.err.println("[AsteorBarHealthFix] DEBUG alive, callCount="
                    + beloong$debugCallCount + " entityId=" + player.getId()
                    + " health=" + player.getHealth());
        }

        int currentId = player.getId();
        float actualHealth = player.getHealth();

        // Detect player entity change (dimension teleport / respawn)
        if (currentId != beloong$lastEntityId && beloong$lastEntityId != -1) {
            System.err.println("[AsteorBarHealthFix] ENTITY CHANGE DETECTED: oldId="
                    + beloong$lastEntityId + " newId=" + currentId
                    + " oldHealth=" + beloong$lastGoodHealth
                    + " newHealth=" + actualHealth
                    + " freezeTicks=20");
            beloong$freezeTicksRemaining = 20;
            beloong$healthFixTarget = beloong$lastGoodHealth;
        }

        beloong$lastEntityId = currentId;

        // During freeze: return old entity's health
        if (beloong$freezeTicksRemaining > 0) {
            if (beloong$freezeTicksRemaining == 20) {
                System.err.println("[AsteorBarHealthFix] FREEZE START: returning oldHealth="
                        + beloong$lastGoodHealth + " instead of actualHealth=" + actualHealth);
            }
            beloong$freezeTicksRemaining--;
            if (beloong$freezeTicksRemaining == 0) {
                System.err.println("[AsteorBarHealthFix] FREEZE END: ticks elapsed, "
                        + "healthFixTarget=" + beloong$healthFixTarget
                        + " currentHealth=" + actualHealth
                        + " maxHealth=" + player.getMaxHealth());
            }
            return beloong$lastGoodHealth;
        }

        // Freeze ended — apply health fix if needed
        if (beloong$healthFixTarget > 0) {
            System.err.println("[AsteorBarHealthFix] APPLYING FIX: target="
                    + beloong$healthFixTarget + " actualHealth=" + actualHealth
                    + " maxHealth=" + player.getMaxHealth());
            if (beloong$healthFixTarget > actualHealth + 1.0F) {
                float corrected = Math.min(beloong$healthFixTarget, player.getMaxHealth());
                System.err.println("[AsteorBarHealthFix] setHealth(" + corrected + ")");
                player.setHealth(corrected);
            } else {
                System.err.println("[AsteorBarHealthFix] SKIP FIX: diff too small ("
                        + (beloong$healthFixTarget - actualHealth) + " <= 1.0)");
            }
            beloong$healthFixTarget = 0;
            actualHealth = player.getHealth();
        }

        beloong$lastGoodHealth = actualHealth;
        return actualHealth;
    }
}
