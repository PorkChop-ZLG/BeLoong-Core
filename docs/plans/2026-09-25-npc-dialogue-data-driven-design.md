# NPC 对话改为服务端权威的 data 数据驱动 设计文档

**日期：** 2026-09-25
**状态：** 已批准
**分支：** `NPC`
**采用方案：** **B1** —— 数据放 `data/`、服务端 `SimpleJsonResourceReloadListener` 加载、右键在**服务端**受理、命中时**按需把这一条**下发（entry + 实体 id）
**前置文档：** `docs/plans/2026-09-20-npc-dialogue-design.md`（本功能的首版设计与实机验收；本文档**修订**其 §4.2 的 D2/D5/D6 与 §九的 R0/R2）
**参考实现：** 龙之生存 `D:\Minecraft\开源模组参考文件\DragonSurvival`（数据驱动做法）；NeoForge 21.1.236 源码；本机 NFRT 合并源（1.21.1 + NeoForge 补丁）

---

## 一、问题陈述

### 1.1 为什么要改

首版把对话数据放在 **`assets/beloong/beloong/npc_dialogue/`**、由**纯客户端**的
`RegisterClientReloadListenersEvent` 加载，据此换来"零网络包、零服务端逻辑"。
当时的取舍（首版文档 §R3）是**明确记录过的**：客户端资源管理器以 `PackType.CLIENT_RESOURCES`
构造，读不到 `data/` 树，所以要么放 `assets/`（可被资源包覆盖），要么改成"服务端读取 + 下发"。

用户裁定改为后者，理由两条：

1. **不符合"数据驱动"的正统语义** —— 数据应当住在数据包里：可被存档数据包覆盖/扩展、
   在专服上由服务端定义，而不是靠资源包。
2. **将来要接原版进度判据**控制对话进行到哪一步 —— 判据的**读**需要"按玩家进度筛选该看到的内容"，
   **写**（把对话推进记成进度）只能在服务端授予（`PlayerAdvancements#award`）。
   纯客户端的架构两头都做不到，必须把决策点搬到服务端。

### 1.2 为什么这件事现在是便宜的

首版文档 §R2 最后一句就已经写下了退路："若将来确实需要数据包覆盖，则改为『服务端读取 + 登录下发』
（先例：`TreasureSyncPayload` + `ClientTreasureCache`）。" 本文档走的就是这条路，
而且**比那条退路更省**：对话是**请求/响应**式的，不需要登录全量同步，也就
**不需要客户端缓存、不需要 `OnDatapackSyncEvent`**（见 §五 D24）。

首版文档 §R2 另有一处**已被本次改动推翻**的推论，必须在旧文档里标注，否则两份文档口径冲突：

> 旧的取舍理由是"对话**没有副作用** ⇒ 整条链路可以跑在客户端"。
> 判据接入后这个前提**不再成立** —— 授予判据就是副作用。

---

## 二、目标与非目标

### 目标

1. 对话数据移到 **`data/beloong/beloong/npc_dialogue/*.json`**，随模组 jar 分发，**可被存档数据包覆盖**。
2. 数据由**服务端**加载（`AddReloadListenerEvent`），启动与 `/reload` 都生效。
3. 右键判定在**服务端**完成，命中后把**该玩家要看的这一条**下发。
4. 客户端**不再持有全表**，也不需要客户端缓存。
5. 数据文件内容（schema）**一字不改**；`NpcDialogueScreen` 的状态机与全部渲染代码**零改动**。
6. 两端的行为语义（触发方式、页数、`§`、`\n`、缺省名、重复绑定规则）**逐条保持**。
7. **继续零 mixin**。

### 非目标

- ❌ 本次**不实现**任何进度判据/条件/分支 —— 只是把决策点搬到服务端，让将来加条件成为**增量**（§十）
- ❌ 不做对话进度存储
- ❌ 不改文案载体：文本仍是**翻译键**，仍在 `assets/beloong/lang/*.json`（§五 D27）
- ❌ 不改 UI（首版 §3 的全部量测与调参结论继续有效）
- ❌ 不改 `beloong.mixins.json`
- ❌ 不修复财宝系统的 `/reload` 缺口（那是另一件事，虽然它可以用同一个钩子修）

