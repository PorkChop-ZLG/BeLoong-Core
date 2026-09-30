# NPC 对话系统 ↔ ChatBox 联动 设计文档

**日期**：2026-09-29
**状态**：**已实施**（代码与数据均已落地；**实机验收待用户执行**）
**选定方案**：**方案 A** —— 新增一个 C2S「回复」包，服务端查表后调用 ChatBox 的公开 API

---

## 一、问题陈述

本模组已有一套自研的**极简 NPC 对话系统**（服务端权威、`data/beloong/beloong/npc_dialogue/*.json` 一维页列表、
右键触发、客户端只渲染），以及一段用 **ChatBox 1.1.5** 做的对话（`data/beloong/chatbox/dialogues/`）。
两者目前**互不相通**：玩家看完 NPC 的台词后，只能点「离开」结束，无法从这里进入 ChatBox 那段更"视觉小说"的演出。

**目标**：让 NPC 对话播放完毕后，在「离开」**上方**额外出现**数据驱动**的「回复」选项；玩家点击后
关闭 NPC 对话界面（与点「离开」等价）并**立刻**进入 ChatBox 的指定对话。

**给谁用**：整合包作者 —— 内容（回复文案、跳到哪段 ChatBox 对话）全部写在数据文件里，不改代码。

**用户给定的第一阶段内容**（末的对话）：
右键末 → 「我是「末」，末影龙族的长公主。」→ 点击 → 出现「这里是什么地方？」与「离开」
→ 点「这里是什么地方？」→ 我方界面关闭 → ChatBox 两页：「这里是龙宫。」→「走，我带你去觐见龙王。」→ 结束。

---

## 二、已核实的既有事实（全部来自源码，附行号）

### 2.1 ChatBox 侧（版本 1.1.5，与本项目依赖的 jar 同版本号）

| 事实 | 依据 |
|---|---|
| 有现成的**公开静态**入口：`ChatBoxCommandUtil.serverSkipDialogues(player, rl, group, index)` | 4 个重载，真正实现是 5 参那个（`ChatBoxCommandUtil.java:50/55/60/65`） |
| **不需要 op**、不受 `maxTriggerCount` 限制 | 权限 `hasPermission(2)`（`ChatBoxCommand.java:37`）与次数判据（`:201-203`）只在**命令**路径上，util 里没有 |
| **不需要队伍/组**，单人可触发 | `isPlayerLeader` 全模组仅 `ChatBoxServerEvents.java:91` 一处（作用是广播给队友）；`isPlayerInGroup`（`ChatBoxSavedData.java:53`）只被组管理自身用 |
| **它自己发包**，外部不必 `PacketDistributor` | `ChatBoxCommandUtil.java:65-69` 发 `ChatBoxPayload.SyncEntityData` + `SimplePayload("skip_chat_s2c")` |
| ChatBox 自己在服务端就这么调 | `ChatBoxServerEvents.java:94`（队内广播）、`ChatBoxDialoguesLoader.java:85`（criteria 自动触发） |
| 硬约束：**服务端主线程** + `player` 非 null | 内部走 `player.connection.send`（`:36/:92`），无判空 |
| **失败时零校验、零异常、零日志** | `ChatBoxCommandUtil.java:65-69` |
| ⚠️ `index` **绝不可传 null** | 会被编码成字符串 `"null"` ⇒ 客户端 `Integer.parseInt("null")` 抛异常（`SimplePayload.java:106/131`） |
| ⚠️ 成功后客户端回执 `SKIP_CHAT_C2S`，服务端取组/页**无判空无越界检查** | `SimplePayload.java:164` |
| 可用的**预检**公开字段 | `ChatBoxDialoguesLoader.parsedDialogues`（`:36`）、`dialoguesMap`（`:34`）、`dialoguesGroupMap`（`:38`） |

### 2.2 本项目侧

| 事实 | 依据 |
|---|---|
| 数据模型是 record `(entity, Trigger, name?, pages[])`，`Page(text, sound?)` | `NpcDialogueEntry.java:41-46,71` |
| `NpcDialogueOpenPayload` 有一条**硬不变量**：线格式**全函数、永不抛**（解码抛异常会中止连接） | 类注释 `NpcDialogueOpenPayload.java:23-34`；`Page.SOUND_STREAM_CODEC` 就是为它写的 `tryParse` 特例（`NpcDialogueEntry.java:89-92`） |
| 「离开」目前是**硬编码的单个按钮** | `NpcDialogueScreen.java:93-94`（`LEAVE_KEY`）、`:234-243`（`showOptions`） |
| 选项按钮可直接复用 | `NpcDialogueOptionButton(int x, int y, int w, int h, Component label, Runnable onPress)`（`:54-55`） |
| 布局常量本就是"**最下一颗选项的底边，向上依次排**" | `NpcDialogueScreen.java:62-63,68-69`（`OPTION_BOTTOM`/`OPTION_HEIGHT`/`OPTION_GAP`） |
| 本项目**目前只有 S2C 包**，没有任何 C2S 包 | `BeLoongCore.java:151/155` 均为 `playToClient`；全仓库无 `sendToServer` |
| ChatBox 已是 `type="required"`/`side="BOTH"` 的必需依赖 | `src/main/templates/META-INF/neoforge.mods.toml`（`generateModMetadata` 的模板） |
| 已有 `compat/` 包惯例（按第三方模组分包） | `compat/{dragonsurvival,ftbchunks,ironsspellbooks,betterendisland}` |

