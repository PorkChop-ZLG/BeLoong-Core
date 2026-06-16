# 财宝堆成长加速——数据驱动改造 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 treasureWeights 从 NeoForge 配置文件迁移为 Codec + SimpleJsonResourceReloadListener 数据驱动系统，新增"其他财宝"类型和检测上限。

**Architecture:** TreasureGrowthEntry record 定义三字段数据模型；TreasureGrowthLoader 继承 SimpleJsonResourceReloadListener，从 `data/<ns>/beloong/treasure_growth/` 加载 JSON 并维护两个 Map<Block, Entry> 缓存；TreasureValueCalculator 在一次遍历中同时统计龙之财宝层数和其他财宝数量，遍历后统一应用 limit 封顶求和。

**Tech Stack:** Minecraft 1.21.1, NeoForge 21.1.219, Java 21, Mojang Codec (com.mojang.serialization)

---

### Task 1: 创建 TreasureGrowthEntry 数据模型

**Files:**
- Create: `src/main/java/com/zonlong/beloong/treasure/TreasureGrowthEntry.java`

- [ ] **Step 1: 创建 TreasureGrowthEntry record**

```java
package com.zonlong.beloong.treasure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

public record TreasureGrowthEntry(Block block, double value, int limit) {

    public static final Codec<TreasureGrowthEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.comapFlatMap(
                    loc -> BuiltInRegistries.BLOCK.getOptional(loc)
                            .map(DataResult::success)
                            .orElseGet(() -> DataResult.error(() -> "Unknown block: " + loc)),
                    BuiltInRegistries.BLOCK::getKey
            ).fieldOf("block").forGetter(TreasureGrowthEntry::block),
            Codec.DOUBLE.fieldOf("value").forGetter(TreasureGrowthEntry::value),
            Codec.INT.optionalFieldOf("limit", Integer.MAX_VALUE).forGetter(TreasureGrowthEntry::limit)
    ).apply(instance, TreasureGrowthEntry::new));
}
```

- [ ] **Step 2: 验证编译**

Run: `cd e:/Minecraft/BeLoong-Core && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/zonlong/beloong/treasure/TreasureGrowthEntry.java
git commit -m "feat: add TreasureGrowthEntry record with Codec for data-driven treasure definitions"
```

---

### Task 2: 创建 TreasureGrowthLoader 资源重载监听器

**Files:**
- Create: `src/main/java/com/zonlong/beloong/treasure/TreasureGrowthLoader.java`

- [ ] **Step 1: 创建 TreasureGrowthLoader**

```java
package com.zonlong.beloong.treasure;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TreasureGrowthLoader extends SimpleJsonResourceReloadListener {

    /** 单例，供 TreasureValueCalculator 和 TreasureGrowthHandler 查询 */
    public static final TreasureGrowthLoader INSTANCE = new TreasureGrowthLoader();

    private static final Codec<Map<String, List<TreasureGrowthEntry>>> FILE_CODEC =
            Codec.unboundedMap(Codec.STRING, Codec.list(TreasureGrowthEntry.CODEC));

    private Map<Block, TreasureGrowthEntry> dragonTreasureEntries = Map.of();
    private Map<Block, TreasureGrowthEntry> otherTreasureEntries = Map.of();

    private TreasureGrowthLoader() {
        super(new Gson(), "beloong/treasure_growth");
    }

    @Override
    protected void apply(@NotNull Map<ResourceLocation, JsonElement> files,
                         @NotNull ResourceManager manager, @NotNull ProfilerFiller profiler) {
        Map<Block, TreasureGrowthEntry> newDragon = new HashMap<>();
        Map<Block, TreasureGrowthEntry> newOther = new HashMap<>();

        for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet()) {
            var result = FILE_CODEC.parse(JsonOps.INSTANCE, file.getValue());
            result.resultOrPartial(error ->
                    BeLoongCore.LOGGER.error("Failed to parse treasure growth file '{}': {}",
                            file.getKey(), error)
            ).ifPresent(map -> {
                for (var entry : map.entrySet()) {
                    String key = entry.getKey();
                    List<TreasureGrowthEntry> entries = entry.getValue();
                    for (TreasureGrowthEntry tgEntry : entries) {
                        Map<Block, TreasureGrowthEntry> target =
                                "dragon_treasure".equals(key) ? newDragon : newOther;
                        TreasureGrowthEntry prev = target.put(tgEntry.block(), tgEntry);
                        if (prev != null) {
                            BeLoongCore.LOGGER.warn(
                                    "Duplicate treasure entry for block '{}' in type '{}', overwriting",
                                    BuiltInRegistries.BLOCK.getKey(tgEntry.block()), key);
                        }
                    }
                }
            });
        }

        this.dragonTreasureEntries = Map.copyOf(newDragon);
        this.otherTreasureEntries = Map.copyOf(newOther);
        BeLoongCore.LOGGER.debug("Reloaded treasure growth: {} dragon entries, {} other entries",
                dragonTreasureEntries.size(), otherTreasureEntries.size());
    }

    @Nullable
    public TreasureGrowthEntry getDragonEntry(Block block) {
        return dragonTreasureEntries.get(block);
    }

    @Nullable
    public TreasureGrowthEntry getOtherEntry(Block block) {
        return otherTreasureEntries.get(block);
    }
}
```

