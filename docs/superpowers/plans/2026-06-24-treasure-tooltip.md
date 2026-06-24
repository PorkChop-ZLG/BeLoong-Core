# 财宝物品 Tooltip 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为财宝方块对应的物品添加 tooltip，显示财宝价值和数量限制，从客户端本地数据驱动 JSON 读取。

**Architecture:** 在客户端注册 `TreasureGrowthLoader` 为重载监听器使其独立解析 JSON。新增 `TreasureTooltipHandler` 订阅 `ItemTooltipEvent`，通过 `BlockItem` 反查方块，从 `TreasureGrowthLoader` 获取数据后追加 tooltip。

**Tech Stack:** NeoForge 1.21.1, Java 21, `RegisterClientReloadListenersEvent`, `ItemTooltipEvent`

---

### Task 1: 注册 TreasureGrowthLoader 到客户端

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCoreClient.java`

- [ ] **Step 1: 添加 `RegisterClientReloadListenersEvent` 监听器**

在 `BeLoongCoreClient` 构造函数 `modEventBus.addListener` 调用区块末尾追加一行，然后在类体中添加监听方法。

当前构造函数第 42-44 行：
```java
    public BeLoongCoreClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
```

无需改动构造函数。修改 `onClientSetup` 方法签名所在类，将类级别的 `@EventBusSubscriber` 注解监听扩展为同时监听客户端重载事件。更简单的方式：在构造函数中注册。

修改构造函数为：
```java
    public BeLoongCoreClient(IEventBus modEventBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modEventBus.addListener(this::onRegisterClientReloadListeners);
    }
```

同时在类体中添加方法：
```java
    private void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(TreasureGrowthLoader.INSTANCE);
    }
```

并在 import 区域添加：
```java
import com.zonlong.beloong.treasure.TreasureGrowthLoader;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
```

注意：原构造函数参数名解构为 `modEventBus`（原代码未命名 IEventBus 参数），需要添加参数。

- [ ] **Step 2: 验证编译**

```bash
cd e:/Minecraft/BeLoong-Core && ./gradlew compileJava 2>&1 | tail -20
```
预期：BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/zonlong/beloong/BeLoongCoreClient.java
git commit -m "feat: 注册 TreasureGrowthLoader 为客户端重载监听器"
```

---

### Task 2: 创建 TreasureTooltipHandler

**Files:**
- Create: `src/main/java/com/zonlong/beloong/treasure/TreasureTooltipHandler.java`

- [ ] **Step 1: 创建文件并编写完整实现**

```java
package com.zonlong.beloong.treasure;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

@EventBusSubscriber(value = Dist.CLIENT)
public class TreasureTooltipHandler {

    private static final String VALUE_KEY = "beloong.treasure_tooltip.value";
    private static final String LIMIT_KEY = "beloong.treasure_tooltip.limit";

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        Item item = stack.getItem();

        if (!(item instanceof BlockItem blockItem)) {
            return;
        }

        Block block = blockItem.getBlock();

        TreasureGrowthEntry entry = TreasureGrowthLoader.INSTANCE.getDragonEntry(block);
        if (entry == null) {
            entry = TreasureGrowthLoader.INSTANCE.getOtherEntry(block);
        }

        if (entry == null) {
            return;
        }

        event.getToolTip().add(Component.translatable(VALUE_KEY, String.format("%.1f", entry.value())));

        if (entry.limit() != Integer.MAX_VALUE) {
            event.getToolTip().add(Component.translatable(LIMIT_KEY, entry.limit()));
        }
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
cd e:/Minecraft/BeLoong-Core && ./gradlew compileJava 2>&1 | tail -20
```
预期：BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/zonlong/beloong/treasure/TreasureTooltipHandler.java
git commit -m "feat: 添加财宝物品 tooltip 事件处理器"
```

---

### Task 3: 添加翻译键

**Files:**
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

- [ ] **Step 1: 添加中文翻译**

在 `zh_cn.json` 的顶层对象末尾（`"title.beloong.treasure_value"` 之后，闭合 `}` 之前）追加：
```json
  "beloong.treasure_tooltip.value": "财宝价值：%s",
  "beloong.treasure_tooltip.limit": "数量限制：%d"
```

实际编辑：找到 `"title.beloong.treasure_value": "财宝价值: %s"` 行，在其后添加逗号并追加两行。

- [ ] **Step 2: 添加英文翻译**

在 `en_us.json` 的对应位置追加：
```json
  "beloong.treasure_tooltip.value": "Treasure Value: %s",
  "beloong.treasure_tooltip.limit": "Limit: %d"
```

- [ ] **Step 3: 验证 JSON 合法性**

```bash
cd e:/Minecraft/BeLoong-Core && python3 -c "import json; json.load(open('src/main/resources/assets/beloong/lang/zh_cn.json')); json.load(open('src/main/resources/assets/beloong/lang/en_us.json')); print('OK')"
```
预期：OK

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/assets/beloong/lang/zh_cn.json src/main/resources/assets/beloong/lang/en_us.json
git commit -m "feat: 添加财宝 tooltip 翻译键"
```

---

### Task 4: 完整编译验证

- [ ] **Step 1: 运行完整构建**

```bash
cd e:/Minecraft/BeLoong-Core && ./gradlew build 2>&1 | tail -30
```
预期：BUILD SUCCESSFUL

- [ ] **Step 2: 启动游戏验证 tooltip 显示**

启动 Minecraft 客户端，在创造模式物品栏中 hover 以下物品确认 tooltip：
- 金财宝 → 应显示 "财宝价值：2.0" + "数量限制：1000"
- 钻石块 → 应显示 "财宝价值：40.0" + "数量限制：10"
- 铁财宝 → 应显示 "财宝价值：1.0" + "数量限制：500"
- 普通石头 → 不应显示财宝 tooltip
