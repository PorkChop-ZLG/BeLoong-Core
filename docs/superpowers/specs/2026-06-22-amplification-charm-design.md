# 放大护符 (Amplification Charm) 设计

## 概述

为 BeLoong Core 添加一个 Curios 饰品：**放大护符**。佩戴在 `charm` 槽位，使玩家的体型增大 75%。

## 核心机制

- **属性**：`minecraft:generic.scale`
- **运算**：`ADD_MULTIPLIED_TOTAL`
- **值**：`+0.75`（增大 75%）
- **Curios 槽位**：`charm`（Curios 内置槽位类型）
- **无配置文件**，值硬编码
- **无开关/切换**功能

## 文件清单

### Java 代码

| 文件 | 操作 | 说明 |
|------|------|------|
| `item/AmplificationCharmItem.java` | 新建 | Item 类，实现 `ICurioItem`，重写 `getAttributeModifiers` 返回 SCALE 属性 |
| `item/ModItems.java` | 修改 | 用 `DeferredRegister` 注册新物品 |
| `item/ModCreativeModeTabs.java` | 修改 | 添加到"化龙"创造模式标签页 |

### 数据/资源文件

| 文件 | 说明 |
|------|------|
| `data/beloong/curios/entities/player.json` | 给玩家添加 charm 槽位 |
| `data/curios/tags/item/charm.json` | 标记物品属于 charm 槽 |
| `assets/beloong/models/item/amplification_charm.json` | 物品模型 |
| `assets/beloong/textures/item/amplification_charm.png` | 物品贴图 |
| `assets/beloong/lang/zh_cn.json` | 添加本地化 key |
| `assets/beloong/lang/en_us.json` | 英文本地化（如有） |

## 物品属性

- `stacksTo(1)` — 不可堆叠
- `rarity(Rarity.RARE)` — 稀有
- `fireResistant()` — 防火

## Curios 集成方式

物品直接实现 `ICurioItem` 接口，重写两个方法：

1. `getAttributeModifiers(SlotContext, ResourceLocation, ItemStack)` — 提供 `generic.scale` 属性修饰符
2. `canEquipFromUse(SlotContext, ItemStack)` — 返回 `true`，允许手持右键直接装备

## 数据文件内容

### `data/beloong/curios/entities/player.json`

```json
{
  "entities": ["player"],
  "slots": ["charm"]
}
```

### `data/curios/tags/item/charm.json`

```json
{
  "replace": false,
  "values": ["beloong:amplification_charm"]
}
```

## 边界说明

- 不提供自定义渲染器，护符不在玩家模型上显示
- 不参与任何 loot table，仅通过创造模式获取（后续可按需添加）
- 与 Artifacts 模组的缩小护符独立运行，互不影响
- Curios 为可选依赖，运行时通过 `neoforge.mods.toml` 已有声明
