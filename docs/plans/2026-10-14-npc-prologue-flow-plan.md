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
**Steps:** 挂 `ServerTickEvent.Post`；对每个 `ServerPlayer`，按 **D14 的原子顺序**（全在同一 tick，绝不跨 tick）：
① **前置条件**（活着 / 非旁观 / 未骑乘 / 不在 CG 中）不过 ⇒ 本 tick 跳过、下 tick 再看；
② 未获得 `root` ∧ `player.tickCount >= secondsToTicks(配置)`；
③ **不在龙宫 ⇒ 按 D13 用 `teleport` 包送回龙宫**：`TeleportTarget.toLoongPalace(player.server.getLevel(TeleportTarget.LOONG_PALACE_LEVEL))` + `DimensionTeleport.toTarget(...)` + `changeDimension`（落点是**实地**：地图由 LockDown 加载）；API 返回 `null` ⇒ 本 tick 不传送、下 tick 重试；
④ **同一 tick**：`ensureDouble(player, player.serverLevel(), …)` —— 传送后 `serverLevel()` 已是龙宫 ⇒ 找到锚点 ⇒ 分身精确落在锚点位置与朝向；
⑤ **确认拿到分身之后**才 `player.getAdvancements().award(root)`；若走了"身前 4 格"兜底（自带 WARN）**也照常继续**（绝不卡住玩家）；
⑥ `¬1_0` ⇒ `cg.play(player, 分身)`。
**附带：** 更正 `TpLoongPalaceEffect` 的类注释 —— 写清"落点必定是实地（地图由 LockDown 加载）、`fallbackY` 只是生成器给不出 Y 时的兜底"，并**同步更正**两处带偏人的旧说法（`docs/龙宫维度总设计.md` 与 `2026-09-16-loong-palace-teleport-api-design.md` §7）。
⚠️ 本注释曾被**两度**改错（据那两处旧说法把正确结论删掉）⇒ 已在注释里写明"勿再据旧说法改本条"。
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
⑤ `NpcPrologueHandler` 存在，且**顺序铁律**成立：`ensureDouble` 的调用**早于**授予 `root` 的那一行（**按行号比较**）；
   并断言"授予 `root` 之前有传送回龙宫的调用"；
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
4. **完整重置（人在龙宫外）**：把 `dimension.grace_ticks` 临时调小 ⇒ **出龙宫到主世界**等它撤回 ⇒
   **应被自动传送回龙宫**（D6/D13）⇒ **重播登场 CG**（D1 期望 ✓）⇒ 全程不需要玩家自己走回去；
4b. **完整重置（人在龙宫内）**：同样撤到 `root` 后 ⇒ 应**当场**重新开场（无需传送）；
5. `/beloong npc debug` 开 ⇒ 看得见**锚点**与**所有分身**、**点不动别人的分身**；关 ⇒ 恢复；
6. **开场前看不见锚点**（D8）；
7. 回归：路线 / 表情 / 通关回收 / 租约 / 离线结算 一切照旧。

---

## 备注：一次返工与它的真实教训（2026-10-14）

**事实**：**龙宫不是虚空** —— 它是 **LockDown 模组加载的固定地图**（整合包预建地图）。
本模组的**生成器**是 `flat + layers: []`（不生成方块 ✓），但这**不等于**"世界里没有地面" ✗：
实际地图由 LockDown 提供 ⇒ **落点是实地**，玩家不会掉进虚空，也不需要"靠飞行才能进入"。
`fallbackY` 的准确含义是"**生成器**给不出高度图时的显式兜底"，**不是**"落在空中"。

**返工过程**：用户先要求更正注释 ✓ 我第一次改对了 ✓；随后我读到 `docs/龙宫维度总设计.md` 里
"所有落点都没有地面可站，玩家必须靠飞行——这与序章「学习飞行」的教学目标一致"，
**误把这句话当成"落点在空中"的证据** ✗，于是把正确的改动**推翻**了 ✗；用户第三次澄清后我才改回来。

**教训（三条）**
1. **"生成器不生成" ≠ "世界里没有地面"** —— 依赖模组（LockDown）会**复制模板地图**进去
   （反证：`2026-08-27-lockdown-template-migration-design.md:11` 说没有模板数据时进入才是虚空）；
   读维度/群系文档时必须区分**生成器层**与**实际地图层**。
