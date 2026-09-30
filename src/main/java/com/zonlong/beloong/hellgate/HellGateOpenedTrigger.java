package com.zonlong.beloong.hellgate;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 「见证地狱之门开启」进度判据。
 *
 * <h2>触发时机（与需求「动画播完、门可以进入之后」一一对应）</h2>
 * 触发点唯一：{@code HellGateBlockEntity#tick} 里
 * {@code animationTicks >= TICK_FULLY_OPEN && !level.isClientSide} 那一段 ——
 * 它先把基准格与 39 个部件格**全部置 {@code OPEN=true}**（碰撞箱清空＝可以走进去），
 * 之后才调用 {@link HellGateOpenedAdvancements#grant}。整扇门**只会触发一次**
 * （置完 {@code OPEN} 后下一 tick 就在 {@code OPEN} 分支早退）。
 *
 * <h2>谁会被发放</h2>
 * 以基准格中心为球心、<b>半径 32 格内的所有非旁观玩家</b>（创造模式计入），
 * 见 {@link HellGateOpenedAdvancements}（口径集中在那里，本类只管判据本身）。
 *
 * <h2>为什么不复用原版判据</h2>
 * 原版没有任何判据能表达「门走完 145 tick 的开启动画、状态从「未开」变为「可通行」」：
 * {@code location} / {@code item_used_on_block} / {@code player_interacted_with_entity} 之类
 * 都只能描述**玩家做了什么**，而需求要的是**方块状态转换完成**这一刻。
 *
 * <h2>为什么没有自定义条件字段（用户裁定）</h2>
 * 判据实例只保留原版标准的 {@code player} 谓词 —— 它已经能让整合包按**维度、坐标、游戏模式**
 * 等条件筛选（例如「只认下界里开的门」）。多加一个自定义字段就等于给整合包多加一个
 * 「以后不能改」的东西（字段是进度 JSON 的 schema，改它会让已有进度失效），收益不明显。
 *
 * <p><b>注册名 {@code beloong:hell_gate_opened} 即数据包书写形式</b>：
 * {@code {"trigger": "beloong:hell_gate_opened"}}。<b>改名会让整合包已写好的进度失效。</b></p>
 *
 * <p>⚠️ <b>没有进度引用本判据时，{@code trigger} 会静默 no-op</b>（原版
 * {@code SimpleCriterionTrigger} 的 listener 集为空）—— 这是原版行为，无害，
 * 但会让实机测试「什么都看不到」。因此本模组只提供判据，真实进度由整合包侧编写；
 * 开发期用的临时示例进度在验证通过后已删除。</p>
 *
 * @see HellGateOpenedAdvancements 发放方（半径、过滤、快照防 CME 都在那边）
 * @see com.zonlong.beloong.registry.ModCriteria 注册处
 */
public class HellGateOpenedTrigger extends SimpleCriterionTrigger<HellGateOpenedTrigger.Instance> {

    /** 供 {@link HellGateOpenedAdvancements} 在判定成立时逐个玩家调用。 */
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
     * @param player 原版标准玩家谓词，可选（整合包用它按维度/坐标/游戏模式筛）
     */
    public record Instance(Optional<ContextAwarePredicate> player)
            implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player)
        ).apply(instance, Instance::new));
    }
}
