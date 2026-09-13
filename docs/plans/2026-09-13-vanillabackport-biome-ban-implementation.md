# VanillaBackport 群系封堵 + 日志英文化 实施计划

**Goal:** 让 `minecraft:pale_garden` / `minecraft:sulfur_caves` 不再在天灾维度生成（同时保留其主世界生成），并把 BeLoong-Core 的全部日志消息改为英文。

**Architecture:** 生成层的 region 注入由「per-mod mixin」改为「注入 TerraBlender `Regions.get` 的通用装饰器」（覆盖所有 region 的 `addBiomes`）；群系封堵仍是数据层——`DisasterBiomeMapping` 补 2 项；查询层与 index-0 兜底树逻辑不变。

**Approach:** 采纳设计文档的**方案 A**（通用 region 装饰器），按**方案 A2（分两批）**执行：先日志英文化（零运行时风险、可独立验收），再群系封堵。

**Status（2026-09-13）:**
- ✅ **批次 A（A1–A6）已完成并通过静态门禁**：8 个 Java 文件 32 处日志改英文（第 33 处随 `BwgRegionBiomeRewriteMixin` 在 B3 删除）；4 份文档 29 行日志原文同步 + 各加英文化注记；`gradlew.bat build` 通过；全模组 `LOGGER` 语句 CJK = 0；`[DisasterPortal:*]` 锚点 7 个保留；**占位符数量逐一比对一致**（17/33/3/13/0/2/0/0）、`String.format` 说明符 12→12。
- ⏸ **批次 B（B1–B4、B7）待执行**（等用户验收批次 A 后开始）。
- 👤 A7/B8（提交）与 B5/B6（实机验证）由用户执行。

**设计依据:** `docs/plans/2026-09-13-vanillabackport-biome-ban-design.md`（已批准，含日志/存档双重取证、决策 D1–D7、异常安全表、账目预期值）

**验收方式说明（为何不是单元测试）:** 本项目**没有测试源集**（`compileTestJava NO-SOURCE`），世界生成行为也无法用 JUnit 断言 ⇒ 每个任务的验收 = ① `gradlew.bat build` 门禁；② 启动账目核对（日志）；③ 存档级取证（只读探针 `build/probe/RegionBiomeProbe.java`）。

**两批都必须遵守的约束:**
- **不**新增任何配置项 / 兜底群系（决策 19）；**不**改 `data/terrablender/tags/dimension_type/overworld_regions.json`（总设计明令）
- **不**改 VB 自身配置（`hasPaleGarden` / `hasSulfurCaves` 保持 true，主世界继续生成这两个群系）
- 注释 / javadoc 保持中文；玩家可见文案（lang）不动；日志保留 `[BeLoong]` / `[DisasterPortal:*]` ASCII 锚点
- **不追溯**已生成区块（修复只对新生成区块生效）

---

## 批次 A：日志英文化（零运行时风险）

### Task A1: `DisasterBiomeSubstitution` 14 处日志改英文

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/worldgen/DisasterBiomeSubstitution.java`

**Steps:**
1. 逐处替换 `BeLoongCore.LOGGER.*` 文本为英文，**保持 `{}` 占位符顺序与数量不变**，保留 `[BeLoong]` 前缀。
2. 账目行的结构与词序保持一一对应（便于文档同步与对账），建议模板：
   - `[BeLoong] disaster biome substitution: parameter points {} = replaced {} + non-vanilla kept {} + unsolved {}`
   - `[BeLoong] disaster biome substitution: {} parameter points could not be resolved, vanilla biomes will be kept there...`
   - `[BeLoong] disaster region tree audit (regions with index!=0 are the real sources; their minecraft: biomes must be empty): {}`
   - `[BeLoong] per-dimension possibleBiomes audit (beloong:disaster minecraft: must be empty): {}`
   - `[BeLoong] custom biomes registered: {} / {} ready {}` 与未注册的 ERROR 版本
   - 单条失败/映射缺失/目标不可用/BWG 配置读取失败/注册表获取失败等 ERROR
3. 同步该文件里**引用日志文本的 javadoc**（若有）与 `logRegionTreeBiomes` 里 index-0 行的注释措辞。

**Verification:**
```powershell
gradlew.bat build   # GRADLE_EXIT=0 / BUILD SUCCESSFUL
Select-String -Path src\main\java\com\zonlong\beloong\worldgen\DisasterBiomeSubstitution.java -Pattern '[\u4e00-\u9fff]' | Where-Object { $_.Line -match 'LOGGER\.' }   # 期望：0 命中
```

### Task A2: `DisasterPortalBlock` 7 处日志改英文

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/block/DisasterPortalBlock.java`

