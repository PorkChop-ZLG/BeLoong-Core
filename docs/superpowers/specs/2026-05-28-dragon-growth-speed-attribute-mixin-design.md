# Dragon Growth Speed Attribute — Mixin Design

## Goal

Add a `growth_speed` attribute that acts as a multiplier on the dragon's natural (passive) growth rate.  
Example: value 2.0 means the dragon grows 2× faster — 1 minute of real time counts as 2 minutes of growth.

## Scope

- Only affects **natural passive growth** (`DragonGrowthHandler.onPlayerUpdate`)
- Does **NOT** affect growth from items (dragon hearts, star bones, etc.)
- The attribute is registered from an **external companion mod** using Mixin to inject into DragonSurvival

---

## Implementation Plan

### 1. Register the Attribute (no Mixin needed)

In the external mod, create an attributes class with a `DeferredRegister<Attribute>`:

```java
// Example: ModAttributes.java
public class ModAttributes {
    public static final DeferredRegister<Attribute> REGISTRY =
        DeferredRegister.create(Registries.ATTRIBUTE, "your_mod_id");

    public static final Holder<Attribute> GROWTH_SPEED = REGISTRY.register("growth_speed",
        () -> new RangedAttribute(
            "attribute.your_mod_id.growth_speed",  // translation key
            1.0,   // default: normal growth speed
            0.0,   // min: no natural growth
            1024.0 // max
        ).setSyncable(true)
    );
}
```

Subscribe to `EntityAttributeModificationEvent` to attach the attribute to players:

```java
@SubscribeEvent
public static void attachAttributes(EntityAttributeModificationEvent event) {
    event.add(EntityType.PLAYER, GROWTH_SPEED);
}
```

### 2. Mixin: Multiply Natural Growth Rate

**Target class:**
`by.dragonsurvivalteam.dragonsurvival.common.handlers.DragonGrowthHandler`

**Target method:**
`onPlayerUpdate(PlayerTickEvent.Pre event)` — specifically line 133:

```java
double desiredGrowth = handler.getDesiredGrowth() + dragonStage.ticksToGrowth(INTERVAL);
```

**Mixin strategy:** `@Redirect` the `DragonStage.ticksToGrowth(int)` call.

`DragonStage.ticksToGrowth` is defined as:
```java
// DragonStage.java:153-155
public double ticksToGrowth(int ticks) {
    return (growthRange().max() - growthRange().min()) / ticksUntilGrown() * ticks;
}
```

**Mixin class:**

```java
@Mixin(DragonGrowthHandler.class)
public class MixinDragonGrowthHandler {

    @Redirect(
        method = "onPlayerUpdate",
        at = @At(
            value = "INVOKE",
            target = "Lby/dragonsurvivalteam/dragonsurvival/registry/dragon/stage/DragonStage;ticksToGrowth(I)D"
        )
    )
    private static double redirectTicksToGrowth(
        DragonStage stage,
        int ticks,
        PlayerTickEvent.Pre event  // captured from enclosing method
    ) {
        double baseGrowth = stage.ticksToGrowth(ticks); // call original

        if (event.getEntity() instanceof ServerPlayer player) {
            AttributeInstance attr = player.getAttribute(ModAttributes.GROWTH_SPEED);
            if (attr != null) {
                return baseGrowth * attr.getValue();
            }
        }

        return baseGrowth;
    }
}
```

**Mixin config JSON** (`your_mod_id.mixins.json`):

```json
{
  "required": true,
  "package": "com.yourmod.mixin",
  "compatibilityLevel": "JAVA_21",
  "refmap": "your_mod_id.refmap.json",
  "mixins": [
    "MixinDragonGrowthHandler"
  ]
}
```

### 3. (Optional) Display Attribute Value

If you want the attribute to show up in-game:

- The DragonSurvival inventory screen reads attributes from the player and displays modifiers
- Since `GROWTH_SPEED` is attached to the player, it will appear automatically if the screen shows all player attributes
- For a custom display slot, an additional mixin targeting the dragon GUI may be needed

---

## Data Flow

```
PlayerTickEvent.Pre (each tick, every 20 ticks / 1 second)
    │
    ▼
DragonGrowthHandler.onPlayerUpdate()
    │
    ├─ (original)  desiredGrowth = oldGrowth + dragonStage.ticksToGrowth(INTERVAL)
    │
    ├─ (redirect)  desiredGrowth = oldGrowth + dragonStage.ticksToGrowth(INTERVAL) × growth_speed
    │
    ▼
handler.setDesiredGrowth(player, desiredGrowth)
    │
    ▼
handler.lerpGrowth(player)  — smooth interpolation each tick
```

## Edge Cases

| Scenario | Behavior |
|---|---|
| `growth_speed = 0.0` | Natural growth stops entirely (same as `isGrowthStopped = true`, but through attribute) |
| `growth_speed = -1.0` | Growth reverses over time |
| Attribute not present / null | Falls back to `baseGrowth` (1× speed) |
| Player is not a dragon | Attribute has no effect (growth handler exits early) |
| Player in enclosed space | `isGrowthAllowed` returns false, so no growth happens regardless of growth_speed |

---

## Target Mod Versions

- **Minecraft:** 1.21.1
- **DragonSurvival:** current `1.21.1` branch
- **NeoForge:** matching DragonSurvival's dependency
- **Mixin:** 0.8.x (via NeoForge's built-in Mixin support)
