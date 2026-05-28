# Dragon Growth Speed Attribute Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 添加 `growth_speed` 属性作为龙自然被动成长速度的倍率，通过 Mixin 注入到 DragonSurvival 的 `DragonGrowthHandler.onPlayerUpdate()` 中。

**Architecture:** 在 BeLoong-Core 中注册一个 `RangedAttribute`（范围 -1024.0 ~ 1024.0，默认 1.0），附加到所有玩家。通过 `@Redirect` Mixin 拦截 `onPlayerUpdate` 中对 `DragonStage.ticksToGrowth(int)` 的调用，将返回值乘以 player 的 `growth_speed` 属性值。不影响 `getGrowth()` 中的物品成长逻辑。

**Tech Stack:** Minecraft 1.21.1, NeoForge 21.1.219, Mixin (NeoForge 内置), Java 21

---

## File Structure

| 操作 | 文件 | 职责 |
|---|---|---|
| 新建 | `src/main/java/com/zonlong/beloong/registry/ModAttributes.java` | 注册 `growth_speed` 属性，附加到玩家 |
| 新建 | `src/main/java/com/zonlong/beloong/mixin/MixinDragonGrowthHandler.java` | `@Redirect` Mixin，倍乘自然成长速率 |
| 修改 | `src/main/resources/beloong.mixins.json` | 添加 MixinDragonGrowthHandler 到 mixins 列表 |
| 修改 | `src/main/java/com/zonlong/beloong/BeLoongCore.java` | 注册 ModAttributes 的 DeferredRegister 和 EventBus |
| 修改 | `src/main/resources/assets/beloong/lang/zh_cn.json` | 添加属性中文翻译 |
| 修改 | `src/main/resources/assets/beloong/lang/en_us.json` | 添加属性英文翻译 |

---

### Task 1: 创建 ModAttributes 属性注册类

**Files:**
- Create: `src/main/java/com/zonlong/beloong/registry/ModAttributes.java`

- [ ] **Step 1: 创建 registry 包目录**

```bash
mkdir -p src/main/java/com/zonlong/beloong/registry
```

- [ ] **Step 2: 编写 ModAttributes.java**

```java
package com.zonlong.beloong.registry;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

@EventBusSubscriber(modid = "beloong", bus = EventBusSubscriber.Bus.MOD)
public class ModAttributes {
    public static final DeferredRegister<Attribute> REGISTRY =
            DeferredRegister.create(Registries.ATTRIBUTE, "beloong");

    public static final Holder<Attribute> GROWTH_SPEED = REGISTRY.register("growth_speed",
            () -> new RangedAttribute(
                    "attribute.beloong.growth_speed",
                    1.0,
                    -1024.0,
                    1024.0
            ).setSyncable(true)
    );

    @SubscribeEvent
    public static void attachAttributes(EntityAttributeModificationEvent event) {
        event.add(EntityType.PLAYER, GROWTH_SPEED);
    }
}
```

- [ ] **Step 3: 验证编译**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/zonlong/beloong/registry/ModAttributes.java
git commit -m "feat: 注册 growth_speed 属性（范围 -1024.0 ~ 1024.0，默认 1.0）"
```

---

### Task 2: 创建 MixinDragonGrowthHandler

**Files:**
- Create: `src/main/java/com/zonlong/beloong/mixin/MixinDragonGrowthHandler.java`

- [ ] **Step 1: 编写 MixinDragonGrowthHandler.java**

```java
package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.common.handlers.DragonGrowthHandler;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.stage.DragonStage;
import com.zonlong.beloong.registry.ModAttributes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(DragonGrowthHandler.class)
public class MixinDragonGrowthHandler {

