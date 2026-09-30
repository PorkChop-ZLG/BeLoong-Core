# NPC 自动寻路系统 实施计划

**Goal:** 数据驱动的路线图（维度 + 路点）指派到 NPC 身上并落盘；NPC 沿路线自动寻路，除 `stop` 外不停，直到抵达终点。
**Architecture:** 见 `docs/plans/2026-09-30-npc-route-system-design.md`（§1 架构 / §2 组件 / §3 数据流 / §4 错误处理 / §5 验证）。
**Approach:** **薄 Goal + 现有命令层** —— 一个只占 `Goal.Flag.MOVE`（优先级 4）的薄 goal 只负责把"当前路点"交给
`moveTarget`；寻路仍由既有 `tickMoveCommand()` 每 20 tick 续路（**寻路一行不新写**）；攻击让位／维度挂起／抵达停止
全部由原版 `lockedFlags` 仲裁免费给出。

> ⚠️ **与技能默认流程的偏离**：本项目**没有测试源集**（`Task :test NO-SOURCE` 是既有事实）⇒
> 每个任务的验证 = **构建 + 脚本对账 + 实机清单**，并给出确切命令。

> ⚠️ **执行纪律（前几轮的教训，本计划强制遵守）**：① 改数据文件前**先读当前内容**，几百字节的文件**整份重写**；
> ② **每一段脚本的退出码都纳入判断**，任一段失败 ⇒ **不继续、不提交**；③ 注释与代码必须同批一致
> （本项目的审查反复抓到"注释与事实不符"）；④ JSON 注释里**不要带引号**。

---

## 提交批次（3 个原子提交）

| 批次 | 含任务 | 内容 |
|---|---|---|
| ① | T1、T2 | 数据层 + 实体状态（此时功能尚不可见，但构建必须过） |
| ② | T3、T4、T5 | 路线 Goal + 两条命令 + `stop`/`reset` 清路线 ⇒ **功能端到端可用** |
| ③ | T6、T7 | `mo_route_1` 数据 + ChatBox 接线 + 注释/文档/memory |

---

## T1：数据层 —— `NpcRoute` + `NpcRouteLoader`

**Files:**
- Create: `src/main/java/com/zonlong/beloong/route/NpcRoute.java`
- Create: `src/main/java/com/zonlong/beloong/route/NpcRouteLoader.java`
- Modify: `src/main/java/com/zonlong/BeLoongCore.java`（注册 reload listener，照 `NpcDialogueLoader` 的注册点）

**Steps:**
1. `NpcRoute`：`record NpcRoute(ResourceLocation dimension, List<Vec3> waypoints, double arrivalRadius)`；
   Codec：`dimension` 用 `ResourceLocation.CODEC`、`waypoints` 用「三元数组 → Vec3」的自定义 Codec、
   `arrival_radius` 用 `Codec.DOUBLE.optionalFieldOf("arrival_radius", 2.0)`。
   **路点为空 ⇒ 报错并丢弃该文件**（照 `NpcDialogueLoader` 对空 `pages` 的处理）。
2. `NpcRouteLoader`：照 `NpcDialogueLoader`（`SimpleJsonResourceReloadListener`，目录串 **`beloong/npc_route`**）；
   提供 `INSTANCE`、`get(ResourceLocation)`、`names()`（喂 Brigadier 补全 ⇒ 照 `CgRegistry:45-86` 的形态）；
   加载完成后打一行 INFO：**扫描数 / 装载数**（数据放错树时"扫描数恒 0"是唯一线索）。
3. 类注释写明：目录字符串是 **PackType 相对**（`data/<ns>/beloong/npc_route/`）—— 与对话系统同款。

**Verification:** `.\gradlew.bat build --console=plain` ⇒ BUILD SUCCESSFUL

---

