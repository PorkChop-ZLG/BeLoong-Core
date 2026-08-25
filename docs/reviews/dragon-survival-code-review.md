# 龙之生存（Dragon Survival）相关代码审查与问题记录

> 审查日期：2026-08-14  
> 审查范围：BeLoong-Core 中与 Dragon Survival 相关的代码  
> 对照源码：`D:\Minecraft\DragonSurvival`（版本 2.0.64，NeoForge 1.21.1）

---

## 1. 审查范围

本次审查只覆盖与龙之生存（Dragon Survival）集成相关的代码，主要包括：

1. `src/main/resources/beloong.mixins.json`
2. `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/*.java`
3. `src/main/java/com/zonlong/beloong/ability/AbilityEffectRegistry.java`
4. `src/main/java/com/zonlong/beloong/ability/AirStrikeEffect.java`
5. `src/main/java/com/zonlong/beloong/ability/TpLoongPalaceEffect.java`
6. `src/main/java/com/zonlong/beloong/fluid/BeloongWaterContactHandler.java`
7. `src/main/java/com/zonlong/beloong/fluid/BeloongWaterContactDetector.java`
8. `src/main/java/com/zonlong/beloong/registry/ModAttributes.java`
9. `src/main/java/com/zonlong/beloong/registry/ManaLossHandler.java`
10. `src/main/java/com/zonlong/beloong/registry/ModMobEffects.java`
11. `src/main/java/com/zonlong/beloong/registry/SereneEffect.java`
12. `src/main/java/com/zonlong/beloong/registry/FlightBanEffect.java`
13. `src/main/java/com/zonlong/beloong/treasure/TreasureGrowthHandler.java`
14. `src/main/java/com/zonlong/beloong/treasure/TreasureValueCalculator.java`
15. `src/main/java/com/zonlong/beloong/treasure/TreasureGrowthLoader.java`
16. `src/main/java/com/zonlong/beloong/util/ClaimProtectionHelper.java`
17. `src/main/java/com/zonlong/beloong/compat/ftbchunks/FTBChunksProtectionBridge.java`
18. `src/main/java/com/zonlong/beloong/compat/ftbchunks/LoongPalaceProtectionPolicy.java`
19. `src/main/resources/data/dragonsurvival/dragonsurvival/dragon_ability/air_strike.json`
20. `src/main/resources/data/dragonsurvival/dragonsurvival/dragon_ability/tp_loong_palace.json`

---

## 2. 总体结论

1. 未发现会导致启动崩溃的 Mixin 签名错误。
2. 已用 `javap` 核对 DS 2.0.64 编译产物，`DragonDestructionHandler` 的 lambda 方法名/参数、`ClientFlightHandler.flightControl`、各 `AbilityBlockEffect.apply`、`ProjectileDamageEffect.apply` 等均与 BeLoong 的 Mixin 目标匹配。
3. `ClientFlightHandlerMixin` 的 handler 只写 `CallbackInfo` 而不写 `ClientTickEvent.Pre` 是合法写法：Mixin 的 `@Inject` 允许 handler 只声明目标方法参数的一个前缀。
4. 主要问题集中在行为逻辑、配置一致性、线程安全、性能等方面。
5. 后续实际测试又确认了一个水中重力 BUG 和一个构建警告 BUG，分别记录在第 4 章。

---

## 3. 代码审查问题

### 3.1 重要问题

1. **低飞行等级时，滑翔/旋转/落地也会被额外加重力**
   - 文件：`src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerMixin.java:115-118`
   - 问题：`!shouldHover && flightLevel < 1.0 && noMoveInput` 分支没有排除滑翔、旋转、地面、水中等状态，导致这些状态下每 tick 额外减去 `gravity`。
   - 建议：追加额外重力前增加 `ServerFlightHandler.isFlying(player)`，并排除 `isGliding` 和 `isSpin`。

2. **`ClientFlightHandlerMixin` 注释说“不覆盖 stableHover”，但代码实际覆盖了**
   - 文件：`src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerMixin.java:90-100`
   - 问题：代码没有读取 `ServerFlightHandler.stableHover`，只要 `flightLevel >= 1` 且无输入就会清零加速度并稳定悬停，即使 DS 配置 `stableHover = false` 也会生效。
   - 建议：明确设计意图，如果不应覆盖 DS 配置，则在 `shouldHover` 中加上 `ServerFlightHandler.stableHover` 判断；如果飞行等级就是要覆盖 DS 配置，请更新注释和文档。

