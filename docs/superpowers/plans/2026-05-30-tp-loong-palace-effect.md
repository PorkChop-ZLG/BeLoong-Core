# tp_loong_palace Effect Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 DragonSurvival 注册一个自定义 `AbilityEntityEffect`——`beloong:tp_loong_palace`，实现主世界与龙宫维度之间的技能传送。

**Architecture:** 创建无参 record 实现 `AbilityEntityEffect` 接口，`apply()` 中根据目标所在维度分发：主世界→龙宫复用 Config 坐标走 `teleportTo()`，龙宫→主世界模拟末地出口传送门查重生点。通过 `@EventBusSubscriber` 注册到 `AbilityEntityEffect.REGISTRY`。附带数据驱动能力 JSON 用于测试。

**Tech Stack:** Minecraft NeoForge 1.21.1, Java 21, DragonSurvival API

---

## File Structure

| 文件 | 操作 | 职责 |
|---|---|---|
| `src/main/java/com/zonlong/beloong/ability/TpLoongPalaceEffect.java` | 创建 | 效果记录，含 `apply()` 传送逻辑 |
| `src/main/java/com/zonlong/beloong/ability/AbilityEffectRegistry.java` | 创建 | `@EventBusSubscriber` 注册入口 |
| `src/main/resources/assets/beloong/lang/zh_cn.json` | 修改 | 新增错误提示翻译 |
| `src/main/resources/assets/beloong/lang/en_us.json` | 修改 | 新增错误提示翻译 |
| `src/main/resources/data/dragonsurvival/dragonsurvival/dragon_ability/tp_loong_palace.json` | 创建 | 测试用数据驱动能力定义 |

---

### Task 1: Create TpLoongPalaceEffect record

**Files:**
- Create: `src/main/java/com/zonlong/beloong/ability/TpLoongPalaceEffect.java`

- [ ] **Step 1: Write the effect class**

```java
package com.zonlong.beloong.ability;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.entity_effects.AbilityEntityEffect;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.util.Set;

public record TpLoongPalaceEffect() implements AbilityEntityEffect {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final MapCodec<TpLoongPalaceEffect> CODEC = MapCodec.unit(TpLoongPalaceEffect::new);

    @Override
    public void apply(final ServerPlayer dragon, final DragonAbilityInstance ability, final Entity target) {
        if (!(target instanceof ServerPlayer player)) {
            return;
        }

        ResourceLocation currentDim = target.level().dimension().location();
        String owKey = Level.OVERWORLD.location().toString();
        String lpKey = ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "loong_palace").toString();

        if (currentDim.toString().equals(owKey)) {
            teleportToLoongPalace(player);
        } else if (currentDim.toString().equals(lpKey)) {
            teleportToOverworldSpawn(player);
        }
    }

    private void teleportToLoongPalace(final ServerPlayer player) {
        String targetDimStr = Config.DimensionTransport.owToLP_targetDimension.get();
        ResourceLocation targetDimId = ResourceLocation.tryParse(targetDimStr);
        if (targetDimId == null) {
            LOGGER.warn("[BeLoongCore] Invalid target dimension ID: {}", targetDimStr);
            player.sendSystemMessage(Component.translatable(
                    "message.beloong.tp_loong_palace.invalid_dimension", targetDimStr));
            return;
        }

        if (player.level().dimension().location().equals(targetDimId)) {
            return;
        }

        ServerLevel targetLevel = player.server.getLevel(
                ResourceKey.create(Registries.DIMENSION, targetDimId));
        if (targetLevel == null) {
            LOGGER.warn("[BeLoongCore] Target dimension not found: {}", targetDimId);
            player.sendSystemMessage(Component.translatable(
                    "message.beloong.tp_loong_palace.dimension_not_found", targetDimId.toString()));
            return;
        }

        if (player.isPassenger()) {
            player.stopRiding();
        }

        double targetX = Config.DimensionTransport.owToLP_targetX.get();
        double targetZ = Config.DimensionTransport.owToLP_targetZ.get();
        double fallbackY = Config.DimensionTransport.owToLP_fallbackY.get();

        int blockX = (int) Math.floor(targetX);
        int blockZ = (int) Math.floor(targetZ);
        targetLevel.getChunk(blockX >> 4, blockZ >> 4);

        int topBlockY = targetLevel.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
        double safeY = topBlockY > targetLevel.getMinBuildHeight() ? topBlockY + 1.0 : fallbackY;

        player.teleportTo(targetLevel, targetX, safeY, targetZ,
                Set.of(), player.getYRot(), player.getXRot());

        LOGGER.debug("[BeLoongCore] {} teleported from overworld to {} ({}, {}, {})",
                player.getName().getString(), targetDimId, targetX, safeY, targetZ);
    }

    private void teleportToOverworldSpawn(final ServerPlayer player) {
        ServerLevel overworld = player.server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            LOGGER.warn("[BeLoongCore] Overworld not found for teleport");
            player.sendSystemMessage(Component.translatable(
                    "message.beloong.tp_loong_palace.dimension_not_found",
                    Level.OVERWORLD.location().toString()));
            return;
        }

        if (player.isPassenger()) {
            player.stopRiding();
        }

        // 模拟末地出口传送门：先查玩家重生点（床/锚），无效则世界出生点
        BlockPos targetPos = player
                .findRespawnPositionAndUseSpawnBlock(overworld, overworld.getSharedSpawnPos(), 0, false, false)
                .map(ServerPlayer.RespawnPosAngle::position)
                .orElseGet(overworld::getSharedSpawnPos);

        overworld.getChunk(targetPos.getX() >> 4, targetPos.getZ() >> 4);
        int topBlockY = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING, targetPos.getX(), targetPos.getZ());
        double safeY = topBlockY > overworld.getMinBuildHeight() ? topBlockY + 1.0 : targetPos.getY() + 0.5;

        player.teleportTo(overworld,
                targetPos.getX() + 0.5, safeY, targetPos.getZ() + 0.5,
                Set.of(), player.getYRot(), player.getXRot());

        LOGGER.debug("[BeLoongCore] {} teleported from loong_palace to overworld spawn ({}, {}, {})",
                player.getName().getString(), targetPos.getX() + 0.5, safeY, targetPos.getZ() + 0.5);
    }

    @Override
    public MapCodec<? extends AbilityEntityEffect> entityCodec() {
        return CODEC;
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (no errors related to TpLoongPalaceEffect)

---

### Task 2: Create AbilityEffectRegistry for registration

**Files:**
- Create: `src/main/java/com/zonlong/beloong/ability/AbilityEffectRegistry.java`

- [ ] **Step 1: Write the registry class**

```java
package com.zonlong.beloong.ability;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.entity_effects.AbilityEntityEffect;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = BeLoongCore.MODID, bus = EventBusSubscriber.Bus.MOD)
public class AbilityEffectRegistry {

