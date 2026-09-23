# 让「袭击增强」的生物把龙之生存的猎人视为同伴 —— 设计文档

**日期：** 2026-09-23
**状态：** 已批准（brainstorming 五阶段完成）
**方案：** 方案 1 —— 把同伴判定提升到原版 `Raider` 层（`@Mixin(Entity.class)` 注入 `isAlliedTo`），并单独处理飞艇的硬编码目标白名单

---

## 问题陈述

整合包装了 **Raids:Enhanced**（`raidsenhanced`，作者 FINDERFEED）。把 `dragonsurvival:hunter_leader`
加进 `#minecraft:illager_friends` 后，**原版**掠夺者不再攻击它，但**袭击增强的生物仍然主动攻击**。

需求：让袭击增强的生物把龙之生存的**猎人首领与其余 `hunter_*` 生物**视为同伴（不再把它们选为攻击目标）。

---

## 背景：为什么"往标签里加一条"不够

`#minecraft:illager_friends` 这套阵营判定**全原版只有一处消费**：

```java
// AbstractIllager.java:34-41
@Override
public boolean isAlliedTo(Entity entity) {
    if (super.isAlliedTo(entity)) return true;
    else return !entity.getType().is(EntityTypeTags.ILLAGER_FRIENDS) ? false
              : this.getTeam() == null && entity.getTeam() == null;
}
```

而判定被选敌流程引用：

```java
// TargetingConditions.java:73
if (this.isCombat && (!attacker.canAttack(target) || !attacker.canAttackType(target.getType())
                      || attacker.isAlliedTo(target))) {
    return false;   // 不是合法目标
}
```

问题出在**继承链**：

| | 类层次 | 是否读 `#illager_friends` |
|---|---|---|
| 原版掠夺者 | `Pillager extends AbstractIllager extends Raider` | ✅ |
| 原版劫掠兽 | `Ravager extends Raider` | ❌（本方案的附带修复对象） |
| **袭击增强的 4 个袭击者** | **`FDRaider extends Raider`**（`FDRaider.java:14`） | ❌ |

`Raider` **没有**覆写 `isAlliedTo`（全量核对过），所以 `FDRaider` 及其子类继承的是 `Entity.isAlliedTo`
——**只看记分板队伍**（`Entity.java:2444-2452`）。于是 `TargetingConditions.java:73` 的阵营判定对它们恒为 false，
猎人照旧是合法目标。袭击增强**全模组没有任何一处覆写 `isAlliedTo`**（已 grep 确认）。

它们的目标选择与原版同构，所以命中猎人：

```java
// ZapperIllager.java:137 与 GolemOfLastResort.java:103 —— 完全相同
this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractVillager.class, true));
```

而 `LeaderEntity extends Villager extends AbstractVillager`（`LeaderEntity.java:46`）⇒ 类过滤命中。

**第二条独立路径**：飞艇根本不走 `TargetingConditions`，它有自己的硬编码白名单：

```java
// RaidBlimp.java:344-346
public boolean checkTargetClass(Entity target){
    return target instanceof AbstractVillager
        || (target instanceof Player player && !player.isSpectator() && !player.isCreative())
        || target instanceof IronGolem;
}
```

调用点只有两处，且**都不是** `Mob#setTarget`：投弹（`RaidBlimp.java:498`）与 6 门炮
（`RaidBlimpCannonsController.java:54`），都通过 `FDTargetFinder.getEntitiesInCylinder(...)`。
⇒ 即使把 `FDRaider` 变成 `AbstractIllager`，飞艇与炮塔仍然会打猎人。**这一处必须单独处理。**

> 附带事实：袭击增强**自己就用 `LivingChangeTargetEvent`** 阻止别人锁定钻头
> （`RaidDrill.java:462-467`），说明"事件取消目标"在本模组语境下是作者认可的手法；但本设计不采用它，
> 因为它只阻止"把目标写进去"，不构成"视为同伴"的语义（被打了仍会还手，且目标选择 goal 会空转）。

---

## 设计

### 架构

**同伴判定只定义一处**，与 `AbstractIllager.isAlliedTo` 逐字同构：

```
isHunterAlly(attacker, target) =
      attacker instanceof Raider          // 含 FDRaider 及其 4 个子类
   && attacker.getTeam() == null
   && target.getTeam()   == null
   && target.getType().is(EntityTypeTags.ILLAGER_FRIENDS)
```

**两个注入点**：

| | 目标 | 注入方式 | 效果 |
|---|---|---|---|
| **P1** | `Entity#isAlliedTo(Entity)`（原版） | `@Inject` at `HEAD`，`cancellable = true` | 任何 `Raider` 子类把标签生物视为同伴 ⇒ `TargetingConditions:73` 排除该目标；`HurtByTargetGoal:93` 也不再还手 |
| **P2** | `RaidBlimp#checkTargetClass(Entity)`（袭击增强） | `@Inject` at `HEAD`，`cancellable = true` → 返回 `false` | 投弹与 6 门炮的候选集里剔除标签生物 |

