# 爪牙剑进度触发器 设计文档

**日期：** 2026-09-16
**状态：** 已批准（方案 A：父/子双进度）· **修订 v2**（撤回 D1、删除 D7）
**方案：** 两个自定义判据 + 父/子两个进度，零 mixin

> **v2 修订说明**：用户裁定 **龙技能 `DamageEffect(use_claw=true)` 的换手路径是允许的、也应当触发**，
> 且教学意义更强。据此**撤回 D1**（原「只覆盖攻击路径」），并**删除 D7**（原「击杀判据加
> `PLAYER_ATTACK` 限制」——该限制本是为防进度树断链而加，现在两条路径都会先触发换手判据，
> 断链风险由事件先后天然消除）。
> 修订后**覆盖面更大，且 F6 不再承重**：`LivingIncomingDamageEvent` 的触发点就在
> `LivingEntity#hurt()` 内部，与 Mixin 注入顺序无关。

## Problem Statement

龙之生存（Dragon Survival）的「爪牙槽」允许龙形态玩家把剑/镐/斧/锹存在四个专用槽位里。
攻击、采掘或施放带 `use_claw` 的龙技能时，DS 会把对应工具**临时搬进主手**（`swapStart`），
做完再搬回去（`swapFinish`），这样原版与其他模组读 `player.getMainHandItem()` 的逻辑无需适配
就能正确工作。

这套机制对玩家完全不可见——换手发生在单次方法调用内，玩家看不到任何提示，很容易一直以为
"爪牙槽没用"。本项目需要：

1. 一个**进度触发器**，在爪牙剑被实际换入主手时触发；
2. 一个**测试用进度**，让玩家"换手后击杀任意生物"，从而理解该机制。

约束：**尽量不用 mixin**（除非用 mixin 会显著更简单）。

## 关键前置事实（均已对本机构件验证，非推测）

| # | 事实 | 证据 |
|---|---|---|
| F1 | BeLoong 锁定的 DS 构建 `8726322` 与参考源码包结构一致，`ClawInventoryData.switchedTool` / `switchedToolSlot` 是 **public 字段** | `javap -p` 目标 jar |
| F2 | 换手是 **方法调用内闭合**的（`Player#attack` 的 HEAD 换入、RETURN 换出；`DamageEffect.apply` 内同样） | DS `PlayerStartMixin`/`PlayerEndMixin`/`DamageEffect` |
| F3 | 因此 `ServerTickEvent.Post` 轮询**永远读到 `switchedTool == false`** | 由 F2 推出 |
| F4 | `LivingEquipmentChangeEvent` 也**无效**：它从 `LivingEntity#collectEquipmentChanges()` 触发，靠比对上一 tick 快照；同 tick 换入换出 diff 不出来 | NeoForge `patches/.../LivingEntity.java.patch` |
| F5 | `Player#attack` 被打补丁后的**第一条语句**是 `if (!CommonHooks.onPlayerAttackTarget(this, target)) return;`（即 `AttackEntityEvent`） | NeoForge `patches/.../Player.java.patch` |
| F6 | DS `PlayerStartMixin` 是 `@At("HEAD")` + `priority = 1` ⇒ 其注入落在方法体第一条指令**之前** ⇒ `AttackEntityEvent` 触发时 `switchedTool == true`。**v2：此假设已不再承重**——它只影响"攻击打空时是否也触发"，正确性由 F11 保证 | `javap -v`（RuntimeVisibleAnnotations）+ Mixin HEAD 语义 |
| F7 | 1.21.1 进度**没有父级门控**：`PlayerAdvancements.award()` → `AdvancementProgress.grantProgress()` 内无任何 parent 检查；`registerListeners()` 遍历全部进度 | 反编译的 1.21.1 源 |
| F8 | 爪中剑击杀时 `LivingDeathEvent` 已经触发而 `swapFinish` 尚未执行 ⇒ 换手仍生效 | `Player#attack` / `DamageEffect.apply` 调用链 + 反编译源 |
| F9 | `DamageSource.is(ResourceKey<DamageType>)` 存在（v2 已不再使用） | 反编译的 1.21.1 源 |
| **F10** | NeoForge 21.1.**236 已移除 `LivingHurtEvent`**；伤害事件是 `LivingIncomingDamageEvent` + `LivingDamageEvent.Pre/Post` | NeoForge `21.1.236-sources.jar` 中无 `LivingHurtEvent`；`LivingEntity.java` 补丁里只有 `onEntityIncomingDamage` / `onLivingDamagePre` |
| **F11** | `LivingIncomingDamageEvent` 在 `LivingEntity#hurt` **第 1153 行**触发；同方法 **第 1266 行**才 `die(source)` ⇒ 同一伤害实例内伤害事件**必然先于**死亡事件 | 反编译的 1.21.1 源 |
| **F12** | `DamageSource(Holder<DamageType>, Entity)` → `this(type, entity, entity)`，该 entity 同时是 direct 与 causing ⇒ `getEntity()` 对龙技能路径返回玩家 | 反编译的 1.21.1 源 |

