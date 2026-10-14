# 对账机制部分重写 实施计划（2026-10-14）

**规格**：`docs/plans/2026-10-14-reconciler-rewrite-design.md`（D1–D8）
**目标**：删掉两条不可靠的清理路径（离开维度 / 扫不到分身），只留"租约到期"与"通关"两条，
并让"撤回进度"与"删除分身"**永远成对**；配套修好序章的两处问题。

---

### T1 删除"离开维度 ⇒ 重置 + 销毁"（含提醒）
**Files:** `npcstory/NpcStoryHandler.java`
**Steps:** 删掉 `reconcilePlayer` 里的 `dimension().enforced()` 分支（`outside_dimension` 的
`removeDouble(..., true)` 与 `warnDimensionGrace(...)` 调用），以及随之无用的
`warnDimensionGrace` 方法；`reconcileStory` 里对 `host` 的使用保留（对账仍只扫 host 维度）。
**Verification:** 全局搜 `outside_dimension` 归零；`gradlew build` 通过。

### T2 删除"扫不到分身 ⇒ 重置 + 销毁"（D21）及其计数机制
**Files:** `npcstory/NpcStoryHandler.java`
**Steps:** 删掉 `reconcilePlayer` 的 `mine.isEmpty()` 里的 D21 分支与 miss 逻辑（该分支改为：孤儿/通关等判定之外**直接返回**）；
删掉 `searchTrustworthy` 的计算与传参、`addMiss/missesOf/clearMiss/missingCounts/MISSES_BEFORE_RESET/missingKey`。
**Verification:** 全局搜 `searchTrustworthy` / `addMiss` / `MISSES_BEFORE_RESET` 归零；build 通过。

### T3 离线租约到期 ⇒ "待撤回"标记 + 重登撤回（D6）
**Files:** `npcstory/NpcStoryHandler.java`（`settleOffline` 与登录路径）
**Steps:**
① `settleOffline` 删掉分身时，在**玩家持久数据**写 `BeloongStoryPendingRevoke = true`（玩家离线，但 `ServerPlayer` 不在线 ⇒
   改为按 UUID 记在**服务器级**的一张待撤回表里，重登时消费 —— 实施时二选一并写清理由）；
② 登录路径（`onPlayerLoggedIn` 已有）里：若该玩家有待撤回 ⇒ `revokeStory(player, story)` + 清标记 + 一条 INFO。
**Verification:** 单机模拟（把 `ticks` 调小、让分身到期、玩家下线再上线）⇒ 日志出现"pending revoke"与"revoked on login"两条；
build 通过。

### T4 序章：先清孤儿、再传送、再新建（C）+ 不在 CG 播放期间补授 `1_0`（D）
**Files:** `npcstory/NpcPrologueHandler.java`；`cg/CgCompletionHandler.java`（加一个只读查询）
**Steps:**
① "从头开场"分支里，`ensureDoubleFor` 之前：扫 `player.serverLevel()` 找 `owner == 我` 的分身 ⇒ `discard()` + INFO；
② `1_0` 补授前：`CgCompletionHandler.hasPending(player, ENTRANCE_DONE)` ⇒ 有则跳过（并 INFO 说明）。
**Verification:** build；日志验证顺序（discard → moved → spawned → granted → playing）。

### T5 `dimension{}` 瘦身（D8 选甲，只改模组数据）
**Files:** `npcstory/NpcStory.java`（codec/record）、`npcstory/NpcStoryLoader.java`（KNOWN_FIELDS/校验）、
`src/main/resources/data/beloong/beloong/npc_story/mo.json`
**Steps:** `dimension{}` 只保留 `host` 与 `_comment`；删除 `enforce` / `grace_ticks` / `warn_before_ticks` / `warn_key`
（以及 `Dimension.enforced()` 与相关校验与语言键）；`mo.json` 同步瘦身。
**Verification:** `stage_invariants.py` 里与这些字段相关的断言同步更新后全绿。

### T6 守卫（新增/调整）
**Files:** `D:\Minecraft\tools\YSMParser\stage_invariants.py`
**Steps:** 新增断言：
① `NpcStoryHandler` 里**不出现** `outside_dimension`（离开维度那条已删）；
② **不出现** `searchTrustworthy` / `addMiss` / `MISSES_BEFORE_RESET`（D21 机制已删）；
③ ★ **`revokeStory` 的每个调用点都必须与"删除分身"相邻**（实施时用"调用点前后 N 行内必须有 removeDouble/discard"的
   结构断言；把"成对原则"钉成机器可查）；
④ 序章里 `discard` 孤儿分身的调用**早于** `award(ROOT)`（行号比较）；
⑤ 序章的 `1_0` 补授前有 `hasPending` 查询；
⑥ `dimension{}` 只允许 `host` / `_comment`。
（一律查**方法声明/键名**，不查注释里的说明文字 —— 本项目已栽过多次。）
**Verification:** `python stage_invariants.py` 全绿。

### T7 文档
**Files:** `docs/NPC系统总设计.md`（§12.7/§12.9 同步）、本设计/计划回填执行记录
**Steps:** 把新的对账形状、租约时间基准、语义变化（离开=暂停）写进活文档；计划回填。
**Verification:** 文档自检。

### T8 完整验证与提交
**Verification:** `gradlew build` + 四套不变量 + 四个探针全绿 ⇒ 提交 ⇒ 重建 jar 装机 ⇒ 派一次独立收尾审查。

---

## 实机验收清单（用户）

1. 带进度**离开龙宫**停留 1 分钟以上 ⇒ **进度不被撤**（日志里**不再**出现 `has the start advancement but no double`）；
2. 回到龙宫 ⇒ 剧情**接着走**（进度在、分身在原处、**不**被送回原点、**不**重播 CG）；
3. 把 `ticks` 调成 30 秒、在龙宫内待着 ⇒ 到期 ⇒ **删分身 + 撤进度** ⇒ 随后序章**从头开场**（送回原点 + 全新分身 + 播 CG）
   ⇒ 三者同步；
4. 同上但**到期时玩家在龙宫外**（分身区块未加载）⇒ 回来时应看到"删 + 撤"同时发生，然后从头开场；
5. **离线**到期：让分身到期后下线等待，再上线 ⇒ 应在登录时**撤回进度**（日志有 pending/revoked 两条）；
6. 通关 ⇒ 分身按 `keep_after_finish` 处理（`false` 则删除，`true` 则保留）；
7. 回归：对话 / 路线 / 表情 / 调试指令 / 不可被移动。
