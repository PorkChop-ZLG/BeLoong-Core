package com.zonlong.beloong.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 对话选项按钮：参考图里的"深黑半透明胶囊 + 左侧图标"。
 * <p>
 * 继承 {@link AbstractWidget} 而不是 {@code Button}：外观要完全自绘（全圆角胶囊、
 * 悬停金色渐变），但点击判定、悬停判定、键盘导航与旁白（narration）都白拿。
 * <p>
 * <b>悬停过渡</b>：{@link #hover} 是一个 0→1 的进度，由 {@link NpcDialogueScreen#tick()}
 * 每 tick 调用 {@link #tickHover()} 推进（约 200ms 走完），再用
 * {@link FastColor.ARGB32#lerp} 在常态底色与暖金之间插值。
 * 刻意放在 {@code tick()} 而不是渲染里推进：渲染帧率可变，用帧做步进会让过渡速度随帧率漂移。
 */
@OnlyIn(Dist.CLIENT)
public class NpcDialogueOptionButton extends AbstractWidget {

    /** 常态底色 RGB：深蓝黑（取自参考图）。 */
    private static final int NORMAL_RGB = 0x1A1F26;
    /** 悬停底色 RGB：暖金。 */
    private static final int HOVER_RGB = 0xC8A05A;
    /** 底衬不透明度（0-255）。 */
    private static final int BASE_ALPHA = 0xC0;
    /**
     * 右侧渐隐起点（占宽度比例）：前 55% 为实心底衬，其后线性淡出到全透明。
     * <p>
     * 参考图的选项底衬**右侧没有硬边**，是渐渐化开的 —— 因此右侧不需要圆角，
     * 只要把 alpha 收到 0 即可（"直角"在视觉上根本不会出现）。
     */
    private static final float FADE_START = 0.55F;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int ICON_COLOR = 0xFFEDEDED;

    /** 图标与内边距（GUI 像素）。 */
    public static final int PADDING_LEFT = 7;
    public static final int ICON_SIZE = 9;
    public static final int ICON_GAP = 5;

    /** 每 tick 向目标靠拢的比例：0.25 ⇒ 约 4 tick ≈ 200ms 完成一次过渡。 */
    private static final float HOVER_STEP = 0.25F;

    private final Runnable onPress;
    private float hover;

    public NpcDialogueOptionButton(int x, int y, int width, int height,
                                   Component label, Runnable onPress) {
        super(x, y, width, height, label);
        this.onPress = onPress;
    }

    /** 由屏幕每 tick 调用，把悬停进度向当前目标靠拢。 */
    public void tickHover() {
        this.hover = Mth.approach(this.hover, isHovered() ? 1.0F : 0.0F, HOVER_STEP);
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackgroundBand(guiGraphics);

        int iconY = getY() + (getHeight() - ICON_SIZE) / 2;
        drawLeaveIcon(guiGraphics, getX() + PADDING_LEFT, iconY);

        guiGraphics.drawString(net.minecraft.client.Minecraft.getInstance().font, getMessage(),
                getX() + PADDING_LEFT + ICON_SIZE + ICON_GAP,
                getY() + (getHeight() - 8) / 2,
                TEXT_COLOR, true);
    }

    /**
     * 底衬：**左侧半圆角、右侧无硬边并向右渐隐**（对齐参考图的行样式）。
     * <p>
     * 逐列 {@code fill}：每列单独算 alpha（右侧淡出）与上下内缩（左端半圆）。
     * 因为原版 {@code fillGradient} 只支持**纵向**渐变，横向渐隐只能这么做；
     * 一列一次 fill，20 行高 × 约 210 列对这种规模的 UI 完全不构成开销。
     * <p>
     * 悬停时只换 RGB（深蓝黑 → 暖金），alpha 轮廓保持不变，
     * 因此"背景变金色"的过渡与渐隐形状互不干扰。
     */
    private void renderBackgroundBand(GuiGraphics guiGraphics) {
        int rgb = FastColor.ARGB32.lerp(this.hover,
                0xFF000000 | NORMAL_RGB, 0xFF000000 | HOVER_RGB) & 0xFFFFFF;
        int radius = getHeight() / 2;

        for (int col = 0; col < getWidth(); col++) {
            int alpha = fadeAlpha(col);
            if (alpha <= 0) {
                continue;
            }
            int inset = 0;
            if (col < radius) {
                double dx = radius - col;
                inset = radius - (int) Math.round(
                        Math.sqrt(Math.max(0.0, (double) radius * radius - dx * dx)));
            }
            guiGraphics.fill(getX() + col, getY() + inset,
                    getX() + col + 1, getY() + getHeight() - inset,
                    (alpha << 24) | rgb);
        }
    }

    /** 横向 alpha 轮廓：前 {@link #FADE_START} 段实心，之后线性淡出到 0。 */
    private int fadeAlpha(int col) {
        float t = (float) col / Math.max(1, getWidth() - 1);
        if (t <= FADE_START) {
            return BASE_ALPHA;
        }
        float remaining = 1.0F - (t - FADE_START) / (1.0F - FADE_START);
        return (int) (BASE_ALPHA * Mth.clamp(remaining, 0.0F, 1.0F));
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        this.onPress.run();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        narrationElementOutput.add(NarratedElementType.TITLE, getMessage());
    }

    /**
     * 左侧的「离开」小图标：一扇门 + 一个向外（右）的箭头，9×9 像素手绘。
     * <p>
     * 参考图里这里是白色三点对话气泡；本功能所有选项都是"离开某段对话"，
     * 用门/箭头语义更直白。不引入贴图资源，将来要换成美术图只改这一个方法。
     */
    private static void drawLeaveIcon(GuiGraphics guiGraphics, int x, int y) {
        // 门框：竖长方形，左侧 5 列
        guiGraphics.fill(x, y, x + 1, y + ICON_SIZE, ICON_COLOR);              // 左边框
        guiGraphics.fill(x + 4, y, x + 5, y + ICON_SIZE, ICON_COLOR);          // 右边框
        guiGraphics.fill(x + 1, y, x + 4, y + 1, ICON_COLOR);                  // 上边框
        guiGraphics.fill(x + 1, y + ICON_SIZE - 1, x + 4, y + ICON_SIZE, ICON_COLOR); // 下边框
        // 向右的箭头：右侧 4 列
        guiGraphics.fill(x + 5, y + 4, x + 9, y + 5, ICON_COLOR);              // 箭杆
        guiGraphics.fill(x + 7, y + 2, x + 8, y + 4, ICON_COLOR);              // 上翼
        guiGraphics.fill(x + 7, y + 5, x + 8, y + 7, ICON_COLOR);              // 下翼
    }
}
