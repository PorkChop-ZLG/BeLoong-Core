package com.zonlong.beloong.dialogue;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * NPC 对话数据加载器。
 * <p>
 * 从 {@code assets/beloong/beloong/npc_dialogue/*.json} 读取，**在客户端资源重载时刷新**。
 * <p>
 * <b>为什么注册在客户端</b>：对话的全部行为（触发判定 + 渲染）都发生在客户端，
 * 数据又随模组 jar 分发（客户端同样把它当资源包加载），因此**不需要任何网络同步**。
 * 注册点在 {@code BeLoongCoreClient#registerClientReloadListeners}。
 * <p>
 * <b>为什么是 {@code assets/} 而不是 {@code data/}</b>：{@code RegisterClientReloadListenersEvent}
 * 交出来的 {@code Minecraft.resourceManager} 是以 {@code PackType.CLIENT_RESOURCES} 构造的
 * （{@code Minecraft.java:487}），而路径解析会按 pack type 加目录前缀
 * （{@code FallbackResourceManager:170} → {@code type.getDirectory()}，即 {@code assets/}），
 * 所以这个监听器**只能看到 {@code assets/} 树**。数据若放在 {@code data/} 下，
 * 客户端永远读不到 —— {@code entries} 恒为空、右键无效，且不会有任何报错。
 * 代价：对话数据**不能**被存档数据包覆盖（设计文档 R2 已接受这一取舍）；
 * 若将来需要，改成"服务端读取 + 登录时下发"即可，先例见本模组的财宝系统
 * （{@code TreasureSyncPayload} + {@code ClientTreasureCache}）。
 * <p>
 * <b>失败隔离</b>：单个文件解析失败只丢弃该文件并打错误日志，绝不中断其余文件的加载。
 * 反面教材见 MCA Conversations 的 {@code DATAPACK.md}：对未知枚举值抛异常的严格解析
 * 会把整个数据包重载（乃至世界创建）一起搞崩。
 * <p>
 * <b>重复绑定</b>：同一实体类型被多个文件绑定时，按 {@link ResourceLocation} 排序后
 * **后处理者胜**，并打 warning。排序是刻意的 —— 让结果不依赖文件系统的枚举顺序。
 */
public class NpcDialogueLoader extends SimpleJsonResourceReloadListener {

    public static final NpcDialogueLoader INSTANCE = new NpcDialogueLoader();

    private Map<EntityType<?>, NpcDialogueEntry> entries = Map.of();

    private NpcDialogueLoader() {
        super(new Gson(), "beloong/npc_dialogue");
    }

    @Override
    protected void apply(@NotNull Map<ResourceLocation, JsonElement> files,
                         @NotNull ResourceManager manager,
                         @NotNull ProfilerFiller profiler) {
        Map<EntityType<?>, NpcDialogueEntry> parsed = new HashMap<>();

        for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            // 刻意用 ifError/ifSuccess 而**不是** resultOrPartial：
            // DFU 的 Codec.list 会保留"部分解码成功"的元素并返回 partial 结果，而
            // resultOrPartial 会把这种残件当成功收下 —— 后果是一页坏数据被静默丢掉、
            // 这条对话却仍然注册（玩家看到被截断的对话，日志还谎称该文件解析失败）。
            // ifSuccess/ifError 只看 result()，Error 即使是 partial 也走 ifError ⇒ 整文件拒绝。
            DataResult<NpcDialogueEntry> result =
                    NpcDialogueEntry.CODEC.parse(JsonOps.INSTANCE, file.getValue());

            result.ifError(error -> BeLoongCore.LOGGER.error(
                    "Failed to parse npc dialogue file '{}': {}", file.getKey(), error.message()));

            result.ifSuccess(entry -> {
                if (entry.pages().isEmpty()) {
                    BeLoongCore.LOGGER.error(
                            "npc dialogue file '{}' declares no pages, ignored", file.getKey());
                    return;
                }
                if (parsed.put(entry.entity(), entry) != null) {
                    BeLoongCore.LOGGER.warn(
                            "Duplicate npc dialogue for entity {}: '{}' overrides an earlier file",
                            entry.entityId(), file.getKey());
                }
            });
        }

        this.entries = Map.copyOf(parsed);
        // 同时打印"扫描到的文件数"与"成功装载条数"：
        // 扫描数就是"客户端能否读到本目录"（原设计风险 R0）的运行时观测点 ——
        // 若它恒为 0，说明目录放错了树（客户端资源管理器只认 assets/，见类注释）。
        BeLoongCore.LOGGER.info(
                "[BeLoong] reloaded npc dialogues: {} file(s) scanned, {} dialogue(s) loaded",
                files.size(), entries.size());
    }

    /** 查询某实体类型是否配置了对话。 */
    @Nullable
    public NpcDialogueEntry get(EntityType<?> type) {
        return entries.get(type);
    }
}
