# 修复方案：进度被误撤导致"进度与分身分家"（2026-10-14）

**状态**：待用户批准。**未改任何代码。**
**起因**：用户实机反馈 —— 未完成流程就离开龙宫，进度**很快被撤回且没有任何提示**；
重新回到龙宫后玩家被传送到原点，但**私有分身没被销毁、也没有新建，而且旧的私有 NPC 播放了 CG**。

---

## 一、日志证据（`run/logs/latest.log`，实测）

| 证据 | 次数 | 说明 |
|---|---|---|
| `has the start advancement but no double` + `revoking the story so it can be restarted` | **6 / 6** | **D21 分支在反复撤回** ⇒ 撤回是**对账器**做的（不是序章、也不是什么新系统）|
| `reason: outside_dimension` | **0** ✗ | **"离开维度"那条清理/提醒分支从未执行** ⇒ 配置里的 `dimension.grace_ticks`（5 分钟）**永远轮不到** |
| `granted root … ensured their double` 但**没有** `spawned private double` | 2 | **复用了旧分身**（⇒ 印证用户"没有增加新的"）|
| `removed double … (reason: orphan)` | 1 | 分身**能被扫到**时，删除逻辑是好的 ⇒ "删不掉"纯粹因为"扫不到" |
| `npc prologue: player 'Dev' had root but not 1_0` | 5 | ★ **序章自身的 bug**：CG 开始后 **1 秒**就补授了 `1_0`（详见 §三-C）|

**时间线（原文）**
```
20:19:18.580  npc prologue: moved player 'Dev' to the story start
20:19:18.580  WARN: 'Dev' has a double but has not started          ← 孤儿分支
20:19:18.582  removed double of 'Dev' (reason: orphan)              ← 删除成功（这次扫得到）
20:19:19.575  spawned private double … granted root … cg playing    ← 全新一只 + CG ⇒ 同步 ✓
（以上是"手动撤进度"那条路，工作正常）

20:20:13.578  WARN: 'Dev' has the start advancement but no double   ← ★ D21 误撤（玩家已离开龙宫，区块卸载）
20:22:41.624  moved player … granted root + ensured（**无** spawned）  ← 复用旧分身 ⇒ CG 播在它身上 ✗
```

---

## 二、根因（两条，缺一不可）

### 根因 1（主因）：`reconcilePlayer` 在"搜索不可信"时**数满 3 次照样撤**

`NpcStoryHandler.java:388-411`：
```java
if (mine.isEmpty()) {
    if (!started || finished || !searchTrustworthy) {
        if (started && !finished && !searchTrustworthy) {
            addMiss(...); if (misses < MISSES_BEFORE_RESET) return;   // 只是"再等 3 轮"
        } else { return; }
    }
    clearMiss(...);
    revokeStory(player, story);      // ← 照样撤
}
```
而 `searchTrustworthy = anchors > 0`（`:341`）⇒ **只代表"扫到了锚点"**，**不代表"扫到了分身"**：
- 玩家**离开龙宫** ⇒ 龙宫没有任何玩家 ⇒ **整个维度区块卸载** ⇒ `level.getAllEntities()` 什么都扫不到
  ⇒ `anchors == 0` ⇒ `searchTrustworthy = false`
- 但对账器每 100 tick（5 秒）跑一次 ⇒ **第 3 次**（≈**15 秒**）就落到 `revokeStory`
- ⇒ 表现：**离开 15 秒就被撤、玩家毫无提示**（D21 只写服务端日志）
- ⚠️ 而"离开维度就清理"那条分支要求**先扫到分身** ⇒ 它**永远没机会执行** ⇒ **5 分钟宽限形同虚设**

### 根因 2：撤回时**删不掉分身** ⇒ 进度与实体分家

D21 是"**扫不到分身**"的那一支 ⇒ 它手里**没有实体** ⇒ 只能撤进度、**无法删除**。
分身只是在区块卸载时被**存档**（原版行为），并没有被删 ⇒ 玩家回来时它还在原处。

### 后果（用户看到的三件事）

| 现象 | 机制 |
|---|---|
| 旧分身没被销毁 | D21 扫不到它 ⇒ 删不掉 |
| 没有新建 | 回来时区块加载 ⇒ 序章的 `ensureDoubleFor` **扫到旧分身** ⇒ **复用**（`spawned=false`）|
| 旧的播了 CG | 序章把 `cg.play(player, npc)` 播在**旧分身**身上（它在半路），而玩家已被传送到**原点** ⇒ **不同步** |

---

## 三、修复方案（四项，按重要性排序）

### A（★核心）搜索不可信时**绝不撤**：`searchTrustworthy == false` ⇒ 直接返回，**不记 miss**

`reconcilePlayer` 的 `mine.isEmpty()` 分支里，`!searchTrustworthy` 这一支改成 **纯 `return`**（不再累计 miss、
更不落到 `revokeStory`）。理由：`revokeStory` 只在**手里有实体**时才与删除配对；扫不到就撤 ⇒ **必然分家**。

> **原则**：“撤回进度”与“删除分身”**必须成对发生**。看不到分身时既不要撤（也删不掉），
> 把清理留给“能真正看到分身的那一刻”。

### B（★核心）从"当前维度"里找分身**不再作为可信判据** ⇒ 改用**玩家侧的存在性记录**

