# Loong Palace Environment Protection Implementation Plan

**Goal:** Add configurable NeoForge event protection and shared Loong Palace
dimension semantics without adding Mixins.

## Task 1: Define server configuration

**Modify:** `src/main/java/com/zonlong/beloong/Config.java`

- Add a master environment-protection switch.
- Add switches for explosion, non-player placement, living destruction, mob
  griefing, farmland trampling, tool modification, crop growth, feature growth,
  and portal creation.
- Default every switch to `true`.

## Task 2: Add shared protection policy

**Create:**
`src/main/java/com/zonlong/beloong/compat/ftbchunks/LoongPalaceProtectionPolicy.java`

**Create:**
`src/main/java/com/zonlong/beloong/compat/ftbchunks/FTBChunksProtectionBridge.java`

- Centralize the dimension key and bypass lookup.
- Fail closed when bypass status is unavailable.
- Never grant bypass to FakePlayer instances.
- Keep FTB API descriptors out of the always-loaded claim helper.

## Task 3: Extend protection consumers

**Modify:**
`src/main/java/com/zonlong/beloong/compat/ftbchunks/LoongPalaceProtectionHandler.java`

**Modify:** `src/main/java/com/zonlong/beloong/util/ClaimProtectionHelper.java`

- Register configurable NeoForge event handlers.
- Preserve explosion entity damage for explosions reaching the NeoForge event,
  while retaining the existing Dragon Survival full-effect cancellation.
- Allow piston/fluid behavior.
- Make helper callers treat Loong Palace as protected, except real bypass players.
- Keep existing claim behavior unchanged outside the dimension.

## Task 4: Verify

- Run the full build with the locally installed Gradle 9.2.1.
- Run `git diff --check` and inspect the output JAR.
- Provide a manual gameplay test matrix.

## Manual Gameplay Matrix

Before testing, ensure `beloong:loong_palace` is not listed in FTB Chunks'
`no_wilderness_dimensions`; that built-in mode would also block interaction.

1. With every option enabled, verify normal players and fake players cannot
   break or place blocks in Loong Palace, while block interaction and attacks
   against living entities still work.
2. Verify an FTB bypass player can perform attributable player, tool, helper,
   bone-meal, and explosion block changes.
3. Verify TNT and other explosions reaching `ExplosionEvent.Detonate` leave
   blocks intact but still damage entities. Dragon Survival's protected
   explosion skill is the documented exception and remains fully cancelled.
4. Exercise non-player placement, living block destruction, mob griefing,
   farmland trampling, axe/hoe/shovel modification, random crop growth, bone
   meal, sapling feature growth, and portal creation.
5. Disable each category separately and verify only that category is restored.
   Disable `enabled` and verify all configurable environment categories are
   restored, while direct player/fake-player and helper protection remains.
6. Verify piston movement, natural fluid flow/reactions, and block interaction
   remain available with protection enabled. Network-magnet fluid extraction
   remains protected through `ClaimProtectionHelper` by design.
7. Repeat representative cases outside Loong Palace and verify existing FTB
   claim behavior is unchanged.
