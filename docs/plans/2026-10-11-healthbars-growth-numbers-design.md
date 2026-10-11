# Health Bars「成长被当成治疗飘字」修复设计文档

- **日期**：2026-10-11
- **状态**：**已批准**（Phase 4 四节逐节确认）
- **选定方案**：**A —— 比例不变性判定**（单 Mixin，只改飘字取值）
- **关联**：`docs/reviews/2026-10-11-health-display-pr19-review.md`（PR #19 审查与处置）、`memory/decisions-log.md`（2026-10-11 两条）

## 一、问题陈述

**现象**：龙之生存（Dragon Survival）的龙在成长期间，Health Bars 会持续冒**绿色 `+1`**（治疗色）飘字，而玩家既没受伤也没被治疗。

**根因链**（每一步都已核到源码行号）：

1. 成长时龙之生存**按比例重设血量**：`DSModifiers.updateGrowthModifiers`（`DSModifiers.java:51-64`）
   `float healthPercentage = player.getHealth() / player.getMaxHealth();`
   → `removeModifiers(DRAGON_STAGE)` → `applyModifiers(..., growth − 最小值)`
   → `player.setHealth(player.getMaxHealth() * healthPercentage);`
   ⇒ **血量绝对值变大、百分比不变**（等价于 `newMax * oldHealth / oldMax`）。
2. Health Bars 只看**绝对差**：`HealthTracker.tick`（`HealthTracker.java:55-61`）把
   `this.lastHealthDelta = health - this.health;` 记为"这 tick 涨了血"，该类只额外记录 `maxHealth` 用于血条动画，**没有任何"最大生命变化引起的等比抬升"判定**。
3. 该差值被取整**放大**：`getLastHealthDelta()`（`HealthTracker.java:37-39`）
   `return Mth.ceil(Math.abs(this.lastHealthDelta)) * Mth.sign(this.lastHealthDelta);`
   ⇒ 哪怕成长只带来 **+0.0004**，也显示成 **+1**。
4. 每 tick 生成一个飘字：`HealthTrackerHandler.onEndEntityTick`（`HealthTrackerHandler.java:17-26`）
   `if (healthDelta != 0 && CONFIG...damageValues.renderDamageValues) addDamageValueParticle(entity, healthDelta);`
   驱动链是 Puzzles Lib 的 `EntityTickEvents.END`（`HealthBarsClient.java:27`）；配置默认开启
   （`ClientConfig.java:165  renderDamageValues = true`）。

**为什么不能靠配置压掉**：Health Bars 的 `DamageValues` 只有
`renderDamageValues` / `damageColor` / `healColor` / `strongTextOutline`（`ClientConfig.java:163-175`）
⇒ **没有阈值、也没有"隐藏治疗"开关**，只能整体关掉飘字。

**修复目标（用户裁定，口径 A）**：只保证「**纯成长不冒数字**」；不追求「成长 + 真实变化同刻时精确扣减」。

## 二、设计

### 2.1 架构

**一句话**：在 Health Bars 的"每实体 tick 末"钩子上，用**几何不变量**（等比抬升 ⇒ 血量百分比不变）识别「这次血量变化是成长造成的」，再把**飘字取值**改成 0 —— **不写 Health Bars 的任何状态**。

- **载体**：新增 `com.zonlong.beloong.mixin.healthbars.HealthTrackerHandlerMixin`，注册进 `beloong.mixins.json` 的 `client` 数组。
- **注解**（项目约定）：`@Pseudo` + `@Mixin(value = HealthTrackerHandler.class, remap = false)`；
  可选目标 ⇒ 全部注入点 `require = 0`（未装即整体跳过；上游改名即静默失效，不硬崩）；
  `neoforge.mods.toml` 里 `healthbars` 的 `type="optional" versionRange="[21.1.0,)" ordering="AFTER" side="CLIENT"` **已存在**。
- **注入点 1（判定）**：`@Inject(method = "onEndEntityTick(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))`
  —— 拿到 `Entity`，与自己的快照比较，得出"本 tick 抑制"标志。
