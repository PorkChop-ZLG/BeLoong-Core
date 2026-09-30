# 原版进度 NPC 阶段系统 设计文档

**日期**：2026-09-29
**状态**：**已实施**（代码 + 数据均已落地；2026-09-30 按实机反馈把"发放"改挂到 ChatBox 的选项 `click` 上（§3.3 修复记录），并按当日的代码审查补齐了注释措辞与主题的功能按钮（见本节末的两处补充））
**选定方案**：**方案 A** —— 服务端过滤可见回复（带数据下标）下发，点击时复检

---

## 一、问题陈述

NPC 对话已经能与 ChatBox 联动（见 `2026-09-29-npc-dialogue-chatbox-bridge-design.md`），
但**谁看过哪段对话**这件事没有存储：任何玩家右键末，都会看到同一条回复选项。

**目标**：用**原版进度系统**（advancement）当阶段状态的唯一存储，让"玩家走到哪一步"变成**数据驱动**的：
数据里给每条回复声明"开始进度"与"结束进度"，据此决定这条回复是否显示。

**给谁用**：整合包作者 —— 阶段与内容全部写在数据文件里；本模组不新增存档字段、不新增网络状态、
不新增 UI（进度界面就是原版那个）。

**用户给定的第一阶段内容**（末的对话）：
`/advancement grant @s only beloong:npc/root` ⇒ 右键末 ⇒ 出现「这里是什么地方？」
⇒ 点它 ⇒ 走完 ChatBox 那段对话（两页）⇒ 拿到 `1_1`（**无进度通知**，但进度界面里看得到）
⇒ 再右键末 ⇒ **只剩「离开」**。

---

## 二、已核实的既有事实（全部附行号）

### 2.1 ChatBox 侧（1.1.5，与本项目依赖的 jar 同版本号）

| 事实 | 依据 |
|---|---|
| **没有"整段对话被关闭"的钩子** | `ChatBoxUtil.closeDialogBox()`（`ChatBoxUtil.java:209-224`）与 `ChatBoxScreen.onClose()`（`:475`）**不 fire 任何事件**；关闭只发 index `-1` 的包，服务端在 `SimplePayload.java:163` 用 `index != -1` 直接跳过 |
| 能做"结束感"的只有三个时刻 | ① `Dialogues.command` = **进入该页**时（`SimplePayload.java:162-165`）② 页级 `renderEvents` + `trigger:"end"` = **该页打字机播完**（`DialogBox.java:79-90,126-128`）③ 选项 `click` = 点击那一刻（且要求本页文字已播完，`ChatBoxScreen.java:379`） |
| 命令的**执行上下文**：服务端、**以玩家本人身份**、权限等级 2、**不需要 op** | `ComponentEvent.java:151,153`（`entity.createCommandSourceStack().withPermission(Commands.LEVEL_GAMEMASTERS)`）；`;` 分隔多条（`:134-140`） |
| 命令失败**不打断对话**，只记一条 ERROR | `ComponentEvent.java:156-162` |
| 可选 MVEL `condition`（`player.hasAdvancement(...)` 在白名单里） | `MVELUtil.java:84,349-363` |
| `criteria` 是**它自己的入口条件**（用原版判据决定"这段对话能不能开始"） | `ChatBoxDialoguesLoader.java:42,68-92,100`（且只绑给 json 的**第一组**） |
| ⚠️ 对话 JSON 语法错会让**整个 reload listener** 挂掉（该处无 try/catch） | `ChatBoxDialoguesLoader.java:56` |

### 2.2 本项目侧

| 事实 | 依据 |
|---|---|
| **进度判定的既有先例**（直接照抄） | `player.server.getAdvancements().get(rl)` → null 检查 → `player.getAdvancements().getOrStartProgress(holder).isDone()`（`StructureEffectHandler.java:85-92`） |
| `advancement/npc/root.json` **已存在**（996 B），是 `npc/` 这棵树的根，带完整 `display`（图标 `beloong:beloong_logo`、背景、标题键） | 该文件全文 |
| 它现在的触发器是 `dragonsurvival:be_dragon`（"已成龙"）；**除它自己外无人引用它** ⇒ 改触发器影响面极小 | 全库搜 `npc/root` 只命中 `root.json` 自身两行 |
| ⚠️ 它的 `title`/`description` 键（`beloong.advancement.npc/root[.desc]`）在**两种语言里都不存在** ⇒ 现在进度界面显示的是**原始键名** | `zh_cn.json`/`en_us.json` 搜 `advancement` = 0 命中 |
| `impossible` 的 1.21.1 写法（`criteria: {"<名>": {"trigger": "minecraft:impossible"}}`，可带 `parent`/`display`） | 参考模组 `irons_spellbooks:.../steal_from_wizard.json` |
| 项目**有**自定义判据机制（`ModCriteria`，如 `beloong:claw_sword_swap`），但本设计**不用**它 | `ModCriteria.java:16-30`；理由见决策 D3 |
| 本项目目前**没有**任何 `award` 调用 | 全库搜 `AdvancementHolder` 只有读取路径 |
| 回复链路现状：`Reply(text, chatbox, group, index?)`；载荷带 `List<String> replyTextKeys`；点击回传 `(entityId, replyIndex)` | `NpcDialogueEntry.java`、`NpcDialogueOpenPayload.java`、`NpcDialogueReplyPayload.java` |

