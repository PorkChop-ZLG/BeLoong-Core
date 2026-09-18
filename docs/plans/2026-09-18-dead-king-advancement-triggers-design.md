# 死者之王击杀进度触发器 设计文档

**日期：** 2026-09-18
**状态：** 已批准（用户确认）
**采用方案：** 方案 A —— `LivingDeathEvent` + 两个独立判据类（**零 mixin**）
**依赖前提：** 铁魔法（Iron's Spells 'n Spellbooks）已在 `2026-09-18` 加入为**必选依赖**
（`io.redspace:irons_spellbooks:1.21.1-3.16.3`，`mods.toml` 声明 `required` + `versionRange="[1.21.1-3.16.3,)"`）

---

## 一、问题陈述

死者之王（`irons_spellbooks:dead_king`）是**多阶段 Boss 且带不祥变种**。
用原版 `player_killed_entity` 判据做"击败死者之王"的进度会**漏**，原因有三条，
均已核到 NeoForge 源码级（`build/review-src/neoforge-21.1.236/`）：

原版归功链路只有一条、且只归给**一个**实体：

```
LivingEntity.die()                    (LivingEntity.java:1408)
 └ getKillCredit()                     (:1814-1820)  → lastHurtByPlayer ?: lastHurtByMob ?: null
     └ ServerPlayer.awardKillScore()   (ServerPlayer.java:757)
         └ CriteriaTriggers.PLAYER_KILLED_ENTITY.trigger(this, killed, source)  (:771)
```

| # | 漏判成因 | 依据 |
|---|---|---|
| 1 | **多人只算一个人** —— 只有"最后打到他"的玩家获得归功 | `getKillCredit()` 只返回单个实体 |
| 2 | **超过 5 秒没打中就丢归功** —— `lastHurtByPlayerTime = 100` tick，`baseTick` 递减归零即清空 `lastHurtByPlayer` | `LivingEntity.java:1218,1221`（置 100）与 `:484-488`（衰减清空） |
| 3 | **致命一击不是玩家时无人获得** —— 归功退化为 `lastHurtByMob` 甚至 `null` | `:1814-1820`；仅**驯服宠物**会把主人设为 `lastHurtByPlayer`（`:1221`） |

**对死者之王尤其致命**：他有 **139 tick（≈7 秒）的无敌转换期**，第二阶段**飞行 + 远程为主**
（近战倾向从 0.8 降至 0.3），玩家很容易连续 >5 秒打不到他。

> 注：**不是误报**。50% 血时的"假死"只把血量设回半血并置无敌、进入 `Transitioning`，
> **不触发 `die()`**，因此原版判据不会在假死时误发。

**结论**：需要一个**不依赖归功**、**按"死亡时在场"判定**、且**能区分不祥变种**的触发器。

---

## 二、目标与非目标

### 目标

1. 新增**两个**进度判据，供整合包侧写进度：
   - `beloong:dead_king_kill` —— 击杀**普通**状态死者之王
   - `beloong:ominous_dead_king_kill` —— 击杀**不祥**状态死者之王
2. Boss 真死时，**按空间快照**给范围内的每个合格玩家各自发放（解决成因 1/2/3）。
3. **优先不用 mixin**。

### 非目标

- ❌ 不改铁魔法任何代码（只读它的 public API）
- ❌ **不读**铁魔法的"参与者"名单（见"决策 D6"；也因此不硬编码 `boss_loot_participants` 私有 NBT 键）
- ❌ 不处理 `dead_king_soul` / `dead_king_corpse`（另外两个实体类型，`instanceof` 天然排除）
- ❌ 不做"只受伤即触发"、"进战即触发"等其它触发点
- ❌ 不加配置开关（纯判定强化，非可调玩法）
- ❌ **不交付真实进度** —— 本模组只提供触发器（项目既定约定，见 `claw_sword` 处置）；
  本次仅**附带两条示例进度**用于测试，**测完删除**

---

## 三、架构

### 变更清单

