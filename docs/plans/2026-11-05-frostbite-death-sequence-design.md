# 霜噬击杀导致第三方 Boss「假死」—— 根因与修复设计

**日期：** 2026-11-05
**状态：** ✅ 已实施并实机验收（2026-11-05；2026-11-06 起由 COMMON 开关 `fixFrostbiteDeath` 控制，默认开）
**范围：** 只改模组侧（`BeLoong-Core`）；不改铁魔法、不改祸乱鬼魅、不改整合包
**起因：** 委托方实测：① 自身带「霜噬」`frostbitten_strikes` → ② 对虚空之花使用「雪球术」→ ③ 在寒冰领域期间击杀 ⇒ 虚空之花永久假死。三个条件缺一不可。

---

## 一、现象与取证

假死实体（存档 `run/saves/虚空之花假死`）直接读 region NBT：

| Pos | Health | DeathTime | TicksFrozen |
|---|---|---|---|
| (737, 96, 209) | **0.0** | **5948** | 418 |
| (696, 96, 230) | **0.0** | **8110** | 700 |
| (759, 94, 229) | **0.0** | **6309** | 418 |

- `DeathTime` 全是四位数，`DeathTime − 1000` 正好等于"死后经过的 tick 数"；
- `Health = 0.0` ⇒ `isDeadOrDying() = true` ⇒ 原版 `LivingEntity.hurt()` 在 `:1147` 直接返回 false
  ⇒ **连 `/kill` 都杀不掉**；
- 无 Passenger / Vehicle ⇒ **与冰棺无关**（与委托方实机结论一致）。

## 二、根因

**铁魔法**（`io.redspace.ironsspellbooks.effect.FrostbiteEffect#handleFrostbiteDeathEffects`，
源码 `FrostbiteEffect.java:23-39`，`LivingDeathEvent` 监听器）：

```java
if (damageSource.getEntity() instanceof LivingEntity attacker) {          // ① 击杀者
    var effect = attacker.getEffect(MobEffectRegistry.FROSTBITTEN_STRIKES); // ② 带霜噬
    if (effect != null && entity.isFullyFrozen()) {                        // ③ 死亡瞬间完全冻结
        FrozenHumanoid iceClone = new FrozenHumanoid(entity.level, entity);
        ...
        entity.deathTime = 1000;                                            // ★ 元凶
    }
}
```

这一行的**本意**是"让原尸体立刻消失、只留冰雕"：原版 `LivingEntity.tickDeath()`
（`LivingEntity.java:565-571`）在 `deathTime >= 20` 时 `remove(KILLED)`，写 1000 ⇒ 下一 tick 必删。

**祸乱鬼魅**（`BaseEntity.java:72-81`）**整个覆盖了 `tickDeath()`、从不调 `super`**，
死亡收尾交给自己的 `LightBlockRemover`：

```java
entity.deathTime++;                       // LightBlockRemover.java:25
if (entity.deathTime == deathMaxAge) {    // :32  deathMaxAge = 70f（:17）
    ...; entity.remove(Entity.RemovalReason.KILLED);
}
```

⇒ `deathTime` 被拨到 1000 之后只会上涨，**`== 70` 永不成立** ⇒ 永不 `remove()`、
永不广播事件 60、`VoidBlossomDropExpDeathTick:26` 的 `== 1` 也不成立（**连经验都不掉**）。

**三条件的对应关系**：① `damageSource.getEntity()`、② `getEffect(...)`、③ `isFullyFrozen()`
——分别落在上面三行。寒冰领域的作用是让 `TicksFrozen` 长期 ≥ 140（`getTicksRequiredToFreeze()`），
保证"死亡那一瞬间"③ 成立。这也解释了为什么"2+3（有领域、无霜噬）"不触发、
"1+3（有霜噬、无领域）"也不触发。

## 三、影响面（不止祸乱鬼魅）

参考区（31 开源 + 21 闭源）实测：

- **写 `LivingEntity.deathTime` 的外部代码只有铁魔法这一处**（其余命中都是各自的同名字段
  `dragonDeathTime`/`colossusDeathTime`/…、或原版自增、或 NBT 读档）。
- **移除/收尾依赖 `deathTime` 精确相等**（会被这行永久打断）：
  - 祸乱鬼魅：`LightBlockRemover:32`（`==70`）、`ServerGauntletDeathHandler:39`（`==50`）
  - 永恒星光：`Permafrost:202`（`==70`）、`LunarMonstrosity:216`（`==75`）、`SolarCreeper:399`（`==100`）
  - 两者在整合包里都在。
- **只用 `>=` 阈值**（135 处）：不会卡死，但死亡动画/演出会被跳过；
  灾变的 `Ender_Golem:396 ==40`、`Ender_Guardian:606/609`、`The_Harbinger:669 ==123`
  属这一类——`==` 只用来播声音/死亡爆炸，所以表现为"死亡爆炸消失"。

⇒ **修在铁魔法侧、且按"谁自己接管了死亡序列"判定**，才能一次覆盖所有模组。

