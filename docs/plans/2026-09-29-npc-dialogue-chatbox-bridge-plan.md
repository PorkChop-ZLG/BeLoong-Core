# NPC 对话系统 ↔ ChatBox 联动 实施计划

> **实施状态（2026-09-29）：T1–T8 全部完成，实机验收待用户执行。**
> 提交：批次 ① + ② = `580c909`（数据模型 / 线载荷 / ChatBox 桥 / C2S 包 / 客户端界面）；批次 ③ 紧随其后。
> 途中唯一的插曲：我自己一处笔误（`ByteCodecs` 应为 `ByteBufCodecs`）导致一次构建失败 ——
> 而那次失败恰好**证明了** `ChatBoxBridge` 里对 ChatBox 的类名/签名引用全部解析成功（javac 只报了那 1 个错误）
> ⇒ 参考源码与 jar 一致，本次最大的技术不确定性已排除。

**Goal:** 让 NPC 对话播放完毕后，在「离开」上方出现**数据驱动**的回复选项；点击后关闭我方界面并立刻进入指定的 ChatBox 对话。
**Architecture:** 设计文档见 `docs/plans/2026-09-29-npc-dialogue-chatbox-bridge-design.md`（§1 架构 / §2 组件 / §3 数据流 / §4 错误处理 / §5 验证）。
**Approach:** **方案 A** —— 新增一个 C2S「回复」包（只带"实体网络 id + 回复下标"），服务端查自己的对话表后用 ChatBox 公开 API `ChatBoxCommandUtil.serverSkipDialogues` 触发。零 mixin、零反射、服务端权威。

> ⚠️ **与技能默认流程的一处偏离**：本技能默认 TDD（RED-GREEN-REFACTOR），但**本项目没有测试源集**
> （构建日志里 `Task :test NO-SOURCE` 是既有事实）。因此每个任务的"验证"用**构建 + 探针 + 实机清单**代替单元测试，
> 并给出**确切命令**。这不是省略验证，而是换成本项目唯一可用的验证方式。

> ⚠️ **本次最大的技术不确定性**：ChatBox 的 jar 与参考源码可能不一致（只有版本号吻合）。
> **缓解手段就是 T3 的构建**：那是编译期依赖，签名/类名不符会**直接编译失败** ⇒ 比任何阅读都硬。

---

## 提交批次（3 个原子提交）

| 批次 | 含任务 | 内容 |
|---|---|---|
| ① | T1、T2 | 数据模型 + 线载荷（加可选字段，此时功能尚未可见，但构建必须过） |
| ② | T3、T4、T5 | ChatBox 桥 + C2S 包 + 客户端界面 ⇒ **功能端到端可用** |
| ③ | T6、T7 | 末的数据与语言键 + 注释/文档回填 |

---

## T1：数据模型 —— `NpcDialogueEntry` 加 `replies`

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueEntry.java`
- Modify: `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueLoader.java`（仅在需要时：确认空 `pages` 的丢弃逻辑不受影响）

**Steps:**
1. 新增 `public record Reply(String text, ResourceLocation chatbox, String group, Optional<Integer> index)`，
   并写 `Reply.CODEC`（`text` 用 `Codec.STRING.fieldOf("text")`；`chatbox` 用 `ResourceLocation.CODEC.fieldOf("chatbox")`；
   `group` 用 `Codec.STRING.fieldOf("group")`；`index` 用 `Codec.INT.optionalFieldOf("index")`）。
2. `NpcDialogueEntry` 加第 5 个分量 `List<Reply> replies`，`CODEC` 里用
   `Codec.list(Reply.CODEC).optionalFieldOf("replies", List.of())`（**缺省空表 ⇒ 老数据文件零影响**）。
3. 更新类注释的字段清单（项目硬要求：注释必须与代码一致）。

**Verification:**
```powershell
$env:JAVA_HOME='D:\Java\jdk-21.0.11'; .\gradlew.bat build --console=plain   # 必须 BUILD SUCCESSFUL
```

---

## T2：线载荷 —— `NpcDialogueOpenPayload` 加 `replyTextKeys`

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueOpenPayload.java`
- Modify: `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueHandler.java`（生产端补传参数）

**Steps:**
1. `NpcDialogueOpenPayload` 加第 5 个分量 `List<String> replyTextKeys`；`STREAM_CODEC` 里用
   `ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list())` —— **只发标签键、不发目标**（设计 D1），
   线格式因此仍然**全函数、永不抛**。
2. `NpcDialogueHandler` 构造 payload 时传 `entry.replies().stream().map(Reply::text).toList()`。
3. 更新两个类注释里对"载荷内容"的描述。

