# NPC 开场流程改造 实施计划（2026-10-14）

**规格**：`docs/plans/2026-10-14-npc-prologue-flow-design.md`（D1–D12，全部经用户确认）
**目标**：开场受控化 + `1_0` 登场进度 + 锚点永不显示 + debug 指令
**批次**：4 批 / 13 任务。**每任务必须先验证再进下一个**。

---

## 批次① 数据层（T1–T4）

### T1 新增 `1_0` 进度
**Files:** Create `src/main/resources/data/beloong/advancement/npc/1_0.json`
**Steps:** 照 `1_1.json` 的骨架；`criteria.1_0.trigger = minecraft:impossible`；`parent = beloong:npc/root`；
`show_toast`/`announce_to_chat` = false；标题/描述走语言键 `beloong.advancement.npc/1_0` 与 `.desc`；
`requirements = [["1_0"]]`。
**Verification:** `python -c "json.load(...)"` 通过；`stage_invariants.py` 新增断言见 T11。

### T2 `1_1` 的 parent 接到 `1_0`
**Files:** Modify `.../advancement/npc/1_1.json`
**Steps:** `parent`: `beloong:npc/root` → `beloong:npc/1_0`。
**Verification:** 守卫断言"链是 root→1_0→1_1"通过。

### T3 `root.json` 改回"只能由代码授予"
**Files:** Modify `.../advancement/npc/root.json`
**Steps:** `trigger`: `minecraft:location` → `minecraft:impossible`；删掉 `conditions` 与坐标盒相关块；
**保留** `root` 作为 `1_0` 的 parent。注释写明"改由 `NpcPrologueHandler` 授予（2026-10-14）"。
**Verification:** 守卫断言 `root` 的 trigger **只能是** `impossible`（收紧现有那条"允许两种"的断言）。

### T4 第一段对话窗口改到 `1_0`
**Files:** Modify `src/main/resources/data/beloong/beloong/npc_dialogue/mo.json` **与整合包同名文件**
**Steps:** 第一条回复 `start_advancement`: `beloong:npc/root` → `beloong:npc/1_0`（`end_advancement` 仍 `1_1`）。
整合包侧改动前**先备份**（备份要写在写盘函数里 —— 本项目既有教训）。
**Verification:** 两份文件 JSON 合法；守卫断言"第一条窗口 = `[1_0, 1_1)`"。

---

## 批次② 序章与 CG 授权（T5–T8）

### T5 配置项：开场等待秒数
**Files:** Modify `src/main/java/com/zonlong/beloong/Config.java`
**Steps:** 在 `NpcStory`（或新建 `NpcPrologue` 节）加 `prologueDelaySeconds`（int，缺省 5，注释写清"只对新人登录生效"）。
**Verification:** `gradlew build`；配置键出现在生成的 toml 里。

### T6 序章处理器
**Files:** Create `src/main/java/com/zonlong/beloong/npcstory/NpcPrologueHandler.java`；Modify `BeLoongCore.java`（注册）
**Steps:** 挂 `ServerTickEvent.Post`；对每个 `ServerPlayer`：
① `player.tickCount >= secondsToTicks(配置)` ∧ ② **未获得** `root` ∧ ③ 人在 `beloong:loong_palace`
⇒ 授予 `root`（`player.getAdvancements().award(...)`）⇒ **确保分身**（复用 `ensureDouble` 的语义；
⚠️ 必须与授予**同一 tick**，否则"有 root 无分身"会被 D21 误撤）⇒ 交给 CG（若 `¬1_0`）。
**Verification:** `gradlew build`；日志能看到"授予 root / 播 CG"两条 INFO；守卫断言存在该处理器且闸门齐全。

### T7 ⚠️ CG 侧：完成时授予进度（**需与 CG 会话协调** ✗）
**Files:** Modify `cg/CgAnimation.java`、`cg/instances/MoEntrance.java`（**另一个会话负责**）
**Steps:** 基类加一个可覆写的"完成时授予的进度 id"（缺省 `null` = 不授予）；
**完成 / 中断 / 失败（`play` 返回 0 或中途终止）三处都要授予**（D7/甲）+ 失败与补授各打一条英文 WARN。
**Verification:** `cg_invariants.py` 不回归；实机：CG 播完 ⇒ 玩家拿到 `1_0`。
**⚠️ 若 CG 会话不接这个改动**：退回"序章侧按配置时长自行计时授予 `1_0`"（行为等价，但时长会重复定义）。

