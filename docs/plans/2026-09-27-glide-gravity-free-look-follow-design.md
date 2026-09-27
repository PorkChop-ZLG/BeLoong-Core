# 滑翔去重力 + 跟随视线 · 设计文档

**日期：** 2026-09-27
**状态：** 已批准（五节设计全部逐节确认）
**采用方案：** Approach A —— 属性窗口（HEAD 挂/摘）+ TAIL 方向插值
**上游文档：**
- `docs/reviews/2026-09-27-glide-fast-fall-diagnosis.md`（诊断：`isGliding()` 判定窗、分支量级表、语义锚点）
- `docs/reviews/2026-09-23-flight-system-conflicts-and-creative-flight-comparison.md`（F-1..F-9）
- `docs/plans/2026-09-23-gliding-gravity-and-water-drag-design.md`（**已否决**：只去重力、不做视线对齐 ⇒ 低头飞不下去）
- `docs/reviews/2026-09-23-pr7-stable-hover-review.md`（PR #7：`look.scale(speed)` 的即时对齐版本）

---

## 1. 问题陈述

三条需求：

1. **滑翔不再受重力影响**，且**无论 DS 的 `stable_hover` 是否开启、玩家 `flight_level` 是否支持稳定悬停**，都不受重力影响。
2. 此前被否决的方案虽然做到了"滑翔无重力"，但**视角朝下时无法往下飞**（只能水平飞）。
3. 需要 PR #7 那种**滑翔时飞往视角朝向方向、可上可下**的效果。

**需求 2 的成因（已定位）**：DS 只在抬头时提供竖直推力（`ClientFlightHandler.java:430` `ay = viewVector.y / 4`），低头时 `:426-428` 只累加**水平**的 `ax/az`，从不产生负 `ay`。所以一旦把重力归零，低头就没有任何向下来源。

**顺带修复的 bug**（源自诊断文档）：当前实现里滑翔虽被排除，但排除项写在 `beloong$isEligible` 内、且位于 `stableHover` 门之后；一旦判定口径与 DS 的真实滑翔状态不重合，`-g` 追加就会落到滑翔身上。本次通过**控制流**（滑翔分支最优先 `return`）而非条件表达式来消除这一类风险。

**关键约束**：需求 1 要求的是**两个向下来源同时消失**——DS `ClientFlightHandler.java:408` 的重力项（经 `:395` 读 `Attributes.GRAVITY`）**和**原版 `LivingEntity.travel:2221→2331` 的 `d0`（同一个属性）。

---

## 2. 设计

### 2.1 Architecture

**一个 mixin、两个注入点**，都在 `mixin/dragonsurvival/ClientFlightHandlerMixin.java`（`@Mixin(value = ClientFlightHandler.class, remap = false)`）。

**HEAD —— 重力闸门（无配置、无等级、无 `stableHover`）**

```java
LocalPlayer p = Minecraft.getInstance().player;
beloong$setZeroGravity(p, p != null && ServerFlightHandler.isGliding(p));   // 滑翔→挂；否则→摘
```

不变式：**每 tick 开始时修饰符一律不在场**，只有滑翔时才在 HEAD 挂上。
`ClientFlightHandler` 内部不写 `isGliding()` 的任何判定输入（已核实），故 HEAD 与 TAIL 在同 tick 必然同值。

**TAIL —— 三路分流，滑翔排在最前**

```java
// ① 滑翔：完全独立于 Config.FIX_STABLE_HOVER / stableHover / flight_level
if (p != null && ServerFlightHandler.isGliding(p)) {
    if (!ServerFlightHandler.isSpin(p)) {          // 旋转攻击交给 DS 自己
        Vec3 delta = p.getDeltaMovement();
        double speed = delta.length();
        if (speed > 1.0E-5) {
            Vec3 target = p.getLookAngle().scale(speed);          // 方向=视线，大小=当前速度
            p.setDeltaMovement(delta.lerp(target, beloong$GLIDE_TURN)
                                    .normalize().scale(speed));   // 严格保大小
        }
    }
    return;                                        // ← 不再进入下面任何分支（bug 修复的关键）
}

// ② 以下保持现状
if (!Config.FIX_STABLE_HOVER.get()) return;
if (!beloong$isEligible(p)) return;                // 其中的 isGliding 排除项已删除
if (level >= 1) { …悬停锁定 + TAIL 挂修饰符… }
else            { …追加 -g（noMoveInput / wasFlying 守卫保持）… }
```

