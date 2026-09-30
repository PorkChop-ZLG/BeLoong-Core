# 过场动画（CG）系统代码审查报告

**审查基线**：`8292a77`（批次① 依赖升级 + CG 框架 + 命令 + 第一条 CG）、`e50adb0`（v2 七段曲线 + 观察者隐身）、
`6b133d4`（v3 六段 + 隐身 130 tick）；即 `git diff 49b781e..HEAD` 的全部内容**加上** `8292a77` 本身
**分支 / HEAD**：`NPC` / `6b133d4`（工作区干净）
**日期**：2026-09-30
**审查范围**：**只审 `src/`** —— 含被本功能改动的 `build.gradle`、`src/main/templates/META-INF/neoforge.mods.toml`、
`src/main/resources/assets/beloong/lang/*.json`。`docs/` 是**被审对象**（本项目头号缺陷面就是"文档/注释声明与代码实际行为脱节"）。
智能体自写的 `D:\Minecraft\tools\YSMParser\*.py` **不算审查发现**（唯一例外见"不在本次范围"）
**方法**：主审单路逐条核验，每条断言都落到三类证据之一 ——
① 本仓库源码；② **fdlib 1.0.9 参考源码**（`D:\Minecraft\开源模组参考文件\FDLib`，其 `mod_version=1.0.9`，
与本项目依赖的 `fdlib-1271749-7844741.jar` 同版本、同为 973,335 字节）；
③ **1.21.1 + NeoForge 21.1.236 的反编译源码**（`开源模组参考文件\DragonSurvival\build\neoForm\neoFormJoined1.21.1-20240808.144430\steps\unzipSources\unpacked`，
已含 NeoForge 补丁）。构建用 `.\gradlew.bat build` 复现。
**结论**：**Critical 无**；**5 Important**（全部是"声明与事实不符"，其中 3 条在 `src/` 的 javadoc 里、且都是 v2→v3 的漏改）；
**10 Minor**；另 1 条**不在本次范围**的脚本假通过。**没有一条是逻辑缺陷或崩溃面**。

> 编号：`I-` = Important，`M-` = Minor，`X-` = 不在本次范围。每条都给出"文件:行号 + 问题 + 证据 + 建议处置"。
> 用户已实机验收通过，因此本报告**不以"好不好用"为判据**；判据是"三份东西（代码 / 设计文档 / 计划文档）是否在讲同一件事"。

---

## 范围与方法

- **只审 `src/`**：`docs/` 作为被审对象（因为它是本项目第一号缺陷面），但 `docs/` 里的问题只在"声明与代码/资产不符"时才算发现。
- **不算发现**：`D:\Minecraft\tools\YSMParser\cg_invariants.py` / `cg_lang_keys.py`（智能体自写工具）。
  按用户口径，**只**在"某条断言会假通过"时于 §四 提一句（本次确实找到一条）。
- **证据口径**：fdlib 的行号一律指 `D:\Minecraft\开源模组参考文件\FDLib\src\main\java\...`；
  原版行号一律指上述 1.21.1 反编译树（下称"MC 源码"）。两者都与实机运行的版本一致。

---

## 一、Critical（0）

无。

- 没有崩溃面：`CgAnimation.play` 的四条预检确实堵住了 fdlib 侧唯一**被核实**的崩溃面（空轨迹列表）；
- 没有服务端/客户端侧别错用：`cg/` 包 4 个类与 `CgCommand` 全部只引用通用类（`MobEffects` / `MobEffectInstance` /
  `CutsceneData` / `CameraPos`），没有客户端专属类，专用服务端加载不会 `NoClassDefFoundError`；
- 没有状态泄漏：全线零持久字段、零新增网络包、零服务端 tick 逻辑（与设计文档 §3.4"状态归属"表一致）。

---

## 二、Important（5）

**I-1｜隐身那段 javadoc 仍写着 v2 的 100 tick / 5 秒（`src` 内，★必须现在改）。**
位置：`src/main/java/com/zonlong/beloong/cg/CgAnimation.java:101-103`（同源第二处在 `:199-200`）。
问题：`:102` 写"那 **100 tick** 要等原效果结束后才生效"、`:103` 写"**5 秒后**自动结束不再是字面事实"、
`:200` 写"效果 **5 秒**就结束、CG **还有 1 秒**"。而 v3 已把常量改成
`MoEntrance.INVISIBILITY_TICKS = DURATION_TICKS + 10`（`MoEntrance.java:158`）= **130 tick = 6.5 秒**，
比 CG 的 120 tick **长 10 tick**（不是短 1 秒）。
证据：`MoEntrance.java:147-158`；设计文档 D8（`docs/plans/2026-09-30-cg-system-design.md:191,406-409`）与计划文档
的 v2/v3 对照表（`docs/plans/2026-09-30-cg-system-plan.md:83`）**都已改成 130**——只有这份 javadoc 漏改。
它描述的是**行为**（"5 秒后自动结束"），不是措辞，所以列为 Important。
**建议处置**：`100 tick` → `130 tick`；`"5 秒后自动结束"` → `"6.5 秒后自动结束"`；
`:199-200` 的括注改成"效果（6.5 s）比 CG（6.0 s）长 0.5 s ⇒ 全程落在隐藏窗口内"（**图标不显示这一结论本身仍然成立**，
只是两边的数字要换成 6.5 / 6.0）。

