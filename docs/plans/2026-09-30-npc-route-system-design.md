# NPC 自动寻路系统 设计文档

**日期**：2026-09-30
**状态**：**已实施**（用户逐节确认 §1 架构、§2 组件、§3 数据流、§4 错误处理后按计划落地；**实机验收待用户执行**，见 §5 的 9 项清单）
**方案**：**薄 Goal + 现有命令层**（Goal 只占 `Goal.Flag.MOVE`、优先级 4；寻路仍由既有 `tickMoveCommand` 负责）

---

## 一、问题陈述

整合包侧使用**固定地图**（龙宫维度由 Lockdown 模组挂载 ✓），因此 NPC 需要**自动走到固定坐标**，
把玩家一路引导过去 —— 这是"向导"这一玩法的骨架。

**目标**：数据驱动的**路线图**（维度 + 多个路点）；玩家完成某段 ChatBox 对话后，把路线**指派**到某个 NPC 身上
并落盘；NPC 沿路线自动寻路，**除 `stop` 指令外不会停**，直到抵达终点。

**给谁用**：整合包作者 —— 路线写在 JSON 里，指派由对话触发，改坐标不动代码。

**首个实战内容**（用户给定，已核对）：
ChatBox 那段「末」的对话结束后 ⇒ 指派 `beloong:mo_route_1` ⇒ 末在 **`beloong:loong_palace`** 里从
`0,64,-8` 走到 `-2,78,-55`（6 个路点，累计 50.5 格、爬升 14 格）。

---

## 二、已核实的既有事实（全部附行号）

### 2.1 移动与持久化（本系统的地基，**本次未被任何改动触及**）

| 事实 | 依据 |
|---|---|
| `moveTo(Vec3)` 是**唯一移动入口**，**只登记目标**（`setMoveTarget`），不直接下发路径 | `NpcEntity.java:581-592` |
| ⚠️ 它**第一句就 `clearEmote()`** | `:590`（⇒ 路线不能每 tick 调它，见 §C12-③） |
| `tickMoveCommand()` 每 **20 tick**（`MOVE_REPATH_INTERVAL`）重发一次 `getNavigation().moveTo(x,y,z,1.0)` | `:1122-1144`、`:389` |
| ⚠️ 寻路探索半径 = **`FOLLOW_RANGE`（默认 16）**，且项目**刻意没调大**（它还决定节点预算 `floor(FOLLOW_RANGE*16)`） | `PathNavigation.java:151`、`NpcEntity.java:1070-1083` |
| `stopMoving()` 只清移动目标（不碰状态/攻击） | `:602-607` |
| `attack()` 会 `clearMotionCommands()`（⇒ 会清移动目标，但**不该**清路线） | `:617-630`、`:627` |
| 持久化：NBT 里目前只有一个字符串键（`BeloongState` 前缀风格、存名字不存 ordinal、读盘经 `setState`、`byNameLenient` 回落） | `:978-1017` |
| goal 优先级：`0` FloatGoal ｜ **`3` `NpcAttackGoal`（`MeleeAttackGoal` 子类，占 MOVE+LOOK）** ｜ `5` `LookAtPlayerGoal`（占 LOOK） | `:519-524` |
| `GoalSelector` 用 `lockedFlags` 仲裁：低优先级 goal 与运行中的高优先级 goal **有 Flag 交集就无法启动** | `:499-505` |
| `customServerAiStep()` 在 `goalSelector.tick()` 与 `navigation.tick()` **之后** ⇒ "本方法续的路不会被任何 goal 的拆解抹掉" | `:530-536`、`:1089` |
| `IDLE` / `FLYING` **都是移动模式**：切换时换导航与移动控制、但**保留移动目标** | `NpcState.java:35-46` |

### 2.2 ChatBox 侧（指派入口）

| 事实 | 依据 |
|---|---|
| 选项的 `click` 由**服务端**以**玩家本人**身份、权限等级 2 执行 ⇒ **不需要 op** | `ComponentEvent.java:149-153` |
| 支持 `;` 分隔多条命令 | `ComponentEvent.java:134-139` |
| ⚠️ **不以 `execute` 开头的命令会被 pluginHelper 整个截走**（本项目运行日志里确有 KubeJS 的 chatbox 插件） | `ComponentEvent.java:145-146`（即既有审查的 I-5，用户选 F2「只留痕」） |
| ⚠️ **页级 `renderEvents` 收不到 `ON_END`**（屏幕只 fire `ON_START/ON_CLICK/TICK/CHECK`）⇒ 发放/触发只能挂在**组件级事件**或**选项 click** 上 | `ChatBoxUtil.java:184`、`ChatBoxScreen.java:196-199`、`DialogBox.java:82-83` |

