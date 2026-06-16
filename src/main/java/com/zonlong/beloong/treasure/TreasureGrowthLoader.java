package com.zonlong.beloong.treasure;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TreasureGrowthLoader extends SimpleJsonResourceReloadListener {

    public static final TreasureGrowthLoader INSTANCE = new TreasureGrowthLoader();

    private static final Codec<Map<String, List<TreasureGrowthEntry>>> FILE_CODEC =
            Codec.unboundedMap(Codec.STRING, Codec.list(TreasureGrowthEntry.CODEC));

    private Map<Block, TreasureGrowthEntry> dragonTreasureEntries = Map.of();
    private Map<Block, TreasureGrowthEntry> otherTreasureEntries = Map.of();

    private TreasureGrowthLoader() {
        super(new Gson(), "beloong/treasure_growth");
    }

    @Override
    protected void apply(@NotNull Map<ResourceLocation, JsonElement> files,
                         @NotNull ResourceManager manager, @NotNull ProfilerFiller profiler) {
        Map<Block, TreasureGrowthEntry> newDragon = new HashMap<>();
        Map<Block, TreasureGrowthEntry> newOther = new HashMap<>();

        for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet()) {
            var result = FILE_CODEC.parse(JsonOps.INSTANCE, file.getValue());
            result.resultOrPartial(error ->
                    BeLoongCore.LOGGER.error("Failed to parse treasure growth file '{}': {}",
                            file.getKey(), error)
            ).ifPresent(map -> {
                for (var entry : map.entrySet()) {
                    String key = entry.getKey();
                    List<TreasureGrowthEntry> entries = entry.getValue();
                    for (TreasureGrowthEntry tgEntry : entries) {
                        Map<Block, TreasureGrowthEntry> target =
                                "dragon_treasure".equals(key) ? newDragon : newOther;
                        TreasureGrowthEntry prev = target.put(tgEntry.block(), tgEntry);
                        if (prev != null) {
                            BeLoongCore.LOGGER.warn(
                                    "Duplicate treasure entry for block '{}' in type '{}', overwriting",
                                    BuiltInRegistries.BLOCK.getKey(tgEntry.block()), key);
                        }
                    }
                }
            });
        }

        this.dragonTreasureEntries = Map.copyOf(newDragon);
        this.otherTreasureEntries = Map.copyOf(newOther);
        BeLoongCore.LOGGER.debug("Reloaded treasure growth: {} dragon entries, {} other entries",
                dragonTreasureEntries.size(), otherTreasureEntries.size());
    }

    @Nullable
    public TreasureGrowthEntry getDragonEntry(Block block) {
        return dragonTreasureEntries.get(block);
    }

    @Nullable
    public TreasureGrowthEntry getOtherEntry(Block block) {
        return otherTreasureEntries.get(block);
    }
}
