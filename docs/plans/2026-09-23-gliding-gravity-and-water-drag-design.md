> # ❌ 已否决，未实施（2026-09-23）
>
> 本文档描述的方案**已实施后回退**：实机测试效果不佳，用户裁定不再修改飞行系统。
> 相关代码改动（`flight/StableFlightState.java`、`mixin/minecraft/PlayerFluidInteractionMixin.java`、
> `ClientFlightHandlerMixin` 的状态机与三路分流、`beloong.mixins.json` 条目）**全部已回退**，
> 工作树回到 `c9181cf`。
>
> **保留本文档的理由**：其中若干结论对以后仍有价值，勿因"已否决"而连带丢弃——
> - DS 的 `ServerFlightHandler.isFlying()` 带 `!isInWater()`（`:170`）⇒ 水中没有 DS 的飞行推力；
> - `Player#isAffectedByFluids()` 是原版创造飞行"不受水阻力"的**唯一**机制，且**无属性可替代**；
> - 一旦覆写 `isAffectedByFluids`，它就变成系统的**输出**，任何谓词都不得再读它（否则抖动）；
> - 属性法（在 DS 计算前置 0）能同时覆盖 DS `:408` 的重力项与 `travel` 的 `d0`，
>   而 PR #7 改 `travel` 局部量的做法只能覆盖后者。

---

# 滑翔去重力 + 水中去阻力 · 设计文档

**日期：** 2026-09-23
**状态：** ~~已批准（四个子决策均按推荐确认）~~ → **已否决，未实施**
**上游文档：**
- `docs/plans/2026-09-23-flight-stable-hover-rewrite-design.md`（属性法本体）
- `docs/reviews/2026-09-23-flight-system-conflicts-and-creative-flight-comparison.md`（冲突 F-1..F-9）

**本次两条需求：**
1. 当前刻意不接管滑翔，改为**滑翔也不再受重力下坠**。
2. **水中飞行不受水的阻力**，与空中一致（新的原版 mixin）。

---

## 1. 需求 1：滑翔去重力

### 1.1 为什么必须在「DS 计算之前」就把重力归零

滑翔期间有两个独立的向下分量：

| 来源 | 位置 | 平视时的量级 |
|---|---|---|
| **DS 自己** | `ClientFlightHandler.java:408` `deltaMovement.add(0, gravity * (-1 + verticalDelta * 0.75), 0)` | ≈ **−0.25g** |
| `LivingEntity.travel` | `:2221` `d0 = getGravity()` → `d2 -= d0` | ≈ **−1g** |

**属性法能同时覆盖两者**，因为 DS 的 `:395` 也是 `player.getAttributeValue(Attributes.GRAVITY)` —— 只要修饰符在 `flightControl` 执行期间就位，DS 的局部 `gravity` 与 `travel` 的 `d0` 会**同时为 0**。

**PR 的做法（`@ModifyVariable` 改 `travel` 的重力局部量）只能覆盖后一半**，滑翔仍会以约 −0.25g 缓慢下沉 ⇒ 达不到"不下坠"。这是我们不采用 PR 做法的直接原因。

### 1.2 HEAD 状态机（取代现在的"无条件摘除"）

```
flightControl HEAD:  滑翔且合格 → 挂修饰符      否则 → 摘除
flightControl TAIL:  悬停分支   → 挂修饰符（现状不变）
                     滑翔分支   → 不动作（HEAD 已挂）
                     非稳定分支 → 确保已摘除（重力必须生效）
```

**为什么悬停分支仍然只能在 TAIL 挂**：DS 的稳定/非稳定之分依赖它读到**真实**重力——
`ay = Math.max(ay, gravity * 1.1)`（`:481`）、`-gravity + ay`（`:525`）、
以及我们非稳定分支要追加的 `-g`。若在 HEAD 就置 0，两者会同时退化为 0 基准。

**为什么滑翔可以且必须在 HEAD 挂**：滑翔走的是 `:404-460` 的加速分支，里面只用
`gravity` 做 `:408` 那个项；把它置 0 正是需求 1 的目的，且滑翔不参与"稳定/非稳定"之分。

### 1.3 三条路径分流（TAIL）

| 状态 | 竖直处理 | 重力 | 水平 |
|---|---|---|---|
| **滑翔**（`isGliding`，需 `flight_level ≥ 1`） | **完全不碰**（保留 DS 的俯冲/抬头动力学） | 已在 HEAD 置 0 | 交给 DS |
| **悬停**（`flight_level ≥ 1`） | `ay = 0` + `setDeltaMovement(x, 0, z)` | TAIL 挂上 | 交给 DS（不再清 `ax/az`） |
| **非稳定**（`flight_level < 1`，仅空中） | 追加 `-g`（含 `wasFlying` 守卫） | **必须为真实值** | 交给 DS |

