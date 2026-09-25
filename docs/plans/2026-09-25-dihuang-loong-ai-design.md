# 地黄龙 NPC 基础 AI 与基础动画 设计文档

**日期：** 2026-09-25
**状态：** 已批准
**分支：** `NPC`
**采用方案：** goal 驱动 + API 门面 —— 开启 AI（`FloatGoal` + `LookAtPlayerGoal` + 自定义 `FacePlayerGoal` 常驻；`TurnGoal` 与移动能力默认不触发），头颈用 Molang 相对角跟随，动画 `idle` ↔ `walk`
**前置文档：** `docs/plans/2026-09-21-dihuang-loong-npc-design.md`（首版：无 AI / 无敌 / 站桩。本文档**推翻其 D2**、**更正其 D5 的理由**）
**参考实现：** GeckoLib 4.9.2 源码（`D:\Minecraft\开源模组参考文件\Geckolib`，版本经 `gradle/libs.versions.toml:3` 与 `changelog.txt:1` 核实）；Dragon Survival `client/models/DragonModel.java`（Molang 头部跟随的既有写法）；原版 1.21.1 + NeoForge 补丁合并源

---

## 一、问题陈述

首版地黄龙是**故意做成"雕像"**的：不注册 goal、并且**覆写 `isNoAi()` 恒为 true**（首版 D2 / R8）。
现在要在保留"无敌 / 不可推动 / 默认站桩"这三条的前提下，让它成为一个**有基础 AI 与基础动画的生物**：

1. 需要有**生物的基础 AI 和基础动画**；
2. **默认不自主行动**（不转圈、不游走，面朝一个方向），但**玩家靠近时面朝玩家**；"转圈"与"移动"作为**能力**提供给外部调用；
3. 保留**无敌**与**不可推动**，但**要受重力影响**。

### 1.1 需求 3 揭示的一个真 bug：它现在根本不受重力

首版设计 D5 写的是"不调用 `setNoGravity`，让重力正常作用"，**理由不完整**。真正拦住重力的是 no-AI：

```
LivingEntity.travel(Vec3)                     // LivingEntity.java:2219
    if (this.isControlledByLocalInstance()) { // :2220  ← 整个方法体都在这个 if 里
        double d0 = this.getGravity();         // :2221
        ...
```

而 `Entity#isControlledByLocalInstance()`（`Entity.java:3215-3217`）对无乘客实体就是 `isEffectiveAi()`，
`Mob#isEffectiveAi()`（`Mob.java:1420`）= `super.isEffectiveAi() && !this.isNoAi()`。
⇒ 首版 `isNoAi()` 恒 true ⇒ `travel()` **整个方法体从不执行** ⇒ **既没有重力、也没有任何位移积分**：
它其实是被"钉"在召唤点的，而不是"站在地上"。

**⇒ 需求 1（开启 AI）与需求 3（受重力影响）是同一件事**：去掉 `isNoAi()` 覆写，重力自动回来。

> **首版 R8 的教训要反着用一次。** R8 教的是"用 getter 覆写承载语义才可靠"（构造函数里的 `setNoAi(true)`
> 会被 `/summon` 的 `load()` 覆盖）。现在要**关掉** AI，就必须把**构造函数里的 `setNoAi(true)` 与
> `isNoAi()` 覆写一起删** —— 只删一处都不生效（首版正是靠这两处并存才稳定）。

---

## 二、目标与非目标

### 目标

1. 开启 mob AI，注册四个 goal（见 §四）；`FloatGoal` 与两个 look/face goal 常驻。
2. **玩家靠近（8 格）时身体平滑转向玩家**，同时**头/颈用 Molang 额外对准**（头先到、身体后到）。
3. **默认零自主行动**：不游走、不自主转身、朝向不变。
4. 提供 `turnTo(yaw)` / `walkTo(pos)` / `stopAction()` 三个 API；**默认不触发**，由外部驱动。
5. 动画状态机：默认 `idle` 循环；**真正在移动时**自动切 `walk`。
6. 保留 `isInvulnerableTo` / `isPushable` / `isPersistenceRequired` 三个覆写不变。
7. 恢复重力（随 1 自动达成）。
8. 加一个 op 级调试命令用于验收与手动摆位。

### 非目标

