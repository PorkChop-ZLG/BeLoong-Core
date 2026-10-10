# 斩杀（`beloong:execute`）被动技能设计

> **文档性质：** 本模组第 4 个自制龙之生存（下称 DS）技能「斩杀」的权威设计说明。
> **事实口径：** 代码事实以当前工作区 `文件:行号` 为准；DS 侧事实基于 DS **2.0.71**（`build.gradle` 固定的 `dragonsurvival-420799:8973485`，其 `neoforge.mods.toml` 自报 `version = "2.0.71"`，与 `D:\wdsjlzscsj\源代码\DragonSurvival-1.21.1` 同版本）。
> **更新日期：** 2026-10-09

---

## 一、一句话

龙玩家攻击**非友方生物**时给目标挂上「斩杀线」标记；一旦目标血量**低于斩杀线**，立即结算一次**数据驱动的真实伤害**（默认 999999），并进入 **4～10 秒**冷却。

技能随**成长值**从 150 升到 300，共 **16 级**（每 10 成长值一级，含两端点）；斩杀线随等级从 **5.0%** 线性升到 **20.0%**（每级 +1.0%），冷却从 **10.0 秒**线性降到 **4.0 秒**（每级 −8 刻）。

> 150 / 300 不是随手取的：整合包把 `dragon_stage` 覆盖为 newborn 10–50 / young 50–100 / adult 100–**150** / **ancient 150–300**，因此 **150 恰是「成年→远古」的分界，300 是远古阶段满成长**。本技能的语义于是是「**变成远古龙即解锁，远古阶段养满即满级**」——见 §2.4。

---

## 二、数值表（唯一权威来源是能力 JSON）

### 2.1 公式

| 量 | 公式 | 说明 |
|---|---|---|
| 斩杀线 `threshold(L)` | `5.0 + (L − 1) × 1.0`（%） | L = 1…16 ⇒ 5.0 … 20.0，每级 +1.0% |
| 标记 amplifier | `2L + 7` | 由「effect 每级 +0.5%、初始 0.5%」反解：`(amp+1)×0.5% = threshold(L)` |
| 冷却 `cooldown(L)` | `200 − (L − 1) × 8`（刻） | L1 = 200 刻（10.0 s），L16 = 80 刻（**4.0 s**），每级 −8 刻 |
| 升级门槛 `growth(L)` | `150 + (L − 1) × 10` | L1 = 150，L16 = 300 |
| 斩杀伤害 | 常数 999999 | 真实伤害 |

### 2.2 逐级表

| 等级 | 成长门槛 | 斩杀线 | effect amplifier | 冷却（刻） | 冷却（秒） |
|---|---|---|---|---|---|
| 1 | 150 | 5.0% | 9 | 200 | 10.00 |
| 2 | 160 | 6.0% | 11 | 192 | 9.60 |
| 3 | 170 | 7.0% | 13 | 184 | 9.20 |
| 4 | 180 | 8.0% | 15 | 176 | 8.80 |
| 5 | 190 | 9.0% | 17 | 168 | 8.40 |
| 6 | 200 | 10.0% | 19 | 160 | 8.00 |
| 7 | 210 | 11.0% | 21 | 152 | 7.60 |
| 8 | 220 | 12.0% | 23 | 144 | 7.20 |
| 9 | 230 | 13.0% | 25 | 136 | 6.80 |
| 10 | 240 | 14.0% | 27 | 128 | 6.40 |
| 11 | 250 | 15.0% | 29 | 120 | 6.00 |
| 12 | 260 | 16.0% | 31 | 112 | 5.60 |
| 13 | 270 | 17.0% | 33 | 104 | 5.20 |
| 14 | 280 | 18.0% | 35 | 96 | 4.80 |
| 15 | 290 | 19.0% | 37 | 88 | 4.40 |
| 16 | 300 | 20.0% | 39 | 80 | 4.00 |

综合强度（斩杀线 ÷ 冷却）从 L1 的 **0.5 %/秒** 升到 L16 的 **5.0 %/秒**，正好 **10 倍**。

### 2.3 两条硬约束（决定「能填什么」）

1. **斩杀线必须落在 0.5% 网格上。** `ExecuteThresholdEffect#amplifierForThreshold` 会把百分比
   四舍五入到 0.5% 一格（`round(百分比 / 0.5) − 1`），而技能提示打印的是**未取整的线性原值**
   （`ExecuteEffect#getDescription` 用 `threshold.calculate(level)`）。⇒ 只要 `base` 与
   `per_level_above_first` **都是 0.5 的整数倍**，16 级就全部落在网格上，**提示与实际永远一致**。
   本曲线的 `5.0 + 1.0×(L−1)` 满足该条件（amplifier 精确为 `2L + 7`）。
2. **冷却步长必须是整数刻。** `ExecuteCooldown#mark` 走 `(int)` **截断**，非整数刻会让提示比实际略长。
   取 `per_level_above_first = -8` ⇒ 16 级全部是整数刻，且都是 8 的整数倍（200, 192, …, 80）。

> **历史（已被本节取代）**：2026-10-09 初版是「15 级 / 3.0%→10.0% / 每级 +0.5% / 冷却 200→100」，
> 冷却用 `lookup` 逐级写死 15 个整数，用来绕开 `-100/14 = -7.142857` 这个非整数步长。
> **2026-10-10 改成 16 级后那个 `lookup` 只剩 15 个值**，第 16 级会静默落到 `fallback`
> （`Lookup.calculate`：`level <= values.size() ? values[level−1] : fallback.calculate(level)`）
> ⇒ 实际冷却 92 刻（4.60 s），跌破 5 s 下限**且不报任何错**。
> ⇒ 现在改为整数步长的纯 `linear`：既满足「不要 `-7.142857` 这种复杂小数」，
> 也让 `lookup` 数组与 fallback 一并消失。
> 初版关于「2.5% / 最大 10% / 15 级三者不能同时成立」的推导记录见 §九。

