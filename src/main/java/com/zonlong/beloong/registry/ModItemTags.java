package com.zonlong.beloong.registry;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * 本模组用到的<b>物品 tag</b> 常量。
 * <p>
 * 目前只有一个：地狱之门的开门钥匙 tag。数据包文件在
 * {@code data/beloong/tags/item/hell_gate_keys.json}（默认只含铁魔法的骸骨钥匙
 * {@code irons_spellbooks:bone_key}）。
 * <p>
 * <b>为什么用 tag 而不是写死物品</b>：灾变把钥匙写死成 {@code cataclysm:strange_key}，整合包改不了；
 * 换成 tag 之后，整合包/其他模组用数据包就能增删钥匙（因而天然支持多种钥匙，也支持
 * {@code "#其他:tag"} 形式的嵌套 tag），不必改代码或配置，且 {@code /reload} 即时生效。
 * 与本模组已有的 {@link ModBlockTags#LOONG_PALACE_PORTAL_FRAME}（龙宫门框架方块）同类做法。
 * <p>
 * 想让整合包「追加」钥匙时，文件<b>必须写在 {@code beloong} 命名空间下</b>
 * （tag 文件的命名空间 = tag ID 的命名空间，不是整合包自己的）：
 * {@code data/beloong/tags/item/hell_gate_keys.json}，用 {@code "replace": false} 合并；
 * 彻底替换则用 {@code "replace": true}，或用 KubeJS 的 {@code ServerEvents.tags('item', ...)} 增删。
 * <p>
 * 注意：tag 缺失或为空时**不会崩**，只是「谁都开不了门」（{@code Holder#is} 对未绑定的 tag 返回 {@code false}）。
 * 这条静默失败面由 {@link com.zonlong.beloong.block.HellGateKeyWatcher} 在加载期告警兜住。
 */
public final class ModItemTags {

    /** 可以开启地狱之门的物品（默认：铁魔法骸骨钥匙）。 */
    public static final TagKey<Item> HELL_GATE_KEYS = TagKey.create(
            Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "hell_gate_keys"));

    private ModItemTags() {}
}