- **注入点 2（取值）**：`@Redirect(method = "onEndEntityTick(...)V", at = @At(value = "INVOKE", target = "Lfuzs/healthbars/client/helper/HealthTracker;getLastHealthDelta()I"))`
  —— 这是**全方法唯一一处**（javap 偏移 34、计数 1）。置位 ⇒ 返回 `0` ⇒ Health Bars 自己的 `healthDelta != 0` 判断不成立 ⇒ **不生成飘字**；未置位 ⇒ 返回原值（行为与现状完全一致）。

**为什么不需要"时间窗"**（与 PR 方案的本质差别）：服务端那次重算的顺序是**固定**的 ——
`DSModifiers.updateGrowthModifiers` = `removeModifiers` → `applyModifiers` → `setHealth(...)`
⇒ 最大生命的属性变化**必然先于/同批于**血量变化发出（同一条连接顺序送达）
⇒ 判定可以直接写成「**本 tick 的 max 变了，且血量百分比没变**」，同一 tick 定论，无需 2 tick 采样合并。

### 2.2 组件

| 组件 | 位置 | 内容 |
|---|---|---|
| Mixin 类 | `src/main/java/com/zonlong/beloong/mixin/healthbars/HealthTrackerHandlerMixin.java` | 判定处理器 + 取值处理器 + 状态 + 锚点日志 |
| 注册 | `src/main/resources/beloong.mixins.json` 的 `client` 数组 | `"healthbars.HealthTrackerHandlerMixin"` |
| 依赖声明 | `src/main/templates/META-INF/neoforge.mods.toml` | 已有 `healthbars` optional 段，无需改动 |

类内部结构：

```
@Pseudo @Mixin(value = HealthTrackerHandler.class, remap = false)
abstract class HealthTrackerHandlerMixin
  @Unique Map<UUID, Float> beloong$prevHealth        // 只对龙玩家建条目
  @Unique Map<UUID, Float> beloong$prevMaxHealth
  @Unique static boolean  beloong$suppressThisTick   // 仅一次方法调用内有效
  @Unique static boolean  beloong$anchorLogged       // 首次抑制时打一条 INFO
  @Unique static final double BELOONG_RATIO_TOLERANCE = 1.0e-4;

  @Inject(HEAD, onEndEntityTick)   beloong$evaluateRescale(Entity)
  @Redirect(getLastHealthDelta)    beloong$filterDelta(HealthTracker) : int
```

判定伪码（HEAD）：

```
if (!(entity instanceof Player p) || !entity.level().isClientSide()
        || !DragonStateProvider.isDragon(p)) { 清条目; 不抑制; return; }
float h = entity.getHealth(), m = entity.getMaxHealth();
if (!Float.isFinite(h) || !Float.isFinite(m) || m <= 0) { 清条目; 不抑制; return; }
Float ph = prevHealth.get(uuid), pm = prevMaxHealth.get(uuid);
boolean suppress = false;
if (ph != null && pm != null && pm > 0 && m != pm) {           // 只在"最大生命变化"的那一 tick 判定
    double before = ph / pm, after = h / m;
    suppress = Math.abs(after - before) <= BELOONG_RATIO_TOLERANCE;
}
prevHealth.put(uuid, h); prevMaxHealth.put(uuid, m);
beloong$suppressThisTick = suppress;
```

取值处理器：置位 ⇒ `return 0`（并在**首次**抑制时打一条锚点 INFO），否则 `return tracker.getLastHealthDelta()`；
标志在取值处理器里消费后清零。

### 2.3 数据流

