# 黑暗二重身阶段重建修复 —— 实施计划

**目标**：用 BeLoong-Core 的一个兼容 mixin，消除「boss 阶段切换时在自己的 goal 迭代内重建 `goalSelector`
⇒ `GoalSelector` 遍历读到 null 槽 ⇒ 服务端 `Ticking entity` 崩溃」。

**架构**：`hurt()` 里的 `set{Second,Third,Final}PhaseGoals()` 调用被 3 个 `@Redirect` 截获，只写**实例**待办标记；
`tick()` 的 `@At("RETURN")`（已在 `super.tick()` 之后、必在 goal 迭代之外）按标记用 `@Shadow` 应用真正的重建。
阶段切换的可见效果（满血 / 改名 / 怒吼 / 爆炸 / 10 道闪电 / 致盲 / 消息）**不变**。

**方案**：设计文档 §2 的 Approach A —— **只在本仓库修**（委托方裁定），不动第三方 jar、不改整合包。

**实施节奏（二选一，推荐 ①）**
1. **一次成型 + 静态预检**（Task 1 先证明注入点仍存在，再写代码）——推荐：注入点是方法名字符串，先证其存在，
   `runClient` 只需跑一次。
2. 分两步（先"只记标记 + 日志"验证注入生效，再打开重建）——更保守，但要多跑一次 `runClient`（数分钟）。

**依据**：`docs/plans/2026-10-13-darkdoppelganger-phase-goal-rebuild-design.md`（已获委托方批准）

**项目约束（`AGENTS.md`）**：本项目**没有测试套件** ⇒ 验证 = 静态门 + `runClient` + 委托方实机验收（§二）；
改过 mixin **必须真跑一次客户端**（§二）；智能体**不 commit**（§十一）。

---

### Task 1：静态预检 —— 证明三处注入点在两版里都还在

**Files**：无（只读）

**Steps**
1. `javap -p -c -classpath <4.1.0 jar> net.bandit.darkdoppelganger.entity.DarkDoppelgangerEntity`
   ⇒ 确认 `hurt` 内含对 `setSecondPhaseGoals()V`、`setThirdPhaseGoals()V`（**两处**）、`setFinalPhaseGoals()V` 的 INVOKE；
2. 同法核对整合包版本 `D:\Downloads\darkdoppelganger-3.4.0-1.21.1.jar`；
3. 确认三个方法签名都是 `protected void …()V`（`@Shadow` 需要），且 `tick()` 是 `public void tick()V`。

**Verification**：两版输出里三处 INVOKE 齐备、签名一致；把命中行留作证据（写进计划文档的执行状态）。

---

### Task 2：新建 mixin

**Files**：Create `src/main/java/com/zonlong/beloong/mixin/darkdoppelganger/DarkDoppelgangerPhaseGoalRebuildMixin.java`

**Steps**
1. `@Pseudo`；`@Mixin(value = DarkDoppelgangerEntity.class, remap = false)`；类声明为 `abstract`；
2. `@Unique private int beloong$pendingPhaseGoals;`（实例字段，**非 static**）；
   `@Shadow protected abstract void set{Second,Third,Final}PhaseGoals();`
3. 三个 `@Redirect`：`method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"`、
   `at = @At(value = "INVOKE", target = "Lnet/bandit/darkdoppelganger/entity/DarkDoppelgangerEntity;set…PhaseGoals()V")`、
   `require = 0`、`remap = false`；handler 签名 = `(DarkDoppelgangerEntity self)` 返回 `void`，**只写标记**；
   **刻意不写 `allow`**；
4. 一个 `@Inject(method = "tick()V", at = @At("RETURN"), require = 0, remap = false)`，
   handler 参数以 **`CallbackInfo`** 结尾（漏写 = `InvalidInjectionException` = 模组加载失败）；
   有标记则调对应 `@Shadow` 方法、随后清零；
5. 锚点日志（每次真正延后打一条 INFO，英文 ASCII）：
   `[BeLoong] darkdoppelganger: deferred phase-N goal rebuild to end of tick`；
6. 类注释写清"为什么推迟、为什么 `RETURN`、为什么不写 `allow`、上游改了会怎样（`require = 0` 静默失效）"。

**Verification**：`.\gradlew.bat compileJava --rerun-tasks --no-build-cache --offline --no-configuration-cache`
⇒ 退出码 **0**、警告 **3**（既有基线，不新增）。

---

### Task 3：注册到 mixins 配置

**Files**：Modify `src/main/resources/beloong.mixins.json`

**Steps**：在 **`mixins`** 数组加入 `"darkdoppelganger.DarkDoppelgangerPhaseGoalRebuildMixin"`（**不是 `client`**；
保持文件既有的排序/前缀习惯）。

**Verification**：`Get-Content src/main/resources/beloong.mixins.json -Raw | ConvertFrom-Json`
⇒ 条目出现在 `mixins`、`client` 数组与其它条目未变；随后 `build` 退出码 0。

---

### Task 4：产物复核（**解析**，不做字符串匹配）

**Files**：无

**Steps**：构建后打开 `build/libs/beloong-<版本>.jar`，解析其中的 `beloong.mixins.json` 与
`META-INF/neoforge.mods.toml`。

**Verification**：解析结果显示新条目在包内；jar 内含
`com/zonlong/beloong/mixin/darkdoppelganger/DarkDoppelgangerPhaseGoalRebuildMixin.class`；
`darkdoppelganger` 的 `type="optional"` 声明仍在。

---

### Task 5：启动门（智能体自己跑 `runClient`）

**Files**：无

**Steps**：`runClient` 启动到主菜单即可（不必进世界）。

