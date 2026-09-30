package com.zonlong.beloong.cg;

import com.finderfeed.fdlib.FDLibCalls;
import com.finderfeed.fdlib.systems.cutscenes.CutsceneData;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.entity.NpcEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * 一条过场动画（CG）的**抽象配方** —— 每个 CG 一个子类，数值全部写死在子类里。
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li>子类只描述"**镜头怎么走**"（{@link #build}，纯函数）与"**目标播哪条动画**"（{@link #animationName}）；</li>
 *   <li>发不发包、什么顺序、失败怎么办 —— 全部由 {@link #play} 一个地方决定。</li>
 * </ul>
 *
 * <h2>为什么 {@code play} 是 {@code final}</h2>
 * 与 {@code NpcEntity.switchState} 那条"状态切换的唯一副作用集中点"同源：本系统刻意**不持有任何运行期状态**
 * （服务端在一个 tick 内烘好轨迹、触发动画、发两个包就撒手），所以"发什么包、按什么顺序、何时放弃"
 * 必须只有一份实现。若允许子类覆写，N 个 CG 类会长出 N 份略有差异的发包逻辑，
 * 而这类差异不会在编译期暴露、只会在实机上表现为"某一条 CG 偶尔不灵"。
 *
 * <h2>{@link #play} 里的四条预检（缺一条都会静默产生坏状态）</h2>
 * <ol>
 *   <li><b>朝向退化</b> ⇒ 返回 0。{@link CgContext#of} 会返回 {@code null}；
 *       归一化一个零向量会得到 NaN，再喂进 {@code CameraPos} 就是一个看不出原因的坏镜头。</li>
 *   <li><b>{@link #build} 抛异常</b> ⇒ 捕获后返回 0。照 {@code EmoteAnimationLookup} 捕 {@code Throwable}
 *       的先例：宁可退化成"这一条没播放"，也不让某条 CG 的 bug 崩掉命令分发。</li>
 *   <li><b>轨迹列表为空</b> ⇒ 返回 0，<b>并且不触发动画</b>。★ 这条不是防御性编程，是一个**已核实的崩溃面**：
 *       fdlib 客户端 {@code CutsceneCameraHandler.startCutscene} 里
 *       {@code data.getCameraPositions().getFirst()} <b>没有任何判空</b>
 *       ⇒ 空列表会让**客户端在收包那一刻抛 {@code NoSuchElementException}**；
 *       而 {@code LinearCameraMotion}/{@code CatmullRomCameraMotion} 还会在**每 tick** 的
 *       {@code calculateCameraPosition} 里再抛一次。这条预检对应项目既有教训
 *       "**对方零校验时，预检必须由我们做**"（{@code memory/learned-patterns.md}）。</li>
 *   <li><b>声明了动画但目标不是 {@link NpcEntity}</b> ⇒ 返回 0。表情系统只存在于 NPC 基类上；
 *       静默跳过会让作者以为"动画播了只是没看见"。
 *       （正常路径上命令层已经先挡了一道，这里是**独立的第二道保险**，同
 *       {@code NpcEntity.resetToDefault} 里那句无条件 {@code setNoGravity(false)} 的取舍。）</li>
 * </ol>
 *
 * <p>设计文档：{@code docs/plans/2026-09-30-cg-system-design.md}（§1 架构 / §2 组件 / §5 错误处理）。
 */
public abstract class CgAnimation {

    /** CG 的指令名（如 {@code mo_entrance}），也是 {@code CgRegistry} 的键。 */
    public abstract String name();

    /**
     * 要在目标身上播的动画名，交给表情系统（{@code NpcEntity.setEmote}）。
     * <p>
     * <b>空串 = 这条 CG 不动画</b>（只动相机）—— 于是"镜头类 CG"与"演出类 CG"共用同一套下发路径。
     */
    public abstract String animationName();

    /**
     * 把这条 CG 编排成一个过场。**纯函数**：不发包、不改世界、不读磁盘。
     *
     * @param ctx 只读上下文（锚点 = 目标位置，forward = 目标朝向的水平单位向量）
     * @return 过场数据；其 {@code time(...)} 必须与 {@code stopMode(AUTOMATIC)} 配套
     *         （fdlib 里只有 {@code AUTOMATIC} 会在播完后自行归还相机）
     */
    protected abstract CutsceneData build(CgContext ctx);

    /**
     * **唯一的副作用出口**。顺序：预检 → 触发动画 → 发过场包。
     *
     * @param viewer 唯一会看到这条 CG 的玩家（用户 2026-09-30 裁定：只发给执行者）
     * @param target 演出主体；正常路径上已由命令层保证是 {@link NpcEntity}
     * @return 1 = 已下发；0 = 预检未过，**什么都没发生**（具体原因在日志里，英文）
     */
    public final int play(ServerPlayer viewer, Entity target) {

        // ① 朝向退化 ⇒ 不做任何事（CgContext.of 返回 null）
        CgContext ctx = CgContext.of(viewer, target);
        if (ctx == null) {
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] cg '{}' aborted: target {} has a degenerate forward vector (cannot place the camera)",
                    this.name(), target.getType());
            return 0;
        }

        // ② build 抛任何东西 ⇒ 不把半个 CutsceneData 发出去
        CutsceneData data;
        try {
            data = this.build(ctx);
        } catch (Throwable t) {
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] cg '{}' aborted: building the cutscene threw (treating as unplayable): {}",
                    this.name(), t.toString());
            return 0;
        }

        // ③ ★ 轨迹为空 ⇒ 客户端会 NoSuchElementException（见类注释第 3 条）
        if (data == null || data.getCameraPositions().isEmpty()) {
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] cg '{}' aborted: built an empty camera track, which fdlib's client cannot consume "
                            + "(CutsceneCameraHandler does getCameraPositions().getFirst() without a check)",
                    this.name());
            return 0;
        }

        // ④ 要播动画就要求目标是 NPC —— 静默跳过不可接受（见类注释第 4 条）
        String animation = this.animationName();
        if (!animation.isEmpty()) {
            if (!(target instanceof NpcEntity npc)) {
                BeLoongCore.LOGGER.warn(
                        "[BeLoong] cg '{}' aborted: it declares animation '{}' but target {} is not an NpcEntity",
                        this.name(), animation, target.getType());
                return 0;
            }
            npc.setEmote(animation);
        }

        FDLibCalls.startCutsceneForPlayer(viewer, data);

        // 一条 INFO 记录本次编排的关键几何量：实机标定时若镜头方向不对，
        // 从这一行就能判断是 forward 取错了、还是仰角常量需要调（见计划 T10 的对照表）。
        BeLoongCore.LOGGER.info(
                "[BeLoong] cg '{}' playing for {} on {} (animation='{}', anchor={}, forward={}, cameraPos={}, keys={}, ticks={})",
                this.name(), viewer.getGameProfile().getName(), target.getType(), animation,
                ctx.anchor(), ctx.forward(), data.getCameraPositions().getFirst().getPos(),
                data.getCameraPositions().size(), data.getCutsceneTime());
        return 1;
    }
}
