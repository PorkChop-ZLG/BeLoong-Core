# 结构药水效果系统 —— 代码审查报告

**日期：** 2026-10-01
**范围：** `structure/` 包 4 个类 + `data/beloong/beloong/structure_effects/` 数据格式
**方式：** 静态阅读 + 对 `neoforge-<版本>-userdev.jar` 的 `patches/**.java.patch` 核对平台行为 + 实机日志
**编号规范：** 按 `AGENTS.md` §八（连续编号，严重程度只决定落在哪一节）

## 严重

1. **无 `display` 的进度 ⇒ "立刻撤除"增强静默失效** —— *已修，用户实机验证通过*
   `StructureEffectHandler.java:295`
   根因：增强原挂在 `AdvancementEarnEvent`，而 NeoForge 把它 post 在 `PlayerAdvancements.award` 的
   **`display().ifPresent(...)` lambda 内部** ⇒ 省略 `display` 的隐形进度永不触发它（整合包的"剧情门禁"
   恰恰常常是隐形的）。修法：改挂 `AdvancementProgressEvent`（在 `display` 之外发），自筛
   `ProgressType.GRANT` + `isDone()`，并用 `isUsedAsGate`（`:309`）做廉价预筛后再查世界。

2. **一条坏 entry ⇒ 整份文件作废** —— *已修，日志验证通过*
   `StructureEffectLoader.java:96`
   根因：原先用 `Codec.unboundedMap(..., list(StructureEffectEntry.CODEC))` 一次解整个文件，而 `effect`
   字段用 `comapFlatMap` 校验存在性 ⇒ 任一条失败 = 整个 map 失败 = **该文件里所有结构一起失效**。
   修法：结构 id / 数组 / 单条 entry 各自独立解码，坏一条只丢那一条。
   证据（`run/logs/debug.log`）：只有**一条** per-entry ERROR
   （`skipping bad entry #0 of structure 'minecraft:jungle_pyramid' ... Unknown mob effect: minecraft:not_an_effect`）
   ＋ `Reloaded structure effects: 13 structures`（整合包 11 + 测试文件 2），且**没有**
   `Failed to parse structure effects file` ⇒ 同文件其余配置被完整保留。

## 重要

3. **结构 id 写错/不存在 ⇒ 静默不生效、零日志**
   `StructureEffectLoader.java:75`（只做 `ResourceKey.create`，不校验存在性）＋
   `StructureEffectHandler.java:122`（`structure == null` 直接 `continue`）
   写错成"合法但不存在的 id"就永远不生效，日志里一个字都没有 —— 与 `watchedEffects` 并列的第二个静默失败面。
   修法：加载期校验一次，未知结构 id 打一条英文 WARN。

4. **增强撤除未限定 `watchedEffects`，也无法区分效果实例来源**
   `StructureEffectHandler.java:326`
   只要"在结构内 + 门禁刚好是刚获得的进度 + 不在应得集合"就 `removeEffect`，可能误删**别处给的同名效果**
   （如远古守卫者的挖掘疲劳）。场景窄，但确实会误删。修法：撤除前限定 `watchedEffects`。

5. **每次检查都全量遍历结构配置，无按区块预筛**
   `StructureEffectHandler.java:111`
   每次 `checkAndApply` 遍历整个 `configMap`（当前整合包 47 个结构）并逐个 `structureManager.getStructureAt`，
   触发点是每次跨区块 ⇒ 随配置线性增长。修法：先用玩家所在区块的 `StructureStart` 反查候选，再比对配置表。

6. **重检只在"跨区块"时触发** ⇒ 竖直进入结构可能完全不触发
   `StructureEffectHandler.java:214`
   `getStructureAt` 要求坐标落在结构包围盒内，而重检只在区块变化时发生 ⇒ ① 传送到结构**上方**再竖直落下、
   ② **单区块的小结构**（如 `minecraft:igloo`，约 7×7）都可能全程不施加任何效果。
   修法：把触发条件放宽为"位置发生有意义的变化"（如每 20 tick 兜底重检一次，或同时比较 Y）。

