# NPC 表情播放系统 设计文档

**日期：** 2026-09-29
**状态：** 已批准（brainstorming 五阶段完成，用户逐节确认）
**采用路径：** A. 双控制器 + 后注册覆盖（DragonSurvival 的做法）
**前情：** `docs/plans/2026-09-27-npc-state-system-design.md`（本设计**取代**其中 `sitting`/`dancing` 两态的部分）

---

## 一、问题与目标

现状 `NpcState` 是**单一互斥枚举**，四态 `idle` / `flying` / `sitting` / `dancing`，其中
`sitting`/`dancing` 被归为「**姿态**」族（`isMovementMode() == false`），进入它们会取消移动与攻击指令。

**问题在于姿态把两件本质不同的事绑进了同一个枚举：**

1. **这个 NPC 现在怎么动** —— 导航/重力/移动控制，**服务端权威、要落盘**；
2. **这个 NPC 现在长什么样** —— 播哪条动画，**纯表现**。

`sit`/`dance` 属于第 2 类，却因为混进第 1 类而被迫与"能不能走"绑定，也无法临时播一条别的动画。

**用户需求（R1–R5）：**

| 编号 | 需求 |
|---|---|
| R1 | 新增 `npc play <targets> <动画名>` 表情播放 |
| R2 | 表情**覆盖**当前状态动画（参考 DragonSurvival 的实现方式） |
| R3 | `state` 收缩到 **idle / fly 两态**，`sit`/`dance` 迁入表情系统 |
| R4 | `play` 能播**任何**动画（idle 态播 fly、fly 态播 dance） |
| R5 | 末额外读取 `mo.extra.animation.json`，**两个动画文件简单合并**（不做按需加载、不做二进制编译+缓存），用 GeckoLib 现成方法 |

---

## 二、调研结论（逐条附行号）

### 2.1 GeckoLib **原生支持**"一个模型多个动画文件"

- `GeoModel.java:76-84`：`getAnimationResourceFallbacks(T animatable)` 返回 `ResourceLocation[]`，默认空数组。
- `GeoModel.java:144-158`：查找顺序是「**主文件优先，miss 才依次查 fallback**」。
- ⇒ 只要覆写该方法返回 extra 文件（约 3 行），**无需任何自定义加载器** ✓

**加载时机（决定 R5 可行）：** `GeckoLibCache.java:113-133` 在资源重载时用
`resourceManager.listResources("animations", f -> f.toString().endsWith(".json"))`
把 `assets/<ns>/animations/` 下**所有** json **全部烘焙**（并行，`:115-121`）。
⇒ 恰好满足 R5 的"简单合并、不按需加载、不用二进制缓存"：extra 放在 `animations/` 下会被**自动**烘焙，
`getAnimationResourceFallbacks` 只负责让它**能按名被找到** ✓

> ⚠️ **同名时主文件优先** ⇒ extra 里的同名动画**永远用不到，且完全静默**。
> 必须靠"删重复项 + 静态探针（两文件动画名交集必须为空）"防住。

### 2.2 覆盖机制：**多控制器 + 后注册者胜**（GeckoLib 库级事实）

- `AnimationProcessor.java:80`：按 `getAnimationControllers().values()` 顺序遍历各控制器；
- `AnimatableManager.java:202-208`：用**保序** `Object2ObjectArrayMap` 按**注册顺序**构建；
- `AnimationProcessor.java:107-131`：对骨骼是 `setRotX/setPosX/setScaleX` **直接赋值**（非累加）。

⇒ **同一骨骼/通道上，最后写入者覆盖前者** ⇒ **后注册的控制器赢** ✓

### 2.3 DragonSurvival 的做法（同族引擎：GeckoLib 4.7.5.1）

