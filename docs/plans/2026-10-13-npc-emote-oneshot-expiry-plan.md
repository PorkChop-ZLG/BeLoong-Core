# 一次性表情"播完即清" 实施计划

**Goal:** 让一次性（`PLAY_ONCE`）表情播完后由服务端把 `DATA_EMOTE` 清回 `""`，从而**在客户端重建实体实例时不再重播**；`LOOP` 姿态完全不受影响。

**Architecture:** 客户端在"首次起表且查到资产"时把**动画长度**申报给服务端；服务端据此记一条内存计划（`deadline = emoteSetAt + duration`），由一个**与区块加载无关**的 tick 处理器到期 `clearEmote()`。

**Approach:** 已选定并逐节批准（设计文档 `docs/plans/2026-10-13-npc-emote-oneshot-expiry-design.md`，提交 `18ef949`）。
**用户裁定**：修在**表情层**（通用）；**四场景全修**；**任何 `NpcEntity` 都受理，仅校验"实体 + 名字"**；`NpcStoryHandler:640` 那条**改代码让行为符合注释**。

---

## 探明的前置事实（实施依据，勿凭记忆改写）

| 事实 | 出处 |
|---|---|
| `DATA_EMOTE` 同步字段，默认 `""` | `NpcEntity:378` 定义、`:502` 默认 |
| `setEmote` **只在服务端生效**，且用 **`force = true`**（值没变也发包，"再播一次同名"正是这种情形） | `NpcEntity:839-849` |
| `onSyncedDataUpdated` 收到 `DATA_EMOTE` 时清 `emoteSeen`/`emoteDone`/`emoteStartTick`；**客户端无条件收到通知**（不比较新旧值） | `NpcEntity:860-868`、注释 `:855-856` |
| 表情控制器（客户端）：空名或 `emoteDone` ⇒ STOP；换名 ⇒ 重钉计时；`find` 失败 ⇒ 静默不播、**不做负缓存**（下一帧再查，可自愈）；`LOOP` ⇒ 一直播；非循环 ⇒ 播满 `animation.length()` 置 `emoteDone = true` | `NpcEntity:1705-1747`（`:1724` 自愈、`:1727` LOOP、`:1736-1744` 收工） |
| `EmoteAnimationLookup.find(npc, name)` 提供 `Animation#length()` 与 `#loopType()` | `client/model/EmoteAnimationLookup.java:82`（注释 `:74-75`） |
| **C→S 包模板**：`registrar.playToServer(TYPE, STREAM_CODEC, ::handleServer)` | `BeLoongCore:174-177`；包形状见 `NpcDialogueReplyPayload:42-67` |
| **handler 注册模板**：`NeoForge.EVENT_BUS.register(new XxxHandler())` | `BeLoongCore:118-139`（`NpcStoryHandler` 在 `:127`） |
| `beloong:mo` 的追踪半径 = 10 区块 = **160 格** | `ModEntities:105` |
| `ensureDouble` 已幂等（有分身就复用），但调用方**无条件** `cg.play` | `NpcStoryHandler:640`（注释声称"不重播 CG"）vs `:151-158` |
| 活文档 | `docs/NPC系统总设计.md` |

**两条必须记住的既有设计取向**（本次不违反）：
1. **动画名/长度/loop 类型都是客户端资产数据，判断全放客户端**（`NpcEntity:1704`）⇒ 服务端**只接收客户端上报的结论**，不自己解析资产。
2. 既有的 8 个 `clearEmote()` 调用点**语义不变**，本次只**新增**一条"到期自动清"。

## 非目标

- 不新增同步字段（`force = true` 已提供"重播意图"的判据 ⇒ 不需要 nonce）
- 不动任何既有 `clearEmote()` 调用点
- 不给"忽略申报"的路径加 WARN（正常竞态）
- 不做防伪（用户已裁定；只做"无效输入不产生行为"）

---

# 批次① 实现与静态守卫

### T1：`NpcEmoteExpiryHandler` —— 与区块加载无关的到期清除

**Files:** Create `src/main/java/com/zonlong/beloong/emote/NpcEmoteExpiryHandler.java`