## T2：实体状态 —— NBT 两键 + 路线 API

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/entity/NpcEntity.java`

**Steps:**
1. 两个 NBT 键常量，**照既有 `Beloong*` 前缀风格**（与 `STATE_NBT_KEY` 并列）。
2. 字段 `@Nullable ResourceLocation routeName` + `int routeIndex`（**只在服务端有意义**）。
3. API：`route()` / `setRoute(ResourceLocation)`（**下标归零**，即使同名也归零 ⇒ D5"重走一遍"）/
   `clearRoute()` / `routeFinished()`（`routeIndex >= 该路线路点数`）/ `routeIndex()` / `advanceRouteIndex()`。
   ⚠️ 若路线文件当前缺失，`setRoute` **照写**（D8：挂起而非拒绝），但**打一条英文 WARN**。
4. 落盘：`addAdditionalSaveData` 写两个键；`readAdditionalSaveData` 读名字 + **下标 clamp 到 [0, size]**
   （照 `NpcState.byNameLenient` 那种"坏存档不崩"的宽容口径；路线文件缺失时下标暂存、等文件回来再 clamp）。
5. 注释里写清 D6/D10：**`stop` 才终止**、**路线上 `move` 无效**。

**Verification:** `.\gradlew.bat build --console=plain`

---

## T3：路线 Goal（只占 `MOVE`，优先级 4）

**Files:**
- Create: `src/main/java/com/zonlong/beloong/entity/ai/NpcRouteGoal.java`
- Modify: `src/main/java/com/zonlong/beloong/entity/NpcEntity.java`（`registerGoals()` 里 `addGoal(4, new NpcRouteGoal(this))`）

**Steps:**
1. `extends Goal`，构造器里 `setFlags(EnumSet.of(Goal.Flag.MOVE))` —— **只占 MOVE、不占 LOOK**
   （这样 `LookAtPlayerGoal` 与它无 Flag 交集、可并行；而攻击 goal 占 MOVE ⇒ 天然互斥 ⇒ **D7 免费**）。
2. `canUse()`：`hasRoute ∧ 路线已加载 ∧ 维度匹配 ∧ !routeFinished`；
   `canContinueToUse()`：同上（维度一变或抵达即自动退出）。
3. `start()`：`moveTo(当前路点)`（**只此一次**）；
   `tick()`：若 `水平距离 ≤ arrivalRadius ∧ |Δy| ≤ 2` ⇒ `advanceRouteIndex()`
   ⇒ **仅在下标变化时**再 `moveTo(新路点)`。
   ⚠️ **绝不能每 tick 调 `moveTo`** —— 它第一句就 `clearEmote()`（`NpcEntity.java:590`），每 tick 调会把表情每 tick 清一次。
4. `stop()`：**只清移动命令，绝不清路线** —— 注释里写明"被攻击抢占时走这里，路线必须活下来"。
5. 类注释写明三条：`stop()` 的语义、D7 靠 `MOVE` 互斥、以及"寻路本身仍由 `tickMoveCommand` 每 20 tick 续"。

**Verification:** `.\gradlew.bat build --console=plain`

---

## T4：命令面（两条）+ "最近对话的 NPC"映射

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/command/NpcCommand.java`（`/beloong npc <targets> route <名>`，附 `/route` 无参回报）
- Create: `src/main/java/com/zonlong/beloong/command/RouteCommand.java`（`/beloong route <名>`，按玩家定位）
- Modify: `src/main/java/com/zonlong/BeLoongCore.java`（注册 `RouteCommand.register`）
- Modify: `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueHandler.java`（写 `lastDialogueNpc` 映射）
- Modify: `src/main/resources/assets/beloong/lang/{zh_cn,en_us}.json`（回报/报错键，两语言键集合一致）

**Steps:**
1. **为什么是两条**：既有形状 `npc` 之后第一个节点就是**实体参数** ⇒ `/beloong npc route <名>` 会被当成
   "把 `route` 解析成实体"而报错。故：手工/验收用 `/beloong npc <targets> route <名>`；
   ChatBox 用 `/beloong route <名>`（**以玩家身份执行** ⇒ 由映射定位目标）。
2. 映射：`NpcDialogueHandler`（唯一入口）里记 `Map<UUID /*玩家*/, UUID /*NPC*/>`；
   解析：先 `player.serverLevel().getEntity(uuid)`，找不到再遍历其它维度兜底。
3. 参数用 **`word() + suggests(NpcRouteLoader::names)`**（照 `CgCommand:69-70`，不要一条路线一个 literal）；
   命令层**三段前置校验、逐条明确报错不静默**（照 `CgCommand:103-128`）：
   ① 路线名不存在 ⇒ 报错 + 列可用名单（**不写 NBT**）；② 不是玩家执行（`/beloong route`）⇒ 报错；
   ③ 查不到"最近对话的 NPC" ⇒ 报错。
4. **维度不匹配**：**照写** + 一条英文 WARN（挂起是 D4 的正确行为，但作者必须知道）。
5. 权限**与 `NpcCommand` 同值（2）** —— 命令子树合并时只保留先注册者的 `requires`（`BeLoongCore:220-224`）。

**Verification:** `.\gradlew.bat build --console=plain`

---

