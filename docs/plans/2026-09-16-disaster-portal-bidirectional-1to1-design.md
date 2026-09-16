# 天灾传送门：双向 1:1 传送 + 传送 API 预留 设计文档

**Date:** 2026-09-16
**Status:** **Implemented（2026-09-16，静态验证通过；实机验收待用户执行）**
**Approach:** A —— 抽出「1:1 落点解析层」+「传送门面层」，方块退化为薄适配器
**Applies to:** Minecraft 1.21.1 / NeoForge 21.1.236 / BeLoong-Core 0.9.3（`disaster2` 分支）

**实施结果**：新增 `teleport/CoordinateLanding`、`teleport/DimensionTeleport`；`DisasterPortalBlock`
删除 `createUpwardTransition` / `createDownwardTransition` / `requestDestinationChunk` / `disasterLevel`
与 `DESTINATION_TICKET_RADIUS`，改为双向共用一次 `toTarget` 调用。静态验证结果见 §7.3。

**取代范围**：本文取代 `docs/天灾维度总设计.md` §3.6 的**传送逻辑部分**（上行/下行落点规则），
以及决策 31/33 中与"上行回重生点""落点兜底"相关的表述。其余（激活流程、渲染、过渡界面、
冷却语义）仍以总设计为准。

---

## 1. 需求

1. **非天灾维度**使用天灾传送门 → 前往 `beloong:disaster`，**1:1 坐标**，查高度图确保落在地表，无返回传送门。
2. **天灾维度**使用天灾传送门 → 前往 `minecraft:overworld`，也是 **1:1 坐标**，查高度图确保落在地表，无返回传送门。
3. 讨论：传送机制能否提供 API，供本模组或其他模组复用做维度传送。

**两条需求都必须参考 2026-09-12 的主线程死锁教训**（`docs/天灾传送门主线程死锁-根因与修复复盘.md`）：
主线程**绝不**用 `Level#getChunk` / `Level#getHeight`（阻塞版）；只允许
`ServerChunkCache#getChunkNow`（非阻塞探测）+ `TicketType.PORTAL` 区域票据 + 下一 tick 重试 + 有界超时。

---

## 2. 设计

### 2.1 架构

```
com.zonlong.beloong.teleport/
    DimensionTeleport.java      ← 公开入口（未来通用跨维度传送 API 的门面）
    CoordinateLanding.java      ← 包内解析器：1:1 坐标 + 高度图 + 兜底
```

- **`teleport` 是本模组的公开 API 包**。不放进 `com.zonlong.beloong.api`：那会暗示"已经是一套 API"，
  与本阶段实际内容（一个门面 + 一个解析器）不符；但包名与类名按**通用跨维度传送**命名，
  将来的邻居（注册表 / 落点策略 / 事件）放同一包。
- `util/LandingY`（龙宫 ↔ 主世界在用）**留在原地、不合并、不委托**——见 §2.3。

### 2.2 组件与签名

```java
// —— 解析层：无状态、纯函数、显式表达"还没好" ——
public final class CoordinateLanding {
    /** 1:1 坐标落点。命中内存区块 → MOTION_BLOCKING 高度 + 1 + 1（水面即水面）；
     *  区块未加载 / 列为虚空（topBlockY <= minBuildHeight）→ null；
     *  坐标越界（|x| 或 |z| >= 3000 万）→ null。 */
    public static @Nullable Double resolve(ServerLevel target, int blockX, int blockZ);

    /** 非阻塞预热：落点区块已在内存则什么都不做，否则申领 TicketType.PORTAL（半径 0）。 */
    public static void requestChunk(ServerLevel target, int blockX, int blockZ, BlockPos anchor);
}

// —— 门面层：公开入口，未来 API 的落点 ——
public final class DimensionTeleport {
    /** fallbackY == null ⇒ 只走"精确落点"（下行口径）；非 null ⇒ 拿不到精确落点时用它（上行口径）。 */
    public record Target(ServerLevel level, double x, double z, @Nullable Double fallbackY) {}

    /** null = 本 tick 不传送（落点未就绪且无兜底 Y）。不抛异常、不写状态、不阻塞。 */
    public static @Nullable DimensionTransition toTarget(Entity entity, Target target);
}
```

