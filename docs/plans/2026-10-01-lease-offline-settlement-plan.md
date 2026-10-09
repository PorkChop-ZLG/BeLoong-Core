# 租约离线结算 + 提醒数据驱动 + npc_story 结构重组 实施计划

**目标**：让到期的私有分身**在主人离线时也被结算**（常加载维度里不再无界累积），把两个提醒的提前量与文案交给数据驱动，并把 `npc_story` 按语义重组为嵌层结构、把校验从"软着陆"改成"报错不静默"。

**架构**：对账器由"只遍历在线玩家"改为"**也遍历 owner 表里不在线的人**"；离线只判租约（只删分身，不撤进度），进度留到**下次登录**由已有的 D21 分支撤 ⇒ 不引入任何每玩家持久状态。`npc_story` 变为 `lease{}` / `dimension{}` 两组 + 四个基础字段，加载期**硬校验、错就拒绝整文件**。

**选定方案**：A（一次破坏性替换，代码 + 两份数据同批）。

**规格来源**：`docs/plans/2026-10-01-lease-offline-settlement-design.md`（D1–D14，已批准）。

---

## 探明的前置事实（实施依据，勿凭记忆改写）

| 事实 | 出处 |
|---|---|
| 加载器的未知名校验目前**只查顶层**，`KNOWN_FIELDS` 是 `Set.of(...)` 字面量 | `npcstory/NpcStoryLoader.java:63-70`、`:94-103` |
| 取值校验目前内联在 `apply` 里（`lifetime_ticks < -1`、`spawn` 未知值 ⇒ 拒绝） | `NpcStoryLoader.java:114-125` |
| 加载器是**单例** `INSTANCE`，在 `BeLoongCore#addServerReloadListeners` 注册 | `NpcStoryLoader.java:49`、`BeLoongCore.java:208-215` |
| **`AddReloadListenerEvent.getRegistryAccess()` 存在** ⇒ `host` 可在加载期校验 | `AddReloadListenerEvent.java:34`、`:74-75` |
| 维度注册表用 `Registries.LEVEL_STEM` | `Registries.java:232` |
| **进度无法保证加载期就绪**：内置监听器是 `List.of(tagManager, recipes, functionLibrary, advancements)`，而重载是**并行**的监听器图 | `ReloadableServerResources.java:76-77`、`:98`、`:118` |
| 要改的方法与行号 | `NpcStoryHandler.java`：`onPlayerLoggedOut:184` · `reconcileStory:243` · `reconcilePlayer:286` · `warnDimensionGrace:391` · `warnBeforeExpiry:401` · `removeDouble:422` |
| 全局兜底配置 | `Config.java:347-351`（`expiryWarningTicks` 默认 600）· `:341-345`（`reconcileIntervalTicks` 默认 100）|
| 项目**没有测试套件** ⇒ 每个任务的验证 = `gradlew build` + 仓库外两套不变量 + 三探针；行为验证靠实机 | `memory/project-context.md` |

**一条由此推出的实现决定**：因为"缺省 600"与"显式写 600"在 codec 里无法区分，两个 `warn_before_ticks` 都声明为 **`Optional<Long>`**（不设 codec 缺省）⇒ 缺省时**租约**用 `Config.NpcStory.expiryWarningTicks`（保留 D11 的兜底语义）、**维度**用常量 600。

## 非目标

- 不做离线撤进度（需读存档或新持久状态）
- 不把校验收紧推广到 `npc_dialogue` / `npc_route`（另一批）
- 不做跨维度传送；不引入每玩家持久状态

**状态：全部完成** —— 3 批次 / 13 任务执行完毕，并通过收尾审查（**1 Critical / 3 Important / 2 Minor，全部已修**）。
审查报告：`docs/reviews/2026-10-01-lease-offline-settlement-review.md`。
⚠️ **实机验收仍待用户执行**（清单见文末）。

---

# 批次① 结构与校验（代码 + 模组自带数据 **同批**，否则自带数据会被自己的新校验拒绝）

### T1：`NpcStory` 改为嵌层记录

**Files:** Modify `src/main/java/com/zonlong/beloong/npcstory/NpcStory.java`

**Steps:**
1. 新增两个嵌套 record：
   `Lease(long ticks, boolean clearOnLogout, boolean keepAfterFinish, Optional<Long> warnBeforeTicks, Optional<String> warnText, Optional<String> warnKey)`
   `Dimension(Optional<ResourceLocation> host, boolean enforce, long graceTicks, Optional<Long> warnBeforeTicks, Optional<String> warnText, Optional<String> warnKey)`
