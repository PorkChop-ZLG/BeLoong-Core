# 维度传送机制（Dimension Transport）

## 概述

通过 Java 代码实现主世界与龙宫维度之间的条件传送：主世界飞到足够高自动进入龙宫，龙宫掉到足够低自动返回主世界。配置驱动，无硬编码。

## 进入方式

1. **主世界 → 龙宫**：玩家 Y > 配置阈值（默认 8848）后自动传送
2. **龙宫 → 主世界**：玩家 Y < 配置阈值（默认 0）后自动传送

两条路径可独立开启/关闭。

## 文件结构

```
src/main/java/com/zonlong/beloong/
├── Config.java                              # 修改：新增 dimension_transport 配置段
└── transport/
    ├── DimensionTransportHandler.java       # 新增：PlayerTickEvent 处理器
    └── DimensionTransportRecord.java        # 新增：单次传送的上下文 record
```

## 配置

### 配置结构

`beloong-server.toml`，使用 `ModConfigSpec`，所有配置收敛在 `dimension_transport` 分类下：

```toml
[dimension_transport]
    checkIntervalTicks = 20
    cooldownTicks = 100

[dimension_transport.overworldToLoongPalace]
    enabled = true
    triggerY = 8848
    targetDimension = "beloong:loong_palace"
    targetX = 0.0
    targetZ = 0.0
    fallbackY = 64.0

[dimension_transport.loongPalaceToOverworld]
    enabled = true
    triggerY = 0
    targetDimension = "minecraft:overworld"
    targetX = 0.0
    targetZ = 0.0
    fallbackY = 64.0
```

### 配置项说明

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | boolean | true | 该方向传送是否启用 |
| `triggerY` | int | 8848 / 0 | 触发传送的 Y 轴阈值。主世界方向为"大于"，龙宫方向为"小于" |
| `targetDimension` | string | — | 目标维度 ID（`ResourceLocation` 格式） |
| `targetX` | double | 0.0 | 目标固定 X 坐标 |
| `targetZ` | double | 0.0 | 目标固定 Z 坐标 |
| `fallbackY` | double | 64.0 | 高度图查找失败时的回退 Y 坐标 |
| `checkIntervalTicks` | int | 20 | 检查间隔（ticks），防止每 tick 性能开销 |
| `cooldownTicks` | int | 100 | 传送后冷却时间（ticks），防止循环传送 |

`checkIntervalTicks` 和 `cooldownTicks` 为两方向共用全局配置，位于 `dimension_transport` 分类根级。

## 核心逻辑

### 触发检测

`PlayerTickEvent.Post` 中，按如下条件判断是否触发：

1. 玩家为 `ServerPlayer`（客户端判断直接跳过）
2. 玩家 `isAlive()` 且 `!isRemoved()`
3. 冷却计时器已归零（`cooldownTicks` 检查）
4. 检查间隔计数到达（`checkIntervalTicks` 检查）
5. 玩家当前维度匹配配置中任一"来源"维度
6. Y 坐标满足触发条件（> triggerY 或 < triggerY）

触发后立即进入传送流程。

### 传送流程

1. 解析目标维度 ID → 获取 `ServerLevel`
2. 获取玩家骑乘实体 → `stopRiding()`（如存在）
3. 在目标坐标 (targetX, targetZ) 处查询 `Heightmap.Types.MOTION_BLOCKING`
4. 若高度图返回有效值 → 目标 Y = `topY + 1`（方块表面上方 1 格）
5. 若高度图返回 null 或最小值 → 目标 Y = `fallbackY`
6. `serverPlayer.teleportTo(targetLevel, targetX, targetY, targetZ, yaw, pitch)`
7. 设置玩家冷却计时器为 `cooldownTicks`

传送时保留玩家当前 yaw 和 pitch，不改变朝向。

### 冷却机制

每个玩家维护一个 tick 计数器，传送后设置为 `cooldownTicks`，每个 tick 递减。冷却期间不触发任何方向传送。

## 边界情况

| 场景 | 处理方式 |
|------|---------|
| 目标维度无效（配置输入错误） | 启动时校验 `ResourceLocation`，无效则打 warning 日志并跳过注册该方向 handler |
| 目标坐标不安全 | 高度图查找为 MOTION_BLOCKING 类型（包含所有非空气、非流体方块），天然避免卡方块 |
| 玩家离线/死亡 | `isAlive()` + `!isRemoved()` 检查，不满足则直接 return |
| 骑乘状态 | 传送前 `stopRiding()` |
| 维度恰好是来源维度自己 | 不触发（`targetDimension` 与当前维度相同则跳过） |
| 多个传送方向同时满足 | 每条方向独立检查，先匹配的先执行（配置顺序决定） |

## 测试

### 场景覆盖

| 测试场景 | 预期结果 |
|----------|---------|
| 主世界 Y > 8848 | 传送到龙宫配置坐标 |
| 龙宫 Y < 0 | 传送到主世界配置坐标 |
| `enabled = false` | 不触发传送 |
| 冷却时间内 | 不触发传送 |
| 目标维度无效 | 启动日志 warning，不触发 |
| Y 坐标精确位于阈值上 | 不触发（严格大于/小于，不含等于） |

### 测试方法

使用 Minecraft GameTest 框架（如项目支持）或手动游戏内验证。高度图 Y 查找逻辑可提取为纯函数单独单元测试。

## 依赖

- 无新增外部依赖
- 使用 NeoForge 21.1 `PlayerTickEvent.Post`、`ServerPlayer#teleportTo`、`Heightmap`
- 与现有 Mixin 无冲突

## 设计决策

- **为什么用 Java 而非数据包**：数据包无法提供 Neoforge `ModConfigSpec` 级别的配置体验（带注释的 toml 自动生成）。对于需要用户自定义坐标的传送机制，配置可用性是核心需求
- **为什么用 `checkIntervalTicks`**：每 tick Y 轴比较虽成本极低，但预留扩展空间；1 秒延迟在 8848 格高空的飞行场景中不可察觉
- **为什么用 `heightmap(TYPE_MOTION_BLOCKING)` 而非逐格遍历**：高度图是原版缓存，O(1) 查询，比从 build height 向下遍历高效且可靠
- **为什么高度图失败时回退到固定 Y 而非阻止传送**：龙宫是纯虚空世界，列中可能无方块，固定 Y 能让玩家至少生成在虚空中而非被卡住；配合 `/execute in` 命令进入也说明虚空站立是可接受的
- **为什么 `targetY` 改名为 `fallbackY`**：语义更准确——正常情况下 Y 由高度图决定，该值仅在查找失败时回退使用
