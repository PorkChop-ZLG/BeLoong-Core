# 天灾维度群系剔除 —— 代码审查报告

**Date:** 2026-09-11
**审查范围:** `disaster2` 分支 `6a77c0e~1..74551bb`（3 个提交，+2302/−38 行）
**审查方式:** 只读静态审查 + 参考源码/字节码交叉验证。**未修改任何代码。**
**审查组织:** 4 个并行子代理，按领域分工

| 组 | 领域 | 报告来源 |
|---|---|---|
| A | Mixin 注入正确性 | 独立子代理 |
| C | 映射表与白名单 | 独立子代理 |
| D | 配置开关覆盖与一致性 | 独立子代理 |
| E | 性能开销 | 独立子代理 |

> 本报告由委托方汇总去重、统一编号。每条问题保留**原始编号**（`A-S1-1` 形式）以便追溯。
> **多组独立命中的问题**在标题后标注发现者——这通常意味着置信度更高。

---

## 零、总体结论

**功能是正确的**：生成层与查询层两条路径均已实测验证通过（`possibleBiomes` 109 → 70，黑名单残留 0；生成层内存读数零黑名单）。

**但存在 2 条 S1 级缺陷**，都不影响默认路径的成功验证结果，却会在特定条件下造成静默错误：

1. 配置开关 `enabled=false` **不能**回到旧行为，反而制造"生成在、查不到、结构与地物消失"的第三种状态
2. `PossibleBiomesFilterMixin` 让 TerraBlender **事实上成为硬依赖**，而 `mods.toml` 声明它为 `optional`

**性能无问题**：无任何改动落在区块生成或游戏刻路径上。

### 问题统计（去重后）

| 级别 | 数量 | 说明 |
|---|---|---|
| **S1 阻断级** | **2** | 配置语义失效、依赖契约不一致 |
| **S2 严重** | **7** | 映射表与白名单问题、维度判据脆弱、确定性丢失、注入顺序依赖 |
| **S3 中等** | **6** | 健壮性、可观测性、性能 |
| **S4 轻微** | **6** | 注释错误、死代码、可复现性 |
| **S5 提示** | **5** | 观察与建议 |

---

## 一、S1 阻断级

### S1-1 关闭 `disaster_biomes.enabled` 不回到旧行为，反而制造"生成在、查不到、结构与地物消失"

**发现者：D 组（D-S1-1）与 A 组（A-S1-2）独立命中**

- **位置**：`mixin/PossibleBiomesFilterMixin.java:78-90`（尤其 `:81` 的 `isBlocklisted` 调用）；`worldgen/DisasterBiomeSubstitution.java:209-211`；`Config.java:119-125`
- **问题**：`filter()` 在 `:209` 读了开关（关闭时 `return original`，生成层正确回退）；但查询层的 `PossibleBiomesFilterMixin` **全文无 `Config` 引用**，其过滤只依赖 `isBlocklisted`（`:150-152`），而该方法不读配置。于是关掉开关后：
  - 生成层：原版 53 + BWG（正确回退）
  - 查询层：`possibleBiomes` 只剩 14 白名单 + BWG，39 个被隐藏
- **影响（比"开关失效"更严重）**：过滤后的集合会进入两条下游链路，造成**静默**丢失：
  - `ChunkGeneratorStructureState.java:61-67` `hasBiomesForStructureSet` 用它决定结构集是否保留 → 仅由那 39 个群系支撑的结构集（村庄、前哨等）**整条从 `possibleStructureSets` 消失**
  - `ChunkGenerator.java:339` `set.retainAll(this.biomeSource.possibleBiomes())` 过滤地物 → 相关地物被剔除
  这是"已核实的静默失效"，不是理论风险。
- **建议修法**：查询层与生成层共用同一"是否生效"判定。最小改动是在 `PossibleBiomesFilterMixin` 处理分支最前面加 `if (!Config.DisasterBiomes.enabled.get()) return;`；**更稳的做法是按"本次实际是否替换了 ≥1 项"判定**——因为降级场景（见 S2-1）同样会造成左右不一致。同时给 `enabled=false` 与"全量降级"各打一条一次性 INFO/WARN。

### S1-2 `PossibleBiomesFilterMixin` 让 TerraBlender 事实上成为必需依赖，但 `mods.toml` 声明它是 optional

