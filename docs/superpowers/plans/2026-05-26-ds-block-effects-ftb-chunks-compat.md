# DS Block Effect × FTB Chunks 领地保护兼容 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过 Mixin 在 6 个破坏性 AbilityBlockEffect 的 apply() 入口处添加 FTB Chunks 领地检查，阻止越权破坏。

**Architecture:** 提取共用 `ClaimProtectionHelper` 工具类，每个 effect 独立 Mixin 类，在 `apply()` HEAD 注入，统一委托 Helper 判断。Config 新增 `ds_ftbchunks_compat` 开关控制全部 6 个 Mixin。

**Tech Stack:** Java 21, Mixin (SpongePowered), NeoForge 21.1, FTB Chunks API, Dragon Survival

---

### Task 1: 创建 ClaimProtectionHelper 共用工具类

**Files:**
- Create: `src/main/java/com/zonlong/beloong/util/ClaimProtectionHelper.java`

- [ ] **Step 1: 创建 ClaimProtectionHelper.java**

```java
package com.zonlong.beloong.util;

import com.zonlong.beloong.Config;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.Protection;
import dev.ftb.mods.ftbchunks.api.ProtectionPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;

public class ClaimProtectionHelper {

    private static final Protection ALWAYS_BLOCK =
            (player, pos, hand, chunk, entity) -> ProtectionPolicy.CHECK;

    public static boolean isClaimed(Entity actor, BlockPos pos) {
        if (actor == null || pos == null) {
            return false;
        }

        if (!ModList.get().isLoaded("ftbchunks")) {
            return false;
        }

        if (!Config.DS_FTBCHUNKS_COMPAT.get()) {
            return false;
        }

        var manager = FTBChunksAPI.api().getManager();
        if (manager == null) {
            return false;
        }

        return manager.shouldPreventInteraction(actor, InteractionHand.MAIN_HAND, pos, ALWAYS_BLOCK, null);
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/zonlong/beloong/util/
git commit -m "feat: 添加 ClaimProtectionHelper 共用工具类"
```

---

### Task 2: 重构 DragonDestructionHandlerMixin 使用 Helper

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/mixin/DragonDestructionHandlerMixin.java`

- [ ] **Step 1: 替换 DragonDestructionHandlerMixin 中的 isClaimed 逻辑**

将原来的静态字段 `ALWAYS_BLOCK` 和静态方法 `isClaimed()` 删除，`isClaimed()` 调用处改为委托 `ClaimProtectionHelper.isClaimed()`。

**删除** `ALWAYS_BLOCK` 字段（当前 L36）：
```java
    private static final Protection ALWAYS_BLOCK = (player, pos, hand, chunk, entity) -> ProtectionPolicy.CHECK;
