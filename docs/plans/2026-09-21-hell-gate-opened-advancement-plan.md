# 地狱之门「开启」进度触发器 实施计划

**状态：** 待批准（设计已批准，见 `docs/plans/2026-09-21-hell-gate-opened-advancement-design.md`）
**目标：** 新增判据 `beloong:hell_gate_opened`，在地狱之门**动画播完、40 格全部 OPEN（可进入）之后**，
向门 **32 格内的所有非旁观玩家**各发一次进度；模组侧用一条**临时进度**验证，验证后删除。
**架构：** 三层单向依赖 —— 触发点（`HellGateBlockEntity` 145 tick 分支，只 +1 处调用）→ 薄编排层
（`HellGateOpenedAdvancements`，口径集中）→ 判据（`HellGateOpenedTrigger`，`SimpleCriterionTrigger`，无自定义字段）。
**采用方案：** 设计阶段的方案 B（用户裁定），本计划不再重复方案对比。

**基线：** `disaster2` @ `71e87b5`（版本 `0.9.6`，领先 `origin/disaster2` 9 个提交，未推送）
**设计文档：** `docs/plans/2026-09-21-hell-gate-opened-advancement-design.md`

---

## 〇、验证范式（与技能模板的差异说明，沿用本仓库既有做法）

本项目**没有测试套件**（`memory/project-context.md`：*No test suite. Verification is `gradlew build`
+ static grep probes + live game runs.*）。因此本计划**不使用** TDD 的 RED-GREEN 步骤，
每个任务的「验证」由三件套构成：

1. `.\gradlew.bat build` 退出码 0；
2. **静态探针** —— 对 `build/libs/beloong-0.9.6.jar` 与 `build/classes/java/main` 取证
   （class 是否存在、`javap` 看继承与方法调用、jar 内资源清单）；
3. **实机探针** —— 数据包驱动，无需真人（见 T7）。

## 〇之二、为什么这次能全自动实机验证

触发条件可以**由命令造出来**：对基准格 `setblock … [lit=true]` 点火后，方块实体自己会走完 145 tick，
「门彻底开启」那一刻自然发生，不需要真人右键。读数用
`execute store result score … run advancement revoke @a only <id>`（结果值 = 持有该进度的玩家数，
一次调用同时完成「读」与「清场」）。

## 〇之三、探针卫生（本次特有的一条）

`run/saves/hellgate_probe` 里**还留着上一轮的门探针** `hellgate_probe`，它的 `view` 函数会在
3/6/10/15/20 秒时把玩家 `tp` 到门前并切创造 —— **会与本计划的场景设置打架**。
⇒ 开跑前把 `hellgate_probe` **移出世界**（归档到 `run/hellgate_probe_packs/`），只留本轮的 `hellgate_advprobe`。

---

## 一、任务分解

### T1 —— 判据本体

**交付物：** 新建 `src/main/java/com/zonlong/beloong/hellgate/HellGateOpenedTrigger.java`（约 66 行）

**步骤：**
1. 照 `compat/ironsspellbooks/DeadKingKillTrigger.java` 的范式写：
   `extends SimpleCriterionTrigger<HellGateOpenedTrigger.Instance>`、
   `public void trigger(ServerPlayer player)`、`codec()` 返回 `Instance.CODEC`、
   `record Instance(Optional<ContextAwarePredicate> player) implements SimpleCriterionTrigger.SimpleInstance`，
   `CODEC` 用 `EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player")`。
2. javadoc 必须写明三件事：**注册名 `beloong:hell_gate_opened` 一旦发布不可改**（改名让整合包进度失效）；
   为什么不需要自定义条件字段（标准 `player` 谓词已能按维度/坐标筛）；本模组只提供判据、真实进度由整合包侧编写。

**验证：** `.\gradlew.bat compileJava` 退出码 0；
`javap -p -classpath build\classes\java\main com.zonlong.beloong.hellgate.HellGateOpenedTrigger`
输出含 `extends net.minecraft.advancements.critereon.SimpleCriterionTrigger` 与
`public void trigger(net.minecraft.server.level.ServerPlayer)`。

### T2 —— 注册判据

**交付物：** 修改 `src/main/java/com/zonlong/beloong/registry/ModCriteria.java`（+1 注册项 + javadoc 一段）