```
新增  src/main/java/com/zonlong/beloong/compat/ironsspellbooks/
      ├── DeadKingKillTrigger.java            判据①（普通）
      ├── OminousDeadKingKillTrigger.java     判据②（不祥）
      └── DeadKingAdvancementHandler.java     事件处理器

改动  src/main/java/com/zonlong/beloong/registry/ModCriteria.java    +2 行（判据注册）
改动  src/main/java/com/zonlong/beloong/BeLoongCore.java             +1 行（处理器订阅）

新增（临时）src/main/resources/data/beloong/advancement/dead_king/
      ├── kill.json                           示例进度（测完删）
      └── ominous_kill.json                   示例进度（测完删）
```

### 决策记录

| # | 决策 | 取值 | 理由 |
|---|---|---|---|
| **D1** | 触发事件 | **`LivingDeathEvent`** | **零 mixin**。`DeadKingBoss` **未覆写 `die()`**（只覆写 `tickDeath()`），事件由 `CommonHooks.onLivingDeath` 在 `die()` 开头派发，此时实体有效、`isOminous()` 可读。项目已有同款范式：`ClawSwordAdvancementHandler.onDeath` |
| **D2** | 判据形态 | **两个独立 `SimpleCriterionTrigger` 子类** | 与 `claw_sword_swap` / `claw_sword_kill` 的"双 ID"风格一致；进度 JSON 不会因漏写参数而静默误配 |
| **D3** | 判据 ID | `beloong:dead_king_kill`<br>`beloong:ominous_dead_king_kill` | 见名知义；`ominous_` 前缀让两者在列表里相邻（用户确认） |
| **D4** | 包位置 | 实现 → `compat/ironsspellbooks/`；注册 → `registry/ModCriteria` | 与 `compat/dragonsurvival/ClawSword*` + `registry/ModCriteria` 的现有分工逐字一致 |
| **D5** | 玩家口径 | **`player.distanceToSqr(boss) < 3600`**（60 格）且 `!isSpectator()`；**创造模式计入** | 半径与铁魔法 `DeadKingBoss.finalizeSpawn`（`:306-308`）一致；**人员过滤有意偏离**（见下方修订）。**这是用户选定的方案 B**：死亡时刻的空间快照 |
| **D6** | 变种判定 | **`boss.isOminous()`** | 它是 `io.redspace.ironsspellbooks.api.entity.IOminousEntity` 接口的 public 方法 ⇒ **不碰铁魔法任何私有实现** |
| **D7** | `isLoaded` 守卫 | **不加** | 铁魔法已是 `required` 依赖，必然存在；加了反而与"必选"语义矛盾 |
| **D8** | 示例进度 | **附带，但标为临时**（用户指定） | 用于排除"判据注册了但没进度引用 ⇒ 静默 no-op"这个测试盲区 |
| **D9** | 是否抽公共基类 | **不抽** | ① 项目先例就是两个独立类；② 将来给其中一个加专属条件（剩余血量/通关时长）不必动另一个；③ 判据类去掉 javadoc 后仅约 15 行，抽象收益小于风格一致性收益 |

### D5 修订（2026-09-18，实机测试后由用户裁定）

> 初版 D5 为「排除创造与旁观」——即**逐字对齐**铁魔法 `finalizeSpawn`。
> 实机测试确认功能正常后，**用户要求改为人事过滤只排除旁观、创造模式计入**。
>
> **当前生效口径**：`distanceToSqr(boss) < 3600`（60 格）**且非旁观**；**创造模式计入**。
>
> **由此产生的一处刻意不对称**（已接受）：创造模式玩家会拿到进度，但**仍不会**进铁魔法
> 自己的"每人掉落"名单（`finalizeSpawn` 那边继续排除创造）
> ⇒ 可能出现**"有进度、无掉落"**。这与第 1 条"不采用参与者名单"的裁定方向一致：
> 本功能与铁魔法的掉落名单**本来就不保证一致**。

### 关于"参与者"的重要说明（为什么没采用）

铁魔法**自己**维护了一份"参与者"名单（`BossLootHandler.participantIds`，用于**每人掉落**），
但本设计**刻意不用它**：

