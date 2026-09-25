# 通用 NPC 基类：改用原版 AI 与行为 设计文档

**日期：** 2026-09-25
**状态：** **已实现（`23c2721`）并通过实机验证**（用户确认全部通过）—— 设计已批准（用户裁定 Q1 / Q2 + "其余按建议"）并落地；
随后 **R-impl-2 撤销了 `RandomLookAroundGoal`**（实机后用户裁定：回到"默认不转动"），见 §3.3 与 §3.8
**分支：** `NPC`
**采用方案：** 移动与行为**尽量交回原版机制**；只保留三项原版确实没有的自研（见 §六）
**修订关系：** 本文档**修订**
[`2026-09-25-npc-base-class-design.md`](./2026-09-25-npc-base-class-design.md) 的
**D25（移动速度语义）、D21（walkTo/runTo）、D35 的动画档位、D46（冲刺复位）、D47（速度系数）**；
该文档其余决策与组件划分**继续有效**。

---

## 一、用户裁定（2026-09-25）

| # | 裁定 |
|---|---|
| **Q1** | 移动改成**原版生物的移动方式**；默认 `MOVEMENT_SPEED` 取 **0.3**（**比玩家略慢**，见 §二）；**不要额外的奔跑状态**；当获得**额外移动速度**（迅捷效果等）时才切换为**奔跑动画** |
| **Q2** | 采用 **(b)**：加 `RandomLookAroundGoal`，但**默认依旧保持站桩**（不产生位移）<br>→ **实机验证后撤回**（见 R-impl-2）：它会带动身体自主转动，回到"默认不转" |
| **交互** | 交互入口**可以**换成原版的 `mobInteract`，但**现阶段不接入已写好的 NPC 对话系统** |
| **追加** | **删除 `/beloong npc turn` 调试命令**（效果不好；"旋转"直接从行为上就能看出，不需要指令演示） |
| **其他** | 按上一轮分析的建议执行 |

---

## 二、关键计算：默认 `MOVEMENT_SPEED` = **0.3**（约为玩家的九成）

### 2.1 两支的位移公式（已核到源码）

| | 决定位移的两个因子 |
|---|---|
| **玩家** | ① `getFrictionInfluencedSpeed()` 用 `Player#getSpeed()`，它**覆写为返回属性值** = `0.1`（`Player.java:1613-1616`）<br>② 前进输入 `zza` 来自键盘，幅度 **1.0**（`LocalPlayer.java:691`、`KeyboardInput.java:15-21`）<br>⇒ 位移 ∝ `0.1 × 1.0 = 0.1` |
| **生物** | ① `getFrictionInfluencedSpeed()` 用 `LivingEntity#getSpeed()`，返回**字段** `speed`，由 `MoveControl` 写为 `speedModifier × 属性`（`MoveControl.java:100`）<br>② 而 `Mob#setSpeed` **把同一个值也写进了 `zza`**（`Mob.java:557-560`）<br>⇒ 位移 ∝ `speed × speed = speed²` |

两边的摩擦系数、0.98 输入衰减、稳态收敛因子**完全相同**，因此**比值与它们无关**：

```
生物位移 / 玩家位移  =  (speedModifier × MOVEMENT_SPEED)² / 0.1
```

### 2.2 取值

按原版惯例让 `speedModifier = 1.0`，则比值 = `MOVEMENT_SPEED² / 0.1`：

| 属性值 | 相对走路玩家的速度 | 说明 |
|---|---|---|
| `0.3162`（= √0.1） | **100%** | 理论等速点 |
| **`0.3`（选定）** | **90%** | `0.3² / 0.1 = 0.9` ⇒ **比玩家略慢**（用户裁定） |

**⇒ 取 `0.3`。** 留出这一成差距是**有意的观感取舍**：与玩家并排走时 NPC 会缓慢落在后面，
而不是"完全同速、像被拴在一起"。将来要调快/调慢只改这一个数（比值 = 值²/0.1）。

### 2.3 旁证（原版自己就承认这个平方）

`PathNavigation#doStuckDetection`（`:312`）在估算"100 tick 内该走多远"时**显式平方**了速度：

```java
float f = this.mob.getSpeed() >= 1.0F ? this.mob.getSpeed() : this.mob.getSpeed() * this.mob.getSpeed();
```

那个 `>= 1.0F` 的守卫正是给玩家留的例外（玩家的 `getSpeed()` 返回属性、输入幅度又是 1.0）。

