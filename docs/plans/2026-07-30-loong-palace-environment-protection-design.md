# Loong Palace Environment Protection Design

**Status:** Approved

## Goal

Strengthen `beloong:loong_palace` protection without adding Mixins. Protect
block changes exposed through selected NeoForge events and make existing
`ClaimProtectionHelper` callers treat the dimension as protected.

## Policy

- A real `ServerPlayer` with FTB Chunks bypass may make attributable changes.
- Fake players, entities, machines, natural processes, and unattributed sources
  are blocked when their NeoForge event category is enabled.
- Piston movement and all fluid mechanics remain allowed.
- Block interaction and attacks against living entities remain allowed.
- Existing FTB claim checks keep their current behavior outside Loong Palace.
- Direct low-level block edits that emit no NeoForge event and do not call
  `ClaimProtectionHelper` remain outside the achievable no-Mixin boundary.

## Event Protection

The existing FTB-only Loong Palace handler protects explosion block damage,
non-player block placement, living-entity block destruction, mob griefing,
farmland trampling, tool block modification, crop growth and bone meal use,
feature growth, and portal creation. Explosion entity damage is preserved.

The existing Dragon Survival `ExplodeBlockEffectMixin` remains unchanged. Its
protected path cancels the complete skill effect, including entity damage. The
entity-damage preservation above applies to other explosions that reach
`ExplosionEvent.Detonate`.

## Configuration

A server-config master switch and one switch per event category control only
the new environment protection. Every switch defaults to enabled. Direct
player break/place protection, claim rejection, and `ClaimProtectionHelper`
dimension handling are independent of the master switch.

## Verification

No automated tests are added by request. Verification consists of a full local
Gradle build, artifact/static inspection, and a documented manual gameplay
matrix.
