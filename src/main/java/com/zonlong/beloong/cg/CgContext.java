package com.zonlong.beloong.cg;

import com.finderfeed.fdlib.systems.cutscenes.CameraPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;
import java.util.function.DoubleUnaryOperator;

/**
 * 一条 CG 的**只读上下文**，外加本系统全部几何数学的**唯一集中点**。
 *
 * <h2>为什么需要一个上下文对象</h2>
 * 每条 CG 都要做同样三件事：把"离目标 N 格"变成一个世界坐标、把"仰角"变成一条视线方向、
 * 把一条"机位 + 仰角随时间变化"的曲线打成 fdlib 的 {@code CameraPos} 列表。这三件事写进基类，
 * N 个 CG 类才不会各抄一份
 * —— 尤其是第三条，它的正确性依赖 fdlib 一个不显眼的时间约定（见 {@link #track}）。
 *
 * <h2>三个字段的语义</h2>
 * <ul>
 *   <li>{@link #anchor} —— 目标的位置（脚底）。所有偏移都以它为原点；</li>
 *   <li>{@link #anchorToViewer} —— <b>"锚点 → 观察者"的水平单位向量</b>，由两个位置相减得到。
 *       ⚠️ <b>刻意不读目标的任何朝向</b>（既不读 {@code getYRot()}、也不读 {@code getForward()}）
 *       —— 理由是一个实机才踩到的坑，见 {@link #DIRECTION_EPSILON} 的注释；</li>
 *   <li>{@link #viewer} —— 唯一会看到这条 CG 的玩家（用户 2026-09-30 裁定：只发给执行者）。</li>
 * </ul>
 *
 * <h2>机位方向由"观察者在哪一侧"决定，距离由 N 决定（v4）</h2>
 * {@code 相机 = 目标.pos + (目标.pos − 观察者.pos).normalize() × N}。
 * 于是同一条 CG 在任何地方触发都得到**同一个构图**（距离恒为 N、回看目标），
 * 且**与目标朝哪无关** —— 目标的朝向不再是输入，"玩家站在目标的哪一侧"才是。
 * <p>
 * v1–v3 曾是"完全由目标决定"（{@code 目标.pos + 目标.forward × N}），并把"依赖目标正朝向执行者"
 * 写成一条**已知代价**接受掉。实机证明那条代价的描述是错的：{@code forward} 读的
 * {@code getYRot()} 是**行走朝向**，而"看向玩家"根本不改它 ⇒ **目标走过路之后镜头会飞到它背后**，
 * 且与"玩家站多远"无关。详见 {@link #DIRECTION_EPSILON}。
 *
 * <h2>坐标系约定：本类只产出"向量"，不产出"角度"</h2>
 * {@link #sightLine} 返回**单位向量**，交给 fdlib 的 {@code CameraPos(Vec3, Vec3)} 去分解成 yaw/pitch
 * —— 由它来分解，我们就不需要知道 Minecraft 的 yaw/pitch 正负号约定，也就没有踩错符号的空间。
 * 本类自己定义的只有 {@code elevationDeg}：**正 = 上看**，与 MC 无关。
 *
 * <p>设计文档：{@code docs/plans/2026-09-30-cg-system-design.md}（§2 组件 / §3 编排）。
 */
public record CgContext(ServerPlayer viewer, Entity target, Vec3 anchor, Vec3 anchorToViewer) {

