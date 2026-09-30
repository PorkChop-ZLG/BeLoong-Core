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
 * <h2>演出</h2>
 * 播放末的 {@code descend}（下降）动画，同时把玩家相机放到末正前方 8 格、眼高处，回看末。
 * 相机**位置整段不动，只有仰角在动**：
 * <pre>
 *   ① 抬升   t =  0 → 34   +44.3° → +59.8°   easeInOut
 *   ② 下降   t = 34 → 68   +59.8° →   0.0°   easeInOut
 *   ③ 保持   t = 68 → 120    0.0°（恒定）
 * </pre>
 * 两个断点**不是拍的，是从资产里量出来的**：
 * <ul>
 *   <li>{@code APEX_TICK = 34} —— {@code AllBody} 骨骼 Y 偏移的极值所在的关键帧是 <b>1.7067 秒</b>
 *       （= 34.134 tick，故取 34）；</li>
 *   <li>{@code LANDING_TICK = 68} —— 该偏移回到接近 0 的关键帧是 <b>3.4267 秒</b>（= 68.534 tick，故取 68）。</li>
 * </ul>
 * 两者都是"秒 → tick"的四舍五入（差 ≤ 0.14°，见 {@link #APEX_TICK} 的注释）。
 * 于是"镜头降到平视"与"末落地"**同 tick 发生** —— 这正是需求里的"缓慢下降直到平视，之后停止住"。
 *
 * <h2>那三个仰角是怎么算出来的（改了资产就要重算）</h2>
 * 换算链：{@code 模型单位 → 格} 的系数 = {@code (1/16) × MODEL_SCALE} = {@code 0.0625 × 0.80 = 0.05}
 * （{@code MoRenderer.java:63} 的 {@code MODEL_SCALE = 0.80F}）；
 * {@code AllBody} 骨骼的 pivot 在 <b>18.7 模型单位</b>（{@code mo.geo.json}）。于是：
 * <pre>
 *   体高 h(t)   = (18.7 + AllBody.position.y(t)) × 0.05        // 相对末的脚底，单位：格
 *   仰角        = atan2( h(t) − VIEW_EYE_HEIGHT , VIEW_DISTANCE )
 *
 *   时刻      AllBody Y 偏移            体高 h       仰角
 *   t = 0     +169.86 单位              9.43 格      +44.3°
 *   t = 34    +288.50 单位（极值）      15.36 格     +59.8°
 *   t = 68    −12.79 单位               0.30 格      −9.4°   ← 不用，见下
 * </pre>
 * <b>交叉验证</b>：本模组那两个动画文件里，其余 7 条动画的骨骼位移幅度只有 5–37 单位
 * （主文件 {@code mo.animation.json} 的 {@code idle} 7.4 / {@code fly} 15.5 / {@code walk} 5.7 /
 * {@code run} 7.0 / {@code attack} 36.6，加同文件的 {@code sit} 18.6 / {@code dance} 9.7），
 * <b>只有 {@code descend} 是 288</b> —— 确认它是刻意做的"从高空飞入"。
 * 其中"峰值 ≈ +288.5"这一条由 {@code cg_invariants.py} 守着（另外两条守的是动画长度与动画存在性）。
 *
 * <h2>⚠️ 一个刻意的取舍：曲线在 0° 截断</h2>
 * 物理上，降到平视的那一刻体高恰好穿过眼高，再往下镜头本该变成 <b>−9.4° 的微俯视</b>（跟到脚边）。
 * 但用户 2026-09-30 裁定"用**写死的缓动曲线**、直到**平视就停住**" ⇒ 曲线在 {@code 0.0°} 截断并保持。
 * <b>代价</b>：末落地后的最后 52 tick 里，镜头水平视线落在 1.62 格高（约末的胸口/头部），
 * 而不是跟着落到脚边 —— 观感正常，只是不再是"精确对准身体"。
 *
 * <h2>⚠️ 还有一件用户已裁定接受的事：开场是"弹射升空"</h2>
 * {@code descend} 在 <b>t = 0 时身体就已偏离原位约 218 模型单位</b>（按本类声明的 ×0.05 系数
 * ≈ <b>10.9 格</b>；其中竖直 8.5 格、水平 6.8 格），
 * 因为动效全在 {@code AllBody} 骨骼上而 {@code Root} 位移恒定 ⇒ 指令一执行，末会**从地面迅速升到高空**。
 * GeckoLib 的 5 tick 过渡（{@code NpcEntity.animationTransitionTicks()}）把它混成约 0.25 秒的位移，
 * 不是瞬间闪现。用户选择**不特殊处理**（不加黑场），因此本 CG 的仰角**一开始就对准高处**
 * 而不是从平视抬上来。
 *
 * <h2>为什么机位整段不动、只转视角</h2>
 * {@code descend} 的 {@code Root} 骨骼位移全程恒定为 {@code (0, 0.332, 0)} ⇒ **实体本身一动不动**
 * ⇒ 机位可以在触发那一刻**烘死**成世界坐标，零精度损失。这同时绕开了 fdlib 一个结构性缺口：
 * 它的朝向逻辑被写死成"关键点之间插值"（{@code CutsceneExecutor} 构造器硬编码
 * {@code new NormalLookProcessor()}，private 且无 setter），**无法自动看向移动目标** ——
 * 而本 CG 根本不需要。
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

    /** 机位到末的水平距离（格）。用户给定：玩家站在末面前 8 格。 */
    private static final double VIEW_DISTANCE = 8.0D;

    /** 相机高度（相对末的脚底，格）。1.62 = 原版玩家站立眼高。 */
    private static final double VIEW_EYE_HEIGHT = 1.62D;

    /**
     * 抬升段结束 tick —— {@code AllBody} Y 偏移极值所在的关键帧，{@code 1.7067 秒}。
     * <p>
     * ⚠️ 它是 {@code round(1.7067 × 20) = 34}（真值 34.134）。按 GeckoLib 线性插值，
     * tick 34 处的偏移是 287.017 单位而不是极值 288.506 ⇒ 实际体高 15.286 格 ⇒ 仰角 <b>59.66°</b>
     * 而非 {@link #PITCH_APEX_DEG} 的 59.8°。<b>差 0.14°，刻意不修</b>：
     * 取整让断点恰好落在采样点上（见 {@link #SAMPLE_STEP}），比抠这 0.14° 重要。
     */
    private static final int APEX_TICK = 34;

    /** 起始仰角：对准 t=0 的体高 9.43 格。 */
    private static final double PITCH_START_DEG = 44.3D;

    /** 峰值仰角：对准 t=34 的体高 15.36 格。 */
    private static final double PITCH_APEX_DEG = 59.8D;

    /** 下降段结束 tick = {@code descend} 的落地时刻（3.4267 秒）。之后保持平视。 */
    private static final int LANDING_TICK = 68;

    /** 平视。用户指定"直到平视就停住"。 */
    private static final double PITCH_LEVEL_DEG = 0.0D;

    /**
     * 关键点采样间隔（tick）。
     * <p>
     * 取 2 的理由：fdlib 的 {@link CgContext#pitchCurve} 按
     * {@code tick(i) = i × totalTicks / intervals} 反算采样时刻，而 {@code 120 / 2 = 60} 段
     * ⇒ 采样点恰好落在 0, 2, 4, …, 120，两个断点 34 / 68 分别命中索引 17 / 34，**分毫不差**。
     * 取 4 会让峰值差 2 tick（无伤，但没必要）；取 2 的代价只是包大一点（约 5 KB，每条 CG 只发一次）。
     */
    private static final int SAMPLE_STEP = 2;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String animationName() {
        return ANIMATION_NAME;
    }

    @Override
    protected CutsceneData build(CgContext ctx) {

        // 机位：末正前方 VIEW_DISTANCE 格、抬高到眼高。整段 CG 不变。
        Vec3 camPos = ctx.ahead(VIEW_DISTANCE).add(0.0D, VIEW_EYE_HEIGHT, 0.0D);

        // ⚠️ 三个 easing/curve 都必须是 LINEAR，见 CgContext.pitchCurve 的 javadoc：
        //    fdlib 的关键点只能等距（t = i/(n-1) × 总时长），任何非线性映射都会让
        //    采样点与播放点错位；缓动已经烘进 elevationAtTick 的采样值里了。
        CutsceneData data = CutsceneData.create()
                .time(DURATION_TICKS)
                .stopMode(CutsceneData.StopMode.AUTOMATIC)
                .moveCurveType(CurveType.LINEAR)
                .timeEasing(EasingType.LINEAR)
                .lookEasing(EasingType.LINEAR);

        // 机位不动、只转视角 ⇒ 一串位置完全相同、只有视线方向不同的 CameraPos。
        List<CameraPos> track = CgContext.pitchCurve(
                camPos, ctx.anchor(), DURATION_TICKS, SAMPLE_STEP, MoEntrance::elevationAtTick);
        for (CameraPos pos : track) {
            data.addCameraPos(pos);
        }
        return data;
    }

    /**
     * 写死的仰角曲线（单位：度，正 = 上看）。参数是过场内的 tick。
     * <p>
     * 三段式：抬升（{@code easeInOut}）→ 下降（{@code easeInOut}）→ 保持。
     * 缓动取 {@link FDEasings#easeInOut} 是为了与 FDBosses 自己的过场观感一致
     * （它的 {@code GeburahBossInitializer} / {@code MalkuthBossInitializer} 用的就是这条 easing）。
     */
    private static double elevationAtTick(double tick) {
        if (tick <= APEX_TICK) {
            double p = tick / (double) APEX_TICK;
            return PITCH_START_DEG + (PITCH_APEX_DEG - PITCH_START_DEG) * FDEasings.easeInOut((float) p);
        }
        if (tick <= LANDING_TICK) {
            double p = (tick - APEX_TICK) / (double) (LANDING_TICK - APEX_TICK);
            return PITCH_APEX_DEG + (PITCH_LEVEL_DEG - PITCH_APEX_DEG) * FDEasings.easeInOut((float) p);
        }
        return PITCH_LEVEL_DEG;
    }
}
