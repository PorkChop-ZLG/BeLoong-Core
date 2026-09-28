# 通用 NPC 飞行 AI 设计文档

**日期**：2026-09-27
**状态**：已批准（brainstorming 五阶段走完，用户逐节确认）
**分支**：`NPC`
**选定方案**：**方案 A —— 字段热插拔（换 `moveControl`/`navigation`）+ 复用 P1-1 的持续续路**

---

## 一、问题与目标

通用 NPC 基类 `NpcEntity` 目前只有地面移动能力。需要给它加"飞行 AI"，并满足三条要求：

1. **尽可能使用原版 API**（先读准则与原版源码再动手）；
2. **飞行默认不开启**，只在特定情况（如指令）下启用；
3. **提供重置指令**，把 NPC 恢复到默认状态：原地待机站桩、无飞行 AI，即"刚被召唤出来的状态"。

**附带范围（设计中途加入）**：把末（Mo）的动画键名英文化（`待机动画` → `idle`、`翅膀默认（展开）` → `wings_idle`），让翅膀动画控制器在飞行时不与 `fly` 混合，并修资产使末的 `fly` 能加载。

---

## 二、调研结论：原版 API 事实（逐条附源码依据）

### 2.1 原版已有现成配方，且完全适用

NeoForge 21.1.236 源码中只有 **4 个**生物使用 `FlyingMoveControl`，**全部是 `PathfinderMob` 后裔**——与 `NpcEntity` 同源：

| 生物 | 基类 | 配方要点 |
|---|---|---|
| `Allay` | **`PathfinderMob`** | `moveControl = new FlyingMoveControl(this, 20, true)`（`:122`）；`.add(FLYING_SPEED, 0.1F)`（`:150`）；`createNavigation` 返回 `FlyingPathNavigation`（`:157-162`，`setCanFloat(true)`）；`checkFallDamage` 空实现（`:213`） |
| `Bee` | `Animal`（→ `PathfinderMob`） | `FlyingMoveControl(this, 20, true)`（`:139`）；`FLYING_SPEED 0.6F`（`:544`）；`createNavigation`（`:551-568`，`setCanFloat(false)`、`setCanOpenDoors(false)`、`setCanPassDoors(true)`）；悬停 = 每 N tick 以低档位重发 `setWantedPosition`（`:1230`） |
| `Parrot` | `ShoulderRidingEntity`（→ `PathfinderMob`） | 同上；**未重写 `travel`** |
| `WitherBoss` | `Monster`（→ `PathfinderMob`） | 同上 |

**关键**：`Bee` 与 `Parrot` **都不重写 `travel`** ⇒ `LivingEntity#travel` + `noGravity` 就够，配方可以更简。`Allay` 的重写非必需（算下来 `加速度 = zza × amount = FLYING_SPEED × amount`，Bee 0.6×0.02 与 Allay 0.1×0.1 同量级）。

### 2.2 关键可行性与陷阱

