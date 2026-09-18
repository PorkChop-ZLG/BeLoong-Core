# 龙宫传送门（荧石框架 + 注水激活）设计文档

**Date:** 2026-09-16
**Status:** **Approved**（已逐段确认：架构与组件 / 数据流 / 错误处理与边界）
**Approach:** 框架探测复刻天境 `AetherPortalShape`；门面资产取自天境 jar（父模型继承原版下界门）；
传送全部复用 `teleport` 包既有 API
**Applies to:** BeLoong-Core 0.9.3（`disaster2` 分支）/ MC 1.21.1 / NeoForge 21.1.236

**参考来源**（均已读源码或 jar，非推测）：

- 天境源码：`D:\Minecraft\开源模组参考文件\The-Aether\src\main\java\com\aetherteam\aether\block\portal\`
- 天境构建产物：`D:\Minecraft\开源模组参考文件\The-Aether\aether-1.21.1-1.5.10-neoforge.jar`
- 原版：`neoforge-21.1.236-sources.jar` / `client-extra-aka-minecraft-resources.jar`
- 龙之生存：`D:\Minecraft\开源模组参考文件\DragonSurvival\...\DragonStateProvider.java`

---

## 1. 需求（用户原话的落实）

| # | 需求 | 落实 |
|---|---|---|
| 1 | 框架用**荧石**，框架内**有水**即激活（类似天境） | 方块 tag 判定框架；`BlockEvent.NeighborNotifyEvent` 检测水 |
| 2 | 进门后 **0 tick** 开始传送（与天灾门一致） | `getPortalTransitionTime` 返回 `PORTAL_TRANSITION_TICKS = 0` |
| 3 | **仅主世界 ↔ 龙宫**，其他维度不生效 | 两层判定：激活器 + 门方块各判一次 |
| 4 | 落点用上一轮的 API：去龙宫 = 配置固定坐标；回主世界 = 世界出生点 + 扩散 | `TeleportTarget.toLoongPalace` / `TeleportTarget.Spawn` + `DimensionTeleport.toTarget` |
| 5 | **不生成返回门**、不做落地平台 | 不调任何 portal-forcer；落点全部来自 API |
| 6 | 门方块 ID = `beloong:loong_palace_portal`，**注册方块 + 物品，进创造物品栏** | `ModBlocks` + `ModItems` + `ModCreativeModeTabs` |
| 7 | 模型/贴图/渲染/音效**从天境复制**（已获授权） | 见 §4 资产清单（父模型继承原版 `nether_portal_{ns,ew}`） |
| 8 | **必须是龙才能传送**（用龙之生存 API） | `DragonStateProvider.isDragon(player)`（每次传送都判） |
| 9 | **无法传送时用消息通知玩家** | `LoongPalacePortalNotifier`：actionbar + 20 tick 节流，覆盖三种失败 |

---

## 2. 架构

```
block/
  LoongPalacePortalBlock.java        门方块：Block implements Portal
                                     （AXIS + 双向碰撞箱 + animateTick 音效/粒子 + updateShape 自毁）
  LoongPalacePortalShape.java        框架探测 + 点亮（包内可见；两个静态入口）
  LoongPalacePortalActivation.java   注水激活（BlockEvent.NeighborNotifyEvent 监听器）
  LoongPalacePortalNotifier.java     传送失败提示（actionbar + 节流）