- ❌ 不做**自主**游走 / 自主转圈（默认永远站桩）
- ❌ 不接 `fall_loop` / `land` / `jump` / 受击 等动画（本轮只 `idle` ↔ `walk`）
- ❌ 不做条件、进度判据、对话驱动的行为
- ❌ 不改对话系统（`PlayerInteractEvent.EntityInteract` 那条链不动）
- ❌ 不做缩放 / 多形态 / 动画状态机之外的花样
- ❌ 不做"玩家离开后转回初始朝向"（朝向停在最后值，用户裁定）
- ❌ 不新增网络包（D27）；不改 mixin；`ModEntities` / `ModAttributes` 除 `MOVEMENT_SPEED` 外不动

---

## 三、已核实的技术前提（附出处）

### 3.1 GeckoLib 4.9.2（版本已核实）

| # | 结论 | 出处 |
|---|---|---|
| 3.1.1 | 头部跟随的钩子是 `GeoModel#applyMolangQueries(AnimationState<T>, double)`（**不是**旧 wiki 的 `(Animatable, double)`），在 `AnimationProcessor#preAnimationSetup` 里于 controller tick **之前**调用 | `model/GeoModel.java:249`；`AnimationProcessor.java:306-309`；`GeoModel.java:221` |
| 3.1.2 | 注册自定义查询：`MathParser.setVariable(String, DoubleSupplier)` 或 `MolangQueries.setActorVariable(String, ToDoubleFunction)`；两者写同一个 `Variable` 实例，因此**烘焙之后再注册也有效**（`Variable` 惰性取值） | `loading/math/MathParser.java:143-145`；`MolangQueries.java:199-201`；`value/Variable.java:17,27-30` |
| 3.1.3 | **未注册的查询静默取 0**，不报错不告警 | `MolangQueries.java:148-150`；`MathParser.java:432-437` |
| 3.1.4 | 查询必须返回**度数**：非常量值在运行期 `toRadians` 且 **X/Y 取负** | `AnimationController.java:747-761`；烘焙期常量转换见 `BakedAnimationsAdapter.java:205-207` |
| 3.1.5 | Molang 变量是**全局静态**的（`MolangQueries.VARIABLES`）⇒ 每个实体每帧必须重设，`applyMolangQueries` 的调用顺序正是为此保证的 | `MolangQueries.java:118,174-176,181-183` |
| 3.1.6 | `applyMolangQueries` 里 **`state.getController()` 为 null**（`AnimationState` 每帧新建，`withController` 发生在更晚）⇒ 只能用惰性 supplier | `GeoEntityRenderer.java:268`；`AnimationProcessor.java:88` |
| 3.1.7 | 内置的 `query.head_y_rotation` 是**绝对** head yaw（`getViewYRot`），会把整体朝向叠进去 ⇒ **不能用**，必须自注册相对角 | `MolangQueries.java:286` |
| 3.1.8 | **转 `yBodyRot` 就能让整个模型转过去**：renderer 用 `Axis.YP.rotationDegrees(180f - Mth.rotLerp(partialTick, yBodyRotO, yBodyRot))` | `GeoEntityRenderer.java:218,250,375-380` |
| 3.1.9 | GeckoLib **没有任何** `LookControl` / lookAt / AI 集成；头骨只有两条路能动：`DefaultedEntityGeoModel` 的 headBone 选项（我们不用），或动画自己 key（我们现在这条路） | 全树 grep 零命中；`DefaultedEntityGeoModel.java:64-74` |
| 3.1.10 | `AnimationState#isMoving()` = **横向 delta ≥ 0.015/tick**（`getMotionAnimThreshold`）**且** `limbSwingAmount(=walkAnimation.speed) ≠ 0`，且 `limbSwingAmount` 上限 1 | `GeoEntityRenderer.java:253,259-260,266-268`；`GeoRenderer.java:117-119` |
| 3.1.11 | `RawAnimation#thenLoop` 才是"循环"；`thenPlay` = `LoopType.DEFAULT`，**跟随 JSON 的 `loop` 标志**（本资产 `idle`/`walk` 都是 `"loop": true`） | `RawAnimation.java:58,67`；`Animation.java:41-42` |
| 3.1.12 | `transitionLengthTicks` 把每个骨骼从**快照**lerp 到新动画首帧，并在过渡期把 `query.anim_time` 强制为 0 | `AnimationController.java:466,519,540-559` |
| 3.1.13 | 动画名解析不到时会**静默 `stop()`**（只有一行日志）；缺失骨骼默认**优雅忽略** | `AnimationController.java:368-385`；`AnimationProcessor.java:49-64`；`GeoModel.java:91-93` |
| 3.1.14 | `GeoEntity` 只需实现 `registerControllers` + `getAnimatableInstanceCache`（`getTick` 已有 default） | `GeoEntity.java:126-128` |