## Design

### Architecture

```
beloong:claw_sword/swap   ← 根进度，判据 beloong:claw_sword_swap    → 换手时 toast
      └─ beloong:claw_sword/kill  ← 子进度，判据 beloong:claw_sword_kill → 爪剑击杀 toast
```

两个判据都是「无附加谓词」型（只带标准 `player` 谓词），在服务端事件里手工 `trigger()`。
选择自定义判据而非 `minecraft:impossible` + 手工 `award()`，是因为前者让子进度 JSON 自解释、
数据包可复用，且**完全不需要查 `AdvancementHolder`，没有 NPE 面**。

**换手判据由两个事件共同触发**，从而同时覆盖两条换手路径：

| 事件 | 覆盖 | 说明 |
|---|---|---|
| `AttackEntityEvent` | 玩家攻击（换手**发生的瞬间**） | 覆盖"打空"——剑已换入主手但未命中。此路依赖 F6，但 F6 不成立也不会出错（只是这一路不生效） |
| `LivingIncomingDamageEvent` | **玩家攻击 + 龙技能** | 触发点在 `hurt()` 内部，**必然**处于换手窗口内，与 head 注入顺序无关。是主力路径，同时兜住 F6 不成立的情形 |

### Components

| 文件 | 动作 | 职责 |
|---|---|---|
| `registry/ModCriteria.java` | 新建 | `DeferredRegister<CriterionTrigger<?>>`（`Registries.TRIGGER_TYPE`）+ 注册两个判据 |
| `compat/dragonsurvival/ClawSwordSwapTrigger.java` | 新建 | 换手判据类（DS `BeDragonTrigger` 同形，~28 行） |
| `compat/dragonsurvival/ClawSwordKillTrigger.java` | 新建 | 爪剑击杀判据类（同上，仅类名不同） |
| `compat/dragonsurvival/ClawSwordAdvancementHandler.java` | 新建 | **3 个事件** → 打判据（见下） |
| `BeLoongCore.java` | 改 2 行 | 构造期 `ModCriteria.REGISTRY.register(modEventBus)`；`NeoForge.EVENT_BUS.register(new ClawSwordAdvancementHandler())` |
| `data/beloong/advancement/claw_sword/swap.json` | 新建 | 根进度 |
| `data/beloong/advancement/claw_sword/kill.json` | 新建 | 子进度，`parent: beloong:claw_sword/swap` |
| `assets/beloong/lang/zh_cn.json`、`en_us.json` | 各 +4 条 | 两个进度的 title / description |

事件处理器三个方法：

| 方法 | 事件 | 条件 | 动作 |
|---|---|---|---|
| `onAttack` | `AttackEntityEvent` | `getEntity()` 是 `ServerPlayer`，且其 `switchedTool && switchedToolSlot == Slot.SWORD.ordinal()` | `CLAW_SWORD_SWAP.trigger(player)` |
| `onIncomingDamage` | `LivingIncomingDamageEvent` | `getSource().getEntity()` 是 `ServerPlayer`，且**该玩家**的换手状态为 SWORD | `CLAW_SWORD_SWAP.trigger(player)` |
| `onDeath` | `LivingDeathEvent` | `getSource().getEntity()` 是 `ServerPlayer`，且**该玩家**的换手状态为 SWORD | `CLAW_SWORD_KILL.trigger(player)` |

> 注意 `onIncomingDamage` / `onDeath` 检的是**攻击者**的换手状态（不是受害者的），
> 因为 `ClawInventoryData` 挂在玩家身上，而事件主体是挨打的一方。

沿用项目既有惯例：注册器放 `registry/ModXxx`，第三方兼容放 `compat/<模组>/`，
事件处理器按 `BeLoongCore` 构造期显式 `NeoForge.EVENT_BUS.register(...)` 注册
（与 `StructureEffectHandler`、`ManaLossHandler` 一致），不用 `@EventBusSubscriber`。
DS 在 `build.gradle` 与 `neoforge.mods.toml` 里都是 **required**（且 `ordering="AFTER"`，
DS 先加载），因此**无需 `ModList.get().isLoaded` 守卫**。

判据类（两个逐字同形）：

```java
public class ClawSwordSwapTrigger extends SimpleCriterionTrigger<ClawSwordSwapTrigger.Instance> {
    public void trigger(ServerPlayer player) { this.trigger(player, instance -> true); }
    @Override public @NotNull Codec<Instance> codec() { return Instance.CODEC; }

    public record Instance(Optional<ContextAwarePredicate> player)
            implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player)
        ).apply(i, Instance::new));
    }
}
```

