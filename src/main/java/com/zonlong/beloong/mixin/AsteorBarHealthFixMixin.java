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
 * health correction based on {@code player.tickCount}, giving the attribute
 * packet time to sync.</p>
 */
@Mixin(value = PlayerHealthOverlay.class, remap = false)
public abstract class AsteorBarHealthFixMixin {

    @Unique
    private static int beloong$lastPlayerEntityId = -1;

    @Unique
    private static float beloong$lastTickHealth = 0;

    @Unique
    private static float beloong$healthFixTarget = 0;

    @Unique
    private static int beloong$healthFixTargetTick = 0;

    @Unique
    private static boolean beloong$healthFixPending = false;

    @Inject(method = "getParameters", at = @At("HEAD"))
    private void beloong$onGetParameters(Player player, CallbackInfoReturnable<?> cir) {
        if (player == null) {
            beloong$lastPlayerEntityId = -1;
            beloong$lastTickHealth = 0;
            beloong$healthFixTarget = 0;
            beloong$healthFixPending = false;
            beloong$healthFixTargetTick = 0;
            return;
        }

        int currentId = player.getId();

        // Detect player entity change (dimension teleport / respawn)
        if (currentId != beloong$lastPlayerEntityId && beloong$lastPlayerEntityId != -1) {
            beloong$healthFixTarget = beloong$lastTickHealth;
            beloong$healthFixTargetTick = player.tickCount + 20;
            beloong$healthFixPending = true;
        }

        beloong$lastPlayerEntityId = currentId;

        // Apply delayed health correction
        if (beloong$healthFixPending) {
            if (player.tickCount < beloong$healthFixTargetTick) {
                // Still waiting
            } else {
                float currentHealth = player.getHealth();
                if (beloong$healthFixTarget > currentHealth + 1.0F) {
                    player.setHealth(Math.min(beloong$healthFixTarget, player.getMaxHealth()));
                }
                beloong$healthFixPending = false;
            }
        }

        beloong$lastTickHealth = player.getHealth();
    }
}
