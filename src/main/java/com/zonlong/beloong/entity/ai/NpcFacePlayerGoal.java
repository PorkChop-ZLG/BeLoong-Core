package com.zonlong.beloong.entity.ai;

import com.zonlong.beloong.entity.NpcEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * 「玩家靠近时，把身体转过去」—— 常驻的自主旋转来源。
 * <p>
 * 与 {@code LookAtPlayerGoal} 的分工：那个只动 {@code yHeadRot}（头 / 颈，进而喂给 Molang
 * 的 {@code query.head_yaw}），本 goal 只动 {@code yRot}（身体）。两者互不触碰对方的量，
 * 因此可以**同时运行** —— 效果是**头先转过去、身体随后慢慢跟上**
 * （look 响应快，而 {@code yBodyRot} 只有 0.3 的插值）。
 * <p>
 * <b>刻意不占用任何 {@link Goal.Flag}</b>，理由同 {@link NpcTurnGoal}：
 * 占用 {@code Flag.LOOK} 会被优先级更高的 {@code LookAtPlayerGoal} 挡死，身体就永远不转。
 * <p>
 * <b>外部指令优先</b>：有转身 / 攻击 / 寻路在进行时整条让位
 * （见 {@link NpcEntity#isExternallyCommanded()}）—— 既保证显式指令生效，
 * 也避免"边走边转身体"与寻路抢 {@code yRot}。
 */
public class NpcFacePlayerGoal extends Goal {

    private final NpcEntity npc;

    /** 排除旁观者：复用原版的目标筛选条件。 */
    private final TargetingConditions targetConditions;

    @Nullable
    private Player player;

    public NpcFacePlayerGoal(NpcEntity npc) {
        this.npc = npc;
        this.targetConditions = TargetingConditions.forNonCombat().range(npc.facePlayerDistance());
    }

    @Override
    public boolean canUse() {
        if (this.npc.isExternallyCommanded()) {
            return false;
        }
        this.player = this.npc.level().getNearestPlayer(
                this.targetConditions, this.npc, this.npc.getX(), this.npc.getEyeY(), this.npc.getZ());
        return this.player != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.npc.isExternallyCommanded() || this.player == null || !this.player.isAlive()) {
            return false;
        }
        float distance = this.npc.facePlayerDistance();
        return this.npc.distanceToSqr(this.player) <= (double) (distance * distance);
    }

    @Override
    public void stop() {
        this.player = null;
    }

    @Override
    public void tick() {
        if (this.player == null) {
            return;
        }
        // 与原版 Mob#lookAt 同一条公式：yaw = atan2(dz, dx) * 180/PI - 90
        float target = (float) (Mth.atan2(
                this.player.getZ() - this.npc.getZ(),
                this.player.getX() - this.npc.getX()) * (180.0D / Math.PI)) - 90.0F;

        float delta = Mth.wrapDegrees(target - this.npc.getYRot());
        float maxTurn = this.npc.maxTurnPerTick();
        this.npc.setYRot(this.npc.getYRot() + Mth.clamp(delta, -maxTurn, maxTurn));
    }
}