    /**
     * {@link #anchorToViewer} 的水平**长度平方**下限。低于它（或为 NaN）即认定"方向退化"。
     *
     * <h2>⚠️ 为什么机位方向取"锚点 → 观察者"，而不是目标的 yaw（2026-09-30 v4 变更）</h2>
     * v1–v3 用的是 {@code Vec3.directionFromRotation(0, target.getYRot())}，即"**目标的朝向**"。
     * 那个做法有一个**实机才暴露的缺陷**：{@code getYRot()} 是这具实体的**行走朝向**，
     * 不是它"面朝 / 看向"的方向 ——
     * <pre>
     *   yRot       ← 只有【移动】会写它（MoveControl / travel）
     *   yHeadRot   ← LookControl.tick() 写它（"看向谁"写的就是这个字段）
     *   yBodyRot   ← BodyRotationControl.clientTick()：移动时 = yRot；静止时追 yHeadRot（且【不同步】）
     * </pre>
     * ⇒ 一个走过路的 NPC，{@code yRot} 会**永久停在最后一次行进方向**上；
     * "看向玩家"只改 {@code yHeadRot}（身体随后跟上），<b>永远不改 {@code yRot}</b>。
     * 于是相机会被烘到那个旧行进方向的 8 格外 —— 任意角度，实测常见"落在末的背后"，
     * 而玩家眼里末明明正回头看着他。**根因是读错了字段，与"站多远"无关。**
     * <p>
     * 改成"锚点 → 观察者"后，机位方向<b>完全不再读目标的任何朝向</b>：它由"玩家站在哪一侧"决定，
     * 而那是触发者当场的、确定的事实。又因为只取<b>方向</b>、距离仍由 {@code N} 决定，
     * "构图恒为 8 格"这一性质保持不变，而且**不必再要求玩家恰好站在 8 格处**。
     *
     * <h2>于是 v1–v3 那条"俯仰耦合"风险整体消失了</h2>
     * 曾经专门记过：{@code Entity#getForward()} 是含俯仰的三维视向量，水平投影长 {@code |cos(xRot)|}，
     * 而本模组 NPC 的飞行态（原版 {@code FlyingMoveControl}）会写俯仰、{@code disableFlight()}
     * 又不归零 ⇒ XRot 可能长期接近 ±90°，导致被**误判成"朝向退化"而拒播整条 CG**。
     * 现在 {@code anchorToViewer} 由两个位置相减并强制取 XZ 得到，与任何实体的 xRot/yRot 都无关。
     *
     * <h2>这个 epsilon 现在守什么</h2>
     * 守"观察者与锚点水平重合"（例如玩家站进了末的身体里）。判据刻意写成
     * {@code !(lengthSqr > eps)} 而不是 {@code lengthSqr < eps} ——
     * <b>NaN 参与任何比较都返回 false</b>，前者能把 NaN 也判成退化，后者会漏掉。
     * <br>⚠️ 阈值只有 1e-6，所以"站进末体内但仍有一点水平偏移"时方向**有定义、但会很吵** ——
     * 那属于使用者自己造成的构图，不额外校验。
     */
    private static final double DIRECTION_EPSILON = 1.0E-6D;

    /**
     * 构造上下文。{@code anchorToViewer} = "锚点 → 观察者"的**水平单位向量**。
     *
     * @return 上下文；**观察者与锚点水平重合（含 NaN）时返回 {@code null}**
     *         —— 调用方必须 fail-closed，不要播放
     */
    @Nullable
    public static CgContext of(ServerPlayer viewer, Entity target) {
        Vec3 anchor = target.position();
        Vec3 horizontal = anchor.subtract(viewer.position()).multiply(1.0D, 0.0D, 1.0D);
        if (!(horizontal.lengthSqr() > DIRECTION_EPSILON)) {
            return null;
        }
        return new CgContext(viewer, target, anchor, horizontal.normalize());
    }

