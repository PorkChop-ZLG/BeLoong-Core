# 暗夜巫妖掉落物悬浮 + 原版高亮 实施计划

**Date:** 2026-11-05
**Design:** [`2026-11-05-lich-loot-float-design.md`](2026-11-05-lich-loot-float-design.md)
**Status:** 待实施

---

## 一、任务

| # | 任务 | 产出 | 验收 |
|---|---|---|---|
| T1 | 新建 `compat/bomd/LichLootFloatHandler.java` | 事件 handler：判定 + 三件改造 + 圆环排布 + 异常兜底 + 一条 INFO | 编译通过 |
| T2 | 在 `BeLoongCore` 注册 | `import` + `NeoForge.EVENT_BUS.register(new LichLootFloatHandler());` + 中文行尾注释 | 编译通过 |
| T3 | 构建 | `build --rerun-tasks --no-build-cache` | 成功 + **0 警告**（基线 0） |
| T4 | 静态复核 | 读回源码核对参数：半径 2.0 / Y+0.2 / `(0,-0.01,0)` / `setGlowingTag(true)` / 不改 `lifespan` | 逐项一致 |
| T5 | 实机验收（**用户**） | 击杀暗夜巫妖 | 见 design §7 六条 |

---

## 二、关键实现约束（照 design 抄，不走样）

1. **判定用 id 字符串**，`import` 里**不得出现** `com.cerbon.bosses_of_mass_destruction.*`（BOMD 是可选依赖）。
2. **不 cancel 事件**、**不改 `lifespan`**、**不改战利品表**。
3. handler 体整体 `try/catch(Throwable)`；异常只记 ERROR —— 事件之后紧接着 `drops.forEach(addFreshEntity)`，
   异常穿透会**整批掉落丢失**。
4. `n <= 1` 时半径取 0（落圆心），避免单件被无故偏出去。
5. 日志英文 ASCII，`[BeLoong] lich loot: floated {} drop(s) at (x, y, z)`，坐标用**方块坐标**（整数，稳定可 grep）。

---

## 三、回归风险

| 风险 | 判据 |
|---|---|
| 误伤其它生物掉落 | 除 Lich 外**任何**生物死亡都必须在判定第一行返回；验收标准 5 覆盖 |
| 掉落物互相重叠 | 8 件在半径 2.0 圆周上均匀分布；验收标准 3 覆盖 |
| 事件链被打断 | 任何情况下不得抛出；`try/catch` 覆盖 |
| 上游 id 变更 | 静默失效（掉落恢复普通行为），不会崩；验收标准 1 是唯一探针 |

---

## 四、执行状态

| # | 任务 | 结果 |
|---|---|---|
| T1 | 新建 `compat/bomd/LichLootFloatHandler.java` | ✅ 已建（`final class` + `@SubscribeEvent`；整体 `try/catch(Throwable)`；日志 `[BeLoong] lich loot: floated {} drop(s) at (x, y, z)` 用方块坐标） |
| T2 | `BeLoongCore` 注册 | ✅ `import` 加在 `compat.betterendisland` 之后（字母序），注册行在 `DeadKingAdvancementHandler` 之后并带中文行尾注释 |
| T3 | 构建 | ✅ `compileJava` / `build --rerun-tasks --no-build-cache` 均 BUILD SUCCESSFUL，**0 警告**（基线 0） |
| T4 | 静态复核 | ✅ 半径 `2.0`、Y `+0.2`、残余下沉 `-0.01`、`setGlowingTag(true)`、`n<=1` 时半径 0、未触碰 `lifespan`、判定为 id 字符串 |
| T5 | 实机验收 | ✅ 两轮确认：初版“效果不错” → rev 1（半径 2.0）后“效果很好”，功能通过 |

**计划之外真实发生的事**

1. **`LivingDropsEvent` 的捕获链要读源码确认**：`LivingEntity.java:1462-1476` 显示掉落先进捕获列表、
   事件之后才 `addFreshEntity` —— 这决定了"改位置/关重力"是安全的（实体还没进世界）。已写进 handler javadoc。
2. **`Entity#spawnAtLocation` 没有随机水平偏移**（`Entity.java:1972-1983`）：8 件掉落物在原版就是同一坐标，
   散开只靠构造器的随机初速度 ⇒ "圆环排布"是**必需项**而非美化。设计文档已按此改写。
3. **BOMD 的 mod id 与实体 id 已双向核对**：`BMDConstants.MOD_ID = "bosses_of_mass_destruction"`、
   `BMDEntities` 用该 namespace 注册 `"lich"`，且 `LichEntity` 未覆写 `getLootTable()` ⇒ 默认表即
   `data/bosses_of_mass_destruction/loot_table/entities/lich.json`。判定字符串由此确认。
4. **启动冒烟**：`runClient` 到主菜单，`run/logs/latest.log` 无 `Failed to register` / `Exception` 记录
   ⇒ 事件 handler 注册与 `@SubscribeEvent` 签名没有问题（但真实行为仍要靠击杀巫妖验收）。
