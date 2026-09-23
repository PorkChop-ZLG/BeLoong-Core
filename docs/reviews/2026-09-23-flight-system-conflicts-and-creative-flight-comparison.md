# 本模组飞行系统审查：与 DS 2.0.70 的冲突 + 稳定悬停与原版飞行的差异

> **审查对象**：`BeLoong-Core` `disaster2` @ `cac1e9c`（含 PR #7 合并 + DS 依赖升级）
> **对照 Dragon Survival**：2.0.70（jar `curse.maven:dragons-survival-420799:8871661`；本地源码 `d6baf03b3`）
> **对照原版**：NeoForge 21.1.236 反编译源码（`build\moddev\artifacts\neoforge-21.1.236-sources.jar`）
> 本报告为只读审查，未改动任何文件。

---

## 1. 本模组当前的飞行实现全貌

| 组件 | 位置 | 作用 |
|---|---|---|
| `flight_level` 属性 | `mixin/dragonsurvival/DSAttributesMixin.java` | 注册进 **DS 的** `DSAttributes.REGISTRY`（id `dragonsurvival:flight_level`，默认 0，范围 ±1024，可同步） |
| 等级语义 | `registry/ModAttributes.java:66-78` | `<0` 禁止展翅 / `=0` 可飞不可稳定悬停 / `≥1` 稳定悬停；属性缺失时**静默返回 0** |
| 展翅门控 | `mixin/dragonsurvival/ToggleFlightMixin.java` | 在 `lambda$handleServer$1` HEAD 拦截：翅膀收起 + 等级 `<0` → 返回 `Result.NONE` |
| 禁空效果 | `registry/FlightBanEffect.java` | 每级 −1 飞行等级；`<0` 时强制收翅 + 同步 + 提示 |
| 悬停速度处理 | `mixin/dragonsurvival/ClientFlightHandlerMixin.java` | `flightControl` TAIL：清 `ax/az/ay`、锁高度、滑翔时速度对齐视线 |
| 悬停重力处理 | `mixin/dragonsurvival/LivingEntityStableHoverMixin.java` | `LivingEntity.travel` 重力 STORE：置 0 |
| 字段访问器 | `mixin/dragonsurvival/ClientFlightHandlerAccessor.java` | `ax/ay/az` |
| 触发条件 | `Config.FIX_STABLE_HOVER`（client，默认 true）∧ DS `stableHover`（server，默认 **false**）∧ `flight_level ≥ 1` ∧ 展翅 ∧ 有飞行能力 ∧ 非旋转 ∧ 无垂直输入 |

---

## 2. 与 DS 2.0.70 的冲突（按严重性编号）

### 🔴 F-1 严重 — 与 DS 自己的 mixin **打在同一注入点**

```
DS   mixins/LivingEntityMixin.java:169-177
     @ModifyVariable(method = "travel", at = @At(value = "STORE", ordinal = 0))
     private double dragonSurvival$handleStableSwim(final double gravity) {
         if (this instanceof Player p && p.getExistingData(SWIM).map(d -> d.hasStableSwim(...)).orElse(false)) return 0;
         return gravity;
     }

本模组 mixin/dragonsurvival/LivingEntityStableHoverMixin.java:26-27
     @ModifyVariable(method = "travel", at = @At(value = "STORE", ordinal = 0), remap = false)
     private double beloong$disableGravityForStableHover(double gravity) { ... return 0.0; ... }
```

**目标指令完全相同**。Mixin 对同一节点的多个 `@ModifyVariable` 会串联，前一者的返回值成为后一者的输入。

**数值结论（与顺序无关）**：DS 侧要么返回常量 `0`、要么返回输入原值；本模组同样 ⇒ **任一方要求置 0 即为 0，不会互相破坏**。这一条是好消息。

