package com.zonlong.beloong.npcstory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.cg.CgRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /**
     * 允许出现的字段名。**必须与 {@link NpcStory#CODEC} 的字段逐一对齐** ——
     * DFU 的 codec 对写错的键是静默忽略的，所以"未知名拒绝"是这里唯一能挡住拼错的地方。
     */
    private static final Map<String, Set<String>> KNOWN_FIELDS = Map.of(
            // 顶层：四个基础字段 + 两个语义组
            "", Set.of("start_advancement", "end_advancement", "spawn", "cg",
                    "lease", "dimension", "_comment"),
            // 租约组
            "lease", Set.of("ticks", "clear_on_logout", "keep_after_finish",
                    "warn_before_ticks", "warn_text", "warn_key", "_comment"),
            // 维度组
            "dimension", Set.of("host", "enforce", "grace_ticks",
                    "warn_before_ticks", "warn_text", "warn_key", "_comment"));

    /**
     * 旧版**平铺**写法 → 新位置。命中时 ERROR 里附上提示 ——
     * 直接解决"改了代码、数据文件忘了跟着改"这类最难自己发现的错误。
     */
    private static final Map<String, String> LEGACY_KEYS = Map.of(
            "lifetime_ticks", "lease.ticks",
            "clear_on_logout", "lease.clear_on_logout",
            "keep_after_finish", "lease.keep_after_finish",
            "required_dimension", "dimension.host (and add dimension.enforce if you want the"
                    + " \"left the dimension\" cleanup)",
            "dimension_grace_ticks", "dimension.grace_ticks");

    /** 供"加载后一次性自检"判断是否发生过重载（每次 {@code apply} 自增）。 */
    private int reloadStamp;

    /** 由 {@code BeLoongCore} 在 {@code AddReloadListenerEvent} 里绑定，用于加载期校验 {@code dimension.host}。 */
    private RegistryAccess registryAccess;

    private NpcStoryLoader() {
        super(new Gson(), "beloong/npc_story");
    }

    /**
     * 递归收集未知字段名，返回 {@code "组.键"} 形式的路径（顶层只给键名）。
     * <p>
     * ⚠️ 必须递归：嵌层组里写错一个键，DFU 会**静默取缺省**，症状是"字段白写、毫无反馈"。
     */
    private static List<String> unknownKeys(JsonObject json, String group) {
        List<String> unknown = new ArrayList<>();
        Set<String> allowed = KNOWN_FIELDS.getOrDefault(group, Set.of());
        for (String key : json.keySet()) {
            if (allowed.contains(key)) {
                continue;
            }
            unknown.add(group.isEmpty() ? key : group + "." + key);
            // 已知的组即使自身合法也要继续往里查；未知的键则不再下降（避免报一堆无意义的路径）
            JsonElement child = json.get(key);
            if (child instanceof JsonObject nested && KNOWN_FIELDS.containsKey(key)) {
                unknown.addAll(unknownKeys(nested, key));
            }
        }
        return unknown;
    }

    /** 由 {@code BeLoongCore} 在 {@code AddReloadListenerEvent} 中调用（每次重载都会重新绑定）。 */
    public void bindRegistryAccess(RegistryAccess access) {
        this.registryAccess = access;
    }

    /** 本次加载的代号（自增）；调用方据此判断"是否发生过重载"以决定要不要重跑一次性自检。 */
    public int reloadStamp() {
        return this.reloadStamp;
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

            // 先拒"未知字段名"：DFU 的 codec 对写错的键是**静默忽略**（取默认值顶上），
            // 症状是"字段白写、毫无反馈"。已知键集合必须与 NpcStory.CODEC 的字段**逐一对齐**。
            if (file.getValue() instanceof JsonObject json) {
                List<String> unknown = unknownKeys(json, "");
                if (!unknown.isEmpty()) {
                    // 报错不静默：逐条点名到"哪个组里的哪个键"，旧写法额外给迁移提示
                    for (String path : unknown) {
                        String legacy = LEGACY_KEYS.get(path);
                        if (legacy != null) {
                            BeLoongCore.LOGGER.error(
                                    "npc story file '{}': '{}' is the OLD flat layout — it must now be"
                                            + " written as '{}'", file.getKey(), path, legacy);
                        } else {
                            BeLoongCore.LOGGER.error(
                                    "npc story file '{}': unknown field '{}' — file rejected"
                                            + " (check spelling)", file.getKey(), path);
                        }
                    }
                    return;
                }
            }

            result.ifSuccess(story -> {
                // getOptional 而不是先 get() 再 containsKey()：后者会先拿到注册表的**默认值**再判断，
                // 读起来像"收下了一个错的类型"。与 NpcDialogueEntry.decodeEntity 同款写法。
                Optional<EntityType<?>> maybeType = BuiltInRegistries.ENTITY_TYPE.getOptional(file.getKey());
                if (maybeType.isEmpty()) {
                    BeLoongCore.LOGGER.error(
                            "npc story file '{}' names an unknown entity type, ignored", file.getKey());
                    return;
                }
                // ── 取值校验：一律"整文件拒绝 + ERROR"，绝不静默取缺省 ──
                if (story.lease().ticks() < -1L) {
                    BeLoongCore.LOGGER.error(
                            "npc story file '{}': lease.ticks {} — only -1 (permanent) or >= 0 is allowed,"
                                    + " file rejected", file.getKey(), story.lease().ticks());
                    return;
                }
                if (story.lease().warnBeforeTicks().orElse(0L) < 0L) {
                    BeLoongCore.LOGGER.error(
                            "npc story file '{}': lease.warn_before_ticks must be >= 0, file rejected",
                            file.getKey());
                    return;
                }
                if (story.dimension().graceTicks() < 0L) {
                    BeLoongCore.LOGGER.error(
                            "npc story file '{}': dimension.grace_ticks must be >= 0, file rejected",
                            file.getKey());
                    return;
                }
                if (story.dimension().warnBeforeTicks().orElse(0L) < 0L) {
                    BeLoongCore.LOGGER.error(
                            "npc story file '{}': dimension.warn_before_ticks must be >= 0, file rejected",
                            file.getKey());
                    return;
                }
                if (story.lease().warnText().filter(String::isBlank).isPresent()
                        || story.dimension().warnText().filter(String::isBlank).isPresent()) {
                    BeLoongCore.LOGGER.error(
                            "npc story file '{}': warn_text must not be blank (remove the field to use the"
                                    + " default key), file rejected", file.getKey());
                    return;
                }
                if (story.lease().warnKey().filter(k -> !isValidKey(k)).isPresent()
                        || story.dimension().warnKey().filter(k -> !isValidKey(k)).isPresent()) {
                    BeLoongCore.LOGGER.error(
                            "npc story file '{}': warn_key must be a valid resource location"
                                    + " (e.g. 'beloong.npc.story.expiring'), file rejected", file.getKey());
                    return;
                }
                if (story.dimension().enforce() && story.dimension().host().isEmpty()) {
                    BeLoongCore.LOGGER.error(
                            "npc story file '{}': dimension.enforce=true requires dimension.host"
                                    + " (otherwise there is no dimension to leave), file rejected",
                            file.getKey());
                    return;
                }
                if (story.lease().isPermanent() && story.lease().warnBeforeTicks().orElse(0L) > 0L) {
                    BeLoongCore.LOGGER.error(
                            "npc story file '{}': lease.ticks=-1 (permanent) cannot have"
                                    + " lease.warn_before_ticks > 0 (it would never expire), file rejected",
                            file.getKey());
                    return;
                }
                // D9/D12：cg 名错了**不拒绝整文件**（坏 CG 数据不毁剧情），但必须在**加载期**点名报 ERROR，
                // 而不是等到玩家触发时才 WARN。列表给出全部已注册的 CG 名，便于当场改对。
                if (story.cg().filter(name -> !CgRegistry.names().contains(name)).isPresent()) {
                    BeLoongCore.LOGGER.error(
                            "npc story file '{}': cg '{}' is not registered — the story still loads,"
                                    + " but no entrance CG will be played (registered: {})",
                            file.getKey(), story.cg().get(), String.join(", ", CgRegistry.names()));
                }
                if (story.dimension().host().isPresent()) {
                    if (this.registryAccess == null) {
                        // 属于我们的接线问题（不在数据作者身上）：绑定点在 BeLoongCore 的事件里。
                        BeLoongCore.LOGGER.warn(
                                "npc story file '{}': registry access not bound — skipping"
                                        + " dimension.host validation", file.getKey());
                    } else if (!this.registryAccess.registryOrThrow(Registries.LEVEL_STEM)
                            .containsKey(story.dimension().host().get())) {
                        BeLoongCore.LOGGER.error(
                                "npc story file '{}': dimension.host '{}' does not exist, file rejected",
                                file.getKey(), story.dimension().host().get());
                        return;
                    }
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

        // 用 LinkedHashMap + unmodifiableMap 而不是 Map.copyOf：后者是不可变但**无序**的，
        // 而 all() 的注释承诺"顺序可复现"（与 NpcRouteLoader 显式 sorted 同款取舍）。
        Map<EntityType<?>, NpcStory> ordered = new LinkedHashMap<>();
        parsed.entrySet().stream()
                .sorted(Comparator.comparing(e -> String.valueOf(
                        BuiltInRegistries.ENTITY_TYPE.getKey(e.getKey()))))
                .forEach(e -> ordered.put(e.getKey(), e.getValue()));
        this.stories = Collections.unmodifiableMap(ordered);
        // 同时打印扫描数与装载数 —— 理由同 NpcDialogueLoader：数据放错树时扫描数恒为 0，
        // 而症状是"一切都像没生效、且不报任何错"。
        this.reloadStamp++;
        BeLoongCore.LOGGER.info(
                "[BeLoong] reloaded npc stories: {} file(s) scanned, {} story(ies) loaded",
                files.size(), this.stories.size());
    }

    /**
     * 翻译键的**格式**校验：必须是一个合法的 resource location
     * （{@code beloong.npc.story.expiring} 这种点分形式是合法的 —— 点允许出现在 path 里）。
     * <p>
     * ⚠️ 只能查格式：**键是否存在是客户端语言文件的事**，服务端看不到（设计 D10）。
     */
    private static boolean isValidKey(String key) {
        return ResourceLocation.tryParse(key) != null;
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