3. **`ToggleFlightMixin` 在网络线程读取属性数据**
   - 文件：`src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ToggleFlightMixin.java:54`
   - 问题：`ToggleFlight.handleServer` 运行在网络线程，`ModAttributes.getFlightLevel(player)` 会跨线程读取属性/附件数据，存在 `ConcurrentModificationException` 或读到过期值的风险。
   - 建议：把门控逻辑移入 `context.enqueueWork` 内部，或在主线程读取飞行等级后再判断。

4. **`AirStrikeEffect` 的 AOE 不遵守数据文件里的 `allies_and_self`**
   - 文件：`src/main/java/com/zonlong/beloong/ability/AirStrikeEffect.java:112-127`
   - 问题：`air_strike.json` 声明 `targeting_mode: allies_and_self`，但代码会伤害除自己外所有生物，包括队友、宠物、村民等。
   - 建议：如果设计上就是要敌我不分，请把数据文件改成 `all_except_self` 或敌对 targeting；如果确实只想打敌人，请在代码里用阵营/`canHarmEntity` 过滤。

5. **`TreasureValueCalculator` 会多扫一层方块**
   - 文件：`src/main/java/com/zonlong/beloong/treasure/TreasureValueCalculator.java:22-26`
   - 问题：`BlockPos.betweenClosed` 是闭区间，而 AABB 的 `max` 是开区间上界；使用 `ceil(max)` 会把不 intersecting 的一层方块也算进去。
   - 建议：参考 `BeloongWaterContactDetector`，使用 `Mth.floor(Math.nextDown(max))`。

6. **`DragonStateHandlerMixin` 静默修改了 DS 的默认大型龙破坏行为**
   - 文件：`src/main/java/com/zonlong/beloong/mixin/dragonsurvival/DragonStateHandlerMixin.java:17-18`
   - 问题：DS 默认 `largeDragonDestruction = ENABLED`，该 Mixin 把所有新玩家改成 `DISABLED`，且没有配置项说明。
   - 建议：如果是刻意设计，请加配置项并写文档；否则移除该 Mixin。

7. **`ProjectileDamageEffectMixin` 可能是在修一个“已不存在”的崩溃**
   - 文件：`src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ProjectileDamageEffectMixin.java:39`
   - 问题：在 1.21.1 / NeoForge 中，`AttributeMap.getValue(Holder<Attribute>)` 通常对缺失属性返回默认值，而不是抛异常；该 Mixin 可能冗余。
   - 建议：在真实游戏环境确认是否仍会崩溃；如果不会崩，删除以降低维护面。

8. **化龙池水接触检测每 tick 全量扫描方块**
   - 文件：`src/main/java/com/zonlong/beloong/fluid/BeloongWaterContactHandler.java:24-45`、`BeloongWaterContactDetector.java:15-29`
   - 问题：`onPlayerTick` 对每个 `ServerPlayer` 每 tick 都调用 `isTouching(player)`，即使玩家已经接触水、没有上升沿，也会做 O(区域体积) 的方块扫描。
   - 建议：在已接触状态下降低扫描频率，或缓存结果，只在玩家移动/离开区域时重新检测。

### 3.2 建议

1. **注释与数值不一致**：`SereneEffect.java:9` 写“每级 +0.001”，但 `ModMobEffects.java:108` 注册的是 `0.004`，请统一。
2. **`TreasureGrowthLoader` 会把非 `dragon_treasure` 的 key 全部当作 `other_treasure`**：如果同一方块同时出现在两个分类里，`TreasureValueCalculator` 会重复计算。建议对未知 key 报错或拒绝。
3. **`TpLoongPalaceEffect` 未检查 `DimensionTransport.owToLP_enabled` / `lpToOw_enabled`**：如果这些开关意图控制所有龙宫传送，当前 DS 技能会绕过它们。
4. **`FTBChunksProtectionBridge.ALWAYS_BLOCK` 返回 `ProtectionPolicy.CHECK`**：与 `ClaimProtectionHelper` 注释中“已认领区块一律拦截”的说法可能不一致；如果确实要无条件阻止，应该确认是否应为 `DENY`。
5. **版本一致性**：BeLoong 的 `build.gradle` 依赖 `curse.maven:dragons-survival-420799:8564714`，而本地 DS 源码是 2.0.64。建议确认该 CurseForge 文件确实对应 2.0.64，否则 Mixin 目标在运行时可能不同。

### 3.3 做得好的地方

