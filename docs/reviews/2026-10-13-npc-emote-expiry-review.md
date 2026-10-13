# NPC 一次性表情"播完即清"（客户端申报长度 + 服务端到期清除）代码审查报告

**审查基线**：`NPC` 分支工作区（`HEAD = a42c7d7`，即设计 `18ef949` + 计划 `a42c7d7` 之后）
**被审改动**：**未提交**的工作区改动 —— 新增 `emote/NpcEmoteExpiryHandler.java`、`emote/NpcEmoteDurationPayload.java`；
修改 `entity/NpcEntity.java`、`BeLoongCore.java`、`npcstory/NpcStoryHandler.java`（`git diff --stat`：3 改 + 1 新目录）。
审查时 `git status` 里**没有**别的未跟踪文件（用户提到的另一路工作 `data/beloong/chatbox/theme/minimal.json` 不在工作区里）⇒ 不存在"把别人的改动算进来"的风险。
**日期**：2026-10-13
**审查范围**：**只审 `src/`**（项目硬规矩）。`docs/` 是**被审对象**（本项目头号缺陷面就是"注释/文档声明与代码实际行为脱节"）——
本次核对 `docs/plans/2026-10-13-npc-emote-oneshot-expiry-{design,plan}.md` 与各处 javadoc/注释是否在讲同一件事。
智能体自写的 `D:\Minecraft\tools\YSMParser\*.py` **不算审查发现**（唯一例外见 §四，且只有一条）。
**方法**：单路逐条核验（主审），每条断言都落到证据上：
① 本仓库源码；② **GeckoLib 4.7.5.1 sources jar**（`~/.gradle/caches/.../geckolib-neoforge-1.21.1-4.7.5.1-sources.jar`，
与本项目实际依赖同版本）；③ **NeoForge 21.1.236 sources jar**；
④ **1.21.1 + NeoForge 21.1.236 反编译源码**（`开源模组参考文件\DragonSurvival\build\neoForm\neoFormJoined1.21.1-20240808.144430\steps\unzipSources\unpacked`，下称"MC 源码"）。
机器验证：`.\gradlew.bat build` **exit 0**；`npc_emote_expiry_invariants.py` **4/4 PASS**。
**结论**：**Critical 无**；**3 Important**（1 条在 `src/` 的注释里、1 条是设计文档与 diff 相反、1 条是"关服无清理"的残留面）；
**9 Minor**（M-1…M-9）；另 1 条**不在本次范围**的脚本假通过。
四条不变量 **I1 / I2 / I3 / I4 逐条核对为成立**（见 §五），`handleServer` 的四条校验**不可能抛异常**，侧别与包注册方向正确。
**本次改动尚未实机验收**（80 个日志文件里 `will be cleared in` **0 命中**，见 §五 J）⇒ 本报告的方法论是**静态 + 构建 + 不变量脚本**，不含实机证据。

> 编号：`I-` = Important，`M-` = Minor，`X-` = 不在本次范围。每条都给出"文件:行号 + 问题 + 证据 + 建议处置"。

---

## 范围与方法

- **只审 `src/`**：`docs/` 作为被审对象，但 `docs/` 里的问题只在"声明与代码/资产/事实不符"时才算发现。
- **不算发现**：`D:\Minecraft\tools\YSMParser\npc_emote_expiry_invariants.py`（智能体自写工具）。
  按用户口径，**只**在"某条断言会假通过"时于 §四 提一句（本次确实找到两条，见 X-1）。
- **证据口径**：GeckoLib 行号指上述 sources jar 解出的源码；原版/NeoForge 行号指 MC 源码树或 NeoForge sources jar。
  两者都与实机运行的版本一致（`geckolib 4.7.5.1`、`neoforge 21.1.236`）。
- **本次改动要解决什么**（用户已定位的根因，我不重新证明、但逐环抽检过）：
  CG 播动画 = 写同步字段 `DATA_EMOTE`；"播完了没有"只记在客户端瞬态字段 ⇒ 客户端重建实例（`beloong:mo` 的
  `clientTrackingRange(10)` = 160 格，`ModEntities.java:105` 实测确认）就从头再播。修复 = 客户端申报长度 + 服务端到期清字段。

---

## 一、Critical（0）

无。

- **没有崩溃面**：`handleServer` 的四条校验在**任何输入**下都不抛（逐条枚举见 §五 F）；新增的 C2S 包发点在
  **客户端渲染路径**上，而 `PacketDistributor.sendToServer` 只要求 `FMLEnvironment.dist.isClient()`
  且 `Minecraft.getInstance().getConnection() != null`（NeoForge `PacketDistributor.java:38-41`）——
  该谓词只可能从客户端渲染被调用（`GeoModel.handleAnimations:190-226` 只被 6 个客户端渲染器调用，
  `GeoEntityRenderer:270`），见 §五 E。
- **没有侧别错用**：`emote` 包两个新类只引用通用类（`ServerPlayer` / `Entity` / `IPayloadContext`），
  专用服务端加载不会 `NoClassDefFoundError`；注册方向是 `playToServer`（`BeLoongCore.java:183-186`）。
- **没有"永远清不掉"**：I1 成立（到期只用 `emoteSetAt`，全仓只有一处算 deadline，见 §五 A）。
- **没有并发面**：`schedule` / `cancel` / `onServerTick` 全在服务端主线程（NeoForge 默认
  `HandlerThread.MAIN` ⇒ `MainThreadPayloadHandler` ⇒ `context.enqueueWork`，见 §五 D）；
  `List.copyOf` 快照 + 先删后清 ⇒ 即使 `clearEmote()` 回环调用 `cancel()` 也不会踩
  `ConcurrentModificationException`，也不会重复处理同一条（`:112-116` 的 `entry == null` 分支就是为它留的）。

---

## 二、Important（3）

**I-1｜`NpcEntity` 的"客户端瞬态字段"小节仍写"**这三个字段**"，本次新增 `emoteReported` 后已是**四个**（`src` 内，★必须现在改）。**
位置：`src/main/java/com/zonlong/beloong/entity/NpcEntity.java:1670`
（`// 这三个字段只被 emote 控制器在**客户端**读写，因此不进同步数据、也不进存档。`）。
问题：同一批改动里，`onSyncedDataUpdated` 的 javadoc 已经**正确**改成了"这四个字段"（`:905`），
却漏了这条**小节表头**——正是本项目"改主表没扫旁注"的典型形态。
证据：该小节内实际四个字段 —— `emoteSeen:1681`、`emoteDone:1684`、`emoteStartTick:1687`、`emoteReported:1699`；
全仓 grep"三个字段/这三个"后，与 `NpcEntity` 相关的**只剩这一处**（其余命中在 `CgContext:23`、`Config:216`、
`Mixin/*:31` 等与本功能无关处）。
**建议处置**：`:1670` 改"这四个字段"。

