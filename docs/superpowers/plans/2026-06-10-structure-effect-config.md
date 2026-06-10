# Structure Effect Config — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 玩家进入配置的结构时自动获得药水效果，离开后效果自然过期消失。零 Mixin、纯事件驱动。

**Architecture:** 单一 Handler 类监听四个 NeoForge 事件（tick chunk 检测、效果过期、维度切换、登录/重生），通过 `StructureManager.getAllStructuresAt()` 单次查询 + AABB 碰撞判定，按配置应用效果。

**Tech Stack:** NeoForge 1.21.1, Java 21, ModConfigSpec, Minecraft StructureManager, BuiltInRegistries

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `src/main/java/com/zonlong/beloong/structure/EffectEntry.java` | Create | 配置条目的数据 record |
| `src/main/java/com/zonlong/beloong/structure/StructureEffectHandler.java` | Create | 核心 Handler：配置解析、事件监听、结构检测、效果应用 |
| `src/main/java/com/zonlong/beloong/Config.java` | Modify | 新增 `ServerStructureEffects` 内部类 + `structure_effects` 配置节 |
| `src/main/java/com/zonlong/beloong/BeLoongCore.java` | Modify | 注册 StructureEffectHandler 到 NeoForge 事件总线 |

---

### Task 1: Create EffectEntry Record

**Files:**
- Create: `src/main/java/com/zonlong/beloong/structure/EffectEntry.java`

- [ ] **Step 1: Write the record**

```java
package com.zonlong.beloong.structure;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;

/**
 * 结构药水效果配置条目。
 *
 * @param effect        药水效果的 Holder 引用
 * @param amplifier     效果等级（0 = I 级，对应原版 amplifier）
 * @param durationTicks 效果持续时间（tick）
 */
public record EffectEntry(Holder<MobEffect> effect, int amplifier, int durationTicks) {}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew build -x test`
Expected: Should compile successfully (record alone has no dependencies on other new code).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/zonlong/beloong/structure/EffectEntry.java
git commit -m "feat: add EffectEntry record for structure effect config"
```

---

### Task 2: Add Config Section to Config.java

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/Config.java`

- [ ] **Step 1: Add imports at top of Config.java**

Add after line 3 (`import java.util.List;`):

```java
import java.util.Set;
```

- [ ] **Step 2: Add ServerStructureEffects inner class**

Insert before the `static {` block (after the DisasterPortal class closing brace at line 135):

```java
    // ==================== structure_effects ====================

    public static final class ServerStructureEffects {
        private ServerStructureEffects() {}

        /** 需要监听过期事件的药水效果 ID 列表 */
        public static ModConfigSpec.ConfigValue<List<? extends String>> watchedEffects;
        /** 结构效果配置条目，格式: "结构ID|效果ID|等级|持续时间" */
        public static ModConfigSpec.ConfigValue<List<? extends String>> entries;
    }
```

- [ ] **Step 3: Add push/pop block in static initializer**

Insert before the very last line of the static block (`SERVER_BUILDER.pop(); // disaster_portal`) at line 293:

```java
        // ========== structure_effects ==========
        SERVER_BUILDER.push("structure_effects");

        ServerStructureEffects.watchedEffects = SERVER_BUILDER
                .comment("需要监听过期事件的药水效果ID列表",
                        "当这些效果在玩家身上过期时，触发结构重检")
                .defineList("watchedEffects",
                        List.of("beloong:flight_ban"),
                        s -> s instanceof String str && str.contains(":"));

        ServerStructureEffects.entries = SERVER_BUILDER
                .comment("结构效果配置，格式: \"结构ID|效果ID|等级|持续时间(tick)\"",
                        "等级: 0 = I级, 1 = II级, 以此类推",
                        "示例: \"cataclysm:burning_arena|beloong:flight_ban|5|1200\"")
                .defineList("entries",
                        List.of("cataclysm:burning_arena|beloong:flight_ban|5|1200"),
                        s -> s instanceof String str && str.contains("|"));

        SERVER_BUILDER.pop(); // structure_effects
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew build -x test`
Expected: Should compile. Any errors will be from missing imports — add them as needed.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/zonlong/beloong/Config.java
git commit -m "feat: add structure_effects server config section"
```

---

### Task 3: Create StructureEffectHandler

**Files:**
- Create: `src/main/java/com/zonlong/beloong/structure/StructureEffectHandler.java`

This is the core file (~120 lines). Each step builds on the previous one.

- [ ] **Step 1: Write the class skeleton with all imports and field declarations**

```java
package com.zonlong.beloong.structure;

