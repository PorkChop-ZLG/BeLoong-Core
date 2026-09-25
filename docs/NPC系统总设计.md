# NPC 系统总设计

> 状态：**现行实现**（基于 `5cf140e`「撤销 RandomLookAroundGoal —— 回到默认不转动」。
> 含通用 NPC 基类、地黄龙 NPC、服务端权威的简易 NPC 对话系统、NPC 调试命令）
> 覆盖范围：`NpcEntity` 通用基类、`DihuangLoongEntity` 与它的资产/渲染、`dialogue` 包与
> `NpcDialogueScreen`、`/beloong npc` 调试命令、`[npc_dialogue]` 配置
> 本文取代此前所有分散的 NPC 相关设计/计划文档（见文末「历史文档与处置状态」）
>
> **三条总目的**（§1.1）：
> ① NPC 尽量**用原版生物的机制**——移动刻度、身朝、持久性全部交回原版，只有原版确实没有的才自研；
> ② 默认**站桩**——不自主游走、不自主转身；走路与攻击只作为**能力**，由外部驱动；
> ③ 对话数据**服务端权威**——放在 `data/` 树，由服务端加载与判定，随数据包可覆写。
>
> **实机验收**：2026-09-25 全部通过。含移速（略慢于走路玩家）、walk/run 动画、站桩不自主转身、
> 玩家靠近时"先扭头、后转身"、地黄龙无陷地/悬空偏移。
>
> **本文以源码为准**：与任何历史文档冲突时，一律以本文件 + 当前源码为准。
> 每条结论都可回溯到类注释或原版源码行号——本项目的约定是"结论要带证据"。

---

## 一、设计目标

NPC 系统是「化龍」整合包的**演出与交互载体**：整合包需要一个能摆在场景里、会被玩家撞见、
能说话、能在脚本/命令驱动下走位与出手的角色，而不是又一类会自己乱跑的战斗怪物。

### 1.1 三条总目的

本文其余部分全部由下面三条总目的推导而来。任何与之冲突的既有设计都应视为需要修正。

**目的 1 —— 尽量用原版生物的机制。**

"又写了一套 AI" 是这个系统最容易犯的错。原版的 `GoalSelector`、`BodyRotationControl`、
`PathNavigation`、`LookControl` 已经处理好了转向平滑、卡位检测、动画速度等大量细节；
自研的替代品只会更差。因此**每一项机制都先问"原版有没有"**，只有三类东西原版确实没有，
才允许自研（§1.3）。

这条目的的直接产物是 §3.4 的"身朝"一节：
「玩家靠近时面朝玩家」以及玩家最直观感受到的"先扭头、后转身"**不是我们写的**，
而是 `LookAtPlayerGoal` + 原版 `BodyRotationControl` 的默认结果 —— 前提是**不插手**。

**目的 2 —— 默认站桩。**

整合包里的 NPC 是布景与剧情的载体，不是野生动物。默认状态下它应当：
不游走、不索敌、不自主转身，只站在原地做待机动画。走路（`walkTo`）与攻击（`attack`）
是**能力**，默认**没有任何调用方**。

反面教训：本系统一度注册了原版被动生物的标准项 `RandomLookAroundGoal`。它表面上"只动头、
不产生位移"，但站桩时原版 `BodyRotationControl` 会把**身体拖向头**（§3.4）——
净效果是 NPC 自主间歇性转身，与目的 2 直接冲突。实机验证后由用户裁定移除。

**目的 3 —— 对话数据服务端权威。**

对话内容必须能被数据包覆写（整合包作者改剧情不该改代码），而"该让玩家看到哪一条"
（将来按原版进度判据筛选）只能由服务端决定。因此数据放在 `data/` 树、由服务端加载、
在服务端受理右键、按需把**那一条**下发给该玩家。客户端不持有全表。

> **首版是纯客户端的**（数据在 `assets/`、判定在客户端、零网络包），当时的取舍前提是
> "v1 的对话没有任何副作用"。引入"按进度筛选内容"的需求后这个前提被推翻，
> 于是整体反转。详见 §5 与决策记录。

### 1.2 职责边界

| 事项 | 归属 |
|---|---|
| NPC 实体的 AI、语义、动画状态机 | **本模组**（`NpcEntity`） |
| NPC 的资产、模型、贴图、渲染 | **本模组**（子类 + `client/`） |
| 对话数据的**加载、判定、下发** | **本模组**（服务端） |
| 对话界面的**渲染与输入** | **本模组**（`NpcDialogueScreen`） |
| 对话**文案** | 整合包 / 数据包（`lang` 与 `npc_dialogue/*.json`） |
| 剧情分支、任务状态机、好感度 | **不由本模组负责**（见 §10.3） |
| NPC 的"什么时候走到哪、什么时候说什么" | 外部（命令、将来的脚本/对话条件） |

### 1.3 明确不做的三件事，以及必须自研的三件事

**不自研**（原版已有，且在 §3 中逐条给出原版出处）：

- 移动刻度与卡位检测 → `PathNavigation` / `Mob#setSpeed`；
- 头身转向的分离与收敛 → `LookControl` + `BodyRotationControl`；
- 持久性（不消失）→ `Mob#requiresCustomPersistence()` 官方钩子；
- 目标选择与 Flag 仲裁 → `GoalSelector`；
- 漂浮/避水 → `FloatGoal`。

**必须自研的三件事**（原版确实没有）：

1. **绝对无敌** —— 原版 `Entity#isInvulnerableTo` 在无敌判断里**显式放行创造模式玩家**
   （`Entity.java:2681-2686` 的 `&& !source.isCreativePlayer()`），即"创造玩家能打无敌实体"
   是**有意设计**；而本 NPC 要的是连创造也打不动。
2. **命令式 API** —— 原版没有"默认关闭、由外部下令才启动"的移动/攻击接口。
   `MeleeAttackGoal` 直注册的话，任何来源给实体设了 `target` 就会开打。
3. **GeckoLib 离散动画** —— 原版 `AnimationState` 是程序化摆动的，无法播基岩版模型动画。

---

## 二、系统构成

### 2.1 三块

| 块 | 内容 | 端 |
|---|---|---|
| **通用 NPC 基类** | `NpcEntity`（抽象）：AI、语义、动画、外部 API | 双端 |
| **具体 NPC** | `DihuangLoongEntity` + 资产 + 渲染器 | 双端 |
| **对话系统** | `dialogue/` 包（服务端）+ `NpcDialogueScreen`（客户端） | 服务端权威 |
| **调试命令** | `/beloong npc walk\|attack\|stop` | 服务端 |

### 2.2 双端职责表

| 环节 | 服务端 | 客户端 |
|---|---|---|
| 对话数据加载 | ✅ `AddReloadListenerEvent` | ❌ 不加载 |
| 右键受理与触发判定 | ✅ `NpcDialogueHandler` | ❌ 只发原版交互包 |
| 对话内容获取 | ✅ 查表 | ❌ 等包 |
| 界面渲染 / 输入 | ❌ | ✅ `NpcDialogueScreen` |
| 动画状态机 | 驱动属性/移动 | ✅ GeckoLib 渲染期求值 |
| 身朝（`yBodyRot`） | 算，但**不下发** | 自己用同一套原版逻辑算（§3.4） |
| 外部 API（`walkTo`/`attack`） | ✅ 只在服务端生效 | ❌ 直接 return |

### 2.3 包结构

```
com.zonlong.beloong
├── entity
│   ├── NpcEntity.java            通用 NPC 基类（抽象）
│   ├── DihuangLoongEntity.java   地黄龙
│   └── ai/NpcAttackGoal.java     命令式攻击 goal
├── dialogue
│   ├── NpcDialogueEntry.java     数据定义 + Codec
│   ├── NpcDialogueLoader.java    服务端加载器（SimpleJsonResourceReloadListener）
│   ├── NpcDialogueHandler.java   服务端右键受理
│   └── NpcDialogueOpenPayload.java  服务端 → 玩家 的下发包
├── client
│   ├── DihuangLoongRenderer.java
│   ├── model/DihuangLoongModel.java
│   ├── NpcDialogueScreen.java    对话界面（状态机）
│   └── NpcDialogueOptionButton.java
├── command/NpcCommand.java
└── registry/{ModEntities, ModAttributes}
```

