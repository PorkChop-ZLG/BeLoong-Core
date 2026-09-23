# Dragon Survival 升级兼容性审查（2026-09-23，2.0.70 基线）

> **审查对象**：`BeLoong-Core` 0.9.7（`D:\Minecraft\BeLoong-Core`）对 `Dragon Survival` 的全部 mixin 与 API 触点
> **对照源码**：`D:\Minecraft\开源模组参考文件\DragonSurvival`（分支 `1.21.1` @ `d6baf03b3`，本轮 2026-09-23 13:26 pull；`origin/1.21.1` = `755a15983`，2026-09-15）
> **对照编译产物**：`curse.maven:dragons-survival-420799:8871661` → `dragons-survival-420799-8871661.jar`，`META-INF/neoforge.mods.toml` `version="2.0.70"`（13,736,896 字节）
> **本报告为只读审查。本轮唯一改动的项目文件是 `build.gradle:137` 的 DS 坐标。**

---

## 0. 本轮基线已更新，且源码与 jar 内容一致

### 0.1 依赖已升级

```groovy
// build.gradle:137
implementation "curse.maven:dragons-survival-420799:8871661"   // 2.0.70（原 8726322 = 2.0.67）
```

解析实证：`gradlew dependencies --configuration compileClasspath` **BUILD SUCCESSFUL**，jar 落入
`…\curse.maven\dragons-survival-420799\8871661\bbd02b8ccc49128ddf0039c77a0b1949024d5c8d\`。

### 0.2 源码仓库确实更新了，且内容就是 2.0.70

`git reflog` 显示 `d6baf03b3 HEAD@{2026-09-23 13:26:39}: pull --tags origin 1.21.1`（上一版审查时这次拉取还是失败的）。

`gradle.properties` 里 `mod_version=2.0.69` 看似落后于 jar 的 `2.0.70`，但**版本号字段不可信**（DS 上游发版时不回写仓库），实际内容已对齐 2.0.70 —— 三项独立证据：

| 证据 | 结果 |
|---|---|
| `dragon_center.json` 有 2.0.70 才补上的 `fly_idle` 片段 | ✅（上一版快照里 `DragonAnimations.FLY_IDLE` 指向的片段尚不存在） |
| `ClientFlightHandler` 使用 2.0.70 才有的 `horizontalVelocity` 变量 | ✅ |
| `dragonsurvival.mixins.json` 与 2.0.70 jar 内逐条比对 | **47 common / 48 client，差异 0** |

### 0.3 编译实证：BeLoong 在 2.0.70 下**编译通过**

```
> Task :compileJava
注: SpongePowered MIXIN Annotation Processor Version=0.8.7
警告: Unable to locate obfuscation mapping for @Shadow field   (PossibleBiomesFilterMixin.java:77)
警告: Unable to locate obfuscation mapping for @Accessor target values (ParameterListAccessor.java:43, :54)
3 个警告
BUILD SUCCESSFUL in 16s
```

3 条警告**全部是既有的**（`PossibleBiomesFilterMixin` / `ParameterListAccessor` 的 `@Shadow`/`@Accessor` 未加 `remap = false`，`memory/project-context.md` 已记录），**没有新增警告、没有错误**。

---

## 1. 结论摘要

**本次审查范围内的不兼容问题共 5 项，无 Critical、无启动期崩溃风险**（完整编号清单见 §1.1）：

| 级别 | 项 |
|---|---|
| 🟠 I-1 Important | `ManaLossHandler` 会静默扣玩家经验（§4.1） |
| 🟡 I-2 Minor | `ClientFlightHandlerMixin` 生存路径修正不完整、注释/算式与实际不符（§4.2，**较上一版已降级**） |
| 🟡 I-3 Minor | `neoforge.mods.toml` 的 GeckoLib 下限 `[4.8,)` 严于 DS 的 `[4.6,)`（§5） |
| 🟡 I-4 Minor | `ProjectileDamageEffectMixin` 的 HEAD-cancel 复制了目标方法体（§4.4） |
| 🟡 I-5 Minor | `DragonDestructionHandlerMixin` 依赖类级 lambda 序号（§4.5） |
| ❓ 未决 | `stableHover` 是 SERVER 配置却在客户端读（§4.3，需专用服务器实测，暂不计入问题清单） |

### 1.1 问题清单（按严重性编号）

| 编号 | 严重性 | 问题 | 位置 | 性质 |
|---|---|---|---|---|
| **I-1** | 🟠 Important | `mana_loss` 效果在龙的法力耗尽后持续扣玩家经验（≈1 点/tick ≈ 20/秒）；2.0.70 把开关默认设为 true（老存档同样生效），唯一开关是客户端 GUI 按钮，且**数据包级关闭途径已被 DS 删除** | `registry/ManaLossHandler.java:50` | 行为级；预存在于 2.0.67，2.0.70 使其更难关掉 |
| **I-2** | 🟡 Minor | 悬停修正在生存路径只清 `ax/az`、不清 `ay`，而 DS 的 y 项 `-gravity + ay` 与 `stableHover` 无关 ⇒ 类头声称的"稳定悬停漂移"实际只在创造模式成立；且 `:131-133` 注释（"总重力达到 -(gravity×2)"）与 DS 实际算式相反 | `mixin/dragonsurvival/ClientFlightHandlerMixin.java:119-137` | 逻辑/文档级；需实机复验，非崩溃 |
| **I-3** | 🟡 Minor | BeLoong 声明 `geckolib [4.8,)`，DS 只要求 `[4.6,)`（DS 开发版本 4.7.5.1）⇒ GeckoLib 4.6/4.7 的组合可满足 DS 却会拒绝加载 BeLoong | `templates/META-INF/neoforge.mods.toml:127` | 依赖声明级；不影响当前整合包（实锁 4.9.2） |
| **I-4** | 🟡 Minor | 取消路径逐行复制了 `apply` 四条语句中的三条 ⇒ DS 以后往 `apply` 里加逻辑会在该路径被静默跳过。今天完全等价 | `mixin/dragonsurvival/ProjectileDamageEffectMixin.java` | 维护性；无当前行为偏差 |
| **I-5** | 🟡 Minor | 注入目标名 `lambda$destroyBlocksInRadius$1` / `lambda$checkAndDestroyCollidingBlocks$0` 的序号来自**类级** lambda 计数器 ⇒ DS 在前面任意位置新增一个 lambda 即重编号，而 `defaultRequire=1` 会让它变成启动期硬崩。本次 2.0.70 未重编号 | `mixin/dragonsurvival/DragonDestructionHandlerMixin.java:35,52` | 脆弱性；当前安全 |

### 1.2 状态对账（2026-09-23，用户裁定后）

| 编号 | 状态 | 依据 / 裁定 |
|---|---|---|
| **I-1** | ✅ **已解决** | `ManaLossHandler:49-66` 改为把请求量夹到可用法力以内（`Math.min(deduction, magic.getAvailableMana())`）。`request <= pureMana` 使 `ManaHandler.consumeMana:113` 的 `pureMana < manaCost` **恒为假** ⇒ 扣经验分支结构上不可能命中；DS 在 `:97` 的创造模式 / `SOURCE_OF_MAGIC` 早退仍照旧生效 |
| **I-2** | ✅ **已解决** | `ClientFlightHandlerMixin:124` 已无条件 `setAy(0.0)`；旧错误注释被 `:140-144` 取代；类头 javadoc 已改写 |
| **I-3** | ✅ **已解决（用户手动调整）** | `neoforge.mods.toml` 的 GeckoLib 下限已由 `[4.8,)` 改为 **`[4.6,)`**，与 DS 声明一致 |
| **I-4** | 🚫 **不修复（用户裁定）** | 理由：当前可正常使用。HEAD-cancel 复制目标方法体的形态保留，作为已知维护性风险 |
| **I-5** | 🚫 **不修复（用户裁定）** | 理由：当前可正常使用。裸 lambda 序号保留，作为已知脆弱性（DS 重编号即启动期硬崩，升级 DS 时必须复核） |
| **❓ 未决** | ✅ **已解决（判定为误报）** | NeoForge 自带 SERVER 配置同步（`net.neoforged.neoforge.network.ConfigSync` 字节码首句即 `ModConfig$Type.SERVER` → `getConfigSet`），连接配置阶段推送；且项目**刻意**读 DS 同一个字段（判定前提与 DS 物理同源）。原自建同步包方案已回退 |

> 同时期还发现并处理了 9 项「飞行系统冲突」（F-1..F-9），见
> `docs/reviews/2026-09-23-flight-system-conflicts-and-creative-flight-comparison.md`。
> 其中 8 项已解决、F-8 部分解决。

**核验覆盖面**：17 个 DS mixin 目标全部仍可解析，约 30 个 DS API 成员在 2.0.70 jar 内逐一存在且描述符一致，`gradlew build` 通过。

> **与上一版报告的关系**：上一版基于 2026-08-26 的中间态源码（当时仓库更新失败），本次已用 **2.0.70 正式产物**重跑全部核验。**§4.2 的结论被更正**（原判"失去对闸门的控制"在 2.0.70 下不成立），详见 §6。

---

## 2. 2.0.67 → 2.0.70 的实际变更面

### 2.1 BeLoong 的 17 个 mixin 目标中，只有 3 个文件变了

| DS 文件 | 改动 | 对 BeLoong 的意义 |
|---|---|---|
| `registry/dragon/ability/entity_effects/DamageEffect.java` | +9 / −2 | 仅 `calculate` 方法体：新增 5 个表达式变量（`ability_level` / `experience_level` / `experience_points` / `growth` / `current_movement_speed`）。**`getDescription` 未变**，`level()` 调用点仍**恰好一处**（jar 字节码偏移 41；偏移 23 那处在 `apply` 里）⇒ `@Redirect` 唯一性成立 ✅ |
| `client/handlers/ClientFlightHandler.java` | +65 / −14 | 飞行加速闸门与控制流重写 + 新增 `flightZoom(ViewportEvent.ComputeFov)`（详见 §4.2） |
| `common/handlers/DragonGrowthHandler.java` | +6 | `isGrowthAllowed` 新增天花板攀爬早退；`onPlayerUpdate` 与其中的 `ticksToGrowth(INTERVAL)` 调用**未动** ⇒ `@Redirect` 仍有效 ✅ |
| 其余 14 个目标文件 | **无改动** | blob 与 2.0.67 基线逐一相同 |

### 2.2 新增 / 删除的类

| 类型 | 类 |
|---|---|
| 新增 | `network/magic/SyncExperienceManaConversion`、`util/CeilingClimbDimensions`、`util/IBoundingBoxOffset` |
| 删除 | `registry/datagen/datapacks/DisableExperienceConversionDatapack`（经验转魔力的**数据包级关闭途径被移除**） |

### 2.3 资源侧

- `assets/dragonsurvival/animations/dragon_center.json`：片段 **102 → 104，删除 0 个**，新增 `continuous_0` 与 `fly_idle`。
  ⇒ BeLoong 用的 `cast_mass_buff` / `magic_alt` / `idle` **全部健在**，动画名无断裂。
- `no_experience_conversion` 内置数据包：2.0.70 jar 内条目数 **0**（确认仍是移除状态）。

### 2.4 依赖声明

2.0.70 的 `neoforge.mods.toml` 依赖集合与 2.0.67 **完全一致**（`neoforge [21.1.186,)` / `geckolib [4.6,)` / `geckoanimfix` incompatible / `trinketsandbaubles` incompatible）；`patchouli` 被**注释掉**。
⇒ **没有新增必需前置**。`META-INF/jarjar/mixinsquared-forge-0.3.3.jar` 仍在，仍是 DS 自带，BeLoong 无需声明。

---

## 3. Mixin 逐条核验（对 2.0.70）

| # | BeLoong mixin | 目标 | 2.0.70 判定 |
|---|---|---|---|
| 1–6 | `{BlockBreak,BlockConversion,BlockHarvest,Bonemeal,ExplodeBlock,Fire}EffectMixin` | 各 `apply(ServerPlayer,DragonAbilityInstance,BlockPos,Direction)` HEAD cancel | ✅ 六个签名逐一相同 |
| 7 | `DamageEffectMixin` | `getDescription` → `@Redirect DragonAbilityInstance.level()I` | ✅ `getDescription` 未变，方法内 `level()` 唯一 |
| 8 | `ProjectileDamageEffectMixin` | `apply(Projectile,Entity,int)` HEAD cancel | ✅ 存在（另有桥接方法 `apply(Projectile,Object,int)`，按名选择器取到真实方法，上一轮实机日志已证） |
| 9 | `ClientFlightHandlerAccessor` | `ax` / `ay` / `az` | ✅ 三个 `static double` 字段均在 |
| 10 | `ClientFlightHandlerMixin` | `flightControl(ClientTickEvent$Pre)` TAIL | ⚠️ 注入解析 ✅，但语义假设需修正（§4.2） |
| 11 | `ToggleFlightMixin` | `lambda$handleServer$1(IPayloadContext,ToggleFlight)→Result` | ✅ 名字与描述符未变；**注意 DS 新增了 `lambda$handleServer$2`**（`thenAccept` 的消费者），BeLoong 取消的是 `$1`，`$2` 仍会把 `Result.NONE` 回发客户端 → `handleToggleResult` 对 `NONE` 无提示，行为安全 |
| 12 | `DragonStateHandlerMixin` | `<init>` RETURN + `@Shadow LargeDragonDestruction` | ✅ 字段与枚举均在，无显式构造器 ⇒ `<init>` 唯一 |
| 13 | `DSAttributesMixin` | `<clinit>` RETURN + `attachAttributes` TAIL | ✅ `REGISTRY` / `attachAttributes(EntityAttributeModificationEvent)` 均在 |
| 14 | `MixinDragonGrowthHandler` | `onPlayerUpdate` → `@Redirect DragonStage.ticksToGrowth(I)D` | ✅ 方法内该调用仍唯一 |
| 15 | `TreasureBlockMixin` | `tick(BlockState,ServerLevel,BlockPos,RandomSource)` HEAD cancel | ✅ |
| 16 | `DragonDestructionHandlerMixin` | 两个 lambda × 3 处 INVOKE | ✅ **lambda 名未重编号**（见 §4.5 的脆弱性说明） |
| 17 | `ModifierMixin` | `getModifierDescription`→`getFormattedDescription(int,boolean)` `@ModifyVariable index=1` | ✅ |

### 3.1 DS API 调用点（对 2.0.70 jar 逐一 `javap` 核验，全部命中）

| 类别 | 成员 | 结果 |
|---|---|---|
| 能力效应 | `AbilityEntityEffect.apply/entityCodec/getDescription` | ✅ |
| 属性 | `DSAttributes.REGISTRY` / `FLIGHT_SPEED` / `DRAGON_ABILITY_DAMAGE` / `attachAttributes` | ✅ |
| 飞行 | `ServerFlightHandler.isFlying/isGliding/isSpin/stableHover/maxFlightSpeed`、`FlightData.getData/areWingsSpread/hasFlight/isWingsSpread` | ✅ |
| 魔法 | `MagicData.getData/getCurrentMana/getAvailableMana/adjustMana/usesExperienceForMana`、`ManaHandler.consumeMana/replenishMana`、`SyncMana(float,boolean)` | ✅ |
| 能力实例 | `DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS` / `level()` / `getMaxLevel()` | ✅ |
| 爪槽 | `ClawInventoryData.getData/switchedTool/switchedToolSlot` | ✅ |
| 财宝 | `TreasureRestData.getData/isResting`、`TreasureBlock.LAYERS` | ✅ |
| 祭坛/水系 | `AltarData.getData/altarCooldown/hasUsedAltar/isInAltar`、`ServerConfig.altarUsageCooldown`、`Functions.secondsToTicks/fromTicks/format`、`OpenDragonAltar(List<SpeciesEntry>)`、`DragonSpecies.getSpecies(ServerPlayer,boolean)` | ✅ |
| 召唤 | `DSDataAttachments.SUMMON`、`SummonData.isOwner` | ✅ |
| 龙形态 | `DragonStateProvider.isDragon/getOptional/getData` | ✅ |
| 方块身份 | `DSBlocks.DARK_VAULT`（注册名 `dragonsurvival:dark_vault`） | ✅ |
| 结构 id | `dragonsurvival:dragon_hunters_castle` | ✅ 2.0.70 jar 内仍在 |
| 包 | `SyncWingsSpread(int,boolean)` | ✅ |

### 3.2 数据驱动内容

3 个技能 JSON 位于 `data/beloong/dragonsurvival/dragon_ability/`，注册表键 `ResourceKey.createRegistryKey(DragonSurvival.res("dragon_ability"))` ⇒ 路径前缀 `dragonsurvival/dragon_ability`，技能 id 正确解析为 `beloong:air_strike` 等。
**窗口内没有任何技能编解码器语义变更**（`registry/dragon/ability/**` 只有 `DamageEffect` 的方法体与 datagen 测试数据变化），字段名/必填性/动画层枚举（`AnimationLayer` = BASE/BREATH/BITE）/`SimpleAbilityAnimation` 的必填 `locks_neck`+`locks_tail` 全部核对通过。

### 3.3 Mixin 配置重叠

BeLoong 与 DS 只有**一个**类级重叠 —— `VaultBlockEntity$Server`：DS 打 `isValidToInsert`（`@ModifyReturnValue`）与 `tick`（`@Inject TAIL`），BeLoong 打 `tryInsertKey` 内 `unlock(...)` 的 INVOKE。**不同方法，无注入点冲突、无 `require` 争用**；语义上是顺序依赖（DS 放宽的校验决定钥匙是否被接受，BeLoong 只在下游成功路径触发），方向正确。
DS 的 mixin 列表 2.0.67 → 2.0.70 **零增减**，无新增重叠面。

---

## 4. 需要处理的发现

### 4.1 🟠 `ManaLossHandler` 会扣玩家经验

`registry/ManaLossHandler.java:49-50` 每 tick 调 `ManaHandler.consumeMana(player, 0.025f × (amplifier+1))`。2.0.70 的 `consumeMana` 在可用法力不足时走经验结算：`javap -c` 可见 `ManaHandling.manaXpConversion()`（偏移 146）→ `Player.giveExperiencePoints(int)`（152）→ `MagicData.setCurrentMana(player, 0)`（158）。

量级：出厂 `ManaHandling.DEFAULT = (0.1, 0.25, 9)`，`convertMana` 对负数取 `floor` ⇒ `floor(-0.025/0.1) = -1` ⇒ **≈1 点经验/tick ≈ 20 点/秒**（amplifier 0–3 均为 −1/tick）。

2.0.70 相对 2.0.67 的变化（都让这件事**更难关掉**，但不是它引入的）：

| 变化 | 证据 |
|---|---|
| 新增 per-player 开关，**默认 true**（老存档也生效） | jar 内 `MagicData` 有 `private boolean useExperienceForMana` + `usesExperienceForMana()` + `setUseExperienceForMana(boolean)`；源码 `:802` 缺字段时按 true 反序列化 |
| 结算分支现在多一道 `usesExperienceForMana()` 门 | jar `javap -c` 偏移 69/124 处 `MagicData.usesExperienceForMana:()Z` |
| 唯一开关是**客户端 GUI 按钮** | `SyncExperienceManaConversion`（2.0.70 新增包） |
| **数据包级关闭途径被删除** | `DisableExperienceConversionDatapack` 被删；2.0.70 jar 内 `no_experience_conversion` 条目数 **0** |

**建议**：若 `mana_loss` 只想掏空法力条，改走 `MagicData.getData(player).adjustMana(player, -deduction)`（jar 内 public），或先判 `getAvailableMana() >= deduction`。**这是行为变更，需产品确认** —— 也可能扣经验正是设计意图。

### 4.2 🟡 `ClientFlightHandlerMixin`：更正后降级（重要）

**上一版结论（基于 2026-08-26 中间态快照）**：DS 把闸门改成对**三轴**求模（`new Vec3(ax,ay,az).length()`），BeLoong 从不零 `ay`，因此"失去了对闸门的控制"。

**2.0.70 的事实推翻了这条**：

```java
// ClientFlightHandler.java:404-407（2.0.70）
double speedThreshold = flightSpeedMultiplier;
Vec3 horizontalVelocity = new Vec3(ax, 0.0D, az);          // ← 只取水平分量
if (ServerFlightHandler.isGliding(player) || horizontalVelocity.length() > speedThreshold) {
...
} else if (!ServerFlightHandler.isGliding(player)) {        // :460 互斥
```

`ay` 不在内。BeLoong 在 `flightLevel >= 1` 时清零 `ax/az` ⇒ `horizontalVelocity.length() == 0` ⇒ 闸门为假 ⇒ 走 `else if` 悬停分支。**旧不变量在 2.0.70 下基本恢复。**

**而且这个闸门在当前配置下根本不可达**：`ax/az` 各被钳到 `±0.4 × maxFlightSpeed × flightSpeedMultiplier`（`:436-437`，`ServerFlightHandler.java:50` `maxFlightSpeed = 0.3`，`flight_speed` 属性基础 1.0），故 `|horizontalVelocity| ≤ √2 × 0.12 ≈ 0.17`，而阈值 `speedThreshold = flightSpeedMultiplier ≈ 1.0`。
⇒ 非滑翔龙无输入时**永远走悬停分支**，BeLoong 的假设成立。下面的差异只在"有人把 `dragonsurvival:flight_speed` 调到 ≈0.17 以下"或 DS 再改钳制时才可观察。

**仍然成立的两点**（与 DS 版本无关的既有缺陷）：

1. **`stableHover` 不改变 y 项，所以只清 `ax/az` 修不掉漂移**。DS 里 `stableHover` 的唯一作用是给 `ay` 设下限：

```java
// :480-482
if (ServerFlightHandler.stableHover && !movement.jumping && !movement.shiftKeyDown
        && !ServerFlightHandler.isSpin(player) && !ServerFlightHandler.isGliding(player)) {
    ay = Math.max(ay, gravity * 1.1);
}
// :524-526
} else if (wasFlying) {
    double yMotion = ToggleFlight.hasEnoughFoodToStartFlight(player) ? -gravity + ay : -(gravity * 4) + ay;
```

y 项在 `stableHover` 真/假时完全相同。BeLoong 的生存路径只清 `ax/az`、**不清 `ay`**；只有创造路径（`ClientFlightHandlerMixin.java:125-129`）清 `ay` 并写 `deltaMovement.y = 0`。
⇒ 类头声称要修的"稳定悬停漂移"实际只在创造模式成立。鉴于 2026-08-14 的审查曾**实机验证过功能正常**，这里建议重新推导而不是直接判缺陷。

2. **`ClientFlightHandlerMixin.java:131-133` 的注释是错的**：写的是「DS 的 stableHover=true 路径仅应用 -gravity；此处追加 -gravity 使总重力达到 -(gravity×2)，与 DS 原版 stableHover=false 一致」。如上，`stableHover=false` 不改变 y 项。

**建议**：把 `flightLevel >= 1.0` 路径按创造路径同样处理（清 `ay` + `setDeltaMovement(delta.x, 0, delta.z)`），改写注释与 `:480-482`/`:524-526` 对齐；**必须实机复验**。若想彻底摆脱对分支选择的依赖，可把注入点从 `TAIL` 移到 `ay = Math.max(...)` 之后。

### 4.3 ❓ `stableHover` 是 SERVER 配置却在客户端读（需实测）

```java
// ServerFlightHandler.java:76-78
@ConfigOption(side = ConfigSide.SERVER, category = "wings", key = "stable_hover")
public static boolean stableHover = false;
```

客户端读它有**两处**：DS 自己（`ClientFlightHandler.java:480`，悬停物理）与 BeLoong（`ClientFlightHandlerMixin.java:64`，整个修复的总开关）。

平台事实（本机实测）：`ModConfig.Type.SERVER` 是按世界落盘的 —— 同时存在
`【化龍】服务端\化龍本地测试服\world\serverconfig\`（专用服务器）与 `BeLoong-Core\run\saves\<存档>\serverconfig\`（客户端单人）。远端专用服务器的客户端没有对应世界目录，因此它能否读到服务器那份值需要一次实机确认。

**倾向性判断**：DS 自己的悬停物理也有同一依赖，若客户端读不到，DS 的 `stableHover` 在所有专用服务器上都是坏的 —— 那会是个人尽皆知的 bug。**大概率能读到**，但结论要用一条日志坐实。
配置侧已确认测试服是打开的：`【化龍】服务端\化龍本地测试服\config\dragonsurvival-server.toml` → `stable_hover = true`。

### 4.4 🟡 `ProjectileDamageEffectMixin` 用 HEAD-cancel 重写了目标方法体

取消路径逐行复制了 `ProjectileDamageEffect#apply` 四条语句中的三条（`amount().calculate(level)` / `target.hurt(...)` / `owner.setLastHurtMob(target)`）。今天完全等价（DS 属性字面默认 1 就等于跳过乘法），但 DS 以后往 `apply` 里加任何逻辑都会在这条路径上被静默跳过。
**建议**（可选加固）：改为对 `apply` 内唯一那处 `LivingEntity.getAttributeValue(Holder)D` 做 `@Redirect`，属性缺失时返回 `1.0D`，让 DS 自己的方法体继续跑。

### 4.5 🟡 `DragonDestructionHandlerMixin` 依赖 javac 的**类级** lambda 序号

`lambda$destroyBlocksInRadius$1` / `lambda$checkAndDestroyCollidingBlocks$0` 的 `$0/$1` 是**整个类的 lambda 计数器**（同 jar 内 `DSAttributes` 打出 `lambda$static$0…$11` 后接 `lambda$attachAttributes$12`，13 个 lambda 共用一条序列）。
**本次 2.0.70 未重编号**（已核验），但 DS 只要在 `DragonDestructionHandler` 里更靠前的任何位置加一个 lambda，名字就会变，而 `defaultRequire=1` 会把它变成**启动期硬崩**。
**建议**：为这两处加显式 `require`/`expect`，或改用描述符选择器，让失败更早更可读。

---

## 5. 依赖与元数据

| 项 | 现状 | 判定 |
|---|---|---|
| `build.gradle:137` | 已升到 `8871661` = **2.0.70** | ✅ 已解析、已编译通过 |
| DS 2.0.70 必需依赖集合 | 与 2.0.67 相同；`patchouli` 被注释掉 | ✅ **无新增前置** |
| `META-INF/jarjar/mixinsquared-forge-0.3.3.jar` | DS 自带，2.0.70 仍在 | ✅ BeLoong 不需要声明 |
| `neoforge.mods.toml:120` `dragonsurvival [2.0.53,)` | 2.0.70 满足 | ✅ 未过期（如需可提到 `[2.0.70,)`） |
| `neoforge.mods.toml:127` `geckolib [4.8,)` | 比 DS 的 `[4.6,)` **更严**；BeLoong 实际锁 GeckoLib **4.9.2**（`geckolib-388172-8350073.jar` 的 `mods.toml`） | ⚠️ **I-3**：会让"GeckoLib 4.6/4.7 + DS"的合法组合拒绝加载 BeLoong。请确认是否真需要 ≥4.8 API |
| `gradle.properties:14` `neo_version=21.1.236` | DS 开发用 21.1.241，必需范围 `[21.1.186,)` | ✅ 兼容（可选对齐 241） |

> **不在审查范围内**：整合包/服务端实际部署的 DS jar 版本。服务端只在整合包正式发布后更新，与本地依赖升级无关，故本报告不对其作任何判定。

---

## 6. 与上一版报告（2026-08-26 快照）的差异

| 项 | 上一版 | 本版（2.0.70） | 原因 |
|---|---|---|---|
| 参考源码 | 停在 2026-08-26，当天 fetch 失败 | `d6baf03b3`，已拉到最新，内容 = 2.0.70 | 用户本轮完成拉取 |
| 对照产物 | jar `8726322` = 2.0.67 | jar `8871661` = **2.0.70** | 本轮升级依赖 |
| `ClientFlightHandler` 闸门 | 「改成对三轴求模，含 `ay`，BeLoong 失去闸门控制」→ RISK 偏重 | **更正**：2.0.70 闸门是 `new Vec3(ax, 0.0D, az)`（**仅水平**），且在当前配置下不可达 ⇒ 旧不变量基本恢复，降级为 Minor（I-2） | 上一版基于 08-26 中间态，该中间态确实用过三轴；2.0.70 又改回水平 |
| `fly_idle` 片段 | 「枚举指向不存在的片段 ⇒ 快照是半成品」 | 2.0.70 已补上（片段 102→104，删除 0） | 中间态 → 正式版 |
| 编译验证 | 未做 | `gradlew build` **BUILD SUCCESSFUL** | 本轮依赖可解析 |
| 盲区（2.0.68/69/70） | 无法验证 | **已关闭**（拿到 2.0.70 产物） | —— |
| `ManaLossHandler` 扣经验 | 已发现 | **仍然成立**（I-1），且确认 2.0.70 的 `no_experience_conversion` 数据包依旧缺失 | —— |

---

## 7. 建议处理顺序

| 优先级 | 动作 | 位置 |
|---|---|---|
| 1 | 决定 `mana_loss` 是否该扣经验；如需只扣法力改走 `MagicData.adjustMana` | `registry/ManaLossHandler.java:50` |
| 2 | 重写 `ClientFlightHandlerMixin` 的悬停修正（补清 `ay`）并改掉错误注释；**实机复验** | `mixin/dragonsurvival/ClientFlightHandlerMixin.java:119-137` |
| 3 | 评估 GeckoLib `[4.8,)` 下限是否必要 | `templates/META-INF/neoforge.mods.toml:127` |
| 4 | 给 `DragonDestructionHandlerMixin` 两处 lambda 加显式 `require`/`expect` | `mixin/dragonsurvival/DragonDestructionHandlerMixin.java:35,52` |
| 5 | 专用服务器实测客户端能否读到 `stableHover` | 一次登录 + 一行日志 |
| 6 | 清理无用 lang 键（`dynamic_desc` 在整个 DS 里不存在）；`trigger_rate: 0.5` 浮点取模 ⇒ 实际每 tick 触发；`tornado` 2~6 级图标都指向 `tornado_1` | `lang/*.json`、`data/beloong/dragonsurvival/dragon_ability/*.json` |

---

## 附：证据来源

- **jar 字节码**：`curse.maven\dragons-survival-420799\8871661\...\dragons-survival-420799-8871661.jar`（`javap -p` / `javap -p -c`，JDK 21，`JAVA_HOME=D:\Java\jdk-21.0.11`）；对照基线 `8726322`（2.0.67）
- **新源码**：`开源模组参考文件\DragonSurvival\src\main\java\...`、`src\main\resources\...`、`src\generated\resources\...`
- **窗口 diff**：`git -C <DS> diff b59a5b309..HEAD`、`git diff --name-status`、`git diff --stat`
- **编译实证**：`gradlew.bat build --console=plain`（本轮 `BUILD SUCCESSFUL`）；`gradlew dependencies --configuration compileClasspath`
- **实机日志（2.0.67 基线）**：`BeLoong-Core\run\logs\debug.log`、`debug-1..5.log.gz` —— 17 条 `Mixing dragonsurvival.*` 全部成功，无注入错误
- **配置实证**：`run\config\dragonsurvival-server.toml:399` `stable_hover = true`；`【化龍】服务端\化龍本地测试服\config\dragonsurvival-server.toml`
