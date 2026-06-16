package com.zonlong.beloong.treasure;

import by.dragonsurvivalteam.dragonsurvival.common.blocks.TreasureBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;

public final class TreasureValueCalculator {
    private TreasureValueCalculator() {}

    public static double calculateWeightedValue(ServerPlayer player) {
        Map<Block, Integer> dragonLayers = new HashMap<>();
        Map<Block, Integer> otherCounts = new HashMap<>();

        AABB area = AABB.ofSize(player.position(), 16, 9, 16);

        for (BlockPos pos : BlockPos.betweenClosed(
                (int) Math.floor(area.minX), (int) Math.floor(area.minY), (int) Math.floor(area.minZ),
                (int) Math.ceil(area.maxX), (int) Math.ceil(area.maxY), (int) Math.ceil(area.maxZ))) {
            BlockState state = player.serverLevel().getBlockState(pos);
            Block block = state.getBlock();

            if (block instanceof TreasureBlock) {
                TreasureGrowthEntry entry = TreasureGrowthLoader.INSTANCE.getDragonEntry(block);
                if (entry != null) {
                    int layers = state.getValue(TreasureBlock.LAYERS);
                    dragonLayers.merge(block, layers, Integer::sum);
                }
            }

            TreasureGrowthEntry otherEntry = TreasureGrowthLoader.INSTANCE.getOtherEntry(block);
            if (otherEntry != null) {
                otherCounts.merge(block, 1, Integer::sum);
            }
        }

        double total = 0;

        for (Map.Entry<Block, Integer> e : dragonLayers.entrySet()) {
            TreasureGrowthEntry entry = TreasureGrowthLoader.INSTANCE.getDragonEntry(e.getKey());
            int applied = Math.min(e.getValue(), entry.limit());
            total += applied * entry.value();
        }

        for (Map.Entry<Block, Integer> e : otherCounts.entrySet()) {
            TreasureGrowthEntry entry = TreasureGrowthLoader.INSTANCE.getOtherEntry(e.getKey());
            int applied = Math.min(e.getValue(), entry.limit());
            total += applied * entry.value();
        }

        return total;
    }

    public static int valueToAmplifier(double value, int step, int maxAmplifier) {
        return Math.clamp((int) (value / step), 0, maxAmplifier);
    }

    public static double amplifierToMultiplier(int amplifier) {
        return amplifier + 2.0;
    }
}
