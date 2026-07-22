# Beloong Water Adjustments Design

**Date:** 2026-07-23
**Status:** Approved
**Approach:** Solid turquoise rendering with an independent server-side trigger cooldown

## Architecture

The fluid remains a vanilla-water-derived fluid. Client rendering changes to a solid layer with an opaque `#40E0D0` tint and matching underwater fog. A separate per-player, in-memory cooldown gates only successful Beloong Water GUI openings and does not replace Dragon Survival's altar cooldown.

## Components

- `Config.BeloongWater`: server configuration for the cooldown in seconds, defaulting to 10.
- `BeloongWaterTriggerCooldown`: testable tick-based per-player cooldown state.
- `BeloongWaterContactHandler`: checks entry, pool cooldown, Dragon Survival cooldown, then records a successful opening.
- `BeLoongCoreClient`: opaque turquoise tint, fog, and solid render layer.

## Behavior

- Continuous contact still triggers at most once per entry.
- Re-entry before the pool cooldown expires does not open the GUI.
- The pool cooldown begins only after the GUI payload is sent successfully.
- Dimension changes and respawns reset contact state but retain the session cooldown; logout removes all temporary state.
- The NeoForge configuration uses an ASCII key and a Chinese display name/comment of `化龙池水冷却`.

## Non-Goals

- No persistent cooldown attachment or network protocol is added.
- Dragon Survival's own altar cooldown behavior is not changed.
- No custom water textures are added.

## Acceptance Criteria

- Given the default server configuration, when a player successfully opens the GUI, then that player cannot open it again through Beloong Water for 200 server ticks.
- Given two players, when one enters cooldown, then the other player's trigger remains available.
- Given the pool cooldown has elapsed and the player re-enters, when Dragon Survival permits altar use, then the original altar GUI opens.
- Given the NeoForge configuration screen, when the option is displayed in Chinese, then its name is `化龙池水冷却` and its default value is 10 seconds.
- Given the fluid is rendered, then its tint and underwater fog are `#40E0D0` and both fluid forms use the solid render layer.