---

## 三、通用 NPC 基类 `NpcEntity`

`src/main/java/com/zonlong/beloong/entity/NpcEntity.java`，373 行，
`public abstract class NpcEntity extends PathfinderMob implements GeoEntity`。

### 3.1 定位

整个类只回答一个问题：**"一个整合包用的站桩 NPC 应该具备哪些定义性特征、哪些可覆写的默认值？"**
子类只回答"**我是什么**"：实体类型绑定、碰撞箱、渲染器与模型、属性起点。

选 `PathfinderMob`（而非 `Mob`）是因为要白拿原版寻路——`walkTo` 直接委托 `getNavigation()`，
不需要自研移动。

### 3.2 定义性语义

三条，全部写成**覆写方法**而不是构造函数里的 `setXxx`：

| 语义 | 实现 | 原版依据 / 先例 |
|---|---|---|
| **无敌** | `isInvulnerableTo(source)` 只放行 `BYPASSES_INVULNERABILITY` | `LivingEntity#hurt` 的第一道闸门（`LivingEntity.java:1084`） |
| **不可推动** | `isPushable()` 恒 `false` | `Warden:555`、`Bat:85`、`Parrot:392` |
| **永不消失** | `requiresCustomPersistence()` 恒 `true` | `Mob.java:736`，先例 `AbstractFish:45`、`Axolotl:423`、`Raider:248`、`EnderMan:434` |

**为什么必须是覆写方法**：`/summon` 与刷怪蛋的流程是"先 `create()`（构造函数在此运行）
再 `load(标签)`"，而 `load()` 会把 `Invulnerable`（`Entity.java:1759`）、
`PersistenceRequired`（`Mob.java:437`）从标签读回，**标签里没有对应键时就覆盖成 false**。
首版地黄龙因此出现过"召唤出来能被打"的 bug。

构造函数里仍然 `setInvulnerable(true)` + `setPersistenceRequired()`，
但那只是让**字段本身**与保存出的 NBT 一致；语义由覆写保证。

**放行 `BYPASSES_INVULNERABILITY` 是刻意的**：该标签只含 `minecraft:out_of_world` 与
`minecraft:generic_kill`，即 `/kill` 与虚空仍然能移除它——留一条管理后路，
否则一个摆错位置的 NPC 将永久占据区块。

### 3.3 基础 AI：三个 goal，全是原版

```java
registerGoals() {
    addGoal(0, new FloatGoal(this));                                  // 原版
    addGoal(3, new NpcAttackGoal(this, 1.0D, true));                  // 唯一自研，且被命令把关
    addGoal(5, new LookAtPlayerGoal(this, Player.class, 8.0F, 1.0F)); // 原版
}
```

**优先级 3 < 5 不是随意排的**：`GoalSelector` 用 `lockedFlags` 仲裁——低优先级 goal
只要与正在运行的高优先级 goal 有 `Flag` 交集就**无法启动**。
`NpcAttackGoal`（`MeleeAttackGoal` 子类）构造器里
`setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK))`，而 `LookAtPlayerGoal` 占 `LOOK`。
若攻击 goal 优先级更低，"玩家在跟随距离内"时它会被 look goal **永久挡死** ⇒
永远打不到人，且不报任何错。

**`probability` 给 1.0**：原版默认 0.02 会让 NPC 平均 2.5 秒才看玩家一眼。
`lookTime` 40~80 tick 一轮、到期立刻重启 ⇒ 实际表现为持续跟随。

**刻意没有的两个 goal**：

- **没有 `RandomLookAroundGoal`**（原版被动生物的标准项，加过又移除 —— 见 §1.1 目的 2）；
- **没有"把身体转向玩家"的自研 goal** —— 原版 `BodyRotationControl` 已经免费提供了，
  再加一个反而会破坏它（见 §3.4 与决策记录中的两处教训）。

> **将来若要"站着四处张望"，先想清楚要不要连身体一起转。**
> 只转头而不动身体做不到，除非覆写 `createBodyControl()`（与目的 1 相悖）。

### 3.4 身朝：头带身体（本系统最容易踩的地方）

**现状：本类完全不碰身朝，一行相关代码都没有。** 「玩家靠近时面朝玩家」与
"先扭头、后转身"全部由原版机制产生。

完整链条：

1. **头** —— `LookAtPlayerGoal` → `LookControl`，以 `getHeadRotSpeed()`（`Mob` 默认 10°/tick）
   把头（`yHeadRot`）转向玩家。**站桩时头不受"不得偏离身体"的夹取**：
   `LookControl#clampHeadRotationToBody` 只在**有寻路时**生效，所以头能先转过去、
   领先身体最多 `getMaxHeadYRot()`（默认 75°）。
2. **身体** —— `Mob#tickHeadTurn`（`Mob.java:377-381`，覆写但**不调 super**）→
   `BodyRotationControl#clientTick()`：
   - 站桩：头相对"上次稳定位置"转过 >15° 就把身体夹到**离头不超过 75°** ⇒
     身体稳定地滞后头，这就是"头先转"；
   - 头停下约 11 tick 后，`rotateHeadTowardsFront` 的允许量**递减到 0**，
     身体被**逐步收到与头完全对齐** ⇒ "身体随后跟上"，最终整体面向玩家；
   - 移动：`yBodyRot = yRot`，贴行进方向。
3. **同步** —— `yBodyRot` **不参与网络同步**（只同步 `yRot`/`yHeadRot`），
   客户端的身朝是它自己用同一套 `BodyRotationControl` 算出来的。

> ⚠️ **绝对不要覆写 `tickHeadTurn`。** 本类干过，代价很大：
> 无论改成"身体以 0.3 插值追 `yRot`"还是"追 `yHeadRot`"，都会把上面那套 75° 滞后关系
> 一起废掉 —— 头与身体的相对角变得很小或恒为 0，而**头部 Molang（`query.head_yaw`）
> 吃的正是这个相对角**，于是症状是"不扭脖子、头身一体转"。
>
> 曾经还犯过第二个错：在基类注册里同时保留 `LookAtPlayerGoal` 和一个自研的转向 goal，
> 两者都以 10°/tick 把**头**转向同一个目标 ⇒ 相对角恒 ≈ 0 ⇒ 同样"没有头部动画"。
> 教训：**同一个自由度上不要放两个等速的驱动者。**

**如果将来确实需要定制身朝**，正统扩展点是覆写 `Mob#createBodyControl()`
返回 `BodyRotationControl` 的子类（原版自己在用：`Phantom:63`、`Armadillo:392`、
`Camel:635`、`Shulker:146`），而不是 `tickHeadTurn`。

### 3.5 移动速度：为什么是 0.3

`MOVEMENT_SPEED = 0.3` 是**原版刻度的值**，与原版生物可直接比较
（僵尸 0.23、铁傀儡 0.25、村民 0.5）。

它与玩家的关系**不是线性的**：

```
生物位移 ∝ (导航档位 × 属性)²        // Mob#setSpeed 会把速度值同时写进 zza（Mob.java:557-560）
玩家位移 ∝  属性 × 输入幅度(1.0)     // Player 不是 Mob 的子类，zza 来自键盘
⇒ 位移比 = (档位 × 属性)² / 0.1      // 0.1 = 玩家默认移速属性（Player.java:231）
⇒ 档位 1.0、属性 0.3 时 = 0.3² / 0.1 = 0.9   // 约为走路玩家的九成，略慢
```

**想要与玩家同速就把属性改成 `√0.1 ≈ 0.3162`；想更慢就继续减小。**
比值恒等于 `属性² / 0.1`，只需改这一个数。

原版自己也承认这个平方：`PathNavigation#doStuckDetection:312` 估算预期位移时
显式平方了速度。

