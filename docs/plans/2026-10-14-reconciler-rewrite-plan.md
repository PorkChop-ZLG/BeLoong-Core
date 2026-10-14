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

### T8 `dimension{}` 瘦身（D8 选甲，只改模组数据）—— **T8 已完成（2026-10-14）**
**Files:** `npcstory/NpcStory.java`、`npcstory/NpcStoryLoader.java`、
`src/main/resources/data/beloong/beloong/npc_story/mo.json`、`assets/beloong/lang/*.json`
**Steps:** `dimension{}` 只留 `host` 与 `_comment`；删 `enforce` / `grace_ticks` / `warn_before_ticks` / `warn_key`
⚠️ **只动 `dimension{}` 组**：`lease{}` 组（`ticks` / `warn_before_ticks` / `clear_on_logout` / `keep_after_finish` …）
一个字都不要动 —— `lease` 组里也有个同名 `warn_before_ticks`，误删会静默改变租约提醒行为
及 `Dimension.enforced()` 与相关校验、语言键；`mo.json` 同步瘦身。（**无需考虑旧存档**：整合包未使用新格式）
**Verification:** build 通过；`stage` 相关断言同步更新后全绿。

### T8b 租约续约（D10 / D11）—— **T8b 已完成（2026-10-14）**
**Files:** `npcstory/NpcStory.java`（`Lease.renewal` 布尔 + `Lease.renew_advancements` 可选列表 + codec/校验）、
`npcstory/NpcStoryLoader.java`（KNOWN_FIELDS + 惰性链解析 + 校验）、
`npcstory/NpcStoryHandler.java`（`onAdvancementEarned` 里续约；链遍历器复用）、
`src/main/resources/data/beloong/beloong/npc_story/mo.json`（**显式写** `"lease_renewal": true`；列表缺省不写，用规则）
**Steps:**
① 解析字段：`lease.renewal`（缺省 true）、`lease.renew_advancements`（可选数组）；
② 惰性解析缺省列表：沿 `parent()` 从 `end` 上溯到 `start`，取**两端之间**的进度 id 集合（含 `1_0`）；
   用 reload stamp 作失效判据；解析时校验"每个 id 都在链上"，违规记录并剔除；
③ `onAdvancementEarned`：若该进度属于某剧情的续约集合 ⇒ 找到**该剧情的分身**（注册表优先、回退全量扫描）
   ⇒ `setLease(now, story.expiryAt(now))` + INFO（旧到期 → 新到期）；
④ 已通关剧情直接跳过；
⑤ 模组 `mo.json` 的 `lease{}` 里显式写 `"renewal": true`。
**Verification:** build 通过；`stage` 断言 ⑩–⑫ 全绿；实机：连续拿里程碑 ⇒ 租约不断刷新（日志可见），
停止推进 ⇒ 租约到期后（缺省 1 小时，实际由 `lease.ticks` 决定）被"删 + 撤"。

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
⑨ `NpcDialogueStage` 的缓存失效 API 在四处调用点都存在；
⑩ 续约只认"链上两端之间"：断言续约判定处**不**使用 `startAdvancement()` / `endAdvancement()` 作为**可续约**项
（端点必须被排除），且缺省列表的推导走 `parent()` 链遍历；
⑪ `NpcStory` 有 `leaseRenewal` 字段且**缺省 true**（断言 codec 里的默认值写法存在）；
⑫ 模组 `mo.json` 的 `lease{}` 里**显式**包含 `renewal`（断言该键存在，不是靠缺省）。
⑬ ★ **续约不得硬编码时长**：断言续约调用点用的是 `story.expiryAt(...)`（读该剧情的 `lease.ticks`），
且 `NpcStoryHandler` 里不出现 `72000` / `3600` / `一小时` 之类字面量。
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
7. 观察日志：对账不再打印任何"扫不到分身"类警告；S1 生效后无新增报错；
8. **续约**：连着拿几个里程碑（含 `1_0`）⇒ 每次日志都有"续约（旧到期 → 新到期）"且到期时间被推后**一整段该剧情配置的租约时长**；
   随后停止推进（例如待在龙宫不动）⇒ 租约到期后（缺省 1 小时）被"删 + 撤"；端点进度（`root` / `5_1`）本身**不**触发续约。

