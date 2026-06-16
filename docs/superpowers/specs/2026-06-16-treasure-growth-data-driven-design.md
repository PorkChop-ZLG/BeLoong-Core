# 财宝堆成长加速——数据驱动改造 技术设计

## 1. 概述

将财宝堆成长加速系统的 `treasureWeights` 配置项从 NeoForge 服务端配置文件迁移为数据驱动的 JSON（Codec + SimpleJsonResourceReloadListener）。同时新增"其他财宝"类型和"检测上限"机制。

## 2. 数据模型

### 2.1 TreasureGrowthEntry

```java
public record TreasureGrowthEntry(String treasureType, Block block, double value, int limit)
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | `String` | `"dragon_treasure"`（龙之财宝）或 `"other_treasure"`（其他财宝） |
| `block` | `Block` | 方块实例，由 `ResourceLocation` 解析 |
| `value` | `double` | 每单位财宝价值 |
| `limit` | `int` | 检测上限，`Integer.MAX_VALUE` 时无上限 |

Codec 用 `RecordCodecBuilder` 构建，`block` 字段通过 `ResourceLocation.CODEC.comapFlatMap` 从字符串转为 `Block`，解析失败则跳过该条目。

### 2.2 两种财宝类型

| 类型 | JSON type 值 | 来源方块 | 计算方式 | 上限应用 |
|------|-------------|---------|---------|---------|
| 龙之财宝 | `dragon_treasure` | `TreasureBlock`（可堆叠 1-8 层） | 层数 × value | `min(总层数, limit)` |
| 其他财宝 | `other_treasure` | 普通方块（不可堆叠） | 方块数 × value | `min(方块数, limit)` |

## 3. 数据文件

**目录：** `src/main/resources/data/beloong/beloong/treasure_growth/`

每个文件为 `TreasureGrowthEntry` 的 JSON 数组。默认包含两个文件：

### dragon_treasure.json

```json
[
  { "type": "dragon_treasure", "block": "dragonsurvival:debris_dragon_treasure", "value": 5.0, "limit": 1000 },
  { "type": "dragon_treasure", "block": "dragonsurvival:diamond_dragon_treasure", "value": 4.0, "limit": 1000 },
  { "type": "dragon_treasure", "block": "dragonsurvival:emerald_dragon_treasure", "value": 3.0, "limit": 1000 },
  { "type": "dragon_treasure", "block": "dragonsurvival:gold_dragon_treasure", "value": 2.0, "limit": 1000 },
  { "type": "dragon_treasure", "block": "dragonsurvival:iron_dragon_treasure", "value": 1.0, "limit": 1000 },
  { "type": "dragon_treasure", "block": "dragonsurvival:copper_dragon_treasure", "value": 0.5, "limit": 1000 }
]
```

### other_treasure.json

```json
[
  { "type": "other_treasure", "block": "minecraft:diamond_block", "value": 40.0, "limit": 10 }
]
```

`type` 字段保留（即使文件名暗示类型），允许未来合并为单文件或混写不同类型。

## 4. 架构

### 4.1 TreasureGrowthLoader

```
TreasureGrowthLoader (extends SimpleJsonResourceReloadListener)
├── 目录: "beloong/treasure_growth"
├── 解析: Codec.list(TreasureGrowthEntry.CODEC)
├── 缓存两个 Map<Block, TreasureGrowthEntry>:
│   ├── dragonTreasureEntries
│   └── otherTreasureEntries
└── 静态查询方法:
    ├── getDragonEntry(Block) → @Nullable TreasureGrowthEntry
    └── getOtherEntry(Block) → @Nullable TreasureGrowthEntry
```

在模组客户端/服务端初始化时注册该 reload listener。数据包重载时自动重建缓存。

### 4.2 TreasureValueCalculator 改造

单次 16×9×16 遍历同时统计两类：

```
for each pos in AABB:
    Block block = state.getBlock()
    
    if block instanceof TreasureBlock:
        entry = dragonEntries.get(block)
        if entry != null:
            dragonLayers.merge(block, layers, Integer::sum)  // 按类型累加层数
    
    entry = otherEntries.get(block)
    if entry != null:
        otherCounts.merge(block, 1, Integer::sum)            // 按类型计数

// 遍历完后求和应用上限：
total = 0

for each dragon block:
    entry = dragonEntries.get(block)
    total += min(dragonLayers.get(block), entry.limit) × entry.value

for each other block:
    entry = otherEntries.get(block)
    total += min(otherCounts.get(block), entry.limit) × entry.value
```

注意：`state.getBlock()` 结果提取为局部变量，复用给 `instanceof` 和 `otherEntries.get()`，避免重复调用。

### 4.3 TreasureGrowthHandler 改造

移除 `weightMap`、`weightMapConfigHash` 缓存，不再调用 `buildWeightMap()`。其余逻辑不变（`isResting` → 扫描 → 封顶 → 效果 → actionbar）。

## 5. 配置变更

- **移除** `Config.TreasureGrowth.treasureWeights`
- **保留** `enabled`、`maxTreasureValue`、`amplifierStep`、`maxAmplifier`、`effectDurationTicks`、`checkIntervalTicks`

## 6. 边界情况

| 场景 | 处理 |
|------|------|
| JSON 解析失败 | 跳过该 entry，log warning |
| 方块未在 JSON 中定义 | 不计入 value（无兜底权重） |
| 数据包重载 | `SimpleJsonResourceReloadListener.apply()` 自动触发，重建缓存 |
| 联动财宝模组未安装 | `BuiltInRegistries.BLOCK.getOptional` 解析失败 → 跳过该 entry |
| limit = 0 | 该类型不提供任何价值 |
| JSON 文件不存在 | 空缓存，value = 0（不崩溃） |
| 玩家传送/死亡/重登/跨维度 | 与现有逻辑一致，`isResting()` 变为 false，效果自然过期 |

## 7. 性能

每检查间隔（默认 20 ticks）单次扫描 4608 格。`getBlockState()` 仍为瓶颈（chunk lookup）。新增的 `otherEntries.get(block)` 每次 HashMap O(1) 查找 ~3ns，总计 ~14 微秒额外开销，可忽略。

## 8. 文件清单

| 文件 | 动作 |
|------|------|
| `treasure/TreasureGrowthEntry.java` | **新增** |
| `treasure/TreasureGrowthLoader.java` | **新增** |
| `treasure/TreasureValueCalculator.java` | **修改** |
| `treasure/TreasureGrowthHandler.java` | **修改** |
| `Config.java` | **修改** |
| `resources/data/beloong/beloong/treasure_growth/dragon_treasure.json` | **新增** |
| `resources/data/beloong/beloong/treasure_growth/other_treasure.json` | **新增** |
