# 滑翔改造的两个严重缺陷 · 诊断报告

**日期：** 2026-10-01
**审查对象：** `master` @ `4bc8d32`（`ClientFlightHandlerMixin.java` 445 行，滑翔 rev 1–5）
**DS 参考源码：** `开源模组参考文件/DragonSurvival`，分支 `1.21.1`，HEAD `511692bea`（2026-09-27）
**DS 实际依赖产物：** `dragons-survival-420799-8973485.jar`（= 2.0.71）—— 关键结论已用 `javap` 与之交叉核对
**状态：** 已定位，**未修改任何代码**

---

## 缺陷 1（Critical）：人类仍可按 Ctrl 飞行 —— 滑翔接管漏了"是不是龙"这道门

### 一句话根因

DS 的 `isGliding()` / `isFlying()` **本身不含"是不是龙"的判定**：它们只读一个**人类也持有的 NeoForge 附件** `FlightData` 与 `Player` 自身状态。DS 自己**每一个消费者都额外补了 `isDragon()`**，**只有本模组的混入没补**。而龙→人切换时 DS **不会清理飞行状态**，那两个人形态下已无意义的布尔值仍是 `true`。

### 证据链

**① 谓词不含龙判定** — `ServerFlightHandler.java:168-171, 346-349`

```java
public static boolean isFlying(final Player player) {
    FlightData data = FlightData.getData(player);
    return data.hasFlight() && data.isWingsSpread() && !player.onGround()
        && !player.isInWater() && !player.isInLava() && !player.isPassenger();
}
public static boolean isGliding(Player player) {
    boolean hasFood = player.getFoodData().getFoodLevel() > flightHungerThreshold || player.isCreative();
    return hasFood && player.isSprinting() && isFlying(player) && !player.hasEffect(MobEffects.LEVITATION);
}
```

`javap` 核对：两者的常量池只引用 `FlightData` 的两个布尔与 `Player` 状态，**无 `DragonStateProvider`/`DragonStateHandler`**；`git blame` 显示这两处历史上就无龙判定。

**② `FlightData` 是人类也有的附件** — `DSDataAttachments.java:29`、`FlightData.java:34-43, 90-96`

```java
FLIGHT = REGISTRY.register("flight_data", () -> AttachmentType.serializable(FlightData::new).copyOnDeath().build());
// FlightData: public boolean areWingsSpread; public boolean hasFlight;   (Java 默认 false)
public static FlightData getData(final Player player) { return player.getData(DSDataAttachments.FLIGHT); }
public boolean isWingsSpread() { return hasFlight && areWingsSpread; }   // 注意：隐含要求 hasFlight
```

⇒ `FlightData.getData(人类)` 合法；`isWingsSpread()` 实际是 `hasFlight && areWingsSpread`。

**③ 龙→人切换**完全不碰** `FlightData`** — `DragonStateHandler.java:872-888`

```java
public void revertToHumanForm(final Player player, final boolean isDragonSoul) {
    if (ServerConfig.noHumansAllowed) { ...; return; }
    ClawInventoryData.reInsertClawTools(player);
    setSpecies(player, null, isDragonSoul);      // species=null ⇒ isDragon() 变 false
    setBody(player, null);
    setDesiredGrowth(player, NO_GROWTH);
    AltarData altarData = AltarData.getData(player);
    altarData.altarCooldown = ...; altarData.hasUsedAltar = true;
}
```

`setSpecies(..., null, ...)` 只走 `:458-461` 的 `species == null` 分支（`PenaltySupply.clear` + `DSModifiers.clearModifiers`），**不含 `FlightData`**。
调用点：祭坛 `AltarTypeButton:185`、`DragonSoulItem:207`、`DragonCommand:87`、`gametests/TestUtils:91`。

**④ DS 自己的技能生命周期也清不掉它们** — `FlightEffect.java:33-37, 56-63`

```java
public void apply(...)  { if (!(target instanceof ServerPlayer t) || !DragonStateProvider.isDragon(t)) return; ... }
public void remove(...) { if (isAutoRemoval) return;
                          if (!(target instanceof ServerPlayer t) || !DragonStateProvider.isDragon(t)) return; ... }
```

