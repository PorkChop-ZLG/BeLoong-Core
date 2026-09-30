package com.zonlong.beloong.item;

import com.zonlong.beloong.registry.ModItemTags;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * 地狱之门的 BlockItem。
 * <p>
 * 唯一的行为是在 tooltip 里列出**当前能开这扇门的钥匙名字**。
 * <p>
 * <b>为什么列名字而不是写「标签」</b>：玩家看的是「我要拿什么去开门」，
 * 直接显示物品名才有用；tag 名（{@code #beloong:hell_gate_keys}）是给整合包作者看的，
 * 放在设计文档里就够了。
 * <p>
 * 钥匙集合来自 {@link ModItemTags#HELL_GATE_KEYS}，因此 tooltip 会**跟着数据包变**：
 * 整合包往 tag 里加了什么，这里就显示什么（每把钥匙一行）。
 * 名字取 {@link ItemStack#getHoverName()}，所以钥匙自带的自定义译名也会跟着走。
 * <p>
 * 物品 tag 是**双端同步**的（服务端在登录/`/reload` 时把 tag 发给客户端），
 * 所以客户端能查到完整内容；但 {@link TooltipContext#registries()} 本身是可空的
 * （例如 {@code TooltipContext.EMPTY}），因此这里先判空再查。
 */
public class HellGateBlockItem extends BlockItem {

    public HellGateBlockItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        HolderLookup.Provider registries = context.registries();
        if (registries == null) {
            return;
        }

        HolderSet.Named<Item> keys = registries.lookupOrThrow(Registries.ITEM)
                .get(ModItemTags.HELL_GATE_KEYS)
                .orElse(null);

        if (keys == null || keys.size() == 0) {
            // tag 被数据包清空/删掉了：据实说明「没有钥匙能开」，与加载期告警一起构成可排查的组合
            tooltipComponents.add(Component.translatable("item.beloong.hell_gate.no_key"));
            return;
        }

        for (Holder<Item> key : keys) {
            tooltipComponents.add(Component.translatable(
                    "item.beloong.hell_gate.key",
                    new ItemStack(key.value()).getHoverName()));
        }
    }
}
