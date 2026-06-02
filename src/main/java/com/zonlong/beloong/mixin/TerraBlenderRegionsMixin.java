package com.zonlong.beloong.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import terrablender.api.Region;
import terrablender.api.RegionType;
import terrablender.api.Regions;
import terrablender.util.LevelUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 拦截 {@code Regions.get()} 调用，对天灾维度替换区域列表。
 * 使用 {@code @WrapOperation} 直接获取 levelResourceKey，无需 ThreadLocal。
 */
@Mixin(value = LevelUtils.class, remap = false)
public abstract class TerraBlenderRegionsMixin {

    private static final ResourceLocation DISASTER_STEM =
            ResourceLocation.fromNamespaceAndPath("beloong", "disaster");

    @WrapOperation(
            method = "initializeBiomes",
            at = @At(value = "INVOKE", target = "Lterrablender/api/Regions;get(Lterrablender/api/RegionType;)Ljava/util/List;"),
            remap = false)
    private static List<Region> wrapRegionsGet(RegionType type, Operation<List<Region>> original,
            RegistryAccess registryAccess,
            Holder<DimensionType> dimensionType,
            ResourceKey<LevelStem> levelResourceKey,
            ChunkGenerator chunkGenerator,
            long seed) {
        List<Region> regions = new ArrayList<>(original.call(type));

        if (levelResourceKey != null && levelResourceKey.location().equals(DISASTER_STEM)) {
            // 移除 biomeswevegone 命名空间的 Region（BWG 原生，受配置控制）
            regions.removeIf(r -> r.getName().getNamespace().equals("biomeswevegone"));

            // 添加 beloong 命名空间的 DisasterBiomeRegion（绕过配置过滤）
            for (Region r : Regions.get(RegionType.OVERWORLD)) {
                if (r.getName().getNamespace().equals("beloong")) {
                    regions.add(r);
                    break;
                }
            }
        }

        return regions;
    }
}
