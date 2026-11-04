package com.zonlong.beloong.mixin.dragonsurvival;

import by.dragonsurvivalteam.dragonsurvival.client.gui.AbilityTooltipPositioner;
import by.dragonsurvivalteam.dragonsurvival.client.util.RenderingUtils;
import by.dragonsurvivalteam.dragonsurvival.registry.datagen.lang.LangKey;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.util.DSColors;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector2ic;
import software.bernie.geckolib.util.Color;

import java.util.ArrayList;
import java.util.List;

import com.zonlong.beloong.client.gui.AbilityTooltipHint;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import by.dragonsurvivalteam.dragonsurvival.client.render.AbilityAndPenaltyTooltipRenderer;

/**
 * Backports Dragon Survival PR #1032's tooltip layout to the pre-PR renderer.
 * Only takes over a tooltip while AbilityButton explicitly supplies a Ctrl hint.
 * The layout below follows the upstream drawTooltip implementation, including
 * hint wrapping, height calculation and placement after the Shift footer.
 */
@Mixin(value = AbilityAndPenaltyTooltipRenderer.class, remap = false)
public abstract class AbilityAndPenaltyTooltipRendererMixin {
    @Shadow @Final private static String INFO_SHIFT;
    @Shadow @Final private static ResourceLocation EFFECT_HEADER;
    @Shadow @Final private static ResourceLocation BARS;
    @Shadow @Final private static int MAX_SHOWN_LINES;
    @Shadow private static int maxScrollAmount;

    @Shadow
    private static FormattedText formatText(List<Component> shiftInfo) {
        throw new AssertionError();
    }

    @Shadow
    private static boolean isEffectHeader(FormattedCharSequence text) {
        throw new AssertionError();
    }

