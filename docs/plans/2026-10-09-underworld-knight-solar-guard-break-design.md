# 冥界骑士「太阳破防」设计文档

**日期：** 2026-10-09
**状态：** ✅ 已批准（brainstorming 流程：Phase 1 上下文加载 → Phase 2 两个意图问题 → Phase 3 三方案对比 → Phase 4 四节设计逐节确认）
**选定方案：** 方案 1（Mowzie 侧三处转换 + 骑士侧单点注入 + 数据层标签作唯一判定入口）
**相关资料：** [首领崛起-冥界骑士-调研.md](../首领崛起-冥界骑士-调研.md)（机制与 BUG 清单）、`memory/decisions-log.md`

---

## 一、问题与目标

**目标**：让 Mowzie's Mobs 的「太阳祝福」三招（太阳耀斑 / 太阳射线 / 太阳打击）能够**直接伤害**传奇怪物《首领崛起》的冥界骑士（`block_factorys_bosses:underworld_knight`，赫尔瓦），并**击穿其护盾**（每次命中扣 1 层免疫层数），同时为后续模组预留"破防伤害类型"的扩展入口。

**为什么需要魔改**：骑士的"无敌"完全实现在它自己的 `hurt`/`processPurt` 里（`isInvulnerable() = 免疫层数 > 0`），**不是原版无敌**；而 Mowzie 三招用的是原版通用伤害类型（见 §1.1），与骑士没有任何交集。纯数据包无法触及"破防"语义（只有 `fromMark=true` 这条内部参数能做到），因此必须做定向注入。

### 1.1 事实基线（已用源码 + 字节码核实）

| 项 | 事实 |
|---|---|
| 骑士护盾 | `DATA_IMMUNE_STACKS`（初始 1；50% 闸门→2；阶段切换→1）`isInvulnerable() = stacks > 0 \|\| super.isInvulnerable()`（`UnderworldKnightEntity.java:351-353`）|
| 骑士免伤管线 | `processPurt(ds, amount, fromMark)`：`fromMark`/`BYPASSES_INVULNERABILITY` ⇒ `flag=true` ⇒ 无视护盾全额结算（`:420-429`）；被格挡 ⇒ `return false`（`:505-507`）|
| **关键**：被格挡的命中不触发任何 NeoForge 事件 | `CommonHooks.onEntityIncomingDamage` 在 `LivingEntity.java:1153`（`hurt` 内部），而模组覆写在到达 `super.hurt` 前就 `return false` ⇒ 事件永不触发 |
| Mowzie 无自定义伤害类型 | 全库无 `damage_type` json、无 `ResourceKey.create(Registries.DAMAGE_TYPE…)`、无 `new DamageType(…)` |
| 三招实际伤害类型 | 耀斑 `minecraft:player_attack`（`SolarFlareAbility.java:109`）；射线/打击 `minecraft:mob_projectile` + `minecraft:on_fire`（两段混合，`EntitySolarBeam.java:255/258`、`EntitySunstrike.java:225/229`）|
| 换类型会脱离原版标签 | `#minecraft:is_player_attack` / `#minecraft:is_projectile` 被**数据文件**消费：`enchantment/projectile_protection.json`、7 个成就（`overoverkill` 用 `is_player_attack`；`blowback/shoot_arrow/sniper_duel/throw_trident/return_to_sender/deflect_arrow` 用 `is_projectile`）|

---

## 二、架构

```
数据层（纯资源）
  3 个伤害类型(mowziesmobs:) ──┐
  1 个语义标签(beloong:)  ←────┘（唯一判定入口）
  2 个原版标签补回 + 6 条死亡信息
        ↓
转换层（Mowzie 侧 3 处 @ModifyArg，保留 on_fire）
  SolarFlareAbility.beginSection / EntitySolarBeam.tick /
  EntitySunstrike.damageEntityLivingBaseNearby
        ↓
判定层（骑士侧 1 处 @Inject HEAD）
  source.is(beloong:underworld_knight_guard_break)
    → processPurt(source, amount, true)     // 穿透 + 正常结算
    → 返回 true 且 层数>0 ⇒ removeOneImmuneStack()   // 只扣 1 层
        ↓
观测层（英文 ASCII 日志锚点 + COMMON 配置开关）
```