**I-2｜设计文档 §6.2 仍把 `NpcStoryHandler` 那条写成"本次不修"，而本次改动已经把它修了（声明与 diff 相反）。**
位置：`docs/plans/2026-10-13-npc-emote-oneshot-expiry-design.md:228-232`。
问题：原文"…但调用方在返回**既有**分身时也会无条件 `cg.play` ⇒ …**记录为独立发现，本次不修**"，
与本 diff 直接矛盾：`NpcStoryHandler.java:151-161`（`if (!ensured.spawned()) continue;`）、
`:635`（`record Ensured(NpcEntity npc, boolean spawned)`）、`:715`（`return new Ensured(npc, true)`）。
证据：计划 `:8` 记的用户裁定是"`NpcStoryHandler:640` 那条**改代码让行为符合注释**"（后一次裁定推翻了设计文档的"不修"）。
**加重情节**：`plan.md:183` 列的 T9 回填清单只有两条（§3.2 的 C4 补 `emoteReported`、§4 的溢出那句收紧），
**不含这一条** ⇒ 不处理就会长期留错，且它是"本次范围"的正式声明（后来者会据此以为 T5 越权）。
**建议处置**：§6.2 该条改成"已按用户裁定于 T5 修复：`ensureDouble` 返回 `spawned`，复用分支不再播 CG
（`NpcStoryHandler.java:156-161`）"，并把这条加进 T9 的回填清单。

**I-3｜`PENDING` 没有任何"关服清理"；一条表项一旦活过关服就**永远不会到期**（时钟冻结），会把整只 `ServerLevel` 一起留住。**
位置：`src/main/java/com/zonlong/beloong/emote/NpcEmoteExpiryHandler.java:56`（`static final Map`，零清理钩子）、
`:107-131`（唯二的移除路径：到期 / `isRemoved()`）、`:122`（到期判据是 `npc.level().getGameTime()` —— **实体自己那个 level 的**时钟）。
问题：表项的寿命 = "实体那个 level 的时钟走到 deadline" 或 "实体被移除"。**两条在关服后都停摆**：
时钟冻结 ⇒ `now < deadline` 恒真；`isRemoved()` 若在关服时没被置真就恒假 ⇒ 表项永久留在静态表里，
强引用链是 `Entry → NpcEntity → Level → ChunkSource/ChunkMap`（一整个已卸载世界的关卡数据）。
证据：① 全仓无 `ServerStoppedEvent` / `LevelEvent.Unload` 监听，`BeLoongCore.java:130` 只注册了实例本身；
② 关服路径 `MinecraftServer.stopServer()`（MC 源码 `server/MinecraftServer.java:592-632`）→ `LevelEvent.Unload` +
`ServerLevel.close()`（`:1692-1695`）→ `PersistentEntitySectionManager.close()/saveAll()`
（MC 源码 `world/level/entity/PersistentEntitySectionManager.java:283-303`）**只保存、不置 `RemovalReason`**；
③ 时钟：`DerivedLevelData.getGameTime()` 委托给主世界数据（`:34-35`），而 `ServerLevel.tickTime()`
只在 `this.tickTime` 为真的那一只 level 里自增（`:444-447`）⇒ 旧 level 的 `getGameTime()` 永久冻结。
**我确认到的边界（必须写清楚）**：`saveAll()` 对 `chunkVisibility == HIDDEN` 的区块会走
`processChunkUnload → unloadEntity`（`:234-237`）置 `UNLOADED_TO_CHUNK`；而关服前 `playerList.removeAll()`
（`MinecraftServer.java:603`）已经把玩家摘掉 ⇒ **如果那一刻 `chunkVisibility` 已经翻成 HIDDEN，实体就会被置 `isRemoved`，
于是下次开服的第一个 tick 会把它扫掉（不残留）**。这一步依赖区块可见性的更新时机，我无法静态证明。
⇒ 结论是：**要么真泄漏，要么虚惊；但代码里根本没有"关服清理"这条路径，这个不确定性本身就该消掉**。
影响面：单机"退出到标题 → 进另一个世界"（同 JVM）；命中窗口 = 表情起点后 `duration` 内退出
（`descend` 是 6 秒，CG 期间退出即命中）。
**建议处置**（3 行，消掉整个不确定性）：`@SubscribeEvent public void onServerStopped(ServerStoppedEvent e) { PENDING.clear(); }`
（或 `LevelEvent.Unload` 按 level 摘）。若选择不修，则必须把"关服残留由下次开服第一 tick 的 `isRemoved` 扫掉"
写成**已被证实的前提**——我建议直接 clear。
**定级说明**：本项目既往报告把"无状态泄漏"与 Critical 并列（`2026-09-30-cg-system-code-review.md:40,354`）。
我没有定 Critical，因为触发需要"在 6 秒窗口内退出"且残留是否发生尚未证实；但它是**结构性**的，且修法只有 3 行。

---

## 三、Minor（9）

**M-1｜计划/设计的实机验收第 1 项要求日志精确出现"cleared in **120** ticks"，而代码永远打不出 120。**
位置：`docs/plans/2026-10-13-npc-emote-oneshot-expiry-plan.md:207`、`...-design.md:195`；
代码 `NpcEmoteExpiryHandler.java:86-89`。
问题：日志打的是 `deadline - npc.level().getGameTime()`，而 `deadline = emoteSetAt + duration`（`:104`）——
**受理时刻恒晚于 `emoteSetAt` 至少 1 tick**（客户端要先收到同步包才会申报，再走一趟网络回来）
⇒ 实机看到的是 **119 或更小（通常 116~119）**，永远不是 120。验收人按字面比对会误判成"修复没生效"。
**建议处置**：两处把期望改成"**≤120** 的一条（实测通常 116~119；它不是常量，等于 120 − 申报往返 tick 数）"。
（这条**只改文档措辞**，代码不动。）

