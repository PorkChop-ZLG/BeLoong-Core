package com.zonlong.beloong.mixin;

import com.afoxxvi.asteorbar.overlay.parts.PlayerHealthOverlay;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fixes AsteorBar health bar showing wrong value after dimension change
 * when player's max health exceeds 20.
 *
 * <p>Root cause: race condition between {@code ClientboundSetEntityDataPacket}
 * (health) and {@code ClientboundUpdateAttributesPacket} (max health). If entity
 * data arrives first, {@code LivingEntity.setHealth()} clamps health to default
 * max health (20). The attribute packet later corrects max health but health
 * stays clamped.</p>
 *
 * <p>This Mixin detects player entity changes and schedules a 20-tick delayed
 * health correction, giving the attribute packet time to sync.</p>
 */
@Mixin(value = PlayerHealthOverlay.class, remap = false)
public abstract class AsteorBarHealthFixMixin {

    @Unique
    private static int lastPlayerEntityId = -1;

    @Unique
    private static float lastTickHealth = 0;

    @Unique
    private static float healthFixTarget = 0;

    @Unique
    private static int healthFixDelay = 0;

    @Unique
    private static boolean healthFixPending = false;

    @Inject(method = "getParameters", at = @At("HEAD"))
    private void onGetParameters(Player player, CallbackInfoReturnable<?> cir) {
        if (player == null) {
            lastPlayerEntityId = -1;
            lastTickHealth = 0;
            healthFixPending = false;
            healthFixDelay = 0;
            return;
        }

        int currentId = player.getId();

        // Detect player entity change (dimension teleport / respawn)
        if (currentId != lastPlayerEntityId && lastPlayerEntityId != -1) {
            healthFixTarget = lastTickHealth;
            healthFixDelay = 20;
            healthFixPending = true;
        }

        lastPlayerEntityId = currentId;

        // Apply delayed health correction
        if (healthFixPending) {
            if (healthFixDelay > 0) {
                healthFixDelay--;
            } else {
                float currentHealth = player.getHealth();
                if (healthFixTarget > currentHealth + 1.0F) {
                    player.setHealth(Math.min(healthFixTarget, player.getMaxHealth()));
                }
                healthFixPending = false;
            }
        }

        lastTickHealth = player.getHealth();
    }
}