```
[服务端] 成长值↑（自然成长 / 成长物品）
   DragonStateHandler.setGrowth(...)
     └─ DSModifiers.updateGrowthModifiers(player, handler)      ← DSModifiers.java:51-64
          ① removeModifiers(DRAGON_STAGE)        // 最大生命可能先掉回基础值
          ② applyModifiers(..., growth−最小值)    // 按新成长重设最大生命
          ③ setHealth(getMaxHealth() * healthPercentage)   // 血量绝对值抬高、百分比不变
     → 发出：属性包(max) → 实体数据包(health) → SyncGrowth 包（同连接，顺序固定）
[客户端]
   SyncGrowth.handleClient（SyncGrowth.java:25）→ setGrowth → updateGrowthModifiers（本地再算一次，通常同值/幂等）
   实体数据包 → LivingEntity.setHealth(新值)
   每实体 tick 末：Puzzles EntityTickEvents.END → HealthTrackerHandler.onEndEntityTick(entity)
     ├─【本模组 HEAD】读 (health, maxHealth) ↔ 快照 ⇒ 命中"只改了最大生命、百分比不变"⇒ 置抑制标志
     ├─ healthTracker.tick(entity)                     ← 原逻辑完全不动（它照旧算出 +0.0004）
     ├─ int delta = healthTracker.getLastHealthDelta()  ←【本模组 @Redirect】置位 ⇒ 0
     └─ if (delta != 0 && renderDamageValues) addDamageValueParticle(...)  ← 置位时不执行
```

### 2.4 边界与错误处理

**判定规则（对称）**：

```
suppress ⇔ (m ≠ pm) && |h/m − ph/pm| ≤ 容差 && (h,m) ≠ (ph,pm)
```

只在"最大生命发生了变化"的那一 tick 判定 ⇒ **恒定最大生命下的真实治疗/伤害永远不会被抑制**；
百分比不变就是等比重算（放大或缩小都算）⇒ 抑制。
**对称是必须的**：龙之生存存在**偷成长**（`EnchantmentEffectHandler.java:89-94`
`victimData.setDesiredGrowth(victim, getGrowth() - stolen)`）⇒ 等比缩小时若不抑制，会冒一个**假伤害红字**。

**唯一旋钮：容差 `1.0e-4`**

- 噪声量级：DS 用 `float` 算百分比（`DSModifiers.java:56,63`），比例本身相对误差 ~1e-7；再经实体数据包(float)+属性包(double) 同步仍 ~1e-7。
- 真实变化的最小可见粒度：Health Bars 的 `ceil` ⇒ 最小可显示变化是 **1 点血** ⇒ 比例变化 = `1/max`（max=40 ⇒ 0.025；max=1000 ⇒ 0.001），比 1e-4 大 1~2 个数量级。
- ⇒ `1e-4` 有约 1000× 噪声余量，且不会吞掉任何"1 点血"级的真实变化；想更保守可调小到 `1e-5`。

| 情形 | 行为 | 理由 |
|---|---|---|
| 无快照（登录 / 首次见到该玩家） | 不抑制 | 避免吞掉登录时的血量初始化 |
| 换维度 / 重生（客户端重建 LocalPlayer） | 不抑制 | 该场景比例会被破坏（血量被钳到基础值）⇒ 判定天然不成立；也保证不与 AsteorBar 修复互相干扰 |
| 非龙玩家 / 非玩家实体 / 服务端 | 不抑制并清条目 | 只服务龙的成长问题（与 AsteorBar 修复同口径）；想去掉限制只需删 `isDragon` |
| `m ≤ 0` 或 `h/m` 非有限值 | 清条目、不抑制 | 避免 NaN 比较行为不确定 |
| 等比缩小（偷成长 / 降级） | **抑制为 0** | 不是真实伤害；见上"对称" |

**状态与生命周期**：

| 状态 | 清理 | 上限 |
|---|---|---|
| `prevHealth` / `prevMaxHealth`（按 UUID） | 判定分支为"非龙/非玩家/非客户端"或非法数值时移除 | ≤ 玩家数（只对龙玩家建条目），不随实体数增长 |
| `suppressThisTick` | 取值处理器消费后清零 | 单次方法调用内有效；客户端主线程串行 ⇒ 无跨实体串扰 |

**上游漂移与失败模式**：`@Pseudo` ⇒ 未装跳过；`require = 0` ⇒ 上游改签名**静默失效**（表现就是"+1 又回来了"），不硬崩；
`getLastHealthDelta()` **只在这一处被调用**（javap=1 / `HealthTrackerHandler.java:21`）⇒ 改成 0 没有其它副作用（它也不驱动血条动画）。

### 2.5 验证策略

**静态门（实现后由智能体执行并给证据块）**：

