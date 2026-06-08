# 飞行等级系统实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在龙之生存飞行系统中引入 FLIGHT_LEVEL 属性梯级飞行能力体系，并在化龙核心中新增"禁空"状态效果。

**Architecture:** 通过 Mixin 向 Dragon Survival 的 DSAttributes 注册新属性；用 Mixin 替换 ClientFlightHandler 中的 stableHover 配置为 FLIGHT_LEVEL 判断；用 Mixin 在 ToggleFlight 中增加飞行等级门控；在化龙核心 ModMobEffects 中添加禁空效果。

**Tech Stack:** Java 21, NeoForge 1.21.1, Sponge Mixin 0.8.7, Dragon Survival API

---

### 文件与职责

| 文件 | 操作 | 职责 |
|------|------|------|
| `mixin/DSAttributesMixin.java` | 新建 | 在 DS 侧注册 `dragonsurvival:flight_level` attribute |
| `mixin/ClientFlightHandlerStableHoverMixin.java` | 新建 | 替换 `flightControl()` 中的 `stableHover` 引用 |
| `mixin/ClientFlightHandlerMixin.java` | 修改 | 更新 `fixStableHoverDrift` 使用 FLIGHT_LEVEL |
| `mixin/ToggleFlightMixin.java` | 新建 | 展翅前检查飞行等级 >= 0 |
| `registry/ModAttributes.java` | 修改 | 添加 `getFlightLevel()` 辅助方法 |
| `registry/ModMobEffects.java` | 修改 | 注册 `flight_ban` 效果 |
| `resources/beloong.mixins.json` | 修改 | 注册新 Mixin 类 |
| `resources/data/dragonsurvival/.../cave_wings.json` | 新建 | 3 级翅膀技能示例 |
| `resources/assets/beloong/lang/zh_cn.json` | 修改 | 禁空效果中文翻译 |
| `resources/assets/beloong/lang/en_us.json` | 修改 | 禁空效果英文翻译 |

---

### Task 1: 注册 FLIGHT_LEVEL Attribute

**Files:**
- Create: `src/main/java/com/zonlong/beloong/mixin/DSAttributesMixin.java`

- [ ] **Step 1: 创建 DSAttributesMixin**

```java
package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.registry.DSAttributes;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DSAttributes.class, remap = false)
public abstract class DSAttributesMixin {

    @Unique
    private static Holder<Attribute> beloong$FLIGHT_LEVEL;

    /** 在 DSAttributes 静态初始化末尾注册 FLIGHT_LEVEL */
    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void registerFlightLevel(CallbackInfo ci) {
        beloong$FLIGHT_LEVEL = DSAttributes.REGISTRY.register(
                "flight_level",
                () -> new RangedAttribute(
                        "dragonsurvival.flight_level",
                        0.0,
                        -1024.0,
                        1024.0
                ).setSyncable(true)
        );
    }

    /** 在 attachAttributes 尾部将 FLIGHT_LEVEL attach 到 PLAYER */
    @Inject(method = "attachAttributes", at = @At("TAIL"))
    private static void attachFlightLevel(EntityAttributeModificationEvent event, CallbackInfo ci) {
        event.add(EntityType.PLAYER, beloong$FLIGHT_LEVEL);
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/zonlong/beloong/mixin/DSAttributesMixin.java
git commit -m "feat: add DSAttributesMixin to register FLIGHT_LEVEL attribute"
```

---

