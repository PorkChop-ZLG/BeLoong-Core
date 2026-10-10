# 龙之生存技能相关三个 PR 的对抗性审查（PR #16 / #17 / #18）

> **范围：** 2026-10-09 ~ 2026-10-10 合并的三个与自制 DS 技能有关的 PR。
> **方法：** 逐条把代码/文档里的「事实声明」（行号引用、机制断言）拿去与上游源码对撞，而不是只读 diff。
> **审查日期：** 2026-10-10
> **审查者：** 智能体（含 2 个独立子代理交叉核对）

---

## ✅ 裁定结果（2026-10-10，用户）：**无需修复 / NO FIX REQUIRED**

> **三个 PR 全部合规，无需任何代码修改。**
>
> - **C-1 / C-2 判定为「设计上的修改，不是 BUG」** ⇒ 已作为设计变更记入
>   `docs/自制龙之生存技能总设计.md` **§十五「2026-10-10 修订（rev 1）：`air_strike` 改为豁免友军」**，
>   并在同文档 §4.5 / §7.5 / §9.2 / §10 / §13 标记被取代的旧条款；
>   同时记入 `memory/decisions-log.md`（2026-10-10 条目，取代该文件 `:1946,1954` 的 2026-08-14 裁定）。
> - **其余 I-x / S-x 项一律不修**，仅作记录留档。
>
> **本报告的全部发现仍然有效**——它们是对上游源码/字节码的核对结果（含 20 余条被验证为**正确**的机制断言）。
> 下方的严重度分级（C/I/S）**现在只表示「若在别的语境下重新评估时的参考权重」，不代表存在待办事项**。
> 若要重启其中任何一条，应以本裁定为起点重新讨论，而不是当作既有的缺陷清单。

---

## 零、被审对象

| PR | merge | 分支 / commit | 内容 | 规模 |
|---|---|---|---|---|
| #16 | `d29a33b` | `Tangwenjun910/feature/execute-ability` @ `4bf8db7` | 新增自制被动技能「斩杀」`beloong:execute` | 28 文件 / +1746 |
| #17 | `b21f967` | `Tangwenjun910/fix/execute-kill-attribution` @ `e56bef6` | 斩杀击杀改计入玩家（伤害来源带击杀者实体） | 4 文件 / +84 −30 |
| #18 | `f5b42fe` | `ken1882/compeador/fix-airstrike-check` @ `4d4abb7` | `air_strike` 增加友军过滤 | 1 文件 / +4 |

## 一、证据基线（可复现）

**⚠️ 先说一个必须先解决的问题：DS 版本有三处不一致。**

| 出处 | 声明的 DS 版本 |
|---|---|
| `build.gradle:147`（实际编译/运行锚定） | `curse.maven:dragons-survival-420799:8973485` |
| 该 jar 内 `META-INF/neoforge.mods.toml` | **2.0.71** |
| 本机唯一的 DS **源码**参考树 `D:\Minecraft\开源模组参考文件\DragonSurvival` | **2.0.69**（`gradle.properties:7`，HEAD `511692bea`） |
| `docs/自制龙之生存技能总设计.md:665` | `8726322`（= **2.0.67**，已过时） |
| `docs/plans/2026-10-09-execute-passive-design.md:4` | 称与 `D:\wdsjlzscsj\源代码\DragonSurvival-1.21.1` 同版本 —— **该路径在本机不存在** |

缓存里三个 jar 实测：`8726322 = 2.0.67`、`8871661 = 2.0.70`、`8973485 = 2.0.71`。
⇒ **「拿 DS 源码核对」这件事本身有 2 个版本的落差**：所有 DS 行号只能对 2.0.69 成立；jar 无源码，2.0.71 是否漂移**无法核验**（本次抽查的 DS 行号在 2.0.69 上全部精确命中，且相关类/签名在 2.0.71 jar 里 `javap` 均可确认存在）。