`walkTo` 的档位固定给原版惯例的 `1.0`（= 该生物的基础速度）。原版 goal 也是这么用的：
`MeleeAttackGoal` 给 1.0、`FollowParentGoal`/`TemptGoal` 给 1.25、`PanicGoal` 给 2.0。

> **不需要"移速补偿""自定义加速度"之类的自研机制。** 首版曾把移速设成 0.1 并试图用
> `1/√attr` 之类的系数补偿，结果是走得像蜗牛；改回原版刻度后一切正常。

### 3.6 动画：idle / walk / run 三档

单个 `AnimationController`（名字 `"main"`，过渡 `animationTransitionTicks()` = 5 tick，
0 = 硬切）用谓词三选一：

| 条件 | 动画 |
|---|---|
| `!state.isMoving()` | `idle` |
| `getAttributeValue(MOVEMENT_SPEED) > getAttributeBaseValue(MOVEMENT_SPEED)` | `run` |
| 其余 | `walk` |

**「跑」不是一种状态，而是「有额外移速加成」的表现**（用户裁定）。
判据是**属性值 > 基础值**：

- 迅捷效果就是往这个属性挂修饰符（`MobEffect#addAttributeModifiers:166-174`）；
- 属性值会同步给客户端（`ClientboundUpdateAttributesPacket`）；
- ⇒ **效果一生效自动切 run、一结束自动回 walk**，不需要状态机、也不需要网络包。

口径是"**任何**额外移速加成"（迅捷 / 信标 / 食物 / 其它模组的 modifier 都算），不只迅捷。
本模组**不自己实现任何加速来源**。

**移动判据用 GeckoLib 的 `isMoving()`**（`AnimationState#isMoving`：横向速度 ≥ 0.015/tick
且 `walkAnimation.speed() != 0`，`GeoEntityRenderer.java:266-268`）。它要求实体**真的在位移**
——所以"原地转身/扭头"不算移动，仍播 idle。

`RawAnimation` **刻意在 `registerControllers` 里构建而不在构造函数里**：动画名来自**可覆写**
方法（`idleAnimationName()` 等），构造函数里调虚方法会踩"子类字段尚未初始化"的经典坑。
而 `registerControllers` 由 `AnimatableManager` **惰性**调用，那时子类早已构造完。

### 3.7 外部驱动 API

```java
public void walkTo(Vec3 pos)          // 走到目标点（导航档位 1.0）
public void attack(@Nullable LivingEntity target)  // 下令攻击；null = 取消
public void stopAction()              // 取消全部指令，回到站桩
public boolean isAttackCommandActive()
public void clearAttackCommand()
```

三个公开方法**都只在服务端生效**（`level().isClientSide()` 时直接 return）。
`attack()` 会把 `attackCommandActive` 置位并 `setTarget()`；`walkTo()` 会清掉攻击指令。

**攻击指令的失效兜底**在 `customServerAiStep()`：

目标死亡、或目标变成创造/旁观玩家时 `MeleeAttackGoal.canUse()` 返回 false，
但若该 goal **从未启动过**，它的 `stop()` 就不会被调用 ⇒ `attackCommandActive` 与
`getTarget()` 会一直挂着（实体永久保留一个已死的目标，攻击 goal 还会每 20 tick 白轮询一次）。
`customServerAiStep` 用与 `canUse()` 一致的排除条件（`EntitySelector.NO_CREATIVE_OR_SPECTATOR`）
把这种残留清掉。

> `NpcAttackGoal` 的 `stop()` 里也会调 `clearAttackCommand()`。
> 两处并存不是冗余：`stop()` 只在"启动过"时被调用，`customServerAiStep` 兜的是"从未启动"。

### 3.8 可覆写的默认值

| 方法 | 默认 | 说明 |
|---|---|---|
| `idleAnimationName()` | `"idle"` | 子类资产里叫别的名字就覆写 |
| `walkAnimationName()` | `"walk"` | |
| `runAnimationName()` | `"run"` | 只在有额外移速加成时移动才播 |
| `animationTransitionTicks()` | `5` | 0 = 硬切 |
| `facePlayerDistance()` | `8.0F` | `LookAtPlayerGoal` 的跟随距离；`protected` |
| `createNpcAttributes()` | 见 §3.9 | `static`，子类用它作起点追加/覆盖 |

> `facePlayerDistance()` 曾经是 `public`（因为当时有一个在 `entity.ai` 包的自研 goal 要读它）。
> 那个 goal 已删除，现在只有本类自己用，因此收回 `protected` —— **对外接口越小越好**。

### 3.9 属性表

```java
PathfinderMob.createMobAttributes()
    .add(MAX_HEALTH,           1000.0D)   // 演出用，不该被随手打死
    .add(KNOCKBACK_RESISTANCE,    1.0D)   // 与 isPushable()=false 配套
    .add(ATTACK_DAMAGE,         100.0D)
    .add(MOVEMENT_SPEED,          0.3D)   // §3.5
```

**`ATTACK_DAMAGE` 必须显式 `add`**：`createMobAttributes()` 只含
MAX_HEALTH / KNOCKBACK_RESISTANCE / MOVEMENT_SPEED / ARMOR / ARMOR_TOUGHNESS，
没有攻击力（原版要 `Monster.createMonsterAttributes()` 才加）。
伤害由 `Mob#doHurtTarget` 读 `ATTACK_DAMAGE` 得出。

**全部可覆写**：子类在自己的 `createAttributes()` 里以 `createNpcAttributes()` 为起点
改任意一项即可。

---

## 四、地黄龙 NPC

`DihuangLoongEntity` 目前**不到 50 行**：一个构造函数 + 一个 `createAttributes()`（直接转发基类）。
这就是 §3.1 想要的形态——"子类只回答我是什么"。

### 4.1 资产

| 文件 | 大小 | 内容 |
|---|---|---|
| `assets/beloong/geo/dihuang_loong.geo.json` | 196 KB | 145 骨骼、340 立方体；两个根骨骼 `Magic` / `Dragon`；`identifier` 为 `geometry.unknown` |
| `assets/beloong/animations/dihuang_loong.animation.json` | 477 KB | 96 个动画 |
| `assets/beloong/textures/entity/dihuang_loong.png` | 153 KB | 256×256 |

**`idle`（32 骨骼 / 4.75s）与 `walk`（63 骨骼 / 1.375s）骨骼覆盖 0 缺失。**
`run`（77 骨骼 / 1s）引用了 3 根本模型没有的骨骼（`Drip1-3`）——GeckoLib 对缺失骨骼是
**优雅忽略**，只是观感略有缺失、不会崩。

### 4.2 渲染与头部跟随

`DihuangLoongRenderer extends GeoEntityRenderer`，**最小实现**：不缩放、不偏移。

`DihuangLoongModel` 做三件事：

1. 声明三条资源路径（geo / animation / texture）；
2. 提取模型自身的 `renderLayers` 与 root 骨骼；
3. 在 `applyMolangQueries` 里注册两个全局 Molang 变量供动画资产使用。

| Molang 变量 | 值 | 说明 |
|---|---|---|
| `query.head_yaw` | `wrapDegrees(rotLerp(yHeadRot) - rotLerp(yBodyRot)) * (-1)` | **相对角**（头 − 身），不是绝对角 |
| `query.head_pitch` | `lerp(xRot)` | 俯仰，绝对角 |

**为什么是相对角**：模型里头的骨骼挂在身体骨骼之下，父骨骼已经把身体朝向带进去了，
子骨骼只需要再转"头相对身体的差"。

**`HEAD_YAW_SIGN = -1.0F`**：Molang 的旋转正方向与 yaw 的增减方向相反，
不加这个符号会得到**左右反了**的头（上下正常，因为 pitch 的符号恰好一致）。
这是实机发现的 bug，修复方式就是这一个常量。

**为什么用 `applyMolangQueries` 而不是在渲染器里手动改骨骼**：这样动画资产自己决定
哪根骨骼吃哪个变量，本模组不需要知道模型内部的骨骼命名与层级。

