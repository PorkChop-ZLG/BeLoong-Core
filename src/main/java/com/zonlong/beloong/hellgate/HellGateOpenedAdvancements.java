package com.zonlong.beloong.hellgate;

import com.zonlong.beloong.registry.ModCriteria;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 地狱之门开启后，向「在场者」发放进度判据的<b>薄编排层</b>。
 *
 * <p>本类的存在意义是：把「谁能拿到进度」这套口径**集中在一个文件里**，
 * 让移植件 {@code HellGateBlockEntity} 只多一行调用（保持可审计），
 * 将来要调半径、改过滤规则也只动这里。同类分层见
 * {@code dreadking.DreadKingRitualStarter}（死王仪式的编排层）。</p>
 *
 * <h2>发放口径（用户裁定，见设计文档 D21）</h2>
 * <ul>
 *   <li><b>半径 {@value #NEARBY_RANGE} 格</b>，球心取<b>基准格中心</b>
 *       （{@link Vec3#atCenterOf(BlockPos)}）。不取门的几何中心：门高 8 格，
 *       取几何中心会让竖直方向偏 4 格，在 32 格半径下这点差异可忽略，
 *       而基准格与「触发点所在的方块」一致、最好解释（D22）。</li>
 *   <li><b>只排除旁观</b> —— 创造模式<b>计入</b>。这是本项目第 2 次刻意不排除创造
 *       （第 1 次是死王仪式），理由是创造玩家同样在场目睹了门开启。</li>
 *   <li><b>左闭右开</b>：{@code distanceToSqr >= 32²} 即排除，与死王仪式
 *       （{@code >= 60²}）写法一致。</li>
 * </ul>
 * <p>⚠️ 半径与死王仪式的 <b>60 格</b> 不同：那边沿用铁魔法自己的口径，这边的 <b>32 格</b> 是用户裁定。
 * 两个数各有出处，不是笔误。</p>
 *
 * <h2>纯位置快照（用户裁定 D24）</h2>
 * 只在门开启完成的那一刻做**一次**空间快照，<b>不特判开门者</b>：
 * 开门动画长达 7.25 s（145 tick），若开门者在这段时间内跑出 32 格，他也拿不到。
 * 这与死王仪式的「死亡时刻在场者」同构，且零额外状态（不必在方块实体里存 UUID、
 * 不必考虑离线与是否落 NBT）。语义是「**目睹**开启」。</p>
 *
 * <h2>为什么必须遍历快照</h2>
 * {@code trigger()} 会执行进度的奖励函数，奖励函数里的跨维度传送或踢出会从
 * {@code ServerLevel#players()} 返回的列表里移除玩家 —— 那个列表既非副本也非不可变视图，
 * 直接遍历即抛 {@code ConcurrentModificationException}，而且异常会从方块实体的 tick
 * 一路抛穿。因此这里遍历 {@link List#copyOf} 得到的快照。
 * （这条纪律与 {@code DeadKingAdvancementHandler} 相同。）</p>
 *
 * <h2>调用方</h2>
 * {@code HellGateBlockEntity#tick} 在 {@code animationTicks >= TICK_FULLY_OPEN} 的
 * 服务端分支里、把 40 格全部置 {@code OPEN} <b>之后</b>调用 {@link #grant} ——
 * 即「门彻底开启、可以进入」之后才发。</p>
 */
public final class HellGateOpenedAdvancements {

    /**
     * 「在场」半径（格）。
     *
     * <p>出处：用户裁定「半径 32 格，只排除旁观」（设计文档 D21）。
     * 与死王仪式的 60 格**不同**，两者各有来源。</p>
     */
    private static final double NEARBY_RANGE = 32.0;

    private HellGateOpenedAdvancements() {}

    /**
     * 向门周围的玩家发放「见证地狱之门开启」判据。
     *
     * @param level   门所在的维度（必须是服务端；调用方已在 {@code !isClientSide} 分支内）
     * @param basePos 门的<b>基准格</b>（{@code PART=CENTER} 且 {@code Y_OFFSET=0} 那一格）
     */
    public static void grant(ServerLevel level, BlockPos basePos) {
        Vec3 center = Vec3.atCenterOf(basePos);
        double rangeSqr = NEARBY_RANGE * NEARBY_RANGE;

        // 快照迭代，理由见类 javadoc「为什么必须遍历快照」。
        for (ServerPlayer player : List.copyOf(level.players())) {
            // 只排除旁观；创造模式计入（本项目有意口径，见类 javadoc）。
            if (player.isSpectator()) {
                continue;
            }
            if (player.distanceToSqr(center) >= rangeSqr) {
                continue;
            }
            ModCriteria.HELL_GATE_OPENED.get().trigger(player);
        }
    }
}
