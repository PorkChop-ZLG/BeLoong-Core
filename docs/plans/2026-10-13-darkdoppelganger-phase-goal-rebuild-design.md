# 黑暗二重身：阶段切换时同步重建 goalSelector 导致 `GoalSelector` 遍历 NPE —— 修复设计

**日期**：2026-10-13
**状态**：**已实施并实机验收**（2026-10-13；三轮实机，见 §10）
**方案**：Approach A —— 把 `hurt()` 里那次 goal 重建**挪到本次 tick 末尾**；**只在本仓库做兼容 mixin**
**委托方裁定**：先做 A（本仓库修）；修完可另出上游 issue/PR 文本（见 §9）
**参考源码分支**：`D:\Minecraft\开源模组参考文件\DarkDoppelganger` 的 **`1.21` 分支**（HEAD `7341a10` = 4.1.0）。
⚠️ **不要用 `master`（那是 1.20.1）**。

---

## 1. 问题陈述

**受影响者**：整合包玩家与整合包服务端。boss「黑暗二重身」在**阶段切换的那一刻**会让**服务端**抛
`Ticking entity` 崩溃 ⇒ 单人存档直接退到主菜单/崩游戏，专用服务器停摆。

**实机复现**（委托方，2026-10-13）：身穿原版**荆棘 III** 下界合金装备、站在 boss 近战范围内不主动攻击，
boss 按血量跨过 40% / 20% 阈值即崩。崩溃报告：
`run/crash-reports/crash-2026-10-13_16.54.16-server.txt`（世界名「二重身崩溃测试」）。

```
NullPointerException: Cannot invoke "net.minecraft.world.entity.ai.goal.WrappedGoal.isRunning()" because "wrappedgoal" is null
  at GoalSelector.tickRunningGoals(GoalSelector.java:121)
  at Mob.serverAiStep(Mob.java:785) → LivingEntity.aiStep → Mob.aiStep → LivingEntity.tick → Mob.tick
  at darkdoppelganger@4.1.0-1.21.1 DarkDoppelgangerEntity.tick(DarkDoppelgangerEntity.java:477)   ← 即 super.tick()
  → ServerLevel.tickNonPassenger → Level.guardEntityTick → … → IntegratedServer
```

**根因链（源码行号 = `1.21` 分支源文件）**：

1. `hurt(DamageSource, float)` `:609-651`：
   `:623` `float newHealth = this.getHealth() - amount;`
   `:625-633` 若 `!secondPhaseTriggered && newHealth <= getMaxHealth() * 0.4f` ⇒ `triggerSecondPhase()` `:546-574`
   （`setHealth(getMaxHealth())` + 改名 + 镜像分身 + 爆炸 + **10 道闪电** + 致盲）
   ⇒ **同一处紧接着** `if (hardMode) setThirdPhaseGoals(); else setSecondPhaseGoals();`（`:628` / `:630`）
   ⇒ `return false`。三阶段同理 `:635-642`（`setFinalPhaseGoals()` / `setThirdPhaseGoals()`）。
2. `set{First,Second,Third,Final}PhaseGoals()`（`:226` / `:253` / `:280` / `:307`）每次都做：
   `goalSelector.getAvailableGoals().forEach(WrappedGoal::stop)` + `goalSelector.removeAllGoals(x -> true)`
   + 若干 `goalSelector.addGoal(...)`。
3. 这些 `addGoal/removeAllGoals` 会**结构性修改** `GoalSelector.availableGoals`
   （fastutil `ObjectLinkedOpenHashSet`，**非 fail-fast**）⇒ 若此刻该集合**正在被 `tickRunningGoals` 的
   for-each 遍历**，遍历状态与哈希表结构错位 ⇒ 读到 null 槽 ⇒ 上面那条 NPE。
4. **荆棘为什么能触发**：boss 的近战 goal 本身就在 `tickRunningGoals` 的 for-each 里执行 ⇒ 它攻击玩家
   ⇒ 荆棘 III 反伤打回 boss ⇒ 这个 `hurt` 落在**同一次遍历内部** ⇒ 命中第 3 步。
   （自伤 TNT / 自己召唤的闪电同理，故只堵荆棘不是根因修复。）