---

## 三、设计

### 3.1 架构（§1）

**一句话**：两套系统仍然各自独立；唯一的耦合是「数据里声明的一条回复」+「服务端一次 ChatBox 公开 API 调用」。

| 层 | 职责 |
|---|---|
| **数据层** | 对话 JSON 新增 `replies[]`，每项 = 标签翻译键 + ChatBox 目标（RL + 组 + 可选页号） |
| **客户端** | 只负责**画**回复按钮；点击时回发 `(实体网络 id, 回复下标)` —— 它不知道也不需要知道目标是什么 |
| **服务端** | 唯一决策点：按 id 找实体 → 查自己的对话表 → 取出回复 → 用 ChatBox 自己的表预检 → 调 `serverSkipDialogues` |

**三条承重设计决定**

- **D1 客户端不携带目标**，只回发"实体 id + 下标" ⇒ 改过的客户端**无法**让服务端播放任意对话；
  且线载荷里只有 `List<String>`（标签键），天然满足 `NpcDialogueOpenPayload` 那条"全函数永不抛"的不变量，
  不必再为 `ResourceLocation` 写 `tryParse` 特例。
- **D2 预检必须用服务端自己的表** ⇒ 既避免 ChatBox 的"静默失败"，又顺带堵住它回执路径上那处
  **无判空取组取页**的服务端 NPE/OOB（`SimplePayload.java:164`）。
- **D3 零 mixin、零反射**：只用双方公开 API（本模组 payload 机制 + `ChatBoxCommandUtil.serverSkipDialogues`）。

### 3.2 组件（§2）

| # | 文件 | 改动 | 要点 |
|---|---|---|---|
| C1 | `dialogue/NpcDialogueEntry` | 新增 `record Reply(text, chatbox, group, index?)`；条目加 `List<Reply> replies`，`optionalFieldOf("replies", List.of())` | 缺省空表 ⇒ 老数据文件零影响 |
| C2 | `dialogue/NpcDialogueOpenPayload` | 加 `List<String> replyTextKeys` | 只发标签键，不发目标（D1）。<br>📌 **2026-09-30 更新**：阶段系统把该字段换成了 `List<VisibleReply>`（标签键 + **数据下标**），只发服务端判定为可见的项 —— 见 `2026-09-29-npc-advancement-stage-system-design.md` |
| C3 | `dialogue/NpcDialogueReplyPayload`（新） | `record(int entityId, int replyIndex)` + `handleServer` | 两个 `VAR_INT`，line codec 平凡全函数；**本项目第一个 C2S 包** |
| C4 | `compat/chatbox/ChatBoxBridge`（新） | `canOpen(rl, group, index)` / `open(player, rl, group, index)` + 每目标只报一次的英文 WARN | **全项目唯一** import ChatBox 的类；与 `compat/` 既有惯例一致 |
| C5 | `client/NpcDialogueScreen` | 构造参数 += `entityId`/`replyKeys`；`showOptions()` 自下而上排；回复点击 = `setScreen(null)` + 发 C2S | 复用 `NpcDialogueOptionButton`；布局常量本就支持向上堆 |
| C6 | `BeLoongCore` | `registerPayloads` 加一行 `.playToServer(...)` | 照 `:151/:155` 的写法 |
| C7 | 数据 ×2 | `npc_dialogue/mo.json` 加 `replies`；`chatbox/dialogues/mo.json` 的 `start` 组改成两页 | 用户裁定"改现有 start 组" |
| C8 | 语言键 ×2 语言 | +`beloong.dialogue.mo.reply1`、`beloong.chatbox.mo.p1`、`.p2`；删旧的 `beloong.chatbox.mo.text` **与**早先遗留的 `beloong.dialogue.mo.p2` | 净 +1 键（240 → **241**） |
| C9 | 注释与文档 | `LEAVE_KEY` 的"v1 只有这一个选项，硬编码"必须改；两个 record 的类注释补字段；本设计文档 + memory | 项目硬要求 |

**服务端处理器形状**（C3 + C4，约 40 行）：找实体 → 查回本模组对话表 → 下标越界就丢 →
`ChatBoxBridge.canOpen(...)` → `ChatBoxBridge.open(...)`。

### 3.3 数据流（§3）

