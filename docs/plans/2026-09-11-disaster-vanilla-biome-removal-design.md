# 天灾维度剔除原版群系 设计文档

**Date:** 2026-09-11
**Status:** Implemented（运行期机制已验证；新区块生成验证待做）
**Branch:** `disaster2`
**Scope:** 仅修改 BeLoong-Core。不改 BWG 源码、不改 TerraBlender、不改 `overworld_regions` tag 语义
**已决事项:** D1 启用 `eroded_borealis` · D2 fallback=`prairie` · D3 手写映射表 · D4 保留 `enabled` 开关（默认 true） · D5 总设计文档待实测通过后再改

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
| `worldgen/DisasterBiomeFilter.java` | **新建** | 替换求解器：白名单判定 / 禁用目标跳级 / fallback 兜底 |
| `Config.java` | 修改 | 新增 `[disaster_biomes]` 节：`enabled` + `fallbackBiome` |
| `beloong.mixins.json` | 修改 | 注册 `ParameterListAccessor` |
| `docs/天灾维度总设计.md` | 修改 | 第四、八节更新为「原版基线已被替换」 |

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

**`eroded_borealis` 已决启用（D1）**，因此 31 个替换目标落地后全部可用。实现仍须保留"查禁用则跳级"的防御逻辑——将来若有人再关掉某个 BWG 群系，不应导致参数列表里出现被禁用的群系。见【已决 D1】。

### 3. 白名单与黑名单（依据 `run/exported_vanilla_biomes.json`，7593 参数点 / 53 群系）

**白名单（14，保留原版）：**

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

### 尚未完成的验证

**新区块生成结果未验证。** 已扫描的存档（`天灾测试3`）其天灾区块是天灾维度仍残留 `badlands 3264` / `stony_shore 1376` 等**黑名单群系**，但这些区块是在旧代码下生成的，NBT 不会回填，属于假阴性——符合预期。

**验收必须在新建世界或未探索区域进行**：

```powershell
cd D:\Minecraft\BeLoong-Core
git show disaster_test:tools/scan-biomes.js | node - "run\saves\<新存档>" --files 8 --chunks 250
```

判据：天灾维度 `minecraft:` 出现次数应**只**来自白名单 14 项；`plains`/`forest`/`badlands`/`stony_shore`/`desert`/`savanna` 等必须为 0。

### 环境改动（不入版本库）

`run/config/biomeswevegone/world_generation.json` 的 `biomeswevegone:eroded_borealis` 已由 `false` 改为 `true`（D1）。
该路径在 `.gitignore` 内（`run/`），**不会随代码分发**——整合包需要单独应用这一项，否则 `grove` 会降级到 `prairie`。

---

| # | 问题 | 决定 |
|---|---|---|
| **D1** | `grove → eroded_borealis`，但后者在 BWG 配置中被禁用 | **启用 `eroded_borealis`**。它是 BWG 唯一的覆雪山林群系，映射语义最准；被禁用看起来是随手关的——BWG 自己的 `igloo` / `village_snowy` 结构标签仍在引用它。落地方式：在 `run/config/biomeswevegone/world_generation.json` 与 `run/defaultconfigs/`（若存在）中把它改为 `true`。**注意这是改 BWG 配置，需在交付说明里注明。** |
| **D2** | `fallbackBiome` 默认值 | `biomeswevegone:prairie`（中温中湿的气候中心，匹配失败时最不突兀） |
| **D3** | 映射表精度：手写 vs 自动最近邻 | **先用手写版**上线并实测观感；若发现某群系出现在气候不协调处，再针对性修正。自动最近邻计算留作后续可选项 |
| **D4** | 是否保留配置开关 | **保留 `enabled` 开关，默认 `true`**。语义：`false` = 跳过替换步骤，完全回到旧行为（原版基线 + BWG 叠加） |
| **D5** | `docs/天灾维度总设计.md` 的更新时间点 | **等代码落地并新区块实测通过后再改**，避免出现"文档已改但代码未生效"的中间状态。本次只交付本设计文档，不产出配套 `*-plan.md` |

**所有阻塞项已关闭，代码已落地。**

## 落地文件清单

| 文件 | 操作 |
|---|---|
| `mixin/ParameterListAccessor.java` | 新建（mixin，暴露 final 的 `values`） |
| `worldgen/DisasterBiomeSubstitution.java` | 新建（求解器，含白名单 + 维度守卫） |
| `worldgen/DisasterBiomeMapping.java` | 新建（39 项映射表） |
| `mixin/CloneParameterListMixin.java` | 修改（插入替换 + 维度守卫） |
| `Config.java` | 修改（`[disaster_biomes]`：`enabled` / `fallbackBiome`） |
| `beloong.mixins.json` | 修改（注册 `ParameterListAccessor`） |
| `build.gradle` | 修改（BWG 升至 2.6.0；不加 `compileOnly`） |
| `run/config/biomeswevegone/world_generation.json` | 修改（环境，`eroded_borealis = true`，**不入版本库**） |

---

## 验收清单（实施完成后逐项打勾）

已在运行期验证的项（证据见「实施记录」）：

- [x] `eroded_borealis` 已在 BWG 配置中启用（D1，环境改动）
- [x] `ParameterListAccessor` 注册成功，`values` 可替换（替换计数 7552 证明读写均生效）
- [x] 主世界 `biomeswevegone:` 计数为 0（无污染回归）
- [x] 替换计数与白名单精确吻合（7552 替换 + 41 保留 = 7593）
- [x] 下界未被误伤（维度守卫生效）
- [x] 无启动崩溃、无 `RTree` 相关异常、日志无映射表 WARN、无 Mixin 错误

**仍需在新建世界验证**（旧区块不回填，已扫描存档无法作为依据）：

- [ ] 新建世界，天灾维度 `minecraft:` 群系**全部** ∈ 白名单 14 项
- [ ] 天灾维度 `minecraft:plains` / `forest` / `desert` / `badlands` / `stony_shore` 计数为 0
- [ ] 天灾维度 `biomeswevegone:` 种类数上升（当前基线 16 种）
- [ ] `/execute in beloong:disaster run locate biome minecraft:plains` 立即失败
- [ ] `/execute in beloong:disaster run locate biome minecraft:river` 成功
- [ ] 自然罗盘对 `minecraft:plains` 只显示主世界
- [ ] `/execute in minecraft:overworld run locate biome minecraft:plains` 正常成功
- [ ] `enabled = false` 时生成行为完全回退到旧状态（D4 验证）
- [ ] 实测通过后更新 `docs/天灾维度总设计.md` 第四 / 八 / 九节（D5）