### 3.2 原版 1.21.1

| # | 结论 | 出处 |
|---|---|---|
| 3.2.1 | `LivingEntity.travel(Vec3)` 的**整个方法体**被 `if (this.isControlledByLocalInstance())` 包住 | `LivingEntity.java:2219-2221` |
| 3.2.2 | `Entity#isControlledByLocalInstance()` = `getControllingPassenger() instanceof Player p ? p.isLocalPlayer() : this.isEffectiveAi()` | `Entity.java:3215-3217` |
| 3.2.3 | `Mob#isEffectiveAi()` = `super.isEffectiveAi() && !this.isNoAi()` | `Mob.java:1420` |
| 3.2.4 | **`yBodyRot` 会以 0.3 插值追 `yRot`**，并被 `getMaxHeadRotationRelativeToBody()` 夹住 —— 所以只需设 `yRot`，不要手写 `yBodyRot` | `LivingEntity.java:2700-2714` |
| 3.2.5 | `LookControl#tick()` **只写 `yHeadRot`**（无目标时还把它往 `yBodyRot` 拉回，并夹在 `yBodyRot ± maxHeadYRot`）⇒ **身体 yaw 永远不跟随 look 目标** | `LookControl.java`（`tick` 全体只有 `yHeadRot` 赋值） |
| 3.2.6 | goal / navigation / moveControl / lookControl 全都在 `serverAiStep()` 里 tick，而它被 `isEffectiveAi()` 把关 | `LivingEntity.java:2760-2769` |
| 3.2.7 | 可用 goal 签名：`LookAtPlayerGoal(Mob, Class<? extends LivingEntity>, float[, float probability[, boolean onlyHorizontal]])`、`RandomLookAroundGoal(Mob)`、`FloatGoal(Mob)` | 三个 goal 类源码 |

### 3.3 资产事实（`assets/beloong/`）

| # | 结论 |
|---|---|
| 3.3.1 | 动画 **96 个**；命名成对出现，`*2` 后缀的只有 **1 根骨骼**（残桩，如 `idle2`/`walk2`），别用 |
| 3.3.2 | **没有"转圈/turn"动画** ⇒ 「转圈」只能作为**行为**（转 yaw）实现，不是播某个动画 |
| 3.3.3 | `idle`：**32 骨骼 / 4.75s / loop**，模型骨骼覆盖 **0 缺失**；`walk`：**63 骨骼 / 1.375s / loop**，0 缺失 |
| 3.3.4 | `idle` / `walk` / `dance` / `sit` … **34 个动画**都用了 `query.head_yaw`（每个 6 处）与 `query.head_pitch`（1~7 处） |
| 3.3.5 | 具体表达式（`idle`）：`Head-Molang.rotation = ["math.clamp(query.head_pitch*0.5,-45,45)", "-math.clamp(query.head_yaw*0.355556,-32,32)", 0]`；`Neck-Molang.rotation = [0, "-math.clamp(query.head_yaw*0.355556,-32,32)", "math.clamp(query.head_yaw*0.111111,-10,10)"]`；`Head.rotation = ["math.clamp(query.head_pitch*0.34,-30,10)", 0, 0]` |
| 3.3.6 | 由 3.3.5 的系数可反推预期量纲：`head_yaw*0.3556` 夹在 ±32 ⇒ `head_yaw ∈ ±90`（**度**）；`head_pitch*0.34` 夹在 −30..10 ⇒ `head_pitch ∈ −88..29`（**度**，原版 `xRot` 约定：负=抬头） |
| 3.3.7 | geo：`geometry.unknown`，**145 骨骼**，根骨骼 `Magic` + `Dragon`，`visible_bounds` 12×5；`idle`/`walk` 都动 `Dragon` 与 `Magic` |
| 3.3.8 | 模型最低点在 **y ≈ −0.88 格**（原点不在脚底）—— 首版的 R1，一直没验；**有了重力后才会真正暴露**（见 §九 R9） |

---

## 四、架构与决策

### 4.1 goal 集合

