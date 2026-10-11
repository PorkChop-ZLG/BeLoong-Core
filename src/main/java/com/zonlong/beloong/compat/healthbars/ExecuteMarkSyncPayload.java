package com.zonlong.beloong.compat.healthbars;

import com.zonlong.beloong.BeLoongCore;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 「斩杀标记」同步包（服务端 → 正在追踪该生物的玩家）。
 *
 * <h3>为什么必须自己发这个包</h3>
 * 本功能原本的设计前提是「斩杀线编码在 effect 的 amplifier 里，而 amplifier 会被原版自动同步到客户端」
 * —— <b>这个前提对旁观者是错的</b>，2026-10-11 实机完全无渲染后查明：
 * <ul>
 *   <li>{@code LivingEntity} 只把 effect 的<b>粒子外观</b>（{@code DATA_EFFECT_PARTICLES}）与一个
 *       聚合环境位放进实体元数据；{@code MobEffectInstance} 本体（含 amplifier / duration / 各标志位）
 *       <b>从不发给旁观者</b>；</li>
 *   <li>{@code ClientboundUpdateMobEffectPacket} 全树只有 3 个发送点：给<b>乘客</b>
 *       （{@code LivingEntity:1057}）、给<b>效果持有者本人</b>（{@code ServerPlayer:1493/1505}）、
 *       登录与骑乘时（{@code PlayerList:535}）。<b>没有一个是「发给正在追踪该生物的玩家」</b>。</li>
 * </ul>
 * 所以客户端对一只怪调 {@code entity.getEffect(...)} <b>永远是 null</b>。本包就是把原版漏发的那几个
 * 字节补上：只带 {@code entityId + amplifier} —— 比原版那个包还小（我们不需要 duration，也不需要
 * ambient/visible/showIcon/blend 四个标志位）。
 *
 * <h3>与既有包的写法一致</h3>
 * 见 {@code NpcDialogueOpenPayload}：{@code playToClient} 注册、{@code handleClient} 默认在主线程执行、
 * 载荷只用「整数」这类永不抛的类型（{@code StreamCodec} 解码失败会<b>中止连接</b>而不是丢包，
 * 所以线格式必须全函数）。
 *
 * @param entityId  被标记生物的网络 id
 * @param amplifier 斩杀线编码值（{@link com.zonlong.beloong.registry.ExecuteThresholdEffect#thresholdFraction(int)}
 *                  的入参）；{@link #NO_MARK} 表示「已无标记，清掉」
 */
public record ExecuteMarkSyncPayload(int entityId, int amplifier) implements CustomPacketPayload {

    /** 「这个实体现在没有斩杀标记」。用 -1 而不是 0：0 是合法的 amplifier（= 0.5% 斩杀线）。 */
    public static final int NO_MARK = -1;

    public static final Type<ExecuteMarkSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "execute_mark_sync"));

    /** 两个 VAR_INT ⇒ 全函数、永不抛。末尾 {@code mapStream} 与既有两条包保持同一写法。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, ExecuteMarkSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ExecuteMarkSyncPayload::entityId,
                    ByteBufCodecs.VAR_INT, ExecuteMarkSyncPayload::amplifier,
                    ExecuteMarkSyncPayload::new
            ).mapStream(buf -> (ByteBuf) buf);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 客户端处理器。
     * <p>
     * 这里引用客户端专属的 {@link ExecuteMarkCache} 是安全的，与
     * {@code NpcDialogueOpenPayload#handleClient} 引用 {@code NpcDialogueScreen} 完全同款：
     * 专用服务器上本方法<b>永不被调用</b>，类加载是惰性的，{@code ExecuteMarkCache} 不会被加载。
     * （{@code tools/validate_execute_line.py} 的第 ⑤ 条断言把这个引用集合钉死。）
     */
    public static void handleClient(ExecuteMarkSyncPayload payload, IPayloadContext context) {
        ExecuteMarkCache.apply(payload.entityId(), payload.amplifier());
    }
}