**Verification:**
```powershell
.\gradlew.bat build --console=plain
```

---

## T3：ChatBox 桥 —— `compat/chatbox/ChatBoxBridge`（**全项目唯一 import ChatBox 的类**）

**Files:**
- Create: `src/main/java/com/zonlong/beloong/compat/chatbox/ChatBoxBridge.java`

**Steps:**
1. `canOpen(ResourceLocation rl, String group, int index)`：用 ChatBox 自己的公开表预检
   （`ChatBoxDialoguesLoader.dialoguesGroupMap` / `parsedDialogues`）——RL 存在、组非空、**页号在范围内**；
   预检保护的不只是"静默失败"，还有 ChatBox 回执路径上那处无判空的取组取页（`SimplePayload.java:164`）。
2. `open(ServerPlayer player, ResourceLocation rl, String group, int index)`：调
   `ChatBoxCommandUtil.serverSkipDialogues(player, rl, group, index)`（**index 绝不能为 null** —— 会被编码成 `"null"` 让客户端 `parseInt` 抛异常）。
3. 失败时打**英文 WARN**，且**每个 (rl, group) 只报一次**（`Set` 缓存；与 `EmoteAnimationLookup` 同一先例）。
4. 类注释写明：为什么隔离在这个包（`compat/` 惯例）、以及"ChatBox 已是 `type="required"` 依赖"这一事实。

**Verification:**
```powershell
.\gradlew.bat build --console=plain   # ★ 这一步同时验证了 ChatBox 的类名/签名与 jar 一致
```

---

## T4：C2S 包 `NpcDialogueReplyPayload` + 注册

**Files:**
- Create: `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueReplyPayload.java`
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`（`registerPayloads` 里加一行 `playToServer`）

**Steps:**
1. `record NpcDialogueReplyPayload(int entityId, int replyIndex) implements CustomPacketPayload`；
   `TYPE` = `beloong:npc_dialogue_reply`；`STREAM_CODEC` = 两个 `ByteBufCodecs.VAR_INT`（平凡全函数）。
2. `handleServer`：取 `ServerPlayer` → `level().getEntity(entityId)` → null 则**静默返回** →
   `NpcDialogueLoader.INSTANCE.get(entity.getType())` → null 则静默返回 → 下标越界则静默返回 →
   `ChatBoxBridge.canOpen(...)` → `ChatBoxBridge.open(...)`。
3. `BeLoongCore.registerPayloads` 里照 `:151/:155` 的写法加 `.playToServer(NpcDialogueReplyPayload.TYPE, …, NpcDialogueReplyPayload::handleServer)`。
   （**本项目第一个 C2S 包**，注册方向别写错。）

**Verification:**
```powershell
.\gradlew.bat build --console=plain
```

---

## T5：客户端界面 —— 在「离开」上方加回复按钮

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/client/NpcDialogueScreen.java`
- Modify: `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueOpenPayload.java`（`handleClient` 把新字段透传给 Screen）

**Steps:**
1. `NpcDialogueScreen` 构造参数 += `int entityId, List<String> replyTextKeys`，各自存字段。
2. `showOptions()` 改为**自下而上**：最下一颗仍是「离开」（`optionBottom - OPTION_HEIGHT`），
   回复依次往上：`y = optionBottom - OPTION_HEIGHT * (i + 2) - OPTION_GAP * (i + 1)`（i 从 0 起，第 0 条最靠近「离开」）。
   每颗复用 `NpcDialogueOptionButton(x, y, width, OPTION_HEIGHT, Component.translatable(key), callback)`。
3. 回复的回调：`this.minecraft.setScreen(null)`（**与「离开」完全等价**）+ `PacketDistributor.sendToServer(new NpcDialogueReplyPayload(this.entityId, i))`。
4. 改掉 `LEAVE_KEY` 上那句"v1 只有这一个选项，硬编码而非放进 JSON"的注释（它已过期）。

**Verification:**
```powershell
.\gradlew.bat build --console=plain
# 实机（属于 T8 的验收，但本任务做完即可先看一次）
```

---

## T6：末的数据与语言键

**Files:**
- Modify: `src/main/resources/data/beloong/beloong/npc_dialogue/mo.json`（加 `replies`）
- Modify: `src/main/resources/data/beloong/chatbox/dialogues/mo.json`（`start` 组：1 页 → **2 页**）
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

**Steps:**
1. `npc_dialogue/mo.json` 加：
   ```json
   "replies": [ { "text": "beloong.dialogue.mo.reply1", "chatbox": "beloong:mo", "group": "start" } ]
   ```
2. `chatbox/dialogues/mo.json` 的 `start` 组改成两页：`beloong.chatbox.mo.p1`（这里是龙宫。）、
   `beloong.chatbox.mo.p2`（走，我带你去觐见龙王。）。