> 关于 §3.4：`query.head_yaw` 吃的是 `yHeadRot - yBodyRot`，
> **这就是"绝不能覆写 `tickHeadTurn`"的最终原因**——那个相对角是头部动画的唯一输入。

### 4.3 注册点

| 位置 | 内容 |
|---|---|
| `registry/ModEntities` | `beloong:dihuang_loong`，`MobCategory.MISC`，`sized(1.5F, 2.5F)`，`clientTrackingRange(10)`，`fireImmune()` |
| `registry/ModAttributes` | `EntityAttributeCreationEvent` → `DihuangLoongEntity.createAttributes().build()` |
| `BeLoongCoreClient` | 注册 `DihuangLoongRenderer` |

**`MobCategory.MISC`**（= `("misc", -1, true, true, 128)`）而非 `CREATURE`/`MONSTER`：
**不占刷怪上限**且持久。它是布景，不该和玩家的刷怪塔抢名额。

**碰撞箱 1.5×2.5 取的是"身体主体段"**：模型约 9 格长（含长尾），
而 Minecraft 的碰撞箱是轴对齐方块，不可能贴合整条龙。

**刻意不做刷怪蛋、不注册自然生成** —— 只用 `/summon` 或结构放置。
一个能自己刷出来的演出 NPC 会污染整合包的野外体验。

---

## 五、简易 NPC 对话系统

### 5.1 定位与三条简化前提

**"简易"是刻意的**。本系统只服务一个场景：整合包作者想让某个实体说几句话。
它**不是**对话引擎。

三条简化前提：

1. **一个实体类型一段对话** —— 同类型实体共享同一段，不做 per-entity 状态；
2. **一维页列表** —— 线性播放完即弹选项，**不做对话树**（没有分支、没有条件、没有回跳）；
3. **数据随模组 jar / 数据包分发** —— 不做游戏内编辑器。

换来的是：数据格式只有 4 个字段、加载器 127 行、没有客户端缓存、没有登录同步。

> **需要剧情分支时应换用第三方对话框模组**（如 MCA Conversations），
> 本系统为其让路的机制见 §5.7 与 §10.3。

### 5.2 数据格式

`data/beloong/beloong/npc_dialogue/<任意名>.json`：

```json
{
  "entity": "minecraft:iron_golem",
  "trigger": "empty_hand",
  "name": "beloong.dialogue.iron_golem.name",
  "pages": [
    { "text": "beloong.dialogue.iron_golem.p1", "sound": "beloong:dialogue.iron_golem.1" },
    { "text": "beloong.dialogue.iron_golem.p2" }
  ]
}
```

| 字段 | 必需 | 缺省 | 说明 |
|---|---|---|---|
| `entity` | ✅ | — | 绑定的实体类型；未知 ID → **该文件整体丢弃并报错** |
| `trigger` | ❌ | `empty_hand` | `empty_hand` \| `any`；未知取值 → 报错（不静默回退） |
| `name` | ❌ | 用实体自身显示名 | 说话人名字的**翻译键** |
| `pages[].text` | ✅ | — | 页文本的**翻译键**；值内可用 `\n` 分行、`§` 颜色代码 |
| `pages[].sound` | ❌ | 无 | 预留的声音事件 ID。**当前只解析不播放**，schema 先定型 |

**文本存翻译键而不是字面文本**：多语言由原版 `lang` 机制负责，数据文件里不该出现中文。

**页数即数组长度**，不设 `count` 字段——避免两处不同步。

**`trigger` 缺省 `empty_hand` 的理由**：这样"手持铁锭右键铁傀儡 = 给铁傀儡回血"
这类原版交互不会被本功能吃掉。需要"手持任何物品都能对话"时逐实体显式写 `"any"`，
代价是该实体的那类原版物品交互会被抢走——这是**逐实体**的显式选择。

**有意不提供"潜行豁免"之类的隐式规则**（用户裁定）：规则越少越不容易踩坑。

### 5.3 加载（服务端）

`NpcDialogueLoader extends SimpleJsonResourceReloadListener`，
在 `BeLoongCore#addServerReloadListeners` 经 `AddReloadListenerEvent` 注册
—— **启动与 `/reload` 都会重新加载**。

**目录字符串 `"beloong/npc_dialogue"` 是 PackType 相对的**，不是绝对路径：

- 服务端资源管理器以 `PackType.SERVER_DATA` 构造（`MinecraftServer.java:1511`）→ 读 `data/`；
- 客户端以 `CLIENT_RESOURCES` 构造（`Minecraft.java:491`）→ 读 `assets/`；
- 路径解析再按 pack type 加目录前缀（`FallbackResourceManager` → `PackResources.listResources(packType, …)`）。

⇒ **同一个字符串，注册在服务端监听器上读 `data/`，注册在客户端监听器上读 `assets/`。**
首版注册在客户端、数据放 `assets/`，迁到服务端后字符串**一行没改**。

**失败隔离**：单个文件解析失败只丢弃该文件并打 ERROR，绝不中断其余文件的加载。
反面教材见 MCA Conversations 的 `DATAPACK.md`：对未知枚举值抛异常的严格解析会把
整个数据包重载（乃至世界创建）一起搞崩。

**用 `ifError`/`ifSuccess` 而不是 `resultOrPartial`**：DFU 的 `Codec.list` 会保留
"部分解码成功"的元素并返回 partial 结果，而 `resultOrPartial` 会把这种残件当成功收下——
后果是一页坏数据被静默丢掉、这条对话却仍然注册（玩家看到被截断的对话，日志还谎称解析成功）。
`ifSuccess`/`ifError` 只看 `result()`，Error 即使是 partial 也走 `ifError` ⇒ 整文件拒绝。

**重复绑定**（同一实体类型被多个文件绑定）：按 `ResourceLocation` 排序后
**后处理者胜**，并打 WARNING。**排序是刻意的**——让结果不依赖文件系统的枚举顺序。

**唯一的运行时观测点**是加载完成后那行 INFO：

```
[BeLoong] reloaded npc dialogues: N file(s) scanned, M dialogue(s) loaded
```

**同时打印扫描数与装载数**是有原因的：数据放错树（放 `assets/` 或目录名写错）时的症状是
"右键毫无反应、且不报任何错"，而**扫描数恒为 0** 会立刻指向它。

**字段刻意不加 `volatile`**：`SimplePreparableReloadListener#reload` 的形态是
`supplyAsync(prepare, backgroundExecutor).thenAcceptAsync(apply, gameExecutor)`
—— 只有 `prepare`（扫目录 + Gson 解析）在后台线程，而它**不碰**这个字段；
服务端侧的 gameExecutor 就是 `MinecraftServer` 自己（`MinecraftServer.java:1512-1519`
把 `(this.executor, this)` 传给 `ReloadableServerResources.loadResources`，
而 `MinecraftServer` 继承 `BlockableEventLoop`，其 `execute` 把任务放进**服务端主线程**队列）。
⇒ `apply`（写）与 `get()`（右键事件里读）**同在服务端主线程**。

> 首版代码审查曾判"不需要 volatile"，理由是"两者都在客户端主线程"。
> 搬迁到服务端后**线程换了、但"两者同处一条主线程"这个性质没变**，所以结论依然成立——
> 但理由必须按上面的事实重新表述，不能说成"客户端主线程"。

### 5.4 触发与下发

`NpcDialogueHandler`（**纯服务端**，注册到游戏总线，**不得**放进 `client/` 包、
**不得**标注 `@OnlyIn(Dist.CLIENT)`——专用服务器必须加载它）：

```
PlayerInteractEvent.EntityInteract
  → player.level().isClientSide() ? return      // 该事件两侧都会派发，只受理服务端那一次
  → Config.NpcDialogue.enabled ? 继续 : return   // ← 必须排在读配置之前（见下）
  → event.getHand() != MAIN_HAND ? return
  → NpcDialogueLoader.INSTANCE.get(target.getType()) == null ? return
  → trigger == EMPTY_HAND && 主手非空 ? return
  → PacketDistributor.sendToPlayer(serverPlayer, NpcDialogueOpenPayload)
```

