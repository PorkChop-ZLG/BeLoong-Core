# 末影龙召唤仪式粒子与音效反馈设计

**Date:** 2026-08-29
**Status:** Approved
**Approach:** 扩展 `DragonSummonHandler`（事件 + 服务端 tick）

## Problem Statement

当前末影龙手动召唤仪式缺少视觉/听觉引导。玩家不知道 4 个仪式位置在哪、放对方块没有反馈、完成时也缺少仪式感。需要增加：

1. 空位提醒粒子。
2. 单个方块放置成功反馈。
3. 仪式完成反馈。

用户明确要求：**转化末地水晶不需要延迟，保持现有即时转化**。

## Design

### Architecture

```
DragonSummonHandler（扩展）
├── onBlockPlace：单块成功反馈 / 完成反馈 + 即时转化
├── onServerTick：每 20 tick 发送空位提醒粒子
└── DragonSummonHelper（新增方法）
    ├── playSingleBlockPlacedEffects()
    ├── playRitualCompleteEffects()
    └── sendEmptySlotParticle()
```

### Components

#### 1. 空位提醒粒子

- `ServerTickEvent.Post` 中每 20 tick 执行。
- 仅当 `DragonSummonHelper.isAvailable()` 且末地 `ServerLevel` 存在时执行。
- 只处理距离仪式中心 32 格内的玩家。
- 对每个空位（当前位置不是 `summonBlock`）向这些玩家发送 `ParticleTypes.END_ROD`。

#### 2. 单个方块放置成功

- 在 `onBlockPlace` 中，当方块正确、位于仪式位置、且 4 个位置未全部放满时：
  - 播放 `SoundEvents.BEACON_POWER_SELECT`
  - 在该位置生成 `ParticleTypes.HAPPY_VILLAGER`

#### 3. 仪式完成反馈

- 在 `onBlockPlace` 中，当 4 个位置全部放满时：
  - 播放 `SoundEvents.BEACON_ACTIVATE`
  - 在 4 个位置生成 `ParticleTypes.DRAGON_BREATH`
  - 立即执行现有转化：
    1. 删除 4 个召唤方块
    2. 生成 4 颗末地水晶
    3. 触发 `doInitialDragonSpawn()` / `tryRespawn()`

### Data Flow

```
ServerTickEvent.Post (每20tick)
  → 空位粒子 → 32格内玩家

BlockEvent.EntityPlaceEvent
  ├─ 未放满 → BEACON_POWER_SELECT + HAPPY_VILLAGER
  └─ 放满 → BEACON_ACTIVATE + DRAGON_BREATH
          → 删除方块 → 生成水晶 → 触发 BEI
```

### Error Handling

- 未安装 BEI / 配置关闭：全部不执行。
- 空位粒子仅发给 32 格内玩家，性能可控。
- 重复放/拆方块不会重复转化。
- 无延迟、无 pending 状态，无需清理。

## Decisions Made

- 转化末地水晶不增加延迟，保持现状。
- 正确放置音效：`BEACON_POWER_SELECT`。
- 完成音效：`BEACON_ACTIVATE`。
- 单块成功粒子：`HAPPY_VILLAGER`。
- 完成粒子：`DRAGON_BREATH`。
- 空位提醒粒子：`END_ROD`，每 20 tick，仅 32 格内玩家。
- 不新增配置项，不新增 Mixin。

## Non-Goals

- 不增加转化延迟。
- 不新增自定义方块/BlockEntity。
- 不把粒子/音效做成可配置。
- 不改变现有仪式触发逻辑。

## Next Steps

创建实施计划并实现。
