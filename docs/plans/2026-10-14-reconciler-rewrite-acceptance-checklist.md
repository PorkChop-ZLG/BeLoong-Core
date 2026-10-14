# 实机验收清单：对账机制重写 + 性能优化 + 租约续约（2026-10-14）

**被测版本**：`36b7e86`（含两轮审查的全部 Critical 修复），已装入整合包 `mods/beloong-0.10.0.jar`。
**当前剧情链**（以仓库数据 `npc_story/mo.json` 为准）：`root → 1_0 → 1_1 → 2_0 → 2_1`
⇒ **里程碑 = `1_0` / `1_1` / `2_0`**（两端点 `root` 与 `2_1` **不**续约）。
⚠️ 活文档 §12.10 的拓扑图里写了 `3_0/3_1/4_1/5_1` —— **那些进度目前不存在**，别照它测。

---

## 〇、准备（3 分钟）

| 项 | 做法 |
|---|---|
| 日志抓取 | 只看这几类关键字即可（下面每条用例都标了）：<br>`npc story` · `npc prologue` · `cg 'mo_entrance'` |
| 缩短租约 | 改 `data/beloong/beloong/npc_story/mo.json` 的 `lease.ticks`：<br>• 60 秒 = `1200` · 5 分钟 = `6000`<br>⚠️ **不要小于 120**（登场 CG 的时长）—— 那会触发一个已知遗留问题（见 §四）|
| 缩短开场等待 | 配置 `npcStory.prologueDelaySeconds`（缺省 5 秒）|
| 加速对账 | 对账间隔配置（缺省 100 tick = 5 秒）—— 想快就调小 |
| 备用命令 | `/beloong npc debug`（调试视角）· `/advancement grant|revoke @s only <id>` |
| ⚠️ 数据红线 | `mo.json` 的 `dimension{}` **只能有 `host`** —— 写上 `enforce`/`grace_ticks`/`warn_*` 会被**整文件拒绝**（日志点名）；链中间的文件（如 `1_1.json`）**不要删**（已知遗留，见 §四）|

---

## 一、必须跑的 6 条（核心行为）

### ① 新人开场（序章 + CG + `1_0`）
- **操作**：用一个没有进度的账号登录，等 `prologueDelaySeconds` + 约 6 秒（CG 时长）
- **期望日志**（按顺序）：`npc story: spawned private double of 'entity.beloong.mo' for player '<你>'`
  → `npc prologue: granted root to '<你>'` → `cg 'mo_entrance' playing for <你>`
  → 约 6 秒后 `cg 'mo_entrance': granted advancement 'beloong:npc/1_0' to <你> (cutscene finished)`
- **期望表现**：你在入场点原地开场（**不该**看到 `moved player` —— 你就站在落点上）；
  CG 播在**你身边那只分身**身上；CG 结束后右键它能打开第一段对话
- **失败看哪里**：只有 `spawned` 没有 `cg playing` ⇒ CG 数据/预检问题；有 `moved player` ⇒ 落点判定不对

### ② ★ CG 期间不得提前补授 `1_0`（本次修的一个真 bug）
- **操作**：同上，**盯住 CG 播放的那 6 秒**
- **期望**：CG 播放期间**不该**出现 `npc prologue: player '<你>' had root but not 1_0 while in beloong:loong_palace`
  （那条只该救"CG 被关服打断"）；`1_0` 只由 CG 自己在结束时授予
- **失败看哪里**：若在 CG 开始后 1 秒就看到那条 INFO ⇒ `hasPending` 闸门失效

### ③ ★ 离开龙宫不再被撤（本次重写的核心目的）
- **操作**：带着进度（已有 `root`）离开龙宫，在外面待 **1 分钟以上**
- **期望日志**：**不该**出现任何 `npc story '<…>': removed double of player '<你>'`
  与 `npc prologue: moved player '<你>'`
- **期望表现**：回到龙宫后**接着原先那一步**继续（进度在、分身还在原处、不重播 CG）
- **失败看哪里**：若出现 `has the start advancement but no double` ⇒ 说明 D21 又回来了（不该有）；
  若出现 `reason: outside_dimension` ⇒ 说明"离开维度就清"又回来了（不该有）

### ④ ★ 续约（本次新增功能）
- **操作**：把 `lease.ticks` 设为 `1200`（1 分钟）⇒ 正常推进剧情，**每次拿里程碑**（`1_0` / `1_1` / `2_0`）时看日志
- **期望日志**：每次里程碑都有
  `npc story '<root>': lease renewed for player '<你>' after milestone '<该里程碑>' (<旧到期> -> <新到期>)`
  且 **新 − 旧 ≈ 1200**（= 你配的 `lease.ticks`，**不是固定一小时**）
- **期望表现**：即使慢慢玩，租约也不会到期（不会被清）
- **反例（同样重要）**：拿到**端点**（`root` 或 `2_1`）时**不该**有 `lease renewed` 这一行

### ⑤ ★ 停止推进 ⇒ 租约到期 ⇒ 删 + 撤（成对）
- **操作**：`lease.ticks` 设 `1200`，开场后在龙宫里**什么都不做**等它到期（或走出去等着再回来）
- **期望日志**：`npc story '<root>': removed double of player '<你>' (reason: timeout, progress revoked: true)`
  ⇒ 紧接着序章重开场：`spawned private double` + `granted root` + `cg playing`
