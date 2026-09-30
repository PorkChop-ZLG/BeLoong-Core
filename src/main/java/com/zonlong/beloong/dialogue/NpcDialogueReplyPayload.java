package com.zonlong.beloong.dialogue;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.compat.chatbox.ChatBoxBridge;
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
 * 「玩家选了某个回复选项」（客户端 → 服务端）。<b>本项目第一个 C2S 包。</b>
 * <p>
 * NPC 对话播放完毕后，界面在「离开」上方列出若干回复选项；玩家点其中一条时，
 * 客户端关闭自己的界面（与点「离开」完全等价）并发出本包。
 * <p>
 * <b>为什么载荷只有「实体网络 id + 回复下标」（设计 D1）</b>：目标（ChatBox 的
 * {@code ResourceLocation} / 组名 / 页号）**留在服务端**。服务端用这两个数去查
 * {@link NpcDialogueLoader} 里自己那张表，因此：
 * <ul>
 *   <li>改过的客户端**无法**让服务端播放任意 ChatBox 对话 —— 它最多只能选中"数据里已经写好的另一条回复"；</li>
 *   <li>线格式退化成两个 {@code VAR_INT}，平凡地满足 {@link NpcDialogueOpenPayload} 那条
 *       "全函数、永不抛"的不变量（连 {@code tryParse} 特例都不需要）。</li>
 * </ul>
 * <p>
 * <b>失败一律静默</b>（设计 §4）：下标越界（伪造/陈旧包）、实体已消失或未加载、热重载后
 * 对话表里已无该条目 —— 这三种都是良性情况，玩家的界面早已关闭，没有可提示的对象。
 * 唯一的日志在真正要对接 ChatBox 时由 {@link ChatBoxBridge#canOpen} 打：那是**数据写错**，
 * 作者必须知道（且每个坏目标只报一次）。
 * <p>
 * 设计文档：{@code docs/plans/2026-09-29-npc-dialogue-chatbox-bridge-design.md}。
 *
 * @param entityId   被右键的那个实体的**网络 id**（服务端据此拿回实体与实体类型）
 * @param replyIndex 第几个回复选项 —— 是它在 {@link NpcDialogueEntry#replies()} 里的**原始下标**
 *                   （不是界面上"可见列表"的下标；界面上可能因阶段闸门而缺项）
 */
public record NpcDialogueReplyPayload(int entityId, int replyIndex) implements CustomPacketPayload {

    public static final Type<NpcDialogueReplyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "npc_dialogue_reply"));

    /** 两个 {@code VAR_INT}，无分支、无查表 ⇒ 全函数。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, NpcDialogueReplyPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, NpcDialogueReplyPayload::entityId,
                    ByteBufCodecs.VAR_INT, NpcDialogueReplyPayload::replyIndex,
                    NpcDialogueReplyPayload::new
            ).mapStream(buf -> (ByteBuf) buf);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 服务端处理器（默认在**服务端主线程**执行，与 {@link NpcDialogueOpenPayload#handleClient} 同款）。
     * <p>
     * 顺序：找实体 → 查回本模组那张对话表 → 取回复（越界即丢）→ 复检阶段闸门 → 预检 ChatBox →
     * <b>按 {@code stop_emote} 决定是否停掉 NPC 的表情</b> → 交给 {@link ChatBoxBridge}。
     * 全程不抛异常：载荷是客户端可控输入，能在服务端线程抛的东西一个都不能留。
     */
    public static void handleServer(NpcDialogueReplyPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }

        Entity entity = player.serverLevel().getEntity(payload.entityId());
        if (entity == null) {
            return;
        }

        NpcDialogueEntry entry = NpcDialogueLoader.INSTANCE.get(entity.getType());
        if (entry == null) {
            return;
        }

        int index = payload.replyIndex();
        if (index < 0 || index >= entry.replies().size()) {
            return;
        }

        NpcDialogueEntry.Reply reply = entry.replies().get(index);

        // 复检阶段闸门：界面打开期间玩家可能已从别处拿到了结束进度 ⇒ 这条回复此刻可能已不该显示。
        // 静默丢弃 —— 界面早已关闭，没有可提示的对象（与下标越界同一种处理）。
        if (!NpcDialogueStage.visible(player, reply)) {
            return;
        }
        // index 缺省在我们这一侧折成 0：ChatBox 那边页序号绝不可为 null（会被编码成 "null"
        // 让客户端 Integer.parseInt 抛异常）。
        int page = reply.index().orElse(0);

        if (!ChatBoxBridge.canOpen(reply.chatbox(), reply.group(), page)) {
            return;
        }
        // stop_emote（可选，缺省 false）：点这条回复时停掉该 NPC 当前的表情动画。
        // 放在 canOpen 之后：ChatBox 打不开时这条回复等于没生效，此时只把表情停掉会留下"半生效"的状态。
        // 被对话的实体不一定是 NpcEntity（对话表按实体类型挂，可以是任意类型）—— 不是就没有表情可停。
        if (reply.stopEmote() && entity instanceof NpcEntity npc) {
            npc.clearEmote();
        }
        ChatBoxBridge.open(player, reply.chatbox(), reply.group(), page);
    }
}