import com.zonlong.beloong.Config;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class StructureEffectHandler {

    private static final Logger LOG = LoggerFactory.getLogger(StructureEffectHandler.class);

    /** 缓存已解析的配置：结构 Key → 效果列表 */
    private Map<ResourceKey<Structure>, List<EffectEntry>> configMap = Map.of();
    /** 缓存已解析的配置：需监听过期的效果 Key 集合 */
    private Set<ResourceKey<MobEffect>> watchedEffects = Set.of();
    /** 配置列表的 hash，用于检测变更 */
    private int lastConfigHash;
    /** 每个玩家最后一次所在的区块，用于跳过未发生区块变化的 tick */
    private final Map<UUID, ChunkPos> playerLastChunk = new HashMap<>();

    // Event handlers and checkAndApply will be added in subsequent steps
}
```

- [ ] **Step 2: Add the config parsing method**

Add inside the class:

```java
    /**
     * 当配置发生变更时重新解析 entries 和 watchedEffects。
     * 解析失败的单条打印 WARN 并跳过，不影响其他条目。
     */
    private void refreshConfig() {
        int currentHash = Config.ServerStructureEffects.entries.get().hashCode()
                ^ Config.ServerStructureEffects.watchedEffects.get().hashCode();
        if (currentHash == lastConfigHash) return;

        Map<ResourceKey<Structure>, List<EffectEntry>> newConfigMap = new HashMap<>();
        Set<ResourceKey<MobEffect>> newWatchedEffects = new HashSet<>();

        for (String entry : Config.ServerStructureEffects.entries.get()) {
            String[] parts = entry.split("\\|");
            if (parts.length != 4) {
                LOG.warn("[BeLoong] structure_effects: 无效条目格式（需要4个字段）: {}", entry);
                continue;
            }

            ResourceKey<Structure> structureKey = ResourceKey.create(
                    Registries.STRUCTURE, ResourceLocation.parse(parts[0].trim()));

            ResourceKey<MobEffect> effectKey = ResourceKey.create(
                    Registries.MOB_EFFECT, ResourceLocation.parse(parts[1].trim()));

            int amplifier;
            int duration;
            try {
                amplifier = Integer.parseInt(parts[2].trim());
                duration = Integer.parseInt(parts[3].trim());
            } catch (NumberFormatException e) {
                LOG.warn("[BeLoong] structure_effects: 等级/持续时间解析失败: {}", entry);
                continue;
            }

            Holder<MobEffect> effectHolder = net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT
                    .getHolder(effectKey).orElse(null);
            if (effectHolder == null) {
                LOG.warn("[BeLoong] structure_effects: 药水效果不存在: {}", parts[1].trim());
                continue;
            }

            newConfigMap.computeIfAbsent(structureKey, k -> new ArrayList<>())
                    .add(new EffectEntry(effectHolder, amplifier, duration));
        }

        for (String effectId : Config.ServerStructureEffects.watchedEffects.get()) {
            ResourceKey<MobEffect> key = ResourceKey.create(
                    Registries.MOB_EFFECT, ResourceLocation.parse(effectId.trim()));
            newWatchedEffects.add(key);
        }

        this.configMap = Collections.unmodifiableMap(newConfigMap);
        this.watchedEffects = Collections.unmodifiableSet(newWatchedEffects);
        this.lastConfigHash = currentHash;
        this.playerLastChunk.clear(); // 配置变更后强制所有玩家重新检测
        LOG.debug("[BeLoong] structure_effects 配置已加载: {} 个结构, {} 个监听效果",
                configMap.size(), watchedEffects.size());
    }
