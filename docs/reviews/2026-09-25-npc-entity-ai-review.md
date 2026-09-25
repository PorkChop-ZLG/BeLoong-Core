# NPC 实体与 AI 代码审查

> 审查范围：`entity/NpcEntity.java`(373)、`entity/DihuangLoongEntity.java`(49)、`entity/ai/NpcAttackGoal.java`(47)、
> `command/NpcCommand.java`(121)、`registry/ModEntities.java`（仅 `DIHUANG_LOONG` 段）、`registry/ModAttributes.java`（全文）
> 审查基线：`24bb701`（分支 `NPC`）
> 审查方式：只读静态审查（未运行游戏、未跑构建、未修改任何文件）
> 原版源码来源：`~/.gradle/caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_0fdaf824..._output.jar`
> （`SharedConstants.VERSION_STRING = "1.21.1"`、`WORLD_VERSION = 3955`，NeoForge patched）
> 原版数据来源：`~/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar`
> 参考库：`D:\Minecraft\开源模组参考文件\Geckolib`（HEAD `0d9d3ea3`，前一个提交 `d57fc640 Update to 4.9.2` ⇒ 4.9.2）
> Brigadier：`~/.gradle/caches/modules-2/files-2.1/com.mojang/brigadier/1.3.10/...-sources.jar`

---

## 结论

整体质量**明显高于常见模组代码**：定义性语义（无敌 / 不可推动 / 不消失）都用覆写方法而不是构造函数 `setXxx`，
原版行号引用**绝大多数精确到行且正确**（我抽查 25 处，21 处完全正确，4 处错行号），
「不覆写 `tickHeadTurn`」「不覆写 `isNoAi()`」「`ATTACK_DAMAGE` 必须显式 add」三条核心判断经核实全部成立。

**最值得修的三件事**：

1. **`C1`（严重）**：`NpcAttackGoal.stop()` → `clearAttackCommand()` → `getNavigation().stop()` 会把
   **同一时刻刚下达的 `walkTo()` 路径一并取消**，导致「先下令攻击、再下令行走」时 walk 静默失效。这是当前唯一的功能性缺陷。
2. **`M1`（重要）**：`/summon` 出来之后 `invulnerable` 字段是 **false**（`Entity#load` 无条件覆盖），
   于是 `isInvulnerable()` 返回 false ⇒ `LivingEntity#canBeSeenAsEnemy()` 为 true ⇒ **原版敌对生物会把 NPC 当合法目标追击和攻击**
   （虽然打不动）。「构造函数里的 set 只为让字段与 NBT 一致」这个理由在 `/summon` 路径上并不成立。
3. **`M2`/`M4`（重要）**：`LookAtPlayerGoal` 的 `lookTime` 实际是 **20–40 tick**（被 `adjustedTickDelay` 折半），
   注释与文档写的 40–80 是错的；以及 1.5×2.5 碰撞箱对**近战命中距离 / 右键交互 / 视锥剔除**的三处可用性后果需要写清。

---

## 严重问题（Critical）

### C1. 攻击→行走切换时，`walkTo()` 下的路径被攻击 goal 的 `stop()` 取消 —— `NpcAttackGoal.java:43-46` + `NpcEntity.java:287,326-330`

- **现象/代码**：

  ```java
  // NpcEntity.walkTo()  —— NpcEntity.java:280-288
  this.clearMotionCommands();          // getNavigation().stop()
  this.attackCommandActive = false;    // ← 先清标志位
  this.setTarget(null);
  this.getNavigation().moveTo(pos.x, pos.y, pos.z, 1.0D);   // ← 最后下达新路径（287）

  // NpcAttackGoal.stop() —— NpcAttackGoal.java:43-46
  super.stop();
  this.npc.clearAttackCommand();       // → setTarget(null) + getNavigation().stop()（NpcEntity.java:329）
  ```

- **证据（原版行为链）**：
  1. `GoalSelector#tick()` 的清理循环对**正在运行**的 goal 调用 `canContinueToUse()`，
     返回 false 即 `wrappedgoal.stop()`（`GoalSelector.java:87-91`）；`WrappedGoal#stop` 转发给 `Goal#stop`（`WrappedGoal.java:51-56`）。
  2. `NpcAttackGoal#canContinueToUse()` = `isAttackCommandActive() && super.canContinueToUse()`（`NpcAttackGoal.java:38-40`），
     `walkTo()` 已把标志位清掉 ⇒ 它在下一次仲裁里必然被判 false。
  3. 仲裁的频率是**每 2 tick 一次**（`Mob#serverAiStep` 里 `i % 2 != 0 && tickCount > 1` 走 `tickRunningGoals(false)`，
     否则走完整 `goalSelector.tick()`，`Mob.java:779-793`）——所以最多 2 tick 后 `stop()` 一定会跑。
  4. **先后关系是确定的**：清理只能"观察到标志位被清掉之后"才触发，因此 `getNavigation().stop()` **永远晚于**
     `walkTo()` 的 `moveTo(...)`；而 `walkTo` 只调用一次、`PathNavigation` 被 `stop()` 后不会自己恢复路径
     （`PathNavigation#stop()` 清 path；`ServerEntity`/`GoalSelector` 都不会替它重发）。
  5. 触发前提是"攻击 goal **当时正在运行**"。若只是 `attack()` 过但 goal 因 20 tick 限流/无路径从未 start，
     则不会有 `stop()`，walk 反而是正常的 —— 所以症状是**间歇性的**，极难复现定位。

- **影响**：`/beloong npc attack <victim>` 之后紧跟 `/beloong npc walk <pos>`（或将来的脚本 / 对话条件做同样的事）
  → NPC **原地不动、无任何报错、无日志**。设计文档 §3.7 明确把 `walkTo()` 定义成"会清掉攻击指令"的支持路径，
  所以这是支持路径上的功能完全失效。

- **建议修法**：把"清状态"与"停导航"拆开，`NpcAttackGoal.stop()` 只清状态。

  ```java
  /** 只清指令状态；不动导航（导航的停止由 MeleeAttackGoal.stop() / 调用方负责）。 */
  public void clearAttackCommand() {
      this.attackCommandActive = false;
      this.setTarget(null);
      // 不再调用 getNavigation().stop()
  }
  ```
  安全性检查：`MeleeAttackGoal.stop()` 自己已经 `getNavigation().stop()`（`MeleeAttackGoal.java:94`），
  所以"攻击结束顺便停导航"这个目标仍然满足；`customServerAiStep()` 兜底路径里 goal 从未启动过 ⇒ 导航本来也不属于攻击 goal
  （`walkTo()` 会清攻击指令，两者不可能共存），去掉 `nav.stop()` 不会留下残留路径。

---

## 重要问题（Major）

### M1. `/summon` 后 `isInvulnerable()` 为 false，NPC 会被原版敌对生物索敌 —— `NpcEntity.java:167-172,190-193`

- **现象/代码**：构造函数里 `setInvulnerable(true)`（170）与 `isInvulnerableTo` 覆写（190-193）并存。
- **证据**：
  - `/summon` 走 `SummonCommand.createEntity` → `EntityType.loadEntityRecursive`（`SummonCommand.java:86`）
    → `EntityType.create(CompoundTag, Level)`：**先 `create(level)`（构造函数在此运行），再 `load(tag)`**（`EntityType.java:1103-1109`）。
  - `Entity#load` 里 `this.invulnerable = compound.getBoolean("Invulnerable");` **没有 `contains` 守卫**（`Entity.java:1854`）
    ⇒ 标签里没有该键时字段被覆盖成 **false**。
  - 因此 `/summon beloong:dihuang_loong ...` 之后 `isInvulnerable()` == false（`Entity.java:2689-2691`）。
  - 而 `isInvulnerable()` 被 `LivingEntity#canBeSeenAsEnemy()` 读取：`!this.isInvulnerable() && this.canBeSeenByAnyone()`
    （`LivingEntity.java:907-908`），后者又用于
    `TargetingConditions#test` 的战斗分支（`TargetingConditions.java:69`）、
    `LivingEntity#canAttack`（`LivingEntity.java:899-901`）、`Warden.java:405`、`EnderDragon.java:983`。
