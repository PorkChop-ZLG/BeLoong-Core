# 通用 NPC 改用原版 AI 实施计划

**Date:** 2026-09-25
**Design:** [2026-09-25-npc-vanilla-ai-design.md](./2026-09-25-npc-vanilla-ai-design.md)
**Status:** **主体已执行（`23c2721`）并通过实机验证（用户确认全部通过）** —— T1–T8 全部完成并通过构建与静态探针；
T5（`RandomLookAroundGoal`）经 **R-impl-2 回退**（实机后用户裁定"改回默认不转动"）；
**剩余项见 §三**（R9 标定 / 两项被刻意推迟的工作）。
**Approach:** 移动与行为回归原版机制，只保留三项原版确实没有的自研。

> 本文件与设计文档配对。因为设计在敲定后即被执行，这里**同时充当这一轮的执行记录**：
> §一 是任务清单与完成状态，§三 才是真正待办的部分。

---

## 一、任务清单（T1–T8）

| # | 任务 | 文件 | 状态 |
|---|---|---|---|
| **T1** | 移速回到原版刻度：`MOVEMENT_SPEED` `0.1 → 0.3`；**删除** `navigationSpeedModifier()`；`walkTo` 档位固定 `1.0` | `entity/NpcEntity.java` | ✅ |
| **T2** | 取消奔跑状态：删 `runTo`、`moveTo(...,sprint)` 的 sprint 分支、`clearMotionCommands` 与 `customServerAiStep` 里的 `setSprinting`；`customServerAiStep` 只保留攻击指令失效兜底 | 同上 | ✅ |
| **T3** | `run` 动画改判据：移动中且 `getAttributeValue(MOVEMENT_SPEED) > getAttributeBaseValue(...)` ⇒ `run` | 同上 | ✅ |
| **T4** | 永不消失改用原版官方钩子 `requiresCustomPersistence()`，取代覆写 `isPersistenceRequired()` | 同上 | ✅ |
| **T5** | ~~新增原版 `RandomLookAroundGoal`，优先级 7~~ → **实机后撤销（R-impl-2）**：它会带动身体自主转动，与"默认不转动"冲突 | 同上 | ⛔ 已回退 |
| **T6** | **整体删除 `turn` 能力**：`entity/ai/NpcTurnGoal.java` 整个文件；`NpcEntity` 的 `turnTo` / `setFacing` / `getTurnTargetYaw` / `clearTurnTarget` / `turnTargetYaw` / `LOOK_AHEAD_DISTANCE` / `maxTurnPerTick()` | 同上 + 删除文件 | ✅ |
| **T7** | 命令与语言键收敛：`NpcCommand` 删 `turn` / `run` 子命令（剩 `walk` / `attack` / `stop`），删 `move` 的 sprint 分支与 `DoubleArgumentType` import；语言文件删 `beloong.command.npc.{turn,run}`（中英各 2 条） | `command/NpcCommand.java`、`assets/beloong/lang/{zh_cn,en_us}.json` | ✅ |
| **T8** | 构建 + 静态探针 | — | ✅（见 §二） |

**实现期附带**（写入设计 §3.8）：`facePlayerDistance()` 由 `public` 收回 `protected`；
`animationTransitionTicks()` 注释改为"动画之间的过渡"（现有三档）。

---

## 二、静态验证记录（已执行）

```
cd D:\Minecraft\BeLoong-Core-NPC
.\gradlew.bat build --console=plain        → BUILD SUCCESSFUL（仅 3 条既有 mixin 注解处理器告警）
```

| # | 探针 | 结果 |
|---|---|---|
| 1 | 全库搜 `turnTo｜setFacing｜NpcTurnGoal｜runTo｜turnTargetYaw｜navigationSpeedModifier｜maxTurnPerTick｜LOOK_AHEAD_DISTANCE` | **零命中** |
| 2 | 全库搜 `setSprinting` | **零命中** |
| 3 | `NpcEntity` 有 `requiresCustomPersistence()`（`:213`）、`MOVEMENT_SPEED, 0.3D`（`:160`） | ✅ |
| 4 | `registerGoals`：`FloatGoal(0)` / `NpcAttackGoal(3)` / `LookAtPlayerGoal(5)` / `RandomLookAroundGoal(7)` | ✅ 四个，全是原版（攻击 goal 除外） |
| 5 | 动画判据含 `getAttributeBaseValue(Attributes.MOVEMENT_SPEED)` 比较 | ✅ |
| 6 | `NpcCommand` 的 `Commands.literal`：`beloong` / `npc` / `walk` / `attack` / `stop` | ✅ 无 `turn` / `run` |
| 7 | 两个语言文件键集合一致（226 = 226），`beloong.command.*` 只剩 5 条 | ✅ |
| 8 | `git diff --stat HEAD -- beloong.mixins.json build.gradle` | **无输出** |
| 9 | 改动面 | `NpcEntity` / `NpcCommand` / 2 个语言文件 + 删 `NpcTurnGoal`（5 文件 +123/−334） |