**本次实际跑出的证据块：**

| 项 | 命令 | 结果 |
|---|---|---|
| 编译 | `.\gradlew.bat compileJava --rerun-tasks --no-build-cache` | **BUILD SUCCESSFUL**，exit 0，`2 actionable tasks: 2 executed`（确为真编译，非 `UP-TO-DATE`），**3 个警告**——与 `memory` 记录的既有 mixin 映射警告完全一致，无新增 |
| 项目自带静态门 | `python tools\validate_execute.py` | **ALL CHECKS PASSED**，exit 0 |
| 语言键 | 逐键集合比对 | zh=321 / en=321，**集合完全一致**（only-zh=[]、only-en=[]） |
| 原版/NeoForge 源 | `build/moddev/artifacts/neoforge-21.1.236-sources.jar` | 已解包到 `build/review-src/neoforge-21.1.236/`（**恢复了总设计文档 §八 引用的那个路径**，该目录此前已不存在） |

---

## 二、PR #18（`air_strike` 友军过滤）—— 发现的冲突（**已裁定为设计变更，不修**）

改动只有 4 行（`ability/AirStrikeEffect.java:117-119`），但影响面最大。

### C-1（严重）．与「用户已裁定」的设计直接冲突，且把 5 处文档变成假陈述

2026-08-14 的裁定原文仍在：

- `memory/decisions-log.md:1946`：``- `AirStrikeEffect`: keep the intentional "hit everyone except self" behavior; change `air_strike.json` `targeting_mode` to `all_except_self`.``
- `memory/decisions-log.md:1954`：`AirStrike's friendly fire is a deliberate design.`

总设计文档里因此写下、现在全部失效的四处：

| 位置 | 原文 | 现状 |
|---|---|---|
| `docs/自制龙之生存技能总设计.md:543`（§9.2 明确不做） | 「**`air_strike` 的敌我不分是有意设计**（打到范围内所有人、只排除自己），不做友军豁免」 | 已被推翻，未同步 |
| `:476`（§7.5） | 「`all_except_self`：声明「打到所有人、只排除自己」，**与效果内部 AoE 的行为一致**」 | **成了假陈述** |
| `:563`（§10 验收要点） | 「队友/自己的宠物是否命中（当前设计为**会**命中）」 | **反向**（现在是不会） |
| `:655`（§13 变更历史） | 只有 2026-08-14 的裁定条目 | 缺 PR #18 的条目 |

PR 提交信息写的是 `fix: air strike land on friendly units`——**这是一次行为回退（或设计变更），不是 bug 修复**，但没有任何文档/裁定同步。
⇒ **请裁定**：是（A）回退该改动，还是（B）确认改成「不打友军」并同步上表全部位置 + `decisions-log`。

### C-2（严重）．在整合包实际配置下，`air_strike` 变成「打不到任何玩家」

这不是「友军豁免」，而是**玩家全豁免**。链条：

1. `TargetingMode.PLAYER_FLAG`（DS 服务端配置键 `abilities.player_targeting_handling`）= 1 时，`ALWAYS_ALLY` 生效。
2. `TargetingMode.java:98-101` —— `isFriendly` 的**第一句**就把**任何** `Player` 判为友方：
   ```java
   if (target instanceof Player && (PLAYER_FLAG & ALWAYS_ALLY) != 0) { return true; }
   ```
3. `TargetingMode.java:83-85` —— 友方分支直接**否决** `NON_ALLIES`（`NON_ALLIES` 不在该分支的白名单里）。
4. ⇒ `NON_ALLIES.isEntityRelevant(player, <任意玩家>, true)` **恒为 false**。

**配置实测**（本次全盘扫描，同一个文件在开发环境与整合包里取值相反）：

