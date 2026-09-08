# 移除通臂大师（Sculptor）跑酷试炼飞行反作弊 设计文档

**Date:** 2026-09-08
**Status:** Implemented（待审阅）
**Scope:** 仅在 BeLoong-Core 内通过 Mixin 修改，不修改 Mowzie's Mobs 源码

## Problem Statement

Mowzie's Mobs 的 Boss `mowziesmobs:sculptor`（通臂大师 / 雕刻家）有跑酷试炼机制。试炼中 `EntitySculptor.checkIfPlayerCheats()` 会执行多组反作弊检测，其中包括：

1. `Abilities.flying`：飞行检测
2. `isInWater() && !onGround()`：水中非站立检测
3. 空中持续向上加速检测
4. 横向距离检测
5. 低于石柱底部检测
6. 传送/瞬移位置跳变检测

需求是移除与飞行/空中移动相关的反作弊（第 1～3 项），但保留距离、高度、传送等基础防作弊（第 4～6 项）。

本地 1.20.1 分支通过注释 `/* Can Fly */` 代码块实现；现在需要在 1.21.1 NeoForge 环境下达到等价效果，并且不修改 Mowzie's Mobs 源码。

## Design

### Architecture

```
BeLoong-Core
├── Config.java
│   └── COMMON 配置：removeSculptorAntiCheat = true
├── src/main/java/com/zonlong/beloong/mixin/mowziesmobs/
│   └── EntitySculptorCanFlyMixin.java
├── src/main/resources/beloong.mixins.json
│   └── "mixins": 新增 "mowziesmobs.EntitySculptorCanFlyMixin"
└── src/main/resources/assets/beloong/lang/
    ├── en_us.json
    └── zh_cn.json
```

### Components

#### 1. 配置开关

在 `Config.java` 的 `COMMON_BUILDER` 中新增：

```java
public static final ModConfigSpec.BooleanValue REMOVE_SCULPTOR_ANTI_CHEAT = COMMON_BUILDER
        .comment("Remove Sculptor anti-cheat",
                "移除通臂大师跑酷试炼中的飞行、水中和持续上升反作弊；保留距离、低于石柱和传送检测")
        .define("removeSculptorAntiCheat", true);
```

- 配置类型：COMMON
- 默认值：`true`
- key：`removeSculptorAntiCheat`
- 配置显示名：`移除通臂大师的反作弊`

语言文件：

`en_us.json`：

```json
"beloong.configuration.removeSculptorAntiCheat": "Remove Sculptor Anti-Cheat",
"beloong.configuration.removeSculptorAntiCheat.tooltip": "Remove flying, swimming, and sustained upward acceleration anti-cheat during the Sculptor's parkour test. Keeps distance, below-pillar and teleport detection."
```

`zh_cn.json`：

```json
"beloong.configuration.removeSculptorAntiCheat": "移除通臂大师的反作弊",
"beloong.configuration.removeSculptorAntiCheat.tooltip": "移除通臂大师跑酷试炼中的飞行、水中和持续上升反作弊；保留距离、低于石柱和传送检测。"
```

#### 2. Mixin

新增 `com.zonlong.beloong.mixin.mowziesmobs.EntitySculptorCanFlyMixin`。

Mixin 目标：

- `com.bobmowzie.mowziesmobs.server.entity.sculptor.EntitySculptor`

注入点：

- `EntitySculptor#checkIfPlayerCheats()`
- `EntitySculptor#setTestingPlayer(Player)`

逻辑：

1. 如果 `removeSculptorAntiCheat = false`，直接放行，让 Mowzie 原版反作弊继续运行。
2. 如果配置开启：
   - 在 `checkIfPlayerCheats()` HEAD 取消原方法。
   - 使用裁剪版逻辑替代：
     - 保留横向距离检测
     - 保留低于石柱底部检测
     - 保留传送/瞬移检测
     - 移除飞行、水中、持续向上加速检测
3. 在 `setTestingPlayer()` 时清空 Mixin 自己的上一位置记录，避免跨试炼误判传送。

#### 3. Mixin 注册

`beloong.mixins.json` 的 `"mixins"` 数组新增：

```json
"mowziesmobs.EntitySculptorCanFlyMixin"
```

### Data Flow

```
Sculptor 试炼中
  → RunTestGoal.tick()
  → EntitySculptor.checkIfPlayerCheats()
  → Mixin HEAD
      ├─ removeSculptorAntiCheat = false → 放行原方法（原版反作弊）
      └─ removeSculptorAntiCheat = true
            ├─ 横向距离检测
            ├─ 低于石柱检测
            ├─ 传送检测
            └─ 取消原方法（飞行/水中/向上加速不再生效）
```

### Edge Cases / Error Handling

- 配置关闭：完全保持 Mowzie 原版行为。
- 新试炼开始：通过 `setTestingPlayer` 注入清空位置追踪，避免旧位置残留。
- 玩家创造模式：原版逻辑本身跳过检测，本实现也保留该跳过行为。
- 玩家传送：仍会被保留的传送检测判定失败。
- 玩家离开试炼范围：仍会被横向距离检测判定失败。
- 玩家掉到石柱下方：仍会被保留的高度检测判定失败。
- 玩家飞行/鞘翅/水中上升：不再触发失败。
- 多人环境：每个 Sculptor 实例独立持有 Mixin 的追踪状态。

## Decisions Made

1. 不在 Mowzie 源码中修改，采用 BeLoong-Core Mixin。
2. 删除范围与 1.20.1 `/* Can Fly */` 注释块一致：
   - 删除飞行
   - 删除水中非站立
   - 删除持续向上加速
3. 保留基础反作弊：
   - 横向距离
   - 低于石柱
   - 传送/瞬移
4. 增加 COMMON 配置开关 `removeSculptorAntiCheat`，默认 `true`。
5. 不新增配置节，放在现有 COMMON 顶层配置区。
6. 不修改 Mowzie 的 `playerCheated()` 或 `FAIL_TEST` 触发机制。

## Non-Goals

- 不删除所有反作弊，只删除与 1.20.1 `Can Fly` 注释块对应的检测。
- 不修改 Mowzie's Mobs 源码。
- 不修改试炼的奖励、失败、超时机制。
- 不修改 Mowzie 原版对搭方块/拆方块/倒水的反作弊（这些仍通过 `ServerEventHandler` 生效）。

## Next Steps

1. 审阅本设计文档。
2. 已在 BeLoong-Core 中实现并编译通过。
3. 游戏内验证：
   - 开启配置：飞行/鞘翅/水中上升不会导致试炼失败。
   - 关闭配置：恢复 Mowzie 原版飞行反作弊。
   - 传送、离开范围、掉到石柱下方仍应失败。
   - 搭方块/拆方块/倒水仍应失败。