---

## 三、研究结论（附出处）

> NeoForge 源码：`~\.gradle\caches\modules-2\files-2.1\net.neoforged\neoforge\21.1.236\...\neoforge-21.1.236-sources.jar`
> 原版（含 NeoForge 补丁）：`~\.gradle\caches\neoformruntime\intermediate_results\sourcesAndCompiledWithNeoForge_*.jar`
> ⚠️ 该 NFRT 缓存里有 4 个版本变体，本次只读了其中一个；引用的 `net/neoforged/**` 类已与 21.1.236 源包逐条对齐，
> `net/minecraft/**` 的行号来自合并源（`D:\Minecraft\闭源模组解压文件\minecraft-1.21.1` 缺 `PlayerList.java`，不可用）。

| # | 结论 | 出处 |
|---|---|---|
| **3.1** | **目录字符串是 PackType 相对的，不是绝对路径。** 服务端管理器以 `SERVER_DATA` 构造、客户端以 `CLIENT_RESOURCES` 构造 ⇒ **同一个字符串** `"beloong/npc_dialogue"` 在服务端读 `data/…`、在客户端读 `assets/…` | `MinecraftServer.java:1511` `new MultiPackResourceManager(PackType.SERVER_DATA, …)`；`Minecraft.java:491` `new ReloadableResourceManager(PackType.CLIENT_RESOURCES)`；`FallbackResourceManager` → `PackResources.listResources(packType, …)` |
| **3.2** | ⇒ **本次改动不需要动目录字符串**，只换注册点 | 3.1 |
| **3.3** | `SimpleJsonResourceReloadListener` **只有一个构造器** `(Gson, String directory)`，无 `FileToIdConverter` 变体；`prepare` 走 `scanDirectory` → `FileToIdConverter.json(directory)` | `SimpleJsonResourceReloadListener.java:23/31/37` |
| **3.4** | `AddReloadListenerEvent` 是**游戏总线**事件，`addListener(PreparableReloadListener)`；**启动与 `/reload` 都会触发** | `AddReloadListenerEvent.java:29/44`；`EventHooks.java:818` `onResourceReload`；`ReloadableServerResources.java:113` |
| **3.5** | 触发时**全部注册表已加载并冻结** ⇒ `apply` 里做注册表查询安全 | `AddReloadListenerEvent` javadoc `:68-73`；`ReloadableServerResources.java:107` 先 reload 注册表、`:113` 再触发监听器 |
| **3.6** | `RegisterClientReloadListenersEvent` 交出的管理器绑 **`CLIENT_RESOURCES`**，**永远看不到 `data/`** ⇒ 首版 R3 的结论正确，且是本次必须换注册点的原因 | 同 3.1；`RegisterClientReloadListenersEvent.java`（仅 `registerReloadListener`，无 getter） |
| **3.7** | `OnDatapackSyncEvent` 只有**两个**构造点：登录（单人）与 `/reload`（`player == null` ⇒ 全员）。**服务端启动不触发它**（启动路径不调 `reloadResources`）。`getRelevantPlayers()` 在无人在线时是**空流** | `OnDatapackSyncEvent.java:43`；`PlayerList.java:208`（`placeNewPlayer`）、`:916`（`reloadResources`）；`MinecraftServer.java:297` vs `:1540` |
| **3.8** | NeoForge 自己的 datapack 同步就是订阅 `OnDatapackSyncEvent` 并遍历 `getRelevantPlayers()`，带 `player.connection.hasChannel(...)` 守卫 | `NeoForgeEventHandler.java:110-131` |
| **3.9** | `PacketDistributor.sendToPlayer(ServerPlayer, CustomPacketPayload…)`；`playToClient(Type, StreamCodec<? super RegistryFriendlyByteBuf, T>, IPayloadHandler)` | `PacketDistributor.java:50`；`PayloadRegistrar.java:44` |