```
[1] 右键末 → 服务端 NpcDialogueHandler（不改）
> 📌 **2026-09-30 更新**：下面的 `replyTextKeys` 已被阶段系统换成 `List<VisibleReply>`（标签键 + **数据下标**，且只含服务端判定为可见的项）—— 见 `2026-09-29-npc-advancement-stage-system-design.md`。

            → S2C: NpcDialogueOpenPayload{nameKey, fallbackNameKey, pages[], entityId, replyTextKeys[]}

[2] 客户端：打字机播完最后一页 → advanceOrFinish() → showOptions()
            自下而上：最下「离开」；其上方 replyTextKeys[0]（将来 [1]、[2]…）

[3] 玩家点「这里是什么地方？」（同一个 tick 内）
            → setScreen(null)                      ← 与「离开」完全等价
            → C2S: NpcDialogueReplyPayload{entityId, replyIndex=0}

[4] 服务端主线程：实体 → 类型 → 对话表 → replies[0]
            → ChatBoxBridge.canOpen(beloong:mo, "start", 0)     ← 预检（D2）
            → ChatBoxCommandUtil.serverSkipDialogues(player, beloong:mo, "start", 0)
              （它内部自己发 SyncEntityData + skip_chat_s2c ⇒ 我不发任何包）

[5] 客户端 ChatBox 界面打开
            「这里是龙宫。」→ 点击 →「走，我带你去觐见龙王。」→ 点击 → 结束
```

**时序安全性**：第 3 步与第 5 步之间隔着一次完整网络往返（≥1 tick）⇒ 我方 Screen 早已关闭，
ChatBox 的 Screen 是之后才被设置的 ⇒ 不会抢屏；ChatBox 客户端是静态单例、同时只持有一段对话，
而我们的触发是"玩家点一下才发生"，天然不会并发。

---

## 四、错误处理（§4）

| 情况 | 行为 | 理由 |
|---|---|---|
| 回复下标越界（伪造/陈旧包） | 静默忽略 | 良性；改过的客户端能触发的上限只是"另一个**已存在**的回复" |
| 实体已消失 / 未加载 | 静默忽略 | 玩家界面早已关闭 |
| 热重载后对话表里已无该条目 | 静默忽略 | 正常情况，非错误 |
| 数据指向的 ChatBox 对话/组/页号不存在 | **英文 WARN，每目标只报一次** + 什么都不发生 | ChatBox 零校验零日志 ⇒ 不报就无迹可寻；只报一次避免连点刷屏（同 `EmoteAnimationLookup` 先例） |
| 页号越界 | 由 `canOpen` 一并挡掉 | 顺带保护 ChatBox 回执路径（`SimplePayload.java:164`） |
| ChatBox 未安装 | 不适用（`type="required"`） | 隔离成 `ChatBoxBridge` 是为"让耦合只存在于一个文件"，不是容错 |
| 旧对话没有 `replies` | 行为完全不变（只有「离开」） | 向后兼容 |
| 玩家在 ChatBox 界面里又右键 NPC | 不会发生 | 我们给 ChatBox 对话写死 `isScreen: true`，Screen 模式吞掉世界输入 |

---

## 五、验证策略（§5）

**静态（实现者可跑）**
1. `gradlew build` —— **对"ChatBox 的 jar 与参考源码是否一致"的最硬检验**：签名/类名不符会直接编译失败；
2. 现有三个探针全绿（表情/攻击那套的 S1/S2/S3）；
3. 新脚本对账：两份 JSON 结构 + **两语言键集合一致**（240/240 → 242/242）+ 数据文件引用的每个键都存在。

**实机清单（用户跑）**

| # | 操作 | 期望 |
|---|---|---|
| 1 | 右键末 | 「我是「末」，末影龙族的长公主。」→ 点击 → 出现**两颗**按钮：上「这里是什么地方？」、下「离开」 |
| 2 | 点「离开」 | 只关界面，**不**弹 ChatBox |
| 3 | 再右键 → 点「这里是什么地方？」 | 我方界面关闭 → **立刻**弹 ChatBox：「这里是龙宫。」→ 点击 →「走，我带你去觐见龙王。」→ 点击 → 结束 |
| 4 | 手持任意物品右键末 | 仍能触发（`trigger: any`） |
| 5 | 右键铁傀儡（无 `replies` 的旧对话） | 只有「离开」，行为与从前完全一致 |
| 6 | 临时把 `chatbox` 改成不存在的 RL 再点回复 | 安静无事 + 日志**一条**英文 WARN（连点多次只报一次） |

**不做**：不新增测试源集（本项目无 `test`；验证 = 构建 + 探针 + 实机三件套）。

---

## 六、非目标（本轮明确不做）

- **反向联动**（ChatBox → 我们的系统 / ChatBox 的选项触发我们的对话）；
- 回复触发**任意命令**（只接 ChatBox 对话，保持"数据即目标"的窄接口）；
- ChatBox 的**立绘/主题/表达式**体系（用户明确不要立绘）；
- 多段对话串联、条件分支、按进度筛选（本模组对话系统刻意不做对话树）。

---

## 七、下一步

调用 `planning` 技能，把本设计拆成可执行任务清单（含静态验证命令与实机验收清单）。
