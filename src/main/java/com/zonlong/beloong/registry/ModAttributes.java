package com.zonlong.beloong.registry;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

/**
 * 化龙核心的属性注册中心。
 *
 * <p>除模组自身的属性（如 {@link #GROWTH_SPEED}）外，还提供跨模组属性查找辅助方法。
 * 其中 {@link #getFlightLevel(Player)} 用于读取由 {@code DSAttributesMixin} 注入到
 * Dragon Survival 侧的 {@code dragonsurvival:flight_level} 属性。</p>
 */
@EventBusSubscriber(modid = "beloong", bus = EventBusSubscriber.Bus.MOD)
public class ModAttributes {
    public static final DeferredRegister<Attribute> REGISTRY =
            DeferredRegister.create(Registries.ATTRIBUTE, "beloong");

    // ===================== 模组自有属性 =====================

    public static final Holder<Attribute> GROWTH_SPEED = REGISTRY.register("growth_speed",
            () -> new RangedAttribute(
                    "attribute.beloong.growth_speed",
                    1.0,
                    -1024.0,
                    1024.0
            ).setSyncable(true)
    );

    // ===================== 跨模组属性查找 =====================

    /**
     * 缓存 {@code dragonsurvival:flight_level} 的 Holder 引用，避免每 tick HashMap 查找。
     * 首次调用时从全局注册表解析，之后直接复用。
     */
    @Nullable
    private static Holder<Attribute> cachedFlightLevel;

    /**
     * 获取玩家的有效飞行等级（已叠加所有 attribute modifier）。
     *
     * <p>FLIGHT_LEVEL 属性由 {@code DSAttributesMixin} 注册在
     * {@code dragonsurvival:flight_level}，不属于 BeLoong 自身属性，
     * 因此需要通过 {@link BuiltInRegistries#ATTRIBUTE} 查找。</p>
     *
     * <h3>飞行等级含义</h3>
     * <ul>
     *   <li>&lt; 0 — 禁止飞行（{@code ToggleFlightMixin} 拒绝展翅）</li>
     *   <li>= 0 — 可飞行，不可稳定悬停</li>
     *   <li>&ge; 1 — 可飞行 + 稳定悬停</li>
     * </ul>
     *
     * @param player 目标玩家
     * @return 有效飞行等级；若属性未注册则返回 0（安全降级）
     */
    public static double getFlightLevel(Player player) {
        if (cachedFlightLevel == null) {
            Attribute raw = BuiltInRegistries.ATTRIBUTE.get(
                    ResourceLocation.fromNamespaceAndPath("dragonsurvival", "flight_level"));
            if (raw != null) {
                cachedFlightLevel = BuiltInRegistries.ATTRIBUTE.wrapAsHolder(raw);
            }
        }
        if (cachedFlightLevel == null) {
            return 0.0;
        }
        return player.getAttributeValue(cachedFlightLevel);
    }

    // ===================== 事件处理 =====================

    @SubscribeEvent
    public static void attachAttributes(EntityAttributeModificationEvent event) {
        event.add(EntityType.PLAYER, GROWTH_SPEED);
    }
}
