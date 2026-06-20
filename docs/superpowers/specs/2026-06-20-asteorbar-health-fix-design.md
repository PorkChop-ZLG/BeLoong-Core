# Fix: AsteorBar Health Display After Dimension Change (Mixin)

## Problem

When a player's max health > 20 (e.g., modified to 40 via attributes) and the player changes dimensions, the AsteorBar health bar shows the wrong value — health displays as 20 instead of the true value (e.g., 40).

### Root Cause

During dimension change, vanilla sends two separate packets from server to client:

- `ClientboundSetEntityDataPacket` — carries health value
- `ClientboundUpdateAttributesPacket` — carries max health attribute

`LivingEntity.setHealth()` clamps to `getMaxHealth()`. If the entity data packet arrives before the attribute packet, the health value (e.g., 40) is clamped to the default max health (20). When the attribute packet later corrects max health to 40, the health was already permanently reduced to 20.

### Scope

- **Affects**: NeoForge client, dimension teleport or respawn, max health > 20
- **Manifests as**: AsteorBar health bar fill and text showing values around 20 instead of the true health

## Solution

Mixin injection into AsteorBar's `PlayerHealthOverlay.getParameters()`, detecting player entity changes and applying a delayed health correction after the vanilla attribute sync completes.

### Approach

- Mixin targets AsteorBar class only — no vanilla class injection
- Mixin adds static state fields + logic at `@At("HEAD")` of `getParameters()`
- 20-tick delay (1 second @20TPS) to cover extreme lag scenarios
- Client-side only, no server installation required

## Design

### State Fields

Added to Mixin (`@Unique` static fields):

| Field | Type | Initial | Purpose |
|-------|------|---------|---------|
| `lastPlayerEntityId` | `int` | `-1` | Previous frame's `player.getId()`, detects player reset |
| `lastTickHealth` | `float` | `0` | Previous frame's `player.getHealth()`, cached as fix target |
| `healthFixTarget` | `float` | `0` | Old player health, locked when entity change detected |
| `healthFixDelay` | `int` | `0` | Countdown ticks before applying fix (20→0) |
| `healthFixPending` | `boolean` | `false` | Whether a fix is awaiting application |

### Flow

```
Each render frame, at HEAD of getParameters(player):
  1. If player == null: reset all state, return
  2. Compare player.getId() with lastPlayerEntityId
  3. If changed AND lastPlayerEntityId != -1 (not first join):
       healthFixTarget = lastTickHealth
       healthFixDelay = 20
       healthFixPending = true
  4. lastPlayerEntityId = player.getId()
  5. If healthFixPending:
       If healthFixDelay > 0: healthFixDelay--
       If healthFixDelay == 0:
         If healthFixTarget > player.getHealth() + 1.0:
           player.setHealth(Math.min(healthFixTarget, player.getMaxHealth()))
         healthFixPending = false
  6. lastTickHealth = player.getHealth()
```

### Key Design Decisions

**20-tick delay**: 1 second at 20 TPS. Accommodates extreme lag where attribute sync may be delayed significantly. For comparison, the original design used 3 ticks (150ms) — this 20-tick version trades a brief visual flicker (≤1s) for robustness under adverse network conditions.

**EntityId over reference comparison**: `player.getId()` is a reliable integer comparison unaffected by object lifecycle.

**1.0 HP correction threshold**: Prevents false corrections from natural health changes.

**`Math.min(target, maxHealth)` safety cap**: Never exceeds actual max health.

**One-shot semantics**: `healthFixPending` cleared after first check, preventing stale corrections.

### Edge Cases

| Scenario | Behavior |
|----------|----------|
| Player is null (loading screen) | Reset all state, no action |
| First world join (`lastPlayerEntityId == -1`) | No fix triggered (no old health) |
| Rapid consecutive dimension changes | New detection overwrites previous pending fix |
| Player took damage right before teleport | `Math.min(target, maxHealth)` prevents overheal |
| Health decreased naturally (not a bug) | Threshold check (`> 1.0`) prevents false fix |
| Death respawn | Entity change detected; old health capped to new maxHealth |
| AsteorBar not installed | Mixin target class absent → Mixin framework skips silently |

## Implementation

### Mixin Registration

Add to `beloong.mixins.json` under `"client"` array:

```json
"AsteorBarHealthFixMixin"
```

### Files Changed

| File | Change |
|------|--------|
| `src/main/java/com/zonlong/beloong/mixin/AsteorBarHealthFixMixin.java` | Create: ~50 lines Mixin |
| `src/main/resources/beloong.mixins.json` | Modify: add 1 entry to `"client"` array |

### No Changes To

- Vanilla Minecraft classes
- AsteorBar source code
- Server-side code
- Other mods
