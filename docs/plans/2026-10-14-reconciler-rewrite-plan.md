# 对账机制部分重写 + 性能优化 实施计划（2026-10-14）

**规格**：`docs/plans/2026-10-14-reconciler-rewrite-design.md`（D1–D9）
**目标**：删掉两条不可靠清理路径，只留"租约到期"与"通关"；让"撤回进度"与"删除分身"**永远成对**；
并做 S1/S2/A1 性能优化；配套修好序章两处。
**批次数**：4 批（① 对账器 ② 性能 ③ 数据/文档 ④ 验证交付），每批结束跑一次全套验证。

---

## 批次 ① 对账器重写

### T1 删除"离开维度 ⇒ 重置 + 销毁"（含提醒）
**Files:** `src/main/java/com/zonlong/beloong/npcstory/NpcStoryHandler.java`
**Steps:** 删掉 `reconcilePlayer` 里 `dimension().enforced()` 那条分支（`removeDouble(..., "outside_dimension", true)`
与 `warnDimensionGrace(...)` 调用）及 `warnDimensionGrace` 方法本身；`reconcileStory` 对 `host` 的使用保留。
**Verification:** 全局搜 `outside_dimension` 归零；`gradlew build --console=plain` 通过。

### T2 删除 D21（扫不到分身 ⇒ 重置）及其计数机制
**Files:** 同上
**Steps:** `reconcilePlayer` 的 `mine.isEmpty()` 分支只保留"孤儿/通关"等**不撤进度**的判定，其余直接返回；
删掉 `searchTrustworthy` 计算与传参、`addMiss` / `missesOf` / `clearMiss` / `missingCounts` / `missingKey` /
`MISSES_BEFORE_RESET`。
**Verification:** 全局搜 `searchTrustworthy`、`addMiss`、`MISSES_BEFORE_RESET` 归零；build 通过。

### T3 删除离线结算（D6 纯乙）
**Files:** 同上
**Steps:** 删掉 `settleOffline` 与 `skipOfflineSettlement`；`reconcileStory` 只对**在线主人**调用 `reconcilePlayer`
（离线主人直接跳过，不删不撤）；登录路径里因此不再有"离线补判"。
**Verification:** 全局搜 `settleOffline`、`skipOfflineSettlement` 归零；build 通过。

### T4 序章：先清孤儿、再传送、再新建（C）+ 不在 CG 播放期间补授 `1_0`（D）
**Files:** `npcstory/NpcPrologueHandler.java`；`cg/CgCompletionHandler.java`
**Steps:** ① "从头开场"分支里，`ensureDoubleFor` 之前先扫出 `owner == 我` 的分身并 `discard()` + INFO；
② `1_0` 补授前加 `CgCompletionHandler.hasPending(player, ENTRANCE_DONE)` 判断，有则不补并 INFO。
**Verification:** build 通过；日志顺序应为 `discard → moved → spawned → granted → playing`。

**批次 ① 结束**：跑全套验证（stage/route/cg/探针）+ 提交。

---

## 批次 ② 性能优化

### T5 S1：实体注册表
**Files:** 新建 `src/main/java/com/zonlong/beloong/npcstory/LoadedNpcIndex.java`；改 `BeLoongCore.java`（注册）
**Steps:**
① 用 `Map<UUID, NpcEntity>`（或 `Set`）保存已加载 `NpcEntity`；
② `@SubscribeEvent onEntityJoin(EntityJoinLevelEvent)`：**只**做 `if (e.getEntity() instanceof NpcEntity npc) put(npc)`
——**绝不**触碰 level / chunk / blockState / 实体查询（死锁红线，写在类注释里并说明依据）；
③ `@SubscribeEvent onEntityLeave(...)`：移除（**仅加速**，正确性不依赖它）；
④ 读取接口：`Collection<NpcEntity> loaded()` —— 内部**先丢 `isRemoved()` 的条目**（卸载路径打的就是这个标记）；
⑤ 另提供 `Collection<NpcEntity> loadedIn(ServerLevel)`（按 `npc.level()` 过滤，供对账按 host 维度取用）。
**Verification:** build 通过；`stage` 新增断言（T10）全绿。

### T6 S1 接入对账器与序章（含 A1）
**Files:** `npcstory/NpcStoryHandler.java`、`npcstory/NpcPrologueHandler.java`
**Steps:** ① `reconcileStory` 的实体来源从 `level.getAllEntities()` 换成 `LoadedNpcIndex.loadedIn(level)`
（一次遍历覆盖所有剧情 ⇒ **A1 同时达成**）；② 序章的"孤儿子分清理"用注册表；
③ `ensureDouble` 找锚点：优先注册表，**找不到时回退到一次全量扫描**（并留 INFO/WARN 说明走了回退）。
**Verification:** build 通过；全局搜确认对账器里不再有 `getAllEntities`（只允许出现在 `ensureDouble` 的回退里）。

