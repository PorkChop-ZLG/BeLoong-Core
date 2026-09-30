package com.zonlong.beloong.cg.instances;

import com.finderfeed.fdlib.systems.cutscenes.CameraPos;
import com.finderfeed.fdlib.systems.cutscenes.CutsceneData;
import com.finderfeed.fdlib.systems.cutscenes.CurveType;
import com.finderfeed.fdlib.systems.cutscenes.EasingType;
import com.finderfeed.fdlib.util.rendering.FDEasings;
import com.zonlong.beloong.cg.CgAnimation;
import com.zonlong.beloong.cg.CgContext;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 第一条 CG：**末的登场**（{@code mo_entrance}）。
 *
 * <h2>演出（七段，总长 120 tick = 6.0 秒）</h2>
 * <pre>
 *   段  秒区间        tick       相机距离   仰角（游戏内 xRot）      缓动
 *   ①   0 → 0.25      0 →  5     8 格       −62.25 → −24.75        ease-out
 *   ②   0.25 → 0.8    5 → 16     8 格       保持 −24.75             —
 *   ③   0.8 → 1.2    16 → 24     8 格       −24.75 → −46.00        ease-in-out
 *   ④   1.2 → 1.6    24 → 32     8 格       保持 −46.00             —
 *   ⑤   1.6 → 1.75   32 → 35     8 格       −46.00 → 0（平视）      linear
 *   ⑥   1.75 → 2.2   35 → 44     8 → 3 格   保持 0                 ease-in-out
 *   ⑦   2.2 → 6.0    44 → 120    3 格       保持 0                  —
 * </pre>
 * <b>⚠️ 缓动是设计者选定的，不在用户口述规格里</b>：用户只指定了**断点与角度**
 * （仅第⑥段说了"平滑移动"）。三条过渡的具体缓动 —— ① {@code easeOut}、③ {@code easeInOut}、
 * ⑤ {@code linear} —— 是设计者按观感选的，**也不被 {@code cg_invariants.py} 守着**；
 * 想改直接改 {@link #elevationAt} / {@link #distanceAt} 里对应的 {@code FDEasings} 调用即可。
 * （第⑤段只有 3 tick，用 linear 是因为任何缓动在那段都看不出来。）
 *
 * 第⑥段的"相机前移"是**纯粹的径向推近**：高度恒为"末的脚底 + 1.62 格"，只沿末的朝向前进。
 * 由于机位始终落在"锚点沿 forward 的前方"这条直线上，水平朝向恒为 {@code −forward}，
 * <b>推近不会带来任何偏航</b>。
 *
 * <h2>⚠️ 这套仰角是**用户直接给的**，不再是"对准末的身体"</h2>
 * 上一版的做法是"从资产算出末的体高，再让相机对准它"。本版由用户手工给角度（2026-09-30），因此：
 * <ul>
 *   <li>仰角与资产的几何<b>完全解耦</b> ⇒ {@code cg_invariants.py} 里"从资产重算仰角"的那条断言已删除；</li>
 *   <li><b>刻意接受了一个后果</b>：第⑤段转到平视的那一刻，末的身体还在约 15 格高空
 *       （从 8 格外平视看，它在视线<b>上方约 60°</b>；第⑥段推到 3 格后约 73°），
 *       而默认 FOV 的半角约 35° ⇒ <b>末会完全出画约 0.8 秒</b>，
 *       直到约 2.5 秒它俯冲下来才重新入画。
 *       用户 2026-09-30 明确裁定"是我要的效果"（镜头先定格在空场，再看它砸进画面）。</li>
 * </ul>
 *
 * <h2>时间基</h2>
 * 秒 → tick 一律 {@code × 20}：{@code 0.25 / 0.8 / 1.2 / 1.6 / 2.2} 秒恰好是
 * {@code 5 / 16 / 24 / 32 / 44} tick。
 * ⚠️ 只有 <b>1.72 秒</b>（= 34.4 tick）不是整数：用户在"34（2 tick）"与"35（3 tick）"里
 * 选了 <b>35</b>（= 1.75 秒），于是第⑤段是 3 tick。
 *
 * <h2>为什么整条轨迹可以在触发那一刻烘死成世界坐标</h2>
 * {@code descend} 的 {@code Root} 骨骼位移全程恒定为 {@code (0, 0.332, 0)} ⇒ <b>实体本身一动不动</b>
 * ⇒ 相机轨迹只依赖末的位置与朝向，而这两者在整段 CG 里都不变。
 * 这同时绕开了 fdlib 一个结构性缺口：它的朝向逻辑被写死成"关键点之间插值"
 * （{@code CutsceneExecutor} 构造器硬编码 {@code new NormalLookProcessor()}，private 且无 setter），
 * <b>无法自动看向移动目标</b> —— 而本 CG 不需要。
 *
 * <h2>调参入口（实机标定用）</h2>
 * 全部数值都是本类的具名常量。{@code docs/plans/2026-09-30-cg-system-plan.md} 的 T10
 * 有一张"症状 → 调哪个常量 → 往哪个方向调"的对照表。
 * <b>唯一不能单独改的是 {@link #DURATION_TICKS}</b> —— 它必须与 {@code descend} 的
 * {@code animation_length × 20} 相等，由 {@code cg_invariants.py} 守着。
 *
 * <p>设计文档：{@code docs/plans/2026-09-30-cg-system-design.md}（§3 编排）。
 */
public final class MoEntrance extends CgAnimation {

    /** 指令里使用的名字：{@code /beloong cg <target> play mo_entrance}。 */
    public static final String NAME = "mo_entrance";

    /**
     * 要在末身上播的动画名。
     * <p>
     * 它在 {@code src/main/resources/assets/beloong/animations/mo.extra.animation.json} 里，
     * 由 {@code MoModel} 的 fallback 链加载，经表情系统（{@code NpcEntity} 的 {@code emote} 控制器）播放。
     * 名字查不到时表情系统会**优雅降级**（打一条英文 WARN、保持状态动画），CG 的相机照常播放。
     */
    private static final String ANIMATION_NAME = "descend";

    /**
     * 过场总时长（tick）。<b>必须等于 {@code descend} 的 {@code animation_length × 20}</b>：
     * {@code animation_length = 6} 秒 ⇒ 120 tick。
     * <p>
     * 它同时是"末动画播完"与"镜头归还"的时刻 —— GeckoLib 的表情控制器按
     * {@code animation.length()} 判定播完，fdlib 按 {@code currentTime >= cutsceneTime} 判定结束，
     * 两者由这一个常量对齐。⚠️ <b>不要为了改节奏而改它</b>（要改只能改动画资产）。
     */
    private static final int DURATION_TICKS = 120;

    // ===================== 时间轴断点（tick）=====================

    /** ① 结束 / ② 开始：0.25 秒 —— 从起始仰角甩到低位，此后保持。 */
    private static final int T_DROP_END = 5;

    /** ② 结束 / ③ 开始：0.8 秒 —— 低位保持结束，开始爬升。 */
    private static final int T_RISE_START = 16;

    /** ③ 结束 / ④ 开始：1.2 秒 —— 爬升到位，此后保持。 */
    private static final int T_RISE_END = 24;

    /** ④ 结束 / ⑤ 开始：1.6 秒 —— 高位保持结束，开始快速下拉。 */
    private static final int T_FALL_START = 32;

    /**
     * ⑤ 结束 / ⑥ 开始：<b>1.75 秒</b>（用户裁定）。
     * <p>
     * 用户原话是"1.6 秒到 1.72 秒"，而 {@code 1.72 × 20 = 34.4} 不是整数。
     * 用户在两个候选里选了 <b>35</b>（1.75 秒 ⇒ 本段 3 tick），而不是 34（⇒ 2 tick）。
     */
    private static final int T_FALL_END = 35;

    /** ⑥ 结束 / ⑦ 开始：2.2 秒 —— 相机推近到 3 格，此后**全程静止**。 */
    private static final int T_PUSH_END = 44;

    // ===================== 仰角（度；正 = 上看 = 游戏内 xRot 取负）=====================

    /** 起始仰角：游戏内 {@code xRot = −62.25}。 */
    private static final double PITCH_START_DEG = 62.25D;

    /** 低位仰角：游戏内 {@code xRot = −24.75}。 */
    private static final double PITCH_LOW_DEG = 24.75D;

    /** 高位仰角：游戏内 {@code xRot = −46.00}。 */
    private static final double PITCH_HIGH_DEG = 46.0D;

    /** 平视。 */
    private static final double PITCH_LEVEL_DEG = 0.0D;

    // ===================== 机位 =====================

    /** 前段机位到末的**水平**距离（格）：用户给定"距离末 8 格"。 */
    private static final double VIEW_DISTANCE_FAR = 8.0D;

    /** 推近后的机位距离（格）：用户给定"来到距离末 3 格"。 */
    private static final double VIEW_DISTANCE_NEAR = 3.0D;

    /**
     * 相机高度（相对末的脚底，格）。{@code 1.62} = 原版玩家站立眼高
     * （{@code Player.DEFAULT_EYE_HEIGHT}）；整段不变（用户要求推近后"依旧平视"）。
     */
    private static final double VIEW_EYE_HEIGHT = 1.62D;

    /**
     * 关键点采样间隔（tick）。本版取 <b>1</b>（121 个关键点）。
     * <p>
     * 上一版取 2 就够了（机位不动、只有仰角在缓动）。本版有两条快速运动：
     * 第⑤段 3 tick 内转 46°、第⑥段 9 tick 内推 5 格 ⇒ 2 tick 的采样会把它们切成可见的折线。
     * 1 tick 采样的代价只是包大一点（约 10 KB，每条 CG 只发一次）。
     */
    private static final int SAMPLE_STEP = 1;

    // ===================== 观察者隐身 =====================

    /** 隐身时长（tick）：5 秒。到点自动结束，我们不做任何移除。 */
    private static final int INVISIBILITY_TICKS = 100;

    /** 隐身等级：{@code amplifier = 1} 即游戏内显示的"隐身 II"。 */
    private static final int INVISIBILITY_AMPLIFIER = 1;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String animationName() {
        return ANIMATION_NAME;
    }

    /**
     * 观察者隐身 5 秒（无粒子、不显示图标）。
     * <p>
     * 理由与代价见 {@link CgAnimation#viewerInvisibilityTicks()} 的注释：fdlib 不隐藏玩家自己的身体，
     * 而 NeoForge 的 {@code LevelRenderer} 补丁会在"相机不是玩家"时把本地玩家渲染出来。
     */
    @Override
    protected int viewerInvisibilityTicks() {
        return INVISIBILITY_TICKS;
    }

    @Override
    protected int viewerInvisibilityAmplifier() {
        return INVISIBILITY_AMPLIFIER;
    }

    @Override
    protected CutsceneData build(CgContext ctx) {

        // ⚠️ 三个 easing/curve 都必须是 LINEAR，见 CgContext.track 的 javadoc：
        //    fdlib 的关键点只能等距（t = i/(n-1) × 总时长），任何非线性映射都会让
        //    采样点与播放点错位；本 CG 的缓动已经全部烘进 distanceAt / elevationAt 的采样值里。
        CutsceneData data = CutsceneData.create()
                .time(DURATION_TICKS)
                .stopMode(CutsceneData.StopMode.AUTOMATIC)
                .moveCurveType(CurveType.LINEAR)
                .timeEasing(EasingType.LINEAR)
                .lookEasing(EasingType.LINEAR);

        List<CameraPos> track = ctx.track(
                DURATION_TICKS,
                SAMPLE_STEP,
                tick -> ctx.ahead(distanceAt(tick)).add(0.0D, VIEW_EYE_HEIGHT, 0.0D),
                MoEntrance::elevationAt);
        for (CameraPos pos : track) {
            data.addCameraPos(pos);
        }
        return data;
    }

    /**
     * 写死的**机位距离**曲线（格，水平）。
     * <p>
     * 第①～⑤段恒为 {@link #VIEW_DISTANCE_FAR}；第⑥段（{@link #T_FALL_END} → {@link #T_PUSH_END}）
     * 用 {@link FDEasings#easeInOut} 平滑推到 {@link #VIEW_DISTANCE_NEAR}（用户要求"平滑移动"）；
     * 之后恒为近距。
     */
    private static double distanceAt(double tick) {
        if (tick <= T_FALL_END) {
            return VIEW_DISTANCE_FAR;
        }
        if (tick >= T_PUSH_END) {
            return VIEW_DISTANCE_NEAR;
        }
        double p = (tick - T_FALL_END) / (double) (T_PUSH_END - T_FALL_END);
        return lerp(VIEW_DISTANCE_FAR, VIEW_DISTANCE_NEAR, FDEasings.easeInOut((float) p));
    }

    /**
     * 写死的**仰角**曲线（度，正 = 上看）。参数是过场内的 tick，**可能带小数**
     * —— 采样时刻由 {@link CgContext#track} 按 fdlib 的公式反算，见那里的说明。
     * <p>
     * 七段：甩低（ease-out）→ 保持 → 爬升（ease-in-out）→ 保持 → 快速下拉（linear）→ 平视保持。
     * <p>
     * 缓动源用 fdlib 的 {@link FDEasings}（{@code easeOut} / {@code easeInOut}），
     * 与 FDBosses 自己的过场同一族函数，整包观感一致。
     * 第⑤段只有 3 tick，任何缓动都看不出来，故直接用线性（最省、最少意外）。
     */
    private static double elevationAt(double tick) {
        if (tick <= T_DROP_END) {
            double p = tick / (double) T_DROP_END;
            return lerp(PITCH_START_DEG, PITCH_LOW_DEG, FDEasings.easeOut((float) p));
        }
        if (tick <= T_RISE_START) {
            return PITCH_LOW_DEG;
        }
        if (tick <= T_RISE_END) {
            double p = (tick - T_RISE_START) / (double) (T_RISE_END - T_RISE_START);
            return lerp(PITCH_LOW_DEG, PITCH_HIGH_DEG, FDEasings.easeInOut((float) p));
        }
        if (tick <= T_FALL_START) {
            return PITCH_HIGH_DEG;
        }
        if (tick <= T_FALL_END) {
            double p = (tick - T_FALL_START) / (double) (T_FALL_END - T_FALL_START);
            return lerp(PITCH_HIGH_DEG, PITCH_LEVEL_DEG, p);
        }
        return PITCH_LEVEL_DEG;
    }

    private static double lerp(double from, double to, double p) {
        return from + (to - from) * p;
    }
}
