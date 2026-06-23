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
 * <p>Root cause: race condition between {@code ClientboundSetEntityDataPacket}
 * (health) and {@code ClientboundUpdateAttributesPacket} (max health). If entity
 * data arrives first, {@code LivingEntity.setHealth()} clamps health to default
 * max health (20). The attribute packet later corrects max health but health
 * stays clamped.</p>
 *
 * <p>This Mixin intercepts the {@code health} local variable in
 * {@code PlayerHealthOverlay.getParameters()} via {@code @ModifyVariable}.
 * Entity recreation is detected via {@code player.tickCount} reset. During a
 * 20-frame freeze period the old health value is returned for display, then
 * the underlying health is restored via {@code player.setHealth()}.</p>
 */
@Mixin(value = PlayerHealthOverlay.class, remap = false)
public abstract class AsteorBarHealthFixMixin {

    @Unique
    private static int beloong$lastTickCount = -1;

    @Unique
    private static float beloong$lastGoodHealth;

    @Unique
    private static int beloong$freezeTicksRemaining;

    @Unique
    private static float beloong$healthFixTarget;

    @ModifyVariable(
            method = "getParameters",
            at = @At(value = "STORE", ordinal = 0)
    )
    private float beloong$fixHealth(float health, Player player) {
        int currentTickCount = player.tickCount;

        // tickCount resets when entity is recreated (dimension change / respawn)
        if (currentTickCount < beloong$lastTickCount) {
            beloong$freezeTicksRemaining = 10;
            beloong$healthFixTarget = beloong$lastGoodHealth;
        }

        beloong$lastTickCount = currentTickCount;

        // During freeze: return old entity's health
        if (beloong$freezeTicksRemaining > 0) {
            beloong$freezeTicksRemaining--;
            return beloong$lastGoodHealth;
        }

        // Freeze ended — apply health fix if needed
        if (beloong$healthFixTarget > 0) {
            if (beloong$healthFixTarget > health + 1.0F) {
                player.setHealth(Math.min(beloong$healthFixTarget, player.getMaxHealth()));
            }
            beloong$healthFixTarget = 0;
            health = player.getHealth();
        }

        beloong$lastGoodHealth = health;
        return health;
    }
}