**发现者：A 组（A-S1-1）与 D 组（D-S2-2）独立命中**

- **位置**：`mixin/PossibleBiomesFilterMixin.java:55`；`src/main/templates/META-INF/neoforge.mods.toml`；`src/main/resources/beloong.mixins.json:2,48-50`
- **问题**：注入目标 `appendDeferredBiomesList` **不存在于原版 `BiomeSource`**（已用 `javap -p dea` 核实成员表无此方法，mappings 段也没有），它只由 TerraBlender 的 `MixinBiomeSource:46` 注入。TB 缺席时该 `@Inject` 找不到目标方法 → `beloong.mixins.json` 顶层 `"required": true` + `"defaultRequire": 1` → Mixin 应用失败即**启动崩溃**。
  而 `mods.toml` 里 `terrablender` 与 `biomeswevegone` 都声明为 `type="optional"`。
  姊妹类 `CloneParameterListMixin` 有 `@Pseudo` 保护，所以总设计文档「TB 缺席时静默失效」这句话**只对它成立**。
- **影响**：按本模组自己声明的依赖契约卸掉 TB，会从"功能失效"变成"无法启动"。
- **建议修法**：三选一 —— (1) `mods.toml` 把 `terrablender` 改为 `required`；(2) 给 `beloong.mixins.json` 加 `"plugin"` 指向 `IMixinConfigPlugin`，在 `shouldApplyMixin` 里对 `PossibleBiomesFilterMixin` 返回 `ModList.get().isLoaded("terrablender")`；(3) 拆到独立的 `beloong.terrablender.mixins.json`，仅 TB 存在时注册。**不要加 `@Pseudo`**——目标类存在，`@Pseudo` 解决不了"方法不存在"。

---

## 二、S2 严重

### S2-1 `fallbackBiome` 可指向黑名单原版群系，绕过白名单约束

**发现者：D 组（D-S2-1）**

- **位置**：`DisasterBiomeSubstitution.java:361-376`（`resolveFallback`）、`:308-313` + `:330-336`；`Config.java:127-131`
- **问题**：`fallbackBiome = "minecraft:plains"` 能通过校验器（只 `tryParse != null`）与 `resolveFallback`（`containsKey` + `isBwgEnabled` —— 后者对**非 BWG 命名空间直接返回 `true`**）。于是任何"映射未覆盖"或"目标被禁用"的参数点会把黑名单群系塞回天灾维度。`isBlocklisted` 在兜底路径上**从未被调用**。
- **触发路径**：① 有人关掉某个映射目标（见 S1-2 的降级连锁）；② 出现映射表未收录的 `minecraft:` 群系——`DisasterBiomeMapping:116-119` 明确为此保留了 `default -> null` 分支。
- **影响**：本该剔除的群系经兜底路径重进天灾维度，功能声明失效，且只有逐点 WARN。
- **建议修法**：`resolveFallback` 增加 `if (isBlocklisted(loc)) { ERROR(...); return null; }`；配置校验器同步拒绝 `minecraft:` 且非白名单的值。

### S2-2 干净部署上 `grove` 会静默降级为 `prairie`（积雪山坡变温带草原）

**发现者：C 组（C-S1-1）**

- **位置**：`DisasterBiomeMapping.java:79`；BWG 侧 `BWGWorldGenConfig.java:32`；`.gitignore:36`
- **问题**：BWG 的 `getDefaultBiomes()` 里有一行**硬编码** `enabledBiomes.replace(BWGBiomes.ERODED_BOREALIS.location(), false)` —— BWG **默认禁用 `eroded_borealis`**。当前工作区之所以能用，只是因为 `run/config/biomeswevegone/world_generation.json:25` 被改成了 `true`。
  但该文件位于 **`.gitignore:36` 忽略的 `run/`**（`git ls-files run/` 为空），且 `src/` 下不存在任何 `world_generation.json`。
- **影响**：**任何干净部署**（服务器/玩家客户端）上 `eroded_borealis: false` → `isUsable()` 判为不可用 → `resolveTarget()` 降级到 `fallbackBiome`（默认 `biomeswevegone:prairie`）。`grove` 占 **312 个参数点**，雪坡上出现温带草原。
  更严重的是它**触发降级连锁**：若其它目标也被禁用，会演变成 S1-1 描述的"生成层 no-op + 查询层仍过滤"。
