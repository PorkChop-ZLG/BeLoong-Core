package com.zonlong.beloong.registry;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.entity.MeteorEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 化龙核心模组的实体注册中心。
 * <p>
 * 使用 NeoForge 的 {@link DeferredRegister} 机制进行懒加载注册，
 * 在 {@link BeLoongCore} 构造函数中通过 {@link #register(IEventBus)} 绑定到 Mod 事件总线。
 * <p>
 * <b>注册的实体：</b>
 * <ul>
 *   <li>{@link #METEOR} — 流星火雨中的陨石实体</li>
 * </ul>
 */
public class ModEntities {

    /** 实体延迟注册器 */
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, BeLoongCore.MODID);

    /** 陨石实体。重力下坠，触地爆炸。 */
    public static final DeferredHolder<EntityType<?>, EntityType<MeteorEntity>> METEOR =
            ENTITIES.register("meteor", () -> EntityType.Builder.<MeteorEntity>of(MeteorEntity::new, MobCategory.MISC)
                    .sized(0.98F, 0.98F)
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .build("meteor"));

    /**
     * 将实体注册到 Mod 事件总线。
     * 在 {@link BeLoongCore} 构造函数中调用。
     */
    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
    }
}
