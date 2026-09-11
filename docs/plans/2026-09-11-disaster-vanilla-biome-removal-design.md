# 天灾维度剔除原版群系 设计文档

> ## ⚠️ 状态：第一阶段已实现并验收 —— 本文是**施工依据**，不是现行说明
>
> **现行权威文档是 [`docs/天灾维度总设计.md`](../天灾维度总设计.md) 第四节。** 两者冲突时以总设计文档为准。
>
> 本文中以下内容**已作废**，仅保留作为"当时为什么这么选"的记录：
>
> | 本文中的说法 | 现状 |
> |---|---|
> | `[disaster_biomes]` 配置节（`enabled` 开关、`fallbackBiome`） | **整体移除**。本功能不提供任何配置项，白名单与映射表全部硬编码 |
> | 白名单 14 项（9 海洋 + 2 河流 + 3 洞穴） | **扩为 21 项**（+3 碎裂地形、`stony_peaks`、`snowy_beach`、`stony_shore`、`windswept_savanna`） |
> | `eroded_borealis` 需手工启用（D1） | **已废弃**。映射表刻意避开 BWG 默认禁用的群系，`grove → frosted_taiga` |
> | fallback = `prairie`（D2） | **已废弃**。无兜底群系：映射不可用时保留原版 + 记 ERROR |
> | 保留 `enabled` 开关（D4） | **已废弃**，见决策 19 |
>
> **仍然有效、值得阅读的部分**：注入点选择理由（为什么必须打在 `LevelUtils.initializeForTerraBlender`
> 与 `appendDeferredBiomesList` 上）、以及"哪些注入点根本不可用"的排除过程。

**Date:** 2026-09-11
**Status:** Implemented & Verified（生成层与查询层均已验证；用户实机确认通过）
**Branch:** `disaster2` · 基线提交 `6a77c0e`
**Scope:** 仅修改 BeLoong-Core。不改 BWG 源码、不改 TerraBlender、不改 `overworld_regions` tag 语义
**已决事项（当时）:** ~~D1 启用 `eroded_borealis`~~ · ~~D2 fallback=`prairie`~~ · D3 手写映射表 · ~~D4 保留 `enabled` 开关~~ · D5 总设计文档待实测通过后再改
（D1/D2/D4 均已在实现中推翻，理由见上方状态块）

---

## Problem Statement

天灾维度（`beloong:disaster`）当前是「**原版基线 + BWG 叠加**」。实测（`run/saves/新的世界disaster`，天灾 8 个 region / 1555 区块）：

| 维度 | 群系种类 | `minecraft:` | `biomeswevegone:` |
|---|---|---|---|
| 主世界 | 9 | 26457 次（100%） | **0** |
| 天灾 | 21 | 23529 次（38%） | 38821 次（62%） |

天灾维度里原版点名次最高的 5 个是 `plains 15392 / river 3678 / dripstone_caves 3086 / deep_dark 739 / lush_caves 634`。

**成因（已逐行核对源码，非推断）：**

1. `MixinParameterList.findValuePositional` 在命中 `Region.DEFERRED_PLACEHOLDER` 时回退到 `uniqueTrees[0]`；
   `uniqueTrees[0] = Climate.RTree.create(this.values)`（`MixinParameterList:81`），而 `values` 就是
   **原版 `OverworldBiomeBuilder` 的完整参数列表**。
2. BWG 的选择器数组本身大量使用 `DEFERRED_PLACEHOLDER`（`OCEANS_BWG` 60%、`SHATTERED_BIOMES_BWG` 100%、
   `TerraBlenderBiomeSelectors` 多数数组 100%）。
3. 被 BWG 配置禁用的群系也会被改写成占位符（`BWGTerraBlenderRegion:170`），进一步加厚原版回退。

### 需求

> 下面第 1、2 条的数字是**当时的中间状态**（14 / 39）。最终为 **21 / 32**，
> 21 项的完整清单与逐项理由见总设计文档 4.6 节。

1. **白名单**：以下 14 个原版群系**允许**与 BWG 群系一同出现在天灾维度——9 个海洋、2 个河流、3 个洞穴：
   `frozen_ocean` `deep_frozen_ocean` `cold_ocean` `deep_cold_ocean` `ocean` `deep_ocean`
   `lukewarm_ocean` `deep_lukewarm_ocean` `warm_ocean` `river` `frozen_river`
   `lush_caves` `dripstone_caves` `deep_dark`
2. **黑名单**：其余 **39 个**原版主世界群系彻底从天灾维度剔除——不仅是"不生成"，
   而是 **`BiomeSource.possibleBiomes()` 里不存在**，从而：
   - `/locate biome minecraft:plains` 在天灾维度**立即**返回"无法找到"
   - 自然罗盘把原版群系只显示在主世界，而非"主世界 + 天灾维度"
3. 主世界维持现状（纯净原版，`biomeswevegone` 命中 0）。

---

## 为什么修改 `values` 能一次性满足需求 2

