# 龙宫传送 API 统一 Implementation Plan

**Goal:** 把「其他世界 → 龙宫」「龙宫 → 主世界」两条行为固化为公共 API，并让**技能、
Y&lt;0 兜底、将来的传送门方块**三条调用方全部走它；同时把冷却统一到天灾门那套 NBT 机制。

**Architecture:** 在既有公开包 `com.zonlong.beloong.teleport` 内新增两个件：
`TeleportTarget`（sealed interface：`At` 坐标+高度图 / `Spawn` 世界出生点 / 工厂 `toLoongPalace` 读配置）
与 `TeleportCooldown`（NBT 冷却，与天灾门共用键）。`DimensionTeleport` 的门面签名由
"仅接受 1:1 坐标"推广为"接受任意 `TeleportTarget`"，天灾门调用随之换类型名、行为不变。

**Approach:** 甲 —— 目标建模与冷却各自独立成件，与 `DimensionTeleport`（门面）/
`CoordinateLanding`（落点解析）既有分层一致；拒绝把三者塞进一个类（做门那轮会继续膨胀）。

**依据**：本轮讨论中用户确认的四条需求 + A/B 两项行为变更（见下）。
**验证现实**：本仓库无测试源集（`compileTestJava NO-SOURCE`），故每任务验证 = **编译** +
**针对性静态检查**；行为验证走实机（由用户执行）。`git commit` 由用户执行。

**已确认的行为变更（用户批准）**

| # | 变更 | 影响 |
|---|---|---|
| A | 技能首次拥有传送冷却（与门共用 NBT，时长 `Config.DisasterPortal.teleportCooldownTicks` 默认 100），冷却中被拦时给 **actionbar 提示** | 玩家可感知；需新增 1 个 lang 键 |
| B | Y&lt;0 兜底的冷却由"自己的静态 Map"改为**同一份 NBT**（数值不变） | "刚用技能/刚从门出来"会同时影响它 |
| C | `Spawn` 落点**不再查高度图**（今天该路径会查） | 极端情形（出生点列被玩家改动）落点 Y 会与高度图不一致；按需求 2"统一为世界出生点、无配置"执行 |

---

### Task 1: `TeleportTarget` —— 落点目标的类型建模

**Files:**
- Create: `src/main/java/com/zonlong/beloong/teleport/TeleportTarget.java`

**Steps:**
1. `public sealed interface TeleportTarget { ServerLevel level(); }`。
2. `record At(ServerLevel level, double x, double z, @Nullable Double fallbackY) implements TeleportTarget`：
   语义 = "Y 走高度图，拿不到用 `fallbackY`；`fallbackY` 为 null ⇒ 本 tick 不传送"（与今天的
   `DimensionTeleport.Target` 完全同义）。
3. `record Spawn(ServerLevel level) implements TeleportTarget`：
   提供 `BlockPos pos()`（= `level.getSharedSpawnPos()`）与 `double centerX()/centerZ()`
   （= `pos.getX() + 0.5` / `pos.getZ() + 0.5`）与 `double fixedY()`（= `pos.getY()`）。
   **不查高度图**（需求 2、变更 C）。
4. `static TeleportTarget toLoongPalace(ServerLevel loongPalace)`：返回
   `new At(loongPalace, Config.DimensionTransport.loongPalace_targetX.get(),
   Config.DimensionTransport.loongPalace_targetZ.get(),
   Config.DimensionTransport.loongPalace_fallbackY.get())`。
   维度 ID 由调用方给（`TpLoongPalaceEffect.LOONG_PALACE_DIM` 常量），本类不认识龙宫。
5. javadoc 写清三条：① 三种目标各自的 Y 策略；② `At` 的 fallbackY=null 语义；
   ③ `toLoongPalace` 是唯一读配置的目标（需求 1"保留配置文件"的落点）。

**Verification:**
```powershell
cd D:\Minecraft\BeLoong-Core
.\gradlew.bat build --console=plain -q ; "EXIT=$LASTEXITCODE"      # 期望 0
& "D:\Java\jdk-21.0.11\bin\javap.exe" -p -classpath build\classes\java\main `
   com.zonlong.beloong.teleport.TeleportTarget `
   'com.zonlong.beloong.teleport.TeleportTarget$At' `
   'com.zonlong.beloong.teleport.TeleportTarget$Spawn'
