# 天灾维度纯 BWG 群系化 + 结构白名单 — 设计文档

> 2026-09-10 · BeLoong Core 0.8.2

## 目标

`beloong:disaster` 维度:

1. 群系**只**来自配置的命名空间(默认 `biomeswevegone` + `regions_unexplored`),零原版群系;
2. 海洋/河流/洞穴由 RU 群系补足(BWG 自身无河/洞群系);
3. 该维度只跑 `structureSetWhitelist` 里的结构集(默认 `beloong:disaster_set`),要塞/矿井等原版残留结构消失;
4. 结构 spacing/separation/frequency(出现概率)可配置;
5. 主世界零影响。

## 机制背景(关键事实,均已对 1.21.1 + TerraBlender 4.1.0.8 + Lithostitched 1.8.0b4 字节码核实)

- 天灾维度 JSON 使用 `minecraft:overworld` 预设的 multi-noise 生物群系来源。
- TerraBlender 在 `LevelUtils.initializeBiomes` 里把**所有**注册的 OVERWORLD 区域(香草 + BWG;RU 0.6.2 已不使用 TB)混合进一张共享参数表,再按**区域唯一性噪声**按坐标选区域——这就是天灾维度混入原版群系的根因。
- 现有 `CloneParameterListMixin` 在该处克隆共享参数表防止跨维度污染(历史修复,必须保留)。
- Lithostitched 的 `BiomeInjectorManager.applyBiomeInjectors`(在 `MinecraftServer.initServer` 尾部、`createLevels` 之前执行)把主世界来源替换为自有的 `InjectorBiomeSource`,其合并参数表 = 原版 + BWG(TB 路径) + RU(LH 注入路径)全部 `(参数点, 群系)` 对;注入项按 `BiomeInjector#dimension()` 分维度。
- 结构生成是群系标签驱动:结构集与维度群系集无交集即被原版丢弃。因此群系过滤自动消灭原版**陆地**结构;但挂在 `#is_overworld`/`#is_ocean` 等标签上的结构(要塞/矿井/海底神殿/试炼密室…)依然残留——必须第二层白名单。
- 包侧已有 35 个标签覆盖(cataclysm/mss/fdbosses → `#beloong:is_disaster`,55 个 BWG 群系),Boss 竞技场在纯 BWG 群系下照常生成,无需处理。

## 实现

### 群系层 — `CloneParameterListMixin` + `DisasterBiomeSourceFactory`

`LevelUtils.initializeBiomes` 的 redirect 内分支:

- 天灾维度且 `disaster_biomes.enabled` → **不走 TB 初始化**,改为:
  1. 首选:取**主世界**来源的参数表(`LevelStem.OVERWORLD → ChunkGenerator → biomeSource`,经反射 `directDelegate()` 解包 LH 的 `InjectorBiomeSource`),其 `Either.left` 即"原版+BWG+RU 合并表";对它做白名单过滤。
  2. 回退(LH 缺失/未合并):共享预设原版点集(精确 ID 白名单)+ 直接遍历 `Regions.get(OVERWORLD)` 调 `Region#addBiomes` 采集 TB 区域点。
  3. 重建普通 `Climate.ParameterList`,经 `MultiNoiseBiomeSourceAccess.setParameters(Either.left(...))` 装回。
- 其它维度 → 与历史行为完全一致(克隆 + TB 初始化)。

普通(未初始化)参数表在 TB 的 `findValuePositional` 中自动退化为全表最近邻——与原版多噪声语义一致,河/海/洞的参数点区间因此不被区域噪声稀释。

**守卫**:收集结果为空(BWG/RU 缺失且额外白名单空)→ 放弃过滤保持原样;WARN 一次。

### 结构层 — `ChunkMapMixin` + `DisasterStructureSetLookup` + `DisasterStructureSetHolder`

`ChunkMap.<init>` 的 `createState` redirect(每维度一次):

- 仅天灾维度 + enabled → 把注册表查询包进 `DisasterStructureSetLookup`:
  - `listElements()` 不裁剪结构集(交集判定,写死);
  - 命中项按配置覆写 `RandomSpreadStructurePlacement` 的 spacing/separation(钳制保证 `separation < spacing`)与 frequency ∈ [0,1],`-1`/`-1.0` 表示保持原值;非随机点状放置(同心环等)保持原样并提示一次;
  - 覆写结果以 `DisasterStructureSetHolder`(`Holder.Reference` 子类,保住 `key()/kind()` 语义)承载,IdentityHashMap 缓存。

原版 `createForNormal` 只调用 `listElements()`,其余接口纯转发——包装面最小。

### 配置 — `Config.DisasterBiomes`(`beloong-server.toml` 新节)

