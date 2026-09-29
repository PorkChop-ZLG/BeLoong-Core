# NPC 表情播放系统 实施计划

**目标：** 新增一条与 `state` 正交的「表情轴」（`npc play <targets> <动画名>`），并把 `sit`/`dance` 从状态枚举迁入表情系统；末额外合并 `mo.extra.animation.json`。

**架构：** `NpcEntity` 新增同步字段 `DATA_EMOTE`（`EntityDataSerializers.STRING`），并注册**两个**动画控制器（`main` 状态 / `emote` 表情，**后注册者胜**，播表情时 `main` 主动 `stop()`）。客户端负责"动画是否存在"的预检与"一次性动画播完"的判定（时间判定）；服务端只负责设置/清除名字。回落到状态动画是**隐式**的，不需要保存"原动画"。

**实施路径：** A. **加法优先 · 两次提交** —— ① 先纯加表情轴（姿态原样保留，每步可编译可实机）② 再纯删姿态与两态。资产改动单独一个提交。

**设计依据：** `docs/plans/2026-09-29-npc-emote-system-design.md`（已批准，提交 `f05a848`）

> **关于 TDD：** 本项目**没有测试源集**（无 `src/test`），既有的验证范式是「**静态探针脚本 + `gradlew build` + 实机清单**」。
> 因此每个任务的红绿循环落成：**先写探针（预期失败）→ 改代码 → 探针转绿**。探针脚本放
> `D:\Minecraft\tools\YSMParser\probe_emote_*.py`（与既有资产探针同处），命令写死在计划里以便复跑。

---

# 第 ① 阶段：新增表情轴（纯加，不动姿态）

## T1：探针 S1 —— 资产一致性（先写探针，预期暴露"extra 未入库"）

**文件：**
- 新建：`D:\Minecraft\tools\YSMParser\probe_emote_assets.py`

**步骤：**
1. 脚本读 `src/main/resources/assets/beloong/animations/mo.animation.json` 与 `mo.extra.animation.json`，打印：
   - 两文件的动画名集合与**交集**（断言交集为**空**）；
   - 每条的 `loop` 值（断言"非 `true` 的只有 `attack`/`descend`"）；
   - `MoModel.java` 里出现的 fallback 路径字符串是否与实际文件名**逐字符一致**；
   - `git status` 里 extra 是否已被跟踪。
2. 运行，确认它当前**报出**"extra 未入库"（红）。

**验证：** `python D:\Minecraft\tools\YSMParser\probe_emote_assets.py`

---

## T2：资产提交（独立提交）

**文件：**
- 已有改动：`src/main/resources/assets/beloong/animations/mo.animation.json`（用户已删除重复的 `sit`）
- 新增：`src/main/resources/assets/beloong/animations/mo.extra.animation.json`（`sit` / `dance` / `descend`）

**步骤：**
1. `git add` 上述两个文件（**只加这两个**，按项目惯例逐文件 `git add`）。
2. 提交，信息说明：`mo` 的动画拆成主文件 + extra，`sit` 去重。

**验证：** `git status --short -- src/main/resources/assets/beloong/animations` 无输出；`python D:\Minecraft\tools\YSMParser\probe_emote_assets.py` 的"未入库"项转绿。

---

## T3：`MoModel` 合并 extra 动画文件

**文件：**
- 修改：`src/main/java/com/zonlong/beloong/client/model/MoModel.java`（资源常量区 `:127-132`，覆写区 `:138-150`）

**步骤：**
1. 仿现有写法新增常量：
   `EXTRA_ANIMATION = ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "animations/mo.extra.animation.json")`
2. 覆写 `public ResourceLocation[] getAnimationResourceFallbacks(MoEntity animatable)` 返回 `new ResourceLocation[]{ EXTRA_ANIMATION }`。
3. 写 javadoc，必须讲清三件事（都附行号）：
   - GeckoLib 原生机制（`GeoModel.java:76-84`、查找顺序 `:144-158`）；
   - **同名时主文件优先、extra 那条静默失效** ⇒ 故必须去重（`sit` 已删）；
   - `animations/` 下所有 json 在资源重载时被**全量烘焙**（`GeckoLibCache.java:113-133`）⇒ 这是"简单合并"的代价与前提。

**验证：** `.\gradlew.bat build --console=plain` 成功；探针 S1 的"路径一致性"项转绿。

---

## T4：`NpcEntity` —— 表情同步字段与 API

**文件：**
- 修改：`src/main/java/com/zonlong/beloong/entity/NpcEntity.java`（字段区 `:319-320`，`defineSynchedData` `:410-412`）

**步骤：**
1. 新增访问器：
   `private static final EntityDataAccessor<String> DATA_EMOTE = SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);`
   javadoc 注明：用原版 `STRING`（先例 `MinecartCommandBlock` 的 `DATA_ID_COMMAND_NAME`），**模组不能注册自定义序列化器**；空串 = 无表情。