- **影响**：召唤出来的 NPC 对**原版敌对生物是"可攻击的合法目标"** —— 僵尸/骷髅会围上来打（造成 0 伤害但仍然
  pathfind + 挥击 + 卡位），Warden 也会把它算进可攻击目标。这与"演出 NPC、怪物不该围着它转"的定位相冲突；
  更麻烦的是**同一份代码在不同创建路径下行为不同**：不经过 `load()` 创建的实例（别的模组 `EntityType.create(level)`）
  字段是 true ⇒ 怪物不理它。注释第 48 行"构造函数里的 `setXxx` 仍然保留，只为让字段本身与保存出的 NBT 一致"
  在 `/summon` 路径上其实**不成立**（写出的 NBT 是 `Invulnerable:0b`）。
- **建议修法**（三选一，推荐第 2 个）：
  1. 接受现状，把类注释改成"字段在 `/summon` 后为 false，语义只看 `isInvulnerableTo` 覆写"，并明确
     "NPC 会被原版怪物索敌"是已知后果；
  2. 在 `NpcEntity` 覆写 `readAdditionalSaveData`，在 `super` 之后重新 `setInvulnerable(true)`，
     让字段/NBT 与"绝对无敌"语义一致（副作用：`canBeSeenAsEnemy()` 变 false ⇒ 怪物不再索敌，正是"布景 NPC"想要的）；
  3. 干脆删掉构造函数里的 `setInvulnerable(true)`，承认字段无意义 —— 但那样 NBT 会一直是 `Invulnerable:0b`，
     第三方模组读 NBT 时看到的是"可被伤害"。

### M2. `LookAtPlayerGoal` 的 `lookTime` 实际是 20–40 tick，不是 40–80 —— `NpcEntity.java:245-247`

- **现象/代码**：`// lookTime 40~80 tick 一轮、到期立刻重启 ⇒ 实际是持续跟随。`
- **证据**：`LookAtPlayerGoal#start()` 写的是 `this.lookTime = this.adjustedTickDelay(40 + this.mob.getRandom().nextInt(40));`
  （`LookAtPlayerGoal.java:93`）。而 `LookAtPlayerGoal` **没有**覆写 `requiresUpdateEveryTick()`（`Goal.java:25-27` 默认 false），
  所以 `Goal#adjustedTickDelay` 会走 `reducedTickDelay` = `Mth.positiveCeilDiv(adjustment, 2)`（`Goal.java:46-51`）
  ⇒ 原式 40–79 被**折半**为 **20–40 tick**（1–2 秒）。文档 §3.3 与决策记录 17 写了同样的 40~80，两处一起错。
- **影响**：观感上"注视窗口"比设计预期短一半（每秒都要重新掷一次 `probability`，本例 `probability=1.0F` 所以必中，
  但 `stop()`（`LookAtPlayerGoal.java:97-99`）会把 `lookAt` 置空、下一 tick 才重新取最近玩家 ⇒ 每次一轮结束有 1 tick 的无目标窗口，
  配合 `LookControl#lookAtCooldown` 的 2 tick 复位置位，会出现轻微的"点头"式停顿）。"到期立刻重启 ⇒ 持续跟随"的结论**成立**
  （`canUse()` 里 `nextFloat() >= 1.0F` 恒为 false，同一 tick 的清理之后就是仲裁，`GoalSelector.java:83-113`）。
- **建议修法**：注释与文档改成"源码值 40–79，经 `adjustedTickDelay` 折半后实际 20–40 tick"。
  若希望真的是 40–80，得自己覆写 `requiresUpdateEveryTick()` 返回 true（但那样 `tick()` 会每 tick 都调 `setLookAt`，
  与现在的行为差别不大，收益有限）。

### M3. 「不可推动」有两条真实缺口：爆炸位移 与 流体流 —— `NpcEntity.java:195-199` + `createNpcAttributes():157`

- **现象/代码**：`isPushable()` 恒 false + `KNOCKBACK_RESISTANCE = 1.0`，两者**不冗余**，但合起来仍不完整。

  | 路径 | 由谁挡住 | 证据 |
  |---|---|---|
  | 玩家/生物碰撞推挤 | `isPushable()` | `Entity#push(Entity)`：`if (!this.isVehicle() && this.isPushable()) this.push(...)`（`Entity.java:1548-1553`）；候选过滤 `EntitySelector#pushableBy` 也看 `isPushable()`（`EntitySelector.java:50-58`） |
  | 攻击击退 | `KNOCKBACK_RESISTANCE` | `LivingEntity#knockback`：`strength *= 1.0 - this.getAttributeValue(KNOCKBACK_RESISTANCE)`，为 0 时整段跳过（`LivingEntity.java:1529-1548`，关键行 1535-1536） |
  | **爆炸位移** | **两条都挡不住** | `Explosion#explode` 用的是**另一个属性**：`d10 = d13 * (1.0 - livingentity.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE))`（`Explosion.java:293-297`），随后 `entity.setDeltaMovement(entity.getDeltaMovement().add(vec31))`（`Explosion.java:304`）——**完全不经过 `isPushable()`**。而 `EXPLOSION_KNOCKBACK_RESISTANCE` 本模组没 `add`（`LivingEntity.createLivingAttributes` 已含该属性，默认 `0.0`，`Attributes.java:52-54`） |
  | **流体流推动** | **两条都挡不住** | `Entity#updateInWaterStateAndDoWaterCurrentPushing` → `interim.flowVector` 累加后直接 `this.setDeltaMovement(this.getDeltaMovement().add(interim.flowVector))`（`Entity.java:3358-3391`），门禁只有 `isPushedByFluid(fluidType)`（默认 true，`Entity.java:2847-2850`） |
  | 除碰撞外的 `Entity#push(d,d,d)` | 无门禁 | `Entity#push(double,double,double)` 只做 `setDeltaMovement(add)`（`Entity.java:1567-1570`） |

- **影响**：站桩 NPC 被爬行者/苦力怕爆炸、TNT、风弹推得满地跑；在水里会被水流带走。
  这与"不可推动"的语义直接冲突，实机只要在 NPC 旁边炸一次就能看到。
- **建议修法**：`createNpcAttributes()` 里补 `.add(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, 1.0D)`（一行，属性已在 supplier 里，
  只是值为 0）；若连水流也要挡，覆写 `isPushedByFluid()` 返回 false（比 `isPushable()` 更对症，且不影响其它路径）。
  已有的两条**都保留** —— 它们挡的是不同路径，不是冗余。

### M4. 碰撞箱 1.5×2.5 与模型（实测原始立方体 Z 跨度 **9.02 格**）不一致的四类后果 —— `ModEntities.java:70`

- **现象/代码**：`.sized(1.5F, 2.5F)`，注释说明"取身体主体段"。
- **本次实测**（脚本解析 `assets/beloong/geo/dihuang_loong.geo.json`，145 骨骼 / 340 立方体的 `origin`+`size` 并集，
  **未叠加骨骼变换**，只作量级参考）：X 2.01 格、Y 2.85 格、**Z 9.02 格**；模型自身声明的
  `visible_bounds_width/height = 12 / 5`。⇒ "约 9 格长"的说法准确。