---

## 三、剩余任务（真正的待办）

### T9 实机验收（**✅ 已通过 —— 用户确认"全部通过"，2026-09-25**）

| # | 操作 | 预期 | 结果 |
|---|---|---|---|
| 1 | `/beloong npc walk @e[type=beloong:dihuang_loong] ~ ~ ~8`，与玩家并排同向走 | 比玩家略慢（约九成），并排会缓慢落后 | ✅ |
| 2 | 给迅捷效果后让它走（`/effect give @e[type=beloong:dihuang_loong] speed 30 1`） | 播 `run`、速度变快；效果结束回 `walk` | ✅ |
| 3 | 静置观察 | 不位移 | ✅（**转动一项已按 R-impl-2 改为"完全不转"，需复核一次**，见下） |
| 4 | 玩家从侧面/背后走近 | 头先转、身体滞后跟上、最后整体面朝玩家 | ✅ |
| 5 | Tab 补全 `/beloong npc ` | 只有 `walk` / `attack` / `stop` | ✅ |
| 6 | `/beloong npc attack @e[…] <一头牛>` | 走过去、一下 100 伤害 | ✅ |
| 7 | 退档重进 / `/kill` / 重力（召唤后下落）/ 无敌 / 不可推动 | 无回归 | ✅ |

**唯一待复核的一项**（R-impl-2 的直接验收，一行即可确认）：
**静置数分钟，NPC 是否完全不自行转动**（朝向保持不变）。改前它会间歇性转身。

### T10 R9 标定：模型 y 偏移（**待你一句话确认**）

- **症状**：模型最低点在 `y ≈ −0.88` 格（原点不在脚底）；首版它不受重力所以看不出来，现在会真的落地。
- **做法**：实机看"脚是陷进地面还是悬空"——
  - 若**看不出问题** ⇒ 本项直接关闭，把"实测无偏移"写回设计 R9（**大概率是这种**，因为你已确认"全部通过"）；
  - 若有偏移 ⇒ 量出格数，在 `client/DihuangLoongRenderer#preRender` 里加一次 y 平移
    （**不动模型文件、不动碰撞箱**），并把常量写回设计 R9。
- **完成标准**：无论哪种结论，设计文档 R9 都要从"待观察"变成"已结案（含数值或无偏移）"。

### T11 交互入口迁移到 `mobInteract`（**刻意推迟，有明确触发条件**）

- 设计与理由见 `2026-09-25-npc-vanilla-ai-design.md` §3.4 / D54。
- **触发条件**：当要把 NPC 对话接到**实体自己的交互钩子**上时（而不是继续用全局 `PlayerInteractEvent`）。
- 届时的工作：`NpcEntity` 覆写 `mobInteract(Player, InteractionHand)`（`Mob` 内 protected 钩子；
  原版 12+ 生物在覆写它），服务端那次开对话；事件侧收窄到非 NPC 实体（因为对话系统目前服务
  任意实体类型，例如示例数据里的原版铁傀儡）。
- **当前不做**的原因：现在 `mobInteract` 里无事可做，空覆写即死代码；且你已裁定"现阶段不接入对话系统"。

### T12（可选）给地黄龙接对话

- 前提是解除"现阶段不接入"的裁定。工作量很小：加一份
  `data/beloong/beloong/npc_dialogue/dihuang_loong.json` + 语言键
  （对话系统已迁到 `data/` 且为服务端权威，见 `2026-09-25-npc-dialogue-data-driven-design.md`）。
- 与 T11 **无依赖关系**：现在也能通过全局事件接（事件按 EntityType 匹配，NPC 也是实体）。

---

## 四、回填（完成 T9/T10 后）

1. `2026-09-25-npc-vanilla-ai-design.md`：把 §五 的实机结果与 R9 的最终偏移量写进"实现期记录"。
2. `memory/project-context.md` 子系统 11：补上实机结论（速度观感、张望副作用是否可接受）。
3. 若 T9 发现 `RandomLookAroundGoal` 的"身体随张望转"不可接受（D53 / R17）：按设计给的两条退路处理 ——
   去掉该 goal，或按 D56 覆写 `createBodyControl()` 让身体不动（后者与"尽量用原版"相悖，需重新权衡）。
4. `memory/decisions-log.md`：若实机再发现"标志位/视觉差值"类新坑，补一条。