**职责边界**

| 层 | 只回答 |
|---|---|
| `CoordinateLanding` | "Y 是多少" 与 "现在要不要申领票据" |
| `DimensionTeleport` | "这一次能不能走、走的话 transition 长什么样" |
| `DisasterPortalBlock`（调用方） | 进门登记、冷却、目标维度选择、**600 tick 上限**、失败报错与玩家提示、过渡与音效 |

**为什么 600 tick 留在调用方**：上限的载体是原版状态 `player.portalProcess.getPortalTime()`，
且"超时后怎么办"是**业务**（本门的失败提示 + 停止重试），不是传送能力。工具类一旦持有超时，
就被迫持有状态、被迫知道"谁在等"——正是本方案要避免的耦合。

### 2.3 与 `util/LandingY` 的分工（**不要合并**）

| | `LandingY.resolveOrFallback` | `CoordinateLanding.resolve` |
|---|---|---|
| 服务对象 | 龙宫 ↔ 主世界（`ServerPlayer#teleportTo`） | 本门（`DimensionTransition`） |
| 语义 | 落点坐标固定，**不需要**精确 Y；一定给一个值 | 必须精确落点，**可以等**；拿不到就说"没拿到" |
| 拿不到时 | 返回调用方给的 `fallbackY` | 返回 `null`（由调用方决定等 / 兜底） |
| 票据 | 不申领（依赖 `POST_TELEPORT` 5 tick + 玩家自身 PLAYER 票据） | 申领 `TicketType.PORTAL`（半径 0） |

两者 javadoc 互相点名，防止后人误用：需要"精确落点、可以等一 tick"的场景用 `CoordinateLanding`；
落点固定、不介意精度、不想引入等待状态的场景用 `LandingY`。

### 2.4 数据流（服务端，接触即 0 tick 倒计时）

```
进门第 1 tick  entityInside
    冷却判定（NBT beloong_portal_cooldown）
    ↓ 冷却期内：顶住原版冷却，return（不登记）
    if (player.isPassenger()) stopRiding()
    setAsInsidePortal(this, pos)                      ← 原版范式：只登记
    CoordinateLanding.requestChunk(target, x, z, pos)  ← 双向都做（非阻塞，只申领票据）

每 tick  Entity.handlePortal → PortalProcessor.processPortalTeleportation
    portalTime++ >= getPortalTransitionTime() == 0
    → DisasterPortalBlock.getPortalDestination(level, entity, pos)
         方向：非天灾 → DISASTER_LEVEL ；天灾 → Level.OVERWORLD
         Target(level, entity.getX(), entity.getZ(), fallbackY)
             fallbackY：下行 = null ；上行 = entity.getY()（见 §2.5）
         DimensionTeleport.toTarget(entity, target)
             → CoordinateLanding.resolve(...)
                  非 null ⇒ new DimensionTransition(target, (x, Y, z), Vec3.ZERO, yRot, xRot, postTransition())
                  null 且 fallbackY != null ⇒ 用 fallbackY 构造（同样带 postTransition）
                  null 且 fallbackY == null ⇒ 返回 null（本 tick 不传）
         返回 null 且 waitedTicks > 600 ⇒ failDestination（ERROR + 提示 + NBT 冷却）
         返回 null 且未超时 ⇒ setPortalCooldown(0) + 下一 tick 重来

换维度后  postTransition()：fallDistance = 0 ；写 beloong_portal_cooldown
```

**`resolve` 的确切判定**

```
chunk = target.getChunkSource().getChunkNow(x >> 4, z >> 4)
chunk == null                                  → null
|x| >= 3000 万 || |z| >= 3000 万                → null
topBlockY = chunk.getHeight(MOTION_BLOCKING, x & 15, z & 15) + 1   // ChunkAccess 口径
topBlockY > minBuildHeight ? topBlockY + 1.0 : null                // 虚空列 → null
```

