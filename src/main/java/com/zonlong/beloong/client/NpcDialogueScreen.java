package com.zonlong.beloong.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.dialogue.NpcDialogueEntry;
import com.zonlong.beloong.dialogue.NpcDialogueOpenPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * 原神式 NPC 对话屏。
 * <p>
 * 交互与渲染全部本地完成、不暂停世界、也**不回发任何包**：内容由服务端在玩家右键命中时
 * 经 {@link NpcDialogueOpenPayload} 一次性送达（入口见 {@link #open}），此后打字机 / 翻页 /
 * 选项都是纯本地状态机。世界保持清晰可见，只有屏幕下方一段渐变压暗 ——
 * 因此{@link #renderBackground} 必须留空（见该方法说明）。
 * <p>
 * <b>交互状态机</b>（详见 {@code docs/plans/2026-09-20-npc-dialogue-design.md} §3.4）：
 * <pre>
 * TYPING(第 i 页, 已显示 n 字) --每 tick n += charsPerTick--&gt; 显示完 ⇒ WAIT_CLICK
 *        点击 ⇒ 本页立刻全显示 ⇒ WAIT_CLICK
 * WAIT_CLICK --点击且非末页--&gt; 第 i+1 页(TYPING, n=0)
 *            --点击且末页---&gt; SHOWING_OPTIONS（箭头隐藏、右侧弹出选项）
 * SHOWING_OPTIONS --点选项--&gt; 执行动作（v1 只有"离开" ⇒ 关闭）
 * 任意状态 --ESC--&gt; 关闭
 * </pre>
 * <p>
 * <b>全部布局常量集中在类顶部</b>：所有数值都是从参考图放大裁切量得的起始值，
 * 必然需要实机试玩微调 —— 调参只改这一处。
 */
@OnlyIn(Dist.CLIENT)
public class NpcDialogueScreen extends Screen {

    // ===================== 布局常量（试玩调参只改这里） =====================

    /** 说话人名字中轴（占屏高）。 */
    private static final float NAME_Y = 0.790F;
    /** 装饰线纵坐标（名字与正文之间）。 */
    private static final float RULE_Y = 0.822F;
    /**
     * 正文**首行顶部**（占屏高）。
     * <p>
     * 刻意锚定"顶部"而不是"区块中轴"：多行正文若按中轴对齐会**向上生长**，
     * 第一行直接压到装饰线上（实机第一版就是这样，见截图）。
     * 锚定顶部后，无论几行都只往下长。
     */
    private static final float TEXT_TOP = 0.860F;
    /** 继续箭头纵坐标。 */
    private static final float ARROW_Y = 0.964F;
    /** 选项列左缘（占屏宽，左对齐）。 */
    private static final float OPTION_LEFT = 0.661F;
    /** 选项**固定长度**（占屏宽）—— 参考图里的选项底衬是等长的，不随文字伸缩。 */
    private static final float OPTION_WIDTH = 0.22F;
    /** 最下一颗选项的底边（占屏高），向上依次排。 */
    private static final float OPTION_BOTTOM = 0.787F;
    /** 装饰线半宽（占屏宽）：全长 0.33 屏宽。 */
    private static final float RULE_HALF_WIDTH = 0.165F;

    /** 选项按钮高度与间距（GUI 像素）。 */
    private static final int OPTION_HEIGHT = 20;
    private static final int OPTION_GAP = 8;

    /** 正文最大行宽（占屏宽）。 */
    private static final float TEXT_MAX_WIDTH = 0.80F;
    /** 行距：原版字高 + 2 像素。 */
    private static final int LINE_GAP = 2;

    /** 底部渐变：从 62% 屏高起，透明 → 深黑（alpha 0xE6）。 */
    private static final float GRADIENT_START = 0.62F;
    private static final int GRADIENT_TOP = 0x00000000;
    private static final int GRADIENT_BOTTOM = 0xE6000000;

    /** 金色（装饰线与箭头）。起始值取自参考图，待试玩调整。 */
    private static final int GOLD = 0xFFE8B84B;
    private static final int GOLD_BRIGHT = 0xFFFFD873;
    /** 名字与正文的基色：白色。名字的金色由语言文件里的 § 代码决定，代码不写死。 */
    private static final int WHITE = 0xFFFFFFFF;
    /** 继续箭头：菱形外框半径 / 内部三角尺寸。 */
    private static final int ARROW_RADIUS = 7;
    private static final int ARROW_TRI_WIDTH = 7;
    private static final int ARROW_TRI_HEIGHT = 4;

    /** 原版格式代码前缀（{@code §}）。 */
    private static final char SECTION_SIGN = '\u00a7';
    /** 「离开」选项的翻译键（v1 只有这一个选项，硬编码而非放进 JSON）。 */
    private static final String LEAVE_KEY = "beloong.dialogue.option.leave";

    // ===================== 状态 =====================

    private enum State { TYPING, WAIT_CLICK, SHOWING_OPTIONS }

    private final Component speakerName;
    private final List<NpcDialogueEntry.Page> pages;

    private State state = State.TYPING;
    private int pageIndex;
    /** 当前页已显示的**可见字符数**（{@code §} 代码对不计入、也不被切断）。 */
    private int revealed;
    /** 当前页预排版后的各行原始文本（含 {@code §} 代码）。 */
    private List<String> lines = List.of();
    /** 当前页可见字符总数。 */
    private int visibleTotal;
    private int tickCount;

    public NpcDialogueScreen(Component speakerName, List<NpcDialogueEntry.Page> pages) {
        super(Component.empty());
        this.speakerName = speakerName;
        this.pages = pages;
    }

    /**
     * 「打开对话」的唯一入口 —— 由 {@code NpcDialogueOpenPayload} 的客户端处理器调用，
     * 把服务端发来的**最小事实**变成一个屏幕。
     * <p>
     * <b>名字回退链</b>：数据文件指定的翻译键 → 实体自身的显示名（实体在客户端存在时，
     * 含命名牌给的自定义名）→ 实体类型名（{@code EntityType#getDescriptionId()}，
     * 服务端随包发来的兜底键）。
     * <p>
     * 首版只有前两级，且隐含假设"实体一定在客户端存在"。现在内容由服务端下发，
     * 实体未必已同步到客户端，故补第三级 —— 不崩、不退屏，且实体在场时行为与首版一致
     * （命名牌自定义名仍然生效，首版 R6 语义未丢）。
     */
    @OnlyIn(Dist.CLIENT)
    public static void open(NpcDialogueOpenPayload payload) {
        // 防御：空页会让 init() → loadPage() 里的 pages.get(0) 越界。
        // 加载器已经拒绝 pages 为空的文件，但本方法是设计上的"永不抛"边界，这一行成本为零。
        if (payload.pages().isEmpty()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Entity speaker = minecraft.level == null ? null : minecraft.level.getEntity(payload.entityId());

        // 显式写出 <Component>：translatable 返回 MutableComponent，与 getDisplayName /
        // translatable 的 Component 不同，不写见证会让 map/orElseGet 推断失败。
        Component name = payload.nameKey()
                .<Component>map(Component::translatable)
                .orElseGet(() -> speaker != null
                        ? speaker.getDisplayName()
                        : Component.translatable(payload.fallbackNameKey()));

        minecraft.setScreen(new NpcDialogueScreen(name, payload.pages()));
    }

    // ===================== 生命周期 =====================

    @Override
    protected void init() {
        if (this.state == State.SHOWING_OPTIONS) {
            // 窗口尺寸变化会清空子控件：这时必须重建选项，否则会卡在"选项态但没有按钮"。
            showOptions();
        } else {
            loadPage();
        }
    }

    /** 载入当前页：取翻译 → 按 {@code \n} 分段 → 按宽度预排版 → 归零揭示进度。 */
    private void loadPage() {
        String raw = Component.translatable(this.pages.get(this.pageIndex).text()).getString();
        this.lines = wrap(raw, (int) (this.width * TEXT_MAX_WIDTH));
        this.visibleTotal = this.lines.stream().mapToInt(NpcDialogueScreen::countVisible).sum();
        this.revealed = 0;
        this.state = State.TYPING;
    }

    @Override
    public void tick() {
        this.tickCount++;

        if (this.state == State.TYPING) {
            this.revealed += Config.NpcDialogue.charsPerTick.get();
            if (this.revealed >= this.visibleTotal) {
                this.revealed = this.visibleTotal;
                advanceOrFinish();
            }
        } else if (this.state == State.SHOWING_OPTIONS) {
            for (var child : this.children()) {
                if (child instanceof NpcDialogueOptionButton button) {
                    button.tickHover();
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        switch (this.state) {
            case TYPING -> {
                // 打字中点击 ⇒ 本页立刻显示完，随后与"自然播放完"走同一条分支
                this.revealed = this.visibleTotal;
                advanceOrFinish();
                return true;
            }
            case WAIT_CLICK -> {
                // 只有中间页会进入这里（最后一页在 advanceOrFinish 里直接弹选项）
                this.pageIndex++;
                loadPage();
                return true;
            }
            case SHOWING_OPTIONS -> {
                // 选项出现后必须做出选择；点空白处不关闭（避免误触打断选择）
                return super.mouseClicked(mouseX, mouseY, button);
            }
        }
        return true;
    }

    /**
     * 当前页显示完之后的分支：<b>中间页</b>等待点击继续（显示"继续箭头"），
     * <b>最后一页直接弹出选项</b>。
     * <p>
     * 最后一页不要求玩家多点一下 —— 实机反馈：文字播完还要再点一次才出选项是多余的。
     * 因此底部的继续箭头只会出现在中间页。
     */
    private void advanceOrFinish() {
        if (this.pageIndex + 1 < this.pages.size()) {
            this.state = State.WAIT_CLICK;
        } else {
            showOptions();
        }
    }

    /** 弹出选项：v1 只有一个「离开」。**固定长度、左缘对齐**（与参考图一致）。 */
    private void showOptions() {
        this.state = State.SHOWING_OPTIONS;
        Component label = Component.translatable(LEAVE_KEY);
        int width = (int) (this.width * OPTION_WIDTH);
        int x = (int) (this.width * OPTION_LEFT);
        int bottom = (int) (this.height * OPTION_BOTTOM);
        addRenderableWidget(new NpcDialogueOptionButton(
                x, bottom - OPTION_HEIGHT, width, OPTION_HEIGHT, label, this::onClose));
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(null);
        }
    }

    /**
     * 故意留空。
     * <p>
     * 原版 {@code Screen#renderBackground} 会调用 {@code renderBlurredBackground}
     * （把整个世界糊掉）与 {@code renderMenuBackground}（再盖一层暗底贴图）。
     * 本功能要的是"世界清晰可见、只有底部一段渐变压暗"，两者直接冲突。
     * 见设计文档 §8.2 / 决策 D13 —— 这里漏了覆写，症状会是"整个画面糊掉"，
     * 而调渐变参数怎么调都不对。
     */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // no-op
    }

    /** 用户裁定：不暂停，保持沉浸感（设计 D14）。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ===================== 渲染 =====================

    /**
     * 绘制顺序：底部渐变 → 名字 → 装饰线 → 正文 → 箭头 → **子控件（选项）最后**。
     * <p>
     * 选项必须最后画：它与底部渐变在纵向上有重叠（最下一颗按钮底边 0.787 屏高，
     * 渐变从 0.72 屏高开始），若先画按钮就会被渐变压暗。
     */
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBottomGradient(guiGraphics);
        renderSpeakerName(guiGraphics);
        renderRule(guiGraphics);
        renderBody(guiGraphics);
        renderArrow(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void renderBottomGradient(GuiGraphics guiGraphics) {
        int top = (int) (this.height * GRADIENT_START);
        guiGraphics.fillGradient(0, top, this.width, this.height, GRADIENT_TOP, GRADIENT_BOTTOM);
    }

    /**
     * 说话人名字：居中、以屏幕中轴为缩放中心。
     * <p>
     * 颜色**不写死**：语言文件里的 {@code §} 代码决定实际颜色（示例给 {@code §6} 金色），
     * 这里传白色基色只是兜底 —— 原版渲染链会在遇到 {@code §} 时覆盖它。
     */
    private void renderSpeakerName(GuiGraphics guiGraphics) {
        float scale = Config.NpcDialogue.nameScale.get().floatValue();
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(this.width / 2.0F,
                (float) (this.height * NAME_Y) - this.font.lineHeight * scale / 2.0F, 0.0F);
        pose.scale(scale, scale, 1.0F);
        guiGraphics.drawCenteredString(this.font, this.speakerName, 0, 0, WHITE);
        pose.popPose();
    }

    /** 装饰线：名字与正文之间的一条横贯金线，两端各一个小菱形端饰。 */
    private void renderRule(GuiGraphics guiGraphics) {
        int y = (int) (this.height * RULE_Y);
        int centerX = this.width / 2;
        int half = (int) (this.width * RULE_HALF_WIDTH);
        guiGraphics.fill(centerX - half, y, centerX + half, y + 1, GOLD);
        fillDiamond(guiGraphics, centerX - half, y, 3, GOLD);
        fillDiamond(guiGraphics, centerX + half, y, 3, GOLD);
    }

    /**
     * 正文：居中，逐字揭示。
     * <p>
     * 揭示按**可见字符**推进，因此必须用预排版好的 {@link #lines}（按行取前缀），
     * 而不是每帧重新按宽度切 —— 后者会让换行位置在打字过程中跳动。
     */
    private void renderBody(GuiGraphics guiGraphics) {
        int lineHeight = this.font.lineHeight + LINE_GAP;
        int y = (int) (this.height * TEXT_TOP);
        int remaining = this.revealed;

        for (String line : this.lines) {
            if (remaining <= 0) {
                break;
            }
            int visible = countVisible(line);
            String shown = remaining >= visible ? line : prefixByVisibleChars(line, remaining);
            guiGraphics.drawCenteredString(this.font, Component.literal(shown), this.width / 2, y, WHITE);
            y += lineHeight;
            remaining -= visible;
        }
    }

    /** 继续箭头：菱形外框 + 内部向下的亮金三角，轻微上下浮动。仅"等待点击"时显示。 */
    private void renderArrow(GuiGraphics guiGraphics) {
        if (this.state != State.WAIT_CLICK) {
            return;
        }
        int centerX = this.width / 2;
        int centerY = (int) (this.height * ARROW_Y)
                + (int) Math.round(Math.sin(this.tickCount * 0.15) * 2.0);
        fillDiamondOutline(guiGraphics, centerX, centerY, ARROW_RADIUS, 1, GOLD);
        fillTriangleDown(guiGraphics, centerX, centerY - 2, ARROW_TRI_WIDTH, ARROW_TRI_HEIGHT, GOLD_BRIGHT);
    }

    // ===================== 文本工具 =====================

    /**
     * 按可用宽度贪心换行，返回**原始字符串**（保留 {@code §} 代码）。
     * <p>
     * 先按 {@code \n} 分段，再逐"原子单元"累加测宽：{@code §} 与其后一个字符作为一个整体，
     * 既不计入宽度也不允许被拆开。
     */
    private List<String> wrap(String raw, int maxWidth) {
        List<String> out = new ArrayList<>();
        for (String paragraph : raw.split("\n", -1)) {
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < paragraph.length(); ) {
                String unit;
                if (paragraph.charAt(i) == SECTION_SIGN && i + 1 < paragraph.length()) {
                    unit = paragraph.substring(i, i + 2);
                    i += 2;
                } else {
                    unit = String.valueOf(paragraph.charAt(i));
                    i++;
                }
                boolean isCode = unit.charAt(0) == SECTION_SIGN;
                if (!isCode && current.length() > 0
                        && this.font.width(Component.literal(current + unit)) > maxWidth) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                current.append(unit);
            }
            out.add(current.toString());
        }
        return out;
    }

    /** 统计可见字符数（{@code §} 代码对不计入）。 */
    private static int countVisible(String raw) {
        int visible = 0;
        for (int i = 0; i < raw.length(); ) {
            if (raw.charAt(i) == SECTION_SIGN && i + 1 < raw.length()) {
                i += 2;
            } else {
                i++;
                visible++;
            }
        }
        return visible;
    }

    /**
     * 取前 {@code n} 个**可见字符**。
     * <p>
     * {@code §} 代码对整对复制、不计入 n，因此切点永远不会把代码切开
     * （否则会短暂渲染出裸的 {@code §c} 或串色）。
     */
    private static String prefixByVisibleChars(String raw, int n) {
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < raw.length() && shown < n; ) {
            char c = raw.charAt(i);
            if (c == SECTION_SIGN && i + 1 < raw.length()) {
                out.append(c).append(raw.charAt(i + 1));
                i += 2;
            } else {
                out.append(c);
                i++;
                shown++;
            }
        }
        return out.toString();
    }

    // ===================== 图形工具（逐行 fill 手绘，不引入贴图） =====================

    /** 实心菱形。 */
    private static void fillDiamond(GuiGraphics guiGraphics, int centerX, int centerY, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int half = radius - Math.abs(dy);
            guiGraphics.fill(centerX - half, centerY + dy, centerX + half + 1, centerY + dy + 1, color);
        }
    }

    /** 空心菱形（只画左右两侧的边）。 */
    private static void fillDiamondOutline(GuiGraphics guiGraphics, int centerX, int centerY,
                                           int radius, int thickness, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int half = radius - Math.abs(dy);
            if (half <= thickness) {
                guiGraphics.fill(centerX - half, centerY + dy, centerX + half + 1, centerY + dy + 1, color);
            } else {
                guiGraphics.fill(centerX - half, centerY + dy, centerX - half + thickness, centerY + dy + 1, color);
                guiGraphics.fill(centerX + half - thickness + 1, centerY + dy, centerX + half + 1, centerY + dy + 1, color);
            }
        }
    }

    /** 向下的实心三角。 */
    private static void fillTriangleDown(GuiGraphics guiGraphics, int centerX, int top,
                                         int width, int height, int color) {
        for (int row = 0; row < height; row++) {
            int half = (width / 2) * (height - row) / height;
            guiGraphics.fill(centerX - half, top + row, centerX + half + 1, top + row + 1, color);
        }
    }
}
