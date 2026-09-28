# 通用 NPC 状态系统 设计文档

**日期**：2026-09-27
**状态**：**已批准**（brainstorming 六轮问答收敛；用户逐轮裁定）
**实施**：**已实施** —— 提交 `3c85d6c`（T1–T9 一个原子提交），静态探针 A1–A8 全过。
**实机验收**：**未做** —— 清单见实施计划 §五 B1'–B14'（旧的 B1–B8 已随指令面变更作废）。
**分支**：`NPC`（不 push）
**选定架构**：**单一互斥状态枚举** + **三轴正交的指令面**（状态 / 移动 / 攻击）+ 全清指令
**与既有文档的关系**：本文**修订** `2026-09-27-npc-flight-design.md` 的 D2、D7、D13 与整个指令面。
飞行不再是独立特性，而是状态系统的一个成员。那份文档里**仍然有效**的是飞行本身的平台事实
（换 `moveControl`/`navigation` 字段的配方、`FOLLOW_RANGE` 上限、悬停机理等），本文引用而不重复。

---

## 一、问题与目标

已实现并实机通过的"通用 NPC 飞行"当前是**一个布尔**（`DATA_FLYING`）。后续还要加
**坐下、跳舞**等状态，它们与飞行有共同的本质：

> **都是"由指令设定、直到指令终止才恢复"的状态，且需要持续播放对应动画、并且要活过存档重登。**

若继续用布尔堆，每加一个状态都要动四处（同步字段、落盘键、状态方法、控制器分支），
且组合语义会失控。目标是把它升级成**可扩展的状态系统**，同时把指令面收敛成三条正交的轴。

---

## 二、调研结论：原版的状态持久化代码（逐条附行号）

### 2.1 原版**没有**通用框架，但有成套零件

原版每个生物各写各的，没有任何"状态机基类"或"状态持久化工具"。但写法高度一致，
可归纳为一条**四段式配方**：

| 段 | 机制 | 出处 |
|---|---|---|
| ① 客户端可见 | `SynchedEntityData` + `EntityDataAccessor` | `Sniffer.java:74`、`Armadillo.java:55`、`Cat.java:81`、`TamableAnimal.java:35` |
| ② 落盘 | `addAdditionalSaveData` / `readAdditionalSaveData` | `Armadillo.java:251,261`、`TamableAnimal.java:59,85` |
| ③ 客户端按状态选动画 | 状态 getter → 模型 / **public 的 `AnimationState` 字段** | `Armadillo.java:59-61`、`Sniffer.java:144` |
| ④ 状态变更时重置计时 | `onSyncedDataUpdated` | `Armadillo.java:114-117`、`Sniffer.java:154-156` |

### 2.2 ⚠️ 关键：原版把状态**分成两族**，只有一族落盘

| 族 | 例子 | 落盘？ | 判据 |
|---|---|---|---|
| **受命 / 权威态** | `TamableAnimal` 的 `orderedToSit` → `"Sitting"`（`:59` 写、`:85-86` 读）；`Armadillo` → `"state"`（`:251` 写、`:261` 读） | **是** | 来自**外部命令**，无法从环境重算 |
| **派生 / 瞬态** | **`Sniffer` 完全没有覆写 `addAdditionalSaveData`**；`Allay` 的 dancing（有 `addAdditionalSaveData` 但**不写** Dancing，`:467-495`）；`Parrot#partyParrot` 是普通字段、不同步不落盘（`:198-200`）；`Cat` 的 `IS_LYING` 同步但**不落盘**（`:81`） | **否** | 每 tick 从环境/脑子重算（如"附近有没有唱片机"） |

**判据只有一条：这个状态能不能从环境重算出来。**
我们的**飞行 / 坐下 / 跳舞全部属于受命态**（由指令设定、直到指令终止）⇒ 正确的先例是
**`TamableAnimal` + `Armadillo` 这一族**，不是 `Allay`/`Parrot` 那一族。
这条区分比任何具体代码都重要，故列为本文的首要依据。

### 2.3 可复用的零件清单