### 2.3 CG 系统（2026-09-30 新增，**与路线零技术冲突**）

| 事实 | 依据 |
|---|---|
| CG = 服务端一次性下发 + **纯客户端演出**，服务端**零运行期状态**、**零落盘**、**零新增 payload** | `cg/CgAnimation.java:140-242`、`design.md:497-504` |
| 对实体**唯一**的写是 `npc.setEmote(animation)`；**不**移动/传送/改状态/碰 NBT/碰寻路 | `CgAnimation.java:183`（全包无 `moveTo`/`setPos`/`getNavigation`） |
| 隐身上给**观察者玩家**（进玩家效果 NBT），不上给末 | `CgAnimation.java:201-220` |
| ⚠️ 相机轨迹在触发那一刻**烘成世界坐标**、**无法跟随移动实体** | `MoEntrance.java:63-68` |
| `cg/` 包内**零 goal、不占 `MOVE`** ⇒ 与路线的 goal 优先级无冲突 | 全包检索 |

---

## 三、设计

### 3.1 架构（§1）

**一句话**：路线是**挂在 NPC 身上的任务状态**（路线名 + 下标，落 NBT）；一个**只占 `MOVE` 的薄 Goal**
把"当前路点"喂给**现有的移动命令层**；"何时走/何时让位/何时停"全部交给**原版 goal 仲裁**。

| 层 | 职责 |
|---|---|
| **数据层** `data/beloong/beloong/npc_route/<名>.json` | 维度 + 路点序列（+ 可选抵达半径）；只读、支持 `/reload` |
| **实体状态**（NBT 两键） | **唯一**的状态存储 ⇒ "只有一个路线"由"只有一个键"**结构性保证** |
| **仲裁 + 驱动** | 薄 Goal（只占 `MOVE`，优先级 4）+ 既有 `tickMoveCommand()`（寻路一行未改） |

**决策清单（D1–D11）**

| # | 决策 |
|---|---|
| D1 | NPC 行为**全局共享**（一个实体一个位置）；只按玩家记录"谁触发的" |
| D2 | 写入入口 = **ChatBox 最后一颗选项的 `click`** 调 `/beloong route <名>`；目标 NPC 由"**该玩家最近对话过的 NPC**"映射决定（右键时记录；ChatBox 界面开着时不可能右键别的 NPC ⇒ 映射必然新鲜） |
| D3 | 偏离路线 ⇒ **只重新寻路**，不瞬移、不自愈；走不回去就一直试，直到 `stop` ；**走不回去就一直试**这一点原先与移动层冲突：`tickMoveCommand` 有"连续 5 次续路无进展即放弃"的有界失败（`NpcEntity.java:1261-1272`）会清空 `moveTarget` ⇒ 2026-09-30 由 goal 的**目标补发**化解（见 §3.2 的 C6 与文末修复记录）|
| D4 | 维度不匹配 ⇒ **挂起**（不寻路、路线保留、状态/动画都不动）；回到该维度 ⇒ 从**当前下标**继续 |
| D5 | 抵达终点 ⇒ 停下 + **保留**路线（"已完成"由**下标越界**表达）；重新指派同一条 ⇒ **重走一遍** |
| D6 | `stop` ⇒ **取消路线**（清 NBT）；`reset` 同样；`attack` **不清**。只有"有路线/无路线"两态 |
| D7 | 攻击期间**暂停**路线、打完自动继续 —— 由攻击 goal 与路线 goal **同占 `MOVE`** 天然互斥免费给出 |
| D8 | NBT 存**路线名 + 下标**（JSON 是唯一事实源）；文件缺失 ⇒ 挂起 + 每名字一次英文 WARN |
| D9 | 驱动器 = **只占 `MOVE` 的薄 Goal**（优先级 **4**，排在攻击 goal 的 3 之后）+ 既有 `tickMoveCommand` 续路 |
| D10 | （推论）**路线上 `move` 指令无效** —— 路线 goal 每 tick 会把 `moveTarget` 改回当前路点；这是刻意的（"除 `stop` 不然一直走"），会写进注释免得当 bug |
| D11 | （推论）路线**不改变状态**：地面态就走、飞行态就飞 —— "怎么走由状态决定"这条既有哲学不变 |