唯一写 `hasFlight = false` 的语句（`:68`）被上面两道守卫挡住。形态切换时**唯一**可能到达它的链路是
`SyncComplete.handleServer:71` → `refreshMagicData` → `MagicData.refresh:537-543`，而 `refresh` 在 `currentSpecies == null` 时**提前 return，连 abilities 都不清**；`DragonSoulItem:207` 与 `DragonCommand:87` 两条路径**连 `refreshMagicData` 都不调用**。
`javap` 核对 `SyncComplete.lambda$handleServer$1` 与 `FlightEffect.remove` 的字节码顺序与源码一致。

⇒ **`areWingsSpread` 的全部 7 个写入点与 `hasFlight` 的全部 4 个写入点中，没有任何一个由"形态切换"触发。**

**⑤ DS 自己的消费者全部额外查 `isDragon()`**（关键对照）

| 消费者 | 位置 | 龙判定 |
|---|---|---|
| 客户端飞行数学 | `ClientFlightHandler.java:342-343` | `getOptional(player).ifPresent(handler -> { if (handler.isDragon()) {` ✅ |
| 坠落伤害 | `ServerFlightHandler.java:151` | `handler.isDragon() && data.hasFlight() && ...` ✅ |
| 饥饿收翅 | `ServerFlightHandler.java:312-313` | `if (dragonStateHandler.isDragon())` ✅ |
| 落地收翅 | `ServerFlightHandler.java:117` | `if (!handler.isDragon()) return;` ✅ |
| 飞行攻击 | `ServerFlightHandler.java:221` | `if (!handler.isDragon()) return;` ✅ |
| **滑翔记账** | `ServerFlightHandler.java:173-190` | ❌ **无分流** |
| **撞墙伤害/收翅** | `ServerFlightHandler.java:192-214` | ❌ **无分流** |

⇒ **DS 的设计是"人类可以被服务端记账当成滑翔者，但绝不获得飞行权"**：飞行权只由客户端数学发放，而那里有 `:343` 的 `handler.isDragon()`。
（顺带：人类"滑翔"会命中第 6/7 条 ⇒ 跳过 `resetFallDistance`、可能吃 `flyIntoWall` 伤害并把翅膀收起而自愈。这是 DS 自身行为，不是本模组的缺陷。）

**⑥ 本模组恰好是那个没查的消费者**

| 位置 | 依据 | 龙判定 |
|---|---|---|
| HEAD `ClientFlightHandlerMixin.java:278` | `isGliding(player)` → 挂零重力修饰符 | ❌ |
| TAIL 滑翔分流 `:309` | `isGliding(player)` → 跟随视线 + 加速 | ❌ |
| `beloong$isEligible:392` | `DragonStateProvider.isDragon(player)` | ✅ **但它在滑翔分流 `return` 之后，对滑翔路径无效** |

### 复现与影响

- **最小复现**：龙形态 → 开飞行技能 + 展翅（`hasFlight` 与 `areWingsSpread` 皆为真）→ 用祭坛/龙魂变回人类 → 空中按住 Ctrl（`isSprinting`）+ 离地 ⇒ `isGliding(人类)` 为真 ⇒ 该人类的 `Attributes.GRAVITY` 被乘 0（**不掉落**），且 TAIL 给出方向与加速 ⇒ **Ctrl 飞行**。
- **影响面**：仅**滑翔路径**。`beloong$isEligible` 之后的分支（悬停锁定、非稳定模拟）本来就有 `isDragon` 检查，故不受影响。

### 自我更正（重要）

设计文档 §2.6 把 `flightControl` 的守卫记成"`player != null` / `!isPassenger` / `!isPaused` / `!LEVITATION`"**四道**，**漏了方法体内部的第三层 `if (handler.isDragon())`（`:343`）**。当初的结论来自对方法头做 `javap`，而这道门在 lambda 里，因而不可见——**正是这个遗漏让"注入点对人类无害"的判断成立**。今后凡引用"DS 方法体的守卫"，必须同时读 lambda 内部。

---

## 缺陷 2（Major）：速度远快于 DS 原版，且没有起步缓加速

### 根因：在 DS 已有的"爬升型累加器"之上，又叠了一个更大且无爬升的常数

DS 自己的前向推力是这样建立的 — `ClientFlightHandler.java:426-437, 445-454`