| # | 零件 | 出处 | 用途 |
|---|---|---|---|
| 1 | `StringRepresentable` + `StringRepresentable.EnumCodec` + **`CODEC.byName(name, 默认值)`** | `Armadillo.java:429,446-447` | 存档里的状态名对不上时**回落默认值、不抛异常** |
| 2 | 落盘写 **`getSerializedName()`** 而非 ordinal | `Armadillo.java:251` | 枚举顺序变化不会读错（ordinal 会） |
| 3 | `ByIdMap.continuous(::id, values(), OutOfBoundsStrategy.ZERO\|CLAMP)` | `Armadillo.java:430-432` | 网络同步用的安全 id 映射，越界回落 |
| 4 | 枚举常量**自带参数** | `Armadillo.java:410` 的 `ROLLING("rolling", true, 10, 1)` | "状态自己知道自己多长"（我们做循环态不需要，留作将来） |
| 5 | **权威值 / 渲染值分离** | `TamableAnimal`：普通字段 `orderedToSit` 是权威，`DATA_FLAGS_ID` 的位是同步给客户端的渲染值；加载时 `:85-86` 读回权威值再推给客户端 | 服务端权威 + 客户端只负责播动画 |
| 6 | `onSyncedDataUpdated` 里重置状态计时 | `Armadillo.java:114-117`（`inStateTicks = 0`） | 状态一同步过去，客户端就知道"从头开始" |
| 7 | 客户端按状态选动画：**public 的 `AnimationState` 字段** | `Armadillo.java:59-61` | 原版的"状态→动画"接口；我们对应 GeckoLib 控制器谓词 |

### 2.4 一条硬事实：模组**不能**自己调 `EntityDataSerializers.registerSerializer`

`EntityDataSerializers.java:133-141`：

```java
@Deprecated
public static void registerSerializer(EntityDataSerializer<?> serializer) {
    if (!STACK_WALKER.getCallerClass().equals(EntityDataSerializers.class)) {
        LOGGER.error("Modded EntityDataSerializers must be registered to NeoForgeRegistries.ENTITY_DATA_SERIALIZERS instead ...");
        throw new UnsupportedOperationException("Modded EntityDataSerializers must be registered to NeoForgeRegistries.ENTITY_DATA_SERIALIZERS instead ...");
    }
```

⇒ 两条路：**① 用现成的 `INT`/`BYTE` + `ByIdMap` 映射（零注册）**；② 走
`NeoForgeRegistries.ENTITY_DATA_SERIALIZERS` 注册自定义序列化器（原版的
`ARMADILLO_STATE`（`:123`）/ `SNIFFER_STATE`（`:126`）就是这么做，用
`EntityDataSerializer.forValueType(STREAM_CODEC)`）。
**本次选 ①**：省掉一次注册，且避开"客户端/服务端 id 不一致"这一整类问题。

---

## 三、设计

### 3.1 状态枚举（单一互斥）

```
NpcState
  IDLE     默认。地面站桩；且是唯一会走 idle/walk/run 三选一的状态
  FLYING   移动模式
  SITTING  姿态
  DANCING  姿态
  …        加状态 = 加一个枚举值
```

**每个状态声明自己的类别**（这是"移动指令隐式退出姿态"能落地的关键）：

```java
boolean isMovementMode();     // IDLE / FLYING → true ; SITTING / DANCING → false
```

**为什么互斥而不是正交轴**（用户裁定）：vanilla 的 `Armadillo`/`Sniffer` 都是互斥枚举，
实现与语义都最简。代价是"一边飞一边跳"做不到 —— 明确记为非目标。

### 3.2 三个轴与指令面

指令面收敛为**三条正交的轴 + 一条全清**：

```
/beloong npc state  <targets> <state>     ← idle | flying | sitting | dancing | …（可扩展）
/beloong npc move   <targets> <pos>       ← 移动：地面走 / 空中飞，由当前状态决定
/beloong npc stop   <targets>             ← 停止寻路（move 的反面）
/beloong npc attack <targets> <victim>    ← 攻击
/beloong npc attack <targets> stop        ← 停止攻击
/beloong npc reset  <targets>             ← 回到"刚被召唤出来的样子"
```