- 水面：`MOTION_BLOCKING` **含流体** ⇒ 海洋列返回水面 Y，玩家落在水面上（用户明确要求，不做接地搜索）。
- 虚空列与"未加载"对调用方**不可区分**（都是 null）⇒ 统一走"重试到超时再兜底"。

### 2.5 上行兜底 Y 的定义

上行入口只能知道"当前在天灾维度"，因此兜底 Y 取 **`entity.getY()`**（即传送所依据的、玩家在天灾侧的 Y），
而非重生点/海平面：

- 常见情形：玩家站在天灾侧地表进门（Y≈90）⇒ 超时兜底即主世界 `(x, 90, z)`；
- 代价：极端情形（等满 600 tick 才走兜底、且主世界对应点是山峰/峡谷）玩家会**悬空或嵌进方块**，
  之后由重力与区块加载自然收敛。这是"永远能过去"换来的代价，用户已确认接受。

### 2.6 下行行为：**刻意与现状等价**（本次不做行为变更）

下行除以下两点外与现状**逐字等价**（详见 §4 决策 D6/D7）：

| 差异 | 现状 | 新方案 | 取向 |
|---|---|---|---|
| 虚空列（该列无方块） | `getHeight + 1 + 1 = minBuildHeight + 1.0`，直接传送 | `resolve` 返回 null → 等待 → 超时后无兜底（下行 fallbackY 为 null）⇒ 报错+提示 | 用户裁定：**接受新方案** |
| 坐标越界（≥3000 万） | 有显式的 `targetY = seaLevel + 2.0` 分支，直接传送 | 同上（null → 等待 → 报错） | 用户裁定：**接受新方案** |
| 目标维度缺失 | 每 tick 一条 ERROR，永不超时 | 首轮一条 ERROR，600 tick 后 `failDestination` | 用户裁定：**接受新方案** |

> 严格说"越界"这处**不是**"拿不到值"的差异：现状 `Level#getHeight` 的越界分支返回 `seaLevel + 1`、
> 外层再 `+1` 得 `seaLevel + 2.0`，与 `LandingY` 的越界口径**相同**。新方案的差异在于
> `resolve` 直接把越界判成 null（不再走 `seaLevel` 分支），属**主动选择的简化**。
> 这一条只在天灾门生效，`LandingY` 保持 `seaLevel + 2.0` 不动。

前两项在实机上**不可达**（虚空列需要 `getChunkNow` 命中一个高度图为空的列；越界坐标受世界边界与传送来源限制），
第三项在天灾维度随数据包强绑的前提下**不可能发生**。因此"下行不变"的实质保证是：
**1:1 坐标、`MOTION_BLOCKING + 1 + 1`、票据类型/半径、`waitedTicks > 600` 判据、transition 构造、冷却语义
全部逐字保留**；且下行的 `Target.fallbackY` 恒为 `null`，兜底路径在下行**结构上不可达**。

### 2.7 不生成返回门

代码中**不存在**任何生成/放置返回门或返回结构的逻辑（`docs/天灾维度总设计.md` §九 早已把
"下行时自动生成返回传送门结构"标为已取消）。本次把
**「上行不生成返回门、也不回玩家重生点/世界出生点」写成硬约束**，并删除
`createUpwardTransition` 里的三级回退（`getRespawnPosition` → `getSharedSpawnPos` → `(0,64,0)`）
与 `+0.5` 中心对齐。

---

## 3. 错误处理与边界

| 情形 | 行为 | 玩家可见后果 |
|---|---|---|
| 目标维度未加载（`getLevel` 返回 null） | 首轮 ERROR 一条 + 返回 null，**不申领票据**；600 tick 后 `failDestination` | 留在门里；30 s 后收到超时提示 |
| 目标区块未就绪 | `TicketType.PORTAL` 刷新 + `setPortalCooldown(0)` + 返回 null | 门里等待，扭曲渐强 |
| 目标列为虚空 / 坐标越界 | 与"未就绪"**完全同流** | 先等，超时后走兜底（上行）或报错（下行） |
| 超时 600 tick | ERROR + `message.beloong.disaster_portal.destination_timeout` + 写 NBT 冷却 | 提示"目标区块 %s, %s 未能加载，请稍后重试" |
| 世界边界 | 落点由 1:1 坐标决定，不乘 `coordinate_scale` | 由原版边界推回/扣血，本模组不额外处理 |
| 玩家死亡/退出/换维度 | `portalProcess` 随实体消失；票据自然过期 | 无残留状态（本门不在任何静态表里存状态） |