### 3.2 组件（§2）

| # | 组件 | 内容 |
|---|---|---|
| C1 | `data/beloong/beloong/npc_route/mo_route_1.json` | 见 §3.3 的 JSON（维度 + 6 路点 + `arrival_radius`） |
| C2 | `NpcRouteLoader`（新，服务端 reload listener） | 照 `NpcDialogueLoader`（`SimpleJsonResourceReloadListener`，目录串 `beloong/npc_route`）；单文件坏只丢该文件 |
| C3 | `NpcRoute` 数据记录（新） | `dimension` + `waypoints`（`List<Vec3>`）+ `arrivalRadius`（缺省 2.0） |
| C4 | `NpcEntity` 的两个 NBT 键 | 名字 + 下标；键名照既有 **`Beloong*` 前缀风格**；读盘**下标 clamp 到 [0, size]**（对应 `byNameLenient` 的宽容口径） |
| C5 | `NpcEntity` 的路线 API | `route()` / `setRoute(rl)`（下标归零）/ `clearRoute()` / `routeFinished()`；只在服务端生效 |
| C6 | `entity/ai/NpcRouteGoal`（新，**只占 `MOVE`**，优先级 **4**） | `canUse()`＝有路线 ∧ 已加载 ∧ 维度匹配 ∧ 未抵达；`start()`/`tick()`＝按下标推进并把当前路点交给移动命令层；`stop()`＝**只停寻路、绝不清路线** |
| C7 | 命令面（**两条**，见下） | `/beloong npc <targets> route <名>`（手工/验收）＋ `/beloong route <名>`（ChatBox 调用，按玩家定位） |
| C8 | "最近对话的 NPC"映射 | 在 `NpcDialogueHandler`（唯一入口）里记：`玩家 UUID → NPC UUID`；解析先查玩家当前维度、再兜底全维度 |
| C9 | `stop` / `reset` 的命令层 | 除 `stopMoving()` 外**再清路线**；⚠️ 清路线**不放进** `stopMoving()`（否则 `attack()` 的 `clearMotionCommands()` 会误伤路线） |
| C10 | 语言键 | 照 `beloong.command.cg.*` 的风格（`beloong.command.route.*`），回报/报错各若干条 × 两语言 |
| C11 | 注释/文档/memory | 必写：D10（`move` 无效）、D6（`stop` 才终止）、C6 的"`stop()` 不清路线"、C12 的两条硬限制 |
| C12 | **CG ↔ 路线的关系**（写进文档） | ① 服务端**无法感知** CG ⇒ "路线让位给 CG"**做不到** ⇒ 只能靠**内容顺序**（登场 CG → 对话 → 路线）；② CG 相机**无法跟随移动实体** ⇒ "**边走边演不成立**"；③ `moveTo()` 会 `clearEmote()` ⇒ 若 CG 正在放 `descend`，路线一开动就掐掉它 |
| C13 | 借 CG 的**形态糖**（不借它的状态观） | 复用：静态保序表 + `names()` 喂 Brigadier 补全 + 按名查找 **fail-closed**、`word()+suggests`、命令层**逐条明确报错不静默**；**不要**学它的"零状态/纯客户端演出" |

**C7 的两条命令（为什么是两条）**：既有形状是 `/beloong npc **<targets>** <动作> …`，`npc` 之后第一个节点
就是实体参数 ⇒ `/beloong npc route <名>` 会被当成"把 `route` 解析成实体"而报错。因此：

| 命令 | 谁用 | 语义 |
|---|---|---|
| `/beloong npc <targets> route <名>` | 手工/调试（admin、验收） | 指派给**指定** NPC，与既有命令轴对称 |
| `/beloong route <名>` | **ChatBox**（玩家身份、权限 2） | 指派给"**该玩家最近对话过的 NPC**"；命令方块/控制台执行 ⇒ **报错** |

### 3.3 数据流（§3，六条路径）

**路线 JSON（C1）**
```json
{
  "dimension": "beloong:loong_palace",
  "waypoints": [ [0,64,-8], [0,64,-20], [0,69,-31], [0,74,-41], [0,78,-50], [-2,78,-55] ],
  "arrival_radius": 2.0
}
```
**路点间距已核**（硬约束：相邻点须落在探索半径 ~16 格内）：`12.00 / 12.08 / 11.18 / 9.85 / 5.39` ✓ 全部合格；
总长 50.5 格、累计爬升 14 格（三段各上 4~5 格、水平进深 11/10/9 格 ⇒ 需真实楼梯/坡道）。

