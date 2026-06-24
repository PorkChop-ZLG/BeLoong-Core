# 财宝物品 Tooltip 技术设计

## 1. 概述

为拥有财宝价值的方块对应的物品添加 tooltip，显示"财宝价值"和"数量限制"，数据直接从数据驱动 JSON 文件中读取。

## 2. 架构

```
RegisterClientReloadListenersEvent
    └→ TreasureGrowthLoader.INSTANCE 注册到客户端
        └→ 独立解析 data/beloong/beloong/treasure_growth/*.json

ItemTooltipEvent（客户端）
    └→ TreasureTooltipHandler
        ├→ item 是 BlockItem → 取 Block
        ├→ TreasureGrowthLoader.getDragonEntry(block)
        │   或 getOtherEntry(block)
        ├→ 命中 → 追加 tooltip 行
        └→ 未命中 → 不追加
```

## 3. 文件清单

| 文件 | 动作 |
|------|------|
| `BeLoongCoreClient.java` | **修改** — 注册客户端重载监听器 |
| `treasure/TreasureTooltipHandler.java` | **新增** — ItemTooltipEvent 处理器 |
| `resources/assets/beloong/lang/zh_cn.json` | **修改** — 中文翻译 |
| `resources/assets/beloong/lang/en_us.json` | **修改** — 英文翻译 |

## 4. 核心逻辑

```
ItemTooltipEvent:
  ItemStack itemStack = event.getItemStack()
  Item item = itemStack.getItem()
  if item instanceof BlockItem blockItem:
      Block block = blockItem.getBlock()

      // 优先查龙之财宝，再查其他财宝
      TreasureGrowthEntry entry = getDragonEntry(block)
      if entry == null:
          entry = getOtherEntry(block)

      if entry != null:
          tooltip.add(翻译("财宝价值：{value}", entry.value))
          if entry.limit != Integer.MAX_VALUE:
              tooltip.add(翻译("数量限制：{limit}", entry.limit))
```

## 5. 翻译

| Key | 中文 | 英文 |
|-----|------|------|
| `beloong.treasure_tooltip.value` | `财宝价值：%s` | `Treasure Value: %s` |
| `beloong.treasure_tooltip.limit` | `数量限制：%d` | `Limit: %d` |

## 6. 边界情况

| 场景 | 处理 |
|------|------|
| 物品非 BlockItem | 跳过 |
| 方块未在 JSON 中配置 | 不追加 tooltip |
| limit = Integer.MAX_VALUE（默认） | 不显示"数量限制"行 |
| 数据包重载 | 客户端自动重新解析，tooltip 随之更新 |
| 方块同时在 dragon_treasure 和 other_treasure 中 | 优先 dragon_treasure（先查 dragon 再查 other） |