- **建议修法**：三选一 —— (a) `case "grove"` 改用 BWG 默认启用的寒带群系（`howling_peaks` d=0.109 或 `frosted_taiga` d=0.200，均优于现状 0.360）；(b) 确保目标表只使用 BWG 默认启用的群系；(c) 若坚持用 `eroded_borealis`，把 `world_generation.json` 作为 `defaultconfigs` 或明确部署步骤交付，并修正映射表 javadoc 第 33-35 行"当前 31 个目标全部启用"这一**无法从仓库复现**的断言。

### S2-3 `windswept_*` 三项被映射到"非碎裂地形"群系，而 BWG 恰恰主动放弃碎裂地形

**发现者：C 组（C-S2-1）**

- **位置**：`DisasterBiomeMapping.java:87`（`windswept_hills → dacite_ridges` d=0.576）、`:88`（`windswept_gravelly_hills → canadian_shield` **d=0.761**）、`:89`（`windswept_forest → black_forest` d=0.543）
- **问题**：三者的共同特征是原版**侵蚀维固定在 0.45–0.55 带**（导出实算 erosion 并集 = `[4500,5500]`），这正是"碎裂地形"的签名。而 BWG 负责该带的 `SHATTERED_BIOMES_BWG`（`BWGBiomeSelectors.java:94-100`）与 `SHATTERED_BIOMES_TERRABLENDER`（`TerraBlenderBiomeSelectors.java:51-57`）**25 格全是 `DEFERRED_PLACEHOLDER` / `THE_VOID`** —— BWG 明确把碎裂地形留给原版。
- **影响**：天灾维度把原版最崎岖的三种地形全部铺成寒带湿润针叶林，地形语义丢失。
- **建议修法**：把这三项加入 `WHITELIST`（理由与"河流/洞穴"同级：**BWG 自己就不覆盖该参数区**），并从映射表删除对应 case。

### S2-4 `stony_peaks → dacite_ridges`：暖带裸岩峰被换成寒带湿润针叶林

**发现者：C 组（C-S2-2）**

- **位置**：`DisasterBiomeMapping.java:86`
- **问题**：原版 `stony_peaks` 占 **WARM 带全部 5 个湿度格**（T 中心 3750），`dacite_ridges` 只在 COLD/NEUTRAL（T 中心 −1250, H 中心 5500）。温度跨 2 带、**格重合度 J=0.000**，d=0.506。
  BWG 自己的 `PEAK_BIOMES_BWG:90` 第 3 行（WARM 带）对最干旱两格是 `DEFERRED` —— **BWG 对 WARM 带主动 defer 回原版 `stony_peaks`**，说明它没有合适的暖带峰。
- **建议修法**：优先把 `stony_peaks` 加入 `WHITELIST`（与 BWG 的 defer 决策一致）；若必须替换，改用 `howling_peaks`（权衡：偏冷）。

### S2-5 `snowy_beach → basalt_barrera`：积雪海滩变成无雪的温带玄武岩滩

**发现者：C 组（C-S2-3）**

- **位置**：`DisasterBiomeMapping.java:110`
- **问题**：`snowy_beach` 独占 ICY 带（T 中心 −7250），`basalt_barrera` 独占 NEUTRAL 带（T 中心 250）。温度差 2 带，d=0.375；而其**大陆度/侵蚀/weirdness 三项与原版完全一致**——唯一的问题就是温度。`basalt_barrera` 的 `temperature=0.85, hasPrecipitation=true`，**不会下雪**。
  BWG 的 `BEACH_BIOMES_BWG:103` 第 0 行（ICY）**全是 DEFERRED**——BWG 没有冰海滩。
- **建议修法**：把 `snowy_beach` 加入 `WHITELIST`（照 BWG 的 defer）。若同时采纳 S3-5，`basalt_barrera` 改为承接温带 `beach`，职责更自洽。

### S2-6 "追加列表含非 minecraft 群系"这一维度判据与维度身份无关

**发现者：A 组（A-S2-1）**

