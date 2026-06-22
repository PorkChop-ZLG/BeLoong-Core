# 放大护符 (Amplification Charm) 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 BeLoong Core 添加一个 Curios `charm` 槽位饰品，佩戴后玩家体型增大 75%。

**Architecture:** 新建 `AmplificationCharmItem` 类直接实现 `ICurioItem` 接口，通过重写 `getAttributeModifiers()` 提供 `minecraft:generic.scale` 属性修饰符（+0.75, ADD_MULTIPLIED_TOTAL）。通过 Curios 数据包文件添加 `charm` 槽位给玩家，物品通过 `curios:charm` 物品标签匹配槽位。

**Tech Stack:** NeoForge 1.21.1, Curios API 9.2.0+, Java 21

---

### Task 1: 创建 AmplificationCharmItem 类

**Files:**
- Create: `src/main/java/com/zonlong/beloong/item/AmplificationCharmItem.java`

- [ ] **Step 1: 编写 AmplificationCharmItem 类**

```java
package com.zonlong.beloong.item;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

public class AmplificationCharmItem extends Item implements ICurioItem {

    private static final ResourceLocation MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath("beloong", "amplification_charm");

    public AmplificationCharmItem() {
        super(new Item.Properties()
                .stacksTo(1)
                .rarity(Rarity.RARE)
                .fireResistant());
    }

    @Override
    public Multimap<Holder<Attribute>, AttributeModifier> getAttributeModifiers(
            SlotContext slotContext, ResourceLocation id, ItemStack stack) {
        Multimap<Holder<Attribute>, AttributeModifier> mods = LinkedHashMultimap.create();
        mods.put(Attributes.SCALE,
                new AttributeModifier(MODIFIER_ID, 0.75,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        return mods;
    }

    @Override
    public boolean canEquipFromUse(SlotContext slotContext, ItemStack stack) {
        return true;
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/zonlong/beloong/item/AmplificationCharmItem.java
git commit -m "feat: add AmplificationCharmItem curio item class"
```

---

### Task 2: 在 ModItems 中注册放大护符

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/item/ModItems.java`

- [ ] **Step 1: 添加 AMPLIFICATION_CHARM 注册项**

在 `ModItems.java` 中，在 `ETERNAL_PORKCHOP` 注册之后添加：

```java
/** 放大护符（Curios charm 槽位饰品，增大玩家体型 75%） */
public static final DeferredItem<Item> AMPLIFICATION_CHARM =
        Items.register("amplification_charm", AmplificationCharmItem::new);
```

最终 `ModItems.java` 中字段区域的顺序为：

```java
public static final DeferredItem<Item> BELOONG_LOGO =
        Items.register("beloong_logo", () -> new Item(new Item.Properties()));

public static final DeferredItem<Item> ETERNAL_PORKCHOP =
        Items.register("eternal_porkchop",
                EternalPorkchopEffect::new
        );

public static final DeferredItem<Item> AMPLIFICATION_CHARM =
        Items.register("amplification_charm", AmplificationCharmItem::new);

public static final DeferredItem<BlockItem> DISASTER_PORTAL_FRAME =
        Items.register("disaster_portal_frame",
                () -> new BlockItem(ModBlocks.DISASTER_PORTAL_FRAME.get(), new Item.Properties()));

public static final DeferredItem<BlockItem> DISASTER_PORTAL_BLOCK =
        Items.register("disaster_portal_block",
                () -> new BlockItem(ModBlocks.DISASTER_PORTAL_BLOCK.get(), new Item.Properties()));
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/zonlong/beloong/item/ModItems.java
git commit -m "feat: register amplification_charm item in ModItems"
```

---

### Task 3: 添加到创造模式标签页

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/item/ModCreativeModeTabs.java`

- [ ] **Step 1: 在 displayItems 回调中添加 AMPLIFICATION_CHARM**

在 `.displayItems(...)` 的 lambda 中，添加到 `BELOONG_LOGO` 之后（或其他位置）：

```java
.displayItems((itemDisplayParameters, output) -> {
    output.accept(ModItems.BELOONG_LOGO);
    output.accept(ModItems.AMPLIFICATION_CHARM);
    output.accept(ModItems.ETERNAL_PORKCHOP);
    output.accept(ModItems.DISASTER_PORTAL_FRAME);
    output.accept(ModItems.DISASTER_PORTAL_BLOCK);
}).build());
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/zonlong/beloong/item/ModCreativeModeTabs.java
git commit -m "feat: add amplification_charm to creative mode tab"
```

---