---

## 三、设计

### 3.1 架构（§1）

**一句话**：**原版进度是唯一的阶段存储**；我们的 NPC 对话系统退化成**只读闸门**；ChatBox 是**唯一写入方**。

| 层 | 职责 |
|---|---|
| **原版进度**（`advancement/npc/`） | 阶段状态的唯一存储 —— 落盘、跨维度、跨死亡、跨重登都由原版负责；本模组不新增任何状态字段 |
| **我们的对话系统** | **只读闸门**：右键时算出哪些回复可见（带数据下标）下发；点击时再判一次 |
| **ChatBox** | **唯一写入方**：末那段对话的最后一页，文字播完时执行 `advancement grant @s only beloong:npc/1_1` |

**四条承重决定**

- **D1 可见性完全在服务端判定**（方案 A）⇒ 客户端不持有进度 ID，无法伪造或过期推断。
- **D2 回传的是"数据下标"**（`replies[]` 里的原始下标），不是"可见列表下标" —— 玩家在对话打开期间
  若状态变化，可见列表会缩水，用可见下标会**点错回复**；带数据下标则由服务端按原表复检。
- **D3 本模组只读不写进度**（用户要求 6）⇒ 我们这边**没有**任何 `award` 调用；
  结束进度只能由 ChatBox 的命令发放。这也让"谁负责哪一半"在代码里一目了然。
- **D4 两个字段都可省略，省略 = 无该约束** ⇒ 两个都省略 ⇔ 现状（永远显示）⇒ 老数据行为一字不变。

**阶段约定（用户确认）**：每个阶段是一对进度，且**首尾相接** —— 某段对话的 `end_advancement`
就是下一段对话的 `start_advancement`（`root → 1_1 → 2_1 → …`）。
⚠️ 进度树的 `parent` 链只是**进度界面的组织**，**不提供运行期保证**（用命令单独授予子进度时父进度不会被自动授予）
⇒ 所以必须真的查两个字段，不能只靠树（这是被否决的方案 C 的核心理由）。

### 3.2 组件（§2）

| # | 文件 | 改动 |
|---|---|---|
| C1 | `dialogue/NpcDialogueEntry` | `Reply` += `Optional<ResourceLocation> startAdvancement, endAdvancement`（JSON 键 `start_advancement` / `end_advancement`）+ 类注释 |
| C2 | `dialogue/NpcDialogueStage`（新） | **可见性规则的唯一实现** + 进度查询 + 未知 id 的"每 id 一次"英文 WARN |
| C3 | `dialogue/NpcDialogueOpenPayload` | `List<String> replyTextKeys` → `List<VisibleReply>`；新增嵌套 `record VisibleReply(String text, int index)` + 全函数 StreamCodec |
| C4 | `dialogue/NpcDialogueHandler` | 生产端改用 `NpcDialogueStage.visibleReplies(...)` |
| C5 | `dialogue/NpcDialogueReplyPayload` | `handleServer` 复检：下标在范围内 + 该回复**当前仍可见** + ChatBox 目标可开 |
| C6 | `client/NpcDialogueScreen` | 按可见列表渲染；点击**回传数据下标** |
| C7 | 数据 ×5 | `advancement/npc/root.json`（触发器 → `impossible`，**其余 display 一字不动**）、`advancement/npc/1_1.json`（新）、`npc_dialogue/mo.json`（+2 字段）、`chatbox/dialogues/mo.json`（最后一页 + **一个「好的」选项**）、`chatbox/theme/minimal.json`（**补 `@Options` 显示事件**） | 见 §3；发放改挂选项 `click` 的原因见 §3.3 的 📌 |
| C8 | 语言键 ×2 语言 | `beloong.advancement.npc/root`、`.desc`（**本来就缺** ⇒ 顺带补）、`beloong.advancement.npc/1_1`、`.desc` ⇒ 241 → **245** |
| C9 | 注释与文档 | `Reply` 记录注释、主设计文档 §5.2/§5.8、本设计文档、memory |

