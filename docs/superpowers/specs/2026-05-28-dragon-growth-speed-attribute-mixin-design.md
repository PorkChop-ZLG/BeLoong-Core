# 龙成长速度属性 — Mixin 设计

## 目标

添加一个 `growth_speed` 属性（attribute），作为龙自然（被动）成长速度的倍率。
例如：值 2.0 表示龙成长速度翻倍 — 现实 1 分钟等于 2 分钟的成长进度。

## 作用范围

- 仅影响**自然被动成长**（`DragonGrowthHandler.onPlayerUpdate`）
- **不**影响物品带来的成长（龙心、星骨等）
- 属性注册在 **BeLoong-Core** 中，通过 Mixin 注入到 DragonSurvival

---

## 实现方案

### 1. 注册属性

新建 `ModAttributes.java`：

```java
public class ModAttributes {
    public static final DeferredRegister<Attribute> REGISTRY =
        DeferredRegister.create(Registries.ATTRIBUTE, "beloong");

    public static final Holder<Attribute> GROWTH_SPEED = REGISTRY.register("growth_speed",
        () -> new RangedAttribute(
            "attribute.beloong.growth_speed",
            1.0,      // 默认值：正常成长速度
            -1024.0,  // 最小值：允许反向成长
            1024.0    // 最大值
        ).setSyncable(true)
    );
}
```

通过 `EntityAttributeModificationEvent` 附加到所有玩家：

```java
@SubscribeEvent
public static void attachAttributes(EntityAttributeModificationEvent event) {
    event.add(EntityType.PLAYER, GROWTH_SPEED);
}
```

在 `BeLoongCore` 主类中注册：

```java
ModAttributes.REGISTRY.register(modEventBus);
modEventBus.addListener(ModAttributes::attachAttributes);
```

### 2. Mixin：倍乘自然成长速率

**目标类：**
`by.dragonsurvivalteam.dragonsurvival.common.handlers.DragonGrowthHandler`

**目标方法：**
`onPlayerUpdate(PlayerTickEvent.Pre event)` — 具体为第 133 行：

```java
double desiredGrowth = handler.getDesiredGrowth() + dragonStage.ticksToGrowth(INTERVAL);
```

**Mixin 策略：** 对 `DragonStage.ticksToGrowth(int)` 调用进行 `@Redirect`，限定在 `onPlayerUpdate` 方法内。

`DragonStage.ticksToGrowth` 的定义：
```java
public double ticksToGrowth(int ticks) {
    return (growthRange().max() - growthRange().min()) / ticksUntilGrown() * ticks;
}
```

**Mixin 类：**

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
        PlayerTickEvent.Pre event
    ) {
        double baseGrowth = stage.ticksToGrowth(ticks);

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

**关键点：**

- `method = "onPlayerUpdate"` 将重定向限定在自然成长内——`getGrowth()`（物品成长）也会调用 `ticksToGrowth`，但不受影响
- `PlayerTickEvent.Pre event` 从包围方法的参数中捕获，用于获取 player 实例
- `instanceof ServerPlayer` 是防御性安全检查（包围方法已做了相同检查，此处二次确认）
- 对 `attr` 的 null 检查确保属性缺失时优雅回退到 `baseGrowth`（1.0× 速度）

**Mixin 配置**（`beloong.mixins.json`）：

```json
{
  "required": true,
  "package": "com.zonlong.beloong.mixin",
  "refmap": "beloong.refmap.json",
  "client": [
    "ClientFlightHandlerMixin",
    "DragonItemRenderLayerMixin",
    "OutlineBufferSourceAccessor"
  ],
  "mixins": [
    "DragonDestructionHandlerMixin",
    "BlockBreakEffectMixin",
    "BlockConversionEffectMixin",
    "ExplodeBlockEffectMixin",
    "FireEffectMixin",
    "BlockHarvestEffectMixin",
    "BonemealEffectMixin",
    "MixinDragonGrowthHandler"
  ],
  "compatibilityLevel": "JAVA_21",
  "injectors": {
    "defaultRequire": 1
  }
}
```

---

## 数据流

```
PlayerTickEvent.Pre（每 tick，每 20 tick / 1 秒触发一次）
    │
    ▼
DragonGrowthHandler.onPlayerUpdate()
    │  !(event.getEntity() instanceof ServerPlayer)? → 返回
    │  !handler.isDragon()? → 返回
    │  tickCount % 20 != 0? → 返回
    │  isGrowthStopped / isNaturalGrowthStopped? → 返回
    │
    ▼
desiredGrowth = handler.getDesiredGrowth() + dragonStage.ticksToGrowth(INTERVAL)
                                                      │
                                                      │  ← @Redirect 注入点
                                                      │  = ticksToGrowth(INTERVAL) × growth_speed
                                                      │
    ▼
isGrowthAllowed(player, handler, desiredGrowth)? → false 则返回
    │
    ▼
handler.setDesiredGrowth(player, desiredGrowth)
    │  clampGrowth() 确保成长值不会跌破全局最小值
    ▼
handler.lerpGrowth(player)  — 每 tick 平滑插值
```

---

## 边界情况

| 场景 | 行为 |
|---|---|
| `growth_speed = 0.0` | 自然成长完全停止（独立于 `isGrowthStopped`） |
| `growth_speed = -1.0` | 成长随时间反退——自然缩小 |
| `growth_speed = 1.0`（默认） | 正常成长，与原版 DragonSurvival 行为一致 |
| 负值导致过度缩小 | `setDesiredGrowth` 中的 `clampGrowth()` 确保成长值不会跌破全局最小值 |
| 属性不存在 / null | 回退到 `baseGrowth`（1× 速度） |
| 玩家不是龙 | `onPlayerUpdate` 在到达重定向之前已返回 |
| `isGrowthStopped = true` | `onPlayerUpdate` 在到达重定向之前已返回 |
| 封闭空间内 | `isGrowthAllowed` 返回 false，无论属性值如何成长均被阻止 |
| 多人/服务器 | 属性通过 `setSyncable(true)` 同步，服务端权威 |

---

## 目标模组版本

- **Minecraft：** 1.21.1
- **DragonSurvival：** 当前 `1.21.1` 分支
- **NeoForge：** 21.1.219
- **Mixin：** 通过 NeoForge 内置 Mixin 支持
