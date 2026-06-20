# AsteorBar Health Fix (Mixin) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mixin into AsteorBar's `PlayerHealthOverlay` to fix health display clamping after dimension change

**Architecture:** Single Mixin class adds 5 static state fields and injects fix logic at HEAD of `getParameters()`. One-line registration in `beloong.mixins.json`. Client-side only, no vanilla class injection.

**Tech Stack:** Java 21 / Mixin (SpongeASM) / NeoForge 1.21.1

---

### Task 1: Create AsteorBarHealthFixMixin

**Files:**
- Create: `src/main/java/com/zonlong/beloong/mixin/AsteorBarHealthFixMixin.java`

- [ ] **Step 1: Write the Mixin class**

```java
package com.zonlong.beloong.mixin;

import com.afoxxvi.asteorbar.overlay.parts.PlayerHealthOverlay;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fixes AsteorBar health bar showing wrong value after dimension change
 * when player's max health exceeds 20.
 *
 * <p>Root cause: race condition between {@code ClientboundSetEntityDataPacket}
 * (health) and {@code ClientboundUpdateAttributesPacket} (max health). If entity
 * data arrives first, {@code LivingEntity.setHealth()} clamps health to default
 * max health (20). The attribute packet later corrects max health but health
 * stays clamped.</p>
 *
 * <p>This Mixin detects player entity changes and schedules a 20-tick delayed
 * health correction, giving the attribute packet time to sync.</p>
 */
@Mixin(value = PlayerHealthOverlay.class, remap = false)
public abstract class AsteorBarHealthFixMixin {

    @Unique
    private static int lastPlayerEntityId = -1;

    @Unique
    private static float lastTickHealth = 0;

    @Unique
    private static float healthFixTarget = 0;

    @Unique
    private static int healthFixDelay = 0;

    @Unique
    private static boolean healthFixPending = false;

    @Inject(method = "getParameters", at = @At("HEAD"))
    private void onGetParameters(Player player, CallbackInfoReturnable<?> cir) {
        if (player == null) {
            lastPlayerEntityId = -1;
            lastTickHealth = 0;
            healthFixPending = false;
            healthFixDelay = 0;
            return;
        }

        int currentId = player.getId();

        // Detect player entity change (dimension teleport / respawn)
        if (currentId != lastPlayerEntityId && lastPlayerEntityId != -1) {
            healthFixTarget = lastTickHealth;
            healthFixDelay = 20;
            healthFixPending = true;
        }

        lastPlayerEntityId = currentId;

        // Apply delayed health correction
        if (healthFixPending) {
            if (healthFixDelay > 0) {
                healthFixDelay--;
            } else {
                float currentHealth = player.getHealth();
                if (healthFixTarget > currentHealth + 1.0F) {
                    player.setHealth(Math.min(healthFixTarget, player.getMaxHealth()));
                }
                healthFixPending = false;
            }
        }

        lastTickHealth = player.getHealth();
    }
}
```

- [ ] **Step 2: Verify the Mixin compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL, no errors related to `AsteorBarHealthFixMixin`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/zonlong/beloong/mixin/AsteorBarHealthFixMixin.java
git commit -m "feat: add AsteorBar health fix Mixin for dimension change health clamping"
```

---

### Task 2: Register Mixin in config

**Files:**
- Modify: `src/main/resources/beloong.mixins.json` (insert 1 line in `"client"` array)

- [ ] **Step 1: Add Mixin entry to client array**

In `beloong.mixins.json`, add `"AsteorBarHealthFixMixin"` to the `"client"` array, after `"FSweepButtonControlMixin"`:

```json
  "client": [
    "ClientFlightHandlerMixin",
    "DragonItemRenderLayerMixin",
    "OutlineBufferSourceAccessor",
    "BossClientEventsMixin",
    "FSweepButtonControlMixin",
    "AsteorBarHealthFixMixin"
  ],
```

- [ ] **Step 2: Verify build passes with Mixin registered**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/beloong.mixins.json
git commit -m "fix: register AsteorBarHealthFixMixin in Mixin config"
```