注意：`BuiltInRegistries` 的 import 需要正确路径 —— 在 TreasureGrowthEntry 中是 `net.minecraft.core.registries.BuiltInRegistries`，Loader 中引用时需要加上：
```java
import net.minecraft.core.registries.BuiltInRegistries;
```

- [ ] **Step 2: 验证编译**

Run: `cd e:/Minecraft/BeLoong-Core && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/zonlong/beloong/treasure/TreasureGrowthLoader.java
git commit -m "feat: add TreasureGrowthLoader for data-driven treasure definitions"
```

---

### Task 3: 添加默认 JSON 数据文件

**Files:**
- Create: `src/main/resources/data/beloong/beloong/treasure_growth/dragon_treasure.json`
- Create: `src/main/resources/data/beloong/beloong/treasure_growth/other_treasure.json`

- [ ] **Step 1: 创建目录**

Run: `mkdir -p "e:/Minecraft/BeLoong-Core/src/main/resources/data/beloong/beloong/treasure_growth"`

- [ ] **Step 2: 创建 dragon_treasure.json**

```json
{
  "dragon_treasure": [
    { "block": "dragonsurvival:debris_dragon_treasure", "value": 5.0, "limit": 1000 },
    { "block": "dragonsurvival:diamond_dragon_treasure", "value": 4.0, "limit": 1000 },
    { "block": "dragonsurvival:emerald_dragon_treasure", "value": 3.0, "limit": 1000 },
    { "block": "dragonsurvival:gold_dragon_treasure", "value": 2.0, "limit": 1000 },
    { "block": "dragonsurvival:iron_dragon_treasure", "value": 1.0, "limit": 1000 },
    { "block": "dragonsurvival:copper_dragon_treasure", "value": 0.5, "limit": 1000 }
  ]
}
```

- [ ] **Step 3: 创建 other_treasure.json**

```json
{
  "other_treasure": [
    { "block": "minecraft:diamond_block", "value": 40.0, "limit": 10 }
  ]
}
```

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/data/beloong/beloong/treasure_growth/
git commit -m "feat: add default treasure growth data files (dragon + other)"
```

---

### Task 4: 从 Config 移除 treasureWeights

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/Config.java`

- [ ] **Step 1: 移除 treasureWeights 字段和默认值定义**

找到 `Config` 类中以下内容并删除：

删除字段声明（第 106 行附近）：
```java
public static ModConfigSpec.ConfigValue<List<? extends String>> treasureWeights;
```

删除 `static` 初始化块中对应的构建代码（第 148-161 行附近）：
```java
TreasureGrowth.treasureWeights = SERVER_BUILDER
        .comment(
                "财宝方块权重，格式: modid:block_id=weight",
                "未列出的方块默认权重 1.0",
                "内置默认: debris=5, diamond=4, emerald=3, gold=2, iron=1, copper=0.5"
        )
        .defineList("treasureWeights", List.of(
                "dragonsurvival:copper_dragon_treasure=0.5",
                "dragonsurvival:iron_dragon_treasure=1.0",
                "dragonsurvival:gold_dragon_treasure=2.0",
                "dragonsurvival:emerald_dragon_treasure=3.0",
                "dragonsurvival:diamond_dragon_treasure=4.0",
                "dragonsurvival:debris_dragon_treasure=5.0"
        ), s -> s instanceof String str && str.contains("="));
```

- [ ] **Step 2: 清理不再需要的 import**

`Config.java` 中如果 `List` 只用于 `treasureWeights`，移除：
```java
import java.util.List;
```
确认：`List` 还被 `eyeItems`（DisasterPortal）和 `watchedEffects`/`entries`（StructureEffects）使用，所以**不能移除**该 import。

- [ ] **Step 3: 验证编译**

