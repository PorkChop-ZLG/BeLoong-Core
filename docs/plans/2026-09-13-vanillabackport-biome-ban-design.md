# 天灾维度「VB 群系封堵」+ 本模组日志英文化 设计文档

**Date:** 2026-09-13
**Status:** Approved（用户已逐节确认 Phase 4 的架构 / 组件 / 数据流 / 异常安全 / 验证方法）
**Approach:** **方案 A** —— 通用 TerraBlender region 装饰器（注入 `Regions.get`），替代 per-mod region mixin
**范围:** 只出设计，**不改任何代码**（本文件与 `memory/` 除外）

---

## 1. Problem Statement

### 1.1 问题一：VanillaBackport 的两个 `minecraft:` 群系泄漏进天灾维度

整合包加入 [VanillaBackport](https://github.com/ItsBlackGear/VanillaBackport)（下称 **VB**，整合包内 `VanillaBackport-neoforge-1.21.1-1.1.7.10.jar`）后，天灾维度出现**原版命名空间群系**，违反总设计 **决策 24**「天灾维度的群系绝不能是原版群系本身」。

**实测证据（`D:\BeLoong\.minecraft\versions\BeLoong\logs`，日志为 GBK，需按 CP936 读取）**

```
[13:17:30.266] ERROR [BeLoong] 天灾群系替换：minecraft:pale_garden 在映射表中没有对应项，该群系将保留原版   ×10
[13:17:30.268] ERROR [BeLoong] 天灾群系替换：minecraft:sulfur_caves 在映射表中没有对应项，该群系将保留原版  ×1
[13:17:30.268] INFO  [BeLoong] 天灾维度群系替换：参数点 7604 个 = 替换 7593 + 非原版保留 0 + 未能求解 11（替换生效）
[13:17:30.386] INFO  [BeLoong] 天灾 region 树账目：
    vanillabackport:overworld：参数点 39，DEFERRED 28，minecraft: 群系 2 种 [pale_garden, sulfur_caves]
    biomeswevegone:region_0/1/2：参数点 7604，minecraft: 群系 2 种 [pale_garden, sulfur_caves]
[13:17:54.941] INFO  [BeLoong] 各维度 possibleBiomes 账目：
    beloong:disaster：共 60 种 {beloong=5, biomeswevegone=55}     ← 查询层"看起来"干净
```

**存档级取证**（解析 `saves/天灾出生点结构清理/dimensions/beloong/disaster/region/*.mca`，每个区块逐 section 读群系调色板）：

| region | 已生成区块 | `minecraft:` 残留 |
|---|---|---|
| `r.-1.0.mca` | 462（full=110） | **`minecraft:sulfur_caves` 出现在 55 个区块，其中 51 个 `status=minecraft:full`** |
| `r.-1.-1` / `r.0.-1` / `r.0.0` | 各 440~484 | 只有 `minecraft:plains`，且**全部**是 `status=minecraft:structure_starts`（未生成完的占位群系，**不是泄漏**） |

> 结论：泄漏是真实的、已落到地形上的（不是纸面推断），且属决策 19 判定不可接受的「第三种状态」——生成层产出、查询层隐藏（`/locate` 与自然罗盘看不到）。

**本仓库 dev 环境 A/B 复现（2026-09-13，同一台机器 / 同一套模组 / 唯一变量 = VB 的有无）**

| 账目 | 无 VB（`run/logs/debug-1.log`，存档 `天灾传送2`） | 有 VB（`run/logs/latest.log` + `debug.log`，存档 `天灾传送3`） |
|---|---|---|
| 参数点汇总 | `7593 = 替换 7593 + 保留 0 + **未能求解 0**` | `**7604** = 替换 7593 + 保留 0 + **未能求解 11**` |
| 报错明细 | 无 | `pale_garden` ×10、`sulfur_caves` ×1「在映射表中没有对应项」 |
| BWG 三棵 region 树 | `minecraft: 群系 0 种 []` | **`minecraft: 群系 2 种 [pale_garden, sulfur_caves]`** |
| VB 的 region | 不存在 | **`vanillabackport:overworld：参数点 39 / DEFERRED 28 / minecraft: 2 种`** |
| 主世界 `possibleBiomes` | 113 种（minecraft=**53**） | **115 种（minecraft=55）** |
| 天灾 `possibleBiomes` | 60 种（beloong 5 + BWG 55） | 60 种（同左）→ 查询层仍干净 |
| 天灾地形取证 | 4 个 region **全部**只有 `structure_starts` 的 `plains` 占位 ⇒ **零真泄漏** | **68 个区块含 `sulfur_caves`，其中 15 个 `minecraft:full`**（另 biomes 17 / carvers 14 / initialize_light 12 / surface 3 / noise 7） |
| 首次进入落点耗时 | `waited=40 / 67 / 130 tick`（三处首访） | `waited=74 tick` → **落在无 VB 区间内，VB 与耗时无关** |

> 这份 A/B 比整合包日志更强：同环境、同模组集，唯一变量是 VB 的有无。
> 另注：`minecraft:plains` 在两侧都大量出现在 `status=minecraft:structure_starts` 的未完成区块里（区块默认群系占位），**不是泄漏**；只有走到群系阶段及之后的区块才算数。
> 存档名校正：带 VB 的测试存档是 **`天灾传送3`**（14:45 创建），`天灾传送2` 的最后一次会话（12:46–13:03）**没有** VB。

**根因：VB 把这两个群系注入了两条路径，我们只覆盖了其中一条**

| # | 注入路径 | VB 的做法（源码） | 本模组现有覆盖 |
|---|---|---|---|
| ① | **共享原版参数表**（index-0 兜底树 + 主世界 preset 本身） | `common/.../integrations/worldgen/BiomeGeneration.java:46-52` 调 `BiomePlacement.registerBiomePlacements` 登记 11 个参数点（`pale_garden` 10 + `sulfur_caves` 1）；实际写入点是 **Platform** 的 `common/.../core/mixin/common/OverworldBiomeBuilderMixin.java:17-30`——在**原版 `OverworldBiomeBuilder.addBiomes` 的 TAIL** 注入并把监听器的点喂进 mapper，使 `minecraft:overworld` preset 由 **7593 → 7604** 点（与日志完全吻合）。开关为 VB 自身配置 `hasPaleGarden` / `hasSulfurCaves`（`CommonConfig.java:90,182`，默认 true） | ✅ 被 `CloneParameterListMixin` → `filter()` 看到，但 `DisasterBiomeMapping` 无对应项 ⇒ 走「保留原版 + 记 ERROR」（决策 19 的降级分支） |
| ② | **VB 自己的 TerraBlender region** | `common/.../integrations/compat/terrablender/OverworldRegion.java:17-33`（`extends Region`），注册于 `neoforge/.../VanillaBackportTerrablender.java`：`Regions.register(new OverworldRegion(..., RegionType.OVERWORLD, 5))`，再吐一遍同样的点 | ❌ **完全没有覆盖**：第三条注入路径 `BwgRegionBiomeRewriteMixin` 的注入目标是 `@Mixin(targets = "net.potionstudios.biomeswevegone...BWGTerraBlenderRegion")`（`mixin/BwgRegionBiomeRewriteMixin.java:84`）——只管 BWG 自己的 region 类 |
| ③ | 查询层 | — | ✅ `PossibleBiomesFilterMixin` 按命名空间过滤，已干净（这正是"地形有、`/locate` 无"的原因） |

**因此：只补映射表治不好本泄漏**——必须同时覆盖路径②。这正是总设计 §九维护提示里警告过的坑（「不要只改 `DisasterBiomeMapping`…」）。

### 1.2 问题二：本模组日志中文在日志文件里是乱码

日志按平台 ANSI（本机 GBK）落盘，按 UTF-8 读取即乱码（连时间戳都是本地化格式 `139月2026`）。用户要求：**BeLoong-Core 的全部 debug/日志消息改为英文**（ASCII），消除歧义。

**清点结果**：模组共 **76 处 `LOGGER.*`**，其中**含中文 33 处**，分布：

| 文件 | 含中文日志数 |
|---|---|
| `worldgen/DisasterBiomeSubstitution.java` | 14 |
| `block/DisasterPortalBlock.java` | 7 |
| `worldgen/BeloongSurfaceRules.java` | 4 |
| `transport/DimensionTransportHandler.java` | 2 |
| `BeLoongCoreClient.java` | 2 |
| `mixin/CloneParameterListMixin.java` | 1 |
| `mixin/PossibleBiomesFilterMixin.java` | 1 |
| `mixin/BwgRegionBiomeRewriteMixin.java` | 1 |
| `BeLoongCore.java` | 1 |

（未发现 `System.out` / `printStackTrace` / 中文异常消息。）

**连带面**：4 份文档引用了这些中文日志原文，共 **23 处**需同步：
`docs/天灾维度总设计.md`（5）、`docs/天灾维度群系剔除-两次尝试复盘.md`（1）、`docs/plans/2026-09-11-disaster-phase2-implementation.md`（15）、`docs/plans/2026-09-11-disaster-vanilla-biome-removal-design.md`（2）。

### 1.3 补充问题：VB 的 TerraBlender 兼容，以及本模组的 `overworld_regions` 标签会不会让两个群系无法在主世界生成？

**问**：VB 是不是有专门添加 TerraBlender 的兼容？本模组用 TerraBlender 的标签使主世界不被初始化，这是否会导致 VB 的两个群系无法在主世界生成？

**答**：VB **确有**专门的 TerraBlender 兼容，但**它和"两个群系在主世界生成"这件事无关**——主世界照常生成这两个群系。逐层拆开：

1. **VB 的 TerraBlender 兼容（确实存在，但受自身配置门控）**
   - `common/.../integrations/compat/terrablender/OverworldRegion.java:17-33`：`extends terrablender.api.Region`，`addBiomes` 里按 VB 配置追加 `BiomeGeneration.PALE_GARDEN` / `SULFUR_CAVES`；
   - 注册点 `neoforge/.../core/neoforge/VanillaBackportTerrablender.java:11-14`：
     `onTerraBlenderInitialized()` → `if (hasPaleGarden || hasSulfurCaves) Regions.register(new OverworldRegion(VanillaBackport.resource("overworld"), RegionType.OVERWORLD, 5))`。

2. **本模组的标签只管"哪些维度做 TB 初始化"，管不到参数表本身**
   - `src/main/resources/data/terrablender/tags/dimension_type/overworld_regions.json` = `{"replace": true, "values": ["beloong:disaster"]}`；
   - TerraBlender 的 `LevelUtils.getRegionTypeForDimension()` 读该 tag（`LevelUtils.java:70-75`）；主世界的维度类型不在其中 ⇒ `LevelUtils.initializeBiomes` 在 `:97-98` **提前 return**——既不建 region 树，也不调用 `appendDeferredBiomesList`（这正是设计里"主世界退出 TerraBlender 管理"的机制）。
   - 但这条早退**完全不修改 `minecraft:overworld` 的多噪声参数表（preset）**。

3. **VB 的两个群系走的是与 TB 无关的第二条路径**
   Platform 的 `common/.../core/mixin/common/OverworldBiomeBuilderMixin.java:17-30` 在**原版 `OverworldBiomeBuilder.addBiomes` 的 TAIL** 注入，把 `BiomePlacement.LISTENERS` 里登记的参数点直接喂给 mapper ⇒ 11 个点进入 **`minecraft:overworld` preset 本身**。主世界用的就是这个 preset，且主世界不做 TB 初始化 ⇒ **两个群系在主世界照常生成**。
   （旁注：TB 的 `DefaultOverworldRegion.addBiomes` / `RegionUtils` 也调用原版 `OverworldBiomeBuilder.addBiomes`，所以监听器在这些上下文里会被再调用一次——这正是账目里 index-0 行显示 55 种的原因。）

4. **实机证据（存档 `天灾传送3`，含 VB）**
   - 主世界 `possibleBiomes`：113 种（minecraft=53）→ **115 种（minecraft=55）**；
   - 主世界地形取证（`saves/天灾传送3/region/`）：`r.-1.-1.mca` **136 个区块**、`r.-1.0.mca` **17 个区块** 含 `minecraft:sulfur_caves` ⇒ 主世界**真的在生成**它。

**对本设计的直接结论**
- **不能**用"改 tag"解决天灾维度的泄漏：标签只决定 TB 初始化范围，管不到 preset 层注入；
- **也不能**用 `Regions.remove(...)` 去"摘掉 VB 的 region"：那既治不了 preset 层（主世界与天灾的 index-0 树都还有那 11 个点），又会**全局**影响其它走 TB 的维度；
- 正确的层就是本设计的**方案 A**：只在"天灾维度那一份被 clone 的参数表 + 该维度的 region 树"上改写，维度作用域由 `isFilteringTargetBiomeList()` 保证；
- **回归基线**：修复后**主世界必须仍是 115 种**（VB 的两个群系继续在主世界生成），天灾必须为 **0 个 `minecraft:` 群系**。

---

## 2. Design

### 2.1 架构（W2 群系封堵）

维持既有「生成层 / 查询层」两层分离与决策 21 的作用域维度判定，只把**生成层的 region 注入**从 per-mod 改为**通用装饰器**：

```
                    ┌─ ① index-0 兜底树 ── CloneParameterListMixin → filter()        （不变）
天灾维度群系替换 ────┼─ ② region 树 ──────── RegionBiomeRewriter（通用装饰器）      （新；替换 BWG 专用 mixin）
                    └─ ③ 查询层 ────────── PossibleBiomesFilterMixin             （不变）
                                 ↑
              三者共用唯一改写入口 DisasterBiomeSubstitution.rewriteKey（口径不漂移）
              维度守卫 = 作用域标志 isFilteringTargetBiomeList()（决策 21）
              群系封堵 = DisasterBiomeMapping +2 项（数据层；遵循决策 19「无兜底」）
```

**为什么注入 `Regions.get(RegionType)`**：它是 TerraBlender 把 region 交给消费方的**唯一出口**，TB 内部全部调用点都经过它：

| 调用点 | 用途 | 装饰器生效后的语义 |
|---|---|---|
| `terrablender/mixin/MixinParameterList.java:74` | 建每棵 region 的 RTree（**实际生成来源**） | 用改写后的群系建树 ⇒ 路径②被封堵 |
| `terrablender/util/LevelUtils.java:117` | 建 `appendDeferredBiomesList` 的追加列表 | 改写后再进集合（见 §2.4 副作用 2） |
| `terrablender/worldgen/noise/InitialLayer.java:54-59` | uniqueness 分层探测（只判"该 region 有没有输出"） | 装饰器照样 `accept` ⇒ 行为不变 |
| 本模组 `DisasterBiomeSubstitution.logRegionTreeBiomes` | region 树账目（自己调用 `addBiomes`） | 自动读到改写后结果 ⇒ 账目保持诚实 |

**安全前提（已逐条核实）**：TB 内部对 `Region` **没有强转、没有身份比较**（只有 `getType()`／`getName()`／`getWeight()` 与 `addBiomes` 调用，见 `Regions.java:107-121`、`InitialLayer.java:59`）⇒ 装饰器改变实例身份**无害**；TB 已是 required 依赖且有 `compileOnly` ⇒ 可直接 `@Mixin(Regions.class)`，无需 `@Pseudo`。

**为什么不用另外两种"更省事"的做法**（详见 §1.3）：
- **改 `overworld_regions` tag**：该 tag 只决定"哪些维度做 TB 初始化"（`LevelUtils.getRegionTypeForDimension` → `:97-98` 早退），**碰不到 preset 层的 11 个点**，且总设计明令不得改它（一票否决开关）；
- **`Regions.remove(OVERWORLD, vanillabackport:overworld)`**：治不了 preset 层（主世界与天灾的 index-0 树里那 11 个点仍在），且会**全局**影响其它走 TB 的维度，属"伸手改别人的注册"。

### 2.2 组件

| 组件 | 动作 | 要点 |
|---|---|---|
| `worldgen/RegionBiomeRewriter` | **新增** | `extends terrablender.api.Region`；构造转发 `(name, type, weight)`；`getName/getType/getWeight` 委托被包 region；`addBiomes` 包住调用方 mapper，逐对 `rewriteKey(registry, key, false)`，单对异常原样放行 |
| `mixin/RegionsGetMixin` | **新增** | `@Mixin(value = Regions.class, remap = false)`；静态 `@Inject(method = "get", at = @At("RETURN"), cancellable = true)`：把返回列表逐项包装（已包装则跳过，幂等）；整体 `try/catch`，失败原样返回 |
| `mixin/BwgRegionBiomeRewriteMixin` | **删除** | 被装饰器完全覆盖（BWG 也经 `Regions.register` 注册）；同步删 `beloong.mixins.json` 条目 |
| `worldgen/DisasterBiomeMapping` | **+2 项** | `"pale_garden" -> "biomeswevegone:weeping_witch_forest"`（与 `dark_forest` 同目标、同气候格：原版 pale garden 本就是深色森林变体）；`"sulfur_caves" -> "beloong:caves"`（与 `lush_caves`/`dripstone_caves`/`deep_dark` 同目标：BWG 没有任何 `depth > 0` 群系，第二阶段已把洞穴统一到自制 `beloong:caves`，且它有自制地表规则） |
| `worldgen/DisasterBiomeSubstitution` | 改 | 14 处中文日志→英文；类 javadoc 的「三条注入路径」改为装饰器描述；`logRegionTreeBiomes` 里 index-0 行的注脚改写（见 §2.4 副作用 1） |
| 其余 7 个文件 | 改 | 共 19 处中文日志→英文（全模组 33 处） |
| 文档 | 改 | 总设计（§4.x + 决策条目 + §九维护提示）、phase2 逐项表 +2 行与选点依据、4 份文档 23 处日志原文同步 |

### 2.3 数据流

```
【启动期】其他模组（BWG / VB）→ Regions.register(...)   // 注册表：BWGTerraBlenderRegion ×3、vanillabackport:overworld ×1

【每个 level stem 初始化】LevelUtils.initializeBiomes
 HEAD   beloong$beginTargetDimension       levelKey == beloong:disaster ? 置作用域标志 : 不动
 ├─ 重定向：clone 共享 ParameterList
 │     filter(clone.values)                改写 index-0 兜底树的 7604 个点（含 VB 的 11 个）
 │     logRegionTreeBiomes(...)            账目：读到的已是改写后结果
 ├─ initializeForTerraBlender(...)
 │     LayeredNoiseUtil.initialUniqueness  → InitialLayer → Regions.get() →【装饰器】addBiomes（只判有无输出）
 │     for (Region r : Regions.get())      →【装饰器】r.addBiomes(registry, TB 的 consumer)
 │                                          ⇒ 每棵 region 树（含 VB 的）用改写后的群系建 RTree
 └─ LevelUtils:117  Region.addBiomes        → 追加列表 → PossibleBiomesFilterMixin 再按命名空间兜底
 RETURN beloong$endTargetDimension          清作用域标志
```

两条不变式：**改写早于 `Climate.RTree.create`**（装饰器在 `addBiomes` 内部，天然满足）；**只有天灾那次初始化会改写**（作用域标志，主世界/下界/末地原样透传）。

### 2.4 异常安全（决策 23 无逃生舱 ⇒ 必需项）

| 失败模式 | 设计响应 |
|---|---|
| `filter()` 抛异常 | 不变：记 ERROR → 继续 TB 原流程（天灾退化保留原版，绝不卡启动） |
| 装饰器**单对**改写抛异常 | 该对原样放行，不中断 `addBiomes`；残留由账目聚合上报 |
| 映射目标不可用（未注册 / 被 BWG 配置禁用） | `isUsable` 失败 ⇒ 保留原版 + ERROR（决策 19「无兜底」）。本次两目标均有实证：`weeping_witch_forest` 现已被 `dark_forest` 使用且账目无其报错（⇒ 默认启用）；`beloong:caves` 是自制群系，由「自制群系注册情况」账目保证 |
| `Regions.get` 签名/形状随 TB 升级变化 | 注入体整体 try/catch ⇒ 原样返回（退化为未装饰）；且 `beloong.mixins.json` 的 `injectors.defaultRequire = 1` 使**找不到注入目标时直接启动报错**（响亮失败，不静默泄漏） |
| VB/BWG 改类名或换实现 | 装饰器不依赖任何第三方 region 类名 ⇒ **天然免疫**（旧写法在对方重构后会静默失效，这是删除它的额外收益） |
| 重复包装（`Regions.get` 多次调用） | 幂等：已包装即跳过；`rewriteKey` 对已是 BWG/beloong 的键是 no-op |

### 2.5 副作用与验证方法

**副作用 1**：账目里 **index 0 那一行**将由「`minecraft:` 群系 55 种」变为「0 种」——因为 `DefaultOverworldRegion` 现在也会被装饰（BWG 那条 mixin 以前碰不到它）。文档中"index 0 其树来自被改写的 values，非实际来源"的注脚需相应改写。

**副作用 2**：追加列表（`LevelUtils:117`）里的原版群系以前被查询层**丢掉**，现在会先改写成 BWG/beloong 群系再进集合。由于这些目标大多已在集合中，`LinkedHashSet` 不会改序 ⇒ **预计 `possibleBiomes` 仍 60 种且顺序不变**（顺序关系到 `FeatureSorter` 全局编号，决策 22）——必须实测确认。

**修复后账目预期值**

| 账目 | 现状 | 修复后预期 |
|---|---|---|
| 参数点汇总 | 7604 = 替换 7593 + 保留 0 + **未能求解 11** | 7604 = 替换 **7604** + 保留 0 + **未能求解 0** |
| region 树（`vanillabackport:overworld`、`biomeswevegone:region_0/1/2`） | `minecraft: 群系 2 种 [pale_garden, sulfur_caves]` | **0 种 []** |
| region 树（index 0 行） | 55 种 | **0 种**（副作用 1） |
| `beloong:disaster` possibleBiomes | 60 种 {beloong=5, bwg=55} | 仍 60 种、顺序不变（副作用 2，实测） |
| 主世界 possibleBiomes | 115 种 {minecraft=55, RU=60} | **不变**（VB 两个群系必须保留在主世界） |
| 下界 / 末地 | 9 / 5 种 | 不变 |

**验证步骤**

1. `gradlew.bat build` 通过（构建门禁）；
2. 启动账目核对：`unsolved 0`；全部 region 树 `minecraft:` 为空集；`possibleBiomes` 数量与顺序与修复前一致；主世界/下界/末地不变；
3. **存档级取证**（`build/probe/RegionBiomeProbe.java`，只读探针）：在**新区域或新世界**生成后扫描，须 `含 minecraft: 群系的区块数 = 0`；
4. 反例校验（**主世界必须保留 VB 的两个群系**）：主世界 `possibleBiomes` 仍为 **115 种**；`locate biome minecraft:pale_garden` 仍可命中；主世界 region 取证仍能扫到 `minecraft:sulfur_caves`（基线：`天灾传送3` 的 `r.-1.-1.mca` 136 个区块 / `r.-1.0.mca` 17 个区块）；
5. 日志语言：`BeLoong-Core` 相关行 CJK 字符数 = 0。

---

## 3. Decisions Made

| # | 决策 | 理由 |
|---|---|---|
| D1 | **用通用 region 装饰器**（注入 `Regions.get`），而非再加一条 VB 专用 mixin | per-mod mixin 正是本次漏检的**根因**（`BwgRegionBiomeRewriteMixin` 只认 BWG 的类）；装饰器一次覆盖所有现有/未来 region，并删除一个 mixin；已核实 TB 对 `Region` 无身份依赖 |
| D2 | 两个群系采用**改写**，不是删参数点、也不引入兜底群系 | 删点会让 `RTree.search` 落到"最近的其他点"，结果不可预测；决策 19 明确「不用兜底群系，不可用时保留原版 + ERROR」 |
| D3 | 映射目标：`pale_garden → biomeswevegone:weeping_witch_forest`、`sulfur_caves → beloong:caves` | 前者与 `dark_forest` 同气候格（原版 pale garden 即深色森林变体）且已被使用 ⇒ 默认启用状态已实证；后者沿用"所有洞穴合并到自制 `beloong:caves`"的第二阶段口径，且它有自制地表规则 |
| D4 | **不新增任何配置项** | 决策 17/19：`enabled` 类开关只覆盖生成层、覆盖不到查询层，会制造新的「第三种状态」 |
| D5 | 日志英文化**只改 `LOGGER.*` 文本** | 注释/javadoc 保持中文（项目约定）；玩家可见文案走 lang 文件、不动；保留 `[BeLoong]` / `[DisasterPortal:*]` ASCII 锚点，便于脚本判读 |
| D6 | **旧区块不追溯** | 已生成区块的群系已写入存档，模组不做区块重写；文档须写明"本修复只对新生成区块生效" |
| D7 | **不用"改 tag"或"摘除 VB 的 region"来治理**（两者都被评估并否决） | 二者都碰不到 preset 层注入：VB 是经 **Platform 的 `OverworldBiomeBuilder` mixin** 直接改原版参数表的（§1.3）；`overworld_regions` tag 是总设计明令的一票否决开关，`Regions.remove` 则有全局副作用。正确的层是"天灾维度那份被 clone 的参数表 + 该维度的 region 树"。**推论：主世界必须继续生成这两个群系（115 种基线）** |

---

## 4. Non-Goals

- **不处理"首次进入天灾维度耗时过长 / 传送超时"**（用户明示暂不处理；实测本包首次生成 312–601 tick，与本设计无关）；
- **不改** `data/terrablender/tags/dimension_type/overworld_regions.json`（总设计明令：它是一票否决开关，改动等于同时动主世界与天灾）；
- 不为 region 注入引入配置开关 / 兜底群系（决策 19）；
- 不改 VB 两个群系在**主世界**的生成行为（必须保留；VB 自身的 `hasPaleGarden`/`hasSulfurCaves` 配置**不动**）；
- 不做 Mixin 到 VB 的类（采纳方案 A 后无此需要）；
- 不动玩家可见文案与 lang 文件；
- 不把日志文件整体改成 UTF-8（属启动参数问题，见下）。

> 附注（可选、不在本设计范围）：日志文件仍是平台 ANSI，其它模组的中文照样乱码；若要彻底 UTF-8，可在启动参数加 `-Dfile.encoding=UTF-8 -Duser.language=en`（后者会同时消除时间戳里的 `月`）。

---

## 5. 实施顺序（供 planning skill）

1. `DisasterBiomeMapping` +2 项；
2. 新增 `worldgen/RegionBiomeRewriter` + `mixin/RegionsGetMixin`，改 `beloong.mixins.json`；
3. 删除 `mixin/BwgRegionBiomeRewriteMixin` 及其 mixins.json 条目；
4. 33 处中文日志英文化（8 个文件）；
5. 文档同步：总设计（§4.x / 决策 / §九）、phase2 逐项表 +2 行、`天灾维度群系剔除-两次尝试复盘.md`、`plans/2026-09-11-disaster-phase2-implementation.md`、`plans/2026-09-11-disaster-vanilla-biome-removal-design.md`（共 23 处日志原文）；
6. 验证：build → 启动账目核对 → 新区域存档取证 → 主世界反例校验 → 日志 CJK 计数为 0。

## 6. Next Steps

- 由 `planning` skill 落成实施计划（任务拆分 + 每步验收标准）；
- 实施前复读总设计 §4.4/§4.5/§九 与 `docs/plans/2026-09-11-disaster-phase2-biome-table.md`，确保与既有决策一致。