**被删除的旧指令**：`walk`（→ `move`）、`fly on|off`（→ `state … flying` / `state … idle`）、
`fly to <pos>`（→ `move <pos>`）。

**对称性（设计意图，不是巧合）**：

```
move ↔ stop                        移动轴：去某点 ↔ 不去
attack <victim> ↔ attack stop      攻击轴：打谁   ↔ 不打
```

两条 `stop` 语义完全一致：**只取消各自轴上的指令，绝不碰状态**。
"地面停下就是站桩待机、空中停下就是原地悬停"是"状态没变 + 动作没了"的自然结果，
**不需要为它们写任何特判**。

### 3.3 契约表

| 指令 | 状态 | 移动指令 | 攻击指令 |
|---|---|---|---|
| `state <t> flying` / `state <t> idle` | 切换 | **保留**，按新模式重跑 | 保留 |
| `state <t> sitting` / `dancing` | 切换 | **取消** | **取消** |
| `move <t> <pos>` | 若是姿态 ⇒ 先隐式切 `idle` | 登记新目标 | 不动 |
| `stop <t>` | **不动** | **取消** | 不动 |
| `attack <t> <victim>` | 若是姿态 ⇒ 先隐式切 `idle` | 不动 | 设为该目标 |
| `attack <t> stop` | **不动** | 不动 | **取消** |
| `reset <t>` | → `idle` | 取消 | 取消 |

**两条由契约推出的规则**（都需要写进注释，因为说反了就是 bug）：

1. **`state` 与移动指令正交 —— 但只在"移动模式之间"成立。**
   进入**姿态**态时必须取消移动与攻击指令，因为"姿态"这个类别的定义就是"不走、不动手"；
   留着指令只会让它一边坐着一边滑行。
   ⇒ **在 `idle` ↔ `flying` 之间切换时，移动指令保留、并按新模式重新执行** ——
   同一条 `move` 在地面是"走"、在飞行是"飞"，这正是 `move` 统一的意义。
2. **`move`/`attack` 的"隐式退出姿态"必须先做，再做登记。**
   否则隐式退出（一次状态切换）会把它刚登记的指令取消掉。**顺序反了就是 bug。**
   同理，`switchState` 进入任何状态前**无条件**收掉上一个状态的副作用
   （教训来自飞行那一轮的 I-3：任何提前 return 都会留下"浮空但状态说没飞"）。

### 3.4 四个零件

| 零件 | 做法 |
|---|---|
| **同步** | 一个 `DATA_STATE`：`EntityDataSerializers.INT` 存 `state.id()`，用 `ByIdMap.continuous(NpcState::id, values(), OutOfBoundsStrategy.ZERO)` 映射（零注册） |
| **落盘** | **一个** NBT 键，写 `getSerializedName()`；读时 `CODEC.byName(name, IDLE)` 回落 |
| **状态机** | `switchState(NpcState)` 是**唯一**入口；所有"进入某状态的副作用"集中在此（`FLYING` ⇒ 换 `FlyingMoveControl`/`FlyingPathNavigation` + `setNoGravity(true)`；其余 ⇒ 确保先退出飞行） |
| **动画** | 控制器第一分支：`state != IDLE` ⇒ 播该状态的动画；否则才进 idle/walk/run 三选一。**动画名不进枚举**（见下） |

**⚠️ 动画名为什么不放进枚举常量**：`Armadillo.java:410` 那样把数据挂在枚举上很诱人，
但**同一个逻辑状态在不同模型上叫法不同**（Mo 与地黄龙的资产各自命名，Mo 的 `fly` 还是
2026-09-27 才修好能加载的）⇒ 动画名必须留在**每个 NPC 可覆写**的
`idleAnimationName()` / `flyAnimationName()` / `sitAnimationName()` / `danceAnimationName()` 里，
枚举只承载**逻辑状态**。

**不变式**：`noGravity == (state == FLYING)`（双向）。

### 3.5 失败策略：**指令严格 / 存档宽松**（两套，不能合成一套）