    @Redirect(
            method = "onPlayerUpdate",
            at = @At(
                    value = "INVOKE",
                    target = "Lby/dragonsurvivalteam/dragonsurvival/registry/dragon/stage/DragonStage;ticksToGrowth(I)D"
            )
    )
    private static double redirectTicksToGrowth(
            DragonStage stage,
            int ticks,
            PlayerTickEvent.Pre event
    ) {
        double baseGrowth = stage.ticksToGrowth(ticks);

        if (event.getEntity() instanceof ServerPlayer player) {
            AttributeInstance attr = player.getAttribute(ModAttributes.GROWTH_SPEED);
            if (attr != null) {
                return baseGrowth * attr.getValue();
            }
        }

        return baseGrowth;
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/zonlong/beloong/mixin/MixinDragonGrowthHandler.java
git commit -m "feat: 添加 MixinDragonGrowthHandler，通过 @Redirect 倍乘自然成长速率"
```

---

### Task 3: 注册 Mixin 配置

**Files:**
- Modify: `src/main/resources/beloong.mixins.json`

- [ ] **Step 1: 在 mixins 数组中添加 MixinDragonGrowthHandler**

编辑 `beloong.mixins.json`，在 `"mixins"` 数组中追加 `"MixinDragonGrowthHandler"`：

修改前：
```json
  "mixins": [
    "DragonDestructionHandlerMixin",
    "BlockBreakEffectMixin",
    "BlockConversionEffectMixin",
    "ExplodeBlockEffectMixin",
    "FireEffectMixin",
    "BlockHarvestEffectMixin",
    "BonemealEffectMixin"
  ],
```

修改后：
```json
  "mixins": [
    "DragonDestructionHandlerMixin",
    "BlockBreakEffectMixin",
    "BlockConversionEffectMixin",
    "ExplodeBlockEffectMixin",
    "FireEffectMixin",
    "BlockHarvestEffectMixin",
    "BonemealEffectMixin",
    "MixinDragonGrowthHandler"
  ],
```

- [ ] **Step 2: 提交**

```bash
git add src/main/resources/beloong.mixins.json
git commit -m "chore: 在 mixins 配置中注册 MixinDragonGrowthHandler"
```

---

### Task 4: 在 BeLoongCore 主类中注册属性系统

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`

- [ ] **Step 1: 添加 import 和注册代码**

在 `BeLoongCore` 构造函数中添加 `ModAttributes.REGISTRY.register(modEventBus)` 调用。

修改前：
```java
import com.zonlong.beloong.item.ModCreativeModeTabs;
import com.zonlong.beloong.item.ModItems;
import com.zonlong.beloong.transport.DimensionTransportHandler;
```

修改后：
```java
import com.zonlong.beloong.item.ModCreativeModeTabs;
import com.zonlong.beloong.item.ModItems;
import com.zonlong.beloong.registry.ModAttributes;
import com.zonlong.beloong.transport.DimensionTransportHandler;
```

修改前（构造函数内）：
```java
        ModItems.register(modEventBus);
        ModCreativeModeTabs.register(modEventBus);
```

修改后：
```java
        ModItems.register(modEventBus);
        ModCreativeModeTabs.register(modEventBus);
        ModAttributes.REGISTRY.register(modEventBus);
```

注意：不需要手动调用 `modEventBus.addListener(ModAttributes::attachAttributes)`，因为 `@EventBusSubscriber` 注解已自动注册 `attachAttributes` 方法。

- [ ] **Step 2: 验证编译**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/zonlong/beloong/BeLoongCore.java
git commit -m "feat: 在主类中注册 ModAttributes 属性系统"
```

---

### Task 5: 添加属性翻译键

**Files:**
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

- [ ] **Step 1: 在 zh_cn.json 中添加属性翻译**

在 JSON 对象的合适位置（与其他游戏内容翻译键相邻）添加：

```json
"attribute.beloong.growth_speed": "成长速度",
```

- [ ] **Step 2: 在 en_us.json 中添加属性翻译**

```json
"attribute.beloong.growth_speed": "Growth Speed",
```

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/assets/beloong/lang/zh_cn.json src/main/resources/assets/beloong/lang/en_us.json
git commit -m "feat: 添加 growth_speed 属性翻译（中英文）"
```

---

### Task 6: 全量构建验证

- [ ] **Step 1: 执行完整构建**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL，产物位于 `build/libs/beloong-0.0.2.jar`

- [ ] **Step 2: 功能验证（需启动 Minecraft 客户端）**

启动游戏后验证：
1. 输入 `/attribute @s beloong:growth_speed get` — 应返回 `1.0`
2. 输入 `/attribute @s beloong:growth_speed base set 2.0` — 应生效
3. 观察龙的自然成长速度是否翻倍
4. 使用龙心等成长道具 — 应不受 attribute 影响
5. 输入 `/attribute @s beloong:growth_speed base set 0.0` — 自然成长应停止
6. 输入 `/attribute @s beloong:growth_speed base set -1.0` — 成长应反向

- [ ] **Step 3: 提交（如有修复）**

如有修复，提交修正。否则无需额外提交。
