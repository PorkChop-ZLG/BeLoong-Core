# Loong Palace Edit Interactions Implementation Plan

**Goal:** Prevent player interactions that edit fluids, flower pots, paintings,
and item frames while preserving ordinary interaction and living-entity
attacks.

**Architecture:** Add targeted server-side NeoForge input listeners to the
existing FTB-only Loong Palace handler. Reuse the shared policy and existing
environment master switch, and correct client prediction after denial.

**Testing constraint:** No automated test source will be added. Compilation,
resource validation, artifact inspection, and a manual gameplay matrix replace
TDD steps by explicit user request.

## Task 1: Add configuration and localization

**Files:**

- Modify: `src/main/java/com/zonlong/beloong/Config.java`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`

**Steps:**

1. Add BooleanValue fields for fluid-container, hanging-entity, and flower-pot
   edit protection.
2. Define all three under `loong_palace.environment_protection`, default true.
3. Add explicit translation keys to every existing and new Loong Palace
   protection value.
4. Replace the Loong Palace protection comments with bilingual English/Chinese
   TOML comments.
5. Add section, option, and tooltip entries to both language JSON files.
6. Parse both JSON files and compile Java to validate config declarations.

**Verification:**

```powershell
Get-Content -Raw src/main/resources/assets/beloong/lang/en_us.json | ConvertFrom-Json | Out-Null
Get-Content -Raw src/main/resources/assets/beloong/lang/zh_cn.json | ConvertFrom-Json | Out-Null
& 'C:\Users\D_Ink\.gradle\wrapper\dists\gradle-9.2.1-bin\2t0n5ozlw9xmuyvbp7dnzaxug\gradle-9.2.1\bin\gradle.bat' --no-daemon --offline --console=plain compileJava
```

## Task 2: Protect bucket and block-targeted edits

**Files:**

- Modify: `src/main/java/com/zonlong/beloong/compat/ftbchunks/LoongPalaceProtectionHandler.java`

**Steps:**

1. Add a `RightClickBlock` listener at `HIGHEST` priority.
2. Deny `BucketItem` and `SolidBucketItem` use when fluid-edit protection is
   active.
3. Deny `HangingEntityItem` use when hanging-entity protection is active.
4. Deny interaction with every `FlowerPotBlock` when flower-pot protection is
   active.
5. Add a `RightClickItem` bucket fallback.
6. Return `FAIL`, notify the player, and synchronize the used hand or inventory
   according to the denied action.

**Verification:**

```powershell
& 'C:\Users\D_Ink\.gradle\wrapper\dists\gradle-9.2.1-bin\2t0n5ozlw9xmuyvbp7dnzaxug\gradle-9.2.1\bin\gradle.bat' --no-daemon --offline --console=plain compileJava
```

## Task 3: Protect the hanging-entity lifecycle

**Files:**

- Modify: `src/main/java/com/zonlong/beloong/compat/ftbchunks/LoongPalaceProtectionHandler.java`

**Steps:**

1. Add `EntityInteractSpecific` and `EntityInteract` listeners for
   `HangingEntity` targets.
2. Cancel with `FAIL` before item-frame content can be inserted, removed, or
   rotated.
3. Add an `AttackEntityEvent` listener that denies only `HangingEntity`
   targets.
4. Keep all living-entity attack behavior unchanged.
5. Centralize held-slot/full-inventory synchronization so duplicate event paths
   cannot drift in behavior.

**Verification:**

```powershell
& 'C:\Users\D_Ink\.gradle\wrapper\dists\gradle-9.2.1-bin\2t0n5ozlw9xmuyvbp7dnzaxug\gradle-9.2.1\bin\gradle.bat' --no-daemon --offline --console=plain compileJava
```

## Task 4: Perform final static and artifact verification

**Files:**

- Update: `docs/plans/2026-07-30-loong-palace-environment-protection.md`

**Steps:**

1. Extend the manual matrix with bucket, flower-pot, painting, and item-frame
   lifecycle scenarios.
2. Run the full offline build with local Gradle 9.2.1.
3. Run `git diff --check`.
4. Confirm no Mixin files changed.
5. Inspect the output JAR for the handler and both language resources.
6. Hand off the built JAR and manual test instructions.

**Verification:**

```powershell
& 'C:\Users\D_Ink\.gradle\wrapper\dists\gradle-9.2.1-bin\2t0n5ozlw9xmuyvbp7dnzaxug\gradle-9.2.1\bin\gradle.bat' --no-daemon --offline --console=plain build
git diff --check
git diff --name-only -- src/main/java/com/zonlong/beloong/mixin
& 'D:\Java\jdk-21.0.11\bin\jar.exe' tf build/libs/beloong-0.6.4.jar
```

## Manual Acceptance Matrix

1. Default: deny fluid placement/collection, fluid-container interactions,
   hanging placement/content changes/destruction, and flower-pot insertion and
   removal.
2. Prediction: no ghost fluid, block state, entity, or inventory changes remain
   after denial.
3. Allowed behavior: containers, doors, buttons, living attacks, pistons, and
   natural fluid propagation still work.
4. Bypass: a real FTB bypass player can perform every protected action.
5. Fake player: protected actions remain denied.
6. Category switches: disabling one switch restores only its category.
7. Master switch: disabling `enabled` restores all three categories while
   existing direct break/place and helper protection remain active.
8. Outside dimension: representative actions behave exactly as before.
9. Localization: the NeoForge config screen has English and Simplified Chinese
   section labels, option labels, and tooltips; raw TOML comments contain both
   languages.