出处：`D:\Minecraft\开源模组参考文件\DragonSurvival\`（branch `1.21.1`，HEAD `511692bea`）。
⚠️ `D:\Minecraft\DragonSurvival-Mod\` **不是源码** —— 它没有 `.java`/`build.gradle`，
是**资源+数据包**（其 `data/.../dragon_emote_set/default_emotes.json` 在**覆盖**上游内置表情表）。

| 它的做法 | 出处 |
|---|---|
| 多控制器：`main` 先注册，`emote_*` 后注册 | `DragonEntity.java:134-135`、`:139` |
| **播非 blend 表情时 `main` 主动停自己** | `DragonEntity.java:606-612`：`state.getController().stop(); return PlayState.STOP;` |
| **回归是隐式的**：状态动画每帧重算，清掉表情后下一帧自动重选 | 同上；⇒ **不需要保存/恢复"原动画"** |
| 一次性判定用**自研渲染帧倒计时** `AnimationTickTimer.java:20-35` | 因为它那份 GeckoLib 的 `isCurrentAnimation()` 不可靠（`AnimationUtils.java:30` 有 TODO） |
| **它没有任何"播动画"指令**（只有 GUI/快捷键 + 服务端盲转发） | ⇒ 本设计的指令面是新东西 |
| 表情槽硬上限 4，满了**静默丢弃** | `DragonEntity.java:223-241` —— 反面教材，**我们不设槽位** |

### 2.4 ⚠️ `hold_on_last_frame` **不是**"播完"（本次纠正的一个错误前提）

`Animation.java:43-47`：

```java
LoopType HOLD_ON_LAST_FRAME = register("hold_on_last_frame", (animatable, controller, currentAnimation) -> {
    controller.animationState = AnimationController.State.PAUSED;   // ← 设为 PAUSED
    return true;                                                    // ← 返回 true（"再播一次"）
});
```

而 `hasAnimationFinished()` = `currentRawAnimation != null && animationState == State.STOPPED`
（`AnimationController.java:330-332`，javadoc 原话"已播完且不会再循环或接下一段"）。

⇒ **`hold_on_last_frame` 永远不会进入 `STOPPED`** ⇒ `hasAnimationFinished()` **恒为 false**。

**实测本项目的资产：**

| 文件 | 动画 | `loop` 值 |
|---|---|---|
| `mo.animation.json` | `attack` | **`"hold_on_last_frame"`** |
| `mo.extra.animation.json` | `descend` | **`"hold_on_last_frame"`** |
| 其余全部（`idle` `fly` `walk` `run` `sit` `dance`） | | `true` |

且**全项目没有任何 `loop: false`** ⇒ 若按"`hasAnimationFinished()` 判一次性"实现，
**`attack`/`descend` 会永久定格在末帧**（brainstorming 中已纠正此点）。

**用户裁定（A：时间为准）：**
- `loopType() == LoopType.LOOP` ⇒ **持续型**（一直播到 `play … stop` / `reset` / 换名）；
- 其它（`PLAY_ONCE` / `HOLD_ON_LAST_FRAME` / 自定义）⇒ **一次性**：由**客户端自记的计时器**判定 ——
  换名那一帧记下 `npc.tickCount` 作起点，之后每帧比较 `npc.tickCount - 起点 >= animation.length()`；
  `Animation.length()` 的单位是 **tick**（`BakedAnimationsAdapter.java:59`：`animation_length * 20d`）
  ⇒ 超过即置 `emoteDone = true` ⇒ 自动回落。
- ⚠️ **不要用 `state.getAnimationTick()` 当"当前动画进度"** —— `GeoModel.java:217` 是
  `animationState.animationTick = this.animTime`，那是**全局动画时钟**，不是本支动画的播放位置；
  拿它比长度会**立刻**判成"播完"。`hasAnimationFinished()` 只对 `PLAY_ONCE` 有效（见 §2.4 两条事实）。
- 📌 **2026-09-29 更正**：本节初稿写的是"用 `state.getAnimationTick() >= animation.length()`"，
  这是**错的**（理由即上一行），已改为"客户端 `tickCount` 计时"。计时挂在 **tick** 而非渲染帧上，
  因此**帧率无关** —— 这一点比 Dragon Survival 挂在渲染帧的 `AnimationTickTimer.java:21-24` 更稳。

> **副作用（须记住）**：用时间判定 ⇒ **做不出"定格在末帧"的表情**。
> 将来若确实需要，得用一条 `loop: true` 且尾部静止的动画来表达。

### 2.5 同步一个字符串：用原版现成的序列化器

- `EntityDataSerializers.STRING = EntityDataSerializer.forValueType(ByteBufCodecs.STRING_UTF8)`（原版字段）✓
- 原版先例：`MinecartCommandBlock.java` → `DATA_ID_COMMAND_NAME = SynchedEntityData.defineId(MinecartCommandBlock.class, EntityDataSerializers.STRING);`
- ⇒ 无需注册自定义序列化器（本项目已确认**模组不能注册**）✓

---

## 三、设计

### 3.1 两条正交轴

```
状态轴  state : idle | flying          ← 服务端权威，决定导航/重力/移动控制，落盘
表情轴  emote : <任意动画名> | 无       ← 纯动画覆盖层，不碰任何行为，不落盘
```

四条轴的对称性（每条都有"设定"与"反面"）：

| 轴 | 设定 | 反面 | 落盘 |
|---|---|---|---|
| 状态 | `state <t> idle\|flying` | —（`reset` 回 idle） | ✅ |
| 移动 | `move <t> <pos>` | `stop <t>` | ❌ 瞬态 |
| 攻击 | `attack <t> <victim>` | `attack <t> stop` | ❌ 瞬态 |
| **表情** | **`play <t> <动画名>`** | **`play <t> stop`** | ❌ **不落盘** |

**⚠️ 2026-09-29 实机后修订（用户裁定）：表情**不再**与状态/移动/攻击正交**
- `state` / `move` / `attack` **都会清掉表情**（且 `state` 那句放在幂等判断**之前** ⇒ 重复下发同一状态也清）。
  理由：表情整层盖住状态动画，玩家分不清"指令到底生效没有"。
- 代价（已确认接受）：**表情不能跨状态存在**，"坐着飞"这类组合不再可能。
- 仍然成立的只有一条：表情**不影响**寻路与飞行 ⇒ `move` 着播 `sit` 会"坐着滑行"（这仍是 D2 的取舍）。
- 能改变表情的入口现在是：`play` 换名、`play … stop`、`state`、`move`、`attack`、`reset`。

### 3.2 指令面

```
/beloong npc <targets> state <state>        ← idle | flying（只剩两态）
/beloong npc <targets> move <pos>
/beloong npc <targets> stop
/beloong npc <targets> attack <victim>
/beloong npc <targets> attack stop
/beloong npc <targets> play <动画名>         ← 新增
/beloong npc <targets> play stop            ← 新增
/beloong npc <targets> reset                ← 追加"清表情"
```

- `play` 的参数用 `StringArgumentType.word()`（单词、不含空格；现有 8 个动画名全是单词）。
- **`play` 没有名字补全** —— 动画名是**客户端资产数据**，服务端不知道。
  这与 `state` 用 `NpcState.NAMES` 补全形成对照，须在注释里写明理由。
- `play … stop` 与 `attack … stop` 同形：`stop` 落在"本该是实体参数"的位置，
  复用 `NpcCommand` 类注释里已有的歧义说明（原版 `/tag <targets> add|remove` 也是这形状）。

### 3.3 组件改动清单

| 组件 | 改动 |
|---|---|
| `NpcState` | 删 `SITTING`/`DANCING` 与 `isMovementMode()`；**`IDLE`=0 / `FLYING`=1 的 id 与名字全不变**（旧档 `"sitting"` 经 `byNameLenient` 回落 IDLE）；`NAMES` → `["idle","flying"]`；类注释重写（去掉"两族"表，**保留**"四种还原入口"表） |
| `NpcEntity` | ① 新增同步字段 `DATA_EMOTE`（`EntityDataSerializers.STRING`，`""` = 无表情）；② `emote()` / `setEmote(String)`（服务端守门，与 `setState` 同构）/ `clearEmote()`；③ **两个客户端瞬态字段**（不同步不落盘）：`emoteSeen` + `emoteDone`；④ `registerControllers` → `main` + `emote` **两个控制器**；⑤ 删 `sitAnimationName()`/`danceAnimationName()`、姿态分支、`exitPoseIfNeeded()`、攻击的状态门；⑥ `resetToDefault()` 追加清表情 |
| `NpcCommand` | 新增 `play <targets> <动画名>` 与 `play <targets> stop`；`reset` 顺带清表情；新增语言键调用 |
| `MoModel` | 覆写 `getAnimationResourceFallbacks()` → `mo.extra.animation.json`（约 3 行，照现有资源路径写法） |
| `DihuangLoongEntity` | 删 `sitAnimationName()`/`danceAnimationName()` 覆写（其 `sit`/`dance` 本就在自带单文件里） |
| `MoEntity` | 保留 `flyAnimationName()`；"`dancing` 会塌成 T-pose"那段注释改写为"`play dance` 由 extra 文件提供" |
| 资产 | `mo.animation.json` **已**删掉重复的 `sit`（现 5 条：`idle` `fly` `walk` `run` `attack`，2.11 MB）；`mo.extra.animation.json`（3 条：`sit` `dance` `descend`，2.27 MB）需入库 |
| 语言文件 | 删 `beloong.npc.state.sitting` / `.dancing`；加 `beloong.command.npc.play` / `.play.stop`（en_us + zh_cn） |
| 文档 | 本文件；旧状态文档加"`sitting`/`dancing` 已被表情系统取代"的注记 |

### 3.4 数据流

**主流 1 —— `/beloong npc @e play dance`（循环型 ⇒ 持续）**

```
① 服务端  指令 → NpcCommand.play() → 对每个目标 setEmote("dance")（if (!level().isClientSide) 守门）
② 原版    entityData.set(DATA_EMOTE,"dance") → 自动同步给跟踪者 + 后来者
③ 客户端  emote 谓词：emote()≠emoteSeen ⇒ 重置 emoteDone=false、emoteSeen="dance"
                     预检 getAnimation(this,"dance")!=null ✓
                     loopType()==LOOP ⇒ setAndContinue(thenLoop("dance")) ⇒ CONTINUE
          main  谓词：emote 非空 ⇒ controller.stop(); return STOP        ← 交出全身
