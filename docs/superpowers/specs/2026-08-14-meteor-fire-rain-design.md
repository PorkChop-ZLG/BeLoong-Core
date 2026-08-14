# 流星火雨（Meteor Fire Rain）设计

**Date:** 2026-08-14
**Status:** Approved
**Approach:** 方案 A — 独立自定义天气系统

## 问题陈述

为天灾维度（`beloong:disaster`）添加一个专属天气事件「流星火雨」。
该天气仅在天灾维度出现（任意生物群系），持续期间大量陨石从天而降，
触碰方块后产生大爆炸，并对周围生物（含玩家）造成巨大伤害。

## 已确认需求

- **触发**：随机自然发生 + 命令控制，命令为 `beloongcore weather meteorrain`
- **视觉**：完整氛围 —— 天空变暗/染红 + 陨石尾焰 + 开始/结束警告提示
- **爆炸**：破坏方块 + 伤害所有生物（含玩家），威力可配置
- **陨石**：自定义实体 + 发光岩石纹理
- **落点**：仅围绕在线玩家附近随机
- **范围**：仅天灾维度，任意群系

## 设计

### 架构

服务端权威状态机 + 自定义陨石实体 + 客户端氛围渲染 + 网络同步，四层结构。

```
┌─────────────────────────────────────────────────────────────┐
│ 服务端（权威）                                                │
│  MeteorRainManager（单例状态机，按维度存 MeteorRainState）      │
│  MeteorRainHandler（@SubscribeEvent ServerTickEvent 驱动）      │
│  MeteorEntity（重力下坠 + 尾焰 + 落地爆炸）                     │
│  MeteorRainCommand（beloongcore weather meteorrain）           │
└──────────────┬──────────────────────────────────────────────┘
               │ MeteorRainSyncPayload（状态同步：维度 + 是否激活）
┌──────────────▼──────────────────────────────────────────────┐
│ 客户端                                                        │
│  ClientMeteorRainState（缓存当前维度是否流星火雨中）             │
│  MeteorRainClientEvents（雾效染红/变暗 + 全屏暗红蒙层）          │
│  MeteorEntityRenderer（发光岩石 + 火焰尾焰渲染）                │
└─────────────────────────────────────────────────────────────┘
```

### 组件

#### 服务端

| 类 | 职责 | 关键成员 |
|----|------|---------|
| `MeteorRainManager` | 单例天气状态机，按维度维护流星火雨状态 | `Map<ResourceKey<Level>, MeteorRainState>`；`tick(ServerLevel)`、`start(...)`、`stop(...)`、`isActive(...)`、`status(...)` |
| `MeteorRainState` | 单个维度的天气状态 | `phase`（`INACTIVE`/`ACTIVE`/`COOLDOWN`）、`ticksRemaining` |
| `MeteorRainHandler` | `ServerTickEvent.Post` 驱动；状态推进 + 向在线玩家附近生成陨石 | `onServerTick(...)`、`spawnMeteorsAroundPlayer(...)`、登录/换维度时补发同步 |
| `MeteorEntity` | 自定义陨石实体：重力下坠、尾焰粒子、触地爆炸 | `tick()`（尾焰 + 落地检测）、`explode()` |
| `MeteorRainCommand` | 注册命令 `beloongcore weather meteorrain [start\|stop\|status]` | `RegisterCommandsEvent` + Brigadier `Commands.literal` |
| `ModEntities` | 实体注册中心（`DeferredRegister<EntityType<?>>`） | `METEOR = ENTITIES.register("meteor", ...)` |
| `MeteorRainSyncPayload` | 服务端 → 客户端同步「某维度是否流星火雨中」 | `TYPE` + `STREAM_CODEC` + `handleClient` |

#### 客户端