| 场合 | 策略 | 依据 |
|---|---|---|
| 指令 `state @e fliing` 拼错 | **严格报错** + 补全提示 | 静默变成 `idle` 会让玩家以为命令成功了 |
| 读存档、状态名对不上（旧存档/降级/手改档） | **宽松回落 `IDLE`** | `Armadillo.java:446-447` 的 `CODEC.byName(name, IDLE)` |

⇒ 需要**严格版**与**宽松版**两个入口。⚠️ 后人很可能图省事把它们合成一个，
本文明确禁止：**这两种场合的正确行为是相反的。**

### 3.6 持久化

- `addAdditionalSaveData` 写状态键；`readAdditionalSaveData` 读回并**经 `switchState` 恢复**
  （而不是直接写 `entityData` —— 否则飞行所需的换字段/重力副作用会漏掉）。
- **恢复时不起飞**（`fly on` 已改为"原地悬停"，见 §四 D2'）：重登后就在存档位置继续悬停。
- **只持久化状态，不持久化目的地**：`moveTarget` 仍是瞬态指令，重登后原地悬停，不续飞。
- 向后兼容：旧存档无此键 ⇒ `IDLE` ⇒ 与"未加状态系统之前"的行为一致。
- 客户端不需要换导航（换字段始终服务端独占），`state` 靠同步数据到达客户端 ⇒ 动画正确。

### 3.7 与既有实现的关系：**是重构，不是新增**

现有的飞行实现（`DATA_FLYING` 布尔 + `setFlying` + `walkTo`/`flyTo`）会被改写成
状态系统的一员。**飞行行为本身一行不变**（原地悬停、`FLYING_SPEED 0.6`、
换字段配方、`FOLLOW_RANGE` 续路、`hoversInPlace`），只是表示形式与入口变了。
⇒ 按本项目规矩，**飞行那套实机验收项必须重跑**。

---

## 四、决策记录

| # | 决策 | 依据 |
|---|---|---|
| **D1'** | 状态用**单一互斥枚举**，不用多个布尔、也不用正交双轴 | 用户裁定；vanilla `Armadillo`/`Sniffer` 同模型 |
| **D2'** | **`fly on`（现 `state … flying`）不起飞，只在原地悬停** | 用户裁定。这同时**从根上绕开**了飞行那一轮暴露的"竖直滑行几十格"问题（见 §六 C），比任何刹车方案都直接 |
| **D3'** | 全部状态经 `npc state <targets> <state>` 控制，**包括 `idle` 与 `flying`** | 用户裁定；指令面可扩展，加状态不改指令树 |
| **D4'** | `fly` 与 `walk` **合并成 `move`** —— 只要移动就用 `move`，走法由状态决定 | 用户裁定；用户不必记"现在该用 walk 还是 fly" |
| **D5'** | **保留 `stop`**：只停寻路（`move` 的反面），**维持当前状态** | 用户裁定。它保住了"停下但保持状态"这个能力（如飞行中停下继续悬停） |
| **D6'** | **保留 `reset`**：状态→`idle` + 取消移动 + 取消攻击 | 对应最初的"重置到刚被召唤出来的状态"需求 |
| **D7'** | **状态持久化**（活过存档重登、关服重开），**只持久化状态、不持久化目的地** | 用户裁定；受命态按 §2.2 必须落盘 |
| **D8'** | 移动指令遇到姿态 ⇒ **隐式退出姿态**再执行，不报错 | 用户裁定；与"飞行中 `walk` 允许"的既有口径一致 |
| **D9'** | `state` 与移动指令正交，**但进入姿态会取消移动与攻击指令** | 由状态类别推出（姿态 = 不走不动手）；否则姿态会被路径滑行破坏 |
| **D10'** | 新增 **`attack <t> stop`** 取消攻击、维持状态 | 用户裁定；补上"只取消攻击不改状态"的缺口，与 `stop` 形成对称 |
| **D11'** | 同步用 `INT` + `ByIdMap`，**不注册自定义 `EntityDataSerializer`** | §2.4；省一次注册并避开 id 不一致风险 |
| **D12'** | 落盘写 `getSerializedName()`；读取用 `CODEC.byName(…, IDLE)` 回落 | `Armadillo.java:251,446-447` |
| **D13'** | **动画名不进枚举**，留在每个 NPC 可覆写的 `xxxAnimationName()` | 同一逻辑状态在不同模型上叫法不同 |
| **D14'** | 失败策略分两套：**指令严格 / 存档宽松** | 两种场合的正确行为相反 |
| **D15'** | 不做自主状态切换（没有 goal 会自己改状态）；不做状态组合 | 用户裁定；与"纯能力 + 命令驱动"的既有定位一致 |
| **D16'** | **各状态的动画名默认就是 `"fly"` / `"sit"` / `"dance"`**（实施计划 §七 选 B） | 用户裁定。好处：资产有动画时开箱即用。**代价（已明确接受）**：资产缺该动画的 NPC 执行对应状态会**静默塌成 T-pose**，且无日志（`AnimationProcessor.java:44-64`）—— **属预期行为，不要当缺陷报**。被否决的选项 A 是"所有状态默认回落 `idleAnimationName()`"，能把灾难级静默失败降级成轻微静默失败（注意 A 不是运行时探测）。<br>📌 **2026-09-27 更正**：本条此前举例说"末没有 `sit`/`dance`"——**错**。核对全部 29 条动画后确认末**有 `sit`（261 骨骼）**、只是**没有 `dance`**；`sit` 因基类默认名正好是 `"sit"` 而**开箱可用**。当时的错误在于**只看了资产里"飞 / 翅膀"相关子集就下断言** |

