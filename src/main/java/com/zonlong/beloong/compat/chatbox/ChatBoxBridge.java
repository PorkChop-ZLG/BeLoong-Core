package com.zonlong.beloong.compat.chatbox;

import com.zhenshiz.chatbox.data.ChatBoxDialogues;
import com.zhenshiz.chatbox.data.ChatBoxDialoguesLoader;
import com.zhenshiz.chatbox.utils.chatbox.ChatBoxCommandUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * NPC 对话系统 → ChatBox 的**唯一**桥接点。
 * <p>
 * <b>全项目只有这个类 import ChatBox 的类。</b>把它单独放在 {@code compat/chatbox/} 下，与
 * {@code compat/{dragonsurvival,ftbchunks,ironsspellbooks,betterendisland}} 同一惯例：
 * 第三方耦合只存在于一个文件里，将来对方改 API 时只需要改这里。
 * <p>
 * ChatBox 在本项目是 {@code type="required"} / {@code side="BOTH"} 的依赖
 * （{@code src/main/templates/META-INF/neoforge.mods.toml}）⇒ 这里的硬引用与模组元数据一致，
 * 不存在"缺依赖才崩"的场景；隔离的目的是**收敛耦合面**，不是容错。
 * <p>
 * <b>为什么调 {@code serverSkipDialogues} 而不是让玩家敲 {@code /chatbox skip}</b>：
 * 命令层带着两道门槛 —— 权限 {@code hasPermission(2)}（{@code ChatBoxCommand.java:37}）与
 * {@code maxTriggerCount} 判据（{@code :201-203}）—— 而**两道都不在这个 util 里**。
 * 直接调 util 因此不需要 op、也不受触发次数限制，且它会自己把
 * {@code ChatBoxPayload.SyncEntityData} 与 {@code SimplePayload("skip_chat_s2c")} 发给客户端，
 * 我们不必自己发包。ChatBox 自己也正是这么调的（{@code ChatBoxServerEvents.java:94} 队内广播、
 * {@code ChatBoxDialoguesLoader.java:85} criteria 触发）。
 * <p>
 * <b>硬约束</b>：必须在**服务端主线程**调用（内部走 {@code player.connection.send}），且
 * {@code player} 不能为 null（那边没有判空）。{@code index} <b>绝不能传 null</b> —— 它会被编码成
 * 字符串 {@code "null"}，让客户端 {@code Integer.parseInt} 抛异常；本类只接受 {@code int}，
 * 把这条约束在类型层面消掉。
 * <p>
 * 设计文档：{@code docs/plans/2026-09-29-npc-dialogue-chatbox-bridge-design.md}。
 */
public final class ChatBoxBridge {

    private ChatBoxBridge() {
    }

    /**
     * 已经报过"目标不存在"的 {@code rl#group#index} 集合。
     * <p>
     * ChatBox 那侧几乎没有**服务端**失败信号：服务端 util 完全无校验、无日志
     * （{@code ChatBoxCommandUtil.java:65-69}）；只有**客户端**会打一条 —— 且只在"组缺失或为空"时
     * （{@code ChatBoxUtil} 的 {@code group "{}" not found or is empty!}，{@code ChatBoxUtil.java:143-154}）。
     * ⇒ 不预检就是"玩家点了回复毫无反应、日志里也查不出原因"。但玩家可能连点，
     * 所以这里**每个目标只报一次**（"只报一次"的做法同 {@code EmoteAnimationLookup}；
     * 那边用 {@code ConcurrentHashMap.newKeySet()}，这里用普通 {@code HashSet} —— 本类只在服务端主线程被调用）。
     */
    private static final Set<String> WARNED = new HashSet<>();

    /**
     * 预检：ChatBox 那侧到底有没有这段对话的这个组这一页。
     * <p>
     * 用**ChatBox 自己的表**（都是 public 字段）而不是我们另存一份：这样预检的结论与它稍后真正
     * 查表的结果一致。顺带还堵住了它的一条脆弱路径 —— 客户端渲染成功后会回执 {@code SKIP_CHAT_C2S}，
     * 而服务端在处理该回执时**不判空、不查越界**（{@code SimplePayload.java:164}）；
     * 只要我们放行的目标确实存在，那条路径就不会被我们触发。
     *
     * @return true 表示可以安全调用 {@link #open}；false 时已（至多一次地）打过英文 WARN
     */
    public static boolean canOpen(ResourceLocation dialogues, String group, int index) {
        List<ChatBoxDialogues.Dialogues> pages = null;
        Set<String> groups = ChatBoxDialoguesLoader.dialoguesGroupMap.get(dialogues);
        if (groups != null && groups.contains(group)) {
            ChatBoxDialogues parsed = ChatBoxDialoguesLoader.parsedDialogues.get(dialogues);
            if (parsed != null) {
                pages = parsed.dialogues.get(group);
            }
        }
        if (pages != null && index >= 0 && index < pages.size()) {
            return true;
        }

        String key = dialogues + "#" + group + "#" + index;
        if (WARNED.add(key)) {
            // 英文（项目要求：日志一律纯英文）
            com.zonlong.beloong.BeLoongCore.LOGGER.warn(
                    "[BeLoong] npc dialogue reply points at a missing ChatBox dialogue: {} group={} index={} "
                            + "(known groups: {})",
                    dialogues, group, index,
                    ChatBoxDialoguesLoader.dialoguesGroupMap.getOrDefault(dialogues, Set.of()));
        }
        return false;
    }

    /**
     * 让该玩家立刻开始 ChatBox 的某段对话。调用前**必须**先过 {@link #canOpen}。
     * <p>
     * 本方法自己不发任何包 —— ChatBox 会发同步实体数据与 {@code skip_chat_s2c}。
     */
    public static void open(ServerPlayer player, ResourceLocation dialogues, String group, int index) {
        ChatBoxCommandUtil.serverSkipDialogues(player, dialogues, group, index);
    }
}
