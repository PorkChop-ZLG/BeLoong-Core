package com.zonlong.beloong.worldgen;

import com.mojang.datafixers.util.Pair;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import terrablender.api.Region;
import terrablender.api.RegionType;
import terrablender.api.Regions;
import terrablender.api.TerrablenderOverworldBiomeBuilder;

import java.lang.reflect.Field;
import java.util.function.Consumer;

/**
 * 天灾维度专用的 TerraBlender Region。
 * 复用 BWG 的 BWGBiomeSelectors / TerraBlenderBiomeSelectors 中的群系-气候参数映射，
 * 但跳过 BWGWorldGenConfig 的配置过滤，始终启用全部 BWG 群系。
 */
public class DisasterBiomeRegion extends Region {

    private static final ResourceLocation REGION_NAME =
            ResourceLocation.fromNamespaceAndPath("beloong", "disaster_bwg");
    private static final int WEIGHT = 24; // BWG 三个区域权重之和 (8+8+8)

    private final TerrablenderOverworldBiomeBuilder builder;

    private DisasterBiomeRegion(TerrablenderOverworldBiomeBuilder builder) {
        super(REGION_NAME, RegionType.OVERWORLD, WEIGHT);
        this.builder = builder;
    }

    /**
     * 通过反射读取 BWG 的群系选择器数组并注册 Region。
     * 若反射失败则记录错误并跳过。
     */
    public static void register() {
        try {
            // 反射获取 BWGBiomeSelectors 类
            Class<?> bwgSelectorsClass = Class.forName(
                    "net.potionstudios.biomeswevegone.world.level.levelgen.biome.selector.BWGBiomeSelectors");
            // 反射获取 TerraBlenderBiomeSelectors 类
            Class<?> tbSelectorsClass = Class.forName(
                    "net.potionstudios.biomeswevegone.world.level.levelgen.biome.selector.TerraBlenderBiomeSelectors");

            // 读取 BWG REGION_1 使用的群系数组
            ResourceKey<Biome>[][] oceans = getBiomeArray(bwgSelectorsClass, "OCEANS_BWG");
            ResourceKey<Biome>[][] middleBiomes = getBiomeArray(bwgSelectorsClass, "MIDDLE_BIOMES_BWG");
            ResourceKey<Biome>[][] middleBiomesVariant = getBiomeArray(bwgSelectorsClass, "MIDDLE_BIOMES_VARIANT_BWG");
            ResourceKey<Biome>[][] plateauBiomes = getBiomeArray(bwgSelectorsClass, "PLATEAU_BIOMES_BWG");
            ResourceKey<Biome>[][] plateauBiomesVariant = getBiomeArray(bwgSelectorsClass, "PLATEAU_BIOMES_VARIANT_BWG");
            ResourceKey<Biome>[][] shatteredBiomes = getBiomeArray(bwgSelectorsClass, "SHATTERED_BIOMES_BWG");
            ResourceKey<Biome>[][] beachBiomes = getBiomeArray(bwgSelectorsClass, "BEACH_BIOMES_BWG");
            ResourceKey<Biome>[][] peakBiomes = getBiomeArray(bwgSelectorsClass, "PEAK_BIOMES_BWG");
            ResourceKey<Biome>[][] peakBiomesVariant = getBiomeArray(bwgSelectorsClass, "PEAK_BIOMES_VARIANT_BWG");
            ResourceKey<Biome>[][] slopeBiomes = getBiomeArray(bwgSelectorsClass, "SLOPE_BIOMES_BWG");
            ResourceKey<Biome>[][] slopeBiomesVariant = getBiomeArray(tbSelectorsClass, "SLOPE_BIOMES_VARIANT_TERRABLENDER");

            TerrablenderOverworldBiomeBuilder biomeBuilder = new TerrablenderOverworldBiomeBuilder(
                    oceans, middleBiomes, middleBiomesVariant,
                    plateauBiomes, plateauBiomesVariant, shatteredBiomes,
                    beachBiomes, peakBiomes, peakBiomesVariant,
                    slopeBiomes, slopeBiomesVariant
            );

            DisasterBiomeRegion region = new DisasterBiomeRegion(biomeBuilder);
            Regions.register(region);
            BeLoongCore.LOGGER.info("[BeLoongCore] DisasterBiomeRegion registered with {} BWG biome arrays", 11);

        } catch (ClassNotFoundException e) {
            BeLoongCore.LOGGER.error("[BeLoongCore] BWG selector classes not found — BWG version may be incompatible", e);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            BeLoongCore.LOGGER.error("[BeLoongCore] Failed to access BWG biome selector fields", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static ResourceKey<Biome>[][] getBiomeArray(Class<?> clazz, String fieldName)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = clazz.getField(fieldName);
        return (ResourceKey<Biome>[][]) field.get(null);
    }

    @Override
    public void addBiomes(Registry<Biome> registry, Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> mapper) {
        this.builder.addBiomes(pair -> {
            // 直接通过，不检查 BWGWorldGenConfig
            // DEFERRED_PLACEHOLDER 会自动回退到 DefaultOverworldRegion（原版群系）
            mapper.accept(pair);
        });
    }
}
