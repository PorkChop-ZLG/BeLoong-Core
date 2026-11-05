# 暗夜巫妖掉落物悬浮 + 原版高亮 设计文档

**Date:** 2026-11-05
**Status:** **已实施并实机验收**（2026-11-05；含 rev 1 的圆环半径 2.0，用户两轮确认：初版“效果不错” → 放大后“效果很好”）
**Approach:** **A —— NeoForge `LivingDropsEvent` 事件层**（掉落入世界之前改造 ItemEntity）
**范围:** 只处理 `bosses_of_mass_destruction:lich`（暗夜巫妖）的死亡掉落

---

## 1. Problem Statement

暗夜巫妖是会飞的 Boss，但它的掉落物走**普通战利品表 + 普通 ItemEntity**，于是死在空中时掉落物会：
① 向上抛一下再自由落体，落到结构外 / 远处；② 在复杂地形里很难被看见。玩家经常找不到。

**取证**

| 事实 | 位置 |
|---|---|
| 掉落表：`ancient_anima`×1、`bone` 3–6、`diamond` 5–7、附魔书 `rolls: 5` ⇒ **一次死亡共 8 件 ItemStack** | `Bosses-of-Mass-Destruction-FORGE/Common/src/main/resources/data/bosses_of_mass_destruction/loot_table/entities/lich.json` |
| `LichEntity.die()` 覆写后调 `super.die()`，所以走原版掉落管线 | `LichEntity.java:183`、`:196` |
| 掉落管线：`captureDrops` → `dropFromLootTable` → **`LivingDropsEvent`** → 未取消才 `addFreshEntity` | `LivingEntity.java:1462-1476`（NeoForge 21.1.236 源码树） |
| `spawnAtLocation` 生成 ItemEntity 时**没有随机水平偏移**，8 件全在同一个坐标；散开只靠 ItemEntity 构造器的随机初速度 | `Entity.java:1972-1983` |

**后果**：只要我们把速度归零 + 关重力，8 件会叠成一件 —— 必须**主动排布**。

## 2. 为什么不能照搬铁魔法

铁魔法（`irons-spells-n-spellbooks`）的做法是**物品自己**声明特殊实体：

- `DragonskinItem.java:17-32`：覆写 `hasCustomEntity` → true，在 `createEntity` 里判断"末影龙 5 格内"，然后
  `setNoGravity(true)` + `setDeltaMovement(0,-0.01,0)` + `setGlowingTag(true)`，另加 100 个末地粒子。
- `ClientEntityMixin.java:22-36`：客户端把该物品的 ItemEntity 描边染成紫色（`getTeamColor`）。

它的前提是"这个物品属于铁魔法"。暗夜巫妖的掉落是 **`minecraft:bone` / `diamond` / `book`** 这类原版物品，
把 `hasCustomEntity` 全局改掉会污染所有来源的同类掉落；要靠自定义数据组件隔离，还得改战利品表 —— 代价远大于收益。

**结论**：借它的「三件套效果」（`noGravity` + 近零速度 + `setGlowingTag`），但挂点换成**掉落入世界之前的事件**。

## 3. 方案对比（Phase 3 结论）

| 方案 | 做法 | 结论 |
|---|---|---|
| **A（采纳）** | 监听 `LivingDropsEvent`，改每个 `ItemEntity` | 零 mixin、零 BOMD 类引用、零数据改动 |
| B | 照搬铁魔法：mixin `Item#hasCustomEntity` + 打标记组件 + 改战利品表 | 需污染原版物品行为，否决 |
| C | mixin `LichEntity#die` 后扫描新生成的 ItemEntity | 需 `@Pseudo`/`require=0`/toml，且"刚生成"只能靠启发式，开始期崩溃风险换一个不存在的需求，否决 |

## 4. Design

### 4.1 组件

| 项 | 内容 |
|---|---|
| 新文件 | `src/main/java/com/zonlong/beloong/compat/bomd/LichLootFloatHandler.java`（服务端事件 handler） |
| 注册 | `BeLoongCore` 构造函数里 `NeoForge.EVENT_BUS.register(new LichLootFloatHandler());`，与既有 handler 同风格（无条件注册） |
| 判定 | `BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType())` 等于 `ResourceLocation.fromNamespaceAndPath("bosses_of_mass_destruction", "lich")`。**不 import 任何 BOMD 类** ⇒ BOMD 缺席时该分支永不命中，无需 `@Pseudo` / `require = 0` / `mods.toml` 声明 |
| 改造对象 | `event.getDrops()`（`Collection<ItemEntity>`，此时**尚未** `addFreshEntity`） |

### 4.2 每件物品的三项改造

```java
item.setNoGravity(true);
item.setDeltaMovement(0.0, -0.01, 0.0);   // 与铁魔法同口径：几乎静止，保留一丝下沉手感
item.setGlowingTag(true);                  // 共享标志位 6 → 客户端渲染原版白色描边
```

`setGlowingTag` 是 `Entity#setGlowingTag`（`Entity.java:2402`，public final），同步到客户端后
`LevelRenderer` 会给该实体画原版 outline；颜色走 `Entity#getTeamColor` 默认值（白色），
符合"**原版的高亮显示**"。

### 4.3 排布：以死亡点为圆心的圆环

8 件全在同一坐标，必须主动散开（用户裁定 **A：圆环**）：