    private AbilityEffectRegistry() {}

    @SubscribeEvent
    static void registerEntityEffects(final RegisterEvent event) {
        if (event.getRegistry() == AbilityEntityEffect.REGISTRY) {
            event.register(AbilityEntityEffect.REGISTRY_KEY,
                    ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "tp_loong_palace"),
                    () -> TpLoongPalaceEffect.CODEC);
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

---

### Task 3: Add translation keys

**Files:**
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

- [ ] **Step 1: Add zh_cn translation**

In `zh_cn.json`, after line 62 (`"message.beloong.dimension_transport.invalid_dimension"`), add:
```json
"message.beloong.tp_loong_palace.dimension_not_found": "§c找不到目标维度：%s",
"message.beloong.tp_loong_palace.invalid_dimension": "§c无效的目标维度ID：%s",
```

- [ ] **Step 2: Add en_us translation**

In `en_us.json`, after line 62 (`"message.beloong.dimension_transport.invalid_dimension"`), add:
```json
"message.beloong.tp_loong_palace.dimension_not_found": "§cTarget dimension not found: %s",
"message.beloong.tp_loong_palace.invalid_dimension": "§cInvalid target dimension ID: %s",
```

- [ ] **Step 3: Verify JSON validity**

Run: `python -c "import json; json.load(open('src/main/resources/assets/beloong/lang/zh_cn.json')); json.load(open('src/main/resources/assets/beloong/lang/en_us.json')); print('OK')"`
Expected: OK

---

### Task 4: Create test datapack ability JSON

**Files:**
- Create: `src/main/resources/data/dragonsurvival/dragonsurvival/dragon_ability/tp_loong_palace.json`

- [ ] **Step 1: Write the ability JSON**

```json
{
  "actions": [
    {
      "target_selection": {
        "applied_effects": {
          "entity_effect": [
            {
              "effect_type": "beloong:tp_loong_palace"
            }
          ],
          "targeting_mode": "allies_and_self"
        },
        "target_type": "dragonsurvival:self"
      }
    }
  ],
  "activation": {
    "activation_type": "dragonsurvival:simple",
    "animations": {
      "end": {
        "animation_key": "magic_alt",
        "layer": "BASE",
        "locks_neck": false,
        "locks_tail": false
      },
      "start_and_charging": {
        "animation_key": "cast_magic_alt",
        "layer": "BASE",
        "locks_neck": false,
        "locks_tail": false,
        "transition_length": 5
      }
    },
    "cast_time": 20.0,
    "cooldown": 200.0,
    "initial_mana_cost": 1.0,
    "sound": {
      "end": "minecraft:entity.enderman.teleport"
    }
  },
  "icon": {
    "texture_entries": [
      {
        "from_level": 0,
        "texture_resource": "dragonsurvival:test"
      }
    ]
  },
  "upgrade": {
    "level_requirement": {
      "type": "minecraft:linear",
      "base": 10.0,
      "per_level_above_first": 10.0
    },
    "maximum_level": 3,
    "upgrade_type": "dragonsurvival:experience_levels"
  }
}
```

- [ ] **Step 2: Verify JSON validity**

Run: `python -c "import json; json.load(open('src/main/resources/data/dragonsurvival/dragonsurvival/dragon_ability/tp_loong_palace.json')); print('OK')"`
Expected: OK

---

### Task 5: Full build verification

- [ ] **Step 1: Clean build**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Verify the mod JAR contains all new files**

Run: `jar tf build/libs/beloong-*.jar | grep -E "(TpLoongPalaceEffect|AbilityEffectRegistry|tp_loong_palace.json)"`
Expected:
```
com/zonlong/beloong/ability/TpLoongPalaceEffect.class
com/zonlong/beloong/ability/AbilityEffectRegistry.class
data/dragonsurvival/dragonsurvival/dragon_ability/tp_loong_palace.json
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/zonlong/beloong/ability/ src/main/resources/data/ src/main/resources/assets/beloong/lang/
git commit -m "feat: add tp_loong_palace ability entity effect for DragonSurvival"
```