### Task 4: 添加 Curios 槽位分配和物品标签数据文件

**Files:**
- Create: `src/main/resources/data/beloong/curios/entities/player.json`
- Create: `src/main/resources/data/curios/tags/item/charm.json`

- [ ] **Step 1: 创建玩家实体槽位分配文件**

`src/main/resources/data/beloong/curios/entities/player.json`:

```json
{
  "entities": ["player"],
  "slots": ["charm"]
}
```

- [ ] **Step 2: 创建 charm 物品标签文件**

`src/main/resources/data/curios/tags/item/charm.json`:

```json
{
  "replace": false,
  "values": ["beloong:amplification_charm"]
}
```

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/data/beloong/curios/entities/player.json
git add src/main/resources/data/curios/tags/item/charm.json
git commit -m "feat: add curios entity slots and charm item tag"
```

---

### Task 5: 添加物品模型和贴图

**Files:**
- Create: `src/main/resources/assets/beloong/models/item/amplification_charm.json`
- Create: `src/main/resources/assets/beloong/textures/item/amplification_charm.png`

- [ ] **Step 1: 创建物品模型 JSON**

`src/main/resources/assets/beloong/models/item/amplification_charm.json`:

```json
{
  "parent": "minecraft:item/generated",
  "textures": {
    "layer0": "beloong:item/amplification_charm"
  }
}
```

- [ ] **Step 2: 创建物品贴图**

需要制作一张 16×16 的 PNG 贴图文件：`src/main/resources/assets/beloong/textures/item/amplification_charm.png`。

此步骤需要实际图片文件，可由用户自行提供。模型和贴图路径已预留，贴图缺失时物品会显示为紫黑方块。

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/assets/beloong/models/item/amplification_charm.json
git add src/main/resources/assets/beloong/textures/item/amplification_charm.png
git commit -m "feat: add amplification_charm model and texture"
```

---

### Task 6: 添加本地化文本

**Files:**
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

- [ ] **Step 1: 添加中文本地化**

在 `zh_cn.json` 中，在 `"item.beloong.eternal_porkchop"` 行后插入：

```json
"item.beloong.amplification_charm": "放大护符",
```

在 `"tooltip.item.beloong.eternal_porkchop"` 行后插入：

```json
"tooltip.item.beloong.amplification_charm": "佩戴在护符槽位，使体型增大 75%",
```

- [ ] **Step 2: 添加英文本地化**

在 `en_us.json` 中，在 `"item.beloong.eternal_porkchop"` 行后插入：

```json
"item.beloong.amplification_charm": "Amplification Charm",
```

在 `"tooltip.item.beloong.eternal_porkchop"` 行后插入：

```json
"tooltip.item.beloong.amplification_charm": "Equip in Charm slot to increase body size by 75%",
```

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/assets/beloong/lang/zh_cn.json
git add src/main/resources/assets/beloong/lang/en_us.json
git commit -m "feat: add amplification_charm localization"
```

---

### Task 7: 构建验证

- [ ] **Step 1: 构建项目**

```bash
cd "e:/Minecraft/BeLoong-Core" && ./gradlew build
```

预期：BUILD SUCCESSFUL，无编译错误。

- [ ] **Step 2: 检查编译输出确认 item 注册成功**

```bash
./gradlew build 2>&1 | grep -i "amplification_charm\|BUILD"
```

预期看到 BUILD SUCCESSFUL。

---

### Task 8: 游戏内测试（手动）

- [ ] **Step 1: 启动客户端**

```bash
cd "e:/Minecraft/BeLoong-Core" && ./gradlew runClient
```

- [ ] **Step 2: 验证清单**

1. 创造模式打开"化龙"标签页，能看到放大护符物品
2. 取出放大护符，右键装备 — 应自动装备到 charm 槽位
3. 打开 Curios 界面（默认无快捷键，可通过 `/curios` 命令或物品栏中的 Curios 按钮），确认物品在 charm 槽中
4. 观察玩家体型明显变大（约 1.75 倍）
5. 取下护符后体型恢复正常

- [ ] **Step 3: 验证属性修饰符 tooltip**

鼠标悬停在放大护符上，按住 Shift 查看属性信息，应显示：
```
佩戴时：
  Scale +75%
```

---

### Task 9: 最终提交

- [ ] **Step 1: 确认所有变更已提交**

```bash
cd "e:/Minecraft/BeLoong-Core" && git status
```

预期：clean，无未提交文件。

- [ ] **Step 2: 查看最终提交日志**

```bash
git log --oneline -8
```
