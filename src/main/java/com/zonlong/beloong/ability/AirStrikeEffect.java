package com.zonlong.beloong.ability;

import by.dragonsurvivalteam.dragonsurvival.network.flight.SyncWingsSpread;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.FlightData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.entity_effects.AbilityEntityEffect;
import by.dragonsurvivalteam.dragonsurvival.server.handlers.ServerFlightHandler;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

public record AirStrikeEffect(
        LevelBasedValue baseDamage,
        double speedFactor,
        double minSpeed
) implements AbilityEntityEffect {

    public static final MapCodec<AirStrikeEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            LevelBasedValue.CODEC.fieldOf("base_damage").forGetter(AirStrikeEffect::baseDamage),
            Codec.DOUBLE.fieldOf("speed_factor").forGetter(AirStrikeEffect::speedFactor),
            Codec.DOUBLE.fieldOf("min_speed").forGetter(AirStrikeEffect::minSpeed)
    ).apply(instance, AirStrikeEffect::new));

    @Override
    public void apply(final ServerPlayer dragon, final DragonAbilityInstance ability, final Entity target) {
        // target 是龙自身（技能目标为"self"）
        if (!(target instanceof ServerPlayer player)) {
            return;
        }

        // 仅在滑翔状态下触发（疾跑 + 飞行 + 展翅）
        if (!ServerFlightHandler.isGliding(player)) {
            return;
        }

        // 检查最低速度阈值
        double totalSpeed = player.getDeltaMovement().length();
        if (totalSpeed < minSpeed) {
            return;
        }

        // 扫描与龙碰撞箱重叠的附近生物
        List<LivingEntity> hitEntities = player.level().getEntitiesOfClass(
                LivingEntity.class,
                player.getBoundingBox().inflate(0.2),
                e -> e != player && e.isAlive() && e.isPickable()
        );

        if (hitEntities.isEmpty()) {
            return;
        }

        // 对第一个命中的实体造成伤害
        LivingEntity hitTarget = hitEntities.getFirst();
        float damage = baseDamage.calculate(ability.level()) + (float) (totalSpeed * speedFactor);
        hitTarget.hurt(player.damageSources().mobAttack(player), damage);

        // 收起翅膀 —— 使龙下坠
        FlightData.getData(player).areWingsSpread = false;
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new SyncWingsSpread(player.getId(), false));
    }

    @Override
    public MapCodec<? extends AbilityEntityEffect> entityCodec() {
        return CODEC;
    }

    @Override
    public List<MutableComponent> getDescription(final Player dragon, final DragonAbilityInstance ability) {
        int level = ability.level();
        float base = baseDamage.calculate(level);
        return List.of(
                Component.translatable("dragon_ability.beloong.air_strike.dynamic_desc",
                        String.format("%.1f", base),
                        String.format("%.1f", speedFactor),
                        String.format("%.1f", minSpeed))
        );
    }
}
