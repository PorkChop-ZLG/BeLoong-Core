# CG 与客户端的同步修复：设计 + 计划（F1 / F2 / F3，2026-10-14）

> 本文档把设计与计划写在一起：本次改动跨"客户端 ↔ 网络 ↔ 服务端"三层，且两者高度耦合。
> **F4（发呆玩家不自动重开场）经用户裁定不做** —— 默认 1 小时租约下它不是问题，
> 那个"每分钟重播一次 CG"只在把 `lease.ticks` 调到 60 秒做测试时出现。

## 一、实测确认的两个根源（日志证据）

**根源 A：序章的等待从"服务端登录"起算，与"客户端能否看见世界"无关**
`NpcPrologueHandler:139-140` 用的是 `player.tickCount < delayTicks`，而 `tickCount` 在服务端收下玩家的那一刻
就开始累加。日志实测：房主 `logged in 00:16:25.216` → `granted root 00:16:30.518` = **5.302 秒**（正好是配置的 5 秒 + 一次巡检）。
房主客户端是本地加载，5 秒够；**远程玩家同样这 5 秒却要额外涵盖"地形从网络流过来"** ⇒ CG 在他还看着
"加载地形中"时就开播了。⚠️ 原版**没有**"客户端加载完成"这个信号（`PlayerLoggedInEvent` 只代表"服务端收下玩家"）。

**根源 B：CG 由客户端本地播放，完成判定却由服务端自己的钟做**
`CgAnimation:271` 把烘好的轨迹交给客户端（`FDLibCalls.startCutsceneForPlayer`），
`:288` 又按自己的 `cutsceneTime` 宣布"播完了"。日志实测：9 次 `cg-start → granted '1_0'` 里 8 次**精确 6.00 秒**
（= 120 tick，与客户端在做什么完全无关）；表情上报里还出现一次 **`will be cleared in 44 ticks`**
—— 服务端登记这只表情时，客户端那边只剩 44 tick ⇒ 44 tick 后服务端把它清掉 ⇒ **动画被硬生生截断**
（运镜属客户端过场，仍在跑）⇒ 正是用户看到的"动画播一半就停、CG 运镜还在"。

**参考模组为什么没这问题**：龙之生存的选龙界面用的**是同一套** `tickCount < secondsToTicks(5)`
（`PlayerLoginHandler.java:109-127`），**并没有等客户端**；它不出事是因为它弹的是**一个等待玩家输入的 GUI**
（早晚一两秒无所谓），而 CG 是**固定时间轴的过场**，客户端晚起播就永远补不回来。

## 二、方案总览（三个修复，两个新 C2S 载荷）

```
F1  客户端就绪信号   :  客户端(加载遮罩消失 + N tick) ──C2S──> 服务端 ⇒ 序章延迟从收到信号起算
F2  CG 播完上报      :  客户端(过场真正结束)        ──C2S──> 服务端 ⇒ 立刻结算并授予 1_0（自己的钟降级为兜底）
F3  表情不被截断     :  服务端在"该 CG 仍在待结算"期间**不清**它设的那只表情；CG 结束才清
```

### 载荷一：`npcstory/NpcClientReadyPayload`（C2S，无数据）
- **客户端发**：`Minecraft.getInstance().getOverlay() == null`（加载遮罩消失）**且**已在世界内 **且**连续 `20` tick
  都满足 ⇒ 发一次；随后本次会话不再发。⚠️ 判据用**原版自己用的那个**（`MouseHandler:89/119/152`、
  `KeyboardHandler:492` 都用 `getOverlay() == null` 判断"能不能接受操作"）。
- **服务端收**：记下 `该玩家就绪于 <gameTime>`（内存，按 UUID）；同一会话重复上报忽略。
- ⚠️ **兜底必须有**：若始终没收到信号（老客户端/模组冲突/被改的客户端），服务端在
  `tickCount > (delay + 60 秒)` 时**照旧开场**并打一条 WARN —— 否则玩家会**永远没有剧情**。
- ⚠️ **安全**：载荷**不携带任何内容**，服务端只把它当"时机信号"，root 仍由服务端授予。

### 载荷二：`cg/CgFinishedPayload`（C2S，可带 CG 名）
- **客户端发**：本玩家本地的过场**真正结束时**发一次（含被取消/被切维度打断的情形）。
- **服务端收**：若该玩家此刻**确有待结算的 CG** ⇒ 立刻 `grantNow(...)` 授予完成进度并清掉待结算项；
  否则忽略（幂等）。**不采信载荷里的任何进度内容**。
- ⚠️ **保留"只要 CG 开始过就一定拿到"的既有语义**：服务端自己的计时**降级为兜底**
  （`cutsceneTime + 200` tick），客户端崩了/没上报时仍会授予。

### F3 表情不被截断
现状：`NpcEmoteExpiryHandler` 按 `emoteSetAt + 客户端上报时长` 清除表情 ⇒ CG 期间若服务端的钟先到，动画就被截断。
改法：`CgCompletionHandler` 的待结算项**带上目标分身的 UUID**；表情到期处理器在清理前先问
"这只 NPC 是否正被某个待结算的 CG 使用中" ⇒ 是 ⇒ **跳过清理**（等 F2 的结束信号/兜底到点后由 CG 侧清）。