`delta.lerp(target, T)` 的 `T` 是 `to` 的权重（`Vec3.lerp(Vec3,double)`，1.21.1 已核）。
两个等长向量插值会**缩短弦长**（急转掉速），末尾 `normalize().scale(speed)` 只抵消这个缩短，
**不动 DS 在 TAIL 之前已施加的拖曳**（`ELYTRA_FLY_DRAG` 的减速照旧生效）。

### 2.2 Components

**注入点**

| # | 位置 | 现状 | 改动 |
|---|---|---|---|
| 1 | `flightControl` HEAD | `beloong$clearZeroGravity(CallbackInfo)`，无条件摘除 | 改名 `beloong$glideGravityGate(CallbackInfo)`；语义变为按闸门挂/摘 |
| 2 | `flightControl` TAIL | `beloong$fixStableHoverDrift(CallbackInfo)` | **保持单一 TAIL 注入**（不拆两个 `@Inject`，同点先后无约定），方法最前插入滑翔分支并 `return` |

**新增成员**

| 成员 | 形式 | 约束 |
|---|---|---|
| `beloong$GLIDE_TURN` | `private static final double = 0.10` | 硬编码。**必须 `< 0.5`**：`T ≥ 0.5` 时 180° 掉头会让 `lerp` 趋零 ⇒ `normalize()` 出 NaN |
| `beloong$followLook(LocalPlayer)` | `private static void` | 滑翔分支体；`speed <= 1.0E-5` 直接返回 |

不额外封装 `isGliding` 包装方法——HEAD/TAIL 都直接调 `ServerFlightHandler.isGliding(player)`，保持"逐字匹配"。

**需同步改动的既有文本**（本次不加配置，但文案不能再说谎）

| # | 位置 | 现状 | 改为 |
|---|---|---|---|
| 1 | `beloong$isEligible:186-188` | `if (isGliding(player)) return false;` | **删除**（死代码；留着会让判定再次分叉，即 F-6 的教训） |
| 2 | 类 javadoc `:44` | "滑翔不再被接管…DS 原版滑翔物理一字不动" | 重写为"滑翔由本 mixin 接管：去重力 + 跟随视线"，补"两个向下来源"与 D1–D6 |
| 3 | 类 javadoc `:158` | 排除项列表含"滑翔" | 移除"滑翔"；注明"旋转只跳过滤线、不跳过去重力" |
| 4 | `Config.java:27` | "滑翔不在本修复范围内，完全由 DS 原版处理。" | "滑翔由独立路径处理，不受本开关门控。" |
| 5 | `Config.java:30` | `.comment("…；滑翔不受影响")` | `"…；滑翔另有独立处理（不受本开关影响）"` |
| 6 | `lang/zh_cn.json` + `en_us.json` 的 `fixStableHoverDrift.tooltip` | 末句"滑翔不受本修复影响。" / "Gliding is not affected." | "滑翔另有独立处理：不受重力、跟随视角，且不受本开关与飞行等级影响。" **两语同步**，键集仍 219/219 |

**明确不动**

- `ClientFlightHandlerAccessor`（`ax/ay/az`）：滑翔路径不需要它；悬停路径仍调 `beloong$setAy(0.0)`。
  附带记录（本次不改）：`setAy(0.0)` 疑似空操作——下一 tick 里 `ay` 会被 DS 自己改写（`:481` 抬回 `1.1g`，或 `:531/:536` 清零），两种结果都不取决于这次调用。
- `beloong$stableWaterHover`、level≥1 悬停锁定、level<1 的 `-g` 模拟（含 `noMoveInput`/`wasFlying` 守卫）——保持现状；**F-4 的既有缺陷不在本次范围**。
- 修饰符常量与 `beloong$setZeroGravity` 复用，**不新增第二个修饰符**。

**修饰符所有权表（最关键的不变式）**