**可见性规则（C2 的全部内容，一行）**

```
可见 ⇔ (start 省略 或 已完成 start) 且 (end 省略 或 未完成 end)
```

- **未知的进度 id ⇒ 该回复一律不可见** + WARN（fail-closed）。理由：`end` 若按 fail-open 处理，
  会**提前显示**本该隐藏的选项。
- 真值表核对：都省略 ⇒ 恒显示（现状）；只有 start ⇒ 已完成 start；只有 end ⇒ 未完成 end；
  都有 ⇒ 已完成 start 且 未完成 end —— **与用户要求 4/5 逐条吻合**。

**`1_1.json` 的形状**

```json
{
  "parent": "beloong:npc/root",
  "criteria": { "1_1": { "trigger": "minecraft:impossible" } },
  "display": {
    "icon": { "count": 1, "id": "minecraft:ender_pearl" },
    "title": { "translate": "beloong.advancement.npc/1_1" },
    "description": { "translate": "beloong.advancement.npc/1_1.desc" },
    "frame": "task",
    "show_toast": false,
    "announce_to_chat": false,
    "hidden": false
  }
}
```

（`show_toast:false` + `announce_to_chat:false` = 用户要求 8 的"没有进度通知"；`hidden:false` = "进度界面里能看到"）

**ChatBox 最后一页的「好的」选项**（放在 `dialogues.start` 数组的**最后一个元素**内，与 `dialogBox` 同级）

```json
"options": [
  {
    "text": "beloong.chatbox.mo.option_ok",
    "next": "-1",
    "click": { "type": "command", "value": "advancement grant @s only beloong:npc/1_1" }
  }
]
```

并且**主题**里必须补上让选项显示的事件（否则按钮既看不见也点不动，原因见 §3.3 的 📌）：

```json
"dialogBox": {
  "renderEvents": [ { "trigger": "end", "type": "show", "value": "@Options" } ]
}
```

⚠️ **两套系统的命名空间不混用**：这颗按钮属于 **ChatBox 侧**，它的键叫
`beloong.chatbox.mo.option_ok`（`好的` / `OK`）—— **不**复用我们 NPC 对话系统的
`beloong.dialogue.*`（那是「离开」「回复」那套的命名空间），也**不**出现在我们的界面上。
[0] 玩家执行 /advancement grant @s only beloong:npc/root

[1] 右键末 → 服务端 NpcDialogueHandler
      → NpcDialogueStage.visibleReplies(player, entry)
          replies[0]：start=beloong:npc/root（✓已完成）、end=beloong:npc/1_1（✗未完成）⇒ 可见，数据下标 0
      → S2C NpcDialogueOpenPayload{ pages(1 页), entityId, replies=[VisibleReply(text=…reply1, index=0)] }

[2] 客户端：那一页播完 → showOptions() → 最下「离开」，其上方「这里是什么地方？」

[3] 点击 → setScreen(null)（与「离开」等价）→ C2S NpcDialogueReplyPayload{entityId, replyIndex=0}

[4] 服务端复检：实体 → 对话表 → replies[0] → 仍然可见 ✓ → ChatBoxBridge.canOpen(beloong:mo, start, 0) ✓
      → serverSkipDialogues(...)  ⇒ ChatBox 界面打开

      → 第二页打字机播完 ⇒ 主题的**组件级** ON_END ⇒ 弹出「好的」按钮
      → 玩家点「好的」⇒ ① 选项的 next:"-1" 关闭对话
                        ② 选项 click 的 command 在服务端以玩家本人身份执行
                           advancement grant @s only beloong:npc/1_1

[6] 玩家关掉，再右键末 ⇒ 服务端过滤：end=1_1 已完成 ⇒ 该回复不可见
      ⇒ 载荷 replies=[] ⇒ 界面只剩「离开」