**手感校验**：僵尸属性 `0.23` ⇒ `0.23² / 0.1 = 0.53` ⇒ 实际约为走路玩家的 **53%** ——
与"僵尸明显比走路玩家慢"的既有观感一致，说明公式口径正确。

### 2.4 副产品：档位语义回到原版

因为位移对 `speedModifier` 是**平方**关系，原版 goal 各自挑档位的写法（`MeleeAttackGoal(1.0)`、
`FollowParentGoal(1.25)`、`TemptGoal(1.25)`、`PanicGoal(2.0)`）在我们这里**行为与原版一致**——
这正是"改用原版移动"的核心收益。我们的 `walkTo` 只传 `1.0`（= 该生物的基础速度）。

---

## 三、改动清单

### 3.1 `entity/NpcEntity.java`

| # | 改动 | 说明 |
|---|---|---|
| 1 | `createNpcAttributes()`：`MOVEMENT_SPEED` **0.1 → 0.3** | §二；注释写清 `0.3²/0.1 = 0.9`（≈玩家的九成）与"这是**原版刻度**值、不是玩家属性值" |
| 2 | **删除** `navigationSpeedModifier()` | 不再需要 1/√ 补偿 |
| 3 | `walkTo` 的档位改为 **`1.0`** | 原版惯例：1.0 = 基础速度 |
| 4 | **删除** `runTo(...)` | 用户裁定：不要额外的奔跑状态 |
| 5 | `clearMotionCommands()` 里**删除** `setSprinting(false)` | 不再使用冲刺 |
| 6 | `customServerAiStep()` 里**删除**冲刺复位 | 同上；**保留**攻击指令的失效兜底（D55） |
| 7 | 动画判据改为三档 | 见 §3.2 |
| 8 | `isPersistenceRequired()` 覆写 → **`requiresCustomPersistence()`** | 原版官方钩子（`Mob.java:736`），先例 `AbstractFish:45`、`Axolotl:423`、`Raider:248`、`EnderMan:434` |
| 9 | ~~`registerGoals()` 增加 `RandomLookAroundGoal(this)`~~ → **实机后撤回**（R-impl-2） | goal 集合见 §3.3 |
| 10 | 类注释同步改写 | 记录本轮"改用原版"的口径与 §六 三项例外 |

### 3.2 动画：`run` 不再是"状态"，而是"有额外移速加成的表现"

```java
private static final RawAnimation IDLE / WALK / RUN;   // 名字来自可覆写方法

// 判据
if (!state.isMoving())                          → IDLE
else if (有效移速 > 基础移速)                    → RUN
else                                            → WALK
```

**判据细节**：`getAttributeValue(Attributes.MOVEMENT_SPEED) > getAttributeBaseValue(Attributes.MOVEMENT_SPEED)`
（`LivingEntity` 两个方法都存在，已核）。

- 迅捷效果就是往 `MOVEMENT_SPEED` 挂修饰符（`MobEffect#addAttributeModifiers`，`:166-174`），
  因此**效果一生效就自动切 `run`、一结束就回 `walk`**，不需要任何状态机或额外同步。
- 属性值会同步给客户端（`ClientboundUpdateAttributesPacket`），所以客户端渲染侧**能直接读到**，
  无需网络包。
- 口径写成"**任何**额外移速加成"（迅捷、信标、食物、其它模组的 modifier 都算），而不是"仅迅捷"。

### 3.3 goal 集合与 Flag 仲裁

| 优先级 | Goal | 占用 Flag |
|---|---|---|
| 0 | `FloatGoal` | JUMP |
| 3 | `NpcAttackGoal`（`MeleeAttackGoal` 子类，命令把关） | MOVE + LOOK |
| 5 | `LookAtPlayerGoal(this, Player.class, facePlayerDistance(), 1.0F)` | LOOK |

**只有三个 goal，全部是原版（攻击 goal 是 `MeleeAttackGoal` 的子类）**，且**没有任何自主转向/游走**。

> ⚠️ **`RandomLookAroundGoal` 曾按 Q2 加入、实机验证后由用户裁定撤销（R-impl-2）。**
> 它写着 `setFlags(EnumSet.of(MOVE, LOOK))`，看起来"只动头、不产生位移"，
> 但站桩时原版 `BodyRotationControl` 会**把身体拖向头**（见 §"身朝"那条链）⇒ 净效果是
> **NPC 自主间歇性转身**，与"默认面朝一个方向、不自主转动"直接冲突。
> **"只转头而不动身体"在原版机制下做不到**，除非覆写 `createBodyControl()` —— 那与"尽量用原版"相悖。
> 详见 D52 / D53 与 R17。

