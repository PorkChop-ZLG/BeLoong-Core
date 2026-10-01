# 结构药水效果系统 —— 代码审查报告

**日期：** 2026-10-01
**范围：** `structure/` 包 4 个类 + `data/beloong/beloong/structure_effects/` 数据格式
**方式：** 静态阅读 + 对着 `neoforge-21.1.236-userdev.jar` 的 `patches/**.java.patch` 核对平台行为 + 实机日志

## 0. 状态总览

| 编号 | 级别 | 标题 | 状态 |
|---|---|---|---|
| C-1 | Critical | 无 `display` 的进度 ⇒ "立刻撤除"增强静默失效 | **已修复，用户实机验证通过** |
| C-2 | Critical | 一条坏 entry ⇒ 整份文件作废 | **已修复，日志验证通过**（§2） |
| I-1 | Important | 结构 id 不存在 ⇒ 静默不生效、零日志 | 未处理 |
| I-2 | Important | 增强撤除未限定 `watchedEffects` ⇒ 可能误删他源同名效果 | 未处理 |
| I-3 | Important | 每次检查全量遍历结构配置，无按区块预筛 | 未处理 |
| I-4 | Important | 重检只在"跨区块"时触发 ⇒ 竖直进入结构可能完全不触发 | 未处理（**本次新增，见 §3**） |
| S-1..S-5 | Suggestion | 见 §4 | 未处理 |
| — | — | 实测"看不到发光"的现象 | **未证实，已记录，不再追查**（§5） |

---

## 1. C-1：无 `display` 的进度 ⇒ 增强静默失效

**现象：** 玩家获得进度后，本应立即撤除的"门禁效果"不消失，要等最多一个 `duration` 才自然消失。

**根因：** 增强原本挂在 `AdvancementEvent.AdvancementEarnEvent` 上，而 NeoForge 把该事件 post 在
`PlayerAdvancements.award` 的 **`display().ifPresent(...)` lambda 内部**：

```java
if (!flag1 && advancementprogress.isDone()) {
    p_300979_.value().rewards().grant(this.player);
    p_300979_.value().display().ifPresent(lambda -> {
        if (...) this.playerList.broadcastSystemMessage(...);
        EventHooks.onAdvancementEarnedEvent(this.player, p_300979_);   // ← 在 lambda 内部
    });
}
```

⇒ **进度 JSON 省略 `display` 时该事件根本不发**。而整合包的"剧情门禁"进度往往正是故意做成隐形的。

**修法（`StructureEffectHandler`）：** 改挂 `AdvancementEvent.AdvancementProgressEvent` —— 它在同一方法里、
`display` 之外发出（`grantProgress` 之后），两类进度都覆盖。代价与对策：
- 它**每条判据达成都发**、且 `ProgressType` 有 `GRANT`/`REVOKE` ⇒ 自己筛
  `getProgressType() == GRANT` **且** `getAdvancementProgress().isDone()`；
- 事件频率远高于"获得进度" ⇒ 先用 `isUsedAsGate(...)` 做**廉价预筛**（只看配置、不查世界），
  再进 `revokeGatedEffects(...)` 做昂贵的结构查询。

**验证：** 用户实机测试通过（隐形进度 `beloong:test_no_display` 也能立刻撤除）。

---

## 2. C-2：一条坏 entry ⇒ 整份文件作废

**现象：** 任意一条效果写错（效果 id 不存在），该文件里**所有**结构的配置一起失效。

**根因：** 原来用 `Codec.unboundedMap(STRING, list(StructureEffectEntry.CODEC))` 一次解整个文件；
`effect` 字段用 `comapFlatMap` 校验存在性 ⇒ 一条失败 ⇒ 整个 map 解码失败 ⇒ `resultOrPartial`
记一条 ERROR 后**整份文件被丢弃**。

**修法（`StructureEffectLoader`）：** 丢掉整文件 Codec，改成"结构 id / 数组 / 单条 entry 各自独立解码"，
坏一条只丢那一条并单独报 ERROR。

**验证（日志，`run/logs/debug.log`）：**
```
[17:43:29.962] [Render thread/ERROR] structure_effects: skipping bad entry #0 of structure
                'minecraft:jungle_pyramid' in file 'beloong:desert_pyramid_test':
                Unknown mob effect: minecraft:not_an_effect
[17:43:29.962] [Render thread/DEBUG] Reloaded structure effects: 13 structures
```
- ✅ 只有**一条** per-entry ERROR（且指明了是第 0 条）⇒ 坏条目被单独跳过；
- ✅ `13 structures` = 整合包 `disaster.json` 11 + 测试文件 2 ⇒ 同文件里的其余配置被保留；
- ✅ **没有** `Failed to parse structure effects file` ⇒ 文件没有被整份丢弃（修复前的必然症状）；
- ✅ 若 `minecraft:glowing` 也无效，会打出**第二条** ERROR —— 没有 ⇒ 它解析成功。

