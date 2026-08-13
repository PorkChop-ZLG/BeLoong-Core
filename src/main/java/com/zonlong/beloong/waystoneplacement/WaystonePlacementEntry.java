package com.zonlong.beloong.waystoneplacement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 龙宫预设传送石碑条目。
 * <p>
 * 数据驱动，从 {@code data/beloong/beloong/waystone_placement/*.json} 加载。
 * 每个条目描述一个「固定地图」上需要由程序放置的传送石碑：
 * <ul>
 *   <li>{@code pos} 为石碑下半块（Lower half）的世界坐标；</li>
 *   <li>{@code style} 仅支持普通 waystone 的材质样式，默认 {@code waystone}；</li>
 *   <li>{@code name} 为该石碑的固定显示名。</li>
 * </ul>
 *
 * @see WaystonePlacementLoader
 * @see WaystonePlacementHandler
 */
public record WaystonePlacementEntry(
        String id,
        BlockPos pos,
        String style,
        String name
) {

    public static final Codec<WaystonePlacementEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(WaystonePlacementEntry::id),
            BlockPos.CODEC.fieldOf("pos").forGetter(WaystonePlacementEntry::pos),
            Codec.STRING.optionalFieldOf("style", "waystone").forGetter(WaystonePlacementEntry::style),
            Codec.STRING.fieldOf("name").forGetter(WaystonePlacementEntry::name)
    ).apply(instance, WaystonePlacementEntry::new));

    /**
     * 确定性 UUID：同名预设石碑在所有存档中共享同一个 UUID。
     * <p>
     * 即使 {@code waystones.dat} 丢失导致数据重建，UUID 也不会漂移，
     * 玩家对该石碑的激活状态与绑定物品因此不会失效。
     */
    public UUID fixedUid() {
        return UUID.nameUUIDFromBytes(("beloong:waystone_placement:" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** 固定显示名（{@link Component} 形式）。 */
    public Component nameComponent() {
        return Component.literal(name);
    }
}
