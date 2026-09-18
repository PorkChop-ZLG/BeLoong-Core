# 天灾维度：TerraBlender region 树输出取证

**Date:** 2026-09-11
**Status:** 取证完成
**手段:** 临时探针（`DisasterBiomeSubstitution.probeRegionTreeBiomes`，已删除）
**运行环境:** `gradlew runServer`，MC 1.21.1 / NeoForge 21.1.236 / TerraBlender 4.1.0.8 / BWG 2.6.0

> 本文只记录一次**消费端取证**的原始结果与解读，不含设计决策。
> 设计结论见 `docs/天灾维度总设计.md`；相关背景见
> `docs/天灾维度群系剔除-两次尝试复盘.md` 与
> `docs/reviews/2026-09-11-disaster-biome-removal-review.md`。

---

## 一、探针要回答的问题

TerraBlender 的 `MixinParameterList.initializeForTerraBlender` 为**每个 region** 单独建一棵
RTree（`MixinParameterList.java:74-95`），而 `findValuePositional` **先查 region 树**，
只有拿到 `Region.DEFERRED_PLACEHOLDER` 才回退到 index 0 的兜底树
（`MixinParameterList.java:144-149`）。

index 0 = `DefaultOverworldRegion`，其树由 `Climate.RTree.create(this.values)` 建立
（`MixinParameterList.java:79-82`）——**这正是 `CloneParameterListMixin` / `DisasterBiomeSubstitution`
改写的那一份 `values`**。

因此：**凡是被 region 树直接吐出的具体群系，都不受群系替换的影响。**

`BWGTerraBlenderRegion.addBiomes`（`:137-172`）的最后一个分支会把既不在 BWG 数组、
也不在 swapper 里的群系**原样放行**：

```java
if (!mapped) {                                                       // :165-168
    mapper.accept(new Pair<>(parameterPoint, this.globalSwapper.getOrDefault(biomeKey, biomeKey)));
}
```

而 TerraBlender 只覆写了 `pickBeachBiome` / `pickPeakBiome` / `pickSlopeBiome` 三个方法
（`TerrablenderOverworldBiomeBuilder.java:112-137`），原版 `OverworldBiomeBuilder` 里
其余大量群系来自**私有硬编码路径**。探针用于把这条源码推断变成事实。

---

## 二、探针实现

在 `DisasterBiomeSubstitution` 中加一个纯读取方法，从 `CloneParameterListMixin`
的目标维度分支调用一次：遍历 `Regions.get(RegionType.OVERWORLD)`，对每个 region
直接调用其公开方法 `addBiomes(Registry<Biome>, Consumer<...>)`，把输出的参数对
按命名空间分类计数。`addBiomes` 只把静态数组展开成参数对，无副作用。

---

## 三、原始输出

```
[探针] region minecraft:overworld：参数点 7593 个
[探针] region minecraft:overworld：minecraft: 群系 53 种 → badlands(282) bamboo_jungle(104)
  beach(330) birch_forest(100) cherry_grove(60) cold_ocean(2) dark_forest(212)
  deep_cold_ocean(2) deep_dark(1) deep_frozen_ocean(2) deep_lukewarm_ocean(2) deep_ocean(2)
  desert(708) dripstone_caves(1) eroded_badlands(144) flower_forest(80) forest(588)
  frozen_ocean(2) frozen_peaks(120) frozen_river(10) grove(312) ice_spikes(102)
  jagged_peaks(120) jungle(248) lukewarm_ocean(2) lush_caves(1) mangrove_swamp(14)
  meadow(180) mushroom_fields(2) ocean(2) old_growth_birch_forest(76)
  old_growth_pine_taiga(112) old_growth_spruce_taiga(100) plains(586) river(8)
  savanna(356) savanna_plateau(128) snowy_beach(110) snowy_plains(388) snowy_slopes(208)
  snowy_taiga(310) sparse_jungle(86) stony_peaks(80) stony_shore(12) sunflower_plains(76)
  swamp(14) taiga(352) warm_ocean(4) windswept_forest(142) windswept_gravelly_hills(96)
  windswept_hills(114) windswept_savanna(216) wooded_badlands(284)
[探针] region minecraft:overworld：非 minecraft 群系 0 种 →

[探针] region biomeswevegone:region_0：参数点 7593 个
[探针] region biomeswevegone:region_0：minecraft: 群系 12 种 → badlands(122) beach(66)
  deep_dark(1) dripstone_caves(1) eroded_badlands(64) frozen_river(10) lush_caves(1)
  mushroom_fields(2) river(8) stony_shore(12) windswept_savanna(216) wooded_badlands(124)
[探针] region biomeswevegone:region_0：非 minecraft 群系 43 种 → （42 个 BWG 群系）
  terrablender:deferred_placeholder(1534)

[探针] region biomeswevegone:region_1：参数点 7593 个
[探针] region biomeswevegone:region_1：minecraft: 群系 11 种 → badlands(122) deep_dark(1)
  dripstone_caves(1) eroded_badlands(64) frozen_river(10) lush_caves(1) mushroom_fields(2)
  river(8) stony_shore(12) windswept_savanna(216) wooded_badlands(124)
[探针] region biomeswevegone:region_1：非 minecraft 群系 34 种 → （33 个 BWG 群系）
  terrablender:deferred_placeholder(2982)

[探针] region biomeswevegone:region_2：参数点 7593 个
[探针] region biomeswevegone:region_2：minecraft: 群系 11 种 → badlands(122) deep_dark(1)
  dripstone_caves(1) eroded_badlands(64) frozen_river(10) lush_caves(1) mushroom_fields(2)
  river(8) stony_shore(12) windswept_savanna(216) wooded_badlands(124)
[探针] region biomeswevegone:region_2：非 minecraft 群系 30 种 → （29 个 BWG 群系）
  terrablender:deferred_placeholder(2990)
```