**附带修正：** 删除 `StructureEffectLoader` 里已无用的 `FILE_CODEC`，并在类文档写明
"跨文件同结构会合并（`merged.addAll`）、同一路径才是覆盖"这一与数据包直觉不同的语义（原 S-4）。

---

## 3. I-4（本次新增）：重检只在"跨区块"时触发

`StructureEffectHandler.onServerTick` 只在 `player.chunkPosition()` 变化时调 `checkAndApply`，
而 `collectPresentEntries` 用 `structureManager.getStructureAt(player.blockPosition(), structure)` ——
后者要求**玩家方块坐标落在该结构包围盒内**。

两个由此产生的空档：
1. **竖直进入不触发**：传送到结构**上方**（或从高处落下穿过结构）时，落点那一刻的判定是"不在结构内"，
   之后再竖直下落进入结构**不会**更换区块 ⇒ 不会重检 ⇒ 全程没有任何效果被施加。
2. **单区块的小结构**：像 `minecraft:igloo`（约 7×7，基本在一个区块内）可以在**完全不跨区块**的情况下
   走进/走进其包围盒 ⇒ 同样不触发。

⇒ 这也是"实测看不到效果"的**最强候选**原因（§5）。修法方向：把触发条件从"跨区块"放宽为
"位置发生有意义的变化"（例如每 20 tick 兜底重检一次，或同时比较 Y）。

---

## 4. S 级（未处理）

- **S-1**｜`isAdvancementDone` 用 `getOrStartProgress`，它有**写入副作用**（给未开始的进度创建并持久化
  progress 条目）。读操作改了玩家存档状态；UI 不可见、体积可忽略。
- **S-2**｜`StructureEffectEntry.duration` 与 `EffectEntry.durationTicks` 命名不一致；两者都**没有取值范围校验**
  （0/负数 ⇒ 加进去立刻到期）。
- **S-3**｜`showIcon` 恒为 `true`，与 `show_particles` 无关 ⇒ 关掉粒子也仍显示图标，当前无法配置。
- **S-4**｜跨文件同结构会**合并**条目且无日志（语义已补进类文档，行为未改）。
- **S-5**｜`refreshWatchedEffects()` 只在 `Expired`/`Remove` 里调 ⇒ 运行中手改 TOML 后，
  若没有任何 watched 效果到期，会一直用旧名单。

---

## 5. 未解释的实测现象（记录在案，**不再追查**）

**报告：** 把测试文件里的 `minecraft:igloo` 换成 `minecraft:jungle_pyramid`，`minecraft:glowing` 仍无效果。

**结论：没有找到确切原因。** C-2 的修复本身已由日志证实生效（§2），"看不到发光"是另一回事。

**最强候选（有日志证据，但未证实）：** 与 §3 的 I-4 同源。日志里的传送落点：
- 最新一次会话：`已将Dev传送至4688.500000, 129.000000, -1215.500000`（丛林神殿，**Y=129**）
- 上一份日志：沙漠神殿 → `Y=103.99`；雪屋 → `Y=73` / `Y=67` / `Y=75`

`getStructureAt` 要求坐标在包围盒内，而 Y=129 / Y=104 都远高于这些结构（沙漠神殿顶约 y≈78）
⇒ 判定"不在结构内" ⇒ 不施加；之后再竖直下落进入结构不换区块 ⇒ 不重检 ⇒ 全程无效果。

**未能证实的部分：** 雪屋那次 `Y=67` 是否已在包围盒内，无法从日志判定 ⇒ 这条只能算强候选。
**留给以后的复现方法：** `/tp` 到结构**内部**（不要用 `~` 保留原 Y），再用
`/data get entity @s active_effects` 读权威结果（不依赖任何视觉表现）。

---

## 6. 复核指引

- 无测试套件。取新证据用 `.\gradlew.bat compileJava --rerun-tasks --no-build-cache`（`build` 常返回 UP-TO-DATE）。
- 日志：`run/logs/latest.log`（INFO）与 `run/logs/debug.log`（含我们所有 DEBUG 行）。
- 关键行：`structure_effects: ...`、`Reloaded structure effects: N structures`。
- 临时测试文件：**已于收尾时删除**（`structure_effects/desert_pyramid_test.json`、
  `advancement/test_no_display.json`），并已还原 `run/config/beloong-server.toml` 的 `watchedEffects`。
  如需复测，按 §5 的方法重建：结构内放一条**故意写坏**的探针条目 + 一条正常条目，
  再配一个**省略 `display`** 的进度（`minecraft:impossible` 触发器）。