**危险调用点唯一**：`set*Goals` 的全部调用点只有三类 ——
`registerGoals()` `:220-221`（构造期，安全）、`onAddedToLevel()` → `restorePhaseGoals()` `:366` / `:762-771`
（入世界期，安全）、**`hurt()` `:628/630/638/640`（唯一会落在 goal 迭代内的）**。

**版本范围与作者状态**：
- 整合包在跑 **3.4.0**，dev/最新为 **4.1.0**（CF 文件 `9121808`）。
- `javap -p` 复核两版：`protected void set{First,Second,Third,Final}PhaseGoals()`、`public void tick()`、
  `public boolean hurt(DamageSource, float)` **签名一致**；`set{Second,Third,Final}PhaseGoals` 在**两版都只被
  `hurt` 调用**（4.1.0 另多一个 `restorePhaseGoals`，3.4.0 没有该方法）。
- 上游 `1.21` 分支自 `7341a10`（2026-10-10）起**无新提交** ⇒ **作者未修**，故本项目自修。

---

## 2. 设计

### 2.1 架构

崩溃的充分必要条件是「**在 goal 正在被遍历时改 goal**」。因此**不动第三方 jar、不动整合包、不动原版**，
只做一件事：把 `hurt()` 里的那次重建**推迟到本次 tick 的 AI 步跑完之后**。
⇒ 阶段切换的**可见部分**（满血、改名、怒吼、爆炸、10 道闪电、致盲、系统消息）**照旧在受伤当刻发生**，
**只有 goal 列表**从"当刻"变为"本 tick 末尾"。

### 2.2 组件

新增 1 个文件 + 1 行注册：

| 文件 | 内容 |
|---|---|
| `src/main/java/com/zonlong/beloong/mixin/darkdoppelganger/DarkDoppelgangerPhaseGoalRebuildMixin.java` | 全部逻辑（见 2.2 代码骨架） |
| `src/main/resources/beloong.mixins.json` | `mixins` 数组（**主数组，非 `client`**）加 `"darkdoppelganger.DarkDoppelgangerPhaseGoalRebuildMixin"` |

```java
@Pseudo
@Mixin(value = DarkDoppelgangerEntity.class, remap = false)
public abstract class DarkDoppelgangerPhaseGoalRebuildMixin {

    @Unique private int beloong$pendingPhaseGoals;   // 0=无, 2/3/4 = 待应用的阶段 goal

    @Shadow protected abstract void setSecondPhaseGoals();
    @Shadow protected abstract void setThirdPhaseGoals();
    @Shadow protected abstract void setFinalPhaseGoals();

    // ①②③ 三个 @Redirect，全部 method = "hurt(...)Z"、require = 0、remap = false：
    //    target 依次指向 setSecondPhaseGoals()V / setThirdPhaseGoals()V / setFinalPhaseGoals()V
    //    ⚠️ hurt 里有【两处】setThirdPhaseGoals（困难模式的二阶段 :628 / 普通模式的三阶段 :640）
    //       —— 一个 @Redirect 默认包住该方法内全部候选指令，故只需 3 个 handler；
    //    ⚠️ 刻意【不写】allow = 2：上限写死会让"上游多一处调用"从无害多包一层变成启动期硬崩。
    //    handler 只记标记、不重建。

    // ④ 应用点：
    @Inject(method = "tick()V", at = @At("RETURN"), require = 0, remap = false)
    private void beloong$applyDeferredPhaseGoals(CallbackInfo ci) { /* 按标记调 @Shadow 方法 → 清零 */ }
}
```

**为什么用 `@At("RETURN")` 而不是 `TAIL`**：`tick()` 第一句就是 `super.tick()`（AI 步在其中跑完），
但方法里还有两处 early `return`（`:479` 死亡、`:482` 分身）⇒ `RETURN` 挂在**每一个** return 之前，
任何路径都会应用；而每条路径都已越过 `super.tick()` ⇒ 应用点**必然在 goal 迭代之外**。

**锚点日志**（英文 ASCII，遵 `AGENTS.md` §十一；每次真正延后都打，用于可察觉与计数）：

