# NPC 开场流程改造 设计文档（2026-10-14）

**目标**：把"玩家 → 开场 CG → 末的第一段对话 → 后续剧情"这条入口，从**环境副作用**（走进坐标盒就获得 `root`）
改造成**受控流程**；并把"登场 CG 是否放过"这件事从**不可控的动画重播**里剥离出来，交给一个**明确的进度**。

## 起因

1. 旧症状"CG 重复播放"的**真因**已由另一个会话查明并修复：**一次性表情缺少生命周期**（播完不清字段 ⇒
   客户端重建实体实例后从头再播）—— 见 `docs/reviews/2026-10-13-npc-emote-expiry-review.md`。
   **不是** CG 触发的问题。
2. 但 `root` 用 `minecraft:location` 触发 ⇒ **走进龙宫那一片坐标盒就会获得** ⇒ 环境副作用不可控；
   且"剧情被完整重置后该不该重播 CG"没有任何显式表达。
3. 需求（用户）：新玩家**看不见锚点末**（锚点永不显示，仅调试指令可看）；开场要有**可配置的等待时间**；
   完整重置后**允许**重播登场 CG（那是设计，不是 bug）。

## 决策（全部由用户裁定）

| # | 决定 |
|---|---|
| D1 | **允许对账器的撤回一路撤到 `root`** ⇒ 完整重置后**可以重复播放**登场 CG（**可控**的重播 = 设计；不可控的动画重播 = 已修的 bug）|
| D2 | **新增进度 `1_0`**（"末的登场"）⇒ 语义 = **登场 CG 已放过**；**由 CG 自己授予**（完成判定写在 CG 类里，不外部查询）|
| D3 | **播 CG 的闸门 = `¬1_0`**（有 `1_0` 就不再播）—— 与对话系统的"开始/结束进度"同一套思路 |
| D4 | **第一段对话的窗口 = `[1_0, 1_1)`**（有 `1_0` 才能右键触发；chatbox 结束授予 `1_1`，这一段**不变**）|
| D5 | **序章由系统授予 `root`**：进入游戏 + 等**可配置**时间 ⇒ 授予（`root.json` 触发改 `impossible` ⇒ 只能由代码给）|
| D6 | **序章绝不移动玩家**（2026-10-14 实机后改判）：玩家**本就在**剧情声明的维度里才开场；不在 ⇒ 什么都不做。**剧情可跳过** —— 离开龙宫等于跳过，宽限期过后由维度规则清理（删分身 + 撤整条链）；玩家自己走回来才会重新开场 |
| D7 | ★ **CG 只要"被开始过"就一定拿到 `1_0`**：正常播完自然给；**中断/换维度/掉线/服务器重启/预检失败**也**补授 + 一条英文 WARN** ⇒ 流程永不卡死 |
| D8 | **锚点永不显示给任何玩家**；**开启 debug 的玩家例外** ⇒ debug 的目的就是让整合包作者看见锚点 |
| D9 | **`beloong npc debug`**：只让玩家**看见**（**不可交互**）· **持久化** · **不检查权限** · 即时生效 |
| D10 | 序章**只对"人在 `beloong:loong_palace`"时生效**（否则 `ensureDouble` 会在别的维度找不到锚点，凭空造一只）|
| D11 | 重播（重置后回龙宫）**不额外等待** —— 只有"新人登录"才等 N 秒 |
| D12 | 不动 `npc_story` 的 `start_advancement = root` / `end = 5_1`（这样 D1 的"撤到 root"才成立）|
| ~~D13~~ | **【已作废｜2026-10-14 实机后】** 原定"重置后用现成传送 API 把玩家送回龙宫" —— 用户改判"剧情应当可跳过、不该强行拉回" ⇒ **序章不再有任何传送调用**（守卫 `stage_invariants.py` 有断言钉住：序章不得出现 `DimensionTeleport`/`changeDimension`/`teleportTo`/`TeleportTarget`）。以下保留原文仅作历史记录：**传送复用现成 API**：`TeleportTarget.LOONG_PALACE_LEVEL` + `TeleportTarget.Spawn`/`toLoongPalace` + `DimensionTeleport.toTarget(...)` ⇒ `player.changeDimension(transition)` |
| D14 | ★ **顺序铁律**：**先确认分身已建成，再授予 `root`**（同一 tick 内完成）。否则"有 `root` 却无分身"会被对账器的 D21 分支反复撤回 ⇒ **授予/撤回抖动** + 日志刷屏 |