## T5：`stop` / `reset` 清路线

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/command/NpcCommand.java`（`stop` 分支）
- Modify: `src/main/java/com/zonlong/beloong/entity/NpcEntity.java`（`resetToDefault()`）

**Steps:**
1. `stop` 分支：`stopMoving()` **之外**再 `clearRoute()`（D6）。
2. `resetToDefault()`：同样清路线（它已经是"全清"的语义）。
3. ⚠️ **清路线不要放进 `stopMoving()`** —— `attack()` 会调 `clearMotionCommands()`，
   而 D6/D7 要求"攻击**不**毁掉路线"。注释里写明这个分工。

**Verification:** `.\gradlew.bat build --console=plain`

---

## T6：数据与接线 —— `mo_route_1` + ChatBox 两条命令

**Files:**
- Create: `src/main/resources/data/beloong/beloong/npc_route/mo_route_1.json`
- Modify: `src/main/resources/data/beloong/chatbox/dialogues/mo.json`（「好的」选项的 `click.value` 变成两条命令）

**Steps:**
1. **先读**这两份文件的当前内容（纪律 ①）。路线 JSON 整份写入（见设计 §3.3，维度 `beloong:loong_palace`、
   6 个路点、`arrival_radius: 2.0`）；ChatBox 那份是**改一个字符串**（值从一条命令变成两条，
   用 `;` 分隔：`advancement grant @s only beloong:npc/1_1; beloong route beloong:mo_route_1`）。
2. ⚠️ ChatBox 那份**语法错会让它的整个 reload listener 挂掉** ⇒ 改完立刻 `json.loads` 验一遍。
3. ⚠️ 顺带留意既有审查的 **I-5**（用户选 F2「只留痕」）：这条 click 现在有**两条**命令，
   而"不以 `execute` 开头的命令会被 pluginHelper 截走"是**逐条**判定的 ⇒ 风险覆盖两条。
   本轮**按 F2 不改**，但要在设计文档里把这一点写清（T7）。

**Verification:**
```powershell
python D:\Minecraft\tools\YSMParser\route_invariants.py     # 本任务写出的不变量脚本（见 T8）
.\gradlew.bat build --console=plain
```

---

## T7：注释与文档回填

**Files:**
- Modify: `docs/NPC系统总设计.md`（§3.7 外部驱动 API 补路线 API；新增一节讲路线系统）
- Modify: `docs/plans/2026-09-30-npc-route-system-design.md`（状态 → 已实施）
- Modify: `memory/decisions-log.md`、`memory/learned-patterns.md`

**Steps:**
1. 主设计文档：`moveTo/stopMoving` 那一节补"路线层"；新节写清 D1–D11 与 C12 的两条硬限制。
2. memory：决策记录（本次七问的答案 + 命令面为什么是两条 + CG 交互结论）+
   一条可复用模式（**"用原版 goal 的 `lockedFlags` 免费拿到让位语义"**：只占 `MOVE` 的 goal，
   与同样占 `MOVE` 的攻击 goal 天然互斥 ⇒ 暂停/恢复零代码；以及"服务端感知不到的子系统不能做让位"）。
3. 顺手记下 CG 审查的 **X-2**（`NpcEntity.setEmote` 的 javadoc 陈旧）—— **本轮不修**，但写进遗留项免得丢。
4. 全库扫一遍本轮是否引入新的"注释与代码不符"。

**Verification:** `Select-String -Path docs\NPC系统总设计.md -Pattern 'route|路线' | Measure-Object` ≥1；`git status --short` 干净

---

## T8：全量验证与验收

**Steps:**
1. `.\gradlew.bat build --console=plain` ⇒ BUILD SUCCESSFUL
2. 三个既有探针全绿：`probe_emote_{assets,java,bytecode}.py`
3. **新写 `D:\Minecraft\tools\YSMParser\route_invariants.py`** 并全绿：
   - 每个路线 JSON 可解析、`dimension` 是已注册维度、路点非空且坐标合法；
   - **相邻路点距离 ≤ 16**（寻路探索半径的硬约束 —— 本系统最容易踩、也最值钱的静态检查）；
   - 路线 id ↔ 文件路径逐一对应；命令里引用的路线名**真实存在**；
   - ChatBox 命令里的路线名与路线文件名一致（防两边写岔）；语言键两语言齐全。
4. 把实机清单交给用户（设计文档 §5，9 项，含反例）。

**Verification:** 上述输出全部符合预期。

---

## 风险与回退

| 风险 | 影响 | 缓解 |
|---|---|---|
| **相邻路点超过探索半径（16）** | 复杂地形上寻路反复失败 ⇒ 表现为"卡在半路一直重试" | T8 的不变量脚本**硬校验**；首个路线已实测 ≤12.1 格 ✓ |
| **CG 与路线并发** | `moveTo` 的 `clearEmote` 掐掉 CG 的 `descend`；CG 相机跟丢（服务端**无法感知** CG ⇒ 做不了让位） | 只能靠**内容顺序**；写进文档（C12）|
| ChatBox 命令被 pluginHelper 截走 | 指派静默不生效（选项照常关闭、回复不消失） | 既有 I-5 的 F2 决定；不变量脚本已会打**非致命提示**；排查方法在审查报告里 |
| `moveTo` 每 tick 调导致表情每 tick 被清 | 表情在路线上永远无法维持 | T3 明确"只在下标变化/start 时调" |
| `stopMoving()` 里清路线 | 攻击会误毁路线（违背 D6/D7） | T5 明确"清路线放命令层与 reset" |
| 路线文件被改名/删除 | 已指派的 NPC 挂起（不崩） | 挂起 + 每名字一次 WARN；`clamp` 保证坏数据不崩 |
| `FOLLOW_RANGE` 被后人调大以"修"寻路 | 会连带改节点预算与入水行为（`:1070-1083` 有明确警告） | 不变量脚本只查间距；文档指向既有警告 |

**回退**：T6 是纯数据（删掉那个 JSON + 把 click 改回一条命令即回到现状）；①②③ 可整体 `git revert`；
NPC 身上的两个 NBT 键是**新增**字段，旧存档没有它们 ⇒ 读到即"无路线" ⇒ 优雅降级 ✓