| 时机 | 谁写 | 结果 |
|---|---|---|
| tick 开始 HEAD | `beloong$glideGravityGate` | 滑翔→挂；非滑翔→摘。每 tick 必有一次写，幂等 |
| DS 飞行数学 | —— | 滑翔：`gravity=0` ⇒ `:408` 贡献 0；非滑翔：真实重力（`stableHover`/`ay` 判定不受影响） |
| `player.tick()` → `travel` | —— | 滑翔：`d0=0` ⇒ 原版重力不施加；非滑翔：真实重力 |
| TAIL · 悬停分支（level≥1 且非滑翔） | `beloong$setZeroGravity(p, true)` | 为本次 travel 挂上（HEAD 已摘过，配对仍正确） |
| TAIL · 滑翔分支 | 不写 | HEAD 已挂好 |

⇒ 单一修饰符、单一所有者、每 tick 由 HEAD 复位：**关开关、退出滑翔、死亡、切维度都不会残留零重力**。

### 2.3 Data Flow

```
T0  ClientTickEvent.Pre → ClientFlightHandler.flightControl(event)
    ├─[本模组 HEAD] beloong$glideGravityGate：写 Attributes.GRAVITY 瞬时修饰符 := isGliding()
    ├─ DS 守卫 → ifPresent(lambda$flightControl$2)
    │     :395 gravity = getAttributeValue(GRAVITY)      ← 滑翔时 0
    │     :407 if (isGliding || 高速)
    │         :408 deltaMovement += gravity*(−1+0.75·vd) ← 滑翔时 0      ★需求1 前半
    │         :445 if (isGliding) → 滑翔分支（DS 原版动力学 + ELYTRA_FLY_DRAG）
    │         :460 else          → 非滑翔分支（stableHover 定 −1g/−2g）
    │     :531 else              → 慢速非滑翔（重置 ax/az/ay）
    └─[本模组 TAIL] ① 滑翔：isSpin ? return : followLook(); return          ★需求3
                    ② !Config.FIX_STABLE_HOVER → return
                    ③ !isEligible → return
                    ④ level≥1 锁高度+挂修饰符 ／ level<1 追加 −g
    ▼
T1  Minecraft.tick() → level.tick() → LocalPlayer.tick() → aiStep() → travel()
    ├─[DS] handleEarlyFlightLogic(PlayerTickEvent.Pre)
    └─ travel(vec) → super.travel → LivingEntity.travel
          :2221 d0 = getGravity()      ← 同一个属性
          :2331 d2 -= d0               ← 滑翔时 0                       ★需求1 后半
          :2341 setDeltaMovement(…, d2*0.98F, …)
    ▼
T2  下一 tick HEAD 复位修饰符
```

**四条由时序保证的性质**

1. **两个向下来源被同一个属性值覆盖**：`:395`（DS）与 `:2221`（原版）都在 HEAD 之后读同一个 `Attributes.GRAVITY`。这是需求 1 的全部实现原理，也是不需要复制 DS 算式的原因。
2. **TAIL 位于 `:457` 之后、`travel` 之前**：改的是"本次 `travel` 将要消费的速度"。DS 在 `:457` 已把 `ay` 记为它自己的 `deltaMovement.y`，但 `:464`/`:536` 会在退出滑翔或转慢速时清零 `ay` ⇒ 我们的改写不会把 `ay` 记账带过滑翔边界。
3. **需求 1 的"不受门控"由控制流保证**：滑翔的 `return` 在 `Config` 与 `isEligible` **之前**，后来谁改这两个条件都不会意外把滑翔重新圈进去。
4. **修饰符生命周期恰好一个 tick**：HEAD 写 → TAIL 仅悬停分支可能再写一次 → 下一 HEAD 复位。

**数值走查**（`T = 0.10`、`|v| = 1.0`、`g = 0.088`）

| 视线 | `:408` | TAIL 插值后 | 结果 |
|---|---|---|---|
| 平视（`look.y = 0`） | 0（g=0） | y 向 0 收敛 | **不下沉**（需求 1） |
| 低头 45°（`look.y = −0.707`） | 0 | y 每 tick 向 −0.707 走 10% | 触发 `:410-413` 的"俯冲换水平速度"（该式用 `deltaMovement.y`，**不含 gravity**）⇒ **能往底下飞**（需求 2） |
| 抬头 45°（`look.y = +0.707`） | 0 | y 向 +0.707 收敛 | **能爬升**（需求 3） |

