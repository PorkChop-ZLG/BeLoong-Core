package com.zonlong.beloong.fluid;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BeloongWaterRegionLoaderTest {

    @Test
    void groupsMultipleResourcesByDimension() {
        Map<ResourceLocation, JsonElement> resources = new LinkedHashMap<>();
        resources.put(id("pool_one"), regionJson("beloong:loong_palace", 0));
        resources.put(id("pool_two"), regionJson("beloong:loong_palace", 10));
        resources.put(id("overworld_pool"), regionJson("minecraft:overworld", 20));

        Map<ResourceKey<Level>, java.util.List<BeloongWaterRegion>> index =
                BeloongWaterRegionLoader.buildIndex(resources);

        assertEquals(2, index.get(dimension("beloong:loong_palace")).size());
        assertEquals(1, index.get(dimension("minecraft:overworld")).size());
    }

    @Test
    void retainsOverlappingRegions() {
        ResourceKey<Level> dimension = dimension("beloong:loong_palace");
        Map<ResourceLocation, JsonElement> resources = new LinkedHashMap<>();
        resources.put(id("pool_one"), regionJson("beloong:loong_palace", 0));
        resources.put(id("pool_two"), regionJson("beloong:loong_palace", 3));

        Map<ResourceKey<Level>, java.util.List<BeloongWaterRegion>> index =
                BeloongWaterRegionLoader.buildIndex(resources);

        assertEquals(2, index.get(dimension).size());
    }

    @Test
    void malformedResourceDoesNotDiscardValidResources() {
        Map<ResourceLocation, JsonElement> resources = new LinkedHashMap<>();
        resources.put(id("valid"), regionJson("beloong:loong_palace", 0));
        resources.put(id("missing_max"), JsonParser.parseString("""
                {
                  "dimension": "beloong:loong_palace",
                  "min": {"x": 0, "y": 60, "z": 0}
                }
                """));

        Map<ResourceKey<Level>, java.util.List<BeloongWaterRegion>> index =
                BeloongWaterRegionLoader.buildIndex(resources);

        assertEquals(1, index.get(dimension("beloong:loong_palace")).size());
    }

    @Test
    void returnsAnImmutableIndex() {
        ResourceKey<Level> dimension = dimension("beloong:loong_palace");
        Map<ResourceLocation, JsonElement> resources = Map.of(
                id("pool"), regionJson("beloong:loong_palace", 0));

        Map<ResourceKey<Level>, java.util.List<BeloongWaterRegion>> index =
                BeloongWaterRegionLoader.buildIndex(resources);

        assertThrows(UnsupportedOperationException.class, () -> index.clear());
        assertThrows(UnsupportedOperationException.class, () -> index.get(dimension).clear());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("beloong", path);
    }

    private static ResourceKey<Level> dimension(String id) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(id));
    }

    private static JsonElement regionJson(String dimension, int x) {
        return JsonParser.parseString("""
                {
                  "dimension": "%s",
                  "min": {"x": %d, "y": 60, "z": 0},
                  "max": {"x": %d, "y": 70, "z": 10}
                }
                """.formatted(dimension, x, x + 5));
    }
}