- **位置**：`PossibleBiomesFilterMixin.java:60-66`
- **问题**：判据读的是 `biomesToAppend`，而该列表来自**全局 region 表**（`LevelUtils.java:115-121` 用 `Regions.get(regionType)` 构造），与"当前是哪个维度"无关。**任何进入 `terrablender:overworld_regions` 的维度，其追加列表必然含 BWG 群系 → 判据恒真。**
  主世界当前之所以安全，不是因为这个判据，而是因为 BeLoong 用自己的 `{"replace": true, "values": ["beloong:disaster"]}` 覆盖了 TB 自带的 `{"replace": false, "values": ["minecraft:overworld"]}`。
- **影响**：若任何数据包把 `minecraft:overworld` 放回该 tag，主世界的 `possibleBiomes` 会丢掉 39 个原版群系 → 结构集与地物静默消失（同 S1-1 的机制）。
- **附加问题**：`DisasterBiomeSubstitution.java:162-164` 的 javadoc 声称"必须同时满足含 BWG 与含黑名单原版，否则主世界会被误伤"——这条**缓解措施在实际生效的 mixin 里没有实现**（它只判了 `modded`）。
- **建议修法**：改为按维度身份判定。`CloneParameterListMixin.cloneBeforeInit` 拿得到 `levelKey`，可在其中把 `chunkGenerator.getBiomeSource()` 登记进一个 identity `Set`（`LevelUtils.java:93` 的 `biomeSource` 与它是同一对象），mixin 改判 `isTargetBiomeSource(this)`。

### S2-7 `Set.copyOf` 丢失确定性迭代顺序，影响地物放置

**发现者：A 组（A-S2-2）；E 组从性能角度命中同一点（E-S3-1）**

- **位置**：`PossibleBiomesFilterMixin.java:88`
- **问题**：`Set.copyOf` 返回 `ImmutableCollections$SetN`，其迭代顺序由 `SALT32L`（JVM 启动时随机化）决定；而 `possibleBiomes()` 的顺序会进入 `ChunkGenerator.java:95-97` 的 `FeatureSorter.buildFeaturesPerStep(List.copyOf(biomeSource.possibleBiomes()), ...)`，决定 `PlacedFeature` 的全局编号（`FeatureSorter.java:41-59` 的 `computeIfAbsent(...getAndIncrement())`），进而决定 `setFeatureSeed` 与放置顺序。
  **原版用 Guava `ImmutableSet`、TB 用 `ObjectLinkedOpenHashSet`，两者都保序**——只有本改动引入了随机化。
- **影响**：同一 seed 的跨会话区块可能出现地物放置不一致。附带：`Set.copyOf` 拒绝 null 元素而 `ObjectLinkedOpenHashSet` 允许。
- **建议修法**：换成保序集合（`ImmutableSet.copyOf(filtered)` 或 `Collections.unmodifiableSet(new LinkedHashSet<>(filtered))`），**并顺便 memoize**（见 S3-6）。

### S2-8 注入顺序依赖 mod 加载顺序，配置里没有优先级声明

**发现者：A 组（A-S3-3）**

- **位置**：`PossibleBiomesFilterMixin.java:49-55`；`beloong.mixins.json`（无 `priority`）
- **问题**：Mixin 解析注入点时看的是"已被先前 mixin 写好的 ClassNode"，所以 TB 的 `MixinBiomeSource` 必须先应用。两边都是默认 priority 1000，顺序完全依赖 mod 加载顺序。当前能工作靠的是 `mods.toml` 里对 terrablender 的 `ordering="AFTER"`。
- **影响**：加载顺序一旦变化，本 mixin 先应用 → 找不到目标方法 → 走 S1-2 的启动崩溃路径。
- **建议修法**：`beloong.mixins.json` 顶层加 `"priority": 1500`；或直接采用 S1-2 的 `MixinConfigPlugin` 方案（可同时解决两条）。

---

## 三、S3 中等

### S3-1 `sunflower_plains → amaranth_grassland` 湿度跨 3 带
- **位置**：`DisasterBiomeMapping.java:65`；d=0.628、J=0.000。同格的 BWG 自选是 `PRAIRIE`（`BWGBiomeSelectors.java:25`）。**建议**：改为 `"prairie"`（d=0.246）。