**为什么 P1 注入 `Entity` 而不是 `Raider`**：`Raider` **自己没有声明** `isAlliedTo`（继承自 `Entity`），
Mixin 无法向"未声明该方法的类"注入；而"新增一个覆写方法"在本项目**没有任何先例**。
注入 `Entity` 的 HEAD 并加 `instanceof Raider` 守卫与之等价，且**不复制原版逻辑**（只增加一个 `true` 分支）。

**作用域**：守卫把它限定在 `Raider` 及子类 ⇒ 影响面 = 全部袭击者
（原版 4 种 + 劫掠兽 + 女巫 + 任意模组袭击者，含袭击增强那 4 个）。

### 组件

| 文件 | 动作 | 说明 |
|---|---|---|
| `src/main/java/com/zonlong/beloong/mixin/minecraft/RaiderHunterAllyMixin.java` | **新增** | P1。`@Mixin(Entity.class)`；放在既有原版目标所在的分目录（现例 `minecraft.DreadKingRitualTriggerMixin`） |
| `src/main/java/com/zonlong/beloong/mixin/raidsenhanced/RaidBlimpTargetMixin.java` | **新增** | P2。`@Pseudo` + 可选依赖规范写法 |
| `src/main/resources/beloong.mixins.json` | 修改 | 两条都进 `mixins` 段（非 `client`：`isAlliedTo` 两端都会被调） |
| `src/main/templates/META-INF/neoforge.mods.toml` | 修改 | 补 `raidsenhanced` = `type="optional"` + `ordering="AFTER"` |
| `build.gradle` | 修改 | 加 `compileOnly`/`localRuntime "curse.maven:raids-enhanced-1467121:7676063"`（**无需新增 maven 仓库**，项目已有 cursemaven） |
| `src/main/resources/data/minecraft/tags/entity_type/illager_friends.json` | **新增** | **模组自带默认值**：写入 6 个 `dragonsurvival:hunter_*`（见「实施要点」）。`"replace": false` ⇒ 与整合包侧那份**合并为并集**，互不覆盖。目的：不依赖整合包即可生效，并让开发运行目录能直接实测 |

### 数据流

**路径 A：袭击者锁定猎人（P1 拦截）**

```
ZapperIllager / GolemOfLastResort.registerGoals()
  → NearestAttackableTargetGoal<AbstractVillager>.canUse() → findTarget()
    → Level.getNearestEntity(..., targetConditions, mob, ...)
      → TargetingConditions.test(attacker, target)          // :73
        → attacker.isAlliedTo(target)                       // ← P1 在此返回 true
          ⇒ 判定为"不是合法目标" → 猎人被排除
```

同一条链还覆盖 `HurtByTargetGoal.canUse():93` ⇒ 猎人打了袭击者，袭击者也不还手。

**路径 B：飞艇投弹 / 炮击（P2 拦截）**

```
RaidBlimp 投弹 (:494-508) ─┐
RaidBlimpCannonsController.tick() (:52-58) ─┤
  → FDTargetFinder.getEntitiesInCylinder(..., e -> checkTargetClass(e))
    → RaidBlimp.checkTargetClass(Entity)                    // :344 ← P2 在此返回 false
      ⇒ 候选集里没有猎人 ⇒ 不投弹、6 门炮不瞄准
```

**路径 C：不经过本设计的部分** —— 爆炸/落雷若本来就落在猎人附近，仍会溅到（见「非目标」）。

### 错误处理与边界

| 情况 | 行为 |
|---|---|
| **未装袭击增强** | P1 目标是原版 `Entity`，始终生效；P2 的 `@Pseudo` 让 Mixin 整体跳过，无副作用 |
| **袭击增强改名了 `checkTargetClass`** | 按项目规范用 `@Pseudo` + `require = 0` ⇒ **静默失效**。已在 javadoc 中显式记录（上一轮审查把"静默失效"列为本项目高发缺陷） |
| **双方有记分板队伍** | 判定照抄原版语义，要求双方都无队伍；有队伍时不生效（与原版灾厄村民一致） |
| **性能** | `isAlliedTo` 只多一次短路 `instanceof Raider`（非袭击者调用近乎零成本）+ 一次实体类型标签查询，且只发生在袭击者路径上 |
| **误伤面** | P1 只改变"袭击者"的看法；村民、铁傀儡、玩家对猎人的关系完全不变 |

---

## 决策记录