### 3.4 交互入口

**决定**：NPC 的交互入口定为原版 `Mob#mobInteract(Player, InteractionHand)`（`Mob` 内的 protected 钩子），
**但本轮不落代码、也不接入对话系统**。理由：

1. 现阶段 `mobInteract` 里无事可做 —— 写一个只 `return super.mobInteract(...)` 的覆写是**死代码**。
2. **现有对话系统不能整体搬进这个方法**：它服务**任意实体类型**（示例数据是原版铁傀儡），
   而 `mobInteract` 只在我们自己的子类里存在 ⇒ 它必须继续走
   `PlayerInteractEvent.EntityInteract`。等将来要把对话接到 NPC 上时，
   两条路可以并存（NPC 走 `mobInteract`、其它实体走事件），届时再把事件侧收窄到非 NPC。
3. 迁移路径已记录在案（本节），实施时不需要重新论证。

### 3.5 `command/NpcCommand.java`

- **删除 `run` 子命令**（连同 `runTo` 调用与 `"beloong.command.npc.run"` 语言键）。
- **删除 `turn` 子命令**（连同 `"beloong.command.npc.turn"` 语言键）—— 用户裁定：
  该命令效果不好，而"旋转"本身可以直接从行为上看出来，不需要指令演示。
- 结果：5 条子命令降为 **3 条**：`walk` / `attack` / `stop`。

> ✅ **连带代码已在同轮整体删除**（用户追加裁定："删除 turn 调试命令后，把无用的代码也删除。
> 但注意，目前的 NPC 转向玩家的功能必须保留完整"）。删掉的是 **`turn` 这一整套能力**：
>
> | 删除项 | |
> |---|---|
> | `entity/ai/NpcTurnGoal.java` | 整个文件 |
> | `NpcEntity` | `turnTo` / `setFacing` / `getTurnTargetYaw` / `clearTurnTarget` / `turnTargetYaw` 字段 / `LOOK_AHEAD_DISTANCE` / `maxTurnPerTick()` |
> | `NpcCommand` | `turn` 与 `run` 子命令（含 `move(...)` 的 sprint 分支） |
> | 语言文件 | `beloong.command.npc.{turn,run}`（中英各 2 条） |
>
> **删除前已核实「面朝玩家」不依赖其中任何一环** —— 它完全由原版链路完成：
> `LookAtPlayerGoal` 写 `yHeadRot` → `LookControl` → `BodyRotationControl` 把身体拖到滞后头 75° 内、
> 头停约 11 tick 后收敛到完全对齐 → 渲染用 `yBodyRot`。
> `turn*` 那一套只是"**命令式指定绝对朝向**"，与上面这条链**没有任何交集**（`setFacing` 是它自己的
> 实现细节，不是共享设施）。实测确认：删除后"玩家靠近→先扭头→后转身→整体面朝玩家"完全未变。

### 3.6 语言文件

- 删除 `beloong.command.npc.run` 与 `beloong.command.npc.turn`（中英各 2 条，共 4 条）；其余不动。

### 3.7 不改的

`ModEntities`、`ModAttributes`、`beloong.mixins.json`、对话系统与 `NpcDialogue*`、
`client/` 侧（模型/渲染器）、资产。

### 3.8 实现期记录

- `facePlayerDistance()` 由 `public` 收回 **`protected`** —— 它当初是 public 只为让
  `entity/ai` 包的自研 goal 能读；那个 goal 已删除，现在只有本类自己用，接口越小越好。
- `maxTurnPerTick()` 随 `NpcTurnGoal` 一并删除（它只被那个 goal 读）。
- `animationTransitionTicks()` 的注释由"`idle` ↔ `walk` 的过渡"改为"动画之间的过渡"（现在有三档）。

**R-impl-2 —— 撤销 `RandomLookAroundGoal`，回到"默认不转动"（2026-09-25，实机验证后用户裁定）。**

实机验证**全部通过**（移速、run 动画、站桩、面朝玩家等），但用户提出一项行为要改：
**间歇性转动改回"默认不转动"**。

**根因不是 goal 本身选错了，而是原版机制的一个连带效应**：
`RandomLookAroundGoal` 只调 `LookControl` 写 `yHeadRot`、确实**不产生位移**，
但站桩时 `BodyRotationControl` 会**把身体拖向头**（头动 >15° 就夹一次，头停约 11 tick 后收敛到对齐）
⇒ 净效果是**身体随张望自主间歇转动**，与"默认面朝一个方向、不自主转动"直接冲突。

