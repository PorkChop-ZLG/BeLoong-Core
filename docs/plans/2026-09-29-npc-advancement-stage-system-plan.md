# 原版进度 NPC 阶段系统 实施计划

> **实施状态（2026-09-30）：T1–T8 全部完成，实机验收待用户执行。**
> 提交：批次 ① = `3ae0b2b`（数据模型 / 阶段助手 / 载荷换型 / 点击复检）；批次 ② = `95b76df`（四份数据 + 语言键）。
> **执行纪律收到成效**：本轮的每一段（数据脚本 / 语言键脚本 / 不变量脚本 / 构建）都检查了退出码，
> 任一段失败即中止；不变量脚本 22 项全 PASS 才提交。
> **两处与计划的偏离**（都已在提交信息里写明）：
> 1. T2 的助手改为返回**载荷无关**的 `visible(...)` / `visibleIndices(...)` —— 计划里让它返回
>    `List<VisibleReply>`，但该类型要到 T3 才存在，中间态编译不过；规则仍只写一处，意图不变。
> 2. T5 顺带 `git add` 了那份**从未入库**的 `advancement/npc/root.json`（其 mtime 是 2026-09-30 11:38），
>    ⇒ 这棵进度树首次随包发布。

**Goal:** 用原版进度当阶段状态的唯一存储 —— 数据里给每条回复声明「开始进度 / 结束进度」，
据此决定该回复是否显示；结束进度由 ChatBox 在最后一页文字播完时发放。
**Architecture:** 设计文档见 `docs/plans/2026-09-29-npc-advancement-stage-system-design.md`
（§1 架构 / §2 组件 / §3 数据流 / §4 错误处理 / §5 验证）。
**Approach:** **方案 A** —— 服务端过滤可见回复（带**数据下标**）下发，点击时复检。零 mixin、零反射、服务端权威。

> ⚠️ **与技能默认流程的偏离（同上一轮）**：本技能默认 TDD，但本项目**没有测试源集**（`Task :test NO-SOURCE`）。
> 每任务的验证 = **构建 + 脚本对账 + 实机清单**，并给出确切命令。

> ⚠️ **上一轮的两个教训，已写进本计划的执行纪律**（详见文末"执行纪律"）：
> ① 改数据文件的脚本，**锚点必须基于当前实际内容**（先读一遍），小文件一律**整份重写**；
> ② **每一段的退出码都要纳入判断**，任一段失败**绝不继续、绝不提交**。

---

## 提交批次（3 个原子提交）

| 批次 | 含任务 | 内容 |
|---|---|---|
| ① | T1–T4 | 数据模型 + 阶段助手 + 载荷换型 + 点击复检（**纯 Java，无数据 ⇒ 行为暂不可见**） |
| ② | T5、T6 | 两份进度 + 两份对话数据 + 语言键 ⇒ **功能端到端可验** |
| ③ | T7 | 注释与文档回填 |

---

## T1：数据模型 —— `Reply` 加两个可选进度字段

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueEntry.java`

**Steps:**
1. `Reply` 记录加两个分量：`Optional<ResourceLocation> startAdvancement, endAdvancement`；
   `Reply.CODEC` 加 `ResourceLocation.CODEC.optionalFieldOf("start_advancement")` 与 `optionalFieldOf("end_advancement")`
   （**都可省略** ⇒ 老数据行为不变）。
2. 记录注释写清：这是"只读闸门"的数据面；判定规则与"未知 id 一律不可见"的取舍指向设计文档。

**Verification:**
```powershell
$env:JAVA_HOME='D:\Java\jdk-21.0.11'; .\gradlew.bat build --console=plain   # BUILD SUCCESSFUL
```

---

## T2：阶段助手 —— `NpcDialogueStage`（可见性规则的唯一实现）

**Files:**
- Create: `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueStage.java`

**Steps:**
1. `private static boolean done(ServerPlayer, ResourceLocation)`：照 `StructureEffectHandler.java:85-92` 的先例
   （`player.server.getAdvancements().get(rl)` → null 检查 → `player.getAdvancements().getOrStartProgress(holder).isDone()`）。
   需要一个**三态**内部表示（`UNKNOWN` / `DONE` / `NOT_DONE`），因为"未知 id"必须与"未完成"区别对待。
2. `public static List<NpcDialogueOpenPayload.VisibleReply> visibleReplies(ServerPlayer, NpcDialogueEntry)`：
   按 `可见 ⇔ (start 省略 或 已完成) 且 (end 省略 或 未完成)` 过滤，并**带上该回复在 `replies[]` 里的原始下标**。
3. 未知 id ⇒ 该回复**不可见** + **英文 WARN，每 id 只报一次**（`Set<ResourceLocation>` 缓存；
   与 `ChatBoxBridge`/`EmoteAnimationLookup` 同一先例）。日志纯英文（项目硬要求）。

**Verification:**
```powershell
.\gradlew.bat build --console=plain
```

---

## T3：载荷换型 + 生产端 + 界面（**共享类型必须同批改，否则中间态编译不过**）

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueOpenPayload.java`
- Modify: `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueHandler.java`
- Modify: `src/main/java/com/zonlong/beloong/client/NpcDialogueScreen.java`

