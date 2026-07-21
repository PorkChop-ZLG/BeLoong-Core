# 黎明曙光耐久度改造设计文档

**日期:** 2026-07-21
**状态:** Approved
**方案:** A — 纯耐久度方案

## 问题陈述

当前黎明曙光为一次性消耗品（堆叠 16），每次使用消耗 1 个。改为耐久度系统，每个物品拥有 10 点耐久，每次使用消耗 1 耐久，耗尽后物品损毁。同时冷却时间从 10s 延长至 3 分钟。

## 设计

### 改动范围

仅涉及 `DawnLightEffect.java`，纯参数级别调整。

| # | 位置 | 旧值 | 新值 |
|---|------|------|------|
| 1 | `Item.Properties` | `.stacksTo(16)` | `.stacksTo(1).durability(10)` |
| 2 | `COOLDOWN_TICKS` | `200` | `3600` |
| 3 | 物品消耗 | `stack.consume(1, player)` | `stack.hurtAndBreak(1, player, ...EquipmentSlot)` |
| 4 | import | — | 新增 `EquipmentSlot` |

### 双持兼容

`use()` 方法已通过形参 `InteractionHand hand` 接收使用的手。改动时将 `hand` 映射为对应 `EquipmentSlot`：

- `MAIN_HAND` → `EquipmentSlot.MAINHAND`
- `OFF_HAND` → `EquipmentSlot.OFFHAND`

### 耐久系统行为

- 物品自动变为不可堆叠（vanilla 规则）
- 自动获得耐久条 UI
- 耐久归零时物品自动损毁（破碎音效由原版处理）

## 决定

- **使用原版耐久系统而非手动计数**：与 Minecraft 物品体系一致，UI 自动生成，无需额外持久化
- **方案 A 而非方案 B**：当前需求明确，不包含损坏特效；后续可按需扩展

## 非目标

- 不添加物品损坏时的自定义粒子/音效
- 不改变扫描范围、Boss 伤害逻辑、白屏特效
- 不添加合成/掉落获取方式

## 下一步

进入规划阶段，生成详细实施计划。
