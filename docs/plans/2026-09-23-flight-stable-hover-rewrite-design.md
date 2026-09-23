# 稳定悬停修复重构 · 设计文档

**日期：** 2026-09-23
**状态：** 已批准（用户确认方案 A′，F-2 取 (a) 排除滑翔）
**方案：** A′ — 属性法（零原版 mixin）+ 服务端同步判定来源
**上游审查：** `docs/reviews/2026-09-23-flight-system-conflicts-and-creative-flight-comparison.md`

---

## 1. 问题陈述

PR #7 带来的 `LivingEntityStableHoverMixin` 用 mixin 修改了**原版** `LivingEntity`，并因此产生 9 项冲突（F-1..F-9）：

- **F-1** 与 DS 自己的 `mixins/LivingEntityMixin.java:169` 打在同一注入点（`@ModifyVariable(method="travel", at=@At("STORE", ordinal=0))`），两套机制并行、无显式顺序。
- **F-3** 水中零重力越过了 DS 的能力门（DS 用 `hasStableSwim` 授予，本模组用 `flight_level` 授予），且未做 `isAffectedByFluids` / `canStandOnFluid` 检查。
- **F-5** 在客户端读取 DS 的 **SERVER** 配置 `stableHover`，专用服务器上能否读到未实测。
- **F-6** 两个 mixin 各自重复七项门控，且条件不一致。
- **F-7** 原版目标放在了 `mixin/dragonsurvival/`，违反项目约定。
- **F-2 / F-4** 滑翔被接管、非稳定悬停模拟不完整（行为级）。

**目标**：在**不修改原版**的前提下（若不得不改，必须放进 `mixin/minecraft/`），消除 F-1/F-3/F-5/F-6/F-7，修正 F-2/F-4，并保留既有的飞行等级语义。

---

## 2. 设计

### 2.1 架构：把「取消重力」从 mixin 改为**属性**

只要在龙稳定悬停期间把玩家的 `Attributes.GRAVITY` 变成 0，`LivingEntity.travel` 自然就不施加重力——**完全不需要碰原版**。

**证据链（NeoForge 21.1.236 反编译源码）**：

| # | 事实 | 位置 |
|---|---|---|
| 1 | `getGravity()` 直接返回 GRAVITY 属性值 | `LivingEntity.java:2216` `return this.getAttributeValue(Attributes.GRAVITY);` |
| 2 | `travel` 里**唯一**一次读取它，存入局部 `d0` | `LivingEntity.java:2221` `double d0 = this.getGravity();` |
| 3 | 原版侧再无其它读者（`Player` / `LocalPlayer` 一次都不读） | grep `Attributes.GRAVITY` / `getGravity()` 全量 |
| 4 | GRAVITY 基础 0.08，范围 `[-1.0, 1.0]`，可同步 | `Attributes.java:74-75` |
| 5 | `ADD_MULTIPLIED_TOTAL` 的算法是 `d1 *= 1.0 + amount` | `AttributeInstance.java:158-160` |
| 6 | 有幂等的瞬时修饰符 API | `AttributeInstance.java:86` `addOrUpdateTransientModifier` |
| 7 | 修饰符是 record，`id` 用 `ResourceLocation` | `AttributeModifier.java:22` |

⇒ `amount = -1.0` 时，无论基础值与其它修饰符如何叠加，**结果恒为 0**（比 `ADD_VALUE -0.08` 稳得多）。

### 2.2 时间窗：必须在 `ClientFlightHandler.flightControl` 的 HEAD 摘 / TAIL 挂

**这是本设计最关键的一点。** DS 自己在客户端读同一个属性来算悬停：

```java
// ClientFlightHandler.java:395
double gravity = player.getAttributeValue(Attributes.GRAVITY);
// → :481  ay = Math.max(ay, gravity * 1.1)
// → :525  double yMotion = ... ? -gravity + ay : -(gravity * 4) + ay;
```

若在 DS 计算之前就把属性置 0，则 `stableHover` 的 `-g` / `-2g` 之分**消失**，本模组"追加 `-gravity` 模拟非稳定悬停"也**归零失效** ⇒ 飞行等级系统整体失效。

因此修饰符只在 `flightControl` 返回之后、`:2221` 之前存在：

```
客户端一个 tick：
┌ ClientTickEvent.Pre（NeoForge ClientHooks:1070，位于 Minecraft#tick 头部）
│  └ DS ClientFlightHandler.flightControl
│       ├─ [本次新增] HEAD：无条件摘掉 ZERO_GRAVITY   → DS 读到真实重力 0.08
│       ├─ DS 自身飞行物理
│       └─ [改造后] TAIL：shouldStabilize ?
│             ├─ 是 → ax/az 清零(仅无水平输入) + ay=0 + deltaMovement.y=0 + 挂 ZERO_GRAVITY
│             └─ 否 → 确保已摘掉
├ Minecraft#tick 主体 → level.tickEntities()
│  ├─ ServerFlightHandler.handleEarlyFlightLogic（PlayerTickEvent.Pre，双端）
│  │     └─ isGliding ? 记录 preCollisionDeltaMovement : (isFlying ? resetFallDistance)
│  ├─ LocalPlayer.aiStep()（abilities.flying=false ⇒ 无原版飞行冲量）
│  └─ Player.travel → LivingEntity.travel
│        └─ d0 = getGravity() = 0 ⇒ 不施加重力（本设计的目的）
└ ClientTickEvent.Post
```