**Steps:**
1. `public final class NpcEmoteExpiryHandler`，私有构造；`private static final Map<NpcEntity, Entry> PENDING = new HashMap<>();`，`private record Entry(String name, long deadline)`。
   （`NpcEntity` 未重写 `equals`/`hashCode` ⇒ 天然身份语义；每个实体最多一条，`setEmote` 覆盖 ⇒ **天然有界**。）
2. `static void schedule(NpcEntity npc, String name, long deadline)`：`PENDING.put(...)`，并打**一条 INFO**：
   `"[BeLoong] emote '{}' on {} will be cleared in {} ticks"`（`name`、`npc.getType()`、`deadline - 当前 gameTime`）。
   ⇒ 这条日志是实机验收第 1 项的**唯一可观测点**（字段本身在游戏里看不到）。
3. `static void cancel(NpcEntity npc)`：`PENDING.remove(npc)`。
4. `@SubscribeEvent public static void onServerTick(ServerTickEvent.Post event)`：
   - 遍历 `List.copyOf(PENDING.keySet())`（**先复制再改**，避免 `ConcurrentModificationException`）
   - `npc.isRemoved()` ⇒ 只 `remove` 表项，**不动任何东西**
   - `!(npc.level() instanceof ServerLevel)` ⇒ 同上
   - `now < deadline` ⇒ 跳过
   - 到期：**先** `PENDING.remove(npc)`，**再** `if (npc.emote().equals(entry.name())) npc.clearEmote();`（名字不一致 ⇒ 不动字段）
5. 类 javadoc 写清三件事：① **为什么必须与区块加载无关**（玩家走远时区块不 tick，而恰是那时需要清）；② 幂等来自 `deadline = emoteSetAt + duration`；③ 名字比对是第二道（第一道是 `setEmote` 作废计划）。

**Verification:** `gradlew build` 成功

### T2：`NpcEntity` —— `emoteSetAt` 字段 + `setEmote` 刷新与作废

**Files:** Modify `src/main/java/com/zonlong/beloong/entity/NpcEntity.java`

**Steps:**
1. 新增**服务端普通字段**（不落盘、不同步）：`private long emoteSetAt;` + 只读访问器 `public long emoteSetAt()`。
2. `setEmote(String)` 末尾（`entityData.set(...)` 之后）追加：
   ```java
   this.emoteSetAt = this.level().getGameTime();   // 每次调用都刷新：force=true 的语义就是"再播一次"
   NpcEmoteExpiryHandler.cancel(this);             // 新表情作废旧计划（I2）
   ```
3. 更新 `setEmote` 的 javadoc：说明这两个动作**为什么每次都要做**（`force = true` ⇒ 每次都是"要求重播"，同名也不例外 ⇒ 必须得到新的到期时刻；同时旧计划绝不能清掉新表情）。
4. ⚠️ **不要**在 `onSyncedDataUpdated` 里做任何改动（T4 才动它）。

**Verification:** `gradlew build` 成功

### T3：`NpcEmoteDurationPayload` + 注册

**Files:**
- Create `src/main/java/com/zonlong/beloong/emote/NpcEmoteDurationPayload.java`
- Modify `src/main/java/com/zonlong/beloong/BeLoongCore.java`

**Steps:**
1. 照 `NpcDialogueReplyPayload:42-67` 的形状写：
   `public record NpcEmoteDurationPayload(int entityId, String emoteName, int durationTicks) implements CustomPacketPayload`
   + `TYPE`（`ResourceLocation.fromNamespaceAndPath(MODID, "npc_emote_duration")`）
   + `STREAM_CODEC`（`composite(VAR_INT, ::entityId, STRING_UTF8, ::emoteName, VAR_INT, ::durationTicks, ::new)`）
2. `handleServer(payload, context)` 按**这个顺序**校验，任一不过就**静默 return**：
   ```
   context.player() instanceof ServerPlayer player ？          否则 return
   player.serverLevel().getEntity(payload.entityId()) instanceof NpcEntity npc ？  否则 return
   npc.emote().equals(payload.emoteName()) ？                  否则 return   ← I3
   payload.durationTicks() > 0 ？                              否则 return   ← 无效输入
   ```