### 1.4 子决策：只去重力，不做「速度对齐视线」

`viewVector.y < 0` 时 `ax/az` 照样累加（`:427-428`），所以**俯冲加速仍然可用**，只是"下坠"不再由重力白送。不采用 PR 的 `look.scale(speed)`——它抹掉动量，与 DS 2.0.70 `dfdbefcfc` 的保动量修复正面冲突。

---

## 2. 需求 2：水中去阻力

### 2.1 平台机制（已用源码核实）

```java
// net.minecraft.world.entity.player.Player
1143: public boolean isAffectedByFluids() { return !this.abilities.flying; }   // ← 原版创造飞行的全部秘密
1979: public boolean isPushedByFluid()   { return !this.abilities.flying; }
// net.minecraft.world.entity.LivingEntity
2228: if ((isInWater() || ...) && this.isAffectedByFluids() && !canStandOnFluid(fluidstate)) { ... 水阻力 multiply(f, 0.8, f) ... }
```

⇒ 置 `isAffectedByFluids() = false` 会**整段跳过水物理**，落到普通空中分支 = 与空中一致。

**无属性替代方案**（已核实）：
- `WATER_MOVEMENT_EFFICIENCY` 只会把 `f` 往 0.546 拉，且 y 上的 `0.8` 是硬编码；
- `isPushedByFluid` / `updateSwimming` 同理；
- 改 `FluidType` 会影响全局玩家。

⇒ **必须 mixin 原版 `Player`**。这是需求 2 的必然代价，也是本项目从"零原版 mixin"回到"1 个原版 mixin"的唯一原因。

### 2.2 新 mixin 规格

```
文件：src/main/java/com/zonlong/beloong/mixin/minecraft/PlayerFluidInteractionMixin.java
注册：beloong.mixins.json 的 "client" 列表 → "minecraft.PlayerFluidInteractionMixin"
```

```java
@Mixin(Player.class)
public abstract class PlayerFluidInteractionMixin {
    @Inject(method = "isAffectedByFluids", at = @At("HEAD"), cancellable = true, remap = false)
    private void beloong$ignoreWaterDrag(CallbackInfoReturnable<Boolean> cir) { ... }

    @Inject(method = "isPushedByFluid", at = @At("HEAD"), cancellable = true, remap = false)
    private void beloong$ignoreFluidPush(CallbackInfoReturnable<Boolean> cir) { ... }
}
```

两个方法同源（原版创造飞行两者都是 `!abilities.flying`），**必须一起改**，否则残留水流推动。
`remap = false` 是本项目对原版目标的硬约定。

**客户端限定**：放在 `client` 列表。玩家移动是客户端权威，服务端不必掺和；这也让条件可以安全地读客户端状态。

### 2.3 已知差异（A 方案的固有代价，已记录）

`ServerFlightHandler.isFlying()` 带 `!player.isInWater()`（`:170`，还留着
`/* TODO :: more universal check for fluids */`）⇒ **水中 DS 仍认为你不在飞**，
`flightControl` 走 `else` 分支把 `ax/az/ay` 清零（`:531-537`）。

因此 A 方案下水中的体验是：**保持入水速度滑行 + 锁高度 + 无阻力 + 无流体推动**，
但**没有 DS 的飞行推力与视角跟随**。要连推力都一样需 B 方案（另 mixin DS 的 `isFlying`），
本次不做。

### 2.4 子决策：`isPushedByFluid` 一并处理；暂不强制非游泳

**一并处理**：见 2.2。

**暂不强制 `setSwimming(false)`**（原版创造飞行会做，`Player.java:1601-1605`）：
存在一个**残余项**——`Player.travel` 开头的游泳上浮块（`if (isSwimming() && !isPassenger())`，
把 `deltaMovement.y` 以 0.06~0.085 的系数拉向视线）**不受 `isAffectedByFluids` 约束**。
但强制非游泳会改龙的姿势、且可能干扰 DS 的稳定游泳/水中旋转体系。
⇒ **先只做 2.2 并实测**；若残余上浮明显，再作为后续增量处理。

---

## 3. 架构清理：共享谓词（避免重演 F-6）

两个 mixin 都要回答"玩家现在是否处于本模组的稳定飞行状态"。本项目已因**两处重复门控、条件不一致**吃过一次亏（审查报告 F-6）。因此：

