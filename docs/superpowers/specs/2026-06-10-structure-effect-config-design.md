# Structure Effect Config — Design Spec

**Date:** 2026-06-10
**Status:** Approved

## Overview

玩家进入特定结构时自动获得配置的药水效果，离开后效果自然过期消失。纯配置驱动，零 Mixin，事件驱动检测。

---

## Config Design

### TOML Schema

Server 配置，在 `Config.java` 中新增 `structure_effects` 节：

```toml
[structure_effects]
    # 需要监听过期事件的药水效果ID列表
    # 当这些效果在玩家身上过期时，触发结构重检
    watched_effects = ["beloong:flight_ban"]

    # "结构ID|效果ID|等级|持续时间(tick)"
    # 等级: 0 = 一级, amplifier = 0，以此类推
    entries = [
        "cataclysm:burning_arena|beloong:flight_ban|5|1200"
    ]
```

### Parsing

- `watched_effects`: `ConfigValue<List<? extends String>>`，解析为 `Set<ResourceKey<MobEffect>>`
- `entries`: 按 `|` 分割，分组为 `Map<ResourceKey<Structure>, List<EffectEntry>>`
- 解析失败条目 → WARN 日志 + 跳过，不阻止模组加载

### EffectEntry Record

```java
record EffectEntry(Holder<MobEffect> effect, int amplifier, int durationTicks) {}
```

---

## Handler Architecture

### Class: `StructureEffectHandler`

**位置:** `com.zonlong.beloong.structure`

**状态:**
- `Map<ResourceKey<Structure>, List<EffectEntry>> configMap` — 配置的结构→效果映射
- `Set<ResourceKey<MobEffect>> watchedEffects` — 监听的效果集合
- `Map<UUID, ChunkPos> playerLastChunk` — 区块变化去重缓存

### Triggers (4 entry points)

| Trigger | Action |
|---|---|
| `ServerTickEvent.END` | 遍历在线玩家，chunk pos 变化 → `checkAndApply` |
| `MobEffectEvent.Expired` | effect 在 watchedEffects 中 → `checkAndApply` |
| `PlayerEvent.PlayerChangedDimensionEvent` | clear cache + `checkAndApply` |
| `PlayerEvent.PlayerLoggedInEvent` / `PlayerEvent.PlayerRespawnEvent` | clear cache + `checkAndApply` |

### Core Method: `checkAndApply(ServerPlayer player)`

1. `level.structureManager().getAllStructuresAt(player.blockPosition())` — 单次 O(1) 查询
2. Filter → 仅保留 `configMap.keySet()` 中配置的结构
3. 对每个匹配的 `StructureStart`，检查 `player.getBoundingBox().intersects(start.getBoundingBox())`
4. 收集匹配的 EffectEntry 列表
5. 对每个 entry → `player.addEffect(new MobEffectInstance(effect, duration, amplifier))`

`addEffect` 自然处理同效果刷新：如果效果已存在且等级≥已有等级，自动覆盖并刷新 duration。

---

## Error Handling

- 配置条目缺失字段 → WARN 日志 + 跳过该条
- 结构 ID 不存在 → WARN + 跳过（允许跨模组声明）
- 效果 ID 不存在 → WARN + 跳过
- `entries` 为空 → 功能静默不启用
- `watched_effects` 为空 → 效果过期重检不生效
- `getAllStructuresAt` 返回 null/空 → 静默跳过
- 玩家不在线/已死亡 → 自然被 tick 遍历过滤
- 效果被 `/effect clear` 手动清除 → 不触发 Expired 事件，自然等待下次触发点

---

## Performance

- **tick 成本:** 每玩家一次 `ChunkPos.equals()` 比较（几乎为零）
- **检测成本:** 仅 chunk 变化时触发；`getAllStructuresAt` 单次 hashmap 查询，与配置条目数量无关
- **零持续开销:** 效果维持由 `Expired` 事件驱动，无定时器

---

## Files Changed

### New
- `src/main/java/com/zonlong/beloong/structure/StructureEffectHandler.java`
- `src/main/java/com/zonlong/beloong/structure/EffectEntry.java`

### Modified
- `Config.java` — 新增 `ServerStructureEffects` 内部类 + 注册调用
- `BeLoongCore.java` — 实例化并注册 `StructureEffectHandler`

### No changes
- mixin config、资源文件、build.gradle — 无需修改

---

## Testing

1. 传送至 `cataclysm:burning_arena` → 获得禁空 5 级 60s
2. 等待 60s 过期 → 仍在结构内 → 效果重新应用
3. 飞离结构 → 效果自然过期 → 不再获得
4. `/effect clear` 后走出结构 → 不触发效果
5. 维度切换返回 → 仍在结构内 → 重新获得效果
6. 退出重进 → 获得效果
7. 空 `entries` → 不报错
8. 无效结构 ID → WARN 日志，其他条目正常工作