**路径 1：指派**
```
[1] 右键末 → NpcDialogueHandler：发 NpcDialogueOpenPayload；写 lastDialogueNpc[玩家UUID]=末UUID
[2] 点回复 → C2S → 服务端复检 → ChatBoxBridge ⇒ 进入 ChatBox 那段对话
[3] 最后一页文字播完 ⇒「好的」出现 ⇒ 玩家点它 ⇒ ChatBox 以玩家身份执行两条命令：
       advancement grant @s only beloong:npc/1_1
       beloong route beloong:mo_route_1
[4] 服务端主线程：查映射得末 ⇒ 查 NpcRouteLoader（不存在 ⇒ 报错、不写）
       ⇒ 写 BeloongRoute="beloong:mo_route_1"、BeloongRouteIndex=0
       ⇒ 维度不匹配则另打一条英文 WARN
[5] 下一 tick ⇒ NpcRouteGoal.canUse 通过（攻击 goal 未运行 ⇒ lockedFlags 无冲突）
```

**路径 2：正常走完**
```
每 tick（服务端，goalSelector.tick 之内）：canUse = 有路线 ∧ 已加载 ∧ 维度匹配 ∧ 下标 < 路点数
  tick：若 到当前路点水平距离 ≤ arrivalRadius ∧ |Δy| ≤ 2 ⇒ 下标 +1（写 NBT）
        仅在**下标变化时或 start() 时**调 moveTo(当前路点)     ← 关键：避免每 tick clearEmote
同一 tick 稍后（customServerAiStep，在 navigation.tick 之后）：
  tickMoveCommand：冷却到期且未达 ⇒ getNavigation().moveTo(moveTarget, 1.0)   ← 既有机制，一行未改
```

**路径 3：服务器重启**
```
存/读两个键（存名字不存 ordinal；读盘经 setter + 下标 clamp）
首次 tick ⇒ start() ⇒ moveTo(当前路点) ⇒ 从**断点**继续（这正是下标必须落盘的原因）
```

**路径 4：被传送到同维度别处** —— 零代码介入：goal 不推进下标；`tickMoveCommand` 每 20 tick 用当前位置重新寻路
⇒ 自然"重新走过去"；不可达则一直重试（D3）。

**路径 5：跨维度** —— `canUse` 因维度不匹配返回 false ⇒ goal 停（只停寻路、不清路线）⇒ 回到该维度自动从当前下标继续（D4）。

**路径 6：抵达终点** —— 下标 == 路点数 ⇒ `canUse` 永假 ⇒ 停下；NBT 保留（"已完成"＝下标越界）；重新指派 ⇒ 重走。

---


### 修复记录（2026-09-30 实机：tp 后永久停住）

**现象**：龙宫内寻路途中把 NPC `/tp` 到同维度别处 ⇒ **永久停住**；**重新指派路线也无效**；`reset` 后再指派才恢复。

**根因（两层都以为自己拥有 `moveTarget`）**：
1. `NpcEntity#tickMoveCommand()` 有一条**有界失败**：连续 `MOVE_MAX_NO_PROGRESS`（5）次续路都没有更靠近目标
   ⇒ 判定不可达，把 `moveTarget` 清成 `null` 并停导航（DEBUG 日志 `npc move target unreachable, giving up at … for …`）。
   对一次性的 `move` 指令这是**正确**设计 —— 但路线层并不知道。
2. `NpcRouteGoal` 当时**仍在运行**（`canUse` 为真：有路线、维度对、下标未越界），而原版
   `GoalSelector` **不会**再调它的 `start()` ⇒ 目标再也没有任何一处会下发。
   `setRoute` 只把下标归零（goal 从未停止）⇒ "重新指派也没用"；`resetToDefault` 走 `clearRoute()`
   ⇒ `canUse` 变 false ⇒ goal **真的停掉** ⇒ 再指派才会 `start()` ⇒ "reset 后恢复"。三个症状全部吻合。