- 把谓词抽到一个纯逻辑类（暂定 `com.zonlong.beloong.flight.StableFlightState`）：
  - `isStableHoverEligible(player)` —— 共同前置（配置 / `ServerFlightHandler.stableHover` / 非骑乘 / 非暂停 / 无漂浮 / 是龙 / 展翅 / 有飞行能力 / 非旋转 / 无垂直输入）
  - `isGlidingFlight(player, level)` / `isStableHover(player, level)` / `isWaterFlight(player, level)`
- `ClientFlightHandlerMixin` 与 `PlayerFluidInteractionMixin` **都调它**，不再各写一份条件。

> 注意：`ServerFlightHandler.stableHover` 必须**直接读 DS 的字段**（不引入任何副本）——
> 这是上一轮实测②③异常的结论，理由写在 `ClientFlightHandlerMixin` 的类 javadoc 里。

---

## 4. 决策记录

| # | 决策 | 理由 | 被否决 |
|---|---|---|---|
| D1 | 滑翔去重力靠**属性**在 DS 计算前置 0 | 属性法能同时覆盖 DS `:408` 与 `travel` 的 `d0`；PR 的做法只能覆盖后者 | PR 的 `@ModifyVariable travel` 做法 |
| D2 | HEAD 改为状态机（滑翔挂 / 其余摘） | 悬停与非稳定分支必须让 DS 读到真实重力 | "HEAD 一律摘除"（会漏掉 DS 的 −0.25g） |
| D3 | 滑翔只去重力，**不做**速度对齐视线 | 保住动量，与 DS `dfdbefcfc` 的修复一致 | PR 的 `look.scale(speed)` |
| D4 | 滑翔去重力要求 `flight_level ≥ 1` | 否则 0 级龙的"非稳定悬停"语义被绕过 | 无门槛 |
| D5 | 水中去阻力靠新 mixin `Player#isAffectedByFluids` / `#isPushedByFluid` | 这是原版创造飞行的**唯一**机制，无属性/事件替代 | 属性法（已核实不可行）、改 FluidType（影响全局） |
| D6 | 暂不强制非游泳 | 先实测 A；强制会改姿势并可能干扰 DS 游泳体系 | 现在就照抄原版 `updateSwimming` |

---

## 5. 非目标

- **不改** DS 的 `ServerFlightHandler.isFlying()`（即不做 B 方案）。水中的"无 DS 推力"是 A 方案的已知差异。
- **不做**速度对齐视线 / 复刻创造飞行的水平或垂直数值。
- **不改** DS 的滑翔俯冲动力学（只把重力分量去掉）。
- **不动**旋转、饥饿、坠落伤害、碰撞伤害体系。

---

## 6. 验证方案

**编译/启动**
1. `gradlew build` 通过，警告数仍为既有 3 条。
2. 启动日志中 `PlayerFluidInteractionMixin` 正常 Mixing，无注入错误。

**滑翔（需求 1）**
3. `stable_hover=true` + `flight_level≥1` + 冲刺滑翔：平视时**不再缓慢下沉**；俯冲仍能加速；抬头仍能爬升。
4. `stable_hover=false`（阴性对照）：滑翔与未装本模组时一致（DS 原版，含重力）。
5. `flight_level=0`：滑翔仍会下沉（非稳定语义未被绕过，D4）。

**水中（需求 2）**
6. `stable_hover=true` + `flight_level≥1`，入水飞行：**无水的减速**（横向速度不被水拖慢）、**无水流推动**、高度锁住；与同等条件下在空中的水平手感一致。
7. 对照：`flight_level=0` 或 `stable_hover=false` 时，水中与 DS 原版一致（正常游泳/阻力）。
8. 观察 2.4 的残余项（冲刺入水时是否有轻微上浮）；有则记录，决定是否追加增量。

**回归**
9. 上一轮的四组对照（①②③④）仍成立。

---

## 7. 与既有两份文档的关系（订正）

| 文档 | 已失效内容 | 说明 |
|---|---|---|
| `2026-09-23-flight-stable-hover-rewrite-design.md` | **D4「`stableHover` 由服务端同步」** | 已废弃：NeoForge 自带 SERVER 配置同步（`network/ConfigSync`），且引入副本会造成判定前提与 DS 物理前提分叉（实测②③）。现行做法是直接读 `ServerFlightHandler.stableHover` |
| `2026-09-23-flight-stable-hover-rewrite-plan.md` | **T1 / T2 / T3** | 已作废（缓存与同步包已删除）；T4–T12 已实施 |

> 本设计文档**不**修改上述两份文件；它们的正文保留为历史记录，以此表为准。

---

## 8. 下一步

进入 `planning` 技能，产出实施计划（任务分解 + 每步验收）。
