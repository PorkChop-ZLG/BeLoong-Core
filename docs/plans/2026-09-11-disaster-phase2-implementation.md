# 天灾维度第二阶段 实施计划

> 注：本文引用的模组日志文本已于 2026-09-13 英文化（原为中文），语义与数值未变。

**Goal:** 用 5 个 `beloong:` 自制群系接管 14 个白名单原版群系，另 7 项改指 BWG 群系，
使天灾维度的群系里**不含任何 `minecraft:` 群系**。

> ## ✅ 执行结果（2026-09-11 完成，T0–T16 + T18 通过；T17 待客户端验收）
>
> **服务端验收四条全部成立**（`run/logs/latest.log`）：
>
> | 断言 | 实测 |
> |---|---|
> | 账目 100% 替换 | `parameter points 7593 = replaced 7593 + non-vanilla kept 0 + unsolved 0 (substitution applied)` |
> | region 树 `minecraft:` 为 0 种 | `region_0/1/2` 三个 BWG region 全部 `[]` |
> | 无 BeLoong 相关 ERROR | 无 |
> | 服务器正常启动 | `Done (21.552s)!` |
>
> **额外的生成层实测**：用临时数据包函数 `forceload` 强制生成天灾维度区块，
> **区块真的生成了**（`region/r.-2.-2.mca` 94 KB、`r.-2.-3.mca` 91 KB 等含实际数据），
> 全程无异常。这证明自制群系与地表规则在**真实生成路径**上可用，而不只是注册表层面正确。
>
> **执行中的两处计划修正**：
> 1. **T2 无法 `import` BWG 的类**——`build.gradle` 中 BWG 只有 `localRuntime`、
>    按决策 18 **刻意没有 `compileOnly`**。改用 Mixin 的字符串 `targets` + `@Pseudo`，
>    处理器签名只用原版类型，**不产生任何编译期依赖**，维持现状。
> 2. **T11 的范围比计划小得多**——关键观察：**若原版对应群系本身在原版规则里没有分支，
>    则复制品不需要规则**（两者都走"未命中"路径，行为天然一致）。
>    因此 {@code beloong:ocean} / {@code river} / {@code caves} 都**不需要**规则，
>    只有 `frozen_ocean`（海面冰）与 `windswept`（石头/砂砾）需要。
>
> **T4 的预测逐项命中**：region 树 `minecraft:` 从 12 种降到 7 种，
> 剩下恰好是白名单那 7 项，被修掉的恰好是 5 项泄漏
> （`badlands` `eroded_badlands` `wooded_badlands` `mushroom_fields` `beach`）。
>
> ---
>
> ### 补充：查询层实测抓到一个已存在的回归（2026-09-11 第二轮修复）
>
> 原计划只验证了**生成层**（index 0 树 + region 树）。补上 §4.8 记录的**查询层
> `possibleBiomes()` 逐维度实测**后，立刻发现一个被掩盖的真实缺陷：
>
> | 维度 | 修复前 | 修复后 |
> |---|---|---|
> | `beloong:disaster` | 61 种（`minecraft:` **0**） | 61 种（`minecraft:` **0**） |
> | **`minecraft:the_nether`** | **4 种（`minecraft:` 0）❌** | **9 种（`minecraft:` 5）✅** |
> | `minecraft:overworld` | 113 种（`minecraft:` 53） | 113 种 |
> | `minecraft:the_end` / `beloong:loong_palace` | 未受污染 | 未受污染 |
>
> **根因**：`PossibleBiomesFilterMixin` 的维度判据是**全局**标志 `isSubstitutionApplied()`，
> 而 `LevelUtils.initializeOnServerStart` 会遍历所有 level stem ——
> **`minecraft:the_nether` 同样会走到 `appendDeferredBiomesList`**。
> 天灾处理完标志即为真，下界的 5 个原版群系便被 `isBlocklisted` 一并过滤
> ⇒ 下界结构集因 `hasBiomesForStructureSet` 预筛失败而**整条剔除**、`/locate` 失效。
>
> **修法**：`CloneParameterListMixin` 新增 `@Redirect`，在 `LevelUtils.initializeBiomes`
> 调用 `appendDeferredBiomesList` 的**前后**用 `levelKey` 精确包夹
> （`beginTargetBiomeList()` / `endTargetBiomeList()` + `try/finally`），
> 查询层改读这个**作用域**标志。
>
> **为什么第一阶段没暴露**：当时白名单非空，被误滤的下界群系在账目里仍算"待剔除残留"，
> 看起来像设计如此；白名单清空后下界 `minecraft:` 直接归零，异常才显形。
>
> **教训**：跨维度共享的全局状态不能用来做维度判定。这也是"消费端取证"的直接收益——
> 计划里原本只安排了生成层验证，补上查询层实测才发现问题。
>
> **已知次要项（未修）**：天灾维度的 `possibleBiomes` 里有 1 个
> `terrablender:deferred_placeholder`（BWG region 树吐出的哨兵）。它不会生成、
> 也不被任何结构集引用，属良性；但它并非真实群系，故天灾的计数是 61 而非 60。
>
> ---
>
> ### 补充二：`/locate biome` 端到端验证 + 哨兵消除（2026-09-11 第三轮）
>
> **先修掉上面那条"已知次要项"**：哨兵虽良性，但 `possibleBiomes()` 正是**自然罗盘的列表**
> 与 `/locate biome` 的数据源——留着它会让自然罗盘把它列成一个"可搜索"的群系，
> 而搜索永远扫不到东西。已加 `DisasterBiomeSubstitution.isDeferredSentinel(...)`
> 并在查询层过滤掉。**实测天灾的 `possibleBiomes` 由 61 种降为 60 种，全部为真实群系。**
>
> **再做端到端验证**：用临时数据包函数在 `beloong:disaster` 里执行 `/locate biome`。
> 这一条之所以是**最强证据**，是因为 `LocateCommand` 的结构决定了输出的可判定性：
>
> ```java
> :128   Pair<BlockPos, Holder<Biome>> pair = source.getLevel().findClosestBiome3d(biome, blockpos, 6400, 32, 64);
> :131       throw ERROR_BIOME_NOT_FOUND.create(...);                        // 找不到 → 抛异常
> :199   source.sendSuccess(...);
> :200   LOGGER.info("Locating element " + elementName + " took " + ...);     // 只在成功路径
> ```
>
> 而 `findClosestBiome3d` 的**第一行**就是 `possibleBiomes()` 预筛
> （`BiomeSource.java:78-84`，集合为空立即返回 null）。**所以"有无那行日志"就等价于
> "能否定位"，而能否定位又等价于"在不在 `possibleBiomes()` 里"。**
>
> | 探测目标 | 实测输出 | 判定 |
> |---|---|---|
> | `minecraft:ocean` / `plains` / `frozen_ocean` / `lush_caves` / `windswept_hills` / `stony_peaks` | **无任何输出** | ✅ 抛 `ERROR_BIOME_NOT_FOUND`，搜不到 |
> | `terrablender:deferred_placeholder` | **无任何输出** | ✅ 已随本轮修复消失 |
> | `beloong:ocean` | `Locating element beloong:ocean took 4 ms` | ✅ 可定位 |
> | `beloong:windswept` | `Locating element beloong:windswept took 2447 ms` | ✅ 可定位 |
> | `biomeswevegone:prairie` | `Locating element biomeswevegone:prairie took 37 ms` | ✅ 可定位 |
>
> **⇒ 设计文档最关心的那条用户可见行为（"`/locate biome` 在天灾维度搜不到原版群系"）
> 已由真实命令路径证实。**
>
> （`beloong:windswept` 的 2447 ms 使服务器出现一次 `Can't keep up!` 警告——
> 那是 `/locate` 的螺旋扫描本身的开销，与总设计 §九"自然罗盘搜索不做预筛"记的是同一现象，不是缺陷。）
>
> ---
>
> ### 补充三：独立代码审查与两处修复（2026-09-11 第四轮）
>
> 对整套改动做了一次独立审查（Critical 零个）。两处 Important 是真实缺陷，均已修复并实机复验。
>
> #### ① Critical 级：`windsweptRocky()` 丢了 `UNDER_FLOOR` 门
>
> **这是本轮发现的唯一 Critical 级问题，而且是本次改动自己引入的。**
>
> `SurfaceSystem.java:124-153` 的循环是
> `for (int i3 = 地表高度; i3 >= getMinBuildHeight(); i3--)`，
> 对**整列中每一个**等于 `defaultBlock`（石头）的方块都调用 `tryApply`，命中非 null 就替换。
>
> 原版 `WINDSWEPT_GRAVELLY_HILLS` 那条阶梯整条挂在
> `SurfaceRuleData.java:280` 的 `ifTrue(UNDER_FLOOR, rulesource6)` 之下，只作用于地表以下若干格。
> 我原先照抄了规则体却**丢掉了这层门**，而最后一级 `stoneOrGravel` 恒不为 null
> ⇒ **整个 `beloong:windswept` 的地下石头都会被换成砂砾**（该群系占 352 / 7593 个参数点）。
>
> **修法**：新增 `surfaceWindow(rule)` = `sequence(ifTrue(ON_FLOOR, rule), ifTrue(UNDER_FLOOR, rule))`，
> 给阶梯的每一级都套上。形态取自 BWG 的 `GRASS_DIRT_DIRT_SURFACE` / `makeBeachSandRule`
> ——它的每条叶子规则都被 `ON_FLOOR`/`UNDER_FLOOR` 门住，是应当照抄的既有范例。
>
> #### ② Important 级：第三条注入路径仍用全局标志推断维度
>
> `BwgRegionBiomeRewriteMixin` 原先读全局的 `isSubstitutionApplied()`——正是上一轮修掉的
> 同一类缺陷（当时只修了查询层）。虽然实测当前不会误伤（`Regions.get(NETHER)` 只含
> `DefaultNetherRegion`，不含 BWG 的 region），但这是"碰巧安全"。
>
> **修法**：把上一轮那个只包夹 `appendDeferredBiomesList` 的 `@Redirect` 换成
> `initializeBiomes` 的 **HEAD/RETURN `@Inject` 包夹**（`levelKey` 精确判定 + `@At("RETURN")`
> 覆盖全部 return），使**两条注入路径共用同一个作用域标志**，彻底移除全局标志依赖。
> 实测日志确认两个 `@Inject` 均已应用：
> ```
> beloong.mixins.json:CloneParameterListMixin ... @Inject::beloong$beginTargetDimension(...)
> beloong.mixins.json:CloneParameterListMixin ... @Inject::beloong$endTargetDimension(...)
> ```
>
> #### 另修的 Important 与次要项
>
> - `BeLoongCore`：地表规则注册改包在 `event.enqueueWork(...)` 里。`FMLCommonSetupEvent` 是
>   **并行分发**的而 `SurfaceRuleManager` 内部是普通 `HashMap`；**BWG 自己也是这么做的**
>   （`BiomesWeveGoneNeoForge.onInitialize`），我原先的注释把它写错了。
>   丢失更新会让规则被静默丢弃且不报错——正是本功能最怕的失效模式。
> - region tree audit 的标题与实际矛盾：index 0（`DefaultOverworldRegion`）的 `addBiomes` 输出恒为
>   53 个原版群系，但它的**树**来自被改写的 `values`、并非实际取群系来源。已在该行显式标注。
> - 账目里的 `whitelist kept` 标签在白名单清空后已名不副实，改为 `non-vanilla kept`。
> - 删掉一处不可达分支（`rewriteKey` 返回不同键时 `getHolder` 必非空）。
> - 修正三处文档/注释与实际不符：`araucaria_savanna` 是"无地表规则"的例外、
>   `mushroom_fields → crag_gardens` 同样无规则（已显式记录为已知例外）、
>   注册时机写成了"mod 构造期"（实为 common setup）。
> - `PossibleBiomesFilterMixin` 的 `ci.cancel()` 会跳过 TerraBlender 的 `hasAppended` 幂等守卫，
>   已加注释说明这是刻意为之。
>
> #### 复验结果
>
> | 断言 | 实测 |
> |---|---|
> | 两条 `@Inject` 已应用 | ✅ 日志可见 |
> | 账目 | `replaced 7593 + non-vanilla kept 0 + unsolved 0` ✅ |
> | region 树 `minecraft:` | 三个 BWG region 均 `0 种` ✅ |
> | `possibleBiomes` | 天灾 `60 种 {beloong=5, biomeswevegone=55}`；**下界 `9 种 {minecraft=5}`** ✅ |
> | 地表规则分发表 | `[minecraft, beloong, biomeswevegone]` ✅ |
> | `/locate` 负例（`minecraft:*`、哨兵） | **无任何输出**（= 抛 `ERROR_BIOME_NOT_FOUND`）✅ |
> | `/locate` 正例 | `beloong:windswept 569 ms`、`beloong:ocean 485 ms` ✅ |
> | 生成期异常 | 无 ✅ |
> | **SURFACE 阶段确实执行过** | 天灾维度 **8/8 个 poi 文件有数据**（区块跑到放置方块阶段，SURFACE 在其之前）⇒ 新地表规则执行未抛异常 ✅ |
>
> > **验证方法上的一处自我纠正**：我一度用"磁盘上的 region 文件"作为生成证据。
> > 但总设计 §4.8 明确警告**不得依赖 NBT/磁盘扫描**。本轮的对照实验正好印证了这点：
> > **主世界的 region 文件同样是 0 字节**（保存/刷盘时序问题），与我们的改动无关——
> > 若只看数据盘会得出错误结论。可靠的信号是 poi 文件 + 日志无异常 + `/locate` 的真实命令路径。
>
> ---
>
> ### 补充四：地表规则的**方块级**验证（2026-09-11 第五轮）
>
> 补充三修掉的那个 Critical（`windsweptRocky()` 丢 `UNDER_FLOOR` 门）此前只验证到
> "SURFACE 阶段执行过且未抛异常"。**它到底有没有产出正确的方块，仍未被验证**——
> 而这正是我写错过一次的地方。本轮用 RCON 把它验掉了。
>
> #### 手法
>
> 启用 `enable-rcon`，用约 40 行 PowerShell 实现最小 RCON 客户端（TCP + 认证包 + 命令包），
> 即可从外部下达命令并**读回返回值**。两条关键命令：
>
> | 命令 | 用途 |
> |---|---|
> | `execute in beloong:disaster run locate biome <b>` | 取得该群系的实际坐标（RCON 源不被抑制，`sendSuccess` 会回传） |
> | `fill <x> -64 <z> <x> 200 <z> <m> replace <m>` | **统计**该柱某材料的数量（含相同方块的 fill 不计数，故用 `minecraft:air` 作目标；**这是破坏性的**，但探测存档本来就是临时的） |
> | `execute in beloong:disaster if biome <pos> <b> run time query gametime` | 条件为真才返回值 ⇒ 可判定该坐标的真实群系 |
>
> #### 结果
>
> **`beloong:windswept` 柱 `(256, -2400)`** —— 内存读数确认群系为 `beloong:windswept`
> （y=130 / 100 / 70 三处均命中；`minecraft:windswept_hills` 从不命中）：
>
> | 材料 | 该柱数量 | 判读 |
> |---|---|---|
> | `minecraft:grass_block` | **0** | ✅ **地表规则确实生效**——若规则未命中会落回原版规则的默认草 |
> | `minecraft:stone` | **68** | ✅ **门生效**——Critical 缺陷若还在，这 68 个石头会全变成砂砾 |
> | `minecraft:gravel` | 6 | ✅ 与阶梯的"噪声 > 2.0 带"相符 |
> | `minecraft:dirt` | 7 | ✅ 与"噪声 > -1.0 带"相符 |
>
> ⇒ **同时证明了「规则生效」与「只作用于表层窗口、没有污染整个地下」两件事。**
>
> **`beloong:frozen_ocean`** —— 内存读数确认为 `beloong:frozen_ocean`
> （`-736/63/-1568`、`-730/63/-1560` 命中；`minecraft:frozen_ocean` 从不命中）；
> 其所在区块（y 40–80）内 **43 个冰方块** ⇒ **冰海确实结冰**。
> 单柱取样曾显示 0 冰，原因是那一柱恰好落在冰面的**开阔水缝**里——这正是冰海的正常外观。
>
> #### 两个把自己绊了一下的地方（记下来）
>
> 1. **`say` 不产生 RCON 响应。** 我最初用
>    `execute if biome … run say HIT` 做判定，结果**恒为空**，无论条件真假。
>    发现方式是加阳性对照（`if loaded` 必真）——它同样返回空，于是暴露出是命令本身的问题。
>    改用会返回值的 `time query gametime` 后，阳性/阴性对照都正常。
>    **⇒ 核对"验证工具本身是否可用"是必需的，不能只看目标结果。**
> 2. **`fill ... replace` 是破坏性的**，我用它数完方块后又想读同一柱的剖面，自然是全空气。
>    计数是在破坏前完成的，故证据成立；但**计数与剖面不能在同一柱上先后做**。


