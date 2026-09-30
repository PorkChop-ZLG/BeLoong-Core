package com.zonlong.beloong.mixin.dragonsurvival;

import by.dragonsurvivalteam.dragonsurvival.registry.DSAttributes;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 通过 Mixin 向 Dragon Survival 的 DSAttributes 注册 {@code dragonsurvival:flight_level} 属性。
 *
 * <h3>设计目的</h3>
 * 飞行等级系统需要一个梯级属性来控制飞行能力（&lt; 0 禁止飞行 / = 0 可飞不能悬停 / &ge; 1 完整飞行）。
 * 由于 DS 模组不可修改，属性通过两个注入点注册：
 * <ol>
 *   <li>{@code <clinit>} RETURN — 在 DS 自身属性全部初始化后，将 flight_level 加入 REGISTRY</li>
 *   <li>{@code attachAttributes} TAIL — 在 DS 将自身属性 attach 到 PLAYER 后，追加 flight_level</li>
 * </ol>
 *
 * <h3>属性规格</h3>
 * ID: {@code dragonsurvival:flight_level}，描述键: {@code attribute.dragonsurvival.flight_level}，
 * 默认值 0，范围 [-1024, 1024]，同步到客户端。
 *
 * <h3>注册时机为何可靠（三件事共同承重，不是"加载顺序"顺带成立的）</h3>
 * <ol>
 *   <li><b>排队而非立即注册</b>：{@code DSAttributes.REGISTRY} 是 {@code DeferredRegister}，
 *       在 {@code <clinit>} 里调用 {@code register} 只是入队，真正落地发生在 RegisterEvent 期间。</li>
 *   <li><b>类加载早于任何 RegisterEvent</b>：{@code DSAttributes} 带 {@code @EventBusSubscriber}，
 *       FML 扫描阶段就会触碰它，故 {@code <clinit>} 必然早于注册事件。</li>
 *   <li><b>属性先于依赖它的注册表</b>：NeoForge 的 {@code GameData.postRegisterEvents} 显式把
 *       {@code Registries.ATTRIBUTE} 排在最前（注释即"Item 与 MobEffect 依赖 Attribute"），
 *       故 {@code ModMobEffects.FLIGHT_BAN} 在 MOB_EFFECT 事件里查
 *       {@code dragonsurvival:flight_level} 必定命中。</li>
 * </ol>
 * <p>若这三条中任一条被 NeoForge 或 DS 改动，症状是 {@code getFlightLevel} 恒返回 0
 * （见 {@link com.zonlong.beloong.registry.ModAttributes#getFlightLevel}），而非崩溃。</p>
 *
 * @see com.zonlong.beloong.registry.ModAttributes#getFlightLevel
 */
@Mixin(value = DSAttributes.class, remap = false)
public abstract class DSAttributesMixin {

    /** 注册后持有 flight_level 属性的 Holder 引用，用于 {@code attachAttributes} 中 attach 到 PLAYER */
    @Unique
    private static Holder<Attribute> beloong$FLIGHT_LEVEL;

    /**
     * 在 DSAttributes 静态初始化完成后，向 REGISTRY 注册 flight_level。
     * 注入点选在 RETURN 而非 HEAD，确保 DS 自身的 static final 字段（如 FLIGHT_SPEED）已初始化完成。
     */
    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void registerFlightLevel(CallbackInfo ci) {
        beloong$FLIGHT_LEVEL = DSAttributes.REGISTRY.register(
                "flight_level",
                () -> new RangedAttribute(
                        "attribute.dragonsurvival.flight_level",
                        0.0,
                        -1024.0,
                        1024.0
                ).setSyncable(true)
        );
    }

    /**
     * 在 DSAttributes 的 attachAttributes 完成后，将 flight_level attach 到 PLAYER。
     * 注入点选在 TAIL 确保 DS 自身属性先 attach（虽然顺序对功能无影响，但保持一致性）。
     */
    @Inject(method = "attachAttributes", at = @At("TAIL"))
    private static void attachFlightLevel(EntityAttributeModificationEvent event, CallbackInfo ci) {
        event.add(EntityType.PLAYER, beloong$FLIGHT_LEVEL);
    }
}
