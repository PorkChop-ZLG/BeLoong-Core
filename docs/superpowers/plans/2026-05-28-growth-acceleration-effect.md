# Growth Acceleration 药水效果实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 添加「成长加速」MobEffect，通过 `/effect` 指令获取，每级增加玩家 1.0 的 `growth_speed` 属性值。

**Architecture:** 创建 `ModMobEffects` 注册类，使用 NeoForge `DeferredRegister<MobEffect>` 注册一个标准 `MobEffect` 实例，通过内置 `addAttributeModifier` 绑定 `GROWTH_SPEED` 属性。在主类中注册 Registry 总线，在语言文件中添加翻译。

**Tech Stack:** Minecraft 1.21.1, NeoForge 21.1.219, Java 21, Mixin

---

### Task 1: 创建 ModMobEffects 注册类

**Files:**
- Create: `src/main/java/com/zonlong/beloong/registry/ModMobEffects.java`

- [ ] **Step 1: 创建 ModMobEffects.java**

```java
package com.zonlong.beloong.registry;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModMobEffects {
    public static final DeferredRegister<MobEffect> REGISTRY =
            DeferredRegister.create(Registries.MOB_EFFECT, "beloong");

    public static final Holder<MobEffect> GROWTH_ACCELERATION = REGISTRY.register(
            "growth_acceleration",
            () -> new MobEffect(MobEffectCategory.BENEFICIAL, 0xFFD700)
                    .addAttributeModifier(
                            ModAttributes.GROWTH_SPEED,
                            ResourceLocation.fromNamespaceAndPath("beloong", "growth_acceleration"),
                            1.0,
                            AttributeModifier.Operation.ADD_VALUE
                    )
    );
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/zonlong/beloong/registry/ModMobEffects.java
git commit -m "feat: 添加 ModMobEffects 注册类，注册 growth_acceleration 效果"
```

---

### Task 2: 在主类中注册 ModMobEffects

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`

- [ ] **Step 1: 在 BeLoongCore 构造函数中添加注册**

在 `ModAttributes.REGISTRY.register(modEventBus);` 之后添加一行：

```java
ModMobEffects.REGISTRY.register(modEventBus);
```

完整上下文：

```java
ModItems.register(modEventBus);
ModCreativeModeTabs.register(modEventBus);
ModAttributes.REGISTRY.register(modEventBus);
ModMobEffects.REGISTRY.register(modEventBus);  // 新增
```

并在文件顶部添加 import：

```java
import com.zonlong.beloong.registry.ModMobEffects;
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/zonlong/beloong/BeLoongCore.java
git commit -m "feat: 在主类中注册 ModMobEffects"
```

---

### Task 3: 添加语言翻译

**Files:**
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

- [ ] **Step 1: 添加中文翻译**

在 `zh_cn.json` 中，在 `"attribute.beloong.growth_speed": "成长速度",` 之后添加：

```json
"effect.beloong.growth_acceleration": "成长加速",
```

- [ ] **Step 2: 添加英文翻译**

在 `en_us.json` 中，在 `"attribute.beloong.growth_speed": "Growth Speed",` 之后添加：

```json
"effect.beloong.growth_acceleration": "Growth Acceleration",
```

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/assets/beloong/lang/zh_cn.json src/main/resources/assets/beloong/lang/en_us.json
git commit -m "feat: 添加 growth_acceleration 效果翻译（中英文）"
```

---

### Task 4: 构建验证

- [ ] **Step 1: 完整构建**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 验证 JAR 内容**

Run: `jar tf build/libs/beloong-*.jar | grep -E "(ModMobEffects|growth_acceleration)"`
Expected: 看到 `ModMobEffects.class` 和语言文件
