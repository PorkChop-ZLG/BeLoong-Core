package com.zonlong.beloong.worldgen;

import com.mojang.datafixers.util.Pair;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
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

            // 过滤 THE_VOID — BWG 用它标记"此位置无群系"，TerraBlender 使用 null 表示回退
            oceans = filterVoidBiomes(oceans);
            middleBiomes = filterVoidBiomes(middleBiomes);
            middleBiomesVariant = filterVoidBiomes(middleBiomesVariant);
            plateauBiomes = filterVoidBiomes(plateauBiomes);
            plateauBiomesVariant = filterVoidBiomes(plateauBiomesVariant);
            shatteredBiomes = filterVoidBiomes(shatteredBiomes);
            beachBiomes = filterVoidBiomes(beachBiomes);
            peakBiomes = filterVoidBiomes(peakBiomes);
            peakBiomesVariant = filterVoidBiomes(peakBiomesVariant);
            slopeBiomes = filterVoidBiomes(slopeBiomes);
            slopeBiomesVariant = filterVoidBiomes(slopeBiomesVariant);

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
        Object raw = field.get(null);
        if (raw == null) {
            throw new IllegalAccessException("Field " + fieldName + " is null");
        }
        Object value = raw;
        // CorgiLib 将静态字段包装为 Wrapped Record，需解包
        if (!(raw instanceof ResourceKey[][])) {
            try {
                java.lang.reflect.Method valueMethod = raw.getClass().getMethod("value");
                value = valueMethod.invoke(raw);
            } catch (Exception e) {
                throw new IllegalAccessException("Failed to unwrap " + fieldName + ": " + e.getMessage());
            }
        }
        // CorgiLib codec 将数组序列化为 List，需转换回二维数组
        if (value instanceof java.util.List<?> list) {
            return convertListToBiomeArray(list);
        }
        return (ResourceKey<Biome>[][]) value;
    }

    @SuppressWarnings("unchecked")
    private static ResourceKey<Biome>[][] convertListToBiomeArray(java.util.List<?> list) {
        int rows = list.size();
        ResourceKey<Biome>[][] result = new ResourceKey[rows][];
        for (int i = 0; i < rows; i++) {
            Object row = list.get(i);
            if (row instanceof java.util.List<?> innerList) {
                result[i] = innerList.toArray(new ResourceKey[0]);
            } else if (row instanceof ResourceKey[]) {
                result[i] = (ResourceKey<Biome>[]) row;
            } else {
                result[i] = new ResourceKey[0];
            }
        }
        return result;
    }

    /** 将数组中的 THE_VOID 替换为 null，与 BWG 的 BWGRegionUtils.filter() 行为一致 */
    private static ResourceKey<Biome>[][] filterVoidBiomes(ResourceKey<Biome>[][] array) {
        for (int i = 0; i < array.length; i++) {
            for (int j = 0; j < array[i].length; j++) {
                if (array[i][j] == Biomes.THE_VOID) {
                    array[i][j] = null; // TerraBlender 用 null 表示回退到原版/常规群系
                }
            }
        }
        return array;
    }

    @Override
    public void addBiomes(Registry<Biome> registry, Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> mapper) {
        // OverworldBiomeBuilder.addBiomes() 在 NeoForge 中是 protected，需反射调用
        try {
            java.lang.reflect.Method method = this.builder.getClass().getSuperclass().getDeclaredMethod("addBiomes", Consumer.class);
            method.setAccessible(true);
            method.invoke(this.builder, (Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>>) pair -> {
                // 直接通过，不检查 BWGWorldGenConfig
                // DEFERRED_PLACEHOLDER 会自动回退到 DefaultOverworldRegion（原版群系）
                mapper.accept(pair);
            });
        } catch (Exception e) {
            BeLoongCore.LOGGER.error("[BeLoongCore] Failed to invoke addBiomes via reflection", e);
        }
    }
}
