package com.zonlong.beloong.mixin;

import com.mojang.datafixers.util.Either;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import terrablender.api.RegionType;
import terrablender.mixin.MultiNoiseBiomeSourceAccess;
import terrablender.util.LevelUtils;
import terrablender.worldgen.IExtendedParameterList;

@Pseudo
@Mixin(value = LevelUtils.class, remap = false)
public class CloneParameterListMixin {

    /**
     * Prevent TerraBlender from polluting the shared {@link Climate.ParameterList}
     * when multiple dimensions use the same {@code minecraft:overworld} preset.
     * <p>
     * Without this, initializing the disaster dimension would mark the shared
     * ParameterList as initialized, leaking BWG biomes into the overworld.
     */
    @Redirect(
        method = "initializeBiomes",
        at = @At(
            value = "INVOKE",
            target = "Lterrablender/worldgen/IExtendedParameterList;initializeForTerraBlender(Lnet/minecraft/core/RegistryAccess;Lterrablender/api/RegionType;J)V"
        ),
        remap = false
    )
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void cloneBeforeInit(
        IExtendedParameterList receiver,
        RegistryAccess targetRA,
        RegionType regionType,
        long targetSeed,
        RegistryAccess enclosingRA,
        Holder<DimensionType> dimensionType,
        ResourceKey<LevelStem> levelKey,
        ChunkGenerator chunkGenerator,
        long enclosingSeed
    ) {
        Climate.ParameterList cloned = (Climate.ParameterList) receiver.clone();
        ((IExtendedParameterList) cloned).initializeForTerraBlender(targetRA, regionType, targetSeed);

        MultiNoiseBiomeSource biomeSource = (MultiNoiseBiomeSource) chunkGenerator.getBiomeSource();
        ((MultiNoiseBiomeSourceAccess) biomeSource).setParameters(Either.left(cloned));
    }
}