| # | 事实 | 依据 |
|---|---|---|
| F1 | **`moveControl` 与 `navigation` 是 `protected` 且非 final** ⇒ 运行期可整替换 | `Mob.java:109,112`（字段）、`:143,146`（构造器赋值） |
| F2 | 二者**每 tick 现读**，无缓存 ⇒ 换掉当 tick 生效 | `Mob.java:205,213`（`getMoveControl()`/`getNavigation()` 只判载具，否则返回字段）、`:797`（`navigation.tick()`）、`:804`（`moveControl.tick()`） |
| F3 | `hoversInPlace=true` ⇒ 空闲时**不关** `noGravity` ⇒ 原地悬停 | `FlyingMoveControl.java:18-55`，尤其 `:49-51` 的 `if (!this.hoversInPlace) setNoGravity(false)` |
| F4 | 空中会读 `FLYING_SPEED`，**属性不存在会抛异常** | `FlyingMoveControl.java:38`；属性默认 0.4、范围 `[0,1024]`（`Attributes.java:65`） |
| F5 | `customServerAiStep()` 在 **goal 仲裁之后**运行 | `Mob.java:792`（`goalSelector.tick()`）→ `:797`（`navigation.tick()`）→ `:800`（`customServerAiStep()`）⇒ 在这里续的路**不会被任何 goal 拆解抹掉** |
| F6 | **`FlyingPathNavigation` 同样吃 `FOLLOW_RANGE`(=16) 的距离上限** | `FlyingPathNavigation extends PathNavigation`；`PathNavigation.java:151,169` 用 `FOLLOW_RANGE` 作 `maxRange`；`Mob.java:160` 给的是 16 ⇒ **不复用续路就会复现 P1-1 的"走一半就停"** |
| F7 | **`NoGravity` 会被写进 NBT 并在加载时恢复** | `Entity.java:1769`（`putBoolean("NoGravity", ...)`）、`:1878`（`setNoGravity(...)`）⇒ 见 §3.4① |
| F8 | `PathNavigationRegion` 用 `getChunkNow` + `EmptyLevelChunk`，**不阻塞主线程** | `PathNavigationRegion.java:43-48`、`:72` |
| F9 | 飞行寻路**不会**把目标区块规整到可站立面 | `GroundPathNavigation.java:47-84` 覆写了 `createPath(BlockPos)` 做地面吸附，`FlyingPathNavigation` **没有** ⇒ 空中坐标按原样使用（这正是飞行要的） |
| F10 | 横向移动会让 `walkAnimation.speed() != 0` ⇒ GeckoLib 认为 `isMoving()` | `LivingEntity.java:2373-2376`（`calculateEntityAnimation(boolean includeHeight)` 只是决定是否把 Y 算进去，X/Z 照算）—— 用"飞行时只播单一 `fly`、不进 idle/walk/run 分支"来规避 |
| F11 | 中文键名受 GeckoLib 用 `Charset.defaultCharset()` 读资产的影响 | `FileLoader.java:73`；本机 `file.encoding=UTF-8` 但 `native.encoding=GBK` ⇒ 英文化可彻底免疫 |

---

## 三、设计

### 3.1 架构

**状态放在 `NpcEntity`（实体侧）**：飞行标志 `flying` 与 `moveTarget`（由现有 `walkTarget` 泛化，地面/空中共用）。

**⚠️ 修正（2026-09-27，planning 阶段发现）**：`flying` **必须存进同步数据**（`SynchedEntityData`，仿 `Allay` 的 `DATA_DANCING`：`Allay.java:84` 定义、`:166-167` `defineSynchedData`、`:417,422` 读写），**不能是普通字段**。原因：动画控制器的谓词跑在**客户端**（渲染时），而普通字段只在服务端更新 ⇒ 客户端永远读到 `false` ⇒ **`fly` 动画根本不会播**。
用 `entityData` 作为唯一真相还有个附带好处：它**天然不持久化**，正好符合 §四 D7。
（依据：`Entity.java:342` 的 `defineSynchedData(SynchedEntityData.Builder)` 是抽象方法，由每个 `Entity` 子类实现；`Entity.java:220,263,1161-1166` 证明连 `DATA_NO_GRAVITY` 本身都是同步项。）

**不持久化 `flying`**，但必须补一步归一化（见 §3.4①）。

**只用原版 API，自研部分只有"换两个字段"**：

| 用途 | 原版 API |
|---|---|
| 空中移动 | `new FlyingMoveControl(this, 20, true)` |
| 空中寻路 | `new FlyingPathNavigation(this, level)`（`setCanOpenDoors(false)`、`setCanFloat(false)`、`setCanPassDoors(true)`） |
| 速度属性 | `Attributes.FLYING_SPEED`，**必须加进属性表** |
| 关飞行 | `setNoGravity(false)` + 换回 `MoveControl`/`GroundPathNavigation` |
| 摔落 | **不覆写** `checkFallDamage`（见 §4） |

**指令面**：

```
/beloong npc fly   <targets> on
/beloong npc fly   <targets> off
/beloong npc fly   <targets> to <pos>
/beloong npc reset <targets>
```

`fly` 下再分 `on|off|to <pos>`，与现有 `walk <targets> <pos>` 的"先选实体再给参数"风格一致；沿用 `hasPermission(2)` + 过滤 `NpcEntity` + 非 NPC 时 `fail()`。

