package com.zonlong.beloong.structure;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * 「结构药水效果」的数据加载器。
 *
 * <p>文件格式是「结构 id → 效果条目数组」：
 * <pre>
 * {
 *   "minecraft:desert_pyramid": [
 *     { "effect": "beloong:flight_ban", "amplifier": 0, "duration": 100 },
 *     { "effect": "minecraft:mining_fatigue", "amplifier": 1, "duration": 100,
 *       "advancement": "minecraft:story/mine_diamond" }
 *   ]
 * }
 * </pre>
 *
 * <h2>⚠️ 为什么是"逐条手工解码"而不是一个整文件的 Codec</h2>
 * 原来这里用 {@code Codec.unboundedMap(STRING, list(StructureEffectEntry.CODEC))} 一次解整个文件。
 * 因为效果字段要校验存在性（{@code StructureEffectEntry.CODEC} 里用 {@code comapFlatMap}），
 * <b>任意一条写错就会让整个 map 解码失败</b> ⇒ 整份文件被丢弃 ⇒ 该文件里**所有**结构一起失效。
 * 也就是说"一个拼错的效果 id"会变成"一个文件里其他结构的配置全部消失"。
 *
 * <p>现在改成：结构 id、数组、单条 entry 各自独立解码，各自报告错误。
 * 坏掉一条只丢那一条，其余照常生效。
 *
 * <h2>合并语义（与数据包直觉不同，注意）</h2>
 * <b>同一路径</b>的文件按资源包优先级覆盖；但<b>不同路径</b>的文件里若出现同一个结构 id，
 * 这里会把两个数组<b>逐个追加合并</b>（见 {@code merged.addAll}），并且合并时不打日志。
 */
public class StructureEffectLoader extends SimpleJsonResourceReloadListener {

    public static final StructureEffectLoader INSTANCE = new StructureEffectLoader();

    private Map<ResourceKey<Structure>, List<EffectEntry>> configMap = Map.of();

    private StructureEffectLoader() {
        super(new Gson(), "beloong/structure_effects");
    }

    @Override
    protected void apply(@NotNull Map<ResourceLocation, JsonElement> files,
                         @NotNull ResourceManager manager, @NotNull ProfilerFiller profiler) {
        Map<ResourceKey<Structure>, List<EffectEntry>> newMap = new HashMap<>();

        for (var fileEntry : files.entrySet()) {
            JsonElement root = fileEntry.getValue();
            if (root == null || !root.isJsonObject()) {
                BeLoongCore.LOGGER.error(
                        "structure_effects: file '{}' must be a JSON object mapping structure id to an effect array",
                        fileEntry.getKey());
                continue;
            }

            for (var structEntry : root.getAsJsonObject().entrySet()) {
                ResourceLocation structureLoc;
                try {
                    structureLoc = ResourceLocation.parse(structEntry.getKey());
                } catch (Exception e) {
                    BeLoongCore.LOGGER.error("Invalid structure ID in file '{}': {}",
                            fileEntry.getKey(), structEntry.getKey());
                    continue;
                }
                ResourceKey<Structure> structureKey = ResourceKey.create(Registries.STRUCTURE, structureLoc);

                JsonElement value = structEntry.getValue();
                if (value == null || !value.isJsonArray()) {
                    BeLoongCore.LOGGER.error(
                            "structure_effects: value of structure '{}' in file '{}' must be a JSON array",
                            structEntry.getKey(), fileEntry.getKey());
                    continue;
                }

                // 逐条解码：坏一条只丢一条，不再连累同文件里的其他结构
                int index = 0;
                List<EffectEntry> converted = new ArrayList<>();
                for (JsonElement rawEntry : value.getAsJsonArray()) {
                    int entryIndex = index++;
                    StructureEffectEntry.CODEC.parse(JsonOps.INSTANCE, rawEntry)
                            .resultOrPartial(error -> BeLoongCore.LOGGER.error(
                                    "structure_effects: skipping bad entry #{} of structure '{}' in file '{}': {}",
                                    entryIndex, structEntry.getKey(), fileEntry.getKey(), error))
                            .ifPresent(se -> converted.add(new EffectEntry(
                                    BuiltInRegistries.MOB_EFFECT.wrapAsHolder(se.effect()),
                                    se.amplifier(), se.duration(),
                                    se.showParticles(), se.advancement())));
                }

                List<EffectEntry> existing = newMap.get(structureKey);
                List<EffectEntry> merged = existing != null
                        ? new ArrayList<>(existing) : new ArrayList<>();
                merged.addAll(converted);
                newMap.put(structureKey, Collections.unmodifiableList(merged));
            }
        }

        this.configMap = Collections.unmodifiableMap(newMap);
        BeLoongCore.LOGGER.debug("Reloaded structure effects: {} structures", configMap.size());
    }

    public Map<ResourceKey<Structure>, List<EffectEntry>> getConfigMap() {
        return configMap;
    }
}