**I-2｜`track` 的"相位差"那段仍是 v2 的数字，且补偿方向与它自己描述的机制相反（`src` 内，★必须现在改）。**
位置：`src/main/java/com/zonlong/beloong/cg/CgContext.java:153-161`（重点 `:157-158`）。
问题：`:157` 写"本 CG 第⑤段是 **3 tick 内转 46°** ⇒ 那一段里'位置'会晚于'朝向'约 **15~30°**"。
v3 的第⑤段是 **44 → 50 = 6 tick**（不是 3 tick），46° ÷ 6 ≈ 7.7°/tick ⇒ 1~2 tick 的相位差只有 **8~15°**。
`:158` 又写"若实机觉得**推近或甩镜偏早**，这就是原因；可在 `MoEntrance` 里把对应断点**前移** 1 tick 补偿"——
但按它上一句自己的机制（位置晚于朝向）能观察到的现象是**推近偏晚 / 甩镜偏早**，
而"前移断点"只会让甩镜更早。**方向写反了**。
证据（机制属实、只是幅度与方向要改）：`CutsceneExecutor.java:49-53` 位置用**自增前**的 `currentTime` 且 `partialTick` 硬编码 0
（`:79-82`），`CutsceneCameraHandler.java:144` 朝向用**自增后**的 `currentTime + partialTick`。
另：`CgContext.track` 只按 `currentTime` 口径算，所以"约 1 tick"按那个口径成立；
但真正渲染出来的机位还要经 `xo→pos` 的 `partialTick` 插值（`CutsceneExecutor.java:36-38`，原版
`Camera.setup` 用 `Mth.lerp(partialTick, entity.xo, entity.getX())`，MC 源码 `client/Camera.java:57-61`），
所以**观感差约 2 tick**（第⑤段 ≈ 15°），不是 1 tick。
**建议处置**：`:157` 改为"第⑤段是 **6 tick** 内转 46°（≈7.7°/tick）⇒ 那一段里'位置'会晚于'朝向'约 **8~15°**，
按渲染插值算约 2 tick"；`:158` 的补偿方向改为"把**仰角**断点**后移** 1 tick（或把**距离**断点**前移** 1 tick）让两条曲线对齐"。
（这是 T10 对照表之外的第六条调参线索，写反了会把人带偏。）

**I-3｜计划文档风险表仍写"给了观察者 5 秒隐身"。**
位置：`docs/plans/2026-09-30-cg-system-plan.md:413`。
问题：同一份文件 `:83` 的 v3 对照表写"**130 tick** = `DURATION_TICKS + 10`"，`:78` 写六段编排，
而 `:413` 的"玩家自己的身体出现在画面里"这一行仍写"**v2 已处理**：给了观察者 **5 秒**隐身"。
这与代码（`MoEntrance.java:158`）和设计文档 D8（`:191,406`）都矛盾。
证据：三处数字（代码 130 / 设计文档 130 / 本行 5 秒）中只有本行是旧的。
**建议处置**：改成"6.5 秒（130 tick）隐身"，并把该行的"v2 已处理"改成"v2 引入、v3 改时长"。

**I-4｜`MoModel` 的 javadoc 说 `descend`"暂无代码引用"——本功能刚刚推翻了它（`src` 内，★必须现在改）。**
位置：`src/main/java/com/zonlong/beloong/client/model/MoModel.java:43`（同源第二处在 `:60`）。
问题：`:43` 原文"其余 3 条（{@code descend}、{@code attack}、{@code idle_old}）已备好、**暂无代码引用**"。
本功能新增 `MoEntrance.ANIMATION_NAME = "descend"`（`MoEntrance.java:79`）⇒ **`descend` 已被引用**；
而且这句话的另两项也是错的 —— `attack` 有引用（`NpcEntity.java:175` 的 `attackAnimationName()` 默认返回 `"attack"`，
`:1293-1295` 的 `triggerableAnim` 用它），`idle_old` **在两个动画文件里都不存在**（`mo.animation.json` 5 条 =
idle/fly/walk/run/attack；`mo.extra.animation.json` 3 条 = sit/dance/descend，`:8303`）。
证据：资产实测 5 + 3 = 8 条；`:42` 那句"代码目前播其中 **6** 条"现在也应是 7 条（`descend` 由 CG 播）。
**处置理由**：这正是"清理未使用动画"时会被当成死资产删掉的那一条 —— 删了 CG 不会崩，只会打一条
`EmoteAnimationLookup` 的英文 WARN（`EmoteAnimationLookup.java:104-107,120-125`），**相机照转、末不动**，
属于"看得见但不容易归因"的失效。
**建议处置**：`:42-43` 改为"代码目前播 **7** 条：idle / walk / run / fly / sit / dance / **descend（CG `mo_entrance`）**；
`attack` 由攻击控制器点播；`idle_old` 已不存在（该名在两份资产里都没有）"，`:60` 的骨名命中清单同步去掉 `idle_old`。

**I-5｜计划文档的"执行状态"台账已与仓库历史脱节。**
位置：`docs/plans/2026-09-30-cg-system-plan.md:43-45`（台账表）与 `:52-56`（批次① 的证据与"尚未做"）。
问题：`:44` 写"② T8–T10 ⏳ 待执行"、`:45` 写"③ T11 ⏳ 待执行"、`:56` 写"**尚未做**：实机验收（T9）"。
事实：T8 已在 `e50adb0` 重写并跑通（提交信息：15/15 PASS；`6b133d4`：14/14 PASS），
T9 已由用户实机验收通过（本轮审查的输入前提）。**T11 确实仍未做**（`memory/` 三份文件在这三个提交里没有变更）。
同一份文件的 `:326` 标题也仍写"T9 实机验收（用户执行）—— **v2 清单**"，而表体已是 v3。
**建议处置**：台账改为"② T8 ✅ / T9 ✅（用户 2026-09-30 验收通过）/ T10 按 v3 常量收敛"，
"尚未做"改为"仅剩 T11（设计文档 §八 + memory 回填）"，`:326` 标题的"v2 清单"改"v3 清单"。