### 2.4 为什么端点是 150 与 300

整合包用 KubeJS 覆盖了全部 `dragon_stage`（`BeLoong-Server` 与 `【化龍】服务端` 两份一致）：

| 阶段 | 成长区间 |
|---|---|
| `newborn` | 10 – 50 |
| `young` | 50 – 100 |
| `adult` | 100 – **150** |
| **`ancient`** | **150 – 300** |

150 恰是**成年与远古的分界值**，300 是**远古阶段的满成长**。⇒ 解锁与满级落在
「成为远古龙」与「远古阶段养满」这两个玩家能直接感知的节点上，而不是区间内部的任意数字。
（DS 自带 `ancient_stage` 数据包把 ancient 定义为 60–300，整合包覆盖为 150–300；相邻阶段首尾相接，
满足 `DragonStage` 的连续性校验。）

---

## 三、四层架构

```
① 能力壳（DS 数据包）
   data/beloong/dragonsurvival/dragon_ability/execute.json
     activation: passive（ConstantTrigger） / actions: 1 个「说明载体」行动 / icon / upgrade: dragon_growth
        │  只负责：等级、成长门槛、图标、工具提示
        ▼
② 标记（Java，事件驱动）
   handler/ExecuteMarkHandler   ← LivingDamageEvent.Post
     攻击者是「持有斩杀技能且等级≥1 的龙玩家」 + 目标非友方  ⇒ 给目标 addEffect(斩杀线, amplifier = 2L+7, 时长 mark_duration)
        │
        ▼
③ 结算（Java，tick 驱动）
   registry/ExecuteThresholdEffect#applyEffectTick   ← 每 tick 检查
     health < maxHealth × (amplifier+1)×0.5%  ⇒ 取标记上的「斩杀者」⇒ 冷却已好 ⇒ 结算
        │
        ▼
④ 表现
   真实伤害（beloong:execute 伤害类型 + 8 个原版伤害标签）
   龙息粒子（缠在目标身上）+ `ability.beloong.execute` 音效（发在**施法者**身上）+ actionbar「斩杀触发：<生物名>」
```

**分层边界**（与三技能设计一致的取舍）：JSON 负责**全部数值**（伤害、斩杀线曲线、冷却曲线、标记时长），Java 负责**行为、归因、失败判定**。

---

## 四、逐条需求落实

| # | 需求 | 落实位置 | 说明 |
|---|---|---|---|
| 1 | 新 effect「斩杀线」，标记斩杀者与斩杀线 | `ModMobEffects.EXECUTE_THRESHOLD` + `ExecuteThresholdEffect` | **斩杀线**由 `amplifier` 编码（客户端可读，血条直接用）；**斩杀者**由 DS 自带的 `AdditionalEffectData` 机制记录（见 §5.2） |
| 2 | 被动技能「斩杀」，成长值 150～300，16 级，线性 | `execute.json` 的 `upgrade` | `dragon_growth`，`growth_requirement = linear(150, +10)` ⇒ L1 150 … L16 300（每 10 成长值一级，见 §2.4） |
| 3 | 攻击非友方生物时施加斩杀 effect | `ExecuteMarkHandler#onLivingDamagePost` | 玩家目标走原版 `canHarmPlayer`，非玩家目标走 DS 的 `TargetingMode.NON_ALLIES` + 宠物 / DS 召唤物豁免，见 §5.9 |
| 4 | 999999 真实伤害，无视护甲与抗性，数据驱动 | `execute.json` 的 `damage` + `data/beloong/damage_type/execute.json` + 8 个 `minecraft:tags/damage_type/*` | 伤害来源带击杀者实体，击杀正常计入玩家（统计 / 进度 / 掉落表 `ATTACKING_ENTITY`）——见 §5.5 |
| 5 | 触发后进入冷却，最长 10 s、最短 4 s | `execute.json` 的 `cooldown` + `ExecuteCooldown` | `linear(200, −8)` ⇒ L1 10.0 s → L16 **4.0 s**（整数步长，见 §2.3）；用 NeoForge 附件存「可再次触发的时间戳」，**不走 DS 的 activation 冷却**（理由见 §5.3） |
| 6 | 斩杀线 16 级、每级 +1.0% | `execute.json` 的 `threshold` | `linear(5.0, +1.0)` ⇒ L1 5.0% … L16 20.0%，16 级全落在 0.5% 网格上 |
| 7 | effect 每级 +0.5%，初始 0.5% | `ExecuteThresholdEffect#thresholdFraction` | `(amplifier + 1) × 0.005` |
| 8 | 死亡消息（有/无来源两种） | `ExecuteDamageSource` + 4 个语言键 | 覆写 `getLocalizedDeathMessage`，阶段名跟随击杀者真实成长阶段（新生/幼年/成年/远古），见 §5.5 |
| 9 | 联动血条模组显示斩杀线血量 | **不做**（2026-10-09 用户裁定撤销） | 曾按 AsteorBar 的 `EXTRA_RENDERERS` / `EXTRA_TEXT_RENDERERS` 实现过，代码已删除；保留的事实记录见 §5.7 |
| 10 | 龙息粒子 + 斩杀音效 + 「斩杀触发：生物名」 | `ExecuteThresholdEffect#execute` | `ParticleTypes.DRAGON_BREATH`（受害者）/ `ModSounds.EXECUTE` = `ability.beloong.execute`（**施法者**位置，`SoundSource.PLAYERS`）/ actionbar —— 见 §八 |

---

