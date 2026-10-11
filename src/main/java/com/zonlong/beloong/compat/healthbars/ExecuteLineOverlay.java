package com.zonlong.beloong.compat.healthbars;

import com.zonlong.beloong.Config;
import com.zonlong.beloong.registry.ExecuteThresholdEffect;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

/**
 * 在 Health Bars 的**左上角血条**上画「斩杀线」覆盖层：橙色半透明填充 {@code 0→斩杀线} 区段，
 * 右端压一条竖直的线标记真正的斩杀线。
 *
 * <h3>⚠️ 这个类是客户端专属的，只能被 {@code mixin/healthbars/GuiRenderingHandlerMixin} 引用</h3>
 * 它引用了 {@link GuiGraphics} 等客户端类型。{@code Config} 的静态块、注册表类、双端事件处理器
 * <b>都不得</b>引用它——那些类在**专用服务端**也会被加载，一旦引用就会
 * {@code NoClassDefFoundError} 崩服。{@code tools/validate_execute_line.py} 的第 ③ 条断言
 * 就是守这条：全仓只允许一个文件引用它。
 *
 * <h3>斩杀线从哪来（⚠️ 2026-10-11 更正：不是「免费读 effect」）</h3>
 * 斩杀线百分比编码在 effect 的 {@code amplifier} 里，但 {@code MobEffectInstance}
 * <b>不会被原版同步给旁观者</b> —— 原版只把效果的粒子外观放进实体元数据
 * （{@code LivingEntity.DATA_EFFECT_PARTICLES}），{@code ClientboundUpdateMobEffectPacket}
 * 只发给效果持有者本人 / 乘客 / 登录与骑乘时。所以对一只怪调
 * {@code entity.getEffect(...)} 永远是 {@code null}（这正是 2026-10-11 实机全无渲染的原因）。
 * <p>
 * 现在由服务端在标记变化时补发 {@link ExecuteMarkSyncPayload}，客户端存进
 * {@link ExecuteMarkCache}，本类按实体 id 读。详见
 * {@link com.zonlong.beloong.registry.ExecuteMarkSyncHandler}。
 *
 * <h3>几何从哪来（⚠️ 左边界是 {@code posX - 1}，不是 {@code posX}）</h3>
 * 上游 GUI 血条 = {@code barWidth × 5} px。{@code GuiRenderingHandler:180} 传下去的是条的
 * <b>中心 X</b>：{@code posX - 1 + barWidth / 2}；{@code HealthBarRenderHelper:53} 内部再
 * {@code posX -= (width / 2)} ⇒ 左边界 = {@code (posX - 1 + W/2) - W/2} = <b>{@code posX - 1}</b>。
 * 两次整除精确相消（任何 W、奇偶都成立），<b>但那个 {@code -1} 不会消失</b>，
 * 所以 {@link #draw} 收到的 {@code anchorX} 必须先减 1。
 */
public final class ExecuteLineOverlay {

    /**
     * 填充色（ARGB）。色相由用户给的参考图**反向标定**得到：同一条线分别落在「天空」与「血条」
     * 两种底色上成像，解 {@code fg = α·S + (1−α)·bg} ⇒ 反解出的原色 ≈ {@code RGB(255,128,36)}；
     * 本常量把它取整为 {@code 0xFF8028} = {@code RGB(255,128,40)}（只差蓝通道 4，不可见）。
     * <p>
     * α = {@code 0x66}（40%）。<b>这是最需要实测微调的一个常量</b>：调大 → 血条越被盖住；
     * 调小 → 越看不出区段。用户 2026-10-11 裁定不把它暴露到配置里（配置只有总开关）。
     */
    private static final int FILL_ARGB = 0x66FF8028;

    /**
     * 竖线色（ARGB）。参考图里竖线实测 {@code α ≈ 1.0}（不透明）——它是「真正的斩杀线」的唯一
     * 权威标记，必须锐利。改 α 会让它变糊。
     */
    private static final int LINE_ARGB = 0xFFFF8028;

    /** 血条高度。来自上游 {@code HealthBarRenderHelper.java:54} 的 {@code blitSprite(..., 5)}。 */
    private static final int BAR_HEIGHT = 5;

    /** 竖线宽度（px）。参考图竖线 6px ÷ 缩放(≈2.4) ≈ 2.5 ⇒ 取 2 更克制。 */
    private static final int LINE_WIDTH = 2;

    /** 竖线在血条上下各出头的像素数。参考图上下各出头 ≈4.5px ÷ 2.4 ≈ 1.9 ⇒ 取 2。 */
    private static final int LINE_OVERHANG = 2;

    private ExecuteLineOverlay() {}

