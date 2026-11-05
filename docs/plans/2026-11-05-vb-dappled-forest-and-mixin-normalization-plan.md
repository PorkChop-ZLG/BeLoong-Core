# VB 1.2.2「斑驳森林」映射 + 天灾 mixin 规范化 实施计划

**Date:** 2026-11-05
**Design:** [`2026-11-05-vb-dappled-forest-and-mixin-normalization-design.md`](2026-11-05-vb-dappled-forest-and-mixin-normalization-design.md)
**Status:** 已实施并实机验收（2026-11-05）

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
| 启动账目 | `7608 = replaced 7608 + 0 + unsolved 0` | ✅ 实测一致，且无 `no mapping entry` |
| region 树 | 5 行账目 `minecraft: biomes 0 []` | ✅ 全 0（含 `vanillabackport:overworld`） |
| 天灾 `possibleBiomes` | 仍 **60** 种、顺序不变 | ✅ `60 biomes {beloong=5, biomeswevegone=55}` |
| 主世界 | 仍 **116** 种（不能掉回 115） | ✅ 116，`dappled_forest` 仍在主世界 |
| `debug.log` | 55 条 `Mixing … from beloong.mixins.json`，前缀已变 | ✅ 主菜单时 39 条（其余按类加载时机触发），前缀全部为新包名、零 mixin 报错 |
| **存档级零泄漏** | 天灾维度已生成区块无 `minecraft:` 群系 | ✅ 9 个 region / 4049 区块；走到 biomes 阶段之后的 2225 个区块只有 bwg + beloong |
| 新映射真的生效 | 生成的区块里出现 `biomeswevegone:pumpkin_valley` | ✅ 已出现 |

---

## 三、计划之外真实发生的事

1. **门的第一次实现是错的**：把 `case "pale_garden"` 的裸路径直接与 `minecraft:pale_garden` 求差集，
   结果"全部未覆盖"（`covered 0 / MISSING 3`）。修法是两侧都归一成裸路径 —— 留下了
   `switch` 前已判过命名空间这个约束的注释，避免下次又踩。
2. **顺带发现**：`mixin/` 根级当时有 6 个文件而非 `AGENTS.md` §四 记的 6 个中的 5 个（`FSweepButtonControlMixin`
   在 `client` 数组、易被漏算）；§四 的复查配方只查 `mixins` 集合会漏掉它。已在文档里写明"根级已无 Java 文件"。
3. `CloneParameterListMixin` 的 `@Inject` 原本依赖**类级** `remap = false`；按 §四"新代码逐注解写"的取向
   补成显式，行为不变。
4. **`runServer` 在本机跑不起来**：`NoClassDefFoundError: org/lwjgl/Version` —— dev 运行时里的纯客户端模组
   在专用服务器初始化，**与本次改动无关**。所以世界加载期账目改用「客户端开局 + 存档取证」两条腿验证。
5. **行尾被改成 LF**：用脚本改 `DisasterBiomeMapping` / `DisasterBiomeSubstitution` / `RegionBiomeRewriter`
   和两个新文档时整文件写回了 LF（仓库是 CRLF）⇒ 收尾时已统一转回 CRLF，避免下次 checkout 产生整文件 diff。
6. **存档级扫描器是临时写的**（不解析数据段、只读 section 的 biome 调色板）；结论见上表，
   若要常态化可固化成 `tools/scan_disaster_biomes.py`（未做，未获授权）。

---

## 四、留给下一轮的

* `mixin/betterendisland/` 两个 mixin 的 `@Pseudo` / `require = 0`（§四 残留清单现在只剩它们）。
* 映射表数据驱动（设计文档 D2 记为 backlog）。
* 可选：把存档级扫描器固化成 `tools/scan_disaster_biomes.py`，作为改映射后的第二道验收（第一道是启动账目）。
