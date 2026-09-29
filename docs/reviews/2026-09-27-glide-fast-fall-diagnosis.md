# 调查：Ctrl 滑翔时"下坠很快"的原因（只诊断，不修复）

- 日期：2026-09-27
- 触发：用户实测报告——用 DS 的 Ctrl 滑翔时下坠很快；本模组悬停修复开/关都一样；DS 稳定悬停开启；
  未安装本模组时 DS 滑翔下坠"正常的比较慢"
- 结论：**不是本模组引入的滑翔物理变化。** 本模组当前的飞行 mixin 对"真滑翔"（`isGliding() == true`）
  一行都不碰；而"开关无效"是 `stable_hover` 门控导致的**必然**现象。真正决定下坠快慢的是
  **DS 自己走哪个分支**，而该分支由 `isGliding()` 决定，`isGliding()` 又比"玩家以为在滑翔"窄得多。
- 本文只给结论与证据，**不含修复方案**（用户要求）。

---

## 1. 已证事实（可复核）

### 1.1 本模组对真滑翔零干预

`mixin/dragonsurvival/ClientFlightHandlerMixin.java`：

| 位置 | 行为 |
|---|---|
| `:83-89` HEAD `beloong$clearZeroGravity` | 每 tick **无条件**摘除零重力修饰符（只摘不加） |
| `:96-98` | `if (!Config.FIX_STABLE_HOVER.get()) return;` —— 配置关闭则 TAIL 整体为空操作 |
| `:105` | `if (!beloong$isEligible(player)) return;` |
| `:186-188` | `if (ServerFlightHandler.isGliding(player)) return false;` —— 滑翔被排除出判定 |

⇒ 只要 `isGliding()` 为真，TAIL 在第 105 行就退出；若配置关闭，更早在第 96 行退出。
**"修复开关开与关没有区别"在 `isGliding()` 为真的滑翔里是设计使然，不是 bug。**

### 1.2 零重力修饰符不可能"加快"下坠

`:74-75` 的 `ADD_MULTIPLIED_TOTAL = -1.0` 只会把 `Attributes.GRAVITY` 乘成 0，
`:214-224` 的挂载只发生在 `:129`（`flight_level >= 1` 的悬停分支，且要求 `flying`）。
把重力置 0 只可能让下坠**变慢**，且它每 tick 在 HEAD 被摘除，窗口只覆盖一次 `travel`。
⇒ 症状是"更快"，所以与这个修饰符无关。

### 1.3 DS 2.0.70 → 2.0.71 的飞行代码逐字节相同

对 `8871661`（2.0.70）与 `8973485`（2.0.71）两个产物做
`javap -p -c by.dragonsurvivalteam.dragonsurvival.client.handlers.ClientFlightHandler`，
`Compare-Object` 差异行数 **0 / 1912 行**。⇒ 依赖升级不是原因。

### 1.4 DS 的下坠量级完全由"走哪条分支"决定

`ClientFlightHandler.java`（DS 检出 `511692bea`）关键结构：

```
:380  if (isFlying(player)) {
:395      double gravity = player.getAttributeValue(Attributes.GRAVITY);
:407      if (isGliding(player) || horizontalVelocity.length() > speedThreshold) {
:408          deltaMovement += (0, gravity * (-1 + verticalDelta*0.75), 0)      // 平视 ≈ -0.25g
:445          if (isGliding(player)) { ... 滑翔专用动力学 ...; return 分支结束 }
:460      } else if (!isGliding(player)) {
:481              if (stableHover && ...) ay = Math.max(ay, gravity * 1.1)     // ← stableHover 只在这里
:496              if (!stableHover) deltaMovement = (x, -(gravity*2) + y, z)   // ← 和这里
:499              else              deltaMovement = (x, -gravity     + y, z)
:525              yMotion = hasEnoughFoodToStartFlight ? -gravity + ay : -(gravity*4) + ay
```

**`stableHover` 在整个文件里只出现在 `:480` 与 `:496`（grep 全类仅 2 处），而这两处都在
`:460` 的"非滑翔"分支内 ⇒ 对 `isGliding() == true` 的真滑翔完全无影响。**
所以"DS 稳定悬停开启"这一条件在真滑翔里既不改变 DS、也不改变本模组。

叠加原版 `LivingEntity.travel`（`:2221` `d0 = getGravity()` → `:2331` `d2 -= d0` → `:2341` `d2 * 0.98F`）后的净值：