| # | 决策 | 理由 |
|---|---|---|
| **D1** | 同伴语义**只到"不把它当作攻击目标"**，不做范围伤害/投射物免疫 | 用户裁定。免疫需要拦截伤害事件，会让飞艇炸弹、落雷、自爆被"穿透"，影响袭击难度平衡 |
| **D2** | 判定**提升到原版 `Raider` 层**，而不是只修袭击增强的类 | 用户裁定。落在原版既有的 `#illager_friends` 标签上，可一次性覆盖**全部**袭击者（原版 4 种 + 劫掠兽 + 女巫 + 任意模组袭击者，含袭击增强那 4 个）；只在袭击增强的类上改则漏掉劫掠兽与其它模组 |
| **D3** | P1 注入 `Entity.isAlliedTo` HEAD，而非新增 `Raider` 覆写 | `Raider` 未声明该方法，Mixin 无法注入；新增覆写在本项目无先例；HEAD 注入不复制原版逻辑 |
| **D4** | P2 单独注入 `RaidBlimp.checkTargetClass` | 该路径是硬编码白名单，完全不经过 `TargetingConditions`/`isAlliedTo` |
| **D5** | 同伴名单落在原版标签 `#minecraft:illager_friends`；**模组自带一份默认标签**写入 6 个 `dragonsurvival:hunter_*`，整合包可继续在同一标签上追加 | 用户裁定（初始版本为"模组不新增标签"，后改为模组也自带，**便于测试**且让功能自包含）。两份文件都 `"replace": false` ⇒ 合并为并集。**副作用（可接受）**：原版灾厄村民也会把全部 6 个猎人当同伴 —— 但其中 5 个不是村民子类、本来就不会被原版选中，实际可观察变化只在猎人首领 |

> **`"replace": false` 不能省**：原版这个标签的内容是 `{"values": ["#minecraft:illager"]}`
> （客户端 jar 实测）。写成 `true` 会把 `#illager` 一起清空 ⇒ **灾厄村民之间互相不再算盟友**，
> 例如 "Johnny" 卫道士会转而攻击其他灾厄村民。
| **D6** | 依赖形态：**curse maven** + `compileOnly` + `localRuntime` | 与项目对 cataclysm / fdbosses / legendary_monsters 的既有做法一致；类型安全；`localRuntime` 让开发运行目录能装到该模组以便实测。**不用 Modrinth maven**：按版本号解析可能命中同版本号的 **Forge** 构建（jar 内是 `META-INF/mods.toml`），NeoForge 1.21.1 只认 `META-INF/neoforge.mods.toml` ⇒ 那个 jar 无法加载（**已实测踩到，导致游戏无法加载**）。curse 的文件 id 逐文件唯一，不会选错 loader |
| **D7** | 可选依赖规范：`@Pseudo` + `require = 0`，并在 `mods.toml` 补 `type="optional"` | 项目既有约定（样板 `AnnihilationPursuerDamageCapMixin:19-26`）；上一轮审查正是把"可选依赖没写进 mods.toml"记为缺陷 |
| **D8** | **不加配置开关** | 沿用本项目既有倾向；需求单一且无副作用面 |

---

## 非目标

- **不做范围伤害/投射物免疫**（D1 的边界）：爆炸、落雷、自爆若落在猎人附近仍会溅到，与原版灾厄村民对待彼此一致。
- **不改整合包**（`BeLoong-Server`）：标签由用户自行维护。
- **不碰龙之生存**：只读它的实体注册名，不修改它。
- **不处理僵尸/溺尸**：它们也把村民当目标（`Zombie.java:115`、`Drowned.java:83`），但同属"村民被敌对生物攻击"的既定行为，不在本需求范围。
- **不改 `RaidDrill`**：它没有任何村民目标（已核对）。

---

## 验证策略

**一级（静态，可靠）**

1. `.\gradlew.bat build` 通过
2. 两条 Mixin 出现在 jar 内、`beloong.mixins.json` 的 `mixins` 段各一条
3. 标签文件进 jar：`data/minecraft/tags/entity_type/illager_friends.json`，且 `"replace"` 为 `false`、6 个 ID 齐全

**二级（实机；需 `localRuntime` 把袭击增强装进开发运行目录 —— 目前 `run\mods` 里没有它）**

| # | 操作 | 期望 |
|---|---|---|
| 1 | 召唤 `raidsenhanced:zapper`（生成蛋 `raidsenhanced:zapper_spawn_egg`）靠近猎人首领 | **不锁定** |
| 2 | 同上 `raidsenhanced:golem_of_last_resort` | **不锁定** |
| 3 | `raidsenhanced:raid_blimp` 悬停在猎人与村民上方 | 投弹/炮击**不瞄猎人** |
| 4 | **负向对照**：把上面 3 个的目标换成普通村民 / 铁傀儡 | **照常锁定并攻击** —— 证明不是"整体 AI 失效" |
| 5 | **负向对照**：把猎人首领**从两份** `#minecraft:illager_friends` 里都移除（模组自带那份 + 整合包那份） | 袭击者**恢复攻击** —— 证明判定确实读标签。⚠️ **只删一份无效**，两份是并集 |
| 6 | 原版对照：掠夺者仍不打猎人 | 无回归 |
| 7 | 劫掠兽 | 由"会打"变为**不打**（本方案附带收益） |

