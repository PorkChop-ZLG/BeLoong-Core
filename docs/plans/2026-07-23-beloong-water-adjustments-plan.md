# Beloong Water Adjustments Implementation Plan

**Goal:** Add a configurable 10-second GUI trigger cooldown and render Beloong Water as opaque turquoise.

## Task 1: Cooldown State

**Files:**
- Create: `src/main/java/com/zonlong/beloong/fluid/BeloongWaterTriggerCooldown.java`
- Create: `src/test/java/com/zonlong/beloong/fluid/BeloongWaterTriggerCooldownTest.java`

**Steps:**
1. Write a failing test for first use, pre-expiry rejection, boundary acceptance, and cleanup.
2. Run the focused test and confirm RED.
3. Implement the minimum tick-based cooldown state.
4. Run the full test suite and refactor if needed.

**Verification:** `./gradlew test --tests com.zonlong.beloong.fluid.BeloongWaterTriggerCooldownTest`

## Task 2: NeoForge Configuration And Handler Integration

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/Config.java`
- Modify: `src/main/java/com/zonlong/beloong/fluid/BeloongWaterContactHandler.java`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Create: `src/test/java/com/zonlong/beloong/fluid/BeloongWaterConfigurationTest.java`

**Steps:**
1. Add a failing source/resource contract test for the default and display name.
2. Register a server config value with a 10-second default.
3. Gate entry handling and record only successful GUI openings.
4. Run the full test suite.

**Verification:** `./gradlew test`

## Task 3: Opaque Turquoise Rendering

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCoreClient.java`
- Create: `src/test/java/com/zonlong/beloong/fluid/BeloongWaterClientRenderingTest.java`

**Steps:**
1. Add a failing rendering contract test for `0xFF40E0D0` and the solid layer.
2. Change tint, fog, and render layer.
3. Run the full test suite.

**Verification:** `./gradlew test`

## Task 4: Final Verification

**Files:** All changed files

**Steps:**
1. Review the diff against the approved design.
2. Run `git diff --check`.
3. Run `./gradlew build`.
4. Smoke-test dedicated-server startup and client resource loading.

**Verification:** `./gradlew build`