### Task 2: 添加飞行等级辅助方法

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/registry/ModAttributes.java`

- [ ] **Step 1: 添加 getFlightLevel 方法**

```java
package com.zonlong.beloong.registry;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.entity.player.Player;
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

    // ───────────────────── 新增 ─────────────────────

    /**
     * 获取玩家的有效飞行等级（已应用所有 attribute modifier）。
     * FLIGHT_LEVEL 由 DSAttributesMixin 注册为 dragonsurvival:flight_level。
     *
     * @return 有效飞行等级，若属性未注册则返回 0
     */
    public static double getFlightLevel(Player player) {
        Holder<Attribute> attr = BuiltInRegistries.ATTRIBUTE.get(
                ResourceLocation.fromNamespaceAndPath("dragonsurvival", "flight_level"));
        if (attr == null) {
            return 0.0;
        }
        return player.getAttributeValue(attr);
    }

    // ───────────────────── 已有 ─────────────────────

    @SubscribeEvent
    public static void attachAttributes(EntityAttributeModificationEvent event) {
        event.add(EntityType.PLAYER, GROWTH_SPEED);
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/zonlong/beloong/registry/ModAttributes.java
git commit -m "feat: add getFlightLevel helper to ModAttributes"
```

---

### Task 3: 注册禁空效果

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/registry/ModMobEffects.java`

- [ ] **Step 1: 添加 FLIGHT_BAN 效果**

```java
package com.zonlong.beloong.registry;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModMobEffects {
    public static final DeferredRegister<MobEffect> REGISTRY =
            DeferredRegister.create(Registries.MOB_EFFECT, "beloong");

    public static final Holder<MobEffect> GROWTH_ACCELERATION = REGISTRY.register(
            "growth_acceleration",
            () -> new MobEffect(MobEffectCategory.BENEFICIAL, 0xFFD700) { }
                    .addAttributeModifier(
                            ModAttributes.GROWTH_SPEED,
                            ResourceLocation.fromNamespaceAndPath("beloong", "growth_acceleration"),
                            1.0,
                            AttributeModifier.Operation.ADD_VALUE
                    )
    );

    // ───────────────────── 新增 ─────────────────────

    /**
     * 禁空效果：每级降低 1 点飞行等级。
     * 原版自动按 (amplifier + 1) 缩放，amount = -1 时：
     *   I 级 (amp 0) → -1 × 1 = -1
     *   II 级 (amp 1) → -1 × 2 = -2
     */
    public static final Holder<MobEffect> FLIGHT_BAN = REGISTRY.register(
            "flight_ban",
            () -> {
                Holder<Attribute> flightLevel = BuiltInRegistries.ATTRIBUTE.get(
                        ResourceLocation.fromNamespaceAndPath("dragonsurvival", "flight_level"));
                MobEffect effect = new MobEffect(MobEffectCategory.HARMFUL, 0x8B0000) { };
                if (flightLevel != null) {
                    effect.addAttributeModifier(
                            flightLevel,
                            ResourceLocation.fromNamespaceAndPath("beloong", "flight_ban"),
                            -1.0,
                            AttributeModifier.Operation.ADD_VALUE
                    );
                }
                return effect;
            }
    );

    // ───────────────────── 已有 ─────────────────────
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/zonlong/beloong/registry/ModMobEffects.java
git commit -m "feat: add flight_ban mob effect"
```

---

### Task 4: ToggleFlight 飞行门控 Mixin

**Files:**
- Create: `src/main/java/com/zonlong/beloong/mixin/ToggleFlightMixin.java`

- [ ] **Step 1: 创建 ToggleFlightMixin**

```java
package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.network.flight.ToggleFlight;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.FlightData;
import com.zonlong.beloong.registry.ModAttributes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 ToggleFlight.handleServer 开头注入飞行等级检查。
 * 若飞行等级 < 0 且玩家试图展翅，则取消并返回 NONE。
 */
@Mixin(value = ToggleFlight.class, remap = false)
public class ToggleFlightMixin {

    @Inject(method = "handleServer", at = @At("HEAD"), cancellable = true)
    private static void flightLevelGate(ToggleFlight packet, IPayloadContext context, CallbackInfo ci) {
        Player player = context.player();
        if (player == null) return;

        FlightData flight = FlightData.getData(player);
        if (!flight.areWingsSpread && ModAttributes.getFlightLevel(player) < 0.0) {
            // 飞行等级不足，静默拒绝展翅
            PacketDistributor.sendToPlayer(
                    (ServerPlayer) player,
                    new ToggleFlight(packet.activation(), ToggleFlight.Result.NONE)
            );
            ci.cancel();
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/zonlong/beloong/mixin/ToggleFlightMixin.java
git commit -m "feat: add ToggleFlightMixin for flight level gate"
```

---

### Task 5: ClientFlightHandler 稳定悬停替换 Mixin

**Files:**
- Create: `src/main/java/com/zonlong/beloong/mixin/ClientFlightHandlerStableHoverMixin.java`

- [ ] **Step 1: 创建 ClientFlightHandlerStableHoverMixin**

`ClientFlightHandler.flightControl()` 中有两处读取 `ServerFlightHandler.stableHover`。用 `@ModifyExpressionValue` 分别替换，统一返回 `flightLevel >= 1`。

```java
package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.client.handlers.ClientFlightHandler;
import com.zonlong.beloong.registry.ModAttributes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyExpressionValue;

/**
 * 将 ClientFlightHandler.flightControl() 中对 ServerFlightHandler.stableHover
 * 的两处引用替换为 FLIGHT_LEVEL >= 1 的判断。
 *
 * 两处分别对应：
 *   ordinal 0 — 悬停时重力抵消逻辑（原: stableHover && !jumping && ...）
 *   ordinal 1 — 非悬停时的双倍重力分支（原: !stableHover）
 *
 * 两处都返回 flightLevel >= 1，因为 ordinal 1 前有 ! 取反，
 * 返回 flightLevel >= 1 经 ! 取反后等价于 flightLevel < 1。
 */
@Mixin(value = ClientFlightHandler.class, remap = false)
public abstract class ClientFlightHandlerStableHoverMixin {

    @ModifyExpressionValue(
            method = "flightControl",
            at = @At(
                    value = "FIELD",
                    target = "Lby/dragonsurvivalteam/dragonsurvival/server/handlers/ServerFlightHandler;stableHover:Z"
            ),
            require = 2
    )
    private static boolean replaceStableHover(boolean original) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            return ModAttributes.getFlightLevel(player) >= 1.0;
        }
        return false;
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/zonlong/beloong/mixin/ClientFlightHandlerStableHoverMixin.java
git commit -m "feat: add ClientFlightHandlerStableHoverMixin to replace stableHover with FLIGHT_LEVEL"
```

---

### Task 6: 更新已有 ClientFlightHandlerMixin

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/mixin/ClientFlightHandlerMixin.java:58`

- [ ] **Step 1: 替换 stableHover 引用**

将 `fixStableHoverDrift` 方法中第 58 行的 `ServerFlightHandler.stableHover` 替换为飞行等级判断：

```java
// 旧（第 58 行）
boolean shouldHover = ServerFlightHandler.stableHover
        && !movement.jumping
        && !movement.shiftKeyDown
        && !ServerFlightHandler.isSpin(player)
        && !ServerFlightHandler.isGliding(player);

// 新
double flightLevel = ModAttributes.getFlightLevel(player);
boolean shouldHover = flightLevel >= 1.0
        && !movement.jumping
        && !movement.shiftKeyDown
        && !ServerFlightHandler.isSpin(player)
        && !ServerFlightHandler.isGliding(player);
```

同时在文件顶部添加 import：

```java
import com.zonlong.beloong.registry.ModAttributes;
```

完整修改后的方法：

```java
@Inject(method = "flightControl", at = @At("TAIL"), remap = false)
private static void fixStableHoverDrift(CallbackInfo ci) {
    if (!Config.FIX_STABLE_HOVER.get()) {
        return;
    }

    LocalPlayer player = Minecraft.getInstance().player;
    if (player == null) return;

    DragonStateProvider.getOptional(player).ifPresent(handler -> {
        if (!handler.isDragon()) return;

        FlightData flightData = FlightData.getData(player);
        if (!flightData.isWingsSpread() || !flightData.hasFlight()) return;

        Input movement = player.input;
        double flightLevel = ModAttributes.getFlightLevel(player);
        boolean shouldHover = flightLevel >= 1.0
                && !movement.jumping
                && !movement.shiftKeyDown
                && !ServerFlightHandler.isSpin(player)
                && !ServerFlightHandler.isGliding(player);

        boolean noMoveInput = movement.forwardImpulse == 0 && movement.leftImpulse == 0;

        if (shouldHover && noMoveInput) {
            ax = 0.0;
            az = 0.0;

            if (player.isCreative()) {
                ay = 0.0;
                Vec3 delta = player.getDeltaMovement();
                player.setDeltaMovement(delta.x, 0, delta.z);
            }
        }
    });
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/zonlong/beloong/mixin/ClientFlightHandlerMixin.java
git commit -m "fix: update ClientFlightHandlerMixin to use FLIGHT_LEVEL instead of stableHover"
```

---

### Task 7: 添加示例 cave_wings.json 数据包

**Files:**
- Create: `src/main/resources/data/dragonsurvival/dragonsurvival/dragon_ability/cave_wings.json`

- [ ] **Step 1: 创建 cave_wings.json**

```json
{
  "actions": [
    {
      "target_selection": {
        "applied_effects": {
          "entity_effect": [
            {
              "effect_type": "dragonsurvival:flight",
              "icon": "dragonsurvival:textures/ability_effect/cave_dragon_wings.png",
              "level_requirement": 1
            },
            {
              "effect_type": "dragonsurvival:modifier",
              "modifiers": [
                {
                  "base": {
                    "duration_type": "dragonsurvival:infinite"
                  },
                  "modifiers": [
                    {
                      "attribute": "dragonsurvival:flight_level",
                      "amount": {
                        "type": "minecraft:lookup",
                        "values": [0, 0, 1],
                        "fallback": {
                          "type": "minecraft:linear",
                          "base": 0,
                          "per_level_above_first": 1
                        }
                      },
                      "operation": "add_value"
                    }
                  ]
                }
              ]
            }
          ],
          "targeting_mode": "allies_and_self"
        },
        "target_type": "dragonsurvival:self"
      }
    }
  ],
  "activation": {
    "activation_type": "dragonsurvival:passive"
  },
  "icon": {
    "texture_entries": [
      {
        "from_level": 0,
        "texture_resource": "dragonsurvival:abilities/cave/cave_wings_0"
      },
      {
        "from_level": 1,
        "texture_resource": "dragonsurvival:abilities/cave/cave_wings_1"
      },
      {
        "from_level": 2,
        "texture_resource": "dragonsurvival:abilities/cave/cave_wings_1"
      }
    ]
  },
  "upgrade": {
    "upgrade_type": "dragonsurvival:dragon_growth",
    "maximum_level": 2,
    "growth_requirement": {
      "type": "minecraft:lookup",
      "values": [0, 0, 60],
      "fallback": {
        "type": "minecraft:linear",
        "base": 60,
        "per_level_above_first": 20
      }
    }
  },
  "usage_blocked": {
    "condition": "minecraft:entity_properties",
    "entity": "this",
    "predicate": {
      "type_specific": {
        "type": "dragonsurvival:dragon_predicate",
        "flight_was_granted": false
      }
    }
  }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/resources/data/dragonsurvival/dragonsurvival/dragon_ability/cave_wings.json
git commit -m "feat: add sample 3-level cave_wings.json with dragon_growth upgrade"
```

---

### Task 8: 更新语言文件

**Files:**
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

- [ ] **Step 1: 中文翻译**

在 `zh_cn.json` 末尾（`"block.beloong.disaster_portal_frame.eye_duplicate"` 之后，`}` 之前）添加：

```json
  "effect.beloong.flight_ban": "禁空"
```

- [ ] **Step 2: 英文翻译**

在 `en_us.json` 末尾（`"block.beloong.disaster_portal_frame.eye_duplicate"` 之后，`}` 之前）添加：

```json
  "effect.beloong.flight_ban": "Flight Ban"
```

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/assets/beloong/lang/zh_cn.json src/main/resources/assets/beloong/lang/en_us.json
git commit -m "feat: add flight_ban effect translations"
```

---

### Task 9: 注册 Mixin 配置

**Files:**
- Modify: `src/main/resources/beloong.mixins.json`

- [ ] **Step 1: 在 beloong.mixins.json 中注册新 Mixin 类**

在 `"client"` 数组中新增：
```json
"ClientFlightHandlerStableHoverMixin"
```

在 `"mixins"` 数组中新增：
```json
"DSAttributesMixin",
"ToggleFlightMixin"
```

完整文件：

```json
{
  "required": true,
  "package": "com.zonlong.beloong.mixin",
  "refmap": "beloong.refmap.json",
  "client": [
    "ClientFlightHandlerMixin",
    "ClientFlightHandlerStableHoverMixin",
    "DragonItemRenderLayerMixin",
    "OutlineBufferSourceAccessor",
    "BossClientEventsMixin"
  ],
  "mixins": [
    "DSAttributesMixin",
    "ToggleFlightMixin",
    "DragonDestructionHandlerMixin",
    "BlockBreakEffectMixin",
    "BlockConversionEffectMixin",
    "ExplodeBlockEffectMixin",
    "FireEffectMixin",
    "BlockHarvestEffectMixin",
    "BonemealEffectMixin",
    "MixinDragonGrowthHandler",
    "TreasureBlockMixin",
    "CloneParameterListMixin",
    "BurningArenaStructureMixin",
    "RuinedCitadelStructureMixin",
    "SunkenCityStructureMixin",
    "CursedPyramidStructureMixin",
    "GeburahArenaStructureMixin",
    "MalkuthArenaStructureMixin",
    "NetMagnetItemMixin"
  ],
  "compatibilityLevel": "JAVA_21",
  "injectors": {
    "defaultRequire": 1
  }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/resources/beloong.mixins.json
git commit -m "feat: register DSAttributesMixin, ToggleFlightMixin, ClientFlightHandlerStableHoverMixin"
```

---

## 验证步骤

完成所有 Task 后执行：

1. `./gradlew build` — 确认编译通过
2. `./gradlew runClient` — 启动游戏，验证：
   - `/attribute @p dragonsurvival:flight_level get` 返回 0.0
   - 给予禁空效果后飞行等级变为 -1
   - 没有翅膀技能的龙无法飞行（`flight_was_granted: false`）
   - 有翅膀技能 Lv1 的龙可以飞行但不能悬停
   - 成长度 60+ 的成年龙 Lv2 可以飞行 + 稳定悬停
   - 成年龙 + 禁空 I → 能飞不能悬停
   - 成年龙 + 禁空 II → 不能飞
