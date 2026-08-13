package com.zonlong.beloong.waystoneplacement;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 龙宫预设传送石碑数据加载器。
 * <p>
 * 从 {@code data/beloong/beloong/waystone_placement/*.json} 读取石碑清单，
 * 在服务端数据重载时刷新。数据文件为 JSON 数组，每个元素是一个
 * {@link WaystonePlacementEntry}。
 */
public class WaystonePlacementLoader extends SimpleJsonResourceReloadListener {

    public static final WaystonePlacementLoader INSTANCE = new WaystonePlacementLoader();

    private static final Codec<List<WaystonePlacementEntry>> FILE_CODEC =
            Codec.list(WaystonePlacementEntry.CODEC);

    private List<WaystonePlacementEntry> entries = List.of();

    private WaystonePlacementLoader() {
        super(new Gson(), "beloong/waystone_placement");
    }

    @Override
    protected void apply(@NotNull Map<ResourceLocation, JsonElement> files,
                         @NotNull ResourceManager manager,
                         @NotNull ProfilerFiller profiler) {
        List<WaystonePlacementEntry> newEntries = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet()) {
            FILE_CODEC.parse(JsonOps.INSTANCE, file.getValue())
                    .resultOrPartial(error -> BeLoongCore.LOGGER.error(
                            "Failed to parse loong palace waystone file '{}': {}", file.getKey(), error))
                    .ifPresent(newEntries::addAll);
        }
        this.entries = List.copyOf(newEntries);
        BeLoongCore.LOGGER.debug("Reloaded loong palace waystones: {} entries", entries.size());
    }

    public List<WaystonePlacementEntry> getEntries() {
        return entries;
    }
}
