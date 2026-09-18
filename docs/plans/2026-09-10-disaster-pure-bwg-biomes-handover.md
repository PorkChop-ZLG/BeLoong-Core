# 天灾维度纯 BWG 群系接管 — 完整交接文档

> **✅ 状态：已结案（Closed）。** 本文记录的目标已由 `disaster2` 分支实现，**第一阶段完成**。
>
> | 项 | 值 |
> |---|---|
> | **原状态** | 未实现，代码已全部撤销 |
> | **结案状态** | **第一阶段已完成**（2026-09-11，`disaster2` `31230e9`） |
> | **原始分支** | `disaster_test`（`306b4b1`），基于 `master` `830c28f` |
> | **实现分支** | `disaster2` |
> | **本文结论的有效性** | 「Mixin 注入 preset 构建路径已连续被否决 7 次」**仍然有效**——该路线确实走不通，不应再试 |
>
> ### 结案说明
>
> 本文原本建议「换方向，不要在此继续试错」。后续会话换了方向后成功：
>
> - **不再从源头重建对照表**，改为在 TerraBlender 为维度**复印参数列表的瞬间**改写副本
> - 生成层与查询层**分别**处理（本文只发现了前者，后者是后续排查才发现的）
> - 保留了原版群系（最初 14 个 = 9 海洋 + 2 河流 + 3 洞穴，验收前扩为 **21 个**），
>   而非本文目标中的「一个不留」；剩余缺口由第二阶段的 `beloong:` 自制群系接管
>
> **本文的价值在于「教训」而非「方案」**：第五、六节的失败记录与教训已被
> `docs/天灾维度群系剔除-两次尝试复盘.md` 吸收整理；第七、八节的建议方向已在
> `docs/plans/2026-09-11-disaster-vanilla-biome-removal-design.md` 中落地，
> 并最终固化为 `docs/天灾维度总设计.md` 第四节。
> 第四节列出的代码资产中，`DisasterBiomeMapping` 与 `CloneParameterListMixin` 已被沿用。
>
> **保留本文的原因**：它的失败记录是「哪些注入点根本不可用」的权威清单（`PUTFIELD` /
> `INVOKEDYNAMIC` 不是合法注入点说明符、`@Invoker` 必然递归、preset 阶段读配置会 NPE 等），
> 这些结论不会过时。
>
> ### 仍未完成的部分
>
> 本文追求的「天灾维度**一个原版群系都不留**」尚未达成——当前保留 21 个白名单原版群系。
> 后续计划用 `beloong:` 命名空间的自制群系接管，见
> `docs/天灾维度总设计.md` 第十节与 `docs/plans/2026-09-11-disaster-phase2-handover.md`。

---

## 一、目标（用户原话已澄清）

### 最终验收标准

> **结案时的达成情况**：标准 1、2、3、4 均已达成。
> 完整的七项判定标准与逐项状态见第八节「判定标准」表。

| # | 标准 | 说明 |
|---|---|---|
| 1 | `/locate biome minecraft:river` 在**天灾维度**应返回「无法搜到」 | 「无法搜到」= **立即返回**，而不是搜索很久后才返回 |
| 2 | 自然罗盘（Nature's Compass）应显示原版群系**只存在于主世界** | 该模组的「群系属于哪个维度」依赖其内部搜索设置，而非维度注册表 |
| 3 | 主世界**完全不含** BWG 群系 | 此条**当时已达成** |
| 4 | 天灾维度仍正常生成；海洋不结冰；洞穴正常 | 当时已达成 |

### 明确的概念澄清（用户已确认）

- **不可能**做「注册层面的维度隔离」：Minecraft 的生物群系注册表全局唯一、所有维度共享，删了主世界也会崩。
- **正确目标**是「天灾维度的地形生成不产出原版群系」。`/locate` 与自然罗盘都靠搜索**世界数据**，只要生成层干净，这两个工具会自动得出正确结论。

### 已确认的设计决策

| 决策 | 内容 |
|---|---|
| 洞穴群系 | `deep_dark` / `lush_caves` / `dripstone_caves` **刻意保留原版**，与海洋一样后续用**自制群系**接管 |
| 海洋 | 最终要**自制 3 个海洋群系**接管 icy/cold/neutral 三列；当前验证批是**复用** `dead_sea`（冰海）/ `lush_stacks`（其余） |
| BWG 依赖 | 已更新至 **2.6.0**（`curse.maven:oh-the-biomes-weve-gone-1070751:8245253`），无异常 |