---

# 执行记录

## 批次① 对账器重写（2026-10-14 完成）

| 任务 | 结果 | 要点 |
|---|---|---|
| T1 删除"离开维度 ⇒ 重置 + 销毁" | ✅ | 连同 `warnDimensionGrace` 与 `NpcEntity.outsideSince`（字段 / 两个存取器 / NBT 键 / 存 / 读 共 6 处死状态）一并清除 |
| T2 删除 D21 及其计数机制 | ✅ | `MISSES_BEFORE_RESET` / `missingCounts` / `missingKey` / `addMiss` / `missesOf` / `clearMiss` / `searchTrustworthy` / 锚点计数全部归零；`mine.isEmpty()` 分支改为**什么都不做**并写明理由 |
| T3 删除离线结算（纯乙） | ✅ | `settleOffline` / `skipOfflineSettlement` / `timeout_offline` 全部移除；离线主人一律不动 |
| T4 序章两处修正 | ✅ | ① 从头开场前 `discardOrphanDoubles` 清场（先快照再删，避免 CME）；② `1_0` 补授前 `CgCompletionHandler.hasPending` 闸门 |

**结构收获**：`revokeStory` 现在全仓只有 **1 处调用**，且位于 `removeDouble` 内部 ⇒
「撤回进度」与「删除分身」从"约定"变成**结构上不可违反**（已写成守卫 15.6-③）。

**闸门更新**：检查点如实报红 6 条（都是针对已删功能的旧断言）⇒ 已全部删除，
并新增 15.6 组 17 条新断言（两条路径必须归零 / 成对原则 / 纯乙不得有离线结算）。

**瘦身**：`NpcStoryHandler` 726 → 567 行（−159）。

**验证**：`gradlew build` ✓ · stage 148 PASS / 0 FAIL ✓ · route 全绿 ✓ · cg PASS ✓ · 四个探针 GREEN ✓

---

## 批次① 独立审查（2026-10-14）与处置

派了独立审查（只读）⇒ 结论 **Critical 1 / Important 6 / Suggestion 7**，总体判断"能进入下一批（有条件）"。

### 已当场修完（审查修复提交）

| 编号 | 问题 | 处置 |
|---|---|---|
| **C1 ★** | `removeDouble` 忽略 `revokeStory` 的 `false` 返回 ⇒ **撤失败仍删分身**，中心不变量在失败路径破洞（触发：链中间环缺失）| fail-closed：`if (revoke && !revokeStory(...)) { ERROR; return; }` —— **不删分身**，留 ERROR 让人修数据 |
| I1 | "在线玩家没分身也巡检"整段空转，且每轮在玩家存档里写**空进度记录** | 整段删除（对账器只处理"此刻有分身的主人"）|
| I2 | 四处注释仍在描述已删的两条清理路径（说假话）| 全部改写为当前事实 |
| I3 | "唯一会撤进度的路径是租约到期"是假的（`clear_on_logout` 也会撤）| 改为"能撤进度的调用点**全部**位于 `removeDouble` 内，而它同时删分身" |
| I4 | "删掉分身后公共锚点会重新对他可见"是假的（`visibleTo` 对有剧情声明的无主实体**恒 false**）| 按设计 D8 改注释（锚点永不显示 ⇒ 通关回收后玩家看到的是**空场**）；若将来想要"通关后锚点补位"，那要改 `visibleTo`，属另一件事（已提请用户确认）|
| I5（简化版）| 序章不读 fail-closed ⇒ 坏数据下每 20 tick 造实体 + 重播 CG（root 与 1_0 同缺时**无限循环**）| 序章先判 `ROOT`/`ENTRANCE_DONE` 的 holder 是否存在 ⇒ 不存在直接 `continue` |
| I6 | 文档里的人工兜底（只撤 `root`）会把玩家**永久卡死** | 序章的"已开场过"判据改为 **`1_0` ∧ `root` 都在** ⇒ "有 `1_0` 无 `root`"也会**从头开场**（自愈 ✓ 顺带兜住 S6 的极端配置）|
| S1 | `NpcStoryHandler` 里 `CgRegistry` import 已无使用 | 删除 |
| S5 | 守卫只因注释里出现"D21"三字而通过（给已删功能背书）| 翻成**否定断言**：`'restarted from the beginning' not in _HDL` |

