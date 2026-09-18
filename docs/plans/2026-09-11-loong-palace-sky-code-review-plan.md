# 龙宫天空渲染 代码审查计划

**Date:** 2026-09-11
**Status:** Ready — 按批次分派只读审查
**审查基线:** `e548e19`
**权威设计文档:** `docs/龙宫天空渲染总设计.md`（576 行 / 15 节）
**产出:** `docs/reviews/<日期>-loong-palace-sky-code-review.md`

---

## 〇、已有的范围决策（不得更改）

| # | 决策 | 来源 |
|---|---|---|
| 1 | **排除** `client/DisasterPortalRenderer.java`（传送门 BER，不改天空）| 用户 |
| 2 | **排除**天灾维度天空（`disaster.json` 的 `effects: minecraft:overworld`）| 用户 |
| 3 | **纳入**龙宫维度类型 JSON（`dimension_type/loong_palace.json`）| 用户 |
| 4 | 报告**附修复建议**（含建议文本）| 用户 |
| 5 | 产出放 `docs/reviews/` | 用户 |
| 6 | **只审查，不修复**——审查阶段不改任何代码与文档 | 用户 |
| 7 | **分派子代理**执行各批次审查 | 用户 |

---

## 一、审查目标

1. **正确性** —— 哪些边界会崩、画错、泄漏、或留下状态残留
2. **资源** —— 180 MB 显存能否降下来；贴图管线是否在做无用功
3. **代码质量** —— 冗余、重复、死代码
4. **文档断言** —— 唯一权威文档的 576 行里，哪些断言与代码不符
5. **定级与修复建议** —— 每条问题给出可执行的修法

---

## 二、与初版计划的差异（为何重写）

| 变化 | 对计划的影响 |
|---|---|
| 文档整合完成：9 份 → 1 份 | 「文档落地」从 7 份降为 **1 份**，但该文档更密、断言更多 |
| 显存已实测 ≈ 180.8 MB | 批次 1 从「最可能」升级为**已确认重点**，且测量已具备 |
| 总设计 §12 已列出 4 类已知项 | 审查价值从「发现」转为「**验证 + 补漏 + 定级**」；真正的价值在 §12 **未列**之处 |
| 总设计本身成为审查对象 | 新增维度 7：逐条核对文档断言 |
| 旧文档的 4 处缺陷随删除消失 | 命名漂移 / 悬空引用 /「跨天未支持」失实 / 天气禁用无记载——**全部作废** |
| 几何缓存已提交 `e548e19` | 基线明确；代码无「从未读过」的盲区 |
| 新增遗留项 | `docs/pictures/` 旧贴图（约 24 MB，非运行时）；`docs/superpowers/` 下非天空的龙宫遗留文档 |

---

## 三、审查范围

### A. 代码（约 817 行）

| 文件 | 行 |
|---|---|
| `client/sky/DramaticSkyRenderer.java` | 255 |
| `client/sky/SkyDecorationsRenderer.java` | 149 |
| `client/sky/CubeAtlasSkyRenderer.java` | 142 |
| `client/sky/SkyRotation.java` | 98 |
| `client/sky/LoongPalaceSkyEffects.java` | 85 |
| `client/sky/SkyBlendMode.java` | 60 |
| `client/LoongPalaceSkyTickHandler.java` | 47 |
| `client/sky/SkyLayerConfig.java` | 17 |
| `client/sky/SkyAlphaSource.java` | 11 |
| `BeLoongCoreClient.java`（两处注册）| — |

### B. 数据 / 资源契约

- `data/beloong/dimension_type/loong_palace.json` —— **纳入**
- `data/beloong/dimension/loong_palace.json`
- `data/beloong/worldgen/biome/loong_palace.json`
- 9 张运行时贴图（尺寸与显存已实测）

### C. 文档

**仅 1 份**：`docs/龙宫天空渲染总设计.md`。

### D. 明确排除

