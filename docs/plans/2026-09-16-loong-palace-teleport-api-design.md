# 龙宫传送 API 统一（设计说明）

**Date:** 2026-09-16
**Status:** **已实施**（构建通过；实机验证待用户执行）
**Applies to:** BeLoong-Core 0.9.3（`disaster2` 分支）
**实施计划:** [`2026-09-16-loong-palace-teleport-api-implementation.md`](2026-09-16-loong-palace-teleport-api-implementation.md)

> 本文**不改动任何既有文档**（按用户要求）。它记载本轮把"龙宫传送"统一到公共 API 的结果，
> 以及与天灾门共用冷却的实现方式。上一轮"删除 Y&gt;8848 机制"的说明见
> [`2026-09-16-loong-palace-transport-cleanup-design.md`](2026-09-16-loong-palace-transport-cleanup-design.md)。

---

## 1. 需求（用户原话的落实）

| # | 需求 | 落实方式 |
|---|---|---|
| 1 | 其他世界 → 龙宫：**统一为当前行为，保留配置文件** | `TeleportTarget.toLoongPalace` 读 `[dimension_transport.overworldToLoongPalace]` 的 `targetX`/`targetZ`/`fallbackY`；维度 ID 硬编码 |
| 2 | 龙宫 → 主世界：**统一为世界出生点，没有配置** | `TeleportTarget.Spawn(overworld)`：XZ 取出生点中心、Y 取出生点自身；**删掉技能里"重生点优先"分支** |
| 3 | 技能 / Y&lt;0 兜底 / 后续门方块 **都走 1、2 的 API** | 两个调用方已改；门方块留待做门那轮，直接复用同一 API |
| 4 | 冷却**统一到天灾门那套 NBT** | 新增 `TeleportCooldown`；天灾门改为委托它，三条调用方共用同一个键 |

**批准的三项行为变更**

| # | 变更 | 影响 |
|---|---|---|
| A | 技能首次拥有传送冷却（NBT，100 tick），冷却中施放弹 actionbar 提示 | 新增 lang 键 `message.beloong.tp_loong_palace.on_cooldown` |
| B | Y&lt;0 兜底的冷却由"自己的静态 Map"改为同一份 NBT（数值不变） | 刚用技能/刚从门出来会同时影响它 |
| C | `Spawn` 落点**不再查高度图**（原先该路径会查） | 极端情形（出生点列被玩家改动）落点 Y 与高度图不一致 |

---

## 2. API 页面（`com.zonlong.beloong.teleport`）

| 件 | 角色 |
|---|---|
| `TeleportTarget`（新，sealed interface） | **落点目标建模**：`At`（坐标 + 高度图 + 兜底）、`Spawn`（世界出生点，不查高度图）、`toLoongPalace`（读配置的 `At` 特例） |
| `TeleportCooldown`（新） | **全模组共用的 NBT 冷却**：`KEY` / `isOnCooldown` / `mark` / `refreshVanillaToOutlastNbt` |
| `DimensionTeleport`（推广） | 门面：`toTarget(entity, TeleportTarget, post)` + 便捷入口 `toCurrentCoords(...)` |
| `CoordinateLanding`（不变） | `At` 目标的 Y 解析实现（非阻塞 `getChunkNow` + `MOTION_BLOCKING + 1 + 1`） |
| `util/LandingY`（保留、无调用方） | 留给"落点固定但不介意 Y 精度、也不想引入等待状态"的场景；javadoc 已注明当前无调用方 |

### 2.1 三种目标的 Y 策略（本 API 存在的唯一理由）

| 目标 | XZ | Y | 失败时 |
|---|---|---|---|
| `At` | 原样 | 高度图 + 1 + 1 | 有 `fallbackY` 就用它；为 `null` ⇒ 返回 `null`（本 tick 不传送） |
| `Spawn` | 出生点中心（+0.5） | **出生点自身的 Y** | 不会失败（不依赖高度图） |
| `toLoongPalace` | 配置 `targetX/targetZ` | 同 `At`（龙宫是纯虚空 ⇒ 实际恒为 `fallbackY`） | 配置 `fallbackY` |

### 2.2 契约（与天灾门那轮一致，未变）

`DimensionTeleport` **只做"解析 Y + 构造 transition"**：
① 仅服务端主线程；② 非阻塞；③ `null` = 本 tick 不传送（由调用方决定重试或放弃）；
④ 不写任何状态（票据、超时、冷却、提示全在调用方）；⑤ `At` 的 `fallbackY=null` 语义如上表。

---

## 3. 调用方映射（改造后）

| 调用方 | 去龙宫 | 回主世界 | 冷却 |
|---|---|---|---|
| 技能 `TpLoongPalaceEffect` | `TeleportTarget.toLoongPalace(lp)` | `TeleportTarget.Spawn(overworld)` | 施放前 `isOnCooldown`（拦下则 actionbar 提示）；`post` 里 `mark` |
| `DimensionTransportHandler`（Y&lt;0） | —（该方向已删除） | `TeleportTarget.Spawn(overworld)` | 每 tick 先 `isOnCooldown`；`post` 里 `mark` |
| 天灾门 `DisasterPortalBlock`（回归验证） | `toCurrentCoords(..., fallbackY=null)` | `toCurrentCoords(..., fallbackY=player.getY())` | `entityInside` 用 `isOnCooldown` + `refreshVanillaToOutlastNbt`；`mark` 在 `postTransition`/`failDestination` |
| 后续龙宫门方块 | 复用上表第 1 行 | 复用第 2 行 | 复用 `TeleportCooldown` |

