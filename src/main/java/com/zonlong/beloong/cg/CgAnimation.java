package com.zonlong.beloong.cg;

import com.finderfeed.fdlib.FDLibCalls;
import com.finderfeed.fdlib.systems.cutscenes.CutsceneData;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.entity.NpcEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
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
 *   <li><b>轨迹列表为空、或 {@code time <= 0}</b> ⇒ 返回 0，<b>并且不触发动画</b>。★ 这条不是防御性编程，
 *       是一个**已核实的崩溃面**：fdlib 客户端 {@code CutsceneCameraHandler.startCutscene} 里
 *       {@code data.getCameraPositions().getFirst()} <b>没有任何判空</b>
 *       ⇒ 空列表会让**客户端在收包那一刻抛 {@code NoSuchElementException}**；
 *       而 {@code LinearCameraMotion}/{@code CatmullRomCameraMotion} 还会在**每 tick** 的
 *       {@code calculateCameraPosition} 里再抛一次。
 *       后半句（{@code time <= 0}）守的是 fdlib 的进度计算 {@code currentTime / cutsceneTime}
 *       —— 为 0 会算出 NaN 机位。本 CG 恒为 120，这道闸防的是**将来的 CG 类**。
 *       这条预检对应项目既有教训
 *       "**对方零校验时，预检必须由我们做**"（{@code memory/learned-patterns.md}）。</li>
 *   <li><b>声明了动画但目标不是 {@link NpcEntity}</b> ⇒ 返回 0。表情系统只存在于 NPC 基类上；
 *       静默跳过会让作者以为"动画播了只是没看见"。
 *       （正常路径上命令层已经先挡了一道，这里是**独立的第二道保险**，同
 *       {@code NpcEntity.resetToDefault} 里那句无条件 {@code setNoGravity(false)} 的取舍。）</li>
 * </ol>
 *
 * <p>除预检之外，{@link #play} 还会在发包前做一件**表现层**的事：
 * 若本 CG 声明了 {@link #viewerInvisibilityTicks()}，就给观察者上一个原版隐身效果
 * （理由与代价见该方法的注释 —— 简言之：fdlib 不隐藏玩家自己的身体）。
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
     * 过场期间给**观察者自己**上的隐身效果时长（tick）。{@code 0} = 不上（默认）。
     *
     * <h2>为什么需要它</h2>
     * fdlib 的过场只隐藏 HUD 与第一人称的"手"（`RenderHandEvent`），
     * <b>不隐藏玩家自己的身体</b>。原因我们核实到了源码级 —— NeoForge 给 `LevelRenderer`
     * 打了补丁（原文注释 {@code // Neo: render local player entity when it is not the camera entity}）：
     * <pre>
     *   &amp;&amp; (!(entity instanceof LocalPlayer) || camera.getEntity() == entity
     *       || (entity == minecraft.player &amp;&amp; !minecraft.player.isSpectator()))
     * </pre>
     * 相机换成 fdlib 的 {@code ClientCameraEntity} 之后，最后那个子条件为真
     * ⇒ <b>本地玩家会被渲染</b>。vanilla 第一人称之所以看不到自己，只是因为那时相机就是玩家本人。
     * <p>
     * 顺带纠正一个易犯的误判：fdlib 强制 {@code LocalPlayer#isControlledCamera()} 为 true
     * <b>并不影响渲染</b> —— 它只决定"还发不发玩家的移动/旋转包""input 是否灌进 xxa/zza"
     * "shift 是否控制垂直飞行"（{@code LocalPlayer:277/689/842}）。
     *
     * <h2>⚠️ 这条路的已知代价（用户 2026-09-30 实测后明确接受）</h2>
     * 走的是**原版隐身效果**，因此：
     * <ol>
     *   <li>效果会同步给其他玩家 ⇒ 这几秒里<b>别人也看不见你</b>；</li>
     *   <li>{@code isInvisible} 会改变<b>生物索敌</b>与 {@code isInvisibleTo} 的语义；</li>
     *   <li><b>它只隐藏"身体模型"这一层</b> —— {@code LivingEntityRenderer.render} 只对主模型做
     *       {@code isBodyVisible} 门控，而 {@code HumanoidArmorLayer} 与 {@code ItemInHandLayer}
     *       都<b>不检查隐身</b> ⇒ <b>盔甲 / 手持物 / 鞘翅照旧渲染</b>。
     *       机位在 t=0 恰在玩家眼位上，而 {@code armorCutoutNoCull} 不做背面剔除
     *       ⇒ 穿甲时可能看到"贴脸的盔甲几何"。<b>实机验收请脱甲、清空双手。</b></li>
     *   <li>若玩家已有一个<b>等级更高</b>的隐身，我们的实例会被挂进 {@code hiddenEffect}
     *       （{@code MobEffectInstance.update}）⇒ 那 130 tick 要等原效果结束后才生效，
     *       "6.5 秒后自动结束"不再是字面事实。极端边界，知道即可。</li>
     *   <li>若玩家所在队伍开了 {@code seeFriendlyInvisibles}，自己与自己必然同队 ⇒
     *       {@code isInvisibleTo} 为 false ⇒ 身体会以约 15% 不透明度渲染出来。原版默认不开启。</li>
     * </ol>
     * <p>
     * 换来的是"**零新增状态、零恢复逻辑**" —— 效果到点自动结束，我们不需要（也无法）知道 CG 何时结束。
     * 这与"用原版系统当状态存储、自己只做只读闸门"是同一路取舍。
     *
     * @see #viewerInvisibilityAmplifier()
     */
    protected int viewerInvisibilityTicks() {
        return 0;
    }

    /**
     * 隐身效果的等级（{@code amplifier}）。{@code 1} 即游戏内显示的"隐身 II"。
     *
     * @see #viewerInvisibilityTicks()
     */
    protected int viewerInvisibilityAmplifier() {
        return 0;
    }

    /**
     * **唯一的副作用出口**。顺序：预检 → 触发动画 → 上隐身 → 发过场包。
     * <p>
     * ⚠️ 这三步**不是原子的**：发包若抛异常，前两步已经生效（动画在播、人隐着），
     * 只是相机没被接管。所以这里的 catch 只保证"不把异常抛给命令层"，不做回滚。
     *
     * @param viewer 唯一会看到这条 CG 的玩家（用户 2026-09-30 裁定：只发给执行者）
     * @param target 演出主体；正常路径上已由命令层保证是 {@link NpcEntity}
     * @return 1 = 已下发；0 = **没有下发成功** —— 要么预检未过（真的什么都没发生），
     *         要么发包抛了异常（此时动画/隐身可能已经生效；两条都有英文 WARN）
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

        // ③ ★ 轨迹不可用 ⇒ fdlib 客户端会炸（见类注释第 3 条）。
        //    顺带守住 time <= 0：fdlib 的进度是 currentTime / cutsceneTime，
        //    为 0 会算出 NaN 机位。本 CG 恒为 120，这道闸防的是**将来的 CG 类**。
        if (data == null || data.getCameraPositions().isEmpty() || data.getCutsceneTime() <= 0) {
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] cg '{}' aborted: built an unusable CutsceneData (empty camera track, or time<=0 which "
                            + "makes fdlib's progress NaN; CutsceneCameraHandler also does "
                            + "getCameraPositions().getFirst() without a check)",
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

        // ⑤ 给观察者上隐身（可选，见 viewerInvisibilityTicks 的注释）。
        //
        //    ⚠️ 顺序保证只覆盖"**效果包**"本身：它与下面的 CG 包都走 player.connection.send(...)，
        //    同一条 Netty 连接 FIFO ⇒ 客户端先拿到效果、再拿到过场。
        //    但**渲染开关不是效果**：LivingEntityRenderer.isBodyVisible 读的是
        //    Entity.isInvisible()（同步实体数据的第 5 位），而服务端写这一位的地方
        //    （LivingEntity.updateInvisibilityStatus）在 tickEffects 里、只跑服务端；
        //    这一位由 ServerEntity.sendChanges() 发出，其调用点 ChunkMap.tick() 在
        //    ServerLevel.tick 里**早于**实体 tick ⇒ 最早要到**下一个服务端 tick** 才上链路。
        //    ⇒ 开头存在 ≥1 tick（约 2~3 帧）"效果已给、但身体仍会被画"的窗口。
        //    实机若真看到这一下，最省的缓解是在这里补一句 viewer.setInvisible(true)：
        //    它写同一个位、能挤进本 tick 的 sendChanges，而下一 tick 的
        //    updateInvisibilityStatus 会按效果重算 ⇒ 自愈、不引入持久状态。
        //    本轮**刻意不加**：t=0 的仰角是 +62.25°（大幅仰视）、机位又在玩家眼位上，
        //    那几帧几乎看不到自己的身体；且用户实测后已认可当前形态。
        int invisibilityTicks = this.viewerInvisibilityTicks();
        if (invisibilityTicks > 0) {
            boolean applied = viewer.addEffect(new MobEffectInstance(
                    MobEffects.INVISIBILITY,
                    invisibilityTicks,
                    this.viewerInvisibilityAmplifier(),
                    false,    // ambient
                    false,    // visible   —— 无粒子（用户明确要求）
                    false));  // showIcon  —— 这一位是**有作用的**：效果比 CG 长 10 tick
                              //              （6.5s > 6.0s）⇒ HUD 在 6.0 秒恢复后图标还会显约 0.5 秒，
                              //              设 false 才不会看到那个"隐身"图标
            if (!applied) {
                // NeoForge 的 MobEffectEvent.Added 可被取消、canMobEffectBeApplied 可返回 false
                // ⇒ 效果没上、人也隐不了。这种"什么都没发生"必须有痕迹。
                BeLoongCore.LOGGER.warn(
                        "[BeLoong] cg '{}': the viewer invisibility effect was rejected for {} "
                                + "(the viewer may see their own body during the cutscene)",
                        this.name(), viewer.getGameProfile().getName());
            }
        }

        // ⑥ 下发。**纳入 try** —— 它是最后一个副作用，若抛异常而我们不接，
        //    命令层会收到一个异常、而世界状态已经半变（见 play 的 javadoc）。
        try {
            FDLibCalls.startCutsceneForPlayer(viewer, data);
        } catch (Throwable t) {
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] cg '{}' failed to send the cutscene to {} (the animation/effect, if any, is already "
                            + "running and is NOT rolled back): {}",
                    this.name(), viewer.getGameProfile().getName(), t.toString());
            return 0;
        }

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