---

## 三、Minor（10）

**M-1｜`play` 的"顺序"漏了一步。** `CgAnimation.java:127` 写"顺序：预检 → 触发动画 → 发过场包"，
漏掉第 ⑤ 步"给观察者上隐身"。同文件 `:46-48`、设计文档 §2 的 C3（`:216`）、§3.4 的图（`:448-462`）
都写的是"预检 → 触发动画 → **上隐身** → 发包"。
**建议处置**：`:127` 补齐为四步。

**M-2｜"出画分析"表 2.5 秒那一行与它自己声明的公式不符。**
位置：`MoEntrance.java:34-45`（表在 `:36-43`）；设计文档同一张表在 `2026-09-30-cg-system-design.md:328-340`。
问题：表的表头声明 `h = (18.7 + AllBody.position.y) × 0.05`。按 `descend` 的资产实测
（`mo.extra.animation.json:27477` 的 `"2.4933": 29.729`、`:27478` 的 `"2.5067": 17.068`，线性插值）
2.5 s 处 `AllBody.position.y ≈ 23.4` ⇒ **h ≈ 2.1 格**，与镜头中心的夹角 `atan((2.1−1.62)/3) ≈ +9.2°`，
**不是表里的 3.7 格 / +35.1°**。3.7 只有把"末的体高"理解成**模型顶到**（而不是 `AllBody` 原点）才凑得出来 ——
而表的其它行用的都是原点（1.2 / 1.75 / 2.0 / 2.2 / 2.7 秒五行我逐点复算**全部命中**：
10.7 / 15.4 / 14.3 / 11.5 / 0.4 与 +2.5° / +13.8° / +11.8° / +4.9° / −21.8°）。
⇒ 结论"只在 2.5 秒前后一瞬间擦到画面边缘"在**声明口径下不成立**（实际还有富余）。
**建议处置**：把 2.5 s 行的"末的体高"改成 2.1、夹角改成 **+9.2°**，并把"⚠️ 刚好擦到边缘"改
"✅（即便按模型顶点算也只到 ~+34.7°，仍在默认半角 35° 之内）"；两处同步改。
注意这条**只是描述性**的（仰角不依赖它），不影响任何行为。

**M-3｜"相机高度恒为 1.62 格"只是「烘出来的关键点」，不是渲染出来的机位。**
位置：`MoEntrance.java:130-134`（注释"整段不变"）；设计文档 `§3.3:319`（"高度恒为 1.62 格"）。
问题：fdlib 的相机实体是 `LivingEntity`，`EntityType.Builder...sized(0.2f, 0.2f)`
（fdlib `init/FDEntities.java:20-24`）⇒ `EntityDimensions.eyeHeight = 0.2 × 0.85 = **0.17**`
（MC 源码 `world/entity/EntityDimensions.java:11-13`），而原版 `Camera.setup` 在实体 Y 上**再加**
`Mth.lerp(partialTick, eyeHeightOld, eyeHeight)`（`client/Camera.java:57-61`）。
所以实际机位 = 烘好的关键点 + 0.17 格；而且 `Camera.tick()` 以 0.5 的系数把 `eyeHeight` 平滑到新相机的 0.17
（`client/Camera.java:76-81`，由 `client/renderer/GameRenderer.java:754` 每 tick 调，且 `Camera` **不会**在换实体时复位）
⇒ 相机实体接管后的最初几帧它还是**玩家的 1.62**：约 +1.62 / +0.90 / +0.53 / +0.35 … 逐 tick 折半衰减。
（顺带核实：fdlib 强制第一人称 ⇒ `detached=false`，`ClientCameraEntity` 也不 sleeping ⇒
`Camera.setup` 的第三人称/sleeping 两个额外位移分支都不走，MC 源码 `client/Camera.java:62-73`。）
**影响**：稳态 0.17 格可忽略；开场前 ~3 tick 的 1 格上下偏移会让"对准高空"的开场构图偏高一点。
**建议处置**：**不要动常量**（实机观感已验收，改 `VIEW_EYE_HEIGHT` 会改掉已认可的构图）。
把 `:131-132` 与文档 §3.3 的措辞改成"烘制高度 1.62 格；实际渲染机位另加原版 `Camera` 平滑的眼高
（fdlib 相机实体 0.17，切换后前几帧从玩家眼高衰减），稳态 ≈ 1.79 格"，并在 T10 表里补一行
"开场前几帧构图整体偏高 → 不要调 `PITCH_*`，那是原版眼高平滑"。

**M-4｜设计文档 §2.2 的命令注册点行号与行数都过期了。**
位置：`docs/plans/2026-09-30-cg-system-design.md:83`："命令注册点：`onRegisterCommands` **目前只有一行** | `BeLoongCore.java:213-216`"。
实际：`BeLoongCore.java:221-224`，**两行**（`NpcCommand.register` + `CgCommand.register`）。
**建议处置**：改成 `BeLoongCore.java:221-224`（并注明本功能加了一行）。

**M-5｜"两条下发路径"现在是三条。**
位置：设计文档 `§3.4:476`："**两条下发路径走两套不同的网络通道**（原版实体数据 vs 模组自定义载荷）"。
v2 起实际是**三条**：表情走原版实体数据（`NpcEntity.setEmote` 的 `force=true` 发包）、
隐身走原版效果包、过场走 fdlib 的 `StartCutscenePacket`。同节 `:456-462` 的图自己已经画了三条。
**建议处置**：`:476` 改成"三条下发路径走两套通道（原版实体数据/效果包 vs 模组自定义载荷）"。

