package com.zonlong.beloong.cg;

import com.finderfeed.fdlib.systems.cutscenes.CameraPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * 一条 CG 的**只读上下文**，外加本系统全部几何数学的**唯一集中点**。
 *
 * <h2>为什么需要一个上下文对象</h2>
 * 每条 CG 都要做同样三件事：把"离目标 N 格"变成一个世界坐标、把"仰角"变成一条视线方向、
 * 把一条仰角曲线打成 fdlib 的 {@code CameraPos} 列表。这三件事写进基类，N 个 CG 类才不会各抄一份
 * —— 尤其是第三条，它的正确性依赖 fdlib 一个不显眼的时间约定（见 {@link #pitchCurve}）。
 *
 * <h2>三个字段的语义</h2>
 * <ul>
 *   <li>{@link #anchor} —— 目标的位置（脚底）。所有偏移都以它为原点；</li>
 *   <li>{@link #forward} —— 目标朝向的**水平单位向量**，由
 *       {@code Vec3.directionFromRotation(0, target.getYRot())} 求得。
 *       ⚠️ <b>刻意不用 {@code Entity#getForward()}</b> —— 那个是**含俯仰**的三维视向量，
 *       原因与后果见 {@link #FORWARD_EPSILON} 的注释；</li>
 *   <li>{@link #viewer} —— 唯一会看到这条 CG 的玩家（用户 2026-09-30 裁定：只发给执行者）。</li>
 * </ul>
 *
 * <h2>为什么机位"完全由目标决定"</h2>
 * 用户 2026-09-30 裁定：`相机 = 目标.pos + 目标.forward × N`。于是同一条 CG 在任何地方触发都得到
 * 同一个构图，与玩家实际站哪无关。**代价**是它依赖"目标正朝向执行者"这个操作前提
 * —— {@code NpcEntity.facePlayerDistance() = 8.0F}，而本 CG 取的机位距离恰好也是 8 格，
 * **正好卡在 {@code LookAtPlayerGoal} 的生效边界上**。用户明确选择接受这个风险（不加前置校验）。
 *
 * <h2>坐标系约定：本类只产出"向量"，不产出"角度"</h2>
 * {@link #sightLine} 返回**单位向量**，交给 fdlib 的 {@code CameraPos(Vec3, Vec3)} 去分解成 yaw/pitch
 * —— 由它来分解，我们就不需要知道 Minecraft 的 yaw/pitch 正负号约定，也就没有踩错符号的空间。
 * 本类自己定义的只有 {@code elevationDeg}：**正 = 上看**，与 MC 无关。
 *
 * <p>设计文档：{@code docs/plans/2026-09-30-cg-system-design.md}（§2 组件 / §3 编排）。
 */
public record CgContext(ServerPlayer viewer, Entity target, Vec3 anchor, Vec3 forward) {

    /**
     * {@link #forward} 的水平**长度平方**下限。低于它（或为 NaN）即认定"朝向退化"。
     *
     * <h2>⚠️ 为什么用 {@code directionFromRotation(0, yRot)} 而不用 {@code Entity#getForward()}</h2>
     * {@code Entity#getForward()} 是 <b>含俯仰的三维视向量</b>：
     * <pre>
     *   Entity#getForward()  = Vec3.directionFromRotation(this.getRotationVector())
     *   getRotationVector()  = (getXRot(), getYRot())
     *   ⇒ 水平投影长度 = |cos(xRot)|        // 只有 xRot == 0 时才是单位长
     * </pre>
     * 于是 {@code |xRot|} 接近 90° 时水平分量趋近于零，会被**误判成"朝向退化"而拒播整条 CG**。
     * 这<b>不是</b>理论情形：本模组 NPC 的飞行态装的是原版 {@code FlyingMoveControl}
     * （见 {@code NpcEntity.enableFlight()}），它每 tick 用
     * {@code setXRot(rotlerp(getXRot(), -atan2(Δy, 水平距离), 20))} 写俯仰，
     * 而 {@code disableFlight()} <b>不把 XRot 归零</b> ⇒ 末只要执行过
     * {@code state … flying} + {@code move <接近正上/正下的空中坐标>}，落地后 XRot 仍可能接近 ±90°。
     * <p>
     * 换成 {@code directionFromRotation(0, yRot)} 后<b>与俯仰完全解耦</b>：按定义就是水平单位向量，
     * 且朝向与 {@code getForward()} 一致 —— 后者只是把同一个 XZ 方向乘了 {@code |cos(xRot)|}，
     * 归一化后方向相同。
     *
     * <h2>于是这个 epsilon 退化成真正的防御</h2>
     * 它只为 NaN / 非有限值兜底（{@code getYRot()} 理论上可能被外部写成 NaN）。
     * 判据刻意写成 {@code !(lengthSqr > eps)} 而不是 {@code lengthSqr < eps} ——
     * <b>NaN 参与任何比较都返回 false</b>，前者能把 NaN 也判成退化，后者会漏掉。
     */
    private static final double FORWARD_EPSILON = 1.0E-6D;

    /**
     * 构造上下文。
     *
     * @return 上下文；**目标朝向退化（含 NaN）时返回 {@code null}**
     *         —— 调用方必须 fail-closed，不要播放
     */
    @Nullable
    public static CgContext of(ServerPlayer viewer, Entity target) {
        Vec3 horizontal = Vec3.directionFromRotation(0.0F, target.getYRot());
        if (!(horizontal.lengthSqr() > FORWARD_EPSILON)) {
            return null;
        }
        return new CgContext(viewer, target, target.position(), horizontal.normalize());
    }

    /**
     * 从锚点沿目标朝向前方 {@code blocks} 格（**水平**，Y 不变）。
     * <p>
     * 这是 FDBosses 里 `bossPos.add(forward.multiply(40,40,40))` 那类写法的具名化版本。
     */
    public Vec3 ahead(double blocks) {
        return anchor.add(forward.scale(blocks));
    }

    /**
     * 视线方向：水平朝向 {@code aimPoint}、仰角 {@code elevationDeg} 度（**正 = 上看**）。
     *
     * <p><b>为什么返回向量而不是 yaw/pitch</b>：fdlib 的 {@code CameraPos(Vec3, Vec3)} 内部会调它自己的
     * {@code yRotFromVector}/{@code xRotFromVector} 做转换。让<b>它</b>去分解，我们就不必知道 MC 的
     * yaw 是 `atan2(-x, z)` 还是别的写法 —— 那种约定错一次，方向会静默偏 90°。
     *
     * <p>水平分量取 {@code aimPoint - camPos} 的 XZ 部分。理论上它可能为零（机位与注视点同一条竖直线），
     * 此时水平朝向无定义；本方法退化为 {@code +Z}。**这是纯防御** —— 本系统的几何下机位与注视点
     * 恒相距一个正的距离（{@code VIEW_DISTANCE} 格），不可达。
     * <p>退化判据与 {@link #of} 共用 {@link #FORWARD_EPSILON}（同一个**长度平方**阈值），
     * 并同样写成 {@code !(x > eps)} 以便把 NaN 一并判为退化。
     */
    public static Vec3 sightLine(Vec3 camPos, Vec3 aimPoint, double elevationDeg) {
        Vec3 horizontal = aimPoint.subtract(camPos).multiply(1.0D, 0.0D, 1.0D);
        double lengthSqr = horizontal.lengthSqr();
        Vec3 unitHorizontal = !(lengthSqr > FORWARD_EPSILON)
                ? new Vec3(0.0D, 0.0D, 1.0D)
                : horizontal.scale(1.0D / Math.sqrt(lengthSqr));
        double radians = Math.toRadians(elevationDeg);
        return unitHorizontal.scale(Math.cos(radians)).add(0.0D, Math.sin(radians), 0.0D);
    }

    /**
     * 「机位不动、仰角按给定函数走」—— 产出 fdlib 的 {@code CameraPos} 列表。
     *
     * <h2>⚠️ 这里有一个必须照着做的约定</h2>
     * fdlib 的 {@code NormalLookProcessor} 把第 i 个关键点钉在
     * {@code t = i / (n - 1) × 总时长}（{@code NormalLookProcessor.java:23-27}）
     * —— <b>关键点只能等距，不能指定任意 tick</b>。
     * <p>
     * 所以本方法<b>不</b>按 {@code sampleStep} 直接步进，而是先用 {@code intervals = totalTicks / sampleStep}
     * 定出段数，再<b>按 fdlib 自己的公式反算</b>每个采样点对应的 tick：
     * <pre>
     *   tick(i) = i × totalTicks / intervals
     * </pre>
     * 这样"我们采样的时刻"与"fdlib 播放该关键点的时刻"**逐一对齐**，与 {@code totalTicks}
     * 能否被 {@code sampleStep} 整除无关（能整除时 {@code tick(i) = i × sampleStep}，最直观）。
     *
     * <p><b>调用方必须同时做两件事</b>，否则曲线会失准：
     * <ol>
     *   <li>{@code timeEasing = LINEAR} —— 它作用在**全局百分比**上，非线性会把等距映射整体扭曲；</li>
     *   <li>{@code lookEasing = LINEAR} —— 它作用在**段内**，非线性会让每个小段各自起涟漪。</li>
     * </ol>
     * ⇒ <b>缓动必须烘进 {@code elevationDegAtTick} 的采样值里</b>，不能交给 fdlib 的 {@code EasingType}。
     * 这是"把一条手写缓动曲线塞进一个只支持等距关键点的引擎"的通用解法。
     *
     * @param camPos            机位（整条曲线的位置都取它，所以相机只转不移）
     * @param aimPoint          水平朝向的参考点（通常取目标的位置）
     * @param totalTicks        过场总时长；必须与 {@code CutsceneData.time(...)} 一致
     * @param sampleStep        期望的采样间隔（tick）。越小越平滑，包越大
     * @param elevationDegAtTick 仰角函数，参数是 tick（double）。正 = 上看
     */
    public static List<CameraPos> pitchCurve(Vec3 camPos, Vec3 aimPoint, int totalTicks, int sampleStep,
                                             DoubleUnaryOperator elevationDegAtTick) {
        int intervals = Math.max(1, totalTicks / Math.max(1, sampleStep));
        List<CameraPos> positions = new ArrayList<>(intervals + 1);
        for (int i = 0; i <= intervals; i++) {
            double tick = (double) i * totalTicks / intervals;
            positions.add(new CameraPos(camPos, sightLine(camPos, aimPoint, elevationDegAtTick.applyAsDouble(tick))));
        }
        return positions;
    }
}
