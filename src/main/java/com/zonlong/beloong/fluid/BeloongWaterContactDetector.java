package com.zonlong.beloong.fluid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

public final class BeloongWaterContactDetector {

    private BeloongWaterContactDetector() {}

    public static boolean isTouching(ServerPlayer player) {
        AABB playerBounds = player.getBoundingBox();

        for (BeloongWaterRegion region : BeloongWaterRegionLoader.INSTANCE
                .getRegions(player.level().dimension())) {
            AABB regionBounds = region.bounds();
            if (!regionBounds.intersects(playerBounds)
                    || !containsIntersectingWater(player, playerBounds, regionBounds)) {
                continue;
            }
            return true;
        }

        return false;
    }

    private static boolean containsIntersectingWater(
            ServerPlayer player,
            AABB playerBounds,
            AABB regionBounds) {
        double minX = Math.max(playerBounds.minX, regionBounds.minX);
        double minY = Math.max(playerBounds.minY, regionBounds.minY);
        double minZ = Math.max(playerBounds.minZ, regionBounds.minZ);
        double maxX = Math.min(playerBounds.maxX, regionBounds.maxX);
        double maxY = Math.min(playerBounds.maxY, regionBounds.maxY);
        double maxZ = Math.min(playerBounds.maxZ, regionBounds.maxZ);

        if (minX >= maxX || minY >= maxY || minZ >= maxZ) {
            return false;
        }

        int minBlockX = Mth.floor(minX);
        int minBlockY = Mth.floor(minY);
        int minBlockZ = Mth.floor(minZ);
        int maxBlockX = Mth.floor(Math.nextDown(maxX));
        int maxBlockY = Mth.floor(Math.nextDown(maxY));
        int maxBlockZ = Mth.floor(Math.nextDown(maxZ));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int x = minBlockX; x <= maxBlockX; x++) {
            for (int y = minBlockY; y <= maxBlockY; y++) {
                for (int z = minBlockZ; z <= maxBlockZ; z++) {
                    pos.set(x, y, z);
                    FluidState fluidState = player.level().getFluidState(pos);
                    if (!isVanillaWater(fluidState.getType())) {
                        continue;
                    }

                    double fluidHeight = fluidState.getHeight(player.level(), pos);
                    AABB fluidBounds = new AABB(
                            x, y, z,
                            x + 1.0, y + fluidHeight, z + 1.0);
                    if (fluidBounds.intersects(playerBounds)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static boolean isVanillaWater(Fluid fluid) {
        return fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER;
    }
}
