# 通用 NPC 基类 设计文档

**日期：** 2026-09-25
**状态：** 已批准
**分支：** `NPC`
**采用方案：** 新增抽象基类 `NpcEntity`（AI、语义、API、动画状态机全在基类），地黄龙改为继承它，子类只剩属性与类型绑定
**取代关系：** 本文档**取代** `docs/plans/2026-09-25-dihuang-loong-ai-design.md` 的 **§四（架构与决策中的组件放置）与 §五（组件）**；
该文档的 **§三（已核实的技术前提：GeckoLib 4.9.2 / 原版 / 资产事实）与 §八（实机测试策略）继续有效**，本文档直接引用不复制。
`docs/plans/2026-09-25-dihuang-loong-ai-plan.md` **整体作废**，由新计划取代。

---

## 一、问题陈述

上一版设计把基础 AI 直接写在 `DihuangLoongEntity` 里。用户裁定改为：**本模组自制一个通用 NPC 类，AI 写在通用类里，具体 NPC 继承它**。

理由（用户口径 + 我补充的工程理由）：

1. 本模组定位是整合包的 NPC 提供方 —— 地黄龙**不会是唯一一个 NPC**。AI、无敌/不可推动/不消失、转向/移动/攻击能力、动画状态机对每个 NPC 都一样，写在每个子类里是纯重复。
2. 上一版设计里的三个 goal（面朝玩家、转身、攻击）**本来就与地黄龙无关** —— 它们只依赖"是个 Mob"。绑到具体子类属于放错了位置。
3. 调试命令也应该服务"所有 NPC"，而不是只服务地黄龙。

**四条需求（累积口径）**：

| # | 需求 | 落点 |
|---|---|---|
| 1 | 通用 NPC 类；AI 写在通用类里；地黄龙继承它 | `NpcEntity` |
| 2 | 默认不自主行动（不转圈、不游走、面朝一个方向），但玩家靠近时面朝玩家；转/走/攻击只作**能力**由外部驱动 | 基类 API + 两个常驻 goal |
| 3 | 保留无敌与不可推动，但受重力影响 | 基类覆写 + 删掉 no-AI |
| 4 | 默认属性：血量 **1000**、击退抗性 **1.0**、攻击力 **100**、走路与奔跑速度**与玩家默认一致**；全部可覆写 | `createNpcAttributes()` + 可覆写方法 |

### 1.1 需求 3 的根因（与上一版相同，此处保留结论）

首版覆写 `isNoAi()` 恒 true；而 `LivingEntity.travel()` 的**整个方法体**被
`if (this.isControlledByLocalInstance())` 包住（`LivingEntity.java:2219-2220`），
它又 = `isEffectiveAi()` = `!isNoAi()`（`Entity.java:3215-3217`、`Mob.java:1420`）。
⇒ 首版**既没有 AI 也没有重力**，"站在地上"其实是被钉在召唤点。
**⇒ 需求 1 与需求 3 是同一件事**：基类不覆写 `isNoAi()`，重力自然回来。

---

## 二、目标与非目标

### 目标

1. 新增抽象基类 `NpcEntity`，承载：无敌/不可推动/不消失、基础 AI、五个 API、`GeoEntity` 与动画状态机、可覆写的默认属性与行为参数。
2. `DihuangLoongEntity` 缩为子类：构造器 + `createAttributes()` + 类注释（**约 30 行**）。
3. 三个 goal 改为绑定基类，放 `entity/ai/`。
4. 默认属性：`MAX_HEALTH 1000` / `KNOCKBACK_RESISTANCE 1.0` / `ATTACK_DAMAGE 100` / `MOVEMENT_SPEED 0.1`，全部可覆写。
5. 走路 = 玩家走路速度；奔跑 = **用原版冲刺机制**（不硬编码倍数）。
6. 攻击只作 API：`attack(target)` 令其走过去打，**不自主索敌、不还手**。
7. 调试命令服务所有 NPC（过滤 `NpcEntity`）。

### 非目标

- ❌ **不抽模型侧基类**（Molang 头部跟随仍写在各自的模型类里 —— 用户裁定 B）
- ❌ 不做自主游走 / 自主转圈 / 自主索敌 / 被打还手
- ❌ 本轮**不做攻击动画**（只 `idle`↔`walk`）
- ❌ 不做条件、进度判据、对话驱动的行为
- ❌ 不改对话系统、`ModEntities`（绑定不变）、mixins、资产、网络包
- ❌ 不做"玩家离开后转回初始朝向"
- ❌ 不为"假想的例外"预先加开关（例如不给无敌加布尔钩子）

