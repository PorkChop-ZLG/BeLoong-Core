# Vanilla Beloong Water Regions Implementation Plan

**Goal:** Replace the custom Beloong Water content with data-driven regions in
which ordinary vanilla water opens the Dragon Survival altar.

**Architecture:** Load inclusive cuboids from data-pack JSON, index them by
dimension, and perform a server-side intersection test between the player,
region, and exact vanilla water volume. Reuse the current contact tracker,
cooldown, and altar integration.

**Approach:** Implement the approved data-driven design and completely remove
the legacy custom fluid, block, bucket, and client rendering code.

---

### Task 1: Add Unit And GameTest Infrastructure

**Files:**
- Modify: `build.gradle`
- Create: `src/test/java/com/zonlong/beloong/fluid/BeloongWaterRegionTest.java`
- Create: `src/main/java/com/zonlong/beloong/gametest/BeloongWaterGameTests.java`
- Create: `src/main/resources/data/beloong/structure/beloong_water_game_test.nbt`

**Steps:**
1. Add `org.junit.jupiter:junit-jupiter:5.11.4` to the test configuration and
   enable `useJUnitPlatform()`.
2. Add the ModDevGradle `gameTestServer` run with
   `neoforge.enabledGameTestNamespaces=beloong`.
3. Add a minimal empty GameTest structure fixture and a required smoke test.
4. Run the test task and GameTest server to confirm the infrastructure is
   discovered before adding behavior tests.
5. Commit the test-infrastructure change.

**Verification:**

```powershell
.\gradlew.bat test
.\gradlew.bat runGameTestServer
```

### Task 2: Define And Test The Region Data Model

**Files:**
- Create: `src/main/java/com/zonlong/beloong/fluid/BeloongWaterRegionDefinition.java`
- Create: `src/main/java/com/zonlong/beloong/fluid/BeloongWaterRegion.java`
- Modify: `src/test/java/com/zonlong/beloong/fluid/BeloongWaterRegionTest.java`

**Steps:**
1. Write failing tests for Codec parsing, reversed-coordinate normalization,
   inclusive block boundaries, and AABB intersection at each face.
2. Run the focused test and confirm the expected failures.
3. Implement the Codec-facing definition and immutable normalized region.
4. Run the focused test until all cases pass, then remove duplication without
   changing behavior.
5. Commit the region model and tests.

**Verification:**

```powershell
.\gradlew.bat test --tests "com.zonlong.beloong.fluid.BeloongWaterRegionTest"
```

### Task 3: Implement The Reloadable Region Index

**Files:**
- Create: `src/main/java/com/zonlong/beloong/fluid/BeloongWaterRegionLoader.java`
- Create: `src/test/java/com/zonlong/beloong/fluid/BeloongWaterRegionLoaderTest.java`
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`

**Steps:**
1. Write failing tests for grouping by dimension, merging multiple resources,
   isolating malformed resources, overlapping regions, and immutable results.
2. Confirm the tests fail before the loader exists.
3. Implement the JSON reload listener and a package-visible pure index-building
   seam used by the tests.
4. Register the listener in `BeLoongCore#addServerReloadListeners`.
5. Run the loader tests and commit the reload/index change.

**Verification:**

```powershell
.\gradlew.bat test --tests "com.zonlong.beloong.fluid.BeloongWaterRegionLoaderTest"
```

### Task 4: Detect Vanilla Water Contact Inside Regions

**Files:**
- Create: `src/main/java/com/zonlong/beloong/fluid/BeloongWaterContactDetector.java`
- Modify: `src/main/java/com/zonlong/beloong/gametest/BeloongWaterGameTests.java`

**Steps:**
1. Add failing GameTests for source water, flowing water, waterlogged stairs,
   dry blocks, water outside the region, and water in another dimension.
2. Add focused identity tests proving that only `Fluids.WATER` and
   `Fluids.FLOWING_WATER` pass and that a non-water fluid such as lava fails;
   the detector must not consult `FluidTags.WATER`.
3. Run the GameTest server and confirm the new behavior tests fail.
4. Implement dimension lookup, player/region intersection, bounded block
   scanning, exact vanilla fluid matching, and fluid-height intersection.
5. Run all GameTests and commit the detector.

**Verification:**

```powershell
.\gradlew.bat runGameTestServer
```