## 三、实施计划

### T1 载荷骨架 + 注册（照 `NpcEmoteDurationPayload` 的样式）
**Files:** 新建 `src/main/java/com/zonlong/beloong/npcstory/NpcClientReadyPayload.java`、
`src/main/java/com/zonlong/beloong/cg/CgFinishedPayload.java`；改**现有的载荷注册处**（照 emote 那个的注册方式）
**Steps:** 定义 record/类型 + `StreamCodec`；在注册事件里登记（C2S、playToServer）。
**Verification:** `gradlew build` 通过；`stage` 新增断言"两个载荷类存在且已注册"。

### T2 客户端钩子（F1 的发送侧 + F2 的发送侧）
**Files:** 新建/改客户端类（照现有 `ClientFlightHandlerMixin` 用的 `ClientTickEvent` 惯用法）
**Steps:**
① 每 tick 检查：`Minecraft.getInstance().level != null && getOverlay() == null` ⇒ 连续 20 tick ⇒ 发 `NpcClientReadyPayload`（一次）；
② 过场结束/被取消时发 `CgFinishedPayload`（客户端过场回调处；若 `FDLibCalls` 只提供"开始"没提供"结束"回调
⇒ 退而求其次：客户端自己按收到的 `cutsceneTime` **本地计时到点**后发，并在"过场被中断"的钩子处也发一次；
**实施时必须先读 `FDLibCalls`/cutscene 的 API 确认有没有结束回调**，有就优先用它）。
**Verification:** build 通过；实机：把 `prologueDelaySeconds` 设为 5，用慢客户端进服 ⇒ **看到世界之后** CG 才开始。

### T3 服务端：序章延迟改从"就绪信号"起算（F1 的服务端侧）
**Files:** `npcstory/NpcPrologueHandler.java`
**Steps:** ① 保存 `readyAt`（内存 Map<UUID, Long>）+ 登出清理；② 未就绪时：若 `tickCount < (delay + 60s)` ⇒ return，
否则 WARN + 照旧开场（兜底）；③ 已就绪 ⇒ `now - readyAt >= delay` 才继续；④ 其余判定一字不改。
**Verification:** build；日志新增一条 "client ready at …" INFO 与一条兜底 WARN（受测时可见）。

### T4 服务端：CG 结算以客户端上报为准（F2 的服务端侧）
**Files:** `cg/CgAnimation.java`（兜底时长）、`cg/CgCompletionHandler.java`（待结算项 + 目标 UUID + `grantNowFromClient`）
**Steps:** ① `settleCompletion` 的正常路径改为"交给待结算表、兜底 `cutsceneTime + 200`"；
② 提供"收到 C2S ⇒ 立刻结算"的入口（按玩家 + CG 名匹配，幂等）；
③ 掉线/关服既有语义不变。
**Verification:** build；实机：CG 期间动画**播完后**才看到 `granted '1_0'`（日志时点应 > 6 秒且与画面对齐）。

### T5 F3：CG 期间不清表情
**Files:** `NpcEmoteExpiryHandler`（或表情清理处）、`CgCompletionHandler`（暴露"该 NPC 是否在待结算 CG 中"）
**Steps:** 清理前先问；在待结算 CG 中 ⇒ 跳过（并记一条 INFO，便于排查）；CG 结束时由 CG 侧清理。
**Verification:** build；实机：CG 期间**不再**出现 `emote 'descend' … cleared (one-shot finished)` 早于画面结束。

### T6 守卫
**Files:** `D:\Minecraft\tools\YSMParser\stage_invariants.py`
**Steps:** 断言 ① 两个载荷类存在且被注册；② 序章存在"未就绪时的 60 秒兜底"；③ CG 的兜底时长存在且 **> `cutsceneTime`**
（不得等于它，否则又变成服务端说了算）；④ 表情清理处存在"待结算 CG 时跳过"的分支；⑤ 载荷里**不出现**任何进度/权限字段。
**Verification:** `python stage_invariants.py` 全绿（含新增 5 组）。

### T7 文档 + 验证与交付
**Files:** 活文档补一节（§12.11 CG 与客户端同步）· 验收清单追加 3 条
**Verification:** 全套（build + stage + route + cg + 探针）⇒ 提交 ⇒ 重建装机 ⇒ 实机复测。

## 四、实机验收（新增 3 条）

1. **F1**：慢客户端进服（联机/首次进入大整合包）⇒ **看到世界之后**才开始等那 5 秒 ⇒ 屏幕有画面时 CG **从头**开始；
   日志里能看到 `client ready at …`，且 **不**出现兜底 WARN。
2. **F2**：CG 全程 ⇒ 末的 `descend` **播完**（不再中途停）⇒ 之后才 `granted advancement 'beloong:npc/1_0'`；
   日志里 `cg-start → 1_0` 的间隔**不再是恒定 6.00 秒**（而应 ≈ 客户端实际播放时长）。
3. **F3**：CG 期间**不再**出现提前的 `emote … cleared (one-shot finished)`；CG 结束后才清。
