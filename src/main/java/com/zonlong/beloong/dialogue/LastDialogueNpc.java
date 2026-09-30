package com.zonlong.beloong.dialogue;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「<b>该玩家最近对话过的 NPC</b>」映射（服务端内存态）。
 * <p>
 * 存在的原因：ChatBox 的选项 {@code click} 是以**玩家**身份执行命令的，命令里**无法**表达
 * "玩家正在对话的那个 NPC"（{@code @e[...]} 选不出来）⇒ 只能由我们这边把这件事记下来，
 * 让 {@code /beloong route <名>} 用"哪个玩家、最近跟谁对话"来定位目标。
 * <p>
 * <b>为什么这份映射一定是新鲜的</b>：它只由 {@link NpcDialogueHandler}（右键受理，唯一入口）写；
 * 而玩家在 ChatBox 那段对话的界面里**不可能**再右键别的 NPC ⇒ 从"写"到"命令被执行"之间，
 * 映射不会被人改掉。设计依据见 {@code docs/plans/2026-09-30-npc-route-system-design.md} 的 D2。
 * <p>
 * 存 <b>UUID</b> 而不是实体 id：实体 id 只在加载期间有效，UUID 才是稳定的身份。
 * 内存态、不落盘：它与"当前这次对话"绑定，重启后本就该忘掉（用户没有对话过，命令也就无从触发）。
 */
public final class LastDialogueNpc {

    private static final Map<UUID, UUID> LAST = new HashMap<>();

    private LastDialogueNpc() {}

    /** 记下"该玩家最近对话过的 NPC"。由 {@link NpcDialogueHandler} 在受理右键时调用。 */
    public static void remember(ServerPlayer player, Entity npc) {
        LAST.put(player.getUUID(), npc.getUUID());
    }

    /**
     * 解析出"该玩家最近对话过的 NPC"的实体；找不到返回 {@code null}（调用方必须报错，不能静默）。
     * <p>
     * 先查玩家**当前维度**（正常路径：刚右键过，必在同一维度），再兜底遍历其它维度
     * （玩家在两 tick 之间换了维度这种极小概率）。实体已卸载 ⇒ 返回 null。
     */
    @Nullable
    public static Entity resolve(ServerPlayer player) {
        UUID id = LAST.get(player.getUUID());
        if (id == null) {
            return null;
        }
        Entity found = player.serverLevel().getEntity(id);
        if (found != null) {
            return found;
        }
        for (ServerLevel level : player.server.getAllLevels()) {
            if (level == player.serverLevel()) {
                continue;
            }
            found = level.getEntity(id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