## 四、修复设计

新增两个类（不动第三方、不加配置项、默认生效）：

| 文件 | 职责 |
|---|---|
| `mixin/ironsspellbooks/FrostbiteDeathSequenceMixin` | `@Inject(TAIL)` 到 `handleFrostbiteDeathEffects`：若 `deathTime > 0` 且该生物自己声明了 `tickDeath()` ⇒ 把 `deathTime` 交还（置 0） |
| `compat/ironspellbooks/CustomDeathSequence` | 判定"是否自己声明 `tickDeath()`"（反射 + `ClassValue` 缓存）+ 锚点日志节流 |

**判据**：

1. `deathTime > 0`（而不是 `== 1000`）——该方法在 `die()` 内由死亡事件触发，此刻原版尚未跑过
   `tickDeath()`，正常必然是 0；因此 TAIL 处 >0 只可能是上游刚写的值。上游改数字也不失效，
   日志会把实际值打出来。
2. "自己声明 `tickDeath()`"——沿继承链找第一个声明 `void tickDeath()` 的类，
   只要不是 `LivingEntity` 本身即成立。**与 modid 无关**，以后新装的模组一并覆盖。
   反射异常一律返回 `false`（不确定就不动别人的死亡状态）。

**为什么保留冰雕**：冰雕（`FrozenHumanoid`）是霜噬的招牌视觉；只把 `deathTime` 还回去，
原尸体按该模组自己的死亡演出走完再消失，活体逻辑与普通生物行为**一个字节都不改**。

**为什么不用白名单**：白名单每遇到一个新模组就要加一次；"覆写 `tickDeath`"这条判据
直接对应"死亡序列归它自己管"这一事实。

## 五、边界与已知取舍

- **不触碰**铁魔法 jar、祸乱鬼魅 jar、整合包；不加配置开关（委托方要求默认生效）。
- **不修**祸乱鬼魅的 `==70`：改它要复制收尾三件事（清 LIGHT 块 / 广播 60 / remove），
  且只救它自己；适合作为上游 issue。
- 覆写了 `tickDeath()` 却仍用原版 `>=20` 收尾的模组不受影响（最多按自己节奏消失）。
- 反之，**不覆写 `tickDeath()` 却仍用 `deathTime` 精确判等**的模组覆盖不到（目前未发现）。

## 六、升级必查项

- 上游改了 `handleFrostbiteDeathEffects` 的方法名/描述符，或不再写 `deathTime` ⇒
  本 Mixin（`require = 0`）静默失效。复核：`javap -p -c` 打铁魔法 jar 的 `FrostbiteEffect`，
  确认该方法里仍有 `PUTFIELD net/minecraft/world/entity/LivingEntity.deathTime : I`。
- 可察觉性：锚点 `[BeLoong] frostbite-death: restored custom death sequence for <type>`。
  它只在修复真正动手时输出；**完全缺席 = 注入没挂上**。

## 七、验证策略

1. 静态：`build`（0 警告为基线）。
2. 产物：解析 jar 内 `beloong.mixins.json` 含 `ironsspellbooks.FrostbiteDeathSequenceMixin`，
   两个新类在包内。
3. 启动门：`runClient` 后 `debug.log` 出现
   `Mixing ironsspellbooks.FrostbiteDeathSequenceMixin … into io.redspace.ironsspellbooks.effect.FrostbiteEffect`；
   `crash-reports` 无新增。
4. 实机验收（委托方）：见配套 `-plan.md` 的 T1–T4。


---

## 收尾（2026-11-06）：验收结论 + 后来加的 COMMON 开关

> **本节取代** §一 的"状态"描述与 §五「边界」里"不加配置开关"的表述。

**验收**：委托方实机通过 —— 霜噬 + 雪球术 + 领域内击杀不再产生假死尸体。

**配置开关（2026-11-06 新增，委托方要求便于对照测试）**：
`Config.COMMON` 的平铺键 **`fixFrostbiteDeath`**（默认 `true`），翻译键
`beloong.configuration.fixFrostbiteDeath[.tooltip]`（中英各一）。
关闭后完全恢复上游行为（`deathTime = 1000` 原样写入），用于复现假死并验证
`/beloong cleandead` —— 见 `2026-11-05-clean-dead-command-design.md`。

实现形态由另一会话按项目既有 flat 风格定稿：顶层
`public static final ModConfigSpec.BooleanValue FIX_FROSTBITE_DEATH`，与
`FIX_CATACLYSM_STRUCTURE_HEIGHT` / `FIX_FSWEEP_CONTAINER_CRASH` 同款；消费方
`FrostbiteDeathSequenceMixin` 每次死亡读一次。

⚠️ NeoForge 会把旧配置文件判为 "not correct. Correcting…" 并**自动重写**：新增键补齐、
已删除的节消失（实测 `run/config/beloong-common.toml` 里上一版的 `[frostbite_death_fix]` 段
在 2026-11-06 那次启动后被丢弃）⇒ 升级不需要手工迁移配置。
