# Dragon Survival 剩余问题修复设计

**日期：** 2026-08-14  
**状态：** 已确认  
**范围：** 审查报告 3.1.3 - 3.1.8

## Problem Statement

修复 BeLoong-Core 中与 Dragon Survival 相关的剩余重要问题：

1. `ToggleFlightMixin` 在网络线程读取属性，存在线程安全风险。
2. `air_strike.json` 的 `targeting_mode` 与“敌我不分”实际设计不一致。
3. `TreasureValueCalculator` 多扫一层方块。
4. `DragonStateHandlerMixin` 静默修改 DS 默认大型龙破坏行为，缺少说明。
5. `ProjectileDamageEffectMixin` 可能冗余。
6. 化龙池水接触检测每 tick 全量扫描方块，存在性能问题。

## Design

### 架构

- 3.1.3：将 `ToggleFlightMixin` 的门控从 `handleServer` HEAD（网络线程）迁移到 `lambda$handleServer$1` HEAD（主线程）。
- 3.1.4：保持 `AirStrikeEffect` 代码不变，将 `air_strike.json` 的 `targeting_mode` 改为 `all_except_self`。
- 3.1.5：修正 `TreasureValueCalculator` 的 AABB 边界，使用 `Mth.floor(Math.nextDown(max))`。
- 3.1.6：保持 `DragonStateHandlerMixin` 行为不变，补充 Javadoc 说明这是刻意设计。
- 3.1.7：保持 `ProjectileDamageEffectMixin` 现状。
- 3.1.8：`BeloongWaterContactHandler` 增加维度早退和“玩家方块坐标变化才扫描”的缓存。

### 组件

1. `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ToggleFlightMixin.java`
   - 移除 `handleServer` HEAD 注入。
   - 新增 `lambda$handleServer$1` HEAD 注入，返回 `ToggleFlight.Result.NONE`。

2. `src/main/resources/data/dragonsurvival/dragonsurvival/dragon_ability/air_strike.json`
   - `targeting_mode` 改为 `all_except_self`。

3. `src/main/java/com/zonlong/beloong/treasure/TreasureValueCalculator.java`
   - 新增 `import net.minecraft.util.Mth;`
   - 使用 `Mth.floor(Math.nextDown(max))` 修正扫描边界。

4. `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/DragonStateHandlerMixin.java`
   - 补充 Javadoc，说明默认 DISABLED 是刻意设计。

5. `src/main/java/com/zonlong/beloong/fluid/BeloongWaterContactHandler.java`
   - 新增 `Map<UUID, BlockPos> lastScannedPositions`。
   - `onPlayerTick` 增加维度早退。
   - 方块坐标未变化时跳过扫描。
   - 换维度/重生/登出时清理缓存。

6. `ProjectileDamageEffectMixin.java`
   - 不修改。

### 数据流

见设计讨论记录：
- `ToggleFlightMixin`：网络线程 → `enqueueWork` → 主线程 lambda → 门控返回 `NONE` 或执行原逻辑。
- `BeloongWaterContactHandler`：tick → 维度早退 → 方块坐标变化检查 → 扫描 → 更新接触状态。
- 其余为数据/注释修改，无运行时数据流变化。

### 错误处理

- `ToggleFlightMixin`：`context.player()` 为 null 时直接返回；DS 版本升级需重新验证 lambda 方法名。
- `TreasureValueCalculator`：开区间边界用 `nextDown` 正确处理。
- `BeloongWaterContactHandler`：登出/换维度/重生清理缓存；同一位置数据包重载可能延迟到移动后检测，属于已知取舍。
- 其余保持现状。

### 测试策略

1. `./gradlew compileJava` 构建通过。
2. 游戏内验证：
   - 飞行等级 < 0 时无法展翅，正常飞行不受影响。
   - 龙击长空仍敌我不分。
   - 财宝价值计算正常。
   - 新玩家大型龙破坏默认禁用。
   - 化龙池水进入/离开正常，静止时不再每 tick 扫描。
   - 非龙宫维度完全跳过化龙池水检测。

## Decisions Made

- **ToggleFlightMixin 使用 lambda 注入**：主线程执行属性读取，解决线程安全问题。
- **AirStrike 保持敌我不分**：代码不改，只修正数据文件语义为 `all_except_self`。
- **TreasureValue 边界修正**：使用 `Mth.floor(Math.nextDown(max))`。
- **DragonStateHandlerMixin 保持现状**：默认 DISABLED 是刻意设计，仅补充文档。
- **ProjectileDamageEffectMixin 保留**：防御未知环境崩溃。
- **BeloongWater 使用方块坐标缓存 + 维度早退**：降低性能开销，同时保持判定精度。

## Non-Goals

- 不改变龙击长空的实际伤害目标。
- 不改变大型龙破坏默认禁用行为。
- 不删除 `ProjectileDamageEffectMixin`。
- 不引入自动化测试框架。

## Next Steps

调用 planning skill 创建详细实施计划。
