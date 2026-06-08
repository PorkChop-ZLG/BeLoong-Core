# 飞行等级系统设计文档

## 概述

向龙之生存的飞行系统引入 `FLIGHT_LEVEL`（飞行等级）属性，将原来的二元模型（`hasFlight` 有无飞行 / `stableHover` 配置稳定悬停）替换为梯级飞行能力体系。同时在化龙核心中新增"禁空"状态效果，降低目标飞行等级，从而在龙翼技能成长与敌对 debuff 之间形成博弈玩法。

## 核心机制

| FLIGHT_LEVEL | 效果 |
|---|---|
| `< 0` | 无法飞行——翅膀展开被拒绝 |
| `= 0` | 可以飞行，不可稳定悬停（类似鞘翅漂移） |
| `>= 1` | 可以飞行 + 稳定悬停（类似创造飞行） |

属性 ID：`dragonsurvival:flight_level`，范围 `[-1024, 1024]`，默认值 `0`，同步到客户端。

## 组件拆解

### 1. FLIGHT_LEVEL 属性注册——Mixin 注入 DSAttributes

通过 Mixin 注入龙之生存的 `DSAttributes` 类，使用其已有的 `REGISTRY`（DeferredRegister）注册 `dragonsurvival:flight_level`。

- 通过 `EntityAttributeModificationEvent` attach 到 `EntityType.PLAYER`
- 调用 `setSyncable(true)` 确保客户端同步

### 2. 翅膀技能重制（3 级）

当前三种翅膀技能（`cave_wings`、`forest_wings`、`sea_wings`）为 2 级，使用 `condition_based` 升级类型（条件为 `flight_was_granted`）。重制后改为 3 级，使用 `dragon_growth` 升级类型。

```
Level 0 → 技能被禁用（usage_blocked: flight_was_granted = false）
Level 1 → hasFlight=true, FLIGHT_LEVEL=0（可以飞行，不能悬停）
Level 2 → hasFlight=true, FLIGHT_LEVEL=1（可以飞行 + 稳定悬停）
```

**升级规则：**
- `upgrade_type`：`dragon_growth`
- `maximum_level`：`2`
- `growth_requirement`：lookup `[0, 0, 60]` —— Level 2 需要龙成长度 >= 60（成年龙）
- `usage_blocked` 承载 `flight_was_granted: false`（从旧 ConditionUpgrade 迁移过来）
- 已有 `marked_by_ender_dragon` 条件通过 `minecraft:any_of` 合并到 `usage_blocked`

**各级实体效果：**
- `FlightEffect`（`level_requirement: 1`）—— 等级 ≥ 1 时授予 `hasFlight`
- `ModifierEffect`，属性为 `dragonsurvival:flight_level` —— 等级 1 提供 `+0`，等级 2 提供 `+1`（通过 `lookup [0, 0, 1]`）

### 3. 稳定悬停替换——Mixin 注入 ClientFlightHandler.flightControl()

在 `ClientFlightHandler.flightControl()` 中有两处引用了 `ServerFlightHandler.stableHover`，需要替换：

| 位置 | 旧代码 | 新代码 |
|---|---|---|
| ~370 行（悬停重力调整） | `ServerFlightHandler.stableHover && ...` | `player.getAttributeValue(FLIGHT_LEVEL) >= 1 && ...` |
| ~386 行（重力倍率分支） | `!ServerFlightHandler.stableHover` | `player.getAttributeValue(FLIGHT_LEVEL) < 1` |

### 4. 已有 Mixin 更新——ClientFlightHandlerMixin.fixStableHoverDrift()

化龙核心中已有的 `ClientFlightHandlerMixin` 第 58 行引用了 `ServerFlightHandler.stableHover`。替换如下：

```java
// 旧
boolean shouldHover = ServerFlightHandler.stableHover && ...

// 新
double flightLevel = player.getAttributeValue(FLIGHT_LEVEL);
boolean shouldHover = flightLevel >= 1 && ...
```

由于 `FLIGHT_LEVEL` 是通过 Mixin 注册的，无法直接通过 `DSAttributes.FLIGHT_LEVEL` 静态字段引用，需通过 `BuiltInRegistries.ATTRIBUTE.get(ResourceLocation.fromNamespaceAndPath("dragonsurvival", "flight_level"))` 获取。