## 五、关键设计决策（最容易踩回去的地方）

### 5.1 为什么标记不用 DS 的 `on_target_hit` 行动

DS 的 `OnTargetHit.trigger`（`OnTargetHit.java:26-43`）在 `LivingDamageEvent.Post` 里只做一件事：找到带该触发器的被动能力并 `ability.tick(player)`。**被击中的实体不会传给行动**，行动只能按自己的 `target_selection` 重新找目标：

- `looking_at`：近战大致可用，但**远程（箭、法术）必定打偏**；
- `area` / `disc`：会波及周围所有生物，与「只标记被打的那只」语义不符。

⇒ 标记放在本模组自己的 `LivingDamageEvent.Post` 监听里，直接拿 `event.getEntity()`，精确且与伤害来源无关。

### 5.2 「斩杀者」为什么不用自己存

DS 给 `MobEffectInstance` 打了 `MobEffectInstanceMixin`（实现 `AdditionalEffectData`），并在 `EffectHandler.handleEffectApplication` 里把 `MobEffectEvent.Added` 的 `effectSource` 写进去：

```java
// DS: common/handlers/magic/EffectHandler.java:21-22
((AdditionalEffectData) event.getEffectInstance()).dragonSurvival$setApplier(event.getEffectSource());
```

而 `LivingEntity.addEffect(instance, entity)` 的第 2 个参数正是 `effectSource`（`LivingEntity.java:971-977`）。
⇒ 只要 `victim.addEffect(instance, player)`，斩杀者就自动挂在 effect 实例上，用 `((AdditionalEffectData) instance).dragonSurvival$getApplier(serverLevel)` 取回即可，**不需要再造一份附件**。

该 applier 只存在服务端（DS 只做了 NBT 存档，没做网络同步），这正好够用：客户端画血条只需要 `amplifier`。

### 5.3 为什么冷却不走 DS 的 `activation.cooldown`

`DragonAbilityInstance.tickActions` 末尾有一条硬编码：

```java
// DragonAbilityInstance.java:213-215
if (value().activation().type() == Activation.Type.SIMPLE || isPassive() && value().activation().getCooldown(level) > 0) {
    stopCasting(dragon, false);   // → release() → cooldown = getCooldown(level)
}
```

也就是说：**被动能力只要声明了 `cooldown > 0`，每次被触发都会立刻进入满冷却**。对斩杀而言这会变成「每次平砍都会触发 4～10 秒冷却」，与需求「触发斩杀后才进冷却」直接冲突（而且 `cooldown` 会反过来卡住标记本身）。

⇒ 能力 JSON **不声明** `activation.cooldown`；冷却值作为 `beloong:execute` 效果的自定义字段存在于同一个 JSON 里，由本模组用附件 `beloong:execute_ready_at`（`long`，存目标游戏刻）自行判定与同步。

> 代价：DS 技能界面里不会显示冷却读数（那是 `activation.cooldown > 0` 才有的行，`DragonAbility.java:116-120`）。改为在 `.dynamic_desc` 里写出来。

### 5.4 「真实伤害」到底要挂哪些标签

`LivingEntity.hurt` → `actuallyHurt` 的减免链路与对应标签（1.21.1 / NeoForge 21.1.236 源码）：

| 减免 | 代码位置 | 关闭它的标签 |
|---|---|---|
| 护甲 + 盔甲韧性 | `LivingEntity.java:1725-1733` | `minecraft:bypasses_armor` |
| 抗性提升 | `LivingEntity.java:1743-1758` | `minecraft:bypasses_resistance` |
| 保护类附魔 | `LivingEntity.java:1762-1775` | `minecraft:bypasses_enchantments` |
| （上面两者的总开关） | `LivingEntity.java:1740-1741` | `minecraft:bypasses_effects` |
| 盾牌格挡 | `LivingEntity.java:1164` | `minecraft:bypasses_shield` |
| 受伤无敌帧（10 刻内只吃差额） | `LivingEntity.java:1190-1199` | `minecraft:bypasses_cooldown` |
| 狼铠 | 1.21.1 | `minecraft:bypasses_wolf_armor` |
| 击退 | `LivingEntity.java:1230+` | `minecraft:no_knockback` |

⇒ 伤害类型 `beloong:execute` 同时进这 8 个原版标签。**刻意不加** `minecraft:bypasses_invulnerability`：那是 `/kill` 级别的口子，会把创造模式玩家、无敌实体一起打穿。

> 1.21.1 的**吸收（absorption）无法用标签关闭**：NeoForge 把吸收挪到了 `actuallyHurt` 里无条件结算（`LivingEntity.java:1790-1792`），`DamageTypeTags` 里也没有 `bypasses_absorption`（`DamageTypeTags.java:8-40`）。已验证并接受。

### 5.5 死亡消息与击杀归因：`getEntity()` 必须挂玩家，文案自己覆写

**第一层：击杀必须算在玩家头上。** `LivingEntity#die` 里所有「算不算玩家击杀」的分支读的都是 `damageSource.getEntity()`，**不是** `getKillCredit()`：

```java
Entity entity = damageSource.getEntity();                       // ← 归因看这里
LivingEntity killer = this.getKillCredit();
if (this.deathScore >= 0 && killer != null) {
    killer.awardKillScore(this, this.deathScore, damageSource);  // 记分板击杀数：只看 killCredit
}
...
if (entity == null || entity.killedEntity(serverlevel, this)) {  // ← entity 为 null 直接短路
    this.gameEvent(GameEvent.ENTITY_DIE);
    this.dropAllDeathLoot(serverlevel, damageSource);            //   掉落照掉，但击杀统计丢了
}
```

