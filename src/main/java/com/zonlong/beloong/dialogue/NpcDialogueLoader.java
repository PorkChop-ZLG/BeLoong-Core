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
 * NPC 对话数据加载器（**服务端**）。
 * <p>
 * 从 {@code data/beloong/beloong/npc_dialogue/*.json} 读取，随模组 jar 分发，
 * 由 {@code AddReloadListenerEvent} 注册 —— **启动与 {@code /reload} 都会重新加载**。
 * 注册点在 {@code BeLoongCore#addServerReloadListeners}。
 * <p>
 * <b>为什么目录字符串仍然是 {@code "beloong/npc_dialogue"}</b>：它是 <b>PackType 相对</b>的，
 * 不是绝对路径。服务端的资源管理器以 {@code PackType.SERVER_DATA} 构造
 * （{@code MinecraftServer.java:1511}），客户端的以 {@code CLIENT_RESOURCES} 构造
 * （{@code Minecraft.java:491}），路径解析再按 pack type 加目录前缀
 * （{@code FallbackResourceManager} → {@code PackResources.listResources(packType, …)}）。
 * ⇒ **同一个字符串，注册在服务端监听器上读 {@code data/}，注册在客户端监听器上读 {@code assets/}。**
 * <p>
 * 首版把本 loader 注册在**客户端**、数据放在 {@code assets/}（首版 R3）—— 那不是必须的，
 * 只是当时"纯客户端 ⇒ 零网络包"这一取舍的产物；而该取舍的真正前提是"v1 没有副作用"，
 * 在引入进度判据后即被推翻（见 {@code docs/plans/2026-09-25-npc-dialogue-data-driven-design.md}）。
 * <p>
 * <b>数据流</b>：服务端加载 → 玩家右键命中时由 {@code NpcDialogueHandler} 把**那一条**
 * 经 {@code NpcDialogueOpenPayload} 下发给该玩家。**客户端不持有全表**，
 * 因此没有客户端缓存、也没有登录全量同步。
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

    /**
     * 解析后的对话表。
     * <p>
     * <b>刻意不加 {@code volatile}</b> —— 这一段是回源码核实过的，改动前请先读完：
     * <ul>
     *   <li>{@code apply} 由 <b>gameExecutor</b> 执行，<b>不是</b>后台工作线程：
     *       {@code SimplePreparableReloadListener#reload} 的形态是
     *       {@code supplyAsync(prepare, backgroundExecutor).thenAcceptAsync(apply, gameExecutor)}
     *       —— 只有 {@code prepare}（扫目录 + Gson 解析）在后台线程，而它不碰本字段。</li>
     *   <li>服务端侧的 gameExecutor 就是 {@code MinecraftServer} 自己：
     *       {@code MinecraftServer.java:1512-1519} 把 {@code (this.executor, this)} 传给
     *       {@code ReloadableServerResources.loadResources}；{@code MinecraftServer} 继承
     *       {@code BlockableEventLoop}，其 {@code execute} 把任务放进<b>服务端主线程</b>的队列。</li>
     * </ul>
     * ⇒ {@code apply}（写）与 {@code get()}（{@code NpcDialogueHandler} 在右键事件里读）
     * <b>同在服务端主线程</b>，不存在跨线程可见性问题，故无需 {@code volatile}。
     * <p>
     * 首版代码审查的 S3 判"不需要 volatile"，理由是"两者都在客户端主线程"。搬迁到服务端后
     * 线程换了、但"两者同处一条主线程"这个**性质没变**，所以 S3 的结论依然成立。
     * （注意：S3 的**理由**要按上面的事实重新表述 —— 不要说成"客户端主线程"。）
     */
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
        // 同时打印"扫描到的文件数"与"成功装载条数" —— 这是本功能**唯一的运行时观测点**：
        // 数据放错树（放 assets/ 或目录名写错）时的症状是"右键毫无反应、且不报任何错"，
        // 而扫描数恒为 0 会立刻指向它（首版 R0 的教训）。
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