### T7 S2：进度查询缓存
**Files:** `dialogue/NpcDialogueStage.java`（收口）、`npcstory/NpcStoryHandler.java`（撤回时失效）、
`cg/CgCompletionHandler.java`（授予时失效）、`entity/NpcEntity.java`（缓存 `NpcStory` 到瞬时字段）
**Steps:** ① 缓存键 `(UUID, ResourceLocation)` → `boolean`，含 `lastValidatedTick`；
② TTL 到期后重新查询；③ 失效 API：`invalidate(player)` / `invalidate(player, id)`；
④ 在 `AdvancementEarnEvent`、`revokeStory`、`revokeWholeAdvancement`、CG 授予、玩家登录/登出 处调用；
⑤ `NpcEntity` 的 `NpcStory` 缓存用 `NpcStoryLoader.INSTANCE.reloadStamp()` 作失效判据。
**Verification:** build 通过；`stage` 新增断言：缓存失效点齐全（存在性断言）。

**批次 ② 结束**：跑全套验证 + 提交。

---

## 批次 ③ 数据与文档

### T8 `dimension{}` 瘦身（D8 选甲，只改模组数据）
**Files:** `npcstory/NpcStory.java`、`npcstory/NpcStoryLoader.java`、
`src/main/resources/data/beloong/beloong/npc_story/mo.json`、`assets/beloong/lang/*.json`
**Steps:** `dimension{}` 只留 `host` 与 `_comment`；删 `enforce` / `grace_ticks` / `warn_before_ticks` / `warn_key`
及 `Dimension.enforced()` 与相关校验、语言键；`mo.json` 同步瘦身。（**无需考虑旧存档**：整合包未使用新格式）
**Verification:** build 通过；`stage` 相关断言同步更新后全绿。

### T9 文档
**Files:** `docs/NPC系统总设计.md`（§12.7/§12.9）、本设计/计划回填执行记录、
`docs/plans/2026-10-14-prologue-reset-desync-fix-plan.md`（标注被本计划取代）
**Steps:** 写清新的对账形状、租约时间基准、语义变化（离开=暂停）、D6 的边界与两个已知风险、S1/S2 的做法与依据。
**Verification:** 文档自检（无与代码不符的表述）。

---

## 批次 ④ 验证与交付

### T10 守卫断言（`D:\Minecraft\tools\YSMParser\stage_invariants.py`）
① `NpcStoryHandler` 里**不出现** `outside_dimension`；
② **不出现** `searchTrustworthy` / `addMiss` / `MISSES_BEFORE_RESET` / `settleOffline`；
③ ★ **`revokeStory` 的每个调用点都必须与"删除分身"相邻**（结构断言：调用点前后 N 行内必须有 `discard`/`removeDouble`
——把"成对原则"钉成机器可查）；
④ 序章里 `discard` 孤儿分身的调用**早于** `award(ROOT)`（行号比较）；
⑤ 序章的 `1_0` 补授前有 `hasPending` 查询；
⑥ `dimension{}` 只允许 `host` / `_comment`；
⑦ ★ **`LoadedNpcIndex` 的 join 处理器里不得出现世界访问**（禁词：`level(` / `getLevel(` / `getChunk` /
`getBlockState` / `isLoaded` / `getEntities` / `getAllEntities`）；
⑧ 注册表读取处必须过滤 `isRemoved()`；
⑨ `NpcDialogueStage` 的缓存失效 API 在四处调用点都存在。
（一律查**方法/键名**，不查注释文字 —— 本项目已栽过多次。）
**Verification:** `python stage_invariants.py` 全绿（含新增 9 类断言）。

### T11 完整验证与提交
**Verification:** `gradlew build` ✓ + `stage` / `route` / `cg` / `npc_emote_expiry` / 三个探针全绿 ⇒ 提交
⇒ 重建 jar 并替换整合包（备份）⇒ 派一次**独立收尾审查**。

---

## 实机验收清单（用户）

1. 带进度**离开龙宫**停留 1 分钟以上 ⇒ **进度不被撤**（日志里**不再**出现 `has the start advancement but no double`）；
2. 回到龙宫 ⇒ 剧情**接着走**（进度在、分身在原处、**不**被送回原点、**不**重播 CG）；
3. 把租约时间调成 30 秒、在龙宫内待着 ⇒ 到期 ⇒ **删分身 + 撤进度**同时发生 ⇒ 随后序章**从头开场**
   （送回原点 + 全新分身 + 播 CG）⇒ 三者同步；
4. 同上但**到期时主人离线** ⇒ 分身**不**被删（纯乙）⇒ 主人再上线且分身被加载 ⇒ 此时"删 + 撤"同时发生；
5. 通关 ⇒ 分身按 `keep_after_finish` 处理（`false` 删除 / `true` 保留）；
6. 回归：对话 / 路线 / 表情 / 调试指令 / 不可被移动 / 新人开场（等配置秒数 ⇒ 送回原点 ⇒ 全新分身 ⇒ CG）；
7. 观察日志：对账不再打印任何"扫不到分身"类警告；S1 生效后无新增报错。