---

## 四、结论

### 4.1 三个 BWG region 各自吐出 11–12 种 `minecraft:` 群系

| Region | 索引 | `minecraft:` 种数 | DEFERRED 参数点 |
|---|---|---|---|
| `minecraft:overworld`（DefaultOverworldRegion） | 0 | 53（完整原版，**是我们改写的那一份**） | — |
| `biomeswevegone:region_0` | 1 | **12** | 1534 |
| `biomeswevegone:region_1` | 2 | **11** | 2982 |
| `biomeswevegone:region_2` | 3 | **11** | 2990 |

### 4.2 白名单 21 项在机制上分为两桶（已被证实）

| 桶 | 项数 | 项 | 来源 | 第二阶段改映射表 |
|---|---|---|---|---|
| **DEFERRED 桶** | 14 | 海洋 9 + 碎裂 3 + `stony_peaks` + `snowy_beach` | 这些格子在 BWG 数组里是 `DEFERRED_PLACEHOLDER` → 回退到 index 0 | ✅ 有效 |
| **region 树桶** | 7 | `river` `frozen_river` `lush_caves` `dripstone_caves` `deep_dark` `stony_shore` `windswept_savanna` | 原版 builder 私有硬编码，BWG **原样放行** | ❌ **无效** |

> region 树桶的 7 项与设计文档 4.6 节里归为「河流 2 + 洞穴 3 + 其他硬编码位置 2」的
> 完全一致。文档的**结论**（这些位置 BWG 给不出替代）正确，但**理由**错了：
> 不是「BWG 在参数空间上留空」，而是「BWG 主动把原版群系放行」。

### 4.3 另有 5 项黑名单群系泄漏（本次新发现）

这 5 项**不在**白名单（应被剔除），却同样由 BWG 的 region 树原样吐出：

| 群系 | region_0 参数点数 | 硬编码位置 |
|---|---|---|
| `badlands` | 122 | `OverworldBiomeBuilder.java:958-964`，经 `:932` 的 `pickMiddleBiomeOrBadlandsIfHot` 在 `temperature == 4` 时调用。TerraBlender **没有覆写** `pickBadlandsBiome` |
| `eroded_badlands` | 64 | 同上（`:960`） |
| `wooded_badlands` | 124 | 同上（`:962`） |
| `mushroom_fields` | 2 | `OverworldBiomeBuilder.java:214`（`addOffCoastBiomes` 硬编码） |
| `beach` | 66 | **`BWGBiomeSelectors.java:104`** —— BWG 在 `BEACH_BIOMES_BWG[1]` 里**直接写了 `Biomes.BEACH`**，于是走 `bwgKeys` 分支被当作「BWG 自己的群系」原样输出 |

**这构成第一阶段的实际缺口。** 它们是黑名单 → `PossibleBiomesFilterMixin` 会把它们从
`possibleBiomes()` 里删掉 → `/locate biome minecraft:badlands` 在天灾维度搜不到；
但**生成层照样产出**。这正是总设计文档决策 19 明确判定不可接受的「第三种状态」。

### 4.4 为什么 4.8 节的验证没有发现

总设计 4.8 节的「生成层」验证读取的是
`biomeSource.parameters().values` ——**那就是 index 0 的兜底树，也就是我们改写的那一份**。
region 树从来不在这条验证路径上。`possibleBiomes()` 侧的验证同样只看合并后的集合，
而过滤器恰好会把泄漏项隐藏掉，反而掩盖了问题。

---

## 五、对第二阶段的影响

1. **7 项无法靠改映射表接管。** index 0 的兜底树只在**未被任何 region 认领的列**上生效；
   BWG region 认领的列走 BWG 的树。把这 7 项从白名单移除并改指向 `beloong:` 之后，
   天灾维度会变成**一部分自制群系、一部分原版群系的空间混合**——比不做更糟。
2. **需要新增一条注入路径**，去改写 BWG region 树输出的参数对。
   现有两条注入路径（`CloneParameterListMixin` 生成层 / `PossibleBiomesFilterMixin` 查询层）
   都只覆盖 index 0。
3. **这 5 项泄漏需要独立修复**，与第二阶段是否推进无关。

---

## 六、附带发现

- `run/config/biomeswevegone/world_generation.json` 中
  `"biomeswevegone:eroded_borealis": true` —— 本机是**手工启用**状态。
  设计文档 4.7 / 第九节按「BWG 默认禁用 `eroded_borealis`」立论，
  在干净部署上该前提是否成立需要单独复核（映射表不使用它，因此不影响正确性）。

---

## 七、复现方式

探针为临时代码，取证后已删除。复现步骤：

1. 在 `DisasterBiomeSubstitution` 中重新加入遍历 `Regions.get(RegionType.OVERWORLD)`
   并调用各 region `addBiomes` 的日志方法；
2. 从 `CloneParameterListMixin.cloneBeforeInit` 的目标维度分支调用一次；
3. `gradlew runServer`（注意 `run/mods` 中的 sodium / iris 是纯客户端模组，
   会让专用服务器在 bootstrap 阶段 `NoClassDefFoundError: org/lwjgl/Version`，
   需临时移出）；
4. 在 `run/logs/latest.log` 中检索 `[探针]`。