Run: `cd e:/Minecraft/BeLoong-Core && ./gradlew compileJava`
Expected: 可能有编译错误，因为 `TreasureGrowthHandler` 和 `TreasureValueCalculator` 仍引用 `Config.TreasureGrowth.treasureWeights`。这是预期行为——后续 task 会修复。

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/zonlong/beloong/Config.java
git commit -m "refactor: remove treasureWeights config field (migrating to data-driven)"
```

---

### Task 5: 改造 TreasureValueCalculator

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/treasure/TreasureValueCalculator.java`

- [ ] **Step 1: 重写 TreasureValueCalculator**

将整个类替换为以下内容：

```java
package com.zonlong.beloong.treasure;

import by.dragonsurvivalteam.dragonsurvival.common.blocks.TreasureBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;

public final class TreasureValueCalculator {
    private TreasureValueCalculator() {}

    public static double calculateWeightedValue(ServerPlayer player) {
        Map<Block, Integer> dragonLayers = new HashMap<>();
        Map<Block, Integer> otherCounts = new HashMap<>();

        AABB area = AABB.ofSize(player.position(), 16, 9, 16);

        for (BlockPos pos : BlockPos.betweenClosed(
                (int) Math.floor(area.minX), (int) Math.floor(area.minY), (int) Math.floor(area.minZ),
                (int) Math.ceil(area.maxX), (int) Math.ceil(area.maxY), (int) Math.ceil(area.maxZ))) {
            BlockState state = player.serverLevel().getBlockState(pos);
            Block block = state.getBlock();

            if (block instanceof TreasureBlock) {
                TreasureGrowthEntry entry = TreasureGrowthLoader.INSTANCE.getDragonEntry(block);
                if (entry != null) {
                    int layers = state.getValue(TreasureBlock.LAYERS);
                    dragonLayers.merge(block, layers, Integer::sum);
                }
            }

            TreasureGrowthEntry otherEntry = TreasureGrowthLoader.INSTANCE.getOtherEntry(block);
            if (otherEntry != null) {
                otherCounts.merge(block, 1, Integer::sum);
            }
        }

        double total = 0;

        for (Map.Entry<Block, Integer> e : dragonLayers.entrySet()) {
            TreasureGrowthEntry entry = TreasureGrowthLoader.INSTANCE.getDragonEntry(e.getKey());
            int applied = Math.min(e.getValue(), entry.limit());
            total += applied * entry.value();
        }

        for (Map.Entry<Block, Integer> e : otherCounts.entrySet()) {
            TreasureGrowthEntry entry = TreasureGrowthLoader.INSTANCE.getOtherEntry(e.getKey());
            int applied = Math.min(e.getValue(), entry.limit());
            total += applied * entry.value();
        }

        return total;
    }

    public static int valueToAmplifier(double value, int step, int maxAmplifier) {
        return Math.clamp((int) (value / step), 0, maxAmplifier);
    }

    public static double amplifierToMultiplier(int amplifier) {
        return amplifier + 2.0;
    }
}
```

**关键变更：**
- 移除 `buildWeightMap()` 方法和相关的 `Map<Block, Double> weights` 参数
- `calculateWeightedValue` 签名从 `(ServerPlayer, Map<Block,Double>)` 变为 `(ServerPlayer)`
- 一次遍历中同时统计龙之财宝（层数累加）和其他财宝（计数累加）
- 遍历后统一应用 limit 封顶：`min(accumulatedCount, entry.limit) × entry.value`
- `block` 变量提取复用，避免重复调用 `state.getBlock()`
- 移除不再需要的 import：`BuiltInRegistries`、`ResourceLocation`、`HashMap`（仍需要）、`List`

- [ ] **Step 2: 验证编译**

Run: `cd e:/Minecraft/BeLoong-Core && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL（Task 6 改动尚未执行，`TreasureGrowthHandler` 还有编译错误属正常；如果本文件没有编译错误即可）

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/zonlong/beloong/treasure/TreasureValueCalculator.java
git commit -m "feat: refactor TreasureValueCalculator to use data-driven entries with dual-type scanning and limits"
```

---