### 3.10 龙之生存的做法（用户指定的参考）

**结论：DS 不手写同步，它用的是 NeoForge 的「同步 datapack 注册表」。**

| 事实 | 出处 |
|---|---|
| 定义层（species / body / stage / ability / penalty / emote / projectile）全部注册为**同步** datapack 注册表（3 参 = 带 networkCodec） | `registry/dragon/DragonSpecies.java:108-111`；`DragonAbility.java:168-171`；`DragonBody.java:219-222`；`DragonStage.java:161-164`；`DragonPenalty.java:110-113`；`DragonEmoteSet.java:30-33`；`registry/projectile/ProjectileData.java:43` |
| 客户端直接读同步注册表，**无缓存类、无同步代码** | `client/gui/screens/DragonAbilityScreen.java:219`；`DragonAltarScreen.java:118/218`；`DragonSkinsScreen.java:273/294/298/361/443`；`DragonEditorScreen.java:852/1148`；`util/ResourceHelper.java:24-39` |
| 全库 **零 `AddReloadListenerEvent`、零 `OnDatapackSyncEvent`**；网络层只传 `ResourceKey`，从不传解析后的对象 | rg 全树；`network/syncing/SyncCooldown.java:19` 等 6 处 `ResourceKey.streamCodec(...)` |
| `DragonPartLoader` 确为**客户端**监听器，读 `assets/dragonsurvival/skin/parts/**`，**没有**服务端对应物 | `client/skin_editor_system/loader/DragonPartLoader.java:28/33`；`DragonSurvivalClient.java:140-143` |
| 但它**故意混用**：assets 侧的表现层目录里，条目引用的是 datapack 注册表的 key | `objects/DragonPart.java:24/36-37` |
| 异步重载的既有坑（DS 为此放弃了监听器机制） | `client/loaders/CustomSoulIconLoader.java:39` 注释："Not using a normal reload listener since it would run async and therefore potentially complete after the registration has happened" |
| DS 的一个真 bug（重载只清一半缓存） | `DefaultPartLoader.apply` 清 `PARTY_BY_MODEL`（`:38`）却从不清 `PARTY_BY_BODY`（`:29`） |

**为什么不照搬 DS（见 §五 D31）**：DS 的定义层要**双端被 UI / tooltip / 伤害计算随意全表读取**，
所以"全表同步 + 按 key 引用"是正解。对话是**请求/响应**式的 —— 客户端只需要"这一次要显示的这一段"。
照搬会把整张表（含服主放在世界数据包里的私有内容）推给每个客户端，收益为零。

---

## 四、架构

### 4.1 双端职责（改前 → 改后）

| | 改前 | 改后 |
|---|---|---|
| 数据树 | `assets/beloong/beloong/npc_dialogue/` | **`data/beloong/beloong/npc_dialogue/`**（`git mv`，内容一字不改） |
| 注册点 | `RegisterClientReloadListenersEvent`（mod 总线 / 仅客户端） | **`AddReloadListenerEvent`**（游戏总线，`BeLoongCore`，与既有 4 个 loader 同构） |
| 目录字符串 | `"beloong/npc_dialogue"` | **不变**（3.1/3.2） |
| 触发受理 | 客户端 | **服务端** |
| 客户端持有全表？ | 是 | **否** —— 只在被打开那一刻收到那一条 |
| 网络包 | 零 | 1 个 `playToClient` |
| 新机制 | — | **无**（复用既有 loader 范式 + 既有 payload 范式） |

### 4.2 组件变化

**新增**

```
src/main/java/com/zonlong/beloong/dialogue/
  ├── NpcDialogueOpenPayload.java     record(entry, entityId) + STREAM_CODEC + handleClient
  └── NpcDialogueHandler.java         服务端右键受理（自 client/ 迁入，反转侧判定）
```

**改动**

