# 地狱之门实施计划

**状态：** ✅ **已完成** —— T1–T7 全部落地；2026-09-20 数据包实机探针 + 用户视觉验收通过（见文末「五、实施结果」）

**目标：** 在 beloong-core 里 1:1 复刻灾变「封印之门」为 `beloong:hell_gate`（地狱之门），
钥匙换成铁魔法 `irons_spellbooks:bone_key`（骸骨钥匙），模型/动画/音效逐字节照搬，
贴图先照搬、后按追加需求换成下界风格（T8），
开门行为照搬（**只是放行**，不传送）。

**架构：** 五件套单向依赖，与灾变同构 ——
方块（`HellGateBlock`）→ 方块实体（`HellGateBlockEntity`，计时）→ 事件 1 驱动
动画定义（`HellGateAnimation`）→ 盒子模型（`HellGateModel`）→ 渲染器（`HellGateRenderer`）。
服务端与客户端只通过**方块状态 + 方块实体 NBT/动画状态**耦合，因此服务端链路可以用
纯数据包探针独立验证，无需真人操作。

**采用方案：** 方案 B —— 完全移植（不继承灾变的类）。
**基线：** `disaster2` @ `def22f1`（版本 `0.9.6`）
**设计文档：** `docs/plans/2026-09-20-hell-gate-design.md`（含移植映射表、决策 D1–D14、与灾变的三处刻意偏离）

---

## 〇、验证范式（与技能模板的差异说明）

本项目**没有测试套件**（`memory/project-context.md`：*No test suite. Verification is
`gradlew build` + static grep probes + live game runs.*）。因此本计划**不使用** TDD 的
RED-GREEN 步骤，每个任务的「验证」由三件套组成：

1. `.\gradlew.bat compileJava jar` 退出码 0
2. **静态探针** —— 对 `build/libs/beloong-0.9.6.jar` 与 `build/classes/java/main` 取证
   （class 是否存在、资产是否在 jar 内、`javap` 看继承关系与常量池引用、资产字节数比对）
3. **实机运行** —— 本项目的最终验收门槛，用例见 §一 T7

## 〇之二、为什么这次用「数据包探针」而不是真人手点

本功能的主链在服务端：**铺 40 格 → 通 `LIT` → 145 tick 后 40 格 `OPEN`**。这条链完全可以用
数据包在无人操作的情况下跑完并把结论打进日志，因此本次新增了一套一次性探针（不进版本库，
`run/` 已 gitignore）：

```
run/saves/hellgate_probe/                      ← 某个已有存档的【副本】（不动用户原存档）
  datapacks/hellgate_probe/
    pack.mcmeta                                ← pack_format 48
    data/minecraft/tags/function/load.json      ← 加载时触发 init
    data/hellgate_probe/function/
      init.mcfunction          ← 铺 80 格（两扇门：一扇待开、一扇恒闭做视觉对照）
      count_closed.mcfunction  ← 40 条状态断言（facing/part/y_offset/lit/open 全量匹配）
      light.mcfunction         ← 40 格置 lit=true
      count_open.mcfunction    ← 40 条断言：open=true
      view.mcfunction          ← 把玩家 tp 到门前并切创造（供人看/截图）
      finish.mcfunction
```

设计要点：
- **幂等**：`init` 首行 `execute if block 0 90 0 minecraft:bedrock run return 0`，用一块基岩当
  「已执行」标记，避免世界被二次加载时重跑（也就不会在客户端再跑一次）。
- **不依赖真人**：所有断言用 `execute if block ... run scoreboard players add` 累计 + `matches 40`，
  只靠世界 tick。
- **不污染原存档**：整份探针跑在存档副本里（源存档「新的世界龙宫」保持原样）。
- **为什么不能顺带验证 `setPlacedBy`**：`/setblock` 在 1.21.1 里**不触发** `setPlacedBy`
  （唯一调用点是 `BlockItem.java:82`），发射器也没有通用的 BlockItem 发射行为 ⇒ 该方法只能真人验。

