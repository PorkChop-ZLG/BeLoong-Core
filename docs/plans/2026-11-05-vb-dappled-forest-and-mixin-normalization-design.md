# VB 1.2.2「斑驳森林」映射 + 天灾 mixin 规范化 设计文档

**Date:** 2026-11-05
**Status:** **已实施并实机验收**（2026-11-05，存档 `天灾维度新版VB测试`：参数表 `7608 = 7608 + 0 + 0`；五棵 region 树 `minecraft: biomes 0 []`；天灾 `60 biomes {beloong=5, bwg=55}`；4049 个区块零 `minecraft:` 泄漏）
**范围:** VB 群系映射（方案 A）+ 静态门（方案 C）+ 天灾 mixin 规范化 + 可选目标三件套补齐
**用户裁定:** 采用 A + C；**不做** B（"未映射改兜底群系"）、**不做** D（映射表数据驱动）；
删 `CloneParameterListMixin` 的 `@Pseudo`；保留 `mixin/bomd/` 空目录；顺带补齐 `FSweepButtonControlMixin`。

---

## 1. Problem Statement

### 1.1 第三次同类泄漏：`minecraft:dappled_forest`

VanillaBackport 1.1.7.10 → 1.2.2（"Age of Mayhem"，92 提交）在 **`minecraft:` 命名空间**新增了第三个群系：

| id | 来源 | 映射状态（修复前） |
|---|---|---|
| `minecraft:pale_garden` | VB 1.1.x | ✅ 已有（2026-09-13） |
| `minecraft:sulfur_caves` | VB 1.1.x | ✅ 已有（2026-09-13） |
| **`minecraft:dappled_forest`** | **VB 1.2.2（26.3 斑驳森林）** | ❌ **缺失** |

实机证据（`run/logs/latest.log`，2026-11-05）：

```
2670-2673  ERROR ×4  disaster biome substitution: no mapping entry for minecraft:dappled_forest
2674       INFO      parameter points 7608 = replaced 7604 + non-vanilla kept 0 + unsolved 4
2677       minecraft:overworld (index 0)      : 7608 点, DEFERRED 0,    minecraft: 1 [dappled_forest]
2678       vanillabackport:overworld          : 35 点,   DEFERRED 20,   minecraft: 1 [dappled_forest]
2679-2681  biomeswevegone:region_0/1/2        : 7608 点, minecraft: 1 [dappled_forest]
2719       beloong:disaster: 60 biomes {beloong=5, biomeswevegone=55}
2718       minecraft:overworld: 116 biomes
```

**四条真实 region 树全部带它**，说明泄漏已落到地形；而查询层仍是 60 种干净群系 ⇒ 正是决策 24 点名
不可接受的「第三种状态」（生成层产出、`/locate` 与自然罗盘看不见）。

**根因是结构性的**：`rewriteKey` 对"未映射"的处置是「保留原版 + ERROR」（决策 19，无兜底），而 VB 的群系
**全部以 `minecraft:` 命名空间注册**，注入又走 Platform 那条**跨模组通用**的
`OverworldBiomeBuilder` TAIL 注入 ⇒ **VB 每新增一个群系必然漏一次**。

### 1.2 天灾 mixin 的规范债

实现天灾维度群系的三层改写用了 4 个 mixin（`PossibleBiomesFilterMixin` / `ParameterListAccessor` /
`CloneParameterListMixin` / `RegionsGetMixin`），它们写于项目规范（`AGENTS.md` §四）成型之前：

* 全部放在 `mixin/` **根级**，未按目标命名空间分包；
* `@Shadow` / `@Accessor` 未写 `remap = false` ⇒ **注解处理器每次构建报 3 条
  `Unable to locate obfuscation mapping`**，用户在 IDEA 里能看到（不影响运行）；
* `CloneParameterListMixin` 挂着冗余的 `@Pseudo`（TerraBlender 是 `required`）。

同时 `mixin/` 根级还躺着两个其它模组的 mixin（`NetMagnetItemMixin` / `FSweepButtonControlMixin`），
其中 `fsweep` 连 `mods.toml` 声明都没有（§四 记录的"三段缺一"）。

---

## 2. Design

### 2.1 A：补一项映射

```java
case "dappled_forest" -> "biomeswevegone:pumpkin_valley";
```

因为 preset 层与 4 棵 region 树共用 `rewriteKey` 这**一个入口**，一项映射同时修好两条路径。

**选点依据**（沿用决策 34 的三条判据）：

| 判据 | 核验 |
|---|---|
| 同气候格 | VB `DAPPLED_FOREST` = COOL + ARID + `NEAR/FAR_INLAND` + 侵蚀 2~4（`BiomeGeneration.java:31-32`）；BWG `MIDDLE_BIOMES_BWG[1][0]` 正是 `PUMPKIN_VALLEY`（`BWGBiomeSelectors.java:23`） |
| BWG 默认启用 | `BWGWorldGenConfig.getDefaultBiomes()` 只把 `ERODED_BOREALIS` 置 false（`:26-34`） |
| 有 BWG 地表规则 | `BWGOverworldSurfaceRules` 有 `PUMPKIN_VALLEY` 规则（`:317`，并列入 `:466` 的 dispatch 表） |

