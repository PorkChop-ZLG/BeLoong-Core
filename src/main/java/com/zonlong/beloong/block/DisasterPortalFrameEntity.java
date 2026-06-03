package com.zonlong.beloong.block;

import com.zonlong.beloong.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 天灾传送门框架的 BlockEntity。
 * <p>
 * 每个 {@link DisasterPortalFrame} 方块都绑定一个该 BlockEntity 实例，
 * 用于持久化存储嵌入的眼球物品 ID（如 {@code "cataclysm:mech_eye"}）。
 * <p>
 * <b>存储字段：</b>
 * <ul>
 *   <li>{@code eyeId} — 嵌入的眼球完整物品 ID 字符串，默认值 {@code "empty"} 表示空框架</li>
 * </ul>
 * <p>
 * <b>数据同步：</b>
 * 通过 {@link #getUpdateTag} 和 {@link #getUpdatePacket} 向客户端同步，
 * 确保客户端渲染能获取正确的眼球类型信息。
 * <p>
 * 该 BlockEntity 通过 {@link ModBlocks#DISASTER_PORTAL_FRAME_ENTITY} 注册。
 *
 * @see DisasterPortalFrame
 */
public class DisasterPortalFrameEntity extends BlockEntity {

    /** 嵌入的眼球完整物品 ID（如 "cataclysm:mech_eye"），默认 "empty" 表示无眼球 */
    private String eyeId = "empty";

    public DisasterPortalFrameEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.DISASTER_PORTAL_FRAME_ENTITY.get(), pos, state);
    }

    /**
     * 将眼球 ID 保存到 NBT 标签中。
     * 用于区块卸载时持久化，确保重新加载区块后眼球数据不丢失。
     */
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("eye_id", this.eyeId);
    }

    /**
     * 从 NBT 标签中读取眼球 ID。
     * 在区块加载或从磁盘读取时调用。
     */
    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.eyeId = tag.getString("eye_id");
    }

    /**
     * 获取用于客户端同步的 NBT 标签。
     * NeoForge 在向客户端发送方块更新时会调用此方法。
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    /**
     * 创建客户端同步数据包。
     * 当方块状态或 BlockEntity 数据变化时，NeoForge 调用此方法
     * 向客户端发送更新数据包。
     */
    @Override
    @Nullable
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** 设置嵌入的眼球物品 ID。调用后需要 {@link #setChanged()} 标记脏数据。 */
    public void setEyeId(String eyeId) {
        this.eyeId = eyeId;
    }

    /** 获取嵌入的眼球物品 ID（完整 ResourceLocation 字符串）。 */
    public String getEyeId() {
        return this.eyeId;
    }

    /** 检查该框架是否为空（未嵌入任何眼球）。 */
    public boolean isEmpty() {
        return "empty".equals(this.eyeId);
    }
}