| 文件 | 行 | 值 |
|---|---|---|
| `D:\Minecraft\BeLoong-Server\config\dragonsurvival-server.toml` | 510 | **1** ← 整合包 |
| `D:\Minecraft\【化龍】服务端\化龍本地测试服\config\dragonsurvival-server.toml` | 510 | **1** ← 整合包 |
| `D:\Minecraft\BeLoong-Core\run\config\dragonsurvival-server.toml` | 510 | 0 ← 开发环境 |

⇒ 开发环境（0）复现不出来，整合包（1）里 `air_strike` 对**任何玩家**（含无队伍、敌对玩家）都打不到。改动前这些玩家是能被打到的，**这是净回归**。
⇒ 同时构成了一个改动前不存在的**隐式外部依赖**：`air_strike` 的命中集合现在由 DS 的一份服务端配置决定，而文档与验证脚本都没提。

### I-1（重要）．与项目自有的另两套「非友方」口径分叉，本处更松

| 过滤项 | `TornadoEntity.canAffect:340-367` | `ExecuteMarkHandler.isValidTarget:89-117` | PR #18 `AirStrikeEffect:116-119` |
|---|---|---|---|
| 同队队友 | 排除 | 排除 | 排除 |
| `TamableAnimal` 宠物（狼/猫/鹦鹉） | 排除 | 排除 | **排除**（`TamableAnimal.java:218-226` 的 `isAlliedTo` 覆盖了 owner，故本处也覆盖） |
| **`OwnableEntity` 但非 `TamableAnimal`**（驯服的马/驴/羊驼） | 排除（`:360`） | 排除（`:111`） | **不排除** ❌ |
| **`is_allied:false` 的 DS 召唤物** | 排除（`:365-366`） | 排除（`:115-116`） | **不排除** ❌ |

