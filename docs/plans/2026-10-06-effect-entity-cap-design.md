# 特效实体上限护栏（`effect_entity_cap`）设计文档

**日期：** 2026-10-06
**状态：** ✅ 已实施并通过实机验收（验收记录见 `docs/reviews/2026-10-06-effect-entity-cap-acceptance.md`）
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
| 10 | 漏斗完整性（字节码级）：两个类里 `Level.addFreshEntity` 的调用点**只出现在各自工厂内部** ⇒ 除 `/summon` 外没有绕过工厂的生成路径 | `javap -c` 扫描 `addFreshEntity` 归属 |

## 三、设计：两条入口、一份账、一个硬上限

### 3.1 判定式（硬界）

```
（最近一次采样的存量）
+（本采样窗口内已放行的"新召唤"数）
+（本采样窗口内已放行的"读盘加入"数）
≥ maxPerDimension  ⇒ 拒绝
```

三个计数都在每次重采样（默认每 20 tick）时清零，且**每个被放行的实体只在其中一个入口记一次账** ⇒
**任意时刻每维度每类型的已加载数 ≤ maxPerDimension**（硬界，不是近似）。

### 3.2 入口一：模组静态工厂（新召唤）

| Mixin | 目标 | 注入 |
|---|---|---|
| `mixin/legendarymonsters/CameraShakeCapMixin` | `CameraShakeEntity`（`@Pseudo`） | `cameraShake` HEAD，`cancellable`，`remap=false`，`require=0` |
| `mixin/legendarymonsters/DynamicCameraZoomCapMixin` | `DynamicCameraZoomEntity`（`@Pseudo`） | `dynamicCameraZoom` **两个重载**（写全描述符，避免同名歧义） |

约定与同包既有的 `AnnihilationPursuerDamageCapMixin` 一致：传奇怪物缺席时整体跳过。
目标描述符与 `ModEntities.CAMERA_SHAKE` / `DYNAMIC_CAMERA_ZOOM` 字段均对线上 jar 核对过。

### 3.3 入口二：存档读盘（治旧存档）

`perf/EffectEntityJoinGate` 监听 NeoForge `EntityJoinLevelEvent`，判定与账目都在 `perf/EffectEntityCap` 里。

- **为什么挂在这里**：NeoForge 把该事件 post 在 `PersistentEntitySectionManager#addEntity` 的**第一条语句**
  （`PersistentEntitySectionManager.java:79`，在 UUID 登记、区块段插入、`entityMap` 跟踪登记、入 tick 表之前）。
  **取消 = 从未进入世界** ⇒ 不进 `entityMap`（因此不被"每个移动包跑一次"的 `move` 遍历）、不进 tick 表、不下发客户端。
- **两条磁盘读取路径都会触发，且 `loadedFromDisk()==true`**：
  `EntityStorage#read → processPendingLoads → addEntity(e, true)`（`…:243-249`）与
  `ChunkSerializer:429 → addLegacyChunkEntities → addEntity(e, true)`（`…:112-117`）。
- **旧存档自愈**：被取消的实体不会在下次存盘时被写回（`EntityStorage#storeEntities` 只写内存里的实体，
  列表为空时直接 `write(pos, null)` 删除文件）⇒ 那个 6 MB 的 `.mcc` 会在下次存盘时**消失**。
- **取消是安全的**：两条路径随后无条件调用的 `Entity#onAddedToLevel()` 只置一个布尔标志
  （`Entity.java:3739`），对即将被 GC 的实体无副作用。

### 3.4 读盘拒绝的两条理由

| 理由 | 判据 | 为什么安全 |
|---|---|---|
| `over-cap` | 已达上限 | 这是"31 万存量也能进得去"的关键：只保留上限以内的量 |
| `not-ticking` | 所在区块不在实体刻范围 | 它永远不会 `tickCount++` ⇒ 永远不自毁，纯死重；一并丢弃可避免残骸长期占满预算、导致该维度再也放不出新抖动 |

### 3.5 线程纪律

读盘 join 可能发生在区块反序列化等后台线程：此时**跳过 `not-ticking` 世界查询**（只用缓存计数做 `over-cap` 判定），
避免跨线程访问世界状态；计数本身在锁内维护。服务端主线程判定用 `MinecraftServer#isSameThread()` 识别。

### 3.6 日志（英文 ASCII，符合本项目约定）

```
[BeLoong] effect-entity-cap: dim=… type=… count=… used=… cap=… suppressed=…              # 新召唤被拦
[BeLoong] effect-entity-cap-load: dim=… type=… count=… used=… cap=… refused=… not_ticking=… reason=…   # 读盘存量被丢
```

