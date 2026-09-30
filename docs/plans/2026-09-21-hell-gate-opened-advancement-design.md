# 地狱之门「开启」进度触发器 设计文档

**日期：** 2026-09-21
**状态：** ✅ **已实施并实机验收通过**（用户 2026-09-21 确认「测试通过，没什么问题」；实机读数见 §八 B）
**采用方案：** 方案 B —— 判据 + **薄编排层**（发放口径集中在一个文件；移植件只加一行调用）
**基线：** `disaster2` @ `da16aea`（版本 `0.9.6`，领先 `origin/disaster2` 8 个提交，未推送）
**依赖前提：** 地狱之门本体已实现并实机验收通过（`docs/plans/2026-09-20-hell-gate-design.md`）

---

## 一、问题陈述

### 需求（用户原话）

> 1. 制作一个新的进度触发器，当玩家**开启地狱之门**之后触发
> 2. 要求等地狱之门**动画播放完成，门彻底开启可以进入之后**，才能触发
> 3. **周围的玩家都可以获得进度**，而不是只有开门的玩家
> 4. 模组侧先**随便写一个测试用进度**，测试完成需要**删除**。具体进度由整合包侧完成。

澄清阶段由用户裁定的四点：

| 议题 | 用户裁定 |
|---|---|
| 「周围」的边界 | **半径 32 格，只排除旁观**（创造模式**计入**） |
| 判据的自定义条件字段 | **不带**（最小实现；整合包用标准 `player` 谓词按维度/坐标筛） |
| 代码落点 | **方案 B：判据 + 薄编排层** |
| 开门者跑远的边界 | **纯位置快照**（不特判开门者；t=145 时不在 32 格内就拿不到） |

### 把设计推向当前形态的六条查证事实

| # | 事实 | 依据 | 对设计的影响 |
|---|---|---|---|
| 1 | **触发点唯一、精确、且只在服务端** —— 「门彻底开启」的落点是 `HellGateBlockEntity.tick` 里 `animationTicks >= TICK_FULLY_OPEN && !level.isClientSide` 那一段，它先把基准格 + 39 个部件格全部置 `OPEN=true`（碰撞箱清空＝可进入）；**整扇门只会走一次**（置完 `OPEN` 后下一 tick 就在 `OPEN` 分支早退并把 `animationTicks` 归零） | `HellGateBlockEntity.java:132-153`、`:106-114` | ⇒ 要求 1+2 不需要任何新检测机制，也**不需要 mixin**；只要在那段末尾挂一次发放 |
| 2 | **判据范式在仓库里现成** —— `SimpleCriterionTrigger<Instance>` + `trigger(ServerPlayer)` + `Instance(Optional<ContextAwarePredicate> player)`，注册进 `ModCriteria.REGISTRY`（`Registries.TRIGGER_TYPE` 冻结注册表，注册失败会启动期直接报错） | `compat/ironsspellbooks/DeadKingKillTrigger.java`（66 行）、`registry/ModCriteria.java:30-55` | ⇒ 新判据照抄即可，无新机制 |
| 3 | **「周围玩家都发」本仓库已有先例，且带一个必须继承的坑** —— `DeadKingAdvancementHandler` 就是「半径内所有玩家各发一次」，并且**必须遍历 `List.copyOf(players())` 快照**：`trigger()` 会执行进度奖励函数，奖励函数里的跨维度传送/踢出会改动 `ServerLevel#players()` 返回的**活列表**（`:1438-1440` 既非副本也非不可变视图），直接遍历即抛 `ConcurrentModificationException` 并一路抛穿事件 | `DeadKingAdvancementHandler.java:93-111` | ⇒ 发放循环照抄该写法（含快照） |
| 4 | **本模组不留进度 JSON** —— 上一次（死王仪式）的示例进度在实机通过后**已删除**，`data/beloong/advancement/` 目前**为空**；处理器 javadoc 里明确写着「本模组只提供判据，真实进度由整合包侧编写」 | 目录实测 + `DeadKingAdvancementHandler.java:64-67` | ⇒ 与要求 4 完全一致，且**有先例可循**（提交粒度、删除时机都照做） |
| 5 | **判据注册成功 ≠ 有人监听** —— `SimpleCriterionTrigger` 的 listener 集只在「引用了该判据的进度**被加载**」时才填充，否则 `trigger()` **静默 no-op** | 项目既有教训（`memory/learned-patterns.md`） | ⇒ **测试必须同时挂一条临时进度**，否则实机「什么都看不到」且无从判断是判据没触发还是没人监听 |
| 6 | **注册名就是整合包书写的形式** —— `"trigger": "beloong:hell_gate_opened"`；改名会让整合包已写好的进度失效 | `ModCriteria.java` 类 javadoc | ⇒ 名字定一次就不要再改，取与仓库既有风格一致的下划线过去式 |