**Architecture:**
三条注入路径共同完成改写 —— 现有两条（`CloneParameterListMixin` 改 index 0 兜底树、
`PossibleBiomesFilterMixin` 过滤查询层）覆盖 14 个 DEFERRED 桶项；
**本计划新增第三条**（Mixin `BWGTerraBlenderRegion.addBiomes`）覆盖 7 个 region 树桶项，
并顺带修掉第一阶段遗留的 5 项黑名单泄漏。
群系本体为手写数据包 JSON（沿用 `beloong:loong_palace.json` 先例），
地表规则用 `beloong:` 命名空间的 `SurfaceRules` 注册。

**Approach:** 采用已定案的「方案 B」——自制 5 个（海洋 2 + 河流 1 + 洞穴 1 + 碎裂地形 1），
其余 7 项交给 BWG。**先做注入路径**，因为它是唯一有技术风险的部分、且同时解锁 7 项与修复 5 项泄漏；
群系与地表规则是机械工作，可以后置。

**上游文档:**
- 设计：[`docs/天灾维度总设计.md`](../天灾维度总设计.md) §10
- 逐项表：[`docs/plans/2026-09-11-disaster-phase2-biome-table.md`](2026-09-11-disaster-phase2-biome-table.md)
- 取证：[`docs/reviews/2026-09-11-disaster-region-tree-probe.md`](../reviews/2026-09-11-disaster-region-tree-probe.md)

