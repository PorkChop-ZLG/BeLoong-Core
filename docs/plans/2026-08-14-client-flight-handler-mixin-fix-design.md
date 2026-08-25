# ClientFlightHandlerMixin 修复设计

**日期：** 2026-08-14  
**状态：** 已确认  
**方案：** 方案 A（统一重构 ClientFlightHandlerMixin）

## Problem Statement

修复 `ClientFlightHandlerMixin` 中与 Dragon Survival 飞行相关的 4 个问题：

1. 低飞行等级时，滑翔/旋转/落地也会被额外加重力。
2. 注释说“不覆盖 stableHover”，但代码实际覆盖了 stableHover。
3. 低飞行等级进入水中会被额外重力拉入水底，难以浮起。
4. IDEA 构建时 `@Shadow` 字段出现 `Unable to locate obfuscation mapping` 警告。

## Design

### 架构

- 修改 `ClientFlightHandlerMixin`，让 Mixin 只在以下条件全部满足时干预：
  - 配置 `fixStableHoverDrift` 开启。
  - DS `stableHover = true`。
  - 玩家是龙、翅膀展开、有飞行能力。
  - 玩家无 WASD 输入、未按跳跃、未按潜行。
  - 空中状态：玩家处于真实空中飞行（`ServerFlightHandler.isFlying`）且不是滑翔/旋转。
  - 水中状态：`flightLevel >= 1` 时锁定当前高度；`flightLevel < 1` 时不干预。
- 新增 `ClientFlightHandlerAccessor`，用 `@Accessor` 替代 `@Shadow` 读写 `ax/ay/az`。
- 在 `beloong.mixins.json` 的 `client` 列表注册 Accessor。

### 组件

1. `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerAccessor.java`
   - 新增。
   - 提供静态 `@Accessor` setter：
     - `beloong$setAx(double)`
     - `beloong$setAy(double)`
     - `beloong$setAz(double)`
   - 方法体使用 `throw new AssertionError()`，由 Mixin 在运行时替换。

2. `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerMixin.java`
   - 删除三个 `@Shadow` 字段。
   - 重写 `fixStableHoverDrift` 逻辑。
   - 使用 `ClientFlightHandlerAccessor` 写入 `ax/ay/az`。

3. `src/main/resources/beloong.mixins.json`
   - 在 `client` 列表加入 `dragonsurvival.ClientFlightHandlerAccessor`。

### 数据流

```text
Client tick
  -> DS ClientFlightHandler.flightControl()
  -> TAIL 注入点
  -> ClientFlightHandlerMixin.fixStableHoverDrift()
  -> 逐层门控检查
  -> 水中且 flightLevel >= 1：清零 ay + 垂直速度，锁定当前高度
  -> 空中且 flightLevel >= 1：清零 ax/az，创造模式清零 ay + 垂直速度
  -> 空中且 flightLevel < 1：追加一次 -gravity
  -> 水中且 flightLevel < 1：不干预
```

### 错误处理

- 所有“不干预”场景均提前返回。
- `flight_level` 属性缺失时 `ModAttributes.getFlightLevel` 返回 `0.0`，安全降级为非稳定悬停。
- Accessor 未注册会在运行时抛 `AssertionError`，因此必须同步完成注册。
- DS API 变更会在编译期暴露。

### 测试策略

1. 构建验证：`./gradlew compileJava` 或 `./gradlew build`，确认无 `@Shadow` 警告。
2. 游戏内手动验证：
   - 稳定悬停/创造模式不再上飘。
   - 低飞行等级空中无输入仍会下坠。
   - 低飞行等级进入水中不再沉底。
   - 高飞行等级进入水中无输入时锁定当前高度，不缓慢下沉。
   - 滑翔、旋转、地面、`stableHover=false` 时 Mixin 不干预。

## Decisions Made

- **尊重 DS stableHover：** `stableHover = false` 时 Mixin 完全不干预。
- **不处理滑翔漂移：** 保持与现状一致，滑翔由 DS 原版物理控制。
- **使用 Accessor 替代 @Shadow：** 统一到项目已有的 Accessor 风格，消除构建警告。
- **只在无操作输入时干预：** 避免额外重力与跳跃/潜行/WASD 操作冲突。
- **水中稳定悬停：** `flightLevel >= 1` 时在水中锁定当前高度；`flightLevel < 1` 时保持原版水中行为。

## Non-Goals

- 不新增滑翔漂移修复。
- 不改变 `ToggleFlightMixin`、`AirStrikeEffect` 等其他审查问题。
- 不修改 DS 模组本体。

## Next Steps

调用 planning skill 创建详细实施计划。