### 并入后续批次的任务（新增）

| 编号 | 内容 | 归入 |
|---|---|---|
| **T5b** | **整链校验**：`checkStoryIdsOnce` 目前只校验 start/end 两个 id ⇒ 改为沿 `parent()` 逐环校验（缺任一环即 DOA）；并让**序章也跳过 DOA 剧情**（I5 完整版；简化版已随本批落地）| 批次② |
| **T8-extra** | S4：`NpcStory` 里 `DEFAULT_WARN_BEFORE_TICKS` 已无消费者、`enforced()` / `dimensionGraceTicks()` 无调用者、`mo.json` 的 `dimension{}` 与两个 `outside_dimension` 语言键成死数据/死键 | 批次③（T8）|
| **T8-extra2** | S6：加载期给 `lease.ticks` 加**下限**校验（≥ 登场 CG 时长，避免"租约比 CG 还短"）；S7：提醒判据改用 `npc.expireAt()`（不用 `story.isPermanent()`），避免"限时改永久"时存量分身被静默删除且跳过提醒 | 批次③（T8）|
| **T10-extra** | S2：序章硬编码 `ROOT`/`ENTRANCE_DONE` 且用 `return` 跳整个玩家检查 ⇒ 只支持一条剧情（当前只有 `mo.json`，属潜在）；S3：`discardOrphanDoubles` 是对账器之外唯一绕过 `removeDouble` 的删实体路径（判据经审查确认**正确、不宽不窄**），加注释点明它是唯一安全旁路 | 批次③ 文档 / 批次④ 守卫 |

### 审查确认无恙的部分（要点）

- **成对原则**：全仓 `revokeStory` 只有 1 处调用（`removeDouble` 内）⇒ 正常路径上不存在"能撤却删不掉"，唯一破洞是 C1（已修）
- **`discard()` 是同步的**（审查去读了原版 `Entity.setRemoved` → `PersistentEntitySectionManager.Callback.onRemove` → `EntityLookup.remove`）⇒ T4 的"同 tick 清场后再生成绝不会拿到旧那只"成立
- **`hasPending` 语义正确**：关服/崩溃导致静态表丢失 ⇒ `hasPending` 为 false ⇒ "CG 被关停打断"这种**该补授**的情形不会被挡
- **`getGameTime()` 各维度同源**（`DerivedLevelData` 委托 overworld）⇒ 换维度不影响租约与 CG 截止时间
- **`getAllEntities()` 只覆盖已追踪实体** ⇒ "扫不到 ≠ 不存在"这条语义在删掉 D21 之后依然成立，**别再把缺失当事实**

## 批次② 性能优化（2026-10-14 完成）

| 任务 | 结果 | 要点 |
|---|---|---|
| T5 S1 实体注册表 | ✅ | 新增 `npcstory/LoadedNpcIndex`：`EntityJoinLevelEvent` 登记 / `EntityLeaveLevelEvent` 移除（**仅加速**）/ 读取时过滤 `isRemoved()`（卸载路径打的就是这个标记 ⇒ leave 不触发也正确）；join 处理器**零世界交互**（死锁红线，守卫 15.7 钉住）|
| T6 S1 接入（含 A1）| ✅ | 对账器实体来源换成索引（一次遍历覆盖所有剧情 ⇒ **A1 达成**）；`doublesOf` 同步；`ensureDouble` **优先索引、索引里没有锚点时回退一次全量扫描**（并留 INFO）；序章清场用索引。全仓 `getAllEntities()` 只剩这一处回退（另有 `MynpcCommand` 一条指令里的，超出本批范围）|
| T7 S2 进度缓存 | ✅ | 缓存放进 `NpcDialogueStage.stateOf`（`isEarned` 与 `visible` 的共同收口）：TTL 20 tick + `UNKNOWN` 不入缓存 + 超 4096 条顺手清过期；**写侧三处显式失效**（序章授予 / CG 授予 / 撤回）—— 否则序章会读到过期值而重复清场与重复传送 |