**步骤：** 加 `HELL_GATE_OPENED = REGISTRY.register("hell_gate_opened", HellGateOpenedTrigger::new)`，
并在类 javadoc 的「当前注册」清单里补一条（含发放口径：32 格、只排除旁观）。

**验证：** `.\gradlew.bat build` 通过；
`javap -v -classpath build\classes\java\main com.zonlong.beloong.registry.ModCriteria` 的常量池含字符串
`hell_gate_opened`；jar 内 class 存在。

### T3 —— 薄编排层

**交付物：** 新建 `src/main/java/com/zonlong/beloong/hellgate/HellGateOpenedAdvancements.java`（约 70 行）

**步骤：**
1. `private static final double NEARBY_RANGE = 32.0;`（javadoc 注明出处：用户裁定的 32 格，
   ⚠️ 与死王仪式的 60 格不同，两个数都有出处、不是笔误）；
2. `public static void grant(ServerLevel level, BlockPos basePos)`：
   - `Vec3 center = Vec3.atCenterOf(basePos);`（球心取基准格中心，D22）
   - `double r2 = NEARBY_RANGE * NEARBY_RANGE;`（平方比较，不开方）
   - `for (ServerPlayer player : List.copyOf(level.players()))` —— **必须是快照**，
     javadoc 里写明理由（`trigger()` 会跑奖励函数，可能传送/踢人 ⇒ 直接遍历活列表会 CME，且异常会抛穿 tick）
   - `if (player.isSpectator()) continue;`（只排除旁观；创造计入，注明为有意偏离）
   - `if (player.distanceToSqr(center) >= r2) continue;`（左闭右开）
   - `ModCriteria.HELL_GATE_OPENED.get().trigger(player);`

**验证：** 编译通过；
`javap -c -classpath build\classes\java\main com.zonlong.beloong.hellgate.HellGateOpenedAdvancements`
输出含 `List.copyOf`、`isSpectator`、`distanceToSqr`、`ModCriteria.HELL_GATE_OPENED` 四处引用。

### T4 —— 方块实体挂钩（对移植件的唯一改动）

**交付物：** 修改 `src/main/java/com/zonlong/beloong/block/HellGateBlockEntity.java`

**步骤：**
1. 在 `if (entity.animationTicks >= TICK_FULLY_OPEN && !level.isClientSide) { … 40 格循环 … }` 的
   **循环之后、分支之内**插入：
   ```java
   // 门此刻已全部 OPEN（可进入）⇒ 才发进度（本模组挂钩，非灾变原有逻辑）
   if (level instanceof ServerLevel serverLevel) {
       HellGateOpenedAdvancements.grant(serverLevel, pos);
   }
   ```
   ⚠️ 用 `instanceof` 而不是 `(ServerLevel) level` 强转：**灾变原来那行条件一字不改**
   （`!level.isClientSide` 保持原样），这样「移植件与原版的差异」仍然只有可数的一行调用。
2. javadoc 的「移植说明」里登记**第 4 处刻意偏离**：本模组在开门完成时挂了一个进度发放。

**验证：** `git diff --stat` 显示该文件仅 +1 处调用（+ javadoc）；
`javap -c` 在 `tick` 方法体内可见 `HellGateOpenedAdvancements.grant` 与 `instanceof ServerLevel`；
`git diff` 中**原条件行未被改动**（人工核对 diff）。

### T5 —— 临时测试进度（用户要求 4）

**交付物：** 新建 `src/main/resources/data/beloong/advancement/hell_gate_test.json`

**内容要点：**
- **无 `parent`** ⇒ 自成一根标签页，便于在进度树里一眼找到（测试用）；
- `background` 用原版存在的 `minecraft:textures/gui/advancements/backgrounds/nether.png`（已实测该文件存在）；
- `icon` 用 `{"count": 1, "id": "beloong:hell_gate"}`（1.21 起是 `id` 不是 `item`）；
- `frame: goal`、`show_toast: true`、`announce_to_chat: true`、`hidden: false`；
- **文案用字面字符串而不是 lang 键**（D26）—— 这个文件之后要删除，用 lang 键会留下孤儿翻译键；
- `criteria` 只有一个：`{ "opened": { "trigger": "beloong:hell_gate_opened" } }`。