**1.21.1 进度 JSON 的 schema 也已实测核对**（避免写出加载不上的测试进度）：
目录是**单数** `data/<ns>/advancement/`；图标用 `{"count":1,"id":"beloong:hell_gate"}`（1.21 起是 `id` 而不是 `item`）；
根进度可给 `background`（原版可选值只有 `stone/adventure/husbandry/end/nether` 五张）。

---

## 二、目标与非目标

### 目标

1. 新增进度判据 `beloong:hell_gate_opened`，注册名即整合包书写形式。
2. 在**地狱之门动画播完、40 格全部 `OPEN`（可进入）之后**发放。
3. 发放给**门 32 格内的所有非旁观玩家**（创造计入），每人一次。
4. 提供一条**临时的**测试进度用于实机验证，验证通过后删除（真实进度由整合包侧编写）。

### 非目标

1. **不加**判据自定义条件字段（用户裁定；整合包可用标准 `player` 谓词筛维度/坐标）。
2. **不做**「记住开门者并在 t=145 补发」（用户裁定纯位置快照）。
3. **不写**真实进度（属整合包侧交付物）。
4. **不引入**自定义事件总线的解耦层（方案 C 已否决：单一消费者，YAGNI）。
5. **不改**地狱之门的任何行为、时序、几何、资产 —— 本功能只**读**它的既有状态转换。
6. **不动**移植件里的其他内容；新增的挂钩会在 javadoc 里登记为第 4 处刻意偏离。

---

## 三、架构

三层单向依赖，只有第②层是新机制：

```
① 触发点（已有代码，仅 +1 行）
   HellGateBlockEntity.tick(...)
     if (animationTicks >= TICK_FULLY_OPEN && !level.isClientSide) {
         40 格全部 setValue(OPEN, true)            ← 先让门可进入（要求 2 的顺序落点）
         HellGateOpenedAdvancements.grant(level, pos);   ← 新增的唯一一行
     }
                │
② 编排层（新）  ▼
   com.zonlong.beloong.hellgate.HellGateOpenedAdvancements
     public static void grant(ServerLevel level, BlockPos basePos)
       · Vec3 center = Vec3.atCenterOf(basePos)        球心 = 基准格中心
       · double r2 = NEARBY_RANGE * NEARBY_RANGE        NEARBY_RANGE = 32.0
       · for (ServerPlayer p : List.copyOf(level.players()))   快照，防 CME
       ·     if (p.isSpectator()) continue;                     只排除旁观
       ·     if (p.distanceToSqr(center) >= r2) continue;        左闭右开
       ·     ModCriteria.HELL_GATE_OPENED.get().trigger(p);
                │
③ 判据（新）  ▼
   com.zonlong.beloong.hellgate.HellGateOpenedTrigger
     extends SimpleCriterionTrigger<HellGateOpenedTrigger.Instance>
     注册名 beloong:hell_gate_opened
     Instance(Optional<ContextAwarePredicate> player)   ← 只有标准玩家谓词
```

---

## 四、组件

| 文件 | 动作 | 内容 |
|---|---|---|
| `hellgate/HellGateOpenedTrigger.java` | 新增（约 66 行） | 判据本体 + `trigger(ServerPlayer)`；`Instance` 记录只有 `player` 字段（`EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player")`）；javadoc 写清注册名不可改、以及为什么不用原版判据 |
| `hellgate/HellGateOpenedAdvancements.java` | 新增（约 70 行，含 javadoc） | 薄编排层：半径常量、球心取法、过滤规则、快照防 CME；口径集中在此一处 |
| `registry/ModCriteria.java` | 修改（+1 项 + javadoc） | `HELL_GATE_OPENED = REGISTRY.register("hell_gate_opened", HellGateOpenedTrigger::new)` |
| `block/HellGateBlockEntity.java` | 修改（**+1 行** + javadoc 一句） | 在 40 格循环之后调用 `grant`；javadoc 登记第 4 处刻意偏离 |
| `data/beloong/advancement/hell_gate_test.json` | 新增（**临时**） | 测试进度；验证通过后 `git rm` |

