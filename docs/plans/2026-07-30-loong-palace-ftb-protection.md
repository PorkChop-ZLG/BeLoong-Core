# Loong Palace FTB Protection Implementation Plan

**Goal:** Prevent non-bypass players and ServerPlayer-based fake players from
breaking or placing blocks in `beloong:loong_palace`, while leaving block
interaction, entity attacks, and non-player world changes untouched.

**Approach:** Register an FTB-only compatibility handler. Use NeoForge block
events for edit protection, FTB Chunks' public bypass API for administrator
exemptions, and `ClaimedChunkEvent.BEFORE_CLAIM` to reject new claims in the
dimension. Existing claims are reported but not modified.

## Task 1: Add the protection handler

**Create:**
`src/main/java/com/zonlong/beloong/compat/ftbchunks/LoongPalaceProtectionHandler.java`

- Cancel player and fake-player break/place events in Loong Palace.
- Permit players with FTB Chunks `bypass_protection`.
- Reject new FTB claims in Loong Palace.
- Report, but do not remove, historical claims.

## Task 2: Register the optional integration

**Modify:** `src/main/java/com/zonlong/beloong/BeLoongCore.java`

- Register the handler only when `ftbchunks` is loaded.
- Keep BeLoong Core loadable without FTB Chunks.

## Task 3: Verify

- Run the full Gradle build using the locally cached Gradle 9.2.1 distribution.
- Inspect the output JAR for the new handler.
- Leave gameplay acceptance testing to the user-provided manual test matrix.