```
[BeLoong] darkdoppelganger: deferred phase-N goal rebuild to end of tick
```

它同时是「注入仍然生效」的证据：未安装该模组、或上游改了调用形式 ⇒ 不会有这条日志。

### 2.3 数据流

```
（在 boss 自己的 goal 迭代内）
玩家受击 → 荆棘 III 反伤 → DarkDoppelgangerEntity.hurt(...)
   → 跨阈值 → trigger*Phase()（可见效果：立刻执行，不变）
   → 调用 set{Second,Third,Final}PhaseGoals()  ← 被本 mixin 的 @Redirect 截获
        ⇒ 只写 beloong$pendingPhaseGoals = 2/3/4（**不重建**）
   → hurt 返回
（同上一次 tick，goal 迭代结束、super.tick() 返回）
DarkDoppelgangerEntity.tick() 的 RETURN 注入
   → 若标记非 0：调对应 @Shadow set*PhaseGoals() → 清零
```

### 2.4 错误处理（失败模式与对策）

| 失败模式 | 表现 | 对策 |
|---|---|---|
| 上游改名 / 把调用点移到父类 | **修复静默失效、崩溃复发** | `require = 0` 的固有代价 ⇒ 靠锚点日志消失察觉；已列入 §7「升级必查项」 |
| 注入点漂移致启动期硬崩 | 不会发生：`require = 0` **只放宽匹配** | — |
| 描述符写错（如漏 `CallbackInfo`） | `InvalidInjectionException` ⇒ 模组加载失败 | 遵 `AGENTS.md` §四；改完**必须真跑 `runClient`**（静态门抓不到） |
| 可见效果被误延后 | 观感变化 | 只有 goal 列表延后；满血/爆炸/闪电/改名/消息不动 |
| 与模组 `restorePhaseGoals` 抢写 goal | 互相覆盖 | 应用点在 `tick` 末尾，`restorePhaseGoals` 只在 `onAddedToLevel` ⇒ 时序不重叠 |

---

## 3. 边界情况（已核对）

1. **同一 tick 内连跨两档**：标记被后写覆盖（2→3）⇒ 只应用**更高阶段** goal，正确。单次 `hurt` 不可能跨两档（阈值分支均 `return`）。
2. **`hurt` 由非 boss 自己的 goal 触发**：延后到 boss 下一次 `tick` 的 RETURN ⇒ 最多晚 1 tick，且必不在迭代内。
3. **本 tick 内死亡/被移除**：标记不再应用，无影响。相位持久化差异已查：`NBT_SECOND/THIRD_PHASE` 是 **4.1.0 新增**字段 ⇒ **3.4.0 不持久化相位**（重载后本就回到一阶段 goal，与"未应用"一致）；4.1.0 由模组自己的 `restorePhaseGoals()` 恢复 ⇒ 两版都不会留下不一致状态。
4. **`isClone` 路径**：clone 的 `hurt` 提前 `return`（`:613-615`）⇒ 三处调用不会被命中。
5. **两个 boss 同时在世**：标记是 `@Unique` **实例**字段 ⇒ 互不干扰。
6. **专用服务器**：AI 在服务端 tick，mixin 在主数组 ⇒ 服务端同样受保护。
7. **客户端**：实体类双端都有，mixin 会加载，但客户端不跑 AI ⇒ 注入不触发。
8. **`@Shadow` 可见性**：两版都是 `protected` ⇒ `@Shadow protected abstract` 成立（已 javap 复核）。

---

## 4. 验收清单（编号 + 场景 + 期望值 + 判据）

1. **静态门**：`compileJava --rerun-tasks --no-build-cache` ⇒ 退出码 **0**、警告 **3**（既有基线，不新增）。
2. **启动门**：`runClient` ⇒ `run\logs\debug.log` 出现 `Mixing …mixin.darkdoppelganger.DarkDoppelgangerPhaseGoalRebuildMixin … into net.bandit.darkdoppelganger.entity.DarkDoppelgangerEntity`；`run\crash-reports\*-fml.txt` 无 `Failure message:`。
   ⚠️ Mixin 不会把每个注入器都写进日志 ⇒ **不要**以"缺某一行"判定失败。