`Player#killedEntity` 干的正是 `awardStat(Stats.ENTITY_KILLED…)`；掉落表的 `LootContextParams.ATTACKING_ENTITY`、`PLAYER_KILLED_ENTITY` 进度判据、`dropExperience(damageSource.getEntity())` 也都依赖它。

⇒ **伤害来源必须带玩家**（`new ExecuteDamageSource(type, player)`，与原版 `playerAttack` / `mobAttack` 的构造方式一致），否则死亡消息里虽然有玩家的名字，击杀却不算他的。

> ⚠️ 第一版这里是**错的**：为了走原版 `.player` 变体而刻意用了无实体的 `new DamageSource(holder)`，
> 结果击杀统计 / 进度 / 掉落表的 `ATTACKING_ENTITY` 全部落空（`getKillCredit()` 只救回了记分板与死亡消息）。
> 2026-10-09 由用户指出后改为带实体。

**第二层：文案自己覆写。** 原版 `getLocalizedDeathMessage` 只给 `.player` 变体传两个参数（受害者、击杀者），**语言值里注入不了任何额外动态文本**。而「远古」在 DS 里是一个**具体的成长阶段**（`dragon_stage.dragonsurvival.ancient`，来自内置 `ancient_stage` 数据包；「远古龙碾压 / 连锁挖掘」也都是该阶段专属），一条新生龙用斩杀打死怪却报「被远古龙被动斩杀」是错的。

⇒ 用 `ExecuteDamageSource extends DamageSource` **覆写** `getLocalizedDeathMessage`（该方法 public 非 final，`DamageSource.java:78`；NeoForge 的 `IDeathMessageProvider.DEFAULT` 对 `DeathMessageType.DEFAULT` 正是直接委派给它），**不需要 mixin**。三种分支：

| 情况 | 语言键 | 参数 | 中文示例 |
|---|---|---|---|
| 击杀者是龙 | `death.attack.beloong.execute.player` | 3（受害者 / 击杀者 / 阶段词） | 僵尸被 Steve 的**成年龙**被动斩杀了 |
| 击杀者不是龙（如中途变回人形） | `death.attack.beloong.execute.unknown_source` | 2 | 僵尸被 Steve 的龙族被动斩杀了 |
| 拿不到击杀者 | `death.attack.beloong.execute` | 1 | 僵尸被龙族被动斩杀了 |

击杀者优先取 `getEntity()`（构造时挂上的玩家），取不到才退到 `LivingEntity#getKillCredit()`；`victim.setLastHurtByPlayer(player)` 同时喂给掉落表的 `LAST_DAMAGE_PLAYER` 与经验值计算，两条路互为兜底。

阶段词 = **本模组自己的词条**（`death.attack.beloong.execute.stage.<阶段路径>`，内置 newborn/young/adult/ancient 四条；中文「新生龙/幼年龙/成年龙/远古龙」，英文「Newborn/Young/Adult/Ancient dragon」）。
数据包自定义的阶段不在表里，回退到 **DS 自己的阶段语言键**（`Translation.Type.STAGE.wrap`），且**不再补字**。

> **为什么不用「DS 阶段名 + 一个『龙』字后缀」**（这是第一版的写法，2026-10-09 被用户打回）：
> DS 的 `dragon_stage.dragonsurvival.adult` 在原版是「成年」，但**汉化资源包/整合包常把它译成「成年龙」**，
> 再拼一个后缀就成了「成年龙**龙**」。自己出词条则译文与拼接规则都归本模组管，
> 任何语言、任何资源包下都不会重复。
>
> 这与三技能文档 §7.1「`.player` 变体从未被使用，已删除」并不矛盾：那两个技能**故意**带了实体做归因，斩杀则**故意**不带，因为需求要的就是「有来源 / 无来源」两种文案。

### 5.6 为什么能力壳带一个「什么都不做」的行动

`DragonAbility.getInfo`（`DragonAbility.java:102-144`）只从 `actions` 里取动态描述；`actions` 本身是可选的（`optionalFieldOf("actions", List.of())`，`:59`）。
若 `actions: []`，技能界面只剩 `.desc` 静态文案，**看不到当前等级的斩杀线 / 冷却 / 伤害**。

⇒ 放一个以 `dragonsurvival:self` 为目标的 `beloong:execute` 行动，它的 `apply` 是空操作（只为提供 `getDescription`），并用 `trigger_rate: 20` 把它压到每 20 刻一次，成本可忽略。

### 5.7 需求 9（AsteorBar 血条联动）—— 已实现过，2026-10-09 撤销

**当前状态：不做。** 相关代码（`compat/asteorbar/AsteorBarCompat`、`AsteorBarExecuteOverlay`）已删除；
`ExecuteThresholdEffect` 上那个只服务于它的 `getMark(LivingEntity)` 访问器也一并删掉了。

下面把**已经核实过的事实**留在文档里，理由是它们跟本技能无关、换任何功能都用得上，避免以后重新挖一遍：

AsteorBar 1.5.3 在 `com.afoxxvi.asteorbar.entity.EntityRenderer` 上暴露了两个**公开静态列表**（无需 mixin 即可登记）：

```java
public static final List<EntityRenderer.ExtraRenderer> EXTRA_RENDERERS;
public static final List<EntityRenderer.ExtraTextRenderer> EXTRA_TEXT_RENDERERS;
```

- `render(...)` 画完血条后调用 `extraRender(entity, poseStack, bufferSource, halfWidth, halfHeight, boundWidth)`；
  再 push 文本位姿、画完血量文字后调用 `extraTextRender(entity, poseStack, bufferSource, halfWidth, halfHeight, boundWidth, textScale)`。