下坠有上限：`:411` 的 `downwardMomentum = |y|·0.1·vd·FLIGHT_SPEED` 随 |y| 线性增长（方向向上），叠加 `ELYTRA_FLY_DRAG = (0.99, 0.98, 0.99)`，构成自限终端速度——**该阻尼不依赖重力**，去掉重力后依然有效。

### 2.4 Error Handling

| # | 失败模式 | 处理 |
|---|---|---|
| E1 | `player == null` | HEAD/TAIL 都先判空返回；HEAD 在 null 时**不写任何东西**（修饰符挂在 player 的属性实例上，实例不存在即无可残留对象） |
| E2 | `getAttribute(GRAVITY) == null` | 复用现有 `if (gravity == null) return;` |
| E3 | `normalize()` 除零/NaN | **由 `T < 0.5` 结构性保证**（为零需 `(1−T)/T = 1` 即 `T = 0.5`）；外加 `speed <= 1.0E-5` 提前返回 |
| E4 | `look` 为零向量 | 不可能（`getLookAngle()` 恒单位向量），不加判空 |
| E5 | 修饰符残留 | ① HEAD 每 tick 必有一次写；② `addOrUpdateTransientModifier` 为瞬时修饰符，不写 NBT、不参与同步，实体销毁即消失；③ 被清后下一 tick 立即重挂 |
| E6 | 服务端属性同步清修饰符 | 影响面 1 tick（`handleUpdateAttributes` → `removeModifiers()`）；与现有悬停路径同一权衡 |
| E7 | `isGliding` 在 HEAD/TAIL 之间变化 | 当前不会（`ClientFlightHandler` 不写判定输入）⇒ 同 tick 恒同值。**该前提必须写进 javadoc**；DS 若改动，最坏是"一 tick 内挂了又摘"，不会崩 |
| E8 | 注入点解析失败 | `injectors.defaultRequire = 1` ⇒ 启动期硬崩（预期，便于立刻发现）；本次不改变该契约 |
| E9 | `isSpin` 与 `isGliding` 同时为真 | 只跳过插值、仍 `return` ⇒ 重力保持归零（D6）。**若旋转手感异常，这是唯一需要翻的开关点** |
| E10 | 与其它改 `deltaMovement` 的模组叠加 | 我们的写是 TAIL 最后一步，且是"向视线靠 10%"的插值而非硬设绝对值 ⇒ 叠加收敛，不会互相锁死 |
| E11 | 删除 `isEligible` 的 `isGliding` 项是否等价 | 等价：滑翔分支在最前 `return`，之后才调 `isEligible`；同 tick `isGliding` 恒同值（E7）⇒ 该判定进不去 |

**日志**：不新增日志。行为确定、逐 tick，打点会刷屏；`ModAttributes.getFlightLevel` 的一次性告警已覆盖"属性缺失"这类真正需要排查的情形。

### 2.5 Testing Strategy

项目无测试套件，验收 = 静态门 + 实机清单。**关键前提**：每组实机必须记录 `stable_hover`、`flight_level`、饱食度 三个变量（诊断文档 §3 的教训：任一不同会让结论反过来）。

**S5.1 静态门**
1. `gradlew build` 通过；Mixin AP 警告仍为既有 3 条，不新增
2. `beloong.mixins.json` 不变 ⇒ DS 侧仍为 14 个文件 / 19 个注入点
3. 语言键：`zh_cn` 与 `en_us` 均 219 键且集合相同
4. 残留扫描：`isEligible` 中不再出现 `isGliding`；旧名 `beloong$clearZeroGravity` 不再出现；`Config.java` 与两份 lang 中不再有"滑翔不受影响"这类已失真表述

**S5.2 验收清单**

> **前置条件（见 §2.6 B-1）**：A1–A4 必须在**饱食度 > 6** 时执行，且确认此刻 `isGliding()` 为真；
> 饱食度 5~6 时 `isGliding()` 必为假，本需求不成立，不得据此判定改动失效。