    /**
     * 从锚点**朝观察者那一侧**水平 {@code blocks} 格（Y 不变）。
     * <p>
     * 这是 FDBosses 里 {@code bossPos.add(forward.multiply(40,40,40))} 那类写法的具名化版本，
     * 只是方向基准换成了"锚点 → 观察者"（为什么换，见 {@link #DIRECTION_EPSILON} 的说明）。
     */
    public Vec3 towardViewer(double blocks) {
        return anchor.add(anchorToViewer.scale(blocks));
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
     * 恒相距一个正的距离（{@code VIEW_DISTANCE_FAR} / {@code VIEW_DISTANCE_NEAR} 格），不可达。
     * <p>退化判据与 {@link #of} 共用 {@link #DIRECTION_EPSILON}（同一个**长度平方**阈值），
     * 并同样写成 {@code !(x > eps)} 以便把 NaN 一并判为退化。
     */
    public static Vec3 sightLine(Vec3 camPos, Vec3 aimPoint, double elevationDeg) {
        Vec3 horizontal = aimPoint.subtract(camPos).multiply(1.0D, 0.0D, 1.0D);
        double lengthSqr = horizontal.lengthSqr();
        Vec3 unitHorizontal = !(lengthSqr > DIRECTION_EPSILON)
                ? new Vec3(0.0D, 0.0D, 1.0D)
                : horizontal.scale(1.0D / Math.sqrt(lengthSqr));
        double radians = Math.toRadians(elevationDeg);
        return unitHorizontal.scale(Math.cos(radians)).add(0.0D, Math.sin(radians), 0.0D);
    }

    /**
     * 采样一条**机位与仰角都随时间变化**的相机轨迹，产出 fdlib 的 {@code CameraPos} 列表。
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
     * <p><b>调用方必须同时做三件事</b>，否则曲线会失准：
     * <ol>
     *   <li>{@code timeEasing = LINEAR} —— 它作用在**全局百分比**上，非线性会把等距映射整体扭曲；</li>
     *   <li>{@code lookEasing = LINEAR} —— 它作用在**段内**，非线性会让每个小段各自起涟漪；</li>
     *   <li>{@code moveCurveType = LINEAR} —— 理由见下。</li>
     * </ol>
     *
     * <p><b>为什么用 LINEAR 而不是 CATMULLROM</b>：{@code LinearCameraMotion} 用
     * {@code FDLibCalls.getListValueOrBoundaries}（越界取首/末，**永不返回 null**）+
     * {@code CameraPos.interpolate}（纯 lerp），语义最直白、不引入额外控制点。
     * <br>注：**CATMULLROM 本身也是安全的** —— {@code FDMathUtil.catmullrom(prev,cur,next,next2,p)}
     * 自带 null 守卫（端点会补点）。选 LINEAR 纯粹是因为本 CG 的曲线已经逐 tick 采样过，
     * 不需要再被样条平滑一次。
     *
     * <p>⚠️ <b>一条 fdlib 的既知相位差（不是 bug，但要知道）</b>：位置与朝向的取样时刻差约 1 tick ——
     * {@code CutsceneExecutor.tick} 先用**自增前**的 {@code currentTime} 算位置、再自增
     * （{@code CutsceneExecutor.java:49-53}），而渲染取朝向用的是
     * **自增后**的 {@code currentTime + partialTick}（{@code CutsceneCameraHandler.java:144}）。
     * 即同一帧里"**位置是 t−1 的值、朝向是 t 的值**"。
     * <br>本 CG 第⑤段是 6 tick 内转 46° + 推 5 格 ⇒ 该段误差上限约 <b>8°</b>（46/6）与 <b>0.83 格</b>（5/6）。
     * 要补偿就对症下药，**二选一、不要同时做**：把<b>距离</b>的断点<b>前移</b> 1 tick（位置提前一步），
     * 或把<b>仰角</b>的断点<b>后移</b> 1 tick（朝向滞后一步）。
     * ⇒ <b>所有缓动都必须烘进 {@code cameraAt} / {@code elevationAt} 的采样值里</b>，
     * 不能交给 fdlib 的 {@code EasingType}。
     * 这是"把一条手写曲线塞进一个只支持等距关键点的引擎"的通用解法。
     *
     * <p>水平朝向**始终指向 {@link #anchor}**：由于机位始终在"锚点 → 观察者"这条直线上，
     * 无论距离怎么变，水平朝向恒为 {@code -anchorToViewer} —— 所以"推近"不会带来任何偏航。
     *
     * @param totalTicks   过场总时长；必须与 {@code CutsceneData.time(...)} 一致
     * @param sampleStep   期望的采样间隔（tick）。越小越平滑，包越大
     * @param cameraAt     给定 tick 的**机位**（世界坐标）
     * @param elevationAt  给定 tick 的**仰角**（度，正 = 上看）
     */
    public List<CameraPos> track(int totalTicks, int sampleStep,
                                DoubleFunction<Vec3> cameraAt,
                                DoubleUnaryOperator elevationAt) {
        int intervals = Math.max(1, totalTicks / Math.max(1, sampleStep));
        List<CameraPos> positions = new ArrayList<>(intervals + 1);
        for (int i = 0; i <= intervals; i++) {
            double tick = (double) i * totalTicks / intervals;
            Vec3 camPos = cameraAt.apply(tick);
            positions.add(new CameraPos(camPos, sightLine(camPos, this.anchor, elevationAt.applyAsDouble(tick))));
        }
        return positions;
    }
}