**`isClientSide()` 检查必须排在读配置之前**：`enabled` 现在是**服务端配置**，
在物理客户端上读它等于依赖配置同步的时机（未加载时 `get()` 会抛
`IllegalStateException`），而客户端反正也不会用这个值做任何事。

**为什么在服务端受理**（首版在客户端，本次反转）：对话的决策点必须在服务端——
数据本身是服务端的（`data/` 树），将来"按原版进度判据筛选该看到的内容"也只能在服务端做。
客户端只负责把收到的这一条渲染出来。

**为什么只认主手**：原版一次右键通常只派发主手事件（副手仅在主手 PASS 时才轮到），
限定主手可保证"一次右键最多打开一次对话"。

**刻意不取消事件**：空手右键本无任何原版行为，取消没有收益，却会抢掉将来第三方模组的交互。

**刻意不手写距离校验**：原版服务端在交互包处理里已做交互距离校验，再加一层是重复的。

**`NpcDialogueOpenPayload`（服务端 → 单个玩家）的载荷是"客户端渲染所需的最小事实"**：

| 字段 | 说明 |
|---|---|
| `nameKey` | 说话人名字的翻译键（`Optional`） |
| `fallbackNameKey` | 兜底名的翻译键（服务端取实体类型名，如 `entity.minecraft.iron_golem`） |
| `pages` | 逐页文本 |
| `entityId` | 目标实体的**网络 id**（客户端用于取命名牌自定义名；实体未加载时允许找不到） |

**为什么不直接下发 `NpcDialogueEntry` 本体**：`StreamCodec` 解码失败**无法优雅降级**
——NeoForge 在解码阶段抛异常会**中止连接**，而不是丢掉一个包。而条目里的 `EntityType`
在客户端只能做注册表反查，是整条链路上**唯一可失败的一步**。于是：

- 不放 `EntityType`，兜底名改用 `EntityType#getDescriptionId()`（普通字符串）；
- 需要实体本体的场合（命名牌自定义名）由 `entityId` 在客户端按网络 id 找，找不到就退到兜底名；
- `trigger` 不上线——触发判定只在服务端做，客户端渲染用不到。

⇒ **线格式全函数、永不抛。**

同一原则也体现在页的 `sound` 字段上：**刻意不用 `ResourceLocation#STREAM_CODEC`**
（它是 `STRING_UTF8.map(ResourceLocation::parse, …)`，而 `parse` 对畸形输入会**抛**），
改用基于 `tryParse` 的 codec（返回 `null` 而不抛）。

> 实践中畸形值不可能出现（唯一的生产者是我们自己的服务端，而它读的是非抛的 JSON codec），
> 这里纯属防御——但正因为"线载荷永不抛"是整个载荷形状的设计依据，
> 这条不变量必须是**真的**，不能只是听起来对。

**客户端处理器默认在主线程执行**（与 `TreasureSyncPayload` 同款），
故直接 `setScreen` 即可，不需要 `context.enqueueWork`。
在 `handleClient` 里引用客户端类 `NpcDialogueScreen` 是安全的：专用服务器上本方法
**永不被调用**，类加载是惰性的。

### 5.5 界面

`NpcDialogueScreen`（460 行）+ `NpcDialogueOptionButton`（147 行）。

**三段状态机**：

| 状态 | 行为 | 出口 |
|---|---|---|
| `TYPING` | 打字机逐字显示当前页（每 tick `charsPerTick` 字） | 打完 → `WAIT_CLICK` |
| `WAIT_CLICK` | 显示"继续"箭头，等玩家点击 | 点击 → 下一页；已是最后一页 → `SHOWING_OPTIONS` |
| `SHOWING_OPTIONS` | 显示选项按钮 | 当前只有一个"离开" → 关闭界面 |

**布局常量**（屏幕相对坐标，便于适配不同 GUI 缩放）：

| 常量 | 值 | 用途 |
|---|---|---|
| `NAME_Y` | 0.790 | 说话人名字基线 |
| `RULE_Y` | 0.822 | 名字下方分隔线 |
| `RULE_HALF_WIDTH` | 0.165 | 分隔线半宽 |
| `TEXT_TOP` | 0.860 | 正文起始（**自下而上**排版） |
| `ARROW_Y` | 0.964 | "继续"箭头 |
| `OPTION_LEFT` / `OPTION_WIDTH` | 0.661 / 0.22 | 选项按钮左右 |
| `OPTION_BOTTOM` | 0.787 | 选项按钮底边 |
| `OPTION_HEIGHT` / `OPTION_GAP` | 20 / 8（像素） | |
| `TEXT_MAX_WIDTH` | 0.80 | 正文换行宽度 |
| `LINE_GAP` | 2（像素） | 行距 |
| `GRADIENT_START` / `GRADIENT_BOTTOM` | 0.62 / `0xE6000000` | 底部渐变遮罩（向上透明） |

**`renderBackground` 必须留空**（覆写为空实现）：原版会调用 `renderBlurredBackground`
——本界面是"叠在游戏画面上的字幕条"，糊掉整个画面就毁了观感。
**`isPauseScreen()` 返回 false**：对话不暂停游戏。

**文本处理**：

- 逐页文本是翻译键，渲染前 `Component.translatable(...).getString()`；
- 打字机按**字符数**截断，但 `§` 颜色代码是两字符且不应被截半个 —— 这是为什么
  截断逻辑要识别 `SECTION_SIGN`；
- 名字渲染走三级回退：`nameKey` → 名字命名牌 → `fallbackNameKey`。

**颜色常量**：`WHITE = 0xFFFFFFFF`、`GOLD = 0xFFE8B84B`、`GOLD_BRIGHT = 0xFFFFD873`
（选项按钮悬停时在两档金色间插值）。

**"离开"选项的文案键是硬编码的** `beloong.dialogue.option.leave`——它是界面的固定元素，
不是数据内容（数据里没有"选项"这一层，见 §5.1）。

### 5.6 配置

| 键 | 规格 | 默认 | 说明 |
|---|---|---|---|
| `[npc_dialogue] enabled` | **SERVER** | `true` | 服务端据此决定是否受理右键 |
| `[npc_dialogue] charsPerTick` | CLIENT | `1`（范围 1–20） | 打字机速度，1 ≈ 每秒 20 字 |
| `[npc_dialogue] nameScale` | CLIENT | `1.5`（范围 1.0–2.0） | 说话人名字字号倍数 |

**分宿两处的依据是"判定发生在哪一端"**：触发判定在服务端 ⇒ `enabled` 是服务端配置；
纯渲染参数只影响本地观感 ⇒ 客户端配置。

`nameScale` 默认 1.5 对齐参考图，代价是位图字体非整数缩放会让部分笔画 1px、部分 2px；
填 1.0 最清晰。

### 5.7 失败模式

| 症状 | 首要检查项 |
|---|---|
| 右键毫无反应、日志无报错 | 启动日志的 `N file(s) scanned` 是否为 0 → 数据放错树（`assets/`）或目录名拼错 |
| 该实体原有交互被抢走 | 该文件的 `trigger` 是不是 `any`；改回 `empty_hand` |
| 对话只有前几页 | 日志里的 `Failed to parse npc dialogue file '...'` —— 整文件被拒（§5.3） |
| 改了 JSON 没生效 | 是否 `/reload` 过；`data/` 树只由 `AddReloadListenerEvent` 重载 |
| 界面糊掉背景 | 有人动过 `renderBackground`（§5.5） |
| 名字是 `entity.minecraft.xxx` | `lang` 里缺该 `name` 键，落到了兜底名 |
| 服务端启动时 `IllegalStateException` | `enabled` 读得太早（§5.4 的顺序要求） |