依据：全树 `isAlliedTo` 只有 `Entity:2444`、`TamableAnimal:218`、`AbstractIllager:35`、`Evoker:106` 四处覆写，**`AbstractHorse` 不在其中**；而 `SummonedEntities.hasSummonRelationship:74` 要求 `data.isAllied` 为真才结成盟友关系。
⇒ 若采纳 (#18)，建议**统一复用 `TornadoEntity.canAffect` 的口径**，而不是再写第三套。

### S-1（建议）．`getRootVehicle()` 那条子句近乎死代码，注释也不准确

`AirStrikeEffect.java:117-118` 的 `e.getRootVehicle() != player.getRootVehicle()`：

- 本效果入口已要求 `ServerFlightHandler.isGliding`（`:68`）；
- `ServerFlightHandler.isGliding:346-349` ⇒ `isFlying(player)` ⇒（`:170`）`!player.isPassenger()`；
- ⇒ 执行到此时恒有 `player.getRootVehicle() == player`，该子句退化为「有实体骑在滑翔中的施法者身上」——原版造不出这种局面。
- 它**不**过滤「施法者自己的坐骑」（那要求施法者是乘客，已被 `isGliding` 排除）。
- 注释 `// Skip the dragon's own riders and allies` 把两个子句混为一谈：`allies` 是下一子句的职责，且范围大得多。

### S-2（建议）．硬编码 `isHarmful = true`，绕过了数据声明

DS 自己的 4 个调用点全部传 JSON 值：`entityTarget.isHarmful()`（`AreaTarget.java:41`、`DiscTarget.java:61`、`DragonBreathTarget.java:48`、`LookingAtTarget.java:48`）；该字段在 codec 里 `Codec.BOOL.optionalFieldOf("is_harmful", false)`（`AbilityTargeting.java:89`），即**默认 false**，而 `air_strike.json` 并未声明它。
本次已证明：在 `PLAYER_FLAG` 默认值下 `true`/`false` 对 `NON_ALLIES` **等价**（能走到 `:91` 分支需要 `canAttackPlayer == false`，而那要求同队且关友伤 ⇒ `isFriendly` 必然已先返回 true，不可达）。⇒ **不是 bug**，但属于「把 DS 内部语义写死在调用点」，建议加注释或改读字段。

### S-3（建议）．声明与实现再次脱节

`air_strike.json:47` 仍写 `"targeting_mode": "all_except_self"`，而 Java 现在实现的是 `NON_ALLIES`（更严）。2026-08-14 那次裁定做的正是「让声明与实现一致」，现在以反方向重新失配。注意 `target_type: dragonsurvival:self` 下该字段**不参与运行期过滤**，所以这是文档/数据一致性问题，不是玩家可见 bug。

### S-4（建议）．零回归防护

`src/test` 不存在；`tools/` 下**没有任何** `*.py` 引用 `air_strike`（全仓 grep 零命中）。⇒ 这次行为变化不可能被任何自动化断言拦下。

---

## 三、PR #16 / #17（斩杀技能 + 归因修复）—— 代码正确；文档/注释有 4 处表述不准（**已裁定不修**）

**结论：修复本身是对的、且必要**，本次逐条核实了它的机制前提（见 §四）。问题集中在**注释与设计文档的事实声明**。

### I-1（重要）．「`PLAYER_KILLED_ENTITY` 进度判据依赖 `getEntity()`」是**错的**

出现位置：`registry/ExecuteDamageSource.java:37`、`registry/ExecuteThresholdEffect.java:138`、`docs/plans/2026-10-09-execute-passive-design.md:181`。

真实链路走的是 **`getKillCredit()`** 一侧，与 `damageSource.getEntity()` 无关：

- `LivingEntity.java:1412-1414`：`LivingEntity livingentity = this.getKillCredit(); ... livingentity.awardKillScore(this, ...)`
- `ServerPlayer.java:757-773`：`awardKillScore` → `:771` `CriteriaTriggers.PLAYER_KILLED_ENTITY.trigger(this, killed, damageSource)`（全树仅此一处 `.trigger`）

**改动仍然必要**，但正确的理由是另外两条（这也是本次核实的）：
- `Player.java:1704-1706` `killedEntity` → `awardStat(Stats.ENTITY_KILLED…)`，且它同时是 `LivingEntity.java:1428` 掉落分支的闸门；
- `LivingEntity.java:1518` 掉落表 `ATTACKING_ENTITY` = `damageSource.getEntity()`。

⇒ 建议把这三处注释/文档改成「击杀统计（`Stats.ENTITY_KILLED`）+ 掉落表 `ATTACKING_ENTITY`」，并删掉 `PLAYER_KILLED_ENTITY`（或注明它走 `getKillCredit()`）。
连带：`design.md:186` 说「`getKillCredit()` **只**救回了记分板与死亡消息」是**低估**——第一版其实还保住了 `PLAYER_KILLED_ENTITY` 进度、`MOB_KILLS`/`PLAYER_KILLS` 与记分板击杀数。

### I-2（重要）．`setLastHurtByPlayer` 早于 `hurt()`，失败分支会留下超长击杀信用

`ExecuteThresholdEffect.java:154-159`：

```java
victim.setLastHurtByPlayer(player);                              // :154
if (!victim.hurt(new ExecuteDamageSource(damageType, player), damage)) {   // :156
    return;                                                      // :157-158
}
```

`LivingEntity.java:626-629` 的 `setLastHurtByPlayer` 存的是 **`this.tickCount`**（不是固定 100）：

```java
public void setLastHurtByPlayer(@Nullable Player player) {
    this.lastHurtByPlayer = player;
    this.lastHurtByPlayerTime = this.tickCount;
}
```

在正常路径上 `hurt()` 内部会把它覆盖成 100 刻（`LivingEntity.java:1218`），所以主流程无恙；但一旦 `hurt()` 返回 false（无敌 / 已死 / `canHarmPlayer` 拦截等）就**提前 return 且不清除**，目标身上会留下一个长度 ≈ 当前游戏刻的「最近被该玩家伤害」信用，可影响此后无关死亡（摔落、火焰）的 `LAST_DAMAGE_PLAYER`、经验与击杀统计。
⇒ 建议在早退分支里清掉，或改成 `hurt()` 成功后再补记。

### I-3（重要）．普通怪物的死亡消息原版**根本不显示**，需求 8 的主用例看不到

走查原版全部现实调用点：

| 死者 | 消息去哪 | 证据 |
|---|---|---|
| 玩家 | 广播（死亡界面/聊天） | `ServerPlayer.java:690` |
| 驯服宠物 | 发给主人 | `TamableAnimal.java:239-244` |
| 村民 | **只写日志** | `Villager.java:654` |
| **其它生物** | **仅在「有自定义名」时写日志** | `LivingEntity.java:1421-1423` |

本模组与 DS 源码中都没有广播怪物死亡消息的处理器。⇒ 覆写 `getLocalizedDeathMessage` 只能覆盖**玩家死亡**与**宠物死亡**；而斩杀的典型目标是**僵尸这类普通怪**，那条「僵尸被 Steve 的成年龙被动斩杀了」**永远不会出现在任何玩家眼前**（除非怪物有名字，那也只是进日志）。
⇒ 需求 8 的「阶段词」目前在主用例上是**不可观测**的。若要真正生效，需要在 `LivingDeathEvent` 里自行广播。

### I-4（重要）．设计文档 §5.10 对 `%d` 失败模式的描述是**错的**

`design.md:326` 与 PR #16 提交信息都称：语言值里写 `%d` 会「在**渲染工具提示的那一刻**抛 `TranslatableFormatException`」。

真实代码是**捕获并降级**，不抛：

- `net/minecraft/network/chat/contents/TranslatableContents.java:114-120` —— `decomposeTemplate` 的异常被就地接住，并回退成**原样的模板字符串**：
  ```java
  } catch (TranslatableFormatException translatableformatexception) {
      this.decomposedParts = ImmutableList.of(FormattedText.of(s));
  }
  ```
- 抛出点确在 `:149`（`%d` ⇒ `Unsupported format`），但它在同一个 try 里。

⇒ `%d` 的真实症状是**静默显示未展开的原文**（如「数量限制：%d」），不是崩溃——这其实**更难被发现**，所以这个修复更有价值；但文档把失败模式写错了，而它正是「全仓扫一遍」的理由。

**顺带核实：`%d` → `%s` 的修复本身正确且完整。** 本次逐键扫描中英两个语言文件，除合法的 `%1$s`/`%2$s` 位置占位符外，**再无 `%d`、`%f` 或裸 `%`**；`%%` 的合法性也在 `TranslatableContents.java:145-146` 得到确认（`if ("%".equals(s4) && "%%".equals(s1))`），因此新增的 `dynamic_desc` / `effect…description` 里的 `%%` 安全。

### S-1（建议）．三处死代码

| 符号 | 位置 | 使用次数 |
|---|---|---|
| `ExecuteAbility.entityTargeting` | `ability/ExecuteAbility.java:100` | 0 |
| `ExecuteMarkHandler.isMarkableBy` | `registry/ExecuteMarkHandler.java:120` | 0 |
| `ExecuteCooldown.remaining` | `registry/ExecuteCooldown.java:41` | 0 |

### S-2（建议）．`execute.json` 的 `targeting_mode` 语义别扭

`data/beloong/dragonsurvival/dragon_ability/execute.json:51` 写 `"allies_and_self"`。该行动是 `beloong:execute` 的**空壳载体**（`ExecuteEffect.apply` 是 no-op），真实语义是「标记非友方」。虽然 `SelfTarget` 不看 `targeting_mode` 且工具提示只输出 "Targets self"，但把「友方与自己」写在一个斩杀被动上，读起来是反的，建议改成 `non_allies` 并在注释里点明它只是声明。

### S-3（建议）．文档遗留不一致（PR #17 只改了一半）

| 位置 | 问题 |
|---|---|
| `design.md:78`（§3 架构图） | 仍写「+ **6 个**原版伤害标签」，而 §4 表格（`:93`）与 §6.2（`:361`）已是 **8 个** |
| `design.md:209` | 「那两个技能故意带了实体做归因，**斩杀则故意不带**」——与本文件 `:185-187` 的 ⚠️ 说明和第 105-109 行的 `super(type, killer)` **自相矛盾**（第一版才是不带） |
| `design.md:91` | 需求原文「成长值 150～**300**」，实现为 150…**290**（`execute.json:132-136`）——同格已注明推导，但需求行本身应改 |
| `design.md:6.1` / §3 架构图 | 把 `ExecuteMarkHandler` 写成 `handler/…`，实际在 `registry/…` |

### S-4（建议）．`KEY_WITH_SOURCE` 命名易误读

`ExecuteDamageSource.java:76` 的 `death.attack.beloong.execute.player` 虽叫 `.player`，但它**不是**原版 `.player` 变体（原版那个只在 `causingEntity == null && directEntity == null` 时用，见 `DamageSource.java:80`），是本模组自取的名字。

---

## 四、已逐条核实为**正确**的项（正面清单，避免以后重复挖）

以下都是本次拿去与上游源码对撞后**确认成立**的，包括行号：

| 声明 | 位置 | 核实结果 |
|---|---|---|
| `DamageSource(Holder, Entity)` 是 public 且委派 `this(type, entity, entity)` | `ExecuteDamageSource.java:108`、doc `:183` | ✅ `DamageSource.java:52-54`，5 个构造器全 public |
| `getLocalizedDeathMessage` public 非 final，覆写即可、不需 mixin | `ExecuteDamageSource.java:48-51` | ✅ `DamageSource.java:78` |
| NeoForge 的 `IDeathMessageProvider.DEFAULT` 对 `DeathMessageType.DEFAULT` 直接委派给该方法 | 同上 | ✅ `IDeathMessageProvider.java:38`；调用链 `CombatTracker.java:106` → `DeathMessageType.java:18-19` → 虚分派命中覆写 |
| `Player#killedEntity` 就是 `awardStat(Stats.ENTITY_KILLED…)` | `ExecuteDamageSource.java:36` | ✅ `Player.java:1704-1706` |
| 掉落表 `ATTACKING_ENTITY` 取自 `damageSource.getEntity()` | doc `:181` | ✅ `LivingEntity.java:1518` |
| `dropExperience(damageSource.getEntity())` | doc `:181` | ✅ `LivingEntity.java:1471` |
| `getKillCredit()` 先 `lastHurtByPlayer`、再 `lastHurtByMob` | doc `:199` | ✅ `LivingEntity.java:1814-1820` |
| `LivingEntity.addEffect(instance, entity)` 第 2 参即 `effectSource` | `design.md:123` | ✅ `LivingEntity.java:971`（`:977` post `MobEffectEvent.Added(…, entity)`） |
| DS 的 `EffectHandler` 把 `effectSource` 写进 effect 实例 | `design.md:119-121` | ✅ `EffectHandler.java:21-22`（`:22` 逐字命中） |
| 「斩杀者」复用 `AdditionalEffectData` 可行 | `ExecuteThresholdEffect.java:111` | ✅ jar `javap`：接口有 `dragonSurvival$getApplier(ServerLevel)`，`MobEffectInstanceMixin implements AdditionalEffectData` |
| DS 被动只要声明 `cooldown > 0` 就每次触发即进满冷却（故自带冷却记账是必需的） | `ModAttachments.java:20-23`、`ExecuteCooldown.java:9-14`、doc `:133-136` | ✅ `DragonAbilityInstance.java:213-215` **逐字命中** |
| `isUsable()` 要求 `level > 0`；`MIN_LEVEL = 0`、`MIN_LEVEL_FOR_CALCULATIONS = 1` | `ExecuteEffect.java:88` | ✅ `DragonAbilityInstance.java:42/44/351-353` |
| `OnTargetHit` 不把被击实体传给**行动** | `ExecuteMarkHandler.java:20-25`、doc `:107` | ✅ `OnTargetHit.java:26-43`（注：它确实用被击实体构造 loot context `:34`，所以对**触发器条件**不成立） |
| `tickEffects` 用**空 catch** 吞掉 CME，故在 `applyEffectTick` 里 `removeEffect` 是静默的 | `ExecuteThresholdEffect.java:163-167` | ✅ `LivingEntity.java:800-817`，`catch` 块**完全为空、无 LOGGER**；`:816-817` 行号逐字命中 |
| `MobEffect` 覆写点名称/签名（1.20.5+ 新名） | `ExecuteThresholdEffect.java:88,93` | ✅ `MobEffect.java:74,82` |
| `%%` 是语言值里唯一合法的字面百分号写法 | `design.md:304-331` | ✅ `TranslatableContents.java:145-146` |
| 伤害类型进 8 个原版标签才能做到「无视护甲/抗性/附魔/盾牌/无敌帧/狼铠 + 不击退」 | doc §5.4 | ✅ tag 文件齐备；`bypasses_shield` 判定在 `LivingEntity.java:1369`、`no_knockback` 在 `:1241`；**刻意不加** `bypasses_invulnerability` 已在 doc `:160` 说明为取舍 |
| 吸收无法用标签关闭 | `design.md:162` | ✅ `DamageTypeTags` 确无 `bypasses_absorption` |
| `amplifier = L + 4`、`斩杀线 = (amplifier+1)×0.5%`、成长 150→290 与 `lookup` 冷却 200→100 逐级自洽 | `execute.json`、`ExecuteThresholdEffect:77-84` | ✅ 15 级逐项复算全部吻合 |

**另：** `execute.json` 的冷却 `lookup`（200→100，14 步）与 `linear base 200 / per_level −7.142857` 的四舍五入逐级一致，无越界；图标 16 条 `from_level 0..15` 覆盖满级 15，两张贴图存在；中英键集合一致。

---

## 五、文档引用漂移清单（留档；**本次不修**）

| # | 位置 | 现值 | 真实值 |
|---|---|---|---|
| 1 | `docs/plans/2026-10-09-execute-passive-design.md:290` | `Player.java:126`（MAX_HEALTH） | **`Player.java:121`** |
| 2 | 同上 `:151-155` 表「盾牌格挡」行 | `LivingEntity.java:1164` | **`:1369`**（`isDamageSourceBlocked`） |
| 3 | 同上 `:158` 表「击退」行 | `LivingEntity.java:1230+` | **`:1241`** |
| 4 | 同上 `:4` | `D:\wdsjlzscsj\源代码\DragonSurvival-1.21.1` | **路径不存在** |
| 5 | `docs/自制龙之生存技能总设计.md:665` | 依赖 jar `8726322`（2.0.67） | `build.gradle:147` 是 `8973485`（**2.0.71**） |
| 6 | 同上 `:206` | 引 `memory/decisions-log.md:1225,1233` | 裁定实际在 **`:1946,1954`** |
| 7 | 同上 `:470` | 「`isEntityRelevant` **只被** AreaTarget/DiscTarget/DragonBreathTarget/LookingAtTarget 调用」 | 本模组自身现在也在 `AirStrikeEffect.java:119`、`ExecuteMarkHandler.java:107` 调用 |
| 8 | 同上 `:11.2` | 「lang 各 **210** 键」 | 现为 **321** |
| 9 | 同上 `:543/:476/:563/:655` | 敌我不分是有意设计 | 见 PR #18 的 C-1 |
| 10 | `build/review-src/neoforge-21.1.236/`（总设计文档 §八 引用） | 目录曾不存在 | 本次已解包恢复（`build/` 是构建产物，会被清理，宜在文档里写"可从 `build/moddev/artifacts/neoforge-21.1.236-sources.jar` 重新解包"） |

---

## 六、未验证 / 局限（不得当作已证）

1. **DS 2.0.71 无源码**，本次 DS 侧全部行号只在 **2.0.69** 上成立；相关**类与签名**在 2.0.71 jar 上已 `javap` 确认存在，但 2.0.71 的**实现细节**是否漂移未核验。
2. **依赖下界 `[2.0.53,)`**（`neoforge.mods.toml:128-133`）无法验证：2.0.53–2.0.66 的 jar 不在本机，`TargetingMode` 是否在那些版本就存在未知（若不存在，会在运行期 `NoClassDefFoundError` 而非加载期明确报错）。且本模组对 `dragonsurvival` **无上界**（对比 `mowziesmobs` 在 `:202-207` 刻意收紧上界）。
3. **未做实机验收**：斩杀的数据包解析门禁与游戏内行为、PR #18 的实际命中集合，均未在运行中的客户端/服务端确认（`design.md:390-400` 也自述未做）。本次结论全部来自源码/字节码逐分支推演。
4. **生产服是否使用上述两份 `=1` 配置**未确认——只核到工作区内的配置文件，未接触运行实例。
5. 本次**只审这三个 diff**；`AirStrikeEffect` 的伤害公式、`isPickable`、收翅判定等沿用部分未做正确性复核。

---

## 七、清单（2026-10-10 已裁定：**全部无需修复**，仅留档）

> 用户裁定：三个 PR 全部合规，无需修改。C-1 / C-2 为**设计变更**，已入档设计文档 §十五；其余各项不修。
> 下表「建议」列保留原审查意见，**不代表待办**。

| 编号 | 事项 | 原审查建议 | 裁定 |
|---|---|---|---|
| C-1 | PR #18 与 2026-08-14 裁定冲突，5 处文档失效 | 二选一：回退 / 确认变更并同步 `总设计:206,476,543,563,655` + `decisions-log` | **设计变更** ⇒ 已同步入档（§十五 + decisions-log） |
| C-2 | 整合包 `player_targeting_handling=1` 下 `air_strike` 打不到任何玩家 | 必改（去玩家 `NON_ALLIES` 路径，或明确接受并写进文档） | **设计内可接受** ⇒ 已写入 §15.3 |
| I-1 | `PLAYER_KILLED_ENTITY` 归因说明错误（3 处） | 改注释/文档 |
| I-2 | `setLastHurtByPlayer` 早退不清除，留超长信用 | 早退分支清除或延后记账 |
| I-3 | 普通怪死亡消息原版不显示，阶段词不可观测 | 决定：自行广播 / 降级为「玩家与宠物可见」 |
| I-4 | 设计文档把 `%d` 失败模式写成抛异常 | 改为「静默回退成原文」 |
| I-5 | 与 `TornadoEntity`/`ExecuteMarkHandler` 口径分叉（马/羊驼、`is_allied:false` 召唤物） | 统一复用同一实现 |
| S-1 | `getRootVehicle` 子句近乎死代码 + 注释不准 | 删或改写注释 |
| S-2 | 硬编码 `isHarmful = true` | 加注释或改读 `entityTarget.isHarmful()` |
| S-3 | `execute.json` 的 `targeting_mode: allies_and_self` 语义反 | 改 `non_allies` |
| S-4 | `execute.json:47` 仍写 `all_except_self` 与实现失配 | 随 C-1 一起定 |
| S-5 | 3 处死代码 | 删 |
| S-6 | 文档遗留不一致（6 个标签 / 故意不带实体 / 150～300 / `handler/` 包名 / 6 条引用漂移） | 一次性刷 |
| S-7 | `air_strike` 零回归防护 | 补一个断言新口径的 `tools/validate_*.py` | 不修 |

**表中未单独标注裁定的各行（I-1 ~ S-7），裁定一律为「不修」。**