| 优先级 | Goal | 作用 | 默认 |
|---|---|---|---|
| 0 | `FloatGoal` | 落水/岩浆上浮（"基础生物"该有的） | 常驻 |
| 5 | `LookAtPlayerGoal(this, Player.class, 8F)` | 驱动 `yHeadRot` —— **头颈 Molang 的数据源** | 常驻 |
| 10 | `DihuangLoongFacePlayerGoal`（自定义） | 玩家靠近时设 `yRot` 朝向他，≤10°/tick | 常驻 |
| 20 | `DihuangLoongTurnGoal`（自定义） | 把 `yRot` 转到 API 指定的 `turnTarget`，≤10°/tick | **不触发**（`turnTarget == null` ⇒ `canUse()` false） |

**为什么头、身体必须是两个 goal**：`LookControl#tick()` 只写 `yHeadRot`（3.2.5），
所以①不注册 `LookAtPlayerGoal` ⇒ `yHeadRot ≡ yBodyRot` ⇒ `query.head_yaw` 恒 0 ⇒ 资产里那 34 个
动画的 `-Molang` 链**依旧是死的**；②`LookControl` **永远不动 `yRot`** ⇒ 不写自定义 goal，身体就不会转向。
两者配合的额外好处：**look 响应快、身体 0.3 插值慢 ⇒ 头先转过去，身体随后慢慢跟上**。

### 4.2 决策记录（接续首版 D1–D15 与 R7/R8）

| # | 决策 | 取值 | 理由 |
|---|---|---|---|
| **D16** | 开启 AI | **同时**删构造函数里的 `setNoAi(true)` **与** `isNoAi()` 覆写 | 只删一处无效：构造函数标志位会被 `/summon` 的 `load()` 覆盖（R8），覆写才是真闸门。首版靠两者并存才稳定，现在要成对拆掉 |
| **D17** | 重力 | **不写任何代码** | 3.2.1~3.2.3：`travel()` 被 `isControlledByLocalInstance()` 包住，而它 = `!isNoAi()`。D16 一做重力自动回来。**首版 D5 的理由更正为**："拦住重力的是 no-AI，不是没调 `setNoGravity`" |
| **D18** | goal 集合 | 见 §4.1 | 满足"基础 AI"且不含任何自主游走 |
| **D19** | 身体转向 | 自定义 goal 里设 `yRot`（≤10°/tick），**不碰 `yBodyRot`** | `yBodyRot` 由原版 `tickHeadTurn` 以 0.3 插值自动跟随（3.2.4）；手写它反而会与 clamp 打架 |
| **D20** | 头颈跟随 | `DihuangLoongModel#applyMolangQueries` 注册 `query.head_yaw`（**相对角**：`wrapDegrees(rotLerp(yHeadRot) − rotLerp(yBodyRot))`）与 `query.head_pitch`（`lerp(xRot)`），**单位度** | 3.1.4/3.1.6/3.1.7/3.3.6。用内置的 `query.head_y_rotation` 会把整体朝向叠进去（绝对角），不能用 |
| **D21** | 转/走能力 | `turnTo(float yaw)` / `walkTo(Vec3)` / `stopAction()`；**不新增配置项** | 用户裁定：纯 API 外部驱动 |
| **D22** | 移动实现 | `walkTo` 直接驱动 `getNavigation()`，**不写 MoveGoal** | `PathNavigation` 独立于 goal selector；没人调就永远不会动 ⇒ "默认不动"天然成立，加 goal 只是多一个文件 |
| **D23** | 冲突处理 | `FacePlayerGoal.canUse()` 追加前置条件：**无外部指令在进行**（`turnTarget == null && getNavigation().isDone()`） | 显式指令优先于自主反应；同时避免"边走边转身体"与寻路抢 `yRot` |
| **D24** | 动画判据 | `state.isMoving() ? walk : idle`，过渡 **5 tick** | 3.1.10/3.1.12。**原地转身不算移动** ⇒ 转身时仍播 `idle`，正合"移动时才切 walk" |
| **D25** | 移动速度 | 显式设 `Attributes.MOVEMENT_SPEED = 0.2`（起始值） | `createMobAttributes()` 的注册表默认是 0.7，对一条巨龙是"贴地飞" |
| **D26** | goal 包位置 | 新增 `entity/ai/` 子包 | `entity/` 目前只有 2 个类，加 2 个 goal 后分层更清楚 |
| **D27** | 不新增网络包 | `turnTo`/`walkTo` 内部 `if (level().isClientSide()) return;` | 朝向与位置本来就在原版同步范围内，加包是多余的 |
| **D28** | 模型 y 偏移 | 先原样接，实机量偏移再补（见 R9） | 遵守首版 R1 的口径：修正在 renderer 的 `preRender` 里做，不动模型文件、不动碰撞箱 |
| **D29** | 调试命令 | root `beloong npc`，op-only（`hasPermission(2)`），新包 `command/` | 第 12 项验收（`turnTo`/`walkTo`）**没有调用方就无法证明** |
| **D30** | 命令参数 | `turn <targets> <yaw>`（绝对度）／`walk <targets> <pos> [speed]`（`Vec3Argument`，支持 `~`）／`stop <targets>` | 与原版命令习惯一致 |
| **D31** | 命令边界 | 命令**只调实体 API**，不碰实体字段 | 保持封装，顺带证明那三个 API 的签名够用 |