2. `defineSynchedData` 追加 `builder.define(DATA_EMOTE, "");`
3. 新增方法（与 `state()` / `setState` 同构）：
   - `public String emote()` —— **双端可读**（客户端谓词要用）；
   - `public void setEmote(String name)` —— `if (level().isClientSide()) return;` 守门；
   - `public void clearEmote()` —— 委托 `setEmote("")`。
4. **刻意不写进** `addAdditionalSaveData` / `readAdditionalSaveData`（`:877-880` / `:902-905`）—— javadoc 写明"**表情不落盘**（设计 D5）"。

**验证：** `.\gradlew.bat build --console=plain` 成功。

---

## T5：`NpcEntity` —— `emote` 控制器 + `main` 让位

**文件：**
- 修改：`src/main/java/com/zonlong/beloong/entity/NpcEntity.java`（`registerControllers` `:1071-1096`）

**步骤：**
1. 新增客户端瞬态字段（不同步、不落盘，注释写明用途）：
   `private String emoteSeen = "";`、`private boolean emoteDone = false;`、`private int emoteStartTick = 0;`（**一次性动画的计时起点**，单位 tick）。
2. 在 `registerControllers` 里：
   - `main` 谓词**开头**插入：表情有效 ⇒ `state.getController().stop(); return PlayState.STOP;`
   - **之后**再 `controllers.add(new AnimationController<>(this, "emote", this.animationTransitionTicks(), …))`：
     - 无表情或 `emoteDone` ⇒ `state.getController().forceAnimationReset(); return PlayState.STOP;`
     - 否则：检测换名（`!emote().equals(this.emoteSeen)` ⇒ 重置 `emoteSeen` / `emoteDone=false`）；
       查动画存在性（见下），不存在 ⇒ 返回 `STOP`（**不做负缓存**，下帧再查，以便资源重载后自愈）；
       存在 ⇒ `LoopType == LOOP` 用 `thenLoop`，否则 `thenPlay`，并记录该动画的 `length()`；
       非 `LOOP` 时，若 `npc.tickCount - emoteStartTick >= anim.length()`（单位 tick）⇒ 置 `emoteDone = true` 并返回 `STOP`。
   - **注册顺序是承重结构**：在 `main` 与 `emote` 的 `add` 处各写一行醒目注释，引用
     `AnimationProcessor.java:80,107-131` 与 `AnimatableManager.java:202-208`，并写明"**不要调换**"。

**T5b（同一任务内）：存在性查询工具**
- 新建：`src/main/java/com/zonlong/beloong/client/model/EmoteAnimationLookup.java`
- 内容：`public static Animation find(NpcEntity npc, String name)` —— 经
  `Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(npc)` 取 `GeoEntityRenderer`，
  再 `getGeoModel()`（**`GeoEntityRenderer.java:79` 是 `public`**）拿到 `GeoModel`，
  最后 `model.getAnimation(npc, name)`（`GeoModel.java:144`，名字不存在时**静默返回 null**）。
  **必须 `try/catch`**：`GeoModel.java:160-165` 在**动画文件**缺失时**抛异常**；失败只记**一次**警告，返回 `null`。
  返回的 `Animation` 同时提供 `length()`（单位 tick）与 `loopType()`，供谓词的两条分支使用。

**验证：** `.\gradlew.bat build --console=plain` 成功。

---

## T6：`NpcCommand` —— `play` 与 `play stop`

**文件：**
- 修改：`src/main/java/com/zonlong/beloong/command/NpcCommand.java`（指令树 `:107-122`，helper 区 `:186-247`）

**步骤：**
1. 指令树里，在 `attack` 之后追加：
   ```
   .then(Commands.literal("play")
       .then(Commands.argument("animation", StringArgumentType.word())
           .executes(ctx -> play(npcs, StringArgumentType.getString(ctx, "animation"), src)))
       .then(Commands.literal("stop")
           .executes(ctx -> stopEmote(npcs, src))))
   ```
   （形状与 `attack <victim>` / `attack stop` 一致；`stop` 落在同一 token 位置的歧义说明**复用类注释已有段落**。）
2. 新增 `play(...)` / `stopEmote(...)` 两个 helper，照 `attack`/`stopAttacking` 的形状写：
   空目标 ⇒ `fail(source)`；成功 ⇒ `source.sendSuccess(Component.translatable("beloong.command.npc.play", n, 名字), true)`。
3. **成功文案措辞中立**：**不声称**该动画存在（服务端无法知道）—— javadoc 写明理由。
4. **不加补全**：`play` 的参数不接 `SuggestionProvider`，javadoc 写明"动画名是客户端资产数据，服务端不知道"，
   并与 `state` 的 `STATE_SUGGESTIONS` 形成对照。
