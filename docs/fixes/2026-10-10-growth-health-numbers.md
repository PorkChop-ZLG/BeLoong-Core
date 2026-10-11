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
dragon players, resets on entity/world changes, and is enabled whenever Health
Bars is installed. Version strings are not checked. Missing Health Bars creates
no dependency. The hook still expects `HealthTracker.tick(LivingEntity)` and the
private `lastHealthDelta` field; future API changes require adapting the hook.

The former `AsteorBarHealthFixMixin` is removed. It inferred health from a previous
entity, counted render calls as ticks and wrote cached health from HUD rendering.
The HUD now reads the current entity's health normally; a server health-sync
problem must not be repaired by restoring a previous entity's cached health.
This supersedes the old AsteorBar freeze/write-back design in `docs/superpowers/`.

## Verification

本项目**不做自动化测试**（约定见 `memory/project-context.md`）：验收方式是 `gradlew build`
+ 静态 grep 探针 + 实机运行。原提交带的 JUnit 测试与 `src/test` 源集已于 2026-10-11 按该约定移除
（同一套基础设施此前已由 `8a5cd7e chore: remove test source and configuration` 移除过一次）。

### Build notes

`build` completed using JDK 21 and the current client's NeoForge **21.1.248**,
Dragon Survival **2.0.71** and GeckoLib **4.9.3** dependencies. Health Bars is now
declared as an optional dependency (`implementation "curse.maven:health-bars-1367734:7122333"`
plus an optional entry in `neoforge.mods.toml`); the hook still uses a string target
together with `@Pseudo` and `require = 0` instead of linking its classes at compile time.
The installed Health Bars 21.1.0 `tick(LivingEntity)` method and private
`lastHealthDelta` field were checked against its bytecode.

Actual client startup, the optional-mod presence/absence cases, combat,
dimension changes, respawn and visual/performance checks remain **unverified**.
Packet separation beyond two ticks and other max-health changes simultaneous
with growth are not covered by the filter's proportional-change assumption.

On a client, compare the same scene before/after, first pausing natural growth to
confirm the cause. Check idle growing dragons, combat, dimension changes and
respawn, both with and without Health Bars. Record actual frame times and a new
spark profile. Do not infer an FPS improvement from a CPU sampling percentage.