---

## 五、组件

| 文件 | 动作 | 职责 |
|---|---|---|
| `entity/DihuangLoongEntity.java` | 改 | 删 no-AI 两处；加 `registerGoals()`；加 `turnTarget` 状态 + `turnTo`/`walkTo`/`stopAction`；动画 predicate 改 `idle`↔`walk`；属性加 `MOVEMENT_SPEED` |
| `entity/ai/DihuangLoongFacePlayerGoal.java` | 新增 | ~40 行：8 格内最近的非旁观存活玩家 → 每 tick ≤10° 转向他 |
| `entity/ai/DihuangLoongTurnGoal.java` | 新增 | ~35 行：把 `yRot` 平滑转到 `turnTarget`，<1° 即清空并结束 |
| `client/model/DihuangLoongModel.java` | 改 | 覆写 `applyMolangQueries`，注册两个查询；带一个 `HEAD_YAW_SIGN` 常量备用（符号实机定） |
| `command/NpcCommand.java` | 新增 | `beloong npc turn/walk/stop`，op 级 |
| `BeLoongCore.java` | 改 | +1 个 `@SubscribeEvent onRegisterCommands(RegisterCommandsEvent)` |
| `client/DihuangLoongRenderer.java` | 可能改 | **仅在实机发现陷地/悬空时**加 `preRender` y 平移（R9）；必要时下调 `getMotionAnimThreshold` |
| `entity/DihuangLoongEntity#createAttributes` | 改 | +`MOVEMENT_SPEED = 0.2` |

**不改**：`ModEntities`、`beloong.mixins.json`、语言文件（命令无 lang 键需求，见 §九 R11）、
对话数据、`NpcDialogue*`、网络包注册。

---

## 六、数据流与线程

### 6.1 服务端每 tick（AI 开启后才有）

```
LivingEntity.tick()  → tickHeadTurn(getYRot(), …)        yBodyRot 以 0.3 插值追 yRot
LivingEntity.aiStep()
 ├ travel()                                              重力/位移 ← D16 恢复
 └ serverAiStep()
     ├ goalSelector.tick()
     │    FloatGoal(0)  LookAtPlayerGoal(5)  FacePlayerGoal(10)  TurnGoal(20)
     ├ moveControl.tick() / lookControl.tick()             yHeadRot 夹在 yBodyRot ± maxHeadYRot
     └ navigation.tick()                                   只有 walkTo() 给过目标才有事做
```

### 6.2 客户端每渲染帧

```
GeoEntityRenderer
 ├ 装 AnimationState(limbSwing, limbSwingAmount, partialTick, isMoving)
 ├ applyMolangQueries(state, animTime)   ← 我们的覆写（此刻 getController() 为 null）
 ├ predicate: isMoving ? walk : idle
 └ 整个模型按 180f - rotLerp(yBodyRotO, yBodyRot) 旋转
```

Molang 两个值都用 `state.getPartialTick()` 插值（头/身用 `rotLerp`、俯仰用 `lerp`），口径与
renderer 保持一致 —— 否则 20Hz 的阶梯感很明显。

### 6.3 外部驱动的 API

```
外部（命令 / 对话 / 将来的脚本）--服务端--> turnTo(yaw) / walkTo(pos) / stopAction()
  turnTo     : 写 turnTarget → 下一 tick TurnGoal.canUse()=true → ≤10°/tick 逼近 → <1° 清空、goal 结束
  walkTo     : navigation.moveTo(pos, speedModifier)；到达或失败后 isDone() 自动 true
  stopAction : turnTarget = null + navigation.stop()
客户端零同步代码（D27）：yRot / yBodyRot / 位置本来就在原版同步范围内
```