- 前者的坐标系：**血条左端 = `-halfWidth`，右端 = `+halfWidth`，上下 = `∓halfHeight`**（由调用点
  `renderSolidGradient(consumer, pose, -halfWidth, -halfHeight, -halfWidth + width, halfHeight, color, z)` 反推）；
  `GuiHelper.renderSolid` 的最后一个 float 参数是 **z 偏移**，不是 alpha。
- 后者在 `scale(textScale)` **之后**的文本坐标系里，所以按血条坐标定位要除以 `textScale`
  ——AsteorBar 自己画吸收值文字时就是这么算的（`(-halfWidth + 1) / textScale`）。
- AsteorBar 是 **compileOnly** 依赖：`isLoaded("asteorbar")` 判定与真正引用其类型的类必须**拆成两个类**，
  否则缺席时会在类加载期 `NoClassDefFoundError`（与 `DisasterBiomeSubstitution` 对 BWG 的处理同构）。

> 另一个与本技能无关的注意点：`EntityRenderer.render` 的实体血条本身有显示条件
> （满血、超出最大距离、无视线、隐身、旁观者等都不画），且它默认叫「生物信息条」、有独立开关。

### 5.8 斩杀「结算时机」的取舍

检查放在**受害者身上的 effect tick**里（`MobEffect.applyEffectTick`），而不是攻击者的伤害事件里。原因：

- 需求写的是「当敌人**血量低于**斩杀线时触发」，是一个**状态**而非某个瞬时事件；
- 掉血可能来自别的玩家、火焰、摔落、DoT —— 只要标记还在，就该结算；
- 攻击者离线 / 死亡 / 掉了技能时，标记自然失效（取不到 applier 或等级为 0 时直接返回），不需要清理逻辑。

副作用（刻意保留）：如果斩杀者正在冷却，目标会「卡在斩杀线以下」直到冷却结束，然后立刻被斩杀。这正是斩杀线标记应有的威慑力。

### 5.9 玩家之间为什么不走 `NON_ALLIES`（2026-10-09 修复）

**症状**：两个玩家互相打，斩杀线挂不上。

**根因**：`TargetingMode#isEntityRelevant`（`TargetingMode.java:66-96`）把 `isFriendly` 判在 `isEnemy` / `isHarmful` **之前**，而且命中友方分支后直接返回：

```java
if (isFriendly(player, target)) {
    return this == ALLIES_AND_SELF || this == ALLIES || this == NON_ENEMIES || this == ALL_EXCEPT_SELF;
}
if (isEnemy(player, target)) {
    return this == ENEMIES || this == NON_ALLIES || this == ALL_EXCEPT_SELF;
}
if (isHarmful && target instanceof Player otherPlayer) {     // ← 同一个队伍时永远到不了这里
    return canAttackPlayer(player, otherPlayer);
}
```

`isFriendly` 只判 `target.isAlliedTo(player)`（记分板队伍）⇒ **同队的两个玩家一律被当成友方**，
哪怕队伍开着友伤、伤害确实打出去了，`NON_ALLIES` 也直接返回 `false`。

**修法**：玩家目标改问原版 `Player#canHarmPlayer`（`Player.java:970-978`）：

```java
public boolean canHarmPlayer(Player other) {
    Team team = this.getTeam();
    Team team1 = other.getTeam();
    if (team == null) return true;                              // 无队伍 ⇒ 可以打
    return !team.isAlliedTo(team1) ? true : team.isAllowFriendlyFire();
}
```

这正是 `ServerPlayer#hurt`（`ServerPlayer.java:796-800`）判定「这一下打不打得到」用的同一个谓词
⇒ 用它与「伤害能落地」等价，且天然尊重队伍友伤开关。非玩家目标仍走 `NON_ALLIES` + 宠物 / DS 召唤物豁免。

**顺带一个不是 bug 的坑**：`Player.MAX_HEALTH = 20`（1.21.1 `Player.java:121`），所以斩杀线的**绝对值**比百分比看上去小得多：

| 等级 | 斩杀线 | 20 血目标需要低于 |
|---|---|---|
| L1 | 5.0% | 1.0 血 |
| L5 | 9.0% | 1.8 血 |
| L10 | 14.0% | 2.8 血 |
| L16 | 20.0% | 4.0 血 |

2026-10-09 的初版曲线（3.0%→10.0%）在低等级只有 0.6 血的窗口，几乎打不动玩家；
**2026-10-10 把起点抬到 5.0% 后，L1 的窗口直接翻倍到 1.0 血**，低等级可用性明显改善。
若仍嫌低等级窗口太窄，可再加一条**绝对血量下限**（数据驱动字段，默认 0 = 关闭）——本文档未实现。

### 5.10 语言值里的字面百分号必须写成 `%%`（本次踩坑）

斩杀的效果描述 `effect.beloong.execute_threshold.description` 里要写「0.5%」，而**原版的语言占位符解析器不接受裸 `%`**。

> **2026-10-10 注**：`dragon_ability.beloong.execute.dynamic_desc` 原本也写了
> 「每级斩杀线 = 效果等级 × 0.5% + 0.5%」，该行已从**中英两条**语言文件里删除，
> 因此现在**只有效果描述**这一个键还携带字面百分号。下面的规则对任何含字面 `%` 的文案都成立，
> 与具体是哪个键无关（`tools/validate_execute.py` 的 `[8]` 段也仍对**每个键**做全量校验）。

`TranslatableContents#decomposeTemplate`（1.21.1，`:124-170`）的行为：

```java
private static final Pattern FORMAT_PATTERN = Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%]|$)");   // :61
...
if (k > j) {
    String s = formatTemplate.substring(j, k);
    if (s.indexOf(37) != -1) {      // 37 == '%' —— 两处匹配之间的字面文本里出现 '%' 就直接抛
        throw new IllegalArgumentException();
    }
    consumer.accept(FormattedText.of(s));
}
...
if (!"s".equals(s4)) {              // 只认 "%s" 与 "%%"，"%d" 之类一律
    throw new TranslatableFormatException(this, "Unsupported format: '" + s1 + "'");
}
```