## 进度拓扑（父链必须真的这么连，否则撤回走不到 `1_0`）

```
root ─┬─ 1_0 ★新增（CG 内授予：登场已放完）
      └─ 1_1（chatbox 授予：第一段对话完成）─ 2_0 ─ 2_1 ─ 3_0 ─ 3_1 ─ 4_1 ─ 5_1
```

`1_0` 的 parent = `root`；**`1_1` 的 parent 从 `root` 改成 `1_0`**。
依据：撤回是沿 `parent()` 上溯逐级执行的（`NpcStoryHandler.java:582-600`）⇒ `1_0` 不在链上就**撤不掉** ⇒
玩家将永远"看过 CG"、再也看不到（与 D1 矛盾）。

## 分身与 CG 的机制（2026-10-14 **读代码**确认，勿凭记忆改）

**`NpcStoryHandler#ensureDouble`（`:648-716`）**
1. 扫 **`level.getAllEntities()`** —— `level` 是调用方传入的 **`player.serverLevel()`** ⇒ **只在玩家当前维度找锚点**；
2. 已有 `owner == 我` 的分身 ⇒ **复用**（`Ensured(npc, spawned=false)`）；
3. 找 `owner == null` 的同类（锚点）⇒ **取离玩家最近者**（多于一个打 WARN）；
4. **新建实体** `type.create(level)`，只**拷位置与朝向**（`:689-690` 的 `moveTo(anchor…)` + `setYHeadRot`）；
5. ⚠️ **锚点找不到不是失败**：**兜底在玩家身前 4 格生成** + WARN（`:691-703`）；唯一返回 `null` 的情形是
   `type.create` 出来不是 `NpcEntity`（`:674-679`，实际不可能）；
6. `setOwner(我)` → `setLease(生成时刻, 到期时刻)` → `addFreshEntity` → INFO。

**CG 的"由分身播放"具体是什么（`CgAnimation:140-181` + `CgContext:108-115`）**
- `CgContext.of(viewer, target)` ⇒ `anchor = target.position()`；`anchorToViewer` = `(viewer.pos − anchor)` 水平单位向量；
- 镜头公式：`相机 = 目标.pos + (观察者.pos − 目标.pos).normalize() × N`（`CgContext:33`）；
- `animationName()`（如 `descend`）由 `NpcEntity#setEmote` **播在 target 身上**（`CgAnimation:176-181`）；
- ⇒ **分身 = CG 的 target** ⇒ 它必须存在、且必须**站在锚点位置**（`ensureDouble` 精确 `moveTo` 到锚点坐标与朝向，
  正是为了让镜头有正确的锚点）；`CgContext.of` 在"观察者水平方向压在目标头顶"时返回 `null` ⇒ `play` 返回 0（D7 兜底）。

### 原子顺序（D14，全在同一 tick）

```
① 前置条件（活着 / 非旁观 / 未骑乘 / 不在 CG 中）不过 ⇒ 本 tick 跳过，下 tick 再看
② 未获得 root ∧ tickCount ≥ 配置秒数
③ 不在龙宫 ⇒ 按 D13 用现成 API 传送回龙宫
④ 同一 tick：ensureDouble(player, player.serverLevel(), …)   ← 传送后 serverLevel() 已是龙宫 ⇒ 找到锚点
⑤ 确认拿到分身 ⇒ 才授予 root（走"身前 4 格"兜底也继续，绝不卡住玩家）
⑥ ¬1_0 ⇒ cg.play(player, 分身)
```

## 流程

```
进入游戏 → 等 N 秒（配置项）→〔序章〕授予 root（人在龙宫 ∧ 未获得 root）
  → 分身已建成（见上"机制"一节）→ 分身播 mo_entrance（闸门：¬1_0）
  → CG 播完/中断/失败 ⇒ CG 自己授予 1_0（+ 失败时 WARN）
  → 玩家右键分身（窗口 [1_0, 1_1)）→ 第一段对话 → 跳 chatbox
  → chatbox 完成 ⇒ 授予 1_1（+ 启动 mo_route_1，这一段不变）→ 后续不变
```

## 各闸门一览

