package com.zonlong.beloong.mixin.darkdoppelganger;

import com.zonlong.beloong.BeLoongCore;
import net.bandit.darkdoppelganger.entity.DarkDoppelgangerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 黑暗二重身（Dark Doppelganger）：把「阶段切换时在自己的 goal 迭代内重建 {@code goalSelector}」
 * <b>推迟到本次 tick 末尾</b>，从而消除服务端 {@code Ticking entity} 崩溃。
 *
 * <p><b>要修的是什么</b>：{@code DarkDoppelgangerEntity.hurt(DamageSource, float)} 在跨过血量阈值时
 * （{@code newHealth <= getMaxHealth() * 0.4f} / {@code 0.2f}）会先做可见的阶段切换
 * （{@code triggerSecondPhase()} / {@code triggerThirdPhase()}：满血、改名、怒吼、爆炸、10 道闪电、致盲），
 * <b>紧接着在同一处</b>调用 {@code setSecondPhaseGoals()} / {@code setThirdPhaseGoals()} /
 * {@code setFinalPhaseGoals()}。这三个方法每次都会做：
 * <pre>
 *   goalSelector.getAvailableGoals().forEach(WrappedGoal::stop);
 *   goalSelector.removeAllGoals(x -&gt; true);
 *   goalSelector.addGoal(...)  × 若干
 * </pre>
 * 而 {@code GoalSelector.availableGoals} 是 fastutil 的 {@code ObjectLinkedOpenHashSet}（<b>非 fail-fast</b>）
 * ⇒ 若此刻该集合<b>正在被 {@code GoalSelector.tickRunningGoals} 的 for-each 遍历</b>，遍历状态与哈希表结构
 * 就会错位，随后读到 null 槽 ⇒
 * {@code NullPointerException: Cannot invoke "WrappedGoal.isRunning()" because "wrappedgoal" is null}。
 *
 * <p><b>什么情况下 {@code hurt} 会落在遍历内部</b>：boss 的近战 goal 本身就在
 * {@code tickRunningGoals} 的 for-each 里执行 ⇒ 它攻击玩家 ⇒ 玩家装备的<b>荆棘 III</b> 反伤打回 boss
 * ⇒ 这个 {@code hurt} 就跑在同一次遍历内部。自伤 TNT / 自己召唤的闪电同理（所以"只堵荆棘"不是根因修复）。
 * 实机复现与崩溃报告见
 * {@code docs/plans/2026-10-13-darkdoppelganger-phase-goal-rebuild-design.md}（§1）。
 *
 * <p><b>为什么只拦 {@code hurt} 这一个调用者</b>：{@code set*Goals} 的调用点只有三类 ——
 * {@code registerGoals()}（构造期，安全）、{@code onAddedToLevel()} → {@code restorePhaseGoals()}（入世界期，
 * 安全）、以及 {@code hurt()}（<b>唯一会落在迭代内的</b>）。因此本 Mixin 的三个 {@code @Redirect} 都限定
 * {@code method = "hurt(...)"}，把初始化与读档恢复两条路径<b>完全交给上游原样处理</b>。
 *
 * <p><b>为什么应用点用 {@code @At("RETURN")} 而不是 {@code "TAIL"}</b>：{@code tick()} 的第一句就是
 * {@code super.tick()}（AI 步在其中跑完），但方法里还有早期 {@code return}（死亡 / 分身分支）——
 * {@code RETURN} 会挂在每一个 return 之前，任何路径都会应用；而每条路径都已越过 {@code super.tick()}
 * ⇒ 应用点<b>必然在 goal 迭代之外</b>。（实测 return 指令数：4.1.0 为 3、3.4.0 为 2。）
 *
 * <p><b>为什么刻意不写 {@code allow = N}</b>：{@code hurt} 里对 {@code setThirdPhaseGoals()} 有<b>两处</b>
 * 调用（困难模式下的二阶段 / 普通模式下的三阶段），一个 {@code @Redirect} 默认就会包住该方法内全部候选指令。
 * 一旦把上限写死，上游将来多出一处调用就会从"无害地多包一层"变成<b>启动期硬崩</b>。
 *
 * <p><b>版本覆盖</b>：整包在跑的 3.4.0 与 dev/最新的 4.1.0，三处调用点位置与
 * {@code protected void set...PhaseGoals()} 签名<b>完全一致</b>（{@code javap -p -c} + 常量池 Methodref 已核对）；
 * 两版 {@code hurt} 的字节码不同，故本 Mixin 只锚<b>方法名</b>、绝不锚 {@code hurt} 内部位置。
 *
 * <p><b>可选依赖处理</b>：{@code @Pseudo} + 每个注入点 {@code require = 0} —— 未安装该模组（或上游改名/移动
 * 调用点）时本 Mixin 整体跳过，不会硬崩。⚠️ 代价是<b>静默失效</b>：那时阶段切换会照旧崩。为此每完成一次延后
 * 都会打一条 INFO 锚点日志；<b>实机跨阶段时看不到它，就说明本修复已失效</b>，需按新字节码重新锚定
 * （见设计文档 §7「升级必查项」）。
 *
 * <p><b>语义</b>：阶段切换的可见效果<b>照旧在受伤当刻发生</b>，只有 goal 列表晚到本 tick 末尾
 * （下一次 {@code goalSelector.tick()} 之前一定换好）。玩家侧观感无差别。
 */