| # | 场景 | 期望 |
|---|---|---|
| A1 | `stable_hover=false`、`flight_level=0`、滑翔中平视（饱食度 > 6） | **不下沉**（需求 1 核心） |
| A2 | 同上，低头 45° | **能下降**，俯角越大下降越快（需求 2）；同期记录空袭速度/伤害（B-4） |
| A3 | 同上，抬头 45° | **能爬升**（需求 3） |
| A4 | `stable_hover=true`、`flight_level≥1`，重做 A1–A3 | 与 A1–A3 一致（不受门控） |
| A5 | 关掉 `fixStableHoverDrift`，重做 A1–A3 | 与 A1–A3 **完全一致** |
| A6 | 滑翔中松开 Ctrl / 撞墙 / 入水 | 立刻回 DS 原版，无"幽灵零重力"。**判读口径见 §2.6 B-3**：那一 tick 仍零重力属正常 |
| A7 | 旋转攻击中（`isSpin`） | 不跟随视线，但重力仍归零（D6）；旋转手感同 DS 原版 |
| A8 | 滑翔 → 松 Ctrl 悬停 → 再滑翔 | 过渡无抖动、无突然上跳 |
| A9 | 水中展翅飞行 | 完全 DS 原版（`isFlying` 排除水） |

**S5.3 回归清单**

| # | 场景 | 期望 |
|---|---|---|
| R1 | 非滑翔悬停（level≥1，不按 Ctrl） | 高度锁定不变 |
| R2 | 非滑翔 level<1 + `stable_hover=true` | 追加 `-g` 行为不变（含 F-4 现状，本次不修） |
| R3 | `stable_hover=false` 的非滑翔 | 本模组仍不介入 |
| R4 | 非龙 / 无翅 / 无飞行能力 | 全程不介入 |

**S5.4 手感锚点**（`lerp` 收敛为 `(1−T)ⁿ·v + (1−(1−T)ⁿ)·target`）

| 指标 | `T = 0.10` 理论值 |
|---|---|
| 转过 90% | `n = ln0.1/ln0.9 ≈ 22 tick ≈ 1.1 s` |
| 若嫌太"重" | `T = 0.15 → ≈14 tick`；`T = 0.20 → ≈10 tick` |
| 平视滑翔 10 s 高度漂移 | 应 `< 1 格` |
| 45° 低头 3 s 下降量 | 应落在 `10 ~ 60 格`（**阈值待实机校准后写死**） |

**S5.5 验收期临时手段**：允许临时加一条英文 ASCII 的 `LOGGER.info` 打印 `isGliding/stableHover/flightLevel/foodLevel`（状态跳变时打，不打逐 tick）。**验收结束必须删除**（先例 `93066f6 chore: 移除调试日志`）。

### 2.6 已知边界与残余风险（2026-09-27 独立审查后补充）

**B-1（Important，需求边界，不修）——"滑翔"严格等于 `isGliding()`，故饱食度 5~6 时本需求不成立**

`isGliding()` 要求 `player.isSprinting()`（`ServerFlightHandler.java:348`），而原版在 `foodLevel <= 6` 时**取消**冲刺（`LocalPlayer.java:786` + `:1125-1127` 的 `hasEnoughFoodToStartSprinting` 阈值 6）。
生产服配置为 `flight_hunger_threshold = 4`，于是 **饱食度 5 或 6** 时：

- DS 认为"有食物，可以滑翔"（`hasFood` 为真）；
- 原版已取消冲刺 ⇒ `isSprinting()` 为假 ⇒ **`isGliding()` 为假**；
- ⇒ HEAD 不挂零重力、DS 走 `:460` 非滑翔分支、且因 `flight_level` 默认 0 会**再追加一个 `-g`**。

**这正是最初报障的那个窗口，本次改动对它零作用。** 这是用户裁定"判定口径必须精确匹配 DS 的滑翔状态"（D7）与"只豁免真滑翔"（D5）的**必然结果**，不是实现缺陷。若要覆盖它，必须引入与 DS 定义分叉的第二条谓词（代价见诊断文档 §2 与 F-6 的教训），本设计明确不做。