### 2.2 C：静态门 `tools/validate_biome_mapping.py`

* **数据源**：VB `ModBiomes.java` 的 `register("id", …)` → 字段→id；`BiomeGeneration.java` 里
  `ModBiomes.<FIELD>` 的引用 → 真正进参数表的字段（只报"真的会泄漏"的，不报"注册了但没注入"的）。
* **对照**：`DisasterBiomeMapping` 的 `case "…"` 集合（switch 走的是 `getPath()`，故两侧都归一成裸路径）。
* **输出**：差集非空 ⇒ 打印缺失清单 + **exit 1**；VB 源码缺席 ⇒ 打印 SKIP + exit 0。
* **解析前先剥注释**（否则 javadoc 里叙述旧方案的散文会稳定误报）。
* **已做变异测试**：临时删掉 `dappled_forest` 那一行 ⇒ 门准确报出该 id 并退出 1；还原后字节一致。

### 2.3 M1–M3：mixin 规范化

| 类 | 新位置 | 附带修正 |
|---|---|---|
| `PossibleBiomesFilterMixin` | `mixin/minecraft/` | `@Shadow(remap = false)`；修正第 77 行多缩进的 4 格 |
| `ParameterListAccessor` | `mixin/minecraft/` | `@Accessor(value = "values", remap = false)` ×2 |
| `CloneParameterListMixin` | `mixin/terrablender/` | **删 `@Pseudo`**；两个 `@Inject` 显式 `remap = false`；新增跨包 `import …minecraft.ParameterListAccessor` |
| `RegionsGetMixin` | `mixin/terrablender/` | 注解原本已合规 |
| `NetMagnetItemMixin` | `mixin/beyonddimensions/` | 补 `@Pseudo` + 两处 `require = 0`（`beyonddimensions` 已是 optional） |
| `FSweepButtonControlMixin` | `mixin/fsweep/` | 补 `@Pseudo` + `require = 0` + `@At` 的 `remap = false`；`mods.toml` 补 `fsweep` optional 声明 |

移包必须同步四处：`package` 声明、`beloong.mixins.json` 条目前缀、跨包 `import`、javadoc 里的
全限定 `{@link}` / `@see`（`DisasterBiomeSubstitution`、`DisasterBiomeMapping`、`RegionBiomeRewriter` 共 12 处）。

---

## 3. Decisions

| # | 决定 | 理由 / 被否的替代 |
|---|---|---|
| D1 | 只补映射（A），**不改"未映射"的处置** | 用户明确不做 B：兜底群系会把"气候不匹配"从**可见的报错**变成**静默的地形错误**，且要改决策 19 |
| D2 | 映射表继续留在 Java（不做数据驱动 D） | 本次只需一项；改数据驱动要新增 Codec + reload listener，性价比不成立，记为 backlog |
| D3 | 删 `CloneParameterListMixin` 的 `@Pseudo` | TB 是 `required`，`@Pseudo` 让"类不存在"静默跳过，与"响亮失败"立场相反 |
| D4 | 保留 `mixin/bomd/` 空目录 | 用户：接下来可能要用 |
| D5 | 补齐 `FSweepButtonControlMixin` 三件套 | §四 明令"改到这些文件时顺手修正"；`fsweep` 是唯一"有 mixin 但 toml 无声明"的模组 |
| D6 | `@Accessor` / `@Shadow` 一律显式 `remap = false` | vanilla 目标本来就不该 remap；漏写只出警告、不会失败 ⇒ 只能靠约定 + 基线 0 守住 |
| D7 | 不改名 `CloneParameterListMixin` | 它的职责已扩为"克隆 + 过滤 + 作用域标志"，但改名要动 json 条目与多处 javadoc，收益低于噪声 |

---

## 4. Non-Goals

* 不处理 `betterendisland` 下两个 mixin 的 `@Pseudo` / `require`（不在本次触碰范围，§四 残留清单已更新）；
* 不改 `yuushya` 的依赖声明（用户明令不做）；
* 不为天灾群系引入配置开关 / 兜底群系 / 数据驱动映射；
* 不追溯已生成区块（决策 6）。

---

## 5. 升级必查项

* **VB（或任何以 `minecraft:` 注入群系的模组）升级后**：先跑 `python tools\validate_biome_mapping.py`，
  再 `runClient` 看启动账目（`unsolved 0` / 各 region 树 `minecraft:` 为空 / 主世界群系数不降）。
* 新的 `@Shadow` / `@Accessor` / `@Invoker` **必须**带 `remap = false`，否则警告基线会从 0 复活。
