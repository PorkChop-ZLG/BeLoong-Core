# 特效实体上限护栏（`effect_entity_cap`）设计文档

**日期：** 2026-10-06（同日第二轮：审查后收敛记账点 + 增加自愈清扫）
**状态：** ✅ 已实施；第一轮已通过实机 A/B 验收（见 `docs/reviews/2026-10-06-effect-entity-cap-acceptance.md`），第二轮改动待复测
**范围：** 传奇怪物 `legendary_monsters:camera_shake` / `dynamic_camera_zoom` 的无界堆积
**起因文档：** 整合包仓库 `D:\BeLoong\.minecraft\versions\BeLoong\docs\plans\2026-10-06-lm-camera-shake-entity-flood-handover.md`
（该文档要求复制到本仓库 `docs/plans/`；本文与验收记录按它的目录结构书写，复制后相对链接即可用）

---

## 一、事故一句话

传奇怪物的 `camera_shake` 是"裸 `Entity` + `MobCategory.MISC` + 唯一销毁路径是 `tick()` 自毁"的纯视觉特效实体。
线上悚域实例累积到 **1,830,969** 个后，玩家登录时单 tick 超过 60 秒，被 `ServerHangWatchdog` 强杀。

## 二、本次复核（对起因文档的修正与补充）

复核依据：NeoForge 1.21.1 打补丁后的真源码（`~/.gradle/caches/neoformruntime`）+ 线上 2.2.3 的 LM jar（`javap`）+ 事故日志/崩溃报告 + 测试客户端存档实测。

| # | 结论 | 依据 |
|---|---|---|
| 1 | **`tickCount` 不是实体自己涨的，而是关卡写的** —— 这是"永不销毁"的根 | `ServerLevel#tickNonPassenger`：`p_entity.tickCount++`（`ServerLevel.java:772`）之后才是 `p_entity.tick()`（`:777`） |
| 2 | 实体 tick 有两道门：进 `entityTickList`，以及 `inEntityTickingRange` 硬门 | `PersistentEntitySectionManager#addEntity` → `startTicking`；`ServerLevel#tick` 的 `:411`；阈值 `ChunkLevel`：`FULL=33`、`BLOCK_TICKING=32`、`ENTITY_TICKING=31` |
| 3 | ⇒ 不在实体刻范围内的实例：**永不 tick、永不增龄、`discard()` 永不执行**，却仍被 `ChunkMap.entityMap` 跟踪；起因文档说的"区块卸载后永不清除"应更正为"**只要不在实体刻范围**（区块可能仍加载并跟踪）" | 同上 |
| 4 | 触发点：`ChunkMap#move` 的第一件事就是**遍历全量 tracked 实体**，而它在 `handleMovePlayer` 里**每个移动包都会跑** | `ChunkMap.java:1024-1031`；`ServerChunkCache#move`；`ServerGamePacketListenerImpl.java:955` |
| 5 | 崩溃报告 `-- Head --` 显示 `Thread: Server Watchdog` 是假象：看门狗用 `error.setStackTrace(serverThread)` 把服务端线程的栈塞进报告 | 崩溃报告（归档副本） |
| 6 | 起因文档漏了**放大器**：`immersive_optimization` 在 `ServerLevel#tickNonPassenger` **HEAD** 取消 tick（**连 `tickCount++` 一起跳过**），把"自毁倒计时"在墙钟时间上拉长最多 `maxLevel=20` 倍；而它的豁免键是 `Entity#noCulling`，LM 其它特效实体都设了、**`camera_shake` 恰好没设**。配置语义已核对：`entities` 映射中 **`false` = 豁免，缺省 `*` → `true` = 参与调度** | 整合包 `mods/【优化】immersive_optimization-…0.2.0.jar` 的 `ServerLevelMixin`/`TickScheduler#shouldTick`/`Config#resolveBlacklist` 反汇编 |
| 7 | 同族第二个隐患：`DynamicCameraZoomEntity#tick` 的销毁条件是 `… && zoomIncrement == 0`，而它是反复 `-= zoomSpeed` 的 **float**，一旦越过 0 就永不等于 0 ⇒ **即使正常 tick 也可能永不销毁** | 2.1.15 源码树 + 2.2.3 `javap` 核对结构一致 |
| 8 | 量级实测：线上悚域 **1,830,969**；某个回归存档**单个区块**里冻着 **310,530** 个（外置实体文件 `entities/c.3.1.mcc` 6.0 MB ⇒ 解压 **108 MB** NBT）；一处测试会话里 LM 的召唤尝试速率约 **28 次/tick（≈560 次/秒）** | 崩溃报告普查行；对 `c.3.1.mcc` 的 zlib 解压计数；`latest.log` 锚点反推 |
| 9 | 起因文档 §4 的调用点表基于 **2.1.15** 源码树，而线上是 **2.2.3**（三个关键 class 与旧坐标 jar 字节一致）。本文的实现按**线上 jar 的 `javap -s` 描述符**核对，不依赖源码树版本 | `javap -s`：`cameraShake(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;FFII)V` 唯一；`dynamicCameraZoom` 恰两个重载 |
| 10 | 漏斗完整性（字节码级）：两个 LM 特效类里 `Level.addFreshEntity` 的调用点**只出现在各自工厂内部**；而所有入世界路径（工厂、`/summon`、数据包、其它模组、世界生成、读盘）都汇聚到 NeoForge `EntityJoinLevelEvent` ⇒ 该事件是**唯一可全覆盖的记账点** | `javap -c` 扫描 `addFreshEntity` 归属；`PersistentEntitySectionManager#addEntity` 第一条语句 post 事件 |