---

## 五、数据流

| 时刻 | 发生什么 |
|---|---|
| t=0 | 玩家右键 → `HellGateBlock.useItemOn` → 换算基准格 → `attemptToRing`：基准格 `LIT=true` + `blockEvent(1)` → 客户端开启动画 |
| t=1…144 | 基准格 BE 每 tick `animationTicks++`（只有「LIT 且未 OPEN」的基准格进这段） |
| t=28 | 音效 + 范围爆炸（不破坏方块） |
| **t=145** | `!isClientSide` 分支：**先把 40 格全部置 `OPEN`**（可进入），**之后**调用 `grant(level, pos)` |
| t=145（同 tick） | `grant`：球心 → 快照 → 过滤 → 逐个 `trigger` → 命中者 `award` → 客户端同步 → toast + 聊天播报 |
| t=146 | 基准格已 `OPEN` ⇒ BE 在方法开头早退 ⇒ **该分支不再进入**（整扇门只发一次） |

> **顺序细节（要求 2 的落点）**：`grant` 必须在 40 格循环**之后**。同一 tick 内玩家感知不到，
> 但语义上「门彻底开启可以进入」发生在置 `OPEN` 的那一刻，发放应当排在其后。

---

## 六、错误处理与边界

| 情形 | 行为 | 理由 |
|---|---|---|
| 32 格内没有玩家 | 循环空转，无事发生 | 没有目标 |
| 开门者是旁观者 | 拿不到 | 用户裁定口径 |
| 开门者是创造模式 | **拿得到** | 用户裁定口径（本项目第 2 次刻意不排除创造） |
| 玩家在别的维度 | 不可能被选中 | 只遍历 `level.players()`（本维度）；跨维度距离无意义 |
| 整合包尚未编写引用该判据的进度 | `trigger` **静默 no-op** | 原版 `SimpleCriterionTrigger` 行为（事实 5）；这正是测试必须挂临时进度的原因 |
| 奖励函数把玩家传送/踢出 | 不会 `ConcurrentModificationException` | 遍历 `List.copyOf(...)` 快照（事实 3） |
| 用 `/setblock … open=true` 硬开门 | **不发放**（没走 145 分支） | 与要求 2 一致：只有走完动画才算 |
| 用 `/setblock … lit=true` 直接点火 | **会发放**（走完 145 tick 后） | 这正是实机探针所用手法 ⇒ **无需真人右键即可验证** |
| 同一扇门反复开 | 判据每扇门只发一次；同一玩家进度也只授一次 | 145 分支只进一次 + 原版进度一次性 |
| 门开完玩家才赶到 | 拿不到（只做一次位置快照） | 语义是「目睹开启」 |
| 动画播到一半服务器重启 | 重载后**继续跑完并发放一次** | `LIT` 是方块状态、`animationTicks` 落 NBT，都会恢复；门确实开了，应当发放 |
| 开门者在 7.25 s 内跑出 32 格 | **拿不到**（用户裁定纯位置快照） | 与死王仪式「死亡时刻在场者」同构；零额外状态 |

---

## 七、决策记录

| # | 决策 | 理由 |
|---|---|---|
| D20 | 判据形态：`SimpleCriterionTrigger` + **仅标准 `player` 谓词**，不带自定义字段 | 用户裁定；整合包已能用标准谓词按维度/坐标/游戏模式筛，加自定义字段只会让 schema 更难改（改名/改字段会让整合包进度失效） |
| D21 | 发放口径：**32 格、只排除旁观、创造计入** | 用户裁定。⚠️ 与死王仪式的 **60 格**不同（那次是铁魔法自己的口径）⇒ 本模组将来有两个「在场者半径」，文档必须写清各自出处 |
| D22 | 球心取**基准格中心**（`Vec3.atCenterOf(basePos)`），不取门的几何中心 | 门高 8 格，取几何中心会让竖直方向偏 4 格；32 格半径下这点差异可忽略，而基准格与「触发点所在方块」一致、最好解释 |
| D23 | **方案 B（薄编排层）** 而非把发放写在方块实体里（A）或发自定义事件（C） | 用户裁定。移植件（`HellGateBlockEntity`）只多一行、保持可审计；发放口径集中一个文件；与本仓库既有的 `dreadking/DreadKingRitualStarter` 分层同构。C 为单一消费者引入事件管道，YAGNI |
| D24 | **纯位置快照**，不特判开门者 | 用户裁定；与死王仪式同构、零额外状态（代价：开门者跑远拿不到，已列入边界表） |
| D25 | 测试进度**单独提交**、验证通过后 `git rm`，并把 JSON 模板留在设计/计划文档里 | 用户要求 4；照死王仪式的先例（示例进度清理单独可见） |
| D26 | 测试进度用**字面文案**而不是 lang 键 | 它是要删除的临时物 ⇒ 不留孤儿 lang 键（删文件即彻底消失） |