- **后果与证据**：

  | 路径 | 后果 | 证据 |
  |---|---|---|
  | **近战命中** | 命中判据是"自身盒 inflate(`DEFAULT_ATTACK_REACH`) 与目标 hitbox 相交" | `Mob#isWithinMeleeAttackRange` = `getAttackBoundingBox().intersects(entity.getHitbox())`（`Mob.java:1465-1467`）、`getAttackBoundingBox()` = `getBoundingBox().inflate(DEFAULT_ATTACK_REACH, 0, DEFAULT_ATTACK_REACH)`（`Mob.java:1483-1487`），`DEFAULT_ATTACK_REACH = Math.sqrt(2.04F) - 0.6F ≈ 0.828`（`Mob.java:104`）⇒ **必须让实体中心贴到玩家约 1.9 格内**。头在原点前方约 5 格 ⇒ 视觉上"头早穿过去了才判命中" |
  | **右键 / 准星** | 只能点中"核心盒 + 0.3"（约 2.1×3.1），头/尾点不到 | `GameRenderer#pick`（`GameRenderer.java:813-834`）→ `ProjectileUtil#getEntityHitResult(...)` 6 参版（`ProjectileUtil.java:108-110`，inflation `0.3F`）→ `aabb.inflate(0.3)`（`ProjectileUtil.java:123`） |
  | **寻路** | 按 `getBbWidth() + 1 ≈ 2` 格宽做碰撞检测 ⇒ 长尾必然穿墙/穿模 | `NodeEvaluator#prepare`：`entityWidth = Mth.floor(mob.getBbWidth() + 1.0F)`（`NodeEvaluator.java:30-32`） |
  | **视锥剔除** | 几何超出 `getBoundingBoxForCulling()`（直接返回 1.5×2.5 盒，`Entity.java:3030-3032`），盒出屏时**整条龙一起消失** | `EntityRenderDispatcher#shouldRender` → `EntityRenderer#shouldRender` 用 `entity.getBoundingBoxForCulling().inflate(0.5)` 做 `Frustum` 判定（`EntityRenderDispatcher.java:137-140`） |

- **影响**：这是**真实的可用性问题**，不是风格问题：右键交互（对话系统一旦接入就依赖它）、近战演出、以及
  "头/尾在屏幕边缘时整条龙突然消失"都会被玩家直接看到。距离剔除不是问题
  （`Entity#shouldRenderAtSqrDistance` 用 `getBoundingBox().getSize() * 64` ⇒ 半径 ~160 格，`Entity.java:1692-1700`）。
- **建议修法**：不要试图把碰撞箱撑到 9 格（会让寻路彻底走不动）；
  按需分项处理：① 若对话要接 NPC，覆写 `getPickRadius()` 给一个较大的值（例 2.0–3.0，`Entity.java:2231-2233` 默认 0.0）；
  ② 近战若要"用头咬"，覆写 `Mob#getAttackBoundingBox()` 或 `isWithinMeleeAttackRange`（原版扩展点）；
  ③ 视锥剔除可覆写 `getBoundingBoxForCulling()`（`Entity.java:3030`）返回按 yRot 旋转后的粗包围盒。
  以上都是"改一处、不影响寻路"的局部手段。

### M5. 文档/注释对 `MobCategory.MISC` 的"持久"解释与源码不符 —— `ModEntities.java:60`

- **现象/代码**：`MobCategory.MISC = ("misc", -1, true, true, 128)`：**不占刷怪上限**且**持久**。
- **证据**：`MISC("misc", -1, true, true, 128)` 的 5 个参数是 `(name, max, isFriendly, isPersistent, despawnDistance)`
  （`MobCategory.java:15`、构造器 25-31）。其中 `isPersistent` **只被自然生成器使用**：
  `(forcedDespawn || !mobcategory.isPersistent())`（`NaturalSpawner.java:111`），
  即"这个类别默认不该被自然生成"。它**与单个实体的存续无关** ——
  实体的持久性来自 `Mob#isPersistenceRequired()`/`requiresCustomPersistence()`。
  "不占刷怪上限"部分成立但机制不同：`max = -1` 使 `counts < -1` 恒 false（`LocalMobCapCalculator.java:52`），
  实际效果是"永远不会被 `NaturalSpawner` 选中"。
- **影响**：读文档的人会以为"选 MISC 就自动持久了"，从而误删 `requiresCustomPersistence()` 覆写 ——
  那会立刻导致 NPC 在 128 格外被 `checkDespawn` discard（`Mob.java:749-762`）。
- **建议修法**：改成"`MISC` = 不参与自然生成、不计入刷怪上限；**实体的持久性由
  `requiresCustomPersistence()` 单独保证**（`MobCategory#isPersistent` 只影响自然生成器的类别选择）"。

### M6. `customServerAiStep()` 兜底在 `NoAI` 下失效 —— `NpcEntity.java:251-267`

- **现象/代码**：注释"（`Mob#serverAiStep` 内部调用，需要 AI 生效）"——作者已经意识到前提，但没有写出失效后果。
- **证据**：`LivingEntity#aiStep` 里 `serverAiStep()` 被两层守卫包住：
  `if (this.isImmobile()) { ... } else if (this.isEffectiveAi()) { ... this.serverAiStep(); }`（`LivingEntity.java:2761-2769`），
  而 `Mob#isEffectiveAi() = super.isEffectiveAi() && !this.isNoAi()`（`Mob.java:1419-1422`）。
  ⇒ `/data merge entity <uuid> {NoAI:1b}`（或任何模组设 NoAI）之后，`GoalSelector` 与 `customServerAiStep` **一起停摆**。
- **影响**：`attackCommandActive = true` 与 `getTarget()` 会一直挂着；但此时实体不跑 AI、不移动、不攻击，
  所以**不是永久故障**，恢复 AI 后的第一个 tick 就会被兜底或 goal 清理掉。属于"状态残留但无害"。
- **建议修法**：值得在注释里补一句"NoAI 期间兜底与 goal 同时停摆，状态残留但休眠；恢复 AI 后自动清理"。
  若想彻底，可在 `readAdditionalSaveData`/`setNoAi` 路径清理，但为这点收益增加钩子不划算。
  **另**：`customServerAiStep()` 没有调 `super.customServerAiStep()`（父实现目前为空，`Mob.java:818-819`），
  现在无害，但将来父类一旦有内容会被静默丢弃，建议补上。

---

## 次要问题（Minor）

### m1. `createMobAttributes()` 的属性清单描述错误 —— `NpcEntity.java:137-139`

注释与文档 §3.9 写"只含 MAX_HEALTH / KNOCKBACK_RESISTANCE / MOVEMENT_SPEED / ARMOR / ARMOR_TOUGHNESS"。
实际 `LivingEntity.createLivingAttributes()` 含 **20 项**（`LivingEntity.java:321-342`：MAX_HEALTH、KNOCKBACK_RESISTANCE、
MOVEMENT_SPEED、ARMOR、ARMOR_TOUGHNESS、MAX_ABSORPTION、STEP_HEIGHT、SCALE、GRAVITY、SAFE_FALL_DISTANCE、
FALL_DAMAGE_MULTIPLIER、JUMP_STRENGTH、OXYGEN_BONUS、BURNING_TIME、EXPLOSION_KNOCKBACK_RESISTANCE、
WATER_MOVEMENT_EFFICIENCY、MOVEMENT_EFFICIENCY、ATTACK_KNOCKBACK、SWIM_SPEED、NAMETAG_DISTANCE），
`Mob.createMobAttributes()` 再加 FOLLOW_RANGE（`Mob.java:159-161`）。
**关键结论（不含 ATTACK_DAMAGE）正确**，"必须显式 add"也正确：不 add 时
`LivingEntity#getAttributeValue(ATTACK_DAMAGE)` → `AttributeMap#getValue`（`AttributeMap.java:64-66`）
落到 `AttributeSupplier#getValue` → `getAttributeInstance` 抛
`IllegalArgumentException("Can't find attribute generic.attack_damage")`（`AttributeSupplier.java:17-24`），
即**第一次出手就崩**（`Mob#doHurtTarget` 首行就读它，`Mob.java:1491-1492`）；`Attributes.ATTACK_DAMAGE` 自身默认值是 `2.0`
（`Attributes.java:27-29`）。建议把清单改成"不含 `ATTACK_DAMAGE`（原版由 `Monster.createMonsterAttributes()` 加，`Monster.java:127-129`）"。

