# Flight Level System Design

## Summary

Introduce a `FLIGHT_LEVEL` attribute to Dragon Survival's flight system, replacing the binary `hasFlight` / `stableHover` config model with a graduated flight capability system. Add a "禁空" (Flight Ban) mob effect that reduces flight level, creating gameplay push-pull between dragon wings progression and hostile debuffs.

## Core Mechanic

| FLIGHT_LEVEL | Result |
|---|---|
| `< 0` | Cannot fly — wing spread is denied |
| `= 0` | Can fly, no stable hover (elytra-like drift) |
| `>= 1` | Can fly + stable hover (creative-like) |

Attribute: `dragonsurvival:flight_level`, range `[-1024, 1024]`, default `0`, synced to client.

## Component Breakdown

### 1. FLIGHT_LEVEL Attribute — Mixin into DSAttributes

Mixin `DSAttributes` to register `dragonsurvival:flight_level` via the existing `REGISTRY` DeferredRegister.

- Attach to `EntityType.PLAYER` via `EntityAttributeModificationEvent`
- Syncable via `setSyncable(true)`

### 2. Wings Ability Rework (3 Levels)

Current wings abilities (`cave_wings`, `forest_wings`, `sea_wings`) have 2 levels with `condition_based` upgrade (`flight_was_granted`). Reworked to 3 levels with `dragon_growth` upgrade.

```
Level 0 → blocked (flight_was_granted = false via usage_blocked)
Level 1 → hasFlight=true, FLIGHT_LEVEL=0 (can fly, no hover)
Level 2 → hasFlight=true, FLIGHT_LEVEL=1 (can fly + stable hover)
```

**Upgrade rules:**
- `upgrade_type`: `dragon_growth`
- `maximum_level`: `2`
- `growth_requirement`: lookup `[0, 0, 60]` — Level 2 requires growth >= 60 (adult dragon)
- `usage_blocked` carries `flight_was_granted: false` (moved from old ConditionUpgrade)
- Existing `marked_by_ender_dragon` condition merged into `usage_blocked` via `minecraft:any_of`

**Entity effects per level:**
- `FlightEffect` (`level_requirement: 1`) — grants `hasFlight` at levels 1+
- `ModifierEffect` with `dragonsurvival:flight_level` attribute — `+0` at level 1, `+1` at level 2 (via `lookup [0, 0, 1]`)

### 3. Stable Hover Replacement — Mixin into ClientFlightHandler.flightControl()

Two injection points in `ClientFlightHandler.flightControl()` replace `ServerFlightHandler.stableHover`:

| Location | Old | New |
|---|---|---|
| Line ~370 (hover gravity adjust) | `ServerFlightHandler.stableHover && ...` | `player.getAttributeValue(FLIGHT_LEVEL) >= 1 && ...` |
| Line ~386 (gravity multiplier branch) | `!ServerFlightHandler.stableHover` | `player.getAttributeValue(FLIGHT_LEVEL) < 1` |

### 4. Existing Mixin Update — ClientFlightHandlerMixin.fixStableHoverDrift()

Line 58 of the existing BeLoong-Core mixin references `ServerFlightHandler.stableHover`. Replace:

```java
// old
boolean shouldHover = ServerFlightHandler.stableHover && ...

// new
double flightLevel = player.getAttributeValue(DSAttributes.FLIGHT_LEVEL);
boolean shouldHover = flightLevel >= 1 && ...
```

`DSAttributes.FLIGHT_LEVEL` must be obtained via `BuiltInRegistries.ATTRIBUTE.get(ResourceLocation.fromNamespaceAndPath("dragonsurvival", "flight_level"))` since the Holder is registered via Mixin and not accessible as a static field import.

### 5. Flight Gate — Mixin into ToggleFlight.handleServer()

In `ToggleFlight.handleServer()`, before the `!flight.areWingsSpread` branch (where wings are about to spread), add:

```java
if (!flight.areWingsSpread) {
    // NEW: flight level gate
    if (getFlightLevel(player) < 0) {
        return Result.FLIGHT_BANNED;
    }
    // existing checks: hunger, trapped, broken_wings, ability unlocked...
}
```

New `Result` enum value: `FLIGHT_BANNED`.

### 6. "禁空" (Flight Ban) Effect — BeLoong-Core

Register `beloong:flight_ban` mob effect:

- Category: `HARMFUL`, color: `0x8B0000` (dark red)
- Attribute modifier: `dragonsurvival:flight_level`, amount `-1`, operation `ADD_VALUE`
- Vanilla automatically scales by `(amplifier + 1)`: I → -1, II → -2, III → -3
- Look up the FLIGHT_LEVEL attribute via `BuiltInRegistries.ATTRIBUTE` since it's registered via Mixin

### 7. Sample Datapack File

`src/main/resources/data/dragonsurvival/dragonsurvival/dragon_ability/cave_wings.json` — the reworked wings ability serving as reference and test fixture.

## Data Flow

```
DragonGrowthUpgrade (growth >= 60)
        │
        ▼
  DragonAbility level = 2
        │
        ├──▶ FlightEffect.apply()
        │         └──▶ FlightData.hasFlight = true
        │
        └──▶ ModifierEffect.apply()
                  └──▶ FLIGHT_LEVEL attribute = 1
                           │
                  "禁空 I"施加
                           │
                           ▼
                  FLIGHT_LEVEL effective = 0
                           │
                  "禁空 II"施加
                           │
                           ▼
                  FLIGHT_LEVEL effective = -1
                           │
                           ▼
                  ToggleFlight Mixin 拒绝展翅
```

## Files

| File | Action | Module |
|---|---|---|
| `mixin/DSAttributesMixin.java` | Create | BeLoong-Core |
| `mixin/ClientFlightHandlerStableHoverMixin.java` | Create | BeLoong-Core |
| `mixin/ClientFlightHandlerMixin.java` | Modify | BeLoong-Core |
| `mixin/ToggleFlightMixin.java` | Create | BeLoong-Core |
| `registry/ModEffects.java` | Create | BeLoong-Core |
| `registry/ModAttributes.java` | Create (lookup helper) | BeLoong-Core |
| `resources/beloong.mixins.json` | Modify | BeLoong-Core |
| `resources/data/dragonsurvival/.../cave_wings.json` | Create | BeLoong-Core |
| `resources/assets/beloong/lang/zh_cn.json` | Modify | BeLoong-Core |
| `resources/assets/beloong/lang/en_us.json` | Modify | BeLoong-Core |

## Verification

1. Dragon without wings ability — cannot spread wings regardless of FLIGHT_LEVEL
2. Dragon with wings level 1 (young) — can fly, drifts when not actively maneuvering (no stable hover)
3. Dragon with wings level 2 (adult, growth >= 60) — can fly + stable hover, no drift when idle
4. Adult dragon + 禁空 I — can fly, no stable hover (FLIGHT_LEVEL drops from 1 to 0)
5. Adult dragon + 禁空 II — cannot fly (FLIGHT_LEVEL drops from 1 to -1)
6. Young dragon + 禁空 I — cannot fly (FLIGHT_LEVEL drops from 0 to -1)
7. broken_wings and trapped effects — behavior unchanged
8. Existing `fixStableHoverDrift` — still works, driven by FLIGHT_LEVEL >= 1 instead of `stableHover` config
