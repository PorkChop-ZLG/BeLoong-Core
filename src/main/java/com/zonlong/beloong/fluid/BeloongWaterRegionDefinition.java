package com.zonlong.beloong.fluid;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public record BeloongWaterRegionDefinition(
        ResourceLocation dimension,
        BlockPos min,
        BlockPos max
) {

    private static final Codec<BlockPos> POSITION_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("x").forGetter(BlockPos::getX),
            Codec.INT.fieldOf("y").forGetter(BlockPos::getY),
            Codec.INT.fieldOf("z").forGetter(BlockPos::getZ)
    ).apply(instance, BlockPos::new));

    public static final Codec<BeloongWaterRegionDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("dimension").forGetter(BeloongWaterRegionDefinition::dimension),
            POSITION_CODEC.fieldOf("min").forGetter(BeloongWaterRegionDefinition::min),
            POSITION_CODEC.fieldOf("max").forGetter(BeloongWaterRegionDefinition::max)
    ).apply(instance, BeloongWaterRegionDefinition::new));

    public BeloongWaterRegion toRegion() {
        ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, dimension);
        return new BeloongWaterRegion(dimensionKey, min, max);
    }
}