**为什么不把修饰符留在服务端**（明确否决）：GRAVITY 是 `setSyncable(true)`，服务端修饰符会同步到客户端，于是 DS 的 `:395` 也读到 0 ⇒ 同样破坏飞行等级系统。修饰符必须留在客户端并限制在时间窗内。

### 2.3 判定来源搬到服务端（解决 F-5）

`ServerFlightHandler` 是 `@EventBusSubscriber`（裸注解，无 dist 限制）的**双端类**，`stableHover` 是 `public static boolean`，**服务端那份是权威值**。

做法（零 mixin，照抄项目现有先例 `BeLoongCore.java:199-214` 的 `TreasureSyncPayload`）：

- 服务端在 `PlayerEvent.PlayerLoggedInEvent` 读取 `ServerFlightHandler.stableHover`，用新的 BeLoong payload 发给该玩家；
- 客户端存入缓存，两个判定点都读缓存，**不再读 DS 的静态字段**。

⇒ 彻底消除"专用服务器客户端可能读不到 SERVER 配置"的不确定性。

### 2.4 统一判定

```java
static boolean shouldStabilize(LocalPlayer p) {
    return syncedStableHover                       // ← 服务端同步，替代 ServerFlightHandler.stableHover
        && !p.isPassenger() && !Minecraft.getInstance().isPaused()
        && !p.hasEffect(MobEffects.LEVITATION)
        && DragonStateProvider.isDragon(p)
        && wingsSpread && hasFlight                // FlightData 只读一次
        && ModAttributes.getFlightLevel(p) >= 1.0
        && !ServerFlightHandler.isSpin(p)
        && !ServerFlightHandler.isGliding(p)       // ← F-2(a)：滑翔完全排除
        && (ServerFlightHandler.isFlying(p) || stableWaterHover(p))
        && !p.input.jumping && !p.input.shiftKeyDown;
}

// F-3：对齐 DS 自己的检查（mixins/LivingEntityMixin.java:196）
static boolean stableWaterHover(LocalPlayer p) {
    if (!p.isInWater() || !p.isAffectedByFluids()) return false;
    return !p.canStandOnFluid(p.level().getFluidState(p.blockPosition()));
}
```

### 2.5 文件级改动

| # | 动作 | 文件 |
|---|---|---|
| 1 | **删除** | `mixin/dragonsurvival/LivingEntityStableHoverMixin.java`；并从 `beloong.mixins.json` 的 `client` 列表移除 `dragonsurvival.LivingEntityStableHoverMixin` |
| 2 | **新增** | `network/FlightStatusSyncPayload.java`（单字段 `boolean stableHover`，结构照 `TreasureSyncPayload`） |
| 3 | **改** | `BeLoongCore.java` — `:132` 处注册 payload；`:199` `onPlayerLogin` 发送 |
| 4 | **改** | `mixin/dragonsurvival/ClientFlightHandlerMixin.java` — HEAD 摘除 + TAIL 施加、`shouldStabilize`、`stableWaterHover`、修饰符常量 |
| 5 | **改** | `registry/ModAttributes.java` — 属性缺失时一次性 WARN |
| 6 | **改** | `Config.java:21` — 更新 `FIX_STABLE_HOVER` 的 comment，说明它是总开关，实际生效还取决于 DS 的 `stable_hover` + `flight_level ≥ 1` |

**改完后全项目对原版零 mixin**，唯一的原版接触点是 `AttributeModifier`（纯 API 调用）。

---

## 3. 数据流

### 3.1 登录同步

```
服务端 PlayerLoggedInEvent（BeLoongCore.onPlayerLogin）
  └─ 读 ServerFlightHandler.stableHover（服务端权威值）
      └─ PacketDistributor.sendToPlayer(player, new FlightStatusSyncPayload(value))
          └─ 客户端 handleClient → 写入缓存（默认值 false = 最保守）
```

### 3.2 每 tick 的修饰符生命周期

```
flightControl HEAD  → attribute.removeModifier(ZERO_G)
flightControl TAIL  → if (shouldStabilize) attribute.addOrUpdateTransientModifier(ZERO_GRAVITY)
                      else                 attribute.removeModifier(ZERO_G)
```

幂等：即使被服务端属性同步清掉，下一 tick 的 TAIL 会重新挂上。

---

## 4. 边界与错误处理