进度 JSON（图标与背景都用**原版占位**，不新增美术资源）：

```jsonc
// swap.json
{
  "criteria": { "swap": { "trigger": "beloong:claw_sword_swap" } },
  "display": {
    "title":       { "translate": "advancements.beloong.claw_sword.swap.title" },
    "description": { "translate": "advancements.beloong.claw_sword.swap.description" },
    "icon": { "id": "minecraft:diamond_sword", "count": 1 },
    "frame": "task",
    "background": "minecraft:textures/gui/advancements/backgrounds/stone.png",
    "show_toast": true, "announce_to_chat": false, "hidden": false
  },
  "requirements": [["swap"]], "sends_telemetry_event": true
}
```
```jsonc
// kill.json
{
  "parent": "beloong:claw_sword/swap",
  "criteria": { "kill": { "trigger": "beloong:claw_sword_kill" } },
  "display": {
    "title":       { "translate": "advancements.beloong.claw_sword.kill.title" },
    "description": { "translate": "advancements.beloong.claw_sword.kill.description" },
    "icon": { "id": "minecraft:iron_sword", "count": 1 },
    "frame": "task", "show_toast": true, "announce_to_chat": false, "hidden": false
  },
  "requirements": [["kill"]], "sends_telemetry_event": true
}
```

`{"id": ..., "count": 1}` 是 1.20.5+ 的 item stack 写法，已对照 DS 自己的 `root.json` 确认。

### Data Flow

**路径一：玩家攻击**（换手发生在 `Player#attack` 内）

```
Player#attack(Entity)
 ├─ HEAD (DS PlayerStartMixin, prio 1)
 │    └─ getDragonSword(player) → 手上有真工具则 EMPTY；否则取爪槽 Slot.SWORD
 │    └─ swapStart(player, sword, 0) → 爪槽清空 · 剑进主手 · switchedTool=true
 ├─ ★ AttackEntityEvent → onAttack → trigger(SWAP)         [F6 成立时走这里，覆盖打空]
 ├─ attack 主体 → target.hurt(damageSources().playerAttack(this), dmg)
 │    ├─ ★ LivingIncomingDamageEvent → onIncomingDamage → trigger(SWAP)   [主力路径]
 │    └─ ... health ≤ 0 → die(source)
 │         └─ ★ LivingDeathEvent → onDeath → trigger(KILL)
 └─ RETURN (DS PlayerEndMixin, prio 10000) → swapFinish（还原）
```

**路径二：龙技能 `DamageEffect(use_claw = true)`**（换手发生在技能结算内，**没有** `Player#attack`）

```
DamageEffect.apply(dragon, ability, target)
 ├─ swap(dragon) → getDragonSword → swapStart(player, sword, Slot.SWORD)
 ├─ target.hurt(new DamageSource(damageType, dragon), …)     [F12: getEntity() == dragon]
 │    ├─ ★ LivingIncomingDamageEvent → onIncomingDamage → trigger(SWAP)   ← 覆盖此路径
 │    └─ ... health ≤ 0 → die(source)
 │         └─ ★ LivingDeathEvent → onDeath → trigger(KILL)
 └─ swapFinish(dragon)
```

**顺序保证（v2 不需要任何门控代码）**：`LivingIncomingDamageEvent`（`hurt` 第 1153 行）
**必然先于** `LivingDeathEvent`（`die` 在同方法第 1266 行触发）⇒ 同一伤害实例内
**换手判据先于击杀判据**。两条路径都成立，因此进度树不会断链。

`AttackEntityEvent` 与 `LivingIncomingDamageEvent` 会在同一次攻击里**各触发一次**换手判据——
这是**无害**的：`PlayerAdvancements.award` 是幂等的，且根进度完成后其监听器即被注销，
第二次触发是 no-op。

爪中剑击杀与技能击杀的 `source.getEntity()` 都直接是玩家本人（F12），因此
**不依赖会衰减的 `lastHurtByPlayer`**（该字段 100 tick 后会被清空）。

### Error Handling