| 状态 | DS 竖直项 | 加原版后 |
|---|---|---|
| 真滑翔（`isGliding()`） | `-0.25g` | **≈ −1.25g**（与 `stable_hover` 无关） |
| 非滑翔快速飞行/悬停，`stable_hover=true` | `-1g` | ≈ −2g |
| 同上，`stable_hover=false` | `-2g` | **≈ −3g** |
| 非滑翔 + 饱食度 ≤ `flight_hunger_threshold` | `-(gravity*4)` | **≈ −5g** |

（命令 `DragonBodies.java:90,135` 还给玩家挂了 `ADD_MULTIPLIED_TOTAL +0.1/+0.2` 的重力修饰符，
所以 `g` 实际是 0.088~0.096 而非 0.08。）

**注意区分两个同名概念**（易混，且直接影响滑翔快慢）：

| 名字 | 实际是什么 | 用在哪 |
|---|---|---|
| `flightSpeedMultiplier`（局部变量） | **`dragonsurvival:flight_speed` 属性值**（`ClientFlightHandler.java:344` `player.getAttributeValue(DSAttributes.FLIGHT_SPEED)`） | `:404` 的 `speedThreshold`、`:411` 抬升系数、`:417/:427/:435` 的缩放 |
| `flight_speed_multiplier`（DS 配置键） | 映射到 `ServerFlightHandler.maxFlightSpeed`，默认 `0.3` | `:354/:377/:435` 的上限钳制 |

⇒ 滑翔那条 `:410-413` 的**抬升项**系数就是 `FLIGHT_SPEED` 属性：
`downwardMomentum = deltaMovement.y * -0.1 * verticalDelta * FLIGHT_SPEED`（`deltaMovement.y < 0` 时为正 ⇒ 向上）。
**`FLIGHT_SPEED` 越低，滑翔抬升越弱、下坠越快**；它由龙种/成长阶段/体型的数据驱动，
本模组未对其施加任何 modifier（已 grep 确认）。

---

## 2. 根因：`isGliding()` 比"玩家以为在滑翔"窄得多

```java
// ServerFlightHandler.java:346-349
public static boolean isGliding(Player player) {
    boolean hasFood = player.getFoodData().getFoodLevel() > flightHungerThreshold || player.isCreative();
    return hasFood && player.isSprinting() && isFlying(player) && !player.hasEffect(MobEffects.LEVITATION);
}
```

**它要求 `player.isSprinting()`。而原版客户端会主动取消冲刺**（`LocalPlayer.java:785-794`）：

```java
if (this.isSprinting()) {
    boolean flag7 = !this.input.hasForwardImpulse() || !this.hasEnoughFoodToStartSprinting();
    boolean flag8 = flag7 || this.horizontalCollision && !this.minorHorizontalCollision || ...
    ... else if (flag8) this.setSprinting(false);
}
```

且 `hasEnoughFoodToStartSprinting()`（`:1125-1127`）是
`isPassenger() || foodLevel > 6.0F || mayFly()` —— **阈值是 6**。

取消冲刺的条件因此包括：**松开前进键 / 饱食度 ≤ 6 / 撞到墙（`horizontalCollision`）/ 在水面。**

### 2.1 与本整合包配置的致命错配

| 配置 | 值 | 阈值 |
|---|---|---|
| 原版"能否持冲刺"（`LocalPlayer.hasEnoughFoodToStartSprinting`） | 硬编码 | `isPassenger() \|\| foodLevel > 6 \|\| mayFly()`（生存龙 `mayFly() == false`） |
| DS `flight_hunger_threshold`（`化龍` 服务端 / `BeLoong-Server`） | **4** | `foodLevel > 4` |
| DS `flight_hunger_threshold`（`BeLoong-Core/run` 开发环境） | 6 | `foodLevel > 6` |

⇒ 在整合包里，**饱食度 5 或 6 时**：DS 的 `hasFood` 判定为真（"允许滑翔"），
但原版已把冲刺取消 ⇒ `isSprinting() == false` ⇒ **`isGliding()` 为假**。

⇒ 此时玩家按住 Ctrl+W 主观上"在滑翔"，而 DS 与判定依据都认为**不在滑翔**：
DS 走 `:460` 的非滑翔调参；本模组的 `beloong$isEligible` **只排除 `isGliding()`**（`:186`），
对这个"近滑翔态"完全不设防。

### 2.2 本模组在这个状态上叠加了什么

`flight_level` 由本模组注入、默认 0（`ModAttributes.getFlightLevel` 的默认 `RangedAttribute(..., 0.0, ...)`，
整合包未对其施加 modifier），因此走的是 `:133` 之后的 **"非稳定悬停"分支**：

