# Structure Effects 数据驱动改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 structure_effects 的 entries 从 TOML 字符串解析迁移为 Codec 数据驱动 JSON 系统，同时新增 advancement 进度条件字段。

**Architecture:** 新增 `StructureEffectEntry`（Codec record）和 `StructureEffectLoader`（SimpleJsonResourceReloadListener），与 treasure_growth 模式一致。`EffectEntry` 追加 `showParticles` 和 `advancement` 字段。`StructureEffectHandler` 改从 Loader 查询配置，在 `doCheckAndApply` 中用 `player.getAdvancements().getOrStartProgress().isDone()` 做进度检查。

**Tech Stack:** NeoForge 1.21.1, Java 21, Mojang Codec / DataResult, SimpleJsonResourceReloadListener

---

### Task 1: Create StructureEffectEntry

**Files:**
- Create: `src/main/java/com/zonlong/beloong/structure/StructureEffectEntry.java`

- [ ] **Step 1: Write StructureEffectEntry record with Codec**

```java
package com.zonlong.beloong.structure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;

import java.util.Optional;

public record StructureEffectEntry(
        Holder<MobEffect> effect,
        int amplifier,
        int duration,
        boolean showParticles,
        Optional<ResourceLocation> advancement
) {
    public static final Codec<StructureEffectEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.comapFlatMap(
                    loc -> BuiltInRegistries.MOB_EFFECT.getOptional(loc)
                            .map(DataResult::success)
                            .orElseGet(() -> DataResult.error(() -> "Unknown mob effect: " + loc)),
                    BuiltInRegistries.MOB_EFFECT::getKey
            ).fieldOf("effect").forGetter(StructureEffectEntry::effect),
            Codec.INT.optionalFieldOf("amplifier", 0).forGetter(StructureEffectEntry::amplifier),
            Codec.INT.fieldOf("duration").forGetter(StructureEffectEntry::duration),
            Codec.BOOL.optionalFieldOf("show_particles", false).forGetter(StructureEffectEntry::showParticles),
            ResourceLocation.CODEC.optionalFieldOf("advancement").forGetter(StructureEffectEntry::advancement)
    ).apply(instance, StructureEffectEntry::new));
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/zonlong/beloong/structure/StructureEffectEntry.java
git commit -m "$(cat <<'EOF'
feat: add StructureEffectEntry record with Codec for data-driven structure effects
EOF
)"
```

---

### Task 2: Update EffectEntry

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/structure/EffectEntry.java`

- [ ] **Step 1: Add showParticles and advancement fields**

```java
package com.zonlong.beloong.structure;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;

import java.util.Optional;

/**
 * 结构药水效果配置条目。
 *
 * @param effect        药水效果的 Holder 引用
 * @param amplifier     效果等级（0 = I 级，对应原版 amplifier）
 * @param durationTicks 效果持续时间（tick）
 * @param showParticles 是否显示粒子效果
 * @param advancement   可选，完成该进度后不再施加此效果
 */