### m2. `npcsIn()` 会**静默丢弃**混合选择里的非 NPC —— `NpcCommand.java:115-120`（及类注释 24 行）

`npcsIn` 只做 `filter(NpcEntity.class::isInstance)`，只有**全部选中项都不是 NPC** 时才走 `fail()`。
`/beloong npc walk @e[type=!beloong:dihuang_loong] ...` 会明确报错，但
`/beloong npc walk @e ...`（10 只僵尸 + 1 只 NPC）会**静默只操作那 1 只并回报数量 1**。
这与类注释"目标过滤 `NpcEntity`"以及文档 §6"选到的不是 NPC 时**明确报错**，不静默"的口径不符。
建议要么改成"只要有非 NPC 入选就 `sendFailure` 列出被跳过的数量"，要么把注释与文档改成"非 NPC 项被静默忽略"。

### m3. `clearAttackCommand()` 的 `public` 暴露面偏大 —— `NpcEntity.java:325-330`

它的名字读起来像"清一下标志位"，实际会 `setTarget(null)` **并停导航**（也正因为如此才有 C1）。
`NpcEntity` 已经是抽象基类、`NpcAttackGoal` 也在同一 mod 内，改成包内可见/`protected` 或者在 Javadoc 里写明
"会停导航、会清 target，混用 `walkTo()` 时必须注意"都能降低误用面。对外接口"越小越好"这条原则本类别处（`facePlayerDistance`）
已经贯彻了，这里不彻底。

### m4. 文档引用的原版行号有 4 处错误（详见文末核对表）

`Entity.java:1759`（写 Invulnerable 的位置）、`Mob.java:437`（`PersistenceRequired` 的**读**位置）、
`LivingEntity.java:1084`（`hurt` 第一道闸门）、`Entity.java:2681-2686`（应为 2681-2687，`!source.isCreativePlayer()` 在 2683）。
含义方向都对，但既然项目约定"结论要带证据"，行号错会让下一个人核实失败。

### m5. GeckoLib `isMoving()` 的判据表述不精确 —— `NpcEntity.java:350`

GeckoLib 4.9.2 实际是
`avgVelocity = (|v.x| + |v.z|) / 2`，`isMoving = avgVelocity >= getMotionAnimThreshold(animatable) && limbSwingAmount != 0`
（`GeoEntityRenderer.java:266-268`），阈值默认 `0.015f`（`GeoRenderer.java:118`）。
注意三点：① 它不是水平速度 `sqrt(x²+z²)`，而是两个分量绝对值的**平均**（斜向移动时比真实速度小约 30%）；
② `limbSwingAmount` 只在 `!shouldSit && animatable.isAlive() && livingEntity != null` 时才从
`livingEntity.walkAnimation.speed(partialTick)` 取值，否则恒 0（`GeoEntityRenderer.java:246-261`）⇒ 死亡瞬间一定回 idle；
③ 它是**渲染期（客户端）**求值，`getDeltaMovement()` 依赖 `ClientboundSetEntityMotionPacket`，而该包在
`ServerEntity#sendChanges` 里被 `tickCount % updateInterval == 0 || hasImpulse || entityData.isDirty` 的闸门包着
（`ServerEntity.java:121`、`174-193`）。本实体类型没调 `updateInterval(...)` ⇒ 默认 `3`
（`EntityType.Builder`：`private int updateInterval = 3`，`EntityType.java:1257`）⇒ **最多 3 tick 的滞后**。
所以"实体在动但客户端 `walkAnimation`/`deltaMovement` 还没更新 ⇒ 播 idle"的时序窗口**确实存在但只有 1–3 tick**（<150ms），
实机看不出来。建议注释补一句"判据是分量绝对值的平均、阈值 0.015、最多有约 `updateInterval` 的同步滞后"。

### m6. `attackCommandActive` 不持久化（结论：可接受，但应写进文档）—— `NpcEntity.java:165`

全 mod 内**没有**任何 `addAdditionalSaveData`/`readAdditionalSaveData` 覆写（已 grep 确认，只有 `TornadoEntity` 有）。
区块卸载→重载后 `attackCommandActive` 回落 false、`target` 丢失（`Mob#target` 本身也不是持久字段），
NPC 回到站桩。在"能力由外部驱动"的设计下**可接受**（重载后本来就该等外部再下令），
但 `/beloong npc attack` 后玩家跑远再回来会发现 NPC 不再追 —— 建议在 §3.7 明确写"指令状态不持久化"。
不持久化还有一个隐性好处：不会把"已死的 UUID 目标"写进存档。

### m7. Brigadier 对同名根字面量的**静默合并**是潜在特权风险 —— `NpcCommand.java:40-41`

`CommandDispatcher#register` 只是 `root.addChild(build)`（`CommandDispatcher.java:98-102`），
`CommandNode#addChild` 发现同名子节点时**直接合并**：保留**先注册者**的 `requirement`，
只把后来者的 children 嫁接进来、后来者的 `command` 覆盖上去（`CommandNode.java:68-90`）。
⇒ 若将来别处（另一个类、另一个模组 ID 在同一 `beloong` 命名空间下）以更低的 `requires` 先注册 `beloong`，
则 `/beloong npc *` 的 op 要求会**被丢掉**（或者反过来：本类先注册则后来者的低权限子命令也被强制 op）。
当前全 mod 只有 `NpcCommand.java:40` 一处注册 `beloong`（已 grep 确认，`BeLoongCore.java:207` 是唯一调用点），
所以**不是现行缺陷**，但注释可以记一笔"`beloong` 根命令只能有一处注册"。

---

## 建议（Nit）

- `N1`：`NpcCommand` 的 `sendSuccess(..., true)` **语义正确、无需改**。`true` 走
  `CommandSourceStack#broadcastToAdmins`（`CommandSourceStack.java:489-490`、495-508）：
  只发给**其它 op**、包成 `chat.type.admin` 灰斜体、且受 `sendCommandFeedback` / `logAdminCommands` 两个 gamerule 控制。
  这是原版惯例（`/summon` 同样用 `true`）。"会给所有 op 刷屏"是事实，但不是缺陷。
- `N2`：`Vec3Argument.vec3()` **不限制绝对坐标**：它唯一的参数是 `centerCorrect`
  （`Vec3Argument.java:26-36`），`~` 与 `^` 都允许，`getVec3` 相对**命令源**解析（`Vec3Argument.java:38-40`）。
  所以 `/beloong npc walk @e[type=beloong:dihuang_loong] ~ ~ ~8` 是以**执行者**为原点的相对坐标 ——
  文档验收清单第 1 项"与玩家并排同向走"依赖的正是这个语义，没问题；但值得在命令 Javadoc 里写清"相对的是执行者，不是 NPC"。
- `N3`：`registerControllers` 里三档判据用 `>` 而不是 `!=` 是**正确的选择**：缓慢/负面 modifier 会让
  `值 < 基础值`，用 `!=` 会误判成 run；用 `>` 则"只有加成才跑"，与"跑 = 有额外移速加成"的口径一致。
  唯一不严谨的地方是**净效果恰好等于基础值**的 modifier 组合（例如 +0.03 与 −0.03 相抵）会漏判。
  若要把口径做严，判据应改成 `npc.getAttribute(Attributes.MOVEMENT_SPEED).getModifiers()` 非空
  （modifier 集合随 `ClientboundUpdateAttributesPacket` 同步，客户端可读），而不是浮点比较。
  浮点比较本身在这里是**可靠的**：`AttributeInstance#getValue` 是对基础值+modifier 的有序求和，
  无 modifier 时逐位等于 `getBaseValue()`，不存在"浮点误差导致误判"的场景。