---

## 一、前提与约束

| 项 | 事实 |
|---|---|
| 构建 | `gradlew.bat`（无全局 gradle）；Java 21；NeoForge 21.1.236 |
| 无测试源集 | **没有 `src/test`，没有 datagen**。本计划用「编译 + 实机服务器验证」替代 TDD |
| 群系定义 | 手写 JSON，放 `src/main/resources/data/beloong/worldgen/biome/` |
| 地表规则 | Java 侧 `SurfaceRuleManager.addSurfaceRules(...)`，项目**此前未使用过** |
| 无配置开关 | 本功能没有逃生舱（决策 19）⇒ **异常安全是必需项**，见 T2 |
| 验证分工 | 我驱动 `runServer` 读日志账目；客户端观感由用户验收 |

### 验证方法（替代 TDD）

项目没有单测，且复盘文档明确要求**消费端取证**。本计划每个可验证的任务用三者之一：

| 手段 | 命令 / 位置 | 能证明什么 |
|---|---|---|
| **编译** | `.\gradlew.bat compileJava` | 代码合法；**不能**证明注入点可用（复盘 M2） |
| **服务器启动** | `.\gradlew.bat runServer` → `run/logs/latest.log` | 注入是否真的应用、账目是否逐点闭合 |
| **region tree audit** | 新注入路径自己打的日志 | region 树里还剩多少 `minecraft:` 群系 |