- `DisasterPortalRenderer`、天灾维度天空
- `docs/pictures/{loong_palace_sky,nebula*,overworld_cubemap_*}.png` → 仅作清理建议
- `docs/superpowers/` 下龙宫非天空遗留文档 → 仅作清理建议

---

## 四、审查维度与检查项

### 维度 1：资源与显存（第一优先）

| 检查项 | 状态 |
|---|---|
| 独立复核 9 张 PNG 的尺寸 / 色深 / 显存数字 | 待复核（不照抄文档）|
| mipmap 是否实际启用（决定是否 ×4/3 → 约 241 MB）| 待查 |
| `moon_phases.png` 16-bit 的解码路径与必要性 | 已测尺寸，待查解码 |
| `mask_moon.png` 31 KB → 24 MB 是否可降采样 | 已测，待评估 |
| `mask.png` 调色板 PNG 的解码与显存 | 已测，待查路径 |
| 6 张 3072×2048 图集能否降采样 | 待评估 |
| 纹理是否**全部**被加载（`sun`/`sunflare` 仅黄昏/黎明绘制）| 待查 |
| 纹理生命周期与泄漏 | 待查 |

### 维度 2：生命周期与状态机
`DISPLAY_ALPHAS` / `initialized` / `lastDayTime` / `unexpectedTransitionActive` 的全部读写点；
`render` 与 `tick` 双写 `initialized`（总设计 §12.2 已标）；`reset()` 的全部边界路径。

### 维度 3：渲染正确性
`SkyRotation` 12 次矩阵施加顺序；两个旋转分支；五个预设等价性；`SkyBlendMode` 的状态泄漏；
渲染后状态复位完整性；几何缓存两条纪律；`FACES` 绕序与面命名。

### 维度 4：线程与静态状态
静态可变字段的重置路径；`VertexBuffer` 的线程断言。

### 维度 5：边界条件
`getMoonPhase()` 越界；`RenderSystem.getShader()` 为 null；`F3+T`；Iris 开/关。

### 维度 6：死代码与冗余（需**验证** §12.1）
`SkyBlendMode.ADD` 未使用；五预设仅两组取值；`ENABLE_ROTATION` / `ENABLE_DECORATIONS` 恒 true。

### 维度 7：文档断言核对
逐节抽取可验证断言 → 代码/JSON 定位 → 判定「一致 / 漂移 / 无据」。
必须覆盖 §4、§5.3、§6.1、§6.2、§7、§8.1、§8.3、§9.1–9.3、§9.5、§10、§11、§12。
另核对文内交叉引用与章节编号。

---

## 五、方法与纪律

- **静态阅读** + 原版对照（`build/moddev/artifacts/neoforge-21.1.236-sources.jar` 可读原文）
- 每个结论必须落到 `文件:行` + 可复现命令
- **禁止修改任何文件**（审查阶段）；禁止 git 写操作
- 严重度分级：**阻塞 / 重要 / 次要 / 仅记录**
- **复用已有证据**（显存、性能、UV、字节码结论），不重复测量——但 §9.5 的数字要求独立复核
- 不能确定的必须明说「无法确认」，禁止臆测

---

## 六、批次与分派

| 批 | 主题 | 执行者 |
|---|---|---|
| 1 | 显存与贴图管线 | 子代理 A |
| 2 | 状态机与生命周期 | 子代理 B |
| 3 | 渲染正确性 | 子代理 C |
| 4 | 总设计断言逐条核对 | 子代理 D |
| 5 | 汇总、去重、终定级、修复建议、清理建议、成文 | 主线 |

批次 1–4 **并行**；批次 5 依赖前四批结论。

---

## 七、产出物

`docs/reviews/<日期>-loong-palace-sky-code-review.md`，含三部分：

1. **问题清单** —— 分级 + 每条修复建议（建议文本，不落地）
2. **总设计断言核对表** —— 一致 / 漂移 / 无据
3. **清理建议** —— 遗留贴图、`docs/superpowers/` 遗留文档、总设计中已失效的表述