- `N4`：`walkTo()` 固定档位 `1.0D`（`NpcEntity.java:287`）与文档 §3.5 一致；`MeleeAttackGoal(1.0)` 这个"原版惯例"的举例正确
  （`NpcEntity.java:244`），`FollowParentGoal`/`TemptGoal` 的 1.25 与 `PanicGoal` 的 2.0 我**没有逐行核实**（见"无法静态确认"）。
- `N5`：`DihuangLoongEntity.createAttributes()` 直接转发基类（`DihuangLoongEntity.java:46-48`），
  保留它作为"子类如何追加属性"活样例的理由成立；`AttributeSupplier.Builder#build()` 每次 `createAttributes()` 都是全新
  Builder（`LivingEntity.createLivingAttributes` → `AttributeSupplier.builder()`，`LivingEntity.java:321-322`），
  所以**不存在"调用两次出问题"**：`build()` 只是把 Builder 冻结并返回 `ImmutableMap.copyOf` 快照
  （`AttributeSupplier.java:109-113`），多次调用互不污染。
  真正会炸的是 `EntityAttributeCreationEvent#put` 对同一实体类型**调用两次**（第二次抛 `IllegalStateException`，
  `EntityAttributeCreationEvent.java:30-33`）—— 本类只 `put` 一次（`ModAttributes.java:98`），没问题。
- `N6`：`ModAttributes` 的 `@EventBusSubscriber(modid="beloong")` 不带 `bus` 是**正确写法**：
  NeoForge 1.21.1 已按监听器的实参类型自动分派（`IModBusEvent` → mod bus，其余 → 游戏 bus），
  `bus()` 字段被标注 `@Deprecated(forRemoval)` 且**被忽略**
  （`EventBusSubscriber.java:39-43`、`64-71`）。所以 `EntityAttributeCreationEvent` /
  `EntityAttributeModificationEvent`（两者都 `implements IModBusEvent`）都能收到。
- `N7`：`NpcAttackGoal.canUse()` 的 `标志位 && super.canUse()` 顺序**优于**反过来：
  `MeleeAttackGoal#canUse` 首行是 20 tick 限流的 `lastCanUseCheck` 写入（`MeleeAttackGoal.java:36-40`），
  标志位为 false 时短路 ⇒ **不会**白白刷新限流时间戳。副作用是"20 tick 限流只在真的有攻击指令时才推进"，
  这让"下令后最多等 1 秒才起步"（`NpcAttackGoal.java:20-21`）实际上**只在连续两次下令之间**成立；
  长期空闲后的第一道命令是**立刻**生效的。建议把这句注释改成这个更准确的说法。

---

## 原版行号核对表

| 注释/文档中的引用 | 实际位置（本次核实） | 是否正确 |
|---|---|---|
| `Entity.java:1759`（`load()` 读回 `Invulnerable`，NpcEntity.java:46、文档 §3.2/决策 4） | **`Entity.java:1854`**（`this.invulnerable = compound.getBoolean("Invulnerable");`，无 `contains` 守卫）；**1752** 是写出；1759 是空行 | ❌ 行号错 |
| `Mob.java:437`（`load()` 读回 `PersistenceRequired`，NpcEntity.java:46-47） | **`Mob.java:461`**（`this.persistenceRequired = compound.getBoolean("PersistenceRequired");`，无守卫）；**392** 是写出；437 是 `DeathLootTable` 的写出 | ❌ 行号错 |
| `LivingEntity.java:1084`（`hurt` 的第一道闸门，NpcEntity.java:178、文档 §3.2、决策 4） | **`LivingEntity.java:1142-1143`**（`public boolean hurt(...)` / `if (this.isInvulnerableTo(source)) return false;`） | ❌ 行号错 |
| `Entity.java:2681-2686`（原版 `isInvulnerableTo`，文档 §1.3、决策 4） | 方法体 **2681-2687**（2681 声明、2686 `CommonHooks.isEntityInvulnerableTo`、2687 `}`）；`&& !source.isCreativePlayer()` 在 **2683** | ⚠️ 范围略窄，内容正确 |
| `Mob.java:736`（`requiresCustomPersistence` 定义） | `Mob.java:736-738`（`return this.isPassenger();`） | ✅ |
| `Mob.java:746-749` / `Mob.java:749`（`checkDespawn` 闸门） | `Mob.java:745` 方法、**746** 是 NeoForge `EventHooks.checkMobDespawn`、**749** 是 `!isPersistenceRequired() && !requiresCustomPersistence()` | ✅（749 准确） |
| `Mob.java:557-560`（`setSpeed` 同时写 `zza`） | `Mob.java:556-560`（`super.setSpeed(speed); this.setZza(speed);`） | ✅ |
| `Mob.java:377-381`（覆写 `tickHeadTurn` 且不调 super） | `Mob.java:377-381`（`this.bodyRotationControl.clientTick(); return animStep;`） | ✅ |
| `Entity.java:3215-3217`（`isControlledByLocalInstance`） | `Entity.java:3215-3217` | ✅ |
| `Mob.java:1420`（`isEffectiveAi`） | `Mob.java:1419-1422` | ✅ |
| `LivingEntity.java:2219-2220`（`travel` 整体被 `isControlledByLocalInstance()` 包住） | `LivingEntity.java:2219-2220` 声明+`if`；`if` 体在 **2344** 闭合，`calculateEntityAnimation` 在包外（2346） | ✅（"整个方法体"严格说是"除最后一行动画计算外"） |
| `Player.java:231`（玩家默认移速属性 0.1） | `Player.java:231`：`.add(Attributes.MOVEMENT_SPEED, 0.1F)` | ✅ |
| `Player.java:1613-1616`（`Player#getSpeed`） | `Player.java:1613-1616` | ✅ |
| `PathNavigation#doStuckDetection:312`（显式平方速度） | `PathNavigation.java:310-313`，平方在 **312**，`>= 1.0F` 守卫也在 312 | ✅ |
| `MobEffect#addAttributeModifiers:166-174` | `MobEffect.java:166-174` | ✅ |
| `LivingEntity#getFrictionInfluencedSpeed:2428-2430` | `LivingEntity.java:2428-2430` | ✅ |
| `MoveControl.java:100`（`setSpeed(speedModifier × 属性)`） | `MoveControl.java:100` | ✅ |
| `Entity#getInputVector:1397-1412` | `Entity.java:1397` 是 `moveRelative`；真正的 `getInputVector` 在 **1402-1412**（`(d0 > 1.0 ? normalize : relative).scale(motionScaler)` 在 1407） | ⚠️ 范围包含了调用者，核心在 1402-1412 |
| `LocalPlayer.java:691` / `KeyboardInput.java:15-21` | `LocalPlayer.java:691`：`this.zza = this.input.forwardImpulse;`；`KeyboardInput.java:15-21`：`calculateImpulse` 返回 ±1.0/0.0 | ✅ |
| `Warden:555` / `Bat:85` / `Parrot:392`（`isPushable` 先例） | 三处全部逐字命中 | ✅ |
| `AbstractFish:45` / `Axolotl:423` / `Raider:248` / `EnderMan:434`（`requiresCustomPersistence` 先例） | 四处全部逐字命中 | ✅ |
| `Phantom:63` / `Armadillo:392` / `Camel:635` / `Shulker:146`（`createBodyControl` 先例） | 四处全部逐字命中 | ✅ |
| `Mob#createBodyControl:196` | `Mob.java:196-198` | ✅ |
| `GeoEntityRenderer.java:266-268`（`isMoving` 判据） | `GeoEntityRenderer.java:266-268`（`avgVelocity = (|x|+|z|)/2`，`>= motionThreshold && limbSwingAmount != 0`）；阈值 `GeoRenderer.java:118` = `0.015f` | ✅（但判据表述需按 m5 修正） |
| `MobCategory.MISC = ("misc", -1, true, true, 128)` | `MobCategory.java:15`（参数序 `name,max,isFriendly,isPersistent,despawnDistance`，构造器 25-31） | ✅ 字面正确 / ❌ 解释错（见 M5） |
| `Monster.createMonsterAttributes()` 才加 `ATTACK_DAMAGE` | `Monster.java:127-129` | ✅ |