**`reset` = "刚被召唤出来的状态"**：清移动目标 + 清攻击 + `setTarget(null)` + 关飞行 + 换回地面导航/移动控制 + **无条件** `setNoGravity(false)`。

### 3.2 组件与代码结构

#### ① `NpcEntity`（改动主体）

- **新增状态**：`flying`（**存同步数据**，见 §3.1 的修正）；`walkTarget` 泛化为 `moveTarget`
- **新增可覆写默认值**（照 `idleAnimationName()` 那套）：
  - `flyAnimationName()` → 默认 `"fly"`（飞行的**唯一**动画）
  - `takeoffHeight()` → 默认 `2.0`（起飞偏移：当前 Y + 此值）
- **新增外部 API**（只在服务端生效，与 `walkTo`/`attack`/`stopAction` 同构）：
  `setFlying(boolean)` / `isFlying()` / `flyTo(Vec3)` / `resetToDefault()`
- **新增属性**：`createNpcAttributes()` 里 `.add(Attributes.FLYING_SPEED, 0.6D)`
  - 取值依据：不重写 `travel` 时空中加速度 `= 0.02 × FLYING_SPEED`；Bee 用 0.6（加速度 0.012）。0.6 是原版飞生物里响应较好的一档，且属**一行可调**常量。
- **内部**：`enableFlight()` / `disableFlight()`；`tickWalkCommand()` → `tickMoveCommand()`（地面/空中两态）
- **控制器**：主状态机加分支 `if (flying) → flyAnimationName()`，**跳过 idle/walk/run**
- **载入归一化**：`readAdditionalSaveData` 收尾 `setNoGravity(false)`

#### ② `NpcCommand`

新增 `fly`（下辖 `on`/`off`/`to <pos>`）与 `reset` 两条子命令，沿用现有风格与反馈消息。

#### ③ `MoEntity`

| 改动 | 内容 |
|---|---|
| `idleAnimationName()` | `"待机动画"` → `"idle"` |
| 翅膀层常量 | `"翅膀默认（展开）"` → `"wings_idle"` |
| 翅膀控制器 | 谓词加：**飞行时返回 `PlayState.STOP`**，不与 `fly` 混合 |

`PlayState.STOP` 是正确做法：停掉该控制器后，被 `fly` 驱动的骨骼由主控制器写，两者都没驱动的骨骼按既有机制复位到 initial snapshot，**不会残留上一个姿态**。

#### ④ 资产 `mo.animation.json`（两处改动，都是"删/改名"而非改姿势）

1. **改名 2 个键**：`待机动画` → `idle`、`翅膀默认（展开）` → `wings_idle`
   —— 顺带**彻底免疫** F11 那条字符集隐患。
2. **删 6 个死骨骼条目**：`fly` / `swim` / `swim_stand` 三条里各删 `AllBody_Molang` 与 `Head_Molang`
   —— 这 10 条非法表达式（含中文与单引号，违反 `MathParser.java:46` 的 `EXPRESSION_FORMAT`，被 `BakedAnimationsAdapter.java:39-53` 整条丢弃）**只挂在这两根骨骼上**，而这两根**都不在 geo 里** ⇒ GeckoLib 本来就会 `if (bone == null) continue` 跳过 ⇒ **删了不影响渲染，却让三条动画从此能加载**。

**明确不改**：geo、贴图、其余 26 条动画、以及另 6 个**代码从不引用**的中文键名（`躯体选择备份`、`头发选择备份`、`以巴`、`以巴2`、`翅膀默认（收起）`、`武器拆分`）。

### 3.3 数据流

**每 tick 公共骨架**（不变）：

```
Mob.serverAiStep
 ├ :792 goalSelector.tick()      ← goal 仲裁（可能 nav.stop()）
 ├ :797 navigation.tick()        ← 读当前字段；把下一路点交给 moveControl
 └ :800 customServerAiStep()     ← 我们在这里 tickMoveCommand() 续路，晚于仲裁 ⇒ 抹不掉
     :804 moveControl.tick()     ← 读当前字段；执行移动 + 管 noGravity
```

**① `fly <targets> on`**

