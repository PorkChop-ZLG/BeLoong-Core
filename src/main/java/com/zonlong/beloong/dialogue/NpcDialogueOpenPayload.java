package com.zonlong.beloong.dialogue;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.client.NpcDialogueScreen;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.Optional;

/**
 * 「打开 NPC 对话」网络包（服务端 → 单个玩家），只在**该玩家**右键命中时发送。
 * <p>
 * 服务端是唯一的决策点：它判定这次右键是否命中、以及该玩家此刻该看到哪一条
 * （见 {@code NpcDialogueHandler}）。客户端**不持有对话全表** —— 因此没有客户端缓存，
 * 也没有登录全量同步。
 * <p>
 * <b>为什么载荷是「客户端渲染所需的最小事实」而不是 {@link NpcDialogueEntry} 本体</b>：
 * {@code StreamCodec} 解码失败**无法优雅降级** —— NeoForge 在解码阶段抛异常会**中止连接**，
 * 而不是丢掉一个包。而条目里的 {@code EntityType} 在客户端只能做注册表反查，
 * 是整条链路上**唯一可失败的一步**。于是：
 * <ul>
 *   <li>不放 {@code EntityType}，兜底名改用
 *       {@link net.minecraft.world.entity.EntityType#getDescriptionId()}（普通字符串）；</li>
 *   <li>需要实体本体的场合（取名要用命名牌自定义名）由 {@link #entityId()} 在客户端按网络 id 找，
 *       找不到就退到兜底名；</li>
 *   <li>{@code trigger} 不上线 —— 触发判定只在服务端做，客户端渲染用不到它。</li>
 * </ul>
 * 结果：线格式**全函数、永不抛**。
 *
 * @param nameKey         说话人名字的翻译键（数据文件里的 {@code name}）；缺省用实体显示名
 * @param fallbackNameKey 兜底名的翻译键（服务端取实体类型名，如 {@code entity.minecraft.iron_golem}）
 * @param pages           逐页文本
 * @param entityId        目标实体的网络 id（客户端用于取命名牌自定义名；实体未加载时允许找不到）
 */
public record NpcDialogueOpenPayload(
        Optional<String> nameKey,
        String fallbackNameKey,
        List<NpcDialogueEntry.Page> pages,
        int entityId
) implements CustomPacketPayload {

    public static final Type<NpcDialogueOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "npc_dialogue_open"));

    /**
     * 末尾的 {@code mapStream} 与 {@code TreasureSyncPayload#STREAM_CODEC} 保持同一写法。
     * <p>
     * 严格说它**不是必需的**：{@code StreamCodec.composite} 的首参类型是
     * {@code StreamCodec<? super B, T>}，而 {@link ByteBuf} 是 {@link RegistryFriendlyByteBuf}
     * 的父类型，所以光凭左侧声明的目标类型也能推出 {@code B = RegistryFriendlyByteBuf}
     * —— 带不带 {@code mapStream}，线格式完全一致。保留只为与本模组既有那条包写法统一，
     * 免得下一个人把两处改成不同风格。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, NpcDialogueOpenPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), NpcDialogueOpenPayload::nameKey,
                    ByteBufCodecs.STRING_UTF8, NpcDialogueOpenPayload::fallbackNameKey,
                    NpcDialogueEntry.Page.STREAM_CODEC.apply(ByteBufCodecs.list()), NpcDialogueOpenPayload::pages,
                    ByteBufCodecs.VAR_INT, NpcDialogueOpenPayload::entityId,
                    NpcDialogueOpenPayload::new
            ).mapStream(buf -> (ByteBuf) buf);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 客户端处理器。默认在**主线程**执行（与 {@code TreasureSyncPayload} 同款），
     * 故直接 {@code setScreen} 即可，不需要 {@code context.enqueueWork}。
     * <p>
     * 这里引用客户端类 {@link NpcDialogueScreen} 是安全的：专用服务器上本方法**永不被调用**，
     * 类加载是惰性的，{@code Screen} 那一系不会在服务端被加载。与
     * {@code TreasureSyncPayload#handleClient} 引用 {@code ClientTreasureCache} 完全同款。
     */
    public static void handleClient(NpcDialogueOpenPayload payload, IPayloadContext context) {
        NpcDialogueScreen.open(payload);
    }
}