### 5. 飞行门控——Mixin 注入 ToggleFlight.handleServer()

在 `ToggleFlight.handleServer()` 中，翅膀展开前（`!flight.areWingsSpread` 分支内）新增飞行等级检查：

```java
if (!flight.areWingsSpread) {
    // 新增：飞行等级门控
    if (getFlightLevel(player) < 0) {
        return Result.FLIGHT_BANNED;
    }
    // 已有检查：饱食度、trapped、broken_wings、能力解锁……
}
```

`Result` 枚举新增 `FLIGHT_BANNED` 值。

### 6. "禁空"状态效果——化龙核心

注册 `beloong:flight_ban` 状态效果：

- 类别：`HARMFUL`，颜色：`0x8B0000`（深红色）
- 属性修饰符：`dragonsurvival:flight_level`，数值 `-1`，运算 `ADD_VALUE`
- 原版自动按 `(amplifier + 1)` 缩放：I 级 → -1，II 级 → -2，III 级 → -3
- 由于 FLIGHT_LEVEL 通过 Mixin 注册，需通过 `BuiltInRegistries.ATTRIBUTE` 查找获取

### 7. 示例数据包文件

`src/main/resources/data/dragonsurvival/dragonsurvival/dragon_ability/cave_wings.json` —— 重制后的翅膀技能，作为参考和测试用例。

## 数据流

```
DragonGrowthUpgrade（龙成长度 >= 60）
        │
        ▼
  龙技能等级 = 2
        │
        ├──▶ FlightEffect.apply()
        │         └──▶ FlightData.hasFlight = true
        │
        └──▶ ModifierEffect.apply()
                  └──▶ FLIGHT_LEVEL 属性 = 1
                           │
                  被施加"禁空 I"
                           │
                           ▼
                  FLIGHT_LEVEL 有效值 = 0
                           │
                  被施加"禁空 II"
                           │
                           ▼
                  FLIGHT_LEVEL 有效值 = -1
                           │
                           ▼
                  ToggleFlight Mixin 拒绝展翅
```

## 文件清单

| 文件 | 操作 | 模块 |
|---|---|---|
| `mixin/DSAttributesMixin.java` | 新建 | 化龙核心 |
| `mixin/ClientFlightHandlerStableHoverMixin.java` | 新建 | 化龙核心 |
| `mixin/ClientFlightHandlerMixin.java` | 修改 | 化龙核心 |
| `mixin/ToggleFlightMixin.java` | 新建 | 化龙核心 |
| `registry/ModEffects.java` | 新建 | 化龙核心 |
| `registry/ModAttributes.java` | 新建（查找辅助） | 化龙核心 |
| `resources/beloong.mixins.json` | 修改 | 化龙核心 |
| `resources/data/dragonsurvival/.../cave_wings.json` | 新建 | 化龙核心 |
| `resources/assets/beloong/lang/zh_cn.json` | 修改 | 化龙核心 |
| `resources/assets/beloong/lang/en_us.json` | 修改 | 化龙核心 |

## 验证清单

1. 无翅膀技能的龙——无论 FLIGHT_LEVEL 值如何，均无法展翅飞行
2. 翅膀等级 1 的龙（青年龙）——可以飞行，不操作时漂移（无稳定悬停）
3. 翅膀等级 2 的龙（成年龙，成长度 >= 60）——可以飞行 + 稳定悬停，静止时无漂移
4. 成年龙 + 禁空 I ——可以飞行，无稳定悬停（FLIGHT_LEVEL 从 1 降为 0）
5. 成年龙 + 禁空 II ——无法飞行（FLIGHT_LEVEL 从 1 降为 -1）
6. 青年龙 + 禁空 I ——无法飞行（FLIGHT_LEVEL 从 0 降为 -1）
7. broken_wings 和 trapped 效果——行为不变
8. 已有 `fixStableHoverDrift` 修复——仍然生效，由 `FLIGHT_LEVEL >= 1` 驱动，不再依赖 `stableHover` 配置