- **期望表现**：玩家被送回入场点、**旧分身在锚点处被新建的替换**、CG 播在新分身上 ⇒ **三者同步**
  （这正是最初那个"玩家在原点、CG 拍在半路那只"的不同步 BUG 的反面）
- **失败看哪里**：若看到 `could not revoke the story … — the double was KEPT`（ERROR）⇒ 链被改坏（见 §四）

### ⑥ 手动只撤 `root` ⇒ 应当自愈（本次修的一个陷阱）
- **操作**：`/advancement revoke @s only beloong:npc/root`
- **期望日志**：`npc prologue: discarded a leftover double of 'entity.beloong.mo' for player '<你>'`
  ⇒ 然后 `spawned private double` + `granted root` + `cg playing`
- **期望表现**：即使"有 `1_0` 却没有 `root`"这种半截状态，也会**从头开场**（不会永久卡死）

---

## 二、应当跑的 5 条（边界与回归）

| # | 用例 | 操作 | 期望 |
|---|---|---|---|
| ⑦ | **纯乙：离线不删** | 租约过期后**下线**，让别人在同一区域活动（或干脆等一会儿） | 日志里**不该**出现该玩家的 `removed double`；等他上线且分身被加载时才会 `timeout` ✔（这时才"删+撤"成对）|
| ⑧ | **通关回收** | 走完 `2_0` → `2_1` | `mo.json` 现为 `keep_after_finish: true` ⇒ 分身**保留**，**不该**有 `reason: finished`；若你把它改成 `false` ⇒ 应有 `(reason: finished, progress revoked: false)`，且玩家在原处看到的是**空场**（锚点不再显示，这是设计 D8）|
| ⑨ | **退出清理**（可选） | 把 `lease.clear_on_logout` 设为 `true` ⇒ 下线 | `(reason: logout, progress revoked: <未通关为 true>)`—— `mo.json` 缺省是 `false`，所以默认**不该**有这条 |
| ⑩ | **多人并发 + 客户端索引污染（C1 回归）** | 单机/局域网开**两个**客户端，两人各走一段剧情 | 日志里**不该**出现 `ConcurrentModificationException`；且两人的分身都能被各自看到、租约都能正常到期清理（C1 修之前，单机下客户端线程会把服务端索引条目删掉 ⇒ 租约清理静默失效）|
| ⑪ | **调试视角** | `/beloong npc debug` 开/关 | 开启后能看到**锚点**与**所有人的分身**；但**别人的分身点不动**（交互闸门不变）；关掉后恢复 |

---

## 三、"不该出现"反例清单（一条条搜日志，命中即有问题）

| 反例关键字 | 含义 |
|---|---|
| `has the start advancement but no double` | D21（"扫不到分身 ⇒ 重置"）又回来了 —— 本次已删，**永不**应出现 |
| `reason: outside_dimension` | "离开维度就重置"又回来了 —— 本次已删 |
| `timeout_offline` | 离线结算又回来了 —— 本次已删 |
| `could not revoke the story` | 撤回失败（链被改坏）⇒ 分身被保留、需人工修数据 |
| `could not walk the parent chain` | 链遍历失败（同上）|
| `milestone '…' earned by '…' but their double was not found` | 拿到里程碑却找不到分身 ⇒ 该次**没续约**（会提前到期）|
| `no anchor for '…' in the loaded-npc index — falling back to a full entity scan` | 索引里没锚点（世界/区块刚重载时偶发可接受；**常态出现**说明索引失效）|
| `npc prologue: player '…' had root but not 1_0` **在 CG 播放期间** | ② 的失败形态 |

---

## 四、本次**已知遗留**（验收时会踩到的三个坑，属下一批）

1. **`lease.ticks` 不要配得小于 120** ✗：租约会在登场 CG 播完前到期 ⇒ "删+撤 → 序章重开场 → 重播 CG" 反复循环
   （计划里登记为 T8-extra2/S6：加载期给 `ticks` 加下限校验）
2. **不要删链中间的文件**（如 `1_1.json`）✗：链断 ⇒ 续约静默失效 + 租约到期时撤回失败 ⇒ 分身永久留存 + 每轮 ERROR
   （登记为 T5b：`checkStoryIdsOnce` 目前只校验 start/end，应改为逐环校验）
3. **"限时改永久"的存量分身会被静默删除且跳过提醒**（登记为 S7）
   另外：`NpcStory` / `NpcPrologueHandler` / `NpcEntity` 里还有几处**与代码不符的注释**（登记为批次⑤ I7–I12）

---

## 五、结论怎么给

- **通过** = §一 六条全过 + §三 反例清单**一条都没命中** ⇒ 这次重写的核心目标（撤与删成对、离开不乱撤、续约生效、性能）都成立
- **部分通过** = 某些条目失败 ⇒ 请把**失败那一条的编号 + 相关日志原文**给我，我按编号定位
- 顺带留意：`stage_invariants.py` 现在是 **172 PASS / 0 FAIL**（含新增 6 组守卫）—— 若你改动数据后想让守卫复跑，跟我说一声即可