```

**📌 修复记录（2026-09-30 实机发现，用户选定方案）**

本节最初写的是"把命令放进**最后一页的页级 `renderEvents`**，`trigger:"end"`"。**实机没有发放** ✗
读源码后确认是**挂错了层**：

- 页级 `renderEvents` 被绑给 **`ChatBoxScreen`**（`ChatBoxUtil.java:184` → `ChatBoxScreen.java:190`），
  而该屏幕只 fire `ON_START`（`:279`）、`ON_CLICK`（`:387`）、`TICK`（`:481`）、`CHECK`（`:484`）
  —— **从不 fire `ON_END`**；
- `ON_END` 只由**组件**触发：`DialogBox.setAllOver` → `fireEvent("ON_END")`（`DialogBox.java:82-83`），
  而组件的 `fireEvent` 只查**该组件自己**的事件表（`AbstractComponent.java:98-102`）。

⇒ 页级那条命令**一次都没执行过**，且 Gson 不报错、ChatBox 也不打日志 —— 症状正是"悄悄什么都没发生"。
（同一个 `trigger:"end"` 挂在**主题的 `dialogBox` 组件**上就有效；ChatBox 自带主题的
`end → show @Options` 正是这么用的。）

**改为**：命令挂到**最后一页的一个选项**的 `click` 上（文本「好的」/ OK），
并给主题的 `dialogBox` 补 `@Options` 显示事件 —— 选项默认 `hidden`，而 `ChatOption.click()`
对 hidden 直接 `return false`（`ChatOption.java:101`）⇒ 不 `show` 就既看不见也点不动。
选项的 `next:"-1"` 负责关闭（`ComponentEvent.java:93-95` → `ChatBoxUtil.java:202` 的 `else closeDialogBox()`）；
`fireAll` 遍历全部匹配事件、不提前退出（`CompEvtWrapper.java:60-65`）⇒ 关闭与发放**都会执行**。
⇒ 发放时刻从"文字播完"变成"**文字播完且玩家点了「好的」**"，这其实更贴近要求 3 的"结束对话后"。

**另一处取舍**：`1_1` 的图标用 `minecraft:ender_pearl`（末/龙宫主题）；
`root` 保持它原有的 `beloong:beloong_logo`。

**📌 审查后的两处补充（2026-09-30）**

1. **主题已补 `functionalButton`**（`log` / `fastForward` / `autoPlay` 三个）：缺了它 ChatBox 会清空功能按钮，
   而且 `ChatBoxScreen.java:509` 的 **Ctrl 快进要求 `fastForwardButton() != null`** ⇒ 连键盘快进也失效。
   （点击仍可补全本页文字，所以那不是"卡死"，是少了一整排按钮。）
2. **⚠️ 已知残余风险（用户决定先只留痕、不加固）**：发放用的那条命令**不以 `execute` 开头**，而 ChatBox 的
   `ComponentEvent.executeCommand` 有这么一条分支：

   ```java
   if (ChatBox.pluginHelper != null && !command.startsWith("execute"))
       return ChatBox.pluginHelper.executeCommand(player.getUUID(), command);   // ComponentEvent.java:145-146
   ```

   ⇒ 一旦某环境里 `pluginHelper` 非 null（本项目的运行日志里确实加载了 KubeJS 的 chatbox 插件），
   命令会被**整个交给插件**、原版 dispatcher 不再执行 ⇒ **静默不发**（选项照常关闭、`1_1` 永不发放、回复永不消失）。
   **症状**：ChatBox 日志里**没有** `Error executing command on server`（因为压根没走 dispatcher），
   而验收清单第 4b 步永远不通过。**一行修法**：把命令改成
   `execute as @s run advancement grant @s only beloong:npc/1_1`（以 `execute` 开头即绕开该分支）。
   本模组侧无法感知这一失败（我们**只查询完成状态**，不发放也不撤销）。
   不变量脚本 `stage_invariants.py` 已加一条**非致命提示**，命令没以 `execute` 开头时会打印出来。


---

## 四、错误处理（§4）

| 情况 | 行为 | 理由 |
|---|---|---|
| 进度 id 写错 / 该进度不存在 | 该回复**一律不可见** + 英文 WARN（每 id 一次） | fail-closed（见上）；每 id 一次避免刷屏（同 `ChatBoxBridge`/`EmoteAnimationLookup` 先例） |
| 进度存在但未完成 | 正常语义 | 要求 5 |
| 点击时该回复已变为不可见 | 静默丢弃 | 与既有回复链路同一策略 |
| 点击时实体/对话表/下标失效 | 静默丢弃 | 既有行为不变 |
| ChatBox 目标不存在 | 既有 `ChatBoxBridge.canOpen`：WARN 每目标一次 + 什么都不发生 | 上一轮既有 |
| 回复可见但 ChatBox 那段对话缺失 | 选项照常显示，点击后无事 + 一条 ChatBox WARN | 闸门只管阶段，不管资产是否存在（单一职责） |
| 玩家在 ChatBox 里看到一半就 ESC | **不发** `1_1`（没点「好的」） | 语义正确，而且**可以重试**：`1_1` 未完成 ⇒ 回复选项仍可见 ✓ |
| 最后一页**文字还没播完**就点「好的」 | 无反应 | 选项可点要求本页文字已播完（`ChatBoxScreen.java:379` 的 `dialogBox.isAllOver`）✓ |
| 玩家用 `/chatbox skip` 直接播那段对话 | 照常发放 `1_1` | 不是漏洞：`impossible` 本就只有服务端能发；且它是数据作者的调试工具 |
| 进度 `display.hidden = true` | 与普通进度同样判定 | 只看 `isDone()`，不看 `display` |
| `advancement/` 下与本系统无关的文件 | 无影响 | 只按数据里写的 id 查 |
| 老数据（两个字段都没有） | 行为一字不变 | D4 |

---

## 五、验证策略（§5）

**静态**
1. `gradlew build` ⇒ BUILD SUCCESSFUL；
2. 既有三探针全绿；
3. **数据不变量脚本**（比肉眼看 JSON 硬）：
   - 两份进度 JSON 可解析；`root` 的 trigger == `minecraft:impossible`；`1_1.parent == beloong:npc/root`；
     `1_1` 的 `show_toast`/`announce_to_chat` 均为 false；
   - `mo.json` 里 `replies[*]` 的两个进度 id **都指向真实存在的进度文件**；
   - ChatBox 那份 JSON 的**最后一页**确实带 `renderEvents`，且 `value` 里的进度 id 与 `mo.json` 的
     `end_advancement` **一致**（防两边写岔）；
   - 语言键集合两语言一致，且两个进度的 `title`/`desc` 四个键都在。

**实机清单（用户执行）**

| # | 操作 | 期望 |
|---|---|---|
| 1 | `/advancement grant @s only beloong:npc/root` | 进度界面出现 `root`（其 `show_toast` 仍为 true ⇒ 有提示；用户未要求改，故未动） |
| 2 | 右键末 → 点击台词 | 两颗按钮：上「这里是什么地方？」下「离开」 |
| 3 | 点「这里是什么地方？」 | 我方界面关闭 → ChatBox「这里是龙宫。」→ 点击 →「走，我带你去觐见龙王。」 |
| 4 | 第二页文字播完 | 弹出**「好的」按钮**（文字没播完时点它无反应） |
| 4b | 点「好的」 | ChatBox 关闭 ⇒ 进度界面出现 `1_1`，**无进度通知** |
| 5 | 关掉后再右键末 | **只剩「离开」** |
| 6 | 反例 A：先 revoke `root`（`1_1` 仍在）再右键 | 仍只剩「离开」⇒ 证明**结束优先于开始** |
| 7 | 反例 B：新存档、不给 `root`，直接右键 | 只剩「离开」 |
| 8 | 反例 C：第二页看到一半 ESC（**没点「好的」**） | 不发 `1_1`；再右键回复选项仍在（可重试） |
| 9 | 右键铁傀儡（无 `replies` 的旧数据） | 行为与从前完全一致 |
| 10 | 故意把 `end_advancement` 改成不存在的 id 再右键 | 该回复不显示 + 日志**一条**英文 WARN（只报一次） |

**不做**：不新增测试源集（本项目无 `test`；验证 = 构建 + 探针 + 不变量脚本 + 实机）。

---

## 六、非目标

- **本模组发放或撤销进度**（用户要求 6）—— 撤销用原版 `/advancement revoke`；
- **阶段的可视化 UI** —— 直接用原版进度界面（`root`/`1_1` 的 `display` 就是它的呈现）；
- **用 `criteria` 让 ChatBox 对话"拿到 root 时自动开始"** —— 机制现成（`ChatBoxDialoguesLoader.java:68-92`），
  但本轮不做：用户要的是"回复选项可控地点进去"；
- 分支/多结局、一次对话多个 start/end 组合语义；
- 阶段的重置/清档（原版指令已够）。

---

## 七、下一步

调用 `planning` 技能，把本设计拆成可执行任务清单（含静态验证命令与实机验收清单）。

**📌 2026-10-01 变更（要求 1）**：`beloong:npc/root` 的**入口**不再是"用指令发放"，改为
**原版 `minecraft:location` 触发** —— 玩家在 `beloong:loong_palace` 里走进以路线起点 `(0,64,-8)`
为中心、**水平与竖直各 ±7** 的盒子即自动获得（并关掉 `show_toast`/`announce_to_chat`）。
（"距末 7 格"在数据层只能写成"以末的固定站位为心的方盒" —— 原版谓词无法引用实体，理由见路线设计文档 §六。）
`1_1` 及后续阶段仍由 ChatBox 在对话结束时发放，**不变**。
⇒ 本文件的判据是"**本模组只读不写进度**"：入口由原版触发器给，闸门由 ChatBox 给，我们只查询 —— 这一点没变。