---

## 三、已核实的技术前提（本次新增；其余见上一版设计文档 §三）

| # | 结论 | 出处 |
|---|---|---|
| 3.1 | **玩家默认走路速度 = 0.1**；玩家默认攻击力 = 1.0 | `Player.java:229-233`：`createPlayerAttributes()` → `.add(Attributes.ATTACK_DAMAGE, 1.0).add(Attributes.MOVEMENT_SPEED, 0.1F)` |
| 3.2 | **冲刺加速是 `LivingEntity` 自带的**（不是玩家专属）：`setSprinting(true)` 往 `MOVEMENT_SPEED` 挂一个瞬态 `AttributeModifier`（`SPEED_MODIFIER_SPRINTING`），`false` 时摘除 | `LivingEntity.java`：`private static final AttributeModifier SPEED_MODIFIER_SPRINTING`；`public void setSprinting(boolean)` 内 `removeModifier` + `addTransientModifier` |
| 3.3 | ⇒ **"奔跑与玩家一致"可以用同一个机制实现**，不必抄数值：生物 `setSprinting(true)` 即获得与玩家同源的加成；将来原版改倍率我们自动跟上 | 3.2 |
| 3.4 | 寻路的 `speedModifier` **直接乘在移速属性上** | `MoveControl.java`：`float f1 = (float)this.speedModifier * f; this.mob.setSpeed(f1);`（`f = getAttributeValue(MOVEMENT_SPEED)`）；`PathNavigation.java`：`this.speedModifier = speed` → `moveControl.setWantedPosition(..., this.speedModifier)` |
| 3.5 | ⇒ `MOVEMENT_SPEED = 0.1` 且 `walkTo` 用 `speedModifier = 1.0` ⇒ **正好是玩家走路速度** | 3.1 + 3.4 |
| 3.6 | **`MeleeAttackGoal` 占用 `Goal.Flag.MOVE` 与 `Goal.Flag.LOOK`** | `MeleeAttackGoal.java`：构造器内 `this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK))` |
| 3.7 | **`GoalSelector` 以 Flag 做仲裁**：`lockedFlags` 记录"每个 Flag 正被哪个 goal 占用"，低优先级 goal 只要与正在运行的高优先级 goal 有 Flag 交集就**无法启动** | `GoalSelector.java`：`private final Map<Goal.Flag, WrappedGoal> lockedFlags`、`goalContainsAnyFlags(...)`、`goalCanBeReplacedForAllFlags(...)` |
| 3.8 | ⇒ 攻击 goal（占 LOOK）**必须比 `LookAtPlayerGoal`（也占 LOOK）优先级更高**，否则"玩家在 8 格内"时永远打不到人 | 3.6 + 3.7 |
| 3.9 | ⇒ 自定义的 `FacePlayerGoal` / `TurnGoal` **不能占用任何 Flag**：占 LOOK 会被优先级更高的 look goal 挡死（身体永远不转）；占 MOVE 会与寻路抢 | 3.7 |
| 3.10 | 伤害来源：`Mob#doHurtTarget` 首行即 `float f = (float)this.getAttributeValue(Attributes.ATTACK_DAMAGE);`，`MeleeAttackGoal` 调它 | `Mob.java`；`MeleeAttackGoal.java`：`this.mob.doHurtTarget(target)` |
| 3.11 | `MoveControl` 的 `speedModifier` 初值是 `0.25` ⇒ **必须显式给 1.0**，否则只有 1/4 速度 | `MoveControl.java`：`this.speedModifier = 0.25;` |

---

## 四、架构

### 4.1 类图

```
PathfinderMob
   └ NpcEntity（abstract, implements GeoEntity）   ← 本模组通用 NPC
        └ DihuangLoongEntity                       ← 只剩属性与类型绑定

entity/ai/
   NpcFacePlayerGoal   extends Goal              ← 绑 NpcEntity
   NpcTurnGoal         extends Goal              ← 绑 NpcEntity
   NpcAttackGoal       extends MeleeAttackGoal   ← 绑 NpcEntity，以命令标志位把关
```

### 4.2 通用 vs 具体

