package com.zonlong.beloong.entity.ai;

import com.zonlong.beloong.entity.NpcEntity;
import com.zonlong.beloong.route.NpcRoute;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 路线 goal —— 把"当前路点"交给 {@link NpcEntity} 的移动目标。
 * <p>
 * 设计：{@code docs/plans/2026-09-30-npc-route-system-design.md}。
 *
 * <h2>它<b>只做两件事</b>：设目标、以及在终点播可选表情</h2>
 * 真正的寻路一行都不在这里 —— {@code NpcEntity#tickMoveCommand()} 每 20 tick 用
 * {@code moveTarget} 续一次路（既有机制）。本类只负责"当前该去哪个路点"，
 * 以及走完后按 {@code NpcRoute#endEmote()}（可选）播一个表情。
 *
 * <h2>⚠️ 只占 {@code MOVE}、<b>不占</b> {@code LOOK}（两件事都很关键）</h2>
 * <ul>
 *   <li><b>占 {@code MOVE}</b> ⇒ 与同样占 {@code MOVE} 的 {@code NpcAttackGoal}（{@code MeleeAttackGoal} 子类）
 *       **天然互斥**：攻击 goal 一启动，原版 {@code GoalSelector} 的 {@code lockedFlags} 就把本 goal
 *       {@code stop()} 掉 ⇒ <b>"攻击期间暂停路线、打完自动继续"零代码</b>（用户裁定 D7）。
 *       依据见 {@code NpcEntity} 里那段 {@code lockedFlags} 说明。</li>
 *   <li><b>不占 {@code LOOK}</b> ⇒ 与 {@code LookAtPlayerGoal}（占 {@code LOOK}）无 Flag 交集 ⇒
 *       两者可并行，NPC 边走边看玩家的既有行为不受影响。
 *       （反面教训：{@code NpcEntity} 的注释记着"攻击 goal 之所以排在 3 而不是更后，
 *       就是为了不被 look goal 挡死" —— 若本 goal 顺手占了 {@code LOOK} 就会重演那个坑。）</li>
 * </ul>
 *
 * <h2>⚠️ {@link #stop()} 只停寻路，<b>绝不清路线</b></h2>
 * 被攻击 goal 抢占（或维度变化、抵达终点）时原版都会调 {@code stop()} ——
 * 那里必须<b>只</b>清移动目标，路线名与下标原样留着，否则一场遭遇战就把向导任务毁掉了（用户裁定 D6/D7）。
 *
 * <h2>⚠️ 什么时候可以调 {@code moveTo}</h2>
 * {@code NpcEntity#moveTo} 第一句就 {@code clearEmote()}（表情会被移动指令清掉是既有的、刻意的语义）
 * ⇒ 不能每 tick 无脑调（那会每 tick 清一次表情）。只在**三种时刻**下发：
 * <ol>
 *   <li>{@link #start()} —— goal 刚开始跑（含被攻击抢占后重新开跑）；</li>
 *   <li><b>路点推进时</b>（见 {@link #tick()}）；</li>
 *   <li><b>目标已经不属于我们时</b> —— 即 {@code NpcEntity#moveTarget()} 为 {@code null}、
 *       或它已不等于当前路点。</li>
 * </ol>
 *
 * <h2>⚠️ 第 3 条是 2026-09-30 一个实机 bug 的修复，别删</h2>
 * 现象：在龙宫里寻路途中把 NPC {@code /tp} 到同维度的别处 ⇒ <b>永久停住</b>；
 * 而且**重新指派路线也救不回来**，只有 {@code reset} 再指派才恢复。
 * <p>
 * 根因是"两个层都以为自己拥有 {@code moveTarget}"：
 * <ul>
 *   <li>{@code NpcEntity#tickMoveCommand()} 有一条<b>有界失败</b>：连续
 *       {@code MOVE_MAX_NO_PROGRESS}（5）次续路都没有更靠近目标 ⇒ 判定不可达，
 *       把 {@code moveTarget} 清成 {@code null} 并停导航（日志是 DEBUG 级的
 *       {@code npc move target unreachable, giving up at … for …}）。对一次性的 {@code move}
 *       指令这是**正确**设计，但电话线另一头的路线并不知道；</li>
 *   <li>而本 goal 那时<b>仍在运行</b>（{@code canUse} 依旧为真：有路线、维度对、下标未越界），
 *       原版 {@code GoalSelector} <b>不会</b>再调它的 {@code start()} ⇒ 若不在这里补发，
 *       就再也没有任何一处会下发目标了。</li>
 * </ul>
 * 「重新指派路线也没用」同样是这条：{@code NpcEntity#setRoute} 只把下标归零，
 * goal 从未停止过 ⇒ 原版不会重新 {@code start()}。而 {@code reset} 会走
 * {@code clearRoute()} ⇒ {@code canUse} 变 false ⇒ goal <b>真的停掉</b> ⇒ 再指派时
 * {@code start()} 被调用 ⇒ 恢复 —— 三个症状因此全部吻合。
 * <p>
 * 补发还有个**副作用是想要**的：{@code moveTo} 会重置那条有界失败的计数
 * （{@code setMoveTarget} 里清 {@code moveBestDistSqr}/{@code moveNoProgressCount}）
 * ⇒ 于是"走不回去就**一直试**"（设计 D3 的原话）真正成立，每约 5 秒重试一轮。
 */
public class NpcRouteGoal extends Goal {

    /**
     * 抵达判定的 Y 容差（格）。
     * <p>
     * 水平距离用路线自己的 {@code arrival_radius}，但 Y 不能直接用半径判：路点写的是**方块坐标**，
     * 而实体的 {@code getY()} 是**脚底**高度 ⇒ 站在 {@code y=64} 那一格上的 NPC，脚底是 65。
     * 给 2 格容差既覆盖这个偏移、也容忍楼梯/半砖的差异。
     */
    private static final double Y_TOLERANCE = 2.0D;

    private final NpcEntity npc;

    public NpcRouteGoal(NpcEntity npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /**
     * 能跑吗：有路线 ∧ 数据已加载 ∧ 维度匹配 ∧ 尚未抵达。
     * <p>
     * ⚠️ 维度不匹配 ⇒ <b>false</b>（挂起）；路线数据缺失 ⇒ <b>false</b>（挂起，且已由
     * {@code setRoute} 打过 WARN）。两者都是"停下但保留路线"，不是"完成任务"。
     */
    @Override
    public boolean canUse() {
        NpcRoute route = this.npc.route();
        if (route == null) {
            return false;
        }
        if (!this.npc.level().dimension().location().equals(route.dimension())) {
            return false;
        }
        return this.npc.routeIndex() < route.size();
    }

    @Override
    public boolean canContinueToUse() {
        return this.canUse();
    }

    @Override
    public void start() {
        this.moveToCurrentWaypoint();
    }

    @Override
    public void tick() {
        NpcRoute route = this.npc.route();
        Vec3 target = this.npc.routeWaypoint();
        if (route == null || target == null) {
            return;
        }
        double horizontal = Math.hypot(this.npc.getX() - target.x, this.npc.getZ() - target.z);
        // 抵达半径取"路线声明值"与"移动层到位半径 + 0.5"的较大者：
        // 若路线的值更小，移动层会先判到位并清掉目标，而这里又判没到、立刻补发 ⇒ 每 tick 打架
        // （并每 tick 清一次表情）。ground arrive distance 是 1.0 ⇒ 下限 1.5 格。
        double effective = Math.max(route.arrivalRadius(), NpcEntity.groundArriveDistance() + 0.5D);
        boolean arrived = horizontal <= effective
                && Math.abs(this.npc.getY() - target.y) <= Y_TOLERANCE;
        // ⚠️ **最后一个路点**要再等"移动层真的停下"（它判到位时会把 moveTarget 清成 null）。
        // 因为本 goal 的抵达半径（路线声明值，本次数据是 2.0）比移动层的到位半径（1.0）宽：
        // 若一到 2 格内就算抵达并播表情，{@code sit} 这类坐姿会在**还差最后一两格**时开始播
        // ⇒ 看起来像"坐着滑行"。中间路点**不**这样等（否则每个路点都会顿一下）。
        // 不会因此卡死：万一移动层到不了，它的有界失败会在约 5 秒后清掉 moveTarget，
        // 那时 arrived 成立、路线正常收尾。
        if (arrived && this.isLastWaypoint(route) && this.npc.moveTarget() != null) {
            arrived = false;
        }
        if (arrived) {
            this.npc.advanceRouteIndex();
            if (this.npc.routeFinished()) {
                // 终点：按数据播可选表情（没有就什么都不做 ⇒ 老数据行为不变）。
                route.endEmote().ifPresent(this.npc::setEmote);
                return;
            }
            this.moveToCurrentWaypoint();
            return;
        }

        // 目标不再属于我们 ⇒ 补发（见类注释"第 3 条是实机 bug 的修复，别删"）。
        // 这一条让"被 tp 走 / 被有界失败放弃 / 被 move 指令顶掉"三种情况都能自愈。
        Vec3 current = this.npc.moveTarget();
        if (current == null || current.distanceToSqr(target) > 1.0E-6D) {
            this.moveToCurrentWaypoint();
        }
    }

    /** 只停寻路、**不清路线** —— 见类注释。 */
    @Override
    public void stop() {
        this.npc.stopMoving();
    }

    /** 当前路点是不是这条路的最后一个。 */
    private boolean isLastWaypoint(NpcRoute route) {
        return this.npc.routeIndex() == route.size() - 1;
    }

    private void moveToCurrentWaypoint() {
        Vec3 target = this.npc.routeWaypoint();
        if (target != null) {
            this.npc.moveTo(target);
        }
    }
}