**注**：`LookAtPlayerGoal` 默认 `probability` 确为 `0.02F`（`LookAtPlayerGoal.java:13`、3 参构造器 24-26），
但 `lookTime` 的 40~80 说法错误（见 M2）。

---

## 已验证正确的部分

以下每一条我都**主动去原版源码核实过**，不是凭记忆：

1. **`isInvulnerableTo` 的布尔逻辑（问题 A 全部四问）—— 成立，无漏洞。**
   `DamageTypeTags.BYPASSES_INVULNERABILITY` 的实际内容为 `minecraft:out_of_world` 与 `minecraft:generic_kill`
   （`minecraft_1.21.1_client.jar!/data/minecraft/tags/damage_type/bypasses_invulnerability.json`）。
   逐项推演 `!source.is(BYPASSES) || super.isInvulnerableTo(source)`（`NpcEntity.java:192`）：

   | 输入 | 左操作数 | 是否求值 `super` | 结果 | 与意图一致？ |
   |---|---|---|---|---|
   | 普通伤害（含创造玩家造成的普通伤害） | `true`（短路） | 否 | `true`（无敌） | ✅ 创造也打不动 |
   | `/kill`（`generic_kill`） | `false` | 是 → `isRemoved()=false`、`invulnerable && !BYPASSES=false`、非火非摔 ⇒ **false** | `false`（放行） | ✅ |
   | 虚空（`out_of_world`） | `false` | 是 → 同上 **false** | `false`（放行） | ✅ |
   | 已 `isRemoved()` + BYPASSES 伤害 | `false` | 是 → `isRemoved()=true` ⇒ **true** | `true`（无敌） | ✅ 与原版一致（对已被移除的实体本就无事可做） |

   特别核对了两个担忧：
   - **"创造玩家 + BYPASSES 伤害"会不会意外放行？** 会走到 `super` 并返回 false（因为 `invulnerable && ...` 那一项为 false、
     `isRemoved()` 为 false），**但这是个空集场景**：`BYPASSES_INVULNERABILITY` 只含 `out_of_world` 与 `generic_kill`，
     两者的 `DamageSource` 都**没有攻击者实体**（`level.damageSources().outOfWorld()/genericKill()`），
     而 `DamageSource#isCreativePlayer()` 要求 `getEntity() instanceof Player && player.getAbilities().instabuild`
     （`DamageSource.java:111-117`）⇒ 永远为 false。故该分支不可达，**不构成漏洞**。
   - **`super` 里的 `!source.isCreativePlayer()`（`Entity.java:2683`）** 在非 BYPASSES 伤害上根本不会被求值（左侧已 true），
     这正是"连创造也打不动"得以实现的机制 —— 与注释声称的一致。
   - **副作用（值得记一笔）**：非 BYPASSES 伤害上直接 `return true`，**不会**触发
     `CommonHooks.isEntityInvulnerableTo` → `EntityInvulnerabilityCheckEvent`（`Entity.java:2686`、`CommonHooks.java:281-283`）。
     即其它模组无法用该事件把 NPC 改回"可被伤害"。对"绝对无敌"来说这是预期行为，但意味着
     NeoForge 的标准 admin 接口在这条路径上失效（`/kill`、`/damage`、虚空仍然有效）。

2. **`requiresCustomPersistence() = true` 确实能阻止消失 —— 成立。**
   闸门 `} else if (!this.isPersistenceRequired() && !this.requiresCustomPersistence()) {`（`Mob.java:749`），
   短路或运算下任一项为 true 即跳过整套 despawn 逻辑（755/761 的 `discard()`）。
   且这一项**必须有**：构造函数里的 `setPersistenceRequired()` 会被 `load()` 覆写为 false（`Mob.java:461` 无守卫），
   所以只有覆写方法能兜住。
3. **`isPersistenceRequired()` 与 `requiresCustomPersistence()` 的区别、以及"用官方钩子"的理由 —— 成立。**
   `isPersistenceRequired()` 只是**字段读取器**（`Mob.java:1238-1240`），而该字段被 NBT 写出
   （`addAdditionalSaveData` 用的是**字段本身**，`Mob.java:392`）、被 `readAdditionalSaveData` 直接赋值（461）、
   被 `convertTo` 读取并转抄（1346-1348）。若覆写 getter 返回 true，会出现"getter 说 true、`addAdditionalSaveData`
   写出的 NBT 是 0b"的分裂状态。`requiresCustomPersistence()` 默认 `isPassenger()`（`Mob.java:736-738`），
   语义正是"我的存续不该由刷怪规则决定"。四个原版先例行号全部核对无误。
4. **不覆写 `isNoAi()` 的核心论据 —— 成立（"整个方法体被包住"严格说是"除最后一行外"）。**
   `LivingEntity#travel` 的 `if (this.isControlledByLocalInstance()) {` 在 2219-2220，
   配对的 `}` 在 **2344**，方法体只剩 2346 行 `this.calculateEntityAnimation(...)` 落在 `if` 之外。
   链路 `isControlledByLocalInstance()`（`Entity.java:3215-3217`）= `isEffectiveAi()`
   （`Entity.java:3219-3221` = `!level.isClientSide`，`Mob#isEffectiveAi` 再加 `&& !isNoAi()`，`Mob.java:1419-1422`）。
   服务端上 `isNoAi()=true` ⇒ `travel()` 只剩动画计算，**重力与位移积分确实全没了**。
   附带核实：`tickHeadTurn` 在 `LivingEntity#tick` 里被调用（`LivingEntity.java:2525`），而 `tick` 本身不检查 AI
   ⇒ 客户端身朝计算不受 `isNoAi()` 影响，与注释第 75-77 行的说法一致。
5. **优先级 3 < 5 的 Flag 仲裁 —— 成立（"永久挡死"的措辞可以保留）。**
   `MeleeAttackGoal` 构造器 `setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK))`（`MeleeAttackGoal.java:31`），
   `LookAtPlayerGoal` `setFlags(EnumSet.of(Goal.Flag.LOOK))`（`LookAtPlayerGoal.java:38`）。
   `GoalSelector#tick` 的启动条件是 `!isRunning() && 无禁用 Flag && goalCanBeReplacedForAllFlags(goal, lockedFlags) && canUse()`，
   而 `goalCanBeReplacedForAllFlags` 逐个 Flag 检查 `lockedFlags.get(flag).canBeReplacedBy(goal)`，
   `WrappedGoal#canBeReplacedBy` 要求 `other.getPriority() < this.getPriority()`（`GoalSelector.java:73-81,97-109`、`WrappedGoal.java:23-25`）。
   ⇒ 低优先级（数值更大）的 goal 在 `lockedFlags` 被占时**永远无法启动**，且不会报错。
   注意一个更精确的表述：**攻击 goal 更高优先级是"必须"，但 look goal 被攻击 goal 挤掉这本身是良性的**
   （`MeleeAttackGoal#tick` 自己会 `getLookControl().setLookAt(target, 30F, 30F)`，`MeleeAttackGoal.java:106`）。
