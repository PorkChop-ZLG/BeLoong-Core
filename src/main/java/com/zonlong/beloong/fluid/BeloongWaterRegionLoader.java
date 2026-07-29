package com.zonlong.beloong.fluid;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BeloongWaterRegionLoader extends SimpleJsonResourceReloadListener {

    public static final BeloongWaterRegionLoader INSTANCE = new BeloongWaterRegionLoader();

    private volatile Map<ResourceKey<Level>, List<BeloongWaterRegion>> regionsByDimension = Map.of();

    private BeloongWaterRegionLoader() {
        super(new Gson(), "beloong/beloong_water_regions");
    }

    @Override
    protected void apply(@NotNull Map<ResourceLocation, JsonElement> files,
                         @NotNull ResourceManager manager,
                         @NotNull ProfilerFiller profiler) {
        regionsByDimension = buildIndex(files);
        BeLoongCore.LOGGER.debug("Reloaded {} Beloong Water region files", files.size());
    }

    public List<BeloongWaterRegion> getRegions(ResourceKey<Level> dimension) {
        return regionsByDimension.getOrDefault(dimension, List.of());
    }

    private static Map<ResourceKey<Level>, List<BeloongWaterRegion>> buildIndex(
            Map<ResourceLocation, JsonElement> files) {
        Map<ResourceKey<Level>, List<BeloongWaterRegion>> mutableIndex = new HashMap<>();

        files.forEach((id, json) -> BeloongWaterRegionDefinition.CODEC
                .parse(JsonOps.INSTANCE, json)
                .resultOrPartial(error -> BeLoongCore.LOGGER.error(
                        "Failed to parse Beloong Water region '{}': {}", id, error))
                .map(BeloongWaterRegionDefinition::toRegion)
                .ifPresent(region -> mutableIndex
                        .computeIfAbsent(region.dimension(), ignored -> new ArrayList<>())
                        .add(region)));

        Map<ResourceKey<Level>, List<BeloongWaterRegion>> immutableIndex = new HashMap<>();
        mutableIndex.forEach((dimension, regions) -> immutableIndex.put(dimension, List.copyOf(regions)));
        return Map.copyOf(immutableIndex);
    }
}
