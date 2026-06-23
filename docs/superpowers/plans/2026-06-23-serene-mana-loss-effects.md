# Serene & Mana Loss Effects Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two potion effects — Serene (+mana regen attribute) and Mana Loss (per-tick mana drain) — to BeLoong Core.

**Architecture:** Serene is a standard `MobEffect` with attribute modifier, Mana Loss is a standard `MobEffect` plus a `PlayerTickEvent.Post` handler that calls DS `ManaHandler.consumeMana()`. Both registered via the existing `DeferredRegister` in `ModMobEffects.java`.

**Tech Stack:** Minecraft NeoForge 1.21.1, Dragon Survival API, Java 21

---

## File Map

| File | Role |
|---|---|
| `registry/ModMobEffects.java` | Register `SERENE` and `MANA_LOSS` effect holders |
| `registry/ManaLossHandler.java` | **New.** `PlayerTickEvent.Post` → deduct mana for players with `MANA_LOSS` |
| `BeLoongCore.java` | Register `ManaLossHandler` on `NeoForge.EVENT_BUS` |
| `resources/assets/beloong/lang/zh_cn.json` | Chinese localization |
| `resources/assets/beloong/lang/en_us.json` | English localization |

---

### Task 1: Register SERENE and MANA_LOSS in ModMobEffects

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/registry/ModMobEffects.java`

- [ ] **Step 1: Add the two effect registrations**

Add after the `FLIGHT_BAN` registration block (after line 84). Insert:

```java
    // ===================== 气定神闲 =====================

    /**
     * 气定神闲效果：每级提升法力回复属性（{@code dragonsurvival:mana_regeneration}）。
     * 默认每级 +0.001（通过 attribute modifier 的 ADD_VALUE 操作叠加）。
     * vanilla 自动按 {@code amount × (amplifier + 1)} 缩放：
     * <pre>
     *   I 级 (amp=0) → +0.001 × 1 = +0.001
     *   II 级 (amp=1) → +0.001 × 2 = +0.002
     * </pre>
     */
    public static final Holder<MobEffect> SERENE = REGISTRY.register(
            "serene",
            () -> {
                Attribute manaRegenAttr = BuiltInRegistries.ATTRIBUTE.get(
                        ResourceLocation.fromNamespaceAndPath("dragonsurvival", "mana_regeneration"));
                MobEffect effect = new MobEffect(MobEffectCategory.BENEFICIAL, 0x87CEEB) {};
                if (manaRegenAttr != null) {
                    effect.addAttributeModifier(
                            BuiltInRegistries.ATTRIBUTE.wrapAsHolder(manaRegenAttr),
                            ResourceLocation.fromNamespaceAndPath("beloong", "serene"),
                            0.001,
                            AttributeModifier.Operation.ADD_VALUE
                    );
                }
                return effect;
            }
    );

    // ===================== 魔力流逝 =====================

    /**
     * 魔力流逝效果——持续扣除龙族玩家的法力值。
     *
     * <p>效果本身仅注册为有害药水效果。每 tick 的法力扣除逻辑由
     * {@link ManaLossHandler#onPlayerTick} 处理。</p>
     *
     * <p>扣除量：{@code 0.025 × (amplifier + 1)} mana/tick</p>
     *
     * @see ManaLossHandler
     */
    public static final Holder<MobEffect> MANA_LOSS = REGISTRY.register(
            "mana_loss",
            () -> new MobEffect(MobEffectCategory.HARMFUL, 0x8B008B) {}
    );
```

- [ ] **Step 2: Add the ManaLossHandler import**

Since the Javadoc on `MANA_LOSS` references `ManaLossHandler`, add the import. If the IDE complains before the class is created, wrap the reference in `{@code ManaLossHandler}` instead.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/zonlong/beloong/registry/ModMobEffects.java
git commit -m "feat: register SERENE and MANA_LOSS mob effects"
```

---

### Task 2: Create ManaLossHandler

**Files:**
- Create: `src/main/java/com/zonlong/beloong/registry/ManaLossHandler.java`

- [ ] **Step 1: Create the handler class**