---

## 八、验收策略

### A. 静态探针

- jar 内字节码含 `ModCriteria.HELL_GATE_OPENED` 与 `HellGateOpenedAdvancements.grant`（`javap` 取证）；
- 临时进度在 jar 内存在；**删除后反向取证**：jar 内不再有 `data/beloong/advancement/`。

### B. 实机探针（无需真人右键，一个客户端运行跑完 5 个场景）

原理：数据包对基准格 `setblock … [lit=true]` 直接点火 ⇒ BE 自己走完 145 tick ⇒ 触发点自然发生。
读数手段：`execute store result score … run advancement revoke @a only <id>` ——
`advancement revoke` 的**结果值 = 有该进度的玩家数**，成功与否 = 有没有人拿到（一次调用同时完成「读」与「清场」）。

| # | 场景 | 设置 | 早期采样（点火后 5 s，早于 145 tick） | 终期采样（点火后 10 s） | 验证的是 |
|---|---|---|---|---|---|
| 1 | 正例 | tp 门前 16 格、生存 | `had=0` | `had=1` | 要求 1 + **要求 2**（早于播完不发） |
| 2 | 负例 | tp 40 格外 | `had=0` | `had=0` | 半径过滤（32） |
| 3 | 负例 | `spectator`、门前 16 格 | `had=0` | `had=0` | 旁观过滤 |
| 4 | 正例 | `creative`、门前 16 格 | `had=0` | `had=1` | 创造计入 |
| 5 | 重复开门 | 已有进度再开一扇 | `had=1` | `had=1` | 进度一次性（且不再播报） |

配套细节（均为既往踩坑换来的纪律）：

1. **每个场景用 40 条 `setblock` 重建一整扇门**（全新 BE，`animationTicks=0`）—— 复用旧门可能带着残留计时器，
   一点火就直接跳到 145，把「要求 2」的测试做废；
2. 每个场景排**两个**检查函数（早/晚），**函数名各不相同**（同名 `schedule` 只留最后一个）；
3. 排程由 `load` 标签发起、按计分板计数推进（`/reload` 后仍能继续）；
4. 结果用 `tellraw` 的 score 组件输出（函数里的命令反馈**不进日志**）；
5. 临时进度设 `announce_to_chat: true` + toast + 不隐藏 ⇒ 日志里另有一条聊天播报作为**第二条独立证据**，
   同时你能亲眼看到弹窗。

#### B-1. 实测结果（2026-09-21，一次客户端运行，5/5 通过）

| # | 场景 | 期望（早 / 晚） | **实测** | 结论 |
|---|---|---|---|---|
| 0 | 冒烟：进度能否被加载 | grant-result=1 | **1** | 进度 JSON 合法、判据被引用（否则 `trigger` 会静默 no-op） |
| 1 | 门前 16 格、生存 | 0 / 1 | **0 / 1** | 要求 1 ✅；**要求 2 ✅**（点火后 ≈4.6 s 时仍未发放） |
| 2 | 40 格外、生存 | — / 0 | **0** | 32 格半径过滤生效 |
| 3 | 门前 16 格、旁观 | — / 0 | **0** | 旁观被排除 |
| 4 | 门前 16 格、创造 | 0 / 1 | **0 / 1** | 创造计入（有意口径） |
| 5 | 已持有进度再开一扇 | — / 1 且 0 条播报 | **1 且 0 条播报** | 进度一次性 |