> ⚠️ **`runServer` 的前置条件**：`run/mods` 里的 `sodium` / `iris` 是纯客户端模组，
> 会让专用服务器在 bootstrap 阶段 `NoClassDefFoundError: org/lwjgl/Version`。
> 验证前需临时移出，验证后还原。`run/eula.txt` 已存在（`eula=true`）。
> 服务器用 `run/server.properties` 的 `level-name`；如需避免动到自己的测试存档，先改成新名字。

---

## 二、任务

### 阶段 0 — 基线

#### T0：记录基线
**Files:** 无（使用现有代码）
**Steps:**
1. 临时移出 `run/mods/{sodium,iris}` 到 `run/mods.disabled-probe/`
2. `.\gradlew.bat runServer`，等 `[BeLoong] disaster biome substitution` 出现后停服
3. 记录基线数字
**Verification:** 日志中应出现
`parameter points 7593 = replaced 6782 + whitelist kept 811 + unsolved 0 (substitution applied)`

---

### 阶段 1 — 第三条注入路径（解锁 7 项 + 修 5 项泄漏）

#### T1：抽出共享的"单条改写"入口
**Files:**
- Modify: `src/main/java/com/zonlong/beloong/worldgen/DisasterBiomeSubstitution.java`

**Steps:**
1. 现有 `resolveTarget(Registry<Biome>, ResourceLocation)` 是 `private`，且只接受 `ResourceLocation`
2. 提升为包内可见，或新增：
   ```java
   /** 给定一个原版群系键，返回应改写成的新键；不应改写或无法改写时返回原键。 */
   public static ResourceKey<Biome> rewriteKey(Registry<Biome> registry, ResourceKey<Biome> from)
   ```
   内部复用 `isBlocklisted` + `resolveTarget`，**失败时返回原键**（不改写），并沿用现有的分条 try/catch 语义
