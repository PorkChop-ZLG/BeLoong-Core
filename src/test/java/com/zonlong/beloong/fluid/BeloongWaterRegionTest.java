package com.zonlong.beloong.fluid;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeloongWaterRegionTest {

    @Test
    void parsesDocumentedRegionDefinition() {
        var json = JsonParser.parseString("""
                {
                  "dimension": "beloong:loong_palace",
                  "min": {"x": -54, "y": 68, "z": -312},
                  "max": {"x": 8, "y": 77, "z": -272}
                }
                """);

        BeloongWaterRegionDefinition definition = BeloongWaterRegionDefinition.CODEC
                .parse(JsonOps.INSTANCE, json)
                .result()
                .orElseThrow();

        assertEquals(ResourceLocation.parse("beloong:loong_palace"), definition.dimension());
        assertEquals(new BlockPos(-54, 68, -312), definition.min());
        assertEquals(new BlockPos(8, 77, -272), definition.max());
    }

    @Test
    void normalizesReversedCoordinates() {
        ResourceLocation dimensionId = ResourceLocation.parse("beloong:loong_palace");
        BeloongWaterRegionDefinition definition = new BeloongWaterRegionDefinition(
                dimensionId,
                new BlockPos(8, 77, -272),
                new BlockPos(-54, 68, -312));

        BeloongWaterRegion region = definition.toRegion();

        assertEquals(ResourceKey.create(Registries.DIMENSION, dimensionId), region.dimension());
        assertEquals(new BlockPos(-54, 68, -312), region.min());
        assertEquals(new BlockPos(8, 77, -272), region.max());
    }

    @Test
    void usesInclusiveBlockBoundsForIntersection() {
        BeloongWaterRegion region = new BeloongWaterRegion(
                LevelKeys.LOONG_PALACE,
                new BlockPos(-54, 68, -312),
                new BlockPos(8, 77, -272));

        assertTrue(region.intersects(new AABB(-54.0, 69.0, -300.0, -53.5, 70.0, -299.0)));
        assertTrue(region.intersects(new AABB(8.5, 69.0, -300.0, 9.0, 70.0, -299.0)));
        assertTrue(region.intersects(new AABB(-10.0, 68.0, -300.0, -9.0, 68.5, -299.0)));
        assertTrue(region.intersects(new AABB(-10.0, 77.5, -300.0, -9.0, 78.0, -299.0)));
        assertTrue(region.intersects(new AABB(-10.0, 69.0, -312.0, -9.0, 70.0, -311.5)));
        assertTrue(region.intersects(new AABB(-10.0, 69.0, -271.5, -9.0, 70.0, -271.0)));

        assertFalse(region.intersects(new AABB(-55.0, 69.0, -300.0, -54.0, 70.0, -299.0)));
        assertFalse(region.intersects(new AABB(9.0, 68.0, -312.0, 10.0, 69.0, -311.0)));
        assertFalse(region.intersects(new AABB(-10.0, 67.0, -300.0, -9.0, 68.0, -299.0)));
        assertFalse(region.intersects(new AABB(-10.0, 78.0, -300.0, -9.0, 79.0, -299.0)));
        assertFalse(region.intersects(new AABB(-10.0, 69.0, -313.0, -9.0, 70.0, -312.0)));
        assertFalse(region.intersects(new AABB(-10.0, 69.0, -271.0, -9.0, 70.0, -270.0)));
    }

    private static final class LevelKeys {
        private static final ResourceKey<net.minecraft.world.level.Level> LOONG_PALACE = ResourceKey.create(
                Registries.DIMENSION,
                ResourceLocation.parse("beloong:loong_palace"));

        private LevelKeys() {}
    }
}
