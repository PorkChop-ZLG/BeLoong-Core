package com.zonlong.beloong.block;

import com.zonlong.beloong.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class DisasterPortalFrameEntity extends BlockEntity {
    private String eyeId = "empty";

    public DisasterPortalFrameEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.DISASTER_PORTAL_FRAME_ENTITY.get(), pos, state);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("eye_id", this.eyeId);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.eyeId = tag.getString("eye_id");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    @Nullable
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    public void setEyeId(String eyeId) {
        this.eyeId = eyeId;
    }

    public String getEyeId() {
        return this.eyeId;
    }

    public boolean isEmpty() {
        return "empty".equals(this.eyeId);
    }
}
