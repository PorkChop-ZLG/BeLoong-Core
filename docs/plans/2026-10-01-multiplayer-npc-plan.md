# 多人兼容 NPC 系统 实施计划

**Goal:** 让多人服务器上每个玩家都能独立走完 NPC 剧情 —— 用"公共锚点 + 每人一份的私有分身 + 原版可见性钩子 + 租约清理"实现。

**Architecture:** 不新增实体类型（私有分身复用 `beloong:mo` + owner NBT）。可见性由覆写 `Entity#broadcastToPlayer` 的**纯函数**决定；"谁该有分身"由新数据类型 `npc_story` **声明**，一个对账器负责让世界与声明一致；一次性演出（登场 CG）走**事件路径**；分身带**租约**（时间/会话/空间），清理与"沿剧情链撤回进度"**原子完成**。

**Approach:** 方案 A（已批准）：按**状态所有权**分类 / 可见性用原版钩子（不自造系统）/ 事实来源用**玩家已有的原版进度**（不新增每玩家持久状态）/ 数据驱动 `npc_story` / 撤回沿 `end_advancement` 的父链走回 `start_advancement`。

**设计文档：** [`2026-10-01-multiplayer-npc-design.md`](2026-10-01-multiplayer-npc-design.md)（已批准，含 D1–D21）
**状态：** **全部完成** —— 4 批次 / 10 任务已全部执行完毕，并已通过收尾审查（2 Critical + 3 Important + 3 Minor 全修）。收尾审查报告：`docs/reviews/2026-10-01-multiplayer-npc-code-review.md`。⚠️ **实机验收仍待用户执行**（清单见文末「执行记录」的批次②③ 两节）。

---

## 任务总览

| 批次 | 任务 | 一句话 | 需要实机？ | 状态 |
|---|---|---|---|---|
| **① 地基** | T1 | `npc_story` 数据层（Codec + Loader + `mo.json`）| 否 | ✅ 完成 `4532c1a` |
| ① | T2 | `NpcEntity` 归属 + 可见性（owner NBT + `broadcastToPlayer`）| 否（回归：地黄龙）| ✅ 完成 `c80a238` |
| **② 私人化闭环** | T3 | 分身管家：生成（锚点同位置/朝向 + 兜底）| **是** | ✅ 完成 `308f22e` |
| ② | T4 | CG 移交给分身（删 ±48 搜索）+ 交互闸门 | **是** | ✅ 完成 `308f22e` |
| **③ 租约与清理** | T5 | 租约写入实体（`bornAt` + **`expireAt`**）| 否 | ✅ 完成 |
| ③ | T6 | 对账器（登录/事件/低频巡检 + D21 重置 + 重复检测）| 部分 | ✅ 完成 |
| ③ | T7 | 清理 + 撤回（沿父链、**原子**）| **是** | ✅ 完成 |
| ③ | T8 | 清理前提示 + 英文审计日志 + 配置项 | **是** | ✅ 完成 |
| **④ 收尾** | T9 | 不变量守卫 9 条（⑮ 节）| 否 | ✅ 完成（脚本不在仓库内）|
| ④ | T10 | 活文档 + memory + 收尾审查 | 否 | ✅ 完成 `eecd9ce` + `3ef05be` |

**每批的验证口径**：`.\gradlew.bat build` ⇒ BUILD SUCCESSFUL ／ 两套不变量全绿 ／ 三探针全绿 ／ 标注"需要实机"的任务由用户在游戏里确认。

---

## 批次 ① 地基

### T1：`npc_story` 数据层

