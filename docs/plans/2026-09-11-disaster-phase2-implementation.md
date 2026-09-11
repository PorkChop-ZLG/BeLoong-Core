# 天灾维度第二阶段 实施计划

**Goal:** 用 5 个 `beloong:` 自制群系接管 14 个白名单原版群系，另 7 项改指 BWG 群系，
使天灾维度的群系里**不含任何 `minecraft:` 群系**。

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
| **region 树账目** | 新注入路径自己打的日志 | region 树里还剩多少 `minecraft:` 群系 |

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
2. `.\gradlew.bat runServer`，等 `[BeLoong] 天灾维度群系替换` 出现后停服
3. 记录基线数字
**Verification:** 日志中应出现
`参数点 7593 个 = 替换 6782 + 白名单保留 811 + 未能求解 0（替换生效）`

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
   —— 替换没真的发生就完全不动手，与查询层的判据保持一致（决策 21）
4. **异常安全（必需）**：每个 pair 的改写包在 try/catch 内，**失败时原样放行**。
   异常若穿透会进入 `initializeForTerraBlender` / `LevelUtils` 的 level stem 循环，
   导致该维度之后的所有 level stem 不再初始化（决策 23）

**Verification:** `.\gradlew.bat compileJava` 通过。
⚠️ **编译成功不代表注入可用**（复盘 M2），必须由 T3 实机确认

#### T3：加 region 树账目日志
**Files:**
- Modify: `src/main/java/com/zonlong/beloong/mixin/BwgRegionBiomeRewriteMixin.java`（或 `DisasterBiomeSubstitution`）

**Steps:**
1. 统计每次 `addBiomes` 调用中：改写条数、**未能改写而残留的 `minecraft:` 群系集合**
2. 用 `BeLoongCore.LOGGER` 打一行，格式对齐现有账目日志风格
3. 残留集合**非空时按 ERROR 级别报出**（与 `filter()` 的 `未能求解` 处理一致）

**Verification:** 由 T4 一并确认

#### T4：验证 5 项泄漏已修
**Files:** 无
**Steps:**
1. `.\gradlew.bat runServer`
2. 读日志
**Verification:** 三条同时成立才算通过：
- `[BeLoong] 天灾维度群系替换：参数点 7593 个 = 替换 6782 + 白名单保留 811 + 未能求解 0`
  （**数字应与 T0 完全相同**——本阶段还不改映射表与白名单）
- region 树账目里 `minecraft:` 群系**从 12 种降到 7 种**
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
   `参数点 7593 个 = 替换 7593 + 白名单保留 0 + 未能求解 0（替换生效）`
2. region 树账目里 `minecraft:` 群系 **0 种**（残留集合为空、无 ERROR）
3. 启动无 `天灾群系替换失败` 报错
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
| **映射目标不可用** | 该项保留原版 + ERROR，账目 `未能求解 > 0` | 检查群系 ID 拼写与 JSON 是否真的加载；T16 的"未能求解 = 0"是硬门槛 |
| **地表规则没生效** | 碎裂地形变草坡（观感损坏，不崩） | T17 客户端验收；`beloong:` 规则返回 `null` 时会落回原版，属安全降级 |
| **无配置开关** | 出问题只能改代码 | 沿用决策 23：新注入路径必须 try/catch，失败时退化为"原样放行"而非抛异常 |
| **动了主世界** | 灾难性 | region 树改写受 `isSubstitutionApplied()` 守卫；主世界不在 `overworld_regions` tag 内，`initializeBiomes` 提前 return，两者都不会触发 |

**回滚方式**：`WHITELIST` 恢复为 21 项 + 删除 `DisasterBiomeMapping` 中新增条目 + 从
`beloong.mixins.json` 移除 `BwgRegionBiomeRewriteMixin` → 完全回到第一阶段状态。
（`beloong.mixins.json` 的注册是唯一的"开关"，与 `disaster_test` 分支当时的撤销方式一致。）

---

## 五、验收标准

**服务端（我做）:**
- [ ] 账目：`替换 7593 + 白名单保留 0 + 未能求解 0 = 7593`
- [ ] region 树账目：`minecraft:` 群系 0 种
- [ ] 启动与运行期无 ERROR 级 `[BeLoong]` 日志

**客户端（用户做）:**
- [ ] 海洋/河流/洞穴/碎裂地形观感符合预期
- [ ] `/locate biome minecraft:ocean` 在天灾维度搜不到
- [ ] 地表规则生效（碎裂地形露石，非草坡）

**文档:**
- [ ] 总设计不再有"第二阶段未开始"表述，实测数字更新