我核对了字节码，`possibleBiomes()` 是**活引用**，不是快照：

```
dej.b()  (MultiNoiseBiomeSource.collectPossibleBiomes)
    d()                      → Either.map(...) 取 parameters()
    def$c.a()                → ParameterList.values()
    List.stream().map(...)   → Stream<Holder<Biome>>

dea.c()  (BiomeSource.possibleBiomes)
    getfield b:Supplier; invokeinterface Supplier.get()    ← 每次调用现算
dea.d()  私有构建体：b().distinct().collect(ImmutableSet.toImmutableSet())
```

TerraBlender 的 `MixinBiomeSource.appendDeferredBiomesList` 把这个 supplier 整体替换为
`() -> new ObjectLinkedOpenHashSet<>(possibleBiomes.stream().distinct()...)`，其中前半段同样来自
`this.possibleBiomes.get()` → `collectPossibleBiomes()` → `parameters().values()`。

**结论：`values` 干净 ⇒ `possibleBiomes` 干净 ⇒ 需求 2 自动达成。需求 2 不需要独立代码。**

---

## Design

### 落点：`CloneParameterListMixin` 的 `@Redirect` 回调体

`CloneParameterListMixin` 已经存在且工作正常，它 `@Redirect` 了
`LevelUtils.initializeBiomes` 中的 `IExtendedParameterList.initializeForTerraBlender(...)` 调用。这个位置**同时满足三个必要条件**：

| 条件 | 为什么满足 |
|---|---|
| 每维度独立 | 操作对象是 `receiver.clone()` 出来的副本，不碰共享的注册表单例 |
| 在兜底树构建**之前** | `uniqueTrees[0]` 是在 `initializeForTerraBlender` **内部**由 `this.values` 构建的，而 `@Redirect` 的回调体正好在这个调用点上 |
| 能换掉 `values` 字段 | 回调体持有 `receiver` 引用，可以整体替换其 `values` 字段 |

其它位置都不行：preset 展开在 `Bootstrap` 阶段（早于 Forge 配置加载，读配置会 NPE）；`initializeForTerraBlender` 之后改就晚了（树已建好）；改共享单例则会污染主世界。

### 数据流

```
LevelUtils.initializeBiomes(维度=beloong:disaster)
  └─ parametersEx.initializeForTerraBlender(...)          ← @Redirect 拦截
       ↓ 回调体 cloneBeforeInit(receiver, ...)
       ①  cloned = receiver.clone()                       ← 浅拷贝
       ②  cloned.values = new ParameterList(替换后的新列表)  ← 换字段，断开与主世界的共享
       ③  ((IExtendedParameterList) cloned).initializeForTerraBlender(...)
              └─ uniqueTrees[0] = RTree.create(cloned.values)   ← 只含白名单原版 + BWG
                 uniqueTrees[1..3] = BWG region 树
       ④  biomeSource.setParameters(Either.left(cloned))
       ⑤  LevelUtils 继续 appendDeferredBiomesList(...)  → possibleBiomes = values ∪ BWG
```

### 落地后的天灾维度群系构成

| 来源 | 内容 |
|---|---|
| `uniqueTrees[0]`（兜底树） | **白名单 14 个原版** + **31 个 BWG 替换目标** |
| `uniqueTrees[1..3]`（BWG region 树） | BWG 群系（`THE_VOID` 空格回落到该 region 的基础数组） |
| `possibleBiomes` | 上述并集，**不含 39 个黑名单群系** |

主世界侧：tag 不含 `minecraft:overworld` ⇒ `getRegionTypeForDimension` 返回 `null` ⇒
`initializeBiomes` 直接 return ⇒ `@Redirect` **根本不会触发** ⇒ 主世界依然是 `shared` 原列表。**零影响。**

---

## 组件清单

| 文件 | 操作 | 说明 |
|---|---|---|
| `mixin/CloneParameterListMixin.java` | 修改 | 在 `@Redirect` 回调体内插入群系替换调用 |
| `mixin/ParameterListAccessor.java` | **新建** | `@Mutable @Accessor` 暴露 `Climate.ParameterList` 的 `values` 字段（final） |
| `worldgen/DisasterBiomeMapping.java` | **新建**（移植） | 原版 → BWG 映射表。取自 `disaster_test` 分支，本方案已逐项核对 |
| `worldgen/DisasterBiomeSubstitution.java` | **新建** | 替换求解器：白名单判定 / 有效性校验 / 生效标志 |
| ~~`Config.java`~~ | ~~修改~~ | ~~新增 `[disaster_biomes]` 节：`enabled` + `fallbackBiome`~~ **已作废——不提供任何配置项** |
| `worldgen/DisasterBiomeMapping.java` | **新建**（移植） | 原版 → BWG 映射表。取自 `disaster_test` 分支，本方案已逐项核对 |
| `mixin/PossibleBiomesFilterMixin.java` | **新建**（本文遗漏） | 查询层剔除。本文写作时**尚未发现**查询层是独立的一条路径，见总设计文档 4.5 |
| `data/terrablender/tags/dimension_type/overworld_regions.json` | **新建**（本文遗漏） | `{"replace": true, "values": ["beloong:disaster"]}`——真正排除主世界的那一步 |
| `beloong.mixins.json` | 修改 | 注册 `ParameterListAccessor` |
| `docs/天灾维度总设计.md` | 修改 | 第四、八节更新为「原版基线已被替换」 |

