package com.zonlong.beloong.mixin.dragonsurvival;

import by.dragonsurvivalteam.dragonsurvival.client.gui.widgets.buttons.AbilityButton;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import com.zonlong.beloong.client.gui.AbilityTooltipHint;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Dragon Survival PR #1032: show the manual toggle hint outside the hotbar. */
@Mixin(value = AbilityButton.class, remap = false)
public abstract class AbilityButtonMixin {
    @Shadow @Final private boolean isHotbar;

    @Redirect(method = "renderWidget", at = @At(value = "INVOKE", target =
            "Lby/dragonsurvivalteam/dragonsurvival/client/render/AbilityAndPenaltyTooltipRenderer;drawAbilityTooltip(Lnet/minecraft/client/gui/GuiGraphics;IILby/dragonsurvivalteam/dragonsurvival/registry/dragon/ability/DragonAbilityInstance;I)V"), remap = false)
    private void beloong$drawAbilityTooltip(GuiGraphics graphics, int x, int y,
                                           DragonAbilityInstance ability, int scrollAmount) {
        AbilityTooltipHint.draw(graphics, x, y, ability, scrollAmount, !isHotbar);
    }
}