⇒ **验收前置条件**：A1–A4 必须在**饱食度 > 6**（生产服同）时执行；否则会把"近滑翔态"的结果误读为本改动失效。

**B-2（Minor，不修）—— 滑翔分支不含 `isDragon` 身份守卫**

`isGliding() ⊆ isFlying()` 已覆盖乘客 / 水 / 熔岩 / 落地 / 翅膀 / 飞行能力，仅缺 `isDragon` 与 `!isPaused`（暂停时实体不 tick，无害）。
`FlightData.hasFlight/isWingsSpread` 是纯存储布尔，只在 `FlightEffect` 与同步包处写；若玩家失去龙形态而附着数据未清，一个非龙玩家空中冲刺会被归零重力并跟随视线。
**可达性很低，且修它必须同时加到 HEAD 闸门**（否则 HEAD/TAIL 分叉，破坏"单一滑翔判定"这一本设计的承重性质）——权衡后选择**只记录不修**。

**B-3（Minor，验收判读口径）—— 两个 ≤1 tick 的窗口**

(a) 修饰符在整个 tick 内挂着，而 sprint / 入水 / 落地状态要到同一 tick 更晚的 `LocalPlayer.aiStep:785-795` 或 `travel`/`move` 里才翻转 ⇒ "松 Ctrl / 撞墙 / 入水"的**那一 tick** 仍以零重力飞行；
(b) 属性同步包处理时会对该属性 `removeModifiers()`，可能落在 HEAD 与 `travel` 之间，白丢一 tick（即 E6）。
两者都 ≤1 tick、都不是残留，但 **A6 若盯着"那一瞬间"看会误以为有幽灵零重力**。

**B-4（Minor，需实测）—— 平视巡航不再自动增速，`AirStrikeEffect` 的速度剖面已变**

> ⚠️ **本节前半已被 rev 2 取代**（见 §6）：rev 2 补齐了"平视/低头沿视线加速"，"平视巡航不再自动增速"**不再成立**。
> 仍成立的部分：`AirStrikeEffect` 的速度剖面确实变了，A2/A3 仍需同期记录空袭速度/伤害。

去重力后 DS `:408` 在平视时那 `-0.25g` 的持续下沉消失，而 `:410-413`"俯冲换速度"的能量原本正来自这个下沉 ⇒ **平视巡航不再自动增速**，俯冲/空袭的速度只能靠主动低头获得。
`ability/AirStrikeEffect.java:67-76` 的伤害按 `getDeltaMovement().length()` 线性计算；本设计严格保大小，所以**单 tick 伤害不变量未被破坏**，但**可达速度剖面变了**。⇒ A2/A3 同期记录空袭速度/伤害（改前 vs 改后），必要时重调 `speed_factor` / `min_speed`。

**B-5（Minor，锚点精度）—— §5.4 的 22 tick 只是量级参考**

该数字来自纯几何级数 `(1−T)ⁿ`。实际每 tick DS 还会在 `:449` 把上一 tick 的 `ay`（`:457` 记录的是**插值前**的 `deltaMovement.y`）加回来一次，收敛会与理论值有偏差。**实机以 A1–A3 的实测为准。**

---

## 3. Decisions Made