**超时的真实语义（须写入文档口径，勿再写成"直到离开传送门"）**：
"停止重试"实际是"**NBT 冷却期内不重试**"，由两件事共同成立，**两者都不能动**：
① 冷却期内 `entityInside` 不进 `setAsInsidePortal`；② 同分支把原版冷却顶到 `(NBT 剩余) + 1`
（原版**玩家**冷却只有 10 tick，`Player#getDimensionChangingDelay` 覆写）。
⇒ 玩家若一直站在门里，每约 `冷却(100) + 600` tick 会再失败一次。

**线程契约（javadoc 硬约束）**：`CoordinateLanding` / `DimensionTeleport` **仅限服务端主线程**。
`getChunkNow` 在非主线程恒返回 null，会静默退化为"一直等"——表现为"传送永远不成功"而不是报错。

**不引入 try/catch**：`toTarget` 的全部判据都是纯读操作（null 判定 + 高度图读取），无强制异常；
失败以"返回 null"表达。若将来加入可能抛异常的逻辑，再按决策 23 的口径评估。

---

## 4. 决策记录

| # | 决策 | 理由 |
|---|---|---|
| D1 | **双向 1:1 坐标**，上行目标硬编码 `minecraft:overworld` | 需求 1/2 明确；不加配置项（与群系映射表同理，属结构性内容）。代价：从整合包自定义维度进天灾的玩家，回来落在主世界对应坐标——但比"回重生点"更可预期 |
| D2 | 落点规则**双向共用一条**：`MOTION_BLOCKING` 高度 + 1 + 1 | 需求 1/2 的落点语义相同；共用即"不会只改好一个方向" |
| D3 | **水面就放水面**，不做接地搜索 | 用户明确选择。`MOTION_BLOCKING` 含流体，深海列落在水面之上 |
| D4 | **无返回门 + 不回重生点** | 需求 1/2 明确；删除三级回退与 `+0.5` 对齐 |
| D5 | 高度图拿不到 ⇒ **先重试到 600 tick，再兜底** | 用户明确选择"等一下再传"。与下行对称，复用同一套非阻塞重试 |
| D6 | 兜底 Y = **`entity.getY()`**（传送所用 Y） | 用户明确。极端情形可能悬空/嵌块，接受该代价换取"永远能过去" |
| D7 | 虚空列与越界坐标**不再有下行专属分支**，统一走"null → 等待 → 兜底/报错" | 用户裁定："新方案几乎等价甚至更好，不用修正差异"。两项实机不可达 |
| D8 | 维度缺失走**统一等待/超时路径**（不新增静态去重状态） | 用户选 A。优雅在于复用既有失败路径、零新状态；日志由"每 tick"降为"首轮一条" |
| D9 | 抽出 `teleport` 包，`DimensionTeleport` 为**公开门面** | 需求 3 的落地方式：先做"内部 + 少量调用方"（①），但包名/签名按通用 API（②）预留 |
| D10 | **不建注册表/事件/KubeJS 绑定** | YAGNI：当前没有第二个使用方；等出现真实需求再长出框架 |
| D11 | 600 tick 上限留在**调用方** | 上限载体是原版 `portalProcess`，且"超时后怎么办"是业务而非传送能力 |
| D12 | `LandingY` **不合并、不委托** | 两者语义不同（一定给值 vs 可以等）；合并会让"精度要求"这一关键差异消失 |
| D13 | 常量全部不变：`PORTAL_TRANSITION_TICKS=0`、`DESTINATION_WAIT_TIMEOUT_TICKS=600`、票据半径 0、`CONFUSION` 过渡、冷却键 `beloong_portal_cooldown` | 与总设计决策 31/33 保持一致；本次只改方向与落点规则 |
| D14 | `getPortalDestination` 签名与上游调用者**保持不变** | `PortalProcessor` 每 tick 调一次；`waitedTicks` 仍取自 `getPortalTime()` ⇒ 超时口径（自进门起算）不变 |