2. 主 record 变为 `(startAdvancement, endAdvancement, spawn, cg, Lease lease, Dimension dimension)`；`CODEC` 用 `Lease.CODEC` / `Dimension.CODEC` 的 `optionalFieldOf("lease", <全缺省>)` 保证"整组可省"
3. 缺省值：`lease.ticks = 72000` · `clear_on_logout = false` · `keep_after_finish = false` · `dimension.enforce = false` · **`grace_ticks = 6000`**（由 1200 改）· 两个 `warnBeforeTicks` 缺省 = `Optional.empty()`（含义见上文决定）
4. 类与字段 javadoc 全部更新（尤其"host 只决定巡检去哪、enforce 才决定必须待在里面"）

**Verification:** `gradlew build` 成功（此刻自带数据尚未迁移 ⇒ 运行期会被拒，这是预期的，批次① 结束前必须完成 T4）

### T2：`NpcStoryLoader` 递归校验 + 迁移提示 + 严格取值 + registry 注入

**Files:** Modify `src/main/java/com/zonlong/beloong/npcstory/NpcStoryLoader.java`

**Steps:**
1. 把 `KNOWN_FIELDS` 换成**分层 schema**（`Map<String, Set<String>>`：顶层 + `lease` + `dimension`），写一个**递归**未知键收集器，报错**点名到 `lease.lifetime_ticks` 这种路径**
2. 加**旧写法迁移提示**表：`lifetime_ticks`/`clear_on_logout`/`keep_after_finish` ⇒ `lease.*`；`required_dimension` ⇒ `dimension.host`（+ 想启用清理再加 `dimension.enforce`）；`dimension_grace_ticks` ⇒ `dimension.grace_ticks` ⇒ 命中时 ERROR 里附提示
3. 严格取值校验（一律整文件拒绝 + ERROR）：`ticks < -1` · 任一 `warn_before_ticks < 0` · `grace_ticks < 0` · `warn_text` 为空串 · `warn_key` 非法 id · **逻辑矛盾**（`enforce=true` 无 `host`；`ticks=-1` 且显式 `warn_before_ticks > 0`）
4. `host` 存在性：`registryAccess.registryOrThrow(Registries.LEVEL_STEM).containsKey(host)` ⇒ 不存在即拒绝
5. 新增 `bindRegistryAccess(RegistryAccess)`（由 `BeLoongCore` 在事件里调用；`INSTANCE` 不变）；未绑定时对 `host` 打 WARN 并跳过该校验（这是我们的接线问题，不是数据错误）
6. `apply` 末尾把 `reloadStamp++`（供 T9 的一次性自检判断"是否发生过重载"）

**Verification:** `gradlew build`；`python stage_invariants.py`（⑬ 语言键检查）不回归

### T3：`BeLoongCore` 注入 registry access

**Files:** Modify `src/main/java/com/zonlong/beloong/BeLoongCore.java`（`:208-215` 附近）

**Steps:** 在 `addServerReloadListeners` 里加一行 `NpcStoryLoader.INSTANCE.bindRegistryAccess(event.getRegistryAccess());`（注释说明：只为让 `dimension.host` 能在加载期校验）

**Verification:** `gradlew build` 成功

### T4：迁移模组自带 `mo.json`（**必须与 T1/T2 同批**）

**Files:** Modify `src/main/resources/data/beloong/beloong/npc_story/mo.json`

**Steps:**
1. 改成新结构；`lease{ ticks: 72000, keep_after_finish: **true** }`（保住 D6）· `dimension{ host: beloong:loong_palace, enforce: true }`（原本就开着）· `grace_ticks` 走新缺省
2. 加 `lease.warn_text` / `dimension.warn_text`，把我们自己的"龙宫"措辞放进去（配合 T10 的内置文案中性化）

**Verification:** 本地用一个 **python 一次性脚本**按 T2 的 schema 静态校验该文件的键集合（计划执行时随写随验）；产物 `gradlew build` 成功

### T5：守卫 ⑰ 升级为逐层对齐

**Files:** Modify `D:\Minecraft\tools\YSMParser\stage_invariants.py`（仓库外）

**Steps:** 断言三层的键集合与 codec 字段逐一对齐；断言新缺省值（`grace_ticks` 缺省 6000、`keep_after_finish` 缺省 false）；断言 `mo.json` 的 `keep_after_finish` 为 true

**Verification:** `python stage_invariants.py` 全绿

### T6：批次① 验证与提交

**Verification:** `gradlew build` ✓ + `stage_invariants.py` ✓ + `route_invariants.py` ✓ + 三个探针 ✓ ⇒ 提交（代码 + 自带数据 + 文档 **一个提交**）

