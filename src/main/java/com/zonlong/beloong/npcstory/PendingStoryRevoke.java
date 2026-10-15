package com.zonlong.beloong.npcstory;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * <b>服务器级"待撤回剧情"表</b>（用户 2026-10-14 裁定，方案"乙"）。
 *
 * <h2>它存在的唯一理由</h2>
 * 撤回进度需要一个**在线的** {@code ServerPlayer}（{@code PlayerAdvancements} 只存在于在线玩家的内存里），
 * 而"删分身"不需要 —— 所以主人**离线**时租约到期，我们能做到的只有：<b>当场删掉那只分身</b>，
 * 然后把"这个人回来时要撤进度"记在这里，等他下次登录时消费。
 * <p>
 * ⚠️ 不记会怎样：玩家上线时手里只剩"有 {@code root}、却找不到分身"这一条判据 ⇒
 * 而"找不到"≠"不存在"（分身可能在**没加载的区块**里）⇒ 会**误撤正在正常玩的人** ——
 * 那正是已被删除的 D21 的原罪。本表就是把"是我们删的"这件事**显式记住**，从而不必去猜。
 *
 * <h2>⚠️ 已知窗口（写进设计文档，不掩盖）</h2>
 * 若在"离线删除之后、玩家登录之前"服务器崩溃，且本表**尚未落盘** ⇒ 该玩家会停在
 * "有 {@code root}、无分身、锚点又不显示"的状态 ⇒ 需管理员手工撤一次进度
 * （{@code /advancement revoke} 整条链）。本表用主世界存储（最早且最频繁落盘）+ 每次改动
 * 立刻 {@code setDirty()} 把窗口压到最小。
 *
 * <h2>消费口径</h2>
 * 登录后被消费时**再判一次**"该剧情已开始 ∧ 未通关"才撤 —— 与既有规则一致：
 * 通关玩家的进度**永不被误撤**。
 */
public final class PendingStoryRevoke extends SavedData {

    private static final String FILE_ID = "beloong_pending_story_revoke";
    private static final String KEY = "players";

    private final Set<UUID> pending = new HashSet<>();

    public static SavedData.Factory<PendingStoryRevoke> factory() {
        return new SavedData.Factory<>(PendingStoryRevoke::new, PendingStoryRevoke::load, null);
    }

    private static PendingStoryRevoke load(CompoundTag tag, HolderLookup.Provider registries) {
        PendingStoryRevoke data = new PendingStoryRevoke();
        ListTag list = tag.getList(KEY, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            try {
                data.pending.add(UUID.fromString(list.getString(i)));
            } catch (IllegalArgumentException ignored) {
                // 坏档不崩：忽略这一条（它是"待撤回"的提示，不是权威数据）
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (UUID id : this.pending) {
            list.add(StringTag.valueOf(id.toString()));
        }
        tag.put(KEY, list);
        return tag;
    }

    /** 记一条"该玩家回来时要撤进度"（主人离线时租约到期用）。 */
    public static void mark(MinecraftServer server, UUID player) {
        PendingStoryRevoke data = get(server);
        if (data.pending.add(player)) {
            data.setDirty();
            BeLoongCore.LOGGER.info(
                    "[BeLoong] npc story: player '{}' is offline and their double expired — the double was"
                            + " removed now, and the story will be revoked when they log back in",
                    player);
        }
    }

    /** 消费一条：返回 true 表示"这次登录确实有待撤回的事要做"。 */
    public static boolean consume(MinecraftServer server, UUID player) {
        PendingStoryRevoke data = get(server);
        if (!data.pending.remove(player)) {
            return false;
        }
        data.setDirty();
        return true;
    }

    private static PendingStoryRevoke get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), FILE_ID);
    }
}