④ 客户端  AnimationProcessor 按注册序 tick：main 先（已停，不写骨骼）→ emote 后（写骨骼）
⑤ 终止    play … stop / reset / 换名 ⇒ 字段变 ""
          ⇒ emote 谓词 forceAnimationReset(); STOP
          ⇒ main 谓词恢复 ⇒ 下一帧自动重选 idle/fly                     ← 零"恢复"代码
```

**主流 2 —— `play attack`（一次性 ⇒ 时间判定后自动回落）**

前 ③ 步同流程，但 `loopType() != LOOP` ⇒ `setAndContinue(thenPlay("attack"))`；
之后每帧在 emote 谓词里比较 `npc.tickCount - emoteStartTick` 与 `animation.length()`（单位 tick），
超过即置 `emoteDone = true`（**只改客户端本地，不回写同步字段**）⇒ 之后谓词直接 STOP、`main` 恢复。

**特殊情形：**

| 情形 | 行为 |
|---|---|
| 跨轴组合（R4） | **已作废**：`state`/`move`/`attack` 都会清表情 ⇒ 不再有"坐着飞" |
| 表情 + 移动/攻击（D2） | `move`/`attack` 照常执行，动画层被表情占着 ⇒ "用坐姿走路" |
| 名字不存在（D4） | 预检失败 ⇒ 什么都不播、**不动 `emoteDone`**、**不做负缓存**（每帧重查，自愈） |
| 重登/读档（D5） | 表情不落盘 ⇒ 新实体 `DATA_EMOTE=""` ⇒ 直接是状态动画 |
| 中途加入的观察者 | 同步字段里若留着一次性表情名，他会**从头再播一遍**（已接受；与 DS 同性质） |
| Mo 的多文件查找 | `play descend` ⇒ 主文件没有 ⇒ fallback（extra）命中；`play idle` ⇒ 主文件命中、**不去** extra ⇒ **同名主文件优先** |
| 资源重载 F3+T | `GeckoLibCache` 整体重建 ⇒ 客户端重新预检 ⇒ 自然跟上新资产 |

### 3.5 生命周期判定（汇总）

| 资产 `loop` | `LoopType` | 行为 | 判据 |
|---|---|---|---|
| `true` | `LOOP` | 持续到 `play stop`/`reset`/换名 | — |
| `false` | `PLAY_ONCE` | 播完自动回落 | `hasAnimationFinished()`（快路径）或时间判定 |
| `"hold_on_last_frame"` | `HOLD_ON_LAST_FRAME` | ⚠️ **实测：完全不播** | **不要用这种写法表达一次性**（见下） |
| **`loop` 字段缺失** | `PLAY_ONCE` | 播完自动回落 | `Animation.java:69-71`：`json == null ⇒ PLAY_ONCE` ⇒ 控制器正常进 `STOPPED`，`hasAnimationFinished()` 可用 |

> ### ⚠️ 2026-09-29 实机结论：`"loop": "hold_on_last_frame"` 的动画**完全不播**
>
> 末的 `attack` 原先用的就是这个值，实机表现为「**没有任何动画**」；把该字段**删掉**（⇒ `PLAY_ONCE`）后
> 立刻正常播出 —— 日志侧的铁证：`emote start name='attack' lengthTicks=30` @17:12:57.848，
> `emote finished name='attack'` @17:12:59.320，即 **1.47 秒**后按 30 tick 的长度正常收工。
>
> **机制未查明**（诚实标注）：`Animation.java:43-47` 显示它把控制器设成 `PAUSED` 并返回"再播一次"，
> 推测与"控制器被暂停后不再写骨骼 ⇒ 走 GeckoLib 复位分支"有关，但本次**没有验证**。
> （这条与"两个控制器同时 STOP ⇒ 全骨骼吸附初始快照"可能是同一个机制 —— 那条已由代码审查证实。）
>
> ⇒ **规矩**：**一律不要用 `hold_on_last_frame` 表达"只播一遍"**。
>
> ✅ **正确写法：删掉 `loop` 字段**（或写 `"loop": false`）⇒ `PLAY_ONCE`。
> 本项目 `mo.animation.json` 的 `attack` 与 `mo.extra.animation.json` 的 `descend` 现已**全部**改为该写法，
> 于是**当前资产里没有任何动画使用 `hold_on_last_frame`**。
>
> 📌 **一并核实（排除误因）**：那次提交还顺手把 `attack` 里约 197 个时间键从 `"1"` 改写成 `"1.0"` ——
> **这不影响任何行为**：`BakedAnimationsAdapter.java:273` 是
> `NumberUtils.isCreatable(timestamp) ? Double.parseDouble(timestamp) : 0`，两种写法都合法且等价。
> ⇒ 修复原因**只是** `loop` 字段，不是格式化。

> **一次性动画的完成判据只有一条**：`npc.tickCount - emoteStartTick >= Animation.length()`（单位 tick，客户端自记）。
> `hasAnimationFinished()`（`AnimationController.java:330-332`）只对 `PLAY_ONCE` 成立，可作快路径，**不能当通用判据**。

### 3.6 错误处理与**两条防线**

| # | 失败模式 | 处理 |
|---|---|---|
| 1 | `play` 了不存在的动画名 | 服务端无法校验 ⇒ 指令**报成功**（措辞中立，**不声称存在**）；客户端预检失败 ⇒ 不播、状态动画照旧、**不写日志** |
| 2 | 客户端缺该动画（版本不一致） | 同一预检兜住 |
| 3 | 名字在主文件与 extra **都有** | 主文件优先 ⇒ extra 那条**静默失效**；靠"删重复项 + **交集探针**"防住 |
| 4 | **extra 文件缺失或路径写错** | ⚠️ `GeoModel.java:160-165` 在**文件**缺失时**是抛异常**（不是返回 null），而**预检本身**就会调它 ⇒ **预检必须 try/catch**，否则一个路径笔误会变成**每帧抛异常**；另加"路径与实际文件名逐字符一致"探针 |
| 5 | `play stop` 时本就没表情 | 幂等：无条件清 + 报成功（与 `attack … stop` 一致） |
| 6 | 目标含非 NPC / 空目标 | 复用现有 `npcsIn(targets)` 与 `fail(source)`，不新增逻辑 |
| 7 | 服务端同 tick 多指令竞态 | 字段只有一个 ⇒ 后写者胜 |
| 8 | `reset` 漏清表情 | `resetToDefault()` 追加清表情（第四样） |
| 9 | 旧存档兼容 | `BeloongState="sitting"/"dancing"` ⇒ 回落 IDLE，**不坏档**；`DATA_EMOTE` 不落盘 ⇒ 无缺键问题。代价：旧档里坐着的 NPC 重登后**站起来**（已接受） |
| 10 | 同步开销 | 短字符串，仅变化时发包 |
| 11 | 名字带空格/特殊字符 | `word()` 不接受 ⇒ Brigadier 报错；将来若需要改 `string()` |
| 12 | 未知名字每帧重查 | **刻意**不做负缓存（为自愈），代价几次哈希查找 |
| 13 | ⚠️ **失败曾被完全静默** | 2026-09-29 实机教训：末的 `attack` 不播，日志里既无 GeckoLib 的 ERROR 也无我们的 WARN ⇒ 无法区分"名字拼错"与"预检链路坏了"。现在 `EmoteAnimationLookup` 对**每种失败**都打一条**英文 WARN**（渲染器不是 `GeoEntityRenderer` / 无 `GeoModel` / 名字找不到 / 查询抛异常），按动画名去重；表情启停另打 `debug`。**本项目日志一律纯英文**，已由探针 B14 守着 |

```
防线一（运行时）：客户端预检 + try/catch      ⇒ 未知名字/坏路径都不会把异常或脏日志带进渲染管线
防线二（静态）：  交集探针 + 路径一致性探针    ⇒ 把"静默失效"提前到构建期暴露
```

### 3.7 多文件合并（仅末）

`MoModel.getAnimationResourceFallbacks()` 返回 `mo.extra.animation.json`；
`animations/` 下所有 json 由 GeckoLib 自动烘焙；同名主文件优先。
⇒ 两文件构成"主 + 扩展"的**逻辑合并**，查找代价是 1~2 次哈希查找。

---

## 四、决策记录

### 本次 brainstorming 的裁定（用户逐条选择）

| # | 决策 | 理由 |
|---|---|---|
| **D1** | 表情生命周期**尊重资产的 `loop` 字段**，但"播完"用**时间判定**（非 `hasAnimationFinished()`） | `hold_on_last_frame` 不进入 `STOPPED`（§2.4），若不改判据，`attack`/`descend` 会永久定格 |
| **D2** | 表情**只覆盖动画，不管移动** | 与 R4"任意组合"一致；实现最简、两轴正交；代价是"坐着滑行"，有意接受 |
| **D3**（**2026-09-29 实机后推翻**） | ~~只有 `play … stop`（与 `reset`）能终止表情~~ ⇒ 改为 **`state`/`move`/`attack` 也都清表情**，且 `state` 那句在幂等判断之前 | 玩家分不清"指令是否生效"；代价是"坐着飞"不再可能 |
| **D4** | 不存在的动画名 ⇒ **客户端预检、静默不播** | 与 DS 的 `doesAnimationExist` 一致；避开 GeckoLib 的 `ERROR + 堆栈`；代价是拼错无反馈 |
| **D5** | 表情**不落盘** | 它是纯表现层；代价是重登后坐着的 NPC 站起来（相对今天 `state sitting` 是退化，已知并接受） |
| **D6** | 采用**路径 A：双控制器 + 后注册覆盖** | DS 在同族引擎上验证过；回归隐式、无需保存"原动画"；给将来的局部/blend 表情留位置 |

### 继承的既有决策（不变）

- 四种还原入口行为各不相同（`byId` / `byNameLenient` / `byNameStrict` / `getSerializedName`）——**严格与宽松不能合并**。
- 写存档写**名字**而不是 ordinal。
- 状态落盘（受命态活过重登）。

### 本次新记住的库级事实

- 多控制器**后注册者胜**（`AnimationProcessor.java:80,107-131`、`AnimatableManager.java:202-208`）。
- `hold_on_last_frame` = 设 `PAUSED` + 返回"再播一次"（`Animation.java:43-47`）⇒ `hasAnimationFinished()` 对它恒 false。
- `getAnimationResourceFallbacks` 是 GeckoLib **原生**的多文件机制（`GeoModel.java:76-84`）。
- `GeoModel.java:160-165`：**动画文件**缺失时**抛异常**（名字缺失只是返回 null）⇒ 任何"存在性预检"都要 try/catch。
- `Animations/` 下所有 json 在资源重载时被**全量烘焙**（`GeckoLibCache.java:113-133`）⇒ 拆文件**不省内存**。

---

## 五、非目标（YAGNI）

| 非目标 | 理由 |
|---|---|
| **局部/blend 表情**（只覆盖部分骨骼） | DS 有，但我们没有需求；路径 A 已给将来留了位置 |
| **多个表情槽 / 同时播多条** | 单槽（一个 `DATA_EMOTE`）足够；DS 的 4 槽还会静默丢弃 |
| **表情的时长 / 速度参数** | DS 的数据包有 `duration`/`speed`，我们不做；变速还会牵出 `tickOffset` 私有字段的移植风险 |
| **表情落盘** | D5 裁定 |
| **自定义网络包** | 只用原版实体数据同步；D4 也因此选择"不做聊天栏回报" |
| **服务端校验动画名** | 服务端不可能知道客户端资产；除非引入白名单（会违背 R4"任意动画"） |
| **把 `descend` 做成"降落状态"** | 它是 extra 里的一条普通表情动画，`play descend` 即可 |

---

## 六、验证策略与已知残留风险

### A. 可静态证明的

| # | 探针 | 证明什么 |
|---|---|---|
| 1 | `.\gradlew.bat build --console=plain` | 编译通过 |
| 2 | **资产探针** | 主 = `{idle,fly,walk,run,attack}`、extra = `{sit,dance,descend}`、**交集为空**；JSON 合法；`MoModel` 的 fallback 路径与实际文件名**逐字符一致**；打印每条的 `loop` 值（将来新加的非循环动画会被暴露出来，逼人确认语义） |
| 3 | **枚举探针** | 只剩 2 个常量、`NAMES=["idle","flying"]`、**`IDLE=0`/`FLYING=1` 未变** |
| 4 | **指令面探针** | 沿用"有序字节码常量"法：六个子命令齐全、`play` 下有参数与 `stop` |
| 5 | **语言键探针** | 两语言键集合一致；`state.sitting`/`.dancing` 已消失；两个新键已存在 |
| 6 | **死代码探针** | `isMovementMode`/`exitPoseIfNeeded`/`sitAnimationName`/`danceAnimationName`/`SITTING`/`DANCING` 全仓零命中 |
| 7 | **同步字段探针** | `DATA_EMOTE` 用 `EntityDataSerializers.STRING`、默认 `""`；且**不出现**在 `addAdditionalSaveData`/`readAdditionalSaveData`（证明不落盘） |
| 8 | **控制器顺序探针** | `javap -c -p` 反编译 `registerControllers`：两次 `ControllerRegistrar.add` 的调用顺序为 `"main"` 在前、`"emote"` 在后 |
| 9 | 生命周期分支探针（尽力而为） | 反编译 emote 谓词，确认引用了 `tickCount` 计时与 `Animation.length`（**防止后人退回用 `getAnimationTick`**） |

### B. 只能实机确认的

| # | 操作 | 期望 |
|---|---|---|
| 1 | `play sit` | 坐下并**保持** |
| 2 | `play dance` | 跳舞并保持 |
| 3 | `play attack` | **打一遍后自动回到** idle/walk（D1 时间判定） |
| 4 | `play descend` | 11.25 秒后自动回落 |
| 5 | idle 态 `play fly` | 跨状态播放成立（R4） |
| 6 | `state flying` + `play sit` | **表情被清掉**（新 D3），且 `state` 重复下发同样清 |
| 7 | `move` + `play sit` | **表情被清掉**（新 D3）；若在 `move` 之后再 `play sit`，才是"坐着滑行" |
| 8 | `play stop` | 立刻回落，过渡自然 |
| 9 | `reset` | 同时清掉移动 + 攻击 + 状态 + 表情 |
| 10 | `play 不存在的名字` | 无变化、无报错、**日志无 ERROR/堆栈**（D4） |
| 11 | A 端 `play attack`，播完后 B 端加入 | B 会**从头再播一遍**（已接受），播完能回落、不崩 |
| 12 | `play sit` 后退出重进 | 站起来（D5） |
| 13 | 旧档（`BeloongState="sitting"`）载入 | 回落 idle、不崩 |
| 14 | 表情在播时 F3+T | 不崩、继续播 |
| 15 | 地黄龙走 1/3/8 | 它的 `sit`/`dance` 在自带单文件里 ⇒ `play sit` 生效 |

### C. 已知残留风险（记录，不修）

1. 中途加入的观察者会重播一次性表情（D5 的必然结果）。
2. 旧档里坐着的 NPC 重登后站起（D5 的必然结果）。
3. `play` 无补全、拼错无任何反馈（服务端不知道资产）。
4. 名字带空格无法播放（`word()`）。
5. 未知名字每帧重查（**刻意的**自愈设计）。
6. **版本敏感点**：一次性判定依赖 `Animation.length()` 的**单位是 tick**（`BakedAnimationsAdapter.java:59`）；
   将来升 GeckoLib 时若该单位或计时方式变化，`attack`/`descend` 的自动回落会失准（过早回落或定格）⇒ 升级清单须列此条。

---

## 七、改动清单（预计）

1. `NpcState.java` —— 收缩到两态、删 `isMovementMode()`、重写类注释。
2. `NpcEntity.java` —— 同步字段 + 表情 API + 两个客户端瞬态字段 + 双控制器 + 删姿态机制。
3. `NpcCommand.java` —— `play` / `play stop` / `reset` 追加清表情。
4. `MoModel.java` —— `getAnimationResourceFallbacks()` 覆写。
5. `DihuangLoongEntity.java` / `MoEntity.java` —— 删覆写、改注释。
6. `en_us.json` / `zh_cn.json` —— 删 2 键、加 2 键。
7. 资产入库：`mo.extra.animation.json`；`mo.animation.json` 的 `sit` 删除（**已完成**）。
8. 旧状态设计文档加取代注记；`memory/decisions-log.md` + `memory/learned-patterns.md` 追加。

---

## 八、下一步

调用 `planning` 技能，把本设计拆成可执行的任务清单（含静态探针的**具体命令**与实机验收清单）。
