# 天灾门双向 1:1 传送 Implementation Plan

**Goal:** 把天灾传送门改成「非天灾 ⇄ 天灾」双向 1:1 坐标传送（上行目标为主世界），
抽出一个可被将来 API 复用的传送门面，同时**保住下行行为不变**、**不重新引入任何主线程阻塞**。

**Architecture:** 新增 `com.zonlong.beloong.teleport` 包：`CoordinateLanding`（无状态落点解析）
+ `DimensionTeleport`（公开门面，返回 `DimensionTransition` 或 `null`）。`DisasterPortalBlock`
退化为薄适配器：只负责选目标维度、登记、冷却、**600 tick 上限**与失败提示。

**Approach:** 甲 —— 先建两个新类（各自可独立验证）→ 再一次性改块类接线并删除旧私有方法
→ 静态验证 → 文档同步。理由：解析层/门面层/接线三者的错误可被精确定位，
且"下行刻意不变"这条约束在**单次可审的 diff** 里最容易守住。

**依据文档:** [`docs/plans/2026-09-16-disaster-portal-bidirectional-1to1-design.md`](2026-09-16-disaster-portal-bidirectional-1to1-design.md)（Approved）

**本仓库的验证现实（影响每个任务的"Verification"写法）**：无测试源集
（`compileTestJava NO-SOURCE`），因此**没有 TDD 红绿循环**；每个任务的验证 = **编译** +
**针对性静态检查（grep / javap）**，行为验证统一放到 T5 之后的实机验收（设计文档 §7.2）。
`git commit` 由用户执行，计划里只标注"建议提交点"。

---

### Task 1: 落点解析器 `CoordinateLanding`

**Files:**
- Create: `src/main/java/com/zonlong/beloong/teleport/CoordinateLanding.java`

**Steps:**
1. 新建 `final class CoordinateLanding`，私有构造器，`final` 类。
2. `public static @Nullable Double resolve(ServerLevel target, int blockX, int blockZ)`，判定顺序
   **必须与设计文档 §2.4 逐字一致**：
   ```
   chunk = target.getChunkSource().getChunkNow(blockX >> 4, blockZ >> 4)
   chunk == null                                   → null
   |blockX| >= 3000 万 || |blockZ| >= 3000 万        → null
   topBlockY = chunk.getHeight(MOTION_BLOCKING, blockX & 15, blockZ & 15) + 1
   topBlockY > target.getMinBuildHeight() ? topBlockY + 1.0 : null
   ```
   注意：`+1` 是补 `Level#getHeight` 与 `ChunkAccess#getHeight` 的差 1；越界判定放在
   `getChunkNow` **之后**（与下行现状的调用顺序一致，避免改变"未加载"与"越界"的优先级）。
3. `public static void requestChunk(ServerLevel target, int blockX, int blockZ, BlockPos anchor)`：
   越界直接 `return`；`getChunkNow != null` 直接 `return`；
   否则 `addRegionTicket(TicketType.PORTAL, new ChunkPos(blockX >> 4, blockZ >> 4), 0, anchor)`。
   半径常量**定义在本类**（`private static final int TICKET_RADIUS = 0`）。
4. javadoc 必须写清三件事（设计文档 §2.3 / §3）：① 与 `util/LandingY` 的分工（不合并的理由）；
   ② **仅限服务端主线程**（`getChunkNow` 非主线程恒 null ⇒ 静默退化为"一直等"）；
   ③ 水面即水面（`MOTION_BLOCKING` 含流体）、虚空列返回 `null` 的语义。

**Verification:**
```powershell
cd D:\Minecraft\BeLoong-Core
.\gradlew.bat build --console=plain -q ; "EXIT=$LASTEXITCODE"     # 期望 EXIT=0
Select-String -LiteralPath src\main\java\com\zonlong\beloong\teleport\CoordinateLanding.java `
  -Pattern 'getChunk\(|managedBlock|\.join\(\)'                   # 期望零命中
