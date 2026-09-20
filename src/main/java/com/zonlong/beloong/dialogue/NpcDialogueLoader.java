package com.zonlong.beloong.dialogue;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
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
 * 从 {@code data/beloong/beloong/npc_dialogue/*.json} 读取，**在客户端资源重载时刷新**。
 * <p>
 * <b>为什么注册在客户端</b>：对话的全部行为（触发判定 + 渲染）都发生在客户端，
 * 数据又随模组 jar 分发（客户端同样把它当资源包加载），因此**不需要任何网络同步**。
 * 注册点在 {@code BeLoongCoreClient#registerClientReloadListeners}。
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
            NpcDialogueEntry.CODEC.parse(JsonOps.INSTANCE, file.getValue())
                    .resultOrPartial(error -> BeLoongCore.LOGGER.error(
                            "Failed to parse npc dialogue file '{}': {}", file.getKey(), error))
                    .ifPresent(entry -> {
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
        // 这行日志同时是"客户端能否读到 jar 内 data/"（设计文档风险 R0）的运行时确认点：
        // 只要它能打印出数字，就说明监听器被调用、客户端资源管理器确实提供了该目录。
        BeLoongCore.LOGGER.info("[BeLoong] reloaded npc dialogues: {} entrie(s)", entries.size());
    }

    /** 查询某实体类型是否配置了对话。 */
    @Nullable
    public NpcDialogueEntry get(EntityType<?> type) {
        return entries.get(type);
    }
}
