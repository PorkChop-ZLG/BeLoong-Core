package com.zonlong.beloong.registry;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组的粒子类型注册中心。
 * <p>
 * 目前只有一个：龙宫传送门的门粒子。它的<b>贴图沿用原版 {@code minecraft:generic_0..7}</b>
 * （与原版下界门同一套），差别只在<b>配色</b>——见
 * {@code client/particle/LoongPalacePortalParticle}（复刻天境：红绿压到 20%，偏冷蓝青）。
 * <p>
 * ⚠️ 粒子类型必须在这里注册，而<b>渲染工厂</b>要挂在客户端事件上
 * （{@code BeLoongCoreClient} 的 {@code RegisterParticleProvidersEvent}）。
 */
public final class ModParticles {

    /** 粒子类型延迟注册器 */
    public static final DeferredRegister<ParticleType<?>> PARTICLES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, BeLoongCore.MODID);

    /** 龙宫传送门的门粒子。 */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> LOONG_PALACE_PORTAL =
            PARTICLES.register("loong_palace_portal", () -> new SimpleParticleType(false));

    private ModParticles() {}

    /** 将粒子类型注册到 Mod 事件总线。 */
    public static void register(IEventBus bus) {
        PARTICLES.register(bus);
    }
}