    @Inject(method = "drawTooltip", at = @At("HEAD"), cancellable = true, remap = false)
    private static void beloong$drawTooltipWithHint(
            @NotNull final GuiGraphics graphics,
            int x,
            int y,
            final List<Component> shiftInfo,
            final FormattedText rawDescription,
            int colorXPos,
            int colorYPos,
            final String headerTranslationKey,
            final Component name,
            final Color tooltipBackgroundColor,
            int maxLevel,
            int abilityLevel,
            final ResourceLocation icon,
            int scrollAmount,
            CallbackInfo ci
    ) {
        Component extraHint = AbilityTooltipHint.current();
        if (extraHint == null) {
            return;
        }
        ci.cancel();

        FormattedText textContents = formatText(shiftInfo);

        int maxLineWidth = 150; // Tooltip#MAX_WIDTH is 170
        // -10 to leave some space so it doesn't overflow into the main description window
        List<FormattedCharSequence> lines = Minecraft.getInstance().font.split(textContents, maxLineWidth - 10);
        maxScrollAmount = lines.size();
        scrollAmount = Math.clamp(scrollAmount, 0, AbilityAndPenaltyTooltipRenderer.maxScroll());

        // Need to format the text for the bottom info at this point to adjust the height of the background
        List<FormattedCharSequence> bottomInfoLines;

        if (!shiftInfo.isEmpty()) {
            bottomInfoLines = Minecraft.getInstance().font.split(Component.translatable(INFO_SHIFT).withStyle(ChatFormatting.DARK_GRAY), maxLineWidth - 10);
        } else {
            bottomInfoLines = new ArrayList<>();
        }

        List<FormattedCharSequence> extraHintLines;

        // The effect header is meant to separate multiple effects from each other
        // Currently we append this header at a point in time where we don't know the actual order of the effects
        // That's why we use this check to skip the first header line
        boolean skipFirstLine = !lines.isEmpty() && isEffectHeader(lines.getFirst());

        Window window = Minecraft.getInstance().getWindow();
        int maxHeight = (int) (window.getHeight() / window.getGuiScale());
        int maxWidth = (int) (window.getWidth() / window.getGuiScale()) - maxLineWidth;

        // '65' is roughly the amount of space needed for the other components so that the name and level can be centered without overlap
        int backgroundWidth = Math.max(maxLineWidth, Minecraft.getInstance().font.width(name) + 65);
        int backgroundHeight;

        int offset = /* Size of the horizontal colored bar */ 20 + /* Line breaks between bar, title and bottom info */ 27 + /* Extra padding to account for word wrap */ 8;
        List<FormattedCharSequence> description;

        // There will still be issues if the description is way too long (side-info not being visible e.g.)
        // But at that point it's just a problem of the description content
        while (true) {
            description = Minecraft.getInstance().font.split(rawDescription, backgroundWidth - 7);
            extraHintLines = extraHint == null ? List.of() : Minecraft.getInstance().font.split(extraHint, backgroundWidth - 7);
            backgroundHeight = offset + (description.size() + bottomInfoLines.size() + extraHintLines.size()) * 9;

            if (backgroundHeight <= maxHeight) {
                break;
            }

            // Preferably, we wrap the lines into the compact default width - but if the description is too long, we need to it
            backgroundWidth = backgroundWidth + 10;

            if (Screen.hasShiftDown() && backgroundWidth > maxWidth) {
                // Forcefully show the side-info when requested
                backgroundWidth = maxWidth;
                break;
            }
        }

        // The side-info doesn't need dynamic width adjustments (scroll support when Shift is pressed)
        int sideWidth = Screen.hasShiftDown() ? maxLineWidth : 15;
        int sideHeight = Screen.hasShiftDown() ? 36 + Math.min(skipFirstLine ? lines.size() - 1 : lines.size(), MAX_SHOWN_LINES) * 9 : backgroundHeight - 10;

        ClientTooltipPositioner positioner = new AbilityTooltipPositioner(Screen.hasShiftDown() ? sideWidth : 0);
        Vector2ic position = positioner.positionTooltip(graphics.guiWidth(), graphics.guiHeight(), x, y, backgroundWidth, Math.max(sideHeight, backgroundHeight));

        int trueX = position.x();
        int trueY = position.y();

        if (!shiftInfo.isEmpty()) {
            // Backing for expandable side info
            graphics.blitWithBorder(BARS, trueX - (Screen.hasShiftDown() ? maxLineWidth : 10), trueY + 3, 40, 20, sideWidth, sideHeight, 20, 20, 3);
            // Top bar for expandable side info
            graphics.blitWithBorder(BARS, trueX - (Screen.hasShiftDown() ? maxLineWidth : 10) + 3, trueY + 9, colorXPos, colorYPos, Screen.hasShiftDown() ? maxLineWidth : 15, 20, 20, 20, 3);

            if (Screen.hasShiftDown()) {
                graphics.drawString(Minecraft.getInstance().font, Component.translatable(LangKey.INFO), trueX - maxLineWidth + 10, trueY + 15, -1);
                int counter = 0;

                for (int line = scrollAmount; line < lines.size(); line++) {
                    FormattedCharSequence text = lines.get(line);

                    int startPosition = trueX - maxLineWidth + 5;
                    int textY = trueY + 5 + 28 + counter * 9;

                    if (line == 0 && skipFirstLine) {
                        continue;
                    }

                    if (isEffectHeader(text)) {
                        RenderingUtils.setShaderColor(DSColors.withAlpha(DSColors.GOLD, 1));
                        graphics.blitSprite(EFFECT_HEADER, startPosition, textY - 4, maxLineWidth - 10, 9);
                        RenderSystem.setShaderColor(1, 1, 1, 1);
                    } else {
                        graphics.drawString(Minecraft.getInstance().font, text, startPosition, textY, DSColors.GRAY);
                    }

                    counter++;

                    if (counter == MAX_SHOWN_LINES) {
                        break;
                    }
                }
            }
        }

        // Background of the main description
        graphics.blitWithBorder(BARS, trueX - 2, trueY - 4, 40, 20, backgroundWidth + 5, backgroundHeight, 20, 20, 3, 3, 3, 3);
        // Top bar of the main description
        graphics.blitWithBorder(BARS, trueX, trueY + 3, colorXPos, colorYPos, backgroundWidth, 20, 20, 20, 3);
        // Backing square for ability icon
        graphics.blitWithBorder(BARS, trueX, trueY, 0, 100, 26, 26, 24, 24, 3);

        graphics.drawCenteredString(Minecraft.getInstance().font, Component.translatable(headerTranslationKey), trueX + backgroundWidth / 2, trueY + 30, tooltipBackgroundColor.getColor());

        if (maxLevel > DragonAbilityInstance.MIN_LEVEL) {
            graphics.drawCenteredString(Minecraft.getInstance().font, Component.empty().append(abilityLevel + "/" + maxLevel), trueX + backgroundWidth - 18, trueY + 9, -1);
            graphics.drawCenteredString(Minecraft.getInstance().font, name, trueX + backgroundWidth / 2, trueY + 9, -1);
        } else {
            graphics.drawCenteredString(Minecraft.getInstance().font, name, trueX + backgroundWidth / 2 + 10, trueY + 9, -1);
        }

        for (int line = 0; line < description.size(); line++) {
            graphics.drawString(Minecraft.getInstance().font, description.get(line), trueX + 5, trueY + 47 + line * 9, -5592406);
        }

        if (!bottomInfoLines.isEmpty()) {
            for (int i = 0; i < bottomInfoLines.size(); i++) {
                FormattedCharSequence line = bottomInfoLines.get(i);
                graphics.drawCenteredString(Minecraft.getInstance().font, line, trueX + backgroundWidth / 2, trueY + 47 + (description.size() - 1 + i) * 9, 0);
            }
        }

        if (!extraHintLines.isEmpty()) {
            // The bottom info occupies the (usually empty) last description line; place this below it
            int startLine = description.size() - 1 + Math.max(1, bottomInfoLines.size());

            for (int i = 0; i < extraHintLines.size(); i++) {
                FormattedCharSequence line = extraHintLines.get(i);
                graphics.drawCenteredString(Minecraft.getInstance().font, line, trueX + backgroundWidth / 2, trueY + 47 + (startLine + i) * 9, 0);
            }
        }

        graphics.blitSprite(icon, trueX + 5, trueY + 5, 16, 16);
    }

}
