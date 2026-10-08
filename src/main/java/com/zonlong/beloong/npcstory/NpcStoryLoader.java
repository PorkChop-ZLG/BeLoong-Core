package com.zonlong.beloong.npcstory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * NPC 剧情声明加载器（**服务端**）。
 * <p>
 * 从 {@code data/beloong/beloong/npc_story/*.json} 读取（目录字符串是 **PackType 相对**的，
 * 理由与 {@code NpcDialogueLoader} / {@code NpcRouteLoader} 的类注释相同），由
 * {@code AddReloadListenerEvent} 注册 ⇒ **启动与 {@code /reload} 都会重新加载**。
 * 注册点在 {@code BeLoongCore#addServerReloadListeners}（**只注册一次** —— 本项目犯过重复注册的错）。
 * <p>
 * <b>与路线加载器的两处不同</b>：
 * <ol>
 *   <li><b>按实体类型索引</b>：文件的 id 就是实体类型的 id（{@code data/<ns>/beloong/npc_story/mo.json}
 *       ⇒ {@code beloong:mo}）⇒ 加载时解析成 {@link EntityType}，查不到的**整文件拒绝**并打 ERROR
 *       （给不存在的实体写剧情是死数据，静默收下只会让人困惑）。</li>
 *   <li><b>多一层字段校验</b>：{@code spawn} 目前只接受 {@code "anchor"}；未知值同样**整文件拒绝**
 *       （设计里明确要求"不静默失效"）。</li>
 * </ol>
 * <p>
 * <b>失败隔离</b>：单个文件解析失败只丢弃该文件并打 ERROR，绝不中断其余文件的加载
 * （与 {@code NpcDialogueLoader} 同款）。
 */
public class NpcStoryLoader extends SimpleJsonResourceReloadListener {

    public static final NpcStoryLoader INSTANCE = new NpcStoryLoader();

    /**
     * 解析后的剧情表，键是**实体类型**。
     * <p>
     * 与 {@code NpcDialogueLoader.entries} 同款：**刻意不加 {@code volatile}** ——
     * {@code apply}（写）与 {@code get()}（服务端主线程的实体 tick / 事件读）同在服务端主线程。
     */
    private Map<EntityType<?>, NpcStory> stories = Map.of();

    private NpcStoryLoader() {
        super(new Gson(), "beloong/npc_story");
    }

    @Override
    protected void apply(@NotNull Map<ResourceLocation, JsonElement> files,
                         @NotNull ResourceManager manager,
                         @NotNull ProfilerFiller profiler) {
        Map<EntityType<?>, NpcStory> parsed = new HashMap<>();

        for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            // ifError/ifSuccess 而不是 resultOrPartial：partial 的残件会被当成成功收下
            // ⇒ 一条字段残缺的剧情声明却仍然注册（与两个既有加载器同款取舍）。
            DataResult<NpcStory> result = NpcStory.CODEC.parse(JsonOps.INSTANCE, file.getValue());

            result.ifError(error -> BeLoongCore.LOGGER.error(
                    "Failed to parse npc story file '{}': {}", file.getKey(), error.message()));

            result.ifSuccess(story -> {
                // getOptional 而不是先 get() 再 containsKey()：后者会先拿到注册表的**默认值**再判断，
                // 读起来像"收下了一个错的类型"。与 NpcDialogueEntry.decodeEntity 同款写法。
                Optional<EntityType<?>> maybeType = BuiltInRegistries.ENTITY_TYPE.getOptional(file.getKey());
                if (maybeType.isEmpty()) {
                    BeLoongCore.LOGGER.error(
                            "npc story file '{}' names an unknown entity type, ignored", file.getKey());
                    return;
                }
                if (!NpcStory.SPAWN_ANCHOR.equals(story.spawn())) {
                    BeLoongCore.LOGGER.error(
                            "npc story file '{}' has unsupported spawn '{}' (only '{}' is supported), ignored",
                            file.getKey(), story.spawn(), NpcStory.SPAWN_ANCHOR);
                    return;
                }
                parsed.put(maybeType.get(), story);
            });
        }

        this.stories = Map.copyOf(parsed);
        // 同时打印扫描数与装载数 —— 理由同 NpcDialogueLoader：数据放错树时扫描数恒为 0，
        // 而症状是"一切都像没生效、且不报任何错"。
        BeLoongCore.LOGGER.info(
                "[BeLoong] reloaded npc stories: {} file(s) scanned, {} story(ies) loaded",
                files.size(), this.stories.size());
    }

    /** 按**实体类型**查剧情声明；没有则返回 {@code null}（调用方据此走"普通 NPC"分支）。 */
    @Nullable
    public NpcStory get(@Nullable EntityType<?> type) {
        return type == null ? null : this.stories.get(type);
    }

    /** 已加载的全部剧情（对账器用；顺序按实体类型的注册名排，保证日志可复现）。 */
    public Map<EntityType<?>, NpcStory> all() {
        return this.stories;
    }
}