```java
if (viewVector.y < 0) { ax += (cos(yaw)*FS*2)/500; az += (sin(yaw)*FS*2)/500; }  // 低头：+0.004·FS/tick 线性爬升
else                  { ay = viewVector.y/4; ax *= 0.98; az *= 0.98; }           // 非低头：衰减
ax = Mth.clamp(ax, -0.4*speedLimit, 0.4*speedLimit);   // speedLimit = maxFlightSpeed*FS ⇒ 上限 0.12·FS
...
if (isGliding(player)) {
    if (viewVector.y < 0) deltaMovement = deltaMovement.add(ax, 0, az);          // 低头消费 ax/az
    else                  deltaMovement = deltaMovement.add(ax, ay, az);         // 否则消费 ay
    deltaMovement = deltaMovement.multiply(ELYTRA_FLY_DRAG);
```

⇒ **DS 的"缓缓加速"就是这个 0.004/tick、约 30 tick 爬满 0.12 的累加器**，而且**只在低头时累加**。

本模组 rev 3/4 又加了一个**常数**水平底座 `+0.25/tick`（`GLIDE_BASE_ACCEL`），并且**平视也施加、第 1 tick 就满额**：

| | DS 原版 | 本模组（rev 3/4） | 偏差 |
|---|---|---|---|
| 水平推力形状 | `+0.004·FS` 线性爬升，30 tick 饱和于 `0.12·FS` | **常数 `+0.25`，第 1 tick 满额** | 起步 ≈ **60 倍**；饱和值 ≈ **2 倍** |
| 是否看俯仰 | 只在低头（其余衰减） | **常开**（含完全平视） | — |
| 平视水平稳态 | ≈ `0.79` 格/tick（≈16 格/秒） | ≈ `0.25 / 10% = 2.5` 格/tick（≈**50 格/秒**） | ≈ **3 倍** |
| 低头时的总前向输入 | DS `ax/az`（≤0.12）+ `dM` | **DS 的 0.12 + 我们的 0.25**（重复叠加） | ≈ 2 倍以上 |
| 竖直每 tick 注入 | 只有重力 `0.11~0.18` | 镜像项 `look.y*(0.25+0.128·FS·min(h,1))`，45° 约 0.27~0.30 | ≈ 1.5~2 倍 |

⇒ **"过快"与"没有缓加速"是同一个原因的两个侧面**：一个**远大于 DS 饱和值、且不爬升**的常数，叠加在 DS 已有累加器之上。

（刹车率参照：水平 `f3 = 0.91` × `ELYTRA_FLY_DRAG.x = 0.99` ≈ 10%/tick；竖直 `0.98 × 0.98 = 0.9604` ≈ 4%/tick，再加 `:410-413` 的 `0.1·vd`。终端 ≈ 输入 / 刹车率。）

### 附带认识：DS 原版的平视前向速度**依赖重力**

重力时代平视滑翔会缓沉（终端约 0.79 格/tick），而那个下坠经 `:410-413` 的
`downwardMomentum = deltaMovement.y * -0.1 * verticalDelta * FS` 换成前向推力（平视约 `0.079`/tick）。
⇒ **一旦把重力归零，DS 侧"下坠换速度"的链路同时断掉**：这正是 rev 1 之后必须由本模组补能量的根本原因，也说明
"贴合 DS 原版速度"不能靠调一个常数达成，而必须**把重力原本间接提供的那部分补回来**（见设计文档）。

---

## 附 A：一处前提的核实 —— 滑翔**不走**原版 elytra 分支（`0.91` 摩擦照常生效）

独立子代理曾提出一条更正："滑翔走 `LivingEntity.travel` 的 `isFallFlying()` 分支（`:2282-2308`），
因此没有 `0.91` 摩擦，`0.91` 与 ELYTRA 是二选一；若按叠加估算，竖直终端会低 2.8 倍。"
**该更正是错的**，两条直接证据：

**① DS 从不设置 elytra 旗标。** 对 DS 全源码 grep `setSharedFlag|SharedFlag|startFallFlying|fallFlying =` ⇒ **零命中**。
而原版 `LivingEntity.java:3345-3347` 就是：

```java
public boolean isFallFlying() { return this.getSharedFlag(7); }
```

旗标 7 只由原版 `startFallFlying()`（需要胸甲槽有滑翔装备）置位。**滑翔中的龙并没有装鞘翅** ⇒ `isFallFlying()` 为 false ⇒
`travel` 落到 `:2322` 的 **`else`** 分支 ⇒ `f3 = 0.91`（`:2325`）与 `d2 -= d0`（`:2331`）、`d2 * 0.98`（`:2341`）**全都生效**。