第二条独立证据（聊天播报，`frame=goal` ⇒ 文案是「…达成了目标[…]」）：
s0 冒烟 14:02:19.458（我自己 grant 的那次）、**s1 于 14:02:29.669**、s4 于 14:03:08.615。
s1 点火时刻 14:02:23.025 ⇒ 播报落在点火后 **≈6.6 s**（理论 145 tick = 7.25 s）。
差值来自单人服务端「追赶 tick」导致墙上时间与 tick 数不成正比 ⇒
**「正好 145 tick」的权威证据是代码位置**（`grant` 就在置 `OPEN` 的同一分支里，见 §三/§九），墙上时间只作旁证。
整轮无本模组 WARN/ERROR、无 crash-report、客户端干净退出。

#### B-2. 一处**未完成**的补强（据实记录）

为把「发放」与「门变成可进入」直接绑在一起（同一次调用里同时读「门是否 OPEN」与「是否已发放」），
我加做过一次定点复核，但**第一次没跑起来**：探针自身的 bug ——
`execute if score #loads gp2 matches 0` 对**从未设置过的分数**不成立（**分数不存在 ≠ 0**），
于是排程根本没发生、一行输出都没有。已修好并归档（`run/hellgate_probe_packs/hellgate_pairprobe`），
但按用户要求停手，未再跑。
⇒ 目前「同生同灭」这条只有**代码级证据**（同一分支内），没有同刻实机读数。

### C. 人眼验收

toast 弹窗 + 进度树里的示例根标签（测试进度无 `parent`，自成一根，便于点开查看）。机器无法断言。

### D. 测试进度的生命周期（要求 4）

| 提交 | 内容 |
|---|---|
| C1 | 判据 + 编排层 + 方块实体挂钩 + `ModCriteria` 注册（**不含**进度 JSON） |
| C2 | **临时** `data/beloong/advancement/hell_gate_test.json` |
| C3 | 设计 + 计划文档（把给整合包参考的 JSON 模板作为代码块留下）+ memory |
| C4 | 测试通过后 **`git rm` 掉临时进度**，提交信息写明「示例进度清理」 |

「测试完成」的口径 = B 的 5 个场景全绿 **且** 用户看到 toast。

### 给整合包侧的参考（模组不保留此文件）

```json
{
  "parent": "整合包自己的父进度（可选；不写则自成一根）",
  "criteria": { "opened": { "trigger": "beloong:hell_gate_opened" } },
  "display": {
    "icon": { "count": 1, "id": "beloong:hell_gate" },
    "title": { "translate": "整合包自己的翻译键" },
    "description": { "translate": "整合包自己的翻译键" },
    "frame": "goal",
    "show_toast": true,
    "announce_to_chat": true
  }
}
```

想限定维度/位置时，用标准玩家谓词即可（无需模组加字段）：

```json
"criteria": {
  "opened": {
    "trigger": "beloong:hell_gate_opened",
    "conditions": { "player": [ { "condition": "minecraft:entity_properties", "entity": "this",
      "predicate": { "location": { "dimension": "minecraft:the_nether" } } } ] }
  }
}
```

---

## 九、证据索引（源码级）

| 内容 | 位置 |
|---|---|
| 触发点（145 tick 全开） | `src/main/java/com/zonlong/beloong/block/HellGateBlockEntity.java:132-153`（40 格循环至 `:152`） |
| 「只走一次」的早退 | 同文件 `:106-114` |
| 判据范式 | `src/main/java/com/zonlong/beloong/compat/ironsspellbooks/DeadKingKillTrigger.java:42-65` |
| 判据注册中心 | `src/main/java/com/zonlong/beloong/registry/ModCriteria.java:30-55` |
| 「周围玩家都发」先例 + 快照防 CME | `src/main/java/com/zonlong/beloong/compat/ironsspellbooks/DeadKingAdvancementHandler.java:93-111` |
| 活列表非副本的证据 | NeoForge 反编译源 `ServerLevel.java:1438-1440`（见该处理器注释） |
| 「判据没人监听即静默 no-op」 | `memory/learned-patterns.md`（死王仪式条目） |
| 1.21.1 进度 JSON schema | 原版资源 jar `data/minecraft/advancement/story/{root,mine_stone}.json`（目录单数、图标 `{"id":…}`） |

---

## 十、已知边界与明确不做的事

1. **开门者跑出 32 格即拿不到**（D24，用户已确认接受）。
2. **机器验不了「多名玩家同时在场各拿一次」**：开发环境一个客户端 = 一个玩家。补偿措施：
   （a）发放循环与死王仪式逐行同构（那边已实机用过）；
   （b）用单玩家跑「半径内/外」「旁观/创造」四组场景，覆盖的正是**筛选语义**；
   （c）多人场景由用户在真实服务器上抽验。