**验证（廉价且能提前分流故障）：** jar 内文件存在；进游戏后
`/advancement grant @s only beloong:hell_gate_test` **能成功**（若 JSON schema 写错，游戏会报找不到该进度）
⇒ 这条把「进度 JSON 写坏」与「判据没触发」在 T7 之前就分开。

### T6 —— 静态探针（实现完成后的整体取证）

**交付物：** 无新源文件（命令与结论写回本文档 §五）

**验证：** 对 `build/libs/beloong-0.9.6.jar` 逐项核对：新增 3 个 class 存在；
`data/beloong/advancement/hell_gate_test.json` 在 jar 内；`javap` 确认 T1–T4 的四处引用。

### T7 —— 实机探针（5 个场景，一个客户端运行跑完）

**交付物：** 探针数据包 `run/saves/hellgate_probe/datapacks/hellgate_advprobe/`（`run/` 已 gitignore）
+ 读日志得到的证据（写回 §五）

**步骤：**
1. 按 §〇之三 把上一轮的 `hellgate_probe` 包移出世界；
2. 写探针函数：
   - `init`（load 标签）：计分板计数决定排第几步；
   - 每个场景一个函数：`advancement revoke @a only …` 清场 → 设位置/游戏模式 →
     **40 条 `setblock` 重建全新门**（关键：复用旧门会带残留 `animationTicks`，一点火就跳 145，
     会把「要求 2」的测试做废）→ `say` 标记 → 给基准格 `setblock … [lit=true]` 点火 →
     排**两个**检查函数（+5 s 与 +10 s，**函数名各不相同**，同名 `schedule` 只留最后一个）；
   - 检查函数：`execute store result score #had gateprobe run advancement revoke @a only …` →
     `tellraw` 的 score 组件输出读数（函数里的命令反馈不进日志）；
   - 场景：① 门前 16 格生存（早 0 / 晚 1）② 40 格外（0/0）③ 旁观（0/0）④ 创造（0/1）⑤ 重复开门（1/1）；
3. 临时在 `build.gradle` 的 client 运行里加 `--quickPlaySingleplayer hellgate_probe`，后台启动客户端；
4. 读 `run/logs/latest.log` 取证据；**跑完必须把那两行临时参数删掉**（沿用既有纪律）。

**验证：** 5 个场景的早/晚读数与预期完全一致；日志里另有进度的聊天播报（`announce_to_chat`）作为第二独立证据。

### T8 —— 文档回填与记忆

**交付物：** 修改 `docs/plans/2026-09-21-hell-gate-opened-advancement-design.md`（状态改「已实施」+ 实机证据）、
本计划 §五 实施结果；更新 `memory/decisions-log.md`（该条改为「已实施」+ 实机读数）、
`memory/project-context.md`（地狱之门子系统补一句「开启进度判据」）。

**验证：** 文档内引用的数字与实际日志一致（逐条对照）。

### T9 —— 删除临时进度（要求 4，收尾）

**交付物：** `git rm src/main/resources/data/beloong/advancement/hell_gate_test.json`

**验证：** `git log --diff-filter=D --name-only -1` 显示该文件被删除；
重新 `.\gradlew.bat jar` 后 **jar 内不再有 `data/beloong/advancement/`**（反向取证）。

---

## 二、提交策略

| # | 内容 | 说明 |
|---|---|---|
| C1 | 判据 + 注册 + 编排层 + 方块挂钩 | 不含进度 JSON ⇒ 此时判据存在但没人监听（原版静默 no-op，无害） |
| C2 | 临时测试进度 | **单独一个提交**，便于精确 revert/审计 |
| C3 | 设计文档补实施结果 + 本计划回填 §五 | 纯文档 |
| C4 | **删除**临时测试进度 | 提交信息写明「示例进度清理」，与上次死王仪式的做法一致 |

## 三、风险与回退