---

## 二、改造前基线（实测数据，可复现）

用 `tools/scan-biomes.js` 扫描 `run/saves/mowzie生物`：

| 维度 | minecraft 群系 | biomeswevegone 群系 |
|---|---|---|
| 主世界 | 10 种 / 12808 次 | **0 种 / 0 次** |
| 天灾 | **8 种 / 27803 次（77%）** | 4 种 / 8259 次（23%） |

天灾维度明细：

```
minecraft:plains                19840      biomeswevegone:maple_taiga        5216
minecraft:river                  3581      biomeswevegone:dacite_ridges      1490
minecraft:forest                 3066      biomeswevegone:coconino_meadow    1440
minecraft:old_growth_pine_taiga   841      biomeswevegone:zelkova_forest      113
minecraft:dripstone_caves         273
minecraft:lush_caves              168
minecraft:cold_ocean               32   ← BWG 的 ocean 缺口
minecraft:deep_dark                 2
```

**天灾维度 77% 的地表由原版群系占据。**

---

## 三、技术背景（已逐行核对源码与字节码）

### 3.1 BWG 的接入机制：本身就是「原版占位」

BWG 的 `BWGTerraBlenderRegion` 通过 TerraBlender 接入，其 11 个选择器数组**对齐原版气候网格**
（`OverworldBiomeBuilder` 的 `temperatures` 5 段 / `humidities` 5 段 / `erosions` 7 段）。
数组中凡未填 BWG 群系的格子使用 `Region.DEFERRED_PLACEHOLDER`。

**逐数组盘点缺口**：

| 数组 | BWG 实填 | `DEFERRED_PLACEHOLDER` | 覆盖 |
|---|---|---|---|
| `MIDDLE_BIOMES` ×3 / `PLATEAU` ×3 / `SLOPE` | 25/25 | 0 | ✅ |
| `PEAK` / `PEAK_VARIANT` | 21/25 | 4（冰带） | ⚠️ |
| `BEACH` | 13/25 | **10**（冰带+热带） | ❌ |
| `PLATEAU_VARIANT` | 8/25 | 8 + 9 个 void | ❌ |
| `SHATTERED` | **0/25** | 15 + 10 个 void | ❌ |
| **`OCEANS` / `OCEANS_2`** | **2/10** | **6** | ❌❌ |

**关键结论：BWG 只填了 `OCEANS` 的 warm/hot 两列（`LUSH_STACKS`、`DEAD_SEA`），
icy/cold/neutral 三列（占海洋 60%）全部交还原版。河流更是完全没有 BWG 数组**——
原版在 `EROSION[6] + coastContinentalness` 上直接注册 `RIVER` / `FROZEN_RIVER`。

**所以「原版 + BWG 混合」是 BWG 的设计本身，不是配置失误。**
「海洋结冰」的历史问题正是「回退到原版海洋群系 + BWG 表面规则」错配的结果。

### 3.2 维度判定：不能用 RegionType

`terrablender:overworld_regions` 标签用 `"replace": true` 排除了主世界（这是刻意设计，
让 BWG 群系整体迁移到天灾）。但**天灾与主世界共用同一个 `minecraft:overworld` 预设**
（`dimension.json` 里 `"preset": "minecraft:overworld"`），因此：

- 主世界与天灾的 TerraBlender `RegionType` **同为 `OVERWORLD`**
- 只能靠 `ResourceKey<LevelStem>` 是否等于 `beloong:disaster` 区分维度

运行日志佐证：`Initialized TerraBlender biomes` 只会为 `minecraft:the_nether` 与
`beloong:disaster` 打印，**主世界从不出现**。

### 3.3 参数列表的两条读取路径（关键）

`Climate$ParameterList` 有两个字段：

```java
private final List<Pair<ParameterPoint, T>> values;   // 我替换的目标
private final Climate$RTree<T> index;                 // 构造时固化的索引
```

原生路径与 TerraBlender 路径**读的不是同一个**：

