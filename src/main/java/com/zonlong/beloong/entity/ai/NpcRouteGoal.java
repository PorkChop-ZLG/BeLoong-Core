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
 * <h2>它<b>只做一件事</b>：设目标</h2>
 * 真正的寻路一行都不在这里 —— {@code NpcEntity#tickMoveCommand()} 每 20 tick 用
 * {@code moveTarget} 续一次路（既有机制）。本类只负责"当前该去哪个路点"。
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
 * <h2>⚠️ 绝不在每 tick 调 {@code moveTo}</h2>
 * {@code NpcEntity#moveTo} 第一句就 {@code clearEmote()}（表情会被移动指令清掉是既有的、刻意的语义）
 * ⇒ 每 tick 调就等于每 tick 清一次表情。故**只在下标变化时与 {@code start()} 时**下发。
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
        if (horizontal <= route.arrivalRadius()
                && Math.abs(this.npc.getY() - target.y) <= Y_TOLERANCE) {
            this.npc.advanceRouteIndex();
            // 仅在此处（下标变化）重新下发 —— 见类注释"绝不在每 tick 调 moveTo"。
            this.moveToCurrentWaypoint();
        }
    }

    /** 只停寻路、**不清路线** —— 见类注释。 */
    @Override
    public void stop() {
        this.npc.stopMoving();
    }

    private void moveToCurrentWaypoint() {
        Vec3 target = this.npc.routeWaypoint();
        if (target != null) {
            this.npc.moveTo(target);
        }
    }
}
