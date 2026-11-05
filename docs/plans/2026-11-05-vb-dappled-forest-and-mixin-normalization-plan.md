# VB 1.2.2「斑驳森林」映射 + 天灾 mixin 规范化 实施计划

**Date:** 2026-11-05
**Design:** [`2026-11-05-vb-dappled-forest-and-mixin-normalization-design.md`](2026-11-05-vb-dappled-forest-and-mixin-normalization-design.md)
**Status:** 已实施（构建与静态门通过；实机账目待验收）

---

## 一、任务与执行状态

| # | 任务 | 状态 | 实际结果 |
|---|---|---|---|
| T1 | 3 个注解补 `remap = false`（`@Shadow` ×1、`@Accessor` ×2） | ✅ | `compileJava --rerun-tasks` 警告 **3 → 0** |
| T2 | 6 个 mixin 移包（`git mv`）+ `package` 改声明 | ✅ | `mixin/` 根级 Java 文件数 **6 → 0** |
| T3 | `beloong.mixins.json` 6 条条目加前缀 | ✅ | 55 文件 / 55 条目仍一一对应 |
| T4 | 跨包 `import`（`CloneParameterListMixin` → `ParameterListAccessor`） | ✅ | 编译通过 |
| T5 | javadoc 全限定引用同步（12 处 / 3 文件） | ✅ | `DisasterBiomeMapping` 1、`DisasterBiomeSubstitution` 10、`RegionBiomeRewriter` 1 |
| T6 | `CloneParameterListMixin` 删 `@Pseudo` + 两处 `@Inject` 补 `remap = false` | ✅ | — |
| T7 | `NetMagnetItemMixin` 补 `@Pseudo` + `require = 0` ×2 | ✅ | `beyonddimensions` 已是 optional |
| T8 | `FSweepButtonControlMixin` 补 `@Pseudo` + `require = 0` + `@At remap=false` | ✅ | — |
| T9 | `mods.toml` 补 `fsweep` optional 声明（`side="CLIENT"`、`[1.5.0,)`） | ✅ | 版本取自实际 jar 的 `neoforge.mods.toml` |
| T10 | `DisasterBiomeMapping` 加 `dappled_forest → pumpkin_valley` | ✅ | — |
| T11 | 新增 `tools/validate_biome_mapping.py` | ✅ | 变异测试通过 |
| T12 | 文档：`AGENTS.md`（门清单 / 警告基线 / 残留清单 / f-sweep 说明）、总设计 §十二 rev 1 | ✅ | — |

---

## 二、验收标准与结果

| 判据 | 期望 | 实测 |
|---|---|---|
| `compileJava --rerun-tasks --no-build-cache` | 成功 + **0 警告** | ✅ BUILD SUCCESSFUL，0 警告 |
| mixin 文件 / json 条目 | 55 / 55 一一对应 | ✅ |
| `python tools\validate_biome_mapping.py` | PASS（3 个 VB 群系全覆盖） | ✅ exit 0 |
| 门的变异测试（删掉一条映射） | FAIL，准确报出 `minecraft:dappled_forest` | ✅ exit 1 |
| 启动账目（待实机） | `7608 = replaced 7608 + 0 + unsolved 0` | ⏳ |
| region 树（待实机） | 5 行账目 `minecraft: biomes 0 []` | ⏳ |
| 天灾 `possibleBiomes`（待实机） | 仍 **60** 种、顺序不变 | ⏳ |
| 主世界（待实机） | 仍 **116** 种（不能掉回 115） | ⏳ |
| `debug.log` | 55 条 `Mixing … from beloong.mixins.json`，前缀已变（`minecraft.` / `terrablender.` / …） | ⏳ |

---

## 三、计划之外真实发生的事

1. **门的第一次实现是错的**：把 `case "pale_garden"` 的裸路径直接与 `minecraft:pale_garden` 求差集，
   结果"全部未覆盖"（`covered 0 / MISSING 3`）。修法是两侧都归一成裸路径 —— 留下了
   `switch` 前已判过命名空间这个约束的注释，避免下次又踩。
2. **顺带发现**：`mixin/` 根级当时有 6 个文件而非 `AGENTS.md` §四 记的 6 个中的 5 个（`FSweepButtonControlMixin`
   在 `client` 数组、易被漏算）；§四 的复查配方只查 `mixins` 集合会漏掉它。已在文档里写明"根级已无 Java 文件"。
3. `CloneParameterListMixin` 的 `@Inject` 原本依赖**类级** `remap = false`；按 §四"新代码逐注解写"的取向
   补成显式，行为不变。

---

## 四、留给下一轮的

* 实机验收（上面的 ⏳ 四项）。
* `mixin/betterendisland/` 两个 mixin 的 `@Pseudo` / `require = 0`（§四 残留清单现在只剩它们）。
* 映射表数据驱动（设计文档 D2 记为 backlog）。