---

# 批次② 离线结算与数据驱动提醒

### T7：对账器支持离线 owner（核心）

**Files:** Modify `src/main/java/com/zonlong/beloong/npcstory/NpcStoryHandler.java`（`reconcileStory:243`）

**Steps:**
1. 先**快照** `byOwner` 的 owner 集合（删除会改实体列表）
2. 逐 owner：**在线** ⇒ 走现有 `reconcilePlayer`（逐字不变）；**离线** ⇒ 新私有方法 `settleOffline(UUID owner, NpcStory story, List<NpcEntity> mine, long now)`
3. `settleOffline` 只做一件事：对每只 `hasExpired(now)` 的分身 ⇒ `removeDouble(..., reason = "timeout_offline", revoke = false)`
4. **`keep_after_finish: true` 的剧情整条跳过离线结算**（D6：宁可不清理也不误删已通关者的分身）—— 在 `reconcileStory` 开头判断并加注释说明理由
5. 加注释说明"离线判不出进度 ⇒ 撤进度交给登录时的 D21"

**Verification:** `gradlew build`；`stage_invariants.py`（⑮ 的纯函数三条）不回归

### T8：两个提醒改读数据（含 `%s` 秒数）

**Files:** Modify `NpcStoryHandler.java`（`warnBeforeExpiry:401`、`warnDimensionGrace:391`）

**Steps:**
1. 抽一个共用方法 `remind(player, warnBeforeTicks, warnText, warnKey, remainingTicks, builtinKey)`：
   `warnBeforeTicks` 为 0/空 ⇒ 不提醒；`warnText` 优先（`String.format` 填秒数，没写 `%s` 就原样）⇒ 否则 `warnKey` ⇒ 否则**内置默认键**
2. 租约缺省取 `Config.NpcStory.expiryWarningTicks`（保留 D11 兜底）；维度缺省取常量 600
3. 维度提醒只在 `enforce = true` 且 `remaining <= warnBeforeTicks` 时发

**Verification:** `gradlew build`；实机（用户）：把 `warn_before_ticks` 调大应能看到提醒提前出现

### T9：加载后一次性自检（进度 id 是否存在）

**Files:** Modify `NpcStoryHandler.java`（新增字段 + 在巡检入口调用）

**Steps:** 记录 `NpcStoryLoader.INSTANCE.reloadStamp()`；不同则做一次自检：逐条剧情查 `server.getAdvancements().get(start/end)` ⇒ 缺失即 **ERROR**（点名文件/剧情/缺哪个 id）并把该剧情标记为"不生成分身"（fail-closed）；`revokeStory` 里那条"advancement 不存在"的日志从 WARN 升为 **ERROR**

**Verification:** `gradlew build`；实机：故意把 `end_advancement` 写错 ⇒ 日志出现 ERROR

### T10：内置提醒文案中性化（中英各两条）

**Files:** Modify `src/main/resources/assets/beloong/lang/zh_cn.json`、`en_us.json`

**Steps:** `beloong.npc.story.expiring` / `beloong.npc.story.outside_dimension` 改成**不含地名**的中性文案（"龙宫"移入 T4 的 `warn_text`）；两语言键集合保持一致

**Verification:** `python stage_invariants.py`（⑬ 语言键）全绿

### T11：批次② 验证与提交

**Verification:** 四套验证全绿 ⇒ 提交

---

# 批次③ 整合包迁移与收尾

### T12：迁移整合包 `npc_story/mo.json`（**备份写在写盘函数内**）

**Files:** Modify `D:\AAA_testclient\.minecraft\versions\BeLoong 1.4\kubejs\data\beloong\beloong\npc_story\mo.json`

**Steps:** 改成新结构（`ticks: 72000` 显式 · `keep_after_finish: false` · `host = beloong:loong_palace` · `enforce: true` · `grace_ticks: 6000`）；备份进 `kubejs/_backup_20261001/`（沿用追加 `.vN` 的做法）；改完**通知用户 `/reload`**

**Verification:** 用户 `/reload` 后日志出现 `npc stories: 1 file(s) scanned, 1 story(ies) loaded`（若是 `0 loaded` 必有一条点名到键的 ERROR）

### T13：活文档 + memory + 收尾审查

**Files:** Modify `docs/NPC系统总设计.md`（§十二 补 lease/dimension 结构、离线结算规则、"报错不静默"的校验策略）· `memory/decisions-log.md`

**Steps:** 更新文档与 memory；派子代理做收尾审查（对着本计划与设计文档）；Critical/Important 全修

