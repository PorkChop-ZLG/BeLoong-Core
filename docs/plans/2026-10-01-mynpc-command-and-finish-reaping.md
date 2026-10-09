# 「我的 NPC」命令族 + 通关后回收分身 实施计划

**目标**：让整合包（kubejs 覆盖的纯数据剧情）能在新的多人 NPC 系统下**正确指定"属于我的那只 NPC"**，
并让**长流程**在通关后能回收私有分身。

**架构**：
1. 新增一条与 `npc` / `route` **平级**的玩家作用域命令族 `/beloong mynpc <实体类型> {route|play|effect|tp}`，
   目标解析规则是「**归属执行者、且类型匹配**的那只 NPC」——O(1)、不依赖对话历史、天然避开公共锚点。
2. `npc_story` 新增 `keep_after_finish`（默认 `false`）：通关（获得 `end_advancement`）后删除该玩家的分身，
   并让公共锚点重新对他可见 ⇒ 长流程既不留垃圾、观感又连续（锚点就在剧情结尾把末送回去的位置）。

**选定方案**：① 1a（新建 `MynpcCommand`）+ ② 2a（`visibleTo` 与对账器两处都改）。被否方案见下表。

| 特性 | 方案 | 否决理由 |
|---|---|---|
| ① | 1b 折进 `RouteCommand` | 类变成混合职责；该类注释中"为什么必须另起一条路径"的论证要重写 |
| ① | 1c 给 `NpcCommand` 加自定义选择器 | 需改原版 `EntitySelectorParser`，成本远高于新建一族，收益相同 |
| ② | 2b 只改对账器 | 删除后锚点仍被隐藏 ⇒ 玩家眼前空无一人 |
| ② | 2c 只改 `visibleTo` | 分身永久滞留（看不见但占资源）⇒ 正是要解决的问题 |

---

## 探明的前置事实（实施依据，勿凭记忆改写）

| 事实 | 出处 |
|---|---|
| 玩家作用域命令的模板：`requires(hasPermission(2))` + `source.getPlayer()` 为空即报错 + `sendFailure`/`sendSuccess(Component.translatable(...))` | `command/RouteCommand.java:45-97` |
| 目标作用域命令的模板：`play <名>` / `play stop` / `route <ResourceLocationArgument.id()>` / `reset` | `command/NpcCommand.java:145-177`、`:373-403` |
| 命令反馈**一律走语言键** ⇒ 新增命令必须补 `beloong.command.*` 键（中英各一份，守卫 ⑬ 会检查） | `NpcCommand.java:195-206` |
| 字面量**不能**放在 `npc` 之后的实体参数位（同名歧义）⇒ 新族必须与 `npc`/`route` 平级 | `RouteCommand.java:22-27` |
| 路线名参数必须用 `ResourceLocationArgument.id()`（`StringArgumentType.word()` 不含冒号） | `NpcCommand.java:160-164` |
| `NpcEntity` 现成 API：`setRoute` / `setEmote` / `clearEmote` / `setState` / `moveTo(Vec3)` / `resetToDefault` / `owner()` | `command/NpcCommand.java` 各方法 |
| `npc_story` 加字段要动**三处**：`NpcStory` 的 record 组件、`CODEC`、`NpcStoryLoader.KNOWN_FIELDS`（漏第三处 ⇒ 整文件被拒） | `npcstory/NpcStory.java:48-77`、`npcstory/NpcStoryLoader.java` |
| 可见性判定是**纯函数**且每追踪周期每玩家都调 ⇒ 不得写实体字段、不得发包（守卫 ⑮ 钉住） | `entity/NpcEntity.java` 的 `visibleTo` |
| 注册点 | `BeLoongCore.java:233`（NpcCommand）、`:235`（RouteCommand） |
| 本项目**没有测试套件** ⇒ 每个任务的验证 = `gradlew build` + 仓库外的两套不变量与三探针；行为验证靠实机 | `memory/project-context.md` |

## 非目标（明确不做）

