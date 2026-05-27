# 龙之宫殿维度（Loong Palace Dimension）

## 概述

通过纯数据包方式创建新维度 `beloong:loong_palace`——纯虚空世界，主世界环境氛围，供其他模组后续填充建筑。

## 进入方式

命令进入，无传送门/物品：

```
/execute in beloong:loong_palace run tp ~ ~ ~
```

## 文件结构

仅需 2 个 JSON 文件，无需任何 Java 代码：

```
src/main/resources/data/beloong/
├── dimension_type/
│   └── loong_palace.json
└── dimension/
    └── loong_palace.json
```

## 维度类型

`data/beloong/dimension_type/loong_palace.json`：

| 参数 | 值 | 说明 |
|------|-----|------|
| `ultrawarm` | false | 水不蒸发 |
| `has_skylight` | true | 有阳光 |
| `has_ceiling` | false | 无基岩天花板 |
| `bed_works` | true | 可睡觉 |
| `effects` | `minecraft:overworld` | 蓝天、太阳、云层渲染 |
| `ambient_light` | 0 | 无额外环境光 |
| `infiniburn` | `#minecraft:infiniburn_overworld` | 主世界可燃方块 |
| `logical_height` | 384 | 允许操作高度上限 |

## 维度定义

`data/beloong/dimension/loong_palace.json`：

| 字段 | 值 | 说明 |
|------|-----|------|
| `type` | `beloong:loong_palace` | 引用上述维度类型 |
| `generator` | `minecraft:flat` | 超平坦生成器 |
| `layers` | `[]` | 空数组 = 纯虚空 |
| `biome` | `minecraft:the_void` | 复用原版虚空生物群系 |
| `lakes` | false | 不生成流体湖 |
| `features` | false | 不触发生物群系 decoration |

## 依赖

- 无新增依赖
- 无需 FTB Chunks、Dragon Survival 或其他模组
- 纯数据包，Minecraft 1.21.1 / NeoForge 21.1 原生支持

## 设计决策

- **为什么用 `minecraft:flat` 而非 `minecraft:noise`**：纯虚空不需要噪声生成器，flat 生成器 + 空 layers 数组是标准做法
- **为什么复用 `minecraft:the_void` 生物群系**：不需要自定义生物群系来增加复杂度，原版虚空生物群系满足需求
- **为什么维度类型不写在 dimension 文件内联**：分离 dimension_type 允许日后其他维度（如地下区域）复用同一环境规则