**M-2｜长度的两条极端边界没有在注释里交代，而它们都会让"永不清除"以另一种形式复活。**
位置：`src/main/java/com/zonlong/beloong/entity/NpcEntity.java:1797-1801`、`emote/NpcEmoteDurationPayload.java:97-100`。
问题与证据：
① **`length() == 0`**：`BakedAnimationsAdapter.java:59` 是 `animation_length * 20d` 或哨兵，作者显式写
`"animation_length": 0` 即得 0 ⇒ 客户端 `Math.ceil(0) = 0` ⇒ 服务端 `durationTicks <= 0` **一律拒收**
⇒ 计划永远建不起来 ⇒ `DATA_EMOTE` 永不清；只是因为客户端自己的收工判据（`:1814` 的
`tickCount - emoteStartTick >= 0`）也会立刻置 `emoteDone`，**重播不可见**，所以只算边界注记。
② **`length() == Double.MAX_VALUE`**：同一处 `:252` —— `calculateAnimationLength` 在算出 0 时**返回
`Double.MAX_VALUE` 哨兵**（即"没写 `animation_length` 且骨骼关键帧全在 0 时刻"）。
`(int) Math.ceil(Double.MAX_VALUE)` 按 Java 窄化语义**饱和**成 `Integer.MAX_VALUE` ⇒ 载荷合法、计划 ≈3.4 年后到期、
日志打 `2147483647`。不会溢出、不会崩，但语义是"永不清理"。
③ 现状可达性（我实测过）：本仓 `src/main/resources/**/*.animation.json` **3 个文件 15 条动画全部带
`animation_length`** ⇒ ② 当前不可达；① 需要显式写 0（现有资产也没有）。**但数据包作者可以引入**。
**建议处置**：在 `:1797-1801` 那句 `ceil` 注释后面补一句"`length()` 可能是 0 或 `Double.MAX_VALUE`
（GeckoLib 哨兵，`BakedAnimationsAdapter.java:59,252`）——前者的申报会被服务端按无效输入丢掉，后者会饱和成
`int` 最大值"；若要更稳，可在申报端夹一下（`Math.max(1, Math.min(ticks, 上限))`，非必需）。

**M-3｜"申报依赖实体被渲染"这条已写进文档，但两处结论把它说轻了：申报其实**依赖视锥**，且"残留"的实际表现是**截断**而不是"重播一次"。**
位置：`...-design.md:117`（§3.1 性质表"**不依赖视线/距离**"）、`:157`（§4"没人渲染过…下次有人靠近时**重播一次**，
同时起表申报 ⇒ 随后被清"）；`plan.md:199`（风险表同一句）。
问题与证据：谓词只在**渲染时**跑（`GeoModel.handleAnimations:190-226`，只被 6 个客户端渲染器调用），
而原版实体渲染**先做视锥剔除** —— `LevelRenderer.java:1009-1010`
（`for (Entity entity : this.level.entitiesForRendering()) { if (this.entityRenderDispatcher.shouldRender(entity, frustum, ...)) ... }`）
⇒ 屏幕外/背后的 NPC **既不"起表"也不申报**，
所以"不依赖视线"只对 CG 场景成立（`design.md:117` 自己的理由也只写了 CG 那一句）。
更关键的是第二处：到期时刻锚在 `emoteSetAt`（I1 的必然结果），所以"很久以前设过、刚被第一次看到"的这条表情，
其 deadline **已经过期**⇒ 服务端**在申报到达的下一个 tick 就清掉**，客户端只会闪 **1~2 帧**（不是"重播一次"）。
**建议处置**：`:117` 改成"不依赖**距离/区块加载**；但申报点挂在渲染上，**屏幕外不会申报**（CG 期间相机锁在末身上，故 CG 场景不受影响）"；
`:157`/`plan.md:199` 的期望改成"下次有人靠近时**闪一下**（1~2 帧）并立刻被清 ⇒ 自愈"。这条**只改文档**——
代码行为（早清）比文档描述更好，不必改代码。

**M-4｜计划里两处"实施细节"与代码不一致（都无害，但属于同族的"文档没跟上"）。**
位置与证据：
① `plan.md:53-58`（T1 步骤 4）要求 `!(npc.level() instanceof ServerLevel) ⇒ 只丢计划`，
`NpcEmoteExpiryHandler.java:117-121` **没有**这道检查（只有 `isRemoved()`）。当前**不可达**：
`schedule` 的唯一调用点 `NpcEmoteDurationPayload.java:89-105` 拿的是 `player.serverLevel().getEntity(...)`
⇒ 表里只可能是服务端实体；将来若有人从别处调 `schedule` 才会踩到（且 `ClientLevel.getGameTime()` 也不会抛，
最坏是空转）。**处置**：要么补一行 `|| !(npc.level() instanceof ServerLevel)`，要么把计划那行改掉。
② `plan.md:47` 写"私有构造"、`plan.md:53` 写 `static void onServerTick`；代码是 `public` 构造器
（`:70-71`）**实例方法**（`:106-107`）。代码自己的 javadoc（`:62-69`）**已解释**为什么偏离计划
（注册方式与项目惯例），所以这是"计划文本过期"而不是缺陷。**处置**：T9 时把计划这两行改成实际形态。
**定级理由**：两条都不改变行为，故 Minor；但它们是本项目"注释/计划与实际形态脱节"的常客，值得顺手对齐。

**M-5｜设计文档与计划里的 `NpcEntity` 行号是**改动前**的快照，本次改动把它们整体推移了。**
位置与证据：设计 `:33`（`DATA_EMOTE`：`:378` 定义、`:502` 默认、`:848` 写）、`:34-36`（控制器 `:1705-1747`、
`emoteDone` `:1635`）；计划 `:16-19` 的事实表（`:378`/`:502`/`:839-849`/`:1705-1747`）。
实测当前行号：定义 `:389`、默认 `:513`、写 `:890`、`emoteDone:1684`、控制器 `:1766-1825`、`setEmote:881-896`。
（这些都是**改动前**写下的正确行号，不是错写；只是 diff 后失效。既往报告 `2026-09-30-…:146-149` 对同类漂移按 Minor 处理。）
**建议处置**：T9 一并把两处行号刷成新值，或改成"方法名 + 不写行号"的写法（本仓已有此惯例的苗头）。

