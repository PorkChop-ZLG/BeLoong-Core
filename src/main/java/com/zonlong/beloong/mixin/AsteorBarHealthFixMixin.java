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
 * <p>Root cause: race condition between {@code ClientboundSetEntityDataPacket}
 * (health) and {@code ClientboundUpdateAttributesPacket} (max health). If entity
 * data arrives first, {@code LivingEntity.setHealth()} clamps health to default
 * max health (20). The attribute packet later corrects max health but health
 * stays clamped.</p>
 *
 * <p>This Mixin uses {@code @Redirect} on the single {@code Player.getHealth()}
 * call in {@code PlayerHealthOverlay.getParameters()}. When a player entity
 * change is detected (dimension teleport / respawn), it freezes the returned
 * health at the old entity's value for 20 ticks, then restores the underlying
 * health via {@code player.setHealth()} once max health attributes have synced.</p>
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

    @Redirect(
            method = "getParameters",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;getHealth()F"
            )
    )
    private float beloong$redirectGetHealth(Player player) {
        int currentId = player.getId();
        float actualHealth = player.getHealth();

        // Detect player entity change (dimension teleport / respawn)
        if (currentId != beloong$lastEntityId && beloong$lastEntityId != -1) {
            beloong$freezeTicksRemaining = 20;
            beloong$healthFixTarget = beloong$lastGoodHealth;
        }

        beloong$lastEntityId = currentId;

        // During freeze: return old entity's health so the bar doesn't flicker
        if (beloong$freezeTicksRemaining > 0) {
            beloong$freezeTicksRemaining--;
            return beloong$lastGoodHealth;
        }

        // Freeze ended — apply health fix if needed
        if (beloong$healthFixTarget > 0) {
            if (beloong$healthFixTarget > actualHealth + 1.0F) {
                player.setHealth(Math.min(beloong$healthFixTarget, player.getMaxHealth()));
            }
            beloong$healthFixTarget = 0;
            actualHealth = player.getHealth();
        }

        beloong$lastGoodHealth = actualHealth;
        return actualHealth;
    }
}