### S3-2 `flower_forest → rose_fields` 湿度跨 3 带
- **位置**：`DisasterBiomeMapping.java:67`；d=0.480，但温度完全一致、语义（花林↔花田）贴切。**建议**：可保留，在注释写明"语义优先于气候"。

### S3-3 `dark_forest → black_forest` 存在格级完全匹配的更好目标
- **位置**：`DisasterBiomeMapping.java:70`；d=0.415、J=0.000。而 `weeping_witch_forest` 与原版 `dark_forest` **格完全一致、d=0.000**，且自带 `village_salem` 结构。**建议**：改为 `"weeping_witch_forest"`。**本报告中收益最大、风险最低的一条。**

### S3-4 白名单遗漏 `stony_shore` 与 `windswept_savanna`
- **位置**：`DisasterBiomeSubstitution.java:93-104`
- **问题**：白名单的既定判据是"BWG 在该参数区没有对应物"。按同一标准，这两个也符合：它们由 `OverworldBiomeBuilder` 中**未被 TerraBlender 覆写**的私有方法硬编码产生（`stony_shore` 在 `addMidSlice():486`/`addLowSlice():660`；`windswept_savanna` 在 `maybePickWindsweptSavannaBiome():940`）。TerraBlender 只覆写了 `pickBeachBiome`/`pickPeakBiome`/`pickSlopeBiome` 三个方法。
- **建议**：补进 WHITELIST，或在注释里明确判据口径并说明这两项属于"有可接受替身因而故意不保留"。

### S3-5 `beach → dacite_shore` 而 `basalt_barrera` 在 5 维上近得多
- **位置**：`DisasterBiomeMapping.java:109`；现状 d=0.326、J=0.133；`basalt_barrera` d=**0.013**。**建议**：改为 `"basalt_barrera"`（配合 S2-5 后职责更自洽）。

### S3-6 supplier 未 memoize，主动放弃了原版 `BiomeSource` 的记忆化
**发现者：E 组（E-S3-1）**
- **位置**：`PossibleBiomesFilterMixin.java:88`
- **执行频率**：每次 `possibleBiomes()` 调用。**全 jar 仅 5 个调用点，零处在区块生成或游戏刻路径上**（E 组反汇编整个 1.21.1 client jar 枚举所得，我已抽查 `ChunkGenerator`/`ChunkGeneratorStructureState` 各 2 处确认）。
- **数据**：`Set.copyOf(LinkedHashSet[108])` 每次 **1542–2865 ns**（JDK 21 字节码确认非不可变输入时走 `new HashSet + toArray + Set.of`）；整段 4.20–5.73 µs，比 TB 等效实现（1.68–1.80 µs）慢 2.4–2.9×。原版 `BiomeSource.<init>` 用 `Suppliers.memoize`（javap 已确认），TB 与本 mixin 都把这个包装丢了。
- **建议修法**：`Suppliers.memoize(() -> Set.copyOf(filtered))` 或先算快照再返回常量。**收益 300–1000×**（→ 5–15 ns）。与 S2-7 的修法可合并为同一处改动。

### S3-7 异常安全：`cloneBeforeInit` 与 `filter` 都没有 try/catch
**发现者：A 组（A-S3-1）与 D 组（D-S3-1）独立命中**
- **位置**：`CloneParameterListMixin.java:65-95`；`DisasterBiomeSubstitution.java:205-261`
- **问题**：redirect handler 整体替换了 `initializeForTerraBlender` 调用点，中途抛异常会使该调用与 `setParameters` 都不执行，并穿透 `LevelUtils.initializeOnServerStart` 的 for 循环（无 try/catch）→ **该维度之后的所有 level stem 全部不再初始化**。
  对比同文件内：`hasModdedBiomes`（`:183-186`）与 `isBwgEnabled`（`:337-352`）都有 `catch (Throwable)`，而真正被调用的 `filter` 一条都没有。
- **建议修法**：把替换段包进 try/catch，失败时**继续**执行原有的 `initializeForTerraBlender` + `setParameters`（退化成 TB 原行为，而不是中断）；`filter` 内部按条目 try/catch。