```

---

### Task 2: 传送门面 `DimensionTeleport`

**Files:**
- Create: `src/main/java/com/zonlong/beloong/teleport/DimensionTeleport.java`

**Steps:**
1. `public record Target(ServerLevel level, double x, double z, @Nullable Double fallbackY)`，
   作为 `DimensionTeleport` 的**嵌套 record**（避免多开一个顶层文件；将来长 API 时再提升）。
2. `public static @Nullable DimensionTransition toTarget(Entity entity, Target target,
   DimensionTransition.PostDimensionTransition post)`：
   ```
   Double y = CoordinateLanding.resolve(target.level(), Mth.floor(target.x()), Mth.floor(target.z()));
   if (y == null) y = target.fallbackY();          // 下行 fallbackY 恒为 null ⇒ 此路对下行不可达
   if (y == null) return null;                     // 拿不到且无兜底 ⇒ 本 tick 不传送
   return new DimensionTransition(target.level(), new Vec3(target.x(), y, target.z()),
           Vec3.ZERO, entity.getYRot(), entity.getXRot(), post);
   ```
   **本方法只做解析与构造**：不申领票据、不判超时、不写冷却、不记位置日志——这些都是调用方职责
   （设计文档 §2.2 的职责边界表）。
3. **票据刷新全部归调用方**（`entityInside` 每 tick + 门类的"未就绪"分支），`toTarget` 内部**不**调用
   `requestChunk`——否则会与块类重复申领，且"落点未就绪"这条信息会被门面吞掉、块类无法记日志。
4. `postDimensionTransition` 由**调用方作为参数传入**（见第 2 步的最终签名）：
   这样门面不需要知道 `beloong_portal_cooldown` 这个业务键，符合"不写状态"的契约。
5. javadoc 必须写清设计文档 §6 的 5 条契约（仅主线程、非阻塞、null 语义、不写状态、fallbackY 语义）。

**Verification:**
```powershell
.\gradlew.bat build --console=plain -q ; "EXIT=$LASTEXITCODE"
# 公开签名核对（与设计文档 §2.2/§6 一致）
& "D:\Java\jdk-21.0.11\bin\javap.exe" -p -classpath build\classes\java\main `
   com.zonlong.beloong.teleport.DimensionTeleport com.zonlong.beloong.teleport.CoordinateLanding
```

---

### Task 3: 块类接线（`DisasterPortalBlock`）

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/block/DisasterPortalBlock.java`

**Steps:**
1. `getPortalDestination` 改为：选目标维度 → 构造 `Target` → 调 `toTarget` → 处理 `null`：
   ```
   目标维度：isDisasterDimension(level) ? player.server.getLevel(Level.OVERWORLD)
                                        : player.server.getLevel(DISASTER_LEVEL)
   fallbackY：下行 = null ；上行 = entity.getY()
   if (targetLevel == null) { 首轮(waitedTicks <= PORTAL_TRANSITION_TICKS + 1) 打一条 ERROR;
                              if (waitedTicks > 600) return failDestination(...); return null; }
   DimensionTransition t = DimensionTeleport.toTarget(player, new Target(targetLevel, x, z, fallbackY),
                                                      postTransition());
   if (t != null) { LOGGER.info("... downward/upward ... (x, y, z) waited=N ticks"); return t; }
   if (waitedTicks > DESTINATION_WAIT_TIMEOUT_TICKS) return failDestination(player, blockX, blockZ, waitedTicks);
   CoordinateLanding.requestChunk(targetLevel, blockX, blockZ, pos);
   player.setPortalCooldown(0);
   首轮 info / 其余 debug 日志（沿用现文案，方向字符串按实际方向填）
   return null;
   ```
   **类型分工（不要写反）**：门面 `DimensionTeleport.toTarget` 收 **`Entity`**（保持 API 通用形态）；
   "只允许玩家传送"这条本模组策略**留在块类**——`getPortalDestination` 开头的
   `if (!(entity instanceof ServerPlayer player)) return null;` 原样保留。门面里**不得**出现
   `ServerPlayer` 判定或 `beloong_portal_cooldown` 这类业务键。
   ⚠️ 注意：旧实现取坐标用的是 `player.getX()/getZ()`，而 `Entity#getX()` 与之对玩家同值
   （`ServerPlayer` 未覆写），因此这一处改写**不改变落点**。
