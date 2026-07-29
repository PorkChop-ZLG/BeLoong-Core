# Vanilla Beloong Water Regions Design

**Date:** 2026-07-29
**Status:** Approved
**Approach:** Data-driven regions that give vanilla water Beloong Water behavior

## Problem Statement

The custom Beloong Water fluid does not participate reliably in vanilla
waterlogging and has poor interoperability with other mods. Replace it with
ordinary vanilla water whose altar-opening behavior is enabled only inside
data-defined regions.

## Architecture

The server loads cuboid region definitions from data packs and indexes them by
dimension. A player is touching Beloong Water only when the player's bounding
box, a configured region, and the actual volume of a vanilla water fluid state
all intersect. The existing entry tracker, per-player trigger cooldown, and
Dragon Survival altar-opening behavior remain in place.

The custom fluid, liquid block, bucket, rendering hooks, models, tags, and
translations are removed without a compatibility registration or automatic
world migration.

## Data Format

Each JSON file under
`data/<namespace>/beloong/beloong_water_regions/<name>.json` defines one
inclusive cuboid:

```json
{
  "dimension": "beloong:loong_palace",
  "min": {
    "x": -54,
    "y": 68,
    "z": -312
  },
  "max": {
    "x": 8,
    "y": 77,
    "z": -272
  }
}
```

Coordinate order is normalized while loading. Multiple files may define
multiple regions, including regions in other dimensions. Data-pack resource
overrides use normal resource-pack precedence.

## Components

### `BeloongWaterRegionDefinition`

Codec-facing representation containing the dimension resource location and two
integer block positions.

### `BeloongWaterRegion`

Normalized runtime representation. It exposes inclusive block bounds and
bounding-box intersection operations.

### `BeloongWaterRegionLoader`

A `SimpleJsonResourceReloadListener` for
`beloong/beloong_water_regions`. It parses files independently, skips malformed
files with resource-specific logs, builds an immutable dimension index, and
atomically replaces the previous cache after a reload.

### `BeloongWaterContactDetector`

Server-side predicate that:

1. Gets candidate regions for the player's dimension.
2. Rejects regions that do not intersect the player's bounding box.
3. Scans only block positions covered by the player/region intersection.
4. Accepts only `Fluids.WATER` and `Fluids.FLOWING_WATER`.
5. Uses the fluid state's actual height when testing the final intersection.

Reading `Level#getFluidState` allows waterlogged blocks to participate without
special handling. Fluid tags are intentionally not used, so third-party fluids
tagged as water do not become Beloong Water.

### `BeloongWaterContactHandler`

The handler delegates contact detection to the detector, then retains the
existing entry-edge behavior, successful-trigger cooldown, Dragon Survival
altar cooldown, and logout/respawn/dimension-change cleanup.

## Data Flow

```text
data-pack JSON
    -> resource reload
    -> immutable dimension-to-regions index
    -> player tick
    -> region/player intersection
    -> exact vanilla water volume intersection
    -> entry edge and cooldown checks
    -> Dragon Survival altar payload
```

Reloading removes deleted regions and activates new regions on the next player
tick. A player already standing in water in a newly loaded region is treated as
a new entry.

## Error Handling

- Invalid JSON, missing fields, non-integer coordinates, and malformed
  dimension IDs invalidate only their own file and are logged.
- A syntactically valid but unavailable dimension remains inert.
- An empty or fully invalid data set produces no active regions, failing closed.
- Overlapping regions are allowed and still produce one boolean contact result.
- The cache is replaced only after the new index has been fully built.

## Removal And Migration

Remove all registrations and assets for:

- `beloong:beloong_water` fluid, fluid type, and liquid block
- `beloong:flowing_beloong_water`
- `beloong:beloong_water_bucket`
- Custom fluid tint, fog, render-layer, and bucket-color handling
- The custom entries in `#minecraft:water`

Do not provide missing-mapping aliases or automatic conversion. Existing custom
fluid blocks and bucket items may be lost when an old world is opened. The
`beloongWaterCooldown` server configuration and its category translation remain
unchanged.

## Testing Strategy

### Unit Tests

- Codec accepts the documented schema.
- Coordinate order normalizes correctly.
- Inclusive boundary and bounding-box intersections are correct.
- Loader indexing groups regions by dimension.
- One malformed resource does not discard valid resources.

### NeoForge GameTests

- Source water, flowing water, and waterlogged blocks inside a region match.
- Dry blocks, outside-region water, other-dimension water, and third-party
  water-tagged fluids do not match.
- Contact entry, exit, re-entry, cooldown, and player isolation remain correct.

The GameTest server is configured using the NeoForge 1.21.1
`gameTestServer` run and the `beloong` enabled test namespace.

## Performance

Regions are indexed by dimension. Each player tick first performs cheap region
bounding-box tests and then scans only the few block positions intersecting the
player. Detection exits on the first valid water intersection. No full-region
or full-pool scan is performed.

## Decisions Made

- Use data-driven cuboids rather than a vanilla water block-state property.
- Use three-dimensional inclusive bounds rather than an X/Z-only rectangle.
- Match exact vanilla water types, not the water fluid tag.
- Remove all legacy custom-fluid content without migration.
- Keep the existing trigger cooldown configuration and entry semantics.

## Non-Goals

- Region-specific water tint or underwater fog.
- Arbitrary non-cuboid region shapes.
- Client-side region synchronization.
- Automatic conversion of old custom fluid blocks or items.