**与第三方对话框模组的接缝**：把 `enabled` 设为 `false` 即整体让位（它挡在受理的第一步）。
`trigger: empty_hand` 缺省已经保住了绝大多数原版物品交互。**不需要**
`ModList.isLoaded` 守卫——本模组不依赖任何对话模组。

---

## 六、调试命令

```
/beloong npc walk   <targets> <pos>     // 让这些 NPC 走到坐标
/beloong npc attack <targets> <victim>  // 下令攻击某个生物
/beloong npc stop   <targets>           // 取消移动与攻击，回到站桩
```

op 级（`hasPermission(2)`）；`targets` 过滤 `NpcEntity`，因此对本模组**所有** NPC 生效；
选到的不是 NPC 时**明确报错**（`beloong.command.npc.no_targets`），不静默。

**定位：验收与手动摆位工具，不是玩法内容。** 它存在的直接原因很具体——
`walkTo` / `attack` 是**纯 API、默认没有任何调用方**，没有这两个命令，它们就无法被当场验证。
将来不需要了，**删本类 + `BeLoongCore` 里一行注册即可**。

它只调实体 API，**不碰实体字段**。

**刻意没有的子命令**（用户裁定）：

- `run` —— 奔跑状态已取消，速度差异改由移速属性的加成体现（§3.6）；
- `turn` —— 效果不好：站桩转向依赖"头带身体"的原版链（§3.4），玩家在场时还会被
  `LookAtPlayerGoal` 让位；而"旋转"本身可以直接从行为上看出来，不需要指令演示。

> 删 `turn` 之后，与之配套的自研转向能力（`maxTurnPerTick()`、`NpcTurnGoal`、
> 相关字段与语言键）**已整类删除**，不是只摘掉命令。
> 当前的"转向玩家"功能**不依赖**它们中的任何一个（§3.4）。

---

## 七、配置参考

### `[npc_dialogue]`（见 §5.6）

```toml
# beloong-server.toml
[npc_dialogue]
    # 启用简易 NPC 对话界面
    enabled = true

# beloong-client.toml
[npc_dialogue]
    # 打字机速度（字/tick），默认 1 ≈ 每秒 20 字
    charsPerTick = 1      # 1 ~ 20
    # 说话人名字的字号倍数。1.0 = 与正文同号（最清晰）
    nameScale = 1.5       # 1.0 ~ 2.0
```

分组标题的语言键两处复用同一个 `beloong.configuration.npc_dialogue`，
故模组菜单里客户端与服务端看到的分组名一致。

### 不受配置控制的部分

- **NPC 的语义**（无敌/不可推动/不消失）—— 结构性决策，子类要例外就覆写方法；
- **属性表**（血量/攻击力/移速）—— 要改就覆写 `createAttributes()`，不走配置；
- **对话数据** —— 由数据包覆写，不走配置。

这是刻意的：**配置项每多一个，就多一种"半开启"状态**（如天灾维度曾因
`enabled` 只覆盖一层而产生的第三种状态）。本系统至今没有为 NPC 加过任何配置开关。

---

## 八、资源清单

### Java（NPC 相关）

行数为本文修订时的实测值（含注释）；注释改动会让它浮动几行，**看趋势即可，不必对齐**。

| 文件 | 行数 | 职责 |
|---|---|---|
| `entity/NpcEntity.java` | 373 | 通用基类（**本系统的主体**） |
| `entity/DihuangLoongEntity.java` | 49 | 地黄龙（构造 + 属性） |
| `entity/ai/NpcAttackGoal.java` | 47 | 命令式攻击 goal |
| `dialogue/NpcDialogueEntry.java` | 164 | 数据定义 + Codec |
| `dialogue/NpcDialogueLoader.java` | 127 | 服务端加载器 |
| `dialogue/NpcDialogueHandler.java` | 77 | 服务端右键受理 |
| `dialogue/NpcDialogueOpenPayload.java` | 85 | 下发包 |
| `client/NpcDialogueScreen.java` | 460 | 对话界面 |
| `client/NpcDialogueOptionButton.java` | 147 | 选项按钮 |
| `client/DihuangLoongRenderer.java` | 27 | 渲染器 |
| `client/model/DihuangLoongModel.java` | 118 | 模型 + Molang |
| `command/NpcCommand.java` | 121 | 调试命令 |

> **比例值得注意**：基类 373 行、对话系统 655 行，而具体的 NPC 只有 49 行。
> 前两者是一次性成本，后者才是每个新 NPC 的边际成本。

### 资源包

| 文件 | 大小 |
|---|---|
| `assets/beloong/geo/dihuang_loong.geo.json` | 196 KB |
| `assets/beloong/animations/dihuang_loong.animation.json` | 477 KB |
| `assets/beloong/textures/entity/dihuang_loong.png` | 153 KB（256×256） |

### 数据包

| 文件 | 大小 | 内容 |
|---|---|---|
| `data/beloong/beloong/npc_dialogue/iron_golem.json` | 297 B | 2 页，`empty_hand` |

> **这是本系统当前唯一的数据文件**——它是格式的活样例，也是唯一的端到端验收对象。
> 地黄龙**尚未**接入对话（§10.2）。

### 多语言键

`zh_cn.json` / `en_us.json` 各 226 键，键集合完全一致。NPC 相关：

| 键 | 用途 |
|---|---|
| `entity.beloong.dihuang_loong` | 实体名 |
| `beloong.command.npc.{walk,attack,stop,no_targets,not_living}` | 调试命令反馈 |
| `beloong.configuration.npc_dialogue(.tooltip)` | 配置分组 |
| `beloong.configuration.npcDialogue{Enabled,CharsPerTick,NameScale}(.tooltip)` | 三个配置项 |
| `beloong.dialogue.iron_golem.{name,p1,p2}` | 样例对话文案 |
| `beloong.dialogue.option.leave` | 界面固定选项 |

**对话文案全部在 `assets/…/lang`，数据文件里只存翻译键**（§5.2）。

---

## 九、设计决策记录

以下为 NPC 分支全部决策的合并记录。**编号在原文档中各自独立，此处重排以主题为序**，
需要追溯过程时以文末「历史文档与处置状态」为入口。

**架构与定位**

1. **通用基类 + 子类只描述"是什么"** — AI、语义、动画状态机、外部 API 全在 `NpcEntity`；
   子类给实体类型绑定、碰撞箱、渲染器、属性起点。地黄龙因此只有 50 行。
   动机：第二个 NPC 到来时不该复制粘贴 AI。
2. **默认站桩，能力即 API** — 不自主游走、不索敌。`walkTo`/`attack` 是**纯 API、默认无调用方**，
   调试命令的存在只为让它们可被当场验证。
3. **对话数据从 `assets/` 迁到 `data/`，服务端权威** — 首版纯客户端是"v1 无副作用"
   这一取舍前提的产物；引入"按进度筛选内容"后前提消失。判定点必须在服务端，
   因为数据是服务端的、判据也只能在服务端求值。

**语义**

4. **无敌用覆写 `isInvulnerableTo`，不用 `setInvulnerable`** — 两条独立原因：
   ① `/summon` 的 `load()` 会把字段从 NBT 覆盖回 false；
   ② 原版无敌**显式放行创造玩家**（`Entity.java:2681-2686`），而我们要连创造也打不动。
5. **放行 `BYPASSES_INVULNERABILITY`** — 只含 `/kill` 与虚空。留一条管理后路，
   否则摆错位置的 NPC 会永久占据区块。
6. **不可推动用 `isPushable()` 覆写** —— 站桩 NPC 不该被玩家推着走。
7. **永不消失用 `requiresCustomPersistence()` 官方钩子**，而不是覆写 `isPersistenceRequired()`
   —— 后者是"当前是否持久化"的状态位，前者才是"我的存续不该由刷怪规则决定"的语义钩子。
8. **不覆写 `isNoAi()`** — `LivingEntity#travel()` 的**整个方法体**被
   `isControlledByLocalInstance()` 包住（`LivingEntity.java:2219-2220`），而它 =
   `isEffectiveAi()` = `!isNoAi()` ⇒ 覆写它会**连重力与位移积分一起关掉**，
   实体其实是被"钉"在召唤点的。首版地黄龙正是这样，且当时的诊断错误地归结为"没有 AI"。