### Task 5: Switch The Contact Handler To Region Detection

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/fluid/BeloongWaterContactHandler.java`
- Modify: `src/main/java/com/zonlong/beloong/gametest/BeloongWaterGameTests.java`
- Create: `src/test/java/com/zonlong/beloong/fluid/BeloongWaterTriggerCooldownTest.java`
- Create: `src/test/java/com/zonlong/beloong/fluid/BeloongWaterContactTrackerTest.java`

**Steps:**
1. Add failing tests for single-entry triggering, exit/re-entry, cooldown timing,
   independent players, logout cleanup, and dimension-change cleanup.
2. Confirm the focused tests fail against the current custom-FluidType check.
3. Inject or call the region detector from the handler while preserving the
   existing altar cooldown and successful-send semantics.
4. Run the focused unit tests and all GameTests.
5. Commit the handler integration.

**Verification:**

```powershell
.\gradlew.bat test --tests "com.zonlong.beloong.fluid.*"
.\gradlew.bat runGameTestServer
```

### Task 6: Remove Legacy Custom Fluid Registrations And Client Code

**Files:**
- Delete: `src/main/java/com/zonlong/beloong/fluid/BeloongWaterFluid.java`
- Delete: `src/main/java/com/zonlong/beloong/registry/ModFluids.java`
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCoreClient.java`
- Modify: `src/main/java/com/zonlong/beloong/registry/ModBlocks.java`
- Modify: `src/main/java/com/zonlong/beloong/item/ModItems.java`
- Modify: `src/main/java/com/zonlong/beloong/item/ModCreativeModeTabs.java`

**Steps:**
1. Add a source/reference audit that fails while legacy registry symbols remain.
2. Remove the fluid and fluid-type registrations, liquid block, bucket item,
   creative-tab entry, and main entry-point registration.
3. Remove client tint, fog, render-layer, and bucket-color code while retaining
   unrelated portal rendering.
4. Compile and run the existing plus new tests.
5. Commit the Java-side legacy-content removal.

**Verification:**

```powershell
rg -n "ModFluids|BELOONG_WATER_BUCKET|BeloongWaterFluid" src/main/java
.\gradlew.bat test
.\gradlew.bat compileJava
```

The `rg` command must return no matches.

### Task 7: Replace Legacy Resources With The Default Region

**Files:**
- Delete: `src/main/resources/assets/beloong/blockstates/beloong_water.json`
- Delete: `src/main/resources/assets/beloong/models/item/beloong_water_bucket.json`
- Delete: `src/main/resources/data/minecraft/tags/fluid/water.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Create: `src/main/resources/data/beloong/beloong/beloong_water_regions/loong_palace_pool.json`

**Steps:**
1. Add the approved `beloong:loong_palace` cuboid from
   `(-54, 68, -312)` through `(8, 77, -272)`.
2. Delete the old blockstate, item model, and custom-water fluid tag file.
3. Remove only the old block and bucket translation keys; retain the cooldown
   category and option translations.
4. Process resources and inspect the built JAR for the new JSON and absence of
   legacy assets.
5. Commit the resource migration.

**Verification:**

```powershell
.\gradlew.bat processResources
rg -n 'block\.beloong\.beloong_water|item\.beloong\.beloong_water_bucket|beloong:flowing_beloong_water' src/main/resources
```

The `rg` command must return no matches.

### Task 8: Full Regression And Acceptance Verification

**Files:**
- Modify if needed: `README.md`
- Modify if needed: `docs/plans/2026-07-29-vanilla-beloong-water-regions-design.md`

**Steps:**
1. Run all JUnit tests and required GameTests from a clean post-change state.
2. Build the distributable JAR and start the dedicated server run long enough
   to confirm registration and data reload complete without client-only errors.
3. Search the source tree and built JAR for all removed registry IDs and assets.
4. Manually verify the approved pool boundaries, waterlogged contact, outside
   water, re-entry cooldown, and `/reload` behavior in a development client.
5. Record any user-facing migration note and commit the final verification/docs.

**Verification:**

```powershell
.\gradlew.bat test
.\gradlew.bat runGameTestServer
.\gradlew.bat build
.\gradlew.bat runServer
```

`runServer` is an interactive smoke check and should be stopped only after the
server reaches a successful startup state.