## 三、设计：一个记账点、一层预筛、一次自愈清扫

### 3.1 判定式（硬界）

```
（最近一次采样的存量）
+（本采样窗口内已放行的"入世界"数）
≥ maxPerDimension  ⇒ 拒绝
```

两个计数都在每次重采样（默认每 20 tick）时清零，且**每个实体只在 `EntityJoinLevelEvent` 上记一次账** ⇒
**任意时刻每维度每类型的已加载数 ≤ maxPerDimension** 成立范围是"**所有经实体管理器的入世界路径**"
（读盘、模组工厂、`/summon`、数据包、其它模组 `addFreshEntity`、世界生成放置的实体；vanilla 里唯一不经过该事件的入世界路径是玩家，而玩家不在名单内）。

### 3.2 预筛：模组静态工厂（只判定、不记账）

| Mixin | 目标 | 注入 |
|---|---|---|
| `mixin/legendarymonsters/CameraShakeCapMixin` | `CameraShakeEntity`（`@Pseudo`） | `cameraShake` HEAD，`cancellable`，`remap=false`，`require=0` |
| `mixin/legendarymonsters/DynamicCameraZoomCapMixin` | `DynamicCameraZoomEntity`（`@Pseudo`） | `dynamicCameraZoom` **两个重载**（写全描述符，避免同名歧义） |

- 它们调用 `EffectEntityCap#shouldRefuseSpawn`：**超限时提前取消，省掉实体构造**（LM 实测 ≈28 次/tick），
  但**不记账**——记账统一由 3.3 的闸门负责（若两处都记一次账，放行速率会减半）。
- 约定与同包既有的 `AnnihilationPursuerDamageCapMixin` 一致：传奇怪物缺席时整体跳过。
  即便上游改名导致它静默失效，3.3 的闸门仍会兜住这些实体（代价只是多构造一次）。
- 目标描述符与 `ModEntities.CAMERA_SHAKE` / `DYNAMIC_CAMERA_ZOOM` 字段均对线上 jar 核对过。

### 3.3 唯一记账点：入世界闸门（`perf/EffectEntityJoinGate`）