### 2.1 架构决策

| # | 决策 | 理由 |
|---|---|---|
| **D1** | 三处转换用 `@ModifyArg` 改参数，且靠"原 source 是否为 `on_fire`"判别，**不使用 `ordinal`** | 项目 `injectors.defaultRequire: 1` + 用户明确"关心崩溃面"；`ordinal` 会随重编译漂移 |
| **D2** | **保留 `on_fire` 段不转换** | 用户要求（燃烧生效）；红利：两段混合伤害里只有第一段命中标签 ⇒ 一次命中只扣 1 层 |
| **D3** | 骑士侧只注入 **`hurt` 的 HEAD 一处**，不碰 `processPurt` 内部 / `stuck` / `setState` | 单点最稳；`flag=true` 会跳过 `stuck` 分支（不会二次扣层、不播 `KNIGHT_BLOCK`）；不改状态 ⇒ 规避 `fake_dead` 死锁（调研 §六 B2）|
| **D4** | 扣层时机 = `processPurt(...)` **返回 true 之后** | 顺带正确处理"过场 cinematic 内不掉层"与"0 伤害不掉层" |
| **D5** | **标签是唯一判定入口** | 未来任何模组/数据包把类型加进 `beloong:underworld_knight_guard_break` 即生效，骑士侧代码不再改 |
| **D6** | 三个类型放 `mowziesmobs:` 命名空间 | 用户选择（语义直观）；已登记遮蔽风险与回退路径（§八）|
| **D7** | 三个类型**补回** `#is_player_attack` / `#is_projectile` | 用户选择（Q2=A）：保住弹射物保护附魔与 7 个成就的判定语义；项目已有同类先例（`data/minecraft/tags/damage_type/{bypasses_cooldown,no_knockback}.json` 里写 `beloong:tornado`）|

---

## 三、组件清单

### 3.1 数据层（`src/main/resources/`，7 个文件）

| 路径 | 内容 |
|---|---|
| `data/mowziesmobs/damage_type/solar_flare.json` | `message_id "mowziesmobs.solar_flare"`、`exhaustion 0.1`、`scaling "when_caused_by_living_non_player"`、`effects "hurt"`、`death_message_type "default"`（数值照抄原版 `player_attack`）|
| `data/mowziesmobs/damage_type/solar_beam.json` | 同上，`message_id "mowziesmobs.solar_beam"`（数值照抄 `mob_projectile`）|
| `data/mowziesmobs/damage_type/sun_strike.json` | 同上，`message_id "mowziesmobs.sun_strike"` |
| `data/beloong/tags/damage_type/underworld_knight_guard_break.json` | `{"values":["mowziesmobs:solar_flare","mowziesmobs:solar_beam","mowziesmobs:sun_strike"]}` |
| `data/minecraft/tags/damage_type/is_player_attack.json` | 追加 `mowziesmobs:solar_flare`（数据包并集，保留原版 `minecraft:player_attack`）|
| `data/minecraft/tags/damage_type/is_projectile.json` | 追加 `mowziesmobs:solar_beam`、`mowziesmobs:sun_strike` |
| `assets/beloong/lang/{zh_cn,en_us}.json` | 6 条死亡信息（见 §3.4）|

> **P3 规范**：三个 json **显式写全 5 个字段**（`message_id`/`exhaustion`/`scaling`/`effects`/`death_message_type`），与项目既有 [air_strike.json](../../src/main/resources/data/beloong/damage_type/air_strike.json) 一致。

### 3.2 代码层（4 个 Mixin + 2 个新类 + 1 处注册）