registry/ModBlocks.java              +1 方块注册
item/ModItems.java                   +1 BlockItem
item/ModCreativeModeTabs.java        +1 accept（BELOONG_TAB）
data/beloong/tags/block/loong_palace_portal_frame.json     框架方块 tag（默认仅荧石）
assets/beloong/...                                         门面资产（见 §4）
```

**复用（不新建）**：`teleport/DimensionTeleport`、`TeleportTarget`、`CoordinateLanding`、`TeleportCooldown`。

**四个关键设计点**

| # | 设计 | 理由 |
|---|---|---|
| **A** | 框架探测**独立成类**（`LoongPalacePortalShape`），不塞进门方块 | 探测发生在"水被放置"时，那一刻**门方块还不存在**，激活器必须能独立调用；将来别的门可复用 |
| **B** | 形状判定的"空" = **空气 ∨ 水 ∨ 本门方块** | 这是"注水能点亮"的前提：水在框内时形状仍算"可建造"，才能把水格替换成门方块 |
| **C** | 激活走**方块事件**（`NeighborNotifyEvent`），不走 tick 轮询 | 方块驱动、零开销；项目里既有的轮询（`DimensionTransportHandler`）是为"每 tick 查 Y"设计的，用在此处是浪费 |
| **D** | 门只做三件事：`entityInside` 登记 → `getPortalTransitionTime` = 0 → `getPortalDestination` 选目标并调 API | 与天灾门同构；落点、冷却、扩散全部已就绪 |

**两处结构上自动成立的约束**

1. **不生成返回门**：原版下界门的返回门来自它自己的 `getExitPortal → createPortal`；我们**根本不调它**，只返回自己算好的 `DimensionTransition`。
2. **大门不含 BlockEntity、不含自定义渲染器**：门面 = 原版下界门模型 + 我们的动态贴图（见 §4），渲染零代码。

---

## 3. 数据流

### 3.1 点亮（注水激活）

```
玩家把水放进荧石框内
  → 方块更新 → NeoForge BlockEvent.NeighborNotifyEvent（pos = 该水格）
LoongPalacePortalActivation：
  1. 仅服务端
  2. 维度 ∈ {overworld, loong_palace}（否则 return —— "其他维度不生效"第一层）
  3. 该格流体是水（任意水，含流动水）
  4. LoongPalacePortalShape.findEmpty(level, pos, Axis.X)   // 内部先试 X 轴，失败再试 Z 轴
  5. 命中 → shape.createPortalBlocks()                       // 框内每格 setBlock(门方块, AXIS, 2|16)
  6. 播放 block.beloong.loong_palace_portal.trigger
  7. **不取消**该邻居事件（与天境 setCanceled(true) 的差异：避免吞掉原版水/荧石的后续结算）
```

**"空"的判定**：`空气 ∨ 水 ∨ 本门方块`；**框架**：方块 tag `#beloong:loong_palace_portal_frame`（默认只含 `minecraft:glowstone`）；**尺寸**：宽 2–21、高 3–21。

### 3.2 自毁

```
门方块 updateShape(state, direction, facingState, level, pos, facingPos)：
  若 ① 该方向是水平轴且与门轴不同，且
     ② 相邻方块不是本门方块，且
     ③ new LoongPalacePortalShape(level, pos, 门轴).isComplete() == false
  → 返回 Blocks.AIR  （整扇门消失）
  否则 → super.updateShape(...)
```
`isComplete()` = 形状有效 ∧ 框内本门方块数 == 宽 × 高。

### 3.3 进门与传送（逐 tick）

```
entityInside（客户端也登记，为 CONFUSION 扭曲供数；服务端仅放行玩家）
  ├─ 冷却期（TeleportCooldown.isOnCooldown）→ 顶住原版冷却 + return（**不登记、不提示**）
  ├─ 骑乘 → stopRiding
  ├─ setAsInsidePortal
  └─ 失败预检 → LoongPalacePortalNotifier.notify(...)   // 仅用于提示，不阻断登记

下一 tick  PortalProcessor.processPortalTeleportation: portalTime++ >= 0 成立
  → getPortalDestination(level, entity, pos)
      ⓐ 非 ServerPlayer                                 → null
      ⓑ !DragonStateProvider.isDragon(player)            → notify(not_a_dragon) + null   ← 新增
      ⓒ 当前维度 ∉ {overworld, loong_palace}              → notify(wrong_dimension) + null
      ⓓ 选目标：龙宫 → overworld / 否则 → loong_palace
         DimensionTeleport.toTarget(player, target, post)
      ⓔ 返回 null（防御）                                 → notify(teleport_failed) + null
  → 成功：post 回调 = fallDistance 归零 + TeleportCooldown.mark(60 tick) + 播放 travel 音效
客户端过渡：getLocalTransition() = CONFUSION（与天灾门一致）
```