**② DS 自己是"抄"了 elytra 分支，而不是"运行在"它里面。** 原版 `:2282-2307` 的 elytra 分支与 DS 的滑翔数学逐行同构：

| 原版 elytra 分支 | DS `ClientFlightHandler` |
|---|---|
| `:2292` `add(0, d0*(-1 + d5*0.75), 0)` | `:408`（乘 `FS`） |
| `:2293-2296` `d6` 向上回收 + 水平分量 | `:410-413`（乘 `FS`） |
| `:2298-2301` `d10*3.2` 抬头项 | `:415-419`（乘 `FS`） |
| `:2303-2305` `*0.1` 水平回正 | `:421-423`（乘 `FS`） |
| `:2307` `multiply(0.99, 0.98, 0.99)` | `:454` `ELYTRA_FLY_DRAG` |

DS 自己在 `ClientFlightHandler.java:149` 就写明"These are the drag values from **vanilla Elytra flying**. See
`isFallFlying()` section in `LivingEntity#travel()`"，`ServerFlightHandler.java:200` 亦有同源注释。
**若滑翔真的运行在 elytra 分支内，DS 再抄一遍同一套数学就是重复施加**——这正说明它抄的是"别处的代码"，而不是"自己所在的分支"。

⇒ **本报告 §"缺陷 2" 的速度对照表与刹车率（水平 `0.91 × 0.99 ≈ 9.91%/tick`、竖直 `0.98 × 0.98 = 3.96%/tick`
再加 `:410-413` 的 `0.1·vd`）成立**；子代理基于相反前提算出的终端数值（平视 0.379 / 45° 1.015 / 竖直 2.077）
**不可采用**。设计文档 D2 的 `A(θ) = g·(2 − 0.75·vd)`（= DS `:408` **加** 原版 `−g`）同样成立。

**仍被采纳的子代理发现**：`FS ≥ 1.5` 时本模组会数值发散（见附 B）；平视 `xRot = 0` 是使 `:410/:415/:421`
三项同时失效的奇异姿态；`noSpeedRequirementForVerticalAcceleration = true` 会显著放大抬头推力。

## 附 B：一处**rev 5 未覆盖**的潜在发散（`FLIGHT_SPEED > ~1.4`）

rev 5 给反馈项加了源封顶 `min(h, 1.0)`，但**封顶只在 `h > 1` 之后才起作用**；在 `h < 1` 区间，
`h ↔ |y|` 的环增益仍随 `FS` **平方**增长（环两侧各含一个 `FS`）：

```
gain(θ, FS) = [0.1·vd·FS / 0.099] · [0.128·FS·|sinθ| / (0.0396 + 0.1·vd)]
45° 时 ≈ 0.51 · FS²      ⇒ FS ≈ 1.4 时达到 1（临界）
```

DS 的体型数据给 `FLIGHT_SPEED` 约 ±0.2（默认 1），所以**正常玩法不会触发**；但数据包/成长阶段一旦把
`FLIGHT_SPEED` 抬到 1.5 以上就会指数发散（子代理以逐 tick 模型复现：`FS=1.5` 抬头 45° 达 1.7e2，`FS=2` 达 1e74，`FS=4` NaN）。
⇒ **设计 D5（删除正反馈项）顺带消除该隐患**，这也是删除它的第二个理由。

---

## 结论与后续

| # | 缺陷 | 性质 | 修法方向（详见设计文档） |
|---|---|---|---|
| 1 | 人类可按 Ctrl 飞行 | **Critical**：本模组把 DS 明确保留的"人类无飞行权"前提打破了 | 门的来源与 `ClientFlightHandler:343` **同源**（`isDragon`），HEAD 与 TAIL 都要 |
| 2 | 速度过快 + 无爬升 | **Major**：手感偏离原版 | 撤掉"大常数 + 常开"的做法，改为对齐 DS 的爬升结构与量级，并把重力原本间接提供的前向速度显式补回 |

**未做：** 未修改任何文件；未跑构建（本轮无需）。
**待办：** 设计文档（`docs/plans/2026-10-01-glide-human-gate-and-ds-speed-parity-design.md`）与计划文档，经确认后再动代码。