## 〇之三、外部前置（不在本计划范围内）

1. **用户需自行做视觉确认**（渲染效果/贴图/观感）—— 机器无法断言渲染结果。
2. **`setPlacedBy` / 骸骨钥匙右键 / 创造模式破坏** 三条真人路径，需用户在游戏里试一次
   （本次已由用户视觉验收覆盖）。

---

## 一、任务分解

### T1 —— 读透灾变封印之门（事实清单）

**交付物：** 六条查证事实（设计文档 §一 表），覆盖：五件套结构、写死的注册项、
`LevelChunk` 的类型校验、客户端泛型、钥匙判定不消耗、门本身不含传送。

**验证：** 每条事实都要能指到源码行号（设计文档 §九 证据索引）。

**结果：** ✅ 完成。其中「`LevelChunk` 的类型校验」一条直接决定了方案选择（不是风格偏好，是可行性）。

### T2 —— 服务端三件：方块 + 方块实体 + 枚举

**交付物：**
- `src/main/java/com/zonlong/beloong/block/HellGateBlock.java`
- `src/main/java/com/zonlong/beloong/block/HellGateBlockEntity.java`

**要点：** 常量具名化（`GATE_HEIGHT=8`、`TICK_SCREEN_SHAKE=1`、`TICK_SOUND_AND_BLAST=28`、
`TICK_FULLY_OPEN=145`）；钥匙判定换 `ItemRegistry.BONE_KEY`；其余逐行照抄。

**验证：** 编译通过；`javap` 确认 `HellGateBlock extends BaseEntityBlock`、
常量池含 `ItemRegistry.BONE_KEY`/`ScreenShake_Entity.ScreenShake`/`ModSounds.HELL_GATE_OPEN`。

**结果：** ✅ 完成。

### T3 —— 注册（方块 / BlockEntity / 物品）—— 第一个实机闸门

**交付物：** `registry/ModBlocks.java`（`HELL_GATE` + `HELL_GATE_BLOCK_ENTITY`）、
`item/ModItems.java`（`HELL_GATE`，`fireResistant().rarity(EPIC)`）、
`item/ModCreativeModeTabs.java`（加入「化龙」页）、`registry/ModSounds.java`（`HELL_GATE_OPEN`）。

**验证：** jar 内 `sounds.json` 含新键；字节码常量池含 `beloong:hell_gate` 与注册项引用；
**实机探针能 `setblock beloong:hell_gate` 成功且没有 BE 类型告警**（这条是本任务真正的闸门：
若 BE 类型不被接受，探针会在 `PASS-open-40` 上失败）。

**结果：** ✅ 完成。探针 `PASS-closed-40` + `PASS-open-40` 证明注册与 BE 类型都成立。

### T4 —— 客户端三件：动画定义 + 盒子模型 + 渲染器

**交付物：**
- `src/main/java/com/zonlong/beloong/client/animation/HellGateAnimation.java`
- `src/main/java/com/zonlong/beloong/client/model/HellGateModel.java`
- `src/main/java/com/zonlong/beloong/client/HellGateRenderer.java`

**要点：** 关键帧与几何数值**一字不改**；渲染器只在 `CENTER + Y_OFFSET==0` 画一次。
过程记录：T4 期间出现并修掉了一处**转录错误**（`left_door` 的 `6.0F` 关键帧被我一度写成
一个无意义的三元表达式），随后逐行对齐灾变原件确认全部关键帧一致 —— 这也是把
「逐行 diff」写进验收步骤的原因。

**验证：** `javap` 确认 `HellGateModel extends AdvancedEntityModel<Entity>`、
`HellGateRenderer implements BlockEntityRenderer<HellGateBlockEntity>`；客户端渲染器注册
出现在 `BeLoongCoreClient` 的 `BootstrapMethods` 里；实机进世界不崩。

**结果：** ✅ 完成。

### T5 —— 资产照搬（贴图 / 音效 / 模型 JSON / 语言文件）

