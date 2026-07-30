# Loong Palace Edit Interactions Design

**Date:** 2026-07-30
**Status:** Approved
**Approach:** Targeted pre-action protection for complete mutable lifecycles

## Problem Statement

Loong Palace block protection does not currently see several player actions
that change world state without firing `BlockEvent.EntityPlaceEvent`:

- Buckets place or collect fluids directly.
- Hanging entity items add paintings and item frames as entities.
- Flower pots replace their own block state during insertion and removal.

FTB Chunks 2101.1.8 prevents these actions at input time through right-click,
bucket, entity-interaction, and non-living attack protection. Loong Palace
cannot use FTB's broad interaction policy because ordinary block interaction
must remain available.

## Architecture

Extend `LoongPalaceProtectionHandler` with targeted, server-side NeoForge
pre-action listeners. Classify only interactions that mutate fluids, flower
pots, or hanging decorations. Reuse `LoongPalaceProtectionPolicy` for the
dimension, FakePlayer, FTB manager, and bypass decisions.

No Mixins are added. Existing player break/place and environment protection
remain unchanged.

## Components

### Player Input Protection

- `PlayerInteractEvent.RightClickBlock` denies protected bucket use, hanging
  entity placement, and flower-pot changes.
- `PlayerInteractEvent.RightClickItem` is the fallback for protected bucket
  items that reach item use without a block interaction.
- `PlayerInteractEvent.EntityInteractSpecific` and `EntityInteract` deny item
  frame content insertion, removal, and rotation.
- `AttackEntityEvent` denies attacks against `HangingEntity` instances only.
  Attacks against living entities remain allowed.

Protected vanilla type families are `BucketItem`, `SolidBucketItem`,
`HangingEntityItem`, `FlowerPotBlock`, and `HangingEntity`. Compatible modded
subclasses inherit the same protection.

### Client Correction

Denied events return `InteractionResult.FAIL`. Bucket and hanging-item denial
resynchronizes the used hand. Flower-pot and hanging-entity content denial
resynchronizes the player inventory so client prediction cannot leave ghost
items. This mirrors the explicit held-item correction used by FTB Chunks.

### Configuration

Add three server values under `loong_palace.environment_protection`, all
defaulting to `true` and gated by the existing `enabled` master switch:

- `protectFluidContainerEdits`
- `protectHangingEntityEdits`
- `protectFlowerPotEdits`

All existing and new Loong Palace protection values receive explicit
`beloong.configuration.*` translation keys. English and Simplified Chinese
labels/tooltips are added to `en_us.json` and `zh_cn.json`. Raw TOML comments
are bilingual because TOML comments cannot switch with the client locale.

## Data Flow

1. A supported NeoForge player event fires.
2. The handler ignores client-side and non-Loong-Palace events.
3. The master and category configuration values are checked.
4. A real FTB bypass player is allowed; FakePlayer and unverifiable bypass
   state remain denied.
5. The item, block, or target entity is classified.
6. A protected action is canceled with `FAIL`, the relevant inventory state is
   synchronized, and the existing FTB action-prevented message is displayed.

## Error Handling

- FTB manager unavailable: fail closed, matching current Loong Palace policy.
- Client prediction: correct the held slot or inventory after server denial.
- Specific entity interaction: return `FAIL` so it cannot fall through to the
  general entity interaction path.
- Duplicate event coverage: cancellation is idempotent and all mutation occurs
  only after a successful guard.

## Non-Goals

- Do not block chests, doors, buttons, beds, crafting stations, or other
  ordinary block interaction.
- Do not block attacks against living entities.
- Do not block pistons or natural fluid propagation.
- Do not promise interception of custom items/entities that neither inherit a
  protected vanilla type nor publish the supported NeoForge events.
- Do not add automated tests, per the approved manual-test workflow.

## Verification

Run a full offline build with the locally installed Gradle, validate both JSON
language files, inspect the output JAR, and provide a manual gameplay matrix
covering default, bypass, category-off, master-off, and outside-dimension
behavior.