⇒ 语言值里**只有两种合法写法**：`%s`（占位）与 `%%`（字面百分号）。裸 `%`（哪怕后面跟的是 `§`、空格、中文标点）与 `%d` 都会在**渲染工具提示的那一刻**抛 `TranslatableFormatException`。

> 注意区分**语言值**与**Java 参数**：
> `String.format("%.1f%%", percent)` 里的 `%%` 是 `String.format` 的转义（产出 `"5.0%"`），
> 这个字符串是作为**参数**传进去的，`getArgument` 只做 `Objects.toString` 转换、**不会再解析格式**，
> 所以参数里的 `%` 是安全的。**要转义的是语言文件那一侧。**

**顺手全仓扫了一遍同类问题**，除本次两条（中英各 2 个值）外还挖出一个**既有 bug**：
`tooltip.beloong.treasure_limit` 写的是 `%d`，而 `TreasureTooltipHandler`（`:50-53`）传的是 `Component` 参数 —— 也就是说只要悬停任何「有数量上限的财宝方块」，物品提示就会抛 `Unsupported format: '%d'`。已一并改为 `%s`。


---

## 六、文件与资产清单

### 6.1 Java

| 路径 | 作用 |
|---|---|
| `ability/ExecuteEffect.java` | `beloong:execute` 能力效果：数值载体 + 动态描述（`apply` 空操作） |
| `ability/ExecuteAbility.java` | 能力键、等级查询、从能力定义里取出 `ExecuteEffect` 配置 |
| `handler/ExecuteMarkHandler.java` | `LivingDamageEvent.Post`：攻击非友方生物 ⇒ 挂斩杀线 |
| `registry/ExecuteThresholdEffect.java` | 「斩杀线」效果的 tick 结算：判定、真实伤害、粒子、音效、提示、冷却 |
| `registry/ExecuteDamageSource.java` | 斩杀的伤害来源：覆写死亡消息，按击杀者真实成长阶段渲染 |
| `registry/ExecuteCooldown.java` | 冷却读写（NeoForge 附件 `beloong:execute_ready_at`） |
| `registry/ModAttachments.java` | 附件类型注册 |

> 原 `compat/asteorbar/{AsteorBarCompat,AsteorBarExecuteOverlay}.java` 已随需求 9 撤销删除，见 §5.7。

### 6.2 数据与资源

| 路径 | 说明 |
|---|---|
| `data/beloong/dragonsurvival/dragon_ability/execute.json` | 能力定义（等级 / 图标 / 全部数值） |
| `data/beloong/damage_type/execute.json` | 伤害类型（`message_id: beloong.execute`） |
| `data/minecraft/tags/damage_type/{bypasses_armor,bypasses_effects,bypasses_resistance,bypasses_enchantments,bypasses_shield,bypasses_cooldown,bypasses_wolf_armor,no_knockback}.json` | 追加 `beloong:execute` |
| `assets/beloong/textures/gui/sprites/abilities/execute_0.png` / `execute_1.png` | 技能图标（**64×64**，经用户显式授权的规格例外，见 §八 与总设计 §7.3；0 级为纯去饱和灰度） |
| `assets/beloong/sounds/ability/execute.ogg` | 斩杀触发音效（用户提供的 `Smolder_Heavenscale_SFX_Q3Burn_execute.ogg` 改名入库；立体声 2ch/44.1 kHz —— 见 §8.2） |
| `assets/beloong/lang/{zh_cn,en_us}.json` | 名字 / 描述 / 动态描述 / 死亡消息 / 触发提示 / effect 名 |

### 6.3 工具

| 路径 | 说明 |
|---|---|
| `tools/make_execute_icons.py` | **只从 `execute_1.png` 派生 0 级灰度图**（`execute_1.png` 是交付美术，脚本只读不写、缺失即 exit 1；尺寸随源图、不硬编码 32；自带灰度自检；幂等）。见 §8.3 |
| `tools/validate_execute.py` | 本技能的静态验收脚本：JSON 语法、数值端点、amplifier 映射、图标存在性、伤害标签、Java 注册点、中英语言键一致性与占位符安全 |

---

## 七、验收要点

1. `.\gradlew.bat build` 通过。
2. 数据包解析门禁：跑一次客户端后 `run\logs\latest.log` 里 `Failed to parse` 与 `Registry loading errors` 零命中。
3. 引用一致性：`execute.json` 的 `effect_type` = `beloong:execute`（与 `AbilityEffectRegistry` 注册键逐字一致）；`icon.texture_resource` 指向的文件存在。
4. 语言键中英集合一致。
5. 实测：
   - 打非友方生物（近战 / 远程均可）后目标挂上斩杀线；
   - 打友方（同队 / 自己的宠物 / DS 召唤物）不挂；
   - 目标血量跌破斩杀线时立刻结算 999999 真实伤害，护甲/抗性/保护附魔都不减免；
   - 触发后 4～10 秒内不再触发（可连续打多只怪验证）；
   - 死亡消息：新生/幼年/成年/远古龙分别打死怪，文案里的阶段词要对得上；龙不在龙形态时走「龙族被动」分支；
   - 龙息粒子（缠在目标身上）+ 斩杀音效（**在施法者身上**播放 `ability.beloong.execute`，距离衰减生效）+ actionbar 提示；字幕开启时显示「斩杀：触发」。

> 需求 9（血条联动）已撤销，不在验收范围内。

### 7.1 当前的验证状态（2026-10-10 更新）

