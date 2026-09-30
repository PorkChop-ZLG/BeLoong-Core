package com.zonlong.beloong.dialogue;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「原版进度 NPC 阶段系统」的**唯一判定处** —— 一条回复选项当前是否该显示。
 * <p>
 * 阶段状态存在**原版进度**里（落盘、跨维度、跨死亡、跨重登都由原版负责），本类只做**只读**判定：
 * <pre>
 *   可见 ⇔ (start 省略 或 已完成 start) 且 (end 省略 或 未完成 end)
 * </pre>
 * 于是三种状态自然表达：都没有 ⇒ 未开始；只有 start ⇒ 进行中（可选可点）；
 * 两者都有 ⇒ 已完成（选项消失）。"**结束优先于开始**"是这条式子的自然结果，不需要额外分支。
 * <p>
 * <b>判定只在服务端做</b>（{@link NpcDialogueHandler} 生成载荷时、{@link NpcDialogueReplyPayload}
 * 受理点击时各一次）⇒ 客户端既不持有进度 id，也无法伪造或依赖过期的推断。
 * <p>
 * <b>为什么"未知的进度 id"必须与"存在但未完成"分开表达</b>：数据里写错一个 id 时，
 * 若把"查不到"当成"未完成"，那么 {@code end} 那一侧就会 {@code !未完成 == true} ⇒ **提前显示**
 * 本该隐藏的选项。故本类用三态、并让"未知"一律判为**不可见**（fail-closed），同时留一条英文 WARN
 * 让作者能定位（每个 id 只报一次，与 {@code ChatBoxBridge}/{@code EmoteAnimationLookup} 同一先例）。
 * <p>
 * 设计文档：{@code docs/plans/2026-09-29-npc-advancement-stage-system-design.md}。
 */
public final class NpcDialogueStage {

    private NpcDialogueStage() {
    }

    /** 进度状态三态：{@code UNKNOWN} 表示这个 id 在服务端根本不存在（数据写错）。 */
    private enum StageState {
        UNKNOWN,
        DONE,
        NOT_DONE
    }

    /** 已经报过"进度不存在"的 id —— 每个 id 只报一次，避免右键连点刷屏。 */
    private static final Set<ResourceLocation> WARNED = new HashSet<>();

    /** 查一个进度的状态（照 {@code StructureEffectHandler} 的既有先例：先取 holder 再判 isDone）。 */
    private static StageState stateOf(ServerPlayer player, ResourceLocation id) {
        var holder = player.server.getAdvancements().get(id);
        if (holder == null) {
            return StageState.UNKNOWN;
        }
        return player.getAdvancements().getOrStartProgress(holder).isDone()
                ? StageState.DONE
                : StageState.NOT_DONE;
    }

    /**
     * 这条回复当前是否可见。规则见类注释；任一侧的进度 id 查不到 ⇒ 直接判不可见（fail-closed）。
     */
    public static boolean visible(ServerPlayer player, NpcDialogueEntry.Reply reply) {
        if (reply.startAdvancement().isPresent()) {
            StageState state = stateOf(player, reply.startAdvancement().get());
            if (state == StageState.UNKNOWN) {
                warnUnknown(reply.startAdvancement().get());
                return false;
            }
            if (state != StageState.DONE) {
                return false;
            }
        }

        if (reply.endAdvancement().isPresent()) {
            StageState state = stateOf(player, reply.endAdvancement().get());
            if (state == StageState.UNKNOWN) {
                warnUnknown(reply.endAdvancement().get());
                return false;
            }
            if (state == StageState.DONE) {
                return false;
            }
        }

        return true;
    }

    /**
     * 可见回复在 {@code entry.replies()} 里的**原始下标**（保持数据顺序）。
     * <p>
     * 刻意回传"数据下标"而不是"可见列表下标"：玩家在对话界面打开期间若阶段状态发生变化
     * （例如别处发放了结束进度），可见列表会缩水，用可见下标就会**点错回复**；
     * 带上数据下标后，服务端受理点击时按原表复检即可。
     */
    public static List<Integer> visibleIndices(ServerPlayer player, NpcDialogueEntry entry) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < entry.replies().size(); i++) {
            if (visible(player, entry.replies().get(i))) {
                indices.add(i);
            }
        }
        return indices;
    }

    /** 英文 WARN，每个 id 只报一次（项目要求：日志一律纯英文）。 */
    private static void warnUnknown(ResourceLocation id) {
        if (WARNED.add(id)) {
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] npc dialogue reply points at an unknown advancement: {} "
                            + "(the reply stays hidden until the id is fixed)",
                    id);
        }
    }
}
