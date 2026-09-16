package com.zonlong.beloong.compat.dragonsurvival;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 「在爪牙槽中的剑换入主手期间击杀生物」进度判据。
 *
 * <p>与 {@link ClawSwordSwapTrigger} 的区别：那个在<b>换手发生时</b>触发，这个在
 * <b>换手仍生效时造成击杀</b>时才触发。二者构成「先换手 → 后击杀」的两级教学进度。</p>
 *
 * <p><b>为什么不需要「先换手」的门控代码</b>：击杀条件本身（{@code switchedTool == true}）
 * 已经蕴含了换手正在发生。而且换手判据挂在 {@code LivingIncomingDamageEvent}（位于
 * {@code LivingEntity#hurt} 内部，第 1153 行附近），击杀判据挂在 {@code LivingDeathEvent}
 * （同一方法的 {@code die()} 调用内，第 1266 行附近）——<b>同一伤害实例内前者必然先于后者</b>，
 * 因此进度树不会出现子进度先于父进度完成的断链。</p>
 *
 * <p>覆盖范围刻意<b>不限于近战攻击</b>：带 {@code use_claw} 的龙技能同样会把剑换入主手，
 * 其击杀也计入——这条路径的教学意义与近战一致。</p>
 */
public class ClawSwordKillTrigger extends SimpleCriterionTrigger<ClawSwordKillTrigger.Instance> {

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