> 上表的**文件名**以本文为准、**配置项与 fallback 相关行**已作废。
> 实际落地的文件清单以总设计文档第七节"资源清单"为准。

> `DisasterBiomeMapping.java` 在 `disaster_test` 分支已存在，但**当时的 javadoc 引用了一个从未创建的
> `DisasterBiomeSubstitutionMixin`，且其注释里"刻意保留三个洞穴群系"的说法现在升级为白名单机制。移植时必须重写文档注释。**

---

## 核心逻辑

### 1. `ParameterListAccessor`

```java
@Mixin(Climate.ParameterList.class)
public interface ParameterListAccessor<T> {
    @Mutable
    @Accessor("values")
    void beloong$setValues(List<Pair<Climate.ParameterPoint, T>> values);

    @Accessor("values")
    List<Pair<Climate.ParameterPoint, T>> beloong$getValues();
}
```

`values` 经 `javap` 确认为 `private final java.util.List<...>`；`@Mutable @Accessor` 是 Mixin 处理 final 字段的
标准手段（TerraBlender 自己的 `MultiNoiseBiomeSourceAccess` 就用同样手法改 `MultiNoiseBiomeSource.parameters` 这个 final 字段）。

**必须换列表实例，不能原地 `set()`**：`clone()` 是浅拷贝，两个维度共享同一个 `values` 对象，原地修改会污染主世界。

### 2. 替换求解（`DisasterBiomeFilter`）

对 `values` 中每个 `Pair<ParameterPoint, Holder<Biome>>`：

```
若 holder 不是 minecraft: 命名空间          → 原样保留
若 id 命中 WHITELIST                        → 原样保留
否则：
    target = DisasterBiomeMapping.substitute(id)
    若 target 为 null（映射表未覆盖）        → 记 WARN，用 fallbackBiome
    若 target 在 BWG 配置中被禁用            → 记 WARN，用 fallbackBiome
    否则                                     → 替换为 target
```

**~~`eroded_borealis` 已决启用（D1）~~** → **已推翻**：映射表不使用 BWG 默认禁用的群系。
但"查禁用则跳过"的防御逻辑**予以保留**（现为：保留原版 + 记 ERROR，无兜底群系）。

### 3. 白名单与黑名单（依据 `run/exported_vanilla_biomes.json`，7593 参数点 / 53 群系）

> ⚠️ **本节数字已过时**（14 白名单 / 39 黑名单 / 31 目标）。最终为
> **21 白名单 / 32 黑名单 / 25 个 BWG 目标**——`eroded_borealis` 被剔除出目标集，
> 且新增 7 项白名单。权威版本见总设计文档 4.6 节与 `DisasterBiomeMapping` 源码。

**白名单（当时 14，保留原版；最终 21）：**

| 类别 | 群系 |
|---|---|
| 海洋（9） | `frozen_ocean` `deep_frozen_ocean` `cold_ocean` `deep_cold_ocean` `ocean` `deep_ocean` `lukewarm_ocean` `deep_lukewarm_ocean` `warm_ocean` |
| 河流（2） | `river` `frozen_river` |
| 洞穴（3） | `lush_caves` `dripstone_caves` `deep_dark` |

**黑名单（39 → 替换为 31 个 BWG 群系）：**

| 原版 | → BWG | 原版 | → BWG |
|---|---|---|---|
| `plains` | `prairie` | `savanna` | `baobab_savanna` |
| `sunflower_plains` | `amaranth_grassland` | `savanna_plateau` | `baobab_savanna` |
| `forest` | `temperate_grove` | `windswept_savanna` | `firecracker_chaparral` |
| `flower_forest` | `rose_fields` | `jungle` | `jacaranda_jungle` |
| `birch_forest` | `aspen_boreal` | `sparse_jungle` | `fragment_jungle` |
| `old_growth_birch_forest` | `aspen_boreal` | `bamboo_jungle` | `tropical_rainforest` |
| `dark_forest` | `black_forest` | `desert` | `mojave_desert` |
| `taiga` | `coniferous_forest` | `badlands` | `rugged_badlands` |
| `snowy_taiga` | `frosted_taiga` | `eroded_badlands` | `sierra_badlands` |
| `old_growth_pine_taiga` | `frosted_coniferous_forest` | `wooded_badlands` | `red_rock_valley` |
| `old_growth_spruce_taiga` | `frosted_coniferous_forest` | `stony_peaks` | `dacite_ridges` |
| `snowy_plains` | `crimson_tundra` | `windswept_hills` | `dacite_ridges` |
| `ice_spikes` | `shattered_glacier` | `windswept_gravelly_hills` | `canadian_shield` |
| `grove` | `eroded_borealis` ✅（D1 启用） | `windswept_forest` | `black_forest` |
| `meadow` | `coconino_meadow` | `stony_shore` | `basalt_barrera` |
| `snowy_slopes` | `howling_peaks` | `snowy_beach` | `basalt_barrera` |
| `frozen_peaks` | `howling_peaks` | `beach` | `dacite_shore` |
| `jagged_peaks` | `howling_peaks` | `cherry_grove` | `sakura_grove` |
| `swamp` | `bayou` | `mushroom_fields` | `crag_gardens` |
| `mangrove_swamp` | `cypress_swamplands` | | |