    /**
     * 斩杀线落在血条上的 X 像素。
     *
     * @param barLeft          血条<b>左</b>边界（= 上游 {@code posX - 1}，见 {@link #draw} 的 {@code anchorX}）
     * @param barWidth         血条宽度（上游为 {@code 45 × scale + 2}，scale ∈ 1..4 ⇒ 47/92/137/182）
     * @param thresholdPercent 斩杀线比例（0.05 .. 0.20），来自 {@link ExecuteThresholdEffect#thresholdFraction(int)}
     * @return 夹在血条范围内的 X
     */
    static int lineX(final int barLeft, final int barWidth, final float thresholdPercent) {
        float clamped = Mth.clamp(thresholdPercent, 0.0F, 1.0F);
        int offset = Math.round(barWidth * clamped);
        return Mth.clamp(barLeft + offset, barLeft, barLeft + barWidth);
    }

    /**
     * 画覆盖层。实体没有斩杀线标记时什么都不做。
     *
     * @param graphics 上游 GUI 的绘制上下文。半透明能生效是因为 {@code RenderType.gui()} 自带
     *                 {@code TRANSLUCENT_TRANSPARENCY}（flush 时生效），<b>不是</b>靠调用点的
     *                 blend 设置（调用点的 dst 因子是 ZERO/ONE，反而会被它覆盖）
     * @param anchorX  上游 {@code GuiRenderingHandler#renderHealthBar} 收到的 {@code posX}。
     *                 <b>它不是血条左边界，而是「左边界 + 1」</b>——上游把
     *                 {@code posX - 1 + barWidth / 2} 作为<b>中心</b>传给
     *                 {@code HealthBarRenderHelper}，后者内部再 {@code posX -= width / 2}
     *                 ⇒ 左边界 = {@code (posX - 1 + W/2) - W/2 = posX - 1}。
     *                 少减这个 1，整条覆盖层（填充左缘与竖线）都会右偏 1px。
     * @param barTop   血条上边界（= 上游的 {@code posY}）
     * @param barWidth 血条宽度（上游为 {@code 45 × scale + 2}，scale ∈ 1..4 ⇒ 47/92/137/182）
     * @param entity   准星指向的生物（读它身上的斩杀线 effect）
     * @return 真的画了返回 {@code true}；总开关关闭 / 没挂斩杀线 / 条宽非法时返回 {@code false}
     *         （调用方用它打「首次生效」锚点日志——这样能把「注入点静默失效」与
     *         「这次准星指的怪没挂斩杀线」在日志里区分开）
     */
    public static boolean draw(final GuiGraphics graphics, final int anchorX, final int barTop,
                               final int barWidth, final LivingEntity entity) {
        // 总开关在 Config 上，由**本类**（客户端专属）主动去读；反过来让 Config 引用本类会崩服，
        // 见 Config#EXECUTE_LINE_ENABLED 的注释。
        //
        // `isLoaded()` 是防御性前置（2026-10-11 审查 S-1）：`ConfigValue#get()` 在 spec 未加载时会抛
        // `IllegalStateException`，而这里的抛点位于 Health Bars 的**每帧** GUI 路径里 ⇒ 一旦触发就是
        // 帧级异常刷屏。触发条件极罕见（NeoForge 在物理客户端必加载 CLIENT 配置，且会修复损坏文件，
        // 前一次审查已论证其不可达），但代价只有一次比较。
        if (!Config.CLIENT_SPEC.isLoaded() || !Config.EXECUTE_LINE_ENABLED.get()) {
            return false;
        }

        // 斩杀线来自服务端补发的同步包（原版不把生物的 MobEffectInstance 给旁观者），
        // 见 ExecuteMarkSyncPayload / ExecuteMarkCache。NO_MARK ⇒ 这只生物没被挂斩杀线。
        int amplifier = ExecuteMarkCache.get(entity.getId());
        if (amplifier == ExecuteMarkSyncPayload.NO_MARK) {
            return false; // 这只生物没被挂斩杀线 → 一点橙色都不画
        }

        if (barWidth <= 0) {
            // 上游今天的取值恒为 45×scale+2 ≥ 47，走不到这里；留着是为了将来上游改了条宽公式时，
            // 不至于在一个无所依附的位置画出一条孤立的竖线。
            return false;
        }

        final int barLeft = anchorX - 1; // 见 @param anchorX：左边界比上游传下来的 posX 小 1

        int line = lineX(barLeft, barWidth, ExecuteThresholdEffect.thresholdFraction(amplifier));

        // ① 半透明橙填充 0→斩杀线。
        //    区段**始终画满**，与当前血量无关（用户 2026-10-11 裁定）：斩杀线是「最大生命的百分比」，
        //    是一个与当前血量无关的固定位置；它只回答「斩杀线在哪」，不回答「现在还差多少」。
        graphics.fill(barLeft, barTop, line, barTop + BAR_HEIGHT, FILL_ARGB);

        // ② 竖线：上下各出头 LINE_OVERHANG，让它比填充本身更醒目。
        int lineLeft = line - LINE_WIDTH / 2;
        graphics.fill(lineLeft, barTop - LINE_OVERHANG,
                lineLeft + LINE_WIDTH, barTop + BAR_HEIGHT + LINE_OVERHANG, LINE_ARGB);

        return true;
    }
}