---

## 5. Non-Goals

- **不给下方维度做"传送门结构生成"**：不生成返回门、不生成落脚平台、不做"落地标记"。
- **不加配置项**：目标维度、票据半径、落点规则全部硬编码。
- **不做接地搜索**（D3）：水面/树顶即落点。
- **不做 `IDimensionLink` 注册表 / NeoForge 事件 / KubeJS 绑定**（D10）。
- **不合并 `LandingY`**（D12）。
- **不改渲染、过渡界面、激活流程、冷却时长**——本次只动方向与落点规则。
- **不追溯**已生成的旧区块/存档（与群系剔除同理）。

---

## 6. API 说明（需求 3 的结论）

**本期交付**：一个**公开静态入口**，签名已按通用形态设计：

```java
DimensionTeleport.Target target = new DimensionTeleport.Target(serverLevel, x, z, fallbackYOrNull);
DimensionTransition transition = DimensionTeleport.toTarget(entity, target);   // null = 本次走不了
```

契约（写进 javadoc）：

1. **仅服务端主线程**调用；
2. **非阻塞**：不加载区块、不等待、不 join；
3. **返回 null 表示"本次不传送"**，调用方决定重试或放弃（上限由调用方持有）；
4. **不写任何状态**（冷却、票据寿命、失败提示都由调用方负责）；
5. `fallbackY == null` ⇒ 只走精确落点；非 null ⇒ 拿不到精确落点时用它。

**将来长成正式 API 的路径**（不在本期）：同一包内新增
`IDimensionLink`（维度对 + 落点策略）、注册表与（可选）NeoForge 事件；
`DimensionTeleport` 保持为门面，签名不变。届时 `DisasterPortalBlock` 退化为内置实现之一。

---

## 7. 验证

### 7.1 静态（本轮执行）

| 检查 | 手段 | 通过判据 |
|---|---|---|
| 编译 | `gradlew.bat build` | `BUILD SUCCESSFUL` |
| **无阻塞 API 回流** | grep `block/` + `teleport/` + `util/LandingY` 的 `getChunk(`、`managedBlock`、future `.join()` | 零命中；只允许 `getChunkNow(` 与 `chunk.getHeight(` |
| 上行重生点回退彻底移除 | grep `getRespawnPosition` / `getSharedSpawnPos` / `getRespawnDimension` | `DisasterPortalBlock` 零命中 |
| 双向共用落点 | 读 `getPortalDestination` | 只剩"选 `ServerLevel`"一处分支 + 一次 `toTarget` |
| API 面 | `javap` 检查两个类的公开签名 | 与 §2.2 一致；`CoordinateLanding` 无实例字段 |

### 7.2 实机（用户执行）

**V1 接口自证**：成功日志一条同时给出方向与目标坐标
```
[BeLoongCore][DisasterPortal:teleport] <玩家> downward <源维度> -> beloong:disaster (x, y, z) waited=N ticks
[BeLoongCore][DisasterPortal:teleport] <玩家> upward   beloong:disaster -> minecraft:overworld (x, y, z) waited=N ticks
```
判据：`x/z` 与进门时逐位相同；`y` 与目标维度 `MOTION_BLOCKING` 一致。

**V2 双向对照（核心）**：主世界记录精确 `(X, Z)` → 进天灾门 → 读天灾侧坐标（`|Δx|,|Δz| < 1e-6`）
→ 原地进天灾侧的门 → 回主世界，判据 `(X, Z)` 复原。
**必须专试水面**：在海洋列进门，判据 = 落在**水面之上**而非海底。

**V3 兜底路径**：制造"目标区块很慢"的场景（从极远处 XZ 首次进天灾）。
判据：正常情况下 `y` 仍是高度图值；若 `waited` 逼近 600 后成功，则该次 `y` = 进门时所在 Y。

