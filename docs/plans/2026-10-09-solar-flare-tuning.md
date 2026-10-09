# 太阳耀斑数值调整（范围 / 基准伤害）

**日期：** 2026-10-09
**状态：** ✅ 已实施（提交 `653c857`）；实机待你验收
**需求（用户原话）：** 「太阳耀斑初始伤害太低，并且攻击距离太短」→ 定值 **距离 9 格、基准伤害 4 点、其他不变**

---

## 一、机制基线（Mowzie 1.8.2 源码 + 字节码实测）

| 项 | 原版事实 |
|---|---|
| 触发 | **主手必须空** + 有「太阳祝福」效果 + **Shift + 左键**（对空/对实体都可）|
| 段时序 | STARTUP **12t** → ACTIVE **瞬时** → RECOVERY **18t**（共 1.5 s）；**冷却 = 0**（`PlayerAbility(...,sectionTrack)` → `this(...,0)`）|
| 伤害 | `float damage = 2.0F;` × `ConfigHandler...SUNS_BLESSING.sunsBlessingAttackMultiplier`（**四招共享**该倍率）|
| 范围 | `float radius = 3.2F;` —— **硬编码、无配置项**；同时用于 AABB `inflate(3.2,3.2,3.2)` 与 `distanceTo(e) <= 3.2`，以**玩家脚底位置**为中心 ⇒ 对高大 Boss 极不友好 |
| 击退 | `knockback = 3.0F` ⇒ 实际 `push(dir × 1.8, 0.1, ...)`（仅当 `hurt` 返回 true）|
| 消耗 | 耀斑**不消耗**祝福时长（只有射线 5 分钟 / 超新星 60 分钟）|
| 副作用 | 前 16t 施加减速（amplifier 2）；`ENTITY_UMVUTHI_BURST` 音效；`solar_flare` 动画；客户端粒子 |

**关键环境事实**：`suns_blessing_attack_multiplier` 在你整合包/服务端是 **10.0**、在开发环境是默认 **1.0** ⇒ 同一次改动在两个环境里的实际伤害差 10 倍（详见 §四）。

---

## 二、本次定值

| 项 | 原版 | 现在 | 实现方式 |
|---|---|---|---|
| 作用半径 | 3.2 格 | **9.0 格** | `@ModifyConstant(floatValue = 3.2F)`（字节码实测：`beginSection` 内 `ldc 3.2f` 恰好 1 次，改一处即 AABB + 距离判定同时生效）|
| 基准伤害 | 2.0 | **4.0** | `@ModifyArg(index = 1)` 把 `hurt` 收到的伤害（= 基准 × 配置倍率）**×2** ⇒ 等效"基准 4.0 × 配置倍率" |
| 击退 / 段时序 / 减速 / 音效动画 | — | **不变** | — |
| Mowzie 配置 | — | **不改** | 保持四招共享倍率的语义，避免与整合包已有的 ×10 重复叠加 |

**为什么伤害也改成 `@ModifyConstant`（代码审查后的修正）**：初版实现是"把已乘配置倍率的伤害 ×2"，
代码审查指出这是**隐式假设**——上游若把基准从 `2.0F` 改成 `2.5F`，注入照常成功、实际基准静默变成 5.0（比设计值高 25%），
启动期毫无提示。现已与半径一样改为**锚定字面量**：`@ModifyConstant(floatValue = 2.0F)` → 4.0F。
<br>`2.0F` 在字节码里确实是 **`fconst_2`**（不进常量池），但 Mixin **可以**匹配常量指令：
`BeforeConstant` javadoc 写明 "searches for `LDC` **and other constant opcodes**"，实现走
`Bytecode.isConstant(insn)`（`Bytecode.CONSTANTS_ALL`）。所以那不构成"不能用 @ModifyConstant"的理由
（本文件初版断言"常量匹配不可靠"，属判断错误，已更正）。
⇒ 现在上游改基准或半径都会**启动期硬失败**（`injectors.defaultRequire = 1`），不再有静默漂移。

**数值集中在这两行**（后续要调只改这里）：
`src/main/java/com/zonlong/beloong/mixin/mowziesmobs/SolarFlareAbilityTuningMixin.java`
```java
private static final float BELOONG_FLARE_RADIUS = 9.0F;
private static final float BELOONG_FLARE_DAMAGE_FACTOR = 2.0F;
```

---

## 三、影响面与副作用（需知悉）

1. **9 格的准确形状**：`radius` 这同一个局部量被传了 4 次，落地为 `getEntityLivingBaseNearby(user, 9, 9, 9, 9)` ⇒
   ① 候选域 = `player.getBoundingBox().inflate(9,9,9)`（约 **18×18×18 的立方体**）；
   ② 再按 `player.distanceTo(e) <= 9` 过滤，而 `distanceTo` 是**以玩家脚底为球心的 3D 欧氏距离** ⇒
   对高大 Boss 而言**有效水平距离 < 9 格**（Y 分量占掉额度），地下/天上的目标会被 ② 滤掉。
2. **友军/宠物/村民会被一起打**（**已接受的设计行为，不是缺陷**）：Mowzie 的循环只排除施法者自己
   （`if (aHit != this.getUser())`），**没有队伍过滤** ⇒ 9 格内的队友、宠物、村民、被动生物都会被命中并被击退 1.8 格。
   原版 3.2 格时几乎察觉不到。要收窄改 `SolarFlareAbilityTuningMixin.BELOONG_FLARE_RADIUS` 即可
   （当前按用户要求硬编码、无配置开关；需要时随时可提升为 COMMON 配置）。
3. **与破防功能的叠加**：伤害类型仍被换成 `mowziesmobs:solar_flare`（既有 `SolarFlareAbilitySolarDamageMixin` 未动），所以对冥界骑士**依旧能穿盾 + 扣 1 层**；本次只是把数值调大。
3. **配置倍率照旧生效**（这是刻意的）：整合包 ×10 时耀斑 = **约 40/次**（原 20）；开发环境 ×1 时 = **4/次**（原 2）。若你觉得整合包里 40 偏高，调 Mowzie 的 `suns_blessing_attack_multiplier` 即可（会同时影响另外三招）。
4. 击退判定依赖 `hurt` 的返回值 ⇒ 被无敌帧挡下（`amount <= lastHurt`）时既不结算伤害也不击退，与原版行为一致。

---

## 四、验证

**静态（已完成）**：`gradlew build --offline` SUCCESSFUL；jar 内含 `SolarFlareAbilityTuningMixin.class`；`beloong.mixins.json` 注册齐全（`SolarFlareAbilitySolarDamageMixin` + `SolarFlareAbilityTuningMixin`）；注入点已用 `javap -c` 前置核对（`ldc 3.2f` ×1、`hurt` 的伤害走 `flocal 7` = index 1）。

**实机（待你执行）**：
1. 日志应出现 `Mixing mowziesmobs.SolarFlareAbilityTuningMixin … into …SolarFlareAbility`（首次使用耀斑时）；
2. 用**计数式锚点**直接读伤害：`…#N source=mowziesmobs:solar_flare … hp=before->after` ⇒ 差值应约等于**原版 2 倍**（dev ×1 ⇒ ~4；整合包 ×10 ⇒ ~40，再经护甲/抗性削减）；
3. 距离：站在离目标 **6~9 格**处按耀斑应能命中（原版必须 ~3 格内）。

**回退**：把两个常量改回（9.0→3.2、2.0→1.0）即可完全恢复原版数值；或删除 `SolarFlareAbilityTuningMixin` 的注册项。
