package com.zonlong.beloong.compat.ironsspellbooks;

import com.zonlong.beloong.registry.ModCriteria;
import io.redspace.ironsspellbooks.entity.mobs.dead_king_boss.DeadKingBoss;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.List;

/**
 * 死者之王死亡时，向范围内玩家发放<b>对应变种</b>的击杀进度。
 *
 * <h3>为什么挂 {@code LivingDeathEvent} 而不是 mixin</h3>
 * {@code DeadKingBoss} <b>没有覆写 {@code Entity#die()}</b>（它只覆写了 {@code tickDeath()}），
 * 而本事件正是由 {@code CommonHooks#onLivingDeath} 在 {@code LivingEntity#die} 的<b>开头</b>派发
 * （NeoForge 21.1.236 反编译源 {@code LivingEntity.java:1408-1409}）⇒ 此时实体完全有效、
 * {@code isOminous()} 可读。因此本功能<b>零 mixin</b>。
 *
 * <h3>为什么不复用铁魔法自己的「参与者」名单</h3>
 * 铁魔法确实维护了一份参与者（{@code loot/BossLootHandler#participantIds}，用于每人掉落），
 * 但本设计<b>刻意不用</b>它，两条理由：
 * <ol>
 *   <li><b>语义相反</b>：{@code setParticipantsFromPlayers} <b>只在 {@code finalizeSpawn()}
 *       调用一次</b>（{@code DeadKingBoss.java:310}），所以参与者 = 「<b>生成时刻</b>在 60 格内」；
 *       而本功能要的是「<b>死亡时刻</b>在场」。对"打一半才加入的玩家"，两者结论相反。</li>
 *   <li><b>会引入脆弱依赖</b>：{@code DeadKingBoss.bossLoot} 是 {@code private final}，
 *       而 {@code BossLootHandler} <b>没有任何 getter</b>（公开方法只有
 *       {@code setParticipants}/{@code setParticipantsFromPlayers}/{@code prepareDrops}/
 *       {@code spawnPreparedDrops}/{@code save}/{@code load}）。唯一零 mixin 的读法是先
 *       {@code Entity#saveWithoutId} 序列化，再去读私有 NBT 键 {@code boss_loot_participants}
 *       —— 等于硬编码两个私有常量，上游改名即<b>静默拿到空名单</b>。</li>
 * </ol>
 * ⇒ 改为在死亡时刻用铁魔法自己的口径<b>重算</b>：半径 60 格。
 *
 * <p><b>唯一的有意偏离（用户裁定）</b>：本实现<b>不排除创造模式玩家</b>，只排除旁观。
 * 注意由此产生的一处不对称 —— 创造模式玩家会拿到进度，但<b>仍不会</b>进铁魔法自己的
 * "每人掉落"名单（{@code finalizeSpawn} 那边继续排除创造）⇒ 可能出现"有进度、无掉落"。
 * 这是刻意接受的。</p>
 *
 * <h3>只依赖公开 API</h3>
 * 变种判定用的是 {@code boss.isOminous()} —— 它声明在公开接口
 * {@code io.redspace.ironsspellbooks.api.entity.IOminousEntity} 上，
 * 因此本功能<b>不碰铁魔法任何私有实现</b>。
 *
 * <h3>取消语义（刻意不声明 {@code receiveCanceled}，但覆盖不完整）</h3>
 * {@code LivingDeathEvent} <b>可被取消</b>（{@code CommonHooks#onLivingDeath} 的返回值就是
 * {@code isCanceled()}）。本处理器<b>刻意</b>不声明 {@code receiveCanceled}，因此<b>已被取消</b>
 * 的事件不会送到这里 —— 死亡既已被别的模组阻止，就不该发放进度。
 *
 * <p><b>但这只覆盖「先取消、后送达」这一种顺序。</b>事件按优先级
 * {@code LOWEST → LOW → NORMAL → HIGH → HIGHEST} 派发，本处理器用默认 {@code NORMAL}；
 * 若有模组在 {@code HIGH}/{@code HIGHEST} 才取消死亡，本处理器<b>已经先跑完并发放了进度</b>。
 * 已知模组里没有对死者之王这么做的东西，故不为此加优先级或自检 {@code isCanceled()} 的复杂度。</p>
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>{@code dead_king_soul} / {@code dead_king_corpse} 是<b>另外两个实体类型</b>，
 *       {@code instanceof DeadKingBoss} 天然排除；</li>
 *   <li>Boss 被 {@code /kill} 或环境/召唤物杀死时<b>照样发放</b> —— 这正是本特性的目的
 *       （原版只看 {@code lastHurtByPlayer}，非玩家致死会漏）；</li>
 *   <li>Boss 复活后再杀会<b>再次</b>触发判据，但进度本身只会授予一次；</li>
 *   <li>若没有任何进度引用该判据，{@code SimpleCriterionTrigger#trigger} 会<b>静默 no-op</b>
 *       （listener 集为空）—— 这是原版行为，无害，但会让测试"什么都看不到"。
 *       开发期曾用两条<b>临时示例进度</b>（{@code data/beloong/advancement/dead_king/}）验证，
 *       实机通过后<b>已删除</b>；本模组只提供判据，真实进度由整合包侧编写。</li>
 * </ul>
 */
public class DeadKingAdvancementHandler {

    /**
     * 参与判定半径的平方（60 格）。
     *
     * <p>半径取值与铁魔法自己的 {@code DeadKingBoss#finalizeSpawn} 一致：
     * {@code player.distanceToSqr(this) < 3600}。人员过滤条件见
     * {@link #onDeath(LivingDeathEvent)} 内的说明。</p>
     */
    private static final double PARTICIPANT_RANGE_SQR = 3600.0;

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof DeadKingBoss boss)) {
            return;
        }
        // 防御性判断：本事件对客户端 Level 无意义（客户端没有服务端玩家列表）。
        if (!(boss.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        boolean ominous = boss.isOminous();

        // 迭代快照，不直接迭代 players() 的返回值：ServerLevel#players() 把内部活列表原样返回
        // （ServerLevel.java:1438-1440，既非副本也非不可变视图），而下面的 trigger() 会执行进度
        // 的奖励函数 —— 奖励函数里的跨维度传送或踢出会从该列表移除玩家，直接迭代即抛
        // ConcurrentModificationException，并把异常从 LivingDeathEvent 一路抛穿 LivingEntity#die()。
        for (ServerPlayer player : List.copyOf(serverLevel.players())) {
            // 只排除旁观。创造模式计入 —— 本项目有意偏离铁魔法口径（那边排除创造），见类 javadoc。
            if (player.isSpectator()) {
                continue;
            }
            if (player.distanceToSqr(boss) >= PARTICIPANT_RANGE_SQR) {
                continue;
            }

            if (ominous) {
                ModCriteria.OMINOUS_DEAD_KING_KILL.get().trigger(player);
            } else {
                ModCriteria.DEAD_KING_KILL.get().trigger(player);
            }
        }
    }
}
