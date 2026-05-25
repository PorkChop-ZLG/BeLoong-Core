# Port DS Bug Fix Mixins into BeLoong-Core

## Summary

Port 3 mixin classes from the DS_bug_fix mod into BeLoong-Core, fixing two Dragon Survival bugs: stable hover drift and glowing-outline dragon invisibility.

## Files to create

```
src/main/java/com/zonlong/beloong/mixin/
├── ClientFlightHandlerMixin.java       # Fix stable hover drift
├── DragonItemRenderLayerMixin.java     # Fix glowing outline on held items
└── OutlineBufferSourceAccessor.java    # Accessor for OutlineBufferSource internals

src/main/resources/
└── beloong.mixins.json                 # Mixin config (client-only)
```

## Files to modify

- **Config.java** — clear example entries (logDirtBlock, magicNumber, etc.), add `FIX_GLOWING_OUTLINE` and `FIX_STABLE_HOVER` boolean toggles (both default true). Use existing `ModConfigSpec.Builder` pattern with no INSTANCE field needed (mixins reference the static fields directly).
- **build.gradle** — no changes needed; NeoForge MDG 2.x auto-discovers and applies mixin configs.

## Mixin port changes

Package: `com.tangwenjun.ds_bug_fix.Mixin` → `com.zonlong.beloong.mixin`
Config reference: `DSBugFixConfig.INSTANCE.fixXxx` → `Config.FIX_XXX`

All other logic is copied verbatim including the accessor method names with `ds_bug_fix$` prefix (avoids collision with other mods' accessors).

The custom config screen (`DSBugFixConfigScreen`) and language files are not ported — NeoForge's built-in `ConfigurationScreen` handles config display automatically.

## Dependencies

None new. Dragon Survival and GeckoLib are already declared in `build.gradle` and `neoforge.mods.toml`.

## beloong.mixins.json

```json
{
  "required": true,
  "package": "com.zonlong.beloong.mixin",
  "client": [
    "ClientFlightHandlerMixin",
    "DragonItemRenderLayerMixin",
    "OutlineBufferSourceAccessor"
  ],
  "compatibilityLevel": "JAVA_21",
  "injectors": {
    "defaultRequire": 1
  }
}
```

All three are client-only since they target rendering and flight control — neither runs on dedicated servers.