2. **删除**这些私有方法/字段：`createDownwardTransition`、`createUpwardTransition`、
   `disasterLevel`、`requestDestinationChunk`、`DESTINATION_TICKET_RADIUS`
   （半径常量随之移到 `CoordinateLanding`）。保留 `isDisasterDimension`、`failDestination`、`postTransition`。
3. `entityInside` 里"下行才预热"改为**双向预热**：先解析目标维度，非 null 时调
   `CoordinateLanding.requestChunk(target, x, z, pos)`。
4. `failDestination` 改为**方向无关**：错误文案里的方向词改为实际方向（新增一个 `String direction`
   参数或在调用点拼好），玩家提示键 `message.beloong.disaster_portal.destination_timeout` **不变**。
5. 类 javadoc 同步：双向 1:1、上行不再回重生点、删除"上行照搬原版末地返回逻辑"的段落，
   保留"禁止 `Level#getChunk`/`getHeight`"的告警并指向死锁复盘。
6. `DESTINATION_WAIT_TIMEOUT_TICKS` 的 javadoc 补一句：**现为双向共用**（原为仅下行）。

**Verification:**
```powershell
.\gradlew.bat build --console=plain -q ; "EXIT=$LASTEXITCODE"
$f = 'src\main\java\com\zonlong\beloong\block\DisasterPortalBlock.java'
"--- 阻塞 API（期望 0）---"
(Select-String -LiteralPath $f -Pattern 'getChunk\(|managedBlock|\.join\(\)|getHeight\(Heightmap').Count
"--- 上行三级回退（期望 0）---"
(Select-String -LiteralPath $f -Pattern 'getRespawnPosition|getSharedSpawnPos|getRespawnDimension').Count
"--- 已删方法（期望 0）---"
(Select-String -LiteralPath $f -Pattern 'createUpwardTransition|createDownwardTransition|requestDestinationChunk|disasterLevel\(').Count
"--- 双向收敛（期望 upward 与 downward 各 1，且只此一处分支）---"
Select-String -LiteralPath $f -Pattern 'Level\.OVERWORLD|DISASTER_LEVEL|DimensionTeleport\.toTarget'
```

---

### Task 4: 静态验证套（设计文档 §7.1 全项）

**Files:**
- 无（纯检查）；如发现有回流，回到 Task 1–3 修正

**Steps:**
1. 全仓 grep 阻塞 API 回流：`src/main/java/com/zonlong/beloong/{block,teleport}` + `util/LandingY`
   ```
   getChunk(   managedBlock   .join()   CompletableFuture.get(
   ```
   期望：只允许 `getChunkNow(`；`chunk.getHeight(` 允许。
2. `javap` 核对两个新类的公开签名与设计文档 §2.2 一致，且 `CoordinateLanding` **无实例字段**。
3. 核对 **mixin 未受影响**：`src/main/resources/beloong.mixins.json` 无新增/无改动
   （本次不涉及注入点）。
4. 核对 `util/LandingY.java` **零改动**（`git diff --stat -- src/main/java/com/zonlong/beloong/util/LandingY.java`
   应为空）。
5. 记录验证输出（供 T5 写进文档的"静态验证结果"表）。

**Verification:** 上述每条的期望值都直接判读；全部满足才算通过。

---

### Task 5: 文档同步（代码落地后）

**Files:**
- Modify: `docs/天灾维度总设计.md`（§3.6 传送逻辑、§3.6 末尾"为什么绝不能同步加载落点区块"保持、
  §六常量表、§九 已知问题、§八 新增**决策 35**）
- Modify: `docs/plans/2026-09-16-disaster-portal-bidirectional-1to1-design.md`（Status 改为
  "Implemented（日期）"，补"静态验证结果"表）
- Modify: `memory/decisions-log.md`（把本条从"设计定案，未实施"改为"**已实施**"）