**核对结果（脚本逐项验证）：**
- 映射表覆盖 `exported_vanilla_biomes.json` 全部 **53/53** 群系，无遗漏
- 白名单 14 项在新映射表中**均不产生替换值**（已修正——旧表会替换海洋与河流）
- 31 个替换目标中 **30 个**在 BWG 配置中为 `true`，仅 `eroded_borealis` 为 `false`

**映射表未被用作目标的 24 个已启用 BWG 群系**（正常，它们通过 region 数组 1..3 进入，不依赖替换）：
`allium_shrubland` `araucaria_savanna` `atacama_outback` `cika_woods` `cypress_wetlands` `dead_sea`
`ebony_woods` `enchanted_tangle` `forgotten_forest` `ironwood_gour` `lush_stacks` `maple_taiga`
`orchard` `overgrowth_woodlands` `pale_bog` `pumpkin_valley` `rainbow_beach` `red_rock_peaks`
`redwood_thicket` `skyris_vale` `weeping_witch_forest` `white_mangrove_marshes` `windswept_desert` `zelkova_forest`

---

## 配置

> ⚠️ **整节已作废。** 本功能最终**不提供任何配置项**——白名单与映射表是硬编码的结构性决策。
> 移除 `[disaster_biomes]` 与 `fallbackBiome` 的理由见总设计文档决策 19。
> 下面保留当时的取舍记录（尤其是"为什么必须是 COMMON 而非 SERVER"这一分析，
> 它解释了**配置方案**为何在客户端/服务端一致性问题上是脆弱的——而这正是最终选择硬编码的原因之一）。

`beloong-common.toml`（COMMON 类型，服务端与客户端行为需一致）：

```toml
[disaster_biomes]
    # 是否在天灾维度剔除原版群系（白名单外）
    enabled = true
    # 替换求解失败时的兜底群系
    fallbackBiome = "biomeswevegone:prairie"
```

**为什么是 COMMON 而非 SERVER**：`possibleBiomes()` 在客户端也会被调用（自然罗盘、地图、调试界面）。
若两端不一致会导致客户端预测与服务端不符，因此必须是 COMMON。这是本方案里唯一的配置类型约束。

---

## 验证方案

### 前置：必须新建世界

旧区块不会回填。`run/saves/` 下有 20 余个历史存档，验证时**必须新建**，否则得到假阳性。

### 自动化：区块 NBT 扫描

```powershell
cd D:\Minecraft\BeLoong-Core
# 脚本在 disaster_test 分支，无需写入工作区
git show disaster_test:tools/scan-biomes.js | node - "run\saves\<新存档>" --files 8 --chunks 250
```

**通过判据：**

| 检查项 | 通过标准 |
|---|---|
| 天灾维度 `minecraft:` 种类 | **≤ 14**，且**只能是白名单的 14 个之一** |
| 天灾维度 `minecraft:` 明细 | 不得出现 `plains` `river` 以外的任何非白名单项；`plains`/`forest`/`ocean`(非白名单)/`beach`/`desert`/`savanna` **必须为 0** |
| 天灾维度 `biomeswevegone:` 种类 | 应显著上升（当前 16 种；替换后兜底树新增 31 个目标，理论上限 55） |
| 主世界 | `biomeswevegone:` **必须为 0**（回归检查，确认没有污染共享单例） |

### 游戏内（需求 2 的直接验收）

| 检查 | 通过标准 |
|---|---|
| `/execute in beloong:disaster run locate biome minecraft:plains` | **立即**返回"无法找到"（不是搜索很久后才返回） |
| 同上，对 `minecraft:forest` / `minecraft:desert` / `minecraft:badlands` | 同上 |
| `/execute in beloong:disaster run locate biome minecraft:river` | **能**找到（白名单，应保留） |
| `/execute in beloong:disaster run locate biome minecraft:lush_caves` | **能**找到 |
| 自然罗盘：`minecraft:plains` | 只显示主世界，不含天灾维度 |
| 自然罗盘：`minecraft:river` | 主世界与天灾维度都显示 |
| `/execute in minecraft:overworld run locate biome minecraft:plains` | 能正常找到（回归检查） |

### 启动期检查