---

## 七、错误处理与边界

| 情形 | 行为 |
|---|---|
| 玩家离开 8 格 / 死亡 / 换维度 / 旁观 | `canUse()` 自然为 false，goal 结束；**朝向停在最后值**（用户裁定，不做回正） |
| 玩家在 8 格外但看得见它 | 不转。**有意只按距离判据**，不做视锥/视线判断 —— 规则越少越不容易踩坑 |
| 玩家靠近 **且** 有外部 `turnTo`/`walkTo` | 显式指令优先，`FacePlayerGoal` 整条让位（D23） |
| `walkTo` 目标不可达（悬空/被封） | `PathNavigation` 自己失败并 `isDone()`；**不重试、不报错** —— 外部驱动方负责 |
| 实体被卸载后重载 | `turnTarget` 是运行期状态、**不写 NBT** ⇒ 回到站桩（外部指令本就不该跨重载） |
| 落水 | `FloatGoal` 上浮，浮到水面后恢复氧气 |
| `/kill` / 掉虚空 | 仍可移除（`bypasses_invulnerability` / `out_of_world`）—— 不变 |
| 对话系统 | 与 AI 无交集：对话走 `PlayerInteractEvent.EntityInteract`，不读 AI 状态 |
| 专服 | Molang 覆写在 `client/model/` 下、两个 goal 不含客户端类 ⇒ 安全 |
| 命令用错目标（不是地黄龙） | 过滤后为空 ⇒ `sendFailure` 提示，不静默 |

### 7.1 运行时观测点

- 命令的 `sendSuccess` 报"对 N 个地黄龙下达了 X"（D29 的观测点）。
- 重力是否恢复：**召唤后是否下落**本身就是观测点（改前它悬停，这个差异肉眼可见）。
- Molang 是否生效：**头/颈是否转动**；`query.head_yaw` 恒 0 与符号反了是两种不同的可见失败。

---

## 八、测试策略

### 一级（静态/构建）

1. `gradlew build` 通过。
2. 静态：`DihuangLoongEntity` **不含** `isNoAi()` 覆写、**不含** `setNoAi`；**含** `registerGoals()`。
3. 静态：`isInvulnerableTo` / `isPushable` / `isPersistenceRequired` 三个覆写仍在。
4. 静态：`DihuangLoongModel` 覆写 `applyMolangQueries`，注册 `query.head_yaw` 与 `query.head_pitch`。
5. 静态：`beloong.mixins.json` 未改；`ModEntities` 未新增注册；无新增 payload 注册。
6. 静态：`command/NpcCommand.java` 存在且 `requires` 为 `hasPermission(2)`。

### 二级（实机 —— **本轮必须实机**：重力与 Molang 都无法静态验证）

| # | 步骤 | 预期 | 在验证 |
|---|---|---|---|
| 1 | 在地面上方几格 `/summon` | **下落并落地** | D16/D17（改前悬在召唤点不动） |
| 2 | 观察落地后的模型 | 脚是否陷地 —— 记下偏移量 | R9 |
| 3 | 静置数分钟 | 不动、**不自主转圈**、朝向不变、不消失 | 需求 2 前半 |
| 4 | 走进 8 格 | **头/颈先转过来**，身体随后慢慢转正 | D20 + D19 |
| 5 | 绕它走一圈 | 身体持续面朝你 | D19；顺便定 `HEAD_YAW_SIGN` 正负 |
| 6 | 走出 8 格 | 停止跟随，停在最后朝向 | §七 口径 |
| 7 | 站高处/低处看它 | 头随俯仰动、方向正确 | `query.head_pitch` |
| 8 | 攻击它 / 挤压它 | 无伤害反馈、不位移 | 需求 3 |
| 9 | `/kill @e[type=beloong:dihuang_loong]` | 可移除 | 管理后路 |
| 10 | 退档重进 | 仍在、仍无敌、不消失；记下重载后朝向 | 无存档数据 |
| 11 | 推到水里 | `FloatGoal` 上浮 | D18 |
| 12 | `/beloong npc turn @e[type=beloong:dihuang_loong] 90` 等 | 转身到位；`walk` 时切动画、停下回 `idle` | D21/D22/D24/D29 |
| 13 | 空手右键它 | 对话无回归 | — |
| 14 | 专服启动 + 执行一次命令 | 无 `NoClassDefFoundError` | D20 的客户端归属 |