| # | 决策 | 理由 |
|---|---|---|
| D1 | 用 `Attributes.GRAVITY` 在 HEAD 归零重力 | 唯一能同时覆盖 DS `:408` 与 `travel` 的杠杆；不复制 DS 任何算式 |
| D2 | 闸门 = `isGliding(player)` 逐字调用，**不看** `stableHover` / 等级 / 配置 | 需求 1；与 DS `:445/:460` 同源，同 tick 同值 |
| D3 | 跟随视线 = TAIL 方向插值、大小保持，`T = 0.10` 硬编码 | 需求 3 + B1；不加配置 |
| D4 | 滑翔在 TAIL 最优先 `return` | bug 修复：滑翔从此不可能被追加 `-g`，也不会被 `stableHover` 门控拦住 |
| D5 | 只豁免真滑翔；`:460`（快但没滑翔）与 `:531`（慢）保持现状 | 用户裁定：快速飞行分支的 `-g` 叠加正是飞行等级系统的契约 |
| D6 | `isSpin` 时仍去重力，但跳过插值 | 需求 1 的字面读法；同时不搅乱 DS 旋转物理。最易翻的一条 |
| D7 | 判定口径严格用 `ServerFlightHandler.isGliding(player)`（同函数、同参数、同 tick） | 全仓库唯一滑翔状态定义；`FlightData` 无滑翔字段，`wasGliding` 只是派生闩锁 |
| D8 | 不加任何配置 | 用户裁定 |
| D9 | 单一 TAIL 注入，不拆两个 | 同点注入先后无约定 |
| D10 | **rev 2**：滑翔补一台"沿视线的能量源"，目标速度 `0.8 × FLIGHT_SPEED × 2`（= DS `:485` 钳制 `deltaMovement` 用的 `maxForward`），每 tick 补 `0.25`，到顶即停 | 用户实测"只有抬头有加速"：DS 的竖直能量**只有向上**（`:415-419`、`:430`→`:449`），低头/平视一个都没有；而 rev 1 的保大小插值只转向不加能 ⇒ 需求扩为"各方向都要加速" |
| D11 | **rev 2**：补速采**叠加式**——只在 `look.y <= 0`（平视/低头）介入，抬头 100% 走 DS 原样 | 用户裁定。抬头那套是"向上加速 + 水平减速"，并非"沿视线加速"；不介入才能保住已认可的抬头手感并避免双重加速 |
| D12 | **rev 2**：目标速度**不新增配置**，写成常量 | 延续 D8 |
| D13 | **rev 2**：方向插值（D3）保留不变，补速是叠加在它之上的第二步 | 保住已实机验证过的"低头能下降"响应速度 |

## 4. Non-Goals

- **水中去阻力**（被否决方案的另一半）：不做
- 不改 DS `:460` / `:531` 分支
- 不改 `AirStrikeEffect`、滑翔动画分档（`DragonEntity:649` 一带）、相机
- 不修 F-4（level<1 分支的 `noMoveInput` 限制）
- 不清理 `beloong$setAy(0.0)` 的空操作（另记）
- 不新增配置与语言键

## 5. Next Steps

交棒给 `planning` 技能，产出实施计划（任务分解 + 每步验收）。

---

## 6. 修订 rev 2（2026-09-27）：滑翔各方向加速

**状态**：已批准（架构/组件两节均确认）
**动因**：rev 1 实机验收"功能正常"，但用户发现**只有视角朝上才有加速**，平视与低头都没有。诊断结论见下。

### 6.1 原因（已回源码核实）

DS 的滑翔分支里，**竖直方向的能量注入只有向上的两条**，向下的唯一来源是重力：

| 项 | 位置 | 平视 | 低头 | 抬头 |
|---|---|---|---|---|
| 重力项 `gravity·(−1+0.75·vd)` | `:408` | `−0.25g` | `−0.625g`@45° | 更小 |
| `downwardMomentum` | `:410-413` | 0（`y≥0` 不触发） | **`+dM`（向上）** + 沿视线水平推力 | 0 |
| `delta*3.2` | `:415-419` | 跳过（要求 `pitch<0`） | 跳过 | **`+3.2·delta` 向上** |
| `ay` 赋值 | `:426-433` | `ay = 0`，且 `ax/az ×= 0.98` | **只累加水平 `ax/az`，`ay` 不动** | `ay = viewVector.y/4` |
| 滑翔分支是否用 `ay` | `:445-452` | 用（=0） | **不用**：`add(ax, 0, az)` | 用（向上） |
| 拖曳 | `:454` | y 每 tick −2% | 同 | 同 |

⇒ ① DS **没有任何产生向下推力的代码**；② 重力是唯一的向下能源，被需求 1 归零；③ rev 1 的插值"严格保大小"只转向不加能；④ `:410-413` 名为"俯冲动量"实为**向上回收**，反向抵消下坠。
结果：平视只受拖曳（减速），低头只拿到方向（下沉但不加速），**只有抬头**有 `:415-419` 与 `ay` 两个纯加法项 ⇒ 加速。

### 6.2 rev 2 设计

**架构不变**（HEAD 重力闸门、TAIL 三路分流、`isSpin` 跳过、无配置门控全部保留）。唯一变化：`beloong$followLook` 从"只旋转"升级为"**旋转 + 按需补速**"。