---

## 五、非目标

- 不做状态的**正交组合**（"飞行中跳舞"明确不支持）
- 不做**自主**状态切换（没有 goal/脑子会改状态；`state` 只能由 API/指令驱动）
- 不做状态间的过渡动画/过渡态（如"起飞中""落地中"这类中间状态）
- 不加自定义 `EntityDataSerializer`
- 不把动画名放进枚举
- 不动 `MoRenderer` 的模型缩放、影子、剔除盒，以及 `NpcAttackGoal` 的攻击语义

---

## 六、验证策略与**已知残留风险**

本项目**无测试源集**，验收 = `gradlew build` + 静态探针 + 实机。

### A. 可静态证明的

| # | 断言 |
|---|---|
| A1 | `javap` 确认 `NpcState` 枚举、`DATA_STATE`、`switchState`、`isMovementMode`、`xxxAnimationName` 均已生成 |
| A2 | `switchState` 的字节码里 `FLYING` 分支引用 `FlyingMoveControl`/`FlyingPathNavigation`，其余分支引用 `GroundPathNavigation`/`MoveControl` |
| A3 | NBT 键读写成对存在；`CODEC.byName(..., IDLE)` 的常量池引用存在 |
| A4 | 指令树：`state`/`move`/`stop`/`attack`(含 `stop`)/`reset` 均可解析；旧指令 `walk`/`fly` 已不存在 |
| A5 | lang：两语言键集合一致；旧键（`walk`/`fly_on`/`fly_off`/`fly_to`）已删、新键齐全 |

### B. 只能实机确认的（指令面变了，**上一轮的 B1–B8 全部作废，需按新面重写并重跑**）

| # | 动作 | 期望 |
|---|---|---|
| B1' | `state @e flying` | **原地悬停、不上升**（D2'） |
| B2' | `move @e <50 格外坐标>`（`flying` 态） | 能飞到（`FOLLOW_RANGE` 续路的回归） |
| B3' | `stop @e`（飞行中） | **停在原地继续悬停**，状态不变 |
| B4' | `move @e <坐标>`（`idle` 态） | 地面走过去 |
| B5' | `state @e sitting` → `move @e <坐标>` | **先站起来再走**（隐式退出姿态） |
| B6' | `state @e sitting`（正在走时） | **停下并坐下**（D9'） |
| B7' | `attack @e <victim>` → `attack @e stop` | 停止攻击；**地面回到站桩、空中回到悬停**（D10'） |
| B8' | `state @e idle`（飞行中） | 落到地面；若此前有 `move` 指令，**以地面方式继续走**（D9' 的正交） |
| B9' | **存档→退出→重进** | **状态保持**：存档时在飞 ⇒ 重登后仍在原地悬停；存档时坐下 ⇒ 仍坐着；未飞 ⇒ 仍在地面 |
| B10' | 关服重开 | 同上 |
| B11' | `state @e fliing`（拼错） | **明确报错** + 补全（严格策略） |
| B12' | 手改存档里的状态名为非法值 | **回落 `IDLE`，不崩**（宽松策略） |