```
n     = drops.size()
r     = (n <= 1) ? 0.0 : 2.0           // 单件时直接落在圆心，避免无意义的偏移（半径 2.0，见 rev 1）
theta = 2π * i / n
x     = deathX + r * cos(theta)
z     = deathZ + r * sin(theta)
y     = deathY + 0.2                    // 略高于脚底，避免与方块面共面闪烁
item.setPos(x, y, z)
```

- `deathX/Y/Z` 取自 `event.getEntity()` 的 `position()`（即掉落生成点，`Entity#spawnAtLocation` 用的就是它）。
- 半径 **2.0 格**（初版 0.45，实机后按 rev 1 放大）：默认 8 件时圆周 12.6 格、相邻间距约 1.57 格。
- 用 `setPos`（`Entity.java:403`，public）而不是 `moveTo`，避免顺带重置朝向。

### 4.4 不改的东西

- **不动 `lifespan`**：保持原版 6000 tick（5 分钟）自然消失（用户裁定 A）。
- 不动拾取延迟（`spawnAtLocation` 已设 `setDefaultPickUpDelay()`）。
- 不 cancel 事件、不重建掉落、不改战利品表 ⇒ 掉落数量、附魔、`looting` 全部维持原版。

### 4.5 异常安全

整个 handler 体包 `try/catch(Throwable)`：失败只记一条 ERROR，**绝不让异常穿透** —— 事件之后的
`drops.forEach(level::addFreshEntity)` 在同一方法里，异常会打断整批掉落进世界。

### 4.6 日志

一条 INFO（英文 ASCII，SLF4J `{}` 占位，遵守 §十一）：

```
[BeLoong] lich loot: floated {} drop(s) at ({}, {}, {})
```

## 5. Decisions

| # | 决定 | 理由 / 被否的替代 |
|---|---|---|
| D1 | 用 `LivingDropsEvent`，不写 mixin | 它是"掉落入世界之前"的官方钩子；无需类引用与三段声明 |
| D2 | 判定用注册 id 字符串，不用 `instanceof LichEntity` | BOMD 是 `localRuntime` 的可选依赖，直接引用会在 BOMD 缺席时碰类解析 |
| D3 | 悬停位置 = 死亡点（圆环半径 **2.0**、Y+0.2） | 用户裁定；先前否掉"下探到地面"（与死亡点脱钩、边界复杂）与"缓慢下落"（本质仍会掉丢） |
| D4 | 存续时间保持原版 5 分钟 | 用户裁定；5 分钟内"知道死在哪 + 有描边"已足够 |
| D5 | 只做白色描边，不做自定义颜色 | 用户要的是"原版的高亮"；自定义颜色需客户端 mixin + 与其它模组抢 `getTeamColor` 的风险 |
| D6 | 不加配置项、不加粒子/音效 | §十「默认不加配置项」；粒子对"找得到"没有实质帮助 |

## 6. Non-Goals

- 不处理 BOMD 的其它 Boss（祸乱鬼魅的另外几个），也不做通用"飞行生物掉落悬浮"；
- 不改战利品表、不改掉落数量/附魔；
- 不做"自动飞向玩家""掉落物进入背包"之类的便利功能；
- 不处理已存在的旧掉落物（只对新的死亡事件生效）。

## 7. 验收标准

| # | 场景 | 期望 |
|---|---|---|
| 1 | 击杀暗夜巫妖 | 掉落物**停在死亡高度**，不落地、不散落远处 |
| 2 | 同上 | 每件都有**白色原版描边**（隔着方块也能看到轮廓） |
| 3 | 同上 | 8 件在半径 2.0 的圆周上均匀排开，互不重叠；可逐件拾取 |
| 4 | 同上 | 5 分钟后自然消失（与原版一致） |
| 5 | 击杀其它生物（含 BOMD 其它 boss） | 掉落行为**完全不变** |
| 6 | 日志 | 出现一行 `lich loot: floated 8 drop(s) at (…)`，无 ERROR |

## 8. 风险 / 升级必查项

- **`LivingDropsEvent` 语义**：只在 `LivingEntity.dropAllDeathLoot` 里发。若 BOMD 将来把掉落改成
  `die()` 之外自行 `addFreshEntity`，本方案会静默失效（掉落恢复普通行为），**不会崩**。
- **BOMD 实体 id 变更**（`bosses_of_mass_destruction:lich`）会静默失效 —— 判据是字符串，编译期发现不了；
  验收标准 1 是唯一探针。
- 该 handler 无 mixin、无 `@Shadow`，**不引入启动期崩溃面**。

---

## 9. 修订 rev 1（2026-11-05，实机验收后）：圆环半径 0.45 → 2.0

**谁 / 何时**：用户，2026-11-05 首次实机验收通过后。
**为什么**：0.45 格在默认 8 件时视觉上过于拥挤；按 8 件为基准，半径 2 格更舒展。
**改了什么**：`LichLootFloatHandler.RING_RADIUS` 0.45 → 2.0（唯一改动；排布公式、Y 偏移、5 分钟存续、
判定与事件挂点全部不变）。
**被否的替代**：让半径随件数增长（`r = max(2.0, n * 0.5)`）—— 用户明确按"默认 8 件、半径 2 格"给值，
且件数增长时按圆周均分的间距本就会变化，无需再叠一层公式。