3. 记计划：
   ```java
   long deadline = npc.emoteSetAt() + (long) payload.durationTicks();   // ★ 用 emoteSetAt，不是 now（I1）
   NpcEmoteExpiryHandler.schedule(npc, payload.emoteName(), deadline);
   ```
   - ⚠️ **这里绝不能出现 `getGameTime()`** —— 一旦出现就说明有人改成了 `now + duration`，那样重新追踪引发的重复申报会把清除**无限推后**（永远清不掉）。这条由 T6 的 **A1** 守着。
   - 溢出：`long + int` **不可能溢出**（gameTime 远小于 `Long.MAX_VALUE`）⇒ 不需要额外防护。同时把设计文档 §4 里"`Math.addExact` 或夹到安全上限"那句**收紧成这个结论**（见 T9）。
   - `emote()` 非空 ⇒ `emoteSetAt > 0` 必然成立（两者只在 `setEmote` 里一起写；表情不落盘，重载后两者同时归零）⇒ 不需要额外分支。
4. `BeLoongCore`：`registerPayloads` 里加 `registrar.playToServer(NpcEmoteDurationPayload.TYPE, ...STREAM_CODEC, NpcEmoteDurationPayload::handleServer)`（照 `:174-177`）；并在 `:127` 附近加 `NeoForge.EVENT_BUS.register(new NpcEmoteExpiryHandler());`，行尾写一句中文注释说明职责。
5. `handleServer` 的 javadoc 写清"受理范围 = 任何 `NpcEntity`，仅校验实体 + 名字（用户裁定）"，以及**已接受**"改装客户端可谎报长度提前清掉共享 NPC 的表情（纯观感）"。

**Verification:** `gradlew build` 成功

### T4：客户端申报（控制器"起表"分支之后）

**Files:** Modify `src/main/java/com/zonlong/beloong/entity/NpcEntity.java`

**Steps:**
1. 新增**客户端瞬态字段** `private String emoteReported = "";`（注释：这条表情是否已经申报过长度）。
2. 在 `onSyncedDataUpdated` 的 `DATA_EMOTE` 分支里追加 `this.emoteReported = "";`（与既有三个字段一起重置）⇒ 服务端每次 `setEmote`（含 force 的同名重播）与每次重新追踪都会让本客户端重新申报一次；**重复申报由服务端 I1 幂等吸收**。
3. 在控制器里、`Animation animation = EmoteAnimationLookup.find(npc, name);` **成功之后**、`if (animation.loopType() == Animation.LoopType.LOOP)` **之前**插入：
   ```java
   // ★ 一次性表情 ⇒ 把长度申报给服务端（一条表情只报一次）
   if (animation.loopType() != Animation.LoopType.LOOP && !name.equals(npc.emoteReported)) {
       npc.emoteReported = name;
       PacketDistributor.sendToServer(new NpcEmoteDurationPayload(npc.getId(), name, animation.length()));
   }
   ```
   - **位置的理由**（写进注释）：只有在 `find` 成功之后才知道"是不是一次性"和"多长"；放在 `find` 之前会在**资源未加载**时静默永不申报。`LOOP` 姿态**根本不申报** ⇒ 保护 `sit`/`dance`。
   - **`emoteReported` 这条守卫是必须的**：控制器谓词**每帧**都跑，没有它就会每秒发 60 个包。
   - 从渲染路径发包是常规做法（MC 的渲染与客户端逻辑同在主线程；先例 `NpcDialogueScreen:292`），无需额外调度。
4. 类注释里"表情系统"那一节补一句：一次性表情的长度由客户端申报给服务端、服务端到期自动清 ⇒ 播完即不再残留。

**Verification:** `gradlew build` 成功

### T5：`NpcStoryHandler` —— 让行为符合注释（用户裁定）

**Files:** Modify `src/main/java/com/zonlong/beloong/npcstory/NpcStoryHandler.java`

