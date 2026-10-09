# 「我的 NPC」命令族 + 通关后回收分身 收尾审查报告

**审查基线**：`54f2d2e..HEAD` 里本特性的源码提交 —— `b906d59`（命令族）/ `aeb16c7`（`keep_after_finish`）
**规格**：`docs/plans/2026-10-01-mynpc-command-and-finish-reaping.md`（用户已批准）
**方法**：子代理独立审查（对着计划逐条核实现）
**结论**：**0 Critical / 2 Important / 3 Minor** —— 全部已修

---

## 一、Important（2，已修）

**I1 —— 多只命中时的取舍与对账器不一致**（`command/MynpcCommand`）
命令只按 `bornAt` 倒序，并列（旧实体的 0、或同 tick 生成）时退化成 `getAllEntities()` 的遍历顺序；
而**对账器**并列时还有"离玩家最近"兜底 ⇒ 命令动的那只可能正是对账器马上要删掉的那只 ——
**而类注释正宣称两者是"同一个取舍"**（自己打自己脸）。
修法：命令侧补上 `.thenComparingDouble(player::distanceToSqr)`，与对账器**逐字同一取舍**。

**I2 —— 非玩家执行者复用了错误的文案，且计划承诺的新键没加**（`command/MynpcCommand`、语言文件）
原先复用了 `beloong.command.route.not_a_player`，而那句文案把理由写成"靠该玩家最近对话过的 NPC 定位目标"
—— 对 `mynpc` **恰好是反的**（本族存在的理由就是不这么做）。控制台/命令方块执行时会看到错误的解释。
修法：新增 `beloong.command.mynpc.not_a_player`（中英各一份，点明"解析归属你的那只 NPC"）并改用它。

## 二、Minor（3，全部已修，其中两条是真行为改进）

| # | 问题 | 修法 |
|---|---|---|
| M3 | `finishedAndReaped` 的注释称"只是两次进度查询，没有副作用" —— 实际是**一次查询 + 一次字段读**，且 `isEarned` 会为未追踪进度顺手登记空记录 | 注释改为实话，并点明调用方靠 `&&` 短路避免为"根本没开始过的玩家"付这次查询 |
| M4a | `tp` 的 `[yaw]` 单独给出不可用（yaw 节点没挂 `executes`）—— 与计划 T6 的"可选 `[yaw]`/`[pitch]`"不符 | 给 yaw 节点补 `executes`（pitch 省略 ⇒ 沿用当前俯仰）|
| M4b | `pos` 里的 `~` 相对的是**执行者**而不是被传送的 NPC，类注释未说明 | 指令面说明里写明"想就地请写绝对坐标" |
| M5 | 回收只遍历**在线**玩家 ⇒ 通关后那一轮巡检之前下线（且 `clear_on_logout=false`）⇒ 分身隐身滞留到下次登录 | 退出处理里补一次"通关即回收"（**只删不撤**，reason = `finished_on_logout`）|

## 三、与计划的对账（审查确认）

- **非目标全部守住**：无 `teleportTo`/跨维度分支、无"跑"概念、`NpcCommand` 一字未改（diff 只碰 9 个文件）
- **4 个子命令语义都对**：`route` 复用 `route.unknown`/`route.set`；`play`·`play stop` 与 `NpcCommand` 同形；
  `effect` 的参数范围与默认值、`!hideParticles` 取反、时长换算（瞬时=秒、其余 ×20、缺省 600/1）
  与 `EffectCommands.java:156-183` 逐一一致；`tp` 的三件事（清移动目标 + 撤销已执行路径 + `Entity#moveTo`）齐备
- **`visibleTo` 与对账器确实成对**：通关那一 tick 起分身即停发、锚点即接管（`broadcastToPlayer` 每追踪周期回调），
  对账器 5 秒内删实体 ⇒ **全程恰好一个**；上一轮的 D21（`finished` 永不重置）与 C1（搜索不可信不动手）均未被破坏
- **codec 对齐**：9 个字段名与顺序与 record 组件逐一相同，`KNOWN_FIELDS` 9 项同名；
  默认 `false` + 自带 `mo.json` 显式 `true` ⇒ 自带短剧情行为不变、新剧情默认回收
- **纯函数约束未破**：`visibleTo` 无写实体字段/发包/实体搜索，只多一次进度查询（计划 T10 已接受）
- **语言键**：中英一致、键序逐位相同、新键占位符数与 Java 实参一致、反馈全走 `Component.translatable`

## 四、审查指出的两处偏差（可辩护，保留）

| 偏差 | 保留理由 |
|---|---|
| 计划 T1 说 `type` 补全列**全部实体类型**，实现只列**有剧情声明**的类型 | 只有接了剧情的类型才可能拥有私有分身 ⇒ 补全列表即"这条命令的全部可用取值"，同时顺带告诉作者还有哪些 NPC 接了剧情（代码注释已写明）|
| 计划 T5 说 `effect` 反馈含"数量"，实现用单只 NPC 名 | 本族的目标**恒为一只**（归属执行者的那只）⇒ 报数量恒为 1，报名字更有信息量 |

## 五、已核对通过

命令族与 `npc`/`route` 平级（无同名歧义）✓ 解析只按归属、无任何"最近"退化路径 ✓
`effect` 需要 `CommandBuildContext` 已正确传入 ✓ `tp` 用 `Entity#moveTo` 而非 `NpcEntity#moveTo(Vec3)` ✓
`keep_after_finish` 三处对齐（守卫 ⑰ 机器检查）✓ 通关回收"两处成对" ✓
`gradlew build` + stage（⑯ 4 条 + ⑰ 5 条）/ route 两套不变量 + 三探针全绿 ✓
