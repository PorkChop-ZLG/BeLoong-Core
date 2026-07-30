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
8. With all protection categories enabled, verify water/lava buckets and solid
   buckets cannot place or collect their contents in Loong Palace. Confirm the
   held item immediately returns to the server-authoritative state without a
   ghost fluid or container change.
9. Verify paintings, item frames, and glow item frames cannot be placed. For an
   existing item frame, verify inserting an item, rotating it, removing its
   contents, and attacking the frame are all denied. Attacking living entities
   must remain allowed.
10. Verify both inserting a valid plant into an empty flower pot and removing a
    plant from a filled pot are denied, with no ghost block or inventory state.
11. Disable `protectFluidContainerEdits`, `protectHangingEntityEdits`, and
    `protectFlowerPotEdits` one at a time. Confirm only the selected lifecycle
    becomes editable. Then disable `environment_protection.enabled` and confirm
    all three become editable while direct break/place protection remains.
12. Give a real player FTB bypass permission and repeat the bucket, flower-pot,
    painting, and item-frame scenarios; every action must be allowed. Repeat a
    representative action with a FakePlayer and confirm it remains denied.
13. Switch the client language between English and Simplified Chinese and open
    the NeoForge server-config screen. Verify the Loong Palace and environment
    groups, every protection option, and every tooltip are localized. Inspect
    the generated server TOML and verify its Loong Palace comments contain both
    languages.