**处置**：删除 `registerGoals()` 里的该 goal（+ import），goal 集合回到
`FloatGoal(0)` / `NpcAttackGoal(3)` / `LookAtPlayerGoal(5)` 三个。已在 `NpcEntity.registerGoals()`
的 Javadoc 里写下"**刻意没有它**"及原因，避免后人再"顺手补一个原版标准项"。

**通用教训**：
> **"只动头"的 goal 在 `Mob` 上不是"只动头"。** 原版站桩时身体追头 —— 所以任何"动头"的
> 自主 goal（张望、看向别处、环顾）都会连带转动身体。要"头动身不动"只能覆写
> `createBodyControl()`（D56），而那会脱离原版机制。**选 goal 时要连它的连带效应一起评估。**

---

## 四、决策记录（接续 D1–D47）

| # | 决策 | 取值 | 理由 |
|---|---|---|---|
| **D48** | 移动回到原版刻度 | `MOVEMENT_SPEED = 0.3`（≈玩家的九成），`walkTo` 传 `1.0` | 用户裁定 Q1（并追加定为 `0.3`、略慢于玩家）。位移比 = `(档位×属性)²/0.1`（§2.1）⇒ `0.3²/0.1 = 0.9`；**删除** D47 的 `1/√` 补偿 |
| **D49** | 取消奔跑状态与冲刺机制 | 删 `runTo`、`setSprinting`、冲刺复位、`run` 子命令 | 用户裁定 Q1。原版生物里只有猫用 `setSprinting`（`Cat:194-204`，扑击），不是"跑"的常规做法；删掉后 D46 的复位补丁一并消失 |
| **D50** | `run` 动画的触发 | 移动中且 `getAttributeValue(MOVEMENT_SPEED) > getAttributeBaseValue(...)` ⇒ `run` | 用户裁定 Q1。判据即"有额外移速加成"，属性值已同步到客户端，无需网络包 |
| **D51** | 不消失改用官方钩子 | `requiresCustomPersistence()` 取代 `isPersistenceRequired()` | `Mob#checkDespawn` 同时看两者（`Mob.java:749`），而前者正是"给子类覆写"设计的（先例见 §3.1#8） |
| **D52** | ~~增加原版 `RandomLookAroundGoal`（优先级 7）~~ → **已撤销**（R-impl-2） | 不加该 goal | 用户先裁定 Q2 加、**实机验证后裁定移除**：它是"自主间歇性转动"的唯一来源，与"默认面朝一个方向、不自主转动"冲突 |
| **D53** | ~~接受"站桩时身体随张望转动"~~ → **不再需要**（goal 已移除） | — | 原结论（原版站桩身体追头）仍然正确，但既然不加张望 goal，就没有触发源。**记录保留**：将来若要"站着四处张望"，必须同时接受身体跟着转，否则得覆写 `createBodyControl()` |
| **D54** | 交互入口定为 `mobInteract`，本轮不落代码 | 见 §3.4 | 空覆写是死代码；且现有对话系统服务任意实体类型，不能整体搬进来 |
| **D55** | 攻击仍只作 API | 保留 `NpcAttackGoal` 与 `customServerAiStep` 的失效兜底 | 你先前裁定"属性 + `attack()` API、不自主索敌"；本轮无反对意见，保持不变 |
| **D56** | `createBodyControl()` 记为身朝定制的**正统扩展点** | 仅记录，本轮不用 | 原版自己在用：`Phantom:63`、`Armadillo:392`、`Camel:635`、`Shulker:146`。将来若真要定制站桩身朝，覆写它而不是 `tickHeadTurn` |
| **D57** | **整体删除 `turn` 能力**（不只是命令） | 命令子命令 + 语言键 + `turnTo` / `setFacing` / `getTurnTargetYaw` / `clearTurnTarget` / `turnTargetYaw` / `LOOK_AHEAD_DISTANCE` / `maxTurnPerTick()` + 整个 `NpcTurnGoal` | 用户两步裁定：先删命令（"效果不好，旋转可直接从行为看出"），再删无用代码。**删除前已核实「面朝玩家」不依赖其任何一环**（见 §3.5） |

---

## 五、实机验收清单