3. 不改动 `filter()` 的对外行为

**Verification:** `.\gradlew.bat compileJava` 通过；`filter()` 逻辑未改（diff 只增不减语义）

#### T2：新建 region 树改写 Mixin
**Files:**
- Create: `src/main/java/com/zonlong/beloong/mixin/BwgRegionBiomeRewriteMixin.java`
- Modify: `src/main/resources/beloong.mixins.json`（`mixins` 数组加 `"BwgRegionBiomeRewriteMixin"`）

**Steps:**
1. 目标：`BWGTerraBlenderRegion.addBiomes(Registry<Biome>, Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>>)`
   —— 公开方法、签名稳定；**一处注入同时覆盖 RTree 构建与 `appendDeferredBiomesList` 两条子路径**，
   因为两者都调 `region.addBiomes`（`MixinParameterList.java:85-89`、`LevelUtils.java:117-120`）
2. 注解：`@Mixin(value = BWGTerraBlenderRegion.class, remap = false)` +
   `@ModifyVariable(method = "addBiomes", at = @At("HEAD"), argsOnly = true)`
   包裹传入的 `Consumer`，对每个 pair 调 `DisasterBiomeSubstitution.rewriteKey(...)`
3. **维度守卫**：`if (!DisasterBiomeSubstitution.isSubstitutionApplied()) return mapper;`
   > ⚠️ **本步描述的是初版实现。** 后续发现该全局标志不足以做维度判定
   > （会误伤下界），已改为作用域标志，见顶部「补充：查询层实测抓到一个已存在的回归」。
   —— 替换没真的发生就完全不动手，与查询层的判据保持一致（决策 21）
4. **异常安全（必需）**：每个 pair 的改写包在 try/catch 内，**失败时原样放行**。
   异常若穿透会进入 `initializeForTerraBlender` / `LevelUtils` 的 level stem 循环，
   导致该维度之后的所有 level stem 不再初始化（决策 23）

**Verification:** `.\gradlew.bat compileJava` 通过。
⚠️ **编译成功不代表注入可用**（复盘 M2），必须由 T3 实机确认

#### T3：加 region tree audit 日志
**Files:**
- Modify: `src/main/java/com/zonlong/beloong/mixin/BwgRegionBiomeRewriteMixin.java`（或 `DisasterBiomeSubstitution`）

**Steps:**
1. 统计每次 `addBiomes` 调用中：改写条数、**未能改写而残留的 `minecraft:` 群系集合**
2. 用 `BeLoongCore.LOGGER` 打一行，格式对齐现有账目日志风格
3. 残留集合**非空时按 ERROR 级别报出**（与 `filter()` 的 `unsolved` 处理一致）

**Verification:** 由 T4 一并确认

#### T4：验证 5 项泄漏已修
**Files:** 无
**Steps:**
1. `.\gradlew.bat runServer`
2. 读日志
**Verification:** 三条同时成立才算通过：
- `[BeLoong] disaster biome substitution: parameter points 7593 = replaced 6782 + whitelist kept 811 + unsolved 0`
  （**数字应与 T0 完全相同**——本阶段还不改映射表与白名单）
- region tree audit 里 `minecraft:` 群系**从 12 种降到 7 种**
  （只应剩下白名单里那 7 个 region 树桶项：`river` `frozen_river` `lush_caves`
  `dripstone_caves` `deep_dark` `stony_shore` `windswept_savanna`）
- 被修掉的应是这 5 个：`badlands` `eroded_badlands` `wooded_badlands` `mushroom_fields` `beach`

> 这是本计划里**唯一有技术不确定性的验收点**。若 7 种这个数字对不上，
> 停下来重新取证，不要继续往下做。

---

### 阶段 2 — 5 个自制群系

> **关键事实**：群系 JSON **不含自己的 ID**（ID 由文件名决定），
> 所以"复制原版群系"就是**换一个文件名**，内容零修改。
> 源文件在 `build/moddev/artifacts/neoforge-21.1.236-client-extra-aka-minecraft-resources.jar`
> 的 `data/minecraft/worldgen/biome/<name>.json`。

#### T5–T9：逐个建群系