| 视线 | 能量来源 | 相对 rev 1 |
|---|---|---|
| 抬头（`look.y > 0`） | **DS 自己**（`:415-419` + `:449`） | **零变化** |
| 平视（`look.y == 0`） | 本模组：沿视线补速 → 水平向前加速 | **新增** |
| 低头（`look.y < 0`） | 本模组：沿视线补速 → 俯冲加速 | **新增** |

**新增常量**：`beloong$GLIDE_ACCEL = 0.25`（DS 抬头 `ay = viewVector.y/4` 的上限，即正上方时的值）
**新增 import**：`by.dragonsurvivalteam.dragonsurvival.registry.DSAttributes`

```java
    private static void beloong$followLook(LocalPlayer player) {
        Vec3 delta = player.getDeltaMovement();
        double speed = delta.length();
        if (speed <= beloong$NORMALIZE_EPSILON) {
            return;
        }

        Vec3 look = player.getLookAngle();

        // 1) 方向插值：方向向视线靠 GLIDE_TURN，大小严格保持（rev 1 行为，不变）
        Vec3 dir = delta.lerp(look.scale(speed), beloong$GLIDE_TURN).normalize();
        Vec3 result = dir.scale(speed);

        // 2) 沿视线补速。叠加式：只在"非抬头"介入 —— 抬头完全交给 DS 自己的
        //    :415-419（+3.2·delta）与 :449（+ay），避免双重加速、保住既有手感。
        //    到顶即停（按 targetSpeed - speed 夹住），故不会超调、也不会无界增长；
        //    DS 在 TAIL 之前已施加的 ELYTRA_FLY_DRAG 会让稳态停在目标值略下方。
        if (look.y <= 0.0) {
            double targetSpeed = 0.8 * player.getAttributeValue(DSAttributes.FLIGHT_SPEED) * 2.0;
            if (speed < targetSpeed) {
                result = result.add(look.scale(Math.min(beloong$GLIDE_ACCEL, targetSpeed - speed)));
            }
        }

        player.setDeltaMovement(result);
    }
```

**为何到顶即停就足够收敛**：`speed` 是**拖曳之后**的值；当 `speed < target` 时最多补 `target − speed` ⇒ 恒不超调。达到目标后拖曳把它压回下方，下一 tick 再补上，稳态在目标值略下方小幅锯齿。DS 的 `:421-423`（目标大小 `FS × 当前速度`）与 `:410-413` 都不构成额外约束，所以**上限必须由这里给出**。

**同步改动**：`beloong$GLIDE_TURN` 的 javadoc 补"本系数只管方向"；`beloong$followLook` 的 javadoc 写明"抬头走 DS、平视/低头走本模组"这一有意的不对称及原因；两份 lang 的 `fixStableHoverDrift.tooltip` 由"跟随视角"补为"跟随视角并沿视线加速"（zh/en 同步，键集仍 219/219）。

**非目标（rev 2 新增）**：不改 DS 的 `ay` 体系（Approach 2 被否：`viewVector.y = 0` 时它无法给平视加速，且与叠加式冲突）；不"只消除拖曳"（Approach 3 只能得恒速）；不新增配置。

### 6.3 rev 2 验收增量

在 rev 1 的 A1–A9 之上补：

| # | 场景 | 期望 |
|---|---|---|
| A10 | 平视滑翔，饱食度 > 6，持续 3 s | **速度明显增长**并稳定在约 1.6 格/tick（32 格/秒）附近，不再单调减速 |
| A11 | 低头 45° 滑翔 | **俯冲加速**（与抬头同强度），不再是"下沉但减速" |
| A12 | 抬头滑翔 | **与 rev 1 手感一致**（叠加式，不应变快） |
| A13 | 三方向各 10 s | 速度均收敛、**无超调、无失控增长** |

> 参考：`FLIGHT_SPEED` 默认 1（`DSAttributes` → `RangedAttribute(..., 1, 0, 1024)`），故默认目标 1.6/tick；
> 龙种/成长阶段若给 `FLIGHT_SPEED` 加了 modifier，目标会随之变化（这是有意复用 DS 的量）。
