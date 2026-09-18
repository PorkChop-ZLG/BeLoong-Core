package com.zonlong.beloong.compat.ironsspellbooks;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 「参与击败<b>普通</b>状态死者之王」进度判据。
 *
 * <h3>为什么不直接用原版 {@code player_killed_entity}</h3>
 * 原版归功链路（{@code LivingEntity#die} → {@code getKillCredit()} →
 * {@code ServerPlayer#awardKillScore} → {@code CriteriaTriggers.PLAYER_KILLED_ENTITY}）
 * 有<b>三处</b>会导致漏判：
 * <ol>
 *   <li><b>只归功给一个实体</b> —— {@code getKillCredit()} 返回单个
 *       {@code lastHurtByPlayer ?: lastHurtByMob ?: null} ⇒ 多人时只有"最后打到他"的人拿得到；</li>
 *   <li><b>5 秒没打中就丢归功</b> —— {@code lastHurtByPlayerTime} 仅 100 tick，
 *       {@code baseTick} 递减归零即清空 {@code lastHurtByPlayer}；</li>
 *   <li><b>非玩家致命一击时无人获得</b> —— 归功退化为 {@code lastHurtByMob} 甚至 {@code null}
 *       （仅<b>驯服宠物</b>会把主人记为 {@code lastHurtByPlayer}）。</li>
 * </ol>
 *
 * <p>死者之王有 <b>139 tick 的无敌转换期</b>与以远程为主的<b>飞行第二阶段</b>
 * （近战倾向从 0.8 降至 0.3），玩家极易连续 5 秒打不到他 ⇒ 第 2 条尤其致命。
 * 本判据改为在「<b>死亡时刻</b>」按空间快照发放，详见 {@link DeadKingAdvancementHandler}。</p>
 *
 * <p><b>注意不是误报</b>：50% 血时的"假死"只把血量设回半血并置无敌、进入
 * {@code Transitioning}，<b>不调 {@code die()}</b>，所以不会在假死时误发。</p>
 *
 * <p>注册名为 {@code beloong:dead_king_kill}，即数据包在进度 JSON 里书写的形式
 * （{@code "trigger": "beloong:dead_king_kill"}）。<b>改名会让已有进度失效。</b></p>
 *
 * @see OminousDeadKingKillTrigger 不祥变种
 * @see DeadKingAdvancementHandler 触发方
 */
public class DeadKingKillTrigger extends SimpleCriterionTrigger<DeadKingKillTrigger.Instance> {

    /** 供事件处理器在判定成立时调用。 */
    public void trigger(ServerPlayer player) {
        this.trigger(player, instance -> true);
    }

    @Override
    public @NotNull Codec<Instance> codec() {
        return Instance.CODEC;
    }

    /**
     * 判据实例。
     *
     * @param player 标准玩家谓词，可选
     */
    public record Instance(Optional<ContextAwarePredicate> player)
            implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player)
        ).apply(instance, Instance::new));
    }
}