2. **历史文档记录的是"当时的状态"，不是"恒久的事实"** ——
   `2026-09-16-loong-palace-portal-design.md:249` 的"进门会坠落"是**当时实测属实**的
   （后来靠改配置/建平台解决）。把"当时属实"读成"现在也如此"或"纯属写错"都是错的 ✗ ——
   正确做法是**查该现象是否已被后续变更解决**（变更记录/后续计划文档）。
3. **找到"权威出处"不等于找到"事实"** —— 文档里的**因果性断言**（"因此玩家必须靠飞行"）
   尤其要拿实际现象与变更史核对；本次连带把我自己的"更正"也改准了一遍（原来写成"那两处文档说法不成立"，
   属于用过头的判断去覆盖）。
4. **不要用"新一轮更正"去覆盖"上一轮更正"时不留痕迹** —— 本次三轮变动全部留了日期与理由，
   最终版能看出每一轮的判断与依据（含我两次误判的来源），这样下一个人不会被带偏。

---

# 执行记录（2026-10-14 全部完成）

| 任务 | 结果 | 关键点 |
|---|---|---|
| T1 新增 `1_0` | ✅ | trigger `impossible` · parent `root` · 无提示 · 图标沿用 `beloong:beloong_logo`（不敢用没见过的 item id —— 会让整个进度加载失败）· 中英各两条语言键「末的登场 / Mo's Entrance」|
| T2 `1_1` 的父 → `1_0` | ✅ | 撤回沿 `parent()` 上溯 ⇒ `1_0` 必须真在链上 |
| T3 `root` 触发 → `impossible` | ✅ | 删除坐标盒 + 附 `_comment` 说明 |
| T4 两份对话窗口 → `[1_0,1_1)` | ✅ | 模组侧 + 整合包侧（整合包侧写盘前已备份到 `_backup_20261014/`）|
| T5 配置 `prologueDelaySeconds` | ✅ | 默认 5 秒 · 中英两条配置界面键（该族共 8 条）|
| T6 序章处理器 | ✅ | `npcstory/NpcPrologueHandler`：前置 → 等秒数 → 补授兜底 → 不在龙宫先传送 → **同一 tick 先分身再授予 root** → 播 CG |
| T7 CG 完成授予 `1_0` | ✅ | `CgAnimation#completionAdvancement`（只声明）+ `CgCompletionHandler`（计时/掉线补授）+ `play` 里 6 处结算 + `MoEntrance` 声明 |
| T8 事件路径交出 CG | ✅ | `onAdvancementEarned` 只留"确保分身"；`ensureDouble` 改 static + 加 `ensureDoubleFor` |
| T9 可见性 | ✅ | 调试视角短路 + **有剧情声明的锚点永不显示**；无剧情声明的 NPC（地黄龙）仍恒可见 |
| T10 `beloong npc debug` | ✅ | `NpcDebugView`（内存缓存 + `getPersistentData()` 持久化）· 只改可见性不改交互 · 无权限检查 |
| T11 守卫 | ✅ | stage：链插入 `1_0`、六条新断言、对话窗口三条、"CG 唯一播放者"改指序章；route ⑧：`root` 触发改 `impossible`、作废触发盒防呆、改指 `1_0` 防呆 |
| T12 文档 | ✅ | 活文档新增 **§12.9 开场流程**（拓扑/流程/闸门/两条铁律/调试指令）|
| T13 验证与提交 | ✅ | `gradlew build` ✓ · stage 131 PASS/0 FAIL ✓ · route 53 PASS/0 FAIL ✓ · cg PASS ✓ · 四个探针 GREEN ✓ · jar 重建并装机（4 个新件已在包内）|

## 实施期的两处设计细化（与本文档原稿的差异）

1. **CG 的"完成计时"没放进 `CgAnimation`** —— 那个类的契约是"刻意不持有任何运行期状态"，
   而"等 CG 放完"是定时状态 ⇒ 计时与授予放到新的 `CgCompletionHandler`，
   CG 类只保留 `completionAdvancement()` 这个**声明式**钩子。用户的原始要求（"写在 CG 那个类里面"）
   在**声明**这一层完全满足；计时放在外面是为了不违反既有契约。
2. **授权范围**：用户 2026-10-14 授权直接改 `cg/CgAnimation.java` 与 `cg/instances/MoEntrance.java`
   （原计划标注为"需与 CG 会话协调"）⇒ 已按授权实施，**需事后告知 CG 会话**。
