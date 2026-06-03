package com.zonlong.beloong.block;

import com.zonlong.beloong.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class DisasterPortalBlockEntity extends BlockEntity {

    public DisasterPortalBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.DISASTER_PORTAL_BLOCK_ENTITY.get(), pos, state);
    }
}