| 事实 | 影响 |
|---|---|
| `DeadKingBoss.bossLoot` 是 `private final`；`BossLootHandler` 公开方法只有 `setParticipants` / `setParticipantsFromPlayers` / `prepareDrops` / `spawnPreparedDrops` / `save` / `load` —— **没有 getter** | 拿不到现成名单 |
| 唯一零 mixin 读法是序列化读 NBT：`Entity.saveWithoutId(tag)` → 键 `boss_loot_participants`（`BossLootHandler.java:31,134-141`） | 可行，但**硬编码两个私有 NBT 键**，改名即静默失效 |
| **`setParticipantsFromPlayers` 只在 `finalizeSpawn()` 调用一次**（`DeadKingBoss.java:310`），之后不再更新 | 参与者 = **生成时刻**的快照；"打一半才加入的玩家"**拿不到**，与"死亡时在场"语义**相反** |

⇒ 用户选择 **D5（死亡时重算）**，因此本设计**不引入对私有实现的依赖**。

---

## 四、组件

| # | 组件 | 职责与关键点 |
|---|---|---|
| **C1** | `DeadKingKillTrigger` | `extends SimpleCriterionTrigger<Instance>`；`Instance` 是只含 `Optional<ContextAwarePredicate> player` 的 record，`CODEC` 用 `EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player")`；对外暴露 `trigger(ServerPlayer)`（内部 `this.trigger(player, instance -> true)`）。**逐字照 `ClawSwordKillTrigger` 样板** |
| **C2** | `OminousDeadKingKillTrigger` | 除类名外与 C1 相同，**独立注册 ID**。`SimpleCriterionTrigger` 的 listener 集是 per-instance 的，两者互不干扰 |
| **C3** | `DeadKingAdvancementHandler` | `@SubscribeEvent public void onDeath(LivingDeathEvent)`：`instanceof DeadKingBoss` → 读 `isOminous()` → 重算 60 格内合格玩家 → 按变种触发 C1/C2 |
| **C4** | `ModCriteria`（改） | 沿用既有 `DeferredRegister<CriterionTrigger<?>> REGISTRY`，加两个条目 |
| **C5** | `BeLoongCore`（改） | 构造器 `NeoForge.EVENT_BUS.register(new DeadKingAdvancementHandler())` |
| **C6** | 两条示例进度（临时） | 见第六节 |

---

## 五、数据流与错误处理

### 数据流

```
Boss 真正死亡
 └ LivingEntity.die()                         (LivingEntity.java:1408)
     └ CommonHooks.onLivingDeath()             → 派发 LivingDeathEvent
         └ DeadKingAdvancementHandler.onDeath
             ├─ event.getEntity() instanceof DeadKingBoss ?  ── 否 → return
             ├─ boolean ominous = boss.isOminous()
             ├─ boss.level().players()
             │     .filter(p -> p.distanceToSqr(boss) < 3600
             │                && !p.isSpectator())
             └─ 对每个命中玩家：
                   (ominous ? C2 : C1).get().trigger(player)
                     └ SimpleCriterionTrigger.trigger(player, i -> true)
                         └ 查该判据的 listener 集合
                             ├─ 空（无进度引用该判据）→ 静默 no-op
                             └ 非空 → listener.run(playerAdvancements) → 授予进度
```

**两个要点**

- **listener 集合只在"引用了该判据的进度被加载"时才填充**
  （链路：`PlayerAdvancements.load → registerListeners → addPlayerListener`，见
  `2026-09-18-post-0.9.3-code-review.md` 的 L5 一节）。因此"判据注册成功"与"有人监听"是两件事。
- 本处理器**不依赖任何掉落逻辑**：`LivingDeathEvent` 在 `dropAllDeathLoot` **之前**派发
  （`die()` 的 `:1409` vs `:1430`），与铁魔法自己的四人掉落互不干扰。

### 错误处理与边界

