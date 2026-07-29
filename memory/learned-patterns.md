# Learned Patterns

<!-- Updated by self-learning and brainstorming skills -->
<!-- Last updated: 2026-07-29 -->

## Data-Driven Gameplay

Gameplay content that belongs to the BeLoong modpack should prefer Codec-backed
server resource reload listeners when pack authors may need to override or add
entries. Existing examples include treasure growth and structure effects. The
approved Beloong Water redesign follows the same pattern for dimension cuboids.

## Compatibility

When behavior can be expressed as contextual semantics over a vanilla object,
prefer retaining the vanilla registry object instead of introducing a parallel
fluid. For Beloong Water, exact `Fluids.WATER` and `Fluids.FLOWING_WATER` checks
inside configured regions preserve waterlogging and third-party interoperability.

## Migration Preference

For the 2026-07-29 Beloong Water redesign, the user explicitly chose complete
removal of the legacy custom fluid, liquid block, bucket, rendering, and assets,
with no missing-mapping alias or automatic world conversion.