**修复**：`NpcRouteGoal.tick()` 增加第 3 种下发时机 —— **目标已不属于我们时补发**
（`moveTarget() == null` 或它已不等于当前路点）。顺带带来两个想要的性质：
- **自愈**：被 tp / 被有界失败放弃 / 被 `move` 指令顶掉，三种情况都会自动恢复；
- **真正实现 D3**：`moveTo` 会重置那条有界失败的计数（`setMoveTarget` 清 `moveBestDistSqr`/`moveNoProgressCount`）
  ⇒ 每约 5 秒重试一轮 = "走不回去就**一直试**"。
  另外给抵达半径设了下限 `max(路线半径, groundArriveDistance() + 0.5)`：否则移动层先判"到位"并清目标、
  而 goal 判"没到"又补发 ⇒ 每 tick 打架（且每 tick 清一次表情）。

**证据**：`run/logs/debug.log` 19:34:58 —— `giving up at BlockPos{x=12, y=64, z=-5} for (0.0, 64.0, -8.0)`，
目标 `(0,64,-8)` 正是 `mo_route_1` 的第 0 个路点。


## 四、错误处理（§4）

| 情况 | 行为 |
|---|---|
| 指派时路线名不存在 | **拒绝**（不写 NBT）+ 命令报错（附**可用名单**） |
| 指派时维度不匹配 | 照写 + **英文 WARN**（挂起本身是 D4 的正确行为，但作者必须知道） |
| 查不到"最近对话的 NPC" | **拒绝** + 报错 |
| 命令由命令方块/控制台执行（`/beloong route`） | **报错** |
| 加载时路线文件不存在 | **挂起** + **每名字一次**英文 WARN |
| 加载时下标越界 | **clamp 到 [0, size]** + WARN（坏数据不崩） |
| 某个路线 JSON 解析失败 | 丢弃**该文件** + ERROR（不影响其它文件） |
| ⚠️ CG 正在放时路线开动 | **不做技术防护**（服务端无法感知 CG）⇒ 只能靠**内容顺序**；后果是 `descend` 被 `clearEmote` 掐掉 + 相机跟丢 |
| ⚠️ CG 相机无法跟随移动实体 | 文档写明"**边走边演不成立**" |
| 被困住 / 地形不可达 | 一直重试（每 20 tick 一次寻路），直到 `stop` |
| `stop` 之后 | 路线清空 ⇒ **重启也不会恢复** |

**"谁写哪个字段"**：`BeloongRoute*` ← `setRoute`/goal 推进/`clearRoute`；`moveTarget` ← `NpcRouteGoal`（下标变化时）+ 既有 `moveTo`；
`lastDialogueNpc` ← `NpcDialogueHandler`（每次右键）；`DATA_EMOTE` ← 路线仅在**换路点**时间接清一次（`moveTo` 的既有语义）。

---

## 五、验证策略（§5）

**静态**
1. `gradlew build` ⇒ BUILD SUCCESSFUL；
2. 既有三探针全绿（表情/攻击那套 S1/S2/S3）；
3. **新写 `route_invariants.py`**（比肉眼看 JSON 硬）：
   - 每个路线 JSON 可解析；`dimension` 是已注册维度；路点非空、**坐标合法**；
   - **相邻路点距离 ≤ 16**（探索半径硬约束）—— 这是本系统最容易踩、也最值钱的静态检查；
   - 路线 id ↔ 文件路径逐一对应；ChatBox 命令里的路线名与数据文件里的 `end_advancement` 同一套对齐方式；
   - 命令引用的路线名**真实存在**；语言键两语言齐全。

**实机清单（用户执行）**

| # | 操作 | 期望 |
|---|---|---|
| 1 | 在龙宫把末放到路线起点附近 ⇒ `/advancement grant @s only beloong:npc/root` ⇒ 右键末 ⇒ 走完对话 ⇒ 点「好的」 | 同时拿到 `1_1` **且**末开始沿 `mo_route_1` 前进 |
| 2 | 观察整条路线 | 依次经过 6 个路点（三段爬升靠真实楼梯/坡道 ✓），到 `-2,78,-55` 停下 |
| 3 | 途中 **重启服务器** | 末**从当前路点继续**，不会倒着走回 `0,64,-8` |
| 4 | 途中把它 `/tp` 到同维度别处 | 重新寻路走回去（不瞬移） |
| 5 | 把它 `/tp` 到别的维度 | 原地不动（挂起）+ 日志**一条**英文 WARN；送回龙宫后自动从当前路点继续 |
| 6 | 让它走到终点 | 停下；`/beloong npc @e[type=beloong:mo] route` 仍回报该路线；重新指派 ⇒ 重走一遍 |
| 7 | 途中下 `stop` | 停下且**路线被清**（重启也不恢复） |
| 8 | 途中下令攻击 | 攻击期间暂停、打完继续走（D7） |
| 9 | （反例）手工 `/beloong npc @e[type=beloong:mo] route 不存在的名` | **报错**并列出可用路线名，NBT 未被写入 |