天灾与主世界的 `ServerLevel` 各查一次 `biomeSource.possibleBiomes()` 大小与内容——这是最直接的证据，
但需要临时调试命令或日志。可选加一条 DEBUG 级启动日志。

---

## 已知风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| `values` 字段是 final | 替换失败 | `@Mutable @Accessor`；TerraBlender 有同样手法的先例 |
| 替换目标被 BWG 配置禁用 | 参数列表含不可用群系 | 替换前查 `BWGWorldGenConfig.INSTANCE.biomes`，禁用则跳级。D1 落地后当前无触发项，但保留防御逻辑 |
| 替换目标不存在于群系注册表 | `RTree` 建树时崩溃 | 查 `BuiltInRegistries.BIOME.containsKey`；不存在则用 fallback |
| 其它 TerraBlender 模组从 `region 0` 贡献原版群系 | 原版群系漏网 | 逐一核对：本项目环境日志只有 `minecraft:overworld`(0) 与 BWG(1/2/3)；index 0 的树已被替换覆盖。**若将来装了别的 TerraBlender 模组需重新核对** |
| `_ocean` 与 `_deep_*_ocean` 各只有 2~4 个参数点（深/浅各一档） | 海洋占地面积极小 | 这是原版参数空间的固有形态，非本方案引入。若观感不足，属独立议题 |
| 客户端/服务端 `possibleBiomes` 不一致 | 预测错位 | 配置用 COMMON 类型 |
| 映射表气候精度 | 某替代群系出现在气候不协调处 | 见 D3：先用手写版实测，发现不协调再局部修正 |

---

## 实施记录（2026-09-11，分支 `disaster2`）

### 与原设计的两处偏差

| # | 原设计 | 实际落地 | 原因 |
|---|---|---|---|
| **I1** | `DisasterBiomeSubstitution` 放在 `mixin` 包 | 移到 `worldgen` 包 | **Mixin 硬约束**：`beloong.mixins.json` 声明了 `package: com.zonlong.beloong.mixin`，该包下的**任何非 mixin 类**都不允许被直接引用。首次实机启动即崩：`IllegalClassLoadError: com.zonlong.beloong.mixin.DisasterBiomeSubstitution is in a defined mixin package ... and cannot be referenced directly`。`ParameterListAccessor` 是真正的 mixin，可留在原处 |
| **I2** | 未设维度守卫 | 新增 `DisasterBiomeSubstitution.isTargetDimension(levelKey)` | **设计遗漏**：`LevelUtils.initializeBiomes` 的重定向对**所有**被 TerraBlender 判定为 `RegionType.OVERWORLD` 的维度都会触发。实测 `minecraft:the_nether` 也命中该调用点，把下界 5 个群系（`nether_wastes`/`soul_sand_valley`/`crimson_forest`/`warped_forest`/`basalt_deltas`）替换成了 `prairie`（日志确认 `参数点 5 个，替换 5 个`）。加维度守卫后下界不再被触碰 |

### 另外两项与原设计不同的实现决定

| # | 项 | 说明 |
|---|---|---|
| **I3** | BWG 配置读取改用反射 | 原计划 `compileOnly` + 直接 import `BWGWorldGenConfig`。但**直接引用会使包含该引用的方法在类校验期就解析 BWG 类**，BWG 缺席时抛 `NoClassDefFoundError`——该错误发生在进入方法体之前，外层 `try/catch` 捕获不到，会导致 Mixin 应用失败。改为 `Class.forName` 反射 + `ModList` 守卫。因此 BWG **不需要** `compileOnly` |
| **I4** | BWG 由 `7745142` 升到 `8245253` | `8245253` = BWG 2.6.0，与实机运行的版本一致（原 `build.gradle` 写的是旧版，与日志不符） |

### 运行期验证结果（已通过）

日志证据（`run/logs/latest.log`，世界 `天灾7`）：

```
[Server thread/INFO] [terrablender/]: Initialized TerraBlender biomes for level stem minecraft:the_nether
[Server thread/INFO] [com.zonlong.beloong.BeLoongCore/]: [BeLoong] 天灾维度群系替换：参数点 7593 个，替换 7552 个，未能求解 0 个
[Server thread/INFO] [terrablender/]: Initialized TerraBlender biomes for level stem beloong:disaster
```

| 检查项 | 结果 |
|---|---|
| `@Accessor` 生效，`values` 可替换 | ✅ （否则读不到 7593 个参数点） |
| 替换数量正确 | ✅ 替换 **7552** + 保留 **41** = 7593，与白名单 14 项的参数点总数**精确吻合**（脚本独立算出白名单占 41 点） |
| 未求解数为 0 | ✅ 映射表 53/53 全覆盖，无降级 |
| `enabled=false` 时行为 | ✅ 已在 `天灾测试3` 上确认替换日志不出现（该次为旧构建） |
| 下界未被误伤 | ✅ 加守卫后 `nether_wastes` 等 5 个群系零命中 |
| 无 Mixin 错误 | ✅ 无 `IllegalClassLoadError` / `Mixin apply failed` |
| **主世界无 BWG 泄漏**（关键回归） | ✅ `天灾测试3` 主世界扫描：`biomeswevegone: 0 种 / 0 次` |
| 编译 | ✅ `gradlew build` BUILD SUCCESSFUL |