| 项 | `NpcEntity`（通用） | `DihuangLoongEntity` |
|---|---|---|
| 无敌 / 不可推动 / 不消失 | ✅ 三个覆写 | — |
| 基础 AI（五个 goal） | ✅ `registerGoals()` | — |
| `walkTo` / `runTo` / `turnTo` / `attack` / `stopAction` + 命令状态 | ✅ | — |
| `GeoEntity` + 实例缓存 + `idle`↔`walk` 状态机 | ✅ | — |
| 属性**默认值** | `createNpcAttributes()`：1000 / 1.0 / 100 / 0.1 | 用默认值 + `createAttributes()` 转发 |
| 动画名 / 距离 / 转速 / 过渡 / 走跑档位 | `protected` 默认实现，可覆写（D35） | 用默认值（资产里正好叫 `idle`/`walk`） |
| 实体类型绑定、碰撞箱、渲染器、模型、Molang | — | ✅ |

### 4.3 goal 集合（含 Flag 冲突分析）

| 优先级 | Goal | 占用 Flag | 说明 |
|---|---|---|---|
| 0 | `FloatGoal` | JUMP | 落水/岩浆上浮 |
| **3** | `NpcAttackGoal`（`extends MeleeAttackGoal`） | MOVE + **LOOK** | **必须早于 `LookAtPlayerGoal`**（3.6~3.8） |
| 5 | `LookAtPlayerGoal(this, Player.class, facePlayerDistance(), 1.0F)` | LOOK | 头颈 Molang 的**唯一数据源**；`probability = 1.0`（默认 0.02 会平均 2.5 秒才看你一眼） |
| 10 | `NpcFacePlayerGoal` | **无** | 身体转向玩家；被外部指令让位 |
| 20 | `NpcTurnGoal` | **无** | 转圈能力，默认不触发 |

### 4.4 可覆写项（D35）

| 方法 | 默认值 | 用途 |
|---|---|---|
| `idleAnimationName()` | `"idle"` | 待机动画名 |
| `walkAnimationName()` | `"walk"` | 移动动画名 |
| `animationTransitionTicks()` | `5` | `idle`↔`walk` 过渡 |
| `facePlayerDistance()` | `8.0F` | 面朝/看向玩家的距离 |
| `maxTurnPerTick()` | `10.0F` | 转身角速度上限（度/tick） |
| `walkSpeedModifier()` | `1.0` | 走路档位（× 移速属性） |
| `runSprinting()` | `true` | 奔跑是否走原版冲刺机制 |

### 4.5 API 集合（全部**只在服务端生效**、**默认不触发**）

| API | 行为 |
|---|---|
| `walkTo(Vec3)` | `navigation.moveTo(pos, walkSpeedModifier())` ⇒ 0.1 = 玩家走路速度 |
| `runTo(Vec3)` | `setSprinting(true)` + 寻路 ⇒ 用**原版冲刺修饰符**，与玩家同源（3.2/3.3） |
| `turnTo(float yaw)` | 停寻路 + 设目标 yaw |
| `attack(@Nullable LivingEntity)` | 置命令标志位 + `setTarget(...)`；传 `null` 取消 |
| `stopAction()` | 以上全清 + `setSprinting(false)` |

内部：`isExternallyCommanded()` = `turnTargetYaw != null || attackCommandActive || !getNavigation().isDone()`，
供 `NpcFacePlayerGoal.canUse()` 让位（D23 的扩展）。

### 4.6 命令语法

```
/beloong npc turn   <targets> <yaw>
/beloong npc walk   <targets> <pos> [speed]
/beloong npc run    <targets> <pos>
/beloong npc attack <targets> <victim>
/beloong npc stop   <targets>
```
op 级（`hasPermission(2)`）；目标过滤 `NpcEntity`；命中为空则 `sendFailure`。

---

## 五、决策记录（接续首版 D1–D15/R7–R10 与上一版 D16–D31）

> D16–D31 中，**行为规格部分继续有效**（goal 语义、Molang 相对角、动画判据、无网络包、命令定位），
> **"写在哪个类里"的部分被 D32 取代**。

