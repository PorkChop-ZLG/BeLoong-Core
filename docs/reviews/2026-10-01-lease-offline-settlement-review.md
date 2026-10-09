# 租约离线结算 + 提醒数据驱动 + npc_story 结构重组 收尾审查报告

**审查基线**：`74965cf..693da47` 的源码提交（批次①②）
**规格**：`docs/plans/2026-10-01-lease-offline-settlement-design.md`（D1–D14）+ 同名 `-plan.md`
**方法**：子代理独立审查（对着设计逐条对账实现）
**结论**：**1 Critical / 3 Important / 2 Minor** —— 全部已修

---

## 一、Critical（1，已修）

**`dimension.enforce` 在运行期从未被读** —— 我把**数据**拆成了 `host` + `enforce`，
却忘了把**行为**挂到 `enforce` 上：`reconcilePlayer` 里判断"是否离开有效维度"用的仍是
`story.requiredDimension().isPresent()`（= `host` 存在）⇒ **`host` 一写，"离开就清 + 撤进度"就生效**，
`enforce: false` 形同虚设。这恰好坑死 D4 想救的那类剧情（长流程：**要 host、不能要 enforce**）。

**修法**：闸门改为 `story.dimension().enforced()`（= `enforce && host` 存在），`inside` 判定改用
`story.dimension().host()`。并新增守卫断言把这条钉死：
`'★ 离开就清的闸门是 dimension.enforced()（enforce && host），不是"有 host 就清"（D4）'`。

📌 **教训（值得记住）**：**拆字段时必须同时重接"读它的那段行为"** ——
只改 schema 不改判断点，症状是"配置写了但不起作用"，而且**编译与静态守卫都不报错**。
本轮守卫只查了"字段存在/对齐"，没查"字段被读" ⇒ 现在补上了。

## 二、Important（3，已修）

| # | 问题 | 修法 |
|---|---|---|
| I2 | **T9 的 fail-closed 未实现**：进度 id 缺失只打 ERROR，剧情照常注册、照常生成分身 | 新增 `storiesWithMissingIds`；自检时把这类剧情记进去，**事件路径直接跳过**（不生成分身），ERROR 文案也改成"this story is disabled … until the data pack is fixed" |
| I3 | **`cg` 名错误的报错时机/等级不符 D9**：只有生成那一刻的 WARN，无加载期校验、无已注册名录 | 在**加载期**按 `CgRegistry.names()` 校验 ⇒ ERROR 点名"哪个文件、写的什么名字、已注册的有哪些"，但**不拒绝**该文件（D12：坏 CG 数据不毁剧情）。生成时的 WARN 保留为双保险 |
| I4 | `reconcileStory` 两处注释仍写旧字段名 `required_dimension`（同文件其余处已用 `dimension.host`）| 两处一并改名 |

## 三、Minor（2，已修）

| # | 问题 | 修法 |
|---|---|---|
| M5 | `checkStoryIdsOnce` 被 `if (!Config.NpcStory.enabled) return;` 挡在后面 ⇒ 总开关关着时自检永不执行 | 把自检移到 enabled 闸门**之前**（它是数据诊断，不是玩法逻辑） |
| M6 | `Dimension#warnBeforeTicks` 的 javadoc 称"缺省 `DEFAULT_WARN_BEFORE_TICKS`"，而 codec 里是 `Optional`（无缺省），真正兜底在 handler ⇒ 与 `Lease` 同字段的注释口径不一致 | 改成与 `Lease` 一致的口径：**缺省值在消费端**，codec 刻意不带缺省 |

## 四、与设计的对账（审查结论）

- **符合**：D1（离线只清不撤，全 src 无第二处 `discard`）· D6（`keep_after_finish: true` 整条跳过，带理由注释）·
  D7/D8（未知键递归点名、四条旧写法迁移提示、取值越界与"enforce 无 host"均拒绝）· D10（键存在性不可校验已写进注释）·
  D11（`remind` 的兜底与 `0 = 不提醒`）· D13（`bindRegistryAccess` 在 `addListener` 之前）· D14（自带数据已迁移）
- **另有静态初始化的独立核对**：四个缺省常量都在 `CODEC` 之前，`Lease.DEFAULT` / `Dimension.DEFAULT`
  取值与常量一致 ⇒ **无"读到 0"的风险**
- **在线且没有分身的玩家仍被巡检**（第三个循环补 `mine.isEmpty()`）⇒ D21 重置不受影响
- 离线路径遍历前确实 `List.copyOf(byOwner.keySet())` 快照；一个 owner 多只分身各判各删；幂等由下一轮保证

## 五、一处基线说明

审查报告末尾提到"批次③ T12（整合包迁移）未做" —— 那是**基线过期**：审查读的是 `693da47` 的快照，
而整合包数据迁移与换 jar 是在其后的 `d070614` 与随后的部署里完成的（两份数据现在都是新结构）。
若有下次，宜在派审查时**先固定 HEAD 并告知**，避免这类"审查时点 vs 修复时点"的错位。

---

**结论**：1 Critical + 3 Important + 2 Minor **全部修完**；修复后 `gradlew build` 成功，
stage（⑰ 31 条）/ route / cg 三套不变量与三个探针全绿。