| 文件 | 改动 |
|---|---|
| `dialogue/NpcDialogueLoader.java` | **只重写类注释**（为什么 `data/` + 服务端）；`apply` 与 `get` 逻辑不动；`entries` 字段加 `volatile`（§五 D32） |
| `dialogue/NpcDialogueEntry.java` | `CODEC` 不动；并列新增 `STREAM_CODEC`（`Page` 同理） |
| `BeLoongCore.java` | `addServerReloadListeners` +1 行；payload 注册 +1 条（**顺手把现有内联 lambda 抽成私有方法**，两条更清楚）；游戏总线 +1 个 handler |
| `BeLoongCoreClient.java` | **删** `registerClientReloadListeners` 方法及其 import；**删** `NpcDialogueHandler` 注册 |
| `client/NpcDialogueScreen.java` | 构造签名放宽为 `(NpcDialogueEntry, @Nullable Entity)`；名字回退链多一级（§4.5） |
| `Config.java` | `enabled` 迁 `SERVER_SPEC`（D26）；`charsPerTick`/`nameScale` 留 `CLIENT_SPEC` |
| `client/NpcDialogueHandler.java` | **删除**（迁入 `dialogue/`） |
| `assets/…/npc_dialogue/iron_golem.json` | **`git mv`** → `data/…/npc_dialogue/iron_golem.json` |

**零改动**（这是本次改动"小"的关键）

- `NpcDialogueScreen` 的状态机与全部渲染代码、`NpcDialogueOptionButton`
- `assets/beloong/lang/zh_cn.json`、`en_us.json`
- `beloong.mixins.json`、`build.gradle`

---

## 五、包结构与决策记录

### 5.1 包结构

```java
NpcDialogueOpenPayload(NpcDialogueEntry entry, int entityId)
```

`entityId` 是目标实体的网络 id，**只**用于客户端解析缺省名（§4.5）。

| 字段 | 编解码 | 说明 |
|---|---|---|
| `entry.entity` | `ResourceLocation.STREAM_CODEC` + 客户端查 `BuiltInRegistries.ENTITY_TYPE` | **与现有 `Codec.decodeEntity` 完全对称**；未知类型 ⇒ 丢包 + error。刻意不用注册表 codec：不依赖 `RegistryFriendlyByteBuf` 的注册表视图，行为与数据包解析一致、失败可见 |
| `entry.trigger` | `STRING_UTF8`，取值仍为 `"empty_hand"` / `"any"` | 与 Codec 同一套字面量。**不用 ordinal**（重排枚举即静默错位），也不压成 bool（将来加第三种触发就要改协议） |
| `entry.name` | 存在标志 + `STRING_UTF8`（若 `ByteBufCodecs.optional` 存在则直接用它，计划阶段核实） | — |
| `entry.pages[]` | `Page.STREAM_CODEC.apply(ByteBufCodecs.list())` | 项目已验证的写法（`TreasureSyncPayload:46` 同款） |
| `Page.text` / `Page.sound` | `STRING_UTF8` / `ResourceLocation`（可选） | — |

### 5.2 决策记录（接续首版 D1–D20）