### C. 已知残留风险（记录，不修）

1. **飞行中的竖直过冲**：`noGravity` 下重力恒为 0（`Entity.java:1173-1175` 的
   `getGravity()` 在 `isNoGravity()` 时返回 0，且该方法 `final` 无法覆写），
   而 `LivingEntity.java:2341` 给非 `FlyingAnimal` 的竖直衰减只有 `×0.98` ⇒
   推力停止后滑行 `≈ 50 × v`；水平是 `×0.91` ⇒ `≈ 11 × v`。又因
   `FlyingMoveControl.java:46` 对 Y 是 **bang-bang**（`yya = d1 > 0 ? f1 : -f1`，无比例项），
   理论上"陡峭/纯竖直"的 `move` 可能过冲。**实机未出现**（路径的竖直分量通常在到达前被化解）。
   ⇒ 将来若有人报"飞高了停不住"，**先查这一条**。
   （D2' 已消灭最常见的触发路径：`state … flying` 不再上升。）
2. **`attack <t> stop` 的 Brigadier 歧义**：`stop` 落在"本该是实体参数"的位置，
   Brigadier 会同时尝试"字面量"与"名为 `stop` 的实体"。**这是原版接受的形状**
   （`/tag <targets> add|remove|list` 同构造）。实际风险可忽略，代码注释中记录。
3. **`/data merge` 改状态键不即时生效**：读取只发生在 `readAdditionalSaveData`，
   要重登一次才恢复。与 `/data merge` 改 `NoGravity` 立刻生效不同，需在文档写清。
4. **加载时每个"非 `IDLE` 状态"的 NPC 会各建一套 `FlyingPathNavigation`**
   （含 `PathFinder` + `FlyNodeEvaluator`）。几个 NPC 无感；若一次 summon 数百个飞行 NPC，
   这是要看的点。

---

## 七、改动清单

| 类别 | 内容 |
|---|---|
| 代码 | `NpcEntity`：`DATA_FLYING` 布尔 → `DATA_STATE` 枚举；`setFlying` → `switchState`；`walkTo`/`flyTo` → `moveTo`（含隐式退出姿态）；新增状态机、`isMovementMode`、`xxxAnimationName`、两套 `fromName`（严格/宽松） |
| 代码 | `NpcCommand`：删 `walk`/`fly`，加 `state`/`move`；`attack` 加 `stop` 分支；`stop` 改为只停寻路（不再清攻击） |
| 代码 | lang：删 4 个旧键、加 `state`/`move`/状态名/`attack_stop` 等新键（两语言同步、按字母序） |
| 代码 | `MoEntity`：若新增 `sitAnimationName()` 等覆写点，按资产实际命名决定是否覆写 |
| 文档 | 本文；改写 `2026-09-27-npc-flight-plan.md`（或新开 plan）；在 `2026-09-27-npc-flight-design.md` 顶部加注"指令面与 D2/D7/D13 已被本文修订" |
| 文档 | `memory/decisions-log.md` + `memory/learned-patterns.md`：记"状态两族判据"与"三轴正交指令面"两条通用模式 |
| 验收 | §六 A 全跑；B1'–B12' 实机 |

---

## 八、下一步

调用 `planning` skill 生成实施计划（建议新开 `docs/plans/2026-09-27-npc-state-system-plan.md`，
并在其中吸收 `2026-09-27-npc-flight-plan.md` 里仍然有效的飞行事实）。