5. `reset(...)`（`:222-234`）**不用改**（它调 `npc.resetToDefault()`，清表情放在 T7 的实体侧）。

**验证：** `.\gradlew.bat build --console=plain` 成功；探针 S3（指令树）通过。

---

## T7：语言键（`play` / `play_stop`）

**文件：**
- 修改：`src/main/resources/assets/beloong/lang/zh_cn.json`（指令键区 `:4-12`）
- 修改：`src/main/resources/assets/beloong/lang/en_us.json`（同区）

**步骤：**
1. 两份各新增两个键（**键集合必须完全一致**，本项目有先例：曾因漏删一个键被探针抓到）：
   - `beloong.command.npc.play` = `已让 %s 个 NPC 播放表情「%s」` / `Made %s NPC(s) play emote "%s"`
   - `beloong.command.npc.play_stop` = `已让 %s 个 NPC 停止表情` / `Made %s NPC(s) stop their emote`
2. 保持字母序（现有键是排好序的）。

**验证：** 探针 S2 的"两语言键集合一致"项通过。

---

## T8：构建 + 探针 + 提交 ①

**步骤：**
1. `.\gradlew.bat build --console=plain`
2. `python D:\Minecraft\tools\YSMParser\probe_emote_assets.py`
3. `python D:\Minecraft\tools\YSMParser\probe_emote_java.py`（T8 时只跑已实现的部分断言）
4. `python D:\Minecraft\tools\YSMParser\probe_emote_bytecode.py`（控制器注册顺序：`main` 在前、`emote` 在后）
5. 逐文件 `git add` 后提交 ①，信息说明"**新增表情轴（姿态未动）**"。

**验证：** 三步全绿；实机清单 B1–B5、B8、B10、B12（见文末）应已可跑。

---

# 第 ② 阶段：删除姿态轴

## T9：`NpcState` 收缩到两态

**文件：**
- 修改：`src/main/java/com/zonlong/beloong/entity/NpcState.java`

**步骤：**
1. 删 `SITTING("sitting", 2, false)`、`DANCING("dancing", 3, false)` 两个常量。
2. 删 `movementMode` 字段、构造参数与 `isMovementMode()` 方法。
3. **`IDLE("idle", 0, …)` 与 `FLYING("flying", 1, …)` 的 id 与名字一个字都不许动**（存档/同步兼容的关键）。
4. 重写类注释：去掉"两族"整节与 `isMovementMode` 的判据说明；**保留**"四种还原入口行为各不相同"那张表；
   追加一句"`sit`/`dance` 已迁入表情系统，见设计文档 `2026-09-29-npc-emote-system-design.md`"。

**验证：** 探针 S2 断言 `NAMES == ["idle","flying"]`、`IDLE=0`、`FLYING=1`。

---

## T10：`NpcEntity` / `NpcAttackGoal` 删姿态机制

**文件：**
- 修改：`src/main/java/com/zonlong/beloong/entity/NpcEntity.java`
  （`switchState` `:701-717` 的姿态分支、`exitPoseIfNeeded` `:633-637` 及其调用点 `:572`、`:602`、
  `sitAnimationName` `:172-174`、`danceAnimationName` `:176-178`、`stateAnimationName` `:190-196`）
- 修改：`src/main/java/com/zonlong/beloong/entity/ai/NpcAttackGoal.java`（状态门）

**步骤：**
1. 删 `exitPoseIfNeeded()` 整个方法与两个调用点（连带 `moveTo`/`attack` 的 javadoc 里相关句子）。
2. 删 `switchState` 里的 `else if (!next.isMovementMode()) { … }` 分支（连带方法 javadoc 的"进入姿态…"一段）。
3. 删 `sitAnimationName()` / `danceAnimationName()`；`stateAnimationName` 的 switch 只剩 `FLYING` 与 `IDLE` 两支。
4. `resetToDefault()`（`:838-847`）追加 `this.clearEmote();`，并更新方法 javadoc（现在是"清四样"）。
5. `NpcAttackGoal`：删掉那道"只有移动模式才运行"的状态门，并把注释里"姿态"相关理由改成"状态门已随姿态机制一并删除"。

**验证：** 探针 S2 断言 `isMovementMode`/`exitPoseIfNeeded`/`sitAnimationName`/`danceAnimationName`/`SITTING`/`DANCING` **全仓零命中**。

---

## T11：子类与注释

**文件：**
- 修改：`src/main/java/com/zonlong/beloong/entity/DihuangLoongEntity.java`（`:89-96`）
- 修改：`src/main/java/com/zonlong/beloong/entity/MoEntity.java`（`:131-134` 一带的注释）