### S3-8 查询层的 catch 静默清空"现有集合"
**发现者：D 组（D-S3-1 的后半）**
- **位置**：`PossibleBiomesFilterMixin.java:72-74`：`catch (Throwable t) { current = Set.of(); }`
- **问题**：把现有集合整个丢弃且**不记任何日志**——若触发，`possibleBiomes` 会退化成只有 BWG（连 14 个白名单原版也从查询层消失），无任何线索。
- **建议修法**：改为保留 `this.possibleBiomes` 原值 + WARN。

### S3-9 降级路径日志策略不足
**发现者：D 组（D-S3-2）**
- **位置**：`DisasterBiomeSubstitution.java:280-286`、`:292-293`、`:256-258`
- **问题**：BWG 配置读取一旦失败会产生 ~7552 条 WARN + 7552 条 ERROR，但**没有一条**说明"整功能已整体降级"；汇总行只在 `replaced>0` 时打印，全量降级时反而没有汇总。
- **建议修法**：改为计数聚合，在 `filter()` 末尾统一输出一条 INFO/WARN，逐点日志降为 DEBUG。

---

## 四、S4 轻微

### S4-1 `hasModdedBiomes` 是死代码
**发现者：A 组（A-S4-1）、D 组（D-S4-3）、E 组（E-S5-1）三组独立命中**

- **位置**：`DisasterBiomeSubstitution.java:169-187`
- **问题**：全仓库无调用方（grep 仅命中定义处）。`PossibleBiomesFilterMixin:61-63` 用的是**另一段内联的 stream 判据**，作用在 `biomesToAppend`（≤55 项）上，第一个元素即非 `minecraft:`，`anyMatch` **第 1 次迭代即短路**。真正会扫 7593 条的是这个未接线的实现。
- **风险**：它的 javadoc 描述了 S2-6 那条**并未实现**的缓解措施，会误导后来者以为该风险已处理。
- **建议修法**：删除，或在按 S2-6 改为维度身份判据后保留唯一一份共享判据。

### S4-2 未使用的 import
- **位置**：`DisasterBiomeSubstitution.java:3`（`Either`）、`:18`（`IExtendedParameterList`）——均**只出现在 import 行**（已独立核实）。

### S4-3 `Config.java:93` 注释路径错误
- 写的是 `mixin/DisasterBiomeSubstitution.java`，实际在 `worldgen/`（设计文档 I1 正说明了迁移原因）。

### S4-4 注释把"当前事实"写成"平台固有属性"
**发现者：A 组（A-S4-2）**
- **位置**：`CloneParameterListMixin.java:52-54`；`DisasterBiomeSubstitution.java:120-122`
- **问题**：断言"`overworld_regions` tag 不含 `minecraft:overworld`"，但 **TB 自带的就是 `{"replace": false, "values": ["minecraft:overworld"]}`**；主世界被排除是因为 BeLoong 自己提供了 `replace:true` 覆盖。
- **影响**：该覆盖的全局副作用——主世界彻底退出 TerraBlender 管理（BWG 群系不在主世界生成）——在本次 diff 范围里没有任何文字说明。
- **建议修法**：注释改为明确写出"依赖 BeLoong 自带 datapack 覆盖该 tag；副作用是主世界不再受 TerraBlender 管理"。

### S4-5 `_perf_probe` 之外的审查产出未被版本控制
- `run/exported_vanilla_biomes.json`（映射表的"权威依据"，javadoc `:21`/`:27` 引用）与 `run/config/.../world_generation.json` 都位于 `.gitignore:36` 忽略的 `run/`，仓库中**无人能复现**。C 组独立验证了该导出数据的真实性（7593/7593 逐点一致 + float32 量化细节），所以**数据本身没问题，问题只是可复现性**。
- **建议**：把导出脚本与产物提交到受版本控制的目录，或改 javadoc 为"由 X 脚本在 Y 环境生成"。

### S4-6 `isWhitelisted` 只比对 path，忽略命名空间
- **位置**：`DisasterBiomeSubstitution.java:137-139`。今天不会出错（两个调用点都先判了命名空间），但方法签名暗示"传任意 ID 都安全"。**建议**：方法内部先判命名空间，或改名为 `isWhitelistedVanillaPath`。

---

## 五、S5 提示

