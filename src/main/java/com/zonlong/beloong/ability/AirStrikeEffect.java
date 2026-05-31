package com.zonlong.beloong.ability;

import by.dragonsurvivalteam.dragonsurvival.network.flight.SyncWingsSpread;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.FlightData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.entity_effects.AbilityEntityEffect;
import by.dragonsurvivalteam.dragonsurvival.server.handlers.ServerFlightHandler;
import com.mojang.datafixers.util.Either;
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
        LevelBasedValue speedFactor,
        LevelBasedValue minSpeed
) implements AbilityEntityEffect {

    /** 同时支持纯数字和 LevelBasedValue 对象格式（如 minecraft:lookup） */
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
            FLEXIBLE_LBV.fieldOf("min_speed").forGetter(AirStrikeEffect::minSpeed)
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
        if (totalSpeed < minSpeed.calculate(ability.level())) {
            return;
        }

        // 计算伤害并在 actionbar 显示当前速度和伤害
        float damage = baseDamage.calculate(ability.level()) + (float) (totalSpeed * speedFactor.calculate(ability.level()));
        player.displayClientMessage(
                Component.translatable("dragon_ability.beloong.air_strike.actionbar",
                        String.format("%.1f", totalSpeed * 20 * 3.6),
                        String.format("%.1f", damage)),
                true);

        // 扫描碰撞箱附近的可攻击生物
        List<LivingEntity> hitEntities = player.level().getEntitiesOfClass(
                LivingEntity.class,
                player.getBoundingBox().inflate(1.0),
                e -> e != player && e.isAlive() && e.isPickable()
        );

        if (hitEntities.isEmpty()) {
            return;
        }

        // 对所有碰撞到的实体造成伤害
        boolean dealtDamage = false;
        for (LivingEntity hitTarget : hitEntities) {
            if (hitTarget.hurt(player.damageSources().mobAttack(player), damage)) {
                dealtDamage = true;
            }
        }

        // 只有成功造成伤害后才收起翅膀
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
        float base = baseDamage.calculate(level);
        return List.of(
                Component.translatable("dragon_ability.beloong.air_strike.dynamic_desc",
                        String.format("%.1f", base),
                        String.format("%.1f", speedFactor.calculate(level)),
                        String.format("%.1f", minSpeed.calculate(level)))
        );
    }
}
