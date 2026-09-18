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
 * 「参与击败<b>不祥</b>状态死者之王」进度判据。
 *
 * <p>与 {@link DeadKingKillTrigger} 是<b>平行</b>关系（不是父子）：那个管普通变种，这个管不祥变种。
 * 二者刻意做成<b>两个独立判据 ID</b> 而不是"单判据 + {@code ominous} 布尔参数"，
 * 因为后者一旦在进度 JSON 里漏写参数，就会<b>静默退化成"两种都触发"</b>；拆成两个 ID
 * 则写错会直接表现为"判据不存在"，是响亮的失败。</p>
 *
 * <h3>不祥变种是怎么产生的（供整合包侧写进度时参考）</h3>
 * 铁魔法在 {@code EntityJoinLevelEvent} 里检查实体是否实现 {@code IOminousEntity}：
 * 若<b>生成那一刻</b>24 格内有玩家带 {@code Bad Omen} 或 {@code Trial Omen}，
 * 则调 {@code onOminousTrigger()}。对死者之王而言这意味着：
 * <b>带着不祥之兆去触发王座厅里的尸体</b>（Boss 由尸体复活产生），即可召出不祥变种
 * （基础血量 500 → <b>1000</b>，伤害/法强 +20%，速度 +10%，召唤伤害 +50%，护甲 +30）。
 * 注意<b>读档进场不触发</b>（该事件带 {@code !loadedFromDisk()} 门控）。</p>
 *
 * <p>注册名为 {@code beloong:ominous_dead_king_kill}。<b>改名会让已有进度失效。</b></p>
 *
 * @see DeadKingKillTrigger 普通变种
 * @see DeadKingAdvancementHandler 触发方
 */
public class OminousDeadKingKillTrigger extends SimpleCriterionTrigger<OminousDeadKingKillTrigger.Instance> {

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
