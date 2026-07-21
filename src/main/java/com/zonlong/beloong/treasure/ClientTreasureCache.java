package com.zonlong.beloong.treasure;

import com.zonlong.beloong.network.TreasureSyncPayload.SyncedEntry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端财宝数据缓存。
 * <p>
 * 服务端通过 {@code TreasureSyncPayload} 将全量财宝条目同步至客户端后写入此缓存。
 * {@link TreasureTooltipHandler} 从中读取数据以渲染 tooltip。
 */
public class ClientTreasureCache {
    public static final ClientTreasureCache INSTANCE = new ClientTreasureCache();

    private Map<Block, TreasureGrowthEntry> dragonEntries = Map.of();
    private Map<Block, TreasureGrowthEntry> otherEntries = Map.of();

    /**
     * 从网络包加载条目，重建缓存 Map。
     *
     * @param entries 服务端同步的财宝条目列表
     */
    public void loadFromSync(List<SyncedEntry> entries) {
        Map<Block, TreasureGrowthEntry> newDragon = new HashMap<>();
        Map<Block, TreasureGrowthEntry> newOther = new HashMap<>();
        for (SyncedEntry e : entries) {
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(e.blockId()));
            if (block == null) continue;
            TreasureGrowthEntry entry = new TreasureGrowthEntry(block, e.value(), e.limit());
            (e.isDragon() ? newDragon : newOther).put(block, entry);
        }
        this.dragonEntries = Map.copyOf(newDragon);
        this.otherEntries = Map.copyOf(newOther);
    }

    @Nullable
    public TreasureGrowthEntry getDragonEntry(Block block) {
        return dragonEntries.get(block);
    }

    @Nullable
    public TreasureGrowthEntry getOtherEntry(Block block) {
        return otherEntries.get(block);
    }
}