**M-6｜D6 那条"已接受风险"的入口比注释写的更宽：**不需要改装客户端**，一个资源包就能改长度，而且**最后一个申报者说了算**。**
位置：`emote/NpcEmoteDurationPayload.java:33-37`（"改装过的客户端可以谎报一个长度…**因此这里不做防伪栅栏**"）、
`NpcEmoteExpiryHandler.java:84`（`PENDING.put` 直接覆盖 ⇒ 每个实体一条、后到者胜）。
问题与证据：长度来自客户端烘焙的资产（`BakedAnimationsAdapter.java:59`，`assets/*/animations/*.json`）⇒
**任何玩家装一个改了 `animation_length` 的资源包**（完全合法的客户端行为）都会"如实申报"一个与服务端预期不同的长度；
配合"后到者胜"，一个把 `descend` 改成 30 秒的玩家会把**共享 NPC** 的表情多留 24 秒（或反过来提前清掉）。
证据链与"谎报"完全同构，只是动机不是作弊 ⇒ **D6 的结论（接受）不变，但风险描述应当补上这条**。
**建议处置**：`:33-37` 补一句"这条风险的入口不只改装客户端：**资源包即可改长度**（资产在客户端烘焙），
且同一 NPC 的最后一个申报者决定到期时刻（`PENDING.put` 覆盖）"。**代码不必动**（D6 已裁定接受）。

**M-7｜新增的发包点在**渲染路径**上、且**没有 try/catch**，而紧邻的查表调用是刻意 `catch (Throwable)` 的。**
位置：`NpcEntity.java:1802`（`PacketDistributor.sendToServer(...)`）；对照 `client/model/EmoteAnimationLookup.java:109-116`。
问题与证据：`EmoteAnimationLookup` 的类注释（`:57-61`）明确写了"宁可退化成查不到，也不要让异常把服务端/渲染带崩"，
于是它捕 `Throwable`；而同一谓词里新增的**发包**没有任何保护。`PacketDistributor.sendToServer` 内部有
`Preconditions.checkState(FMLEnvironment.dist.isClient())` 与 `Objects.requireNonNull(Minecraft.getInstance().getConnection())`
（NeoForge `PacketDistributor.java:38-41`）⇒ 理论上两条能抛（服务端执行 / 连接为 null）。
我**没能构造出可达路径**（谓词只在客户端渲染跑；`ClientLevel` 存在时连接必然非 null；
且 `ClientPacketListener.send` 自身对已断开有 `isConnected()` 保护）——所以这是 M 而不是 I。
另外 `emoteReported = name`（`:1796`）在发包**之前**赋值 ⇒ 万一发包抛了，这一轮就再也不会重报（要等下一次
`onSyncedDataUpdated`/重新追踪才自愈）。
**建议处置**（零成本加固）：把 `:1796-1802` 包进 `try { ... } catch (Throwable t)`（失败就清回 `emoteReported`，留一条**一次性**英文 WARN），
或至少把 `emoteReported = name;` 挪到发包成功之后。**不阻塞提交**。

**M-8｜2026-09-29 的表情设计文档里"中途加入的观察者会重播（**已接受**）"已被本次修复推翻，但没人给它加注。**
位置：`docs/plans/2026-09-29-npc-emote-system-design.md:214`（"…他会**从头再播一遍**（已接受；与 DS 同性质）"）、
`:385`（"1. 中途加入的观察者会重播一次性表情（D5 的必然结果）"）。
问题与证据：本次设计文档自己写明"本次修复把这一整类（含那条已接受项）一起收掉"（`...-design.md:79-81`）。
`docs/NPC系统总设计.md:1170-1173` 那张表是本仓记录"某份计划被取代/部分取代"的正式位置，而这条**不在**里面；
T9 只计划在活文档"表情一节"补新机制（`plan.md:182`），没有"给 09-29 那条加注"这一项。
**建议处置**：在 `NPC系统总设计.md` 的 supersession 表（`:1172` 那一行）补一句
"`2026-09-29-npc-emote-system-design.md:214/385` 的'中途加入会重播（已接受）'已被 2026-10-13 的
'播完即清'推翻"，或直接在 09-29 那两行加"（已被 2026-10-13 取代）"。

**M-9｜设计与计划都写"既有的 **6** 个 `clearEmote()` 调用点"，代码里实际是 **8** 个（按触发路径算是 7 类，设计列的 6 类还漏了 `swing`）。**
位置：`...-design.md:37-40`（§2.1 第 3 环；`:168` 的"不改任何既有 `clearEmote()` 调用点"）、
`plan.md:29`（"既有的 6 个 `clearEmote()` 调用点语义不变"）、`plan.md:169`（提交信息要求写"未动既有 6 个"）。
证据（我逐个点名）：`NpcEntity.moveTo:731`（move）、`attack:767`（attack）、`swing:825`（**swing**，设计没列）、
`setState:965`（state）、`resetToDefault:1140`（reset），加上 `NpcCommand:398`（`play stop`）、
`MynpcCommand:241`（`play stop`）、`NpcDialogueReplyPayload:105`（对话 `stop_emote`）= **8 个调用点 / 7 类触发源**。
问题：这是一条**可机器核对的计数**，写成 6 会让后来者用错误的判据去复查"有没有动过既有调用点"。
（先例：`2026-09-30-cg-system-code-review.md` 的 M-4 就是同型的"行数/条数对不上"。）
结论不受影响：本次确实**没有**改动任何一个既有调用点，只新增了 `NpcEmoteExpiryHandler:128` 一处。
**建议处置**：把三处的"6 个"改成"8 个调用点（7 类触发：move / attack / swing / state / reset / play stop / 对话 stop_emote）"，
并给 §2.1 那张调用点清单补上 `swing`。

---

## 四、不在本次范围（顺带发现）

**X-1｜`npc_emote_expiry_invariants.py` 的 A1 与 A4 会**假通过**（工具脚本，按用户口径不算审查发现，仅提一句）。**
位置：`D:\Minecraft\tools\YSMParser\npc_emote_expiry_invariants.py:61-69`（A1）、`:87-91`（A4）。
问题：两条都把"**整个文件**里出现某段文本"当成断言，而不是"在那段代码里"：
- **A1**（守 I1，最值钱的那条）只查 `NpcEmoteDurationPayload.java`："有 `emoteSetAt()`" +
  "没有 `getGameTime()`"。把 deadline 的计算**搬进 `NpcEmoteExpiryHandler`**（一个最自然的"整理"动作——
  那个文件**本来就已经有** `npc.level().getGameTime()`，就在 `schedule` 的日志行 `:88`），
  改成 `schedule(npc, name, durationTicks)` 并在里面算 `npc.level().getGameTime() + duration`，
  就**正好是 I1 要防的那条错法**，而 A1 **仍然全绿**。