**与天灾门的两处结构性差异**

1. **没有"落点未就绪 → 票据 + 重试 + 600 tick 超时"状态机**——龙宫侧是固定坐标（不查高度图）、主世界侧是出生点+扩散（不查高度图）⇒ `toTarget` 不会因"区块未加载"返回 null。这是本轮最大的简化。
2. **没有专用过渡界面**（用原版通用背景）。

### 3.4 返回落点

龙宫 → 主世界：`TeleportTarget.Spawn(overworld)` → `SpawnSpreadResolver`（gamerule `spawnRadius` 内扩散；失败回出生点原样坐标）。已实现，不属本轮。

---

## 4. 资产清单（全部取自天境 jar，仅改路径与命名空间）

| 我们文件 | 来源 | 说明 |
|---|---|---|
| `assets/beloong/blockstates/loong_palace_portal.json` | `blockstates/aether_portal.json` | `axis=x → beloong:block/loong_palace_portal_ns`；`axis=z → ..._ew` |
| `assets/beloong/models/block/loong_palace_portal_ns.json` | `models/block/aether_portal_ns.json` | **父模型仍为 `minecraft:block/nether_portal_ns`**，`render_type: translucent`，只换贴图 |
| `assets/beloong/models/block/loong_palace_portal_ew.json` | `..._ew.json` | 同上 |
| `assets/beloong/textures/block/loong_palace_portal.png` | `textures/block/miscellaneous/aether_portal.png` | **16×512（32 帧）** |
| `assets/beloong/textures/block/loong_palace_portal.png.mcmeta` | 同名 `.mcmeta` | `{"animation":{"frametime":2}}` |
| `assets/beloong/models/item/loong_palace_portal.json` | 天境**没有**门物品模型 | 自建：`parent: minecraft:item/generated` + `layer0 = beloong:block/loong_palace_portal` |
| `assets/beloong/sounds.json` | `assets/aether/sounds.json` 的三条 | 改名 `block.beloong.loong_palace_portal.{ambient,trigger,travel}` |
| `assets/beloong/sounds/portal/{portal,trigger,travel}.ogg` | `assets/aether/sounds/portal/*.ogg` | 原样复制 |

**天境的三条声音定义**（原样搬）：`ambient` = `{name:"…/portal", attenuation_distance:10}` + subtitle；`trigger` + subtitle；`travel` 无 subtitle。

**音效播放点**：`ambient` → `animateTick` 低频（天境 1/100；原版下界门 1/2000，**取天境的 1/100**）；`trigger` → 点亮瞬间（广播附近玩家）；`travel` → 传送成功后的 `post` 回调。

**粒子**：`animateTick` 用**本模组自己的粒子类型** `beloong:loong_palace_portal`——
贴图与原版下界门**完全相同**（`minecraft:generic_0..7`），差别只在**配色**。

### 4.1 实机验收反馈后的两项修正（2026-09-16）

首次实机验收（功能正常）反馈了两处**观感**问题，均已修：

| # | 问题 | 根因 | 修法 |
|---|---|---|---|
| **S1** | 音效**太大** | ① `trigger`/`travel` 音量用了 `1.0`，天境是 **`0.25`**（响 4 倍）；② `ambient` 用了 `level.playLocalSound`——那是原版下界门的**本地环境音**路径（`Attenuation.NONE`），贴着门听不衰减 | `trigger`/`travel` → `0.25`；`ambient` 改为**客户端直接播放 `SimpleSoundInstance`**（其公开构造器默认 `Attenuation.LINEAR`，音量 `0.5` 与天境一致）。**注意**：`Level#playLocalSound` 只接受 `SoundEvent`、无法传入自定义 `SoundInstance`，所以必须走客户端路径（天境也是这样） |
| **P1** | 粒子**像下界门** | 贴图本来就相同；真差异是**配色**：原版 `PortalParticle` 是 `r=f*0.9, g=f*0.3, b=f`（暖橙红），天境是 `r=g=b=f` 再 `r*=0.2, g*=0.2`（冷蓝青、更暗） | 新增 `registry/ModParticles` + `client/particle/LoongPalacePortalParticle`（`extends PortalParticle`，只覆写那 3 行配色）+ `assets/beloong/particles/loong_palace_portal.json`；`BeLoongCoreClient` 加 `RegisterParticleProvidersEvent` 绑定 |