| # | 决策 | 取值 | 理由 |
|---|---|---|---|
| **D32** | 新增抽象基类 | `entity/NpcEntity.java`；三个 goal 移入 `entity/ai/` 并改名为 `Npc*` | 需求 1；goal 只依赖"是个 Mob"，绑具体子类是放错位置 |
| **D33** | 基类实现 `GeoEntity` | 是（实例缓存 + `registerControllers` + `idle`↔`walk`） | 本模组 NPC 都会用 GeckoLib；每个子类重写一遍控制器是纯重复 |
| **D34** | `RawAnimation` 构建时机 | **在 `registerControllers()` 里构建，不在构造函数里** | 动画名来自**可覆写**方法（D35）。构造函数调虚方法会踩"子类字段未初始化"的坑；而 `registerControllers` 由 `AnimatableManager` **惰性**调用，那时子类早已构造完 |
| **D35** | 可覆写项 | §4.4 七项，**全部给默认值** | 用户裁定"约定 + 可覆写" |
| **D36** | 定义性语义 | 无敌/不可推动/不消失**直接写在基类，不加布尔开关** | 这就是"NPC"的定义；要例外的子类自己覆写方法即可，不为假想的例外预先加一层间接 |
| **D37** | 属性 | 基类 `public static AttributeSupplier.Builder createNpcAttributes()`：1000 / 1.0 / 100 / 0.1 | 需求 4；`ModAttributes` 的注册结构不变 |
| **D38** | 命令过滤 | `NpcEntity`；失败文案改通用 | 命令是 NPC 框架的工具，不该只服务一个 NPC |
| **D39** | 基类不注册实体类型 | `NpcEntity` 是抽象类，无自己的 `EntityType` | 注册仍集中在 `ModEntities` |
| **D40** | 不抽模型侧基类 | Molang 头部跟随留在各自模型类 | 用户裁定 B |
| **D41** | 攻击不做自主索敌 | 只给 `ATTACK_DAMAGE` 与 `attack()` API；**不加** `NearestAttackableTargetGoal` / `HurtByTargetGoal` | 用户裁定 B：能力只作 API。它不会主动打人，也不会被打就还手 |
| **D42** | 攻击 goal 的把关 | `NpcAttackGoal extends MeleeAttackGoal`，`canUse()`/`canContinueToUse()` 追加 `isAttackCommandActive()`；`stop()` 清标志位 | 否则任何来源设了 `setTarget` 都会触发攻击，就不是"只作 API"了 |
| **D43** | 攻击 goal 优先级 = **3** | 高于 `LookAtPlayerGoal` 的 5 | 3.6~3.8：`MeleeAttackGoal` 占 LOOK，优先级更低会被 look goal 永久挡死 |
| **D44** | 伤害来源 | `Mob#doHurtTarget` 读 `ATTACK_DAMAGE` ⇒ 一下 100（3.10） | — |
| **D45** | 本轮不做攻击动画 | 只 `idle`↔`walk`（用户先前裁定） | **已知观感缺口**：它会"滑"过去打人而没有挥击动作。资产里有 `bite`（46 骨骼 / 0.5s）备着 |
| **D46** | 冲刺必须复位 | 覆写 `customServerAiStep()`：`if (isSprinting() && getNavigation().isDone()) setSprinting(false);` | 原版冲刺修饰符**挂上就不会自己摘**（3.2）⇒ 不复位会永久快一档并带冲刺粒子 |
| **D47** | 走路档位显式给 1.0 | `walkTo` 传 `speedModifier = 1.0` | 3.11：`MoveControl` 初值是 **0.25**，不显式给就只有 1/4 速度 |

---

## 六、组件

| 文件 | 动作 | 职责 |
|---|---|---|
| `entity/NpcEntity.java` | **新增** | 抽象基类：三个语义覆写、`registerGoals`、五个 API、`isExternallyCommanded`、`customServerAiStep`、`GeoEntity` + 动画状态机、`createNpcAttributes`、七项可覆写默认值 |
| `entity/ai/NpcFacePlayerGoal.java` | 新增 | 玩家 8 格内时把 `yRot` 转过去；被外部指令让位 |
| `entity/ai/NpcTurnGoal.java` | 新增 | 把 `yRot` 平滑转到 `turnTargetYaw`，到位即清空 |
| `entity/ai/NpcAttackGoal.java` | 新增 | `extends MeleeAttackGoal` + 命令标志位把关 |
| `entity/DihuangLoongEntity.java` | **改（缩到 ~30 行）** | `extends NpcEntity`；构造器、`createAttributes()`、类注释 |
| `client/model/DihuangLoongModel.java` | 改 | 覆写 `applyMolangQueries`（D40：不进基类） |
| `command/NpcCommand.java` | 新增 | 五条子命令，过滤 `NpcEntity` |
| `BeLoongCore.java` | 改 | +1 个 `RegisterCommandsEvent` 订阅 |
| `assets/beloong/lang/{zh_cn,en_us}.json` | 改 | 命令反馈键（字母序插入） |
| `client/DihuangLoongRenderer.java` | 可能改 | **仅** R9（陷地偏移）需要时 |

