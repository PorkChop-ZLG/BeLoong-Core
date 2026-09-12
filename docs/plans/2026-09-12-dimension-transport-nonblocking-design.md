# 维度传送落点解析非阻塞化 设计文档

**Date:** 2026-09-12
**Status:** Approved（用户确认：B-1 + `util/LandingY` 纯函数 + `fallbackY=65.0`）
**Approach:** B-1「未命中 → 兜底立即传送」+ (c) 零状态纯函数

## Problem Statement

`transport/DimensionTransportHandler` 与 `ability/TpLoongPalaceEffect` 中**共 4 处**在主线程上
**同步加载目的地区块再查高度图**，与已修复的「天灾传送门主线程永久卡死」是逐字同源的写法：

| 站点 | 触发 | 目的地 | 目的地精确形式 |
|---|---|---|---|
| `DimensionTransportHandler:126` | `PlayerTickEvent.Post`（按 `checkIntervalTicks`） | 配置目标（龙宫） | `owToLP_targetX/Z` + `owToLP_fallbackY` |
| `DimensionTransportHandler:172` | 同上 | 主世界世界出生点 | `spawnPos`（Y 直接取 `spawnPos.getY()`） |
| `TpLoongPalaceEffect:81` | 龙技能 `AbilityEntityEffect.apply` | 配置目标（龙宫） | 同站点 1 |
| `TpLoongPalaceEffect:119` | 同上 | 主世界重生点/出生点 | `targetPos`，兜底 `targetPos.getY()+0.5` |

风险：一旦目的地区块生成 future 永不完成（实机已发生过一次工作线程 `StackOverflowError`），
主线程会在 `managedBlock` 上**永久静默卡死**。

## Design

### Architecture

**零新增状态、零票据、零超时。** 唯一新增的是一个纯函数：

```
触发点（enabled 判定 / 触发条件 / 维度校验 / stopRiding / 玩家消息 全部保持原样）
   → LandingY.resolveOrFallback(targetLevel, x, z, fallbackY)     // 非阻塞；唯一新增调用
   → player.teleportTo(ServerLevel, x, y, z, Set.of(), yRot, xRot)
        （其内部：addRegionTicket(POST_TELEPORT, 落点区块, 1) → changeDimension(DimensionTransition)）
   → finishTransport / fallDistance = 0（原样）
```

之所以不需要等待机制：`ServerPlayer#teleportTo(ServerLevel, …)`（`ServerPlayer.java:1535-1553`）
**自带 `TicketType.POST_TELEPORT` 落点票据**，落点区块在传送后必然被加载。放弃的只是"传送前 Y 的精度"。

**明确不做**：异步线程（Approach C）、公共延迟传送服务/状态机（Approach A）、修改已验收的 `DisasterPortalBlock`。

### Components

| 文件 | 动作 |
|---|---|
| `util/LandingY.java` | **新建**：单方法纯函数 `resolveOrFallback(ServerLevel, double x, double z, double fallbackY)` |
| `transport/DimensionTransportHandler.java` | 站点 1 改用 `LandingY`；站点 2 **删除无用 `getChunk`**；清 `Heightmap` import |
| `ability/TpLoongPalaceEffect.java` | 站点 3/4 改用 `LandingY`；清 `Heightmap` import |
| `Config.java` | `owToLP_fallbackY` 默认 `64.5 → 65.0`（注释同步） |

`LandingY` 语义（与旧代码**逐字等价**）：

```java
LevelChunk chunk = level.getChunkSource().getChunkNow(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
if (chunk == null) return fallbackY;                       // 未加载：不等待、不加载
int topBlockY;
if (在 ±3000 万范围内) topBlockY = chunk.getHeight(MOTION_BLOCKING, x&15, z&15) + 1;  // ≡ Level#getHeight
else                  topBlockY = level.getSeaLevel() + 1;                              // ≡ Level#getHeight 越界分支
return topBlockY > level.getMinBuildHeight() ? topBlockY + 1.0D : fallbackY;
```

等价性依据：旧代码 `Level#getHeight` 在**未加载**时走 `hasChunk` 为假 → 返回 `getMinBuildHeight()`
→ 谓词为假 → 使用 `fallbackY`；新代码把这一分支提前为显式 `chunk == null → fallbackY`。
`Mth.floor(teleportX)` 在原三处均等于旧代码的 `blockX/blockZ`（Palace: `floor(0.5)=0`；
重生点: `floor(targetPos.getX()+0.5)=targetPos.getX()`）。

### Data Flow

无线程、无跨 tick、无状态：单次调用内完成"探测 → 取 Y → 传送"。落点区块的实际加载发生在传送之后
（`POST_TELEPORT` 票据 + `changeDimension`），由客户端显示正常的维度切换加载界面。