| 用途 | 判据 |
|---|---|
| 序章触发（授予 `root`）| `tickCount ≥ 配置秒数` ∧ 未获得 `root` ∧ **分身已建成**（人在龙宫外则先按 D6/D13 传送回龙宫）|
| 播 CG | 未获得 `1_0` |
| 第一段对话可见 | `[1_0, 1_1)` |
| 私有分身可见 | 已获得 `root`（且未进入"通关且要回收"态）|
| 锚点可见 | **任何人都不显示**；开启 debug 的玩家例外 |
| 剧情重置 | 撤到 `root`（全撤含 `1_0`）⇒ 回龙宫即重新开场 + 重播 CG（D1）|

## 影响面（逐文件）

| 文件 | 改动 |
|---|---|
| `data/beloong/advancement/npc/1_0.json` | **新增**：trigger `impossible` · parent `root` · 无提示（`show_toast`/`announce_to_chat` = false）|
| `data/beloong/advancement/npc/1_1.json` | parent: `root` → **`1_0`** |
| `data/beloong/advancement/npc/root.json` | trigger: `location` → **`impossible`**（去掉坐标盒，改由序章代码授予）|
| `data/beloong/beloong/npc_dialogue/mo.json`（模组 + 整合包两份）| 第一条回复窗口 `[root,1_1)` → **`[1_0,1_1)`** |
| `assets/beloong/lang/{zh_cn,en_us}.json` | `1_0` 的标题与描述两键（中文名 **「末的登场」**）|
| **新增** `npcstory/NpcPrologueHandler.java` | 序章：每 tick 检查（D5/D10/D11）⇒ 授予 `root` ⇒ 确保分身 ⇒ 交给 CG |
| `cg/CgAnimation.java`（**CG 会话负责** ✗ 需协调）| 基类加"完成时授予哪个进度"（可覆写 · 缺省不授予）+ 在**完成/中断/失败**三处都授予（D7）|
| `cg/instances/MoEntrance.java`（同上 ✗）| 声明完成时授予 `beloong:npc/1_0` |
| `npcstory/NpcStoryHandler.java` | `onAdvancementEarned` **只保留"确保分身"**，CG 播放移交序章（避免两条路各播一次）|
| `entity/NpcEntity.java` | `visibleTo`：**锚点恒不可见**；debug 玩家**一律可见**（私有分身的判据仍是 `root`）|
| `command/NpcCommand.java` | 新增 `debug` 子命令（toggle，持久化在 `player.getPersistentData()`）|
| 配置 | 新增"开场等待秒数"（缺省待定，建议 5 秒；只对新人登录生效）|

## 非目标

- **不**用 attachment、**不**给流程加持久 NBT（`1_0`/`root` 都用进度表达）；
- **不**传送玩家（D6）；
- **不**改 `npc_story` 的 `start`/`end`（D12）；
- **不**让 debug 玩家能交互（D9）；**不**给 debug 加权限检查（用户裁定）。

## 已知取舍

- D9 的"不检查权限"⇒ **任何玩家**都能开 debug 看到**所有人的分身与锚点**（纯观感信息，无实体/交互影响）；
- D8 的"锚点永不显示"⇒ 重置后玩家在**回到龙宫并重新开场之前**看不到任何 NPC（此时他也没有分身）⇒
  这正是 D5/D10 序章要补齐的空窗；
- D7 的"开始过就补授"⇒ 极端情况下玩家没真正看到 CG 也会进入后续（流程依然可玩，用户已接受）。

## 验证

- **自动化**：`gradlew build`；仓库外 `stage_invariants.py` / `route_invariants.py` / `cg_invariants.py` /
  `npc_emote_expiry_invariants.py` + 三个 emote 探针；**新增断言**见实施计划 T11。
- **实机验收**（用户）：
  1. 新存档进游戏 → 等 N 秒 → 自动开场 + 播登场 CG → 拿到 `1_0`；
  2. CG 结束后右键末 ⇒ 第一段对话 ⇒ chatbox ⇒ 拿到 `1_1`；
  3. **走远再回来**：不再重播动画（已修）+ **不再重播 CG**（有 `1_0`）；
  4. **完整重置**（把 `dimension.grace_ticks` 临时调小 ⇒ 出龙宫等它撤）⇒ 回龙宫 ⇒ **重播登场 CG**（D1 期望）；
  5. `/beloong npc debug` 开 ⇒ **看得见锚点与所有分身**、**点不动别人的分身**；关 ⇒ 恢复；
  6. 新人视角：**开场前看不见锚点**（D8）。