**交付物：** `blockstates/hell_gate.json`、`models/block/hell_gate.json`、
`models/item/hell_gate.json`、`textures/block/hell_gate.png`、`textures/item/hell_gate.png`、
`sounds/block/hell_gate_open.ogg`、`sounds.json` 新条目、`lang/{zh_cn,en_us}.json` 各 2 键。

**验证：** jar 内三份二进制资产的**字节数与灾变原件逐一相同**（15680 / 277 / 114357）；
⚠️ 两张贴图**后续按追加需求换成下界风格**（见 **T8**），音效至今仍逐字节相同。
客户端资源加载日志里**没有一条**属于 `beloong` 的模型/贴图/音效告警。

**结果：** ✅ 完成。

### T6 —— 前置与集成

**交付物：** `build.gradle`（`compileOnly "curse.maven:lionfish-api-1001614:8094835"`）、
`src/main/templates/META-INF/neoforge.mods.toml`（`lionfishapi` required，`[3.0-beta,)`）、
`BeLoongCoreClient`（注册 BER）。

**要点与理由（D9–D11）：** LionfishAPI 是模型/动画基类的来源；灾变自己的 mods.toml **没有**声明它，
但灾变 3.26 实际依赖它 ⇒ 本模组显式声明为 required，缺了它宁可给出清晰加载错误也不想运行时
`NoClassDefFoundError`。版本下界必须写 `3.0-beta`（`3.0-beta < 3.0`）。

**验证：** jar 内 `mods.toml` 含该依赖；`gradlew build` 通过。

**结果：** ✅ 完成。

### T7 —— 构建 + 静态探针 + 实机探针 + 人工验收

**验证用例（每条都要能证伪）：**

| # | 用例 | 期望 | 结果 |
|---|---|---|---|
| 1 | `gradlew compileJava jar` | 退出码 0 | ✅ |
| 2 | jar 内 5 个 `.class` + 6 个资产 + 2 份 lang + `sounds.json` + `mods.toml` | 全部命中 | ✅ 15/15 |
| 3 | 二进制资产字节数（首轮） | 15680 / 277 / 114357 | ✅（贴图后被 T8 换色） |
| 4 | 数据包探针：40 格状态断言 | `PASS-closed-40` | ✅ |
| 5 | 数据包探针：通 `LIT` 后 145 tick | `PASS-open-40` | ✅ |
| 6 | 全程 BE 类型告警 | 0 条 | ✅ |
| 7 | 客户端模型/贴图/音效告警（`beloong` 域） | 0 条 | ✅ |
| 8 | `run/crash-reports` 新增 | 无 | ✅ |
| 9 | 用户视觉验收 | 通过 | ✅ |

**结果：** ✅ 全部通过（证据见 §五）。

### T8 —— 追加需求：贴图换成下界风格（2026-09-20 追加）

**用户原话：** 请先视觉确认原贴图（蓝宝石锁 / 石砖主体 / 白雪装饰），然后把地狱之门改成下界风格 ——
蓝宝石→红宝石、石砖→下界砖、白雪→血红，**灰色石框不用改**。

**交付物：**
- `tools/recolor_hell_gate_texture.py`（本机工具，`tools/` 已 gitignore）
- 覆盖 `assets/beloong/textures/{block,item}/hell_gate.png`

**要点：**
1. **先核对再动手**：原图 256×256 只有 25 色、无抗锯齿 ⇒ 逐色分类。
   核对结果与用户描述一一对应，且「石砖主体」那 7 色与**原版 `minecraft:block/stone_bricks`
   调色板逐色完全相同**（这是「石砖」判断的硬证据，也说明换色可以做成纯调色板替换）。
2. **同序调色板替换**：三组各自按明度升序一一对应 ⇒ 原图的明暗层次全部保留。
   目标色一律取自**原版材质的真实调色板**：`nether_bricks`(7) / `nether_wart_block` 上 4 档 / 深红→亮红 7 阶。