| # | 决策 | 取值 | 理由 |
|---|---|---|---|
| **D21** | 数据树 | `data/beloong/beloong/npc_dialogue/` | 用户裁定。**推翻首版 R2 的取舍**：存档数据包现在能覆盖对话 |
| **D22** | 注册点 | `AddReloadListenerEvent`（`BeLoongCore`） | 与既有 4 个 loader 同构；目录字符串无需改动（3.1/3.2） |
| **D23** | 受理侧 | **服务端**（反转首版 D2） | 服务器权威；判据的读与写都只能在服务端完成 |
| **D24** | 下发时机 | **打开时按需发那一条**，不做登录全量同步 | 对话是请求/响应式 ⇒ 无客户端缓存、无 `OnDatapackSyncEvent`；顺带绕开财宝系统"`/reload` 后要重连"的坑（3.7） |
| **D25** | 包内容 | entry 全量 + 目标实体 id | 客户端需要实体来走缺省名（`getDisplayName()`）⇒ **保住首版 R6 的命名牌语义** |
| **D26** | `enabled` 归属 | 迁 **`SERVER_SPEC`** | 首版 D19 的原意是"将来给第三方模组让位"，那是整合包/服务端级决定，不是玩家偏好；留在客户端会变成"服务端照样发包、客户端自己不开屏"。`charsPerTick`/`nameScale` 是纯渲染，留客户端 |
| **D27** | 文案载体 | **不动**，仍在 `assets/beloong/lang/*.json` | 只有"结构"进 `data/`；翻译照样走资源包，翻译者不受影响 |
| **D28** | 是否取消事件 | 仍**不取消**（首版 D4 不变） | 空手右键本无原版行为；取消没收益还会抢第三方交互 |
| **D29** | 距离/权限校验 | **不手写** | 原版服务端在交互包处理里已有交互距离校验（计划阶段核实到具体方法行号后回填本节） |
| **D30** | 失败隔离 | 单文件解析失败只丢该文件（`ifError/ifSuccess` 不变） | 首版 I1 的结论继续有效 |
| **D31** | 不用同步 datapack 注册表（不照搬 DS） | 采用 B1 | §3.10 末尾：对话是请求/响应式，全表同步只会把服主私有内容推给所有客户端 |
| **D32** | `entries` 字段加 **`volatile`** | 采纳 | **推翻首版审查的 S3**。S3 当时的理由是"`apply` 与 `get()` 都在客户端主线程"——本次搬迁**推翻了这个前提**：`apply` 在重载工作线程、`get()` 在服务端主线程。**旧的"不采纳"结论必须同步作废，否则下一个人会按旧理由把它删掉** |
| **D33** | 文件名保持扁平 `iron_golem.json` | 保持 | 改动最小；loader 按 `entity` 字段建表，文件名只是身份；数据包覆盖写同名文件即可。（备选 `minecraft/iron_golem.json` 的好处是"文件名 = 实体 id"，本次不采纳） |

---

## 六、数据流与线程

### 6.1 时序

```
玩家空手右键地黄龙
 └ 原版 Entity#interact → PlayerInteractEvent.EntityInteract 派发两侧
     ├ 客户端：已无监听者 ⇒ 什么都不做
     └ 服务端：NpcDialogueHandler
         ├ Config.NpcDialogue.enabled?              否 → 返回
         ├ 主手（InteractionHand.MAIN_HAND）？        否 → 返回
         ├ NpcDialogueLoader.get(target.getType())   空 → 返回
         ├ trigger==EMPTY_HAND 且主手非空？           是 → 返回
         └ PacketDistributor.sendToPlayer(player,
               new NpcDialogueOpenPayload(entry, target.getId()))
 └ 客户端 payload handler（主线程）
     ├ 按 entityId 在 level 中找实体（允许 null）
     └ Minecraft.getInstance().setScreen(new NpcDialogueScreen(entry, entity))
 └ 屏幕内部：纯本地状态机（打字机 / 翻页 / 选项）—— 零服务端往返，沿用现状
```

### 6.2 线程与重载

- **服务端**：`apply` 跑在**重载工作线程**，`get()` 跑在**服务端主线程** ⇒ 跨线程（D32 的 `volatile`）。
  现有实现已经是"局部 map 建好再整体赋值"，无需额外同步结构。
- **注册表已冻结**：3.5 ⇒ `apply` 内做注册表查询安全（当前实现不查，但将来加条件时要用）。
- **异步重载无害**：对话表只在玩家右键时被读，那时重载早已结束。DS 的
  `CustomSoulIconLoader:39` 那个"重载可能晚于注册完成"的坑属于**资源注册**时序，我们不涉及。
- **客户端**：NeoForge play 包默认在**主线程**执行（与 `TreasureSyncPayload` 同款），直接 `setScreen`。

### 6.3 名字回退链（客户端唯一的行为改动）

`entry.name()`（翻译键）→ 按 `entityId` 找到的实体 `getDisplayName()` → **`entry.entity().getDescription()`**

原实现是 `entry.name().map(Component::translatable).orElseGet(speaker::getDisplayName)`。
现在实体在客户端**可能不存在**，故加第三级兜底：不崩、不退屏，且实体在场时仍显示命名牌自定义名（首版 R6 语义不变）。