**Steps:**
1. `NpcDialogueOpenPayload`：把 `List<String> replyTextKeys` 换成 `List<VisibleReply> replies`；
   新增嵌套 `public record VisibleReply(String text, int index)` 与其**全函数** `STREAM_CODEC`
   （`STRING_UTF8` + `VAR_INT`，无查表无分支）。
2. `NpcDialogueHandler`：生产端改用 `NpcDialogueStage.visibleReplies(...)`。
3. `NpcDialogueScreen`：按可见列表渲染，**回传数据下标**（`onReply(visible.index())`），
   字段从 `List<String> replyTextKeys` 换成 `List<VisibleReply> replies`。
4. 三个类的注释同步（`NpcDialogueOpenPayload` 那条"全函数永不抛"的说明仍然成立，但要补"可见性由服务端定"）。

**Verification:**
```powershell
.\gradlew.bat build --console=plain
```

---

## T4：点击端复检

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueReplyPayload.java`

**Steps:**
1. `handleServer` 在现有三步（找实体 → 查对话表 → 下标越界即丢）之后，**再判一次该回复当前是否仍可见**
   （复用 `NpcDialogueStage`），不可见即静默丢弃。
2. 注释补一句"为什么收的是数据下标而不是可见下标"（D2）。

**Verification:**
```powershell
.\gradlew.bat build --console=plain
```

---

## T5：四份数据

**Files:**
- Modify: `src/main/resources/data/beloong/advancement/npc/root.json`（**只把触发器换成 `minecraft:impossible`，其余 display 一字不动**）
  ⚠️ **这份文件目前不在 git 里**：`advancement/` 目录下当前没有任何被跟踪的文件
  （历史上那两次 advancement 提交是爪牙槽的，且 `519dc3e` 已把它们删除），
  而这份 `root.json` 的 mtime 是 **2026-09-30 11:38**（用户新写的草稿）⇒ **T5 必须 `git add` 它**，
  否则这棵进度树永远不随包发布（现构建出的 jar 里确实没有 `data/beloong/advancement/`）。
- Create: `src/main/resources/data/beloong/advancement/npc/1_1.json`
- Modify: `src/main/resources/data/beloong/beloong/npc_dialogue/mo.json`（`replies[0]` 加两个字段）
- Modify: `src/main/resources/data/beloong/chatbox/dialogues/mo.json`（**最后一页**加 `renderEvents`）

**Steps:**
1. **先读一遍这四份文件的当前内容**（上一轮的教训：不能凭记忆写锚点）。四份都很小 ⇒ **锚点补丁或整份重写都可以，但必须逐份确认改后内容**。
2. `root.json`：`criteria` 从 `dragonsurvival:be_dragon` 换成 `{"root": {"trigger": "minecraft:impossible"}}`
   （`requirements` 里的名字要跟着对上）。
3. `1_1.json`：照设计文档 §3.2 的 JSON（`parent = beloong:npc/root`、`impossible`、`show_toast:false`、
   `announce_to_chat:false`、`hidden:false`、图标 `minecraft:ender_pearl`）。
4. `mo.json`：`replies[0]` 加 `"start_advancement": "beloong:npc/root"` 与 `"end_advancement": "beloong:npc/1_1"`。
5. ChatBox 的 `mo.json`：**第二个**（也是最后一个）页元素加 `renderEvents`，`value` =
   `advancement grant @s only beloong:npc/1_1`（**不加 MVEL condition**，见设计 §3.3 取舍 1）。
   ⚠️ 这份文件语法错会让 ChatBox 整个 reload listener 挂掉 ⇒ 改完必须用 `json.loads` 验一遍。

**Verification:**
```powershell
python D:\Minecraft\tools\YSMParser\stage_invariants.py   # 本任务顺带写出的数据不变量脚本（见 T8）
.\gradlew.bat build --console=plain
```

---

## T6：语言键（4 条 × 2 语言，241 → 245）

**Files:**
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

**Steps:**
1. 加 `beloong.advancement.npc/root`、`beloong.advancement.npc/root.desc`
   （**这两条本来就缺**，现在进度界面显示的是原始键名 ⇒ 顺带补上）、
   `beloong.advancement.npc/1_1`、`beloong.advancement.npc/1_1.desc`。
2. 中文例如："末的初次相遇" / "与「末」在龙宫说上话"；`1_1`："龙宫引路" / "听末讲完龙宫与觐见龙王之事"。
   英文同步（键集合必须一致）。
3. **先读当前语言文件再插**，插入后跑对账（`json.loads` + 键集合差集 + 引用齐全）。

**Verification:**
```powershell
python D:\Minecraft\tools\YSMParser\t6b_lang_check.py   # 或复用上一轮的 add_*_lang.py 的结尾对账段
.\gradlew.bat build --console=plain
```

---

## T7：注释与文档回填（项目硬要求）

**Files:**
- Modify: `docs/NPC系统总设计.md`（§5.2 字段表补 `replies[].start_advancement`/`end_advancement`；§5.8 补"阶段闸门"一段）
- Modify: `docs/plans/2026-09-29-npc-advancement-stage-system-design.md`（状态 → 已实施）
- Modify: `memory/decisions-log.md`、`memory/learned-patterns.md`

**Steps:**
1. 主设计文档 §5.2 字段表加两行；§5.8 补"阶段闸门"（真值表 + fail-closed + 首尾相接约定 + 指向本设计文档）。
2. memory：决策记录（方案 A、D1–D4、ChatBox 无 close 钩子这一事实、首尾相接约定）+
   一条可复用模式（**用原版系统当状态存储**：把阶段交给 advancement，自己只做只读闸门）。
3. 全库扫一遍本轮是否引入新的"注释与代码不符"（上次审查抓到 8 处，这类必须同批修掉）。

**Verification:**
```powershell
Select-String -Path docs\NPC系统总设计.md -Pattern 'start_advancement' | Measure-Object   # ≥1
git status --short                                                                          # 干净
```

---

## T8：全量验证与验收

**Steps:**
1. `.\gradlew.bat build --console=plain` ⇒ BUILD SUCCESSFUL
2. 三个既有探针全绿：`probe_emote_{assets,java,bytecode}.py`
3. **数据不变量脚本** `stage_invariants.py`（T5 时写出，T8 复跑）必须全绿：
   - 两份进度 JSON 可解析；`root` 的 trigger == `minecraft:impossible`；`1_1.parent == beloong:npc/root`；
     `1_1` 的 `show_toast` 与 `announce_to_chat` 均为 false；
   - `mo.json` 的 `start_advancement`/`end_advancement` **都指向真实存在的进度文件**；
   - ChatBox 那份 JSON 的**最后一页**带 `renderEvents`，且 `value` 里的进度 id 与 `mo.json` 的
     `end_advancement` **字符串一致**（防两边写岔）；
   - 两语言键集合一致（245/245），且两个进度的 title/desc 四个键都在。
4. 把实机清单交给用户（见设计文档 §5，共 10 项，含 3 条反例）。

**Verification:** 上述命令的输出全部符合预期。

---

## 风险与回退

| 风险 | 影响 | 缓解 |
|---|---|---|
| 改 `root.json` 的触发器影响别处 | 低 | 已核实：全库除它自己外**无人引用** `npc/root`；且只改 `criteria`，display 一字不动 |
| **`root.json` 从未入库** | 中：不 `git add` 就等于这棵树不存在 | T5 显式 `git add`；T8 再用 jar 列表核对 `data/beloong/advancement/` 确实随包发布 |
| ChatBox 的对话 JSON 写坏 | **高**：会让 ChatBox 整个 reload listener 挂掉 | 改完立刻 `json.loads` 验；只加一个 `renderEvents`，不动其余字段 |
| 未知进度 id 的语义取错 | 中：会提前显示本该隐藏的选项 | 明确 fail-closed（设计 §3.2），并有 T5 的不变量脚本兜底 |
| 载荷换型漏改一处 | 编译失败 | T3 把三处放同一任务，一次构建验证 |
| 数据脚本锚点失配 | 上次的真实故障：只提交了文档 | **整份重写小文件** + 逐段检查退出码（见下） |

**回退**：批次 ② 是纯数据，删掉两个进度字段即回到旧行为；批次 ①③ 可整体 `git revert`。

---

## 执行纪律（上一轮的教训，本计划强制遵守）

1. **改数据文件前先读一遍它的当前内容** —— 锚点必须基于**实际内容**，不能基于记忆里的版本
   （上一轮我假设 `mo.json` 只有 1 页，实际有 2 页 ⇒ 锚点失配）。
2. **几百字节的文件一律整份重写**，不在多处锚点上赌运气。
3. **每一段脚本的退出码都要纳入判断**：
   `python t5.py; if ($LASTEXITCODE -ne 0) { throw 'T5 failed' }` —— 上一轮正是"脚本 exit 1 但我继续跑并提交"，
   导致"数据一字节没改、文档却标已实施"。
4. **任一段失败 ⇒ 不提交该批次**，先修好再提交。