| 检查 | 期望 |
|---|---|
| `gradlew build --offline --no-configuration-cache` | 退出码 0（仅既有 3 条 `@Shadow/@Accessor` 映射告警）|
| `gradlew compileJava --rerun-tasks --no-build-cache --offline` | 退出码 0 |
| javap 复核 | `onEndEntityTick(Lnet/minecraft/world/entity/Entity;)V` 内 `getLastHealthDelta()` = **1 次** |
| 产物 | jar 内含 `HealthTrackerHandlerMixin.class`；jar 内 `beloong.mixins.json` 的 `client` 含该条目 |

**实机验收清单**：

| # | 场景 | 期望 | 判据 |
|---|---|---|---|
| V1 | 龙**静止成长** | **完全不出现绿色数字** | 盯 30 s 无任何 `+N` |
| V2 | 成长期间**受到伤害** | 红色伤害数字照常、数值=真实伤害 | 与关掉本 mixin 时读数一致 |
| V3 | 成长期间**被治疗**（再生/食物/模组治疗） | 数字照常显示（除非恰好百分比完全不变 ⇒ 被吞，可接受） | 治疗值可读 |
| V4 | 触发**偷成长**效果 | **不出现虚假伤害数字** | 被偷瞬间无红字 |
| V5 | **换维度 / 重生** | AsteorBar 修复照常；Health Bars 不出虚假 `+N` | 同时看是否有"与钳位差值相当的大数字"（已知漏网）|
| V6 | **多龙/多玩家同屏** | 互不干扰 | 各实体飘字独立正确 |
| V7 | **未装 Health Bars** | 正常启动、无报错 | 启动日志无 `healthbars` 相关错误 |
| V8 | 关系对照：临时注释掉该 mixin | `+1` **复现**；恢复即消失 | 排除"别的东西碰巧修好了" |
| V9 | 专用服务端 | 无此代码、无影响 | 服务端启动正常 |

**验证支持（锚点日志）**：抑制的效果是"什么都不出现"，无法区分"修好了"与"这次恰好没成长" ⇒
**每次进程内第一次抑制时**打一条（英文 ASCII、项目格式）：

```
[BeLoong] healthbars: growth-rescale number suppressed (first hit this session)
```

不做逐次日志；不引入配置项。

**已知漏网（登记，不在本轮修）**：

1. **AsteorBar 修复写回血量那一刻**（换维度冻结结束）比例发生变化 ⇒ 不抑制 ⇒ Health Bars 可能冒一个**假治疗大数字**
   （既有相互作用残留：旧修复同样写回血量时也会如此），要根治需跨模块协同。
2. **同一 tick 内既有成长又有真实变化** ⇒ 按口径 A 一律不显示那一次数字（可能吞掉一个真实小数字）。
3. **属性包与血量包顺序异常**（当前 DS 顺序保证属性在前）⇒ 若将来反序，那一次会漏一个 `+N`。

## 三、关键决策与理由

| 决策 | 理由 |
|---|---|
| 用**几何不变量**（百分比不变）判定，而不是读成长值 | 不耦合龙之生存内部；天然覆盖放大与缩小两个方向；只需一个条件 |
| 抑制点选**飘字取值处**（`@Redirect getLastHealthDelta()`），不覆盖私有字段 | 不写第三方状态；该取值全仓仅 1 处调用（javap=1）⇒ 无其它副作用；血条/动画/GUI 数字完全不受影响 |
| **不加固定采样窗口** | 服务端"先改属性再 `setHealth`"决定 max 变化先于/同批于血量变化 ⇒ 同 tick 可判定（与 PR 方案的本质差别）|
| 判定**只对龙玩家** | 与 AsteorBar 修复同口径；不影响其它模组的等比重算（去掉只需删一行）|
| 规则**对称化** | 存在"偷成长"⇒ 等比缩小时不抑制会冒假伤害红字 |
| 增加**首次抑制锚点日志** | 抑制类修复必须可观测，否则实机无法区分"修好了"与"未触发" |
| **不新增配置项** | 与《方块小镇》声明屏蔽的处理一致（默认生效）；唯一旋钮 `BELOONG_RATIO_TOLERANCE` |

## 四、被否方案（留痕）