**Verification**：`run\logs\debug.log` 出现
`Mixing com.zonlong.beloong.mixin.darkdoppelganger.DarkDoppelgangerPhaseGoalRebuildMixin … into net.bandit.darkdoppelganger.entity.DarkDoppelgangerEntity`；
`run\crash-reports\*-fml.txt` 无 `Failure message:`。
⚠️ Mixin **不会**把每个注入器都写进日志 ⇒ 不要以"缺某一行"判定失败。

---

### Task 6：实机验收（**由委托方执行**）

**Files**：无

**Steps**
1. 用已验证配方：荆棘 III 全套 + 站在 boss 近战范围内**不主动攻击**，让 boss 依次跨过 40% / 20%；
2. （可选对照）临时从 `beloong.mixins.json` 摘掉该条目再复现 ⇒ 应重现同一崩溃。

**Verification**（设计文档 §4 的 ①②③④）
1. `run\crash-reports` **无**新的 `Ticking entity` 服务端崩溃；
2. 每次延后出现锚点 `[BeLoong] darkdoppelganger: deferred phase-N goal rebuild to end of tick`；
3. 满血 / 爆炸 / 10 道闪电 / 改名 / 消息**仍立刻出现**；
4. 换阶段后 boss 确实换招（二阶段火球 / 三阶段霜射线）。

---

### Task 7：收尾回填与留痕

**Files**：Modify 设计文档状态行；Modify 本计划（执行状态 + **"计划之外真实发生的事"**）；
Modify `memory/decisions-log.md`

**Steps**：按 `AGENTS.md` §九 回填；**不 commit**（交委托方提交）。

**Verification**：两份文档状态一致；`git status` 只显示预期文件。

---

## 依赖顺序与风险

**顺序**：Task 1 → 2 → 3 → 4 → 5 → 6 → 7（Task 6 依赖 Task 5 通过）。

| 风险 | 征兆 | 处置 |
|---|---|---|
| 注入点已漂移（上游改名/移到父类） | Task 1 就对不上，或 Task 5 无 `Mixing …` 行 | 按新字节码重新锚定；`require = 0` 下不会硬崩，但**修复会静默失效** |
| 描述符写错 | Task 5 起不来，`*-fml.txt` 有 `Failure message:` / `InvalidInjectionException` | 查 handler 是否以 `CallbackInfo` 结尾、`@Redirect` 参数是否 `(owner)` |
| 只命中部分调用点 | Task 6 仍崩、且崩溃栈与 pre-fix 一致 | 回 Task 1，逐条核对三处 INVOKE 的 owner/描述符 |
| 可见效果被误延后 | 实机观感变化 | 检查是否误把 `trigger*Phase()` 也纳入延迟（本设计只延迟 `set*Goals`） |

**回退方式**：删除新 mixin 文件 + `beloong.mixins.json` 里的那一行即可（无其它耦合）。

---

## 非目标

- 不给上游 jar 打补丁、不修改整合包实例文件、不新增配置项。
- 不修该模组的其它缺陷；不改 `registerGoals()`（构造期）与 `restorePhaseGoals()`（入世界期）两条安全路径。
- 给上游的 issue/PR 文本**不属于本次实施**（委托方已确认在修复完成后再出）。

---

## 执行状态（2026-10-13 收尾回填）

| Task | 状态 | 证据 |
|---|---|---|
| 1 静态预检 | ✅ | 两版 `hurt` 内 4 处 INVOKE + 常量池 owner + `protected void ()V` + `tick` return 数 3/2 |
| 2 新建 mixin | ✅ | `compileJava` exit 0、警告 3（基线）、类已编译 |
| 3 注册 | ✅ | 源码与包内 json 均 47/6、新条目在 `mixins`、`client` 未动 |
| 4 产物复核 | ✅ | 包内 mixin 类存在、`darkdoppelganger type=optional` 仍在 |
| 5 启动门 | ✅ | `debug.log` 有 `Mixing …`；本次运行无新 `*-fml.txt` |
| 6 实机验收 | ✅ | 三轮共 **14 次延后、0 崩溃**（第三轮困难模式覆盖 phase-3/phase-4） |
| 7 收尾回填 | ✅ | 本节 + 设计文档 §10 + memory；测试配置已还原 |

### 计划之外真实发生的事

1. **验收跑了三轮**（计划只安排一轮）：第 2 轮在裁剪法术列表后复测；第 3 轮委托方改为**困难模式 + 皮甲 + 木剑**
   ⇒ 第 2、3 个 `@Redirect`（困难模式路径）额外拿到实机覆盖，**比原计划覆盖更完整**。
2. **Task 1 第一次验证失败是我命令的 bug**：把 `[regex]::Escape()` 与 `-SimpleMatch` 一起用，实际去搜"带反斜杠的字面量"
   ⇒ 0 命中；改用裸字面量后立即通过。**注入点本身从未有问题**（这一点已写进设计文档的取证记录，避免后人误读）。
3. **改配置文件时踩了项目已登记的坑**：`[System.IO.File]::WriteAllText('run\config\…')` 用的是
   `[Environment]::CurrentDirectory`（工作区根），PowerShell 的 `cd` 不影响它 ⇒ 报
   `Could not find a part of the path 'D:\Minecraft\run\…'`；改用 `Resolve-Path` 的绝对路径后成功
   （那次失败**没有破坏文件**，SHA256 与备份一致可证）。
4. **启动门那次 `runClient` 以 exit 1 结束**：不是启动失败，是取证后**主动强杀**客户端（Gradle 把子进程非零退出视为任务失败）。
5. 为测试临时改过 `run/config/darkdoppelganger-common.toml`（法术列表 / 默认装备 / `hard_mode`），**收尾已还原**。
