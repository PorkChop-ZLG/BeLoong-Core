package com.zonlong.beloong.route;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NPC 路线数据加载器（**服务端**）。
 * <p>
 * 从 {@code data/beloong/beloong/npc_route/*.json} 读取（目录字符串是 **PackType 相对**的，
 * 理由与 {@code NpcDialogueLoader} 的类注释相同），由 {@code AddReloadListenerEvent} 注册
 * ⇒ **启动与 {@code /reload} 都会重新加载**。注册点在 {@code BeLoongCore#addServerReloadListeners}。
 * <p>
 * <b>形态刻意照 {@code CgRegistry} 那套</b>（保序表 + {@code names()} 喂 Brigadier 补全 + 按名查找 fail-closed），
 * 区别是它由代码注册、本类由数据文件加载。
 * <p>
 * <b>失败隔离</b>：单个文件解析失败只丢弃该文件并打 ERROR，绝不中断其余文件的加载
 * （与 {@code NpcDialogueLoader} 同款，理由见那边的类注释）。
 */
public class NpcRouteLoader extends SimpleJsonResourceReloadListener {

    public static final NpcRouteLoader INSTANCE = new NpcRouteLoader();

    /**
     * 解析后的路线表。
     * <p>
     * 与 {@code NpcDialogueLoader.entries} 同款：**刻意不加 {@code volatile}** ——
     * {@code apply}（写）与 {@code get()}（服务端主线程的命令/goal 读）同在服务端主线程。
     */
    private Map<ResourceLocation, NpcRoute> routes = Map.of();

    private NpcRouteLoader() {
        super(new Gson(), "beloong/npc_route");
    }

    @Override
    protected void apply(@NotNull Map<ResourceLocation, JsonElement> files,
                         @NotNull ResourceManager manager,
                         @NotNull ProfilerFiller profiler) {
        Map<ResourceLocation, NpcRoute> parsed = new HashMap<>();

        for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            // ifError/ifSuccess 而不是 resultOrPartial：理由见 NpcDialogueLoader 的类注释
            // （partial 的残件会被当成成功收下 ⇒ 一条空路线的对话却仍然注册）。
            DataResult<NpcRoute> result = NpcRoute.CODEC.parse(JsonOps.INSTANCE, file.getValue());

            result.ifError(error -> BeLoongCore.LOGGER.error(
                    "Failed to parse npc route file '{}': {}", file.getKey(), error.message()));

            result.ifSuccess(route -> {
                if (route.waypoints().isEmpty()) {
                    BeLoongCore.LOGGER.error(
                            "npc route file '{}' declares no waypoints, ignored", file.getKey());
                    return;
                }
                parsed.put(file.getKey(), route);
            });
        }

        this.routes = Map.copyOf(parsed);
        // 同时打印扫描数与装载数 —— 理由同 NpcDialogueLoader：数据放错树时扫描数恒为 0，
        // 而症状是"指派成功但 NPC 一动不动、且不报任何错"。
        BeLoongCore.LOGGER.info(
                "[BeLoong] reloaded npc routes: {} file(s) scanned, {} route(s) loaded",
                files.size(), this.routes.size());
    }

    /** 按名查路线；不存在返回 {@code null}（调用方**必须** fail-closed，见 {@code NpcRouteGoal}）。 */
    @Nullable
    public NpcRoute get(@Nullable ResourceLocation id) {
        return id == null ? null : this.routes.get(id);
    }

    /** 已加载的全部名字符串（补全用）。 */
    public List<String> nameStrings() {
        return this.routes.keySet().stream().map(ResourceLocation::toString).sorted().toList();
    }
}