```java
package com.zonlong.beloong.registry;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.common.handlers.magic.ManaHandler;
import by.dragonsurvivalteam.dragonsurvival.network.syncing.SyncMana;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 处理魔力流逝（mana_loss）效果的每 tick 法力扣除。
 *
 * <p>在 {@link PlayerTickEvent.Post} 中检查玩家是否拥有
 * {@link ModMobEffects#MANA_LOSS} 效果，若有则调用 Dragon Survival 的
 * {@link ManaHandler#consumeMana} 扣除法力。</p>
 *
 * <p>扣除量：{@code 0.025 × (amplifier + 1)} 每 tick</p>
 */
public class ManaLossHandler {

    /** 每级效果等级扣除的法力量（tick） */
    private static final float BASE_DRAIN = 0.025f;

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();

        if (player.level().isClientSide()) {
            return;
        }

        if (!DragonStateProvider.isDragon(player)) {
            return;
        }

        MobEffectInstance instance = player.getEffect(ModMobEffects.MANA_LOSS);
        if (instance == null) {
            return;
        }

        float deduction = BASE_DRAIN * (instance.getAmplifier() + 1);
        ManaHandler.consumeMana(player, deduction);

        PacketDistributor.sendToPlayer(player,
                new SyncMana(MagicData.getData(player).getCurrentMana()));
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/zonlong/beloong/registry/ManaLossHandler.java
git commit -m "feat: add ManaLossHandler for per-tick mana drain"
```

---

### Task 3: Register ManaLossHandler in BeLoongCore

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`

- [ ] **Step 1: Add the event handler registration**

In the constructor, after the StructureEffectHandler registration line:

```java
NeoForge.EVENT_BUS.register(new StructureEffectHandler());
```

Add:

```java
NeoForge.EVENT_BUS.register(new ManaLossHandler());
```

And add the import:

```java
import com.zonlong.beloong.registry.ManaLossHandler;
```

The constructor block should look like:

```java
// === 事件处理器 ===
NeoForge.EVENT_BUS.register(this);
NeoForge.EVENT_BUS.register(new DimensionTransportHandler());
NeoForge.EVENT_BUS.register(new StructureEffectHandler());
NeoForge.EVENT_BUS.register(new ManaLossHandler());
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/zonlong/beloong/BeLoongCore.java
git commit -m "feat: register ManaLossHandler on event bus"
```

---

### Task 4: Add localization entries

**Files:**
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

- [ ] **Step 1: Add Chinese localization**

In `zh_cn.json`, add after the existing `effect.beloong.growth_acceleration.description` line:

```json
"effect.beloong.mana_loss": "魔力流逝",
"effect.beloong.mana_loss.description": "持续扣除法力值。",
"effect.beloong.serene": "气定神闲",
"effect.beloong.serene.description": "提升法力回复速度。",
```

- [ ] **Step 2: Add English localization**

In `en_us.json`, add after the existing `effect.beloong.growth_acceleration.description` line:

```json
"effect.beloong.mana_loss": "Mana Loss",
"effect.beloong.mana_loss.description": "Continuously drains mana.",
"effect.beloong.serene": "Serene",
"effect.beloong.serene.description": "Increases mana regeneration speed.",
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/assets/beloong/lang/zh_cn.json src/main/resources/assets/beloong/lang/en_us.json
git commit -m "feat: add localization for Serene and Mana Loss effects"
```

---

### Task 5: Build and verify

- [ ] **Step 1: Run the Gradle build**

```bash
cd e:/Minecraft/BeLoong-Core && ./gradlew build
```

Expected: BUILD SUCCESSFUL. Fix any compilation errors.

- [ ] **Step 2: Verify the mod loads in-game**

```bash
cd e:/Minecraft/BeLoong-Core && ./gradlew runClient
```

Expected:
- Game launches without crash
- `/effect give @s beloong:serene` applies the effect, mana regen bar shows faster regen
- `/effect give @s beloong:mana_loss` applies the effect, mana bar visibly drains
- `/effect clear @s beloong:mana_loss` stops the drain

- [ ] **Step 3: Commit if any fixes were made**

```bash
git add -A
git commit -m "fix: build verification fixes"
```

---

## Verification Checklist

- [ ] `./gradlew build` passes
- [ ] Serene effect applies and increases mana regen attribute (`/effect give @s beloong:serene 30 0`)
- [ ] Serene effect scales with amplifier (`/effect give @s beloong:serene 30 1` gives 2x regen boost)
- [ ] Mana Loss effect drains mana (`/effect give @s beloong:mana_loss 100 0`)
- [ ] Mana Loss scales with amplifier (`/effect give @s beloong:mana_loss 100 1` drains 2x faster)
- [ ] Mana Loss does nothing on non-dragon players
- [ ] Source of Magic effect grants immunity to Mana Loss
- [ ] Effect names appear correctly in inventory tooltip