**Verification:** 审查报告写入 `docs/reviews/`；四套验证全绿

---

## 风险

| 风险 | 应对 |
|---|---|
| **破坏性迁移** | 代码与两份数据**同批**；T4 与 T1/T2 同一提交；T12 紧跟其后 |
| 进度 id 无法在加载期校验 | 落到 T9 的"加载后一次性自检"，ERROR 点名（不与并行重载顺序较劲）|
| `host` 指向的维度暂时不存在（数据包加载顺序）| 加载期 `LEVEL_STEM` 查询（reload 时注册表已完整）；确实查不到即拒绝并要求作者改 |
| `Optional<Long>` 语义被后人误改回带缺省 | T5 的守卫断言"缺省即为 Optional.empty()" |
| 离线清理依赖 `host` | 无 `host` 的剧情离线清理不生效 —— 文档写明，加载期不报错（合法退化）|
| 项目无测试套件 | 验证 = 编译 + 两套不变量 + 三探针 + 实机 |

## 实机验收清单（交给用户）

1. `/reload` ⇒ 日志 `1 file(s) scanned, 1 story(ies) loaded`（若 `0 loaded`，ERROR 会点名到具体键）
2. **故意写错一个字段名** ⇒ 整文件被拒 + ERROR **附"旧写法→新写法"提示**
3. **故意写 `enforce: true` 但不写 `host`** ⇒ 整文件被拒 + ERROR
4. 拿新分身 ⇒ 看日志 `lifetime: 72000 ticks`
5. **离线结算（核心）**：拿新分身 ⇒ 退出游戏 ⇒ 等租约到期（可临时把 `ticks` 调小）⇒ 日志出现 **`reason: timeout_offline`** ⇒ 重登 ⇒ 进度被撤、锚点重现
6. **`keep_after_finish: true` 的剧情不参与离线清理**（用模组自带数据验）⇒ 退出后到期，分身**仍在**
7. 提醒数据驱动：改 `warn_before_ticks` / 写 `warn_text` ⇒ 生效；写一个不存在的 `warn_key` ⇒ 屏幕上出现原始键名（这是唯一无法服务端校验的一类）

---

# 执行记录

## 批次① 结构与校验 —— **已完成**

| 任务 | 状态 |
|---|---|
| T1 `NpcStory` 嵌层记录（`Lease` / `Dimension` + 委托访问器）| ✅ |
| T2 加载器：递归未知名 + 旧写法迁移提示 + 严格取值 + `host` 存在性 + `bindRegistryAccess` | ✅ |
| T3 `BeLoongCore` 注入 `event.getRegistryAccess()` | ✅ |
| T4 自带 `mo.json` 迁移（含两处 `warn_text`）| ✅ |
| T5 守卫 ⑰ 升级为**逐层**对齐 + 数据/schema 一致性 | ✅ |

**实施期发现（都已处理）**

1. **嵌层会让旧访问器消失** ⇒ `NpcStoryHandler` 有 11 处调用 ✗。为让批次① 能独立编译验证，
   `NpcStory` 保留了**最小委托访问器**（`lifetimeTicks()` / `keepAfterFinish()` / `requiredDimension()` …），
   数据源仍然只有一处（`lease` / `dimension` 组内）。
2. **静态初始化顺序**：`CODEC` 的初始化会触发嵌套 record 的静态初始化，而它们的 `DEFAULT` 要读外层的常量
   ⇒ 四个常量必须声明在 `CODEC` **之前**（否则读到 0）。已加注释钉住。
3. ⚠️ **我自己写错了一个工具方法**：`isValidKey` 里莫名其妙引用了 `CgRegistry`（编译能过、语义荒谬 ✗）
   —— **编译验证抓不到这类错误**，是我自己回看发现的。已改成 `ResourceLocation.tryParse(key) != null`。
   📌 教训：编译通过 ≠ 逻辑正确；工具方法要自己念一遍语义。
4. 守卫 ⑰ 里新增的"**自带 mo.json 的键都在 schema 内**"这一条，正好能机器化地抓住
   "改了代码忘改数据"（本轮最危险的中间态）—— 它现在是对**两份真实数据文件**的静态等价校验。

## 批次② 离线结算与数据驱动提醒 —— **已完成**（`693da47`）

7 条新守卫全 PASS：`timeout_offline` 独立 reason / `keep_after_finish` 跳过离线结算 /
owner 遍历前快照 / 一次性自检存在 / 两个提醒的提前量与字面文案都读数据 / 内置文案不含地名。

## 批次③ 整合包迁移与收尾 —— **已完成**