```
setFlying(true) → enableFlight()   ← 幂等：已飞则直接 return
  ├ moveControl = new FlyingMoveControl(this, 20, true)
  ├ navigation  = new FlyingPathNavigation(this, level)（按 Bee/Allay 设三个开关）
  ├ setNoGravity(true)
  └ moveTarget  = 当前位置 + (0, takeoffHeight, 0)；冷却清零
下一 tick：tickMoveCommand() → navigation.moveTo(target)
再下一 tick：PathNavigation.tick():254 → moveControl.setWantedPosition(...) → 上升
三维到位：moveTarget = null + nav.stop()
⇒ FlyingMoveControl 进 WAIT：hoversInPlace=true ⇒ 只把 yya/zza 归零，不关 noGravity ⇒ 悬停
```

**"悬停"不是独立状态**，它就等于「飞行中 + 没有移动目标」。

**② `fly <targets> to <pos>`**

前置校验：不在飞行状态就 `fail("先 /beloong npc fly <目标> on")`——**不做隐式自动开启**。

与 P1-1 完全相同的续路循环（`isDone()` 或冷却到期 → `moveTo`；目标 > 16 格 → 部分路径 → 飞一段 → 续）。**到位判定空中用三维**（地面版只看水平，因为寻路会把 Y 规整到可站立面，空中没有这个前提，见 F9）。

**③ `fly <targets> off`**

```
setFlying(false) → disableFlight()
  ├ moveTarget = null；navigation.stop()    ← 先停旧路径，再换字段
  ├ setNoGravity(false)
  └ moveControl = new MoveControl(this)；navigation = new GroundPathNavigation(this, level)
⇒ 恢复重力，直接掉下来
```

**④ `reset <targets>`**

```
moveTarget = null；冷却/计数复位
attackCommandActive = false；setTarget(null)
if (flying) disableFlight()
getNavigation().stop()      ← 兜底
setNoGravity(false)         ← 【无条件】，见 §3.4①
⇒ 无飞行、无移动、无攻击、地面站桩；下一帧 isMoving()=false ⇒ 自动回 idle
```

顺序上「先停旧路径再换字段」是有意的：否则旧 `FlyingPathNavigation` 还持有下一路点，而 `moveControl` 已换成地面版，会出现一拍「地面移动控制执行空中路点」的错配。

### 3.4 错误处理与边界

#### ① 状态一致性不变式：`flying == false` ⇒ `noGravity == false`

`flying` 不持久化、但 `NoGravity` 持久化（F7）⇒ 飞行中存档/重登会留下"**永久浮空、但 `flying=false`**"的实体，且 `disableFlight()` 永远不会被触发。两处强制：

| 位置 | 做法 |
|---|---|
| `resetToDefault()` | **无条件** `setNoGravity(false)`——**不写成 `if (flying)`**，否则正是脱钩状态下修不回来 |
| 实体加载 | `readAdditionalSaveData` 收尾 `setNoGravity(false)`：既然 `flying` 不持久化，加载后必为 false，就把不变式拉回来 |

⇒ 重登后一律回到"无飞行、地面站桩"，与"默认状态是无飞行 AI"及 `reset` 语义一致。

#### ② 参数/前置校验

| 情况 | 处理 |
|---|---|
| `fly to` 但未开飞行 | `fail()` 明确报错并提示先 `fly on`（不隐式开启） |
| 选择器没选中 NPC | 沿用现有 `fail()` |
| 权限不足 | 沿用现有 `hasPermission(2)` |

#### ③ 幂等性

| 命令 | 重复执行 |
|---|---|
| `fly on` | **已飞则直接 return，不重建字段**——重建会丢掉正在执行的飞行路径，等于无声地把 NPC 定住 |
| `fly off` / `reset` | 无害（内部判空） |

`disableFlight()` **不设任何提前 return 路径**，保证 `setNoGravity(false)` 一定执行到——"浮空且无人知道"是最难查的一类状态。

#### ④ 不可达目标

复用 P1-1 的**有界失败**（连续 N 次续路未更靠近 ⇒ 放弃 + debug 日志）。空中比地面安全：放弃后是**悬停**，不会掉进虚空。

#### ⑤ 未加载区块