| 场景 | 行为 | 评价 |
|---|---|---|
| 60 格内无玩家 | 谁都不触发 | 正确 |
| 玩家在范围内但**旁观** | 跳过 | 与铁魔法口径一致 |
| 玩家在范围内且**创造** | **计入**（会发放） | **D5 修订**后有意偏离铁魔法口径；此类玩家不会进它的"每人掉落"名单 |
| 无任何进度引用该判据 | 静默 no-op（原版行为） | 无害；C6 的示例进度正是用来排除这个测试盲区 |
| Boss 被 `/kill` 或环境/召唤物杀死 | **照样触发** | **本特性的目的**（原版只看 `lastHurtByPlayer`，非玩家致死会漏） |
| Boss 复活后再杀 | 判据再次触发；进度本身只授予一次 | 正确 |
| **其他模组取消 `LivingDeathEvent`** | **按默认语义不发放** | **刻意不干预**：`CommonHooks.onLivingDeath` 返回 `isCanceled()`（可取消），死亡既被阻止就不该给进度。**不加 `receiveCanceled`、不改优先级** |
| 打死 `dead_king_soul` / `dead_king_corpse` | 不触发 | `instanceof DeadKingBoss` 天然排除 |
| 铁魔法缺席 | 不可能 | 已是 `required` 依赖；处理器直接引用 `DeadKingBoss`，不加守卫 |

**一个明确的不做**：不监听 `LivingIncomingDamageEvent` 记"谁打过他"。用户选定 D5（死亡时空间快照），
加记账会把语义滑向"只给参战者"。

---

## 六、示例进度（**临时，测完删除**）

两条都做成**根进度**（不设 `parent`）：① 避开"父级不存在 ⇒ 子进度被移出进度树、toast 与界面永远没有"
这个已知坑（见 `memory/learned-patterns.md`）；② 各自独立成页签，**可单独测试任意一条**。

文案用**字面文本**而非 `translate` 键 ⇒ **不碰 lang 文件**，删除面仅这 2 个 JSON。
图标故意用对应变种的**真实掉落**。

```jsonc
// src/main/resources/data/beloong/advancement/dead_king/kill.json
{
  "display": {
    "icon": { "id": "irons_spellbooks:necronomicon_spell_book" },
    "title": { "text": "[示例] 击败死者之王" },
    "description": { "text": "在 60 格内参与击败普通状态的死者之王。" },
    "frame": "challenge",
    "background": "minecraft:textures/gui/advancements/backgrounds/end.png",
    "show_toast": true, "announce_to_chat": true, "hidden": false
  },
  "criteria": { "killed": { "trigger": "beloong:dead_king_kill" } },
  "requirements": [["killed"]]
}
```

```jsonc
// src/main/resources/data/beloong/advancement/dead_king/ominous_kill.json
{
  "display": {
    "icon": { "id": "irons_spellbooks:wicked_bone_ring" },
    "title": { "text": "[示例] 击败不祥死者之王" },
    "description": { "text": "在 60 格内参与击败不祥状态的死者之王。" },
    "frame": "challenge",
    "background": "minecraft:textures/gui/advancements/backgrounds/nether.png",
    "show_toast": true, "announce_to_chat": true, "hidden": false
  },
  "criteria": { "killed": { "trigger": "beloong:ominous_dead_king_kill" } },
  "requirements": [["killed"]]
}
```

---

## 七、测试策略

沿用项目既定口径（`memory/learned-patterns.md`：「运行期验证在本工程不稳定，批次门不能只依赖它」）。

### 一级（实现方执行，可靠）

1. `.\gradlew.bat build` 通过
2. **jar 内自检**：两个判据类在 jar 内；两条示例进度在 jar 内；
   `beloong.mixins.json` **未被改动**（本方案零 mixin）
3. **注册名核对**：读 `ModCriteria` 的两个 ID 字面量，与两条 JSON 的 `"trigger"` 值**逐字比对**
   —— 这是最容易写错、且写错后**静默失效**的地方

### 二级（需实机）

| 步 | 操作 | 期望 |
|---|---|---|
| 1 | 创造模式取 `irons_spellbooks:dead_king_corpse_spawn_egg` 放尸体并触发 | Boss 出现 |
| 2 | 打死（走完第二阶段） | **弹「[示例] 击败死者之王」** |
| 3 | 用护命匣复活，**这次带 Bad Omen** 站近处触发 | 不祥变种（血量 **1000**） |
| 4 | 打死 | **弹「[示例] 击败不祥死者之王」** |
| 5 | `/advancement revoke @s only beloong:dead_king/kill` 后再打一次普通版 | 应能再次获得 |
| 6 | 站在 60 格外（用旁观以外的号）远程参与 | **不该**获得 |
| 7 | 用**创造模式**号站在 60 格内参与击杀 | **应该**获得（D5 修订后） |
| 8 | 用**旁观模式**号站在 60 格内 | **不该**获得 |

