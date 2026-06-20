# Fix: AsteorBar Health Display After Dimension Change (Mixin v2)

## Problem

When a player's max health > 20 (e.g., modified to 40 via attributes) and the player changes dimensions, the AsteorBar health bar shows the wrong value — health displays as 20 instead of the true value.

### Root Cause

During dimension change, vanilla sends two separate packets from server to client:

- `ClientboundSetEntityDataPacket` — carries health value
- `ClientboundUpdateAttributesPacket` — carries max health attribute

`LivingEntity.setHealth()` clamps to `getMaxHealth()`. If the entity data packet arrives before the attribute packet, the health value (e.g., 40) is clamped to the default max health (20). When the attribute packet later corrects max health to 40, the health was already permanently reduced to 20.

### Scope

- **Affects**: NeoForge client, dimension teleport or respawn, max health > 20
- **Manifests as**: AsteorBar health bar showing 20 instead of true health

## Solution

Mixin into AsteorBar's `PlayerHealthOverlay.getParameters()` using `@Redirect` to intercept the single `Player.getHealth()` call at line 39. During the freeze period after an entity change, return the old entity's cached health value. At freeze end, restore the player's actual health via `player.setHealth()`.

### Why @Redirect instead of @Inject

- `PlayerHealthOverlay.getParameters()` calls `player.getHealth()` exactly once (line 39); all subsequent code uses the `health` local variable
- `@Redirect` replaces that single call's return value — no need to modify local variables or deal with `CallbackInfoReturnable` generics issues
- `@Redirect` handler runs in the original method's stack frame, can have side effects (calling `player.setHealth()`)

### Approach

- Mixin targets AsteorBar class only — no vanilla class injection
- `@Redirect` on `Player.getHealth()` call within `getParameters()`
- 20-tick freeze using `player.tickCount` for timing
- During freeze: return old entity's cached health (smooth display, no flicker)
- At freeze end: call `player.setHealth()` to fix underlying data, then resume normal reads
- Client-side only

## Design

### State Fields

| Field | Type | Initial | Purpose |
|-------|------|---------|---------|
| `beloong$lastEntityId` | `int` | `-1` | Previous player entity ID for change detection |
| `beloong$lastGoodHealth` | `float` | `0` | Health from before entity change, used during freeze |
| `beloong$freezeTicksRemaining` | `int` | `0` | Remaining freeze ticks (20→0) |
| `beloong$healthFixTarget` | `float` | `0` | Target health for restoration at freeze end |

### Flow

```
@Redirect on Player.getHealth() in getParameters(Player player):
  1. currentId = player.getId()
  2. actualHealth = player.getHealth()     // real value (may be clamped)
  3. If currentId != lastEntityId AND lastEntityId != -1:
       freezeTicksRemaining = 20
       healthFixTarget = lastGoodHealth    // capture old health before overwrite
  4. lastEntityId = currentId
  5. If freezeTicksRemaining > 0:
       freezeTicksRemaining--
       return lastGoodHealth               // freeze display at old value
  6. If healthFixTarget > 0:
       If healthFixTarget > actualHealth + 1.0F:
         player.setHealth(Math.min(healthFixTarget, player.getMaxHealth()))
       healthFixTarget = 0
       actualHealth = player.getHealth()   // re-read after fix
  7. lastGoodHealth = actualHealth
  8. return actualHealth
```

### Key Design Decisions

**20-tick freeze**: 1 second at 20TPS. During this window the health bar displays the old (correct) value while waiting for the attribute packet to arrive and `getMaxHealth()` to be updated.

**Freeze + fix, not just freeze**: After freeze ends, we call `player.setHealth()` to correct the underlying entity data. Without this, unfreezing would show the still-clamped value.

**Health fix at freeze end**: The `setHealth()` call happens AFTER the 20-tick wait, ensuring `getMaxHealth()` has been updated by the attribute packet. `Math.min(target, maxHealth)` prevents overheal.

**1.0 HP correction threshold**: Prevents false corrections from natural health changes.

### Edge Cases

| Scenario | Behavior |
|----------|----------|
| First world join (`lastEntityId == -1`) | No freeze/redirect, normal read |
| Rapid consecutive dimension changes | New freeze overwrites previous |
| Player took damage before teleport | `Math.min(target, maxHealth)` prevents overheal |
| Natural health change (not a bug) | Threshold `> 1.0` prevents false fix |
| AsteorBar not installed | Target class absent, Mixin framework skips |

## Implementation

### Files Changed

| File | Change |
|------|--------|
| `src/main/java/com/zonlong/beloong/mixin/AsteorBarHealthFixMixin.java` | Rewrite: ~60 lines using @Redirect |
| `src/main/resources/beloong.mixins.json` | Already registered |

### No Changes To

- Vanilla Minecraft classes
- AsteorBar source code
- Server-side code