**Files:**
- Create: `src/main/java/com/zonlong/beloong/npcstory/NpcStory.java`
- Create: `src/main/java/com/zonlong/beloong/npcstory/NpcStoryLoader.java`
- Create: `src/main/resources/data/beloong/beloong/npc_story/mo.json`
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`（**只加一行**注册 —— 本项目在 2026-10-01 犯过"同一行加两遍"的错，守卫会检查）

**Steps:**
1. `NpcStory` record + `CODEC`（`RecordCodecBuilder`，默认值用 `optionalFieldOf(name, default)`，与 `NpcRoute.arrivalRadius` 同款）：
   `startAdvancement`(ResourceLocation) / `endAdvancement`(ResourceLocation) / `spawn`(String，默认 `"anchor"`，只接受 `anchor`，未知值 ⇒ 整文件拒绝) / `cg`(Optional&lt;String&gt;) / `lifetimeTicks`(long，默认 72000，`-1`=永久) / `clearOnLogout`(boolean，默认 false) / `requiredDimension`(Optional&lt;ResourceLocation&gt;) / `dimensionGraceTicks`(long，默认 1200)
2. `NpcStoryLoader`：照 `NpcRouteLoader`（`SimpleJsonResourceReloadListener`，目录字符串 `beloong/npc_story`，`INSTANCE`、`get(EntityType<?>)`、整文件失败隔离 `ifError/ifSuccess`、一条英文 INFO 统计日志）
3. `mo.json`：设计 §1.4 那份（`root` / `2_1` / `anchor` / `mo_entrance` / 72000 / false / `beloong:loong_palace` / 1200）
4. `BeLoongCore` 的 `addServerReloadListeners` 里注册（一行）

**Verification:**
- `.\gradlew.bat build` ⇒ BUILD SUCCESSFUL
- 启动/`/reload` 日志出现 loader 的英文 INFO
- T9 会加守卫：`npc_story` 的 `start/end_advancement` 必须与对话数据里的闸门**逐字一致**

---

### T2：`NpcEntity` 归属 + 可见性

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/entity/NpcEntity.java`
- Modify: `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueStage.java`（把既有的私有进度查询提成一个 **public static** 方法，避免复制实现）

**Steps:**
1. NBT 键 `OWNER_NBT_KEY = "BeloongOwner"`；字段 `@Nullable UUID owner` + `owner()` / `setOwner(@Nullable UUID)`（服务端守卫：`level().isClientSide()` 早退，照 `setRoute`）/ `isPrivate()`
2. `addAdditionalSaveData`：`if (owner != null) compound.putUUID(OWNER_NBT_KEY, owner)`；
   `readAdditionalSaveData`：`if (compound.hasUUID(OWNER_NBT_KEY)) this.owner = compound.getUUID(OWNER_NBT_KEY)`
   （向后兼容：旧存档没有这个键 ⇒ `null` ⇒ 公共锚点）
3. `@Override public boolean broadcastToPlayer(ServerPlayer player) { return this.visibleTo(player); }`
4. `protected boolean visibleTo(ServerPlayer player)` —— **纯判定：不写实体字段、不发包**
   - `NpcStory story = NpcStoryLoader.INSTANCE.get(this.getType())`；`story == null` ⇒ `return true`（地黄龙等行为不变）
   - `if (owner != null) return player.getUUID().equals(owner);`
   - 否则 ⇒ `return !NpcDialogueStage.isEarned(player, story.startAdvancement());`
   - 注释写明：这里的进度查询用原版 `getOrStartProgress`（它会对尚未追踪的进度**幂等**地建一个进度对象）——
     这正是 `NpcDialogueStage:60` 一直在用的写法，属于项目既有先例；**它不改游戏状态**

**Verification:**
- `.\gradlew.bat build` ⇒ BUILD SUCCESSFUL
- 守卫：owner 的 NBT 读写**对称**；`broadcastToPlayer` 覆写**存在**且方法体内**无赋值**（文本断言）
- 实机回归：地黄龙照旧可见可交互；未获 `root` 时 `beloong:mo` 可见

---

## 批次 ② 私人化闭环

### T3：分身管家 —— 生成

