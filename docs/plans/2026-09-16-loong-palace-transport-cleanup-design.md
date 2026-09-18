# 龙宫传送：删除 Y&gt;8848 机制 + 落点常量固化（设计说明）

**Date:** 2026-09-16
**Status:** **已实施**（代码构建通过；实机验证待用户执行）
**范围:** BeLoong-Core `disaster2` 分支

> 本文**不改动任何既有文档**（按用户要求）。它记载本轮对 `[dimension_transport]` 的删减，
> 以及后续"龙宫传送门 + 传送 API"的方向。
> 与被删机制相关的旧文档（`docs/superpowers/specs/2026-05-28-dimension-transport-design.md` 等）
> **保持原样、不加注记**，如需了解历史设计请直接读它们。

---

## 1. 本轮需求

1. **删除**"在主世界 Y &gt; 8848 触发传送（→ 龙宫）"机制的**相关代码与配置项**；
2. **保留**"龙宫 Y &lt; 0 → 主世界出生点"的传送行为；
3. 技能里仍需要的目标坐标改为**硬编码常量**（不再有配置项）。

---

## 2. 为什么删：它是死配置（证据）

| 项 | 值 | 出处 |
|---|---|---|
| 该方向的触发条件 | `player.getY() > owToLP_triggerY` | `DimensionTransportHandler.tryTransportToConfiguredDestination`（已删） |
| `triggerY` 默认值 | `8848` | 原 `Config.java` 第 346-348 行（已删） |
| 主世界实际可站高度上限 | `maxBuildHeight = 320`（`min_y -64` + `height 384`） | 原版 |

⇒ `getY() > 8848` 在主世界**永假**，该方向 `enabled=true` 但**从未可能触发**。
同时它是"龙宫 → 主世界"那条兜底网的镜像，去掉后另一条不受影响。

---

## 3. 删除清单（已执行）

| # | 文件 | 内容 |
|---|---|---|
| 1 | `Config.java` | `DimensionTransport` 内 6 个字段：`owToLP_enabled` / `_triggerY` / `_targetDimension` / `_targetX` / `_targetZ` / `_fallbackY` |
| 2 | `Config.java` | 注册块里 `SERVER_BUILDER.push("overworldToLoongPalace")` 整节（6 个键 + 注释），并把 `dimension_transport` 节的注释改写为"龙宫传送的兜底安全网" |
| 3 | `DimensionTransportHandler.java` | `onPlayerTick` 中对 `tryTransportToConfiguredDestination` 的调用 |
| 4 | `DimensionTransportHandler.java` | `tryTransportToConfiguredDestination` 方法本体；随之删除失效的 `import com.zonlong.beloong.util.LandingY`；类 javadoc 补写变更说明 |
| 5 | `assets/beloong/lang/{zh_cn,en_us}.json` | 成为孤儿的键：`message.beloong.dimension_transport.invalid_dimension`、`message.beloong.tp_loong_palace.invalid_dimension`（后者因目标维度改为常量、不再有"解析失败"分支）；以及配置界面键 `beloong.configuration.overworldToLoongPalace` / `.button` / `.tooltip`。**中英各删 5 条**，删后两份文件均为 194 条且键集合完全一致 |
| 6 | `run/config/beloong-server.toml`（实例，非仓库） | 删除孤儿节 `[dimension_transport.overworldToLoongPalace]`（避免启动时产生配置修正记录） |

---

## 4. 落点常量固化（原配置 → 编译期常量）

`ability/TpLoongPalaceEffect.java` 原先从被删的配置节读取 4 个值。现固化为：

```java
private static final String LOONG_PALACE_DIM = "beloong:loong_palace";  // 原 owToLP_targetDimension 默认值
private static final double TARGET_X   = 0.5;   // 原 owToLP_targetX 默认值
private static final double TARGET_Z   = 0.5;   // 原 owToLP_targetZ 默认值
private static final double FALLBACK_Y = 65.0;  // 原 owToLP_fallbackY 的【类默认值】
```

**取向**：与 `DisasterPortalBlock` 的维度 ID 同一做法——结构性内容不进配置，避免"改配置绕过设计"。

> ⚠️ **一处行为微调（记录在案）**：实测开发实例的配置文件里 `fallbackY` 是 **64.5**
> （`docs/plans/2026-09-12-dimension-transport-nonblocking-design.md` 记载过这批"旧存档残留"，
> 该文档当时把类默认值对齐为 65.0，但实例文件不会被覆盖）。
> 固化后统一为 **65.0** ⇒ 该实例的龙宫落点 Y 由 64.5 变为 65.5（`fallbackY + 0.5` 的中心对齐仍在）。
> 这是"消灭新旧存档不一致"的必然结果，不是回归。