```java
// 原生（Climate$ParameterList 自身）
findValue(t) → findValueIndex(t) → getfield index → index.search(t)

// TerraBlender（MixinMultiNoiseBiomeSource.getNoiseBiome 注入后）
findValuePositional(t, x, y, z):
    if (this.initialized)
        return uniqueTrees[uniqueness].tree().search(t, ...);   // 用按 region 构建的树
    else
        return this.findValue(t);                                // 回退到固化索引
```

且 `MixinParameterList.initializeForTerraBlender` 的 index 0 分支：

```java
if (regionIndex == 0)
    this.uniqueTrees[0] = new SearchTreeEntry(region, Climate.RTree.create(this.values));
```

**index 0 的树由 `this.values` 构建** —— 这是「改 `values` 就能生效」的**唯一**理由。

### 3.4 决定性发现：实例不一致

诊断日志实测：

```
诊断②（初始化）：biomeSource identity = 1225073804，写回生效=true，initialized=true
诊断④（生成期）：biomeSource identity = 1229537921，参数列表=null（用 preset！）
```

**初始化时改的是实例 A，区块生成时实际用的是实例 B。**
B 的 `parameters()` 返回 preset（`Either.right`），与 A 毫无关系。

**这解释了为什么初始化期诊断全绿（`values` 已替换为纯 BWG、`initialized=true`）而世界照旧生成原版群系。**

### 3.5 唯一共同的源头：preset 构造

`MultiNoiseBiomeSourceParameterList` 构造器字节码：

```
 9: aload_0
10: aload_1                              // Preset
11: getfield      Preset.provider
14: aload_2                              // HolderGetter
20: invokedynamic HolderGetter::getOrThrow -> Function
25: invokeinterface SourceProvider.apply(Function) -> Climate$ParameterList
30: putfield      parameters             // 构造时展开并固化
```

`parameters()` 只是 `getfield parameters; areturn`。
**所有 `MultiNoiseBiomeSource` 实例都从这里取参数列表。**

而 `Preset.generateOverworldBiomes` 的字节码：

```
 0: invokestatic  ImmutableList.builder
 4: new           OverworldBiomeBuilder
11: aload_1                              // ImmutableList$Builder
12: aload_0                              // 传入的 resolver Function
13: invokedynamic accept:(Builder; Function) -> Consumer
18: invokevirtual OverworldBiomeBuilder.addBiomes(Consumer)
21: new           Climate$ParameterList
25: aload_1
26: invokevirtual ImmutableList$Builder.build
29: invokespecial Climate$ParameterList.<init>(List)
32: areturn
```

`OverworldBiomeBuilder.addBiomes` 的**唯一调用方**就是这里（已全源码检索确认）。

### 3.6 两个致命的时序约束

| 约束 | 后果 |
|---|---|
| preset 的展开发生在 `Bootstrap.validate → VanillaRegistries.createLookup → RegistrySetBuilder.build`，**早于 Forge 配置加载** | 在此处读 `Config.DisasterBiomes.enabled.get()` 会抛 **NPE 并崩在启动阶段**。此处的接管只能无条件执行 |
| 该时机也早于任何世界/维度上下文 | 无法用「当前维度」做条件判断 |

---

## 四、代码资产（当前分支已有，可复用）

| 文件 | 状态 | 说明 |
|---|---|---|
| `worldgen/DisasterBiomeMapping.java` | ✅ 沿用 | **原版群系 → BWG 群系**的气候对照表（当时 53 项，现为 32 项 + 21 项白名单）。映射依据是 `run/exported_vanilla_biomes.json`（7593 个参数点的权威导出，已确认是 BWG 接管**前**的状态）算出的气候中心 |
| `mixin/CloneParameterListMixin.java` | ✅ 沿用 | 当时只负责 TerraBlender 参数列表**隔离**；现已扩展为同时承担**生成层群系剔除**（见总设计文档 4.4） |
| `mixin/PresetBiomeTakeoverMixin.java` | ❌ 已停用 | 已从 `beloong.mixins.json` 移除注册。文件保留，内部注释记录了 7 个已证伪的注入点 |
| `run/exported_vanilla_biomes.json` | ✅ 有用 | 7593 个参数点的原版基线导出（在 `.gitignore` 的 `run/` 内，未版本控制） |
| ~~`tools/scan-biomes.js`~~ | ❌ **不要用它验证** | 它会把尚未生成群系的区块（`Status=minecraft:structure_starts`）计入统计，且量纲为 palette 出现次数而非群系体积，读数显著失真——**曾因此一度误判实现失败**。验证一律读游戏内存（`ServerLevel.getBiome()` / `chunk.getSection(i).getBiomes()`），详见总设计文档决策 17 |
| ~~`Config.DisasterBiomes`~~ | ❌ **已删除** | `enabled` / `fallbackBiome` 两项配置连同整个 `[disaster_biomes]` 配置节已移除（无配置项，全硬编码），见总设计文档决策 19 |