**M-6｜"同文件其它动画"里混进了另一个文件。**
位置：设计文档 `§2.3:103`："**同文件**其它动画的骨骼位移幅度只有 5–37 单位（`sit` 18.6 / `dance` 9.7 / `attack` 36.6）"。
`attack` 在 `mo.animation.json:16034`，不在 `mo.extra.animation.json`（后者只有 `sit:4` / `dance:2870` / `descend:8303`）。
`| 实测全部 8 条动画` 这一格本身**是对的**（5 + 3）。
**建议处置**：去掉"同文件"三字，或改成"（`sit`/`dance` 在 extra，`attack` 在主文件）"。

**M-7｜错误处理表缺一行。**
位置：设计文档 `§四:500-516` 的"情况/行为/理由"表。
问题：`CgAnimation.play:201-208` 有一条"`addEffect` 被拒（`MobEffectEvent.Added` 被取消 /
`canMobEffectBeApplied` 返回 false）⇒ 英文 WARN、CG 照常播"的分支（`viewer.addEffect` 的返回值**有**检查 ✓），
但表里没有对应行。
**建议处置**：在表中补一行"观察者隐身被拒 → WARN + CG 照常播；后果是这几秒能看见自己的身体"。

**M-8｜`play` 自称"唯一的副作用出口"，但真正的发包在 `try` 之外、且在两个副作用之后。**
位置：`CgAnimation.java:144-153`（`build` 有 `catch (Throwable)`）与 `:211`（`FDLibCalls.startCutsceneForPlayer` 在 try 之外）。
问题：`setEmote`（`:173`）与上隐身（`:193`）都在发包**之前**。若 `StartCutscenePacket` 的编码/发送抛异常
（`CutsceneData.encode` → `autoSave` 走 NBT 反射，理论上可抛），异常会穿出 `play`、穿出命令处理器，
留下"**动画已经播了、相机没接管**"的半应用状态。
**建议处置**：把 `:211` 也纳入同一个 `try`（失败则返回 0，让命令层给 `beloong.command.cg.failed`），
或把触发动画挪到发包成功之后（两行改动）。

**M-9｜四条预检没有覆盖 `cutsceneTime <= 0`。**
位置：`CgAnimation.java:155-162`（预检③）；类注释 `:27` 把四条表述成"缺一条都会静默产生坏状态"的完备清单。
问题：fdlib 的 `CutsceneUtil.getPercent`（`CutsceneUtil.java:6-8`）= `(currentTime + partialTick) / data.getCutsceneTime()`，
`cutsceneTime = 0` 时得 NaN ⇒ `(int) NaN = 0`、`localPercent = NaN` ⇒
`LinearCameraMotion.calculateCameraPosition` 返回 NaN ⇒ 客户端 `camera.setPos(NaN)`。
`CutsceneExecutor.tick` 的 `if (currentTime > data.getCutsceneTime()) return true;` 拦不住 0。
**当前不可达**（`DURATION_TICKS = 120`，且被 `cg_invariants.py` 的 A1 钉在 `descend` 上），
所以定 Minor；但"唯一的副作用出口"里加一条 `data.getCutsceneTime() <= 0` 是零成本加固，
能挡住将来第 N 条 CG 的作者。
**建议处置**：预检③ 改成 `data == null || data.getCutsceneTime() <= 0 || data.getCameraPositions().isEmpty()`，
或另立一条，并同步类注释的条数。

**M-10｜`WARNED_UNKNOWN` 只增不清。** `CgRegistry.java:47-48` 的 `HashSet` 永不清理。
规模按"执行者敲错过的不同名字"计，极小；同 `NpcDialogueStage` 的既有先例。
**建议处置**：仅记录，不必动。

---

## 四、不在本次范围（顺带发现）

**X-1｜`cg_invariants.py` 的 A7 会假通过（工具脚本，按用户口径不算审查发现，仅提一句）。**
位置：`D:\Minecraft\tools\YSMParser\cg_invariants.py:147-157`。
`ok_a7 = step >= 1 and duration_ticks % step == 0 and step <= snap_span`（`snap_span = 6`）。
判据是 `step <= 6`，于是 `SAMPLE_STEP = 4/5/6` 全部 **PASS**，而那时第⑤段只采到 2 个点
（`int(6 // 4) + 1 = 2`，脚本自己的输出文案还写着"（要求 ≥3 …）"）。
⇒ 脚本、设计文档 §5 的 A7（`design.md:546-547`）与计划文档 T8（`plan.md:312-313`）三处都声明
"第⑤段**至少采到 3 个点**"，但实现只声明了 `step ≤ 6`。同一条断言上还有第二个缺口：
A6（`:137-145`）只守隐身**时长与等级**，不守 `MobEffectInstance` 构造器的
`visible=false`（无粒子）/ `showIcon=false` —— 把第一个 `false` 改成 `true` 脚本仍 PASS，
而"无粒子"是用户明确提出的需求。
**建议处置**（若之后要动脚本）：A7 改成 `samples_in_snap >= 3`；A6 补一条对构造器实参的文本断言。
这两条**不影响本次收尾**，也**不改变 `src/` 的任何结论**。