| 情形 | 行为 |
|---|---|
| 非龙形态玩家 | `switchedTool` 恒 false → 两个判据都不触发 |
| 手上持有真工具 / 爪槽没放剑 | `getDragonSword` 返回 EMPTY → 不换手 → 不触发 |
| 创造 / 旁观模式 | DS `swapStart` 直接 return → `switchedTool` 保持 false → 不触发 |
| 客户端预测 | 三个事件都过滤 `ServerPlayer`；`AttackEntityEvent` 客户端侧是 `LocalPlayer` |
| 攻击落空（未命中） | 仅 `AttackEntityEvent` 触发 → 根进度照给；若 F6 不成立则此情形不触发（可接受） |
| 龙技能 `DamageEffect(use_claw)` 击杀 | **v2：正常触发**两条判据（用户裁定允许） |
| 换手期间被第三方取消攻击 | `AttackEntityEvent` 若被取消则 `Player#attack` 提前返回，但 DS 的 HEAD 已执行、RETURN 注入仍会 `swapFinish`；无伤害则 `LivingIncomingDamageEvent` 不触发 |
| 进度被数据包删除 | 判据由进度系统自身分发，无手工 `AdvancementHolder` 查找 → 无 NPE 面 |
| 判据未注册 | 位于冻结注册表 `Registries.TRIGGER_TYPE`，注册发生在 mod 构造期；失败会在启动期响亮报错 |
| `/kill` 等无攻击者的死亡 | `getSource().getEntity()` 为 null → 不触发（与"用爪剑击杀"语义一致） |

## Decisions Made

| # | 决策 | 理由 |
|---|---|---|
| **D1（v2 修订）** | 换手触发器覆盖**玩家攻击 + 龙技能 `DamageEffect(use_claw)` 两条路径** | **用户 v2 裁定**：技能路径的换手是允许的、也应当触发，教学意义更强。（原 v1 为"只覆盖攻击路径"） |
| D2 | 击杀侧用**独立判据** `beloong:claw_sword_kill`，不用 `minecraft:impossible` + 手工 `award()` | 子进度 JSON 自解释、数据包可复用；且免去 `AdvancementHolder` 查找与 null 检查 |
| D3 | 图标与背景都用**原版占位**（`minecraft:diamond_sword` / `iron_sword` / 原版 `stone.png` 背景） | 用户明确"随便找个…后面要删掉的"。用原版⇒连新美术文件都不产生，清理时只删 JSON |
| D4 | `announce_to_chat: false`，`show_toast: true` | 教学 toast 不应刷聊天框；DS 自己的根节点也是关的 |
| D5 | 判据只带 `player` 谓词，**不带** entity 谓词 | YAGNI——"用爪剑杀特定怪"当前无使用方 |
| D6 | 进度 ID `beloong:claw_sword/{swap,kill}`，文件放 `data/beloong/advancement/claw_sword/` | 为将来"爪牙槽教学"扩展留位置 |
| ~~D7~~ | ~~击杀判据必须加 `source.is(DamageTypes.PLAYER_ATTACK)`~~ | **v2 删除**。该限制本是为防"技能击杀 → 子进度先于父进度"的断链；覆盖技能路径后，换手判据在技能路径上同样会触发，且 F11 保证了事件先后，断链风险消失 |
| D8 | 不用 mixin。换手判据用 `AttackEntityEvent` + `LivingIncomingDamageEvent` 双事件覆盖，从而**不依赖任何注入顺序假设** | 用户约束；且 v2 后 F6 不再承重，无需回退梯度 |
| **D9（v2 新增）** | 换手判据由**两个**事件共同触发（而非合并为一个） | `AttackEntityEvent` 是"换手瞬间"的最近似表达且覆盖打空；`LivingIncomingDamageEvent` 覆盖技能路径且不依赖注入顺序。二者互补，重复触发幂等无害 |

## Non-Goals

- 覆盖**采掘**路径下剑被换入主手的情形（`getDragonHarvestToolAndSlot` 从槽 0 起遍历，
  理论上挖蛛网这类方块可能选中剑）。属边角情形，且需要额外的注入点
- 制作自定义图标/背景贴图（D3）——本版是"跑通机制"的测试进度
- 判据携带 entity 谓词或其他筛选条件（D5）
- 正式进度树美术、整合包正式进度编排（后续另做）
- 使用 mixin（D8）

## Verification Strategy

1. `.\gradlew.bat build` → EXIT=0
2. 客户端启动，日志确认：无 advancement JSON 解析错误、DS mixin 正常应用
3. 实机（**由用户执行**）：
   - 龙形态 → 爪槽放剑 → 攻击生物（期望根 toast）→ 用它击杀（期望子 toast）
   - **技能路径**：给龙配一个 `use_claw = true` 的技能，用技能击杀生物
     （期望同样两次 toast——v2 的新增覆盖面）
4. **F6 不再是承重假设**。它只影响"攻击打空时是否也触发根进度"。
   若发现打空不触发而命中触发，属预期内的降级，**无需回退**。
   若两条路径**都完全不触发**，则问题不在 F6，应依次检查：
   判据是否注册成功、`switchedTool` 是否真的为 true（加临时日志）、
   DS 的换手是否被其他模组阻止。

## Next Steps

实施计划见 `docs/plans/2026-09-16-claw-sword-advancement-plan.md`（v2）。
