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
 * <h2>演出（六段，总长 120 tick = 6.0 秒）</h2>
 * <pre>
 *   段  秒区间        tick       相机距离   仰角（游戏内 xRot）      缓动
 *   ①   0 → 0.25      0 →  5     8 格       −62.25 → −24.75        ease-out
 *   ②   0.25 → 0.8    5 → 16     8 格       保持 −24.75             —
 *   ③   0.8 → 1.2    16 → 24     8 格       −24.75 → −46.00        ease-in-out
 *   ④   1.2 → 2.2    24 → 44     8 格       保持 −46.00             —
 *   ⑤   2.2 → 2.5    44 → 50     8 → 3 格   −46.00 → 0（平视）      仰角 linear / 距离 ease-in-out
 *   ⑥   2.5 → 6.0    50 → 120    3 格       保持 0                  —
 * </pre>
 * <b>第⑤段同时做两件事</b>：仰角下拉与相机推进挤在同一个 6 tick 窗口里（用户 2026-09-30 要求
 * "与此同时"）。两条曲线刻意用**不同的**缓动 —— 仰角 {@code linear}（字面"快速下拉"）、
 * 距离 {@code easeInOut}（字面"平滑推进"）。
 *
 * <h2>这个时间安排几乎消除了"末出画"</h2>
 * v2 曾经让镜头在 1.6 秒就转到平视，而末那时还在约 15 格高空 ⇒ **末完全出画约 0.8 秒**。
 * v3 把 −46° 一直保持到 **2.2 秒**（这段时间末正好从峰值往下走），再让"下拉 + 推进"与末自己的下坠
 * **同向收敛**。用 `h = (18.7 + AllBody.position.y) × 0.05` 估算"末在镜头中心上方多少度"：
 * <pre>
 *   秒     距离   仰角     末的体高   与镜头中心的夹角   在画面内？
 *   1.2    8      −46°     ~10.7      +2.5°            ✅
 *   1.75   8      −46°     ~15.4(峰值) +13.8°           ✅
 *   2.0    8      −46°     ~14.3      +11.8°           ✅
 *   2.2    8      −46°     ~11.5      +4.9°            ✅
 *   2.5    3       0°      ~3.7       +35.1°           ⚠️ 刚好擦到边缘（默认 FOV 半角≈35°）
 *   2.7    3       0°      ~0.4       −21.8°           ✅
 * </pre>
 * ⇒ 只在 **2.5 秒前后一瞬间**会擦到画面边缘，之后末完全在画面内落地。
 * （上表用的是 v1 遗留的换算模型；v3 的仰角不依赖它，模型有偏差也只影响这段描述。）
 *
 * <h2>时间基</h2>
 * 秒 → tick 一律 {@code × 20}：{@code 0.25 / 0.8 / 1.2 / 2.2 / 2.5} 秒恰好是
 * {@code 5 / 16 / 24 / 44 / 50} tick —— **本版全部是整数**，没有取整问题。
 * <br>（历史：v2 的 {@code 1.72} 秒 × 20 = 34.4 曾需要取整，用户当时选了 35；v3 把它整段删掉了。）
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

    /** ③ 结束 / ④ 开始：1.2 秒 —— 爬升到位，此后一直保持到 2.2 秒。 */
    private static final int T_RISE_END = 24;

    /** ④ 结束 / ⑤ 开始：2.2 秒 —— 高位保持结束，开始"仰角下拉 + 相机推进"。 */
    private static final int T_SNAP_START = 44;

    /** ⑤ 结束 / ⑥ 开始：2.5 秒 —— 已到平视 + 3 格，此后**全程静止**。 */
    private static final int T_SNAP_END = 50;

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

    /** 第①～④段的机位距离（格）：用户给定"距离末 8 格"。 */
    private static final double VIEW_DISTANCE_FAR = 8.0D;

    /** 第⑤段推近后的机位距离（格）：用户给定"来到距离末 3 格"。 */
    private static final double VIEW_DISTANCE_NEAR = 3.0D;

    /**
     * 相机高度（相对末的脚底，格）。{@code 1.62} = 原版玩家站立眼高
     * （{@code Player.DEFAULT_EYE_HEIGHT}）；整段不变（用户要求推近后"依旧平视"）。
     */
    private static final double VIEW_EYE_HEIGHT = 1.62D;

    /**
     * 关键点采样间隔（tick）。本版取 <b>1</b>（121 个关键点）。
     * <p>
     * 取 1 而不是 2 的理由：第⑤段要在 **6 tick 内同时**转 46° 与推 5 格（约 7.7°/tick、0.83 格/tick），
     * 第①段是 5 tick 内转 37.5°。2 tick 的采样会把这两段切成可见的折线。
     * 1 tick 采样的代价只是包大一点（约 10 KB，每条 CG 只发一次）。
     */
    private static final int SAMPLE_STEP = 1;

    // ===================== 观察者隐身 =====================

    /**
     * 隐身时长（tick）。
     * <p>
     * = {@link #DURATION_TICKS} <b>+ 10</b>（6.5 秒）。用户 2026-09-30 要求"刚好和动画长度一样"，
     * 但那会留一个尾巴：效果是**服务端先上**的，而相机要等客户端下一个 tick 才接管
     * （且渲染开关走的是另一个包，见 {@link CgAnimation#viewerInvisibilityTicks()} 与
     * {@code CgAnimation.play} 第 ⑤ 步）⇒ 效果会比 CG 早约 1 tick 结束、结尾可能闪一帧自己的身体。
     * 用户因此选了留 10 tick 余量。
     * <p>
     * 到点自动结束，我们不做任何移除（见 {@link CgAnimation#viewerInvisibilityTicks()}）。
     */
    private static final int INVISIBILITY_TICKS = DURATION_TICKS + 10;

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
     * 观察者隐身 6.5 秒（无粒子、不显示图标）。
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
     * 第①～④段恒为 {@link #VIEW_DISTANCE_FAR}；第⑤段（{@link #T_SNAP_START} → {@link #T_SNAP_END}，
     * 与仰角下拉**同一窗口**）用 {@link FDEasings#easeInOut} 平滑推到 {@link #VIEW_DISTANCE_NEAR}
     * （用户原话"平滑推进"）；之后恒为近距。
     */
    private static double distanceAt(double tick) {
        if (tick <= T_SNAP_START) {
            return VIEW_DISTANCE_FAR;
        }
        if (tick >= T_SNAP_END) {
            return VIEW_DISTANCE_NEAR;
        }
        double p = (tick - T_SNAP_START) / (double) (T_SNAP_END - T_SNAP_START);
        return lerp(VIEW_DISTANCE_FAR, VIEW_DISTANCE_NEAR, FDEasings.easeInOut((float) p));
    }

    /**
     * 写死的**仰角**曲线（度，正 = 上看）。参数是过场内的 tick，**可能带小数**
     * —— 采样时刻由 {@link CgContext#track} 按 fdlib 的公式反算，见那里的说明。
     * <p>
     * 六段：甩低（{@code easeOut}）→ 保持 → 爬升（{@code easeInOut}）→ 长保持 → 下拉 → 平视保持。
     * <p>
     * 第⑤段用**线性**：用户原话是"快速下拉"，6 tick 转 46°（约 7.7°/tick），
     * 匀速下沉正好对应"快速"；而同一窗口里的距离刻意用 {@code easeInOut}（"平滑推进"）。
     * <p>
     * 其余缓动源用 fdlib 的 {@link FDEasings}（{@code easeOut} / {@code easeInOut}），
     * 与 FDBosses 自己的过场同一族函数，整包观感一致。
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
        if (tick <= T_SNAP_START) {
            return PITCH_HIGH_DEG;
        }
        if (tick <= T_SNAP_END) {
            double p = (tick - T_SNAP_START) / (double) (T_SNAP_END - T_SNAP_START);
            return lerp(PITCH_HIGH_DEG, PITCH_LEVEL_DEG, p);
        }
        return PITCH_LEVEL_DEG;
    }

    private static double lerp(double from, double to, double p) {
        return from + (to - from) * p;
    }
}