**X-2｜`NpcEntity.setEmote` 的 javadoc 与表情系统的实际行为不符（既有文本，非本功能引入）。**
`NpcEntity.java:705-707` 写"客户端会在播放前预检、查不到就**静默不播**……**不会有任何反馈**"，
但 `EmoteAnimationLookup.find` 对名字缺失会打英文 WARN（`EmoteAnimationLookup.java:104-107,120-125`），
且这条 WARN 在 2026-09-29 就被刻意加过（该类注释 `:25-35` 说明了理由）。
设计文档 §4（`design.md:507`）与 `MoEntrance.java:77` 说的"打一条英文 WARN、保持状态动画"才是对的。
**处置**：不在本次范围（那两行不是这三个提交改的），但它是同一个"声明与事实不符"家族的成员，值得顺手改。

---

## 五、已核对通过

以下是我**逐条确认过**的关键点（不是"看起来没问题"），附证据：

**A. 数值与规格逐项对账（设计文档 §3.3 表 ↔ `MoEntrance` 常量）**
1. 断点 `0.25 / 0.8 / 1.2 / 2.2 / 2.5` 秒 × 20 = `5 / 16 / 24 / 44 / 50`，与 `T_DROP_END / T_RISE_START / T_RISE_END / T_SNAP_START / T_SNAP_END`
   （`MoEntrance.java:94-106`）**逐项相等**；v3 全是整数，确无取整问题 ✓。
2. 仰角常量 `62.25 / 24.75 / 46.00 / 0.0`（`:111-120`）与设计文档 §3.3 表右列、以及用户给定的"游戏内 `xRot` 取负"
   （−62.25 / −24.75 / −46.00 / 0）**逐项相等** ✓。
3. 距离与眼高 `VIEW_DISTANCE_FAR = 8.0` / `VIEW_DISTANCE_NEAR = 3.0` / `VIEW_EYE_HEIGHT = 1.62`（`:125-134`）✓。
4. `SAMPLE_STEP = 1`（`:143`）⇒ `CgContext.track` 得 `intervals = 120`、**121 个关键点**，与文档"121 点"一致 ✓；
   包体量级也对（121 个 `CameraPos` 走 NBT 编码约 10~20 KB，远低于原版自定义载荷 1 MiB 上限）✓。
5. `DURATION_TICKS = 120`（`:89`）= `descend.animation_length 6 × 20`：资产实测
   `mo.extra.animation.json:8304` 为 `"animation_length": 6` ✓；`descend` 确实在该文件（`:8303`）✓，
   且 `MoModel.getAnimationResourceFallbacks` 把它挂成 fallback 链 ⇒ `EmoteAnimationLookup.find` 查得到 ✓；
   GeckoLib 侧长度单位也是 tick（`NpcEntity.java:1275-1276` 注释 + `animation.length()` 比较）✓。
6. 隐身：`INVISIBILITY_TICKS = DURATION_TICKS + 10 = 130`（`:158`）、`amplifier = 1`（`:161`）✓，
   与设计文档 D8（`:191`）与常量表（`:421`）一致 ✓。
7. `MoEntrance` 类注释的时间轴表（`:17-26`）与 `elevationAt` / `distanceAt` / `build` 的代码**逐格一致** ✓，
   包括"第⑤段 8→3 格、−46°→0、仰角 linear / 距离 ease-in-out" ✓。

**B. 曲线边界（逐断点求值，不是看代码像不像）**
- `elevationAt`：`t=5` 得 `24.75`（`easeOut(1)=1`），紧接着的分支也返回 `24.75`；`t=16` 两侧同为 `24.75`；
  `t=24` 得 `46.0`，紧接着的分支同为 `46.0`；`t=44` 两侧同为 `46.0`；`t=50` 得 `0.0`，之后恒 `0.0`
  ⇒ **每个断点都连续，无重叠、无空隙** ✓。
- `distanceAt`：`t≤44` 恒 `8.0`，`44<t<50` 走 `easeInOut`（`p=0→0`、`p=1→1`），`t≥50` 恒 `3.0` ⇒ 同样连续 ✓。
- 断点链严格单调 `5 < 16 < 24 < 44 < 50 ≤ 120` ✓；末段（第⑥段）仰角与距离**都恒定** ✓。
- `FDEasings.easeOut(p) = 1−(p−1)²`、`easeInOut` 为 2 段二次曲线（fdlib `util/rendering/FDEasings.java:37-56`）：
  两者都满足 `f(0)=0, f(1)=1`，在断点处不会跳变 ✓。
- `track` 送进 `elevationAt` 的 tick 是 `i × 120 / 120 = i`（double 精确），不会产生 `5.0000001` 之类的越界一分支 ✓。

**C. v3 那一处合并（第⑤段同时下拉 + 推近）**
- 代码确实如此：`elevationAt` 的 `tick <= T_SNAP_END` 分支用**纯 `lerp`（线性）**（`MoEntrance.java:258-261`），
  `distanceAt` 的同一窗口用 **`FDEasings.easeInOut`**（`:224-228`），两者窗口都是 `44 → 50` ✓。
- 文档描述与代码一致：类注释 `:24` 与 `:27-29`、设计文档 §3.3 表第⑤行（`:294`）与正文（`:297-299`）
  都写"同一窗口、仰角 linear / 距离 easeInOut" ✓。

**D. fdlib 的时间约定（这是最需要"照着源码算"的一条）**
- `CutsceneUtil.getPercent(data, currentTime, partialTick) = (currentTime + partialTick) / cutsceneTime`
  （`CutsceneUtil.java:6-8`）；`NormalLookProcessor` 用 `timeEasing.apply(percent)`，
  再 `globalPercent = p × (positions.size() − 1)`（`NormalLookProcessor.java:21-27`），
  `LinearCameraMotion` 同构（`LinearCameraMotion.java:23-31`）。
  ⇒ 第 i 个关键点落在 `currentTime + partialTick = i × T / (n − 1)`。
  `CgContext.track` 用 `intervals = T / step`、`n = intervals + 1` ⇒ `n − 1 = intervals`，
  采样的 `tick(i) = i × T / intervals` **与之逐点相等**（`CgContext.java:174-181`）✓。
  **这条 javadoc 的推导是正确的**，且与 `T` 能否被 `step` 整除无关 ✓。