监听 NeoForge `EntityJoinLevelEvent`，判定与账目都在 `perf/EffectEntityCap#shouldRefuseJoin` 里。

- **为什么挂在这里**：NeoForge 把该事件 post 在 `PersistentEntitySectionManager#addEntity` 的**第一条语句**
  （`PersistentEntitySectionManager.java:79`，在 UUID 登记、区块段插入、`entityMap` 跟踪登记、入 tick 表之前）。
  **取消 = 从未进入世界** ⇒ 不进 `entityMap`（因此不被"每个移动包跑一次"的 `move` 遍历）、不进 tick 表、不下发客户端。
- **两支都管**：`loadedFromDisk()==true`（读盘：`EntityStorage#read → processPendingLoads → addEntity(e,true)` 与
  `ChunkSerializer:429 → addLegacyChunkEntities → addEntity(e,true)`）与 `false`（任何来源的新生成）。
- **旧存档自愈**：被取消的实体不会在下次存盘时被写回（`EntityStorage#storeEntities` 只写内存里的实体，
  列表为空时直接 `write(pos, null)` 删除文件）⇒ 那个 6 MB 的 `.mcc` 会在下次存盘时**消失**。
- **取消是安全的**：两条路径随后无条件调用的 `Entity#onAddedToLevel()` 只置一个布尔标志
  （`Entity.java:3739`），对即将被 GC 的实体无副作用。

### 3.4 拒绝理由

| 理由 | 适用支 | 判据 | 为什么安全 |
|---|---|---|---|
| `over-cap` | 两支 | 已达上限 | 这是"31 万存量也能进得去"的关键：只保留上限以内的量 |
| `not-ticking` | **仅读盘支** | 所在区块不在实体刻范围 | 它永远不会 `tickCount++` ⇒ 永远不自毁，纯死重；一并丢弃可避免残骸长期占满预算、导致该维度再也放不出新抖动 |

新生成支**不做** `not-ticking` 判定：新实体刚由 ticking 的实体创造，而世界加载瞬间的票据状态可能瞬时不准，
误判会直接吃掉玩家眼前的抖动（万一真的落到非实体刻范围，3.6 的清扫会处理它）。

### 3.5 线程纪律

读盘 join 若发生在区块反序列化等后台线程：此时**跳过重采样、`not-ticking` 世界查询与清扫**
（只用缓存计数做 `over-cap` 判定），避免跨线程访问世界状态；计数本身在锁内维护。
服务端主线程判定用 `MinecraftServer#isSameThread()` 识别。（已核实当前两条读盘路径都在主线程：
`ChunkMap#scheduleChunkLoad` 用 `mainThreadExecutor`，`PersistentEntitySectionManager#processPendingLoads` 在 `tick()` 里，
所以该降级分支目前是纯防御。）

### 3.6 自愈清扫（`resample` 内）

被放行的实体若**事后**冻结（玩家 `/tp`、掉线、死亡离开 ⇒ 区块掉出实体刻范围），它会永不 `tickCount++`、
永不自毁，却被每次重采样计入 `sampled`，**永久占用预算**；累积约 `maxPerDimension` 个后该维度会
永久拒绝所有新抖动（视觉静默消失）。因此在重采样遍历里顺带清扫：

- 判据 = 与读盘闸门同一谓词（`!inEntityTickingRange`）；
- **先收集、循环结束后再 `discard()`**：`getAllEntities()` 是活视图，边遍历边删会 `ConcurrentModificationException`；
- **每趟预算 = `maxPerDimension`**。判定式保证"冻结数 ≤ 上限"，故一趟必清空；`cap` 次 `discard()` 是微秒~毫秒级；
- 清扫后**立刻把计数扣减**，使当次召唤就能拿到释放出来的预算（效果在 ≤1 个采样窗口内恢复）；
- 天然不会命中"正在被 tick 的实体"（正在 tick 的必然在实体刻范围内）。

### 3.7 日志（英文 ASCII，符合本项目约定）

