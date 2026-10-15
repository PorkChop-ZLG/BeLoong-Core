package com.zonlong.beloong.client;

import com.zonlong.beloong.cg.CgFinishedPayload;
import com.zonlong.beloong.npcstory.NpcClientReadyPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端侧的"同步申报"处理器（F1 + F2，2026-10-14）。
 *
 * <h2>① 就绪信号（F1）</h2>
 * 判据用 {@code Minecraft#getOverlay() == null} —— **原版自己就用它**判断"能不能接受操作"
 * （{@code MouseHandler} / {@code KeyboardHandler}），所以它正是"加载遮罩已消失"的可靠标志。
 * 连续 {@link #READY_STREAK_TICKS} tick 都满足才上报（让首批区块与首帧稳住），**每次会话只报一次**。
 * <p>
 * 服务端收到后才开始计序章的等待 ⇒ 慢客户端（联机首次进入、大整合包）不会再出现
 * "屏幕还在加载地形，CG 已经开播"。
 *
 * <h2>② 过场结束上报（F2）</h2>
 * 收到 {@code CgStartedPayload} 时起表（那就是客户端真正开始播的时刻），
 * 本地 tick 数到点后回 {@link CgFinishedPayload} ⇒ 服务端据此结算并授予完成进度。
 * 服务端仍保留自己的兜底计时（{@code cutsceneTime + 200}），所以客户端崩了也不会卡住流程。
 *
 * <h2>⚠️ 只在物理客户端加载</h2>
 * 本类由 {@code BeLoongCoreClient} 注册（{@code dist = Dist.CLIENT}），专用服务器不会碰到它。
 */
public final class NpcClientSyncHandler {

    /** 加载遮罩消失后还要连续满足多少 tick 才算"真的稳了"（20 tick = 1 秒）。 */
    private static final int READY_STREAK_TICKS = 20;

    /** 连续满足的计数。 */
    private static int readyStreak = 0;

    /** 本次会话是否已经报过就绪。 */
    private static boolean readyReported = false;

    /** 正在本地计时的过场：CG 名 → 剩余 tick。 */
    private static final Map<String, Integer> PENDING_CG = new LinkedHashMap<>();

    /** 服务端刚通知"这段过场开始了"（载荷入口）。 */
    public static void onCgStarted(String cgName, int durationTicks) {
        PENDING_CG.put(cgName, Math.max(1, durationTicks));
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            // 断开/回主菜单：就绪与过场计时都作废（下次进世界要重新申报）。
            readyStreak = 0;
            readyReported = false;
            PENDING_CG.clear();
            return;
        }

        // ① 就绪
        if (!readyReported) {
            if (minecraft.getOverlay() == null) {
                if (++readyStreak >= READY_STREAK_TICKS) {
                    readyReported = true;
                    PacketDistributor.sendToServer(NpcClientReadyPayload.INSTANCE);
                }
            } else {
                readyStreak = 0;
            }
        }

        // ② 过场到点
        if (!PENDING_CG.isEmpty()) {
            for (Iterator<Map.Entry<String, Integer>> it = PENDING_CG.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Integer> entry = it.next();
                int left = entry.getValue() - 1;
                if (left <= 0) {
                    it.remove();
                    PacketDistributor.sendToServer(new CgFinishedPayload(entry.getKey()));
                } else {
                    entry.setValue(left);
                }
            }
        }
    }
}