- **T12**：整合包 `mo.json` 迁移到新结构（`ticks: 72000` · `keep_after_finish: false` ·
  `host = beloong:loong_palace` · `enforce: true` · `grace_ticks: 6000`）；
  旧版已备份进 `kubejs/_backup_20261001/`（追加 `.vN`，保留历次改动前版本）。
- **T13**：活文档 §12.7（离线结算 + 报错不静默）+ memory 第二十三则。

**两份数据的迁移对照（关键）**

| | 迁移前（旧平铺）| 迁移后（嵌层）|
|---|---|---|
| 模组自带 | `lifetime_ticks: 72000` `keep_after_finish: true` `required_dimension` `dimension_grace_ticks: 1200` | `lease{ ticks: 72000, keep_after_finish: true, warn_text: … }` + `dimension{ host, enforce: true, warn_text: … }`（宽限走新缺省 6000）|
| 整合包 | `ticks: 72000`（测试期）`keep_after_finish: false` `host` `enforce` `grace: 200`（测试期 10 秒）| `lease{ ticks: 72000, keep_after_finish: false }` + `dimension{ host, enforce: true, grace_ticks: 6000 }` |

⇒ **要快速验 D2（离开维度规则）**：把整合包里的 `grace_ticks` 临时改成 `200`（10 秒）再 `/reload`。

## 实机验收清单（交给用户）

1. `/reload` ⇒ 日志 `npc stories: 1 file(s) scanned, 1 story(ies) loaded`
2. **故意写错一个字段名**（如把 `ticks` 写成 `tick`）⇒ 整文件被拒 + ERROR 点名到 `lease.tick`
3. **故意写旧版平铺**（如顶层写 `lifetime_ticks`）⇒ 被拒 + ERROR **附"应写成 lease.ticks"**
4. **故意 `enforce: true` 却不写 `host`** ⇒ 被拒 + ERROR
5. **离线结算（核心）**：拿新分身 ⇒ **退出游戏** ⇒ 等租约到期（临时把 `ticks` 调到 600 = 30 秒）⇒
   日志出现 **`reason: timeout_offline`** ⇒ 重登 ⇒ 进度被撤、锚点重现
6. **`keep_after_finish: true` 不参与离线清理**（用模组自带数据验）⇒ 退出后到期，分身**仍在**
7. 提醒数据驱动：调大 `warn_before_ticks` 应更早看到提醒；写 `warn_text` 覆盖内置文案；
   写一个不存在的 `warn_key` ⇒ 屏幕上出现原始键名（唯一无法服务端校验的一类）

## 收尾审查 —— **已完成**

**1 Critical / 3 Important / 2 Minor，全部已修。**

**Critical 值得单独记一笔**：`dimension.enforce` 在运行期**从未被读** —— 我把数据拆成了
`host` + `enforce`，却忘了把**行为**挂到 `enforce` 上（判断"是否离开有效维度"用的仍是"host 是否存在"）
⇒ `enforce: false` 形同虚设，恰好坑死 D4 想救的那类"要 host、不能要 enforce"的长流程。
📌 **教训：拆字段时必须同时重接"读它的那段行为"** —— 只改 schema 不改判断点，
不报错、不崩溃、只是"配置写了不起作用"。已加守卫断言钉死（`⑰` 里带 ★ 的那条）。

**同时修掉**：T9 的 fail-closed 真正落地（缺失进度 id 的剧情**不生成分身**，不只是打日志）·
`cg` 改为加载期 ERROR（点名到文件名/名字/已注册名录，但不拒绝文件）· 两处旧字段名注释 ·
自检移到总开关闸门之前 · `Dimension#warnBeforeTicks` 的 javadoc 与 `Lease` 统一口径。

## 用户实测结论 —— **通过**

用户在整合包里实机验过：离线结算、报错不静默（错字段名 / 旧写法 / `enforce` 无 `host` 各自被拒并点名）、
两个提醒的数据驱动、以及两份 `npc_story` 的新结构。**本轮收尾成立。**

## 收尾补项（实测之后追加，已提交 `4391abb`）

1. **本剧情专用翻译键**：`beloong.npc.story.mo.expiring` / `beloong.npc.story.mo.outside_dimension`
   —— 写在**模组**语言文件里（键由客户端解析，必须随 jar 分发）；整合包可在自己的 assets 里写同名键覆盖。
2. **两份 `mo.json` 的所有可写字段显式写出**（不依赖缺省），差别只在有意为之的
   `lease.keep_after_finish`（模组自带 `true` 保 D6；整合包 `false` 参与离线结算）。
3. 模组语言键总数 297，中英逐位一致；jar 已重建替换并确认新键在包内。