**V4 死锁回归（每次动传送逻辑都必须重跑）**：首次进天灾（新存档/未探索区）连续进出 5 次。
判据：无 `ModernFix integrated server watchdog` 转储、无 `Thread Dump:`、
`latest.log` 无 `[DisasterPortal:timeout]`。

**V5 既有语义不破坏**：① 天灾 → 主世界**不再**回床边（在床边建门验证）；② 连续两次传送被
`teleportCooldownTicks=100` 拦住（无第二条 `:teleport`）；③ 骑乘进门下车、坐骑留原地；
④ 非玩家实体穿过不传送。

**V6 文档同步**：`docs/天灾维度总设计.md` §3.6 / §六常量表 / §九 + 决策 35。

**实例日志按 GBK 解码**（`[System.Text.Encoding]::GetEncoding(936)`）。

### 7.3 静态验证结果（2026-09-16 实测）

| 项 | 结果 |
|---|---|
| `gradlew.bat build` | **BUILD SUCCESSFUL**（`EXIT=0`）；仅 3 条**既有的** Annotation Processor 警告（`PossibleBiomesFilterMixin` 的 `@Shadow`、`ParameterListAccessor` 的两处 `@Accessor`，与本次改动无关） |
| 阻塞 API 回流 | `block/` + `teleport/` + `util/LandingY` 内 `getChunk(` / `managedBlock` / `.join()` / `CompletableFuture` **全部命中均为 javadoc 注释**（讲死锁教训的文字）；真实加载调用只有 `CoordinateLanding` 的 `getChunkNow` ×2 与 `chunk.getHeight` ×1、`LandingY` 的 `getChunkNow` ×1 与 `chunk.getHeight` ×1 |
| 上行三级回退移除 | `DisasterPortalBlock` 内 `getRespawnPosition` / `getSharedSpawnPos` / `getRespawnDimension` **零命中** |
| 已删成员残留 | `createUpwardTransition` / `createDownwardTransition` / `requestDestinationChunk` / `disasterLevel(` / `DESTINATION_TICKET_RADIUS` **零命中** |
| 双向收敛 | 目标维度分支只有 `getPortalDestination`（L255-258）与 `entityInside`（L199-201）两处；`DimensionTeleport.toTarget` 调用 **1 处**；`CoordinateLanding.requestChunk` 调用 **2 处**（进门预热 + 未就绪刷新） |
| API 签名 | `javap` 核对：`CoordinateLanding` → `public static Double resolve(ServerLevel,int,int)` + `public static void requestChunk(ServerLevel,int,int,BlockPos)`，**无实例字段**（仅 2 个 `private static final int`）；`DimensionTeleport` → `public static DimensionTransition toTarget(Entity, DimensionTeleport$Target, DimensionTransition$PostDimensionTransition)` |
| 未受牵连 | `util/LandingY.java` 与 `beloong.mixins.json` 的 `git diff --stat` **均为空**（第 1 段承诺的"不合并"与"本次不涉及注入点"成立） |
| javadoc 口径 | 全文件扫描"停止重试直到玩家离开" / "回重生点" / "照搬原版末地返回逻辑" **零命中**（已改为"NBT 冷却期内不再重试"等新口径） |

### 7.4 明确不做

- 不做自动化回归脚本：传送必须实机，静态脚本证明不了"落在地表"。
- 不做"同 XZ 往返一致性"单元测试：`ServerLevel` 无法在单测中构造。

---

## 8. 落地后需要同步的文档

| 文件 | 改动 |
|---|---|
| `docs/天灾维度总设计.md` | §3.6 传送逻辑（双向 1:1、无返回门、兜底语义）；§六常量表（等待上限现为双向共用）；§九（超时真实语义改写、兜底 Y 代价、移除"上行回重生点"相关表述）；新增决策 35 |
| `memory/decisions-log.md` | 本次决策条目 |
| `memory/learned-patterns.md` | "提取共用落点解析前先逐行比旧行为"这条方法论 |

---

## 9. 下一步

调用 `planning` skill 产出实施计划（逐任务 + 静态/实机验证映射），**经用户批准后再动代码**。