| Task | 目标文件 | 复制自 | 备注 |
|---|---|---|---|
| **T5** | `.../beloong/worldgen/biome/frozen_ocean.json` | `minecraft:frozen_ocean` | 保留 `temperature: 0.0` + `temperature_modifier: frozen` → 自动结冰 |
| **T6** | `.../beloong/worldgen/biome/ocean.json` | `minecraft:ocean` | 温度 `0.5`。覆盖 cold/deep_cold/ocean/deep_ocean |
| **T7** | `.../beloong/worldgen/biome/river.json` | `minecraft:river` | 温度 `0.5`。`frozen_river` 也指它 ⇒ 冻河不再结冰（已接受） |
| **T8** | `.../beloong/worldgen/biome/caves.json` | `minecraft:lush_caves` | 内容最丰富（洞穴藤蔓、孢子花、杜鹃树、美西螈、专属音乐） |
| **T9** | `.../beloong/worldgen/biome/windswept.json` | `minecraft:windswept_hills` | 覆盖 gravelly/forest（三者 JSON 本就相同，差别只在地表规则） |

**Steps（每个）:**
1. 从 jar 提取源 JSON 内容
2. 写为目标文件（内容不变）
3. 确认 `features` 仍是 11 个数组、`carvers` 仍是 `{"air":[...]}`（结构完整）

**Verification（T5–T9 合并一次）:** `.\gradlew.bat runServer` 能正常启动。
**群系 JSON 若有语法/字段错误，服务器会在数据包加载阶段直接失败** —— 这是有效验证。
此时群系尚未被任何映射指向，属正常。

#### T10：补语言键
**Files:**
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

**Steps:** 为 5 个别群系加 `biome.beloong.*`
（`frozen_ocean` / `ocean` / `river` / `caves` / `windswept`），
按项目惯例**保持键有序**、中文与英文语气对齐现有条目。

**Verification:** JSON 可被解析；键名与群系 ID 一一对应

---

### 阶段 3 — beloong 命名空间地表规则

> **为什么必需**：原版 `overworld` 的 `surface_rule` 按**具体群系 ID** 分支
> （`SurfaceRuleData.java` 内 30 余处 `isBiome(...)`）。换成 `beloong:` ID 后一条都不命中，
> 会全部掉到默认**草/土**。对海洋/河流/洞穴影响有限，但**碎裂地形会变成草坡**。

#### T11：新建 rules 类
**Files:**
- Create: `src/main/java/com/zonlong/beloong/worldgen/BeloongSurfaceRules.java`

**Steps:**
1. 参照 BWG 的 `BWGOverworldSurfaceRules` 结构（`biomeAbovePreliminarySurface` 包裹 + `SurfaceRules.sequence`）
2. 至少覆盖：
   - `beloong:frozen_ocean` → 冰分支（对应原版 `SurfaceRuleData.java:69` 的 `minecraft:temperature` 条件）
   - `beloong:ocean` → 海底材质（暖/温海要沙，冷/中性海默认砂砾）
   - `beloong:windswept` → 石头 / 砂砾（对应 `:85` `:119`）
   - `beloong:caves` → 石头（对应 `:88` 的 `dripstone_caves` 规则）
3. **只处理 `beloong:` 群系即可** —— 返回 `null` 时会自动落回原版规则（两段式）

**Verification:** `.\gradlew.bat compileJava`