6. **`LookControl#clampHeadRotationToBody` "只在有寻路时生效" —— 成立（"头身分离"设计的基石成立）。**
   ```java
   protected void clampHeadRotationToBody() {
       if (!this.mob.getNavigation().isDone()) {          // LookControl.java:77
           this.mob.yHeadRot = Mth.rotateIfNecessary(this.mob.yHeadRot, this.mob.yBodyRot, (float)this.mob.getMaxHeadYRot());
       }
   }
   ```
   （`LookControl.java:76-80`，由 `tick()` 末行无条件调用，73 行）。
   精确条件 = "`PathNavigation#isDone()` 为 false（即 path 非空且未走完）"。站桩时 `isDone()` 为 true ⇒ **不夹取**，头可以随便转过去。
7. **`Mob#tickHeadTurn` 覆写但不调 super、只走 `BodyRotationControl` —— 成立。**
   `Mob.java:377-381`（`this.bodyRotationControl.clientTick(); return animStep;`），
   对照组是 `LivingEntity#tickHeadTurn`（`LivingEntity.java:2700-2715`，0.3 插值 + `getMaxHeadRotationRelativeToBody()=50`）。
   机制与数值核实（`BodyRotationControl.java`）：移动分支 `yBodyRot = yRot`（19-22）；
   站桩分支 `HEAD_STABLE_ANGLE = 15`（8、26 行）、`DELAY_UNTIL_STARTING_TO_FACE_FORWARD = 10`（9、32 行）、
   `HOW_LONG_IT_TAKES_TO_FACE_FORWARD = 10`（10 行，实际写在 50 行 `(float)i / 10.0F`），
   `rotateBodyIfNecessary` 把身体夹到离头不超过 `getMaxHeadYRot()`（40-42 行），
   `rotateHeadTowardsFront` 的允许量 `getMaxHeadYRot() * (1 - clamp((headStableTime-10)/10, 0, 1))`（48-53 行）。
   `getMaxHeadYRot()` 默认 **75**（`Mob.java:825-827`）、`getHeadRotSpeed()` 默认 **10**（`Mob.java:838-840`）。
   **⚠️ 数值措辞需修正**：注释/文档写的"头停下约 11 tick 后 `rotateHeadTowardsFront` 的允许量**递减到 0**"
   应改为"**第 11 tick 起开始递减，到第 20 tick 才减到 0**"（`headStableTime > 10` 才进入该分支，`f` 从 1/10 走到 1）。
   即"最终完全对齐"约需 1 秒而不是 11 tick。
8. **`yBodyRot` 不参与网络同步 —— 成立。**
   `ServerEntity` 只跟踪/下发 `lastSentYRot`、`lastSentXRot`（`ServerEntity.java:59-60`、`137-138`、`205-208`）
   与 `lastSentYHeadRot`（61、213-217 → `ClientboundRotateHeadPacket`），
   `ClientboundMoveEntityPacket` 的字段只有 `xa/ya/za/yRot/xRot/onGround`（`ClientboundMoveEntityPacket.java:12-20`）。
   `yBodyRot` 是 `LivingEntity` 的普通 `public float` 字段（`LivingEntity.java:204`），
   `setYBodyRot` 只是赋值（3092-3094），`Entity#setYBodyRot` 甚至是空方法（`Entity.java:2635-2636`）。
   ⇒ 注释"服务端写 `yBodyRot` 对画面没有用，而覆写 `tickHeadTurn` 在双端都会生效"**完全正确**，
   这也是"绝对不要覆写 `tickHeadTurn`"的硬依据。客户端一侧确实会自己算：
   `LivingEntity#tick` 不检查 AI 就调 `tickHeadTurn`（2525），`BodyRotationControl#isMoving()` 用的是
   `getX()-xo`/`getZ()-zo`（`BodyRotationControl.java:59-62`），而客户端的插值位移由
   `lerpPositionAndRotationStep` 写入（`LivingEntity.java:2731-2733`、`Entity.java:3830-3839`），双端同源。
9. **`GoalSelector` 对"从未启动过"的 goal 不调 `stop()` —— 成立。**
   清理循环的前提是 `wrappedgoal.isRunning()`（`GoalSelector.java:87-91`），
   而 `isRunning` 只在 `WrappedGoal#start()` 里置 true（43-48）、`stop()` 里置 false（51-56）。
   所以 `customServerAiStep()` 兜底的定位（"从未启动过的残留"）是准确的，它与 `NpcAttackGoal.stop()`
   两条路径确实不冗余（文档 §3.7 的说法成立）。
10. **移速平方关系 `位移比 = (档位 × 属性)² / 0.1` —— 成立（在 `档位×属性 ≤ 1` 的定义域内）。**
    完整链路：`LivingEntity#travel`（2219）→ `handleRelativeFrictionAndCalculateMovement(travelVector, friction)`
    （调用 2326、定义 2383）→ `moveRelative(getFrictionInfluencedSpeed(friction), deltaMovement)`（2384）
    → `Entity#getInputVector(relative, motionScaler, yRot)`（1398、定义 1402-1412），
    关键行 1407：`(d0 > 1.0 ? relative.normalize() : relative).scale((double)motionScaler)`
    —— **`|relative| ≤ 1` 时不归一化**，于是位移含两个因子之积：
    ① 输入幅度 `|relative| = |zza|`；② `motionScaler = getFrictionInfluencedSpeed = getSpeed() × (0.216/f³)`（2428-2430）。
    生物侧：`zza` 与 `speed` 字段被写成**同一个值** `档位 × 属性`
    （`MoveControl#tick`：`this.mob.setSpeed(speedModifier * this.mob.getAttributeValue(MOVEMENT_SPEED))`，`MoveControl.java:100`；
    `Mob#setSpeed`：`super.setSpeed(speed); this.setZza(speed);`，`Mob.java:556-560`）
    ⇒ 位移 ∝ `(档位 × 属性)²`。
    玩家侧：`Player#getSpeed()` 被覆写为属性值（`Player.java:1613-1616`），属性默认 `0.1F`（`Player.java:231`），
    而 `zza = input.forwardImpulse ∈ {-1, 0, 1}`（`LocalPlayer.java:691`、`KeyboardInput.java:15-21`）⇒ 位移 ∝ `0.1 × 1.0`。
    ⇒ 比值 `(档位×属性)² / 0.1`，`档位=1.0、属性=0.3` ⇒ **0.9**。
    **定义域提醒**（应补进文档）：当 `档位 × 属性 > 1.0` 时 `getInputVector` 会归一化，平方关系退化为线性 ——
    这正是 `PathNavigation#doStuckDetection:312` 那个 `getSpeed() >= 1.0F` 守卫的由来（原注释已正确引用该行）。
11. **`ModAttributes` 的注册时机与"调用两次"问题 —— 正确且安全。**
    `EntityAttributeCreationEvent` 是 mod-bus 事件、在"注册之后、common setup 之前"触发
    （`EntityAttributeCreationEvent.java:16-23`），`DihuangLoongEntity.createAttributes().build()`（`ModAttributes.java:98`）
    每次都是新 Builder ⇒ `build()` 多次调用无副作用（`AttributeSupplier.java:109-113`）。
    唯一会出问题的是对同一类型 `put` 两次（`EntityAttributeCreationEvent.java:30-33` 抛异常），本类只 put 一次。
    `@EventBusSubscriber` 的 bus 自动分派也核实无误（见 N6）。
12. **`fireImmune()` 不是冗余（但"免疫伤害"这层确实冗余）—— 成立。**
    伤害层面：`super.isInvulnerableTo` 只在 BYPASSES 时被求值，而 BYPASSES 只有 uid/虚空，所以
    `source.is(IS_FIRE) && this.fireImmune()`（`Entity.java:2684`）这一支永远走不到 ⇒ 对伤害**冗余**。
    但 `fireImmune()` 还挡住：`Entity#lavaHurt` 的整个方法体（不着火、不受伤、不播放燃烧音效，`Entity.java:530-537`）
    与 `Entity#isOnFire()`（不显示着火渲染，`Entity.java:2322-2325`）。
    ⇒ 有实际副作用（正面），建议保留，但把注释写成"除了免掉火焰伤害，还免掉着火状态与岩浆点燃的视觉效果"。