---

## 八、后续

1. **本例的两个进度 JSON 与相关触发链，测完即删**（用户指定）；判据本身**保留**
   （与 `claw_sword` 的处置一致：本模组只提供触发器，真实进度写在整合包侧）。
2. 若将来要给不祥那个判据加专属条件（如"Boss 剩余血量"或"通关时长"），
   `Instance` 加字段即可，不影响普通判据（D9 的收益）。
3. 若将来要改成"只给参战者"（方案 D），需要新增 `LivingIncomingDamageEvent` 记账 —— 属**新需求**，不在本设计范围。

## 九、来源

- 死者之王机制：`docs/死者之王机制总结.md`（基于铁魔法官方源码 `1.21.1-3.16.3`）
- 原版归功链路：`build/review-src/neoforge-21.1.236/` 的 `LivingEntity.java` / `ServerPlayer.java`
- 项目判据先例：`compat/dragonsurvival/ClawSword{Kill,Swap}Trigger.java`、
  `registry/ModCriteria.java`、`BeLoongCore.java:97,107`

---

## 十、收尾（实际结果记录）

### 10.1 实机验证

二级测试矩阵（第七节）**全部通过**，含两条关键行：创造模式 60 格内**获得**、旁观**不获得**。

### 10.2 实现后审查：发现 3 项，均已修

| # | 严重度 | 问题 | 处置 |
|---|---|---|---|
| 1 | **中**（文档与代码矛盾，会诱发回归） | `ModCriteria` 两个判据的注释写「**非创造**非旁观」，与 D5 修订后的真实行为**正好相反**。这行注释会诱导后人把创造排除"改回来"，而创造计入是用户明确裁定要保留的偏离 | 注释改写为「只排除旁观 —— 创造模式计入」，并指向 `DeadKingAdvancementHandler` 类 javadoc |
| 2 | 低（javadoc 过度声称） | 处理器的「取消语义」原文称"死亡既然已被别的模组阻止，就不该发放进度"。但事件按优先级派发，本处理器在默认 `NORMAL`；若取消方在 `HIGH`/`HIGHEST`，本处理器**已经先发放了**。原表述只覆盖「先取消、后送达」 | javadoc 改为显式说明该覆盖不完整，并记录"已知模组无此操作，故不加复杂度"的取舍 |
| 3 | 低（潜在崩溃向量） | 直接迭代 `serverLevel.players()`。该方法把**内部活列表原样返回**（`ServerLevel.java:1438-1440`，非副本、非不可变视图），而 `trigger()` 会执行进度**奖励函数**；奖励函数里的跨维度传送或踢出会从该列表移除玩家 ⇒ `ConcurrentModificationException` 从 `LivingDeathEvent` 抛穿 `LivingEntity#die()` | 改为 `List.copyOf(serverLevel.players())` 快照迭代 |

复核确认无害的两点：

- `trigger(player, instance -> true)` 的 `true` **不会**绕过进度 JSON 里可选的 `player` 谓词 ——
  `SimpleCriterionTrigger.java:51-52` 会独立用 `EntityPredicate.createContext` 求值；
- 距离边界 `>= 3600.0` 与铁魔法 `finalizeSpawn` 的 `< 3600` 严格互补，60.0 格整被排除，
  与铁魔法口径逐字一致。

### 10.3 示例进度清理

`data/beloong/advancement/dead_king/{kill,ominous_kill}.json` **已删除**（第八节第 1 条）。
判据本身保留 —— 本模组只提供触发器，真实进度由整合包侧编写；删掉后若整合包未引用，
`trigger()` 回归原版的静默 no-op，无害。

### 10.4 遗留（明确不做）

- 创造模式「有进度、无掉落」的不对称：刻意接受（10.2 第 1 项）。
- 60 格内未参战者（挂机、安全屋）也会拿进度：这是"死亡时刻空间快照"方案的固有代价，
  用户已在方案对比时选定了它，而非记账式的方案 D。