### 映射表要点（`DisasterBiomeMapping`）

| 原版 | BWG | 说明 |
|---|---|---|
| `frozen_ocean` / `deep_frozen_ocean` | `dead_sea` | BWG 唯一寒水系群系 |
| `cold_ocean` / `ocean` / `lukewarm_ocean` 及 deep 变体 | `lush_stacks` | BWG 温水系群系 |
| `river` | `prairie` | BWG 无河流群系 |
| `frozen_river` | `crimson_tundra` | |
| `plains` | `prairie` | |
| `forest` | `temperate_grove` | |
| `deep_dark` / `lush_caves` / `dripstone_caves` | **不替换** | 刻意保留 |

---

## 五、全部尝试记录（12 次提交，逐条）

> 所有条目均为**实测结果**，非推断。`git log 830c28f..306b4b1` 可查看完整历史。

### 阶段一：改副本（从原理上就错，但当时不知）

| # | 提交 | 做法 | 结果 |
|---|---|---|---|
| 1 | `e85d801` | 在 `LevelUtils.initializeBiomes` 里 clone 参数列表、替换 `values`、写回 biomeSource | 日志显示"替换 7590/7593"，但世界照旧 |
| 2 | `480b0bf` | 修注入点：改到 `MixinParameterList.initializeForTerraBlender` | **建存档崩溃**：`RTree.create` 在 `LevelUtils` 里根本不存在（`Scanned 0 target(s)`），且 `require` 默认 1 |
| 3 | `5bf48b8` | 改回 `LevelUtils` 当"enclosing class" | 推理错误（见第六节教训 3） |
| 4 | `50d77b8` | 改注入目标为 `terrablender.mixin.MixinParameterList` | **启动闪退**：`Cannot add target ... because the target is a mixin` |
| 5 | `545a8d3` | 加诊断日志①（替换前后残留群系）+ BWG 升 2.6.0 | 诊断①显示替换后仅剩 3 个洞穴群系，**但世界照旧** |
| 6 | `21fc73b` | 加 `markInitialized`（强制置位 TerraBlender 的 `initialized` 标志） | 诊断③显示已置位，**世界照旧** |
| 7 | `ad2fbf3` | 加诊断④（生成期读 biomeSource 的参数列表） | **突破点**：发现实例 identity 不一致、生成时用的是 preset |

### 阶段二：改 preset（方向对，但注入点全被否决）

| # | 提交/尝试 | 注入点 | 失败原因（原文） |
|---|---|---|---|
| 8 | `5156ff9` | `Preset$SourceProvider.apply`，接收者写 `Object` | `Found unexpected argument type java.lang.Object at index 0, expected ...Preset$SourceProvider` |
| 9 | `30a7a7f` | `PUTFIELD parameters` | `PUTFIELD is not a valid injection point specifier` |
| 10 | `41004b1` | `INVOKEDYNAMIC`（合成 Consumer） | `INVOKEDYNAMIC is not a valid injection point specifier` |
| 11 | 实测（未提交） | 改写 `OverworldBiomeBuilder.addBiomes` + `@Invoker` 回调原生实现 | **`StackOverflowError`** —— `@Invoker` 仍会调回被改写的方法 |
| 12 | 实测（未提交） | 同上，但移除配置读取（避开 NPE） | 成功进入 `addBiomes`，但递归问题依旧 |
| 13 | 实测（未提交） | `@Redirect ImmutableList$Builder.build()`，`method = "generateOverworldBiomes"` | `could not find any targets matching 'generateOverworldBiomes'` |
| 14 | 实测（未提交） | 同上，改用完整描述符 `(Ljava/util/function/Function;)Lnet/minecraft/world/level/biome/Climate$ParameterList;` | **仍未命中** |