| 方案 | 内容 | 否决理由 |
|---|---|---|
| **B** | 龙之生存侧精确埋点（mixin `DSModifiers.updateGrowthModifiers`）+ Health Bars 侧核销 | 两个 mixin、耦合 DS 内部方法名；口径 A 下不需要这种精度 |
| **C** | PR 思路变体（采样 `(tick, health, max, growth)` + 2 tick 窗口 + **覆盖私有 `lastHealthDelta`**） | 正是要换掉的方案：写第三方私有字段、需要固定窗口 |
| **配置层** | 关掉飘字 / 改治疗色 | Health Bars 无阈值与"隐藏治疗"开关 ⇒ 只能整体关掉，代价过大 |

## 五、非目标

- 不做"成长 + 真实变化同刻"的精确扣减（口径 A）。
- 不改 Health Bars 的任何内部状态与配置；不改服务端；不改龙之生存行为。
- 不动 AsteorBar 修复代码（场景正交：比例被破坏时本判定不抑制）。
- 不处理"AsteorBar 修复写回血量时 Health Bars 的假治疗数字"（既有残留，见 §2.5 已知漏网 1）。

## 六、下一步

调用 `planning` 技能产出实施计划（T0…Tn），其验收项直接映射到本文档 §2.5 的 V1–V9；
实现后由智能体执行静态门并给证据块，实机验收由作者完成。**设计文档与代码改动只 `git add` 暂存，提交由作者执行。**

## 七、方案修订：加入"噪声地板"（2026-10-11，委托方实测驱动）

**实测确认（委托方，零代码验证）**：把 Health Bars 的 `render_damage_values` 关掉后绿字消失 ⇒ 绿字确为 Health Bars 的 **level 飘字**，也就是本 Mixin 拦截的那条路径 —— 首轮无效的原因**不是**"注入没生效"（`debug.log` 证明 mixin 已应用、锚点日志也说明抑制触发过），而是**判定门槛覆盖面不够**。

**首轮漏在哪（已核到源码）**：

- Health Bars 对**任何非零**血量差都 `Mth.ceil` 成 ±1（`HealthTracker.java:37-39`）；
- 一次龙成长会**写两次血量**：
  ① 客户端自己按比例重算（`SyncGrowth.java:25` → `DragonStateHandler.setGrowth` → `DSModifiers.updateGrowthModifiers`，`max` 与 `health` 在同一次调用里都变）；
  ② 服务端同 tick 的 `ServerEntity.sendDirtyEntityData`（`ServerEntity.java:325-341`）同步"实体数据（血量）+ 属性（最大生命）"，但属性包**只在 `getAttributesToSync()` 非空时才发**，而客户端此刻的 max 已由 ① 算成同一个值 ⇒ 从本 Mixin 看"最大生命没变" ⇒ 首轮"只在 max 变化那一 tick 判定"**直接跳过**；
- 于是 ② 的残差（两侧各自 float 计算的舍入差，可能仅 1 ULP）原样交给 Health Bars ⇒ 冒 ±1。叠加 `DamageValueParticle.lifetime = 50`（2.5 秒）与 `DragonStateHandler.AGE_LERP_SPEED = 0.1`（每 tick 都在成长）⇒ 观感上"几乎没变"。

**修订后的判定（两条规则互补，均只对龙玩家、仅客户端）**：

| 规则 | 内容 | 负责的情形 |
|---|---|---|
| **1 噪声地板** | `|Δ血量| < 1.0` ⇒ 不显示（**双向**） | 每 tick 成长增量 < 1 点，以及"再对齐"的微小残差 |
| **2 等比判定** | 在"最大生命发生变化"的 tick，若血量百分比不变（容差 `1e-4`）⇒ 不显示（**对称**） | 成长步 ≥ 1 点；被偷成长导致的等比缩小 |

两条都在**同一个 HEAD 注入**里判定，命中任一即抑制；`@Redirect` 仍只把 `getLastHealthDelta()` 的返回值改成 `0`（**不写第三方任何状态**）。
**唯一旋钮**：`BELOONG_MIN_VISIBLE_DELTA = 1.0F` —— 取 1.0 的依据是 Health Bars 的显示粒度本身就是 1 点（`Mth.ceil`），不是拍脑袋的数字。