#### T12：在 commonSetup 注册
**Files:**
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`（`commonSetup`，第 119 行）

**Steps:**
1. `SurfaceRuleManager.addSurfaceRules(SurfaceRuleManager.RuleCategory.OVERWORLD, "beloong", BeloongSurfaceRules.makeRules());`
2. 加一行 INFO 日志确认注册发生
3. 注意 `FMLCommonSetupEvent` 是**并行分发**的；`SurfaceRuleManager` 内部是普通 `HashMap`。
   跟随 BWG 的既有做法（它也在 common setup 注册），但若出现异常改走 `event.enqueueWork(...)`

**Verification:** `.\gradlew.bat compileJava` + `runServer` 正常启动且日志出现注册行

#### T13：确认规则被装配进噪声设置
**Files:** 无
**Steps:** `runServer`，确认启动无异常、`namespacedSurfaceRuleSource` 构建路径未被破坏
**Verification:** 服务器正常启动到 `Done`；无 `SurfaceRules` 相关异常

> 地表规则的**观感**只能由 T17 的客户端验收确认。

---

### 阶段 4 — 接线

#### T14：扩展映射表支持任意命名空间
**Files:**
- Modify: `src/main/java/com/zonlong/beloong/worldgen/DisasterBiomeMapping.java`

**Steps:**
1. 现有实现把目标硬编码为 `biomeswevegone:` 前缀。改为 switch 直接返回**完整 ID 字符串**，
   再用 `ResourceLocation.parse(...)`（或按 `:` 拆分）构造 `ResourceKey`
2. 新增 14 条映射：

   | 原版 | 目标 |
   |---|---|
   | `frozen_ocean` / `deep_frozen_ocean` | `beloong:frozen_ocean` |
   | `cold_ocean` / `deep_cold_ocean` / `ocean` / `deep_ocean` | `beloong:ocean` |
   | `river` / `frozen_river` | `beloong:river` |
   | `lush_caves` / `dripstone_caves` / `deep_dark` | `beloong:caves` |
   | `windswept_hills` / `windswept_gravelly_hills` / `windswept_forest` | `beloong:windswept` |
   | `lukewarm_ocean` / `deep_lukewarm_ocean` | `biomeswevegone:lush_stacks` |
   | `warm_ocean` | `biomeswevegone:dead_sea` |
   | `stony_shore` | `biomeswevegone:basalt_barrera` |
   | `windswept_savanna` | `biomeswevegone:araucaria_savanna` |
   | `stony_peaks` | `biomeswevegone:red_rock_peaks` |
   | `snowy_beach` | `biomeswevegone:dacite_shore` |

3. **新增条目必查项**：核对 BWG 目标在 `BWGOverworldSurfaceRules.makeRules()` 里有没有地表规则
   （逐项表 §6.2）。上表 7 个 BWG 目标均已核对过

**Verification:** `.\gradlew.bat compileJava`

#### T15：清空白名单
**Files:**
- Modify: `src/main/java/com/zonlong/beloong/worldgen/DisasterBiomeSubstitution.java`（`WHITELIST`）

**Steps:**
1. 把 `WHITELIST` 清空为 `Set.of()`；保留字段与判据方法（`isWhitelisted` / `isBlocklisted` 语义不变）
2. 更新类 javadoc：21 项已全部接管，白名单机制保留但当前为空
3. **不要**删除 `isWhitelisted` —— 它是两条注入路径共用的抽象

**Verification:** `.\gradlew.bat compileJava`

#### T16：全量验证（本阶段关键验收）
**Files:** 无
**Steps:** `.\gradlew.bat runServer`，读日志
**Verification:** 四条全部成立：
1. 账目逐点闭合且**替换率为 100%**：
   `parameter points 7593 = replaced 7593 + non-vanilla kept 0 + unsolved 0 (substitution applied)`
2. region tree audit 里 `minecraft:` 群系 **0 种**（残留集合为空、无 ERROR）
3. 启动无 `disaster biome substitution failed` 报错
4. 全流程无异常（服务器跑到 `Done`）

---

### 阶段 5 — 收尾

#### T17：客户端验收（**用户执行**）
**Steps:** 用户 `runClient`，进入天灾维度，检查：
- 海洋仍是海、冰海仍结冰、冰山仍在
- 河流有水
- 洞穴有 lush_caves 的内容（藤蔓、孢子花、发光地衣、美西螈）
- 碎裂地形露出石头/砂砾，**不是草坡**
- 暖/热海洋变成海藻海 / 死海（这是刻意选择）
- `/locate biome minecraft:ocean` 在天灾维度搜不到

#### T18：更新文档
**Files:**
- Modify: `docs/天灾维度总设计.md`（§1.3 阶段说明、§4.1 目标状态、§4.8 实测数字、§六、§八 决策、§九、§10）
- Modify: `docs/plans/2026-09-11-disaster-phase2-handover.md`（标记结案）
- Modify: `memory/decisions-log.md`

**Verification:** 文档中不再有"第二阶段未开始"的表述；账目数字与实测一致

---

## 三、顺序与依赖

```
T0 ── T1 ── T2 ── T3 ── T4 ──┐
                             │
T5..T9 ── T10 ───────────────┤
                             ├── T14 ── T15 ── T16 ── T17 ── T18