- **不动** `/beloong npc <targets> …` 与 `/beloong route <路线名>` 的现有形状与行为
- 不做跨维度 `tp`（整合包场景里末与目标同维度；真需要时再加）
- 不做 `state`/`move`/`attack`/`reset` 的玩家作用域版本（YAGNI：整合包不需要）
- 不引入"跑"这种新概念（`run` 动画由移速属性驱动，用通用 `effect` 即可）
- 不改整合包的任何文件（见文末"连带改动"，另行交付）

**状态：批次①② 已完成（`b906d59` / `ac0111a` / `aeb16c7` / `0814f3d`）；批次③（收尾审查）进行中。**

---

# 批次① 命令族 `/beloong mynpc`

### T1：新建 `MynpcCommand`，只做骨架与注册

**Files:**
- Create: `src/main/java/com/zonlong/beloong/command/MynpcCommand.java`
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`（`:235` 后加一行 `MynpcCommand.register(event.getDispatcher());` + import）

**Steps:**
1. 建类：`final`、私有构造、`register(CommandDispatcher<CommandSourceStack>)`
2. 树形：`beloong`（`requires(hasPermission(2))`）→ `mynpc` → 参数 `type`（`ResourceLocationArgument.id()`，补全用 `BuiltInRegistries.ENTITY_TYPE.keySet()`）→ 四个子命令占位
3. 在 `BeLoongCore` 注册（紧邻 `RouteCommand.register`，注释风格一致）

**Verification:** `gradlew build` 成功；游戏内 `/beloong mynpc beloong:mo` 能补全出四个子命令。

### T2：目标解析（本计划的核心）

**Files:** Modify: `command/MynpcCommand.java`

**Steps:**
1. 私有方法 `@Nullable NpcEntity resolveMine(ServerPlayer player, ResourceLocation typeId)`：
   - `EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(typeId)`；空 ⇒ 报错并列出可用类型
   - 只扫 `player.serverLevel()`（同维度）：`e.getType() == type && e instanceof NpcEntity npc && player.getUUID().equals(npc.owner())`
   - 命中多只 ⇒ 取 `bornAt` 最大者（与对账器"重复分身保留最近出生"的取舍一致），并留一条英文 WARN
   - 一只都没有 ⇒ **明确报错**（不许静默、不许退化成"最近的那只" —— 这正是本族存在的理由）
2. 抽出统一的失败/成功反馈（`Component.translatable`）

**Verification:** `gradlew build`；实机：未获得 `root` 时执行 ⇒ 报"没有属于你的该类 NPC"；获得后 ⇒ 命中自己的分身（`/data get entity` 对 `BeloongOwner` 可见）。

### T3：`route` 子命令

**Files:** Modify: `command/MynpcCommand.java`

**Steps:**
1. `mynpc <type> route <route>`（`ResourceLocationArgument.id()` + 路线名补全，照 `RouteCommand:41-54`）
2. 校验路线存在（复用 `NpcRouteLoader`）⇒ `npc.setRoute(id)` + 复用 `RouteCommand.warnDimensionMismatch(npc, id, route)`
3. 反馈复用既有键 `beloong.command.route.set` / `beloong.command.route.unknown`

**Verification:** `gradlew build`；实机：对整合包场景执行 `beloong mynpc beloong:mo route beloong:mo_route_3`，只有**自己的**末开始走。

### T4：`play <名>` / `play stop` 子命令

**Files:** Modify: `command/MynpcCommand.java`

**Steps:**
1. 树形照 `NpcCommand:145-156`（先接 `animation` 参数，再接 `stop` 字面量 —— 同一 token 位的歧义是原版接受的形状）
2. 分别调 `npc.setEmote(name)` / `npc.clearEmote()`
3. 反馈复用 `beloong.command.npc.play` / `beloong.command.npc.play_stop`

**Verification:** `gradlew build`；实机：`beloong mynpc beloong:mo play stop` 只让**自己的**末站起（另一名玩家的末不受影响）。

### T5：`effect` 子命令（通用形式）

**Files:** Modify: `command/MynpcCommand.java`

**Steps:**
1. 参数与构造**照原版** `EffectCommands.java`（在 `mc_src` 内 `net/minecraft/server/commands/EffectCommands.java`）：
   `ResourceArgument.resource(ctx, Registries.MOB_EFFECT)` + `[seconds]`（`IntegerArgumentType`，默认 30）+ `[amplifier]`（默认 0）+ `[hideParticles]`（`BoolArgumentType`，默认 false）
2. 施加：`npc.addEffect(new MobEffectInstance(holder, seconds * 20, amplifier, false, !hideParticles))`
3. 反馈新键 `beloong.command.mynpc.effect`（数量、效果名、秒数）

**Verification:** `gradlew build`；实机：`beloong mynpc beloong:mo effect minecraft:speed 30 1 true` ⇒ 只有自己的末加速、动画切 `run`（整合包注释所述判据）。

### T6：`tp` 子命令（同维度、绝对坐标）

**Files:** Modify: `command/MynpcCommand.java`

**Steps:**
1. 参数：`Vec3Argument.vec3()`（支持 `~` 相对量，整合包用绝对量）+ 可选 `[yaw]`/`[pitch]`（`FloatArgumentType`）
2. 执行：`npc.moveTo(x, y, z, yaw, pitch)`（`Entity` 的重载）+ `npc.setYHeadRot(yaw)` + 清掉移动目标（`npc.stopMoving()`）避免"传过去又自己走回来"
3. 反馈新键 `beloong.command.mynpc.tp`
4. ⚠️ 类注释写明：**不做跨维度**；需要时另加（当前整合包场景同维度）

**Verification:** `gradlew build`；实机：`beloong mynpc beloong:mo tp 0 64 -8` ⇒ 只有自己的末回到入口。

### T7：语言键（中英各一份）

**Files:**
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

**Steps:**
1. 新增键：`beloong.command.mynpc.not_a_player`、`.unknown_type`、`.no_owned_npc`、`.effect`、`.tp`（措辞照既有 `beloong.command.*` 风格）
2. 中英键集合必须逐字一致（守卫 ⑬ 会检查"Java 引用到的键在两份文件里都存在"）

**Verification:** `python stage_invariants.py`（⑬ 节）全绿。

### T8：守卫补充 + 批次提交

**Files:** Modify: `D:\Minecraft\tools\YSMParser\stage_invariants.py`（仓库外，不入库）

**Steps:**
1. ⑮ 节加两条：`MynpcCommand` 的解析**只按 owner**（断言源码里出现 `player.getUUID().equals(npc.owner())` 且**不出现** `getEntitiesOfClass`/`@n` 式的"最近"退化）
2. 加一条"命令族的平行性"：`mynpc` 与 `npc`/`route` 都是 `beloong` 的**直接子字面量**（断言 `Commands.literal("mynpc")` 出现在 `literal("beloong")` 之后**且** 不嵌套在 `argument("targets"` 之下）
3. `gradlew build` + `stage_invariants.py` + `route_invariants.py` + 三探针全绿后提交

**Verification:** 上列四条命令全绿 + `git status` 干净。

---

# 批次② 通关后回收分身（`keep_after_finish`）

### T9：字段与数据（三处 + 我们自己的数据）

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/npcstory/NpcStory.java`（record 组件 + `CODEC` 的 `optionalFieldOf("keep_after_finish", false)` + javadoc）
- Modify: `src/main/java/com/zonlong/beloong/npcstory/NpcStoryLoader.java`（`KNOWN_FIELDS` 加 `"keep_after_finish"`；漏了会整文件被拒）
- Modify: `src/main/resources/data/beloong/beloong/npc_story/mo.json`（**显式写 `"keep_after_finish": true`** —— 默认 false 会让我们的短剧情通关后也被回收，D6 的"结尾它坐在那里"就不成立了）

**Steps:**
1. 三处同步改（⚠️ 声明、codec、已知名集合必须一次改完 —— 拆开会出现"编译通过但数据被整文件拒绝"）
2. 更新 `NpcStory` 的类 javadoc 与字段说明（说清默认 false 的理由：新剧情默认回收）

**Verification:** `gradlew build`；实机日志出现 `npc stories: 1 file(s) scanned, 1 story(ies) loaded`（若被拒会是 ERROR）。

### T10：可见性规则（外观）

**Files:** Modify: `src/main/java/com/zonlong/beloong/entity/NpcEntity.java`（`visibleTo`）

**Steps:**
1. 规则改为：
   - 私有分身：不是我的 ⇒ `false`；是我的 ⇒ `已获 start ∧ ¬(已获 end ∧ ¬keepAfterFinish)`
   - 公共锚点：无剧情声明 ⇒ `true`；有 ⇒ `¬已获 start ∨ (已获 end ∧ ¬keepAfterFinish)`
2. 仍然**只用两次进度查询**（O(1)）、不写实体字段、不发包（守卫 ⑮ 会验）
3. 更新该方法的 javadoc（说明"通关后锚点重新可见"的用意：整合包结尾把末送回锚点处 ⇒ 观感连续）

**Verification:** `gradlew build` + `stage_invariants.py`（⑮ 的纯函数三条）全绿。

### T11：对账器回收（实体真的删掉）

**Files:** Modify: `src/main/java/com/zonlong/beloong/npcstory/NpcStoryHandler.java`（`reconcilePlayer`）

**Steps:**
1. 现在的 `if (finished) return;` 改为：
   `if (finished) { if (!story.keepAfterFinish()) removeDouble(npc, story, player, "finished", false); return; }`
   —— **只删不撤**（剧情已完成，撤回会毁掉玩家的通关记录）
2. `mine.isEmpty()` 分支保持"`finished` 永不重置"（上一轮 C1 的修复），并补注释说明两者如何配合
3. 类注释的判定表补一行 `已通关 ∧ keep_after_finish=false ⇒ 清理（不撤回）`

**Verification:** `gradlew build` + `stage_invariants.py` + `route_invariants.py` 全绿。

### T12：守卫补充

**Files:** Modify: `D:\Minecraft\tools\YSMParser\stage_invariants.py`（仓库外）

**Steps:**
1. ⑮ 加断言：`NpcStoryLoader.KNOWN_FIELDS` 的键集合与 `NpcStory.CODEC` 的字段**逐一对齐**（把"加字段要动三处"这件事变成机器检查）
2. ⑮ 加断言：`keep_after_finish` 的默认值是 `false`，且我们自己的 `mo.json` 里**显式**写了 `true`
3. 全绿后提交

**Verification:** `stage_invariants.py` 全绿。

---

# 批次③ 收尾

### T13：活文档与 memory

**Files:**
- Modify: `docs/NPC系统总设计.md`（§十二 补 `keep_after_finish` 一节 + §12.5 文件表加 `MynpcCommand`）
- Modify: `memory/decisions-log.md`（第二十一则）、`memory/learned-patterns.md`（若得新教训）

**Steps:** 记录两项的语义、默认值、以及"为什么长流程必须能回收"的来龙去脉

**Verification:** 文档与代码逐条对齐（人工核对 `visibleTo` 的规则与文档一致）。

### T14：收尾审查（子代理）+ 修复

**Steps:** 按本项目既有节奏，派子代理只审这两个特性（对着本计划与前一轮的设计文档），Critical/Important 全修

**Verification:** 审查报告写入 `docs/reviews/2026-10-01-mynpc-and-finish-reaping-review.md`；全部修复后 build + 三套不变量 + 三探针全绿。

---

## 风险

| 风险 | 说明 | 应对 |
|---|---|---|
| `effect` 的参数形状写歪 | 原版 `/effect give` 的参数顺序/默认值容易记错 | 实施时**照抄** `EffectCommands.java`，不凭记忆（本项目已因此栽过多次） |
| 加字段只改两处 | 漏 `KNOWN_FIELDS` ⇒ 数据被整文件拒绝，日志为 ERROR，症状是"分身根本不生成" | T12 加机器检查（键集合 ↔ codec 字段对齐） |
| 默认值 false 波及我们自己的剧情 | 通关玩家分身被删 ⇒ D6 失效 | T9 显式写 `true`；T12 加断言钉住 |
| `tp` 与移动指令打架 | 传送后被残留的移动目标拉走 | T6 同时 `stopMoving()` |
| 实机未验 | 本项目无测试套件 | 两批次各自附实机验收清单（见下） |

## 实机验收清单（交给用户）

**批次①**
1. 未获得 `root` 时 `beloong mynpc beloong:mo play stop` ⇒ 明确报错（不是"什么都没发生"）
2. 获得 `root` 后同样命令 ⇒ 只有**自己的**末站起
3. 双人同场：A 执行 `effect`/`tp`/`route` ⇒ B 的末**完全不受影响**
4. 玩家站在入口（锚点与自己的分身同位置）时执行 ⇒ 命中的是**分身**（`@n` 时代会有一半概率打在隐形锚点上）

**批次②**
5. 用自己的存档走完剧情（或手动 `advancement grant @s only beloong:npc/5_1`）⇒ 5 秒内自己的末消失、**入口锚点重新出现**（同一位置、同一外观）
6. 通关记录**不被撤回**（`/advancement` 里 `5_1` 仍在）
7. 未通关玩家的行为完全不变（他们的分身照常在，锚点照样隐藏）

---

## 连带改动（不在本计划范围，但必须与模组一起交付给整合包）

1. **先替换 jar**：整合包现有的 `beloong-0.10.2.jar` 不含多人系统，也不含本计划的命令族
2. 新增 `kubejs/data/beloong/beloong/npc_story/mo.json`：`end_advancement = beloong:npc/5_1`、`lifetime_ticks = -1`、**省略** `required_dimension`（`keep_after_finish` 用默认 false 即可）
3. 整合包 4 处命令改为 `beloong mynpc beloong:mo …`：
   - `mo_pool.json:31` → `effect` + `route` 两条
   - `dihuang_loong.json:49,67` → `play stop`
   - `dragon_talk.json:37` → `tp 0 64 -8`
4. 整合包语言文件补键：`beloong.command.mynpc.*`（5 条）+ 本特性早前新增的 7 条配置/提示键（整文件覆盖，不补就显示原始键名）

---

# 执行记录

## 批次① 命令族 —— **已完成**（`b906d59`）

| 任务 | 状态 |
|---|---|
| T1 骨架 + 注册（`MynpcCommand.register(dispatcher, context)`）| ✅ |
| T2 目标解析（`type ∧ BeloongOwner == 执行者`，多只取 `bornAt` 最大 + WARN）| ✅ |
| T3 `route` / T4 `play`·`play stop` / T5 `effect` / T6 `tp` | ✅ |
| T7 语言键（新增 5 条，中英 294/294 一致）| ✅ |
| T8 守卫 ⑯（4 条）+ 提交 | ✅ |

**实施期发现（写下来免得下次重踩）**

1. **`effect` 需要 `CommandBuildContext`**：原版用
   `ResourceArgument.resource(context, Registries.MOB_EFFECT)`（`EffectCommands.java:61`）
   ⇒ 本命令的 `register` 必须多带一个参数，而它由
   `RegisterCommandsEvent.getBuildContext()` 提供（`RegisterCommandsEvent.java:50`）。
   ⚠️ 若照 `NpcCommand.register(dispatcher)` 的单参形状写，会直接编译不过。
2. **`tp` 有三件事都不能省**：
   `stopMoving()` 清移动目标（否则 `tickMoveCommand` 会往旧目标继续寻路 ⇒ "传过去又自己走回来"）+
   `getNavigation().stop()` 撤销**已经在执行**的那条路径（只清目标撤不掉它）+
   用 `Entity#moveTo(double,double,double,float,float)`（`Entity.java:1464`，定位+转向）
   而不是 `NpcEntity#moveTo(Vec3)`（`:654`，**登记移动目标**，同名不同义）。
3. **时长换算照抄原版**：瞬时效果直接用秒数、其余 `×20`、省略 `seconds` 时非瞬时 600 tick / 瞬时 1 tick；
   `addEffect` 返回 false（已有更强同类效果）⇒ 报失败，不谎报成功。
4. **⚠️ 守卫自身出过一次错**：⑯ 的 16.1 起初对**全文**断言"不出现 `@n`"，
   而类注释里**正解释着**"为什么不能用 `@n`" ⇒ 守卫被自己的说明文字绊倒（FAIL）。
   已改为**先剥注释再断言**。
   📌 教训升级：守卫不仅要"锁意图而不锁实现"，**还不能锁散文** —— 断言的对象应当是**代码**。

**本批实机验收清单（需要你）**

1. `/beloong mynpc beloong:mo` 能补全出 `route` / `play` / `effect` / `tp` 四个子命令
2. 未获得 `root` 时执行 ⇒ **明确报错**（不是"什么都没发生"）
3. 获得 `root` 后 `beloong mynpc beloong:mo play stop` ⇒ 只有**自己的**末站起
4. 双人同场：A 执行 `effect`/`tp`/`route` ⇒ B 的末**完全不受影响**
5. 玩家站在入口（锚点与自己的分身同位置）时执行 ⇒ 命中的是**分身**（`@n` 时代会有一半概率打在隐形锚点上）

## 批次② 通关后回收 —— **已完成**（`aeb16c7` 代码 / `0814f3d` 文档）

| 任务 | 状态 |
|---|---|
| T9 新增 `keep_after_finish`（三处同步）+ 自带 `mo.json` 显式 `true` | ✅ |
| T10 `visibleTo` 成对规则（抽出 `finishedAndReaped`）| ✅ |
| T11 对账器"通关即回收"（**只删不撤**）+ 判定表补两行 | ✅ |
| T12 守卫 ⑰（5 条，把"加字段要动三处"变成机器检查）| ✅ |
| T13 活文档 §12.6 + memory 第二十一则 | ✅ |

**实施期要点**

1. **record 组件顺序必须与 codec 的 group 顺序一致**（`RecordCodecBuilder` 按位置匹配）
   ⇒ 我把新字段在**两处都追加到末尾**，并在守卫 ⑰ 里做了"字段名集合逐一对齐"的机器检查。
2. **`&&` 的短路是刻意的**：`visibleTo` 里"没开始过的玩家连是否已完成都不必查"
   ⇒ 少一次进度查询，也少一条懒建的空进度记录。
3. **通关回收必须两处成对**（`visibleTo` 外观 + 对账器真删）：
   只改前者 ⇒ 实体永久滞留（看不见但占资源）；只改后者 ⇒ 删掉后锚点仍隐藏 ⇒ 玩家眼前空无一人。
   且对账器侧**只删不撤** —— 剧情已完成，撤回会毁掉玩家的通关记录。
4. **与上一轮 C1 的配合**：`mine.isEmpty()` 分支里"`finished` 永不重置"保持不变，
   于是"通关后被回收"与"无分身不重置"两者互不干扰（这正是把 D21 与回收放在一起时最容易出的洞）。

**本批实机验收清单（需要你）**

1. 走完剧情（或手动 `advancement grant @s only beloong:npc/5_1`）⇒ 5 秒内自己的末消失、
   **入口锚点重新出现**（同一位置、同一外观）
2. 通关记录**不被撤回**（`/advancement` 里 `5_1` 仍在）
3. 未通关玩家的行为完全不变（分身照常在、锚点照样隐藏）
4. 模组自带的 `mo.json` 是 `keep_after_finish: true` ⇒ 用**原版数据**时通关后末仍坐在原地（D6 不变）