---

## 九、风险

| # | 风险 | 评估与对策 |
|---|---|---|
| **R9** | **模型最低点在 y ≈ −0.88 格**（原点不在脚底，首版 R1）。首版它压根不受重力、悬在召唤点，所以看不出来；**现在会真的落地，可能整体陷进地面 0.88 格** | **中**。先原样接（D28），实机量偏移后在 `DihuangLoongRenderer#preRender` 里加一次 y 平移 —— 不动模型文件、不动碰撞箱 |
| **R10** | `query.head_yaw` **符号**可能反（GeckoLib 对 Molang 的 Y 轴取负，而 MC yaw 与 Blockbench 的旋转正向关系需要实测） | **低**，且**可见**：头朝反向就是反了。留一个 `HEAD_YAW_SIGN` 常量，翻一下即可 |
| **R11** | `isMoving()` 阈值（0.015/tick + `walkAnimation.speed ≠ 0`）可能对**慢速**行走不敏感 ⇒ 走了却还播 `idle` | **中**。对策：`walkTo` 的 `speedModifier` 先给足；仍不灵就在 renderer 覆写 `getMotionAnimThreshold` 调低 |
| **R12** | 转身速度 10°/tick 是**起始值**，与 `yBodyRot` 的 0.3 插值叠加后总延迟未知 | **低**。实机调；两个常量都在同一处 |
| **R13** | `MOVEMENT_SPEED = 0.2` 是**起始值**，对 1.5×2.5 碰撞箱的巨龙可能偏快/偏慢 | **低**。实机调 |
| **R14** | 开 AI 后其它模组的索敌/AI 可能开始对它起作用（首版因无 AI 天然免疫） | **低**：它无敌、不可推动、`MobCategory.MISC`；但**行为上可能被别的 mob 盯着**，属观感问题 |
| **R15** | 命令是**唯一的长期新增表面**，且它不是玩法内容 | **低**：op-only、Javadoc 写明定位。若将来不想留，删一个类 + 一行注册即可 |

---

## 十、后续（不在本次范围）

1. 若需要"自主游走/巡视"：把 `TurnGoal` 的思路扩展成一个 `StrollGoal`，用同一套 `yRot` 机制。
2. 接 `fall_loop` / `land` / `jump`：现在有重力了，这些动画第一次变得有意义。
3. 受击/交互反应动画：`triggerableAnim` + 服务端 `triggerAnim` 会自动同步（GeckoLib 4.9.2 已核实）。
4. 动画状态机扩到 `run`（按速度分级）—— 注意 `run` 缺 3 根骨骼（`Drip1-3`）。
5. 头部跟随也可以顺便驱动 `fly_*` / `sit` 等动画（它们同样用了那两个查询）。
6. 若将来要"玩家离开后回正"：在 `FacePlayerGoal` 结束时记录并回归初始 yaw。

---

## 十一、来源与收尾产物

| 类别 | 出处 |
|---|---|
| GeckoLib 4.9.2 | `D:\Minecraft\开源模组参考文件\Geckolib`（version：`gradle/libs.versions.toml:3`、`changelog.txt:1`），逐条出处见 §3.1 |
| Molang 头部跟随的既有写法 | `DragonSurvival\...\client\models\DragonModel.java:51,66-70` |
| 原版 1.21.1 | NFRT 合并源 `sourcesAndCompiledWithNeoForge_*.jar`（`net/minecraft/**` 行号来源） |
| 资产事实 | `src/main/resources/assets/beloong/{animations,geo}/dihuang_loong.*`（本机实测） |
| 首版设计 | `docs/plans/2026-09-21-dihuang-loong-npc-design.md` |

**收尾产物**

- 本文档（新建）；首版文档追加 **R9/R10** 指向本文档（D2 被推翻、D5 理由更正）
- `memory/project-context.md` 子系统 10 口径更新
- `memory/decisions-log.md` 追加一条：**"关掉 AI 的代价"** —— 一个 `isNoAi()` 覆写同时废掉了
  AI 与重力（经 `isControlledByLocalInstance()` → `travel()`），而外观上完全看不出来
- 提交：`feat(entity): …`（原子）+ `docs(entity): …`，不 push
