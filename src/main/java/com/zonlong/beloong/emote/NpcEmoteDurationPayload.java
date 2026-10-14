package com.zonlong.beloong.emote;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.entity.NpcEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 「这条一次性表情我起表了，它的长度是 N tick」（客户端 → 服务端）。
 * <p>
 * <b>为什么必须由客户端申报长度</b>：动画名、长度、循环类型<b>全都是客户端的资产数据</b>
 * （见 {@code NpcEntity} 表情控制器那段注释"全部判断都在客户端做"），
 * 服务端<b>无从知道</b>一条表情要播多久。所以这里由客户端把"结论"送过来 ——
 * 服务端仍然不解析资产，只是照着这个数字记一条到期计划。
 *
 * <h2>服务端拿它做什么</h2>
 * 记一条待清计划（{@link NpcEmoteExpiryHandler#schedule}），到期把 {@code DATA_EMOTE} 清回 {@code ""}。
 * 于是"这条一次性表情已经播完了"变成**服务端知道的事实**，
 * 此后任何客户端重建实体实例（走远回来 / 换维度 / 断线重连 / 别人中途加入）看到的都是空名字 ⇒ 不再重播。
 *
 * <h2>⚠️ 到期时刻算的是 {@code emoteSetAt + duration}，不是 {@code now + duration}</h2>
 * 客户端在**每次重新追踪**时都会再申报一次（初始实体数据包也会让它清瞬态、重新起表）。
 * 用 {@code now} 的话，玩家在末旁边来回走就会把清除时刻**无限推后 ⇒ 永远清不掉**，
 * 且实机表现是"时好时坏"、极难察觉。见 {@link NpcEntity#emoteSetAt()}。
 *
 * <h2>受理范围与校验（用户 2026-10-13 裁定）</h2>
 * <b>任何 {@link NpcEntity} 都受理</b>，只校验"实体 + 名字"两项 —— 这样公共锚点上的一次性表情
 * 也一并修好、没有特例。**已接受**的代价：改装过的客户端可以谎报一个长度，
 * 去**提前清掉某个共享 NPC 的表情**（例如让末提前站起来）—— 纯观感，无存档或数据影响，
 * 且名字必须报对。**因此这里不做防伪栅栏**，只做"无效输入不产生行为"。
 * <p>
 * ⚠️ <b>这条已接受风险的入口比"改装客户端"更宽</b>（2026-10-13 收尾审查 M-6 指出，结论不变但要写全）：
 * <ol>
 *   <li>长度来自**客户端资产** ⇒ 一个**资源包**（不必改装任何客户端代码）就能改掉某条动画的
 *       {@code animation_length}，从而影响它的到期时刻；</li>
 *   <li>{@code NpcEmoteExpiryHandler} 的 {@code PENDING.put} 是**后到者胜** ——
 *       同一条表情被重复申报时以最后一次为准。**这正是 I1 幂等所依赖的性质**
 *       （重新追踪会引发重复申报，而重复申报必须得出同一个到期时刻）。</li>
 * </ol>
 *
 * <p><b>失败一律静默</b>（照 {@link com.zonlong.beloong.dialogue.NpcDialogueReplyPayload} 的口径）：
 * 载荷是**客户端可控输入**，下面四种不过关的情形都是良性情况，没有可提示的对象，
 * 而且服务端**线程里绝不能抛异常**。判定顺序与理由：
 * <ol>
 *   <li>调用者不是 {@link ServerPlayer} ⇒ 丢（不该发生）；</li>
 *   <li>那个网络 id 在玩家所在维度找不到、或不是 {@link NpcEntity} ⇒ 丢（实体已卸载/消失/伪造）；</li>
 *   <li>当前表情名与申报的不一致 ⇒ 丢 —— <b>名字校验</b>。玩家在这几 tick 内换了表情是正常竞态，
 *       <b>刻意不报 WARN</b>（报了就是噪声）；</li>
 *   <li>{@code durationTicks <= 0} ⇒ 丢 —— <b>无效输入</b>，不是防伪栅栏。
 *       资产里最短的动画也是 1 tick，"长度 0"在数据上不存在；放它过去会变成"立刻清除"。</li>
 * </ol>
 * 溢出：{@code long + int} 在本时基下（gameTime 远小于 {@code Long.MAX_VALUE}）**不可能溢出**
 * ⇒ 不需要 {@code Math.addExact} 之类的额外防护。
 *
 * <p>⚠️ <b>两个长度边界（当前不可达，但要知道）</b>（审查 M-2）：
 * {@code Animation#length()} 可能返回
 * {@code 0}（显式声明 0）或 {@code Double.MAX_VALUE}（缺 {@code animation_length} 时
 * {@code BakedAnimationsAdapter} 用的哨兵）。前者会被上面第 4 条**拒收** ⇒ 服务端没有计划 ⇒
 * **永不清除**；后者经 {@code (int) Math.ceil(...)} **饱和成 int 最大值**（≈3.4 年）⇒ 等于永不到期。
 * 两者都只是**退化成"修复前的行为"**（这条一次性表情仍会在重新追踪时重播），不会更糟。
 * 且**当前不可达**：仓里 15 条动画**全部显式带 `animation_length`**（已核）。
 *
 * <p>计划文档：{@code docs/plans/2026-10-13-npc-emote-oneshot-expiry-plan.md}。
 *
 * @param entityId      那只 NPC 的**网络 id**（服务端据此拿回实体）
 * @param emoteName     申报的那条表情名（服务端会与 {@code npc.emote()} 比对）
 * @param durationTicks 这条动画的长度（tick），来自客户端的 {@code Animation#length()}
 */
public record NpcEmoteDurationPayload(int entityId, String emoteName, int durationTicks)
        implements CustomPacketPayload {

    public static final Type<NpcEmoteDurationPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "npc_emote_duration"));

    /** 两个 {@code VAR_INT} + 一个 UTF-8 串，无分支、无查表 ⇒ 全函数。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, NpcEmoteDurationPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, NpcEmoteDurationPayload::entityId,
                    ByteBufCodecs.STRING_UTF8, NpcEmoteDurationPayload::emoteName,
                    ByteBufCodecs.VAR_INT, NpcEmoteDurationPayload::durationTicks,
                    NpcEmoteDurationPayload::new
            ).mapStream(buf -> (ByteBuf) buf);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 服务端处理器（默认在**服务端主线程**执行，与 {@code NpcDialogueReplyPayload#handleServer} 同款）。
     * <p>
     * 四条校验的顺序与理由见类注释；全过则记一条待清计划。
     */
    public static void handleServer(NpcEmoteDurationPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }

        Entity entity = player.serverLevel().getEntity(payload.entityId());
        if (!(entity instanceof NpcEntity npc)) {
            return;
        }
        // 名字校验：这几 tick 内玩家可能已经换了表情。正常竞态 ⇒ 静默。
        if (!npc.emote().equals(payload.emoteName())) {
            return;
        }
        // 无效输入（不是防伪栅栏）：数据里不存在长度为 0 的动画。
        if (payload.durationTicks() <= 0) {
            return;
        }

        // ★ 用 emoteSetAt（服务端自己记的"最后要求播它的时刻"），**不是 now** ——
        //   重新追踪会引发重复申报，用 now 会把清除无限推后。见类注释。
        long deadline = npc.emoteSetAt() + (long) payload.durationTicks();
        NpcEmoteExpiryHandler.schedule(npc, payload.emoteName(), deadline);
    }
}
