# Growth Acceleration 药水效果设计

## 概述

添加名为「成长加速」的 MobEffect，通过 `/effect` 指令获取，每级增加玩家 1.0 的 `growth_speed` 属性值。

## 背景

`growth_speed` 属性（`ModAttributes.GROWTH_SPEED`）已注册：
- 范围：-1024.0 ~ 1024.0
- 默认值：1.0
- 作用：`MixinDragonGrowthHandler` 中作为成长速率的倍乘系数

## 方案

使用 NeoForge 标准 `MobEffect` + 内置属性修饰器，无需自定义子类。

## 新增

### ModMobEffects.java

`src/main/java/com/zonlong/beloong/registry/ModMobEffects.java`

- `DeferredRegister<MobEffect>` 注册表
- 单条目 `GROWTH_ACCELERATION`
- 通过 `addAttributeModifier(ModAttributes.GROWTH_SPEED, ..., 1.0, ADD_VALUE)` 绑定属性
- 效果类别：`BENEFICIAL`
- 颜色：`0xFFD700`（金色）

## 修改

### BeLoongCore.java

- 注册 `ModMobEffects.REGISTRY`

### zh_cn.json

- 添加 `"effect.beloong.growth_acceleration": "成长加速"`

### en_us.json

- 添加 `"effect.beloong.growth_acceleration": "Growth Acceleration"`

## 不需要

- 药水物品 / 酿造配方 / 创造模式物品栏
- 自定义 MobEffect 子类