- 三处 `LINEAR` 确有必要且都设了：`timeEasing` / `lookEasing`（`MoEntrance.java:199-200`）、
  `moveCurveType = CurveType.LINEAR`（`:198`）✓；`EasingType.LINEAR` 的确是恒等（`EasingType.java:9`）✓。
- `moveCurveType` 的选择后：`LinearCameraMotion` 用 `getListValueOrBoundaries`（越界取首/末，**永不返回 null**，
  `FDLibCalls.java:76-89`）+ `CameraPos.interpolate` 纯 lerp（`CameraPos.java:43-49`）✓，
  所以"越界不会 NPE"这一条成立 ✓。
- "位置比朝向晚"的**机制**属实：位置用自增前的 `currentTime` 且 `partialTick=0`
  （`CutsceneExecutor.java:49-53,79-82`），朝向用自增后的 `currentTime + partialTick`
  （`CutsceneCameraHandler.java:144`）✓。引用的两个行号段**逐条命中** ✓。
  （幅度与补偿方向的文案问题见 I-2。）
- "水平朝向恒为 −forward、推近不带来偏航"：`sightLine` 的水平分量取 `aimPoint − camPos` 的 XZ 部分
  （`CgContext.java:113-121`），而 `aimPoint` 恒为 `anchor`、`camPos = anchor + forward·d` ⇒ 恒为 `−forward` ✓。
  并且因为**所有关键点共用同一个方向**，`NormalLookProcessor` 的 `lerpAround` 不会产生绕圈 ✓。
- 符号约定：`FDMathUtil.xRotFromVector(v) = −toDegrees(atan2(v.y, |xz|))`（`FDMathUtil.java:71-77`）
  ⇒ `elevationDeg > 0`（上看）落到 `xRot < 0` ✓；`yRotFromVector(directionFromRotation(0, yRot)) == yRot`
  （我按 `Vec3.directionFromRotation` 的公式手算核对过 `:61-69`）⇒ 用 `directionFromRotation(0, getYRot())`
  代替 `Entity#getForward()` 的 I-1 修正是**正确且必要**的 ✓。
- `CameraPos(Vec3, Vec3)` 的注释（"接近 (0,±1,0) 会异常"）在本 CG 下**不可达**：最大仰角 62.25°、
  水平分量 `cos(62.25°) ≈ 0.47` ≠ 0 ✓；`sightLine` 也永远返回单位向量（不会喂进零向量）✓。
- `sightLine` 里 `Vec3` 的不可变性：1.21.1 的 `Vec3.multiply/scale/add/subtract` **全部返回新对象**
  （MC 源码 `world/phys/Vec3.java:98-172`）⇒ `forward.scale(...)` 不会污染 record 字段 ✓。
- `CameraPos` 的 `Mth.clamp(pitch, −90, 90)`（`CameraPos.java:30`）在本 CG 内不生效（|62.25| < 90）✓。

**E. 隐身那条路（D8）**
- `MobEffectInstance` 6 参构造器的**形参顺序**：`(Holder<MobEffect> effect, int duration, int amplifier,
  boolean ambient, boolean visible, boolean showIcon)`（MC 源码 `world/effect/MobEffectInstance.java:72-74`）
  ⇒ 代码里的 `(INVISIBILITY, ticks, 1, false, false, false)` 与三行注释 `ambient / visible / showIcon` **一一对应** ✓；
  `MobEffects.INVISIBILITY` 在 1.21 是 `Holder<MobEffect>`，与构造器签名匹配 ✓。
- `addEffect` 的返回值**有**检查，且失败有英文 WARN（`CgAnimation.java:193-208`）✓
  （NeoForge 的 `MobEffectEvent.Added` 可取消 / `canMobEffectBeApplied` 可拒 —— 该分支不是杞人忧天）。
- 它隐藏的**层**：`LivingEntityRenderer.isBodyVisible` 就是 `!livingEntity.isInvisible()`
  （MC 源码 `client/renderer/entity/LivingEntityRenderer.java:158-160`），而 RenderLayer 们
  **无条件**渲染（`:131-135`，只被 `isSpectator()` 挡）⇒ **只隐身体模型**；
  `HumanoidArmorLayer.render`、`ItemInHandLayer.render` 里都**没有** `isInvisible` 检查（逐方法核过）
  ⇒ "盔甲/手持物/鞘翅照旧渲染"成立 ✓，"实机验收请脱甲、清空双手"这条要求是必要的 ✓。
- "开头 ≥1 tick 身体仍会被画"的机制**属实**：`updateInvisibilityStatus()` 在 `tickEffects()` 内、
  且只在 `!level().isClientSide` 时调用（MC 源码 `world/entity/LivingEntity.java:819-820`），
  该位由 `ServerEntity.sendChanges()` 发出，而它的调用点在 `ChunkMap` 的 tick 里，
  由 `ServerLevel.tick` 的 `this.getChunkSource().tick(...)`（MC 源码 `server/level/ServerLevel.java:382`）
  **早于**实体 tick 循环（同文件 `:403`）⇒ 最早下一个服务端 tick 才上链路 ✓。
  `CgAnimation.java:176-190` 那段注释写的是**事实**，包括"最省的缓解是 `viewer.setInvisible(true)`、自愈" ✓。