**Steps:**
1. `ensureDouble` 改为返回一个小 record `Ensured(NpcEntity npc, boolean spawned)`；`spawned` 在"已有自己的分身 ⇒ 复用"分支为 `false`，在新建分支为 `true`。
2. `onAdvancementEarned` 只在 `ensured.spawned()` 时才走 `story.cg().ifPresent(... cg.play ...)`；**复用**分支不再播 CG（这正是 `:640` 注释承诺的行为）。
3. 更新 `:640` 的注释与 `ensureDouble` 的 javadoc：把规则写成"**幂等复用时不重播 CG**"，并说明**为什么**（`root` 被重新获得而分身仍在 ⇒ 剧情没有重新开始，不该重演登场）。
4. 检查 `ensureDouble` 的其它调用点（若有）同步改签名。

**Verification:** `gradlew build` 成功

### T6：探针脚本 `npc_emote_expiry_invariants.py` + 变异测试

**Files:** Create `D:\Minecraft\tools\YSMParser\npc_emote_expiry_invariants.py`

**Steps:** 四条**文本级**断言（必须先剥掉 Java 注释再查代码 —— 本项目注释里大量提到这些名字），并**逐条做变异测试**：

| 断言 | 判据 | 变异（必须 exit 1） |
|---|---|---|
| **A1** | `NpcEmoteDurationPayload.java` 里出现 `emoteSetAt()`，且**不出现** `getGameTime()` | 把 deadline 改成 `npc.level().getGameTime() + durationTicks` |
| **A2** | `NpcEntity.java` 的申报点前出现 `!= Animation.LoopType.LOOP` | 删掉那句判断 |
| **A3** | 申报长度来自 `animation.length()`，且该文件不出现硬编码的 `120`（CG 的长度常量） | 把长度换成字面量 `120` |
| **A4** | `setEmote` 方法体里同时出现 `emoteSetAt =` 与 `cancel(` | 删掉 `cancel(` 那一行 |

**Verification:** `python -X utf8 npc_emote_expiry_invariants.py` 全绿 **且** 四条变异逐条 exit 1、还原后 exit 0

### T7：批次① 验证与提交

**Steps:** 跑齐四套验证并提交。
**Verification:**
```powershell
$env:JAVA_HOME='D:\Java\jdk-21.0.11'; .\gradlew.bat build --console=plain      # BUILD SUCCESSFUL
python -X utf8 D:\Minecraft\tools\YSMParser\npc_emote_expiry_invariants.py      # 四条全绿
python -X utf8 D:\Minecraft\tools\YSMParser\cg_invariants.py                    # 不回归
python -X utf8 D:\Minecraft\tools\YSMParser\cg_lang_keys.py                     # 不回归（语言键未动）
```
提交信息要写清：根因（四环）、用户的三条裁定、`emoteReported` 守卫的作用、以及"本次只新增一条清理路径、未动既有 8 个 `clearEmote()` 调用点"。

---

# 批次② 实机验收与收尾

### T8：实机验收（用户执行）

**Steps:** 按文末"实机验收清单"逐项确认。**任一项不符 ⇒ 记录现象与 # 号，回到批次① 定位。**

### T9：活文档 + 设计文档回填 + memory + 收尾审查

**Files:**
- Modify `docs/NPC系统总设计.md`（表情一节：补"一次性表情播完即清"与"长度由客户端申报"）
- Modify `docs/plans/2026-10-13-npc-emote-oneshot-expiry-design.md`（回填：① §3.2 的 C4 处补 `emoteReported` 守卫；② §4 的溢出那句收紧为"`long + int` 不可能溢出"；③ §6.2 那条已改成"T5 已修"；④ 验收第 1 项的期望已改成"≤120（通常 116~119）"；⑤ §2.1/§3.1 引用的 `NpcEntity` 行号是**改动前快照**，刷成新值；⑥ §3.1 那条"不依赖视线"**只对 CG 成立** —— 谓词仅在渲染时跑且有视锥剔除，措辞改准）
- Modify `docs/plans/2026-09-29-npc-emote-system-design.md`（`:214` / `:385` 那条"中途加入会重播（已接受）"**已被本次修复推翻**，加 supersession 注）
- Modify `src/main/java/com/zonlong/beloong/emote/{NpcEmoteExpiryHandler,NpcEmoteDurationPayload}.java` + `NpcEntity.java`（收尾审查留下的**注释类** Minor）
- Modify `memory/decisions-log.md`（第二十六则的状态从"待实施"改为"已实施"，补提交号与实机结论）
- Modify `memory/learned-patterns.md`（若实施期有新教训；**没有就不写**）

