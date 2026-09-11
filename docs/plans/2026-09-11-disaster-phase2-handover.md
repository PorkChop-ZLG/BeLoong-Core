# 天灾维度 第二阶段交接

**Date:** 2026-09-11
**Status:** 交接（设计未定稿）
**适用分支:** `disaster2`

> 本文**不是设计文档**。天灾维度的唯一权威文档是
> [`docs/天灾维度总设计.md`](../天灾维度总设计.md)——本文件只做三件事：
> 记录**已核实的机制事实**、标明**当前有几条注入路径**、列出**待决策项**。
> 凡与总设计文档冲突之处，以总设计文档为准。

---

## 一、第二阶段要做什么

把天灾维度仍然保留的 **21 个白名单原版群系**换成 `beloong:` 命名空间的群系，
使天灾维度不含任何 `minecraft:` 群系。

两条总目的与压缩后的目标数量见总设计文档 **§1.1** 与 **§10.0**。

---

## 二、已核实的机制事实（不必重新调研）

以下全部用**运行时工件**核实过（MC/NeoForge 21.1.236 反编译源码、
TerraBlender 4.1.0.8 与 BWG 2.6.0 的运行时 jar），不是参考仓库的 HEAD。

| # | 事实 | 依据 |
|---|---|---|
| 1 | **水与群系无关**。海洋/河流的水由 `NoiseGeneratorSettings.default_fluid` + `sea_level` 决定 | `NoiseBasedChunkGenerator.java:71` → `Aquifer` |
| 2 | **结冰只看群系温度**：`getTemperature(pos) >= 0.15F` 就不结冰。不涉及标签或群系 ID | `Biome.java:139-173` |
| 3 | **自制群系的注册时机无问题**。`filter()` 跑在 `ServerAboutToStartEvent`，数据包注册表已就绪 | `IntegratedServer.java:76`；现有代码已对 `biomeswevegone:` 成功 `getHolder` |
| 4 | **`beloong:` 群系不会被现有判据拦截**：非 BWG 命名空间在 `isBwgEnabled` 直接放行；`isBlocklisted` 只匹配 `minecraft:` | `DisasterBiomeSubstitution.java:353-355`、`:197-199` |
| 5 | **不需要注册新的 TerraBlender Region**。白名单参数点由 index 0 兜底树供给 | `Regions.java:142` 先注册 `DefaultOverworldRegion` = index 0；`MixinParameterList.java:79-82` |
| 6 | **`possibleBiomes()` 在构造期就被 `Suppliers.memoize` 包死**，任何早于写回的读取都会永久固化旧集合 | `BiomeSource.java:29-31` |
| 7 | **原版 `overworld` 的 `surface_rule` 按具体群系 ID 分支**（30 余处 `isBiome(...)`）。换 ID 后一条都不命中，全部掉到默认草/土/石 | `SurfaceRuleData.java:69,74,82,85,88,117,119,127,178,221,289` 等 |
| 8 | **原版冰海的大冰架无法复刻**——按 `Biomes.FROZEN_OCEAN \|\| DEEP_FROZEN_OCEAN` **群系身份**硬编码 | `SurfaceSystem.java:158-160`、`:232-275` |
| 9 | **1.21.1 没有"按维度选结构集"的机制**。`createState` 把**全部** structure set 交给每个维度，只按 `possibleBiomes()` 筛一道 | `ChunkGenerator.java:111-113`、`ChunkGeneratorStructureState.java:52-67` |
| 10 | **BWG 给自己的群系加了原版结构标签**，其中 `HAS_TRIAL_CHAMBERS` 与 `IS_OVERWORLD` 覆盖全部 BWG 主世界群系 | BWG 2.6.0 jar 内 `data/minecraft/tags/worldgen/biome/{has_structure/trial_chambers,is_overworld}.json` |
| 11 | **命名空间地表规则是两段式**（我们的规则返回 `null` 才落回原版），且 `SurfaceRuleManager.addSurfaceRules` 接受任意命名空间 | `NamespacedSurfaceRuleSource.java:56-68`、`SurfaceRuleManager.java:88-94` |
| 12 | **该地表规则在首次 `surfaceRule()` 调用时一次性构建并缓存**，故 `addSurfaceRules` 必须在 mod 构造期完成 | `MixinNoiseGeneratorSettings.java:43-53` |

---

## 三、当前有几条注入路径

**两条，都只覆盖 index 0 兜底树：**

| 路径 | 类 | 覆盖范围 |
|---|---|---|
| 生成层 | `mixin/CloneParameterListMixin` | 改写 `Climate.ParameterList.values`（index 0 树） |
| 查询层 | `mixin/PossibleBiomesFilterMixin` | 过滤 `possibleBiomes()` 缓存 |

**但 21 项在机制上分两桶**（详见总设计 **§10.6** 与
[`docs/reviews/2026-09-11-disaster-region-tree-probe.md`](../reviews/2026-09-11-disaster-region-tree-probe.md)）：

- **DEFERRED 桶 14 项**（海洋 9 + 碎裂 3 + `stony_peaks` + `snowy_beach`）→ 走 index 0 → **现有两条路径够用**
- **region 树桶 7 项**（`river` `frozen_river` `lush_caves` `dripstone_caves` `deep_dark`
  `stony_shore` `windswept_savanna`）→ 由 BWG 的 region 树**原样放行原版群系** → **现有路径无效**

