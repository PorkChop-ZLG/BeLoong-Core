package com.zonlong.beloong.entity.ai;

import com.zonlong.beloong.entity.NpcEntity;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

/**
 * 攻击 goal —— 以 {@link NpcEntity#attack} 的命令标志位把关。
 * <p>
 * <b>为什么不直接注册原版 {@code MeleeAttackGoal}</b>：任何来源只要给实体设了 {@code target}
 * （别的模组的索敌、将来的 goal）它就会开打，那就不是"攻击只作 API"了。这里给
 * {@code canUse()} / {@code canContinueToUse()} 都加上"必须有攻击命令"的前置条件。
 * <p>
 * <b>优先级必须比 {@code LookAtPlayerGoal} 更靠前</b>（基类里注册为 3，look goal 是 5）：
 * {@code MeleeAttackGoal} 构造器里 {@code setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK))}，
 * 而 look goal 占 LOOK；{@code GoalSelector} 用 {@code lockedFlags} 仲裁 ⇒ 若本 goal 优先级更低，
 * "玩家在跟随距离内"时它会被 look goal **永久挡死**，永远打不到人。
 * <p>
 * 伤害由 {@code Mob#doHurtTarget} 读 {@code ATTACK_DAMAGE} 得出（基类默认 100）。
 * <p>
 * 注意原版 {@code canUse()} 有 <b>20 tick 限流</b>（{@code i - lastCanUseCheck < 20L → false}）：
 * 下令后最多可能等 1 秒才起步。
 */
public class NpcAttackGoal extends MeleeAttackGoal {

    private final NpcEntity npc;

    public NpcAttackGoal(NpcEntity npc, double speedModifier, boolean followingTargetEvenIfNotSeen) {
        super(npc, speedModifier, followingTargetEvenIfNotSeen);
        this.npc = npc;
    }

    @Override
    public boolean canUse() {
        return this.npc.isAttackCommandActive() && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return this.npc.isAttackCommandActive() && super.canContinueToUse();
    }

    @Override
    public void stop() {
        super.stop();
        this.npc.clearAttackCommand();
    }
}