**不改**：`ModEntities`、`ModAttributes`（仍按 EntityType 注册，supplier 改为调基类工厂）、对话系统、
`beloong.mixins.json`、资产、网络包注册。

---

## 七、数据流与线程

### 7.1 服务端每 tick

```
LivingEntity.tick()  → tickHeadTurn(getYRot(), …)      yBodyRot 以 0.3 插值追 yRot
LivingEntity.aiStep()
 ├ travel()                                            重力/位移（D16 恢复）
 └ serverAiStep()
     ├ goalSelector.tick()   FloatGoal(0) → NpcAttackGoal(3) → LookAtPlayerGoal(5)
     │                       → NpcFacePlayerGoal(10) → NpcTurnGoal(20)
     │                       （Flag 仲裁：攻击 goal 运行时会顶掉 look goal）
     ├ moveControl.tick() / lookControl.tick()
     ├ navigation.tick()
     └ customServerAiStep()  ← 基类覆写：寻路结束后摘冲刺（D46）
```

### 7.2 客户端每渲染帧

与上一版设计相同：`applyMolangQueries` 注册 `query.head_yaw`（**相对角**）/`query.head_pitch`（度），
predicate 按 `state.isMoving()` 在 `idle`/`walk` 间切换。**注意**：`isMoving()` 要求横向速度 ≥ 0.015/tick
且 `walkAnimation.speed() ≠ 0`；走路档位是 0.1（玩家速度），比多数原版怪慢，若实测读不到移动，
在 renderer 覆写 `getMotionAnimThreshold` 调低（上一版设计 R11）。

### 7.3 外部驱动

```
walkTo  → navigation.moveTo(pos, 1.0)                        ⇒ 0.1（玩家走路）
runTo   → setSprinting(true) + navigation.moveTo(pos, 1.0)   ⇒ 原版冲刺修饰符自动加成
attack  → attackCommandActive = true + setTarget(victim)
          → NpcAttackGoal(3) 抢下 MOVE+LOOK → 走进距离 → Mob#doHurtTarget（读 ATTACK_DAMAGE=100）
stopAction → 全清 + setSprinting(false)
```

---

## 八、错误处理与边界

| 情形 | 行为 |
|---|---|
| 玩家离开 8 格 / 死亡 / 换维度 / 旁观 | `canUse()` 自然 false；朝向**停在最后值**（不做回正） |
| 玩家靠近 **且** 有外部指令 | 显式指令优先，`NpcFacePlayerGoal` 整条让位 |
| `walkTo`/`runTo`/`attack` 目标不可达 | 寻路自己失败并 `isDone()`；**不重试、不报错**，外部驱动方负责 |
| `attack` 的目标是创造/旁观玩家 | `MeleeAttackGoal.canUse()` 自行排除（原版行为）——命令报成功但不会追 |
| `attack` 目标被一下打死 | 正常；**不会自动换目标** |
| 冲刺残留 | D46 复位 ⇒ 每次寻路结束摘掉 |
| 实体被卸载后重载 | 转向/攻击/冲刺都是运行期状态、**不写 NBT** ⇒ 回到站桩 |
| `/kill` / 掉虚空 | 仍可移除（`bypasses_invulnerability` / `out_of_world`）|
| 命令选到的不是 NPC | `sendFailure`，不静默 |
| 专服 | 基类与三个 goal 都不含客户端类；Molang 覆写在 `client/model/` 下 ⇒ 专服不加载 |

---

## 九、测试策略

### 一级（静态）

1. `gradlew build` 通过。
2. `DihuangLoongEntity` 里**没有** `isNoAi` / `setNoAi` / `registerGoals` / `isInvulnerableTo` / `isPushable` / `isPersistenceRequired`（全部上移到基类）。
3. `NpcEntity` 里**有**上述各项。
4. 三个 goal 构造参数的类型是 `NpcEntity`；`NpcAttackGoal` 的注册优先级常量 = 3。
5. `DihuangLoongModel` 覆写 `applyMolangQueries` 并注册两个查询。
6. `beloong.mixins.json`、`ModEntities`、`build.gradle` 未改。

### 二级（实机）