⇒ **第二阶段需要新增第三条注入路径。**
建议注入点：`BWGTerraBlenderRegion.addBiomes`（公开方法、签名稳定），
`@ModifyVariable(argsOnly = true)` 包裹传入的 `Consumer` —— 一处注入同时覆盖 RTree 构建
与 `appendDeferredBiomesList` 两条子路径。

**同一处注入顺带修掉第一阶段的 5 项泄漏**（`badlands` / `eroded_badlands` / `wooded_badlands` /
`mushroom_fields` / `beach`）。

---

## 四、职责边界：结构隔离不归本项目

**「天灾维度不生成原版结构」是整合包的目标，由整合包层面达成。BeLoong-Core 不做这件事。**

本模组的全部剩余工作只有一件：**让天灾维度的群系里不残留 `minecraft:` 群系。**

之所以仍然要了解结构机制，是因为它给第二阶段加了一条**约束**（而不是一项任务）：

- 1.21.1 没有"按维度选结构集"的机制（`ChunkGenerator.java:111-113`、
  `ChunkGeneratorStructureState.java:52-67`），原版结构能否生成完全取决于群系标签。
- 把原版群系复制成 `beloong:` 后，**不继承任何原版标签**——原版结构在这些参数区
  自然失去生成条件。这是"复制 + 改名"的**免费副产品**，服务于整合包的目的。
- ⚠️ **因此：接管白名单项时，绝对不要给 `beloong:` 群系挂任何原版 `has_structure/*` 标签。**

详细说明见总设计 **§1.3**。

---

## 五、待决策项

1. **~~自制群系数量与合并粒度~~** —— **已定案**（方案 B，自制 5 个）。
   **~~3 个交给 BWG 的目标群系~~** —— **已定案**
   （`windswept_savanna → araucaria_savanna`、`stony_peaks → red_rock_peaks`、
   `snowy_beach → dacite_shore`）。逐项方案见
   [`docs/plans/2026-09-11-disaster-phase2-biome-table.md`](2026-09-11-disaster-phase2-biome-table.md)。
2. **~~双用途群系标签~~** —— **已定案：一个都不挂**，优先保证结构隔离；
   代价清单（哪些可用群系数据补偿、哪些会真实丢失）见逐项表 §6.1。
3. **第三条注入路径的注入点**——建议 `BWGTerraBlenderRegion.addBiomes`；
   备选是 `Climate.ParameterList.initializeForTerraBlender` 内 `@Redirect Region.addBiomes`
   （需 MixinExtras `@Local` 捕获局部 `pairs`）。
4. **地表规则的具体内容**——自制的 5 个群系至少要覆盖 `windswept_*`（石头/砂砾）与
   海洋底（沙 vs 砂砾）；`dripstone_caves`（石头）按洞穴合并粒度决定。
   交给 BWG 的项**由 BWG 的地表规则负责，但必须先核对它有没有**——
   BWG 只给 55 个群系中的 42 个写了规则，没有规则的会掉到默认草/土。
   已核对过的目标见逐项表 §6.2。**这也是今后新增映射条目时的必查项。**
5. **~~是否修目的 2 的缺口~~** —— **不修。结构隔离是整合包的目标，不在本项目范围内**（见第四节）。
6. **第一阶段的 5 项泄漏是否单独先修**——修法与第三条注入路径是同一处。

---

## 六、验证方法（含已知盲区）

**必须读游戏内存，不得依赖 NBT 扫描工具**（见总设计 §4.8 的两条警告）。

> ⚠️ **总设计 §4.8 的生成层验证有盲区**：它读的
> `biomeSource.parameters().values` 是 **index 0 兜底树**，**完全不覆盖 region 树**。
> 这正是 5 项泄漏长期未被发现的原因。接管 region 树桶时必须补一条针对 region 树的验证。

可复用的做法（本次取证用过）：在目标维度分支里遍历
`Regions.get(RegionType.OVERWORLD)`，直接调用各 region 的公开方法
`addBiomes(Registry<Biome>, Consumer<...>)`，把输出按命名空间分类打印。
`addBiomes` 只把静态数组展开成参数对，无副作用。

**探针记录**：驱动专用服务器时，`run/mods` 里的 `sodium` / `iris` 是纯客户端模组，
会让服务器在 bootstrap 阶段 `NoClassDefFoundError: org/lwjgl/Version`，需临时移出。

---

## 七、阅读顺序

1. [`docs/天灾维度总设计.md`](../天灾维度总设计.md) —— **§1.1（两条总目的）→ §10.0（第二阶段原则）
   → §4（现行机制）→ §10.6（分桶）**
2. [`docs/reviews/2026-09-11-disaster-region-tree-probe.md`](../reviews/2026-09-11-disaster-region-tree-probe.md)
   —— region 树取证的原始输出
3. [`docs/天灾维度群系剔除-两次尝试复盘.md`](../天灾维度群系剔除-两次尝试复盘.md)
   —— 哪些注入点根本不可用、哪些验证方法会骗人