**Steps:**
1. 7 处 `LOGGER.*` 文本改英文，**必须保留** `[BeLoongCore][DisasterPortal:wait|teleport|timeout|error]` 锚点（脚本判读依赖它）。
2. 典型模板：`[BeLoongCore][DisasterPortal:wait] destination chunk {} ({}, {}) not ready yet, PORTAL ticket claimed, waiting`、`[BeLoongCore][DisasterPortal:teleport] {} {} {} -> {} ({}, {}, {}) waited={} tick`。

**Verification:** 同 A1（build + 该文件 LOGGER 行 CJK = 0）。

### Task A3: `BeloongSurfaceRules` 4 处 + `DimensionTransportHandler` 2 处

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/worldgen/BeloongSurfaceRules.java`
- Modify: `src/main/java/com/zonlong/beloong/transport/DimensionTransportHandler.java`

**Verification:** build + 两文件 LOGGER 行 CJK = 0。

### Task A4: 其余 5 处（**跳过将被删除的 `BwgRegionBiomeRewriteMixin`**）

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`（1 处）
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCoreClient.java`（2 处）
- Modify: `src/main/java/com/zonlong/beloong/mixin/CloneParameterListMixin.java`（1 处）
- Modify: `src/main/java/com/zonlong/beloong/mixin/PossibleBiomesFilterMixin.java`（1 处）
- **不改** `mixin/BwgRegionBiomeRewriteMixin.java`（该文件在 Task B3 被整体删除，其 1 处中文日志随之消失）

**Verification:** build + 上述 4 文件 LOGGER 行 CJK = 0。

### Task A5: 全模组静态校验（批次 A 门禁）

**Steps:**
1. 全仓库扫描：所有 `LOGGER.*` 语句（含跨行拼接）中不得出现 CJK。
2. 确认 `[BeLoong]` / `[BeLoongCore]` / `[DisasterPortal:*]` 锚点仍在。

**Verification:**
```powershell
# 语句级扫描（含跨行）：期望 0
Get-ChildItem src\main\java -Recurse -Filter *.java | ForEach-Object {
  $l=[System.IO.File]::ReadAllLines($_.FullName,[System.Text.Encoding]::UTF8)
  for($i=0;$i -lt $l.Count;$i++){ if($l[$i] -match 'LOGGER\.(info|warn|error|debug|trace)\s*\('){
    $s=$l[$i]; $j=$i; while($s -notmatch '\)\s*;' -and $j -lt $l.Count-1){$j++; $s+=" "+$l[$j]}
    if($s -match '[\u4e00-\u9fff]'){ "{0}:{1}" -f $_.FullName,$i+1 } } } }
gradlew.bat build
```

### Task A6: 同步 4 份文档的 23 处日志原文

**Files:**
- Modify: `docs/天灾维度总设计.md`（5 处）
- Modify: `docs/天灾维度群系剔除-两次尝试复盘.md`（1 处）
- Modify: `docs/plans/2026-09-11-disaster-phase2-implementation.md`（15 处）
- Modify: `docs/plans/2026-09-11-disaster-vanilla-biome-removal-design.md`（2 处）

**Steps:** 把引用的中文日志片段替换为 A1–A4 定稿的英文原文；若某处引用的是**当时的实机输出**，改为英文原文 + 注明"（原输出为中文，2026-09-13 英文化）"。

**Verification:** `Select-String -Path docs\*.md,docs\plans\*.md -Pattern '天灾群系替换|region 树账目|possibleBiomes 账目|未能求解'` 期望 0 命中（或在历史复盘里显式标注为历史文本）。

### Task A7: 批次 A 提交（**可选，等你确认**）

**Verification:** `git status --porcelain` 只含 A1–A6 涉及文件；`gradlew.bat build` 通过。
**建议提交信息:** `chore(logs): 将 BeLoong-Core 全部日志消息改为英文（便于 GBK 日志排查）`

---

## 批次 B：群系封堵

### Task B1: `DisasterBiomeMapping` 补 2 项

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/worldgen/DisasterBiomeMapping.java`
- Modify: `docs/plans/2026-09-11-disaster-phase2-biome-table.md`（逐项表 +2 行与选点依据）