```java
// ClientFlightHandlerMixin.java:145-151
if (noMoveInput && !ClientFlightHandler.wasFlying) return;
double gravity = player.getAttributeValue(Attributes.GRAVITY);
player.setDeltaMovement(delta.x, delta.y - gravity, delta.z);   // ← 再减一个完整 g
```

`:145` 的守卫是 `noMoveInput && !wasFlying`，滑翔中 `wasFlying == true` ⇒ **不会 return**，
于是在 DS 已经施加的 `-1g`（`stable_hover=true`）之上**再追加 `-1g`**，
整体下坠约翻倍。若此时饱食度 ≤ `flight_hunger_threshold`，DS 那侧本来就是 `-(gravity*4)`，
再叠加本模组的 `-g`，就是 ~5g 的俯冲。

**这就是"装了本模组就下坠快"的路径**：不是滑翔物理被改，而是**滑翔状态没被识别出来**，
于是针对"非滑翔"写的那段追加落到了滑翔身上。

---

## 3. 为什么"开关开与关都一样"

`:96` 的配置门控之上还有 `:162` 的第一道门：

```java
private static boolean beloong$isEligible(LocalPlayer player) {
    if (!ServerFlightHandler.stableHover) return false;      // ← 第一道门
```

⇒ 当 DS 的 `stable_hover = false` 时，**无论 `fixStableHoverDrift` 是开是关，本模组都毫无作为**。

测试环境实测配置：

| 环境 | 文件 | `stable_hover` | `fixStableHoverDrift` |
|---|---|---|---|
| `BeLoong-Core/run`（开发客户端，测试世界名就是「飞行测试」） | `run/config/dragonsurvival-server.toml` / `beloong-client.toml` | **false** | **false** |
| `BeLoong-Server`（整合包服务端） | `config/dragonsurvival-server.toml` | **true** | — |
| `【化龍】服务端\化龍本地测试服` | 同上 | **true** | — |

⇒ 在 `stable_hover = false` 的环境里，开关怎么拨都一样，**而 DS 自己就是 `-2g` 的快速下坠**（§1.4 表）。
"无论开关都一样，而且都下坠快"由此完全解释，且**与本模组无关**。
而"未装本模组时下坠慢"来自 `stable_hover = true` 的环境（`-1g`）。

**即：两次对照测试的 `stable_hover` 取值不同，这个差异本身就足以造成约 2 倍的下坠速度差，
掩盖了本模组真正的贡献。**

---

## 4. 复现/确认判据（供后续验证，本文不实施）

1. 在**同一个**环境里把 `stable_hover` 固定为 `true`，其余不动，只切 `fixStableHoverDrift`；
2. 滑翔时确认 `isGliding()` 的真值（最直接的是看饱食度是否 ≤ 6 —— 该值下原版必然取消冲刺）；
3. 若确认 `isGliding() == false` 而 `stable_hover == true`，则在 `flight_level = 0`、
   `fixStableHoverDrift = true` 下应当观察到"比关掉修复时快约一倍"的下坠。

---

## 5. 与既有文档的关系

- `docs/reviews/2026-09-23-flight-system-conflicts-and-creative-flight-comparison.md` 的 **F-4**
  已记录过同源缺陷（"该分支要求 `noMoveInput`…低飞行等级的玩家只要按住 WASD 就仍享受
  `stableHover=true` 的竖直阻尼"），本文补上的是**滑翔侧**的对应缺口：
  排除条件用的是 `isGliding()` 而不是"是否处于滑翔姿态"。
- `docs/plans/2026-09-23-gliding-gravity-and-water-drag-design.md` 的 §1.1 给出了
  `-0.25g`（DS `:408`）与 `-1g`（`travel`）的拆分，本文沿用该量级并补齐了 `:496/:499/:525` 三档。

---

## 6. 受影响面（供调整设计时圈定范围，本节不含方案）

### 6.1 语义锚点（任何方案都必须与之等价）

DS 客户端飞行物理里读 `stableHover` 的**全部两处**：

| 位置 | 语义 |
|---|---|
| `ClientFlightHandler.java:480-482` | `stableHover && !jumping && !shift && !isSpin && !isGliding` ⇒ `ay = max(ay, gravity*1.1)`（悬停抬升） |
| `ClientFlightHandler.java:496-500` | 移动中：`false ⇒ -(gravity*2)+y`；`true ⇒ -gravity+y` |

⇒ `isGliding() == true` 的分支（`:445-458`）**一处都不读**。所以"等级 < 1 应该等于 DS 的
`stableHover = false`"这句话的**唯一准确含义**就是：让这两处按 `false` 求值，且不影响 `travel` 侧。

