# Design: Serene & Mana Loss Potion Effects

**Date:** 2026-06-23
**Status:** approved

## Overview

Add two potion effects (`MobEffect`) to BeLoong Core that interact with Dragon Survival's mana system:

- **Serene** (气定神闲): Modifies `dragonsurvival:mana_regeneration` attribute, +0.001 base per level.
- **Mana Loss** (魔力流逝): Drains mana each tick at `0.025 × (level + 1)`, only for dragon players.

## Architecture

### Registration

Follow the existing `ModMobEffects.java` pattern using `DeferredRegister<MobEffect>`.

### Serene — Pure Attribute Modifier

A standard `MobEffect` (BENEFICIAL) with `.addAttributeModifier()` targeting `dragonsurvival:mana_regeneration`. No custom subclass needed.

- **Attribute lookup**: Use `BuiltInRegistries.ATTRIBUTE.get(ResourceLocation.fromNamespaceAndPath("dragonsurvival", "mana_regeneration"))` — same pattern as `FLIGHT_BAN` which resolves `dragonsurvival:flight_level`. The DS attribute is registered before BeLoong constructs, so it will be found.
- **Operation**: `ADD_VALUE`, amount = `0.001`. Vanilla scales this by `(amplifier + 1)`, producing 0.001 at level 0, 0.002 at level I, etc.
- **Color**: Light blue (`0x87CEEB`).

### Mana Loss — Tick Handler

A standard `MobEffect` (HARMFUL) registration plus a `ManaLossHandler` event handler class.

The handler subscribes to `PlayerTickEvent.Post`:

1. Server-side only (`player.level().isClientSide` guard).
2. `DragonStateProvider.isDragon(player)` — skip non-dragons.
3. `player.hasEffect(ModMobEffects.MANA_LOSS)` — check for the effect.
4. Read amplifier, compute deduction = `0.025f × (amplifier + 1)`.
5. `ManaHandler.consumeMana(player, deduction)` — respects DS mechanics (Source of Magic, creative mode bypass).
6. Send `SyncMana` packet to client so the mana bar updates.

**Color**: Dark purple (`0x8B008B`). **Tick interval**: every tick (no periodic batching).

## Files Changed

| File | Action |
|---|---|
| `registry/ModMobEffects.java` | Add `SERENE` and `MANA_LOSS` effect registrations |
| `registry/ManaLossHandler.java` | **New.** Per-tick mana deduction logic |
| `BeLoongCore.java` | Register `ManaLossHandler` on `NeoForge.EVENT_BUS` |
| `resources/assets/beloong/lang/zh_cn.json` | Add `effect.beloong.serene`, `effect.beloong.mana_loss` |
| `resources/assets/beloong/lang/en_us.json` | Same, English |

## Data Flow

```
PlayerTickEvent.Post
  → ManaLossHandler.onPlayerTick
    → DragonStateProvider.isDragon?   ─ no → return
    → hasEffect(MANA_LOSS)?           ─ no → return
    → amplifier = getEffect(MANA_LOSS).getAmplifier()
    → deduction = 0.025 * (amplifier + 1)
    → ManaHandler.consumeMana(player, deduction)
    → SyncMana packet → client HUD
```

## Dependencies

- Dragon Survival (already a hard dependency in `build.gradle`)
- Uses: `DragonStateProvider`, `MagicData`, `ManaHandler`, `SyncMana`, `DSAttributes`

## Edge Cases

- **Non-dragon entities**: Mana Loss is a no-op (ManaHandler guard + explicit `isDragon` check).
- **Source of Magic effect**: Mana Loss respects it via `ManaHandler.consumeMana()` — infinite mana immunity.
- **Creative mode**: Respected via `ManaHandler.consumeMana()` → `hasInfiniteMaterials()` check.
- **Mana at 0**: `consumeMana` calls `setCurrentMana(Math.max(0, ...))`, won't go negative.
- **Dispel/removal**: When the effect is removed (milk, command, expiry), the tick handler simply stops seeing the effect — no cleanup needed.
- **`mana_regeneration` attribute not found**: Register the effect without the modifier (graceful degradation, same as `FLIGHT_BAN` pattern).

## Localization Keys

| Key | zh_cn | en_us |
|---|---|---|
| `effect.beloong.serene` | 气定神闲 | Serene |
| `effect.beloong.serene.description` | 提升法力回复速度。 | Increases mana regeneration speed. |
| `effect.beloong.mana_loss` | 魔力流逝 | Mana Loss |
| `effect.beloong.mana_loss.description` | 持续扣除法力值。 | Continuously drains mana. |