**T7 审查留下、推迟到这里处理的 Minor**（都是注释/措辞类，审查者已确认不阻塞提交）：
1. **M-2** 长度边界：`Animation#length()` 可能为 `0`（显式 0 ⇒ 被 `durationTicks <= 0` 拒收 ⇒ 永不清）
   或 `Double.MAX_VALUE`（哨兵 ⇒ 饱和成 int 最大值 ≈ 3.4 年）⇒ 在 `NpcEntity` 申报处与
   `NpcEmoteDurationPayload` 的类注释各补一句边界（当前 15 条资产全带 `animation_length`，不可达）。
2. **M-6** D6 的"已接受风险"**真实入口比注释更宽**：改**资源包**（不必改装客户端）即可改动画长度，
   且 `PENDING.put` 是**后到者胜** ⇒ 注释补这条（结论不变）。
3. **M-3** "不依赖视线"只对 CG 成立（见上面 Files 的第 ⑥ 条）。
4. **M-4** 计划文本与实际实现不符的两处：计划写了 `ServerLevel` 检查（未实现、当前不可达）、
   写了"私有构造 + static 方法"（实际是 public 构造 + 实例方法，javadoc 已解释理由）⇒ 改计划文本或补一行。
5. **M-5** 行号快照刷新（见上面 Files 的第 ⑤ 条）。

**Steps:** 照本项目惯例派**独立审查**（只审 `src/`，报告写入 `docs/reviews/2026-10-13-npc-emote-expiry-review.md`），修完再写 memory 与活文档。
**Verification:** 四套验证全绿 + 审查报告落盘 + 实机清单通过

---

## 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| **A1 那条写错**（用 `now` 而不是 `emoteSetAt`） | 重新追踪引发的重复申报把清除**无限推后** ⇒ 永远清不掉，且**行为上极难察觉**（看起来"时好时坏"） | **A1 专门守它** + 变异测试；实机第 2 项（走远回来）就是它的端到端验收 |
| 有人把 `LOOP` 判断删了 | `sit`/`dance` 被按期清除 ⇒ "它坐在那里"被破坏 | **A2 守它** + 实机第 6 项 |
| `emoteReported` 守卫被删 | 控制器每帧发包（~60/s） | 代码注释写明理由；实机第 9 项查日志/看网络异常 |
| 申报依赖"实体被渲染" | 生命周期内无人看的一次性表情会残留 ⇒ 下次有人靠近时**重播一次**再自愈 | **已知残留**（设计 §4）；CG 场景不成立（CG 期间相机锁在末身上、且锁输入） |
| 受理范围不限（用户裁定） | 改装客户端可谎报长度提前清掉共享 NPC 的表情（纯观感） | **已接受**；只做"无效输入不产生行为"（`durationTicks <= 0` 忽略） |
| 用户当前测试实例是**旧版** | 修完在新代码里，旧整合包实例不会有变化 | 交付时明确告知：要验需用新构建的 jar |

## 实施期修订（执行时与原计划的偏离，逐条留档）