### T8 `NpcStoryHandler` 只保留"确保分身"
**Files:** Modify `src/main/java/com/zonlong/beloong/npcstory/NpcStoryHandler.java`
**Steps:** `onAdvancementEarned` 里**移除 CG 播放**（移交序章，避免两条路各播一次）；
保留"确保分身"与 `Ensured.spawned()` 的语义；注释写明"CG 的唯一播放者是 `NpcPrologueHandler`"。
**Verification:** `stage_invariants.py` 里那条"CG 与生成解耦"的断言按新形态调整后通过。

---

## 批次③ 可见性与调试（T9–T10）

### T9 锚点永不显示 + debug 玩家全可见
**Files:** Modify `entity/NpcEntity.java`（`visibleTo`）
**Steps:**
- 锚点分支（`owner == null`）⇒ **恒 `false`**，**但** debug 玩家恒 `true`；
- 私有分身：不变（仍按 `root` 判 `started`），**但** debug 玩家恒 `true`；
- ⚠️ 保持**纯函数**契约（不写实体字段、不发包）；debug 集合是静态只读集合，服务端单线程访问；
- 注释写明"debug 有意打破'任一玩家眼里恰好一个'的不变量，是调试工具"。
**Verification:** `stage_invariants.py`/`route_invariants.py` 全绿；实机见验收 5/6。

### T10 `beloong npc debug` 子命令
**Files:** Modify `src/main/java/com/zonlong/beloong/command/NpcCommand.java`（+ 语言键）
**Steps:** 新增 `debug` 字面量，**toggle** 执行者自己的开关；状态存 `player.getPersistentData()`
（本仓库既有先例 `TeleportCooldown.java:41/46`）；**不加权限检查**（用户裁定）；给出中英反馈消息两键。
**Verification:** `gradlew build`；实机开关一次即可见/恢复。

---

## 批次④ 守卫与收尾（T11–T13）

### T11 守卫新增/调整断言
**Files:** Modify `D:\Minecraft\tools\YSMParser\stage_invariants.py`（仓库外）
**Steps:** 新增：
① `1_0` 存在、trigger `impossible`、parent 是 `root`；
② 链是 `root → 1_0 → 1_1`；
③ `root` 的 trigger **只能是** `impossible`（收紧旧断言）；
④ 两份 `npc_dialogue/mo.json` 第一条窗口 = `[1_0, 1_1)`；
⑤ `NpcPrologueHandler` 存在且闸门三件齐全（tickCount / ¬root / 龙宫维度）；
⑥ `NpcStoryHandler` 里**不再**出现 `cg.play`（CG 唯一播放者是序章）；
⑦ `visibleTo` 里锚点分支恒 false 且 debug 分支存在；
⑧ `NpcCommand` 里有 `debug` 子命令。
⚠️ 断言**不得**扫注释里的反例文字（本项目已栽过三次）⇒ 一律查**方法声明/键名**。
**Verification:** `python stage_invariants.py` 全绿。

### T12 活文档 + 设计/计划回填
**Files:** Modify `docs/NPC系统总设计.md`（§十二新增一小节"开场流程"）+ 本计划回填执行记录
**Steps:** 把进度拓扑、各闸门、D1–D12、影响面写进活文档；计划的每个任务记结果。
**Verification:** 文档自检（关键词齐备）。

### T13 完整验证与提交
**Verification:** `gradlew build` ✓ + `stage/route/cg/npc_emote_expiry` 四套 + 四个探针全绿 ⇒ 提交；
然后派一次**独立收尾审查**（照本项目惯例：至少覆盖"闸门是否真的有唯一播放者""父链是否真的连通""锚点分支是否真的恒 false"）。

---

## 实机验收清单（交给用户）

1. **新存档**：进游戏 → 等 N 秒 → 自动开场 + 登场 CG → 拿到 `1_0`；
2. CG 结束 ⇒ 右键末 ⇒ 第一段对话 ⇒ chatbox ⇒ 拿到 `1_1`；
3. **走远再回来**：不重播动画（已修）· **不重播 CG**（有 `1_0`）；
4. **完整重置**：把 `dimension.grace_ticks` 临时调小 ⇒ 出龙宫等它撤回 ⇒ 回龙宫 ⇒ **重播登场 CG**（D1 期望 ✓）；
5. `/beloong npc debug` 开 ⇒ 看得见**锚点**与**所有分身**、**点不动别人的分身**；关 ⇒ 恢复；
6. **开场前看不见锚点**（D8）；
7. 回归：路线 / 表情 / 通关回收 / 租约 / 离线结算 一切照旧。
