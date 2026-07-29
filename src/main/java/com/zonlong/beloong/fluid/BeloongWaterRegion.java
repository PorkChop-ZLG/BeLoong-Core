package com.zonlong.beloong.fluid;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

public record BeloongWaterRegion(
        ResourceKey<Level> dimension,
        BlockPos min,
        BlockPos max
) {

    public BeloongWaterRegion {
        int minX = Math.min(min.getX(), max.getX());
        int minY = Math.min(min.getY(), max.getY());
        int minZ = Math.min(min.getZ(), max.getZ());
        int maxX = Math.max(min.getX(), max.getX());
        int maxY = Math.max(min.getY(), max.getY());
        int maxZ = Math.max(min.getZ(), max.getZ());

        min = new BlockPos(minX, minY, minZ);
        max = new BlockPos(maxX, maxY, maxZ);
    }

    public AABB bounds() {
        return new AABB(
                min.getX(), min.getY(), min.getZ(),
                (double) max.getX() + 1.0,
                (double) max.getY() + 1.0,
                (double) max.getZ() + 1.0);
    }

    public boolean intersects(AABB box) {
        return bounds().intersects(box);
    }
}
