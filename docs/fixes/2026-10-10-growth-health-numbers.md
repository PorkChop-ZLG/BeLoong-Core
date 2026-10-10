# Growing dragons and Health Bars healing numbers

Dragon Survival 2.0.71 recalculates health in `DSModifiers.updateGrowthModifiers`
as `newMaxHealth * oldHealth / oldMaxHealth`. Growth therefore increases health
even when a dragon remains at the same health percentage. Health Bars 21.1.0
interprets every increase as healing and uses `ceil(abs(delta))`, turning tiny
growth increments into repeated green `+1` particles.

The optional client compatibility subtracts proportional max-health changes from
the number shown only when the dragon's growth value also changes. Equipment or
other max-health changes without growth keep their original health deltas.
A fixed two-client-tick window tolerates health, growth and attribute
packets arriving separately; continuous growth cannot indefinitely extend it.
Ordinary healing and damage with unchanged maximum health remain separate.
During a growth/maximum-health transition the number represents the net damage/healing
over that window. Changes below four health ULPs are treated as float noise.
The health value and bar animation are not modified. The hook is scoped to real
dragon players, resets on entity/world changes, and is enabled only for the
verified Health Bars 21.1.0 API. Missing Health Bars creates no dependency.

The former `AsteorBarHealthFixMixin` is removed. It inferred health from a previous
entity, counted render calls as ticks and wrote cached health from HUD rendering.
The HUD now reads the current entity's health normally; a server health-sync
problem must not be repaired by restoring a previous entity's cached health.
This supersedes the old AsteorBar freeze/write-back design in `docs/superpowers/`.

## Verification

Run `gradlew test build`. The unit tests cover full/partial health growth, both
packet orders separated by 0–2 ticks, real damage/healing during growth, fractional
healing, opposing ordinary changes, float noise, shrinking, death, duplicate
ticks and entity/world resets. These tests do not establish in-game performance.

### Build result on 2026-10-10

All **37** JUnit cases passed, and `test build` completed using JDK 21 and the
current client's NeoForge **21.1.248**, Dragon Survival **2.0.71** and GeckoLib
**4.9.3** dependencies. Health Bars is absent from the compile classpath; the
optional hook uses a string target and `@Pseudo` instead of linking its classes.
The installed Health Bars 21.1.0 `tick(LivingEntity)` method and private
`lastHealthDelta` field were checked against its bytecode.

The normal NeoForm Minecraft recompilation failed in existing `package-info`
sources before compiling the mod. This build recovered the exact cached
NeoForge 21.1.248 mapped SDK after checking its version metadata and SHA-256,
and skipped only `createMinecraftArtifacts`. A local init script moved build
outputs to an ASCII path after Gradle's test worker could not load classes from
the Chinese workspace path. The repository's default NeoForge version was not
changed. The produced jar requires NeoForge 21.1.248 or later.

Actual client startup, the optional-mod presence/absence cases, combat,
dimension changes, respawn and visual/performance checks remain **unverified**.
The unit reset tests simulate entity changes; they do not launch a game.
Packet separation beyond two ticks and other max-health changes simultaneous
with growth are not covered by the filter's proportional-change assumption.

On a client, compare the same scene before/after, first pausing natural growth to
confirm the cause. Check idle growing dragons, combat, dimension changes and
respawn, both with and without Health Bars. Record actual frame times and a new
spark profile. Do not infer an FPS improvement from a CPU sampling percentage.
