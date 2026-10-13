package com.zonlong.beloong.mixin.dragonsurvival;

import by.dragonsurvivalteam.dragonsurvival.client.gui.screens.DragonEmoteScreen;
import by.dragonsurvivalteam.dragonsurvival.client.render.ClientDragonRenderer;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateHandler;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.common.entity.DragonEntity;
import by.dragonsurvivalteam.dragonsurvival.network.emotes.StopAllEmotes;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复 Dragon Survival 的一个<b>客户端断连崩溃</b>（"Network Protocol Error / Packet handling error"）。
 *
 * <h3>现象与根因</h3>
 * <p>{@code DragonEmoteScreen#addEmote(String)} 把 {@code getEmote(key)} 的结果直接塞进
 * {@code SyncEmote}，而 {@code DragonEmoteSet#getEmote} 在找不到 key 时返回 {@code null}
 * （实现即 {@code ...findFirst().orElse(null)}）。于是当玩家按下了一个"当前龙体上不存在"的
 * 表情键位——例如换体/换种族后残留的旧键位、或整合包/数据包删改过的表情 key——就会发出一个
 * {@code emote == null} 的 {@code dragonsurvival:sync_emote} 包。该包在
 * {@code SyncEmote.STREAM_CODEC}（{@code DragonEmote.STREAM_CODEC = ByteBufCodecs.fromCodec(DragonEmote.CODEC)}）
 * 逐字段编码时对 null 取值 ⇒ {@code NullPointerException}（栈顶
 * {@code RecordCodecBuilder$Instance.lambda$ap2$4}），客户端随即断开并生成崩溃报告。</p>
 *
 * <h3>修法：判空则"停止表情"</h3>
 * <p>在 {@code addEmote} 的 {@code HEAD} 用与 DS 原方法<b>同源</b>的方式解析一次表情
 * （{@code handler.body().value().emotes().value().getEmote(key)}）：</p>
 * <ul>
 *   <li><b>解析成功</b>（合法键位）⇒ 原样放行，DS 逻辑不变。</li>
 *   <li><b>解析为 {@code null}</b> ⇒ 本地 {@code dragon.stopAllEmotes()} 并发送
 *       {@code StopAllEmotes} 包（服务端再广播给追踪玩家），最后 {@code ci.cancel()} 跳过原方法体。
 *       即"键位按下了、但它对应的表情在当前龙体上不存在"时，不是静默无操作，而是让表情停下。</li>
 * </ul>
 *
 * <p><b>为什么用 {@link StopAllEmotes} 而不是 {@code new SyncEmote(..., null, true)}：</b>
 * {@code SyncEmote} 的第二个字段就是 {@code DragonEmote}，只要它是 {@code null}，
 * 无论 {@code stop} 传 true 还是 false，编码都会在同一处 {@code RecordCodecBuilder} 路径 NPE——
 * {@code stop} 只是它后面的第三个 boolean 字段，救不了那个 null。{@code StopAllEmotes} 只带
 * {@code playerId}（{@code ByteBufCodecs.INT}），不含任何可空对象，且正是 DS 自己
 * {@code EmoteHandler#playerTick} 在"蹲下/挥手/挨打"时用的同一条"停全部"通道，语义与实现都同源。</p>
 *
 * <h3>为什么必须在 HEAD 拦截而不是放行</h3>
 * <p>已核对运行版本 2.0.71 的 {@code addEmote}：其方法体在 {@code getEmote} 之后<b>没有任何
 * null 守卫</b>（直接 {@code beginPlayingEmote(emote)} + {@code new SyncEmote(...)}）。因此上游
 * 修复缺失，只能在此接管。若日后 DS 自行修复也无害——届时合法键位的 {@code getEmote} 结果恒非 null，
 * 本拦截分支永不进入。</p>
 */
@Mixin(value = DragonEmoteScreen.class, remap = false)
public abstract class DragonEmoteScreenMixin {

    @Inject(method = "addEmote", at = @At("HEAD"), cancellable = true, remap = false)
    private static void beloong$guardUnresolvedEmote(String key, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        // 非龙：DS 原方法自己会在开头的 getDragon(...) == null 处提前 return，不碰它
        DragonEntity dragon = ClientDragonRenderer.getDragon(player);
        if (dragon == null) {
            return;
        }

        DragonStateHandler handler = DragonStateProvider.getData(player);
        if (handler == null || handler.body() == null) {
            return;
        }

        // 合法键位：完全交给 DS 原逻辑（播放/切换）
        if (handler.body().value().emotes().value().getEmote(key) != null) {
            return;
        }

        // 判空：不能发 SyncEmote(emote == null, ...)（null 字段照样会在编码期 NPE），
        // 改走 DS 自带的"停全部"通道，让表情停下来。
        dragon.stopAllEmotes();
        PacketDistributor.sendToServer(new StopAllEmotes(player.getId()));

        BeLoongCore.LOGGER.warn(
                "[BeLoong] emote '{}' does not exist on the player's current dragon body; "
                        + "stopped all emotes instead of sending SyncEmote (vanilla would send emote == null and disconnect the client)",
                key);

        ci.cancel();
    }
}
