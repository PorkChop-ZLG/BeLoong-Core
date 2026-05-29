package com.zonlong.beloong.client;

/** 客户端共享数据缓存，由网络包写入、HUD overlay 读取。两边均可加载，无 @OnlyIn */
public class TreasureGrowthClientData {
    private static double treasureValue;
    private static double growthMultiplier;
    private static boolean isResting;
    private static long lastUpdateTick;

    public static void update(double treasureValue, double growthMultiplier, boolean isResting) {
        TreasureGrowthClientData.treasureValue = treasureValue;
        TreasureGrowthClientData.growthMultiplier = growthMultiplier;
        TreasureGrowthClientData.isResting = isResting;
        TreasureGrowthClientData.lastUpdateTick = System.currentTimeMillis();
    }

    public static double getTreasureValue() {
        return treasureValue;
    }

    public static double getGrowthMultiplier() {
        return growthMultiplier;
    }

    public static boolean isResting() {
        return isResting;
    }

    /** 距上次更新超过 2 秒视为数据过期，防止网络断连后显示残留数据 */
    public static boolean hasRecentData() {
        return System.currentTimeMillis() - lastUpdateTick < 2000;
    }
}