### 6.2 本模组当前牵动的代码点

| # | 位置 | 现状作用 | 与本次缺陷的关系 |
|---|---|---|---|
| 1 | `ClientFlightHandlerMixin.java:186-188` | 滑翔排除项，只认 `isGliding()` | 缺陷 (a)：近滑翔态漏网 |
| 2 | `ClientFlightHandlerMixin.java:133-151` | 等级 < 1 分支：`:145` 的 `noMoveInput && !wasFlying` 守卫、`:151` 追加 `-g` | 缺陷 (b)：在 DS 已施加的重力之上叠加 |
| 3 | `ClientFlightHandlerMixin.java:113-131` | 等级 ≥ 1 分支：`setAy(0)`、`setDeltaMovement(x,0,z)`、挂零重力修饰符 | 语义未受本次缺陷影响，但共用同一判定 |
| 4 | `ClientFlightHandlerMixin.java:83-89` / `:214-224` | 零重力修饰符的摘/挂时机（属性窗口） | 与缺陷无关；但若改用 6.1 的两处求值，需重新确认窗口是否仍必要 |
| 5 | `Config.java:29-31` + `lang/{zh_cn,en_us}.json` 的 `fixStableHoverDrift` 两键 | 总开关 | 开关在 `stable_hover = false` 时天然无效（§3） |
| 6 | `ClientFlightHandlerAccessor.java`（`ax/ay/az`） | 提供 `beloong$setAy` 等 | `beloong$setAy(0.0)` 目前是**语义空操作**：`ay` 在每次使用前都会被 DS `:430` 或 `:457` 重写（可选清理项） |
| 7 | `ModAttributes.getFlightLevel`（默认 0） | 等级读取 | 默认 0 ⇒ 绝大多数玩家一直走等级 < 1 分支 |

### 6.3 不可改但可影响的 DS 侧输入

| 输入 | 当前值 | 影响 |
|---|---|---|
| `dragonsurvival-server.toml` → `stable_hover` | 整合包 `true` / 开发环境 `false` | 决定本模组是否介入，且独立改变 DS 自身下坠档位 |
| `dragonsurvival-server.toml` → `flight_hunger_threshold` | 整合包 **4** / DS 默认 6 | 与原版冲刺阈值（6）错配，制造饱食度 5~6 的"想滑翔却滑不了"窗口 |
| `dragonsurvival:flight_speed` 属性 | 数据驱动，本模组未干预 | 滑翔抬升项系数与 `speedThreshold`（§1.4 注） |
| 原版冲刺取消条件 | 硬编码 | `!hasForwardImpulse` / `foodLevel<=6` / `horizontalCollision` / 水面 |

### 6.4 若要"主观滑翔"可判定，可用的输入

`FlightData.isWingsSpread()`、`hasFlight()`、`ServerFlightHandler.isFlying(player)`、
`ServerFlightHandler.isGliding(player)`、`player.isSprinting()`、`player.input.forwardImpulse`、
`player.input.jumping` / `shiftKeyDown`、`player.getFoodData().getFoodLevel()`、
`new Vec3(ax, 0, az).length()`（即 DS `:405` 的 `horizontalVelocity`，需经 `ClientFlightHandlerAccessor`）。

---

## 7. 证据复现命令

```powershell
# 1) DS 2.0.70(8871661) 与 2.0.71(8973485) 的客户端飞行代码逐字节对比（预期 0 差异）
#    解包两产物后：
javap -p -c -cp <8871661解包目录> by.dragonsurvivalteam.dragonsurvival.client.handlers.ClientFlightHandler > a.txt
javap -p -c -cp <8973485解包目录> by.dragonsurvivalteam.dragonsurvival.client.handlers.ClientFlightHandler > b.txt
Compare-Object (Get-Content a.txt) (Get-Content b.txt)

# 2) stableHover 在 DS 客户端飞行类中的全部读取点（预期只有 :480 与 :496）
Select-String -Path <DS源码>\client\handlers\ClientFlightHandler.java -Pattern 'stableHover'

# 3) 两个环境的 DS 飞行配置
Select-String -Path 'BeLoong-Core\run\config\dragonsurvival-server.toml',
                     'BeLoong-Server\config\dragonsurvival-server.toml' `
              -Pattern 'stable_hover|flight_speed_multiplier|flight_hunger_threshold|no_speed_requirement'

# 4) 本模组侧的开关与判定
Select-String -Path 'BeLoong-Core\run\config\beloong-client.toml' -Pattern 'fixStableHoverDrift'
```

**未做任何代码改动。**
