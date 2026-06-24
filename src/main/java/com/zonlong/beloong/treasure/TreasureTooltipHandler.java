package com.zonlong.beloong.treasure;

import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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

    private static final String VALUE_KEY = "tooltip.beloong.treasure_value";
    private static final String LIMIT_KEY = "tooltip.beloong.treasure_limit";

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

        MutableComponent valueLine = Component.translatable(VALUE_KEY,
                Component.literal(String.format(Locale.ROOT, "%.1f", entry.value()))
                        .withStyle(ChatFormatting.YELLOW)
        ).withStyle(ChatFormatting.GOLD);
        event.getToolTip().add(valueLine);

        if (entry.limit() != Integer.MAX_VALUE) {
            MutableComponent limitLine = Component.translatable(LIMIT_KEY,
                    Component.literal(String.valueOf(entry.limit()))
                            .withStyle(ChatFormatting.YELLOW)
            ).withStyle(ChatFormatting.GOLD);
            event.getToolTip().add(limitLine);
        }
    }
}