### 关键发现：天灾维度有两条独立路径（I5）

第一版实现落地后**用户实测失败**：天灾维度仍生成恶地等原版群系，`/locate` 仍能搜到，自然罗盘仍显示。
排查后发现根因是本设计**遗漏了一个结构性事实**——天灾维度存在两条彼此独立的数据路径，只修其中一条不够：

| 路径 | 数据源 | 对应修复 |
|---|---|---|
| **生成路径**<br>`getNoiseBiome` → `MixinParameterList.findValuePositional` → `uniqueTrees` | TerraBlender 初始化时被替换过的 clone 的 `values` | `CloneParameterListMixin` ✅ |
| **查询路径**<br>`/locate biome`、自然罗盘、地图 → `BiomeSource.possibleBiomes()` | **关卡实际持有**的那份 `parameters().values()` | `PossibleBiomesFilterMixin` ✅ |

**为什么关卡持有的 BiomeSource 不是初始化时那一个**：`LevelUtils.initializeOnServerStart` 遍历的是
`LevelStem` 注册表，改的是注册表里那个 ChunkGenerator 的 BiomeSource；关卡运行时持有的是**另一个实例**，
其 `values` 从未被替换。实测对比（`[VERIFY]` 探针）：

| 维度 | `parameters` | 第一版 `possibleBiomes` | 修复后 |
|---|---|---|---|
| `minecraft:overworld` | `Right(preset)` | 53（14+39） | 53（不变，正确） |
| `beloong:disaster` | `Left(ParameterList)` | **109**（53 原版 + 56 BWG） | **70**（14 + 55，黑名单 0） |
| `minecraft:the_nether` | `Left(ParameterList)` | 5 | 5（不变，正确） |

109 = 53 + 56 正是「完整原版 + 全部 BWG」，说明关卡那份 `values` 完全没被碰过。

### 查询路径修复的两个坑（I6 / I7）

**I6 — 只过滤 `collectPossibleBiomes` 无效。**
第一版补丁注入 `MultiNoiseBiomeSource.collectPossibleBiomes()` 的 RETURN 点。
日志确认**注入确实触发、语义判据也正确**（`含mod群系=true`），但 `possibleBiomes()` 仍返回 109。

原因：`possibleBiomes()` 的实现是 `possibleBiomes.get()`——读一个 supplier 字段，而 TerraBlender 的
`MixinBiomeSource.appendDeferredBiomesList` 会把这个字段整体替换成一个**缓存闭包**：

```java
this.possibleBiomes = () -> new ObjectLinkedOpenHashSet<>(possibleBiomes.stream().distinct()...);
```

闭包捕获的是**替换那一刻**算出的集合。`collectPossibleBiomes` 只在缓存建立时被调用一次，
之后 `possibleBiomes()` 直接读缓存，不再经过被过滤的方法。**必须在缓存建立的那一点就让内容干净。**

**I7 — 正确的注入点是 `appendDeferredBiomesList`。**
`PossibleBiomesFilterMixin` 在该方法 HEAD 处 `cancellable` 注入：对天灾维度自行合并
「现有集合 + 本次追加列表」、剔除黑名单、重设 supplier，然后 `ci.cancel()` 跳过 TerraBlender
用未过滤集合重建缓存闭包的原逻辑。

**如何识别天灾维度**：用**语义判据**而非对象身份——TerraBlender 只在 `RegionType.OVERWORLD`
维度调用本方法，而实测五个维度中只有天灾维度的参数列表含 `biomeswevegone:` 群系
（主世界 53 个全原版、下界 5 个全原版、末地走 `TheEndBiomeSource`、龙宫走 `FixedBiomeSource`）。
判据：**追加列表里含非 `minecraft:` 命名空间群系**。

> ⚠️ **若将来主世界恢复 BWG 群系**，该判据会同时命中主世界。届时需改为比对维度身份
> （例如在 `CloneParameterListMixin` 里按 `levelKey` 打标记），不能只靠这条语义判据。

### 最终验收结果（本轮实测，干净构建）

用临时 `[VERIFY]` 探针（已删除）在新建世界启动后打印：

```
[VERIFY] minecraft:overworld   白名单原版=14 黑名单残留=39 BWG=0    ← 正确，主世界本就该有
[VERIFY] minecraft:the_nether  白名单原版=0  黑名单残留=5  BWG=0    ← 正确
[VERIFY] minecraft:the_end     白名单原版=0  黑名单残留=5  BWG=0    ← 正确
[VERIFY] beloong:disaster      白名单原版=14 黑名单残留=0  BWG=55   ← ★ 目标达成
[VERIFY] beloong:loong_palace  白名单原版=0  黑名单残留=0  BWG=0
[BeLoong] 天灾维度群系替换：参数点 7593 个，替换 7552 个，未能求解 0 个
```

