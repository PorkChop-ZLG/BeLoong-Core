package com.zonlong.beloong.ability;

import by.dragonsurvivalteam.dragonsurvival.network.flight.SyncWingsSpread;
import by.dragonsurvivalteam.dragonsurvival.registry.DSAttributes;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.FlightData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.entity_effects.AbilityEntityEffect;
import by.dragonsurvivalteam.dragonsurvival.server.handlers.ServerFlightHandler;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

public record AirStrikeEffect(
        LevelBasedValue baseDamage,
        LevelBasedValue speedFactor,
        LevelBasedValue collisionSize,
        LevelBasedValue minSpeed
) implements AbilityEntityEffect {

    static final ResourceKey<DamageType> AIR_STRIKE = ResourceKey.create(
            Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "air_strike")
    );

    /** 同时支持纯数字和 LevelBasedValue 对象格式 */
    private static final Codec<LevelBasedValue> FLEXIBLE_LBV = Codec.either(
            LevelBasedValue.CODEC,
            Codec.DOUBLE
    ).xmap(
            either -> either.map(lbv -> lbv, d -> LevelBasedValue.constant((float)(double)d)),
            Either::left
    );

    public static final MapCodec<AirStrikeEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            FLEXIBLE_LBV.fieldOf("base_damage").forGetter(AirStrikeEffect::baseDamage),
            FLEXIBLE_LBV.fieldOf("speed_factor").forGetter(AirStrikeEffect::speedFactor),
            FLEXIBLE_LBV.fieldOf("collision_size").forGetter(AirStrikeEffect::collisionSize),
            FLEXIBLE_LBV.fieldOf("min_speed").forGetter(AirStrikeEffect::minSpeed)
    ).apply(instance, AirStrikeEffect::new));

    @Override
    public void apply(final ServerPlayer dragon, final DragonAbilityInstance ability, final Entity target) {
        if (!(target instanceof ServerPlayer player)) {
            return;
        }

        if (!ServerFlightHandler.isGliding(player)) {
            return;
        }

        int level = ability.level();
        double speed = player.getDeltaMovement().length();
        float min = minSpeed.calculate(level);
        if (speed < min) {
            return;
        }

        float base = baseDamage.calculate(level);

        double weaponDamage = 0;
        var mainHand = player.getMainHandItem();
        if (!mainHand.isEmpty()) {
            for (ItemAttributeModifiers.Entry entry : mainHand.getAttributeModifiers().modifiers()) {
                if (entry.attribute().is(Attributes.ATTACK_DAMAGE)) {
                    weaponDamage += entry.modifier().amount();
                }
            }
        }
        var offHand = player.getOffhandItem();
        if (!offHand.isEmpty()) {
            for (ItemAttributeModifiers.Entry entry : offHand.getAttributeModifiers().modifiers()) {
                if (entry.attribute().is(Attributes.ATTACK_DAMAGE)) {
                    weaponDamage += entry.modifier().amount();
                }
            }
        }

        double abilityScale = player.getAttributeValue(DSAttributes.DRAGON_ABILITY_DAMAGE);
        float speedFactorVal = speedFactor.calculate(level);
        float damage = (float) ((base + weaponDamage) * speed * speedFactorVal * abilityScale);

        // actionbar display, throttled to every 5 ticks
        if (player.tickCount % 5 == 0) {
            player.displayClientMessage(
                    Component.translatable("message.beloong.air_strike.actionbar",
                            String.format("%.1f", speed * 20 * 3.6),
                            String.format("%.1f", damage)),
                    true);
        }

        double size = collisionSize.calculate(level);
        List<LivingEntity> hitEntities = player.level().getEntitiesOfClass(
                LivingEntity.class,
                player.getBoundingBox().inflate(size),
                e -> e != player && e.isAlive() && e.isPickable()
        );

        if (hitEntities.isEmpty()) {
            return;
        }

        Holder<DamageType> damageType = dragon.serverLevel().registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(AIR_STRIKE);

        boolean dealtDamage = false;
        for (LivingEntity hitTarget : hitEntities) {
            if (hitTarget.hurt(new DamageSource(damageType, dragon), damage)) {
                dealtDamage = true;
            }
        }

        if (dealtDamage) {
            FlightData.getData(player).areWingsSpread = false;
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                    new SyncWingsSpread(player.getId(), false));
        }
    }

    @Override
    public MapCodec<? extends AbilityEntityEffect> entityCodec() {
        return CODEC;
    }

    @Override
    public List<MutableComponent> getDescription(final Player dragon, final DragonAbilityInstance ability) {
        int level = ability.level();
        return List.of(
                Component.translatable("dragon_ability.beloong.air_strike.dynamic_desc",
                        String.format("%.1f", baseDamage.calculate(level)),
                        String.format("%.1f", speedFactor.calculate(level)),
                        String.format("%.1f", collisionSize.calculate(level)),
                        String.format("%.1f", minSpeed.calculate(level)))
        );
    }
}