@Pseudo
@Mixin(value = DarkDoppelgangerEntity.class, remap = false)
public abstract class DarkDoppelgangerPhaseGoalRebuildMixin {

    /** 待应用的阶段 goal：{@code 0} = 无，{@code 2}/{@code 3}/{@code 4} = 二/三/终阶段。实例字段 ⇒ 多个 boss 互不干扰。 */
    @Unique
    private int beloong$pendingPhaseGoals;

    // 这三个方法在两版里都是 protected void（javap 已核对）；下面只借它们真正执行重建。
    @Shadow
    protected abstract void setSecondPhaseGoals();

    @Shadow
    protected abstract void setThirdPhaseGoals();

    @Shadow
    protected abstract void setFinalPhaseGoals();

    /**
     * 截获 {@code hurt} 里"切到二阶段 goal"的那次调用：只登记待办，不在遍历中改集合。
     *
     * @param self 目标实体（{@code invokevirtual} 的接收者；本 handler 不使用，仅为签名匹配）
     */
    @Redirect(
            method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/bandit/darkdoppelganger/entity/DarkDoppelgangerEntity;setSecondPhaseGoals()V"
            ),
            require = 0,
            remap = false
    )
    private void beloong$deferSecondPhaseGoals(DarkDoppelgangerEntity self) {
        this.beloong$pendingPhaseGoals = 2;
    }

    /**
     * 截获 "切到三阶段 goal" 的调用。<b>{@code hurt} 里有两处调用它</b>（困难模式的二阶段分支 /
     * 普通模式的三阶段分支），一个 {@code @Redirect} 默认包住全部候选指令；两处语义相同
     * （都表示"接下来用三阶段 goal"），故共用本 handler。
     */
    @Redirect(
            method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/bandit/darkdoppelganger/entity/DarkDoppelgangerEntity;setThirdPhaseGoals()V"
            ),
            require = 0,
            remap = false
    )
    private void beloong$deferThirdPhaseGoals(DarkDoppelgangerEntity self) {
        this.beloong$pendingPhaseGoals = 3;
    }

    /** 截获 {@code hurt} 里"切到终阶段 goal"的那次调用（困难模式的三阶段分支）。 */
    @Redirect(
            method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/bandit/darkdoppelganger/entity/DarkDoppelgangerEntity;setFinalPhaseGoals()V"
            ),
            require = 0,
            remap = false
    )
    private void beloong$deferFinalPhaseGoals(DarkDoppelgangerEntity self) {
        this.beloong$pendingPhaseGoals = 4;
    }

    /**
     * 在本次 tick 的末尾（已在 {@code super.tick()} 之后 ⇒ 必在 goal 迭代之外）应用被推迟的重建。
     *
     * <p>同一 tick 内若连跨两档，标记会被后写覆盖 ⇒ 只应用<b>更高阶段</b>的 goal（低阶段本就作废）。
     *
     * @param ci 回调信息（{@code @Inject} 的处理器<b>必须</b>以此结尾，即使不使用也不能省）
     */
    @Inject(method = "tick()V", at = @At("RETURN"), require = 0, remap = false)
    private void beloong$applyDeferredPhaseGoals(CallbackInfo ci) {
        int phase = this.beloong$pendingPhaseGoals;
        if (phase == 0) {
            return;
        }
        this.beloong$pendingPhaseGoals = 0;

        switch (phase) {
            case 2 -> this.setSecondPhaseGoals();
            case 3 -> this.setThirdPhaseGoals();
            case 4 -> this.setFinalPhaseGoals();
            default -> {
                return; // 不可达：仅为穷尽性
            }
        }

        // 锚点：既证明"注入真的生效"，也可用来评估延后发生了多少次；失效时这条日志会消失。
        BeLoongCore.LOGGER.info("[BeLoong] darkdoppelganger: deferred phase-{} goal rebuild to end of tick", phase);
    }
}