### 顺带证伪的一条

| 尝试 | 结论 |
|---|---|
| 反射替换 `values` 字段（`private final`） | **可行**（独立 Java 探针实测：Java 21 下对 unnamed module 的 private final 字段 `setAccessible` + `Field.set` 成功）。**但无意义** —— 因为改的是没人读的副本 |

---

## 六、必须继承的教训（比代码更重要）

### 教训 1：Mixin 的注入点在**应用期**才校验，构建期不校验

`require = 1` **能通过构建**（注解处理器只解析注入点语法），但运行期会抛
`InvalidInjectionException` 并崩溃。**构建成功、`require = 1` 通过，都不能作为注入可用的证据。**

**正确做法**：每改一个注入点，**必须实际启动游戏**验证。本项目 `gradlew runClient` 可后台启动，
约 90~110 秒到达主菜单；注入失败会在 `Bootstrap` 阶段崩溃，看日志即可判定。

### 教训 2：不要用"代码执行了"当"结果被使用了"的证据

前 6 次尝试的日志都显示"替换成功"，但世界照旧。因为诊断跑在**初始化期**，
而被观察的对象是个**没人读的副本**。

**正确做法**：在**消费端**取证。本轮唯一有效的证据是诊断④ ——
它在**区块生成那一刻**读取 biomeSource 实际持有的对象，并打印 identity。

### 教训 3：不得用常量池字符串搜索推断注解里写了哪个类

`@Mixin` 的 value 是 `Class` 字面量，编译后只留字段描述符，**类名字符串不会进常量池**。
我曾据此误判 `MixinParameterList` 是 `LevelUtils` 的嵌套类，导致一轮失败。

### 教训 4：参考源码必须与运行时工件版本一致

排查初期 `开源模组参考文件\TerraBlender` 检出在 **26.1.2** 分支（MC 26.1.2），
而非项目实际使用的 1.21.1。**已修正为 `1.21.1` 分支（HEAD `c903443`）**——
该分支与 maven 工件 `4.1.0.8` 同日构建。同时已添加 `upstream` 远程指向 `Glitchfiend/TerraBlender`。

> 即便如此，**仍应以 `javap -p -c` 核对运行时 jar 为准**，
> 因为参考源码与 maven 工件仍可能不是同一提交。

### 教训 5：`@Invoker` 不能用于「改写方法 + 回调原生实现」

`@Invoker` 生成的调用**仍会落到被改写后的方法上**，立即形成无限递归
（`StackOverflowError`）。若确需在方法体内改写，应：
- 用 `@Redirect` / `@ModifyArg` 处理**内部调用**，或
- 在 `@Inject` 中**直接内联原生逻辑**，切勿回调自身

### 教训 6：不要依据参考源码推断注入点

必须对**运行时实际加载的 jar** 执行 `javap -p -c`，确认目标方法里
**确实存在**要注入的指令与描述符。

---

## 七、给下一个会话的建议方向

### 已被穷尽的路线

**Mixin 注入 `MultiNoiseBiomeSourceParameterList` 的 preset 构建路径** —— 7 个注入点全部被否决。
若仍要走这条，**必须先解决「注入点合法性」这个前置问题**，而不是先写业务逻辑。

**建议的前置验证方法**（本轮缺失的关键步骤）：

> 先只加**一处注入 + 一行日志**，不改任何业务逻辑，
> 用 `gradlew runClient` 验证注入确实命中（日志出现），再写替换逻辑。
> 一次只验证一个注入点。

### 尚未探索的方向