```

**删除** `isClaimed()` 方法（当前 L38-L57）。

**替换** 三处 `isClaimed(...)` 调用为 `ClaimProtectionHelper.isClaimed(...)`：

- L74: `if (isClaimed(player, pos))` → `if (ClaimProtectionHelper.isClaimed(player, pos))`
- L91: `if (isClaimed(event.getEntity(), pos))` → `if (ClaimProtectionHelper.isClaimed(event.getEntity(), pos))`
- L104: `if (isClaimed(event.getEntity(), pos))` → `if (ClaimProtectionHelper.isClaimed(event.getEntity(), pos))`

**删除不再需要的 import**：
```java
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.Protection;
import dev.ftb.mods.ftbchunks.api.ProtectionPolicy;
```

**添加新的 import**：
```java
import com.zonlong.beloong.util.ClaimProtectionHelper;
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/zonlong/beloong/mixin/DragonDestructionHandlerMixin.java
git commit -m "refactor: DragonDestructionHandlerMixin 改用 ClaimProtectionHelper"
```

---

### Task 3: 创建 6 个 Block Effect Mixin

**Files:**
- Create: `src/main/java/com/zonlong/beloong/mixin/BlockBreakEffectMixin.java`
- Create: `src/main/java/com/zonlong/beloong/mixin/BlockConversionEffectMixin.java`
- Create: `src/main/java/com/zonlong/beloong/mixin/ExplodeBlockEffectMixin.java`
- Create: `src/main/java/com/zonlong/beloong/mixin/FireEffectMixin.java`
- Create: `src/main/java/com/zonlong/beloong/mixin/BlockHarvestEffectMixin.java`
- Create: `src/main/java/com/zonlong/beloong/mixin/BonemealEffectMixin.java`

- [ ] **Step 1: 创建 BlockBreakEffectMixin.java**

```java
package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.block_effects.BlockBreakEffect;
import com.zonlong.beloong.util.ClaimProtectionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockBreakEffect.class)
public abstract class BlockBreakEffectMixin {

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true, remap = false)
    private void beloong$beforeApply(ServerPlayer dragon, DragonAbilityInstance ability,
            BlockPos position, Direction direction, CallbackInfo ci) {
        if (ClaimProtectionHelper.isClaimed(dragon, position)) {
            ci.cancel();
        }
    }
}
```

- [ ] **Step 2: 创建 BlockConversionEffectMixin.java**

```java
package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.block_effects.BlockConversionEffect;
import com.zonlong.beloong.util.ClaimProtectionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockConversionEffect.class)
public abstract class BlockConversionEffectMixin {

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true, remap = false)
    private void beloong$beforeApply(ServerPlayer dragon, DragonAbilityInstance ability,
            BlockPos position, Direction direction, CallbackInfo ci) {
        if (ClaimProtectionHelper.isClaimed(dragon, position)) {
            ci.cancel();
        }
    }
}
```

- [ ] **Step 3: 创建 ExplodeBlockEffectMixin.java**

```java
package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.block_effects.ExplodeBlockEffect;
import com.zonlong.beloong.util.ClaimProtectionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ExplodeBlockEffect.class)
public abstract class ExplodeBlockEffectMixin {

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true, remap = false)
    private void beloong$beforeApply(ServerPlayer dragon, DragonAbilityInstance ability,
            BlockPos position, Direction direction, CallbackInfo ci) {
        if (ClaimProtectionHelper.isClaimed(dragon, position)) {
            ci.cancel();
        }
    }
}
```

- [ ] **Step 4: 创建 FireEffectMixin.java**

```java
package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.block_effects.FireEffect;
import com.zonlong.beloong.util.ClaimProtectionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireEffect.class)
public abstract class FireEffectMixin {

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true, remap = false)
    private void beloong$beforeApply(ServerPlayer dragon, DragonAbilityInstance ability,
            BlockPos position, Direction direction, CallbackInfo ci) {
        if (ClaimProtectionHelper.isClaimed(dragon, position)) {
            ci.cancel();
        }
    }
}
```

- [ ] **Step 5: 创建 BlockHarvestEffectMixin.java**

```java
package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.block_effects.BlockHarvestEffect;
import com.zonlong.beloong.util.ClaimProtectionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockHarvestEffect.class)
public abstract class BlockHarvestEffectMixin {

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true, remap = false)
    private void beloong$beforeApply(ServerPlayer dragon, DragonAbilityInstance ability,
            BlockPos position, Direction direction, CallbackInfo ci) {
        if (ClaimProtectionHelper.isClaimed(dragon, position)) {
            ci.cancel();
        }
    }
}
```

- [ ] **Step 6: 创建 BonemealEffectMixin.java**

```java
package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.block_effects.BonemealEffect;
import com.zonlong.beloong.util.ClaimProtectionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BonemealEffect.class)
public abstract class BonemealEffectMixin {

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true, remap = false)
    private void beloong$beforeApply(ServerPlayer dragon, DragonAbilityInstance ability,
            BlockPos position, Direction direction, CallbackInfo ci) {
        if (ClaimProtectionHelper.isClaimed(dragon, position)) {
            ci.cancel();
        }
    }
}
```

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/zonlong/beloong/mixin/BlockBreakEffectMixin.java \
        src/main/java/com/zonlong/beloong/mixin/BlockConversionEffectMixin.java \
        src/main/java/com/zonlong/beloong/mixin/ExplodeBlockEffectMixin.java \
        src/main/java/com/zonlong/beloong/mixin/FireEffectMixin.java \
        src/main/java/com/zonlong/beloong/mixin/BlockHarvestEffectMixin.java \
        src/main/java/com/zonlong/beloong/mixin/BonemealEffectMixin.java
git commit -m "feat: 添加 6 个 Block Effect Mixin 拦截破坏性技能"
```

---

