package com.zonlong.beloong.ability;

import by.dragonsurvivalteam.dragonsurvival.registry.DSAttributes;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.entity_effects.AbilityEntityEffect;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zonlong.beloong.entity.TornadoEntity;
import com.zonlong.beloong.registry.ModEntities;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 「龙卷风」技能效果：向施法者准星方向发射一个 {@link TornadoEntity}。
 * <p>
 * 与 {@code AirStrikeEffect} 同构：{@code record implements AbilityEntityEffect} + {@code MapCodec}，
 * 所有数值字段都是 {@link LevelBasedValue}，让 6 个玩法参数全部随技能等级缩放。
 * 伤害在<b>这里</b>乘上 {@code DSAttributes.DRAGON_ABILITY_DAMAGE}，实体本身不碰属性系统。
 */
public record TornadoEffect(
        LevelBasedValue damagePerTick,
        LevelBasedValue lifetime,
        LevelBasedValue pullRadius,
        LevelBasedValue damageRadius,
        LevelBasedValue speed,
        LevelBasedValue pullStrength
) implements AbilityEntityEffect {

    /** 同时支持纯数字和 LevelBasedValue 对象格式（与 AirStrikeEffect 一致） */
    private static final Codec<LevelBasedValue> FLEXIBLE_LBV = Codec.either(
            LevelBasedValue.CODEC,
            Codec.DOUBLE
    ).xmap(
            either -> either.map(lbv -> lbv, d -> LevelBasedValue.constant((float) (double) d)),
            Either::left
    );

    public static final MapCodec<TornadoEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            FLEXIBLE_LBV.fieldOf("damage_per_tick").forGetter(TornadoEffect::damagePerTick),
            FLEXIBLE_LBV.fieldOf("lifetime").forGetter(TornadoEffect::lifetime),
            FLEXIBLE_LBV.fieldOf("pull_radius").forGetter(TornadoEffect::pullRadius),
            FLEXIBLE_LBV.fieldOf("damage_radius").forGetter(TornadoEffect::damageRadius),
            FLEXIBLE_LBV.fieldOf("speed").forGetter(TornadoEffect::speed),
            FLEXIBLE_LBV.fieldOf("pull_strength").forGetter(TornadoEffect::pullStrength)
    ).apply(instance, TornadoEffect::new));

    @Override
    public void apply(final ServerPlayer dragon, final DragonAbilityInstance ability, final Entity target) {
        int level = ability.level();

        double abilityScale = dragon.getAttributeValue(DSAttributes.DRAGON_ABILITY_DAMAGE);
        float damage = (float) (this.damagePerTick.calculate(level) * abilityScale);
        int life = (int) this.lifetime.calculate(level);
        double pullR = this.pullRadius.calculate(level);
        double damageR = this.damageRadius.calculate(level);
        double strength = this.pullStrength.calculate(level);
        float projectileSpeed = this.speed.calculate(level);

        Vec3 look = dragon.getLookAngle();
        // 发射高度照抄灾变：眼高 − 0.5。灾变原版把生成点偏到玩家侧面，那是它的 bug，不复刻。
        Vec3 spawn = new Vec3(
                dragon.getX() + look.x,
                dragon.getEyeY() - 0.5D,
                dragon.getZ() + look.z);

        TornadoEntity tornado = new TornadoEntity(
                ModEntities.TORNADO.get(), dragon.serverLevel(), dragon,
                damage, pullR, damageR, strength, life);
        tornado.setPos(spawn);
        tornado.setDeltaMovement(look.scale(projectileSpeed));
        dragon.serverLevel().addFreshEntity(tornado);
    }

    @Override
    public MapCodec<? extends AbilityEntityEffect> entityCodec() {
        return CODEC;
    }

    @Override
    public List<MutableComponent> getDescription(final Player dragon, final DragonAbilityInstance ability) {
        // LevelBasedValue.Lookup 不接受 level 0（会索引 values.get(-1) 崩溃），
        // 未学习能力时按 1 级展示数值。
        int level = Math.max(DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS, ability.level());
        return List.of(
                Component.translatable("dragon_ability.beloong.tornado.dynamic_desc",
                        String.format("%.1f", this.damagePerTick.calculate(level)),
                        String.format("%d", (int) this.lifetime.calculate(level)),
                        String.format("%.1f", this.pullRadius.calculate(level)),
                        String.format("%.1f", this.damageRadius.calculate(level))));
    }
}
