package com.zonlong.beloong.registry;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 化龙核心的药水/状态效果注册中心。
 *
 * <p>注册的所有效果通过 BeLoongCore 中的
 * {@code REGISTRY.register(modEventBus)} 提交到游戏注册表。</p>
 */
public class ModMobEffects {
    public static final DeferredRegister<MobEffect> REGISTRY =
            DeferredRegister.create(Registries.MOB_EFFECT, "beloong");

    // ===================== 成长加速 =====================

    /**
     * 成长加速效果：每级提升成长速度属性（{@link ModAttributes#GROWTH_SPEED}）。
     * 默认每级 +1.0（通过 attribute modifier 的 ADD_VALUE 操作叠加）。
     */
    public static final Holder<MobEffect> GROWTH_ACCELERATION = REGISTRY.register(
            "growth_acceleration",
            () -> new MobEffect(MobEffectCategory.BENEFICIAL, 0xFFD700) { }
                    .addAttributeModifier(
                            ModAttributes.GROWTH_SPEED,
                            ResourceLocation.fromNamespaceAndPath("beloong", "growth_acceleration"),
                            1.0,
                            AttributeModifier.Operation.ADD_VALUE
                    )
    );

    // ===================== 禁空（飞行等级系统） =====================

    /**
     * 禁空效果——飞行等级系统的博弈核心。
     *
     * <h3>效果</h3>
     * 每级降低 1 点 {@code dragonsurvival:flight_level} 属性。
     * vanilla 的 attribute modifier 自动按 {@code amount × (amplifier + 1)} 缩放：
     * <pre>
     *   I 级 (amp=0) → -1.0 × 1 = -1
     *   II 级 (amp=1) → -1.0 × 2 = -2
     *   III 级 (amp=2) → -1.0 × 3 = -3
     * </pre>
     *
     * <h3>属性查找</h3>
     * flight_level 由 DSAttributesMixin 注入到 Dragon Survival 侧，不属于 BeLoong
     * 自有属性，因此通过 {@link BuiltInRegistries#ATTRIBUTE} 动态查找。
     * BeLoong-Core 依赖 DS（mods.toml 中声明），确保 DS 先构造、属性先注册。
     *
     * <h3>强制收翅逻辑</h3>
     * 由 {@link FlightBanEffect#onEffectStarted} 处理，见该类的文档。
     *
     * @see FlightBanEffect
     * @see com.zonlong.beloong.mixin.dragonsurvival.DSAttributesMixin
     */
    public static final Holder<MobEffect> FLIGHT_BAN = REGISTRY.register(
            "flight_ban",
            () -> {
                // 跨模组查找 DS 侧的 flight_level 属性
                Attribute flightLevelAttr = BuiltInRegistries.ATTRIBUTE.get(
                        ResourceLocation.fromNamespaceAndPath("dragonsurvival", "flight_level"));
                FlightBanEffect effect = new FlightBanEffect();
                if (flightLevelAttr != null) {
                    effect.addAttributeModifier(
                            BuiltInRegistries.ATTRIBUTE.wrapAsHolder(flightLevelAttr),
                            ResourceLocation.fromNamespaceAndPath("beloong", "flight_ban"),
                            -1.0,
                            AttributeModifier.Operation.ADD_VALUE
                    );
                }
                // 若属性未找到（极不可能，DS 先于 BeLoong 构造），
                // 效果仍注册但不带 modifier，安全降级
                return effect;
            }
    );

    // ===================== 气定神闲 =====================

    /**
     * 气定神闲效果：每级提升法力回复属性（{@code dragonsurvival:mana_regeneration}）。
     * 默认每级 +0.001（通过 attribute modifier 的 ADD_VALUE 操作叠加）。
     * vanilla 自动按 {@code amount × (amplifier + 1)} 缩放：
     * <pre>
     *   I 级 (amp=0) → +0.001 × 1 = +0.001
     *   II 级 (amp=1) → +0.001 × 2 = +0.002
     * </pre>
     */
    public static final Holder<MobEffect> SERENE = REGISTRY.register(
            "serene",
            () -> {
                Attribute manaRegenAttr = BuiltInRegistries.ATTRIBUTE.get(
                        ResourceLocation.fromNamespaceAndPath("dragonsurvival", "mana_regeneration"));
                SereneEffect effect = new SereneEffect();
                if (manaRegenAttr != null) {
                    // 若属性未找到（极不可能，DS 先于 BeLoong 构造），效果仍注册但不带 modifier，安全降级
                    effect.addAttributeModifier(
                            BuiltInRegistries.ATTRIBUTE.wrapAsHolder(manaRegenAttr),
                            ResourceLocation.fromNamespaceAndPath("beloong", "serene"),
                            0.004,
                            AttributeModifier.Operation.ADD_VALUE
                    );
                }
                return effect;
            }
    );

    // ===================== 魔力流逝 =====================

    /**
     * 魔力流逝效果——持续扣除龙族玩家的法力值。
     *
     * <p>效果本身仅注册为有害药水效果。每 tick 的法力扣除逻辑由
     * {@code ManaLossHandler#onPlayerTick} 处理。</p>
     *
     * <p>扣除量：{@code 0.025 × (amplifier + 1)} mana/tick</p>
     */
    public static final Holder<MobEffect> MANA_LOSS = REGISTRY.register(
            "mana_loss",
            () -> new MobEffect(MobEffectCategory.HARMFUL, 0x8B008B) {}
    );
}