**连带代码简化**：`teleportToLoongPalace` 里"解析 `targetDimension` 字符串 + 失败提示"的分支整段删除
（常量解析不可能失败），改用 `ResourceLocation.parse(LOONG_PALACE_DIM)`。

---

## 5. 保留不动的部分

| 项 | 说明 |
|---|---|
| 龙宫 → 主世界（兜底安全网） | `tryTransportToOverworldSpawn`：玩家在龙宫且 `getY() < lpToOw_triggerY(0)` → `overworld.getSharedSpawnPos() + 0.5`，**不查高度图**（落点 Y 恒为出生点自身的 Y） |
| 配置项 | `checkIntervalTicks`(20) / `cooldownTicks`(100) / `[dimension_transport.loongPalaceToOverworld].enabled` / `.triggerY`(0) |
| 实现方式 | 仍是 `PlayerTickEvent.Post` 轮询 + 每玩家静态 `Map<UUID,Integer>`（登出清理）——**未被改为事件驱动**，本轮不涉及 |
| 技能本身 | `beloong:tp_loong_palace` 照旧；数据包 `usage_blocked` 仍限定"只在主世界/龙宫可施放" |
| 技能的回程落点 | **仍为"玩家重生点优先，否则世界出生点"**（本轮未改，见 §7 待办） |

---

## 6. 静态验证（本轮已执行）

| 项 | 结果 |
|---|---|
| `gradlew.bat build` | `EXIT=0` |
| `owToLP_` / `tryTransportToConfiguredDestination` / `invalid_dimension` | 源码与资源里 **0 处代码引用**（仅剩 `TpLoongPalaceEffect` 里 2 条"原配置名"注释，有意保留） |
| `overworldToLoongPalace` | 仅剩 `TpLoongPalaceEffect` javadoc 的 1 处历史说明 |
| 保留项仍在 | `lpToOw_` 6 处、`dimension_transport.dimension_not_found` 3 处、`tp_loong_palace.dimension_not_found` 4 处、`tryTransportToOverworldSpawn` 2 处 |
| 语言文件 | 两份 JSON 均可解析，**194 = 194 条，键集合完全一致** |
| 实例配置 | `[dimension_transport]` 下只剩 `loongPalaceToOverworld` |

---

## 7. 后续任务（按你的规划，尚未开始）

### 7.1 待定：回程落点口径

技能目前"回主世界"是**重生点优先**；而兜底安全网是**恒世界出生点**。你已选定的口径是
**恒为世界出生点**（二者对齐），但本轮未改技能——改动点是把 `TpLoongPalaceEffect.teleportToOverworldSpawn`
里的 `getRespawnPosition()` 分支删掉。**待你确认后执行。**

### 7.2 龙宫传送门（下一步主任务）

形态已定：**完全照抄原版下界门**（黑曜石框架 + 打火石激活 + 走进触发），只改传送目标。
落点语义也已定：**龙宫侧固定坐标；主世界侧恒为世界出生点**（无返回点记忆）。
细节（框架材料/尺寸、激活物、龙宫侧落点坐标）留到该轮再谈。

### 7.3 传送 API（与传送门同批）

与天灾门同一取向：把"落点解析 + transition 构造"抽成公开件、门方块只做登记。
天灾侧已具备 `teleport/DimensionTeleport`（门面）+ `teleport/CoordinateLanding`（解析）；
龙宫门与天灾门的**两条差异**是：
1. 龙宫侧落点是**固定坐标 + 固定 Y**（不能用高度图）；
2. 主世界侧落点是**世界出生点**（XZ 也变，不是 1:1）。

推荐把 `Target` 由单一 record 升级为 `sealed interface`（`At(坐标+高度图)` / `Spawn(世界出生点)`），
使一个 API 同时表达两种落点，且天灾门的现有调用**零改动**。是否顺带让现有两条龙宫机制改走该 API，
待该轮一并决定。

### 7.4 已知缺陷（与传送无关，但会被龙宫门放大）

**龙宫侧落点 `(0.5, ?, 0.5)` 在虚空里**：龙宫是 `flat + layers: []` 纯虚空维度，该列无方块
⇒ 高度图必然取不到 ⇒ **永远走 `FALLBACK_Y`**（65.0）⇒ 玩家落在 y=65.5 的空中。
而龙宫实际可站立面（水体区域）在 `x[-54,8] y[68,77] z[-312,-272]`，与 `z=0.5` **完全不相交**。
做门时必须一并定下"龙宫侧落点到底在哪"，否则新门会把这个缺陷固化下来。

---

## 8. Non-Goals（本轮）

- 不动 `docs/` 下任何既有文档（包括不加"已废弃"注记）；
- 不把轮询实现改为事件驱动；
- 不改技能的回程落点（§7.1 待确认）；
- 不新增任何配置项；
- 不实现传送门与 API（§7.2/7.3 是后续任务）。
