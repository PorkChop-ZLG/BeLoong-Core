package com.zonlong.beloong.client.gui;

import by.dragonsurvivalteam.dragonsurvival.client.render.AbilityAndPenaltyTooltipRenderer;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** Call-scoped context for the old renderer API, which has no extra-hint parameter. */
public final class AbilityTooltipHint {
    private static final ThreadLocal<Component> CURRENT = new ThreadLocal<>();

    private AbilityTooltipHint() {}

    @Nullable
    public static Component current() {
        return CURRENT.get();
    }

    public static void draw(GuiGraphics graphics, int x, int y,
                            DragonAbilityInstance ability, int scrollAmount, boolean showCtrlHint) {
        Component previous = CURRENT.get();
        Component hint = null;
        if (showCtrlHint && ability.value().canBeManuallyDisabled()) {
            boolean disabled = ability.isDisabled(true);
            hint = Component.translatable(disabled
                    ? "beloong.gui.ability.ctrl_enable"
                    : "beloong.gui.ability.ctrl_disable")
                    .withStyle(disabled ? ChatFormatting.GREEN : ChatFormatting.RED);
        }

        if (hint == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(hint);
        }
        try {
            AbilityAndPenaltyTooltipRenderer.drawAbilityTooltip(graphics, x, y, ability, scrollAmount);
        } finally {
            // Also restore context on rendering failure or a nested tooltip call.
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