**两处都只改观感，不动传送逻辑、不影响已通过的验收结论。**

---

## 5. 决策记录

| # | 决策 | 理由 |
|---|---|---|
| D1 | 框架材料走**方块 tag**（默认仅荧石） | 整合包/其他模组可用数据包换材料；与项目已有的 `#minecraft:needs_netherite_tool` 同类做法 |
| D2 | 框架探测**复刻天境** `AetherPortalShape`，**不**混用原版 `PortalShape` | 原版 `PortalShape` 的框架谓词是 NeoForge `isPortalFrame`（默认只认黑曜石，荧石不是），且它**硬编码放 `NETHER_PORTAL`**、关键字段 private |
| D3 | 门面**继承原版下界门模型**（`minecraft:block/nether_portal_{ns,ew}`）+ 天境动态贴图 | jar 证据：天境正是这么做的 ⇒ **渲染零代码**，动画由原版贴图系统提供 |
| D4 | **不取消**注水触发的邻居事件 | 天境 `setCanceled(true)` 会吞掉这次方块更新；原版水/荧石结算依赖邻居更新 |
| D5 | 方块属性照抄天境：`noCollission` + `strength(-1)` + `lightLevel(11)` + `GLASS` + `pushReaction(BLOCK)` | 与下界门/天境一致 |
| D6 | 激活**不检查是否龙** | 搭门是建造行为；若点亮时就拦，非龙玩家会困惑"为什么没反应"。龙检查只在传送时做 |
| D7 | 龙检查放在 `getPortalDestination`（**每次传送都判**） | 玩家可能在门里变形为人形态；且 `isDragon` 只是一次数据查询，零额外开销 |
| D8 | 维度限制**卡两层**（激活器 + 门方块） | 防"用指令/结构把门放进下界后当传送器" |
| D9 | 失败提示用 **actionbar + 每玩家 20 tick 节流** | `getPortalDestination` 每 tick 都可能调；actionbar 会被新消息替换、不刷聊天框 |
| D10 | 提示覆盖**三种失败**：非龙 / 维度不允许 / API 返回 null（防御性） | 用户指定；前两条是真实可达路径，第三条是防御 |
| D11 | 冷却期内**不提示** | 持续接触时提示无意义（沿用天灾门"冷却期内不重试"） |
| D12 | **不做**自定义 HUD 扭曲（天境 `AetherOverlays` + `portalIntensity`） | 我们已有 `getLocalTransition() = CONFUSION`，两套叠加会打架 |
| D13 | **不做**落点被占时的挤出（天境 `findCollisionFreePosition`）、**不做**自动平台 | 落点由 API 给出；用户明确不生成返回门/平台 |
| D14 | **不做**专用过渡界面 | 用原版通用背景 |
| D15 | 技能 `TpLoongPalaceEffect` **保持现状**（不加龙检查） | 它是龙之生存能力，只有龙能用；为不可能的情形加代码不值得 |

---

## 6. 错误处理与边界