**Steps:**
1. 在 switch 中新增（放在对应分区，附中文注释说明依据）：
   - `case "pale_garden" -> "biomeswevegone:weeping_witch_forest";`（与 `dark_forest` 同目标、同气候格；原版 pale garden 即深色森林变体）
   - `case "sulfur_caves" -> "beloong:caves";`（与 `lush_caves`/`dripstone_caves`/`deep_dark` 同目标；BWG 无 `depth > 0` 群系）
2. 更新类 javadoc 的"覆盖全部 53 个"表述为"覆盖原版 53 + 后续模组新增的 `minecraft:` 群系（当前 55）"，并说明维护规则：**任何新增 `minecraft:` 群系都必须在 24 小时内补进本表**（否则走 ERROR 分支并泄漏）。
3. 逐项表补 2 行，注明"目标默认启用 + 有地表规则"的核对结果（`weeping_witch_forest` 已被 `dark_forest` 使用且账目无报错 ⇒ 默认启用；`beloong:caves` 为自制群系，有自制地表规则）。

**Verification:** `gradlew.bat build` 通过；`grep -c 'case "'` 计数由 53 → 55。

### Task B2: 新增 `RegionBiomeRewriter`

**Files:**
- Create: `src/main/java/com/zonlong/beloong/worldgen/RegionBiomeRewriter.java`

**Steps:**
1. `public final class RegionBiomeRewriter extends terrablender.api.Region`，构造 `(Region delegate)` → `super(delegate.getName(), delegate.getType(), delegate.getWeight())`。
2. 覆写 `addBiomes(Registry<Biome> registry, Consumer<Pair<ParameterPoint, ResourceKey<Biome>>> mapper)`：若 `!DisasterBiomeSubstitution.isFilteringTargetBiomeList()` 或 `activeBiomeRegistry() == null` → 直接 `delegate.addBiomes(registry, mapper)`（等价透传）；否则传一个包装 mapper：逐对 `rewriteKey(registry, key, false)`，**单对 try/catch 原样放行**（决策 23）。
3. 提供 `delegate()` 访问器（供幂等判断）。

**Verification:** `gradlew.bat build` 通过；`javap -p -cp build\classes\java\main com.zonlong.beloong.worldgen.RegionBiomeRewriter` 能看到 `addBiomes`。

### Task B3: 新增 `RegionsGetMixin` + 删除 `BwgRegionBiomeRewriteMixin`（**原子操作**）

**Files:**
- Create: `src/main/java/com/zonlong/beloong/mixin/RegionsGetMixin.java`
- Modify: `src/main/resources/beloong.mixins.json`
- Delete: `src/main/java/com/zonlong/beloong/mixin/BwgRegionBiomeRewriteMixin.java`

**Steps:**
1. `@Mixin(value = terrablender.api.Regions.class, remap = false)`；静态 `@Inject(method = "get", at = @At("RETURN"), cancellable = true)`：读取原返回值，逐项包装为 `RegionBiomeRewriter`（已是则跳过，幂等）；整体 `try/catch`，任何异常都**不改变**原返回值。
2. `beloong.mixins.json`：`mixins` 数组里移除 `BwgRegionBiomeRewriteMixin`、加入 `RegionsGetMixin`。
3. 删除 `BwgRegionBiomeRewriteMixin.java`（其职责已被装饰器覆盖）。
4. ⚠️ 三步必须在**同一次编辑**内完成并一起构建——任何中间态（旧 mixin 已删、新注入未生效）都会让泄漏重新出现。

**Verification:**
```powershell
gradlew.bat build    # 通过
Select-String -Path src\main\resources\beloong.mixins.json -Pattern 'BwgRegionBiomeRewriteMixin'   # 0 命中
Test-Path src\main\java\com\zonlong\beloong\mixin\BwgRegionBiomeRewriteMixin.java                  # False
```

### Task B4: 批次 B 静态门禁

**Steps:** 构建 + 确认新 mixin 已被 Mixin 处理（构建日志/`run/logs` 里 `Mixing RegionsGetMixin ... into terrablender.api.Regions`）。

**Verification:** `gradlew.bat build` 通过；客户端启动日志中出现 `RegionsGetMixin ... Regions`（若未出现即为注入失败，`defaultRequire: 1` 会直接报错）。

### Task B5: 运行时账目核对（**需要你跑一次客户端**）

**Steps:** 进入天灾维度（触发维度初始化即可），核对下列行：

