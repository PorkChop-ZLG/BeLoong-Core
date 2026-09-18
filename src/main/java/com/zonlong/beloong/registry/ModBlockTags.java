package com.zonlong.beloong.registry;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * 本模组用到的<b>方块 tag</b> 常量。
 * <p>
 * 目前只有一个：龙宫传送门的框架方块 tag。数据包文件在
 * {@code data/beloong/tags/block/loong_palace_portal_frame.json}（默认只含荧石）。
 * <p>
 * <b>为什么用 tag 而不是写死方块</b>：整合包/其他模组可以用数据包替换框架材料，
 * 不需要改代码；与项目里已有的 {@code #minecraft:needs_netherite_tool} 之类做法同类。
 */
public final class ModBlockTags {

    /** 可作为龙宫传送门框架的方块（默认：荧石）。 */
    public static final TagKey<Block> LOONG_PALACE_PORTAL_FRAME = TagKey.create(
            Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "loong_palace_portal_frame"));

    private ModBlockTags() {}
}