3. **`/setblock … open=true` 硬开门不发进度** —— 这是要求 2 的必然结果，不是缺陷。
4. **不做**：判据自定义字段、开门者兜底、自定义事件、真实进度、进度条件里的门坐标。
5. **多人同时在场**已由用户实机确认通过（2026-09-21）。

---

## 十三、用户追加提问：「用钥匙开门那一刻」要不要再写判据？

**结论：不需要。那一刻用原版判据 `minecraft:item_used_on_block` 就能纯数据包实现，模组侧零改动。**

### 13.1 为什么能（两条源码级依据）

1. **触发链**：`ServerPlayerGameMode#useItemOn` 里
   `if (iteminteractionresult.consumesAction()) { CriteriaTriggers.ITEM_USED_ON_BLOCK.trigger(player, blockpos, itemstack); }`
   —— 而本模组 `HellGateBlock#useItemOn` 在钥匙命中时返回
   `ItemInteractionResult.sidedSuccess(...)`（即 `SUCCESS`，`consumesAction()` 为真）
   ⇒ **用钥匙点门必然触发该原版判据**；用无关物品点门返回 `PASS_TO_DEFAULT_BLOCK_INTERACTION` ⇒ 不触发。
2. **条件表达力足够**：1.21.1 的该判据条件是一个**战利品条件列表**，原版范式
   `data/minecraft/advancement/adventure/lighten_up.json`＝「用斧子点铜灯点亮」，
   用的正是 `minecraft:location_check`（可按方块与**方块状态**筛）＋ `minecraft:match_tool`（物品谓词，支持 `#tag`）。

### 13.2 可直接粘贴给整合包的 JSON（模组侧不需要任何东西）

```json
{
  "criteria": {
    "unlock": {
      "trigger": "minecraft:item_used_on_block",
      "conditions": {
        "location": [
          { "condition": "minecraft:location_check",
            "predicate": { "block": { "blocks": ["beloong:hell_gate"], "state": { "lit": "false" } } } },
          { "condition": "minecraft:match_tool",
            "predicate": { "items": "#beloong:hell_gate_keys" } }
        ]
      }
    }
  }
}
```

`state: {"lit": "false"}` 这一条**顺手解决了一个语义问题**：只认「这一次把门点着」，
对着已经开着的门再点不会重复触发（本模组自己的判据没有这个区分，因为它的语义是「门开完」）。

### 13.3 两个时刻的语义对照（选哪个取决于想要哪种进度）

| | **点击那一刻**（纯数据包） | **门开完那一刻**（本次实现的自定义判据） |
|---|---|---|
| 语义 | 「我用钥匙解锁了这扇门」 | 「门开了；在附近的人都算」 |
| 时机 | 右键瞬间 | 点击后 **145 tick（7.25 s）** |
| 谁能拿到 | 默认**只有点击者** | 门 **32 格内所有非旁观玩家**（创造计入） |
| 误触发 | 可用 `state.lit=false` 卡住；否则点已开的门也会触发 | 只在真正开完时发一次 |
| 落在哪 | 整合包侧 JSON（零代码） | 模组侧判据 + 编排层（已实现） |
| 额外的门槛 | 无（原版判据，整合包随便写） | 需要本模组提供 |

### 13.4 如果「点击那一刻」也要求**周围玩家都能拿**

纯数据包同样可以，但要靠**奖励函数**发散（模组的判据已经自带这个能力，数据包方案要自己写）：

```json
"rewards": { "function": "整合包:hell_gate_unlock_fanout" }
```
```mcfunction
# data/<整合包ns>/function/hell_gate_unlock_fanout.mcfunction
advancement grant @a[distance=..32,gamemode=!spectator] only <整合包的真实进度>
advancement revoke @s only <上面那个隐藏的触发用进度>   # 允许下次再触发
```
字段依据：`AdvancementRewards(int experience, List<loot>, List<recipes>, Optional<CacheableFunction> function)`
——`rewards.function` 是原版支持的字段（原版自己的进度只用了 `experience`/`recipes`，所以现成例子不多）。

### 13.5 一句话建议

- 想要「**我拿钥匙开了门**」⇒ 用 §13.2 的原版判据，**别再写判据**；
- 想要「**门开完了、在场的都算**」⇒ 就是本次这条自定义判据，已实现并验收；
- 两者语义不同，**可以并存**，互不冲突。