- 残留/自愈：效果到点由原版自行结束，我们没有移除逻辑，也不持有任何状态 ✓；
  "玩家已有更高等级隐身 ⇒ 进 `hiddenEffect`、时长不再是字面事实"这条边界说明与
  `MobEffectInstance.update` 的语义一致 ✓，且**只写在注释里、不承诺行为**，口径恰当 ✓。
- `viewerInvisibilityTicks/Amplifier` 默认 0 ⇒ 未来 CG 不会被动获得效果 ✓（`CgAnimation.java:113-124`）。

**F. 预检与错误路径**
- 预检③ 对应的崩溃面**逐行核实**：`CutsceneCameraHandler.startCutscene` 里
  `CameraPos pos = data.getCameraPositions().getFirst();`（`CutsceneCameraHandler.java:175`）**确实无判空**，
  且 `LinearCameraMotion.java:19-21` / `CatmullRomCameraMotion.java:19-21` 在 `calculateCameraPosition` 里
  还会**每 tick 再抛一次** `RuntimeException("List of camera positions cannot be empty!")` ✓
  ⇒ 这条预检不是防御性编程 ✓。
- 预检② 捕 `Throwable` 与 `EmoteAnimationLookup.java:109-116` 的先例一致 ✓；
  预检④ 的"第二道保险"与命令层的类型校验并存，且**不静默**（都有英文 WARN）✓。
- `CgRegistry`：未知名 `Optional.empty()` + **每名一次**英文 WARN（`CgRegistry.java:75-81`）✓；
  撞车时 `putIfAbsent` **保留先注册** + WARN（`:61-68`）✓；`LinkedHashMap` 保序、`names()` 返回 `List.copyOf` ✓；
  反注册路径不存在（静态表）⇒ 不需要清理 ✓。
- `CgCommand`：权限 2 与 `NpcCommand` 一致（`CgCommand.java:77`）✓；`EntityArgument.entity()`（单数）✓；
  `getPlayerOrException()` 先抛原版异常、不自造文案 ✓；分支顺序（玩家 → 名 → 目标类型）与类注释 `:93-99` 一致 ✓；
  指令树**没有** `stop` 子命令，与用户签名一致 ✓。
- 语言键**逐个配对**：`zh_cn.json` / `en_us.json` 各 **250** 键、集合**完全一致**、无单边键 ✓；
  4 条新键 `beloong.command.cg.{play,unknown,not_npc,failed}` 都在代码里被 `Component.translatable` 引用 ✓；
  `cg.play` 的两个 `%s` 与 `(cg.name(), npc.getDisplayName())` 顺序一致 ✓。
- `build.gradle`（fdlib `implementation` + `localRuntime`）与 `neoforge.mods.toml:108-113`
  （`modId="fdlib" type="required" versionRange="[1.0.9,)" ordering="AFTER" side="BOTH"`）✓；
  jar 实测 973,335 字节、`systems/cutscenes/` 下 **19** 个 `.class`（22 个 zip 条目 = 19 class + 3 目录），
  与设计文档 §2.1 的版本注（`:51-55`）**完全一致** ✓。

**G. 常规项**
- 构建：`.\gradlew.bat build` **exit 0**（`BUILD SUCCESSFUL`）✓；`src` 里没有遗留的 `T_FALL_*` / `T_PUSH_END` 符号 ✓。
- 空指针/越界：`data == null` 与 `isEmpty()` 都判了（`:156`）；`getFirst()` 只在非空之后调用（`:218`）✓；
  `CgContext.of` 对 NaN 用 `!(lenSqr > eps)` 的写法确实能把 NaN 判成退化 ✓（`Vec3.normalize` 本身对
  `< 1e-4` 也会返回 `ZERO`，双保险，MC 源码 `world/phys/Vec3.java:82-85`）。
- 整数除法：`intervals = Math.max(1, totalTicks / Math.max(1, sampleStep))` 是**故意**的整数除法，
  并已用 `tick(i) = i × T / intervals` 的反算把误差归零（`:174`）；`sampleStep > T` 时退化成 2 个点，
  不会除零 ✓。
- 侧别：`cg/` 包与 `CgCommand` 只引用通用类；`CgAnimation.play` 只在服务端执行（`NpcEntity.setEmote`
  自己也有一道 `isClientSide` 早退，`NpcEntity.java:710-712`）✓。
- 引用行号的可信度：设计文档 §2.1 / §2.3 里对 fdlib 与资产的引用我抽查了
  `CutsceneCameraHandler:79-88/104-151/175`、`CutsceneExecutor:24-32/36-38/49-53/79-82`、
  `NormalLookProcessor:21/23-27`、`LinearCameraMotion:27-31`、`CameraPos:30/34/35-41`、
  `CutsceneData:42`、`FDLibCalls:40-42`、`NpcEntity:201-203/524/709-719/730-738/1245-1287/1304-1318`、
  `MoRenderer:63`、`MoModel` fallback、`mo.extra.animation.json:8303-8304, 27121-27125`
  —— **全部命中** ✓（唯一过期的是 M-4 记的 `BeLoongCore` 那一条）。

---

## 六、结论

- **Critical 0**：没有逻辑缺陷、没有崩溃面、没有状态泄漏、没有侧别错用。
- **Important 5**：**全部是"某处声明与事实不符"**，其中 3 条在 `src/` 的 javadoc 里
  （I-1 隐身时长、I-2 相位差、I-4 `descend` 的引用状态），2 条在计划文档里（I-3 5 秒、I-5 台账）。
  它们的共同根因是 **v2 → v3 这一轮改名改值只改了"主表"，没扫干净"旁注"** ——
  这与本项目此前的头号缺陷面（"注释与事实脱节"）完全同型。
