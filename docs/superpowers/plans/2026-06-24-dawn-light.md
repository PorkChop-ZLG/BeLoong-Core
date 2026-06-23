# 黎明曙光（Dawn Light）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增消耗品"黎明曙光"，右键检测周围 16 格内的逆卡巴拉 Boss，对其专用计数器造成 1 点伤害，附带 fdlib 白屏特效。

**Architecture:** 新建 `DawnLightItem` 类继承 `Item`，在 `use()` 中搜索三类 Boss 实体并调用其公开计数器方法。屏幕特效通过 fdlib 的 `FDLibCalls.sendScreenEffect()` 发送。

**Tech Stack:** NeoForge 1.21.1, fdlib (screen effects), fdbosses (boss entities)

---

### Task 1: 添加 fdlib compileOnly 依赖

**Files:**
- Modify: `build.gradle:175`

- [ ] **Step 1: 将 fdlib 从 pure localRuntime 改为 compileOnly + localRuntime**

```gradle
// 修改前 (line 175):
    localRuntime "curse.maven:fdlib-1271749:7844741"

// 修改后:
    compileOnly "curse.maven:fdlib-1271749:7844741"
    localRuntime "curse.maven:fdlib-1271749:7844741"
```

- [ ] **Step 2: 构建验证**

```bash
cd e:/Minecraft/BeLoong-Core && ./gradlew build
```

预期: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add build.gradle
git commit -m "build: add fdlib as compileOnly dependency for Dawn Light screen effects"
```

---

### Task 2: 创建 DawnLightItem 物品类

**Files:**
- Create: `src/main/java/com/zonlong/beloong/item/DawnLightItem.java`

- [ ] **Step 1: 创建物品类**

```java
package com.zonlong.beloong.item;

import com.finderfeed.fdbosses.content.entities.chesed_boss.ChesedEntity;
import com.finderfeed.fdbosses.content.entities.geburah.GeburahEntity;
import com.finderfeed.fdbosses.content.entities.malkuth_boss.MalkuthEntity;
import com.finderfeed.fdlib.FDLibCalls;
import com.finderfeed.fdlib.init.FDScreenEffects;
import com.finderfeed.fdlib.systems.screen.screen_effect.instances.datas.ScreenColorData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

public class DawnLightItem extends Item {

    private static final int SCAN_RADIUS = 16;
    private static final int COOLDOWN_TICKS = 1200;
    private static final int SCREEN_IN_TIME = 10;
    private static final int SCREEN_STAY_TIME = 0;
    private static final int SCREEN_OUT_TIME = 30;

    public DawnLightItem() {
        super(new Item.Properties()
                .stacksTo(16)
                .rarity(Rarity.EPIC));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }

        List<Object> bosses = new ArrayList<>();
        bosses.addAll(level.getEntitiesOfClass(
                ChesedEntity.class,
                new AABB(player.blockPosition()).inflate(SCAN_RADIUS)));
        bosses.addAll(level.getEntitiesOfClass(
                MalkuthEntity.class,
                new AABB(player.blockPosition()).inflate(SCAN_RADIUS)));
        bosses.addAll(level.getEntitiesOfClass(
                GeburahEntity.class,
                new AABB(player.blockPosition()).inflate(SCAN_RADIUS)));

        if (bosses.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("item.beloong.dawn_light.no_boss"),
                    true);
            return InteractionResultHolder.fail(stack);
        }

        stack.consume(1, player);
        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);

        for (Object boss : bosses) {
            if (boss instanceof ChesedEntity chesed) {
                chesed.decreaseHitCount(1);
            } else if (boss instanceof MalkuthEntity malkuth) {
                malkuth.hurtBoss(1);
            } else if (boss instanceof GeburahEntity geburah) {
                geburah.setSinnedTimes(geburah.getSinnedTimes() + 1);
            }
        }

        if (player instanceof ServerPlayer serverPlayer) {
            FDLibCalls.sendScreenEffect(
                    serverPlayer,
                    FDScreenEffects.SCREEN_COLOR,
                    new ScreenColorData(1f, 1f, 1f, 1f),
                    SCREEN_IN_TIME,
                    SCREEN_STAY_TIME,
                    SCREEN_OUT_TIME
            );
        }

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1f, 1f);

        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(
                Component.translatable("item.beloong.dawn_light.tooltip")
                        .withStyle(ChatFormatting.GRAY));
    }
}
```

- [ ] **Step 2: 构建验证**

```bash
cd e:/Minecraft/BeLoong-Core && ./gradlew build
```

预期: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/zonlong/beloong/item/DawnLightItem.java
git commit -m "feat: add DawnLightItem - scan and damage FDBosses bosses"
```