```
[BeLoong] effect-entity-cap:       dim=… type=… count=… used=… cap=… suppressed=…                        # 工厂预筛拦下
[BeLoong] effect-entity-cap-join:  dim=… type=… source=disk|new count=… used=… cap=… refused=… not_ticking=… reason=…   # 入世界闸门拒绝
[BeLoong] effect-entity-cap-sweep: dim=… type=… dropped=… reason=not-ticking                            # 冻结残骸被清扫
```

- `-join` 那条按**对数里程碑**输出（累计拒绝数每跨过 1/10/100/1k/10k/100k/… 打一条），
  因为纯时间节流会让"几秒内丢出 31 万"的 burst 只留下 `refused=1`（已由第一轮验收实测暴露）。
- 三条都按 `logIntervalTicks` 做时间节流兜底（长期零星场景）。
- ⚠️ 第一轮的 `effect-entity-cap-load` 已更名为 `effect-entity-cap-join` 并加 `source=` 字段（旧 grep 需更新）。

## 四、决策表

| 编号 | 决策 | 理由 / 被否方案 |
|---|---|---|
| D1 | **唯一记账点 = 入世界闸门**；工厂 Mixin 只做预筛 | 若两个入口各记一套账，最坏情况各放行一次 ⇒ 上限翻倍；若两处都记同一实体 ⇒ 放行速率减半 |
| D2 | **阈值统一**，默认 **200**，配置范围 **1~1024** | 生成与读盘同一语义；上限收到 1024（审查 3.1）以避免误配到事故危险量级（10⁴~10⁵）。⚠️ 默认值变更**不会改写已存在的配置文件** |
| D3 | **不做"生成 +1 / 销毁 -1"的增量计数** | 销毁路径只有 `tick()`，冻结实例永不 tick ⇒ 计数只增不减、上限永久饱和、抖动彻底消失且难察觉。改为"低频重采样 + 唯一记账点"，并靠 D9 清扫自愈 |
| D4 | 本轮**不做** `EntityType#canSerialize() → false`（结构性"永不落盘"） | 原版对同类实体的既定处理（`LightningBolt`/`FishingHook` 用 `Builder.noSave()`）；本轮按用户决定不做，留作后续加固 |
| D5 | 本轮**不做** `ChunkMap$TrackedEntity#updatePlayer` 配对守卫 | 它能兜住"内存里已有巨量存量"的极端场景（正是原事故栈顶），但属注入原版热点路径，按用户决定留作后续 |
| D6 | 读盘支**连 `not-ticking` 一起丢** | 只丢超限会让"永不 tick 的残骸"长期占用预算，等同把该维度的抖动永久堵死 |
| D7 | 闸门**两支都管**（`loadedFromDisk` 真/假都判定并记账） | 只治读盘会留下"`/summon`、数据包、其它模组"这条无界缺口（审查 2.1）；新生成支因此也纳入上限 |
| D8 | 本轮**不做** NBT 层过滤（`EntityType#loadEntitiesRecursive` HEAD 剔除 compound） | 首次进图仍会构造 31 万实体对象；实测代价为 2.6 s + 5.7 s 两次卡顿、不卡死，用户接受（方案 a） |
| D9 | **自愈清扫**：`resample` 内无条件清扫"不在实体刻范围"的受监视实体，每趟预算 = 上限，不新增配置项 | 否则冻结残骸会永久占满预算、该维度抖动静默消失（审查 1.1）；预算取上限已足够（判定式保证冻结数 ≤ 上限）。被否方案：只统计"可 tick 实例"——那会让冻结数不再计入预算、读盘每窗口又能放行上限个 ⇒ 残骸无界增长，破坏防崩溃硬界 |
| D10 | 预筛**只判定不记账** | 见 D1 |
| D11 | **传奇怪物维持 optional 依赖**（不加 `type="required"`、不加 `versionRange`、Mixin 保持 `@Pseudo` + `require=0`） | 用户裁定：既往踩过"把第三方模组改成必选导致起服阻塞"的坑。附带事实：LM 自家 `mods.toml` 写的是 `version="1.21.1"`（FML 日志同），所以 `versionRange="[2.2.3,)"` 会**反过来挡住我们自己**。**已知并接受的风险**：上游若改名 `cameraShake`/`dynamicCameraZoom`，预筛会静默失效（闸门仍兜住实体）；若连注册名都改，则名单匹配失败、护栏整体静默失效且无日志 |
| D12 | 日志更名 `-load` → `-join`（加 `source=`），新增 `-sweep` | 闸门现在两支都管，`-load` 的名字会误导；三条日志各有明确语义 |