**生成路径**（更强的证据，非 NBT 扫描而是游戏内存中的群系容器）：强制生成 289 个全新区块
（坐标 20000,20000），读 25 个区块全部 section 的群系：

```
biomeswevegone:dacite_ridges=46216, biomeswevegone:coniferous_forest=238,
minecraft:deep_dark=1966, minecraft:lush_caves=2780
其中原版群系：[deep_dark, lush_caves]   ← 全部白名单，黑名单 0
```

另有 289 个地表点抽样，原版只出现 `minecraft:river`（白名单）。

### 排查过程中犯的错（值得记录）

1. **把「代码执行了」当成「结果被使用了」**——第一版只验证了替换计数 7552 与白名单 41 精确吻合，
   就下了「机制成功」的结论。这正是 `disaster_test` 交接文档第六节「教训 2」警告的错误，我重犯了。
   教训：**必须在消费端取证**——本轮唯一有效的证据是读区块内存的群系容器与 `possibleBiomes()` 的真实内容。
2. **`Climate.RTree` 是 protected**，无法在包外查询树内容；诊断时应改用 `getTree(0)` 之外的公开 API。

### ⚠️ 重要：`tools/scan-biomes.js` 的读数是失真的（I8）

本项目一直用 `disaster_test` 分支的 `tools/scan-biomes.js`（解析 `.mca` 的 `sections[].biomes.palette`）
做群系统计。**本次发现它对单个区块的读数不可信**，会显著高报原版群系。

证据：对同一批全新生成的区块（chunk 3000,3000 附近 16 个区块，天灾维度），
用**两种互相独立**的方式读同一份数据：

| 读数方式 | 结果 |
|---|---|
| **A. 内存**：`chunk.getSection(i).getBiomes().get(x,y,z)` | 种数 2，黑名单 **0**：`temperate_grove=30034`, `lush_caves=2734` |
| **B. 游戏自己的序列化器**：反射调用 `ChunkSerializer.write` 再读 `sections[].biomes.palette` | 种数 2，黑名单 **0**：`temperate_grove=490`, `lush_caves=53`（palette 条目数） |

**两种方式结论完全一致：零黑名单。**

而同一时期用 `scan-biomes.js` 扫该存档的天灾维度，却报出 `minecraft:plains 29056`、`ocean 5216`
等大量黑名单群系。进一步按区块 `Status` 拆解，露出马脚：

| 区块 Status | 区块数 | 含黑名单 | 比例 |
|---|---|---|---|
| `minecraft:structure_starts` | 2513 | 2513 | **100.0%** |
| `minecraft:full` | 2449 | 728 | 29.7% |
| `minecraft:biomes` | 286 | 93 | 32.5% |
| `minecraft:initialize_light` | 285 | 92 | 32.3% |
| `minecraft:carvers` | 275 | 92 | 33.5% |
| `minecraft:noise` | 8 | 0 | 0.0% |

`structure_starts` 阶段的区块**尚未生成群系**，却是 100% 命中——这说明脚本在读**不属于它的字节**。
另外它统计的是「palette 出现次数」而非「群系实际占用体积」，量纲本身就与预期不符
（对照：读数 B 里 32 个 section 的 palette 合计仅 543 条）。

**结论与后续约定：**

- `scan-biomes.js` **不得再用于「某群系是否在天灾维度生成」的判定**。它的正确用途仅限于
  大范围粗粒度分布观察，且结论必须用下面的方式复核。
- 验证群系是否生成，一律以**游戏内存**为准：`ServerLevel.getBiome(pos)` 或
  `chunk.getSection(i).getBiomes().get(x,y,z)`。
- 旧区块本就含修复前生成的原版群系（NBT 不回填），用旧存档扫描必然得到假阳性。

### 环境改动（不入版本库）

> ⚠️ **已作废。** 映射表最终**刻意避开** BWG 默认禁用的群系（`grove → frosted_taiga`），
> 因此**不需要**任何 BWG 配置改动，干净部署即可生效。以下为当时的记录。

`run/config/biomeswevegone/world_generation.json` 的 `biomeswevegone:eroded_borealis` 已由 `false` 改为 `true`（D1）。
该路径在 `.gitignore` 内（`run/`），**不会随代码分发**——整合包需要单独应用这一项，否则 `grove` 会降级到 `prairie`。

---