- **Minor 10**：多为行号/措辞漂移、描述性表格的算术、以及两条"当前不可达但值得加固"的预检缺口。
- **对"必须现在改才能收尾"的判定**：**I-1 / I-2 / I-4 必须现在改**（三处都在 `src/`，都是随代码发布的 javadoc，
  且 I-4 有"资产被当死代码删掉"的实际后果）；I-3 / I-5 属文档台账，**建议与 T11（设计文档 §八 + memory 回填）一并处理**，
  不阻塞收尾。M-2 / M-3 建议顺带改（同一段落的两处描述性说法，改起来只有几行）。
- **功能本身**：用户已实机验收通过；本报告**没有任何一条要求改行为或改常量** ——
  唯一的代码改动建议是 M-8（把发包纳入 try）与 M-9（补一条预检），两者都是"给下一条 CG 上保险"，
  可以留到第二条 CG 落地时再做。

---

## 七、修复状态（收尾时回填）

**除 M-10 外全部已修**；M-8 / M-9 报告里说"可留到第二条 CG"，实际**本次一并做了**（都只有几行，且降低将来的风险）。

| 编号 | 处置 | 落在哪 |
|---|---|---|
| I-1 隐身 javadoc 仍是 v2 的 100 tick / 5 秒 | ✅ 改为 130 / 6.5 秒；**并顺带纠正了 `showIcon` 的说法** —— v3 起效果比 CG 长 10 tick，那个图标**真的会显示约 0.5 秒**，所以这一位是**有作用的**（原文"本来也不会显示"是错的） | `CgAnimation.java` |
| I-2 `CgContext` 相位差段仍是 v2 的「3 tick / 15~30°」且补偿方向相反 | ✅ 改为「6 tick，误差上限约 8° 与 0.83 格」；补偿方向改成**距离断点前移 / 仰角断点后移，二选一** | `CgContext.java` |
| I-3 风险表「给了观察者 5 秒隐身」 | ✅ 改为 **130 tick（6.5 秒）** | `plan.md` |
| I-4 `descend` 被标「暂无代码引用」 | ✅ 改写整段。**顺带查出比报告更多的过期**：`attack` 有 5 处引用、`sit`/`dance` 自 2026-09-29 起**已无 Java 引用**、`idle_old` 在**两份资产里都不存在** | `MoModel.java` |
| I-5 执行状态台账 + T9 标题 | ✅ 台账改 ①✅ / ②✅ / ③🔄，T9 标题 v2 → **v3** | `plan.md` |
| M-1 `play` 的顺序注释漏了"上隐身" | ✅ 补成「预检 → 触发动画 → 上隐身 → 发过场包」 | `CgAnimation.java` |
| M-2 出画表 2.5 s 那行与公式不自洽 | ✅ **审查者判断正确，且比报告说的更严重**：真实值不是 "+35.1° 擦边"而是 **+9.18°**。逐 tick 重算全条 ⇒ **最大 +27.51° @ 2.40s，全程在画面内、余量 7.5°**。两处（代码 javadoc + 设计 §3.3）同步换表并留修正痕迹 | `MoEntrance.java`、`design.md` |
| M-3 「相机高度恒为 1.62」措辞 | ✅ 改成"这是**写进轨迹**的高度、不等于最终渲染高度"（fdlib 相机实体眼高 `0.2×0.85` + 原版 `Camera` 平滑眼高）；T10 表加一行"只有开场偏高 ⇒ **别调 `PITCH_START_DEG`**" | `MoEntrance.java`、`design.md`、`plan.md` |
| M-4 `BeLoongCore` 行号过期 | ✅ 改为 `:221-224`，并说明"原本只有 `NpcCommand` 一行，CG 加第二行" | `design.md` |
| M-5 「两条下发路径」 | ✅ 改为**三条**（实体数据 / 效果包 / 模组载荷） | `design.md` |
| M-6 `attack` 归属 | ✅ 去掉"同文件"，改成"两份资产合计 8 条" | `design.md` |
| M-7 错误表缺"隐身效果被拒"一行 | ✅ 补了**两行**（效果被拒 / 发包抛异常，并写明三步不是原子的、返回 0 不代表"什么都没发生"） | `design.md` |
| M-8 `startCutsceneForPlayer` 在 try 之外 | ✅ **已修**：纳入同一 catch；`play` 的 javadoc 也改为明说"这三步不是原子的、不回滚" | `CgAnimation.java` |
| M-9 未守 `getCutsceneTime() <= 0` | ✅ **已修**：折进第 ③ 条预检（不新增条数），类注释同步说明它防的是**将来的 CG 类** | `CgAnimation.java` |
| M-10 `WARNED_UNKNOWN` 只增不清 | ⏸️ **不修**（按坏名计、量极小，同既有先例）—— 仅记录 | — |
| （不在范围内但报告提到的脚本问题）A7 假通过、A6 不守 `visible=false` | ✅ **已修**：A7 由 `step <= snap_span`（对 4/5/6 全通过）改为"第⑤段至少采到 3 个点"；新增 **A6b** 按 `// visible` 标记定位并断言 `visible=false`。**并做了变异测试**（`SAMPLE_STEP=4` / `visible=true` / 去掉 `+10` 余量，三条全部 exit 1，还原后 0） | `cg_invariants.py` |

**收尾时的验证**：`gradlew build` exit 0；`cg_invariants.py` **15/15 PASS**；
`cg_lang_keys.py` PASS（250/250）；`src/` 内无 v2 残留符号。