读盘那条按**对数里程碑**输出（累计丢弃数每跨过 1/10/100/1k/10k/100k/… 打一条），
因为纯时间节流会让"几秒内丢出 31 万"的 burst 只留下 `refused=1` —— 运维最想看的量级反而看不到（已由验收实测暴露并修复）。

## 四、决策表

| 编号 | 决策 | 理由 / 被否方案 |
|---|---|---|
| D1 | **两条入口共用一份账**（读盘放行也占用同一预算） | 若各用一套计数，最坏情况会各放行一次 ⇒ 上限翻倍，硬界不成立 |
| D2 | **阈值统一**，默认 **200**（`maxPerDimension`） | 生成与读盘同一语义；400 实测有效，200 更保守。⚠️ 默认值变更**不会改写已存在的配置文件**，升级后需手改或删掉该行 |
| D3 | **不做"生成 +1 / 销毁 -1"的增量计数** | 销毁路径只有 `tick()`，冻结实例永不 tick ⇒ 计数只增不减、上限永久饱和、抖动彻底消失且难察觉。改为"低频重采样 + 各入口保守预留" |
| D4 | 本轮**不做** `EntityType#canSerialize() → false`（结构性"永不落盘"） | 原版对同类实体的既定处理（`LightningBolt`/`FishingHook` 用 `Builder.noSave()`），能根治"再被污染"；本轮按用户决定不做，留作后续加固 |
| D5 | 本轮**不做** `ChunkMap$TrackedEntity#updatePlayer` 配对守卫 | 它能兜住"内存里已有巨量存量"的极端场景（正是原事故栈顶），但属注入原版热点路径，按用户决定留作后续 |
| D6 | 读盘时**连 `not-ticking` 一起丢** | 只丢超限会让"永不 tick 的残骸"长期占用预算，等同把该维度的抖动永久堵死 |
| D7 | 读盘闸门**只管 `loadedFromDisk()==true`** | 新生成由入口一负责；若两处都记一次账会减半放行速率。已知缺口见 §六 |
| D8 | 本轮**不做** NBT 层过滤（`EntityType#loadEntitiesRecursive` HEAD 剔除 compound） | 首次进图仍会构造 31 万实体对象；实测代价为 2.6 s + 5.7 s 两次卡顿、不卡死，用户接受（方案 a） |

## 五、配置与翻译

COMMON 段（本项目"跨模组修复开关一律 COMMON"的惯例）：

```toml
[effect_entity_cap]
	enabled = true
	types = ["legendary_monsters:camera_shake", "legendary_monsters:dynamic_camera_zoom"]
	maxPerDimension = 200
	rescanTicks = 20
	logIntervalTicks = 1200
```

- 五个值都给了显式 `.translation("beloong.configuration.effectEntityCap…")`，中英各 12 键（节标题/说明 + 5 值 + 5 说明）。
- `types` 为扩展点：其它模组再出现同类"裸特效实体"可直接追加，无需改代码。

## 六、已知残留缺口（本轮范围内明确不做）

1. **非 LM 的新生成路径不受限**：`/summon`、数据包、其它模组直接 `addFreshEntity` 仍可绕过上限
   （闸门只覆盖"LM 静态工厂"+"读盘"）。补法是让 `EntityJoinLevelEvent` 对 `loadedFromDisk()==false` 也限流，
   但必须把工厂侧的记账并入事件侧，否则同一实体会被记两次账、放行速率减半。
2. **验证工具盲区（重要）**：整合包自带的 `docs/tools/count-camera-shake.py` 只扫 `.mca`，
   会把"某区块 310,530 个"报成 **456** —— 因为超大区块的实体数据被原版写进了外置文件 `entities/c.x.z.mcc`
   （`RegionFile#EXTERNAL_FILE_EXTENSION`，`Saving oversized chunk … to external file` 就是它的告警）。
   验收/巡检必须同时扫 `.mcc`，或以该告警行作为污染信号。
3. **整合包侧建议（不替代本修复）**：给 `immersive_optimization` 的 `entities` 加
   `"legendary_monsters:camera_shake": false`（= 豁免调度），恢复"21 tick 必死"的原版语义；
   另可把该实体纳入 AtomSweep 的清理名单。
4. **线上必须 `enabled = true`**：COMMON 配置各端读各自文件，客户端关闭不影响服务端，反之亦然。

## 七、验收入口

- 实机 A/B 验收记录：`docs/reviews/2026-10-06-effect-entity-cap-acceptance.md`
- 复现要点（本地单人即可）：把 `simulation-distance` 调小、`view-distance` 保持较大，
  打一场 LM Boss 后立刻 `/tp` 远离 ⇒ 原区块落进"跟踪但不 tick"的带 ⇒ 残骸冻结；
  装/卸本 jar 对照 `count-camera-shake.py`（记得先补 `.mcc` 支持）与日志锚点。