T11 ── T12 ── T13 ───────────┘
```

- **阶段 1 必须先做**：它是唯一有技术不确定性的部分；T4 若失败，后续全部无意义
- **阶段 2 / 3 可并行**（互不依赖）
- **T14 依赖 T5–T9**（映射目标必须已存在，否则 `isUsable` 判为不可用 → 保留原版 + 记 ERROR）
- **T15 必须在 T14 之后**：白名单清空前，那 14 项仍会被 `filter()` 放行

---

## 四、风险与回滚

| 风险 | 影响 | 应对 |
|---|---|---|
| **T2 注入点不可用** | 阶段 1 整体失败 | 复盘 M2：必须实机确认。备选注入点见总设计 §10.6（`Climate.ParameterList` 内 `@Redirect Region.addBiomes`，需 MixinExtras `@Local`） |
| **T4 的 7 种数字对不上** | 说明 region 树构成与取证不符 | **停下来重新取证**，不要继续 |
| **映射目标不可用** | 该项保留原版 + ERROR，账目 `unsolved > 0` | 检查群系 ID 拼写与 JSON 是否真的加载；T16 的"unsolved = 0"是硬门槛 |
| **地表规则没生效** | 碎裂地形变草坡（观感损坏，不崩） | T17 客户端验收；`beloong:` 规则返回 `null` 时会落回原版，属安全降级 |
| **无配置开关** | 出问题只能改代码 | 沿用决策 23：新注入路径必须 try/catch，失败时退化为"原样放行"而非抛异常 |
| **动了主世界** | 灾难性 | region 树改写受 `isSubstitutionApplied()` 守卫；主世界不在 `overworld_regions` tag 内，`initializeBiomes` 提前 return，两者都不会触发 |

**回滚方式**：`WHITELIST` 恢复为 21 项 + 删除 `DisasterBiomeMapping` 中新增条目 + 从
`beloong.mixins.json` 移除 `BwgRegionBiomeRewriteMixin` → 完全回到第一阶段状态。
（`beloong.mixins.json` 的注册是唯一的"开关"，与 `disaster_test` 分支当时的撤销方式一致。）

---

## 五、验收标准

> 状态截至 2026-09-11 第 3 轮。**服务端与文档部分已全部达成并附实测证据；客户端观感部分待用户执行。**

**服务端（已达成）:**
- [x] 账目：`replaced 7593 + non-vanilla kept 0 + unsolved 0 = 7593`
      （标签由 `whitelist kept` 改名——白名单已清空，该项现在指"原样放行的非 minecraft: 条目"）
- [x] region tree audit：`biomeswevegone:region_0/1/2` 的 `minecraft:` 群系 **0 种**
- [x] 启动与运行期无 ERROR 级 `[BeLoong]` 日志
- [x] **额外** — 查询层 `possibleBiomes()` 逐维度实测：
      天灾 `60 种 {beloong=5, biomeswevegone=55}`（`minecraft:` **0**）；
      下界 `9 种 {minecraft=5, regions_unexplored=4}`；主世界 `113 种 {minecraft=53, …}`——
      即**其余维度未被波及**（下界一度被误伤，已修复，见顶部「补充一」）
- [x] **额外** — `/locate biome` 端到端：`minecraft:*` 与哨兵全部**无输出**（= 抛
      `ERROR_BIOME_NOT_FOUND`）；`beloong:ocean` `took 4 ms`、`beloong:windswept` 可定位
- [x] **额外** — **5 个自制群系逐个可定位**（最后一轮补测，此前只测过其中 2 个）：
      `beloong:frozen_ocean` 12019 ms、`beloong:ocean` 151 ms、`beloong:river` 0 ms、
      `beloong:caves` 0 ms、`beloong:windswept` 738 ms；
      对照 `biomeswevegone:lush_stacks` 1166 ms；对照负例
      `minecraft:frozen_ocean` / `river` / `lush_caves` / `terrablender:deferred_placeholder`
      全部无输出。
      > `frozen_ocean` 耗时 12 秒是 `/locate` 的螺旋扫描开销——它只占 4 / 7593 个参数点且仅在
      > 冰带出现，属于本项目的正常现象（同 §九「自然罗盘搜索不做预筛」那条）。不是缺陷。
- [x] **额外** — 5 个自制群系全部注册就绪（`registry.getHolder` 命中）
- [x] **额外** — 地表规则进了分发表：`[minecraft, beloong, biomeswevegone]`
- [x] **额外** — 三条注入路径的 Mixin 注入均已在启动日志中确认应用
- [x] **额外** — SURFACE 阶段确实执行且未抛异常：天灾维度 **8/8 个 poi 文件有数据**
      （区块到了放置方块阶段，SURFACE 在其之前）
- [x] **额外** — **地表规则的方块级验证**（见顶部「补充四」）：
      `beloong:windswept` 柱 `(256,-2400)` 内 `grass_block` **0 个**、
      `stone` **68 个**、`gravel` 6 个、`dirt` 7 个
      ⇒ 规则生效（无草）**且**只作用于表层窗口（石头未被整列换掉）；
      该柱与 `beloong:frozen_ocean` 处的群系身份经 `if biome` 内存读数确认；
      冻结海区块内有 **43 个冰方块** ⇒ 冰海结冰

**客户端（待用户执行 —— 本计划唯一未完成项）:**
- [ ] 海洋/河流/洞穴/碎裂地形观感符合预期
- [ ] `/locate biome minecraft:ocean` 在天灾维度搜不到（服务端已证，客户端可复核）
- [ ] 地表规则生效（碎裂地形露石，非草坡）

**文档（已达成）:**
- [x] 总设计不再有"第二阶段未开始"表述，实测数字更新
- [x] 逐项表、交接文档、`memory/decisions-log.md` 同步

---

## 六、可复用的验证手法（记下来）

1. **临时数据包函数**：`data/minecraft/tags/function/load.json` 挂一个
   `execute in <dim> run <cmd>` 函数，即可在**无客户端**的情况下从游戏内存端到端验证。
   已验证有效的两类命令：
   - `locate biome <biome>` —— `LocateCommand.java:200` 的
     `Locating element ... took N ms` **只在成功路径打印**，可直接当断言信号；
     而 `findClosestBiome3d` 第一行就是 `possibleBiomes()` 预筛，故它同时验证查询层。
   - `forceload add <x> <z>` —— 强制生成区块，让只在生成期执行的代码（如地表规则）真正跑到。
2. **主世界做对照**：判断某个磁盘/日志现象是否由本次改动引起时，先看主世界是否同样如此。
   本轮"region 文件全为 0 字节"就是这样排除的。
3. **不要用磁盘产物当生成证据**（总设计 §4.8 已警告）。可靠信号是 poi 文件、
   游戏内存读数、以及真实命令路径的返回值。
4. **看完服务器记得 `job_output` 排空后台任务的 stdout** —— 缓冲区满了会让服务器
   阻塞在日志写入上，表现为"卡死"，实为假象。
5. **清理临时探针文件时只删自己创建的确切路径**，不要对上层目录用 `-Recurse`
   （本项目曾因此误删 3 个原有标签文件）。