```

- [ ] **Step 3: Add the core detection method**

Add inside the class:

```java
    /**
     * 检查玩家当前位置是否在任意配置的结构内，若是则应用对应效果。
     */
    private void checkAndApply(ServerPlayer player) {
        refreshConfig();
        if (configMap.isEmpty()) return;

        var structureManager = player.serverLevel().structureManager();
        Map<Structure, StructureStart> structuresAt = structureManager.getAllStructuresAt(player.blockPosition());
        if (structuresAt.isEmpty()) return;

        var structureRegistry = player.serverLevel().registryAccess().registryOrThrow(Registries.STRUCTURE);

        for (var entry : structuresAt.entrySet()) {
            StructureStart start = entry.getValue();
            ResourceKey<Structure> key = structureRegistry.getResourceKey(entry.getKey()).orElse(null);
            if (key == null) continue;

            List<EffectEntry> effects = configMap.get(key);
            if (effects == null) continue;

            if (player.getBoundingBox().intersects(start.getBoundingBox())) {
                for (EffectEntry ee : effects) {
                    player.addEffect(new MobEffectInstance(
                            ee.effect(), ee.durationTicks(), ee.amplifier(),
                            false, true, true
                    ));
                }
            }
        }
    }
```

- [ ] **Step 4: Add the ServerTickEvent.END handler (chunk change detection)**

Add inside the class:

```java
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
```

- [ ] **Step 5: Add the MobEffectEvent.Expired handler**

Add inside the class:

```java
    @SubscribeEvent
    public void onEffectExpired(MobEffectEvent.Expired event) {
        refreshConfig();
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        var effectKey = event.getEffectInstance().getEffect().getKey();
        if (effectKey.filter(watchedEffects::contains).isPresent()) {
            checkAndApply(player);
        }
    }
```

- [ ] **Step 6: Add the dimension change handler**

Add inside the class:

```java
    @SubscribeEvent
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        playerLastChunk.remove(player.getUUID());
        checkAndApply(player);
    }
```

- [ ] **Step 7: Add the login/respawn/logout handlers**

Add inside the class:

```java
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
```

- [ ] **Step 8: Verify compilation**

Run: `./gradlew build -x test`
Expected: Compile successfully.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/zonlong/beloong/structure/StructureEffectHandler.java
git commit -m "feat: add StructureEffectHandler for structure-based potion effects"
```

---

### Task 4: Register Handler in BeLoongCore.java

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`

- [ ] **Step 1: Add import**

Add after line 7 (`import com.zonlong.beloong.transport.DimensionTransportHandler;`):

```java
import com.zonlong.beloong.structure.StructureEffectHandler;
```

- [ ] **Step 2: Register handler on NeoForge event bus**

Add after line 67 (`NeoForge.EVENT_BUS.register(new DimensionTransportHandler());`):

```java
        NeoForge.EVENT_BUS.register(new StructureEffectHandler());
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew build -x test`
Expected: Compile successfully.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/zonlong/beloong/BeLoongCore.java
git commit -m "feat: register StructureEffectHandler on NeoForge event bus"
```

---

### Task 5: End-to-End Build Verification

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Verify generated config**

Run the game, connect to a world, then check `run/serverconfig/beloong-server.toml`. Expected output:

```toml
[structure_effects]
    watchedEffects = ["beloong:flight_ban"]
    entries = ["cataclysm:burning_arena|beloong:flight_ban|5|1200"]
```

- [ ] **Step 3: Functional test**

1. Use `/locate structure cataclysm:burning_arena` to find the structure
2. Teleport into the structure bounds
3. Verify you receive Flight Ban V for 60s
4. Wait 60s — verify effect re-applies
5. Teleport far away — verify effect expires and does NOT re-apply

- [ ] **Step 4: Commit any final adjustments**

```bash
git add -A
git commit -m "chore: final verification of structure effect feature"
```

---

## Error Handling (built-in)

- Config entries with wrong format → WARN log + skip entry
- Effect ID not found in registry → WARN log + skip entry  
- Structure ID reference doesn't exist → no-op at runtime (no matching structure at position)
- `getAllStructuresAt` returns empty → no-op
- Player not alive / not online → naturally filtered by tick loop iterator
- Empty `entries` config → `configMap.isEmpty()` short-circuits, zero overhead
- Empty `watchedEffects` → `Expired` handler has nothing to match