```

---

### Task 2: `TeleportCooldown` —— NBT 冷却统一（变更 A/B 的载体）

**Files:**
- Create: `src/main/java/com/zonlong/beloong/teleport/TeleportCooldown.java`
- Modify: `src/main/java/com/zonlong/beloong/block/DisasterPortalBlock.java`（改为委托）

**Steps:**
1. 新建 `public final class TeleportCooldown`，常量与语义**逐字沿用**天灾门现有实现：
   - `public static final String KEY = "beloong_portal_cooldown";`（**键名不得更改**）
   - `public static boolean isOnCooldown(ServerPlayer p)`
     = `p.getPersistentData().getLong(KEY) > p.level().getGameTime()`
   - `public static void mark(ServerPlayer p)`
     = `putLong(KEY, p.level().getGameTime() + Config.DisasterPortal.teleportCooldownTicks.get())`
   - `public static void refreshVanillaToOutlastNbt(ServerPlayer p, Level level)`
     = 今天 `DisasterPortalBlock.entityInside` 里那段"把原版冷却顶到 NBT 剩余 +1"的代码
       （含它为什么必须存在的注释）。
2. `DisasterPortalBlock`：删掉私有 `COOLDOWN_KEY` 常量与 `postTransition()` 里的写入，
   改为调 `TeleportCooldown.mark(player)`；`entityInside` 的冷却分支改为
   `isOnCooldown` + `refreshVanillaToOutlastNbt`；`failDestination` 的写入同样改调 API。
   **行为必须逐字不变**（含 `postTransition` 的 `fallDistance = 0`）。
3. javadoc 写清："三条调用方（门 / 技能 / Y&lt;0 兜底）读同一个键 ⇒ 互不绕过"，
   以及 `refreshVanillaToOutlastNbt` 只对**门方块**有意义（技能路径不必顶原版冷却）。

**Verification:**
```powershell
.\gradlew.bat build --console=plain -q ; "EXIT=$LASTEXITCODE"
# 键名与语义未被改动（应命中 1 处定义 + 无其它硬编码字符串）
Select-String -Path src\main\java\com\zonlong\beloong\**\*.java -Pattern 'beloong_portal_cooldown'
(Select-String -Path src\main\java\com\zonlong\beloong\block\DisasterPortalBlock.java `
   -Pattern 'getPersistentData\(\)\.putLong|getPersistentData\(\)\.getLong').Count   # 期望 0
```

---

### Task 3: `DimensionTeleport` 门面推广

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/teleport/DimensionTeleport.java`
- Modify: `src/main/java/com/zonlong/beloong/block/DisasterPortalBlock.java`（换类型名）

**Steps:**
1. 删除嵌套 `record Target`，签名改为：
   `public static @Nullable DimensionTransition toTarget(Entity entity, TeleportTarget target,
   DimensionTransition.PostDimensionTransition post)`。
2. 实现按目标分派（**保持"只解析 + 构造"的职责边界**，仍不申领票据、不判超时、不写冷却）：
   ```
   At    → y = CoordinateLanding.resolve(level, floor(x), floor(z));
           y == null ? y = fallbackY : ; y == null ? return null : ;
           pos = (x, y, z)
   Spawn → pos = (centerX(), fixedY(), centerZ())      // 不查高度图
   ```
3. 另外新增便捷入口（保留天灾门"直取当前坐标"的调用形态，避免它被迫改用 `At` 的全部字段）：
   `public static @Nullable DimensionTransition toCurrentCoords(Entity entity, ServerLevel level,
   @Nullable Double fallbackY, Post post)` → 内部构造 `At(level, entity.getX(), entity.getZ(), fallbackY)`。
4. `DisasterPortalBlock.getPortalDestination`：`new DimensionTeleport.Target(...)` →
   `DimensionTeleport.toCurrentCoords(player, target, fallbackY, postTransition())`。
   **`At` 与旧 `Target` 字段完全同义 ⇒ 天灾门行为零变化**（下行 `fallbackY=null`、上行 `player.getY()`）。
5. javadoc 的"契约 5 条"同步更新（补 `Spawn` 语义）。

**Verification:**
```powershell
.\gradlew.bat build --console=plain -q ; "EXIT=$LASTEXITCODE"
& "D:\Java\jdk-21.0.11\bin\javap.exe" -p -classpath build\classes\java\main com.zonlong.beloong.teleport.DimensionTeleport
# 天灾门仍只有一处门面调用；旧类型名已消失
(Select-String -Path src\main\java\com\zonlong\beloong\**\*.java -Pattern 'DimensionTeleport\.Target').Count  # 期望 0
Select-String -LiteralPath src\main\java\com\zonlong\beloong\block\DisasterPortalBlock.java -Pattern 'DimensionTeleport\.'
```

---

### Task 4: 配置恢复（需求 1"保留配置文件"）

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/Config.java`
- Modify: `run/config/beloong-server.toml`（实例，仅补回三个键，**沿用原节名**）

**Steps:**
1. `DimensionTransport` 类内新增 3 个字段：
   `loongPalace_targetX` / `loongPalace_targetZ` / `loongPalace_fallbackY`。
2. 注册块：在 `[dimension_transport]` 下**恢复原节名** `SERVER_BUILDER.push("overworldToLoongPalace")`
   （**节名不改** ⇒ 你实例里已有的三个值原地继续生效），只定义 3 个键：
   `targetX`(0.5) / `targetZ`(0.5) / `fallbackY`(65.0)；
   注释写明"本节的三个值是**其他世界 → 龙宫**的落点，供 API 读取"。
   **不恢复** `enabled` 与 `triggerY`（它们属于已删除的 Y&gt;8848 机制）。
3. 实例 `run/config/beloong-server.toml` 在 `[dimension_transport]` 下补回该节（三个键沿用实例现值：
   `targetX = 0.5` / `targetZ = 0.5` / `fallbackY = 64.5`——**尊重实例现值，不回写默认**）。
4. 确认 `[dimension_transport]` 里**不再有** `cooldownTicks` 的用途（Task 5 会删除该键的使用；
   本次先不动它，Task 5 一并处理）。

**Verification:**
```powershell
.\gradlew.bat build --console=plain -q ; "EXIT=$LASTEXITCODE"
Select-String -LiteralPath src\main\java\com\zonlong\beloong\Config.java -Pattern 'loongPalace_target|overworldToLoongPalace'
Select-String -LiteralPath run\config\beloong-server.toml -Pattern 'overworldToLoongPalace|targetX|targetZ|fallbackY'
(Select-String -LiteralPath src\main\java\com\zonlong\beloong\Config.java -Pattern 'owToLP_').Count   # 期望 0
```

---

### Task 5: 两条调用方改造（技能 + Y&lt;0 兜底）

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/ability/TpLoongPalaceEffect.java`
- Modify: `src/main/java/com/zonlong/beloong/transport/DimensionTransportHandler.java`
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json` / `en_us.json`（新增 1 个提示键）

**Steps:**
1. **技能**（`TpLoongPalaceEffect`）：
   - 删除 4 个落点常量（`TARGET_X/TARGET_Z/FALLBACK_Y` 与不再需要的 `LOONG_PALACE_DIM` 保留用于取维度）；
   - 删掉 `LandingY` import 与两处调用、删掉"重生点优先"整段（**变更 C**）；
   - `apply(...)`：先 `if (TeleportCooldown.isOnCooldown(player)) { 发 actionbar 提示; return; }`；
     去龙宫 → `DimensionTeleport.toTarget(player, TeleportTarget.toLoongPalace(lp), post)`；
     回主世界 → `DimensionTeleport.toTarget(player, new TeleportTarget.Spawn(overworld), post)`；
   - `post` 用 `TeleportCooldown.mark` + `entity.fallDistance = 0`；
   - 目标维度解析失败/维度不存在时保留现有告警与提示键（`dimension_not_found` 仍在用）。
2. **Y&lt;0 兜底**（`DimensionTransportHandler.tryTransportToOverworldSpawn`）：
   - 落点改用 `new TeleportTarget.Spawn(overworld)`（与技能同一策略，**变更 C**）；
   - 冷却改为 `TeleportCooldown`：进入即 `if (isOnCooldown) return;`，成功后 `mark`；
   - **删除**静态 `COOLDOWNS` Map 与 `finishTransport` 里的冷却写入（`TICK_COUNTERS` 保留，
     它决定"多久查一次 Y"）；`onPlayerLogout` 相应只清 `TICK_COUNTERS`；
   - **删除**配置键 `cooldownTicks`（字段 + 注册块 + 实例文件那一行）（**变更 B**：
     冷却统一到天灾门那份）；`lpToOw_enabled` / `lpToOw_triggerY` **保留**（它们是触发条件，不是冷却）；
   - `LandingY` import 随 `At→Spawn` 一并删除。

**已知技术风险（记录在案，不额外处理）**：`isOnCooldown` / `mark` 用 `player.level().getGameTime()`，
而跨维度后 `player.level()` 已切到目标维度。三个维度同属 `natural: true` 且由同一服务端推进，
`getGameTime()` 同步递增，故功能上成立；但若将来某维度 `natural: false` 或时间被指令单独改动，
冷却时长会出现偏差。已在 `TeleportCooldown` javadoc 写明该前提。
3. **新增提示文案**（变更 A）：键 `message.beloong.tp_loong_palace.on_cooldown`，
   zh「§c传送冷却中，请稍后再试」/ en「§cTeleport is on cooldown」，中英各 1 条，保持键集合一致。
   发送方式：`player.displayClientMessage(Component.translatable(KEY), true)`（actionbar）。

**Verification:**
```powershell
.\gradlew.bat build --console=plain -q ; "EXIT=$LASTEXITCODE"
# 调用方不再直接用 LandingY / 旧冷却
(Select-String -Path src\main\java\com\zonlong\beloong\ability\TpLoongPalaceEffect.java,
   src\main\java\com\zonlong\beloong\transport\DimensionTransportHandler.java -Pattern 'LandingY').Count    # 期望 0
(Select-String -Path src\main\java\com\zonlong\beloong\**\*.java -Pattern 'lpToOw_|cooldownTicks').Count
Select-String -LiteralPath src\main\java\com\zonlong\beloong\transport\DimensionTransportHandler.java -Pattern 'TeleportCooldown|TeleportTarget'
# 语言文件键集合一致性
$zh=(Get-Content src\main\resources\assets\beloong\lang\zh_cn.json -Raw | ConvertFrom-Json).PSObject.Properties.Name
$en=(Get-Content src\main\resources\assets\beloong\lang\en_us.json -Raw | ConvertFrom-Json).PSObject.Properties.Name
Compare-Object $zh $en        # 期望空
```

---

### Task 6: 静态验证套 + 新文档

**Files:**
- Create: `docs/plans/2026-09-16-loong-palace-teleport-api-design.md`

**Steps:**
1. 全仓扫描（逐条给出期望值）：
   - `LandingY` 的引用：源码里**应为 0**（其类文件暂留，见 Task 7）→ 若为 0，
     在 javadoc 标注"当前无调用方"；
   - `TeleportCooldown.KEY` 字符串：**仅 1 处定义**；
   - `TeleportTarget` 的三种目标各至少一处使用（`toLoongPalace` / `Spawn` / 天灾门的 `toCurrentCoords`）；
   - `getChunk(` / `managedBlock` / `.join()` 在 `teleport/` + 三个调用方：**仅 javadoc 命中**。
2. 写新文档（用户要求：不改旧文档、只写新文档）：API 契约、三种目标语义、
   调用方映射表、配置键、冷却统一、A/B/C 三项行为变更、静态验证结果、未做的部分
   （传送门/点火/注册表）、待办（`LandingY` 的去留、龙宫侧精确落点待实机量）。
3. `git status` 核对：只有预期文件改动。

**Verification:** 上述每条的期望值逐条判读；`git status --porcelain` 与预期清单一致。

---

### Task 7（可选，待用户裁决）：`LandingY` 的去留

**Files:**
- Modify 或 Delete: `src/main/java/com/zonlong/beloong/util/LandingY.java`

**Steps:** Task 5 完成后 `LandingY` 将**无调用方**（三处都不再需要它）。
两个选项：① **保留**并在 javadoc 注明"当前无调用方，留给将来'落点固定但仍想查高度图'的场景"；
② **删除**并把 `CoordinateLanding` 的 javadoc 里指向它的分工说明改为历史说明。
**默认执行 ①**（不删，最小改动）；若你选 ② 我另跑一次。

**Verification:** `gradlew.bat build` EXIT=0；（选②时）全仓 `LandingY` 引用为 0。

---

## 实机验收（Task 6 之后，由用户执行）

| 编号 | 判据 |
|---|---|
| V1 | 技能「龙宫传送」：主世界 → 龙宫落在 `(targetX, 高度图/fallbackY, targetZ)`；龙宫 → 主世界**恒落世界出生点**（先在主世界睡床设重生点，验证**不再回床边**） |
| V2 | 技能连按：第二次被冷却拦下并显示 actionbar 提示 |
| V3 | 龙宫 Y&lt;0 掉出：仍回世界出生点；且**刚用过技能/门时会被同一冷却拦住**（变更 B 的判据） |
| V4 | 天灾门回归：下行/上行行为与上一轮验收完全一致（1:1 + 高度图、无超时/报错） |
| V5 | 配置：改 `overworldToLoongPalace.targetX` 后技能落点随之改变（证明配置仍生效） |

## Non-Goals（本轮）

传送门方块 / 点火 / 框架探测 / 注册表型门框架 API；天灾门的任何行为变更；LockDown 登录传送；
不改任何既有文档（只写新文档）。

## 建议提交点

| 提交 | 内容 | 建议信息 |
|---|---|---|
| ① | Task 1–3 | `refactor(teleport): 抽出 TeleportTarget/TeleportCooldown，门面推广到任意落点` |
| ② | Task 4–5 | `feat(teleport): 龙宫传送统一走 API，冷却并入天灾门 NBT` |
| ③ | Task 6（+7） | `docs(teleport): 龙宫传送 API 说明与静态验证结果` |
