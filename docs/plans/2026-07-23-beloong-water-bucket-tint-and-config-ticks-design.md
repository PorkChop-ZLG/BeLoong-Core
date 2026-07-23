# Beloong Water: 水桶贴图修复 + 配置单位统一

**Date:** 2026-07-23
**Status:** Approved
**Approach:** Register DynamicFluidContainerModel.Colors ItemColor + rename config to ticks

## Problem Statement

1. 化龙池水桶的物品贴图中液体呈灰色而非绿松石色 (#40E0D0)
2. 配置文件单位不统一 — 化龙池水冷却用"秒"，其他配置都用 ticks

## Root Cause

**Issue 1:** NeoForge `DynamicFluidContainerModel` 将流体纹理渲染在 tintindex=1，
但 `ItemColor` 不会自动注册。源码注释明确写道:
> "Fluid tinting requires registering a separate `ItemColor`. An implementation is provided in `Colors`."

项目缺少 `DynamicFluidContainerModel.Colors` 的 `RegisterColorHandlersEvent.Item` 注册。

**Issue 2:** `Config.BeloongWater.triggerCooldownSeconds` 单独使用秒，
`BeloongWaterContactHandler` 中通过 `* TICKS_PER_SECOND` 转换。

## Design

### Changes

| File | Change |
|------|--------|
| `BeLoongCoreClient.java` | 新增 `registerItemColors` 方法，注册 `DynamicFluidContainerModel.Colors` |
| `Config.java` | `triggerCooldownSeconds` → `triggerCooldownTicks`, 默认 10→200, 范围 0~3600→0~72000 |
| `BeloongWaterContactHandler.java` | 移除 `TICKS_PER_SECOND` 常量，移除 `* TICKS_PER_SECOND` 乘法 |

### Data Flow (Tint)

```
BucketItem → FluidUtil.getFluidContained(stack) → beloong:beloong_water
  → IClientFluidTypeExtensions.getTintColor() → 0xFF40E0D0
  → DynamicFluidContainerModel.Colors (ItemColor)
  → tintindex=1 → 绿松石色渲染
```

### Non-Goals

- 不修改 lang 文件（translation key 不变）
- 不修改配置文件名（`beloongWaterCooldown` 不变）
- 不添加自定义水桶贴图

## Verification

- `./gradlew build` 通过
- 配置默认值 200 ticks = 10 秒，行为不变