1. `DragonDestructionHandlerMixin` 对 lambda 方法的注入与 DS 2.0.64 实际编译字节码完全匹配，包括捕获参数顺序。
2. 六个 `AbilityBlockEffect` Mixin 的 `apply` 签名全部正确。
3. `DSAttributesMixin` 在 `DSAttributes.<clinit>` 注入注册 `flight_level` 的时机是可靠的，能赶上 DS 的 `DeferredRegister` 注册。
4. DS API 使用整体准确：`FlightData`、`TreasureRestData`、`AltarData`、`ManaHandler.consumeMana`、`DragonSpecies.getSpecies`、`OpenDragonAltar` 等签名均匹配。
5. 自定义技能数据文件没有悬空引用，`beloong:air_strike` 和 `beloong:loong_palace` 都有对应注册资源。

---

## 4. 后续 BUG 记录

### 4.1 BUG-1：低飞行等级进入水中被额外重力拉入水底

#### 4.1.1 现象

实际测试发现：当玩家处于低飞行等级（没有稳定悬停）且翅膀展开时，一旦进入水中，会被施加额外的强力重力，导致玩家直接沉入水底且难以上升。

#### 4.1.2 根因

出问题代码：

`src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerMixin.java:115-118`

```java
if (!shouldHover && flightLevel < 1.0 && noMoveInput) {
    double gravity = player.getAttributeValue(Attributes.GRAVITY);
    Vec3 delta = player.getDeltaMovement();
    player.setDeltaMovement(delta.x, delta.y - gravity, delta.z);
}
```

原因链：

1. DS 的 `ServerFlightHandler.isFlying()` 在玩家 `isInWater() || isInLava()` 时返回 `false`：
   - `D:\Minecraft\DragonSurvival\src\main\java\by\dragonsurvivalteam\dragonsurvival\server\handlers\ServerFlightHandler.java:170`
2. 因此在水中，DS 的 `ClientFlightHandler.flightControl` 会走 `else` 分支，把 `ax/az/ay` 清零，不再按飞行物理处理：
   - `D:\Minecraft\DragonSurvival\src\main\java\by\dragonsurvivalteam\dragonsurvival\client\handlers\ClientFlightHandler.java:470-476`
3. 但 BeLoong 的 Mixin 是在 `flightControl` 的 `TAIL` 注入的，DS 已经清空飞行加速度后，它仍然会执行额外重力逻辑。
4. 结果：水中低飞行等级玩家每 tick 被额外减 `gravity`，沉入水底。

#### 4.1.3 与审查问题 3.1.1 的关系

与 3.1.1 是**同一个根因**，但 3.1.1 当时没有把“水中/熔岩”场景列全。

共同点：额外重力分支只排除了 `shouldHover`，但没有排除所有“不应该模拟非稳定悬停”的场景，包括水、熔岩、滑翔、旋转、落地。

#### 4.1.4 修复建议

在追加额外重力前，确保玩家处于 DS 认可的“空中飞行”状态，并排除滑翔/旋转：

```java
if (!shouldHover && flightLevel < 1.0 && noMoveInput
        && ServerFlightHandler.isFlying(player)
        && !ServerFlightHandler.isGliding(player)
        && !ServerFlightHandler.isSpin(player)) {
    double gravity = player.getAttributeValue(Attributes.GRAVITY);
    Vec3 delta = player.getDeltaMovement();
    player.setDeltaMovement(delta.x, delta.y - gravity, delta.z);
}
```

说明：

- `ServerFlightHandler.isFlying(player)` 本身已经排除了水中、熔岩、地面、骑乘状态。
- 再排除 `isGliding` / `isSpin`，避免影响 DS 原有的滑翔和旋转物理。
- 如果只做最小修复，也可以先加 `!player.isInWater() && !player.isLava()`，但建议使用上面的完整条件。

---

### 4.2 BUG-2：IDEA 构建时 `ClientFlightHandlerMixin` 的 `@Shadow` 字段映射警告

#### 4.2.1 现象

使用 IDEA 构建时出现以下警告：

```
> Task :compileJava
注: SpongePowered MIXIN Annotation Processor Version=0.8.7
注: Supported obfuscation types: ObfuscationServiceMCP supports [searge,notch]
D:\Minecraft\BeLoong-Core\src\main\java\com\zonlong\beloong\mixin\dragonsurvival\ClientFlightHandlerMixin.java:52: 警告: Unable to locate obfuscation mapping for @Shadow field
    @Shadow
    ^
D:\Minecraft\BeLoong-Core\src\main\java\com\zonlong\beloong\mixin\dragonsurvival\ClientFlightHandlerMixin.java:56: 警告: Unable to locate obfuscation mapping for @Shadow field
    @Shadow
    ^
D:\Minecraft\BeLoong-Core\src\main\java\com\zonlong\beloong\mixin\dragonsurvival\ClientFlightHandlerMixin.java:60: 警告: Unable to locate obfuscation mapping for @Shadow field
    @Shadow
    ^
3 个警告
```