| 情形 | 行为 |
|---|---|
| 非龙玩家站在门里 | 不传送 + actionbar 提示（20 tick 节流）；仍会看到 `CONFUSION` 扭曲（客户端照常登记） |
| 门出现在下界/末地/自定义维度 | 注水**点不亮**；即使门方块存在也不传送 |
| 维度不存在 | ERROR 日志 + `teleport_failed` 提示 |
| `toTarget` 返回 null | ERROR 日志 + `teleport_failed` 提示（防御） |
| 框架被拆 | `updateShape` 检测形状不完整 → 门变空气 |
| 水被移除 | 门**不熄灭**（水已被门方块替换；点亮是一次性事件） |
| 玩家在门里变成人形态 | 下一次传送被拒 + 提示 |
| 冷却期内（60 tick） | `entityInside` 拦下、不登记、不提示 |
| 传送后落在门里 | 冷却保证不被立刻弹回 |
| 多人同时进门 | 各自独立（冷却/落点/提示均按玩家） |
| 落点被方块占住 | 不处理（D13）；玩家可能卡住或悬空 |

**性能说明**：① `getPortalDestination` 不是热点（每 tick 每玩家至多一次，且仅在门内）；② `updateShape` 会构造形状对象 ⇒ 门方块附近的每次邻居更新都触发一次形状扫描（最坏 21×21），**天境也如此**（照抄）。若将来出现性能问题再考虑状态缓存，本轮不做。

---

## 7. 验证策略

**静态（本轮执行）**

| 检查 | 判据 |
|---|---|
| `gradlew.bat build` | `EXIT=0` |
| 无阻塞 API | 新增 4 个类内 `getChunk(`/`managedBlock`/`.join()` 仅 javadoc 命中 |
| 门面资产 | blockstate/model 的父模型与贴图路径正确；`.mcmeta` 存在；贴图 16×512 |
| tag 数据包 | `data/beloong/tags/block/loong_palace_portal_frame.json` 合法且含荧石 |
| 中英 lang 键集合一致 | `Compare-Object` 为空 |
| API 复用 | 门方块内只出现 `DimensionTeleport.toTarget` / `TeleportTarget.*` / `TeleportCooldown.*`，**无**重复的落点或冷却实现 |

**实机（用户执行）**

| 编号 | 判据 |
|---|---|
| V1 | 荧石搭框 + 倒水 → 门出现（框内水被替换）；拆任一块荧石 → 门消失 |
| V2 | 非龙玩家（人形态）进门：不传送 + actionbar 提示"只有龙族…"；变龙后可传送 |
| V3 | 龙玩家：主世界 → 龙宫落在配置固定坐标；龙宫 → 主世界落在出生点附近（扩散） |
| V4 | 其他维度（下界）倒水：**不点亮** |
| V5 | 视觉/听觉：门面 32 帧动画正常；环境音可闻；点亮与传送各播一次音效 |
| V6 | 连续进出：60 tick 冷却生效；无卡顿、无 `Thread Dump:`、无 timeout 日志 |
| V7 | 物品：创造物品栏可见"龙宫传送门"，放置后形态正确 |

**实例日志按 GBK 解码。**

---

## 8. Non-Goals

- 不生成返回门、不生成落地平台、不做落点挤出（D13）
- 不做自定义 HUD 扭曲 / 专用过渡界面（D12 / D14）
- 不给技能加龙检查（D15）
- 不追溯已生成的旧区块/存档
- 不改既有文档（按用户要求：只写新文档）

---

## 9. 未决/待办

| 项 | 说明 |
|---|---|
| **龙宫侧落点在虚空** | 默认 `(0.5, 65.5, 0.5)` 处无方块 ⇒ 进门后会坠落，约 2.3 秒后触发 Y&lt;0 安全网被送回主世界。用户已明确**不做自动平台**，故这是**已知可用性缺陷**，需在龙宫内另建平台或改配置坐标 |
| **非龙玩家在门里会看到扭曲但不传送** | 设计如此（§3.3）；若观感不佳，可考虑"非龙不登记"（但那会同时失去扭曲过渡的提示性） |
| 天境 `packs/classic_base` 里的旧版门贴图 | 未采用（用的是主资源的那张 16×512） |

---

## 10. 下一步

调用 `planning` skill 产出实施计划（逐任务 + 静态/实机验证映射），**经用户批准后再动代码**。