---

### Task 3: 注册 DawnLight 物品

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/item/ModItems.java:46`
- Modify: `src/main/java/com/zonlong/beloong/item/ModCreativeModeTabs.java:38`

- [ ] **Step 1: 在 ModItems 中注册 DAWN_LIGHT**

在 `AMPLIFICATION_CHARM` 注册之后添加:

```java
    /** 黎明曙光 */
    public static final DeferredItem<Item> DAWN_LIGHT =
            Items.register("dawn_light", DawnLightItem::new);
```

- [ ] **Step 2: 在 ModCreativeModeTabs 中添加物品**

在 `output.accept(ModItems.AMPLIFICATION_CHARM);` 之后添加:

```java
                        output.accept(ModItems.DAWN_LIGHT);
```

- [ ] **Step 3: 构建验证**

```bash
cd e:/Minecraft/BeLoong-Core && ./gradlew build
```

预期: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/zonlong/beloong/item/ModItems.java src/main/java/com/zonlong/beloong/item/ModCreativeModeTabs.java
git commit -m "feat: register Dawn Light item and add to creative tab"
```

---

### Task 4: 添加翻译键

**Files:**
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

- [ ] **Step 1: 在 zh_cn.json 中添加中文翻译**

在 `"item.beloong.amplification_charm.tooltip"` 条目之后添加:

```json
  "item.beloong.dawn_light": "黎明曙光",
  "item.beloong.dawn_light.tooltip": "代表希望的曙光，使用后可直接对逆卡巴拉Boss造成伤害。",
  "item.beloong.dawn_light.no_boss": "未检测到逆卡巴拉Boss",
```

- [ ] **Step 2: 在 en_us.json 中添加英文翻译**

在 `"item.beloong.amplification_charm.tooltip"` 条目之后添加:

```json
  "item.beloong.dawn_light": "Dawn Light",
  "item.beloong.dawn_light.tooltip": "Dawn of hope. Use to directly damage FDBosses bosses.",
  "item.beloong.dawn_light.no_boss": "No FDBosses boss detected nearby",
```

- [ ] **Step 3: 构建验证**

```bash
cd e:/Minecraft/BeLoong-Core && ./gradlew build
```

预期: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/assets/beloong/lang/zh_cn.json src/main/resources/assets/beloong/lang/en_us.json
git commit -m "feat: add Dawn Light localization keys"
```

---

### Task 5: 创建物品模型

**Files:**
- Create: `src/main/resources/assets/beloong/models/item/dawn_light.json`

- [ ] **Step 1: 创建物品模型 JSON**

```json
{
  "parent": "minecraft:item/generated",
  "textures": {
    "layer0": "beloong:item/dawn_light"
  }
}
```

- [ ] **Step 2: 构建验证**

```bash
cd e:/Minecraft/BeLoong-Core && ./gradlew build
```

预期: BUILD SUCCESSFUL（模型不存在贴图时会使用丢失贴图占位）

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/assets/beloong/models/item/dawn_light.json
git commit -m "feat: add Dawn Light item model"
```

---

### Task 6: 集成测试

- [ ] **Step 1: 启动游戏并验证**

启动 Minecraft 客户端，进入世界后:
1. `/give @s beloong:dawn_light` — 确认物品出现在手中，贴图为紫黑方块（无贴图时正常）
2. 在没有 Boss 的情况下右键 — 确认 actionbar 显示"未检测到逆卡巴拉Boss"，物品不消耗
3. 使用 `/data modify entity @s ForgeData.fdbosses_last_damage_received set value -1.0f` 并穿戴 Justice Core 确认可复现之前的崩溃 bug

注: Boss 存在于世界中时才可测试伤害逻辑，可用 fdbosses 的 spawn egg 生成 Boss 后测试。

- [ ] **Step 2: 提交（如有修改）**

```bash
git add -A
git commit -m "chore: integration test notes for Dawn Light"
```