- **A4** 判据是 `"emoteSetAt =" in entity and "NpcEmoteExpiryHandler.cancel(" in entity`，
  同样不限定方法体：把 `:894-895` 两行整体挪到 `hasActiveEmote()` 之类的地方（I2 立刻破），A4 **仍然全绿**。
  计划 `plan.md:155` 自己写的判据是"**`setEmote` 方法体里**同时出现"，脚本没有做到。
（A2/A3 也有同样的"整文件文本匹配"性质，但 A3 已按 `plan.md:154` 的旧判据改成表达式形状
`Math.ceil(animation.length())`，实际上更稳；A2 依赖的 `!= Animation.LoopType.LOOP` 一旦删掉就找不到，可接受。）
**建议处置**（若之后要动脚本）：A1 同时守 `NpcEmoteExpiryHandler`（例如断言该文件里**不出现**
`getGameTime() +`、或断言 `schedule(` 的第三参来自 `emoteSetAt`）；A4 先按大括号配平切出 `setEmote` 方法体再查。
这两条**不影响本次收尾**，也**不改变 `src/` 的任何结论**（当前代码本身是对的：4/4 PASS 且我逐条人工核过）。

---

## 五、已核对通过

以下是我**逐条确认过**的关键点（不是"看起来没问题"），附证据。四条不变量单列在最前。

### A. I1（重复申报幂等）—— 成立
1. **全仓只有一处算 deadline**：`NpcEmoteDurationPayload.java:104` = `npc.emoteSetAt() + (long) payload.durationTicks()`；
   没有任何 `now + duration` 的路径（grep `schedule(` 只有一个调用点，`:105`）。
2. **`emoteSetAt` 只在 `setEmote` 里写**：`NpcEntity.java:894`；且 `DATA_EMOTE` 全仓只被四处引用 ——
   定义 `:389`、默认值 `:513`、读 `:840`、写 `:890` ⇒ **没有任何绕过 `setEmote` 直写 `DATA_EMOTE` 的地方**。
3. **"没写就被读到"不可能**：`emote()` 非空 ⇒ 必然走过 `:890` ⇒ 同一次调用必然写过 `:894`；
   `PENDING` 只由 `handleServer` 填充，其目标实体取自 `player.serverLevel()`（服务端实例）。
   （`emoteSetAt` 是普通字段、不落盘、不同步；读档后 `emote()` 与 `emoteSetAt` **同时归零**，
   与 `:851-853` 的 javadoc 一致。唯一措辞瑕疵：javadoc 说"必然**大于** 0"，理论上 `gameTime == 0` 那一次会等于 0 ——
   但那不影响结论（0 + duration 仍然正确），故不单列发现。）
4. **同轮申报结果恒等**：`PENDING.put` 覆盖式写入（`NpcEmoteExpiryHandler.java:84`）⇒ 同一 `emoteSetAt` 下重复申报
   得到同一个 deadline；我按三条时序手工推演过：① 重新追踪（客户端重建实例再申报）② 同名 force 二次点播
   ③ A→B→A 换名后到达的**陈旧申报** —— 三条都收敛到"当前 `emoteSetAt` + 该名字的长度"，
   其中 ③ 恰好说明"用 `emoteSetAt` 而不是申报里的任何时间戳"是**充分**的（陈旧包也只会算出正确值）。
5. **跨维度比较无问题**：`DerivedLevelData.getGameTime()` 委托主世界数据（`:34-35`，唯一自增点是
   `ServerLevel.tickTime()` `:444-447`）⇒ 所有维度共用一个 `gameTime`，`deadline` 不与维度绑定。

### B. I2（`setEmote` 每条路径都刷新 + 作废）—— 成立
- `setEmote:881-896` 在 `entityData.set(..., force=true)`（`:890`）之后**无条件**执行 `:894`（刷新时刻）与
  `:895`（`cancel`）；早退分支只有"客户端"一条（`:882-884`），服务端任何调用（含 `null` ⇒ `""`）都会走到这两行。
- **全部调用点**（grep）：`CgAnimation.java:184`、`NpcCommand.java:379`、`MynpcCommand.java:228`、
  `NpcRouteGoal.java:183`（方法引用，服务端 goal）—— 全部走 `setEmote`；`clearEmote()` = `setEmote("")`（`:919-922`），
  因此清空也走同一条路（正确：清空同样刷新时刻、同样作废旧计划）。
- 既有 **8 个** `clearEmote()` 调用点语义未变（我逐个点名过：`NpcEntity` 的 `moveTo:731` / `attack:767` /
  `swing:825` / `setState:965` / `resetToDefault:1140`，以及 `NpcCommand:398`、`MynpcCommand:241`、
  `NpcDialogueReplyPayload:105`），本次只**新增**一条清理路径（`NpcEmoteExpiryHandler:128`）。
  （文档把它写成"6 个"，见 M-9；这条不影响 I2 的结论。）

### C. I3（到期时名字仍一致才清）—— 成立（且是"第二道"）
- 入口侧：`NpcEmoteDurationPayload.java:94` `!npc.emote().equals(payload.emoteName()) ⇒ return`；
  到期侧：`NpcEmoteExpiryHandler.java:127` `if (npc.emote().equals(entry.name())) npc.clearEmote();`。
- 我确认到期侧那条在当前架构下**不可达**（任何 `setEmote` 都会 `cancel` ⇒ 计划存活 ⟺ 名字没被换过），
  这正是 javadoc（`:37-43`"两道防线"）的口径 —— 记录为"防御纵深"，与文档一致。

### D. I4（`LOOP` 姿态永不上报 / 永不被清）—— 成立
1. 判据语义在 GeckoLib 侧逐条核过：`Animation.LoopType.fromJson`（`Animation.java:69-77`）——
   `loop` 缺失/非原始类型 ⇒ `PLAY_ONCE`；`loop: true` ⇒ `LOOP`；`loop: false` ⇒ `PLAY_ONCE`；
   未知字符串 ⇒ `fromString` 落到 `PLAY_ONCE`（`:85`）。