| 类 | 职责 | 关键成员 |
|----|------|---------|
| `ClientMeteorRainState` | 缓存当前客户端所在维度是否流星火雨中 | 静态 `boolean active`（按维度缓存） |
| `MeteorRainClientEvents` | 雾效染红/变暗 + 全屏暗红蒙层 + 开始/结束警告提示 | `ViewportEvent.ComputeFogColor`、全屏蒙层渲染钩子 |
| `MeteorEntityRenderer` | 发光岩石模型 + 火焰尾焰渲染 | `EntityRenderer<MeteorEntity>`，在 `BeLoongCoreClient#registerRenderers` 绑定 |

#### 资源与配置

| 项 | 内容 |
|----|------|
| `data/beloong/damage_type/meteor.json` | 陨石伤害类型（参照 `air_strike.json`） |
| `assets/beloong/textures/entity/meteor.png` | 陨石发光岩石纹理 |
| `assets/beloong/lang/en_us.json` / `zh_cn.json` | 命令反馈、警告、死亡消息本地化 |
| `Config.java` → `meteor_rain` 节（SERVER） | 触发概率、时长、冷却、密度、爆炸威力、伤害、落点半径等 |

### 数据流

#### 状态机迁移

```
INACTIVE ──(每 checkIntervalTicks 概率判定命中，且维内有玩家)──▶ ACTIVE
ACTIVE   ──(ticksRemaining 归零)──▶ COOLDOWN
COOLDOWN ──(cooldownTicks 归零)──▶ INACTIVE
命令 start ──▶ 强制 INACTIVE/COOLDOWN → ACTIVE（重置随机时长）
命令 stop  ──▶ 任意态 → INACTIVE（不影响已落地实体）
```

#### 触发与生成时序（ServerTickEvent.Post）

1. 获取 `beloong:disaster` 的 `ServerLevel`；无在线玩家则直接跳过（性能边界）。
2. `MeteorRainManager.tick(level)`：仅在 `checkIntervalTicks` 间隔执行一次判定。
   - `INACTIVE`：掷 `triggerChance`，命中则进入 `ACTIVE` 并取 `[minDurationTicks, maxDurationTicks]` 随机时长。
   - `ACTIVE`：递减计时；同时进入陨石生成阶段；归零 → `COOLDOWN`。
   - `COOLDOWN`：递减计时；归零 → `INACTIVE`。
3. `ACTIVE` 期间生成陨石：对维内每个在线玩家，掷密度判定，在玩家水平半径 `spawnRadius` 内取随机点，从 `spawnHeight` 生成 `MeteorEntity`。
4. 状态变化时广播：`INACTIVE→ACTIVE` 与 `ACTIVE→COOLDOWN` 两个时刻，向维内玩家发送 `MeteorRainSyncPayload`。
5. 补发：玩家登录 / 换维度进入天灾维度时，立即按当前状态补发一次同步。

#### 陨石实体生命周期（MeteorEntity.tick）

```
生成(spawnHeight, 向下) → 每 tick 尾焰粒子 + 重力下坠
   → 触地(onGround / 垂直碰撞 / fuse 保险计时归零) → explode()
       └─ level.explode(meteor 伤害源, x, y, z, explosionPower, fire, DESTROY)
          └─ 破坏方块 + 范围内生物(含玩家)受到伤害 → discard 实体
```

#### 客户端氛围同步

```
MeteorRainSyncPayload → ClientMeteorRainState.active 更新
   ├─ 变 true：雾效染红/变暗 + 全屏暗红蒙层 + 开始警告(聊天/字幕)
   └─ 变 false：恢复正常雾色/蒙层 + 结束提示
```

### 错误处理