3. **功能复现**（沿用委托方已验证的配方）：荆棘 III 全套 + 站近战范围不主动攻击，让 boss 依次跨 40% / 20%：
   - ① `run\crash-reports` **无**新的 `Ticking entity` 服务端崩溃；
   - ② 每次延后出现锚点 `[BeLoong] darkdoppelganger: deferred phase-N goal rebuild to end of tick`；
   - ③ 满血 / 爆炸 / 10 道闪电 / 改名 / 消息**仍立刻出现**；
   - ④ 换阶段后 boss 确实换招（二阶段火球 / 三阶段霜射线）。
4. **对照（可选）**：临时从 `beloong.mixins.json` 摘掉该条目再复现 ⇒ 应重现同一崩溃（pre-fix 报告见 §1 可作对照）。
5. **回归**：普通击杀（不掉阶段）与 clone 路径无异常日志。

---

## 5. 决策记录

| # | 决策 | 理由 |
|---|---|---|
| D1 | 修复落点 = **本仓库兼容 mixin**（Approach A），不动第三方 jar、不改整合包 | 委托方裁定"先做 A"；跨版本可用；作者未修 |
| D2 | 锚点选**方法名字符串**，不锚 `hurt` 内部位置 | `hurt` 的字节码在 3.4.0 / 4.1.0 **不同**（阈值判定位置与分支都不一样） |
| D3 | 应用点用 `@At("RETURN")` 而非 `TAIL` | `tick()` 有两处 early return；`RETURN` 覆盖全部路径 |
| D4 | 不写 `allow = 2` | 上限写死 ⇒ 上游多一处调用会从"无害多包一层"变**启动期硬崩** |
| D5 | 不新增配置项 | 委托方偏好"默认生效"（见 `memory/user-preferences.md`） |

**被否方案**：
- **B 无条件延迟三个 `set*Goals`**：注入点更少，但连 `restorePhaseGoals`（入世界/读档恢复）也延后 1 tick，属不必要的语义改动。
- **C 在 vanilla `GoalSelector.tickRunningGoals` 让遍历走快照**：能一次修掉所有同类 mod BUG，但动原版热路径、依赖字段读取 ordinal（`defaultRequire = 1` 下漂移 = 启动期硬崩）、影响面是全游戏 ⇒ 为一个 mod 的缺陷改原版公共面，性价比不成立。
- **D 只堵荆棘反伤**（免疫窗口 / 取消反射）：自伤 TNT 与自己召唤的闪电同样触发同一崩溃 ⇒ 非根因修复。

---

## 6. 非目标

- 不给上游 jar 打补丁，不修改整合包实例内文件。
- 不修该模组的其它缺陷（例如 `PatchedWarlockAttackGoal`、阶段切换 `return false` 导致该次伤害被吞等既有行为）。
- 不改动 `registerGoals()`（构造期）与 `restorePhaseGoals()`（入世界期）两条安全路径。

---

## 7. 升级必查项（上游升级后复核）

1. `javap -p -c -classpath <jar> net.bandit.darkdoppelganger.entity.DarkDoppelgangerEntity`
   ⇒ 确认 `hurt` 仍调用 `set{Second,Third,Final}PhaseGoals`，且它们仍是 `protected void ()V`；
2. `runClient` 后查 `run\logs\debug.log` 是否仍有 `Mixing …DarkDoppelgangerPhaseGoalRebuildMixin …`；
3. 实机跨阶段时查是否仍有 `[BeLoong] darkdoppelganger: deferred phase-N goal rebuild …` 锚点。

三者任一缺失 ⇒ 本修复**静默失效**（`require = 0`），需按新字节码重新锚定。

---

## 8. 未能验证

- **3.4.0 的实机复现**：本机 dev 只有 4.1.0；3.4.0 侧结论全部来自 `javap` 对照（调用点与方法签名一致），未在 3.4.0 上跑过。
- **专用服务器实机表现**：推断同受保护（mixin 在主数组、AI 在服务端 tick），未实测。
- **`hurt` 在客户端是否会被调用**：即便会，也只是延后，无害；未实测。