2. 资产实测（解析 JSON）：`mo.extra.animation.json` 的 `sit` = `loop: true`（`animation_length: 3`）、
   `dance` = `loop: true`（`20.5`）、`descend` = **无 `loop`**（`6`）⇒ `sit`/`dance` 是 `LOOP`、`descend` 是 `PLAY_ONCE`。
3. 申报点 `NpcEntity.java:1795` 是 `animation.loopType() != Animation.LoopType.LOOP` ⇒ `sit`/`dance` **不进包**；
   而**能清掉表情的路径只有两条**（既有的 8 个事件驱动调用点 + 本 handler 的到期清除，见 §B / M-9），本 handler 只处理
   `PENDING` 里的表项 ⇒ `LOOP` 姿态结构上不可能被清。
4. 边界（已写进注释、我也确认无误）：`HOLD_ON_LAST_FRAME` 与**自定义 `LoopType`** 都会被当成"一次性"申报
   （`LoopType` 是 `@FunctionalInterface` 且支持 `register` 自定义名 —— `Animation.java:39-48,99-103`）；
   当前资产没有这两种，且客户端自身的收工判据本来也会在 `length()` 后置 `emoteDone` ⇒ 行为一致。

### E. `emoteReported` 守卫 与"渲染路径发包"—— 守卫有效；发包安全（附一条加固建议见 M-7）
1. **守卫真的防得住 60 包/秒**：谓词只在渲染时被求值，且 GeckoLib 对"同一实例同一帧的重复渲染"有早退
   （`GeoModel.handleAnimations:200-202` 的 `isReRender && instanceId == lastRenderedInstance`）；
   正常路径是**每帧一次**。`:1795` 的 `!name.equals(npc.emoteReported)` 在第一次后即挡住；
   下一次允许申报的时机只有一个 —— `onSyncedDataUpdated(DATA_EMOTE)`（`:910-916`，本次新增 `:915` 清 `emoteReported`）。
2. **两者的关系**：`emoteSeen`（"我见过这条名字"）与 `emoteReported`（"我报过这条名字"）**刻意分开**，
   而且清 `emoteSeen` 的早退分支（`:1769-1775`）**不清** `emoteReported` —— 我确认这不会造成漏报：
   播完后若服务端重新 force 同一条，`onSyncedDataUpdated` 会清 `emoteReported`，
   下一帧就补报；若同步通知"因故没到"（`:1770` 的假设），`emoteSeen` 已被清 ⇒ 走"换名"分支重新起表，
   而 `emoteReported` 仍是同一条 ⇒ **不重复申报**（此时旧计划仍在，正是期望行为）。
3. **重复申报的来源被证实**：`ServerEntity` 构造时缓存 `getNonDefaultValues()`（MC 源码 `ServerEntity.java:83`），
   玩家**开始追踪**时把这份快照发出去（`:247-257`），值变化时发 dirty 集合（`:326-330`）
   ⇒ "走远回来 = 重新追踪 = 客户端收到非默认值 `DATA_EMOTE`"这条根因链**成立**，而重复申报由 I1 吸收。
4. **谓词不会在服务端跑**：唯一调用链是 `GeoRenderer.render → GeoModel.handleAnimations → processor.tickAnimation`
   （`GeoModel.java:224`），6 个调用者全是客户端渲染器（`GeoEntityRenderer:270`、`GeoArmorRenderer:350`、
   `GeoItemRenderer:234`、`GeoBlockRenderer:155`、`GeoObjectRenderer:162`、`GeoReplacedEntityRenderer:277`）；
   `handleAnimations` 第一句就是 `Minecraft.getInstance()`（`:200`）⇒ 这段代码在专用服务端根本不会执行。
   （`EmoteAnimationLookup` 的类注释 `:57-61` 也持同一假设。）
5. **发包本身安全**：客户端渲染在主线程；`PacketDistributor.sendToServer` 的两条前置条件
   （`dist.isClient()` + 连接非 null）在这条路径上恒满足；`ClientPacketListener.send` 对已断开的连接会静默降级；
   客户端 `npc.getId()` 就是服务端分配的网络 id（`ClientboundAddEntityPacket`）⇒ `handleServer` 能取回同一实体。
   **注意**：这是本仓**第一个从渲染路径发出的 C2S 包**（此前 `NpcDialogueScreen:292` 是按钮回调、
   `BeLoongCore:295` 等是服务端/客户端 tick），所以 M-7 的加固建议值得考虑。

### F. 载荷安全（`handleServer` 客户端可控 ⇒ 服务端线程绝不抛）—— 成立
逐条枚举 `NpcEmoteDurationPayload.java:84-106`：
1. `context.player()`：`instanceof ServerPlayer` 对 `null` 安全（`null instanceof` 为假）⇒ 不会 NPE。
2. `player.serverLevel().getEntity(payload.entityId())`：任何越界/负/超大 id 都返回 `null` ⇒
   `!(entity instanceof NpcEntity)` 直接返回，不抛。
3. `npc.emote().equals(payload.emoteName())`：接收者 `emote()` 恒非 null（同步字段，默认 `""`）；
   参数即使为 `null` 也只得到 `false`（`String.equals(null)` = false）⇒ 不抛。
4. `payload.durationTicks() <= 0` ⇒ 返回（负数、0 都被拦）；`Integer.MAX_VALUE` 通过，
   `emoteSetAt + (long) Integer.MAX_VALUE` **不溢出**（`long` 运算；`gameTime` 距 `Long.MAX_VALUE` 有天文距离）⇒ 不会变负。
5. 线格式：`VAR_INT + STRING_UTF8 + VAR_INT`，`STRING_UTF8` 自带 32767 字节上限 ⇒ 超长串在**框架层**解码失败，
   不会进到我们的 handler（与 `NpcDialogueReplyPayload` 的"全函数"口径一致；新包多了一个字符串，但结论不变）。
6. 四条的**顺序**与类注释 `:41-48`、计划 `:90-96` 完全一致（玩家 → 实体 → 名字 → 长度）；
   失败**一律静默**（无 WARN）与 D6/计划非目标一致。
7. 日志本身也不抛：`NpcEmoteExpiryHandler.java:86-89` 的占位符 3 个、实参 3 个，`npc.getType()` 与 `getGameTime()` 都非空。
（一条已记录的口径差异：`schedule` 在**每次**受理（含重复申报、每次重新追踪）都打一条 **INFO**
⇒ 玩家来回走会线性新增日志；这是验收第 1 项的唯一可观测点，属刻意，但改装客户端可以拿它刷日志。）—— 仅记录，不单列发现。