public record EffectEntry(
        Holder<MobEffect> effect,
        int amplifier,
        int durationTicks,
        boolean showParticles,
        Optional<ResourceLocation> advancement
) {}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/zonlong/beloong/structure/EffectEntry.java
git commit -m "$(cat <<'EOF'
feat: add showParticles and advancement fields to EffectEntry
EOF
)"
```

---

### Task 3: Create StructureEffectLoader

**Files:**
- Create: `src/main/java/com/zonlong/beloong/structure/StructureEffectLoader.java`

- [ ] **Step 1: Write StructureEffectLoader**

```java
package com.zonlong.beloong.structure;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class StructureEffectLoader extends SimpleJsonResourceReloadListener {

    public static final StructureEffectLoader INSTANCE = new StructureEffectLoader();

    private static final Codec<Map<String, List<StructureEffectEntry>>> FILE_CODEC =
            Codec.unboundedMap(Codec.STRING, Codec.list(StructureEffectEntry.CODEC));

    private Map<ResourceKey<Structure>, List<EffectEntry>> configMap = Map.of();

    private StructureEffectLoader() {
        super(new Gson(), "beloong/structure_effects");
    }

    @Override
    protected void apply(@NotNull Map<ResourceLocation, JsonElement> files,
                         @NotNull ResourceManager manager, @NotNull ProfilerFiller profiler) {
        Map<ResourceKey<Structure>, List<EffectEntry>> newMap = new HashMap<>();

        for (var fileEntry : files.entrySet()) {
            var result = FILE_CODEC.parse(JsonOps.INSTANCE, fileEntry.getValue());
            result.resultOrPartial(error ->
                    BeLoongCore.LOGGER.error("Failed to parse structure effects file '{}': {}",
                            fileEntry.getKey(), error)
            ).ifPresent(map -> {
                for (var structEntry : map.entrySet()) {
                    ResourceKey<Structure> structureKey = ResourceKey.create(
                            Registries.STRUCTURE, ResourceLocation.parse(structEntry.getKey()));

                    List<EffectEntry> converted = new ArrayList<>();
                    for (StructureEffectEntry se : structEntry.getValue()) {
                        converted.add(new EffectEntry(
                                se.effect(), se.amplifier(), se.duration(),
                                se.showParticles(), se.advancement()));
                    }

                    List<EffectEntry> existing = newMap.get(structureKey);
                    List<EffectEntry> merged = existing != null
                            ? new ArrayList<>(existing) : new ArrayList<>();
                    merged.addAll(converted);
                    newMap.put(structureKey, Collections.unmodifiableList(merged));
                }
            });
        }

        this.configMap = Collections.unmodifiableMap(newMap);
        BeLoongCore.LOGGER.debug("Reloaded structure effects: {} structures", configMap.size());
    }

    public Map<ResourceKey<Structure>, List<EffectEntry>> getConfigMap() {
        return configMap;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/zonlong/beloong/structure/StructureEffectLoader.java
git commit -m "$(cat <<'EOF'
feat: add StructureEffectLoader for data-driven structure effects
EOF
)"
```

---

### Task 4: Create default data file

**Files:**
- Create: `src/main/resources/data/beloong/beloong/structure_effects/default_effects.json`

- [ ] **Step 1: Write default JSON**

```json
{
  "cataclysm:burning_arena": [
    {
      "effect": "beloong:flight_ban",
      "amplifier": 5,
      "duration": 100,
      "show_particles": false
    }
  ]
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/data/beloong/beloong/structure_effects/default_effects.json
git commit -m "$(cat <<'EOF'
feat: add default structure effects data file
EOF
)"
```

---

### Task 5: Remove entries from Config

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/Config.java`

- [ ] **Step 1: Delete entries field and update comments**

Remove `entries` field definition (lines 140-141 in `StructureEffects`) and its registration (lines 267-273 in static block). Replace the static block registration of `entries` with nothing.

In `StructureEffects` class, replace:

```java
public static final class StructureEffects {
    private StructureEffects() {}

    /** 需要监听过期事件的药水效果 ID 列表 */
    public static ModConfigSpec.ConfigValue<List<? extends String>> watchedEffects;
    /** 结构效果配置条目，格式: "结构ID|效果ID|等级|持续时间" */
    public static ModConfigSpec.ConfigValue<List<? extends String>> entries;
}
```

with:

```java
public static final class StructureEffects {
    private StructureEffects() {}

    /** 需要监听过期事件的药水效果 ID 列表 */
    public static ModConfigSpec.ConfigValue<List<? extends String>> watchedEffects;
}
```

In the static block, remove the `entries` definition (lines 267-273):

```java
StructureEffects.entries = SERVER_BUILDER
        .comment("结构效果配置，格式: \"结构ID|效果ID|等级|持续时间(tick)\"",
                "等级: 0 = I级, 1 = II级, 以此类推",
                "示例: \"cataclysm:burning_arena|beloong:flight_ban|5|100\"")
        .defineList("entries",
                List.of("cataclysm:burning_arena|beloong:flight_ban|5|100"),
                s -> s instanceof String str && str.contains("|"));
```

And update the watchedEffects comment as needed:

```java
StructureEffects.watchedEffects = SERVER_BUILDER
        .comment("需要监听过期/移除事件的药水效果ID列表",
                "当这些效果过期、被牛奶清除或被指令移除时，触发结构重检",
                "具体效果与结构的映射请在 data/beloong/beloong/structure_effects/ 中配置")
        .defineList("watchedEffects",
                List.of("beloong:flight_ban"),
                s -> s instanceof String str && str.contains(":"));
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/zonlong/beloong/Config.java
git commit -m "$(cat <<'EOF'
refactor: remove structure_effects entries from TOML config (migrated to data-driven)
EOF
)"
```

---

### Task 6: Update StructureEffectHandler

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/structure/StructureEffectHandler.java`

- [ ] **Step 1: Rewrite StructureEffectHandler**

```java
package com.zonlong.beloong.structure;

import com.zonlong.beloong.Config;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class StructureEffectHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(StructureEffectHandler.class);

    private Set<ResourceKey<MobEffect>> watchedEffects = Set.of();
    private int lastWatchedHash;
    private final Map<UUID, ChunkPos> playerLastChunk = new HashMap<>();
    private boolean refreshing;

    private void refreshWatchedEffects() {
        int currentHash = Config.StructureEffects.watchedEffects.get().hashCode();
        if (currentHash == lastWatchedHash) return;

        Set<ResourceKey<MobEffect>> newWatched = new HashSet<>();
        for (String effectId : Config.StructureEffects.watchedEffects.get()) {
            try {
                ResourceLocation loc = ResourceLocation.parse(effectId.trim());
                newWatched.add(ResourceKey.create(Registries.MOB_EFFECT, loc));
            } catch (Exception e) {
                LOGGER.warn("[BeLoong] structure_effects: invalid watched effect ID: {}", effectId.trim());
            }
        }
        this.watchedEffects = Collections.unmodifiableSet(newWatched);
        this.lastWatchedHash = currentHash;
    }

    private boolean checkAndApply(ServerPlayer player) {
        if (refreshing) return false;
        refreshing = true;
        try {
            return doCheckAndApply(player);
        } finally {
            refreshing = false;
        }
    }

    private boolean doCheckAndApply(ServerPlayer player) {
        var configMap = StructureEffectLoader.INSTANCE.getConfigMap();
        if (configMap.isEmpty()) return false;

        var structureManager = player.serverLevel().structureManager();
        var structureRegistry = player.serverLevel().registryAccess()
                .registryOrThrow(Registries.STRUCTURE);
        boolean applied = false;

        for (var configEntry : configMap.entrySet()) {
            ResourceKey<Structure> structureKey = configEntry.getKey();
            List<EffectEntry> effects = configEntry.getValue();

            Structure structure = structureRegistry.get(structureKey);
            if (structure == null) continue;

            StructureStart start = structureManager.getStructureAt(player.blockPosition(), structure);
            if (start == null || !start.isValid()) continue;

            BoundingBox bb = start.getBoundingBox();
            AABB aabb = new AABB(bb.minX(), bb.minY(), bb.minZ(),
                    bb.maxX() + 1, bb.maxY() + 1, bb.maxZ() + 1);
            if (!player.getBoundingBox().intersects(aabb)) continue;

            for (EffectEntry ee : effects) {
                if (ee.advancement().isPresent()) {
                    var progress = player.getAdvancements()
                            .getOrStartProgress(ee.advancement().get());
                    if (progress.isDone()) continue;
                }

                if (player.addEffect(new MobEffectInstance(
                        ee.effect(), ee.durationTicks(), ee.amplifier(),
                        false, ee.showParticles(), true
                ))) {
                    applied = true;
                }
            }
        }
        return applied;
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ChunkPos currentChunk = player.chunkPosition();
            ChunkPos lastChunk = playerLastChunk.get(player.getUUID());

            if (lastChunk == null || !currentChunk.equals(lastChunk)) {
                playerLastChunk.put(player.getUUID(), currentChunk);
                checkAndApply(player);
            }
        }
    }

    @SubscribeEvent
    public void onEffectExpired(MobEffectEvent.Expired event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ResourceKey<MobEffect> effectKey = event.getEffectInstance().getEffect().getKey();
        if (effectKey != null && watchedEffects.contains(effectKey)) {
            refreshWatchedEffects();
            if (checkAndApply(player)) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public void onEffectRemoved(MobEffectEvent.Remove event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ResourceKey<MobEffect> effectKey = event.getEffect().getKey();
        if (effectKey != null && watchedEffects.contains(effectKey)) {
            refreshWatchedEffects();
            if (checkAndApply(player)) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        playerLastChunk.remove(player.getUUID());
        checkAndApply(player);
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        playerLastChunk.remove(player.getUUID());
        checkAndApply(player);
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        playerLastChunk.remove(player.getUUID());
        checkAndApply(player);
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        playerLastChunk.remove(event.getEntity().getUUID());
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/zonlong/beloong/structure/StructureEffectHandler.java
git commit -m "$(cat <<'EOF'
refactor: use StructureEffectLoader cache and add advancement check
EOF
)"
```

---

### Task 7: Register StructureEffectLoader in BeLoongCore

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`

- [ ] **Step 1: Add import and register listener**

Add import:
```java
import com.zonlong.beloong.structure.StructureEffectLoader;
```

In `addServerReloadListeners` method, add the registration:

```java
@SubscribeEvent
public void addServerReloadListeners(AddReloadListenerEvent event) {
    event.addListener(TreasureGrowthLoader.INSTANCE);
    event.addListener(StructureEffectLoader.INSTANCE);
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/zonlong/beloong/BeLoongCore.java
git commit -m "$(cat <<'EOF'
feat: register StructureEffectLoader as server reload listener
EOF
)"
```

---

### Task 8: Build and verify

**Files:** None

- [ ] **Step 1: Run Gradle build**

```bash
cd e:/Minecraft/BeLoong-Core && ./gradlew build
```

Expected: BUILD SUCCESSFUL, no compile errors.

- [ ] **Step 2: Verify data file is packaged in JAR**

```bash
jar tf build/libs/beloong-*.jar | grep "data/beloong/beloong/structure_effects/default_effects.json"
```

Expected: Shows the file path.