13. **`clientTrackingRange` 的单位是区块 —— `clientTrackingRange(10)` = **160 格**，绰绰有余。**
    `ChunkMap.java:1113` 与 `1355`：`int i = entitytype.clientTrackingRange() * 16;`。
    所以"模型 9 格会不会超出追踪范围"这个担心不成立（160 ≫ 9）。
    另外 `Entity#shouldRenderAtSqrDistance` 用 `getBoundingBox().getSize() * 64`（`Entity.java:1692-1700`）⇒ 半径 ~160 格才开始不渲染。
14. **`isPushable()=false` 不是"多余的"（`Entity` 默认就是 false，但 `LivingEntity` 覆写成了 true）—— 成立。**
    `Entity#isPushable()` 返回 false（`Entity.java:1671-1673`），但 `LivingEntity#isPushable()`
    返回 `isAlive() && !isSpectator() && !onClimbable()`（`LivingEntity.java:3070-3073`）
    ⇒ `NpcEntity` 的覆写是**真实生效的必要覆写**，与 `KNOCKBACK_RESISTANCE` 各挡一条不同路径（见 M3）。
15. **`ATTACK_DAMAGE` 必须显式 add —— 成立，且后果比注释写的更严重（不 add 会抛异常而非"伤害为 0"）。**
    详见 m1。
16. **`MeleeAttackGoal` 的 20 tick 限流 —— 成立**（`MeleeAttackGoal.java:22-23`、`36-40`，`COOLDOWN_BETWEEN_CAN_USE_CHECKS = 20L`）。
17. **`MeleeAttackGoal.stop()` 与 `clearAttackCommand()` 的去重逻辑（问题 12）—— 不是纯冗余，但顺序有副作用（见 C1）。**
    `MeleeAttackGoal#stop` 只在 `!EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target)` 时才 `setTarget(null)`
    （`MeleeAttackGoal.java:87-95`；`NO_CREATIVE_OR_SPECTATOR` 定义在 `EntitySelector.java:31-32`），
    而 `clearAttackCommand()` 是无条件清 target ⇒ 后者补上了"目标是创造/旁观玩家时清不掉"的缺口，**有价值**；
    重复的只有 `setAggressive(false)`（后者不做）与 `getNavigation().stop()`（重复且有害，见 C1）。
18. **`canBeSeenAsEnemy()` 这条链的存在 —— 已核实**（这是 M1 的证据基础，不是猜测）。
    `LivingEntity.java:907-908` → 被 `TargetingConditions.java:69`（战斗目标筛选）、`LivingEntity.java:899-901`
    （`canAttack`）、`Warden.java:405`、`EnderDragon.java:983` 使用。

---

## 无法静态确认的项

| # | 需实机确认的点 | 建议验证动作 |
|---|---|---|
| V1 | **C1 是否真的能复现**（静态推导确定的先后关系，但仍建议实测） | 让 NPC 处于"正在攻击"状态（`/beloong npc attack @e[type=...]` 后确认它在追打），紧接着 `/beloong npc walk @e[type=beloong:dihuang_loong] ~ ~ ~8`，观察是否**完全不动**；再单独执行一次 `walk`（无前置攻击）对比。若复现，修 `clearAttackCommand()` 后重测。 |
| V2 | NPC 是否真的被原版怪物索敌（M1） | 站在 NPC 旁 `/summon zombie`，看僵尸是否把 NPC 当目标；再 `/data get entity <npc> Invulnerable` 确认字段是 `0b`。 |
| V3 | 爆炸/水流位移（M3） | 在 NPC 旁爆一个 TNT，记录坐标变化；用水桶在 NPC 旁放水并观察是否被流带走。 |
| V4 | 模型的**实际渲染朝向与偏移**（决定 M4 里"头在原点前方多少格"是 +Z 还是 −Z，以及 `DihuangLoongRenderer#preRender` 无偏移是否成立） | 我现在只能给"原始立方体并集 Z 跨度 9.02 格、Z 中心偏 +0.69 格、首尾相对原点 −3.82/+5.20 格"，那是**未叠加骨骼变换**的估计。实机需要看：① 站桩时头相对碰撞箱的位置；② 右键点身体中段/头部/尾部是否都能选中；③ 把视角转开，龙是否整条消失。 |
| V5 | `LookAtPlayerGoal` 一轮结束时的 1 tick 无目标窗口（M2）是否肉眼可见 | 连续观察"玩家绕圈走动"时的头部跟随是否有规律性停顿。 |
| V6 | `updateInterval` 默认 3 带来的动画滞后（m5）是否可感知 | 让 NPC 起步/停步，观察 walk↔idle 切换是否"拖"了 1–3 tick。 |
| V7 | 文档 §3.5 的举例 `FollowParentGoal`/`TemptGoal` 1.25、`PanicGoal` 2.0 | 我只核实了这三个类有 `speedModifier` 字段（`FollowParentGoal.java:14`、`TemptGoal.java:16`、`PanicGoal.java:20`），**没有逐行核实具体数值**。 |
| V8 | idle/walk/run 三档动画在实机上的过渡观感（`animationTransitionTicks=5`） | 5 tick 过渡在 20 tps 下是 0.25 秒；语义已核实为"tick"（`AnimationController.java:112-121`），0 = 立即切换（679-680），观感需人眼判断。 |

---

## 越界发现

以下不在本次审查范围（我只被要求审 6 个文件），仅报告，未修改：

1. **`docs/NPC系统总设计.md` §3.7 与 `NpcEntity` Javadoc 把 `clearAttackCommand()` 列为公开 API**（文档 302 行、代码 326 行）。
   配合 C1，建议修代码的同时把文档 §3.7 补一句"`clearAttackCommand()` 只清状态、不动导航"。
2. **`NpcEntity.java:109-110`（`runAnimationName` 注释）声称"资产里的 `run` 引用了 3 根本模型没有的骨骼（`Drip1-3`），
   GeckoLib 对缺失骨骼是优雅忽略"** —— 这条属于资产侧断言，我没有解析 `dihuang_loong.animation.json`（477 KB）去核对，
   §4.1 里的"idle 32 骨骼 / walk 63 骨骼 / run 77 骨骼、覆盖 0 缺失"同理。若这是硬结论，建议附上核验脚本或输出。
3. **`NpcEntity.java:44-48` 的注释把"构造函数 `setXxx` 与覆写方法并存"的收益表述为"让字段本身与保存出的 NBT 一致"**，
   但 M1 已证明 `/summon` 路径下字段最终是 false。文档 §3.2 决策 9「语义写成覆写方法，构造函数里的 `set` 只保证 NBT 一致」
   需要按 M1 修正措辞。
4. **`NpcEntity` 覆写了 `registerGoals()` 但没有 `super.registerGoals()`**（`NpcEntity.java:241-248`）——
   父实现 `Mob#registerGoals()` 目前是空方法（`Mob.java:156-157`），现在无害；与 `customServerAiStep` 未调 super 是同一类
   "将来父类有内容会被静默丢弃"的隐患，可一并补上。
5. **`sendSuccess` 的文案里 `pos.toString()`**（`NpcCommand.java:73-74`）会输出 `Vec3` 的默认 `toString`
   （形如 `(1.0, 2.0, 3.0)`），与命令语法里常用的 `1.0 2.0 3.0` 不一致；纯观感，可换成
   `pos.x + " " + pos.y + " " + pos.z` 或 `Component` 格式化。
6. **`BeLoongCore.java:207` 在 `RegisterCommandsEvent` 里注册 `NpcCommand`**，与 `NpcCommand` 类注释"将来不需要了，
   删本类 + `BeLoongCore` 里一行注册即可"一致 —— 这一条经核对**成立**（只有 1 处调用点、1 处 `beloong` 根注册）。