| 方向 | 思路 | 需要先验证 |
|---|---|---|
| **A. `@ModifyArg` 改写 `addBiomes` 的 Consumer 参数** | `Consumer` 是公开类型，`@ModifyArg` 只改参数、不回调目标方法，**无递归可能**。这是唯一还没试过且签名合法的 Mixin 位置 | 方法描述符与 `index` 是否正确 |
| **B. 数据包覆盖 preset** | `MultiNoiseBiomeSourceParameterList` 若能通过数据包注册表覆盖，则完全绕开 Mixin。需先确认该注册表项是否可被数据包替换 | 注册表项的可覆盖性 |
| **C. 自定义 `Region` 接管 index 0** | `Regions.register(name, index, region)` 可在指定索引插入。若能让自定义 region 成为 index 0，则其 `addBiomes` 决定兜底树内容。需绕过 `DefaultOverworldRegion` 已占 index 0 的问题 | 是否可在不破坏主世界的前提下替换 index 0 |
| **D. 换目标：不做「纯 BWG」** | 承认 BWG 的空缺是设计的一部分，改为**用自制群系填 BWG 的缺口**（海洋 3 列、河流），保留原版群系作为其他维度的兜底。这更贴近 BWG 的实际设计 | 是否可接受 |

### 建议的下一步优先级

1. **先做方向 A 的最小验证**（一处注入 + 一行日志），成本最低且签名合法
2. 若 A 失败，评估方向 B 与 C
3. 若都不行，与用户确认是否接受方向 D

---

## 八、验证方法论（务必沿用）

### 判定标准（用户已明确）

> **结案时的逐项达成情况**（2026-09-11 追加，✅ / ⏳ 为结案后标注）：

| 检查 | 通过标准 | 结案状态 |
|---|---|---|
| `/locate biome minecraft:river`（天灾维度） | **立即**返回"无法搜到"（不是搜索很久后返回） | ✅ 已达成 |
| 自然罗盘 | 原版群系**只显示主世界** | ✅ 已达成 |
| 主世界 | `minecraft:` 10 种左右、`biomeswevegone:` **0 种** | ✅ 已达成（实测 53 种原版 / BWG 0 种） |
| 天灾 | `minecraft:` **只剩 3 种洞穴群系**，`river`/`ocean`/`plains`/`forest`/`stony_shore` **必须为 0** | ⏳ **未达成**——保留 21 个（9 海洋 + 2 河流 + 3 洞穴 + 3 碎裂地形 + 4 保守项）。`plains`/`forest` 已为 0；`stony_shore`/`windswept_savanna` 在验收时被**主动**收进白名单（语义不搭的替身，刻意保守） |
| 天灾 | `biomeswevegone:` 种类应从 4 种涨到 **20~33 种** | ✅ 已达成（`possibleBiomes` 中 BWG 有 55 种可选） |
| 海洋 | 不结冰 | ✅ 已达成（海洋群系保留原版，结冰语义不变） |
| 洞穴 | `dripstone_caves` / `lush_caves` / `deep_dark` 可 `/locate` 到且生成正常 | ✅ 已达成（三者均在白名单内，且**第二阶段接管前必须一直保持可 locate**） |

> **未达成项的原因**：BWG **完全没有河流群系**（源码内 `river` 零命中，也不声明
> `minecraft:is_river` 标签），海洋的寒/冷/中性三列它也主动留空（占海洋 60%），
> 洞穴所需的 `depth > 0` 区域 BWG 一格未占，`SHATTERED_BIOMES_*` 两个数组 25 格全空。
> 强行用 BWG 地表群系填这些位置，会让水面/河道位置出现陆地群系、地下出现地表群系。
> 这 21 个需由 `beloong:` 命名空间的自制群系接管，属**第二阶段**——
> 见 `docs/天灾维度总设计.md` 第十节。

### 工具

```powershell
cd D:\Minecraft\BeLoong-Core
node tools\scan-biomes.js run\saves\<存档名>          # 扫描实际生成结果
node tools\scan-biomes.js run\saves\<存档名> --files 12 --chunks 200
```

**⚠️ 必须新建世界验证** —— 旧区块不会回填，用旧存档会得到假阳性/假阴性。
`run/saves/` 下已有 8 个历史测试存档（`disaster_test`、`天灾4~6`、`天灾测试1~3`、`新的世界disaster`），
建议清理后再测。

### 不要在日志里搜中文

`run/logs/latest.log` 的中文在本机 PowerShell 下会乱码。改用：
- `Select-String -Pattern "BeLoong"` 搜 ASCII 关键词
- ANSI：`chcp 65001`
- 或直接读 `run/crash-reports/*.txt`（UTF-8）

---

## 九、当时的分支状态（**历史记录，已过时**）