## 五、配置与翻译

COMMON 段（本项目"跨模组修复开关一律 COMMON"的惯例）：

```toml
[effect_entity_cap]
	enabled = true
	types = ["legendary_monsters:camera_shake", "legendary_monsters:dynamic_camera_zoom"]
	maxPerDimension = 200     # 默认 200，范围 1~1024；同时是清扫的每趟预算
	rescanTicks = 20
	logIntervalTicks = 1200
```

- 五个值都给了显式 `.translation("beloong.configuration.effectEntityCap…")`，中英各 12 键（节标题/说明 + 5 值 + 5 说明）。
- `types` 为扩展点：其它模组再出现同类"裸特效实体"可直接追加，无需改代码。

## 六、残留缺口与已知风险

1. **验证工具盲区（重要）**：整合包自带的 `docs/tools/count-camera-shake.py` 只扫 `.mca`，
   会把"某区块 310,530 个"报成 **456** —— 因为超大区块的实体数据被原版写进了外置文件 `entities/c.x.z.mcc`
   （`RegionFile#EXTERNAL_FILE_EXTENSION`，`Saving oversized chunk … to external file` 就是它的告警）。
   验收/巡检必须同时扫 `.mcc`，或以该告警行作为污染信号。
2. **整合包侧建议（不替代本修复）**：给 `immersive_optimization` 的 `entities` 加
   `"legendary_monsters:camera_shake": false`（= 豁免调度），恢复"21 tick 必死"的原版语义；
   另可把该实体纳入 AtomSweep 的清理名单。
3. **线上必须 `enabled = true`**：COMMON 配置各端读各自文件，客户端关闭不影响服务端，反之亦然。
4. **已接受的行为变化**：越限时 `/summon` 会回报"召唤失败"（运维可见性，用户已接受）。
5. **已接受的观感代价**：`not-ticking` 是"加入瞬间"的快照判定——世界/维度加载瞬间可能删掉读盘抖动。
   经核对不是缺陷（vanilla 的 `ServerLevel.java:411` 用同一谓词，被删的本来也不会 tick），代价只是"少看一次几秒的抖动"。
6. **D11 的静默失效面**（护栏依赖"实体注册名"与"上游方法名"两处字符串/签名）：不修，登记为已知风险。

## 七、验收入口

- 实机 A/B 验收记录：`docs/reviews/2026-10-06-effect-entity-cap-acceptance.md`
- 复现要点（本地单人即可）：把 `simulation-distance` 调小、`view-distance` 保持较大，
  打一场 LM Boss 后立刻 `/tp` 远离 ⇒ 原区块落进"跟踪但不 tick"的带 ⇒ 残骸冻结；
  装/卸本 jar 对照 `count-camera-shake.py`（记得先补 `.mcc` 支持）与日志锚点。
- 第二轮（记账收敛 + 清扫）需复测：① LM Boss 战看三条锚点、`used` 只由闸门推进；
  ② 旧存档读盘仍能打出 `refused` 量级里程碑；③ **自愈**：制造冻结后回原地，应能在 ≤1 个采样窗口内重新放出抖动，
  并出现 `effect-entity-cap-sweep` 行；④ `/summon` 越限提示；⑤ `dynamic_camera_zoom` 无异常。
