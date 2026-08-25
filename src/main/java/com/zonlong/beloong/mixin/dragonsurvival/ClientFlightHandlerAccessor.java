package com.zonlong.beloong.mixin.dragonsurvival;

import by.dragonsurvivalteam.dragonsurvival.client.handlers.ClientFlightHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ClientFlightHandler.class, remap = false)
public interface ClientFlightHandlerAccessor {

    @Accessor("ax")
    static void beloong$setAx(double value) {
        throw new AssertionError();
    }

    @Accessor("ay")
    static void beloong$setAy(double value) {
        throw new AssertionError();
    }

    @Accessor("az")
    static void beloong$setAz(double value) {
        throw new AssertionError();
    }
}