#### 4.2.2 根因

`ClientFlightHandlerMixin` 中的三个 `@Shadow` 字段没有关闭 remap：

```java
@Mixin(ClientFlightHandler.class)
public abstract class ClientFlightHandlerMixin {

    @Shadow
    private static double ax;

    @Shadow
    private static double az;

    @Shadow
    private static double ay;
    ...
}
```

- `@Mixin(ClientFlightHandler.class)` 没有写 `remap = false`。
- 三个 `@Shadow` 字段也没有写 `remap = false`。
- 注解处理器默认认为 `ax/ay/az` 是 Minecraft 中需要混淆映射的字段，于是尝试查找 `ClientFlightHandler.ax` 的 obfuscation mapping。
- 但 `ClientFlightHandler` 是 Dragon Survival 的模组类，不是 Minecraft 原版类，没有对应的 searge/notch 映射，因此产生警告。

游戏能正常运行，是因为 DS 模组类在运行时不会被混淆，Mixin 最终仍按源码字段名匹配成功；但这是一个隐患。

#### 4.2.3 修复方案：使用 `@Accessor` 替代 `@Shadow`

为了统一风格、彻底去掉 `@Shadow`，采用项目已有的 Accessor 模式（参考 `MalkuthEntityAccessor`）。

##### 步骤 1：新建 `ClientFlightHandlerAccessor`

路径：

`src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerAccessor.java`

```java
package com.zonlong.beloong.mixin.dragonsurvival;

import by.dragonsurvivalteam.dragonsurvival.client.handlers.ClientFlightHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ClientFlightHandler.class, remap = false)
public interface ClientFlightHandlerAccessor {

    @Accessor("ax")
    static void beloong$setAx(double value) {
        throw new AssertionError();
    }

    @Accessor("ay")
    static void beloong$setAy(double value) {
        throw new AssertionError();
    }

    @Accessor("az")
    static void beloong$setAz(double value) {
        throw new AssertionError();
    }
}
```

> 静态 Accessor 方法体写 `throw new AssertionError()` 是 Mixin 的标准写法，运行时会被 Mixin 替换成真正的字段读写。

##### 步骤 2：修改 `ClientFlightHandlerMixin`

删除三个 `@Shadow` 字段：

```java
@Shadow
private static double ax;

@Shadow
private static double az;

@Shadow
private static double ay;
```

然后把原来直接赋值的地方改成调用 Accessor：

```java
// 原来：
ax = 0.0;
az = 0.0;

// 改为：
ClientFlightHandlerAccessor.beloong$setAx(0.0);
ClientFlightHandlerAccessor.beloong$setAz(0.0);
```

以及：

```java
// 原来：
if (player.isCreative()) {
    ay = 0.0;
    ...
}

// 改为：
if (player.isCreative()) {
    ClientFlightHandlerAccessor.beloong$setAy(0.0);
    ...
}
```

##### 步骤 3：注册 Accessor 到 mixins 配置

`ClientFlightHandler` 是客户端类，所以要把 Accessor 加到 `beloong.mixins.json` 的 `client` 列表：

```json
"client": [
  "dragonsurvival.ClientFlightHandlerAccessor",
  "dragonsurvival.ClientFlightHandlerMixin",
  ...
]
```

##### 步骤 4：验证

修改后重新构建，应不再出现 `Unable to locate obfuscation mapping for @Shadow field` 警告。

> 补充：如果只是想最快消除警告，也可以给现有 `@Shadow` 加 `remap = false`：
> ```java
> @Shadow(remap = false)
> private static double ax;
> ```
> 但这仍然保留 `@Shadow`。为了统一、彻底去掉 `@Shadow`，推荐上面的 Accessor 方案。

---

## 5. 后续待办

1. 按 3.1 和 4.1 修复 `ClientFlightHandlerMixin` 的额外重力判断，覆盖水中/熔岩/滑翔/旋转/落地场景。
2. 按 4.2 用 `ClientFlightHandlerAccessor` 替换 `ClientFlightHandlerMixin` 中的 `@Shadow`，并更新 `beloong.mixins.json`。
3. 确认 3.1.3 的 `ToggleFlightMixin` 网络线程读取问题并调整。
4. 确认 3.1.4 的 `AirStrikeEffect` 目标过滤是否符合设计。
5. 确认 3.1.7 的 `ProjectileDamageEffectMixin` 是否仍需要保留。
6. 确认 BeLoong 依赖的 DS CurseForge 文件版本与本地 DS 2.0.64 是否一致。
