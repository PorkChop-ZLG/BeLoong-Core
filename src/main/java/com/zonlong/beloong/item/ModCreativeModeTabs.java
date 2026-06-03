package com.zonlong.beloong.item;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 化龙核心模组的创造模式物品栏标签页。
 * <p>
 * 注册了一个名为"化龙"的标签页，包含模组的所有物品：
 * <p>
 * 玩家在创造模式下可以通过此标签页直接获取传送门方块，
 * 无需使用 {@code /give} 命令。
 */
public class ModCreativeModeTabs {

    /** 创造模式标签页延迟注册器 */
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BeLoongCore.MODID);

    /**
     * "化龙"创造模式标签页。
     * <p>
     * 图标使用 {@link ModItems#BELOONG_LOGO}，
     * 标题使用本地化键 {@code itemGroup.beloong_tab}。
     */
    public static final Supplier<CreativeModeTab> BELOONG_TAB =
            CREATIVE_MODE_TABS.register("beloong_tab", () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModItems.BELOONG_LOGO.get()))
                    .title(Component.translatable("itemGroup.beloong_tab"))
                    .displayItems((itemDisplayParameters, output) -> {
                        output.accept(ModItems.BELOONG_LOGO);
                        output.accept(ModItems.ETERNAL_PORKCHOP);
                        output.accept(ModItems.DISASTER_PORTAL_FRAME);
                        output.accept(ModItems.DISASTER_PORTAL_BLOCK);
                    }).build());

    /** 将标签页注册到 Mod 事件总线 */
    public static void register(IEventBus eventBus) {
        CREATIVE_MODE_TABS.register(eventBus);
    }
}
