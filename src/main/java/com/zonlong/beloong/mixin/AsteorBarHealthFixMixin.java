package com.zonlong.beloong.mixin;

import com.afoxxvi.asteorbar.overlay.parts.PlayerHealthOverlay;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Fixes AsteorBar health bar showing wrong value after dimension change
 * when player's max health exceeds 20.
 *
 * <p>Uses {@code @ModifyVariable} on the {@code health} local variable in
 * {@code PlayerHealthOverlay.getParameters()} to intercept and correct the
 * clamped health value. No vanilla class references in annotations.</p>
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

    @ModifyVariable(
            method = "getParameters",
            at = @At(value = "STORE", ordinal = 0)
    )
    private float beloong$fixHealth(float health, Player player) {
        beloong$debugCallCount++;
        if (beloong$debugCallCount % 100 == 1) {
            System.err.println("[AsteorBarHealthFix] ALIVE callCount="
                    + beloong$debugCallCount + " entityId=" + player.getId()
                    + " health=" + health);
        }

        int currentId = player.getId();

        // Detect player entity change (dimension teleport / respawn)
        if (currentId != beloong$lastEntityId && beloong$lastEntityId != -1) {
            System.err.println("[AsteorBarHealthFix] ENTITY CHANGE: oldId="
                    + beloong$lastEntityId + " newId=" + currentId
                    + " oldHealth=" + beloong$lastGoodHealth + " newHealth=" + health);
            beloong$freezeTicksRemaining = 20;
            beloong$healthFixTarget = beloong$lastGoodHealth;
        }

        beloong$lastEntityId = currentId;

        // During freeze: return old entity's health
        if (beloong$freezeTicksRemaining > 0) {
            if (beloong$freezeTicksRemaining == 20) {
                System.err.println("[AsteorBarHealthFix] FREEZE START");
            }
            beloong$freezeTicksRemaining--;
            if (beloong$freezeTicksRemaining == 0) {
                System.err.println("[AsteorBarHealthFix] FREEZE END target="
                        + beloong$healthFixTarget + " health=" + health
                        + " maxHealth=" + player.getMaxHealth());
            }
            return beloong$lastGoodHealth;
        }

        // Freeze ended — apply health fix
        if (beloong$healthFixTarget > 0) {
            if (beloong$healthFixTarget > health + 1.0F) {
                float corrected = Math.min(beloong$healthFixTarget, player.getMaxHealth());
                System.err.println("[AsteorBarHealthFix] FIX: setHealth(" + corrected + ")");
                player.setHealth(corrected);
            } else {
                System.err.println("[AsteorBarHealthFix] SKIP FIX: diff="
                        + (beloong$healthFixTarget - health));
            }
            beloong$healthFixTarget = 0;
            health = player.getHealth();
        }

        beloong$lastGoodHealth = health;
        return health;
    }
}