**不做**：不新增测试源集（本项目无 `test`；验证 = 构建 + 探针 + 不变量脚本 + 实机）。

---

## 六、非目标

- 巡逻 / 循环路线；多路线排队或优先级（**"只有一个路线"是本系统的核心约束**）；
- **按玩家不同的路线**（一个实体一个位置 —— D1）；
- 路点上的动作（播表情 / 等待 / 发进度 / 执行命令）；
- NPC **自己跨维度**（挂起即 D4 的全部内容）；
- **"边走边演"**（CG 相机无法跟随；见 C12-②）；
- 失败检测与自愈（卡住就重试 —— D3 的明确取舍）；
- 路线的可视化调试 UI（验收靠命令回报 + 日志）。

---

## 七、下一步

调用 `planning` 技能，把本设计拆成可执行任务清单（含静态验证命令与实机验收清单）。

---

## 六、三系统串联（2026-09-30 新增）

把 **NPC 对话**、**寻路**、**CG 过场** 三个系统接成一条完整体验链：

| 步 | 发生什么 | 由谁实现 |
|---|---|---|
| 1 | 玩家在龙宫走进末所在区域 ⇒ 自动获得 `beloong:npc/root` | **原版** `minecraft:location` 触发器 + 坐标盒（`advancement/npc/root.json`） |
| 2 | 获得 `root` ⇒ 播放 `mo_entrance`（末的登场） | `cg/MoEntranceTrigger`（Java 事件；见下"为什么不用 reward function"） |
| 3 | 玩家上前右键末 ⇒ 我们的对话 ⇒ 点「这里是什么地方？」⇒ ChatBox 那段对话 | 既有对话系统 + ChatBox 桥 |
| 4 | ChatBox 最后一页点「好的」⇒ `1_1` + `beloong route beloong:mo_route_1` | ChatBox 选项的 `click`（两条命令，都带 `execute` 前缀） |
| 5 | 末沿 `mo_route_1` 走到终点 `-2,78,-55` ⇒ 停下 | 本系统的 Goal + 移动层 |

**`root` 的触发条件**（实证依据）：`CriteriaTriggers.LOCATION` 是 `PlayerTrigger`，
在 `ServerPlayer.doTick()` 里 **每 20 tick 轮询一次**（反编译：`tickCount % 20 == 0` ⇒ `CriteriaTriggers.LOCATION.trigger(this)`），
`minecraft:tick` 则在 `ServerPlayer.tick()` 里**每 tick**触发。
⇒ 用 `minecraft:location` + `player` 条件（维度 + 坐标盒）即可"玩家一进区域就发"，最快 1 秒内命中。

⚠️ **原版没有"附近存在某实体"这种条件** —— `player` 条件只能描述玩家自身（维度/坐标/光照/群系/方块）
⇒ 所以"看到末"是用"**走进末所在的区域**"近似的。对一段**镜头演出**而言这个近似更合适：
CG 会把玩家锁进电影模式，玩家不必正好盯着末。触发盒中心与路线起点 `0,64,-8` 重合（同一处场景），
由 `route_invariants.py` 的 ⑧ 守着"改了一边忘了另一边"。

**为什么"进度 ⇒ CG"用 Java 事件而不是 `rewards.function`**：
1. **权限**：进度 reward 以玩家命令源执行，而 `Entity#getPermissionLevel()` 的字节码是 `iconst_0`（返回 0）
   ⇒ 非 op 玩家跑不动 `/beloong cg`（它要求权限 2）；Java 处理器不经命令层，没有这个问题。
2. **失败可见**：mcfunction 里只能写 `@e[type=beloong:mo,…]`，找不到末就**静默失败**；
   Java 处理器能打一条英文 WARN，把"该放没放"留在日志里。

⚠️ **已知取舍（用户裁定）**：若获得 `root` 的那一刻附近确实没有末，**只打 WARN、不重试** ⇒
那种情况下玩家会拿到 `root` 却看不到登场演出。触发盒本就围绕末的站位，正常流程里两者同时存在。