3. 灰框 6 色与宝石高光 `#FFFFFF` 原样不动。
4. 物品贴图（16×16）用同一配方（它没有宝石与雪，只发生「石砖→下界砖」）。

**验证：** alpha 通道逐像素相同、不透明像素数不变（17920 / 160）、未匹配色 0、
调色板 25→25 与 7→7、jar 内文件与工作区 SHA-256 一致；对照图人眼确认。

**结果：** ✅ 完成，详见设计文档 §十一。

### T9 —— 追加需求 2：钥匙改为物品 tag 匹配（2026-09-20 追加）

**用户原话：** 钥匙目前硬编码（和灾变一样），希望改成 **tag 匹配**，便于整合包修改、支持多种钥匙；
先讨论可行性。讨论后拍板四条：tag 名 `beloong:hell_gate_keys`、保留骸骨钥匙为出厂默认、
**要**空 tag 加载期警告、**要** tooltip（但显示**钥匙名字**而不是 tag 名）。

**交付物：** `registry/ModItemTags`、`data/beloong/tags/item/hell_gate_keys.json`、
`block/HellGateKeyWatcher`、`item/HellGateBlockItem`、`HellGateBlock`（判定改 tag）、
`ModItems`（改用新类）、`BeLoongCore`（注册 watcher）、两份 lang 各 2 键。

**验证：**
1. 静态：jar 内有 tag 文件；`HellGateBlock` 字节码用 `ModItemTags.HELL_GATE_KEYS` 且**不再**引用
   `ItemRegistry.BONE_KEY`；`HellGateBlockItem` 字节码含 `TooltipContext.registries()` /
   `lookupOrThrow(Registries.ITEM)` / `getHoverName()`；`BeLoongCore` 构造 watcher。
2. 实机：一次客户端运行同时拿到两种 tag 状态，见 §五 第三轮。
3. 真人：tooltip 悬停 + 右键开门。

**结果：** ✅ 完成。设计文档 §十二 记录了机制取证与已知限制。

### T9 的探针工程经验（本次新增，值得复用）

| 现象 | 结论 |
|---|---|
| 函数里的 `clear` / `data get` / `scoreboard players get` 反馈**不进日志** | 要观测命令结果，用 `execute store result score … run <命令>` 存进计分板，再用 `tellraw` 的 score 组件打出来（tellraw 是聊天消息，必进日志） |
| 同一个函数名重复 `schedule` **只留最后一个** | 多步排程要**各用各的函数名** |
| `/reload` 之后不能指望旧排程还在 | 排程全部由 `load` 标签重新发起（用计分板计数决定排第几步） |
| 新放进世界 `datapacks/` 的数据包会**自动启用** | 日志实证：`Found new data pack file/hellgate_keyprobe, loading it automatically` |
| 世界里的数据包可以在客户端运行时从宿主机直接换掉 | 换完 `/reload` 即生效 ⇒ **一次客户端运行就能拿到两种 tag 状态**，省掉一次整轮启动 |

---

## 二、提交策略

`run/`、`memory/`、`build/`、`tools/`、`preview/` 均已在 `.gitignore` 内 ⇒ 探针存档、记忆文件、
贴图工具与对照图**不会进版本库**。提交分五个，每个都能独立编译：

| # | 内容 | 说明 |
|---|---|---|
| C1 | 服务端移植 + 注册 + 资产 + 语言文件 | 落在 C1 后这扇门已经「能放、能开、能看」 |
| C2 | 客户端三件（动画/模型/渲染器）+ 客户端 BER 注册 | 与 C1 合并起来才是完整功能；拆开是为了让 diff 聚焦 |
| C3 | 设计文档 + 实施计划 | 纯文档 |
| C4 | 贴图换成下界风格（T8，只动两个 PNG） | 用户追加需求；代码与音效零改动 |
| C5 | 文档同步 T8 / §十一 | 纯文档（把首轮「贴图逐字节相同」的说法改正） |
| C6 | 钥匙改为物品 tag 匹配（T9） | 3 个新类 + 1 个 tag 数据文件 + 判定/注册/lang 改动 |
| C7 | 文档同步 T9 / §十二 | 纯文档（决策 D17–D19、偏离 #4、实机证据） |