**Steps:**
1. §3.6 数据流图改为双向 1:1（删掉上行"回重生点/世界出生点/(0,64,0)"与 `+0.5` 对齐的描述）。
2. §六"不受配置控制的部分"表：`落点区块等待上限` 行注明**双向共用**；
   `进门倒计时`、`落点预热方式` 保持不变。
3. §九 三处改写：
   - "传送门落点失败无自动兜底" → 补"上行超时后以**传送所用 Y** 兜底"；
   - `failDestination` 的"停止重试直到玩家离开传送门" → **"在 NBT 冷却期内不重试"**（真实语义）；
   - 新增一行：上行 1:1 带来的"从自定义维度进天灾→回主世界对应坐标"这一行为。
4. 新增**决策 35**：双向 1:1 + 无返回门 + `teleport` 包 API 预留（摘录设计文档 D1–D14 的要点）。
5. 设计文档 Status → Implemented，并附静态验证结果表。

**Verification:**
```powershell
Select-String -LiteralPath docs\天灾维度总设计.md -Pattern '决策 35|双向共用|NBT 冷却期内不重试'
git status --porcelain    # 期望：仅预期的几个文件
```

---

## 实机验收（Task 5 之后，由用户执行；判据见设计文档 §7.2 V1–V6）

| 编号 | 判据摘要 |
|---|---|
| V1 | `:teleport` 日志同时给出方向与 `(x, y, z)`；`x/z` 与进门时逐位相同 |
| V2 | 主世界 → 天灾 → 主世界，XZ 复原（`<1e-6`）；**必须专试海洋列**，落点在水面之上 |
| V3 | 从极远处 XZ 首次进天灾：正常时 `y` = 高度图值；若逼近 600 tick 才成功，则 `y` = 进门时 Y |
| V4 | 连续进出 5 次：无 ModernFix watchdog 转储、无 `Thread Dump:`、无 `[DisasterPortal:timeout]` |
| V5 | 不再回床边；连续两次传送被 100 tick 冷却拦住；骑乘下车；非玩家不传送 |

实例日志按 **GBK** 解码（`[System.Text.Encoding]::GetEncoding(936)`）。

---

## 建议提交点

| 提交 | 内容 | 建议信息 |
|---|---|---|
| ① | Task 1–3 | `refactor(disaster): 天灾门改为双向 1:1 传送，抽出 teleport 包` |
| ② | Task 5 | `docs(disaster): 同步总设计（双向 1:1 / 决策 35）` |

（`memory/` 在 `.gitignore` 内，不入提交。）

---

## Task 依赖与顺序

```
T1 CoordinateLanding ──┐
                       ├──> T3 块类接线 ──> T4 静态验证 ──> T5 文档 ──> 实机验收
T2 DimensionTeleport ──┘
```

T1 与 T2 之间**无编译依赖**（T2 依赖 T1 的方法，但两者可分别 build：T2 需要 T1 已存在）。
若需并行，可先写 T1 的签名骨架再各自填充，但**不推荐**——本包只有两个文件，串行更快且 diff 更干净。

## 风险与回退

| 风险 | 影响 | 回退 |
|---|---|---|
| `toTarget` 的 null 语义被写成"要么给 transition 要么抛异常" | 门里永久重试/永久失败 | T4 的 grep + 实机 V1/V3 可发现；回退到"块类内联实现"（乙方案） |
| 上行兜底 Y 用错（写成 `player.getY()` 之外的量） | 极端情形落点离谱 | V3 判据直接读日志 |
| 删私有方法时漏删/漏改 javadoc 引用 | 编译过但文档失真 | T3 第 6 步 + T4 第 2 步 + `learned-patterns` 的"删类前先扫 `{@link}`" |
| 误给 `LandingY` 加改动 | 龙宫传送被牵连 | T4 第 4 步的 `git diff --stat` 判据 |

## Non-Goals（与设计文档 §5 一致）

不生成返回门/落地标记、不加配置项、不做接地搜索、不建注册表/事件/KubeJS 绑定、
不合并 `LandingY`、不改渲染与过渡界面、不追溯旧区块。
