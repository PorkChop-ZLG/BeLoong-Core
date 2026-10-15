package com.zonlong.beloong.emote;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.cg.CgCompletionHandler;
import com.zonlong.beloong.entity.NpcEntity;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>一次性表情的"播完即清"</b> —— 把"这条表情已经播完了"从**客户端内存**变成**服务端知道的事实**。
 *
 * <h2>它修的是什么</h2>
 * {@code DATA_EMOTE} 是**同步字段**，语义是"**当前**表情"；但"播完了没有"原先只记在
 * 客户端的瞬态字段上（{@code emoteDone} / {@code emoteSeen} / {@code emoteStartTick}），
 * 而**客户端的实体实例会重建**（走出追踪半径、换维度、断线重连、别人中途加入）
 * ⇒ 新实例看到字段里还留着一条一次性表情名 ⇒ **从头再播一遍**。
 * 表现就是"玩家走远再回来，末又演了一遍登场动画，但没有运镜"。
 * <p>
 * 于是这里做一件很朴素的事：客户端在起表时把**动画长度**申报给服务端，服务端据此**到期把字段清回 {@code ""}**。
 * 此后任何客户端重建实例看到的都是空名字 ⇒ 不会再播。{@code LOOP} 姿态（{@code sit} / {@code dance}）
 * **根本不会申报**（客户端不上报）⇒ 它们照旧"一直在"，这是刻意的（"它坐在那里"）。
 *
 * <h2>⚠️ 为什么必须挂在"与区块加载无关"的服务端 tick 上</h2>
 * 玩家走远之后，**那个区块就不再 tick 了** —— 而**恰恰是这段时间需要把字段清掉**，
 * 否则玩家走回来的那一刻字段还在、客户端照样起表重播。
 * ⇒ 挂在 {@code NpcEntity.tick()} 上**必然失效**；必须由一个每 tick 都跑的服务端处理器驱动。
 *
 * <h2>⚠️ 到期时刻必须是 {@code emoteSetAt + length}，不能是 {@code now + length}</h2>
 * 客户端在**每次重新追踪**时都会重新收到实体数据 ⇒ 会**再申报一次**。
 * 如果用"现在 + 长度"，玩家在末旁边来回走就会把清除时刻**无限推后** ⇒ **永远清不掉**，
 * 而且这种错误在实机上表现为"时好时坏"，极难察觉。
 * 用服务端自己记的 {@link NpcEntity#emoteSetAt()}（= 最后一次 {@code setEmote} 的时刻）则**重复申报结果恒等**。
 *
 * <h2>两道防线</h2>
 * <ol>
 *   <li>{@link NpcEntity#setEmote} 会调 {@link #cancel} —— **换了表情就作废旧计划**，
 *       保证旧计划绝不会清掉新表情；</li>
 *   <li>到期时还要**再比对一次名字**（{@link Entry#name()} vs {@code npc.emote()}）——
 *       不一致就只丢计划、不动字段。</li>
 * </ol>
 *
 * <p>由 {@code BeLoongCore} 注册到游戏事件总线（与 {@code NpcStoryHandler} 同处一组）。
 * 计划文档：{@code docs/plans/2026-10-13-npc-emote-oneshot-expiry-plan.md}。
 */
public final class NpcEmoteExpiryHandler {

    /**
     * 待清计划。**每个实体最多一条**（{@link #schedule} 直接覆盖）⇒ 天然有界；
     * 到点、实体被移除、或 {@link #cancel} 时删除 ⇒ 无泄漏。
     * <p>
     * 以**实体引用**为键：{@code NpcEntity} 未重写 {@code equals} / {@code hashCode} ⇒ 天然身份语义。
     */
    private static final Map<NpcEntity, Entry> PENDING = new HashMap<>();

    /** 一条待清计划：要清的那条名字 + 到期时刻（绝对 gameTime）。 */
    private record Entry(String name, long deadline) {
    }

    /**
     * 构造器是 {@code public} 的 —— 本类由 {@code BeLoongCore} 以**实例**形式注册到游戏事件总线
     * （与 {@code NpcStoryHandler} 等同一套做法），而 {@link #onServerTick} 是实例方法（项目惯例）。
     * <p>
     * 但**状态与另外两个工具方法都是静态的**：{@link #schedule} / {@link #cancel} 由
     * {@code NpcEntity.setEmote} 与 {@code NpcEmoteDurationPayload.handleServer} 从外部调用，
     * 不能挂在某个实例上（否则"谁持有那个实例"会变成一个真问题）。
     */
    public NpcEmoteExpiryHandler() {
    }

    /**
     * 记一条待清计划（服务端受理 {@code NpcEmoteDurationPayload} 时调用）。
     * <p>
     * ⚠️ 调用方负责保证 {@code deadline} 是由 {@link NpcEntity#emoteSetAt()} 算出来的
     * （见类注释里那条"不能是 now"的说明）。
     *
     * @param npc      目标 NPC（服务端实例）
     * @param name     申报的那条表情名；到期时会与 {@code npc.emote()} 再比对一次
     * @param deadline 到期时刻，单位 tick，与 {@code npc.level().getGameTime()} 同一时基
     */
    public static void schedule(NpcEntity npc, String name, long deadline) {
        PENDING.put(npc, new Entry(name, deadline));
        // 这一行是实机验收第 1 项的唯一可观测点：字段本身在游戏里看不到。
        BeLoongCore.LOGGER.info(
                "[BeLoong] emote '{}' on {} will be cleared in {} ticks",
                name, npc.getType(), deadline - npc.level().getGameTime());
    }

    /**
     * 作废该实体的待清计划（{@link NpcEntity#setEmote} 每次都调 —— 见类注释"两道防线"第 1 条）。
     * <p>
     * 没有计划时是空操作，不需要调用方先判断。
     */
    public static void cancel(NpcEntity npc) {
        PENDING.remove(npc);
    }

    /**
     * 每 tick 驱动到期清除。
     * <p>
     * 先 {@code List.copyOf} 再遍历：本轮里 {@code clearEmote()} 会回调 {@link #cancel}，
     * 直接遍历 {@code keySet()} 会踩 {@code ConcurrentModificationException}。
     */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        for (NpcEntity npc : List.copyOf(PENDING.keySet())) {
            Entry entry = PENDING.get(npc);
            if (entry == null) {
                // 本轮早先被 cancel 掉了（例如玩家在这几 tick 内换了表情）
                continue;
            }
            // 实体没了 ⇒ 只丢计划。**绝不能**对已移除的实体动手。
            if (npc.isRemoved()) {
                PENDING.remove(npc);
                continue;
            }
            if (npc.level().getGameTime() < entry.deadline()) {
                continue;
            }
            // 先丢计划再清：clearEmote() 自己会回调 cancel()，顺序上先移除可避免重入歧义。
            // ★ F3（2026-10-14）：这只 NPC 的表情正被一段过场**押后** ⇒ 现在不许清它。
            //   服务端按自己的钟到期时，客户端那边的一次性动画可能还在播（实机日志："只剩 44 ticks"就被清掉
            //   ⇒ 动画播一半就停、而 CG 运镜还在）。过场结束（客户端上报 / +200 兜底到期）后本表自然失效，
            //   下一次巡检就会照常清掉它 —— 所以这里**不需要**任何解锁配对，不存在"忘了解锁"的风险。
            if (CgCompletionHandler.ownsEmote(npc)) {
                continue;
            }
            PENDING.remove(npc);
            if (npc.emote().equals(entry.name())) {
                npc.clearEmote();
                // ★ 这一行是"修复真的生效"的**唯一直接证据** —— 清除本身不会引发任何其它日志。
                //   它与 schedule() 那条配对（"will be cleared in N ticks" → "cleared"），
                //   让"走远回来不再重播"这件事也能从日志交叉验证，而不只靠眼睛看。
                BeLoongCore.LOGGER.info(
                        "[BeLoong] emote '{}' on {} cleared (one-shot finished)",
                        entry.name(), npc.getType());
            }
        }
    }

    /**
     * 关服时**整表清空**。
     * <p>
     * ⚠️ 为什么必须有这一条：{@link #PENDING} 是**静态**字段，生命周期是整个 JVM（不是存档）。
     * 而关服之后那个维度的时钟就**冻结**了（{@code getGameTime()} 不再前进）⇒
     * 表里没到期的项会**永远不到期**，于是它强引用着那只 {@code NpcEntity} ——
     * 连带它所在的 {@code ServerLevel}、区块、以及整个旧存档对象图，一起活到本进程结束
     * （单人游戏里"退回主菜单再进新世界"正是这种情形）。
     */
    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
    }

    /**
     * 单个维度卸载时，只丢掉**属于它**的表项。
     * <p>
     * 理由同上：那个维度的时钟不再前进 ⇒ 它的表项永不到期，而每条都强引用着一个实体。
     * 只清那个维度，别的维度的待清计划不受影响。
     */
    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        PENDING.keySet().removeIf(npc -> npc.level() == level);
    }
}
