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
 * 「爪牙槽中的剑被换入主手」进度判据。
 *
 * <p>龙之生存的爪牙槽机制会在玩家攻击、或施放带 {@code use_claw} 的龙技能时，把爪牙槽里的剑
 * <b>临时搬进主手</b>（{@code ClawInventoryData#swapStart}），做完再搬回去。这样原版与其他模组
 * 读 {@code player.getMainHandItem()} 的逻辑无需适配就能正确工作。</p>
 *
 * <p>本判据的触发点是 {@code ClawSwordAdvancementHandler}：它读
 * {@link by.dragonsurvivalteam.dragonsurvival.registry.attachments.ClawInventoryData}
 * 的 public 字段 {@code switchedTool} / {@code switchedToolSlot} 判定换手是否正在进行。
 * 之所以不用轮询或装备变更事件，是因为换手在<b>单次方法调用内闭合</b>，
 * tick 末尾与「与上一 tick 快照比对」两类观测点都读不到它。</p>
 *
 * <p>谓词只带标准的 {@code player} 字段——没有「换的是哪把剑」之类的筛选需求，
 * 需要时再加。</p>
 */
public class ClawSwordSwapTrigger extends SimpleCriterionTrigger<ClawSwordSwapTrigger.Instance> {

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