### Task 6: 改造 TreasureGrowthHandler

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/treasure/TreasureGrowthHandler.java`

- [ ] **Step 1: 移除 weightMap 缓存并更新调用**

删除类中三行（第 28-30 行附近）：
```java
private static Map<Block, Double> weightMap = Map.of();
private static long weightMapConfigHash;
```

删除 `import java.util.Map;` （确认 `Map` 是否还在其他地方使用 —— `TICK_COUNTERS` 使用 `Map<UUID, Integer>` — 需要保留 import）。

删除 `import java.util.Locale;`（check: `String.format(Locale.ROOT, ...)` 在第 77-78 行使用 — 需要保留）。

删除 `import net.minecraft.world.level.block.Block;`（check: 只有 weightMap 使用 — 可以删除？不，计算过程中不再直接引用 Block 类型了）。

但 `weightMap` 被删除了，TreasureGrowthHandler 不再直接引用 `Block` 类型，所以可以移除 `import net.minecraft.world.level.block.Block;`。

删除第 54-58 行的 hash 检测：
```java
long currentHash = Config.TreasureGrowth.treasureWeights.get().hashCode();
if (weightMap.isEmpty() || weightMapConfigHash != currentHash) {
    weightMap = TreasureValueCalculator.buildWeightMap();
    weightMapConfigHash = currentHash;
}
```

修改第 60 行调用：
```java
double treasureValue = TreasureValueCalculator.calculateWeightedValue(player, weightMap);
```
改为：
```java
double treasureValue = TreasureValueCalculator.calculateWeightedValue(player);
```

- [ ] **Step 2: 验证最终 `onPlayerTick` 方法**

`onPlayerTick` 方法 `isResting()` 分支内应变为：

```java
if (restData.isResting()) {
    double treasureValue = TreasureValueCalculator.calculateWeightedValue(player);
    int maxValue = Config.TreasureGrowth.maxTreasureValue.get();
    treasureValue = Math.min(treasureValue, maxValue);

    int step = Config.TreasureGrowth.amplifierStep.get();
    int maxAmp = Config.TreasureGrowth.maxAmplifier.get();
    int amplifier = TreasureValueCalculator.valueToAmplifier(treasureValue, step, maxAmp);
    double multiplier = TreasureValueCalculator.amplifierToMultiplier(amplifier);

    int duration = Config.TreasureGrowth.effectDurationTicks.get();
    player.addEffect(new MobEffectInstance(
            ModMobEffects.GROWTH_ACCELERATION, duration, amplifier,
            false, true, true
    ));

    String valueText = String.format(Locale.ROOT, "%.1f", treasureValue);
    String multText = String.format(Locale.ROOT, "%.1fx", multiplier);
    Component message = Component.literal("")
            .append(Component.translatable("title.beloong.treasure_value", valueText).withStyle(ChatFormatting.GOLD))
            .append(Component.literal("  "))
            .append(Component.translatable("title.beloong.growth_multiplier", multText).withStyle(ChatFormatting.GREEN));
    player.displayClientMessage(message, true);
}
```

- [ ] **Step 3: 验证编译**

Run: `cd e:/Minecraft/BeLoong-Core && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/zonlong/beloong/treasure/TreasureGrowthHandler.java
git commit -m "feat: migrate TreasureGrowthHandler to use TreasureGrowthLoader cache"
```

---

### Task 7: 注册 TreasureGrowthLoader 到资源重载系统

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`

- [ ] **Step 1: 在 BeLoongCore 构造函数中注册 reload listener**

在 `NeoForge.EVENT_BUS.register(new StructureEffectHandler());` 后添加：

```java
NeoForge.EVENT_BUS.register(TreasureGrowthLoader.INSTANCE);
```

`SimpleJsonResourceReloadListener` 实现了 `PreparableReloadListener`，但它需要通过 `AddServerReloadListenersEvent` 或资源管理器来触发。在 NeoForge 1.21.1 中，需要使用 `AddServerReloadListenersEvent`。

改为在 BeLoongCore 中添加一个事件监听方法：

```java
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import com.zonlong.beloong.treasure.TreasureGrowthLoader;

// 在 BeLoongCore 类中添加：
@SubscribeEvent
public void addServerReloadListeners(AddServerReloadListenersEvent event) {
    event.addListener(TreasureGrowthLoader.INSTANCE);
}
```

因为 `BeLoongCore` 已经 `NeoForge.EVENT_BUS.register(this)`，此方法会自动被订阅。

- [ ] **Step 2: 验证编译**

Run: `cd e:/Minecraft/BeLoong-Core && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/zonlong/beloong/BeLoongCore.java
git commit -m "feat: register TreasureGrowthLoader as server reload listener"
```

---

### Task 8: 完整编译验证

- [ ] **Step 1: 完整构建**

Run: `cd e:/Minecraft/BeLoong-Core && ./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 最终提交（如有未提交内容）**

```bash
git status
```
如果没有未提交的内容，跳过此步。