## 可选

7. `isAdvancementDone` 用 `getOrStartProgress`，它有**写入副作用**（给未开始的进度创建并持久化 progress 条目）
   —— `StructureEffectHandler.java:174`。读操作改了玩家存档状态；UI 不可见、体积可忽略。

8. `StructureEffectEntry.duration` 与 `EffectEntry.durationTicks` 命名不一致，且两者都**没有取值范围校验**
   —— `StructureEffectEntry.java:16`、`EffectEntry.java:21`。0/负数 ⇒ 加进去立刻到期。

9. `showIcon` 恒为 `true`，与 `show_particles` 无关 —— `StructureEffectHandler.java:200`。
   关掉粒子也仍显示图标，当前无法配置。

10. 跨文件同结构会把条目**逐个追加合并**且不打日志，与"同一路径覆盖"的直觉不同
    —— `StructureEffectLoader.java:109`（语义已补进类文档，行为未改）。

11. `refreshWatchedEffects()` 只在 `Expired` / `Remove` 里调 —— `StructureEffectHandler.java:238`、`:262`。
    运行中手改 TOML 后，若没有任何 watched 效果到期，会一直用旧名单。

## 已知取舍

- **出结构后效果不会立刻消失**，等 ≤ 一个 `duration` 自然到期（系统只有"加"、没有"减"）。
- **`Expired` 与 `Remove` 的判据故意不同**：到期问"刚有没有被重新施加"（必须同时把时长续上），
  主动移除问"这个效果该不该在"。两条判据不能合并（满时长时 `addEffect` 返回 false，合并会放行牛奶把它清掉）。
- **门禁 fail-open**：进度 id 解析不到时按"未完成"处理 + 只告警一次。
- **`refreshing` 重入保护期间返回空集** ⇒ 真发生重入时那次移除不会被取消（有意保守）。

## 未能验证

- **把测试文件的 `minecraft:igloo` 换成 `minecraft:jungle_pyramid` 后，`minecraft:glowing` 仍看不到效果。**
  **未找到确切原因。** 第 2 条的修复本身已由日志证实生效（坏条目被单独跳过、同文件其余配置保留），
  "看不到发光"是另一回事。
  - **最强候选（有日志证据，但未证实）**：与第 6 条同源。日志里的 `/tp` 落点为
    `4688.5, 129, -1215.5`（丛林神殿）、`208.5, 103.99, 848.5`（沙漠神殿）、雪屋 `73 / 67 / 75`；
    而 `getStructureAt` 要求坐标在包围盒内，Y=129 / 104 都远高于结构（沙漠神殿顶约 y≈78）
    ⇒ 判定"不在结构内" ⇒ 不施加；之后再竖直下落**不换区块** ⇒ 不重检。
  - **未能证实的部分**：雪屋那次 `Y=67` 是否已在包围盒内，无法从日志判定 ⇒ 本项只能算强候选。
  - **复现方法（留给以后）**：`/tp` 到结构**内部**（不要用 `~` 保留原 Y），再用
    `/data get entity @s active_effects` 读权威结果（不依赖任何视觉表现）。
  - **本项已由用户裁定：不再追查。**

## 复核指引

- 无测试套件。取新证据用 `.\gradlew.bat compileJava --rerun-tasks --no-build-cache`（`build` 常返回 UP-TO-DATE）。
- 日志：`run/logs/latest.log`（INFO）与 `run/logs/debug.log`（含所有 DEBUG 行）。
- 关键行：`structure_effects: ...`、`Reloaded structure effects: N structures`。
- 临时测试文件**已于收尾时删除**（`structure_effects/desert_pyramid_test.json`、`advancement/test_no_display.json`），
  并已还原 `run/config/beloong-server.toml` 的 `watchedEffects`。如需复测：结构内放一条**故意写坏**的探针条目
  + 一条正常条目，再配一个**省略 `display`** 的进度（`minecraft:impossible` 触发器）。