### G. `NpcStoryHandler` 的行为变更 —— 首次仍播 CG；两个返回值都对
1. `spawned == true` 只出现在**新建**分支（`:715`）；复用分支 `:661` 返回 `false`；
   建不出来 `:674-679` 返回 `null`（`@Nullable` 正确标注，调用方 `:152-154` 正确判空）。
2. 首次触发（没有任何自己的分身）⇒ 走 anchor/兜底生成 ⇒ `spawned=true` ⇒ `story.cg().ifPresent(...)` 照常播（`:163-169`）。
3. **"该播却误判成不该播"的路径**：只有"该玩家已有同类型分身"这一种（`:658-662`），
   而这正是用户裁定要的语义（重新获得 `root` 但分身仍在 ⇒ 剧情没有重新开始）。
   我额外核对了循环体：`continue` 之后没有**其它**副作用被跳过（`:156-170` 之后就是循环结束）。
4. `ensureDouble` 全仓**只有一个调用点**（`:151`）⇒ 改签名的连带修改没有遗漏。
5. `Ensured` 是 `private record`（`:635`），嵌套在 `NpcStoryHandler` 内 ⇒ 隐式 static，无持有外部实例的问题。

### H. 表结构与并发 —— 无 CME、无同会话泄漏
- `PENDING` 以**实体引用**为键：`NpcEntity` 未重写 `equals`/`hashCode`（grep 该包无命中）⇒ 身份语义，
  与类注释 `:54` 一致。
- **`discard()`/区块卸载**：`PersistentEntitySectionManager.unloadEntity:234-237` 会
  `setRemoved(UNLOADED_TO_CHUNK)` ⇒ 下一 tick 的 `:118-121` 摘掉表项，**且不对已移除实体动手**（符合设计 §4 那一行）。
  同时因为 `DATA_EMOTE` 不落盘（`:377-388` 的 javadoc + 我 grep 确认 `addAdditionalSaveData`/`readAdditionalSaveData`
  里没有它），区块重载后字段本来就是 `""` ⇒ 设计目标（走远回来不再重播）在这条路径上也成立。
- **换维度**：`Entity.changeDimension:2735-2740` —— 跨维度时 `getType().create(...)` + `restoreFrom(NBT)`（`:2710-2716`）
  且旧实例 `removeAfterChangingDimensions()` ⇒ 旧键随 `isRemoved()` 摘除，新实例 `emoteSetAt = 0` / `emote = ""`
  （不落盘）⇒ **换维度本来就不会"带着名字过去"**（见 M-8 与"文档一致性"注记）。
- **遍历安全**：`List.copyOf(PENDING.keySet())`（`:111`）+ 循环内 `remove` ⇒ 不在被遍历的 map 上增删；
  `clearEmote()` 回环 `cancel()` 时该键已被 `:126` 移除 ⇒ 不会重复处理（`:112-116` 的兜底分支是诚实的冗余）。
- **线程**：NeoForge `PayloadRegistrar` 默认 `HandlerThread.MAIN`（`PayloadRegistrar.java:29,164-169`），
  注册时被包进 `MainThreadPayloadHandler`（`MainThreadPayloadHandler.java:13-17` = `context.enqueueWork(...)`）
  ⇒ `schedule`/`cancel` 与 `onServerTick` **同在主线程**，`HashMap` 无并发问题（这条也解释了为什么不必用并发容器）。

### I. 文档一致性（除 I-2/M-1/M-3/M-4/M-5/M-8/M-9 之外的部分）—— 与代码一致
- `NpcEmoteExpiryHandler` 类注释的三件事与代码一致：① 为什么必须与区块加载无关（`:26-29`；
  到期判据用 level 时钟，而 level 时钟在区块不 tick 时**照走** ⇒ 结论成立）；
  ② 幂等来自 `emoteSetAt + duration`（`:31-35`）；③ 名字比对是第二道（`:37-43`）。
- 计划 `:59` 要求类 javadoc 写清的三件事**都写了**；计划 `:49-51` 的日志文案与 `:86-89` **逐字一致**。
- 计划 `:68-74`（T2）与 `NpcEntity.java:892-896` 逐行对应；计划 `:115-127`（T4）与 `:1689-1699,1795-1803` 对应
  （含"必须在 `find` 成功之后""LOOP 不申报""守卫必须存在"三条理由，注释里都在）。
- 设计 §3.3 的四条不变量表与代码实现**逐条对应**（本次核对的结论就是 §A–§D）。
- 设计 §3.2 的组件表 C1–C5 **全部落地**（两个新文件 + `BeLoongCore` 两处注册 + `NpcEntity` 两处改动 + handler）。
- `docs/NPC系统总设计.md` 的表情一节（`:266-299`）目前**没有**被本次改动写错的地方；
  "一次性表情播完即清"的补写属于 T9 的既定任务（`plan.md:182`），本报告不把它算作缺陷。

### J. 常规项 —— 通过
- **构建**：`.\gradlew.bat build` **exit 0 / BUILD SUCCESSFUL**（`compileJava UP-TO-DATE` ⇒ 现有 class 文件
  正是由当前这批输入产出的，即这批源码已经成功编译过）。
- **不变量脚本**：`python -X utf8 tools\YSMParser\npc_emote_expiry_invariants.py` → **4/4 PASS**（exit 0）。
- **数值类型**：`Animation#length()` 是 `double`（`Animation.java:25` 的 record 分量）⇒
  代码用 `Math.ceil` 再窄化，注释解释了为什么不是强转/`round`（`:1797-1801`），
  且与客户端"播完"判据 `tickCount - emoteStartTick >= animation.length()`（`:1814`）**同一口径**（120.4 → 第 121 tick 停）。
  我另外确认：`length` 的单位是 tick（`BakedAnimationsAdapter.java:59` 的 `* 20d`），与 `tickCount` 同量纲，不存在秒/tick 混用。
- **`@Nullable`**：`ensureDouble` 的注解与调用点判空一致；`EmoteAnimationLookup.find` 的 `null` 返回值在
  新代码里**先判空再取 `loopType()`/`length()`**（`:1783-1787` ⇒ `:1795`），没有 NPE 面。
