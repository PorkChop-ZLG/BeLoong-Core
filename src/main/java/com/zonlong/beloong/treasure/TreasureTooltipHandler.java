package com.zonlong.beloong.treasure;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

@EventBusSubscriber(value = Dist.CLIENT)
public class TreasureTooltipHandler {

    private static final String VALUE_KEY = "beloong.treasure_tooltip.value";
    private static final String LIMIT_KEY = "beloong.treasure_tooltip.limit";

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        Item item = stack.getItem();

        if (!(item instanceof BlockItem blockItem)) {
            return;
        }

        Block block = blockItem.getBlock();

        TreasureGrowthEntry entry = TreasureGrowthLoader.INSTANCE.getDragonEntry(block);
        if (entry == null) {
            entry = TreasureGrowthLoader.INSTANCE.getOtherEntry(block);
        }

        if (entry == null) {
            return;
        }

        event.getToolTip().add(Component.translatable(VALUE_KEY, String.format("%.1f", entry.value())));

        if (entry.limit() != Integer.MAX_VALUE) {
            event.getToolTip().add(Component.translatable(LIMIT_KEY, entry.limit()));
        }
    }
}