**验证**：`gradlew build` ✓ · stage **159 PASS / 0 FAIL**（含新增 15.7/15.8 组）· route 全绿 · cg PASS · 四探针 GREEN

### 批次③ T8 执行记录（2026-10-14）

- `NpcStory.Dimension`：(host, enforce, graceTicks, warnBeforeTicks, warnText, warnKey) ⇒ **(host)**
  · 顺带删除两个已无消费者的缺省常量 `DEFAULT_DIMENSION_GRACE_TICKS` / `DEFAULT_WARN_BEFORE_TICKS`
  · 删除 `enforced()` 与 `dimensionGraceTicks()`；`Dimension` 的 javadoc **整体重写**（说明为何只剩 host，
    并点明 `Lease` 的 `warn_*` 仍有效 —— 两个组有同名字段，别删错组）
- `NpcStoryLoader`：删掉 `dimension.grace_ticks` / `dimension.warn_before_ticks` / `dimension.enforce` 三段校验；
  `warn_text` / `warn_key` 收窄为**只查租约组**；`dimension` 的已知键集合移除那 5 个键名
- `mo.json`：`dimension{}` 只剩 `host`；语言键删 2×2 个死键 ⇒ 中英各 **301** 键
- 顺带订正 `NpcStory` 三处"离线结算"与一处"玩家会重新看到公共锚点"（后者 = 审查 I4 同类假话）
- 守卫：删"离开维度的宽限缺省 = 6000"，换成 **15.9**（`dimension{}` 只允许 host/_comment + host 值）

### 批次③ T8b 执行记录（2026-10-14）

- `Lease` 新增：`renewal`（`optionalFieldOf("renewal", Boolean.TRUE)` ⇒ **缺省开**）与
  `renewAdvancements`（`ResourceLocation.CODEC.listOf().optionalFieldOf(...)`，可选）
- 加载器 `lease` 组已知键加入这两个名字
- `NpcStoryHandler`：
  · `onAdvancementEarned` 在"是不是起点"判断**之前**调 `renewLeaseIfMilestone`
  · `chainBetween`：从 **`end` 的父**开始沿 `parent()` 上溯、**遇到 `start` 收工** ⇒ 端点天然排除；走不通 ⇒ 空集
  · `milestonesOf`：显式列表优先（逐条校验"必须在链上"，违规 WARN 忽略）；否则推导结果按 `reloadStamp` 惰性缓存
  · 续约对象 = 该剧情那只分身（索引优先、未命中回退扫一次玩家当前维度）；时长 = `story.expiryAt(now)`；
    未找到分身时 WARN（不静默）
- `mo.json`：`lease{}` 显式写 `"renewal": true`
- 守卫 15.10（5 组）：从 end 的父开始 / codec 缺省 TRUE / mo.json 显式写 / 走 `expiryAt` 且不得硬编码 / 只续该剧情那只

## 批次③ 完成（2026-10-14）

- **T8** ✅ `dimension{}` 瘦身为只剩 `host`（含加载器已知键、`mo.json`、死语言键、守卫 15.9）
- **T8b** ✅ 租约续约（`Lease.renewal` 缺省开 / `chainBetween` 从 end 的父起推导、端点天然排除 /
  `milestonesOf` 显式优先 + 惰性缓存 / 只续该剧情那只分身 / 时长取自 `lease.ticks`；守卫 15.10）
- **T9** ✅ 活文档新增 **§12.10 对账机制重写**（判定表 / 中心不变量 / 删掉的两条路径 / 语义变化 /
  租约时间基准 / 续约 / S1·S2·A1 / 两个已知取舍），并给 §12.9 加上"已被 §12.10 取代"的指向
- **T10** ✅ 守卫现状：15.5b/c（序章位置规则 + 续约不得硬编码占位）、15.6（对账器两条路径归零 + 成对原则 + 纯乙）、
  15.7（S1 死锁红线 + isRemoved 过滤）、15.8（S2 缓存 + 三处写侧失效 + UNKNOWN 不入缓存）、
  15.9（dimension 只允许 host/_comment）、15.10（续约 5 组）、**15.11（序章清场早于授予 root + 补授前问 hasPending）**
