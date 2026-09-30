# NPC 阶段闸门（原版进度）代码审查报告

**审查基线**：`origin/NPC..HEAD`（12 个提交）
**日期**：2026-09-30
**审查范围**：**只审 `src/` 里的代码与资源**（用户裁定）；智能体自己写的工具/脚本不在范围内
**方法**：两路子代理（① Java 逻辑 ② 数据与资源，均只读）+ 主审逐条核验引用行号
**结论**：**Critical 无**；**5 Important**（全部是"注释/措辞与实际不符"或"UX 取舍的后果"，**没有一条是逻辑缺陷**）；
**5 Minor**；另 1 条**不在本次范围**的既有问题

> **本文只记录审查结果，不含修复。** 编号：`I-` = Important，`M-` = Minor，`X-` = 不在本次范围。
> 修复的讨论见本文件末尾的"§修复讨论"一节（只讨论，未改代码）。

---

## 一、Important（5）

**I-1｜发放时刻的注释已过期。** 注释仍写"结束进度由 ChatBox 在**对话最后一页文字播完时**发放"，
而实机修复（`45c3f5f`）已把它改成**第 2 页「好的」选项的 `click`**。
位置：`src/main/java/com/zonlong/beloong/dialogue/NpcDialogueEntry.java:140`。

**I-2｜"零校验零日志"言过其实。** 该说法只对**服务端 util**（`ChatBoxCommandUtil.java:65-69`）成立；
客户端 `ChatBoxUtil.skipDialogues` 对**缺组/空组**会打 `group "{}" not found or is empty!`，页号也有范围判定。
位置：`src/main/java/com/zonlong/beloong/compat/chatbox/ChatBoxBridge.java:47`，
同句亦见 `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueEntry.java:121`。

**I-3｜三态概括有歧义。** "都没有 ⇒ 未开始 / 两者都有 ⇒ 已完成（选项消失）"是用**数据字段**在说话，
却读起来像在描述**玩家进度**（规则本身在上一行是正确的）。
位置：`src/main/java/com/zonlong/beloong/dialogue/NpcDialogueStage.java:19-20`。

**I-4｜主题缺 `functionalButton` ⇒ 少一整排按钮。** 该对话界面没有快进/自动/历史按钮，
**且 Ctrl 快进也失效**（`ChatBoxScreen.java:509` 要求 `fastForwardButton() != null`）。
不是卡死：**点击仍可补全本页文字**（ChatBox 既有手感）。
位置：`src/main/resources/data/beloong/chatbox/theme/minimal.json`。

**I-5｜进度的唯一写入点是一条服务端指令。** 该指令以权限等级 2 执行
（`createCommandSourceStack().withPermission(LEVEL_GAMEMASTERS)`）⇒ 若整合包限制命令执行，
会**静默失效**（选项照常关闭、`1_1` 不发、回复永不消失）。好在命令失败会在 ChatBox 日志留一条 ERROR，**可诊断**。
位置：`src/main/resources/data/beloong/chatbox/dialogues/mo.json:24`。

---

## 二、Minor（5）

**M-1｜"只读判定"字面不成立。** `getOrStartProgress(...)` 对**尚未被追踪**的进度会顺手登记一条记录（微小写入）
⇒ 宜改为"**不发放、不撤销**"。位置：`NpcDialogueStage.java:15`（`NpcDialogueEntry.java:140` 同源）。

**M-2｜"同一先例"只对了一半。** 先例 `EmoteAnimationLookup:66` 用 `ConcurrentHashMap.newKeySet()`，
而两个新 `WARNED` 是普通 `HashSet`（服务端主线程单线程访问，**功能上够用**，只是措辞不准）。
位置：`NpcDialogueStage.java:28`、`ChatBoxBridge.java:51`。

**M-3｜`index` 同名不同义。** `VisibleReply.index` 是**数据下标**、`Reply.index` 是 **ChatBox 页号**
（四处用法已核对一致、无混用可见列表下标 ✓）。位置：`NpcDialogueOpenPayload.java:62`。

**M-4｜两个 `WARNED` 集合只增不清。** 规模极小（按坏 id / 坏目标计），同先例，仅记录。

**M-5｜数据侧两条提醒。** ① 新语言键插在文件中部的非排序位（两语言 **246/246、无重复、无单边键** ✓）；
② `root.json` 本次是**新增文件**（此前从未入库），所以 diff 里看不到文档所述"触发器被改成 `impossible`"这一"改动"。

---

## 三、不在本次范围（顺带发现）

**X-1｜渐变注释与常量不符。** `NpcDialogueScreen.java:328` 写"渐变从 **0.72** 屏高开始"，
而常量 `GRADIENT_START = **0.62**`。该行**不是这 12 个提交改的**，属既有问题。

---

## 四、已核对通过（不展开）

- 闸门**真值表与规则完全一致**；`UNKNOWN` 与 `NOT_DONE` 确实分离；点击端 `visible` 复检有效；
  两侧省略 ⇒ 恒显示（**向后兼容** ✓）。
- `VisibleReply` / `Page` 的元素 codec 与 `ByteBufCodecs.list()` **无抛分支**，"全函数永不抛"成立 ✓。
- `ChatBoxBridge` 用的是 ChatBox 自己的 `parsedDialogues` / `dialoguesGroupMap`（public，服务端主线程读写同线程），
  **不会在服务端触发客户端类加载** ✓。
- 注释引用的 **5 处 ChatBox 行号**（`ChatBoxCommand:37/201-203`、`ChatBoxServerEvents:94`、
  `ChatBoxDialoguesLoader:85`、`SimplePayload:164`、`ChatBoxCommandUtil:65-69`）在 1.1.5 参考源中**全部命中** ✓。
- 7 份 JSON 全部可解析、无 BOM、尾换行齐全；`criteria` 键名与 `requirements` 一致；
  `root` 有 `background` 而 `1_1` 没有（符合原版要求）✓。
- ChatBox 命令里的进度 id 与 `end_advancement` **逐字一致**；16 处被引用语言键两语言全命中 ✓。
- `src` 里已无旧字段 `replyTextKeys` 残留；`NpcDialogueStage` 的两个公开方法都被真实调用 ✓。

**一条设计说明（不是缺陷）**：`root` 的触发器是 `impossible` 且**全库没有发放点** ⇒ 按用户要求 8，
它是"**用指令获得**"的起点 ✓ 所以在那之前，末的回复选项**不会出现** —— 这是刻意的阶段门。

---

## 五、修复讨论

（见提交本文件的同一次对话；**只讨论，未改任何代码**。）
