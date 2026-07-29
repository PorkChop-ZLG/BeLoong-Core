# Decisions Log

## 2026-07-29: Replace Custom Beloong Water With Data-Driven Vanilla-Water Regions

**Decision:** Remove the custom Beloong Water fluid, block, bucket, and render
integration. Treat exact vanilla water as Beloong Water only inside data-pack
cuboids indexed by dimension.

**Context:** The custom fluid cannot participate reliably in waterlogging and
has poor compatibility with other mods. The approved initial region is in
`beloong:loong_palace`, from `(-54, 68, -312)` through `(8, 77, -272)`, with
inclusive boundaries.

**Rationale:** Region semantics preserve the complete vanilla water ecosystem,
including waterlogged blocks, while keeping the behavior server-authoritative,
reloadable, and extensible to multiple pools.

**Alternatives considered:** A custom `beloongwater` property on the vanilla
water block state; one fixed region in the NeoForge server configuration. The
property cannot represent waterlogged water and is fragile across fluid-state
recreation. The fixed configuration is simpler but less aligned with this
project's existing data-driven content patterns.