| 风险 | 处理 |
|------|------|
| 天灾维度未加载 / 无玩家 | `tick` 直接 no-op，不产生任何状态 |
| 命令在非天灾维度执行 `start` | 返回错误提示「仅在 beloong:disaster 可用」，不改变状态 |
| 命令缺少/非法子命令 | 打印用法 `beloongcore weather meteorrain [start\|stop\|status]` |
| 陨石实体卡在空中不落地 | 内置 `fuseTicks` 保险（如 200 ticks）强制爆炸，杜绝永久悬浮 |
| 陨石在非服务端被误 tick | `MeteorEntity.tick` 首行守卫 `level.isClientSide` 直接 return（服务端权威） |
| 配置越界 | 全部用 `defineInRange` 约束；概率用 `defineInRange(0.0~1.0)` |
| 玩家在火雨中死亡/掉线/换维度 | 同步按维度广播，掉线自动剔除；换维度触发补发 |
| 服务端重启 | 状态为内存态，重启即回到 `INACTIVE`（已声明的非目标） |

### 测试策略

- **功能验证**：进天灾维度 → `beloongcore weather meteorrain start` → 观察天空染红 + 陨石下坠 + 爆炸 + 受伤；`stop` → 立即恢复正常。
- **范围验证**：天灾维度内不同群系各测一次（任意群系均出现）；主世界/龙宫维度确认不出现陨石。
- **命令验证**：`status` 正确回报相位；非法子命令显示用法；非天灾维度 `start` 报错。
- **边界验证**：维内无玩家时无陨石；事件结束后冷却期不立即重启；玩家在火雨中死亡/重登/换维度后氛围状态正确补发。
- **随机验证**：降低 `triggerChance`/`minDurationTicks` 跑一段时间，确认随机自然发生与结束符合配置。

### 性能

- **节流**：触发判定与陨石生成统一走 `checkIntervalTicks`，不每 tick 掷骰。
- **玩家门槛**：天灾维度无在线玩家时跳过全部处理。
- **数量上限**：每次生成的陨石数按 `meteorsPerPlayerPerSpawn` 封顶。
- **实体清理**：陨石爆炸后立即 `discard`；`fuseTicks` 兜底自毁，不残留实体。
- **客户端**：雾色/蒙层仅在 `active == true` 时计算；陨石渲染复用单纹理。

## 配置默认值（建议，后续可微调）

`Config.java` 新增 `SERVER` 节 `meteor_rain`，全部 `defineInRange`：

| 键 | 建议默认 | 说明 |
|----|---------|------|
| `enabled` | `true` | 总开关 |
| `triggerChance` | `0.001` | 每次判定（每 `checkIntervalTicks`）触发概率 |
| `checkIntervalTicks` | `100` | 状态机判定间隔（5 秒） |
| `minDurationTicks` | `600` | 最短持续（30 秒） |
| `maxDurationTicks` | `2400` | 最长持续（2 分钟） |
| `cooldownTicks` | `12000` | 结束后的冷却（10 分钟） |
| `meteorsPerPlayerPerSpawn` | `3` | 每次生成波次每玩家的陨石数 |
| `spawnIntervalTicks` | `40` | 生成波次间隔（2 秒） |
| `spawnRadius` | `24` | 玩家附近落点半径（方块） |
| `spawnHeight` | `320` | 陨石生成高度（Y） |
| `explosionPower` | `5.0` | 爆炸威力（TNT 为 4） |
| `entityDamage` | `20.0` | 对范围内生物的额外直接伤害 |
| `fire` | `true` | 爆炸是否产生火焰 |

## 决策记录

- **独立自定义天气系统**：不绑定原版下雨/雷暴，自建状态机以精确控制染红天空、尾焰与命令。
- **内存态天气状态**：不跨重启持久化，随机事件无常驻必要。
- **复用 `level.explode`**：直接获得原版爆炸的方块破坏 + 生物伤害 + 击退，仅替换伤害来源与威力。
- **配置走服务端 TOML**：触发概率/时长/密度/威力均为调优旋钮，不符合项目数据驱动内容模式。

## 非目标

- 不做跨重启状态持久化。
- 不做数据驱动（Codec/reload listener）的天气内容。
- 不影响原版下雨/雷暴；流星火雨与原版天气独立共存。
- 不做陨石落点对地形的预判/避让。

## 下一步

调用 planning 技能产出实施计划。