### Task 4: 更新 Config.java

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/Config.java`

- [ ] **Step 1: 在 Config.java 服务端配置区域添加新配置项**

在 `FIX_FTB_CHUNKS_COMPAT` 定义之后（L40 之后，`SERVER_SPEC` 之前）插入：

```java
    /** 龙之生存FTB区块兼容 — 阻止龙之生存的技能effect破坏已认领区块（默认启用） */
    public static final ModConfigSpec.BooleanValue DS_FTBCHUNKS_COMPAT = SERVER_BUILDER
            .comment("龙之生存FTB区块兼容 — 阻止龙之生存的技能effect破坏已认领区块")
            .translation("config.beloong.dsFTBChunksCompat")
            .define("ds_ftbchunks_compat", true);
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/zonlong/beloong/Config.java
git commit -m "feat: 添加 ds_ftbchunks_compat 配置项"
```

---

### Task 5: 更新 beloong.mixins.json 注册所有新 Mixin

**Files:**
- Modify: `src/main/resources/beloong.mixins.json`

- [ ] **Step 1: 在 server 列表中添加 6 个新 Mixin**

将 `server` 数组从：

```json
  "server": [
    "DragonDestructionHandlerMixin"
  ],
```

改为：

```json
  "server": [
    "DragonDestructionHandlerMixin",
    "BlockBreakEffectMixin",
    "BlockConversionEffectMixin",
    "ExplodeBlockEffectMixin",
    "FireEffectMixin",
    "BlockHarvestEffectMixin",
    "BonemealEffectMixin"
  ],
```

- [ ] **Step 2: 提交**

```bash
git add src/main/resources/beloong.mixins.json
git commit -m "feat: 在 mixin 配置中注册 6 个 Block Effect Mixin"
```

---

### Task 6: 更新语言文件

**Files:**
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

- [ ] **Step 1: 添加翻译条目**

在 `zh_cn.json` 中添加（在最后一个 config 行之后）：

```json
  "config.beloong.dsFTBChunksCompat": "龙之生存FTB区块兼容"
```

在 `en_us.json` 中添加（在最后一个 config 行之后）：

```json
  "config.beloong.dsFTBChunksCompat": "Dragon Survival FTB Chunks Compatibility"
```

注意 JSON 格式：在原有最后一个条目后加逗号，然后添加新条目（不加尾逗号）。

- [ ] **Step 2: 提交**

```bash
git add src/main/resources/assets/beloong/lang/zh_cn.json \
        src/main/resources/assets/beloong/lang/en_us.json
git commit -m "feat: 添加 ds_ftbchunks_compat 配置翻译"
```

---

### Task 7: 构建验证

- [ ] **Step 1: 运行 Gradle 构建**

```bash
cd e:/Minecraft/BeLoong-Core && ./gradlew build
```

预期：`BUILD SUCCESSFUL`，无编译错误。

- [ ] **Step 2: 检查构建产物**

```bash
ls -la build/libs/
```

预期：`beloong-0.0.2.jar` 存在。

---

### 文件变更总览

| 文件 | 操作 | 所属 Task |
|------|------|----------|
| `src/main/java/com/zonlong/beloong/util/ClaimProtectionHelper.java` | 新建 | 1 |
| `src/main/java/com/zonlong/beloong/mixin/DragonDestructionHandlerMixin.java` | 修改 | 2 |
| `src/main/java/com/zonlong/beloong/mixin/BlockBreakEffectMixin.java` | 新建 | 3 |
| `src/main/java/com/zonlong/beloong/mixin/BlockConversionEffectMixin.java` | 新建 | 3 |
| `src/main/java/com/zonlong/beloong/mixin/ExplodeBlockEffectMixin.java` | 新建 | 3 |
| `src/main/java/com/zonlong/beloong/mixin/FireEffectMixin.java` | 新建 | 3 |
| `src/main/java/com/zonlong/beloong/mixin/BlockHarvestEffectMixin.java` | 新建 | 3 |
| `src/main/java/com/zonlong/beloong/mixin/BonemealEffectMixin.java` | 新建 | 3 |
| `src/main/java/com/zonlong/beloong/Config.java` | 修改 | 4 |
| `src/main/resources/beloong.mixins.json` | 修改 | 5 |
| `src/main/resources/assets/beloong/lang/zh_cn.json` | 修改 | 6 |
| `src/main/resources/assets/beloong/lang/en_us.json` | 修改 | 6 |
