package com.zonlong.beloong.item;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

public class AmplificationCharmItem extends Item implements ICurioItem {

    private static final ResourceLocation MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath("beloong", "amplification_charm");

    public AmplificationCharmItem() {
        super(new Item.Properties()
                .stacksTo(1)
                .rarity(Rarity.RARE)
                .fireResistant());
    }

    @Override
    public Multimap<Holder<Attribute>, AttributeModifier> getAttributeModifiers(
            SlotContext slotContext, ResourceLocation id, ItemStack stack) {
        Multimap<Holder<Attribute>, AttributeModifier> mods = LinkedHashMultimap.create();
        mods.put(Attributes.SCALE,
                new AttributeModifier(MODIFIER_ID, 0.75,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        return mods;
    }

    @Override
    public boolean canEquipFromUse(SlotContext slotContext, ItemStack stack) {
        return true;
    }
}