| 项目 | 状态 |
|---|---|
| `.\gradlew.bat build --rerun-tasks --no-build-cache` | **已通过**（exit 0；jar 内含全部 class / 数据 / 贴图 / 音效） |
| `tools/validate_execute.py` 全部检查 | **已通过**（含 2026-10-10 新增的 16 级端点、0.5% 网格、整数冷却三条断言） |
| 数据包实机解析（`run/logs/latest.log` 零错误） | **未做**——见下 |
| 游戏内行为实测（含 §九 的 16 级数值） | **未做**——见下 |

**为什么没跑实机验收**（2026-10-09 的记录，保留为历史）：当时的文件沙箱只允许子进程写入它自己新建的目录，`run/` 与 `build/` 下的既有目录对 `gradlew runClient` 启动的游戏进程不可写，因此无法在本环境里拉起客户端/服务端读数。
⇒ 第 2 条与第 5 条必须在能正常运行游戏的机器上补做。

> ⚠️ **数值改动只过了静态门**：§2 的 16 级曲线与 §九 的端点全部来自 JSON 解析 + 静态断言推演，
> **没有实机验证**。

**贴图生成的一个环境坑**（记录以免重复踩）：同一沙箱限制让 `tools/make_execute_icons.py` 无法把 PNG 直接写进 `src/main/resources/assets/...`。本次的做法是先用脚本把图标生成到可写目录，再借 Gradle 守护进程（脱离沙箱）复制进资源树。**在正常机器上直接跑脚本即可**，不需要这一步。

> ⚠️ **2026-10-10 起该脚本的职责已收窄**：它不再生成彩色图标（彩色图标现在是交付美术 `execute_1.png`），只从它派生 0 级灰度图。上面这段沙箱坑记录保留为历史。见 §八。

---

## 八、2026-10-10（一）调整：图标替换 + 专属触发音效

> **性质：纯表现层调整。** 用户两项需求，均已实施并通过构建/静态门。
> **不改动**任何数值、目标过滤、结算逻辑、冷却或击杀归因。本节是这两项变更的权威记录。

### 8.1 图标

| | 变更前 | 变更后 |
|---|---|---|
| 尺寸 | 32×32 RGBA（由脚本手绘的占位示意图） | **64×64**（用户提供的满幅正式美术，**不缩放**） |
| 0 级（未解锁） | 脚本手绘的灰阶示意图 | 1 级原图**纯去饱和**得到 |
| 1 级及以上 | 脚本手绘的彩色示意图 | 原图 |
| `execute.json` 的 `icon.texture_entries` | 16 条（`from_level` 0…15，1 级以上全部指向同一张） | **2 条**（`0 → execute_0`、`1 → execute_1`） |

- **为什么 2 条就够**：`LevelBasedResource.get(level)` 把 entries 按 `from_level` **降序**排序后，
  取第一个满足 `level >= fromLevel` 的项（`LevelBasedResource.java:17-33`）⇒ 2 级及以上自然命中
  `from_level: 1`，**无需逐级写条目**。
- **64×64 是显式授权的规格例外**：图标规范仍是 32×32（总设计 §7.3），本例经用户 2026-10-10 授权偏离，
  并已在 §7.3 登记。**未登记的偏离按违规处理。**
- **灰度口径是量出来的，不是拍脑袋**：既有 4 张 0 级图标（tornado / air_strike / tp_loong_palace /
  旧 execute）的饱和度**全部恰为 0.000**，亮度比 L0/L1 = 1.00 / 0.84 / 1.31 ⇒ **没有统一的调暗**。
  因此 0 级图 = 只去饱和、**不压暗**（`convert("L")`，即 ITU-R 601-2 luma）。
- **原图无 alpha 通道**（colortype=2 RGB，满幅不透明方图），与其余 11 张带透明通道的图标不同；
  这是原图本身的形态，未做抠图。落库时统一转 RGBA（alpha 全 255），与其他图标格式一致，视觉无差异。

### 8.2 触发音效

| | 变更前 | 变更后 |
|---|---|---|
| 音效 | `SoundEvents.ENDER_DRAGON_GROWL`（原版末影龙低吼） | `ModSounds.EXECUTE` = `ability.beloong.execute`（用户提供的 `Smolder_Heavenscale_SFX_Q3Burn_execute.ogg`，改名入库） |
| 文件 | — | `assets/beloong/sounds/ability/execute.ogg` |
| **发声点** | 受害者（`victim.getX/Y/Z`） | **施法者（`player.getX/Y/Z`）** |
| 音量类别 | `SoundSource.HOSTILE` | **`SoundSource.PLAYERS`**（发声点已在玩家身上，归「玩家」滑条） |
| 播放范围 | 广播给附近所有玩家（`playSound(null, ...)`，含施法者本人） | **不变** |
| **音量** | `1.0F` | **`0.5F`**（2026-10-10 实机反馈偏响，降半；见下方第 1 条） |
| 粒子 | 龙息粒子缠在受害者身上 | **不变**（本次只有音效换位置） |

- **音量是唯一的旋钮**：`playSound(..., 音量, 音高)` 的第 7 个参数。2026-10-10 实机试听后
  由 `1.0F` 降到 **`0.5F`**。它只改变响度，**不改变可听距离**——可听距离由 `sounds.json` 的
  `attenuation_distance` 决定，本音效未设置 ⇒ 走原版默认衰减。想再调就只改这一个数字。
- **立体声**：该 ogg 是 **2 声道 / 44.1 kHz**，而原版定位音惯例是单声道。已核实 1.21.1 `SoundEngine`
  播放时只依据 `Attenuation` 决定 `linearAttenuation` / `disableAttenuation`、**不按声道数分支**
  （`SoundEngine.java:483-495`）⇒ **距离衰减照常生效**，仅声像定位不如单声道精确。
  本机无 ffmpeg / oggenc / sox 且无网络，无法就地转码 ⇒ **按已知项接受**（用户 2026-10-10 裁定）。