### Error Handling

| 情形 | 行为 |
|---|---|
| 落点区块未加载 | 直接使用传入的 `fallbackY`（Palace → 配置值；重生点 → `targetPos.getY()+0.5`） |
| 区块已加载但该列为虚空（模板未应用） | `getFirstAvailable == minBuildHeight` → 谓词为假 → 兜底 ✓（与旧代码一致） |
| 坐标越界（±3000 万） | 沿用 `Level#getHeight` 的 `seaLevel + 1` 分支 |
| 非主线程调用 `LandingY` | `getChunkNow` 在非主线程恒返回 `null` ⇒ 静默走兜底；已在 javadoc 标注"仅服务端主线程" |
| 目标维度/重生维度不存在 | 保持现有 early-return + 玩家消息，不变 |
| 落点生成永久失败 | 服务器**不再卡死**；玩家可能卡在客户端加载界面或落在未就绪区块（残余风险，已确认接受） |
| 重生点被封闭建筑包围 | `+0.5` 兜底可能落进方块（已确认接受，值保持不变） |

## Evidence（实测，2026-09-12）

从实例模板 `template\dimensions\beloong\loong_palace\region\r.0.0.mca` 的区块 `(0,0)`
直接解析 `Heightmaps.MOTION_BLOCKING`（大端 NBT + `SimpleBitStorage` 9 位/7 项每组）：

| 数据 | 值 |
|---|---|
| 落点列 `(0,0)` | `getFirstAvailable = 64` → 最高实体方块 **Y=63**，**地表 64.0** |
| 整块 256 列分布 | 253 列 = 64；3 列 = 65/66/67（装饰，不在落点列） |
| 旧「已加载」路径落点 | `Level#getHeight(64) + 1.0` = **65.0** |
| 旧 `fallbackY` | 64.5 ⇒ 与已加载路径差 0.5 |

⇒ 取 **65.0** 使两条路径**完全不可区分（Δ=0）**，且不改变已验证的"已加载路径"行为。

## Decisions Made

1. **B-1（兜底立即传送）** 而非 A（公共延迟服务）/ C（异步线程）：A 被判定过于复杂；
   C 会引入线程边界与生命周期竞态，且生成永久失败时仍需超时兜底。
2. **公式集中在 `util/LandingY` 纯函数**（三处调用，一份实现），而非三处内联 ⇒ 避免语义漂移。
3. **`DimensionTransportHandler:172` 直接删掉无用加载**：该行结果从未被使用（Y 恒为 `spawnPos.getY()`），
   行为零变化；世界出生点区块由 `TicketType.START` 常驻加载。
4. **`owToLP_fallbackY` 默认值 64.5 → 65.0**，与实测对齐；**代码默认值只影响全新安装**。
5. **不改 `DisasterPortalBlock`**（其公式的兜底语义不同：无 `fallbackY` 概念，越界用 `seaLevel+2`）。

## Non-Goals

- 不改触发条件（`enabled`/`triggerY`/`checkIntervalTicks`）与落点坐标
- 不改 `TpLoongPalaceEffect` 的技能注册、DS 冷却、重生点三级选择逻辑
- 不引入任何跨 tick 状态、票据申领、超时或玩家提示
- 不修改整合包实例内的 `config/beloong-server.toml`（由整合包侧自行同步）
- 不做第 3 项「传送门界面」（另行设计）

## Verification Strategy

1. `grep -rn "\.getChunk(" src/main/java/com/zonlong/beloong/` ⇒ 仅剩 `WaystonePlacementHandler` 的 `event.getChunk()`（事件对象，无关）
2. `gradlew.bat build` 通过
3. 逐站点对照旧/新公式（已加载 / 未加载两分支）
4. 实机（整合包侧）：龙技能 主世界→龙宫；龙宫掉出底部救回主世界；龙宫→主世界重生点。
   并在 `/execute in beloong:loong_palace run forceload add 0 0` **前后各测一次**，覆盖两条分支
5. 日志：无新增锚点，沿用各处原有 DEBUG

## Implementation Order

1. `util/LandingY.java` → 2. 三处调用点替换 + 删无用加载 + import 清理 → 3. `Config` 默认值
→ 4. 构建 + 静态闸门 → 5. 文档同步（总设计 §九 / 复盘文档 / memory 决策日志）

## Follow-ups（交给整合包侧）

- 若希望游戏内立即生效 Δ=0：把 `config/beloong-server.toml` 的
  `dimension_transport.overworldToLoongPalace.fallbackY` 从 `64.5` 改为 `65.0`（本轮按要求不修改实例文件）
- 站点 1（`DimensionTransportHandler:126`）当前配置 `triggerY = 10000`，而主世界最高 Y=320 ⇒ 实际不可达