**Files:**
- Create: `src/main/java/com/zonlong/beloong/npcstory/NpcStoryHandler.java`
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`（注册到 `NeoForge.EVENT_BUS`）

**Steps:**
1. `@SubscribeEvent onAdvancementEarned(AdvancementEvent.AdvancementEarnEvent)`：遍历全部 story，命中 `startAdvancement` ⇒ `ensureDouble(player, story)`
2. `ensureDouble`（**幂等**）：
   - a. **查重**：一次 `serverLevel.getAllEntities()`（`ServerLevel.java:1602`）过滤 `getType() == story 的实体类型 && owner == player` ⇒ 已有 ⇒ 直接复用它，返回
   - b. 同一次遍历里找**锚点** = 该类型的 `owner == null` 实例（多个 ⇒ 取离玩家最近的 + WARN）
   - c. 放位置：有锚点 ⇒ 复制其**位置与朝向**（`moveTo` + `setYRot/setYHeadRot`，身体朝向照 `NpcEntity` 现有写法）；
     **无锚点** ⇒ 沿玩家视线**身前 4 格** + WARN —— ⚠️ **不可放在玩家脚下**：`CgContext.of` 在"观察者与锚点水平重合"时返回 `null`（方向退化）⇒ CG 会中止
   - d. `setOwner(player.getUUID())`（租约字段由 T5 负责）
   - e. `serverLevel.addFreshEntity(double)` ⇒ 返回它
3. 任何路径都不得产生第二只（T9 守卫断言"生成前先查重"）

**Verification:** build ✓ ／ **实机**：获得 `root` 的那一刻，玩家眼里 mo 的**位置与外观无变化**（因为分身生成在锚点处）⇒ 之后对话、走路、表情照旧

---

### T4：CG 移交给分身 + 交互闸门

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/npcstory/NpcStoryHandler.java`
- Delete: `src/main/java/com/zonlong/beloong/cg/MoEntranceTrigger.java`（职责被 story 驱动取代；**删除**而不是留着两套入口）
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`（撤销它的注册）
- Modify: `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueHandler.java`

**Steps:**
1. 生成后：`story.cg()` 非空 ⇒ `CgRegistry.byName(name)` 命中 ⇒ `cg.play(player, theDouble)`；未命中 ⇒ **英文 WARN，不影响分身**
2. 删除 `MoEntranceTrigger` 的"±48 就近搜索 `MoEntity`"与 `SEARCH_RADIUS`，以及"找不到就只 WARN、不重试"那条裁定（设计 D9 已推翻）
3. `NpcDialogueHandler`：取到 `target` 后加闸门 ——
   `if (target instanceof NpcEntity npc && npc.isPrivate() && !player.getUUID().equals(npc.owner())) return;`
   （防改包/去同步硬闯；正常玩家看不到别人的分身，走不到这里）

**Verification:**
- build ✓
- **实机**：登场 CG **由分身播放**，观感与旧版一致；旧的 `no beloong:mo is within 48 blocks` WARN 不再出现
- 守卫：CG 调用只出现在**事件路径**里（对账器补生成时不得调 CG）

---

## 批次 ③ 租约与清理

### T5：租约写入实体

**Files:** Modify `entity/NpcEntity.java`、`npcstory/NpcStoryHandler.java`

**Steps:**
1. NBT 键 `BORN_AT_NBT_KEY = "BeloongBornAt"`、`EXPIRE_AT_NBT_KEY = "BeloongExpireAt"`（`long`；缺省 0 = 无租约）+ `bornAt()` / `expireAt()` / `setLease(long bornAt, long expireAt)`（服务端守卫）
2. 生成时折算：`lifetimeTicks < 0` ⇒ `expireAt = Long.MAX_VALUE`（永久）；否则 `expireAt = now + lifetimeTicks`
   ⚠️ **必须存绝对时刻而不是"只存生成时刻"**：设计 D14 承诺"以后改数据里的时长不会追溯影响已存在的分身" ——
   若每次现读数据里的时长，改数据就会影响老分身 ⇒ 承诺失效

**Verification:** build ✓ ／ 守卫：两个 long 字段读写对称 + "生成时同时写 `bornAt` 与 `expireAt`"

---

### T6：对账器

**Files:** Modify `npcstory/NpcStoryHandler.java`（或新建 `NpcStoryReconciler.java`）、`BeLoongCore.java`（server tick）、`Config.java`（巡检间隔）

**Steps:**
1. 触发点：`PlayerLoggedInEvent` + 进度变化事件 + `ServerTickEvent.Post` 每 N tick（默认 100）
2. 按设计 §3.3 决策表逐玩家 × 逐 story 判定：
   - `start` 未获得 ∧ 有分身 ⇒ 异常 ⇒ 删除 + WARN
   - `start` 已获 ∧ 无分身 ⇒ **D21 重置**（T7 的撤回 + WARN）
   - `start` 已获 ∧ 有 ∧ `now > expireAt` ⇒ 超时 ⇒ 进行中 ⇒ 清理 + 撤回；**已完成 ⇒ 只清理不撤回** + INFO
   - `requiredDimension` 不匹配且超过 `dimensionGraceTicks` ⇒ 清理 + 撤回
   - `clearOnLogout` 且离线超过宽限 ⇒ 在**下次登录时**处理（离线时拿不到 `ServerPlayer`）
   - 已完成（有 `end`）⇒ 保留（D6）
3. 效率：**每个维度一次** `getAllEntities()` 建 `owner → entity` 映射，再逐个在线玩家比对（O(实体 + 玩家)）
4. 重复分身 ⇒ 保留最近生成的、其余删除 + WARN

**Verification:** build ✓ ／ 实机清单见设计 §6.2 的 6/7/8 ／ 守卫：对账器幂等、CG 不在对账路径里

---

### T7：清理 + 撤回（**原子**）

**Files:** Modify `npcstory/NpcStoryHandler.java`（或 `NpcStoryReconciler.java`）

**Steps:**
1. `revokeStory(player, story)`：
   ```
   holder = 取 end_advancement 的 AdvancementHolder（找不到 ⇒ WARN + 返回）
   while (holder != null):
       player.getAdvancements().revoke(holder, false)     // false = 不弹 toast
       if (holder.id().equals(story.endAdvancement())) break 时注意：先撤 end 再上溯
       if (holder.id().equals(story.startAdvancement())) break
       holder = holder.parent()                           // 走不到 start ⇒ WARN（链被改坏）
   ```
2. `clearDouble(entity)` ⇒ `entity.discard()`
3. **原子**：撤回与清理在**同一次对账、同一方法**里完成，不得只做一半（只做一半就是"有进度没分身"的软锁）
4. 英文审计日志：原因（`timeout` / `dimension` / `logout` / `duplicate` / `orphan`）+ 玩家名 + story 名 + 实体 UUID

**Verification:** build ✓ ／ **实机**：剧情中途走出龙宫维度超过宽限 ⇒ 分身消失 + 两条回复重新出现 ⇒ 回入口重新获得 `root` 再走一遍 ／ 守卫：撤回走父链的实现存在、`revoke` 与 `discard` 在同一方法内

---

### T8：清理前提示 + 配置项

**Files:** Modify `Config.java`、`npcstory/NpcStoryHandler.java`、`src/main/resources/assets/beloong/lang/{zh_cn,en_us}.json`

**Steps:**
1. 配置：总开关（默认 true）、巡检间隔（默认 100 tick）、清理前提示提前量（默认 600 tick = 30 s）
2. 提示：当 `expireAt - now <= 提前量` 时给玩家一条 actionbar（文案走**语言键**：`beloong.npc.story.expiring`，两语言各一条）
3. 日志全部**英文**

**Verification:** build ✓ ／ 守卫 ⑬（Java 引用的语言键两语言都有）+ 键集合一致 ／ **实机**：把提前量调大 ⇒ 能看到提示

---

## 批次 ④ 收尾

### T9：不变量守卫（7 条）

**Files:** Modify `D:\Minecraft\tools\YSMParser\stage_invariants.py`
（⚠️ **验证脚本在仓库之外** —— 见本项目 `memory/project-context.md` 的记录；仓库里 `.java` 注释用 `tools/YSMParser/...` 这种"相对工作区根"的写法引用它们）

**Steps:** 加设计 §6.1 的 7 条：owner NBT 对称 ／ `broadcastToPlayer` 覆写存在且方法体内无赋值 ／ 剧情链线性且无剧情外子进度 ／ 对账器幂等 ／ CG 只在事件路径 ／ 租约两字段对称 ／ `npc_story` 的闸门与对话数据逐字一致

**Verification:** `python stage_invariants.py` ⇒ 全部通过；`python route_invariants.py` ⇒ 全绿（回归）；三探针全绿

---

### T10：活文档 + memory + 收尾

**Files:** Modify `docs/NPC系统总设计.md`（新增"多人兼容"一节 + 更新 §八 资源清单）、`memory/decisions-log.md`、`memory/learned-patterns.md`

**Steps:** 照本项目"收尾"惯例：实施期回填（计划里的偏离）→ 收尾审查（1 Critical / N Important / N Minor）→ 审查报告 `docs/reviews/2026-…-multiplayer-npc-code-review.md` → memory 回填

**Verification:** `.\gradlew.bat build` + 两套不变量 + 三探针 + 文档与代码一致（逐条核对，本项目最在意这一类）

---

## 已批准的延后项（Deferrals）

| 项 | 理由 |
|---|---|
| `spawn` 的**坐标形态**（`[x,y,z]` + `dimension`）| 当前内容只需要 `anchor`；锚点缺失时用"玩家身前 4 格"兜底已够。字段先只接受 `"anchor"`，未知值整文件拒绝（不会静默失效）|
| **地黄龙**接剧情（给它一份 `npc_story` + 对话数据）| 框架支持，但当前没有需求；接的时候只加数据文件 |
| **锚点自动重建**（被删后）| 属于运维事项；本设计只 WARN |
| **多人共看同一段演出** | 用户明确：暂不需要 |
| `end_advancement` 用于"分身回收" | 当前 D6 是"保留但只对主人可见"；字段已在，将来要用随时可用 |

## 风险与已知代价

| 项 | 说明 |
|---|---|
| **`broadcastToPlayer` 的"纯"** | 里面的进度查询用 `getOrStartProgress`（幂等、会懒建进度对象，与 `NpcDialogueStage:60` 同款）⇒ 守卫断言的是"方法体内不写实体字段/不发包"，不是"不调任何有副作用的 API" |
| **可见性切换的延迟** | 在下一个追踪周期生效（非瞬时）⇒ 本设计下两者**位置重合** ⇒ 视觉无感 |
| **老存档会被重置** | 已裁定 A（D21）⇒ 升级后已获 `root` 的玩家回入口重走一遍；**开发期测试存档影响很小**，但发布前需在更新说明里提醒 |
| **多人实机验证的前提** | 需要**两个真实玩家**才能验收"B 看不到 A 的分身" ⇒ 建议 `runServer` + 两个 `runClient`（不同 `--username` 与独立 run 目录），或客户端连本体局域网 |
| **验证网在仓库之外** | `tools/YSMParser/*.py` 不在仓库里（工作区根的 `tools/` 是 gitignored）⇒ clone 到别处就没了；这是既有状态，本计划不改 |

---

## 后续

计划批准后：按批次执行（批次 ① → ② → ③ → ④），每批完成后回填本文件的执行状态，
"需要实机"的任务由用户在游戏里确认；全部完成后进入收尾（T10）。

---

## 执行记录

### 批次① 地基 —— **已完成**

| 任务 | 提交 | 验证 |
|---|---|---|
| T1 `npc_story` 数据层 | `4532c1a` | `build` BUILD SUCCESSFUL ✓ ／ jar 内三产物齐全 ✓ ／ 注册**恰好一次**（grep 复核）✓ ／ 两套不变量 + 三探针全绿 ✓ |
| T2 `NpcEntity` 归属 + 可见性 | `c80a238` | 同上全绿 ✓ ／ 自查 `visibleTo` 方法体（无字段写入）✓ |

**实施期修订 / 备注**

1. **T1 自查修正**：未知实体类型改用 `getOptional`（项目既有写法，见 `NpcDialogueEntry.decodeEntity`），
   而不是"先 `get()` 再 `containsKey()`" —— 后者会先拿到注册表默认值，读起来像收下了一个错的类型。
2. **T2 把进度查询提成公用入口**：`NpcDialogueStage.isEarned(player, id)`（复用既有的 fail-closed
   与"每个 id 只报一次"WARN），而不是复制一份实现 —— 该类注释已注明它现在同时是
   "NPC 按玩家可见性"的判定入口。
3. **给 T9 的守卫留了一条坑**：不能用朴素的 `=` 检查"方法体内无赋值" ——
   `visibleTo` 里的 `NpcStory story = ...` 是**局部变量**，会被误报。
   T9 的守卫要按"类的字段名集合 + `this.` 赋值 + 已知 mutator 调用"来判。

### 批次② 私人化闭环 —— **已完成**

| 任务 | 提交 | 验证 |
|---|---|---|
| T3 生成 + T4 CG 移交/闸门 | `308f22e` | `build` ✓ ／ 两套不变量 + 三探针全绿 ✓ ／ 旧类引用清零 ✓ |

**实施要点（与计划的差异）**

1. **T3 与 T4 合为一次提交**：CG 必须在分身生成**之后**播（相机轨迹触发即烘成世界坐标），
   两者是同一段事件路径，拆开只会让中间态更碎（这正是批次①审查 C1/C2 的教训）。
2. **删除了 `cg/MoEntranceTrigger.java`**（而不是留一个薄封装）：设计 D9 已推翻"就近搜索演员"，
   验证网的 ⑧ 节相应重写为钉"数据 + 新管家"，并新增三条守卫**防止旧做法复活**
   （不存在该文件、不存在 `SEARCH_RADIUS`/`getEntitiesOfClass`）。
3. **验证网自身也修了一次**：`route_invariants.py` 原本读已删除的文件 ⇒ 直接崩。
   ⚠️ 教训：**删类时必须连验证网一起改**，否则"绿"是假象、"红"是崩溃。
4. 补上初版漏掉的设计要求：**找到多个锚点 ⇒ 取最近 + WARN**。

### 批次③ 租约与清理 —— **已完成**

**实施要点 / 与计划的差异**

1. **`clear_on_logout = true` 实现为"退出即清"**（不是"离线超过宽限后清"）：后者需要额外记录退出时刻，
   等于为一个小开关引入新状态；这个名字本身就表达"退出就清"。我们的数据用 `false`，无行为影响。
2. **维度宽限的状态放在实体上**（`BeloongOutsideSince`）：宽限是"这一个分身"的状态，随实体存档，
   与租约其余部分同源（D14 的同一取舍）。
3. **撤回严格照原版**：`AdvancementCommands.java:448-458` 的做法是**逐个 criterion 撤**
   （`PlayerAdvancements` 只提供按 criterion 的 `revoke`）⇒ 我们照抄，不自己发明。
4. **路径事故记录**：`Config.NpcStory` 的**赋值**与**嵌套类声明**被拆到了两次执行里，
   第一次因锚点未命中而中止 ⇒ 编译报一串"找不到符号"（全是它的级联）。
   ⚠️ 教训：**成对的东西（声明 + 赋值）要么同一条命令里完成，要么先确认声明已存在**。
5. **又一次"凭记忆写锚点"** ✗：我给补丁写了个磁盘上并不存在的横梁注释（`// ===== 生成 =====`），
   直接导致脚本中止。已改为**扫描方法签名后向上找 javadoc 起点**。
   📌 本批次共因此中止 3 次 —— 教训已明确：**锚点一律从磁盘扫描，不写记忆里的文本**。

**本批实机验收清单（需要你，且要小心）**

⚠️ **本批次会"撤回进度"** —— 按 D21，凡是**已获得起点进度但没有分身**的玩家，
在登录/巡检时会被**统一按"剧情重新开始"处理**（撤回整条链）。你的 4 个测试存档里若已有剧情进度，
**首次进入时会被清掉**（这是你裁定 A 的预期行为，不是 bug）。

1. 正常流程：进龙宫 → `root` → 分身出现 → 走完第一段 → 第二段 → 终点 `sit`；全程不被清理
2. 中途离开龙宫维度超过 60 秒 ⇒ 分身被清 + 进度被撤 ⇒ 回龙宫要重新走到触发区拿 `root`
3. 把 `lifetime_ticks` 临时改小（如 600）⇒ 到点清理；**已完成剧情的玩家只清不撤**
4. 到期前 30 秒应看到 actionbar 提示（配置项可调）
5. 存档里已有的旧进度玩家 ⇒ 首次登录被重置（预期）

**本批实机验收清单（需要你）**

1. 进龙宫拿到 `root` 的**那一刻**：mo 的位置与外观**无变化**（分身生成在锚点处）
2. 登场 CG **由分身播放**，观感与旧版一致；日志里不再出现 `no beloong:mo is within 48 blocks`
3. 与它对话、点「好的」⇒ 它开始走路线 1（指派给的是**分身**，锚点没动）
4. （可选，需要两个玩家）另一名玩家看到的仍是**入口那只锚点**，看不到你的分身、也点不到它

**仍待实机确认（随批次②一并看）**

- 载入日志出现 `npc stories: N file(s) scanned, M story(ies) loaded`
- `beloong:dihuang_loong` 行为不变（可见、可交互）；未获 `root` 时 `beloong:mo` 可见

### 批次④ 收尾 —— **已完成**

| 任务 | 提交 | 验证 |
|---|---|---|
| T9 不变量守卫 9 条（`stage_invariants.py` ⑮ 节，脚本在仓库外）| —（不入库）| 9 条全 PASS ✓ |
| T10a 活文档新增 §十二 + memory 回填 | `eecd9ce` | 文档与代码逐条对齐 ✓ |
| T10b 收尾审查（子代理，对着 D1–D21）+ 全部修复 | `3ef05be` | 三套不变量 + 三探针全绿 ✓ |

**收尾审查结论**：2 Critical / 3 Important / 4 Minor —— 全部已修（1 条 Minor 接受并在注释声明）。
详见 `docs/reviews/2026-10-01-multiplayer-npc-code-review.md`。

**两条 Critical 都值得记住**（它们是"分批 + 区块加载"这两件事的复合产物）：

1. **通关玩家的进度会被静默清掉**：删除分身时正确地"只删不撤"，却没防住**下一轮对账**把"无分身"解读成
   "分身丢了"⇒ 走 D21 重置 ⇒ 通关玩家被打回起点。**教训：一个"终态"必须被每一条清理/重置规则显式豁免。**
2. **"没找到"不等于"不存在"**：`getAllEntities()` 只覆盖已加载区块，而登录时玩家常在别的维度
   ⇒ 立刻误判为"分身丢了"并撤回进度。**教训：任何"以缺失为依据"的破坏性操作，都必须先证明
   "搜索本身是可信的"**（这里用"该轮扫到了无主锚点"当判据 + 连续 3 轮阈值）。

### 全部提交一览（本特性）

| 提交 | 内容 |
|---|---|
| `66bd414` / `815dd41` | 设计文档（含 D21 修订）+ 实施计划 |
| `4532c1a` | T1 `npc_story` 数据层 |
| `c80a238` | T2 `NpcEntity` 归属 + 按玩家可见性 |
| `308f22e` | T3+T4 生成 + CG 移交 + 交互闸门 |
| `a11ec06` | T5–T8 租约 + 对账器 + 清理/撤回 + 提示/配置 |
| `eecd9ce` | T9+T10a 活文档 + memory |
| `3ef05be` | 收尾审查修复（2 Critical + 3 Important + 3 Minor）|

### 实机验收清单（合并批次②③，**待用户执行**）

⚠️ 按 D21（用户裁定 A），**已获起点进度但没有分身**的玩家会在登录/巡检时被统一重置
⇒ 现有测试存档里的旧进度**首次进入时会被清掉**（预期行为，请先备份或用新世界）。

1. 进龙宫拿 `root` 的那一刻：**mo 的位置与外观无变化**；登场 CG 由**分身**播放；日志不再有 `no beloong:mo is within 48 blocks`
2. 对话 → 「好的」→ 走路线 1 的是分身（`/data get entity @e[type=beloong:mo] BeloongOwner` 可对比）
3. 中途离开龙宫维度超过 60 秒 ⇒ 分身被清 + 进度被撤 ⇒ 回龙宫需重新拿 `root`；**宽限期内**应看到 actionbar 倒计时
4. 把 `lifetime_ticks` 临时改成 600 ⇒ 到点清理 + 到期前的 actionbar 提示；**已通关的玩家只清不撤**（C1 的修复）
5. （双人）另一名玩家只看到入口那只锚点，看不到也点不到你的分身
6. 通关玩家的分身**不再被自动清理**（D6 终态保留）—— 确认这符合预期