| 文件 | 目标 / 内容 |
|---|---|
| `mixin/mowziesmobs/SolarFlareAbilitySolarDamageMixin.java` | `protected void beginSection(Lcom/bobmowzie/mowziesmobs/server/ability/AbilitySection;)V`：`@ModifyArg` on `Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z` index 0（bytecode 131）|
| `mixin/mowziesmobs/EntitySolarBeamSolarDamageMixin.java` | `public void tick()V`：`@ModifyArg` on `DamageUtil.dealMixedDamage(...)Pair;` index **1**（bytecode 1187）＋ `@ModifyArg` on `Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z` index 0（bytecode 1209）|
| `mixin/mowziesmobs/EntitySunstrikeSolarDamageMixin.java` | `public void damageEntityLivingBaseNearby(D)V`：`@ModifyArg` on `Entity;hurt(...)Z` index 0（bytecode 324 与 347 两处，靠 handler 判别 `on_fire` 决定是否转换）|
| `mixin/legendarymonsters/UnderworldKnightGuardBreakMixin.java` | `UnderworldKnightEntity#hurt(DamageSource,F)Z`：`@Inject(at = HEAD, cancellable = true, remap = false, require = 0)`（`@Pseudo`，可选依赖）|
| `compat/mowziesmobs/SolarDamageTypes.java` | 3 个 `ResourceKey<DamageType>`（`ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath("mowziesmobs", …))`）＋ `convert(original, key)`：`original.is(DamageTypes.ON_FIRE)` ⇒ 原样返回；否则 `new DamageSource(level.registryAccess().holderOrThrow(key), original.getDirectEntity(), original.getEntity())`（保留 direct/causing）；`holderOrThrow` 异常 ⇒ 捕获后原样返回 + 节流 WARN |
| `registry/ModDamageTypeTags.java` | `UNDERWORLD_KNIGHT_GUARD_BREAK`（`TagKey.create(Registries.DAMAGE_TYPE, beloong:underworld_knight_guard_break)`，对齐既有 `registry/ModEntityTypeTags.java`）|
| `src/main/resources/beloong.mixins.json` | 新增 4 条（`mowziesmobs.*` ×3 与 `legendarymonsters.UnderworldKnightGuardBreakMixin`）|

**骑士侧注入逻辑（伪代码，非最终代码）**

```java
@Inject(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
        at = @At("HEAD"), cancellable = true, remap = false, require = 0)
private void beloong$solarGuardBreak(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
    if (!Config.SolarGuardBreak.enabled.get()) return;
    if (self.level().isClientSide) return;                       // E7
    if (!source.is(ModDamageTypeTags.UNDERWORLD_KNIGHT_GUARD_BREAK)) return;
    boolean dealt = self.processPurt(source, amount, true);      // 穿透 + 正常结算
    if (dealt && self.getImmuneStacks() > 0) self.removeOneImmuneStack();   // 只扣 1 层
    logAnchor(source, dealt);                                    // 节流 5s
    cir.setReturnValue(dealt);
}
```

### 3.3 观测层

| 组件 | 内容 |
|---|---|
| 日志锚点（英文 ASCII，5 s 节流）| `[BeLoong] solar-guard-break: source=mowziesmobs:solar_flare stacks=1->0 dealt=true` |
| 配置（COMMON，P1 要求）| 新增 `[solar_guard_break]`：`enabled = true`；关闭后三处转换与骑士侧判定都短路，恢复 Mowzie 原版行为 |

### 3.4 死亡信息（P2：中文不带空格）

| 键 | zh_cn | en_us |
|---|---|---|
| `death.attack.mowziesmobs.solar_flare` | `%1$s被%2$s的太阳耀斑烧成灰烬` | `%1$s was incinerated by %2$s's solar flare` |
| `death.attack.mowziesmobs.solar_beam` | `%1$s被%2$s的太阳射线贯穿` | `%1$s was pierced by %2$s's solar beam` |
| `death.attack.mowziesmobs.sun_strike` | `%1$s被%2$s的太阳打击碾碎` | `%1$s was crushed by %2$s's sunstrike` |

（与 `death.attack.player` 同形：两个占位符；这三种伤害的施法者必然存在 ⇒ 不会出现 `%2$s` 残留。）

---

## 四、数据流与状态变化

### 4.1 主路径：太阳耀斑命中骑士（1 层盾）

```
① 玩家带 suns_blessing → 潜行左键 → SolarFlareAbility.beginSection(ACTIVE)
② 原逻辑 playerAttack(user) 构造 DamageSource
③ 我们的 @ModifyArg 换成 mowziesmobs:solar_flare（direct/causing 仍为玩家）
④ UnderworldKnightEntity.hurt(source, 12.0)
⑤ HEAD 注入：source.is(标签) == true
⑥ 先 processPurt(source, amount, true) → 跳过黑名单与护盾拦截 → 75/50/25% 闸门 → super.hurt
⑦ 返回 true 且 层数>0 ⇒ removeOneImmuneStack()：1 → 0
⑧ 日志锚点
```

