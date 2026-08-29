# 末影龙手动召唤仪式实施计划

**Date:** 2026-08-29
**Status:** Implemented & verified
**Depends on:** `docs/plans/2026-08-29-dragon-summon-ritual-design.md`

## 目标

在 BeLoong-Core 中实现 YUNG's Better End Island 的手动召唤仪式兼容层：

- 阻止 BEI 自动生成 4 颗召唤水晶。
- 玩家在 4 个仪式位置放置配置方块后，方块转化为末地水晶并触发 BEI 召唤/复活流程。
- 不修改原版 Minecraft 类，不直接修改 BEI 源码。

## 实施步骤

### 1. 构建依赖

修改 `build.gradle`：

```groovy
// YUNG's API - BEI 前置
compileOnly "curse.maven:yungs-api-neoforge-1015100:6715463"
localRuntime "curse.maven:yungs-api-neoforge-1015100:6715463"

// YUNG's Better End Island
compileOnly "curse.maven:yungs-better-end-island-neoforge-1015127:6300968"
localRuntime "curse.maven:yungs-better-end-island-neoforge-1015127:6300968"
```

### 2. 配置

在 `Config.java` 的 SERVER 配置中新增 `dragon_summon` 节：

- `enabled`（默认 `true`）
- `summonBlock`（默认 `bosses_of_mass_destruction:levitation_block`）

使用显式翻译 key：

- `beloong.configuration.dragonSummonEnabled`
- `beloong.configuration.dragonSummonBlock`

### 3. 本地化

更新 `assets/beloong/lang/en_us.json` 和 `zh_cn.json`：

- 配置节：`beloong.configuration.dragon_summon`
- 开关：`beloong.configuration.dragonSummonEnabled`
- 方块 ID：`beloong.configuration.dragonSummonBlock`
- 对应 tooltip

### 4. 原版标签

新增 `src/main/resources/data/minecraft/tags/block/dragon_immune.json`：

```json
{
  "replace": false,
  "values": [
    "minecraft:reinforced_deepslate"
  ]
}
```

### 5. 新建 `DragonSummonHelper`

包路径：`com.zonlong.beloong.compat.betterendisland`

职责：
- 判断功能是否启用（BEI 已加载 + 配置开启）。
- 从 `EndDragonFightAccessor` 获取 `portalLocation`。
- 计算 4 个仪式坐标：
  ```java
  center = portalLocation.above(1)
  for (Direction dir : Direction.Plane.HORIZONTAL) {
      pos = center.relative(dir, 8)
  }
  ```
- 判断某个坐标是否是仪式坐标。
- 在指定位置生成末地水晶：
  ```java
  EndCrystal crystal = new EndCrystal(level, x + 0.5, y, z + 0.5);
  crystal.setShowBottom(false);
  crystal.setInvulnerable(!hasDragonEverSpawned);
  level.addFreshEntity(crystal);
  ```

### 6. 新建 `DragonSummonHandler`

包路径：`com.zonlong.beloong.compat.betterendisland`

- 注册到 `NeoForge.EVENT_BUS`。
- 监听 `BlockEvent.EntityPlaceEvent`：
  - 快速失败检查。
  - 计算 4 个仪式坐标。
  - 检查 4 个位置是否同时为 `summonBlock`。
  - 触发仪式：
    1. 删除 4 个方块。
    2. 生成 4 颗末地水晶。
    3. 首次：`((IBetterDragonFight) fight).doInitialDragonSpawn()`
    4. 复活：`fight.tryRespawn()`

### 7. 新建 `DragonSummonMixin`

包路径：`com.zonlong.beloong.mixin.betterendisland`

目标：`com.yungnickyoung.minecraft.betterendisland.world.feature.BetterEndPodiumFeature`

- 使用 `@Redirect` 拦截 `level.addFreshEntity(crystal)`，在手动模式开启时跳过水晶生成。
- 使用 `@Inject(method = "place", at = @At("RETURN"))` 在中央塔生成后，把 4 个仪式位置下方的基岩替换为 `minecraft:reinforced_deepslate`。

> 注意：具体 `addFreshEntity` 调用签名和“下方基岩坐标”需在实现时用 `javap` 或游戏内验证确认。

### 8. 注册 Mixin

更新 `src/main/resources/beloong.mixins.json`：

```json
"mixins": [
  ...
  "betterendisland.DragonSummonMixin"
]
```

### 9. 注册事件处理器

在 `BeLoongCore` 构造函数中：

```java
NeoForge.EVENT_BUS.register(new DragonSummonHandler());
```

### 10. 验证

按设计文档“测试策略”执行游戏内验证。

## 风险点

- `BetterEndPodiumFeature` 的 `addFreshEntity` 调用签名可能随 BEI 版本变化，需要先用 `javap` 确认。
- 仪式位置“下方基岩”的准确坐标需要游戏内确认。
- `reinforced_deepslate` 是否能真正阻止玩家手动放置末地水晶需要实测。
- BEI 每 5 tick 检查的残余开销已接受，不处理。