`PathNavigationRegion` 把未加载区块当 `EmptyLevelChunk`（空气）⇒ 飞行寻路"看穿"未加载地形。这是原版飞生物已有行为（F8），且超出 16 格本就会截断 ⇒ **不额外处理**，仅记录。

#### ⑥ `fly` 动画不存在 = 静默塌成 T-pose（**只写文档，不加代码保护**）

动画名找不到 → **不报错、不打日志** → 空动画列表 → 骨骼复位到静止姿态。末正是靠改资产绕开。
**决定：不加运行时回落保护**（避免客户端 API 耦合），改为在 `NpcEntity#flyAnimationName()` 的 javadoc 里写明"名字必须存在于资产，否则静默塌成 T-pose"。

#### ⑦ 飞行中执行地面指令

`walk` 在飞行状态下：导航已是飞行版、`moveTarget` 共用 ⇒ 语义上**就是 `fly to`**。**决定：允许**（拒绝反而更绕——得先 `off` 再 `walk`），但会在 javadoc 与指令反馈里写明。`attack` 同理：`MeleeAttackGoal` 走 `getNavigation()`，会以飞行方式接近目标，不特判，列入实机验收。

---

## 四、决策记录

| # | 决策 | 依据 |
|---|---|---|
| D1 | **定位为"纯能力"**：只提供 API + 指令，NPC 不自主动、不盘旋、不跟随 | 用户裁定；与"默认站桩、能力即 API"的既有原则一致 |
| D2 | **开启飞行即起飞并悬停**（当前 Y + `takeoffHeight`），之后不去就浮着 | 用户裁定；`hoversInPlace=true` 天然支持 |
| D3 | **关飞行（含 reset）在半空时直接恢复重力掉下来**，不做降落状态机 | 用户裁定；实现最简 |
| D4 | **飞行只有一个动画、键名 `fly`、可覆写** | 用户裁定；顺带规避 F10（飞行时不进 walk 分支 ⇒ 腿不会在空中走） |
| D5 | **`checkFallDamage` 不覆写** | 免伤已由 `isInvulnerableTo`（只放行 `BYPASSES_INVULNERABILITY`）保证；覆写只剩"吞掉落地音效"一个作用，却要多一个条件分支。原版配方的永久空实现（Bee/Allay）反而会改掉它**不飞行时**的落地行为 |
| D6 | **`fly to` 未开飞行时报错**，不隐式开启 | 用户原话"开启飞行后用 fly to 控制"⇒ 前置条件而非副作用 |
| D7 | **状态不持久化 + 加载归一化** | 与既有 `attackCommandActive`/`walkTarget` 一致；F7 要求补一行 |
| D8 | **复用 P1-1 的持续续路** | F6：`FlyingPathNavigation` 同样吃 `FOLLOW_RANGE` 的 16 格上限，不复用必然复现"飞一半就停" |
| D9 | **末的 `fly` 靠改资产修**（删死骨骼条目），而不是换成播 idle | 用户裁定 |
| D10 | **末的中文动画键名英文化**（`idle`/`wings_idle`），翅膀控制器飞行时 `PlayState.STOP` | 用户裁定；顺带免疫 F11 |
| D11 | **⑥ 静默 T-pose 只写文档，不加代码保护** | 用户裁定，避免客户端 API 耦合 |
| D12 | **不改 `MoModel`/`DihuangLoongModel` 逻辑**（只更新 javadoc 里的旧动画名）、不动碰撞箱/剔除盒/影子/`NpcAttackGoal` | 控制改动面 |
| **D13** | **`flying` 存进同步数据（`SynchedEntityData`）而非普通字段** | planning 阶段发现：动画控制器谓词在**客户端**执行，普通字段在客户端永远为 `false` ⇒ `fly` 动画不会播。依据 `Entity.java:342`（`defineSynchedData` 抽象）+ `Allay.java:84,166-167,417,422`（vanilla 范式） |

---

## 五、非目标