| # | 内容 | 来源 |
|---|---|---|
| **S5-1** | `isUsable` 的 `containsKey` 与后续 `getHolder` 判据不对称——NeoForge 的 alias 解析会让两者对"可用"的定义不一致（当前无实际影响）。建议统一用 `getHolder(key).isPresent()` | A-S5-1 |
| **S5-2** | `ci.cancel()` 抹掉了 TerraBlender 的 `hasAppended` 幂等标志。当前不可观测，但语义已与上游分叉。建议自行维护 `@Unique private boolean beloong$filtered` | A-S3-2 |
| **S5-3** | 白名单**过度保留**了 3 个 BWG 已有对应物的海洋：`lukewarm_ocean`/`deep_lukewarm_ocean`（BWG 有 `lush_stacks`）、`warm_ocean`（BWG 有 `dead_sea`）。其余 6 项保留正确 | C-S4-1 |
| **S5-4** | `mangrove_swamp → cypress_swamplands` 是 BWG region_1 swapper 的合法自选，但 `white_mangrove_marshes`/`pale_bog` 的 d=0.000 更贴。另：`swamp → bayou` 与 region_1 的 `SWAMP→BAYOU` **完全一致** | C-S4-3 |
| **S5-5** | 映射表 javadoc 第 29-30 行声称"同一 BWG 群系被复用是刻意的"，7 组多对一中 **4 组合理**（`baobab_savanna` 合并有 BWG 自身依据：它在 MIDDLE 与 PLATEAU 两个数组同格都放 `BAOBAB_SAVANNA`），**3 组是 S2-3/S2-4/S2-5/S3-3 的副作用**，采纳那些修法后会降到 4 组 | C-S5-1 |

---

## 六、审查者纠正委派方的三处错误

子代理在审查中**否证了委托方（我）写入任务描述或代码注释的错误前提**。记录在此，因为它们本身就说明审查不是橡皮图章：

| # | 我的错误前提 | 审查者的纠正 | 来源 |
|---|---|---|---|
| 1 | "`isBwgEnabled` 是相对早期版本的**回退**（`21fc73b` 曾缓存过 BWG 配置 Field）" | `git log --all -S "isBwgEnabled"` 只命中 `6a77c0e`（新增）。`21fc73b` 里缓存的 `Field` 是 `Climate.ParameterList.values`，服务于**完全不同的目的**。**从未存在过"缓存 BWG 配置 Field"的版本** | E-S4-2 |
| 2 | "`hasModdedBiomes` 在 `PossibleBiomesFilterMixin` 中被使用" | 全仓库无调用方。我在切换语义判据时把判据**内联**写进了 mixin，那个方法从未接线 | A-S4-1 / D-S4-3 / E-S5-1 |
| 3 | 代码注释把"主世界不在 tag 里"写成**平台固有属性** | TB 自带的就是 `{"replace": false, "values": ["minecraft:overworld"]}`；主世界被排除是 BeLoong 自己 `replace:true` 覆盖的结果 | A-S4-2 |

---

## 七、已核实无缺陷的部分

这些是审查**确认为正确**的，同样有保留价值：

| 项 | 结论 | 来源 |
|---|---|---|
| `@Redirect` 签名 | 与 TB `initializeBiomes` 字节码偏移 202-209 **严格匹配**；9 个形参 = 1 owner + 3 实参 + 5 外层形参；`remap=false` 正确 | A-Q3 |
| `@Accessor("values")` | 正确，且**显式名是必需的**——`beloong$getValues` 不匹配 `AccessorName.PATTERN`，省略会抛 `InvalidAccessorException`。`@Accessor` 的显式名会跳过 `inflectTarget()`，方法名前缀不影响目标解析 | A-Q4 |
| refmap 警告 | **无风险**。全项目 refmap 为 `{"mappings": {}}`，一条映射都没生成；警告来自 AP 的 `NO_OBFDATA_FOR_ACCESSOR`，是项目级配置产物，NeoForge 运行时用官方名 | A-Q4 |
| `@Pseudo` 使用 | `CloneParameterListMixin`（目标是 TB 类）正确且必要；另两个目标类是原版、永远存在，不需要也不该加 | A-Q7 |
| 映射表覆盖完整性 | 53 原版群系 = 白名单 14 + 映射 39，**`MISSED: NONE`**，无多余项、无交集 | C-Q1 |
| 31 个目标可用性 | 全部已注册且启用；55 个 BWG 群系**全部至少获得 1 个参数点**，不存在"注册了但永不生成" | C-Q6 |
| 缓存数字 | 7552 替换 + 41 保留 = 7593，**独立验算精确吻合**（41 = 海洋 20 + 河流 18 + 洞穴 3） | D、C 双方 |
| 性能 | **无 S1/S2**。所有改动落在"开服一次"或"单次指令"路径；`filter()` 实测上界 45 ms，占开服 <1%。**没有一处落在区块生成或游戏刻路径上**（E 组反汇编整个 client jar 枚举 `possibleBiomes()` 全部 5 个调用点确认） | E |
| 内存 | 无累积、无泄漏，开服峰值 <1 MB | E-S4-4 |
| 维度守卫 | 下界维度初始化仅 2 ms 且日志无 filter 记录，**反证 `isTargetDimension` 守卫有效** | E |