---

## 9. 下一步

1. 由 `planning` 技能产出配套实施计划（`docs/plans/2026-10-13-darkdoppelganger-phase-goal-rebuild-plan.md`）。
2. 实施后回填本文件状态行与计划文档的执行状态（含"计划之外真实发生的事"）。
3. 修复完成并实机验收后，另出**给上游的 issue/PR 文本**（委托方已确认此顺序，但**不属于本次实施范围**）。

---

## 10. 实施与验收结果（2026-10-13 追加；本节取代 §9 的执行部分）

**实施**：新增 `src/main/java/com/zonlong/beloong/mixin/darkdoppelganger/DarkDoppelgangerPhaseGoalRebuildMixin.java`，
并在 `beloong.mixins.json` 的 `mixins` 数组加一行；**未改第三方、未改整合包**。逐任务执行与证据见
`docs/plans/2026-10-13-darkdoppelganger-phase-goal-rebuild-plan.md` 的执行状态节。

**静态与产物门**
- 两版 jar 静态预检：`hurt` 内 **4 处 INVOKE**（`setSecondPhaseGoals`×1 / `setThirdPhaseGoals`×2 / `setFinalPhaseGoals`×1）、
  常量池 Methodref 的 owner 均为 `net/bandit/darkdoppelganger/entity/DarkDoppelgangerEntity`、三个方法均
  `protected void ()V`；`tick()` 的 return 指令数 3（4.1.0）/ 2（3.4.0）⇒ 证实 §2.2 的"必须用 `RETURN` 而非 `TAIL`"。
- `compileJava --rerun-tasks --no-build-cache --offline --no-configuration-cache` → exit **0**，警告 **3**（既有基线，无新增）。
- `build`（同 flag）→ exit **0**；包内 `beloong.mixins.json` 47/6 且含新条目、新 mixin 类在包内、`darkdoppelganger type=optional` 仍在。

**启动门**：`runClient` → `debug.log` 出现
`Mixing darkdoppelganger.DarkDoppelgangerPhaseGoalRebuildMixin from beloong.mixins.json … into net.bandit.darkdoppelganger.entity.DarkDoppelgangerEntity`；
本次运行无新的 `*-fml.txt`。

**实机验收（委托方执行，三轮，共 14 次延后、0 崩溃）**

| 轮次 | 配置 | 锚点 | 崩溃 |
|---|---|---|---|
| 1（17:39–17:43） | 普通模式 `hard_mode=false` | phase-2 ×2、phase-3 ×1 | **0** |
| 2（17:54–17:58） | 同上（裁剪法术列表后） | phase-2 ×2、phase-3 ×1 | **0** |
| 3（18:10–18:12） | **困难模式** + 皮甲 + 木剑 | **phase-3 ×5、phase-4 ×3**（phase-2 = 0） | **0** |

- 三轮 `run\crash-reports` 均**无**新增 `*-server.txt`（最新仍是 pre-fix 的 `crash-2026-10-13_16.54.16-server.txt`）。
- 第 3 轮的锚点形态（只有 phase-3 / phase-4）**本身就是"跑在困难模式"的证据**，并额外覆盖了第 2、3 个 `@Redirect`
  （困难模式 40% 走 `setThirdPhaseGoals`、20% 走 `setFinalPhaseGoals`）；第 1 个 `@Redirect`（`setSecondPhaseGoals`，
  普通模式路径）由前两轮覆盖。
- 日志里唯一提到该模组的 ERROR 行是 FTB Chunks 的 `Missing entity icon settings for darkdoppelganger:…` ——
  与本修复无关（三轮都在，属既有噪声）。

**测试用配置（已还原）**：为便于复现曾临时改过 `run\config\darkdoppelganger-common.toml`（裁剪法术列表、默认装备改皮甲、
武器改木剑、`hard_mode=true`）；收尾时已从备份 `darkdoppelganger-common.toml.bak-20261013`（SHA256 `D6D13F62…`）**还原**。

**仍未验证（保持 §8 口径）**：3.4.0 的实机复现、专用服务器实机表现 —— 两者结论仍只来自静态/字节码对照与推断。