| 账目 | 期望值 |
|---|---|
| 参数点汇总 | `7604 = replaced 7604 + non-vanilla kept 0 + unsolved 0` |
| region 树（`vanillabackport:overworld`、`biomeswevegone:region_0/1/2`） | `minecraft: 0 种 []` |
| region 树（index 0 行） | `0 种`（副作用 1） |
| `beloong:disaster` possibleBiomes | 仍 60 种、顺序与修复前一致 |
| 主世界 possibleBiomes | **仍 115 种**（minecraft=55）← 回归基线，不得下降 |
| 下界 / 末地 | 9 / 5 种不变 |

**Verification（dev 的 `run/logs` 是 UTF-8）:**
```powershell
$lines=[System.IO.File]::ReadAllLines('run\logs\latest.log',[System.Text.UTF8Encoding]::new($false))
$lines | Select-String -Pattern 'substitution|region tree audit|possibleBiomes audit' | ForEach-Object { $_.Line }
```

### Task B6: 存档级取证（新区域）

**Files:** 只读使用 `build/probe/RegionBiomeProbe.java`

**Steps:** 在**新生成区域或新世界**进入天灾维度，然后扫描该维度 region 文件。

**Verification:**
```powershell
& "$env:JAVA_HOME\bin\java.exe" "-Dstdout.encoding=UTF-8" build\probe\RegionBiomeProbe.java `
  "<save>\dimensions\beloong\disaster\region\r.<x>.<z>.mca" --scan
# 期望：群系清单中不含 minecraft:pale_garden / minecraft:sulfur_caves；
#       "含 minecraft: 群系的区块" 只应出现在 status=minecraft:structure_starts（占位 plains）
```
⚠️ **已生成区块不会被追溯清理**：旧存档里既有区块仍可能显示 `sulfur_caves`（设计 D6），这是预期，不作为失败判据。

### Task B7: 文档与 memory 同步

**Files:**
- Modify: `docs/天灾维度总设计.md`（§4.x 第三条注入路径改名、§八 新增决策 34、§九 已知问题中"5 项泄漏已修复"条目的后续）
- Modify: `docs/天灾传送门主线程死锁-根因与修复复盘.md`（若引用到相关机制）
- Modify: `docs/plans/2026-09-13-vanillabackport-biome-ban-design.md`（状态改为 Implemented，附实施日期与验证结果）
- Modify: `memory/decisions-log.md`（该条目标记"已实施 + 验证结果"）
- Modify: `memory/learned-patterns.md`（如实施中发现新的踩坑点）

**Verification:** 文档内不再出现"第三条注入路径 = BWG region 类"的旧描述；`docs/` 中引用旧 mixin 名的地方为 0。

### Task B8: 批次 B 提交（**可选，等你确认**）

**建议提交信息:** `fix(disaster): 用通用 region 装饰器阻止 VB 的两个 minecraft: 群系在天灾维度生成`

---

## 关键路径与依赖

```
A1 → A2 → A3 → A4 → A5（日志门禁）→ A6（文档）→ [A7 提交]
B1（映射）──┐
B2（装饰器）─┴→ B3（注入 + 删旧 mixin，原子）→ B4（静态门禁）→ B5（账目）→ B6（存档取证）→ B7（文档+memory）→ [B8 提交]
```
- A 批与 B 批**无代码依赖**（B 批可先做），但 A 批先做能让 B 批的账目日志直接是英文 ⇒ 按 A → B 顺序
- B1 单独落地时属**半修复**（VB region 树仍漏），不得在此时宣称"已修复"

## 回滚方案

| 变更 | 回滚方式 |
|---|---|
| 日志英文化 | `git revert` 该提交（纯文本，零运行时影响） |
| 映射 +2 | 删掉两行 `case`（回到 `unsolved 11`，泄漏恢复但不新增其他影响） |
| 装饰器 + 删旧 mixin | `git revert` 该提交（RegionsGetMixin 与旧 BWG mixin 一并回来）——**必须整体回滚**，不可只回滚一半 |

## 已知边界（写入交付说明）

1. **不追溯**：修复只影响新生成区块；旧存档既有区块的群系保持原样；
2. **主世界不变**：VB 的两个群系继续在主世界生成（115 种基线）；
3. **不解决**"首次进入天灾维度耗时长"（用户明示暂缓；与本次改动无关，实测 VB 有无都是 40–130 tick）；
4. 日志文件整体 UTF-8 化属启动参数问题（`-Dfile.encoding=UTF-8 -Duser.language=en`），不在本计划范围。
