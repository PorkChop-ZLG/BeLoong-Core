# 末影龙手动召唤仪式（YUNG's Better End Island 兼容）设计文档

**Date:** 2026-08-29
**Status:** Approved
**Approach:** 方案 B（事件驱动 + 最少 Mixin）

## Problem Statement

整合包使用 YUNG's Better End Island（BEI）重做末地战斗。当前 BEI 在玩家靠近末地中心时会自动召唤末影龙，且首次进入末地会自动生成 4 颗“召唤水晶”围绕中央塔。需求是：

1. 取消首次战斗的自动召唤，改为玩家手动召唤。
2. 玩家需在原本 4 颗召唤水晶的位置放置配置的特殊方块。
3. 4 个位置都放满后，方块被删除并转化为末地水晶，随后自动走 BEI 的召唤/复活流程。
4. 首次战斗和死亡后复活都使用该手动召唤机制。
5. 在 BeLoong-Core 中通过 Mixin / 事件实现，不直接修改 BEI 源码。

## Design

### Architecture

```
BeLoong-Core
├── Config.java
│   └── server 配置节 dragon_summon
│       ├── enabled = true
│       └── summonBlock = "bosses_of_mass_destruction:levitation_block"
│
├── compat/betterendisland/
│   ├── DragonSummonHandler        # NeoForge 事件主逻辑
│   ├── DragonSummonMixin          # Mixin 进 BEI BetterEndPodiumFeature
│   └── DragonSummonHelper         # 仪式坐标计算、水晶生成
│
├── beloong.mixins.json
│   └── 新增 "mixins": ["compat.betterendisland.DragonSummonMixin"]
│
└── build.gradle
    ├── compileOnly "curse.maven:yungs-api-neoforge-1015100:6715463"
    ├── localRuntime "curse.maven:yungs-api-neoforge-1015100:6715463"
    ├── compileOnly "curse.maven:yungs-better-end-island-neoforge-1015127:6300968"
    └── localRuntime "curse.maven:yungs-better-end-island-neoforge-1015127:6300968"
```

### Components

#### 1. Config

服务端配置新增 `dragon_summon` 节：

- `enabled`：总开关，默认 `true`
- `summonBlock`：召唤用特殊方块 ID，默认 `bosses_of_mass_destruction:levitation_block`

使用显式翻译 key：

- `beloong.configuration.dragonSummonEnabled` = “末影龙召唤仪式” / “Ender Dragon Summon Ritual”
- `beloong.configuration.dragonSummonBlock` = “召唤方块ID” / “Summon Block ID”
- `beloong.configuration.dragon_summon` = 配置节标题

#### 2. DragonSummonMixin（Mixin 进 BEI `BetterEndPodiumFeature`）

仅当 `dragon_summon.enabled = true` 且 BEI 已加载时生效：

- 阻止 BEI 在中央塔周围生成 4 颗召唤水晶。
- 把 4 个仪式位置下方的基岩替换为 `minecraft:reinforced_deepslate`。
- 不修改原版 Minecraft 类。

#### 3. DragonSummonHandler（NeoForge 事件）

监听 `BlockEvent.EntityPlaceEvent`：

- 快速失败：BEI 未加载 / 配置关闭 / 非末地 / 方块不匹配 / `portalLocation` 为 null。
- 通过 `EndDragonFightAccessor.getPortalLocation()` 获取传送门位置。
- 通过 `DragonSummonHelper` 计算 4 个仪式坐标（复刻 BEI 中央塔水晶公式：`portalLocation.above(1)` 四方向偏移 8 格）。
- 当 4 个位置同时为 `summonBlock` 时：
  1. 删除 4 个方块（设空气，不掉落）。
  2. 在相同位置生成 4 颗 `EndCrystal`。
  3. 显式触发：
     - 首次：`((IBetterDragonFight) fight).doInitialDragonSpawn()`
     - 复活：`fight.tryRespawn()`

#### 4. DragonSummonHelper

- 从 `EndDragonFightAccessor` 获取 `portalLocation`。
- 计算 4 个仪式坐标。
- 判断坐标是否属于仪式位置。
- 生成末地水晶（`setShowBottom(false)`，首次/复活按需设置 `setInvulnerable`）。

#### 5. 资源文件

新增 `src/main/resources/data/minecraft/tags/block/dragon_immune.json`：

```json
{
  "replace": false,
  "values": [
    "minecraft:reinforced_deepslate"
  ]
}
```

确保强化深板岩不会被末影龙破坏。

### Data Flow

```
Config → DragonSummonHandler → BlockEvent.EntityPlaceEvent
  → 检查 4 个仪式位置是否放满 summonBlock
  → 删除方块 → 生成 EndCrystal
  → doInitialDragonSpawn() / tryRespawn()
  → BEI 原召唤/复活阶段动画接管
```

### Error Handling

- BEI 未安装或配置关闭：完全禁用，不影响原版/BEI。
- `portalLocation` 未初始化：忽略放置，等待 BEI 初始化。
- 4 个位置未同时放满：不触发，无进度状态。
- 错误位置放置：忽略。
- 强化深板岩被玩家破坏：不自动修复（当前非目标）。
- 重复触发：触发后 4 个方块已删除，天然防重入。
- 服务器重启：无持久化状态，玩家重新放置即可。

## Decisions Made

- 采用方案 B：事件驱动 + 最少 Mixin。
- 手动召唤同时覆盖首次战斗与死亡后复活。
- 首次战斗时 BEI 不再自动生成 4 颗召唤水晶。
- 完全用特殊方块替代直接放置末地水晶。
- 配置只支持单个方块 ID。
- 不加原版 Mixin，接受 BEI 每 5 tick 检查的残余开销（仅在首次战斗未开始阶段存在）。
- 基岩替换为 `minecraft:reinforced_deepslate`，并加入 `minecraft:dragon_immune` 标签。
- 显式调用 BEI 触发方法，玩家无需额外操作。
- 不添加水晶标签，不过滤实体。

## Non-Goals

- 不修改 BEI 源码。
- 不 Mixin 原版 Minecraft 类。
- 不自动修复被玩家破坏的强化深板岩。
- 不提供多种召唤方块（仅单个配置 ID）。
- 不处理 BEI 使用原版祭坛模式（`spawnCentralTowerInitially=false`）时的额外兼容路径（当前默认中央塔路径）。

## Next Steps

调用 planning skill 创建详细实施计划。