---

## 七、错误处理与边界

| 情形 | 行为 | 变化 |
|---|---|---|
| JSON 解析失败 / `entity` 未注册 / `pages` 为空 / 重复绑定 | 丢单文件并报错；重复按 ResourceLocation 排序后者胜 + warn | 不变（D30） |
| `get()` 时表为空（首轮重载未完成，或全部解析失败） | 返回 null ⇒ 不触发 | 不变（安全降级） |
| **世界数据包覆盖模组 jar** | `listResources` 按 ResourceLocation 合并，高优先级包胜 ⇒ 同名文件覆盖 | **新增能力**（改前不可能） |
| 客户端不认识的实体类型 | 丢包 + `LOGGER.error` | 新增 |
| 客户端按 `entityId` 找不到实体 | 允许 null ⇒ 名字回退（§6.3） | 新增（不崩） |
| `/reload` 时有人正在看某段对话 | 屏幕持有**打开那一刻的快照**，不受影响；下次打开拿新数据 | 新增（B1 顺带的好处） |
| 玩家跑远 / 实体死亡 / 掉线 | v1 仍不处理（无服务端状态；ESC 即退） | 不变。**注意**：对话无副作用这个前提在接入判据后会被打破，届时需要重新审视本条 |
| 模组缺失 / 版本不匹配 | `playToClient` 走 `Registrar` 的通道校验 | NeoForge 既有语义 |

### 7.1 运行时观测点

保留 loader 那行日志（`N file(s) scanned, M dialogue(s) loaded`）—— 它的角色从
"客户端能否读到 `assets/`"变成"**服务端能否读到 `data/`**"，仍然是
"目录放错树 ⇒ 静默无反应、且不报任何错"这个失效模式的**第一观测点**（首版 R0 的教训）。

---

## 八、测试策略

### 一级（实现方，可靠）

1. `.\gradlew.bat build` 通过。
2. `jar tf`：产物里有 `data/beloong/beloong/npc_dialogue/iron_golem.json`，
   且 `assets/beloong/beloong/npc_dialogue/` **已不存在**。
3. 静态：`BeLoongCoreClient` 里**没有** `RegisterClientReloadListenersEvent`、**没有** `NpcDialogueHandler`。
4. 静态：`client/` 下 `NpcDialogueHandler.java` 已删，`dialogue/` 下有。
5. 静态：`NpcDialogueLoader` 的 `entries` 字段带 `volatile`。
6. 静态：`beloong.mixins.json` 未改；`build.gradle` 未新增依赖。

### 二级（实机）

| # | 步骤 | 预期 | 这一步在证明什么 |
|---|---|---|---|
| 1 | 看服务端日志 | `1 file(s) scanned, 1 dialogue(s) loaded` | 服务端读到了 `data/` 树 |
| 2 | **把 json 放进世界数据包**（`saves/<world>/datapacks/x/data/beloong/beloong/npc_dialogue/iron_golem.json`），删掉 jar 里那份，右键 | 仍然弹窗 | **决定性验收**：改前客户端读不到 `data/`，这一步**必失败**；通过即同时证明数据树搬迁与服务端权威 |
| 3 | 改 JSON 加第三页 → `/reload` → 右键（**不退出重进**） | 出现第三页 | 服务端数据热重载生效（改前对 `/reload` 完全无感） |
| 4 | 世界数据包放同名文件覆盖 jar | 覆盖生效 | 数据包覆盖语义 |
| 5 | 手持铁锭右键（缺省 `empty_hand`） | 不触发，走原版修血 | 触发语义未回归 |
| 6 | 对未配置实体（牛）空手右键 | 无反应 | — |
| 7 | 服务端 `[npc_dialogue] enabled=false` | 右键无反应 | 新配置归属生效 |
| 8 | **联机**：两名玩家，一人右键 | 只有右键者弹窗 | 下发是点对点的 |
| 9 | 打字机 / 点击补全 / 翻页箭头 / 末页弹选项 / 悬停金色过渡 / ESC / 世界不模糊 | 与改前逐项一致 | 回归（屏幕代码未改，应零偏差） |
| 10 | 读旧存档 | 正常 | 对话无存档数据，不需要修复 |