提交信息沿用本仓库既有风格（中文、`<主题>：<要点>`）。

## 三、风险与回退

| 风险 | 影响 | 回退/缓解 |
|---|---|---|
| LionfishAPI 版本/坐标变化导致编译失败 | 构建红 | 依赖坐标与 `mods.toml` 下界都已写死在注释里说明理由；必要时只改这两处 |
| 灾变将来改动封印之门的实现 | 本移植件与灾变不一致（但不会坏） | 本模组**不引用**灾变的这五个类 ⇒ 灾变单方面改动不会让本模组崩溃；`ScreenShake_Entity` 是唯一耦合点，其签名若变则编译期就会暴露 |
| 40 格多方块在复杂地形放不下 | 玩家反馈「放不下去」 | 照搬原版行为（`doesGateFitInDirection` 要求全部可替换）；不自行放宽 |
| 门高 8 格带来渲染开销 | 性能 | 渲染器已 `shouldRenderOffScreen`+256 视距+3×8×3 包围盒；与灾变一致 |

## 四、完成定义（DoD）

1. `gradlew build` 退出码 0；
2. 需求 1/2/3 **逐条可证**（本文 §一 T7 表 1–9 全绿）；
3. 需求 4（复用灾变类）**有明确结论**：不可能，且理由指到源码行号（设计文档 §六）；
4. 与灾变的行为差异**有清单**（设计文档 §七，共 3 处，每处都在代码 javadoc 里标注）；
5. 设计文档 + 实施计划 + 记忆更新齐备。

---

## 五、实施结果（2026-09-20 收尾）

### 变更清单

**新增 11 个文件：**

| 文件 | 行数/字节 |
|---|---|
| `block/HellGateBlock.java` | 370 行 |
| `block/HellGateBlockEntity.java` | 177 行 |
| `client/animation/HellGateAnimation.java` | 90 行 |
| `client/model/HellGateModel.java` | 94 行 |
| `client/HellGateRenderer.java` | 86 行 |
| `assets/beloong/blockstates/hell_gate.json` | 5 行 |
| `assets/beloong/models/block/hell_gate.json` | 7 行 |
| `assets/beloong/models/item/hell_gate.json` | 6 行 |
| `assets/beloong/textures/block/hell_gate.png` | 14260 B（下界风格换色后；源件 15680 B） |
| `assets/beloong/textures/item/hell_gate.png` | 317 B（下界风格换色后；源件 277 B） |
| `assets/beloong/sounds/block/hell_gate_open.ogg` | 114357 B（= 灾变原件，未动） |

**修改 10 个文件：** `build.gradle`、`src/main/templates/META-INF/neoforge.mods.toml`、
`registry/ModBlocks.java`、`registry/ModSounds.java`、`item/ModItems.java`、
`item/ModCreativeModeTabs.java`、`BeLoongCoreClient.java`、
`assets/beloong/sounds.json`、`assets/beloong/lang/zh_cn.json`、`assets/beloong/lang/en_us.json`。

### 实机探针原始证据

```
23:49:13 [Server] [HELLGATE-PROBE] init-start
23:49:20 [Server] [HELLGATE-PROBE] block-entity-nbt-follows
23:49:20 [Server] [HELLGATE-PROBE] PASS-closed-40
23:49:22 [Server] [HELLGATE-PROBE] light
23:49:33 [Server] [HELLGATE-PROBE] view
23:49:37 [Server] [HELLGATE-PROBE] PASS-open-40
23:49:41 [Server] [HELLGATE-PROBE] done
23:50:14 Stopping server → 23:50:18 Stopping!     ← 客户端干净退出，exit code 0
```

补充取证：`BlockEntity` 相关 WARN/ERROR **0 条**；`beloong` 域的模型/贴图/音效缺失告警 **0 条**
（日志里命中的同类告警全部来自 legendary_monsters / beyonddimensions / fdbosses / fdlib /
irons_spellbooks，属既有问题）；`run/crash-reports` 无新文件。