**但仍是问题**：
1. 机制重复——DS 已有 `hasStableSwim` 做"水中取消重力"，本模组用另一套条件（`stableHover + flightLevel≥1`）再做一遍，两套并行、无显式 `priority`，执行顺序无约定；任一方日后改动都会波及另一方。
2. DS 的 `@Inject`（`LivingEntityMixin.java:181`）带 `@Local double gravity`，读的是**被改后**的值。已核验当前不冲突：熔岩路径本模组被排除，而 DS 只在 `if (isLavaSwimming)` 块（`:261`）用 `gravity`；水中路径（`:205-229`）不用 `gravity`。**但这是巧合级安全，不是设计保证**——DS 只要在水的分支里开始用 `gravity`，就会被本模组改变。

### 🔴 F-2 严重 — 滑翔被同时改造，与 DS 2.0.68/2.0.70 的修复正面相反

`ClientFlightHandlerMixin.java:135-143`：

```java
if (ServerFlightHandler.isGliding(player)) {
    Vec3 look = player.getLookAngle();
    double speed = delta.length();                 // 含 Y 分量
    if (speed > 1.0E-5) player.setDeltaMovement(look.scale(speed));   // 速度方向硬对齐视线
```

而同 tick 稍后的 `LivingEntityStableHoverMixin` 又把重力置 0（`isFlying()` 在滑翔时为 true）⇒ **滑翔变成"无重力成本 + 方向锁视线 + 速率保留"**。

与 DS 近两条版本刚做的修复方向相反：

| DS 提交 | 内容 |
|---|---|
| `f6507602e` | Fixed flight decelleration when switching from gliding to flying |
| `dfdbefcfc` | Fix some bugs with flight regarding **retaining momentum** after gliding |
| `40f44f2a4` / `f1e0d1bd4` | Fix **snapping** when transitioning between gliding and flying |

具体后果：
- **动量被抹掉**：任意时刻转向都是瞬时的；俯冲中抬头，`(0,−3,0)` 立刻变 `(0,+3,0)`。
- **滑翔代价消失**：DS 的滑翔设计建立在"俯冲换速度"的重力权衡上（`ELYTRA_FLY_DRAG`、`viewVector.y<0` 提速）；重力置 0 后可以无成本持续爬升。
- **动画失真**：`DragonEntity.java:654-667` 的滑翔动画按 `deltaMovement.y` 分档（`<−1 → FLY_DIVE_ALT`、`<−0.25 → FLY_DIVE`、`>0.5 → FLY`、否则 `FLY_SOARING`）。速度被强制对齐视线后，这套分档与实际动作脱节。
- **`AirStrikeEffect` 失衡**：该技能以 `isGliding` 为前提、以 `speed = deltaMovement.length()` 与 `min_speed: 0.8` 判定（`AirStrikeEffect.java:67-74`），撞击后收翅（`:134`）。速度方向锁视线后，这个技能从"靠动量撞上去"变成"**指哪打哪**"，难度显著下降；而速率被保留，`min_speed` 判定不受影响 ⇒ 只降低操作门槛、不降低数值门槛。
- **冲刺语义反转**：`isGliding` 要求 `player.isSprinting()`；而原版创造飞行里冲刺是"快 2 倍"。

### 🟠 F-3 中 — 水中零重力越过了 DS 的能力门

DS 把"水中不下沉"作为**能力**授予（`SwimData.hasStableSwim`，见 `LivingEntityMixin.java:172`）；本模组用 `flightLevel ≥ 1` 授予同样的效果，且：

```java
// LivingEntityStableHoverMixin.java:59-61
if (!player.isInWater() && !ServerFlightHandler.isFlying(player)) return gravity;
```

- 未检查 `player.isAffectedByFluids()`（DS 的 `handleDragonSwimming` 检查了，`LivingEntityMixin.java:196`）；
- 未检查 `player.canStandOnFluid(fluidState)`（DS 也检查了）⇒ **站在水面上的龙也不下沉**（此时 `onGround()` 为 false，早退保护不生效）。

⇒ 结果：任何"展翅 + 飞行等级≥1"的龙种都获得水中悬浮能力，不看它是否有 `stable_swim` 能力。这是**两套并行系统**，不是互补。