`searchTrustworthy = anchors > 0` 的洞在于：**锚点在固定位置、分身会沿路线走远**，二者可以一个加载、一个不加载。
故引入**不依赖区块加载**的存在性信号（**方案"甲"**）：
- 在**玩家**的持久数据里记 `{doubleUUID, lastSeenGameTime}`；
- **生成分身时写入**（`ensureDouble` 成功处）、**本模组删除分身时清除**（`removeDouble`）；
- 每次对账**扫到该分身时刷新** `lastSeenGameTime`；
- D21 的触发条件改为：`started ∧ ¬finished ∧ 扫不到 ∧ 没有记录（或记录已过期）`；
- “过期”阈值＝**该剧情的 `dimension.grace_ticks`**（语义对齐：离开超过宽限才视为弃坑）。

⚠️ 代价：给玩家加一条**持久簿记**（用户此前偏好“流程不放持久 NBT” ⇒ 但这是**簿记**不是流程状态，需明确同意）。
⚠️ 备选（不加持久 NBT）：**把 D21 彻底删掉**，只留租约/通关两条清理 —— 代价是"分身被外部删掉（如 `/kill`）"
时玩家会停在"有 root、无分身、锚点又永不显示"的**卡死**状态（需要管理员介入）。

### C（★核心）序章在"从头开场"时，必须**先清掉属于该玩家的孤儿分身**

序章分支顺序改为：
```
在 host 维度 ∧ ¬1_0 ∧ tickCount ≥ delay ∧ ¬root
  → ① 若存在"owner == 我"的分身（此时玩家没有 root ⇒ 它必然是上一轮的孤儿）⇒ discard 掉
  → ② 传送到入场落点（不在附近才传；传后本 tick 结束）
  → ③ 确保分身（这时必然是**全新**的、生成在锚点）
  → ④ 授予 root
  → ⑤ 播登场 CG
```
这样"回到龙宫 ⇒ 从头开始"才是**真的从头**：玩家在原点、新分身在锚点、CG 播在它身上 ⇒ **三者同步**。
（A/B 修好之后这一步是**兜底**；即使将来又出现"扫不到的残留分身"，它也能保证不出现"玩家在原点、
CG 却拍在别处"✗。）

### D（附带，序章自身的 bug）恢复分支不得在 **CG 播放期间**触发

日志显示 `had root but not 1_0` 在 **CG 开始后 1 秒**就补授了 `1_0`（5 次）—— 那条分支本意是
"CG 被服务器关停打断"的兜底，不该在 CG 正在播时触发。
改法：询问 `CgCompletionHandler` **是否存在该玩家的待授予项**（它本来就维护着这张表）⇒
**有待授予项 ⇒ 不补授**（`1_0` 会由 CG 自己按时长授予）。

---

## 四、验证与验收

**自动化**：`gradlew build` + 仓库外 `stage_invariants.py`（新增断言见下）+ `route_invariants.py` +
`cg_invariants.py` + `npc_emote_expiry_invariants.py` + 三个 emote 探针。

**新增守卫断言（stage）**
1. `reconcilePlayer` 的"不可信"分支**不出现** `addMiss` / 不得落到 `revokeStory`（按结构断言：不可信分支内只有 `return`）；
2. `revokeStory` 的**调用点**必须与"删除分身"相邻（同一次对账里成对）；
3. 序章的"从头开场"分支里，**discard 孤儿分身**的调用必须早于 `award(ROOT)`（行号比较）；
4. 序章的 `1_0` 补授必须**先问** `CgCompletionHandler` 有没有待授予项。

**实机验收（用户）**
1. 未完成流程**离开龙宫**并停留 30 秒以上 ⇒ 进度**不应**被撤（日志里**不应**出现 `has the start advancement but no double`）；
2. 回到龙宫 ⇒ 剧情**接着原先那一步**继续（进度还在、分身还在原处）—— 若你要的是"离开即重置"，
   那就走第 3 条（**这是本次要你定的关键点**，见 §五）；
3. 若第 2 条选择"离开即重置"：回到龙宫 ⇒ 玩家被送回原点 + **旧的孤儿分身被删除** + **新分身生成在锚点** +
   CG 播在新分身上 ⇒ 三者同步；
4. 走远再回来 ⇒ 不重播动画（已修）· 不重播 CG（有 `1_0`）；
5. 手动撤掉进度 ⇒ 回到龙宫应走"孤儿删除 + 全新开场"（现有日志里这条是**正常**的，作为回归用例）。

---

## 五、需要你定的关键点（这决定 A/B 的具体形态）

**离开龙宫（未完成流程）到底应该是"暂停"还是"跳过"？**

| 选项 | 行为 | 与你的历次裁定 |
|---|---|---|
| **甲：暂停** | 离开 ⇒ **什么都不发生**（进度保留、分身留在原处）；回来 ⇒ **接着走**（不送回原点、不重播 CG）| 与你"不该强制拉回"一致 ✓ 但与你"离开=跳过剧情"**不一致** ✗ |
| **乙：跳过** | 离开 ⇒ 超过宽限后**删分身 + 撤进度**；回来 ⇒ 送回原点 + 全新分身 + 重播 CG | 与你"剧情可跳过""从头开始"一致 ✓ 正是本方案 A–C 的目标 ✓ |

⇒ 你之前两次的表述都指向 **乙**（“离开龙宫相当于跳过剧情”“只清理 NPC 和进度”“要传送到初始位置确保从头开始”）
⇒ 本方案按 **乙** 写；若其实想要 **甲**，那 A/B 可以大幅简化（甚至不需要 B 的持久记录）。