> ⚠️ 本节记录的是 **2026-09-10 结案前** `disaster_test` 分支的状态，仅作历史留存。
> 当前实际状态见文末「结案状态」。

```
分支: disaster_test   HEAD: 306b4b1   工作区干净   master 未动 (830c28f)
构建: BUILD SUCCESSFUL
游戏: 正常启动（BeLoong Launch! 已加载，无崩溃）
天灾维度: 仍是「原版 + BWG 混合」的原始状态 —— 纯 BWG 接管【未生效】
```

`disaster_test` 相对 `master` 的改动（8 文件 / +1049 行）：

```
build.gradle                                     |   2 +-    BWG 升 2.6.0
docs/plans/2026-09-10-disaster-pure-bwg-biomes-design.md | 461 +++++  设计文档
src/.../Config.java                              |  33 ++   disaster_biomes 配置节
src/.../mixin/CloneParameterListMixin.java       |  35 +-   TerraBlender 隔离（保留）
src/.../mixin/PresetBiomeTakeoverMixin.java      | 180 +++  已停用，记录 7 个失败注入点
src/.../worldgen/DisasterBiomeMapping.java       | 172 +++  映射表（可复用）
src/main/resources/beloong.mixins.json           |   2 +-
tools/scan-biomes.js                             | 171 +++  验证工具
```

> 上表是 `disaster_test` 的**历史** diff，仅供追溯。其中 `Config.java` 的
> `disaster_biomes` 配置节在 `disaster2` 上已被**整体删除**（决策 19），
> `tools/scan-biomes.js` 也已被判定为不可用于验收（见第四节文件表）。

### 关于清理（原始建议，已被结案取代）

原文建议：`PresetBiomeTakeoverMixin.java` 已停用但保留（注释是有价值的失败记录）；
`CloneParameterListMixin.java` 不要删——它是独立且必需的隔离逻辑。

**结案后的处理**：该建议已按 `disaster2` 的实际情况执行——
`CloneParameterListMixin` 被沿用并扩展（增加生成层群系剔除），
`PresetBiomeTakeoverMixin` **未合入 `disaster2`**（其失败记录已整理进复盘文档）。
`disaster_test` 分支整体保留为历史分支，不再继续开发。

---

## 结案状态（2026-09-11 追加）

| 项 | 结果 |
|---|---|
| **目标** | 由 `disaster2` 分支实现，**第一阶段完成** |
| **实现提交** | `6a77c0e`（生成层）→ `31230e9`（查询层 + 文档） |
| **实际做法** | 在 TerraBlender 为维度复印参数列表的瞬间改写副本；生成层与查询层分别注入 |
| **与原目标的差异** | 保留 21 个白名单原版群系（9 海洋 + 2 河流 + 3 洞穴 + 3 碎裂地形 + 4 保守项），非「一个不留」 |
| **本文第五节（12 次尝试）** | 全部结论有效，该路线确实走不通 |
| **本文第七节（建议方向）** | 方向 A/B/C 未采用；实际走的是「换时机」而非「换注入点」 |
| **本文第八节（验证方法论）** | 部分被推翻——`tools/scan-biomes.js` 读数失真，**不得再用于生成判定**，详见复盘文档 3.4 节 |
| **后续** | 用 `beloong:` 自制群系接管那 21 个，见 `docs/天灾维度总设计.md` 第十节与 `docs/plans/2026-09-11-disaster-phase2-handover.md` |

**文档去向**：

| 内容 | 现在何处 |
|---|---|
| 失败的注入点清单（第五节） | 本文（权威清单，保留） |
| 必须继承的教训（第六节） | `docs/天灾维度群系剔除-两次尝试复盘.md` 第二节 |
| 代码资产（第四节） | `DisasterBiomeMapping` 与 `CloneParameterListMixin` 已被 `disaster2` 沿用 |
| 成功方案（施工依据） | `docs/plans/2026-09-11-disaster-vanilla-biome-removal-design.md` |
| **现行群系设计（权威）** | `docs/天灾维度总设计.md` 第四节、第九节、第十节 |
| 实现后的审查记录 | `docs/reviews/2026-09-11-disaster-biome-removal-review.md` |
| 第二阶段交接 | `docs/plans/2026-09-11-disaster-phase2-handover.md` |
