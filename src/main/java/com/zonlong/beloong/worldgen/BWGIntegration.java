package com.zonlong.beloong.worldgen;

import com.zonlong.beloong.BeLoongCore;
import net.neoforged.fml.ModList;

/**
 * BWG/TerraBlender 可选依赖门面。
 * 负责检测依赖、注册 DisasterBiomeRegion、触发反射初始化。
 */
public final class BWGIntegration {

    private static final String BWG_MOD_ID = "biomeswevegone";
    private static final String TB_MOD_ID = "terrablender";

    private static boolean initialized = false;

    private BWGIntegration() {}

    /**
     * 在主 mod 构造器中调用。若 BWG 或 TerraBlender 未安装，静默跳过。
     */
    public static void init() {
        if (initialized) return;
        initialized = true;

        if (!ModList.get().isLoaded(TB_MOD_ID)) {
            BeLoongCore.LOGGER.info("[BeLoongCore] TerraBlender not installed, skipping BWG biome injection");
            return;
        }
        if (!ModList.get().isLoaded(BWG_MOD_ID)) {
            BeLoongCore.LOGGER.info("[BeLoongCore] BWG not installed, disaster dimension will use vanilla biomes only");
            return;
        }

        try {
            DisasterBiomeRegion.register();
            BeLoongCore.LOGGER.info("[BeLoongCore] DisasterBiomeRegion registered for BWG biome injection");
        } catch (Exception e) {
            BeLoongCore.LOGGER.error("[BeLoongCore] Failed to register DisasterBiomeRegion", e);
        }
    }

    public static boolean isEnabled() {
        return initialized
                && ModList.get().isLoaded(BWG_MOD_ID)
                && ModList.get().isLoaded(TB_MOD_ID);
    }
}