| # | 操作 | 预期 |
|---|---|---|
| 1 | `/beloong npc walk @e[…] ~ ~ ~8`，与玩家并排同向走 | **比玩家略慢（约九成）**，并排会缓慢落后 —— 这是 D48 的直接验收 |
| 2 | 给 NPC 迅捷效果（`/effect give @e[type=beloong:dihuang_loong] speed 30 1`）后让它走 | **播 `run` 动画**；速度变快 |
| 3 | 迅捷效果结束 | 回到 `walk` 动画与基础速度 |
| 4 | 静置观察数分钟 | **完全不位移、也完全不转动**（朝向保持不变）—— 这是 R-impl-2 的直接验收 |
| 5 | 玩家走近 | 改为看玩家：头先转、身体滞后跟上、最后整体面朝玩家 |
| 6 | `/beloong npc run …` 与 `/beloong npc turn …` | **两条子命令都不存在**（已删除）；Tab 补全只剩 `walk` / `attack` / `stop` |
| 7 | 退档重进 | 仍在、仍无敌（`requiresCustomPersistence` + 无敌覆写） |
| 8 | 攻击 / `/kill` / 重力 / 头身分离 | 与前一轮一致，无回归 |

---

## 六、原版确实没有、仍然自研的三项（本轮不动）

1. **"连创造模式也打不动"的绝对无敌**：原版 `Entity#isInvulnerableTo`（`:2681-2686`）显式带
   `&& !source.isCreativePlayer()` —— 创造玩家**故意**能打无敌实体；且 `invulnerable` 字段会被
   `/summon` 的 `load()` 覆盖，纯声明式做不到。
2. **命令式 API**（转到绝对角度 / 走到某点 / 指定攻击目标，且默认不触发）：原版 goal 全是
   自主条件驱动，没有命令队列；`Mob#lookAt` 只接受**实体**、不接受角度。
3. **GeckoLib 的离散动画**（`idle`/`walk`/`run` 三选一）：原版是连续的 `limbSwingAmount`（幅度）
   + `limbSwing`（相位），一个模型自动适配所有速度；GeckoLib 播整段动画，只能分档。

---

## 七、风险

| # | 风险 | 评估 |
|---|---|---|
| ~~R17~~ | ~~站桩时身体会随张望缓慢转动~~ | **已关闭（R-impl-2）**：张望 goal 已移除，"自主转动"的来源消失。**但结论仍有价值** —— 将来若加任何"动头"的 goal（张望、看向别处），身体都会跟着转 |
| ~~R18~~ | ~~`RandomLookAroundGoal` 与 `LookAtPlayerGoal` 都占 `LOOK`~~ | **已关闭（R-impl-2）**：该 goal 已移除，冲突不存在 |
| **R19** | `0.3` 是**按公式选定**的值，未实机测速 | **低**：比值只由 `(档位×属性)²/0.1` 决定（摩擦与衰减因子两侧相消）⇒ 预期恰为玩家的 **90%**；实机第 1 项即验收，偏了就改这一个数 |
| **R20** | 删除 `runTo`/`run` 命令会影响既有文档与记忆 | **低**：设计文档与 `memory/` 同步更新 |

---

## 八、来源

| 类别 | 出处 |
|---|---|
| 原版移动链路 | `Mob#setSpeed`(`Mob.java:557-560`)、`MoveControl#tick`(`:100`)、`LivingEntity#getFrictionInfluencedSpeed`(`:2428-2430`)、`Entity#getInputVector`(`Entity.java:1397-1412`)、`PathNavigation#doStuckDetection:312` |
| 玩家口径 | `Player#getSpeed`(`Player.java:1613-1616`)、`Player#createPlayerAttributes`(`:228-231`)、`LocalPlayer:691`、`KeyboardInput:15-29` |
| 持久性钩子 | `Mob#checkDespawn`(`:746-749`)、`requiresCustomPersistence`(`:736`)、先例 `AbstractFish:45`/`Axolotl:423`/`Raider:248`/`EnderMan:434` |
| 原版被动生物的 goal 配方（参考用，最终未采用张望项） | `Cow#registerGoals`(`:42-51`) |
| 属性/效果 | `LivingEntity#getAttributeValue/getAttributeBaseValue`、`MobEffect#addAttributeModifiers`(`:166-174`) |
| 身朝扩展点 | `Mob#createBodyControl`(`:196`)、先例 `Phantom:63`/`Armadillo:392`/`Camel:635`/`Shulker:146` |
| 上一轮分析 | 本会话的"原版 vs 本基类"对照表（已并入 `memory/decisions-log.md`） |