---

## 八、未能核实的点（汇总）

| # | 项 | 建议核实方法 |
|---|---|---|
| 1 | TB 缺席时是否**必然**崩溃（vs 仅告警） | 最小实例（只装 BeLoong 不装 TB）跑一次启动 |
| 2 | 跨 mixin 配置文件的 `priority` 是否严格保证应用顺序 | `javap -c MixinProcessor` 看收集与排序路径 |
| 3 | 整合包里是否存在把 `minecraft:overworld` 加回 tag 的包 | 对 `run/mods` 全部 jar 搜该 tag 文件 |
| 4 | `Set.copyOf` 顺序变化对生成结果的**可见幅度** | 同一存档连续两次启动，对比未生成区块的地物分布 |
| 5 | **已安装 mod** 的 jar 是否有人在热路径调用 `possibleBiomes()` | 在 supplier 里加计数器跑真实游戏（属"修"，本次未做） |
| 6 | 注册表冻结时机 | 在 `ServerAboutToStartEvent` 处检查 `MappedRegistry.frozen` |
| 7 | BWG 配置的部署方式（是否有仓库外分发手段） | 若存在，S2-2 应降级为 S4 |
| 8 | `possibleBiomes()` 被剔除后结构集的**净损失** | 比对 BWG 的 `data/minecraft/tags/worldgen/structure/**` 与 `biome/has_structure/**` |

---

## 九、建议的修复顺序

按"影响 × 修复成本"排序：

| 优先级 | 问题 | 理由 |
|---|---|---|
| **P0** | S1-1（开关失效） | 已核实的静默失效；修法明确、改动小 |
| **P0** | S1-2（TB 依赖契约） | 会崩服；改动小（`mods.toml` 一行或加 plugin） |
| **P0** | S2-2（干净部署降级） | **当前部署即已触发**；且会连锁到 S1-1 的状态 |
| **P1** | S2-7 + S3-6（保序 + memoize） | 同一处一行改动，同时修掉确定性与性能两个问题 |
| **P1** | S2-6（维度判据） | 改用维度身份，同时消除 S4-1 的死代码 |
| **P1** | S2-1（兜底绕过白名单） | 加一行白名单校验 |
| **P2** | S2-3/4/5、S3-1/3/4/5（映射表与白名单） | 逐项调整，风险低、收益明确。**S3-3 收益最大**（d 0.415→0.000） |
| **P2** | S2-8、S3-7、S3-8、S3-9（健壮性与可观测性） | 防御性改动 |
| **P3** | S4/S5 各项 | 清理与文档 |

---

## 十、审查过程说明

- **范围有效性**：`git merge-base --is-ancestor 6a77c0e HEAD` 返回 0，`6a77c0e` 是 HEAD 祖先，审查范围成立。
- **未修改代码**：全程 `git status --porcelain` 为空，`gradlew build` 通过。
- **子代理越界说明**：
  - A 组曾误将 Mixin javadoc jar 解压到项目根，**已自行删除并验证工作树干净**（委托方已独立复核确认）。
  - E 组为取得性能实测数据，在工作区根建立了临时目录 `D:\Minecraft\_perf_probe\`（探针 `PerfProbe.java`、全 jar 反汇编 `javap_all.txt` 76 MB、报告）。**未触碰 `BeLoong-Core` 项目目录。**