| 风险 | 影响 | 缓解 |
|---|---|---|
| 临时进度 JSON schema 写错 | 实机「什么都没发生」，误判成判据坏了 | T5 的 `/advancement grant` 冒烟先分流 |
| 判据没人监听而静默 no-op | 同上 | T5 保证有监听者；场景 1 会立刻暴露 |
| 复用旧门导致计时器残留 | 「要求 2」的测试作废（一点火就跳 145） | 每场景 40 条 `setblock` 重建 |
| 上一轮门探针的 `view` 干扰场景 | 位置/游戏模式被覆盖 | §〇之三：跑前移出旧包 |
| 同名 `schedule` 只留最后一个 | 早/晚采样只剩一个 | 每个检查函数各用各的名字 |
| 客户端启动失败/超时 | 无实机证据 | 沿用已跑通的 quickPlay 手法；若确实跑不起来，**如实标注「未实机验证」**，不用静态证据冒充 |
| 把临时参数留在 `build.gradle` | 污染提交 | T7 步骤 4 + 提交前 `git diff build.gradle` 核对为空 |

## 四、完成定义（DoD）

1. `.\gradlew.bat build` 退出码 0；T6 静态探针全绿；
2. T7 五个场景全绿 **且** 用户看到 toast（人眼）；
3. 临时进度已删除，且删除有 git 与 jar 双向证据（T9）；
4. 设计文档/计划文档/memory 三处记录与实际实现一致；
5. 移植件 `HellGateBlockEntity` 的差异仍可数：本次只多一处调用，且原条件行未改。

## 五、实施结果（2026-09-21 收尾）

**状态：** ✅ T1–T9 全部落地；用户实机确认「测试通过，没什么问题」（含多人场景）。
**提交：** `52028ad`（C1 判据+注册+编排层+挂钩）→ `80c520c`（C2 临时进度）→ C3 文档 → C4 删除临时进度。

| 任务 | 结果 | 证据 |
|---|---|---|
| T1 判据 | ✅ | `javap`：`extends SimpleCriterionTrigger<…Instance>` + `public void trigger(ServerPlayer)` |
| T2 注册 | ✅ | `ModCriteria` 常量池 `Utf8 hell_gate_opened`；既有三个判据名未受影响 |
| T3 编排层 | ✅ | 字节码含 `Vec3.atCenterOf` / `List.copyOf` / `isSpectator` / `distanceToSqr` / `ModCriteria.HELL_GATE_OPENED` |
| T4 挂钩 | ✅ | `tick` 内 `instanceof ServerLevel` + `grant(ServerLevel, BlockPos)`；`git diff` 证实**灾变原有条件行一字未改** |
| T5 临时进度 | ✅ | JSON 可解析、jar 内含（475 B）；实机冒烟 grant-result=1 |
| T6 静态探针 | ✅ | jar 内 3 个新 class + 进度 JSON；四项联动检查全绿 |
| T7 实机探针 | ✅ 5/5 | 见设计文档 §八 B-1（含第二条独立证据：聊天播报时刻） |
| T8 文档/memory | ✅ | 本文档 §五 + 设计文档 §八/§十三 + `memory/` 三处 |
| T9 删除临时进度 | ✅ | `git rm` + 重建 jar 后 `data/beloong/advancement/` 不再存在（反向取证） |

### 本次新增的两条探针教训（已记入 `memory/learned-patterns.md`）

1. **`execute if score #x obj matches 0` 对从未设置过的分数不成立** —— 分数不存在 ≠ 0。
   我加的定点复核（把「门是否 OPEN」与「是否已发放」在同一次调用里对读）就是因为这条**第一次整轮白跑**：
   排程条件不成立 ⇒ 一行输出都没有。**凡是要用分数做「第几次加载/第几步」的开关，先 `add`/`set` 再判断。**
2. **单人服务端的墙上时间 ≠ tick 数**：观测到「点火 → 播报」≈6.6 s，而代码是 145 tick = 7.25 s
   —— 追赶 tick 会让 ticks/秒 短时高于 20。⇒ **判定「等了多少 tick」这类断言，要么读游戏时间，要么只认代码位置，
   不要用日志时间戳反推 tick 数。**

### 未完成/遗留（据实记录）

- **同刻配对复核**未跑成（探针 bug，已修且已归档 `hellgate_pairprobe`，按用户要求停手）⇒
  「发放与门变可进入同生同灭」目前只有**代码级证据**（同一分支），没有同刻实机读数。
- 多玩家并发由用户实机确认 ✅（开发环境单客户端无法机器验证）。