### 🟠 F-4 中 — `flightLevel < 1` 的"非稳定悬停"模拟**只覆盖了一半**

```java
// ClientFlightHandlerMixin.java:148
} else if (flightLevel < 1.0 && noMoveInput && !ServerFlightHandler.isGliding(player)) {
    ... player.setDeltaMovement(delta.x, delta.y - gravity, delta.z);
```

**先说一个对我上一轮结论的更正**：我在 PR 审查里判这段的注释（`:149-151`「追加 -gravity 使总重力达到 -(gravity×2)，与 DS 原版 stableHover=false 一致」）是错的。**重新逐行核对后需要更正**——注释的**结论是对的**：

| 情形 | DS `stableHover=true` | DS `stableHover=false` | 本模组（在 true 之上追加） |
|---|---|---|---|
| 水平不动、无垂直输入 | `ay = max(ay, 1.1g) × 0.9 = 0.99g` ⇒ `yMotion = -g + 0.99g = **−0.01g**` | `ay` 衰减到 ≈0 ⇒ `yMotion = **−g**` | `−0.01g − g = **−1.01g**` |

⇒ **≈ DS 的 stableHover=false（−1g），误差 1%**，等效是成立的。注释里"-(gravity×2)"这个**数值说法不对**（那是 DS 移动分支 `:497` 的 `-(gravity*2)`），但"与 stableHover=false 一致"的**判断正确**。

**真正的缺陷在另一个地方**：该分支要求 `noMoveInput`，而 DS 的 stableHover 差异**在水平移动时同样存在**（`:496-499`：`false → -(gravity*2) + y`，`true → -gravity + y`）⇒ **低飞行等级的玩家只要按住 WASD，就仍然享受 `stableHover=true` 的竖直阻尼（−g 而非 −2g）**，"无法稳定悬停"的设定在移动时失效。

### 🟠 F-5 中 — 依赖 DS 的 SERVER 配置在客户端判定

`ServerFlightHandler.java:77-78` 是 `@ConfigOption(side = ConfigSide.SERVER)`，却在客户端被**三处**读取：DS 自己（`ClientFlightHandler.java:480`）、`ClientFlightHandlerMixin.java:66`、`LivingEntityStableHoverMixin.java:32`。