**有意为之的代价（委托方知情接受）**：龙玩家身上**不足 1 点**的真实变化（例如某些模组每 tick 回/掉 0.5 血）也会被隐藏 —— 它们原本显示成 ±1，本身也是四舍五入后的错报。**其它实体不受影响**（委托方裁定：只对龙玩家生效）。

## 八、方案再修订：规则 0「玩家自身一律不显示」（2026-10-11，委托方裁定 V1）

**新增实测证据（否掉上一节假设）**：关闭原版自然回血（`/gamerule naturalRegeneration false`）后，成长期间的绿色 `+1` **依旧存在** ⇒ 上一节"原版自然回血"的推断**被实测否掉**；绿字来源至今**未最终定位**（已排除：原版自然回血、AsteorBar 飘字、Health Bars 的 GUI 血条数字，以及"非本模组的渲染者"—— 因为关掉 `[level.damage_values]` 后绿字消失）。

**委托方裁定（V1）**：**本地玩家自身的血量跳字一律不显示**（回血与扣血都不显示）；其它生物与其它玩家保持不变。
理由：自己头顶与界面上本来就有血条（Health Bars 名牌 / AsteorBar / 原版心形），自身数字属冗余信息；更关键的是这样**不做任何来源分类**，免疫包序、跨 tick 与浮点噪声 —— 前两轮的失败都来自"试图分辨哪些变化算成长"。

**实现**（同一 HEAD 注入的最前面加一条）：

    if (entity instanceof LocalPlayer) { 清快照条目; 抑制本次飘字; return; }

`@Redirect getLastHealthDelta()` 返回 0 的方式不变 ⇒ 仍**不写 Health Bars 任何状态**。规则 1（噪声地板）/ 规则 2（等比判定）**保留**，继续作用于**其它**龙玩家。

**代价（知情接受）**：本地玩家失去自身受伤/回血的数字反馈（血条仍可见）；且这是"消音"而非"治病"—— 服务端那次回血照旧发生、饱食度照旧消耗。

**性能影响（顺带收益，已核到代码）**：被抑制的数字不会创建 `DamageValueParticle`（生存期 50 tick），而该粒子的 `render()` 每帧都会 `bufferSource.endBatch()`（**每个粒子每帧一次批次刷新**），其文字默认走 8 遍描边（`strongTextOutline = true`，委托方配置已确认）。按实机刷屏场景估算（成长期间约每 tick 一个数字、寿命 2.5 秒 ⇒ 稳态约 50 个粒子）⇒ 规则 0 大约省掉**每帧 ~50 次批次刷新 + ~400 次描边字形绘制**。属"小幅但真实"，主要体现于刷屏场景，不应期待整体 FPS 大跳。

## 九、按委托方要求极简化：只保留"本地玩家自身不显示"（2026-10-11）

**委托方裁定**：不要规则 1（噪声地板）与规则 2（等比判定），**只保留彻底禁用本地玩家自身血量跳字**这一条。

**改动**：`HealthTrackerHandlerMixin` 重写为最小实现 ——
- `HEAD`：`beloong$suppressThisTick = entity instanceof LocalPlayer;`（一行，不做任何分类）；
- `@Redirect HealthTracker#getLastHealthDelta()`：抑制时返回 0（行为不变，仍不写第三方状态）；
- **移除**：两个容差常量、每实体快照表、等比判定与噪声地板逻辑、`DragonStateProvider` 依赖。

**影响面变化（知情）**：**其它龙玩家**的成长飘字不再被过滤（规则 1/2 已删）—— 单人游玩无影响；若将来多人同服出现龙玩家成长飘字，可按需加回"只隐藏正向变化"这类规则（V2 变体）。

**性能**：与第八节结论一致（被抑制的数字不再创建 `DamageValueParticle`，而该粒子每帧 `endBatch()`、文字 8 遍描边）⇒ 刷屏场景每帧约省 ~50 次批次刷新与 ~400 次描边字形绘制；逻辑层收益可忽略。移除快照表后，本地玩家每 tick 的开销进一步降到"一次 `instanceof`"。