| # | 问题 | 决定 | 最终结果 |
|---|---|---|---|
| **D1** | `grove → eroded_borealis`，但后者在 BWG 配置中被禁用 | **启用 `eroded_borealis`**。它是 BWG 唯一的覆雪山林群系，映射语义最准；被禁用看起来是随手关的——BWG 自己的 `igloo` / `village_snowy` 结构标签仍在引用它。落地方式：在 `run/config/biomeswevegone/world_generation.json` 与 `run/defaultconfigs/`（若存在）中把它改为 `true`。**注意这是改 BWG 配置，需在交付说明里注明。** | ❌ **推翻**：改配置的路径在 `.gitignore` 内、无法随代码分发，会变成"干净部署静默降级"。改为 `grove → frosted_taiga`（默认启用） |
| **D2** | `fallbackBiome` 默认值 | `biomeswevegone:prairie`（中温中湿的气候中心，匹配失败时最不突兀） | ❌ **推翻**：无兜底群系，映射不可用时保留原版 + 记 ERROR |
| **D3** | 映射表精度：手写 vs 自动最近邻 | **先用手写版**上线并实测观感；若发现某群系出现在气候不协调处，再针对性修正。自动最近邻计算留作后续可选项 | ✅ **采纳**（14 → 32 项） |
| **D4** | 是否保留配置开关 | **保留 `enabled` 开关，默认 `true`**。语义：`false` = 跳过替换步骤，完全回到旧行为（原版基线 + BWG 叠加） | ❌ **推翻**：`false` 回不到旧行为（生成层关了、查询层还开着）。删除整个配置节 |
| **D5** | `docs/天灾维度总设计.md` 的更新时间点 | **等代码落地并新区块实测通过后再改**，避免出现"文档已改但代码未生效"的中间状态。本次只交付本设计文档，不产出配套 `*-plan.md` | ✅ **已执行**（总设计文档已完成更新） |

**所有阻塞项已关闭，代码已落地。**

## 落地文件清单

| 文件 | 操作 |
|---|---|
| `mixin/ParameterListAccessor.java` | 新建（mixin，暴露 final 的 `values`） |
| `mixin/PossibleBiomesFilterMixin.java` | 新建（查询路径：在 `appendDeferredBiomesList` 处过滤缓存） |
| `worldgen/DisasterBiomeSubstitution.java` | 新建（求解器：白名单 + 映射 + 生效标志） |
| `worldgen/DisasterBiomeMapping.java` | 新建（**32 项**映射表 / 25 个目标） |
| `mixin/CloneParameterListMixin.java` | 修改（插入替换 + 异常安全） |
| ~~`Config.java`~~ | ~~修改（`[disaster_biomes]`：`enabled` / `fallbackBiome`）~~ **不修改——配置节已删除** |
| `src/main/templates/META-INF/neoforge.mods.toml` | 修改（`terrablender` / `biomeswevegone` 改为 `required`） |
| `data/terrablender/tags/dimension_type/overworld_regions.json` | 新建（`replace: true`，只列 `beloong:disaster`） |
| `beloong.mixins.json` | 修改（注册 3 个生产 mixin） |
| `build.gradle` | 修改（BWG 升至 2.6.0；不加 `compileOnly`） |
| ~~`run/config/biomeswevegone/world_generation.json`~~ | ~~修改（环境，`eroded_borealis = true`）~~ **不再需要** |

---

## 验收清单

**已全部验证通过**（下表为**当时**的读数；白名单后来由 14 扩为 21，
最终读数为 `7593 = 6782 替换 + 811 保留`、`possibleBiomes` 为 `21 白名单 + 55 BWG = 76`）。
现行权威读数见总设计文档 4.8 节。

运行期证据（`[VERIFY]` 探针 + 用户实机确认）：

- [x] ~~`eroded_borealis` 已在 BWG 配置中启用（D1，环境改动）~~ → 改为映射表避开，无需环境改动
- [x] 替换计数与白名单精确吻合（当时 7552 替换 + 41 保留 = 7593，未求解 0）
- [x] **天灾维度 `possibleBiomes` 黑名单残留 = 0**（当时 109 → 70：14 白名单原版 + 55 BWG）
- [x] **天灾维度生成层黑名单 = 0**（内存读数与游戏序列化器读数双重确认）
- [x] 主世界 `possibleBiomes` 完全不变（53 = 21 + 32，BWG = 0）
- [x] 下界 / 末地 / 龙宫完全不受影响
- [x] 编译干净、无启动崩溃、无 Mixin 错误、无 `RTree` 异常
- [x] 日志无残留诊断输出（全部探针已删除）

用户实机确认（2026-09-11）：

- [x] `/execute in beloong:disaster run locate biome minecraft:plains` 立即返回「无法找到」
- [x] 自然罗盘对原版群系只显示主世界
- [x] 天灾维度不再生成黑名单原版群系（恶地等）
- [x] 用户结论：「验证通过，完美符合要求」

剩余可选项（非阻塞）：

- [ ] 天灾维度长期观感确认；若发现某替代群系出现在气候不协调处，再做 D3 的自动最近邻映射
- [x] ~~`enabled = false` 的回退行为实测（D4）~~ → 配置项已删除，无此行为
- [x] ~~更新 `docs/天灾维度总设计.md` 第四 / 八 / 九节（D5）~~ → **已完成**，见该文档第四、六、八、九、十节

> **下一步不在本文范围**：剩余 21 个白名单原版群系由 `beloong:` 自制群系接管，
> 见 `docs/plans/2026-09-11-disaster-phase2-handover.md`。
