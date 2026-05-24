# BeLoong Core

[![Build](https://github.com/PorkChop-ZLG/BeLoongCore/actions/workflows/build.yml/badge.svg)](https://github.com/PorkChop-ZLG/BeLoongCore/actions/workflows/build.yml)

一个用于 BeLoong 整合包的 Minecraft NeoForge 附属模组。

## 项目信息

| 属性 | 值 |
|------|-----|
| **模组ID** | `beloong` |
| **版本** | 0.0.2 |
| **Minecraft** | 1.21.1 |
| **NeoForge** | 21.1.219 |
| **Java** | 21 |
| **许可证** | MIT |

## 功能特性

### 永恒猪排 (Eternal Porkchop)

一种可以重复食用的特殊食物：

- **饥饿值恢复**: 8点（与熟猪排相同）
- **饱和度**: 12.8（系数0.8）
- **耐久度**: 256次使用
- **食用时间**: 1.6秒（32游戏刻）
- **冷却时间**: 30秒（600游戏刻）

## 构建

```bash
./gradlew build
```

编译后的JAR文件位于 `build/libs/` 目录。

## 开发环境配置

1. 克隆仓库
2. 使用 IntelliJ IDEA 或 Eclipse 打开项目
3. 运行 `./gradlew genIntellijRuns`（IntelliJ）或 `./gradlew genEclipseRuns`（Eclipse）
4. 通过生成的运行配置启动游戏

## 项目结构

```
src/main/
├── java/com/zonlong/beloong/
│   ├── BeLoongCore.java          # 主模组类
│   ├── BeLoongCoreClient.java    # 客户端初始化
│   ├── Config.java               # 配置文件
│   └── item/
│       ├── ModItems.java         # 物品注册
│       ├── ModCreativeModeTabs.java  # 创造模式标签页
│       └── effect/
│           └── EternalPorkchopEffect.java  # 永恒猪排实现
├── resources/
│   ├── assets/beloong/
│   │   ├── lang/en_us.json       # 语言文件
│   │   ├── models/item/          # 物品模型
│   │   └── textures/item/        # 物品贴图
│   ├── beloong.mixins.json       # Mixin配置
│   └── logo.png                  # 模组图标
└── templates/META-INF/
    └── neoforge.mods.toml        # 模组元数据
```

## 配置

模组会在 `config/beloong-common.toml` 生成配置文件。

## 致谢

- **作者**: PorkChop-ZLG
- **许可证**: MIT

## 链接

- [NeoForge 文档](https://docs.neoforged.net/)
- [NeoForge Discord](https://discord.neoforged.net/)

---

## 代码附录：物品实现详解

### 永恒猪排 (Eternal Porkchop)

#### 物品注册

**文件**: [ModItems.java](src/main/java/com/zonlong/beloong/item/ModItems.java)

```java
public class ModItems {
    public static final DeferredRegister.Items Items =
            DeferredRegister.createItems(BeLoongCore.MODID);

    public static final DeferredItem<Item> ETERNAL_PORKCHOP =
            Items.register("eternal_porkchop",
                    EternalPorkchopEffect::new
            );

    public static void register(IEventBus eventBus) {
        Items.register(eventBus);
    }
}
```

物品通过 NeoForge 的 `DeferredRegister` 系统注册，注册名为 `eternal_porkchop`。

#### 核心实现

**文件**: [EternalPorkchopEffect.java](src/main/java/com/zonlong/beloong/item/effect/EternalPorkchopEffect.java)

##### 属性定义

```java
private static final FoodProperties COOKED_PORKCHOP_FOOD = new FoodProperties.Builder()
        .nutrition(8)              // 恢复8点饥饿值
        .saturationModifier(0.8f)  // 饱和度系数 (实际饱和度 = 0.8 × 2 × 8 = 12.8)
        .build();

private static final int EAT_DURATION_TICKS = 32;  // 食用时长：1.6秒
private static final int COOLDOWN_TICKS = 600;     // 冷却时间：30秒
private static final int DURABILITY_DAMAGE = 1;    // 每次使用消耗1点耐久
```

##### 构造函数

```java
public EternalPorkchopEffect() {
    super(new Properties()
            .food(COOKED_PORKCHOP_FOOD)  // 设置食物属性
            .durability(256)              // 可食用256次
    );
}
```

##### 食用行为重写

```java
@Override
public UseAnim getUseAnimation(ItemStack stack) {
    return UseAnim.EAT;  // 使用进食动画
}

@Override
public int getUseDuration(ItemStack stack, LivingEntity entity) {
    return EAT_DURATION_TICKS;  // 32游戏刻 = 1.6秒
}

@Override
public SoundEvent getEatingSound() {
    return SoundEvents.GENERIC_EAT;  // 通用进食音效
}
```

##### 核心逻辑：finishUsingItem

```java
@Override
public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
    // 1. 恢复饥饿值和饱和度
    if (entity instanceof Player player) {
        player.getFoodData().eat(
                COOKED_PORKCHOP_FOOD.nutrition(),
                COOKED_PORKCHOP_FOOD.saturation()
        );
    }

    // 2. 设置30秒冷却时间
    if (entity instanceof Player player) {
        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
    }

    // 3. 消耗耐久度
    InteractionHand hand = entity.getUsedItemHand();
    EquipmentSlot slot = hand == InteractionHand.MAIN_HAND ?
            EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
    stack.hurtAndBreak(DURABILITY_DAMAGE, entity, slot);

    // 4. 防止物品被消耗（耐久度机制已处理）
    if (!stack.isEmpty()) {
        stack.setCount(stack.getCount() + 1);
    }

    return stack;
}
```

**逻辑说明**：
1. 调用 `player.getFoodData().eat()` 直接恢复饥饿值
2. 使用 `player.getCooldowns().addCooldown()` 添加冷却
3. `hurtAndBreak()` 消耗耐久，耐久耗尽时物品自动销毁
4. 通过 `setCount(+1)` 抵消父类默认的物品数量减少

#### 创造模式标签页

**文件**: [ModCreativeModeTabs.java](src/main/java/com/zonlong/beloong/item/ModCreativeModeTabs.java)

```java
public class ModCreativeModeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BeLoongCore.MODID);

    public static final Supplier<CreativeModeTab> BELOONG_TAB =
            CREATIVE_MODE_TABS.register("beloong_tab", () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModItems.ETERNAL_PORKCHOP.get()))
                    .title(Component.translatable("itemGroup.beloong_tab"))
                    .displayItems((itemDisplayParameters, output) -> {
                        output.accept(ModItems.ETERNAL_PORKCHOP);
                    }).build());
}
```

#### 资源文件

**物品模型** ([eternal_porkchop.json](src/main/resources/assets/beloong/models/item/eternal_porkchop.json)):
```json
{
  "parent": "minecraft:item/generated",
  "textures": {
    "layer0": "beloong:item/eternal_porkchop"
  }
}
```

**语言文件** ([en_us.json](src/main/resources/assets/beloong/lang/en_us.json)):
```json
{
  "itemGroup.beloong_tab": "BeLoong",
  "item.beloong.eternal_porkchop": "Eternal Porkchop"
}
```

---

## 代码审计报告

### 严重问题

#### 1. 永恒猪排物品数量无限复制BUG

**文件**: [EternalPorkchopEffect.java:76-78](src/main/java/com/zonlong/beloong/item/effect/EternalPorkchopEffect.java#L76-L78)

**问题描述**: 食用永恒猪排后，物品数量会不断增加，导致物品越吃越多。

**问题代码**:
```java
// 第 76-78 行
if (!stack.isEmpty()) {
    stack.setCount(stack.getCount() + 1);
}
```

**根本原因**:

作者的假设是：父类 `finishUsingItem` 或游戏机制会自动减少物品数量，所以用 `+1` 来抵消。

但实际情况是：

| 操作 | 作者预期 | 实际行为 |
|------|----------|----------|
| 父类 `finishUsingItem` | 会减少数量 | **没有被调用** |
| `Player.eat()` | 会调用 `shrink(1)` | **没有被调用** |
| 耐久度物品的食物消耗 | 会减少数量 | **有耐久度的物品不会自动减少数量** |

**触发流程**:

```
玩家食用永恒猪排
        ↓
finishUsingItem() 被调用
        ↓
❌ 没有调用 super.finishUsingItem() → 物品数量不变（仍为 1）
        ↓
✅ 手动调用 player.getFoodData().eat() 恢复饥饿值
        ↓
✅ 设置 30 秒冷却时间
        ↓
✅ 调用 stack.hurtAndBreak() 消耗 1 点耐久度
        ↓
❌ stack.setCount(stack.getCount() + 1) → 数量从 1 变成 2！
        ↓
返回 stack（数量为 2）
```

**复现步骤**:

1. 在游戏中获取一个永恒猪排
2. 食用该物品
3. 观察物品栏，物品数量从 1 变成 2
4. 再次食用，数量从 2 变成 3
5. 重复食用，物品数量持续增加

**修复方案**:

删除第 76-78 行的代码：

```java
// 删除以下代码
if (!stack.isEmpty()) {
    stack.setCount(stack.getCount() + 1);
}
```

**修复后预期行为**:

- 物品数量保持为 1
- 每次食用消耗 1 点耐久度
- 耐久度耗尽时物品销毁

---

#### 2. Mixin配置指向不存在的包

**文件**: [beloong.mixins.json](src/main/resources/beloong.mixins.json)

Mixin配置声明了 `package: "com.zonlong.beloong.mixin"`，但项目中不存在此包，会导致运行时错误。

**修复方案**：
- 如果不需要Mixin，从 `neoforge.mods.toml` 中删除 `[[mixins]]` 部分
- 如果需要Mixin，创建对应的包和类

### 中等问题

#### 3. 模板示例代码未清理

**文件**: 
- [Config.java](src/main/java/com/zonlong/beloong/Config.java)
- [BeLoongCore.java](src/main/java/com/zonlong/beloong/BeLoongCore.java)

配置文件包含示例值（`LOG_DIRT_BLOCK`、`MAGIC_NUMBER`、`ITEM_STRINGS`），应替换为实际的模组配置或移除。

#### 4. 类命名问题

**文件**: [EternalPorkchopEffect.java](src/main/java/com/zonlong/beloong/item/effect/EternalPorkchopEffect.java)

类名 `EternalPorkchopEffect` 继承自 `Item`，但名称暗示是效果类。建议重命名为 `EternalPorkchopItem`。

#### 5. 变量命名规范

**文件**: [ModItems.java:11](src/main/java/com/zonlong/beloong/item/ModItems.java#L11)

```java
public static final DeferredRegister.Items Items = ...
```

应遵循Java常量命名规范：
```java
public static final DeferredRegister.Items ITEMS = ...
```

### 轻微问题

#### 6. 缺少中文语言文件

建议添加 `zh_cn.json` 中文语言文件。

#### 6. 缺少Issue追踪URL

**文件**: [neoforge.mods.toml](src/main/templates/META-INF/neoforge.mods.toml#L18)

`issueTrackerURL` 被注释掉了，建议添加GitHub Issues链接。

#### 8. 缺少单元测试

项目缺少 `src/test/` 目录下的测试代码。

### 代码质量建议

#### 9. EternalPorkchopEffect逻辑清晰度（已确认为BUG）

此问题已在问题 #1 中详细描述，是一个会导致物品无限复制的严重BUG。

---

*最后更新: 2026-05-24*
