# 特效实体上限护栏 —— 实机验收记录

**日期：** 2026-10-06
**范围：** `effect_entity_cap`（生成入口上限 + 旧存档读盘闸门）
**设计文档：** `docs/plans/2026-10-06-effect-entity-cap-design.md`
**验收人：** 用户（实机操作）+ 本项目会话（日志与存档取证）

---

## 一、被测产物

| 阶段 | jar | SHA256 |
|---|---|---|
| A/B 对照测试所用 | `beloong-0.10.1.jar` | `C2C58BE2C5CAD0F47D8CA6FA32036B758EB1B0ED53955FB72032781B41148E43` |
| 测试后追加"对数里程碑日志"的构建 | `beloong-0.10.1.jar` | `01590A7018A1EA9E5F2220D6030C7A4CD3D298D14CAEA5007BDE320812AEB7` |

A/B 测试时配置：`[effect_entity_cap] enabled = true`、`maxPerDimension = 400`（当时文件里仍是 400；默认值随后改为 200）。

## 二、环境

- 实例：`D:\AAA_testclient\.minecraft\versions\BeLoong 1.4`（NeoForge 21.1.248，含传奇怪物 2.2.3、immersive_optimization 等）
- 存档：`saves\崩溃测试` —— 修复前在该存档 `entities\c.3.1.mcc`（外置超大区块，区块 [3,1]）里冻着大量 `legendary_monsters:camera_shake`
- 日志：`logs\latest.log`（18:59:09 → 19:07:58）

## 三、时间线（同一存档、同一位置，A/B 对照）

| 时刻 | 日志事件 | 判读 |
|---|---|---|
| 19:00:46 | `Preparing start region`（第 1 次进入，**护栏开启**） | 开始加载含残骸的区块 |
| 19:00:53 | `Can't keep up! 2610ms / 52 ticks` | 一次性代价（108 MB NBT 解析 + 大规模实体对象构造） |
| **19:01:01** | **`[BeLoong] effect-entity-cap-load: dim=minecraft:overworld type=legendary_monsters:camera_shake count=0 used=400 cap=400 refused=1 not_ticking=0 reason=over-cap`** | **读盘闸门介入**：前 400 个被接受（`used` 顶到 `cap`），第 401 个起被拒；`not_ticking=0` ⇒ 该区块当时在实体刻范围内，走 `over-cap` 分支 |
| 19:01:13 | `Can't keep up! 5723ms / 114 ticks` | 仍是"数秒卡顿"，**未卡死、未强杀** |
| 19:03:00 / 19:04:03 | `[BeLoong] effect-entity-cap: … suppressed=1` → `suppressed=9005` | 游玩期间生成侧上限拦下 9,005 次召唤（LM 的每 tick 刷） |
| 19:04:35 / 19:06:50 / 19:06:52 | 自动存档 + `Stopping server` + 退出存档 | 被拒实体从未入世界 ⇒ 区块按"没有它们"的形态写回 |
| **19:07:23** | 配置文件被写入（`enabled = false`） | 关闭护栏，准备对照 |
| 19:07:34 | `Preparing start region`（第 2 次进入，**护栏关闭**） | 同存档、同位置 |
| 19:07:58 | `Can't keep up! 3857ms / 77 ticks`（日志在此截止） | **无任何护栏锚点**，24 秒内卡顿重新出现 ⇒ bug 重新触发 |

`latest.log` 中**没有**任何来自 `EffectEntityCap` / `EffectEntityJoinGate` 的异常或报错行。

## 四、磁盘侧独立核对（权威证据）

| 项 | 修复前 | 修复后（当前） |
|---|---|---|
| `entities\c.3.1.mcc`（外置超大区块，区块 [3,1]） | **6,042 KB / 310,530 个 camera_shake**（解压后 108.25 MB NBT） | **文件已删除** |
| `entities\*.mca` 中的 camera_shake | 456（1 个区块） | **0**（4 个区块全扫） |
| 存盘时的 `Saving oversized chunk [3, 1]` 警告 | 每次存盘必报 | 本次会话**未再出现** |

⇒ **31 万残骸 + 6 MB 外置文件被彻底清除，存档自愈**，与设计预期一致（被取消 join 的实体不会在下次存盘时被写回）。

## 五、结论

1. ✅ **护栏开启**：旧存档可以进入；读盘闸门超限即丢弃（`over-cap`）；生成侧上限在游玩期间持续生效（`suppressed=9005`）；不卡死、无异常。
2. ✅ **护栏关闭**：同一存档 24 秒内复现卡顿（3.9 s / 77 ticks behind）⇒ 归因成立，卡顿确实由该实体堆积引起。
3. ✅ **存档自愈**：`.mcc` 被删除、残留归零。
4. ⚠️ **修掉的日志盲区**：A/B 测试暴露出纯时间节流（60 s）会把"几秒内丢 31 万"的 burst 只留下 `refused=1`，运维看不到量级。已改为**对数里程碑**（累计丢弃数跨过 1/10/100/1k/10k/100k/… 各打一条），并保留原时间节流用于零星丢弃。

## 六、遗留待办

| # | 事项 | 说明 |
|---|---|---|
| 1 | **重新打开开关** | 测试客户端当前 `enabled = false`；线上服务端必须 `enabled = true`（COMMON 配置各端读各自文件） |
| 2 | **`200` 尚未实机验证** | 本次"开启"阶段用的是 400。200 会把放行速率从 20 次/秒降到 10 次/秒；若抖动观感变弱，可调到 400~1000（事故量级 10 万~180 万，余量极大） |
| 3 | 再测"修复旧存档"需恢复污染备份 | 当前存档已被治好（这就是目标） |
| 4 | 修 `count-camera-shake.py` 的 `.mcc` 盲区 | 它把 310,530 报成 456；见设计文档 §六.2 |
| 5 | 可选加固（本轮未做） | `EntityType#canSerialize()→false`（永不落盘）、`ChunkMap$TrackedEntity#updatePlayer` 配对守卫、`/summon` 等非 LM 生成路径的入世界限流 |
