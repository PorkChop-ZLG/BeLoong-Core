# 黎明曙光（Dawn Light）设计文档

## 概述

新增消耗品"黎明曙光"，右键使用后检测周围 16 格内的逆卡巴拉 Boss（Chesed / Malkuth / Geburah），对每个 Boss 的专用计数器造成 1 点伤害，并播放白屏特效。

## 物品属性

| 属性 | 值 |
|---|---|
| ID | `beloong:dawn_light` |
| 名称（中文） | 黎明曙光 |
| 名称（英文） | Dawn Light |
| 堆叠 | 16 |
| 稀有度 | `Rarity.EPIC` |
| 附魔光效 | 无 |
| 冷却 | 60 秒（1200 ticks） |
| 创造模式标签 | `beloong_tab` |

## 核心逻辑

```
DawnLightItem.use(Level, Player, hand):
  1. 服务端执行
  2. 16 格半径搜索 ChesedEntity / MalkuthEntity / GeburahEntity
  3. 列表为空:
     a. actionbar 发送 Component.translatable("item.beloong.dawn_light.no_boss")
     b. 返回 InteractionResultHolder.fail（不消耗）
  4. 列表非空:
     a. 消耗 1 个物品
     b. 设置 60s 冷却
     c. 遍历每个 Boss:
        - ChesedEntity → decreaseHitCount(1)
        - MalkuthEntity → hurtBoss(1)
        - GeburahEntity → setSinnedTimes(getSinnedTimes() + 1)
     d. 向玩家发送 fdlib 白屏特效:
        - FDLibCalls.sendScreenEffect(player, FDScreenEffects.SCREEN_COLOR,
          new ScreenColorData(1,1,1,1), 10, 0, 30)
     e. 播放 SoundEvents.TOTEM_USE
     f. 返回 InteractionResultHolder.success
```

## 涉及文件

| 文件 | 操作 | 说明 |
|---|---|---|
| `item/DawnLightItem.java` | 新建 | 物品逻辑类 |
| `item/ModItems.java` | 修改 | 注册 DAWN_LIGHT |
| `build.gradle` | 修改 | 添加 fdlib compileOnly |
| `lang/zh_cn.json` | 修改 | 2 个翻译 key |
| `lang/en_us.json` | 修改 | 2 个翻译 key |
| `models/item/dawn_light.json` | 新建 | 物品模型 |

## 翻译键

| Key | 中文 | 英文 |
|---|---|---|
| `item.beloong.dawn_light` | 黎明曙光 | Dawn Light |
| `item.beloong.dawn_light.tooltip` | 代表希望的曙光，使用后可直接对逆卡巴拉Boss造成伤害。 | Dawn of hope. Use to directly damage FDBosses bosses. |
| `item.beloong.dawn_light.no_boss` | 未检测到逆卡巴拉Boss | No FDBosses boss detected nearby |

## 依赖

- **fdlib**：需在 `build.gradle` 添加 `compileOnly`，用于 `FDLibCalls.sendScreenEffect()` 和 `ScreenColorData`
- **fdbosses**：已作为必选前置依赖，直接引用 ChesedEntity / MalkuthEntity / GeburahEntity

## 模型

使用 `minecraft:item/generated` 模板，手持贴图 `beloong:item/dawn_light`。贴图文件由用户自行提供或后续补充占位贴图。