### 第二轮：贴图下界风格换色（T8，同日追加）

在首轮三个提交之后，用户追加了「贴图改成下界风格」的需求，于是**只动了两个 PNG**（代码与音效零改动）：

| 项 | 首轮 | 第二轮 |
|---|---|---|
| `textures/block/hell_gate.png` | 15680 B（= 灾变原件） | **14260 B**（下界砖 / 血 / 红宝石） |
| `textures/item/hell_gate.png` | 277 B（= 灾变原件） | **317 B**（下界砖） |
| `sounds/block/hell_gate_open.ogg` | 114357 B | 114357 B（未动） |

校验：alpha 逐像素相同、不透明像素数 17920 / 160 不变、未匹配色 0、调色板 25→25 与 7→7、
`gradlew jar` 后 jar 内贴图与工作区 SHA-256 一致；对照图见
`preview/cmp_block_4x.png`、`preview/cmp_lock_7x.png`、`preview/cmp_item_16x.png`（左原版 / 右新版）。

### 第三轮：钥匙改为 tag 匹配（T9，同日追加）

代码改动见 T9。实机验收**一次客户端运行拿到两种 tag 状态**（世界数据包可在运行时从宿主机替换，
换完 `/reload` 即生效）：

```
12:42:25 [Server] [KEYPROBE] plan-a (expect: tag = bone_key + stick, no empty-tag warning)
12:43:05 [CHAT] [KEYPROBE] bone-key matched=1 (expect 1)      ← 默认钥匙在 tag 里
12:43:05 [CHAT] [KEYPROBE] stick matched=1 (...)              ← 整合包追加的钥匙也生效（可扩性）
           （该时段没有任何空 tag 告警 —— 阴性对照）
12:43:34 [Server] [KEYPROBE] reload-now                      ← 此时世界里已换成「清空 tag」的数据包
12:43:38 [WARN]  [BeLoong] hell gate keys: tag #beloong:hell_gate_keys is empty;
                            no item can open the hell gate   ← 加载期告警按预期触发
12:44:08 [CHAT] [KEYPROBE] bone-key matched=0 (expect 1)      ← 空 tag ⇒ 谁都开不了，且不崩
12:44:08 [CHAT] [KEYPROBE] stick matched=0
12:44:41 [WARN]  （第二次 /reload 再次告警 —— 每次数据加载都体检）
12:44:43 [Render thread] Stopping!                            ← 干净退出，无 crash-report
```

计数手法：`execute store result score … run clear @a #beloong:hell_gate_keys 99`（结果值 = 匹配件数）
+ `tellraw` 的 score 组件（函数里的 `clear` 反馈本身不进日志）。

探针数据包与两种 tag 包都归档在 `run/hellgate_probe_packs/`（`run/` 已 gitignore），
需要复跑时拷回 `run/saves/hellgate_probe/datapacks/` 即可；验收后探针世界已还原为
「出厂默认 tag（只认骸骨钥匙）」状态。

### 探针资产处置

`run/saves/hellgate_probe` 与其数据包**保留在工作区**（`run/` 已 gitignore，不进版本库），
供用户复盘或复跑；不再需要时可直接删除该目录。
贴图换色过程产生的分析脚本与对照图在 `preview/`（同样 gitignore）：`analyze_door_texture.py`、
`index_map.py`、`group_map.py`、`zoom.py`、`vanilla_palette.py`、`compare.py`、`probe_jar_textures.py`。

### 遗留（明确未验证，见设计文档 §八 D）

`setPlacedBy`（真人放置铺满 5×8）、`useItemOn`（骸骨钥匙右键 + 事件 1 启动动画）、
`playerWillDestroy`（创造模式整扇拆除）—— 三条都需要真人交互，已由用户视觉验收覆盖；
`/setblock` 与本项目现有的自动化手段无法触达它们（原因见 §〇之二）。