```toml
[disaster_biomes]
enabled = true
allowedNamespaces = ["biomeswevegone", "regions_unexplored"]
allowedBiomes = []                        # 额外精确 ID(如没有 RU 时填原版水/洞穴)
structureSpacingOverride = -1             # -1 = 保持结构集原值
structureSeparationOverride = -1
structureFrequencyOverride = -1.0         # 0.0~1.0
```

### 结构层 — `ChunkMapMixin` + `DisasterStructureSetLookup` + `DisasterStructureSetHolder`

`ChunkMap.<init>` 的 `createState` redirect(每维度一次):

- 仅天灾维度 + enabled → 把注册表查询包进 `DisasterStructureSetLookup`:
  - `listElements()` 不裁剪结构集(交集判定,写死);
  - 命中项按配置覆写 `RandomSpreadStructurePlacement` 的 spacing/separation(钳制保证 `separation < spacing`)与 frequency ∈ [0,1],`-1`/`-1.0` 表示保持原值;非随机点状放置(同心环等)保持原样并提示一次;
  - 覆写结果以 `DisasterStructureSetHolder`(`Holder.Reference` 子类,保住 `key()/kind()` 语义)承载,IdentityHashMap 缓存。

原版 `createForNormal` 只调用 `listElements()`,其余接口纯转发——包装面最小。

**白名单为空 = 交集模式**:白名单未配置且无 placement 覆写时,直接透传注册表——结构集去留完全交由原版"结构群系标签 ∩ 维度群系集"判定。这与结构罗盘等按标签判定的一方使用同一条规则,避免"罗盘说会生成、实际不生成"的判定分叉。

根因记录:罗盘虚报的 69 个结构(dungeons_arise×26、biomeswevegone×19、dragonsurvival×7、legendary_monsters×6、totw_modded×4、iss/irons/eternal_starlight/iceandfire_dreadland 等)均为"结构群系标签 ∩ BWG/RU 群系非空",但其结构集不在 `disaster_set` 白名单内;且 RU 的注入器硬编码目标为 `Level.OVERWORLD`(bytecode 级证据:`BiomeTarget`/`RULithostitched` 均 `getstatic Level.OVERWORLD`),天灾维度的注入器集合为空——两种判定天然分叉。交集模式下双方共用原版判定,分叉消失;需要额外结构时再显式配置白名单。

```
[beloong] disaster_biomes: built parameter list from 'overworld-merged|shared+TB' with NNNN biome points, namespaces=[...], ...
[beloong] disaster_biomes: structure-set filter active, whitelist=[beloong:disaster_set], spacingOverride=..., separationOverride=..., frequencyOverride=...
[beloong] disaster_biomes: 'xxx' placement rewritten: spacing a→b, separation c→d, frequency e→f   (仅当覆写开启)
```

断言:`namespaces` 含全部期望命名空间;天灾维度无原版群系;有 RU 的海洋/河流/洞穴(`hyacinth_deeps`/`cold_river`/`bioshroom_caves` 等);Boss 竞技场照常;主世界群系分布不变。

## 已知边界

- 老区块已固化的群系/结构不回填,需新区块验证。
- 装不下 Lithostitched 时走 `shared+TB` 回退,RU 群系缺失,水体洞穴需靠 `allowedBiomes` 手动补原版 ID。
- 同心环放置(如原版要塞)不参与 spacing/frequency 覆写(保持原值,白名单过滤仍生效)。


## 追记(20:51 实测定稿): 构造期表不全 → 延迟并集交换

实测时序(LH 1.8.0b4 + TB 4.1.0.8):
- 天灾维度来源构造期,共享预设表仅原版 7593 点(`namespaces=[minecraft]`)——LH/TB 都还没写;
- 主世界侧,LH 的 `InjectorBiomeSource`(非 MNBS 子类)以 `Either.right(preset)` 持共享表,
  其 RU 主群系走 LH 内部 Region 路由(运行期,不进参数表),仅 2 条 AddPoints 深部洞穴
  (`redstone_caves`/`prismachasm`,depth 全区间)被就地写入共享表 → 主世界表 7595 点;
- 故构造期收集永远缺 RU 主群系,延迟交换改为**并集**:天灾现有表(构造期 TB 采集的 8976 BWG 点)
  ∪ 主世界合并表过滤后新增点(2 条 RU 深洞) = 8978 点,`namespaces=[biomeswevegone, regions_unexplored]`,
  装回后 `refreshFeaturesPerStep()` 重建特性步骤。

ET(#c:is_cold → #biomeswevegone:climate/cold → 17 个 BWG 冷群系,全部在天灾维度)等结构
在**交集模式**(`structureSetWhitelist = []`)下有宿主群系,罗盘判定与实际生成一致。
