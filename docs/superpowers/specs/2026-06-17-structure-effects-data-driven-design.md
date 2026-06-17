# Structure Effects 数据驱动改造 — Design Spec

**Date:** 2026-06-17
**Status:** Approved

## 1. Overview

将 `structure_effects` 配置的 `entries` 从 TOML 字符串解析迁移为 Codec 数据驱动 JSON（`SimpleJsonResourceReloadListener`），同时新增 `advancement` 字段支持进度条件抑制效果。

---

## 2. 数据模型

### 2.1 StructureEffectEntry（JSON Codec）

```java
public record StructureEffectEntry(
    Holder<MobEffect> effect,
    int amplifier,
    int duration,
    boolean showParticles,
    Optional<ResourceLocation> advancement
)
```

| JSON 字段 | Java 类型 | 说明 |
|-----------|-----------|------|
| `effect` | `Holder<MobEffect>` | 药水效果 ID，经 `ResourceLocation.CODEC.comapFlatMap` 解析 |
| `amplifier` | `int` | 效果等级，0 = I 级 |
| `duration` | `int` | 持续时间（ticks） |
| `show_particles` | `boolean` | 是否显示粒子，默认 false |
| `advancement` | `Optional<ResourceLocation>` | 可选，完成后跳过该效果 |

Codec 使用 `RecordCodecBuilder`，`effect` 通过 `BuiltInRegistries.MOB_EFFECT.getOptional` 从字符串解析。`advancement` 使用 `optionalFieldOf`，省略即 `Optional.empty()`。

### 2.2 EffectEntry 更新

```java
public record EffectEntry(
    Holder<MobEffect> effect,
    int amplifier,
    int durationTicks,
    boolean showParticles,
    Optional<ResourceLocation> advancement
)
```

在现有 record 中新增 `showParticles` 和 `advancement` 字段，替代原有硬编码的 `true`。

---

## 3. JSON 格式

**目录:** `src/main/resources/data/beloong/beloong/structure_effects/`

顶层 key 为结构 ID，value 为该结构的 effect 列表：

```json
{
  "cataclysm:burning_arena": [
    {
      "effect": "beloong:flight_ban",
      "amplifier": 5,
      "duration": 100,
      "show_particles": false,
      "advancement": "cataclysm:kill_ignis"
    },
    {
      "effect": "minecraft:slowness",
      "amplifier": 2,
      "duration": 200,
      "show_particles": true
    }
  ],
  "minecraft:stronghold": [
    {
      "effect": "beloong:flight_ban",
      "amplifier": 3,
      "duration": 60,
      "advancement": "minecraft:story.enter_the_end"
    }
  ]
}
```

- `advancement` 可选，省略表示无条件始终施加
- 多个文件可并存，`apply()` 时自动合并

---

## 4. Loader — StructureEffectLoader

```
StructureEffectLoader (extends SimpleJsonResourceReloadListener)
├── 目录: "beloong/structure_effects"
├── Codec: Codec.unboundedMap(Codec.STRING, Codec.list(StructureEffectEntry.CODEC))
├── apply() 遍历所有 JSON 文件，合并构建:
│   └── configMap: Map<ResourceKey<Structure>, List<EffectEntry>>
│       - structure ID 经 ResourceLocation 解析为 ResourceKey<Structure>
│       - JSON 解析失败 → log error + 跳过该 entry
│       - 结构/效果 ID 不存在 → log warning + 跳过
│       - 同结构 ID 合并列表
└── 查询:
    └── getConfigMap() → Map<ResourceKey<Structure>, List<EffectEntry>>
```

- 单例模式 `INSTANCE`，参考 `TreasureGrowthLoader`
- 在 `BeLoongCore.addServerReloadListeners` 中注册
- 数据包 `/reload` 时自动重建缓存

---

## 5. StructureEffectHandler 改造

### 变更

| 变更 | 说明 |
|------|------|
| 移除 `refreshConfig()` | 不再从 TOML 解析 entries |
| 移除 `configMap`、`lastConfigHash` | 改从 `StructureEffectLoader.INSTANCE.getConfigMap()` 查询 |
| `watchedEffects` 保留 | 仍在 Config 中从 TOML 解析，逻辑不变 |
| `doCheckAndApply` 新增进度检查 | 每个 entry 查 `player.getAdvancements().getOrStartProgress(adv).isDone()` |

### doCheckAndApply 核心流程

```
configMap = StructureEffectLoader.INSTANCE.getConfigMap()
if configMap.isEmpty() → return

for each (structureKey, effects) in configMap:
    structure = registry.get(structureKey)
    start = structureManager.getStructureAt(player.pos, structure)
    if start 无效或不包含 player → continue

    for each entry in effects:
        if entry.advancement 存在 && 玩家已完成 → continue  // 跳过
        player.addEffect(...)
```

### 进度检查

使用原版 `ServerPlayer.getAdvancements().getOrStartProgress(advancement).isDone()`。若 advancement ID 不存在，`getOrStartProgress` 返回虚拟进度，`isDone()` 始终 false，不报错。

---

## 6. Config 变更

删除 `Config.StructureEffects.entries`，保留 `watchedEffects`：

```toml
[structure_effects]
    # 需要监听过期/移除事件的药水效果ID列表
    watched_effects = ["beloong:flight_ban"]
```

---

## 7. 文件清单

| 文件 | 动作 |
|------|------|
| `structure/StructureEffectEntry.java` | **新增** — 带 Codec 的 record |
| `structure/StructureEffectLoader.java` | **新增** — 数据驱动加载器 |
| `structure/EffectEntry.java` | **修改** — 加 `advancement` 字段 |
| `structure/StructureEffectHandler.java` | **修改** — 用 Loader 缓存 + 进度检查 |
| `Config.java` | **修改** — 删除 `entries` 字段 |
| `BeLoongCore.java` | **修改** — 注册 StructureEffectLoader |
| `data/beloong/beloong/structure_effects/default_effects.json` | **新增** |

---

## 8. 边界情况

| 场景 | 处理 |
|------|------|
| JSON 解析失败 | DataResult.error → log error + 跳过该 entry |
| 结构 ID / 效果 ID 不存在 | `comapFlatMap` 失败 → log warning + 跳过 |
| `advancement` 字段省略 | `Optional.empty()` → 永久生效 |
| 进度 ID 不存在 | `getOrStartProgress` 返回虚拟进度，`isDone()` 始终 false |
| 数据包 `/reload` | SimpleJsonResourceReloadListener 自动重建缓存 |
| 空 JSON 文件 | 空 map，功能静默不启用 |
| `watched_effects` 为空 | 效果过期重检不生效 |
| 多文件同结构 ID | 合并列表 |
| 同结构同效果重复定义 | 后者覆盖前者 |

---

## 9. 测试

1. 进入 `cataclysm:burning_arena` → 获得 flight_ban 5 级 5s + slowness 2 级 10s
2. 完成 `cataclysm:kill_ignis` 后进入 → 只获得 slowness
3. 离开结构后效果自然过期 → 不重新获得
4. `/effect clear` 后仍在结构内 → watched effect 触发重检
5. 维度切换/登录/重生 → 正确检测
6. JSON 含无效结构 ID → 仅该条目被跳过，其余正常
7. `/reload` 后新 JSON 立即生效