- 字幕键：`subtitles.beloong.ability.execute`（zh「斩杀：触发」/ en "Execute triggers"），中英各一条。

### 8.3 工具

`tools/make_execute_icons.py` 的职责已收窄为「**从 `execute_1.png` 派生 `execute_0.png`**」：

- **不再生成 `execute_1.png`**——那是交付美术，脚本**只读不写**；源图缺失时明确报错并以 exit 1 退出。
- **尺寸随源图**（脚本不再硬编码 32），因此下次换成别的尺寸也不需要改脚本。
- **自带灰度自检**：输出的平均饱和度 > 0.001 即判失败并 exit 1，保证「0 级必为灰度」这条约定不会静默失效。
- **幂等**：对同一源图重复运行产出逐字节相同的文件。
- 旧的 `--force` 防覆盖逻辑已随之删除（它保护的是「脚本自己生成的产物」，而彩色图现在不由脚本生成）。

### 8.4 增量验收要点

1. 技能栏里 0 级显示灰度、1 级及以上显示原图，无缺图、无「未找到贴图」。
2. 触发斩杀时：目标身上出现龙息粒子；**音效从施法者位置发出**、音量 `0.5F`、随距离衰减；音量由「玩家」滑条控制。
3. 字幕开启时显示「斩杀：触发」。
4. `python tools/validate_execute.py` 全绿；`.\gradlew.bat build` 通过。

---

## 九、2026-10-10（二）数值重标定：15 级 3.0%→10.0% ⇒ **16 级 5.0%→20.0%**

> **性质：纯数值改动**——只动 `execute.json` 的三个字段与验收脚本的断言，**不改任何 Java 行为**。
> 用户需求：在 **150–300 成长区间**内可用；斩杀效果**均匀线性提升**；每 10 成长值一级、
> 含两端点共 **16 级**；150 时斩杀线 5% / 冷却 10 s，300 时斩杀线 20% / 冷却**低于 5 s**，
> 且**不要 `-7.142857` 这类复杂小数**。

### 9.1 前后对照

| 字段 | 改前 | 改后 |
|---|---|---|
| `threshold` | `linear(3.0, +0.5)` ⇒ 3.0% … 10.0% | **`linear(5.0, +1.0)`** ⇒ 5.0% … 20.0% |
| `cooldown` | `lookup`（15 个手写整数 200…100，`fallback = linear(200, −7.142857)`） | **`linear(200, −8)`** ⇒ 200 … 80 刻（10.0 s → **4.0 s**） |
| `upgrade.maximum_level` | 15 | **16** |
| `upgrade.growth_requirement` | `linear(150, +10)` | **不变**（本来就是 150…300 的 16 级） |
| amplifier 映射 | `L + 4`（5…19） | **`2L + 7`**（9…39） |
| `damage` / `mark_duration` / 图标 / 目标过滤 / 击杀归因 | — | **一律不变** |

端点对齐依据见 §2.4：150 是 adult→ancient 的分界，300 是 ancient 的满成长。

### 9.2 为什么必须**同时**改冷却（本次真正的坑）

`cooldown` 改前是 **15 个值**的 `lookup`，而 `LevelBasedValue.Lookup.calculate` 是

```java
return level <= this.values.size() ? this.values.get(level - 1) : this.fallback.calculate(level);
```

`maximum_level` 一旦升到 16，**第 16 级就落到 `fallback`**：
`200 − 7.142857 × 15 = 92.857` ⇒ `(int)` 截断成 **92 刻（4.60 s）**，跌破 5 s 下限，
而且**全程不报任何错**。⇒ 这是典型的**静默漂移**。
改成整数步长的纯 `linear` 之后，`lookup` 数组与 fallback **一并消失**——根因被移除，而不是被掩盖。

### 9.3 新曲线的两条性质（已由 `tools/validate_execute.py` 断言）

1. **16 级全部落在 0.5% 网格上**（`5.0 + (L−1)×1.0` 恒为 0.5 的整数倍）⇒ `amplifierForThreshold`
   精确取整，而提示打印的是线性原值 ⇒ **16 级提示与实际值无一处偏差**。
2. **16 级冷却全部是整数刻**（且都是 8 的整数倍）⇒ `ExecuteCooldown` 的 `(int)` 截断不产生偏差。

### 9.4 被取代的初版推导（保留为历史）

初版需求是「起始 2.5%、最大 10%、每级 +0.5%」，这三条数学上不能同时成立
（`2.5 + 14×0.5 = 9.5 ≠ 10`）；2026-10-09 与用户确认后取了「保 15 级 + 每级 +0.5%，把起点抬到 3.0%」。
**2026-10-10 的 16 级方案取代了整条曲线**：起点 5.0%、每级 +1.0%、终点 20.0%；
由于端点被重新锚定到远古阶段的 150/300，「15 级」这条约束本身也不复存在。

### 9.5 增量验收要点

1. 远古龙（成长 150）拿到技能即 L1：斩杀线 5.0%、冷却 10.0 s。
2. 成长 300 为 L16：斩杀线 20.0%、冷却 **4.0 s**；成长 299 停在 L15（19.0% / 4.4 s）——端点精确。
3. 技能提示里的斩杀线与冷却读数**逐级与实际一致**（16 级无一处偏差）。
4. 升到 L16 后**不再显示**「下一级解锁成长值」那一行（`AbilityAndPenaltyTooltipRenderer.java:259`
   有 `ability.level() < upgrade.maxLevel()` 守卫）。

