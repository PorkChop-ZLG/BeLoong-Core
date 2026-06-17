package com.zonlong.beloong.structure;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class StructureEffectLoader extends SimpleJsonResourceReloadListener {

    public static final StructureEffectLoader INSTANCE = new StructureEffectLoader();

    private static final Codec<Map<String, List<StructureEffectEntry>>> FILE_CODEC =
            Codec.unboundedMap(Codec.STRING, Codec.list(StructureEffectEntry.CODEC));

    private Map<ResourceKey<Structure>, List<EffectEntry>> configMap = Map.of();

    private StructureEffectLoader() {
        super(new Gson(), "beloong/structure_effects");
    }

    @Override
    protected void apply(@NotNull Map<ResourceLocation, JsonElement> files,
                         @NotNull ResourceManager manager, @NotNull ProfilerFiller profiler) {
        Map<ResourceKey<Structure>, List<EffectEntry>> newMap = new HashMap<>();

        for (var fileEntry : files.entrySet()) {
            var result = FILE_CODEC.parse(JsonOps.INSTANCE, fileEntry.getValue());
            result.resultOrPartial(error ->
                    BeLoongCore.LOGGER.error("Failed to parse structure effects file '{}': {}",
                            fileEntry.getKey(), error)
            ).ifPresent(map -> {
                for (var structEntry : map.entrySet()) {
                    ResourceLocation structureLoc;
                    try {
                        structureLoc = ResourceLocation.parse(structEntry.getKey());
                    } catch (Exception e) {
                        BeLoongCore.LOGGER.error(
                                "Invalid structure ID in file '{}': {}",
                                fileEntry.getKey(), structEntry.getKey());
                        continue;
                    }
                    ResourceKey<Structure> structureKey = ResourceKey.create(
                            Registries.STRUCTURE, structureLoc);

                    List<EffectEntry> converted = new ArrayList<>();
                    for (StructureEffectEntry se : structEntry.getValue()) {
                        Holder<MobEffect> effectHolder =
                                BuiltInRegistries.MOB_EFFECT.wrapAsHolder(se.effect());
                        converted.add(new EffectEntry(
                                effectHolder, se.amplifier(), se.duration(),
                                se.showParticles(), se.advancement()));
                    }

                    List<EffectEntry> existing = newMap.get(structureKey);
                    List<EffectEntry> merged = existing != null
                            ? new ArrayList<>(existing) : new ArrayList<>();
                    merged.addAll(converted);
                    newMap.put(structureKey, Collections.unmodifiableList(merged));
                }
            });
        }

        this.configMap = Collections.unmodifiableMap(newMap);
        BeLoongCore.LOGGER.debug("Reloaded structure effects: {} structures", configMap.size());
    }

    public Map<ResourceKey<Structure>, List<EffectEntry>> getConfigMap() {
        return configMap;
    }
}