`ModConfig.Type.SERVER` 按世界落盘（本机实测同时存在 `world\serverconfig\` 与 `saves\<存档>\serverconfig\`），远端专用服务器的客户端能否读到服务器那份值**未实测**。若读不到，整条悬停修复在多人环境静默失效。

### 🟡 F-6 轻 — 两处重复门控，且条件不一致

两个 mixin 各自重复 `FIX_STABLE_HOVER` / `stableHover` / `isDragon` / `isWingsSpread` / `hasFlight` / `flightLevel≥1` / `isSpin` 五到七个条件。`LivingEntityStableHoverMixin` **不检查 `noMoveInput`**（水平移动时也取消重力），而 `ClientFlightHandlerMixin` 检查——两处语义不同却各自为政，日后极易改漏一处。

### 🟡 F-7 轻 — 违反项目 mixin 分类约定

`LivingEntityStableHoverMixin` 打的是**原版** `LivingEntity`（DS 代码一行未改，只读 DS 状态），却放在 `mixin/dragonsurvival/`、`beloong.mixins.json` 条目前缀也是 `dragonsurvival.`。项目约定（`memory/project-context.md`）要求原版目标进 `mixin/minecraft/`、前缀 `minecraft.`。

### 🟡 F-8 轻 — 两个静默失败面

1. `ModAttributes.getFlightLevel` 在属性缺失时**静默返回 `0.0`**（`ModAttributes.java:74-76`），语义是"可飞、不可稳定悬停"⇒ DSAttributesMixin 若因 DS 变更而失效，表现为"悬停突然不工作"，无任何日志。
2. `flight_level` 注册进**DS 的** `DSAttributes.REGISTRY`；DS 将来若自己注册同名属性会撞名（当前 DS 无此属性，已 grep 确认）。

### ⚪ F-9 极轻 — `ClientFlightHandlerMixin` 文件末尾缺换行

---

## 3. 本模组稳定悬停 vs 原版创造模式飞行

原版创造飞行的完整实现（NeoForge 21.1.236 源码）：

```java
// Player.travel:1588-1594
if (this.abilities.flying && !this.isPassenger()) {
    double d2 = this.getDeltaMovement().y;                 // 记录飞行前 y
    super.travel(travelVector);                             // 照常跑物理（重力照算）
    Vec3 vec31 = this.getDeltaMovement();
    this.setDeltaMovement(vec31.x, d2 * 0.6, vec31.z);      // 之后把 y 覆写成 旧y×0.6
    this.resetFallDistance();
    this.setSharedFlag(7, false);
}
// LocalPlayer.aiStep:842-855（垂直输入冲量）
if (abilities.flying && this.isControlledCamera()) {
    int j = 0; if (input.shiftKeyDown) j--; if (input.jumping) j++;
    if (j != 0) setDeltaMovement(getDeltaMovement().add(0, j * abilities.getFlyingSpeed() * 3.0, 0));
}
// Player.getFlyingSpeed:2277-2280（水平速度）
if (abilities.flying && !isPassenger()) return isSprinting() ? flyingSpeed * 2.0F : flyingSpeed;   // 0.05
```

| 维度 | 原版创造模式飞行 | 本模组稳定悬停 |
|---|---|---|
| **触发条件** | `abilities.flying`（服务端同步的玩家能力） | 客户端配置 ∧ DS 服务端配置 ∧ `flight_level≥1` ∧ 展翅 ∧ 非旋转 ∧ 无垂直输入 |
| **生效范围** | 双端一致，服务端知道 | **仅客户端**（服务端只知道 DS 的 `FlightData`，原版 `abilities.flying` 仍为 false） |
| **垂直输入** | `aiStep` 中 ±0.15 **冲量**（累加到当前 y） | 交给 DS：`jumping → y = 0.4 + y`、`shift → y = −0.5 + y`（**绝对值设定，非冲量**） |
| **垂直阻尼** | 每 tick `y = 飞行前y × 0.6`（固有阻尼） | **无阻尼**；无垂直输入时被硬置 `y = 0` |
| **垂直稳态速度** | ≈0.225/tick（约 4.5 格/秒） | 上升 ≈0.31/tick（扣重力后，约 6.3 格/秒）、下降 ≈−0.5/tick（约 10 格/秒）⇒ **约 1.4× / 2.2×** |
| **垂直惯性** | 有：松开跳跃后仍上升约 4~6 tick（衰减 0.6^t） | 无：无输入立即归零 |
| **水平模型** | `moveRelative(0.05)` + 0.91 空气摩擦 ⇒ 稳态 ≈0.556（冲刺 ≈1.111） | DS 模型：向视角加速 + `ax/az` 钳到 ±0.12 + drag(0.99, 0.98) + 衰减 ×0.9；`maxForward = 0.8×flightSpeedMultiplier×2 ≈ 1.6` ⇒ **上限约 2.9×、起步更慢、松手滑更远** |
| **冲刺** | 飞行速度 ×2 | 不加速；冲刺反而触发滑翔 |
| **重力处理** | 照常施加，事后覆写 y（结果等价无效） | 在 `travel` 里**直接把重力置 0**（介入更早，中间量不同） |
| **坠落距离** | 每 tick `resetFallDistance()` | DS 已代劳（`ServerFlightHandler:184`）⇒ **等价** |
| **坠落伤害** | `mayFly()` 使其完全免疫 | DS 的 `enable_flight_fall_damage` 体系照常生效 ⇒ **不等价：仍可受飞行坠落伤害** |
| **落地** | `abilities.flying = false`（`LocalPlayer.aiStep:886`） | DS 的 `foldWingsOnLand` / `foldWingsThreshold`（按饥饿收翅） |
| **水中** | y 被覆写；仍不下沉 | 额外把重力置 0，且**对水面站立也生效**（见 F-3） |
| **滑翔** | 无此概念 | 被改造成"无重力 + 方向锁视线"（见 F-2） |
| **其它模组可见性** | 通过 `abilities.flying` / `mayFly()` 可见 | 不可见（未设置真实字段） |

### 结论

**只有"水平静止时不会下沉/上浮"这一点像创造飞行**。除此之外：

- **垂直**——没有原版的"冲量 + 每 tick ×0.6 阻尼"，而是硬置零；上下速度约为原版的 1.4×/2.2×，且**没有垂直惯性**；
- **水平**——完全是 DS 的模型（约 3 倍上限速度、强惯性），不是原版的 `moveRelative + 0.91 摩擦`；
- **冲刺**——语义相反；
- **实现层次**——纯客户端，服务端只通过 DS 的 `FlightData` 感知。

要真正"复刻创造飞行"，精确路径是上一轮给出的三段配方：`Player.travel` 里 `y = 飞行前y × 0.6`、`LocalPlayer.aiStep` 里 ±0.15 冲量、`getFlyingSpeed` 用 0.05（冲刺 0.1）——其中 `Player.travel` 恰好是 **DS 唯一不注入的 travel 相关类**，与本项目零注入点冲突。

---

## 4. 建议处理优先级

| 优先级 | 动作 | 对应 |
|---|---|---|
| 1 | 实测专用服务器客户端能否读到 `stableHover`（一条日志即可），否则整条修复在多人环境可能静默失效 | F-5 |
| 2 | 决定滑翔处置：要么排除滑翔（恢复 DS 原版），要么明确"稳定悬停时滑翔被接管"并接受与 DS 修复的冲突 | F-2 |
| 3 | 给 `LivingEntityStableHoverMixin` 补 `isAffectedByFluids()` / `canStandOnFluid()`，或改用 DS 的 `hasStableSwim` 判定，消除两套并行系统 | F-3 / F-1 |
| 4 | 把 `flightLevel<1` 的分支扩展到水平移动场景（去掉 `noMoveInput`），或在移动分支里按 `stableHover=false` 的 `−2g` 改写 | F-4 |
| 5 | 给两个同点 `@ModifyVariable` 显式 `priority`，并在类 javadoc 记录串联语义 | F-1 |
| 6 | 合并两处重复门控为一个共享判定方法 | F-6 |
| 7 | 按约定把新 mixin 移到 `mixin/minecraft/`，条目前缀改 `minecraft.` | F-7 |
| 8 | 给 `getFlightLevel` 的属性缺失路径加一条一次性 WARN | F-8 |

---

## 附：证据来源

- **本模组代码**：`mixin/dragonsurvival/{ClientFlightHandlerMixin,ClientFlightHandlerAccessor,LivingEntityStableHoverMixin,DSAttributesMixin,ToggleFlightMixin}.java`、`registry/{ModAttributes,FlightBanEffect}.java`、`ability/AirStrikeEffect.java`、`Config.java:21`、`beloong.mixins.json`
- **DS 2.0.70**：`client/handlers/ClientFlightHandler.java:404-407,480-481,496-499,513-527`、`server/handlers/ServerFlightHandler.java:77-78,168-190`、`mixins/LivingEntityMixin.java:169-181,196,205-229,261`、`common/entity/DragonEntity.java:648-685`
- **原版**：`build\moddev\artifacts\neoforge-21.1.236-sources.jar` → `Player.java:1576-1598,2277-2280`、`LocalPlayer.java:842-855,886`、`LivingEntity.java:2322-2343,2383,2432`；字节码 `neoforge-21.1.236-merged.jar`
- **NeoForge 事件时序**：`ClientHooks.java:1067-1070`（`ClientTickEvent.Pre` 在 `Minecraft#tick()` 头部触发）