---

## 九、风险与已知取舍

| # | 风险 | 评估与对策 |
|---|---|---|
| **R7** | **服务端确实派发 `EntityInteract` 吗？** 这是 B 的前提 | **低**。证据：`compat/ftbchunks/LoongPalaceProtectionHandler` 两侧都处理该事件；现 `NpcDialogueHandler` 的 `isClientSide()` 早退本身就说明两侧都会来。实机第 2/8 条即为反证；若真不派发，退路是改听 `EntityInteractSpecific` 或自建 serverbound 包 |
| **R8** | 跨线程可见性 | 已列入改动（D32 `volatile`）与静态检查第 5 条 |
| **R9** | 玩家已有 `beloong-client.toml` 里的 `enabled` 失效 | 回默认 `true`（本就是默认），可接受，已记录 |
| **R10** | 弹窗多一个 RTT | 感知不到（弹窗前本就要等打字动画）；单人 ~0ms |
| **R11** | 数据包作者写坏字段 | 失败可见：error 日志 + "扫描数 vs 装载数"对照 |
| **R12** | 首版文档 §R3 与本文档口径冲突 | **必须**在旧文档追加 R4 指向本文档（§十一） |

---

## 十、后续（不在本次范围）

1. **接入原版进度判据**（本次改动铺的路）。做的时候**不需要**动架构：
   - 数据侧：`NpcDialogueEntry` 加可选条件字段（对齐原版数据包谓词，而不是自造 DSL）；
   - 服务端：`NpcDialogueHandler` 在命中后按玩家进度**筛选**，**只下发该看到的那一条**（RPG Dialogue 的做法）；
   - 若需要"同一实体多套对话"，把 loader 的 `Map<EntityType<?>, Entry>` 改成 `Map<EntityType<?>, List<Entry>>` + 服务端选择器 —— **局部改动**；
   - 写进度（把对话推进记成判据）：在服务端受理路径里 `PlayerAdvancements#award`，与本模组既有的
     `ModCriteria` + `ClawSwordAdvancementHandler` / `DeadKingAdvancementHandler` 惯用法一致。
2. 有副作用的选项：需要再加一个 `playToServer` 的选择回包（或"服务端预先声明可用动作"）。
3. `sound` 实装播放（首版 D18 保留字段，仍只解析）。
4. 头部跟随 / 多形态 / 动画状态机（见 `2026-09-21-dihuang-loong-npc-design.md` §十）。
5. **顺带可修**：财宝系统把 `PlayerLoggedInEvent` 换成 `OnDatapackSyncEvent`（3.7/3.8），
   `/reload` 后不再要求重连。**本次不做**。

---

## 十一、来源与收尾产物

| 类别 | 出处 |
|---|---|
| 首版设计 + 实机验收 | `docs/plans/2026-09-20-npc-dialogue-design.md` |
| 龙之生存（数据驱动做法） | `D:\Minecraft\开源模组参考文件\DragonSurvival`，逐条出处见 §3.10 |
| NeoForge 21.1.236 | `neoforge-21.1.236-sources.jar`（3.3–3.9） |
| 原版（含 NeoForge 补丁） | NFRT `sourcesAndCompiledWithNeoForge_*.jar`（3.1/3.4/3.7 的 `net/minecraft/**` 行号） |

**收尾产物**

- 本文档（新建）；旧文档 `2026-09-20-npc-dialogue-design.md` 追加 **R4** 指向本文档
- `memory/decisions-log.md` 追加一条（含 `data/` vs `assets/` 的 PackType 事实、B1 取舍、**D32 推翻 S3**）
- `memory/project-context.md` 子系统 9 口径更新：由"纯客户端、零网络包、零服务端逻辑"
  改为"服务端权威 + 打开时按需下发"
- 提交拆两个：`feat(dialogue): …`（数据搬迁 + 架构切换，**必须原子**）+ `docs(dialogue): …`