9. **语义写成覆写方法，构造函数里的 `set` 只保证 NBT 一致** — 两者的用途不同，
   并存不是冗余。

**AI 与身朝**

10. **基础 AI 全部用原版 goal** — 三个 goal 里两个是原版的，唯一自研的那个还被命令标志位把关。
11. **攻击 goal 优先级 3 < look goal 5** — `GoalSelector` 的 `lockedFlags` 仲裁会让
    优先级更低的 LOOK 占用者**永久无法启动**；这个错误**不报错**，只表现为"永远打不到人"。
12. **攻击 goal 用命令标志位把关，不直注册原版 `MeleeAttackGoal`** — 否则任何来源
    （别的模组的索敌、将来的 goal）给实体设了 `target` 就会开打，"攻击只作 API"就不成立。
13. **没有"把身体转向玩家"的自研 goal** — 原版 `BodyRotationControl` 会追着头走，
    这一步是白拿的。
14. **绝不覆写 `tickHeadTurn`** — 会废掉"身体滞后头 75°"的关系，而这个相对角是
    头部 Molang 的**唯一输入** ⇒ 症状是"不扭脖子、头身一体转"。
    本类曾改过，已完整回退（`0945a16`）。
15. **同一个自由度上不要放两个等速驱动者** — 曾同时保留 `LookAtPlayerGoal` 与自研转向 goal，
    两者都以 10°/tick 驱动**头**转向同一目标 ⇒ 相对角恒 ≈ 0 ⇒ 同样没有头部动画。
16. **移除 `RandomLookAroundGoal`** — 它只动头，但站桩时原版会把身体拖向头 ⇒
    净效果是**自主间歇性转身**，与"默认不转动"冲突。实机验证后由用户裁定移除（`5cf140e`）。
17. **look goal `probability` 给 1.0** — 原版默认 0.02 会让 NPC 平均 2.5 秒才看玩家一眼，
    观感上不成立。
18. **将来要定制身朝就覆写 `createBodyControl()`** — 这是原版留给子类的扩展点
    （`Phantom`/`Armadillo`/`Camel`/`Shulker` 都在用），不是 `tickHeadTurn`。
19. **删除 `turn` 调试指令及配套的自研转向能力** — 效果不好（站桩转向由原版链提供，
    玩家在场时还会被 look goal 让位），而旋转可以直接从行为上看出来。
    **当前的转向功能不依赖其中任何一个。**

**移动与速度**

20. **移速用原版刻度的 0.3** — 与原版生物可直接比较（僵尸 0.23 / 铁傀儡 0.25 / 村民 0.5）。
    首版设 0.1 并试图补偿，结果是走得像蜗牛。
21. **位移比 = `(档位 × 属性)² / 0.1`** — 平方来自 `Mob#setSpeed` 同时写 `zza`
    （`Mob.java:557-560`），而玩家不是 `Mob` 的子类。⇒ 0.3 是走路玩家的九成，
    `√0.1 ≈ 0.3162` 才是同速。原版 `PathNavigation#doStuckDetection:312` 也显式平方了速度。
22. **`walkTo` 档位固定 1.0** — 与 `MeleeAttackGoal` 一致；档位是原版的速度语义。
23. **不做任何自研的移动补偿机制** — 属性值本身就是唯一的旋钮。

**动画**

24. **三档 idle / walk / run，由 `AnimationController` 谓词切换** ——
    GeckoLib 的标准用法，不加状态机。
25. **"跑"不是状态，判据是"属性值 > 基础值"** — 迅捷/信标等 modifier 会自动让属性值上升，
    且属性值本来就同步给客户端 ⇒ 效果一生效自动切 run、一结束自动回 walk，
    **不需要状态机、也不需要网络包**。
26. **移动判据用 GeckoLib `isMoving()`** — 它要求实体**真的在位移**，
    所以"原地转身/扭头"仍播 idle。
27. **`RawAnimation` 在 `registerControllers` 里构建而非构造函数** — 动画名来自可覆写方法，
    构造函数里调虚方法会踩"子类字段尚未初始化"的经典坑；而 `registerControllers`
    由 `AnimatableManager` 惰性调用。
28. **动画名做成可覆写方法**，过渡时长默认 5 tick（0 = 硬切）。

**头部 Molang**

29. **`query.head_yaw` 是相对角（头 − 身）**，不是绝对角 —— 头的骨骼挂在身体骨骼之下，
    父骨骼已经把身体朝向带进去了。
30. **`HEAD_YAW_SIGN = -1`** — Molang 的旋转正方向与 yaw 增减方向相反。
    这是实机发现的"头左右反了"bug 的全部修复内容。
31. **用 `applyMolangQueries` 注册全局 Molang 变量**，而不是在渲染器里手动改骨骼 ——
    让动画资产自己决定哪根骨骼吃哪个变量，本模组不需要知道模型内部命名与层级。
32. **`.geo.json` 的 `identifier` 为 `geometry.unknown`** — 不影响 GeckoLib 使用
    （资源路径由模型类显式给出），故不修改第三方导出的模型文件。

**对话系统**

33. **数据在 `data/beloong/beloong/npc_dialogue/`，服务端加载** — 可被数据包覆写，
    且随 jar 分发。
34. **同一个目录字符串在服务端读 `data/`、客户端读 `assets/`** — 因为它是 PackType 相对的，
    解析时按资源管理器的 pack type 加前缀。首版迁到服务端时**这个字符串一行没改**。
35. **一个实体类型一段对话 + 一维页列表，不做对话树** — "仅供整合包使用"这一前提换来的简化；
    需要分支时应换用第三方对话框模组。
36. **`trigger` 缺省 `empty_hand`，只提供 `empty_hand` / `any`** — 缺省值保住原版物品交互；
    有意不做潜行豁免之类的隐式规则。
37. **每页只存翻译键，文本留在 `lang`** — 多语言交回原版机制，数据文件里不出现自然语言。
38. **服务端受理右键，客户端不持有全表** ⇒ 无客户端缓存、无登录全量同步。
39. **只认主手 + 不取消事件 + 不手写距离校验** — 主手保证一次右键最多开一次；
    不取消是为了不抢第三方交互；距离校验原版已做。
40. **下发载荷是"渲染所需最小事实"，不放 `EntityType`** — `StreamCodec` 解码失败会
    **中止连接**（无法优雅降级），而 `EntityType` 反查是链路上唯一可失败的一步。
    ⇒ 线格式全函数、永不抛。
41. **`sound` 字段先定型不播放** — schema 先立住，将来接配音只需加一行播放调用。
42. **单文件解析失败只丢该文件** — 严格解析抛异常会把整个世界创建搞崩（MCA 的教训）。
43. **用 `ifError`/`ifSuccess` 而非 `resultOrPartial`** — 后者会把 DFU 的 partial 结果
    当成功收下，导致"一页坏数据被静默丢掉、对话却仍然注册"。
44. **重复绑定按 `ResourceLocation` 排序后处理者胜 + WARNING** — 排序让结果不依赖
    文件系统枚举顺序。
45. **`entries` 不加 `volatile`** — `apply` 与 `get()` 同在服务端主线程
    （只有 `prepare` 在后台线程，而它不碰这个字段）。**理由要按服务端主线程表述**，
    不能说成"客户端主线程"（首版审查的措辞在搬迁后已不准确）。
46. **加载后同时打印扫描数与装载数** — 这是本功能唯一的运行时观测点；
    "放错树"的症状是右键毫无反应且不报错，而扫描数恒为 0 会立刻指向它。
47. **`enabled` 在 SERVER_SPEC，`charsPerTick` / `nameScale` 在 CLIENT_SPEC** —
    依据是"判定发生在哪一端"。