**技能 JSON 同步调整**：`data/dragonsurvival/.../dragon_ability/tp_loong_palace.json` 的
`cooldown` 由 `0.0` 改为 `5.0`（= 100 tick），使数据侧冷却与 NBT 冷却对齐，
避免"技能转好了但传送还在冷却"这一 2 秒空窗反复弹提示。

---

## 4. 配置（`beloong-server.toml`）

```
[dimension_transport]
    checkIntervalTicks = 20            # Y 阈值轮询间隔（保留）
    [dimension_transport.overworldToLoongPalace]   # 节名沿用历史名，内容只服务"去龙宫落点"
        targetX   = 0.5
        targetZ   = 0.5
        fallbackY = 65.0（实例现值 64.5 就地保留）
    [dimension_transport.loongPalaceToOverworld]
        enabled  = true                # 触发开关（保留）
        triggerY = 0                   # Y < 此值时触发（保留）
```

**删除**：`cooldownTicks`（冷却统一到 `[disaster_portal].teleportCooldownTicks`）。
**不恢复**：`enabled` / `triggerY`（属已删除的"Y&gt;8848"机制）。
**节名不改**的原因：你实例里已有的三个值**原地继续生效**，不会被重置成默认值。

---

## 5. 静态验证结果（2026-09-16 实测）

| 项 | 结果 |
|---|---|
| `gradlew.bat build` | **EXIT=0** |
| `LandingY` 调用方 | **0**（仅剩 `CoordinateLanding` javadoc 的分工说明 + 自身）；javadoc 已加"当前无调用方"注记 |
| NBT 冷却键定义 | **仅 1 处**（`TeleportCooldown.KEY`）；天灾门内不再直写 NBT |
| 三种目标均被使用 | `toLoongPalace` 1 处调用、`TeleportTarget.Spawn` 2 处调用、`toCurrentCoords` 1 处调用 |
| 门面签名（`javap`） | `toTarget(Entity, TeleportTarget, PostDimensionTransition)` + `toCurrentCoords(Entity, ServerLevel, Double, PostDimensionTransition)`，与设计一致 |
| 旧物残留 | `DimensionTeleport.Target` 0、`owToLP_` 0、`getRespawnPosition` 0（技能里"重生点优先"已删） |
| `cooldownTicks` | 与龙宫/天灾无关的 `cooldownTicks = ` 命中只有 2 处（`BeloongWater.triggerCooldownTicks`、`DisasterPortal.teleportCooldownTicks`） |
| 阻塞 API | `teleport/` + 三个调用方内 `getChunk(`/`managedBlock`/`.join()` **仅 javadoc 命中** |
| 语言文件 | 中英各 **195** 条、键集合一致（新增 `message.beloong.tp_loong_palace.on_cooldown`） |
| 技能 JSON | 合法；`cooldown = 5.0`、`cast_time = 60.0` |

**已知技术风险（记录在案）**：`TeleportCooldown` 用 `player.level().getGameTime()` 作时间基准，
跨维度后 `player.level()` 已切到目标维度。当前三个维度都是 `natural: true` 且同源推进，故成立；
若将来出现 `natural: false` 的维度或单独改动某维度时间，冷却时长会偏差。已写进类 javadoc。

---

## 6. 实机验收判据（待用户执行）

| 编号 | 判据 |
|---|---|
| V1 | 技能：主世界 → 龙宫落在 `(targetX, ?, targetZ)`；龙宫 → 主世界**恒落世界出生点**（先在主世界睡床设重生点，验证**不再回床边**） |
| V2 | 技能连按：第二次被拦下并显示 actionbar「传送冷却中，请稍后再试」 |
| V3 | 龙宫 Y&lt;0 掉出：仍回世界出生点；且**刚用过技能/门时被同一冷却拦住**（变更 B 的判据） |
| V4 | 天灾门回归：下行/上行与上一轮验收完全一致（1:1 + 高度图、无超时/报错） |
| V5 | 改 `overworldToLoongPalace.targetX` 后技能落点随之改变（证明配置仍生效） |

---

## 7. 未做（后续）

- **龙宫传送门方块**（照抄原版下界门：黑曜石框架 + 打火石激活 + 走进触发）：
  经核实，"照抄原版"有一处**无法照抄**——`PortalShape.createPortalBlocks()` 硬编码放置
  `Blocks.NETHER_PORTAL`（`PortalShape.java:167`）且 `bottomLeft/width/height` 为 private；
  `BaseFireBlock` 同样硬编码。故门方块那轮需要**自己写几行框架探测 + 点火**，
  玩家观感可完全一致。这一轮已把"传送目标"这一层做干净，门方块只需接上。
- **点火/框架探测工具**（若做成公共件，可放同一 `teleport` 包）。
- **注册表型门框架 API**（`IPortalKind` 等）：无第二个使用方，暂不做。
- **龙宫侧精确落点待实测**：龙宫是 `flat + layers: []` 纯虚空维度，默认落点 `(0.5, 65.5, 0.5)`
  在**空中**；龙宫实际可站立面（水体区域）在 `x[-54,8] y[68,77] z[-312,-272]`，
  与 `z = 0.5` 完全不相交。做门那轮必须一并定下龙宫侧落点。
- **`LandingY` 的最终去留**：本轮按计划**保留**（无调用方）。若确认不再需要，可单独删除。
