package com.zonlong.beloong.entity.ai;

import com.zonlong.beloong.entity.NpcEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * 「原地转到 API 指定的朝向」——**只由 {@link NpcEntity#turnTo} 驱动的能力，默认永不触发**。
 * <p>
 * 触发方式是 API 写入目标角度；目标为 {@code null} 时 {@link #canUse()} 恒 false，
 * 所以外人不调 API 就永远不会动。
 * <p>
 * <b>刻意不占用任何 {@link Goal.Flag}（不是遗漏）</b>：{@code LookAtPlayerGoal} 占
 * {@code Flag.LOOK}，而 {@code GoalSelector} 用一张 {@code lockedFlags} 做互斥仲裁
 * （低优先级 goal 只要与正在运行的高优先级 goal 有 Flag 交集就**无法启动**）。
 * 若本 goal 占 LOOK，则只要玩家在跟随距离内，优先级更高的 look goal 就会让它
 * **永远启动不了** —— 显式转身指令被静默吃掉。
 * <p>
 * {@code yBodyRot} 不在这里写：原版 {@code LivingEntity#tickHeadTurn} 会以 0.3 插值
 * 自动让它追 {@code yRot}（{@code LivingEntity.java:2700-2714}），手写反而与它的夹取对冲。
 */
public class NpcTurnGoal extends Goal {

    /** 与目标的角差小于此值即认为到位（度）。 */
    private static final float ARRIVE_EPSILON = 1.0F;

    private final NpcEntity npc;

    public NpcTurnGoal(NpcEntity npc) {
        this.npc = npc;
    }

    @Override
    public boolean canUse() {
        return this.npc.getTurnTargetYaw() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.npc.getTurnTargetYaw() != null;
    }

    @Override
    public void tick() {
        Float target = this.npc.getTurnTargetYaw();
        if (target == null) {
            return;
        }

        float delta = Mth.wrapDegrees(target - this.npc.getYRot());
        if (Math.abs(delta) <= ARRIVE_EPSILON) {
            this.npc.setYRot(target);
            this.npc.clearTurnTarget();   // 到位即结束，goal 随之自然停止
            return;
        }

        float maxTurn = this.npc.maxTurnPerTick();
        this.npc.setYRot(this.npc.getYRot() + Mth.clamp(delta, -maxTurn, maxTurn));
    }
}