**唯一状态变化**：`DATA_IMMUNE_STACKS: 1 → 0`。
**明确不改**：`DATA_BOSS_PHASE`、`State`（不产生 `stuck`/`stagger`/`knocked_down`）、`DATA_IMMUNE_MAX`、不生成印记、不播 `KNIGHT_BLOCK`/`WISP_EXPLODE`。

### 4.2 射线 / 太阳打击（两段混合伤害）

| 段 | 原类型 | 处理 | 结果 |
|---|---|---|---|
| 1 | `mob_projectile` | 换成 `mowziesmobs:solar_beam` / `sun_strike` | 命中标签 ⇒ 穿透 + 扣 1 层 |
| 2 | `on_fire` | **原样保留** | 燃烧照旧；不命中标签 ⇒ 不再扣层（避免一次扣 2 层）|

射线是持续性伤害且**不做额外节流**：层数只有 1–2，语义上"每次命中剥一层"合理；层数归零后每次命中都造成穿透伤害。

### 4.3 边界分支

| 场景 | 结果 |
|---|---|
| 命中非骑士实体 | **行为等价原版**（仅死亡信息文案变化；保护附魔/成就由 D7 的标签补回保证）|
| 普通攻击命中骑士 | ⑤ 判定 false ⇒ 不 cancel，原 `hurt` 完整执行（`stuck` 破防、`KNIGHT_BLOCK` 等原逻辑不变）|
| 骑士层数 = 0 | 穿透结算照常；跳过扣层（不会扣成负数，规避 `removeOneImmuneStack` 无下限保护的坑）|
| 骑士处于过场（cinematic）| `processPurt:417` `return false` ⇒ 不伤害、不扣层 |
| 配置关闭 | ③⑤ 均短路 ⇒ 完全回到 Mowzie 原版 |
| `processPurt` 内部重入 | 内部调的是 `super.hurt`（`LivingEntity.hurt`），**不回到覆写的 `hurt`** ⇒ 无递归 |

### 4.4 已定默认

射线/打击的施法者可以是玩家或太阳鸟 Umvuthi；类型转换在实体侧、与施法者无关 ⇒ **太阳鸟打骑士也会破防**。**默认不限制**（保持"太阳伤害即破防"的纯语义）。若要"仅玩家施法"，在转换 handler 加 `caster instanceof Player` 判定。

---

## 五、错误处理

| # | 失败点 | 表现 | 处理 |
|---|---|---|---|
| E1 | 骑士侧注入点漂移（可选依赖 + `require=0`）| 静默失效（回到"被盾挡"）| 日志锚点观测；提交前临时 `require=1` 让 AP 校验；文档登记 |
| E2 | Mowzie 侧注入点漂移（必选依赖 `require=1`）| 启动期硬崩（可见）| 刻意选择硬失败；mixin 注释记录 `javap` 偏移（1187/1209/324/347/131）|
| E3 | `@ModifyArg` 多匹配（`EntitySunstrike` 两处 `hurt`）| 只注入第一处 ⇒ 会多扣层/丢燃烧；或直接报错 | 设计为"两处都注入 + handler 判别 `on_fire`"；若不符 ⇒ 按 `javap` 偏移补 `ordinal` 并注释 |
| E4 | `holderOrThrow` 拿不到类型（json 被禁/写错）| 抛异常打断伤害 | `convert()` try/catch ⇒ 降级返回原 source + 节流 WARN |
| E5 | 数据文件语法/路径错 | 启动日志 `Registry loading errors` / 标签 `missing following references` | 由 S1/S2 覆盖；E4 保证不崩 |
| E6 | 命名空间遮蔽（未来 Mowzie 自带同名 id）| 资源栈高优先级者静默胜出 | 登记风险 + 回退路径（§八）；可选启动期体检 |
| E7 | 客户端也执行骑士侧注入 | 客户端本地副本扣层/UI 闪动 | handler 加 `!level().isClientSide` 守卫（对齐 `KnightMarkEntity.hurt:101`）|
| E8 | COMMON 配置读取时机 | 配置未加载即取值会抛异常 | 只在游戏内伤害事件里读取 ⇒ 必然已加载（注释写明）|
| E9 | 射线持续命中刷日志 | 噪音 | 锚点 5 s 节流 |