48. **界面不糊背景、不暂停游戏** — 它是叠在游戏画面上的字幕条，不是独立菜单。
49. **名字默认 1.5 倍，可调到 1.0** — 1.5 对齐参考图，代价是位图字体非整数缩放的笔画粗细不均；
    这一点写进了配置项的 tooltip，让整合包作者自己选。
50. **"离开"选项的文案键硬编码** — 它是界面的固定元素，数据里没有"选项"这一层。

**工程与验证**

51. **调试命令是验收工具，不是玩法内容** — 删除成本是"删本类 + 一行注册"，这是刻意的。
52. **结论必须带证据（类注释或原版行号）** — 本系统多处 bug 的根因与最初诊断不同
    （`isNoAi` 与重力、"没有 walk 动画"与 `isMoving()` 阈值、`turn` 指令与 `yBodyRot` 不同步），
    因此"看起来对"不算通过。
53. **实机验收是唯一终审** — 移速快慢、动画是否有、头身是否分离、有无陷地，
    这四类问题静态检查都看不出来。
54. **旧设计文档一律保留、不删除** — 它们是决策过程的记录；本文件以源码为准，
    冲突时以本文件为准（用户明确要求保留）。

---

## 十、已知问题与后续可选项

### 10.1 已结案的风险

| 编号 | 风险 | 结论 |
|---|---|---|
| R9 | 地黄龙模型可能陷地/悬空 | **实测无偏移**，`DihuangLoongRenderer#preRender` 保持最小实现。若将来换模型或碰撞箱需重新确认 |
| — | "站桩身体永远对不正"（我此前的错误判断） | **不成立**。`rotateHeadTowardsFront` 的允许量会递减到 0，身体最终与头完全对齐（§3.4） |
| — | 无 AI 会不会关掉重力 | 会（`LivingEntity#travel` 整体被闸门包住）⇒ 已改为不覆写 `isNoAi()`（决策 8） |
| — | 站桩时头能不能领先身体 | 能。`clampHeadRotationToBody` 只在**有寻路**时生效（§3.4） |

### 10.2 推迟的待办

| 项 | 内容 | 状态 |
|---|---|---|
| T11 | 把对话触发从 `PlayerInteractEvent` 迁到 `Mob#mobInteract(Player, InteractionHand)` | **刻意推迟**。`mobInteract` 是原版官方钩子（`protected`，12+ 个原版覆写者），迁过去就不需要事件总线，但它要求每个 NPC 子类显式覆写，而事件方案对**任意**实体类型开箱生效（当前样例绑的是原版铁傀儡，我们不可能去覆写它）。**等有了本模组自己的第二个绑定对话的 NPC 再决定。** |
| T12 | 把地黄龙接入对话（加一个 `dihuang_loong.json`） | 刻意推迟——地黄龙还在调 AI 与动画，剧情文案未定。 |
| — | `run` 动画缺 3 根骨骼（`Drip1-3`） | **不修**。GeckoLib 优雅忽略，观感缺失轻微；改模型资产的风险大于收益。 |
| — | `pages[].sound` 只解析不播放 | 有意为之（决策 41）。 |
| — | 第二个 NPC 子类 | 通用基类的价值要靠第二个子类才能验证；目前只有地黄龙一个，它是"基类能否只剩 50 行"的证据，但还不能证明"复制成本低"。 |

### 10.3 明确不做的事

- **剧情分支 / 任务状态机 / 好感度 / 条件解锁** —— 不是本系统的定位（§5.1）。
  需要这些时应换用第三方对话框模组；本系统的让路机制是 `enabled = false`（§5.7）。
- **游戏内对话编辑器** —— 数据文件是唯一的编辑入口。
- **为 NPC 加配置开关** —— 见 §七「不受配置控制的部分」。
- **自研任何加速来源** —— 奔跑由外部效果经移速属性体现（决策 25）。

### 10.4 未建立测试集的老问题

本模组**没有 `test` source set**（`src/test` 不存在），因此本系统全部验收依赖：

1. `.\gradlew.bat build --console=plain` 必须 BUILD SUCCESSFUL；
2. 静态探针（如"源码中不应再出现 `turnTo|setFacing|NpcTurnGoal|maxTurnPerTick` 等符号"）；
3. **实机运行验收**（唯一能发现移速/动画/转向类问题的环节）。

这是项目级的现状而非本系统的选择；引入测试集是独立的、更大的工程决定。

---

## 十一、历史文档与处置状态

> **本文件是 NPC 系统的唯一权威文档。** 下列文档**全部保留在仓库中**，
> 作为决策过程与失败记录的历史存档；与本文冲突时**以本文与当前源码为准**。

### 已被本文整合的文档（保留，不再维护）

| 文件 | 内容 | 处置状态 |
|---|---|---|
| `docs/plans/2026-09-20-npc-dialogue-design.md` | 对话系统首版设计（客户端版） | **设计已整体反转为服务端权威**（决策 3）。保留其"界面三段状态机""简化三条前提"部分 |
| `docs/plans/2026-09-20-npc-dialogue-plan.md` | 上述设计的实施计划 | **已执行完毕**（✅），过程记录 |
| `docs/plans/2026-09-25-npc-dialogue-data-driven-design.md` | 对话数据从 `assets/` 迁到 `data/` 的设计 | **已实现**（`d7849e7`）。本文 §5 的正文来源 |
| `docs/plans/2026-09-25-npc-dialogue-data-driven-plan.md` | 上述设计的实施计划 | **已执行完毕** |
| `docs/plans/2026-09-21-dihuang-loong-npc-design.md` | 地黄龙首版设计（无 AI 雕像 + 无敌不可推动） | **部分被取代**：`isNoAi()` 方案已推翻（决策 8），R9 已结案（§10.1）。保留其资产调研与风险清单 |
| `docs/plans/2026-09-25-dihuang-loong-ai-design.md` | 地黄龙 AI 设计（D16–D31） | **§四/§五已被取代**（自研转向能力已删除，决策 19）；其余各项已并入本文 §3、§4 |
| `docs/plans/2026-09-25-dihuang-loong-ai-plan.md` | 上述设计的实施计划 | **作废**（转向能力已删除） |
| `docs/plans/2026-09-25-npc-base-class-design.md` | 通用基类设计（D32–D47） | **已实现**。其中"移速补偿"与"冲刺状态"两项已修订（决策 20、25） |
| `docs/plans/2026-09-25-npc-base-class-plan.md` | 上述设计的实施计划 | **已执行**（`dc0d763`），已被后续工作取代 |
| `docs/plans/2026-09-25-npc-vanilla-ai-design.md` | "尽量用原版机制"的裁定文档（D48–D57 + R-impl-2） | **已实现 + 实机通过**。本文 §3 的正文来源 |
| `docs/plans/2026-09-25-npc-vanilla-ai-plan.md` | 上述设计的实施计划（T1–T12） | **T1–T9 已完成并验证；T10–T12 见 §10.2** |

### 相关但不在整合范围的文档

| 文件 | 关系 |
|---|---|
| `docs/天灾维度总设计.md` | **格式范本**。本文的章节结构、决策记录写法、"以源码为准"的声明方式均照此办理 |
| `docs/龙宫维度总设计.md`、`docs/龙宫天空渲染总设计.md`、`docs/自制龙之生存技能总设计.md` | 同类"总设计"文档，可作交叉参考 |

### 阅读顺序建议

- **要改 NPC 行为** → §3（基类）→ §3.4（身朝，最容易踩）→ §九 决策 10–23；
- **要加对话内容** → §5.2（格式）→ §5.3（加载与日志）→ §5.7（失败模式）；
- **要接第二个 NPC** → §3.8（可覆写的默认值）→ §4.3（注册点清单）→ §10.2；
- **要复现验收** → §10.4（三步验证流程）。

---

## 附：本文与旧文档的编号对照

旧文档的决策编号（D1–D57、对话系统 D1–D33、风险 R0–R18）**不在本文中续用**。
本文 §九 是重新按主题编排的 54 条。需要追溯某条决策的原始讨论时，
按上表进入对应旧文档；**若两者冲突，以本文与源码为准。**