3. 语言键：两语言各 +3（`beloong.dialogue.mo.reply1`、`beloong.chatbox.mo.p1`、`beloong.chatbox.mo.p2`），
   删 2（旧的 `beloong.chatbox.mo.text`，以及**早先误解第 2 步时遗留的** `beloong.dialogue.mo.p2`）
   ⇒ 净 +1（**240 → 241**），保持**键集合两语言一致**。
   > 📌 实施时的更正：我方对话数据当时有 **2 页**（p2 是那次误解的产物），
   > 而按用户明确的流程（"我是…" → 点击 → **立刻**弹选项）必须有且只有 1 页 ⇒ 已删。

**Verification:**
```powershell
# 键集合一致 + 数据文件引用的键都存在
python D:\Minecraft\tools\YSMParser\add_chatbox_mo_lang.py   # 或写一个只读对账脚本
.\gradlew.bat build --console=plain
# 确认数据被 jar 打包
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z=[System.IO.Compression.ZipFile]::OpenRead((Get-ChildItem build\libs\*.jar|?{$_.Name -notmatch 'sources|javadoc'}|Select -First 1).FullName)
$z.Entries|?{$_.FullName -match 'chatbox/|npc_dialogue/'}|%{"$($_.Length)`t$($_.FullName)"}; $z.Dispose()
```

---

## T7：注释与文档回填（项目硬要求）

**Files:**
- Modify: `docs/NPC系统总设计.md`（§5.2 数据格式表加 `replies`；§5.5 界面描述补"回复选项"）
- Modify: `docs/plans/2026-09-29-npc-dialogue-chatbox-bridge-design.md`（状态改为"已实施"）
- Modify: `memory/decisions-log.md`、`memory/learned-patterns.md`

**Steps:**
1. 主设计文档 §5 补 `replies` 字段与"回复 → ChatBox"的行为（附本设计文档链接）。
2. memory：决策记录（方案 A 与三条承重决定）+ 一条可复用模式（"跨模组联动优先用对方 public 静态入口；
   客户端只回传下标、目标由服务端解析"）。
3. 全库扫一遍本轮是否引入新的"注释与代码不符"（上次审查抓到 8 处，这类必须同批修掉）。

**Verification:**
```powershell
Select-String -Path docs\NPC系统总设计.md -Pattern 'replies' | Measure-Object   # 至少 1 处
git status --short                                                              # 干净
```

---

## T8：全量验证与验收

**Steps:**
1. `.\gradlew.bat build --console=plain` ⇒ BUILD SUCCESSFUL
2. 三个既有探针全绿：`python D:\Minecraft\tools\YSMParser\probe_emote_{assets,java,bytecode}.py`
3. 两语言键集合对账（242/242）+ 数据文件引用的键齐全
4. 把实机清单交给用户

**实机验收清单（用户执行）**

| # | 操作 | 期望 |
|---|---|---|
| 1 | 右键末 | 「我是「末」，末影龙族的长公主。」→ 点击 → **两颗**按钮：上「这里是什么地方？」、下「离开」 |
| 2 | 点「离开」 | 只关界面，**不**弹 ChatBox |
| 3 | 再右键 → 点「这里是什么地方？」 | 我方界面关闭 → **立刻**弹 ChatBox：「这里是龙宫。」→ 点击 →「走，我带你去觐见龙王。」→ 点击 → 结束 |
| 4 | 手持任意物品右键末 | 仍能触发 |
| 5 | 右键铁傀儡（无 `replies`） | 只有「离开」，行为不变 |
| 6 | 把 `chatbox` 临时改成不存在的 RL 再点回复 | 安静无事 + 日志**一条**英文 WARN（连点多次只报一次） |

---

## 风险与回退

| 风险 | 影响 | 缓解 |
|---|---|---|
| ChatBox jar 与参考源码不一致 | 编译失败 | **T3 的构建就是检验**；失败即停，改为按 jar 反查签名 |
| 本项目第一个 C2S 包，方向/API 写错 | 点击无效或连接异常 | 照 `BeLoongCore.java:151/155` 的既有写法；`playToServer` + `PacketDistributor.sendToServer` |
| 「立刻弹出」被误判为抢屏 | 观感差 | 时序上隔着一次网络往返（设计 §3），实机第 3 项专测 |
| 回复把 ChatBox 对话打进 HUD 模式 | 看不到 | ChatBox 对话里写死 `isScreen: true`（已完成） |

**回退**：批次 ③ 是纯数据，删掉 `replies` 字段即回到旧行为；批次 ①② 可整体 `git revert`。