| # | 原计划 | 实际做法 | 原因 |
|---|---|---|---|
| 1 | 批次① 先提交、T9 再派审查 | **审查放在提交之前** | 让 Critical/Important 在提交前修掉；已在开工时说明 |
| 2 | T1：`NpcEmoteExpiryHandler` 用**私有构造** | **public 构造** | 由 `BeLoongCore` 以**实例**注册到事件总线（项目惯例）⇒ 私有构造编译不过。T1 的构建抓不到（那时还没人构造它），T3 才暴露 |
| 3 | T1：加 `!(npc.level() instanceof ServerLevel)` 检查 | **未加** | `Level#getGameTime()` 在 `Level` 上就有 ⇒ 该检查不可达、无意义 |
| 4 | T4 第 5 步：写进"类注释里**表情系统**那一节" | 写进 **`DATA_EMOTE` 字段自己的 javadoc** | `NpcEntity` 的类注释**没有**那一节（只有：原版机制 / 定义性语义 / isNoAi / 身朝 / 多人兼容 / 子类必须提供）。字段自身的生命周期说明才是最该被看到的地方 |
| 5 | T4：`animation.length()` 当 int 用 | **`(int) Math.ceil(animation.length())`** | 验证期发现 `Animation#length()` 返回 **double**（tick）。用 ceil 而非强转：控制器判定"播完"用 `tickCount - start >= length()` ⇒ 实际播放时长正是 `ceil(length)`，这样服务端到期时刻与客户端真正停下的那一 tick 对齐（验收第 1 项的 N=119 也由此而来） |
| 6 | T6 的 A3：断言"文件里不出现硬编码 `120`" | 断言**申报表达式的形状**（`Math.ceil(animation.length())`） | 原判据太钝：`NpcEntity` 里本来就有别的 `120` ⇒ 会**假失败** |
| 7 | T7 只做四套验证 + 提交 | 审查后又修 **I-1 / I-2 / I-3 / M-1 / M-7 / M-9**，并收紧探针 **A1**（审查 X-1） | I-3 是新代码里的真缺陷（静态表跨存档活到进程结束）；M-7 是渲染路径异常会冒到渲染循环；**M-1 会让实机验收假失败** |
| 8 | （无） | 实机日志分析发现**可观测缺口** ⇒ 补"真正清除"时的 INFO（提交 `10c93bf`） | 清除原本无日志 ⇒ 验收第 2/3/4/5 项**只能靠眼睛看**，而这次修复的本质恰是"让一个看不见的字段回到空" |

**实机未能覆盖的一项**：T5 的 `Ensured.spawned=false` 路径（"分身仍在 ⇒ 不重播 CG"）
只在**对账器下一轮巡检之前**的窄窗口可达 —— 实机里一撤销进度，对账器约 3 秒内就把孤儿分身删了
（日志 `15:58:02` 撤销 → `15:58:05` WARN），于是重新触发时是新建分身、CG 照常播放（**那是正确的**）。
⇒ **T5 靠代码审查与实现一致性确认，不是靠实机**。

## 实机验收清单（交给用户）

| # | 操作 | 期望 |
|---|---|---|
| 1 | 触发一次 CG（或 `/beloong npc @e[type=beloong:mo,limit=1] play descend`） | 日志出现**一条** `emote 'descend' on entity.beloong.mo will be cleared in N ticks`，其中 **N ≤ 120（通常 116~119）** —— 日志打的是 `deadline − now`，而申报到达时已经过了几 tick，所以**不会正好是 120**（按"正好 120"去核会假失败） |
| 2 | **走远 >160 格再回来** | **不再重播 descend** ← **核心验收**。日志侧的关键判据：一旦字段被清掉，玩家走远回来**不会**再出现新的 `will be cleared in …` 行（若字段没清，每次重新追踪都会再报一次 ⇒ 会多出**对不上 CG 触发**的申报行） |
| 3 | 换维度 / 传送后回来 | 不再重播 |
| 4 | 断线重连 | 不再重播 |
| 5 | 第二客户端中途加入 | 不重播 |
| 6 | **姿态回归**：`play sit` ⇒ 走远回来 | **仍然坐着**（LOOP 不受影响） |
| 7 | **同名二次点播**：`play descend` 播完 ⇒ 再 `play descend` | **还能播** |
| 8 | **换表情作废旧计划**：一次性表情播完前立刻 `play sit`，等过 6 秒 | `sit` **没有被清掉** |
| 9 | 查 `latest.log` | 无异常、无 WARN 噪声 |
| 10 | **T5 专项**：`/advancement revoke @s only beloong:npc/root` 后重新走进触发区（分身仍在） | **不重播 CG**（行为已符合 `:640` 的注释） |

## 下一步

计划批准后，用 `task-management` 技能从**批次① T1** 开始执行。