- **`emoteReported` 在服务端也被清**（`onSyncedDataUpdated` 双端都会触发）—— 无害（该字段只被客户端谓词读）。
- **根因抽检**（用户说不必重证，我抽了三环）：`ModEntities.java:105` 的 `clientTrackingRange(10)` = 160 格 ✓；
  `ServerEntity` 开始追踪时发非默认同步数据 ✓（§E.3）；`DATA_EMOTE` 不落盘 ✓（§H）。
  **未复核**：用户提到的"79 个日志文件 / 83 条 `cg ... playing`"统计（我另跑了 `run\logs` 共 80 个文件，
  `will be cleared in` **0 命中**，与"本次修复尚未实机跑过"一致）。

### K. 实机验收状态（写实）
- 本次改动**没有实机证据**：80 个日志文件里新日志 0 命中；计划的 T8（用户执行 10 项清单）尚未开始。
- 因此本报告不对"走远回来不再重播""`sit` 仍然坐着"等行为下结论，只对**静态可判定**的性质下结论（§A–§J）。
- 实机清单里**有一项必须先改文案再跑**（M-1 的"120 ticks"），否则会看到假失败。

---

## 六、结论

- **Critical 0**：没有崩溃面、没有并发面、没有侧别错用、没有"永远清不掉"。
- **四条不变量全部成立**（I1/I2/I3/I4，证据见 §五 A–D）——这是本次改动成败的全部，我按用户给的四种错法逐一反证过：
  ① 有没有退化成 `now`（无，全仓只有一处 deadline）② 有没有绕过 `setEmote` 直写 `DATA_EMOTE`（无）
  ③ `LOOP` 有没有别的路径被清（无）④ `emoteReported` 有没有漏报/重报（没有 60 包/秒，也没有该报却不报的路径）。
- **3 Important**：`I-1` 是 `src/` 内的注释漏改（**★必须现在改**，一行）；
  `I-2` 是设计文档的非目标清单与 diff 相反（**建议现在改**，因为它会误导后来者，且 T9 的清单漏了它）；
  `I-3` 是"关服无清理 + 时钟冻结"的残留面（**建议现在改**，3 行；不改则必须把它写成已被证实的前提）。
- **9 Minor**：全部是"文档/注释与事实的偏差"或"零成本加固"，**没有一条要求改行为或改常量**。
- **对"必须现在改才能提交"的判定**：**只有 I-1**（`src/` 随代码发布的一行注释，正是本项目第一号缺陷面）。
  **I-2 / I-3 / M-1 / M-3 / M-5 / M-8 / M-9 建议与 T9 的文档回填一并做**（T9 已经要改设计文档与活文档，
  把它们并进去成本最低）；**M-4 的两行、M-7 的 try/catch、M-2 的注释补一句**可留到下一次触碰该文件时再做。
- **功能本身**：实现与设计文档的机制描述**一致**，且实现比文档在两处更稳（早清而不是重播、名字双检）。
  真正的风险不在四条不变量上，而在**两条"文档说的观测/行为"与代码对不上**（M-1 的日志数字、M-3 的"重播一次"）——
  这与本项目"声明与事实脱节"的老毛病同型，也正是本次审查最有价值的两条发现。

---

## 七、修复状态（提交前回填）

**Important 全修，Minor 修了 3 条、其余 6 条并入 T9**（都在计划文档的 T9 任务体里逐条列明，不会丢）。

### Important

| 编号 | 处置 | 落在哪 |
|---|---|---|
| **I-1** ★ | ✅ **已修**：那个小节表头仍写"这三个字段"（`:905` 已改对，漏了表头 `:1670`）⇒ 改成"这四个字段" | `NpcEntity.java` |
| **I-2** | ✅ **已修**：设计文档 §6.2 原来把 `NpcStoryHandler` 那条列成"本次不修"，与 T5 已实现相反 ⇒ 改成"**已在本次修复（T5，用户裁定）**"并写明做法（`Ensured(npc, spawned)`） | `design.md` |
| **I-3** | ✅ **已修**（比"3 行"多做了维度一侧）：新增 **`ServerStoppedEvent` ⇒ 整表清空** 与 **`LevelEvent.Unload` ⇒ 只丢该维度的表项**。理由写进 javadoc：`PENDING` 是**静态**字段，生命周期是整个 JVM，而关服/卸载后那个维度的时钟**冻结** ⇒ 未到期的表项**永不到期**，强引用着实体 → `ServerLevel` → 整个旧存档对象图 | `NpcEmoteExpiryHandler.java` |

### Minor

| 编号 | 处置 |
|---|---|
| **M-1**（日志数字会让实机假失败） | ✅ **已修**：设计文档与计划的验收第 1 项都由"`in 120 ticks`"改成"**`in N ticks`，N ≤ 120（通常 116~119）**"并说明原因（日志打的是 `deadline − now`，申报到达时已过了几 tick） |
| **M-7**（渲染路径发包无 try/catch） | ✅ **已修**：包一层 `catch (Throwable)` + 一条 WARN。**刻意不重试**（谓词每帧跑，重试会把日志刷成每秒 60 条）；`emoteReported` 改为**无论成败都记账** ⇒ 同一条表情只申报一次。申报是 best-effort，失败只是退化成"修复前的行为" |
| **M-9**（`clearEmote()` 调用点数写错） | ✅ **已修**：实测**既有 8 处**（我原先写 6）⇒ 计划文档两处改成 8 |
| **M-2 / M-3 / M-4 / M-5 / M-6 / M-8** | ⏸️ **并入 T9**（计划文档 T9 任务体已逐条列明）—— 全是注释/措辞类，审查者已确认不阻塞提交 |
| **X-1**（不在范围，但关于本次新增的探针脚本） | ✅ **已修并加了第五条变异**：A1 原判据只查"`emoteSetAt()` 出现与否"，把**加法搬到 `NpcEmoteExpiryHandler`**（那个文件里本来就有 `getGameTime()`）仍会全绿 ⇒ 收紧为必须出现 **`emoteSetAt() +`**（加法就地）。新增变异"把加法搬走"验证过：旧判据漏、新判据抓到。另外在脚本头部**如实写明这类文本级断言的固有局限**（搬文件/搬方法躲得过；端到端证伪靠实机清单） |

### 提交前的最终验证

```
gradlew build                                  ✓ BUILD SUCCESSFUL
npc_emote_expiry_invariants.py                 ✓ 4/4 PASS（五次变异各自让对应断言失败，还原后哈希一致）
cg_invariants.py                               ✓ 17/17（不回归）
cg_lang_keys.py                                ✓ PASS（不回归）
```
