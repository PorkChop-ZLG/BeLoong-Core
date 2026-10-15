package com.zonlong.beloong.cg;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.dialogue.NpcDialogueStage;
import com.zonlong.beloong.entity.NpcEntity;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>「CG 播完 ⇒ 授予某个进度」的计时器</b>（2026-10-14）。
 * <p>
 * 为什么不在 {@link CgAnimation} 里做：那个类的契约是"<b>刻意不持有任何运行期状态</b>"
 * （服务端在一个 tick 内烘轨迹、发两个包就撒手），而"等 CG 放完再授予"恰恰是个定时状态
 * ⇒ 它只能住在外面。CG 类只负责<b>声明</b>要授予什么（{@link CgAnimation#completionAdvancement()}）。
 *
 * <h2>语义（用户裁定，方案"甲"）</h2>
 * <b>只要 CG 被"开始过"，进度就一定拿到</b>：
 * <ul>
 *   <li>正常播完 ⇒ {@code cutsceneTime} 之后授予（{@link #grantLater}）；</li>
 *   <li>没播成（预检未过 / 发包失败）⇒ {@code CgAnimation#play} 里<b>当场</b>调用 {@link #grantNow}；</li>
 *   <li>播放途中<b>掉线</b> ⇒ 本类在 {@link PlayerEvent.PlayerLoggedOutEvent} 里立刻补授 + WARN。</li>
 * </ul>
 * 目的：剧情流程永远不会卡在"有起点进度、却没有完成标志"上（那会让第一段对话永远打不开）。
 * <p>
 * ⚠️ 服务器**关停**时待授予项会丢（内存表）⇒ 下次登录由序章处理器按"有 root 却没有 1_0"补授
 * （见 {@code NpcPrologueHandler}）。
 */
public final class CgCompletionHandler {

    /** 待授予的一条：谁、哪个进度、到什么时候（gameTime）授予、哪条 CG 留下的。 */
    private record Pending(UUID player, ResourceLocation advancement, long deadline, String cg) {}

    /** ⚠️ 只在服务端主线程访问（服务端 tick / 事件回调）⇒ 不需要并发容器。 */
    private static final List<Pending> PENDING = new ArrayList<>();

    /** 延迟授予：{@code delayTicks} 之后（以该玩家当前维度的 gameTime 计）。 */
    public static void grantLater(ServerPlayer player, ResourceLocation advancement, int delayTicks, String cg) {
        long deadline = player.serverLevel().getGameTime() + Math.max(1, delayTicks);
        PENDING.add(new Pending(player.getUUID(), advancement, deadline, cg));
    }

    /**
     * 该玩家此刻是否有一条**待授予**的完成结算（用于序章判断"CG 是不是正在播"）。
     * <p>
     * ⚠️ 序章的 {@code 1_0} 补授分支必须先用它挡一下：那条分支本意是救"CG 被服务器关停打断"，
     * 而"CG 正常播放中"也满足 {@code 有 root ∧ 无 1_0} ⇒ 不挡就会在 CG 开始后 1 秒就把 {@code 1_0}
     * 发出去（实机日志里出现过 5 次）。
     */
    public static boolean hasPending(ServerPlayer player, ResourceLocation advancement) {
        for (Pending p : PENDING) {
            if (p.player().equals(player.getUUID()) && p.advancement().equals(advancement)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>被过场占用的表情</b>：NPC 的 UUID → 押后到哪个 gameTime 为止（F3，2026-10-14）。
     * <p>
     * 为什么需要：一次性表情的清除由<b>服务端</b>按"客户端申报的时长"到期执行，
     * 而 CG 期间客户端可能还在播（实机日志里出现过"只剩 44 ticks"就被清掉 ⇒
     * 动画播一半就停、而 CG 运镜还在）。所以过场一开始就把这只 NPC 的表情清除**押后**，
     * 由 CG 结束（客户端上报会提前，最迟 {@code cutsceneTime + 200} 兜底到期）后自然解除。
     * <p>
     * ⚠️ 用"限时押后"而不是"注册/注销配对"：本表的生命周期不依赖任何结算路径是否走到，
     * 到点即失效 ⇒ 不存在"忘了解锁"导致表情永不清除的风险。
     */
    private static final Map<UUID, Long> CG_EMOTE_HOLD = new HashMap<>();

    /** 把某只 NPC 的表情清除押后到 {@code untilGameTime}（过场开始时调用）。 */
    public static void holdEmote(net.minecraft.world.entity.Entity npc, int extraTicks) {
        long now = npc.level() instanceof net.minecraft.server.level.ServerLevel level ? level.getGameTime() : 0L;
        CG_EMOTE_HOLD.put(npc.getUUID(), now + Math.max(1, extraTicks));
    }

    /**
     * 这只 NPC 的表情是否正被某段过场押后（表情到期处理器据此**跳过**清除）。
     * <p>
     * 顺带清掉已过期的条目 ⇒ 本表自然有界，不需要额外的生命周期钩子。
     */
    public static boolean ownsEmote(NpcEntity npc) {
        long now = npc.level() instanceof net.minecraft.server.level.ServerLevel level ? level.getGameTime() : 0L;
        Long until = CG_EMOTE_HOLD.get(npc.getUUID());
        if (until == null) {
            return false;
        }
        if (now >= until) {
            CG_EMOTE_HOLD.remove(npc.getUUID());
            return false;
        }
        return true;
    }

    /**
     * <b>客户端上报"过场播完了"</b>（{@link CgFinishedPayload} 的落点）。
     * <p>
     * 只对"该玩家此刻确有待结算项、且 CG 名对得上"的条目生效 ⇒ 立刻授予并移出待结算表；
     * 其余一律静默忽略（**幂等**：重复上报、过期上报都不会有副作用）。
     * <p>
     * ⚠️ 授予的进度**取自待结算项自己**（客户端只提供"播完了"这个时机，不提供任何进度内容）。
     */
    public static void settleFromClient(ServerPlayer player, String cgName) {
        for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (!p.player().equals(player.getUUID()) || !p.cg().equals(cgName)) {
                continue;
            }
            it.remove();
            grantNow(player, p.advancement(), p.cg(), "client reported the cutscene finished");
            return;
        }
    }

    /** 当场授予（没播成、或掉线补授）。永不抛异常 —— 调用方在 {@code play} 的关键路径上。 */
    public static void grantNow(ServerPlayer player, ResourceLocation advancement, String cg, String why) {
        award(player, advancement);
        BeLoongCore.LOGGER.info(
                "[BeLoong] cg '{}': granted advancement '{}' to {} ({})",
                cg, advancement, player.getGameProfile().getName(), why);
    }

    /** 授予一个进度的**全部** criterion（我们的进度都只有一个；写全是为了不依赖命名约定）。 */
    private static void award(ServerPlayer player, ResourceLocation advancement) {
        AdvancementHolder holder = player.server.getAdvancements().get(advancement);
        if (holder == null) {
            BeLoongCore.LOGGER.error(
                    "[BeLoong] cg completion: advancement '{}' does not exist — nothing was granted", advancement);
            return;
        }
        for (String criterion : holder.value().criteria().keySet()) {
            player.getAdvancements().award(holder, criterion);
        }
        // 性能 S2：发放后让进度状态缓存立刻失效（否则序章/对账器/可见性最长会看到 1 秒前的旧值）。
        NpcDialogueStage.invalidate(player);
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(p.player());
            if (player == null) {
                it.remove();    // 掉线由 onPlayerLoggedOut 处理；这里只是兜底清理
                continue;
            }
            if (player.serverLevel().getGameTime() >= p.deadline()) {
                it.remove();
                grantNow(player, p.advancement(), p.cg(), "cutscene finished");
            }
        }
    }

    /** 播放途中掉线 ⇒ 立刻补授（"开始过就一定拿到"）+ WARN，免得玩家下次进来卡在第一段对话之前。 */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (!p.player().equals(player.getUUID())) {
                continue;
            }
            it.remove();
            grantNow(player, p.advancement(), p.cg(), "the player logged out before the cutscene finished");
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] cg '{}': {} logged out before it finished — advancement '{}' was granted anyway "
                            + "(so the prologue flow can continue on their next login)",
                    p.cg(), player.getGameProfile().getName(), p.advancement());
        }
    }
}
