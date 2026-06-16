package com.zonlong.beloong.treasure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

public record TreasureGrowthEntry(Block block, double value, int limit) {

    public static final Codec<TreasureGrowthEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.comapFlatMap(
                    loc -> BuiltInRegistries.BLOCK.getOptional(loc)
                            .map(DataResult::success)
                            .orElseGet(() -> DataResult.error(() -> "Unknown block: " + loc)),
                    BuiltInRegistries.BLOCK::getKey
            ).fieldOf("block").forGetter(TreasureGrowthEntry::block),
            Codec.DOUBLE.fieldOf("value").forGetter(TreasureGrowthEntry::value),
            Codec.INT.optionalFieldOf("limit", Integer.MAX_VALUE).forGetter(TreasureGrowthEntry::limit)
    ).apply(instance, TreasureGrowthEntry::new));
}