第 4、5 条是这套验证的关键：**没有负向对照，就无法区分"修好了"和"整个 AI 挂了"。**

---

## 实施要点（供 planning 阶段展开）

- **依赖**：**无需新增 maven 仓库**（项目已有 cursemaven）；
  `dependencies` 加 `compileOnly "curse.maven:raids-enhanced-1467121:7676063"` + `localRuntime` 同坐标。
  已核实该 jar 内 `META-INF/neoforge.mods.toml`：`modId="raidsenhanced"`、`version="1.0.2"`、
  要求 `neoforge [21.1.218,)`（项目 21.1.236 ✓）与 `minecraft [1.21.1]` ✓。
  **不要用 Modrinth maven 坐标**（`maven.modrinth:raidsenhanced:1.0.2`）：按版本号解析会命中同版本号的
  **Forge** 构建（`META-INF/mods.toml`），NeoForge 1.21.1 读不到它 ⇒ **游戏无法加载**（已实测）。
- **fdlib 无需变更**：项目现用的 `curse.maven:fdlib-1271749:7844741` 实为 **1.0.9**，满足袭击增强要求的 `[1.0.8,2.0.0)`。
- **P1 注入签名**：`isAlliedTo(Lnet/minecraft/world/entity/Entity;)Z`（`Entity` 有两个重载，**必须锁定 `(Entity)` 这个**）。
  注入体内 `this` 是混入类，需 `(Object) this instanceof Raider` 形式做守卫。
  **`remap = false` 同样是必需的**（P1 打的是原版类）：NeoForge 运行时即用 Mojang 官方名、
  dev 命名空间 == 运行时命名空间；留默认 `true` 会直接构建失败
  `Unable to locate obfuscation mapping for @Inject target`。
  先例见 `mixin/minecraft/DreadKingRitualTriggerMixin.java:103-107` 与
  `mixin/PossibleBiomesFilterMixin.java:74,80`（后者形态与本注入完全一致）。
- **P2 注入签名**：`checkTargetClass(Lnet/minecraft/world/entity/Entity;)Z`，`@Pseudo`，`remap = false`（模组方法）。
- **同伴判定来源**：`net.minecraft.tags.EntityTypeTags.ILLAGER_FRIENDS`（原版常量，双端可用）。
- **模组自带标签**（本设计的一环，非可选）：
  `src/main/resources/data/minecraft/tags/entity_type/illager_friends.json`，**必须带 `"replace": false`**，
  值按 DS 自己生成的 `hunter_faction` 标签顺序写入 6 个：

  ```
  dragonsurvival:hunter_ambusher
  dragonsurvival:hunter_griffin
  dragonsurvival:hunter_hound
  dragonsurvival:hunter_knight
  dragonsurvival:hunter_leader
  dragonsurvival:hunter_spearman
  ```

  这 6 个 ID 已双源核对：DS 生成的 `data/dragonsurvival/tags/entity_type/hunter_faction.json`
  与 `DSEntities` 里各 `REGISTRY.register(...)` 的首参**完全一致**。
- **实测实体 ID**：`raidsenhanced:zapper` / `golem_of_last_resort` / `raid_blimp` / `raid_drill`
  （注意模组本身的命名不一致：`ZapperIllager` 注册名是 `zapper` 而 `build("engineer")` 只是翻译键；`PlayerBlimp` 同理）。

---

## 来源

- 袭击增强源码：`D:\Minecraft\开源模组参考文件\raidsenhanced`（本地克隆，master `a1a6ded`，mod_version `1.0.2`）
- 原版/NeoForge 反编译源：`build/review-src/neoforge-21.1.236/`
- 龙之生存源码参考树：`D:\Minecraft\开源模组参考文件\DragonSurvival`
- 项目 mixin 规范先例：`mixin/minecraft/DreadKingRitualTriggerMixin.java`、
  `mixin/legendarymonsters/AnnihilationPursuerDamageCapMixin.java:19-26`、
  `mixin/mowziesmobs/EntityWroughtnautMaceMixin.java:30-34`、`beloong.mixins.json`
- 上一轮审查（可选依赖缺陷面）：`docs/reviews/2026-09-18-post-0.9.3-bug-list.md` 第 2 条
- 6 个猎人实体 ID 的双源核对：DS 生成的 `data/dragonsurvival/tags/entity_type/hunter_faction.json`
  与 `DSEntities.java` 各 `REGISTRY.register(...)` 首参
- 整合包侧既有标签：`BeLoong-Server/kubejs/data/minecraft/tags/entity_type/illager_friends.json`