- 不做自主飞行行为（无新增 goal、不盘旋、不跟随玩家）
- 不做起飞/降落的过渡状态机
- 不做飞行时的自定义避障（避障由 `FlyNodeEvaluator` 免费提供）
- 不持久化飞行状态
- 不做 `fly` 动画缺失的运行时回落保护（只写文档）
- 不英文化另外 6 个代码从不引用的中文键名
- 不动 `MoRenderer` 的模型缩放、影子、剔除盒

---

## 六、验证策略

本项目**没有测试源集**，验收 = `build` + 静态探针 + 实机。

### A. 可静态证明的

| # | 断言 | 手段 |
|---|---|---|
| A1 | 新成员已生成 | `javap` 查 `flying`、`setFlying`/`isFlying`/`flyTo`/`resetToDefault`/`flyAnimationName` |
| A2 | 换字段用的是原版类 | `javap -c` 常量池含 `FlyingMoveControl`、`FlyingPathNavigation`、`GroundPathNavigation`、`MoveControl` |
| A3 | `FLYING_SPEED` 进了属性表 | 常量池 + `Attributes.FLYING_SPEED` 引用（**不 add 会在空中抛异常**，F4） |
| A4 | **三条动画真的能加载** | 重新解析 `mo.animation.json`，断言 `fly`/`swim`/`swim_stand` 的字符串中 **`'` 与非 ASCII 计数均为 0** ⇒ 可证明 |
| A5 | 改名彻底 | 断言 `idle`/`wings_idle` 在、旧中文名不在；全仓 `grep` 无旧名引用 |
| A6 | 死骨骼条目已删 | 断言 `AllBody_Molang`/`Head_Molang` 不再出现于那三条；动画总数仍为 29；记录字节数变化 |
| A7 | 模型源文件仍未入库 | `git ls-files docs/models` 为空 |
| A8 | 构建通过 | `gradlew build` BUILD SUCCESSFUL |

### B. 只能实机确认的

| # | 动作 | 期望 |
|---|---|---|
| B1 | `fly @e on` | 起飞约 2 格并**悬停不掉**，播 `fly`；末的翅膀**不被翅膀层拉扯** |
| B2 | `fly @e to <50 格外坐标>` | **能飞到**（P1-1 那个 16 格上限在飞行上的回归测试） |
| B3 | `fly @e off`（半空） | 直接掉下落地、无摔落伤害、恢复地面行为 |
| B4 | `reset @e` | 回到地面站桩 + `idle`；末播 `idle`、翅膀回 `wings_idle` |
| B5 | 未开飞行时 `fly @e to ...` | 明确报错并提示先 `on` |
| B6 | **存档→退出→重进** | NPC 必须**站在地上**、不浮空（§3.4① 的 `NoGravity` 陷阱回归测试） |
| B7 | 飞行中 `walk` | 以空中方式移动（已定为等同 `fly to`） |
| B8 | 飞行中 `attack` | 以飞行方式接近目标；若卡住需反馈 |

### C. 本设计最没把握的三件事（实机重点看）

1. **换 `navigation`/`moveControl` 是否真即插即用**——F2 已核对无缓存，但**原版没有生物这么干过**。若 `fly on` 毫无反应，问题就在这里。
2. **`hoversInPlace=true` 是否真悬得住**——若发现缓慢下沉，可能要补周期性 `setWantedPosition`（Bee 的悬停就是每 N tick 重发，`:1230`）。
3. **末的 `fly` 姿势**——它是导入的玩家状态动画、缺 75 根骨骼。加载问题解决了，**姿势像不像飞行是另一回事**。

---

## 七、施工顺序（供 planning 参考）

1. `NpcEntity`：状态/API/`enableFlight`/`disableFlight`/`tickMoveCommand`/`readAdditionalSaveData` 归一化 + `FLYING_SPEED` + 控制器飞行分支
2. `NpcCommand`：`fly on|off|to` + `reset`
3. 资产 `mo.animation.json`：改名 2 键 + 删 6 个死条目
4. `MoEntity`：改引用名 + 翅膀控制器飞行时 `PlayState.STOP`
5. 更新 `MoModel`/`MoEntity` javadoc 里的旧动画名
6. 静态探针 A1–A8 → 实机 B1–B8

---

## 八、下一步

调用 `planning` skill 生成 `docs/plans/2026-09-27-npc-flight-plan.md`。