沿用上一版设计 §八 的清单（1–11、16、17 项），并把能力相关项扩为：

| # | 步骤 | 预期 | 验证 |
|---|---|---|---|
| 12 | `/beloong npc walk …` | 以**玩家走路速度**过去；播 `walk` | 3.4/3.5/D47 |
| 13 | `/beloong npc run …` | **明显更快**；到达后**恢复常速、冲刺粒子消失** | 3.2/D46 |
| 14 | `/beloong npc turn … 90` | 平滑转到 90° | D21 |
| 15 | `/beloong npc attack … <一头牛>` | 走过去、**一下 100 伤害** | D42/D43/D44 |
| 16 | `attack` 后 `stop` | 立刻放弃追击 | API |
| 17 | **攻击时玩家站在旁边** | **仍然打得到**（不是被 look goal 钉在原地） | **D43 的核心验证点** |
| 18 | 攻击全程 | 无挥击动画（已知缺口 D45） | 记录观感，不阻塞验收 |

---

## 十、风险

| # | 风险 | 评估与对策 |
|---|---|---|
| **R9** | 模型最低点在 y ≈ −0.88 格，**恢复重力后会真的落地并可能陷地** | 中。先原样接，实机量偏移后在 `DihuangLoongRenderer#preRender` 补平移（不动模型、不动碰撞箱） |
| **R10** | `query.head_yaw` 符号可能反 | 低且可见。留 `HEAD_YAW_SIGN` 常量翻一下 |
| **R11** | 移速 0.1（玩家速度）比多数原版怪慢，`isMoving()` 阈值可能读不到 ⇒ 走了还播 `idle` | 中。对策：renderer 覆写 `getMotionAnimThreshold` 调低 |
| **R12** | 转身速度、过渡时长、跟随距离都是起始值 | 低。全部集中且可覆写 |
| **R13** | 100 点攻击力一下能打死绝大多数生物 | 低（用户指定）。但**没有攻击动画**放大了突兀感（D45） |
| **R14** | 攻击 goal 优先级 3 高于 look goal ⇒ 攻击期间头不再跟随玩家 | 低。这是**正确**的取舍：攻击时不该还在闲看 |
| **R15** | 命令是本轮唯一新增的长期表面 | 低。op-only、Javadoc 写明定位；不要了删一个类 + 一行注册 |
| **R16** | 开 AI 后其它模组的索敌可能开始作用于 NPC | 低：它无敌、不可推动、`MobCategory.MISC` |

---

## 十一、后续（不在本次范围）

1. 攻击动画（资产有 `bite`）；受击/交互反应（`triggerableAnim` + 服务端 `triggerAnim` 会自动同步）。
2. 自主游走/巡视：在基类加一个默认关闭的 `NpcStrollGoal`。
3. `run` 动画档位（资产里 `run` 缺 3 根骨骼 `Drip1-3`）。
4. 模型侧基类（Molang 跟随抽公共）—— 本轮按用户裁定 B 不做，等第二个 NPC 出现时再评估。
5. 数据驱动的 NPC 参数（动画名、属性、行为开关进 JSON）。

---

## 十二、来源与收尾产物

| 类别 | 出处 |
|---|---|
| 行为规格与 GeckoLib/资产事实 | `docs/plans/2026-09-25-dihuang-loong-ai-design.md` §三（继续有效） |
| 上一版（被取代的组件放置） | 同文档 §四/§五 |
| 原版 | NFRT 合并源 `sourcesAndCompiledWithNeoForge_*.jar`（§三 逐条行号） |
| GeckoLib 4.9.2 | `D:\Minecraft\开源模组参考文件\Geckolib` |

**收尾产物**

- 本文档（新建）；上一版设计文档顶部加"§四/§五 已被取代"标记；上一版实施计划标注作废
- 新实施计划 `docs/plans/2026-09-25-npc-base-class-plan.md`
- `memory/project-context.md`：新增"通用 NPC 基类"子系统；地黄龙条目改为"继承自它"
- `memory/decisions-log.md`：追加 **"自定义 goal 的可用性会被原版 goal 的 Flag 占用悄悄决定"**
  （两次踩到：`FacePlayerGoal` 不能占 LOOK；攻击 goal 必须比 look goal 优先级高），
  以及 **"挂上就不会自己摘的状态要显式复位"**（冲刺修饰符）
- 提交：`docs(entity): …` + `feat(entity): …`，不 push