---

## 六、验证策略与完成定义

### 6.1 静态 / 构建期

| # | 检查 |
|---|---|
| S1 | 7 个数据文件路径与 JSON 语法；标签内容与类型 id 一一对应 |
| S2 | 启动日志无 `Registry loading errors`、无 `missing following references` |
| S3 | Mowzie 三条 `require=1` 由构建期校验；骑士那条临时改 `1` 校验后改回 `0` |
| S4 | 若 E3 触发 ⇒ 按 `javap -c` 偏移补 `ordinal` 并记录 |
| S5 | `-Dmixin.debug.verbose=true -Dmixin.debug.export=true` 启动，确认 `Mixing … into …` 并 `javap -c` 复核插入点（传奇怪物 mixin 用过的同一手法）|

### 6.2 实机功能（T）

| # | 场景 | 期望 |
|---|---|---|
| T1 | 太阳祝福 → 潜行左键耀斑打骑士 | 伤害落地；层数 1→0；出现锚点 |
| T2 | 潜行右键太阳射线 | 同 T1 + 燃烧仍在 + **只扣 1 层** |
| T3 | 太阳打击 | 同 T2 |
| T4 | 用太阳伤害打死骑士 | 中英死亡信息正确、无空格、无 `%2$s` 残留 |
| T5 | 语义保留 | ① 弹射物保护对射线仍减伤；② `adventure/overoverkill` 仍可由耀斑达成 |
| T6 | 负向：普通攻击打骑士 | 与未装本功能完全一致 |
| T7 | 过场中打骑士 | 不掉血不掉层 |
| T8 | 层数=0 后继续打 | 伤害照常、层数不再变化、无负数 |
| T9 | 关闭配置开关 | 三招回到原版 |
| T10 | 未装 bossesrise 启动 | 双端正常启动 |
| T11 | 双端 | 客户端 + 专用服务器各跑一次 |

### 6.3 回归（R）

R1 太阳三招打**其它**生物数值/行为不变；R2 骑士其它机制（`stuck`/印记/阶段/成就）无变化；R3 日志无新增异常。

### 6.4 完成定义（DoD）

1. T1–T4 + T10–T11 全过；2. T5 全过；3. T6–T9 全过；4. 构建零新增警告 + S1–S5 全过；5. 设计文档落库、调研文档补索引。

---

## 七、非目标（Out of scope）

1. 不改骑士其它机制（`stuck` 破防姿势、冥界印记、阶段切换、层数上限）
2. 不做"强制倒地 / ×2 伤害"（用户选择"只扣 1 层"）
3. 不改 Mowzie 其它技能与其它伤害；只动这三招的"非 `on_fire`"段
4. 不做通用"任意 Boss 破防框架"（标签机制天然可复用，但本次只为冥界骑士实现）

## 八、已知风险与回退

| 风险 | 回退 |
|---|---|
| `mowziesmobs:` 命名空间遮蔽（未来 Mowzie 自带同名 id）| 整体改名到 `beloong:`（类型 json 路径 / 标签内容 / `message_id` / 翻译键 四处同步）|
| Mowzie 三招实现重构（注入点消失）| 启动期硬崩可见 ⇒ 按 `javap` 重新定位注入点；若改用新 API，评估在骑士侧改按"直接实体指纹"识别 |
| 骑士上游改名/改签名 | 日志锚点消失即暴露；按调研文档 §九 重新反编译 + `javap` 定位 |

## 九、后续步骤

1. 用 `planning` 技能把本设计拆成可执行任务（数据层 → Mowzie 转换层 → 骑士判定层 → 观测层 → 验证）
2. 实施后在本仓库 `memory/decisions-log.md` 记录实际结果，并把"无敌判定 + 破防全链 + 本次设计索引"补进 `docs/首领崛起-冥界骑士-调研.md`
