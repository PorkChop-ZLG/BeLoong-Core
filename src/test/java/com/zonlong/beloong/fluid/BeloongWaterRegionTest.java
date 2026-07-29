package com.zonlong.beloong.fluid;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BeloongWaterRegionTest {

    @Test
    void minecraftTypesAreAvailableToUnitTests() {
        ResourceLocation id = ResourceLocation.parse("beloong:loong_palace");

        assertEquals("beloong", id.getNamespace());
        assertEquals("loong_palace", id.getPath());
    }
}