**步骤：**
1. `DihuangLoongEntity`：删 `sitAnimationName()` / `danceAnimationName()` 两个覆写（其 `sit`/`dance` 本就在自带单文件里，`play sit` 直接可用）。
2. `MoEntity`：把"`dancing` 会用基类默认名而资产里没有 ⇒ 会塌成 T-pose"那段注释改写为
   "`sit`/`dance` 现在由 `mo.extra.animation.json` 提供，用 `play sit` / `play dance`"。

**验证：** `.\gradlew.bat build --console=plain` 成功。

---

## T12：语言键删除 + 旧文档注记

**文件：**
- 修改：`zh_cn.json` / `en_us.json`（删 `beloong.npc.state.sitting`、`beloong.npc.state.dancing`，各 `:155,:158` 一带）
- 修改：`docs/plans/2026-09-27-npc-state-system-design.md`（加取代注记）
- 修改：`docs/plans/2026-09-27-npc-state-system-plan.md`（同）

**步骤：**
1. 两语言文件各删两个键（**必须同时删**，键集合一致性由探针守）。
2. 两份旧文档加一行醒目注记：`⚠️ 2026-09-29 起 sit/dance 已被表情系统取代，见 2026-09-29-npc-emote-system-design.md`。

**验证：** 探针 S2：`state.sitting`/`state.dancing` 已消失、两语言键集合一致。

---

## T13：全量构建 + 全探针 + 提交 ②

**步骤：**
1. `.\gradlew.bat build --console=plain`
2. `python D:\Minecraft\tools\YSMParser\probe_emote_assets.py`
3. `python D:\Minecraft\tools\YSMParser\probe_emote_java.py`（全部断言）
4. `python D:\Minecraft\tools\YSMParser\probe_emote_bytecode.py`
5. 逐文件 `git add` 后提交 ②，信息说明"**状态收缩到 idle/fly，姿态机制整体删除**"。

**验证：** 三个探针 + 构建全绿。

---

# 探针脚本清单（本计划要求新建）

| 探针 | 路径 | 断言 |
|---|---|---|
| **S1 资产** | `D:\Minecraft\tools\YSMParser\probe_emote_assets.py` | 两文件动画名**交集为空**；`loop` 值分布；`MoModel` fallback 路径与实际文件名**逐字符一致**；extra 已被 git 跟踪 |
| **S2 Java 源码** | `D:\Minecraft\tools\YSMParser\probe_emote_java.py` | `NpcState` 只剩 2 常量且 `IDLE=0`/`FLYING=1`；`NAMES==["idle","flying"]`；死代码零命中；`DATA_EMOTE` 用 `STRING` 且**不在** `addAdditionalSaveData`/`readAdditionalSaveData`；两语言键集合一致、旧键已删、新键已在 |
| **S3 字节码** | `D:\Minecraft\tools\YSMParser\probe_emote_bytecode.py` | `javap -c -p` 反编译 `NpcEntity.registerControllers`：`"main"` 的 `add` 在 `"emote"` 之前；`NpcCommand` 指令树含 `play` 与 `play→stop` |

---

# 实机验收清单（用户执行）

| # | 操作 | 期望 |
|---|---|---|
| B1 | `play sit` | 坐下并保持 |
| B2 | `play dance` | 跳舞并保持 |
| B3 | `play attack` | 打一遍后**自动回到** idle/walk |
| B4 | `play descend` | 11.25 秒后自动回落 |
| B5 | idle 态 `play fly` | 跨状态播放成立 |
| B6 | `state flying` + `play sit` | "坐着飞"，切 state **不清**表情 |
| B7 | `move` + `play sit` | 坐着滑行（有意接受） |
| B8 | `play stop` | 立刻回落、过渡自然 |
| B9 | `reset` | 同时清移动 + 攻击 + 状态 + 表情 |
| B10 | `play 不存在的名字` | 无变化、无报错、**日志无 ERROR/堆栈** |
| B11 | A 端 `play attack` 播完后 B 端加入 | B 从头再播一遍（已接受）、不崩 |
| B12 | `play sit` 后退出重进 | 站起来（不落盘） |
| B13 | 旧档（`BeloongState="sitting"`）载入 | 回落 idle、不崩 |
| B14 | 表情在播时 F3+T | 不崩、继续播 |
| B15 | 地黄龙 `play sit` / `play dance` / `play attack` | 均生效（单文件内自带） |

---

# 已知残留风险（记录，不修 —— 来自设计 §六 C）

1. 中途加入者会重播一次性表情；2. 旧档里坐着的 NPC 重登后站起；3. `play` 无补全、拼错无反馈；
4. 名字带空格无法播放；5. 未知名字每帧重查（刻意）；6. **版本敏感点**：一次性判定依赖 `Animation.length()` 的单位是 tick ——
将来升 GeckoLib 时若该单位或计时方式变化，`attack`/`descend` 的自动回落会失准（过早回落或定格）。