| 场景 | 处理 |
|---|---|
| `Config.FIX_STABLE_HOVER` 被关掉 | **HEAD 无条件摘除**，且 HEAD 在任何 early-return 之前 ⇒ 不会残留修饰符 |
| 服务端属性同步清除修饰符 | `ClientPacketListener.handleUpdateAttributes` 会 `removeModifiers()`；每 tick TAIL 幂等重挂，最坏丢 1 tick（≈0.08 格） |
| payload 未送达（老客户端 / 连接异常） | 缓存默认 `false` ⇒ 不干预，退化为 DS 原版行为（安全方向） |
| `flight_level` 属性缺失（DS 变更导致 `DSAttributesMixin` 失效） | `getFlightLevel` 返回 0 ⇒ 不进入稳定悬停，并打一次性 WARN |
| DS `stable_hover = false` | `syncedStableHover = false` ⇒ **完全不干预** |
| 玩家死亡 / 换维度 / 断线 | 修饰符是 transient，不入 NBT；属性实例随实体重建 |
| 旋转攻击 | `isSpin` 排除，交给 DS |
| 滑翔 | `isGliding` 排除 ⇒ DS 滑翔物理一字不动（**同时**：重力属性在滑翔期间也不再被置 0） |

---

## 5. 决策记录

| # | 决策 | 理由 | 被否决的方案 |
|---|---|---|---|
| D1 | 用 GRAVITY 属性取消重力，**不打原版 mixin** | 满足"尽量不改原版"约束；原版侧只有 `travel` 读该属性，时间窗可控 | 保留原版 mixin 并移入 `mixin/minecraft/`（仍改原版） |
| D2 | 修饰符只存在于 `flightControl` TAIL → 下 tick HEAD 之间 | 否则 DS 的 `:395` 读到 0，`stableHover` 之分与"非稳定模拟"双双失效 | 服务端持久持有（会被同步到客户端，同样破坏） |
| D3 | `ADD_MULTIPLIED_TOTAL = -1.0` | 恒为 0，与基础值/其它修饰符无关 | `ADD_VALUE = -0.08`（假设总和恰好 0.08） |
| D4 | `stableHover` 由服务端同步 | 服务端有权威值；消除 F-5 的跨端配置读取不确定性 | 继续在客户端读静态字段（未实测，有静默失效风险） |
| D5 | 滑翔排除出接管（F-2(a)） | 与 DS 2.0.68/2.0.70 的防抖/保动量修复完全一致；滑翔物理不动 | (b) 插值对齐视线 / (c) 只对齐水平分量 —— 用户裁定后续按实机手感再调 |
| D6 | 水中零重力补 DS 同款检查 | 对齐 `LivingEntityMixin:196` 的 `isAffectedByFluids` / `canStandOnFluid` | 沿用 PR 的无条件判断（会变成"站水面不下沉"） |
| D7 | `flightLevel < 1` 分支去掉 `noMoveInput` | 移动时 DS 给 `-g + y`，追加 `-g` 即得 DS 的 `-2g + y`（`:497`），模拟才完整 | 保留 `noMoveInput`（移动时仍享受稳定悬停阻尼） |

---

## 6. 非目标

- **不**改 DS 的滑翔物理（滑翔完全排除出接管）。
- **不**复刻原版创造模式飞行的水平/垂直数值——那是另一项需求（见审查报告 §3 的对比表），需另立设计。
- **不**改旋转、饥饿消耗、坠落伤害、碰撞伤害体系。
- **不**动 `ax/ay/az` 之外的 DS 内部状态。

---

## 7. 验证方案

**编译/启动**
1. `gradlew build` 通过，只有既有的 3 条 `@Shadow`/`@Accessor` 映射警告。
2. 启动日志中 `LivingEntityStableHoverMixin` **不再出现**；17 条 `Mixing dragonsurvival.*` 无新增错误。

**单人四组场景（阴性对照必须做）**

| # | 场景 | 期望 |
|---|---|---|
| A | DS `stable_hover=false` | 飞行手感与"未装本模组"完全一致（本模组不介入） |
| B | `stable_hover=true` + `flight_level=0` | 静止与移动时竖直行为**都**等于 DS 的 `stableHover=false`（移动分支应为 `-2g`） |
| C | `stable_hover=true` + `flight_level=1` | 无输入时高度恒定；跳跃/下潜仍由 DS 驱动（0.4 / −0.5） |
| D | 冲刺滑翔 | 与 DS 原版一致（本模组不介入） |

**属性读数验证**
3. 悬停时确认 `Attributes.GRAVITY` = 0，脱离悬停后恢复 0.08（`/attribute` 或临时日志）。

**专用服务器**
4. 登录后确认客户端收到的 `syncedStableHover` 与服务器 `dragonsurvival-server.toml` 的 `stable_hover` 一致（临时打印一行），并在该环境重跑场景 C。

---

## 8. 下一步

进入 `planning` 技能，产出实施计划（任务分解 + 每步验收）。
