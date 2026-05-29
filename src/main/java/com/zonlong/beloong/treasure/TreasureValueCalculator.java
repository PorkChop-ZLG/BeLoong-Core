package com.zonlong.beloong.treasure;

import by.dragonsurvivalteam.dragonsurvival.common.blocks.TreasureBlock;
import com.zonlong.beloong.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 根据配置中的方块→权重映射，计算加权财宝值 */
public final class TreasureValueCalculator {
    private TreasureValueCalculator() {}

    /** 从配置字符串解析 "modid:block=weight" 格式，构建 Block→Double 权重表 */
    public static Map<Block, Double> buildWeightMap() {
        Map<Block, Double> map = new HashMap<>();
        List<? extends String> entries = Config.TreasureGrowth.treasureWeights.get();

        for (String entry : entries) {
            int eqIdx = entry.lastIndexOf('=');
            if (eqIdx <= 0) continue;

            String id = entry.substring(0, eqIdx);
            double weight;
            try {
                weight = Double.parseDouble(entry.substring(eqIdx + 1));
            } catch (NumberFormatException e) {
                continue;
            }

            ResourceLocation loc = ResourceLocation.tryParse(id);
            if (loc == null) continue;

            BuiltInRegistries.BLOCK.getOptional(loc).ifPresent(block -> map.put(block, weight));
        }

        return map;
    }

    /** 扫描玩家周围 16x9x16 范围，累加每个财宝方块的 层数×类型权重 */
    public static double calculateWeightedValue(ServerPlayer player, Map<Block, Double> weights) {
        double total = 0;
        AABB area = AABB.ofSize(player.position(), 16, 9, 16);

        for (BlockPos pos : BlockPos.betweenClosed(
                (int) Math.floor(area.minX), (int) Math.floor(area.minY), (int) Math.floor(area.minZ),
                (int) Math.ceil(area.maxX), (int) Math.ceil(area.maxY), (int) Math.ceil(area.maxZ))) {
            BlockState state = player.serverLevel().getBlockState(pos);
            if (state.getBlock() instanceof TreasureBlock) {
                int layers = state.getValue(TreasureBlock.LAYERS);
                double weight = weights.getOrDefault(state.getBlock(), 1.0);
                total += layers * weight;
            }
        }

        return total;
    }

    /** 财宝值 → 效果等级：value / step，封顶 maxAmplifier */
    public static int valueToAmplifier(double value, int step, int maxAmplifier) {
        return Math.clamp((int) (value / step), 0, maxAmplifier);
    }

    /**
     * 效果等级 → 成长倍率。
     * growth_acceleration 的 modifier amount=1.0(ADD_VALUE)，
     * 经 amplifier 缩放后 growth_speed = 1.0 + 1.0 * (amplifier + 1) = amplifier + 2
     */
    public static double amplifierToMultiplier(int amplifier) {
        return amplifier + 2.0;
    }
}
