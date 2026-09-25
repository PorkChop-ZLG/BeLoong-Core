# 核验报告：NPC 实体与 AI

> 核验对象：`docs/reviews/2026-09-25-npc-entity-ai-review.md`（审查者 C，527 行）、
> `docs/reviews/2026-09-25-npc-doc-code-consistency-review.md`（审查者 E，348 行，仅实体/AI 相关条目）
> 核验基线：`24bb701`（`git rev-parse HEAD` = `24bb701ac1712c796f7da82328de72a27cb5ab92`，工作树仅多出 5 份未跟踪的 review 文档）
> 核验方式：从一手源码独立重新推导；审查者报告仅作待验证输入。
> 原版源码：`~/.gradle/caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_0fdaf824d72c9fa3ae563d2373cd5ac7600c63c5_output.jar`
> （`System.IO.Compression.ZipFile` 抽取 `net/minecraft/...` 与 `net/neoforged/...` 后逐行读；下文所有"我核实"均为本次自读）
> 未运行 gradle、未运行游戏、未修改任何源码或既有文档。

## 总体裁决

**两份报告的可信度都高，但都不是可直接采信的结论。** 逐条复核后：

| 状态 | 条数 | 说明 |
|---|---|---|
| 成立（诊断与修法都对） | 19 | 含 3 条严重发现中的 2 条诊断 |
| 成立但修法错/不足 | 3 | **C-C1**（修法无效，上级判断正确）、**C-M3**（只对一半）、C-M1（修法可行但未给关键前提） |
| 部分成立 | 5 | 引用行号范围或机制表述不精确（C-m4、C-M4 的"0.3"、C 的 m5 部分） |
| 不成立（引用值/结论错） | 3 | **C-M4 的"inflation 0.3F"**（真实走 `getPickRadius()` 默认 0.0）、**C-m4 的行号区间**（写 1671-1673 却断言"覆写生效"——那是 `Entity` 版）、**E-C3 的 `:1143` 单值**（闸门 `if` 是 1143、方法声明 1142，C 给的 `1142-1143` 更完整） |

**系统性偏差**（这是核验的主要价值）：

1. **两位审查者都把"我抽到的行号"当成"唯一正确值"**，且都在同一组行号（`1084`/`1759`/`437`）上给出**互不相同**的"正确值"（C：`1142-1143`、`2681-2687`；E：`1143`、`2681-2686 ✅`）。本报告用"声明行 + 逻辑行"分别列出确定值（见核对表），消除这处分歧。
2. **C 的"建议修法"整体偏乐观**：C 自陈"安全性检查"的 C-C1 修法实际上是**无效的**（上级已指出，我独立确认）；C-M1 的三选一里推荐的方案 2 缺一个**必要前提**（必须在 `load` 之后覆盖，这正是它有效的唯一理由）；C-M3 的"补一行属性"只堵住爆炸、**完全没堵住流体**，而 C 自己在同一条里就写了"流体走 `isPushedByFluid`"。
3. **E 的强项是数值核对**（属性项数 21、`lookTime` 20–40、三处错行号），**弱项是运行时语义**——E 没有对任何一条给"运行时后果"评估，而它对 `Entity.java:2681-2686` 打了 ✅，这条与本报告 C-C1 的核心缺陷同源（都是"注释声称的机制不是实际机制"）。
4. **重定级后两位审查者的"严重/重要/次要"阈值确实不一致**：C 把 `lookTime` 注释错误列为 Major，E 列为 Critical；而 C 把真实功能缺陷 C1 列 Critical、E 完全没盖到。按运行时影响重定级后，只有 C-C1 留在 P1 头部。

**建议采信**：C 的 **C-C1 诊断**（C1/M1/M3/M6 的现象描述）、E 的 **C2/C3/C5/M2/M3 的数值与行号**。**建议丢弃**：C 的 C-C1 修法、C-M3 的单一修法、C-M4 的 `0.3F` 论据、C-m4 的行号区间、E 的 `:1143` 单值写法。

---

## 逐条裁决

### C-C1 攻击→行走切换时 `walkTo()` 的路径被攻击 goal 的 `stop()` 抹掉

- **裁决**：**诊断成立**（我独立复现了完整链路）；**C 的建议修法无效**（确认上级判断）。
- **我的核实**（全部自读）：
  1. **仲裁确实是每 2 tick 才有一次"完整 `goalSelector.tick()`"**：`Mob#serverAiStep`（`Mob.java:772-812`）里 `int i = this.tickCount + this.getId();`（779），`if (i % 2 != 0 && this.tickCount > 1)` 走 `tickRunningGoals(false)`（780-786，**只 tick 不仲裁**），`else` 走完整的 `targetSelector.tick(); goalSelector.tick();`（787-794）。
  2. **`walkTo()` 必然让攻击 goal 在下一次仲裁请求停止**（三条独立路径，任一即足以杀死 walk）：
     - 若攻击 goal **正在运行**：清理循环 `if (wrappedgoal.isRunning() && (... || !wrappedgoal.canContinueToUse())) wrappedgoal.stop();`（`GoalSelector.java:87-91`），而 `NpcAttackGoal#canContinueToUse` = `isAttackCommandActive() && super.canContinueToUse()`（`NpcAttackGoal.java:38-40`），`walkTo` 已在 `NpcEntity.java:285` 把标志清掉 ⇒ `stop()`（`NpcAttackGoal.java:43-46`）⇒ `super.stop()`（`MeleeAttackGoal.java:86-95`，**line 94 无条件 `this.mob.getNavigation().stop()`**）⇒ `clearAttackCommand()`（`NpcEntity.java:326-330`，**line 329 再次 `getNavigation().stop()`**）。`PathNavigation#stop()` 只做 `this.path = null;`（`PathNavigation.java:364-366`）——`walkTo` 刚在 `NpcEntity.java:287` 建的路径被丢弃，**且没有任何机制重发**（重发路径只有 `MeleeAttackGoal#start:80` 与 `#tick:138`，两者都需要 goal 运行）。
     - 若标志为 false 但 goal 仍运行：`canContinueToUse` 左侧已 false，同上。
     - 若标志为 true、目标非 null 且 goal **未**运行：`NpcEntity.java:286` 已 `setTarget(null)` ⇒ `MeleeAttackGoal.canUse()` 在 `MeleeAttackGoal.java:42-43` 返回 false ⇒ goal 不启动。**这条分支下 walk 能存活**，所以症状是间歇性的——与 C 第 61 行的观察一致。
  3. **触发路径可达**：`NpcCommand.walk` → `NpcEntity.walkTo`（`NpcCommand.java:65-76`）。命令源在服务端主线程、实体 tick 之前执行，因此 `walkTo` 的重发一定早于同 tick 内的 `goalSelector.tick()`——先后关系不是概率问题。
  4. **C 的"最多 2 tick 后 `stop()` 一定会跑"表述偏松**：完整仲裁只在 `i % 2 == 0` 的那一 tick 发生，`i = tickCount + getId()`，所以到下一次完整仲裁的距离是 1 或 2 tick。结论（会跑）不受影响。
- **重定级**：**P1**（既有"攻击 goal 正在运行"这一可达前置，也真的会出现"NPC 原地不动、无报错、无日志"；不是 P0：无崩溃/无数据损坏，`attack()` 单独用、`walkTo()` 单独用都正常）。
- **建议修法是否有效**：**C 的方案（`clearAttackCommand()` 去掉 `getNavigation().stop()`）无效。** 上级的判断我独立确认：`NpcAttackGoal.stop()` 是 `super.stop(); clearAttackCommand();`（`NpcAttackGoal.java:43-46`），而 `MeleeAttackGoal#stop()` **自己**在 `MeleeAttackGoal.java:94` 调 `this.mob.getNavigation().stop()`，删掉 `NpcEntity.java:329` 那一行**不会**阻止路径被清。C 在第 78-80 行的"安全性检查"直接引用了这一行，却得出相反结论——这是 C 报告里最实质的错误。
- **我验证过的修法（两条候选，均已逐步推演）**：

  **(A) 推荐 —— 把"清攻击指令"从 `walkTo()` 里挪到 `customServerAiStep()` 延后执行，并补 `super` 调用。**

  ```java
  // NpcEntity
  private boolean attackCommandClearPending;   // 加字段

  public void walkTo(Vec3 pos) {
      if (this.level().isClientSide()) return;
      this.attackCommandClearPending = true;   // ← 不再当场清标志/target
      this.getNavigation().stop();
      this.getNavigation().moveTo(pos.x, pos.y, pos.z, 1.0D);
  }

  @Override
  protected void customServerAiStep() {
      super.customServerAiStep();              // 顺手补上 C-M6 提到的父调用
      if (this.attackCommandClearPending) {
          this.attackCommandClearPending = false;
          this.clearAttackCommand();           // 保留 nav.stop()，无害
      } else if (this.attackCommandActive) {
          LivingEntity target = this.getTarget();
          if (target == null || !target.isAlive()
                  || !EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target)) {
              this.clearAttackCommand();
          }
      }
  }
  ```
  为什么堵得住：`customServerAiStep()` 在 `Mob.java:800`，**晚于** 792 行的 `goalSelector.tick()`。清标志的那一刻，仲裁已经跑完，`NpcAttackGoal#stop()`（也就是 `super.stop()` 里的 `nav.stop()`）在本 tick **不可能再被调用**；下一 tick 清理循环调用 `canContinueToUse()` 时 `target` 已是 null ⇒ `MeleeAttackGoal.java:65-66` 直接返回 false，`stop()` 不执行 ⇒ `nav.stop()` 不执行 ⇒ `walkTo` 的路径存活。`MeleeAttackGoal` 也不会重发路径（`canUse()` 在 `MeleeAttackGoal.java:42-43` 就被 null target 挡住）。**净效果等价于"攻击 goal 从未运行过"**——而 proof 2.3 已证明那条路径下 walk 是正常的。

  **(B) 备选 —— 命令世代号 + 攻击 goal 停止时不自作主张停导航。**

  ```java
  private int commandGeneration;                                  // 每次 walkTo/attack/stopAction 自增
  public int commandGeneration() { return this.commandGeneration; }
  // NpcAttackGoal: 记下 start() 时的世代，canContinueToUse() 里 generation 变了就返回 false
  public void stop() { this.npc.clearAttackCommand(); }           // 不再 super.stop()
  public void clearAttackCommand() { this.attackCommandActive = false; this.setTarget(null); }
  ```
  成立的理由：`MeleeAttackGoal.stop()` 里唯一"有价值"的行为是 `setAggressive(false)` 与 `setTarget(null)`（后者已在 `clearAttackCommand` 里无条件做了，而且原版 `MeleeAttackGoal:88-91` 只在 `!NO_CREATIVE_OR_SPECTATOR.test(target)` 才清）。`nav.stop()` 必须去掉，否则仍然杀 walk。**代价**：`setAggressive(false)` 要自己补；调用方不再走 `GoalSelector` 的 `WrappedGoal.stop()`，`isRunning` 标志由 `GoalSelector` 自己置位，不补也无所谓，但改动面比 (A) 大得多，且引入"世代号"这一新状态。

  **比较**：(A) 只加一个 boolean 字段、把一段逻辑挪位置，**不触碰 goal 的停止语义**，最不容易漏；代价是"清指令"晚一个 tick 生效（最多 20ms，攻击 goal 的 20 tick 限流本就更慢）。(B) 语义上更"干净"（每个指令有唯一身份），但要自己重实现 `stop()` 的一部分，且新增世代号需要额外的持久化/复位考虑。**推荐 (A)。**

  **两条都不能省的共同前提**：`walkTo()` 里的 `setTarget(null)`（`NpcEntity.java:286`）**本身就是 `canContinueToUse()` 返回 false 的充分条件**——上级的这个观察我确认成立（`MeleeAttackGoal.java:64-66`）。任何修法都必须保证"清 target"发生在 `MeleeAttackGoal.stop()` 之后的 tick，否则 `super.stop()` 里的 `nav.stop()` 一定会跑。

### C-M1 `/summon` 后字段为 false ⇒ 被原版敌对生物索敌

- **裁决**：**成立**（三层链路我全部独立复现，含 C 自己列为"关键一步"的索敌闸门）；**修法方向对，但 C 未给出让它成立的关键前提**。
- **我的核实**：
  1. `EntityType.create(CompoundTag, Level)` 确实是"先构造再 load"：`Util.ifElse(by(tag).map(t -> t.create(level)), t -> t.load(tag), ...)`（`EntityType.java:1103-1109`），构造函数在 `create(level)`（`:1099-1101`）里运行。
  2. `Entity#load` 确实**无 `contains` 守卫**地覆写：`this.invulnerable = compound.getBoolean("Invulnerable");`（`Entity.java:1854`，写出侧 `:1752`）。⇒ 标签无该键时字段变 false。
  3. `Entity#isInvulnerable()` 就是字段读取器（`Entity.java:2689-2691`）；`LivingEntity#canBeSeenAsEnemy()` = `!this.isInvulnerable() && this.canBeSeenByAnyone()`（`LivingEntity.java:907-909`）。
  4. **关键一步（我自己找的，不是引用 C 的）**：索敌**确实**走 `canBeSeenAsEnemy()`，共两条独立闸门：
     - `TargetingConditions#test`：`if (this.isCombat && (!attacker.canAttack(target) || !attacker.canAttackType(...) || attacker.isAlliedTo(target))) return false;`（`TargetingConditions.java:73`），而 `LivingEntity#canAttack(LivingEntity)` = `target.canBeSeenAsEnemy()`（`LivingEntity.java:899-901`）。`NearestAttackableTargetGoal` 用的是 `TargetingConditions.forCombat()`（`isCombat=true`）。
     - `TargetGoal#canContinueToUse`：`if (!this.mob.canAttack(livingentity)) return false;`（`TargetGoal.java:49`）。
     ⇒ **C 的 M1 是真 Bug，不是"语义洁净度"问题。**
  5. 附带确认：`Mob#doHurtTarget` 读 `ATTACK_DAMAGE`（`Mob.java:1491-1492`），NPC 自身 `isInvulnerableTo` 覆写（`NpcEntity.java:190-193`）会让伤害为 0——所以实际症状是"怪物对着一堵 1000 血的空气墙寻路+挥击"，**有真实的 AI/寻路开销与观感污染**，但没有伤害。
- **重定级**：**P1**（触发路径平凡：`/summon` 出 NPC + 任意僵尸；可观测、可复现，且与"演出布景"的定位直接冲突）。附带 D2：`NpcEntity.java:44-48` 的类注释声称"构造函数里的 `setXxx` 仍然保留，只为让字段本身与保存出的 NBT 一致"——在 `/summon` 路径上写出的 NBT 是 `Invulnerable:0b`，这句话**不成立**。
- **建议修法是否有效**：**覆写 `readAdditionalSaveData` 重设字段（C 的方案 2）有效且比覆写 `isInvulnerable()` 稳**，但 C 漏了唯一让它成立的事实：`Entity#load` 在 `Entity.java:1894` 调 `this.readAdditionalSaveData(compound)`，**位于 1854 行的覆写之后**——所以这是正确的扩展点。C 只写了"在 `super` 之后重新 `setInvulnerable(true)`"，没说为什么这次不会被覆盖。
  - **副作用体检**：`setInvulnerable(true)` 还会被 `isInvulnerableTo` 的 `super` 分支读到——但普通伤害走不到（`NpcEntity.java:192` 左侧已 short-circuit），只有 BYPASSES 伤害（`/kill`、虚空）才会走到，而那时 `invulnerable && !source.is(BYPASSES...)` 因右侧 false 而不成立 ⇒ **不改变 `/kill`/虚空放行**，管理后路保住。综上：**无负面副作用，且顺带让第三方模组读 NBT/`isInvulnerable()` 时看到一致语义。**
  - **为什么不选覆写 `isInvulnerable()` 恒 true**：(a) 让 `NpcEntity.java:170` 的 `setInvulnerable(true)` 变成永不生效的"装饰"；(b) 全局改变 `isInvulnerable()` 的语义（任何未来读它的原版/模组代码都会受影响，包括 `NpcEntity.java:185-188` 注明"想连创造也打不动"的那条设计理由所依赖的字段语义）；(c) 与"尽量用原版机制"的裁定相悖。**注意**：C 提出的方案 3（删掉构造函数里的 `setInvulnerable(true)`）**不解决问题**——字段仍会被 `load` 写成 false。

### C-M3 "不可推动"的两条真实缺口：爆炸位移 / 流体流

- **裁决**：**成立，且诊断与行号全部正确**；**C 的修法不完整**（只堵了一半，而另一半 C 自己已经写出来了却只当"可选项"）。
- **我的核实**：
  1. `Entity#isPushable()` 默认 false（`Entity.java:1671-1673`），但 `LivingEntity#isPushable()` = `this.isAlive() && !this.isSpectator() && !this.onClimbable()`（`LivingEntity.java:3070-3073`）⇒ **`NpcEntity` 的覆写是真实生效的必要覆写**，挡的是 `Entity#push(Entity)`（`Entity.java:1548-1553`，`if (!this.isVehicle() && this.isPushable())`）。
  2. **爆炸位移**：`Explosion#explode` 的击退项是 `d10 = d13 * (1.0 - livingentity.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE))`（`Explosion.java:294`），随后 `entity.setDeltaMovement(entity.getDeltaMovement().add(vec31))`（`Explosion.java:304`）——**完全不经过 `isPushable()`，也完全不经过 `KNOCKBACK_RESISTANCE`**。属性本体默认 `0.0`、范围 `[0,1]`（`Attributes.java:52-54`）⇒ 当前 `d10 = d13`，NPC 会被炸飞。
  3. **流体流**：`Entity#updateInWaterStateAndDoWaterCurrentPushing` 只在 `if (this.isPushedByFluid(fluidType))`（`Entity.java:3358`）里累加 `interim.flowVector`，最后 `this.setDeltaMovement(this.getDeltaMovement().add(interim.flowVector))`（`:3391`）。`isPushedByFluid()` 默认 `true`（`Entity.java:2847-2850`），**`LivingEntity` 与 `Mob` 都没覆写**（我 grep 了三个类，只有 `Entity` 有定义）⇒ 站桩 NPC 照样被水流带。
- **重定级**：**P1**（两条路径都只需一个 TNT / 一桶水，症状直接可见）。
- **建议修法是否有效**：**只做 `.add(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, 1.0D)` 不够**——它**只**堵爆炸（因为 `Explosion.java:294` 读的就是这个属性，`(1.0 - 1.0) = 0` 使 `d10 = 0`，击退项 `vec31` 归零，`setDeltaMovement(add(ZERO))` 等价于不动），**对流体的 `setDeltaMovement` 毫无作用**。我验证过的完整修法是两条一起：

  ```java
  // createNpcAttributes() 里追加
  .add(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, 1.0D)
  // 另覆写（属性已在 supplier 中，只是值为 0；见 Attributes.java:52-54）
  @Override
  public boolean isPushedByFluid() { return false; }
  ```
  属性那条**不会**因"属性未注册"而抛：`EXPLOSION_KNOCKBACK_RESISTANCE` 已由 `LivingEntity.createLivingAttributes()` 加入（`LivingEntity.java:337`），这是 `add(attr, value)` 覆盖默认值的标准用法。
  **仍有残余（C 没提）**：`Entity#push(double,double,double)`（`Entity.java:1567-1570`）无任何门禁；风弹/活塞/其它模组的直接 `setDeltaMovement` 也挡不住。真要"绝对钉死"，得覆写 `push(double,double,double)` 为空——但那会连爆炸/流体的正常响应一起废掉，**不建议**。

### C-M4 碰撞箱 1.5×2.5 与约 9 格模型：四处后果

- **裁决**：**部分成立**——整体判断（这是真实可用性问题）成立，四条路径里有 **3 条我逐行核实为真、1 条（右键交互）C 的论据是错的**。
- **我的核实**：
  1. **近战命中**：`Mob#isWithinMeleeAttackRange` = `getAttackBoundingBox().intersects(entity.getHitbox())`（`Mob.java:1465-1467`），`getAttackBoundingBox()` = `getBoundingBox().inflate(DEFAULT_ATTACK_REACH, 0.0, DEFAULT_ATTACK_REACH)`（`Mob.java:1469-1488`，关键 `:1487`），`DEFAULT_ATTACK_REACH = Math.sqrt(2.04F) - 0.6F`（`Mob.java:104`）≈ 0.828。**C 的行号与算式全部正确** ⇒ 攻击者中心必须进到 NPC 盒扩 0.83 内，而"头在原点前方约 5 格"（C 的实测，属渲染组范围）意味着**视觉上要贴到身体中段才判命中**。
  2. **右键/准星 —— C 错在这里**：C 引 `ProjectileUtil.java:108-110`（6 参版，`inflationAmount = 0.3F`）与 `:123`（`aabb.inflate(0.3)`）。但客户端准星走的是 `GameRenderer#pick`（`GameRenderer.java:813-834`）→ `ProjectileUtil.getEntityHitResult(shooter, startVec, endVec, boundingBox, filter, distance)` 的 **6 参重载**（`ProjectileUtil.java:68-76`），它用的是 `entity1.getBoundingBox().inflate((double)entity1.getPickRadius())`（**`ProjectileUtil.java:75`**），而 `Entity#getPickRadius()` 返回 **`0.0F`**（`Entity.java:2231-2233`）。
     ⇒ **`0.3` 是别的重载（`:108-110`，弹射物用）的参数，与准星无关。**
     **但 C 的结论反而更严重**：准星选中需要射线**真正穿过** 1.5×2.5 的裸盒，膨胀量为 **0**（不是 C 说的 2.1×3.1）。而 `p_234237_ -> !isSpectator() && isPickable()`（`GameRenderer.java:829`）与 `LivingEntity#isPickable()`（默认 true）都通过 ⇒ 头/尾**完全点不中**。**这条是对话系统的直接前置**（`NpcDialogueHandler` 走 `PlayerInteractEvent.EntityInteract`，需要先被准星选中）。
     **修法仍然有效**：覆写 `getPickRadius()` 给 1.5–3.0 即可（`Entity#getPickRadius` 就是原版为此留的钩子）。**但 C 引的 `Entity.java:2231-2233` 正确、`:108-110/:123` 错误**，实施前必须知道真实机制是"膨胀 0"而不是"膨胀 0.3"。
  3. **寻路**：`NodeEvaluator#prepare` 的 `this.entityWidth = Mth.floor(mob.getBbWidth() + 1.0F);`（`NodeEvaluator.java:30`）与 `:32` 的 `entityDepth` 同式。**C 的行号正确**。1.5 ⇒ 2 格宽的寻路门 ⇒ 长尾（视觉上远超 2 格）必然穿墙穿模。**这一条我认为属"设计上可接受"**：把碰撞箱撑到 9 格会让寻路彻底不可用，C 自己的建议（不要撑大）是对的。
  4. **视锥剔除**：`Entity#getBoundingBoxForCulling()` 直接返回 `this.getBoundingBox()`（`Entity.java:3030-3032`），C 的行号正确；`Entity#shouldRenderAtSqrDistance` 用 `getBoundingBox().getSize() * 64`（`Entity.java:1692-1700`）⇒ 距离剔除不是问题，C 的判断正确。**这条与渲染组重叠，我只确认"轴对齐盒不随 `yRot` 旋转"这一事实**（`getBoundingBoxForCulling` 没有任何旋转逻辑），因此龙横向摆放时头/尾在盒外，属于真实可复现的剔除缺陷——修法与影响留给渲染组裁决。
- **重定级**：
  - 右键/准星选中困难：**P1**（`getPickRadius()=0` 是硬事实，对话系统依赖它，且这是"手感"级的问题）。
  - 近战命中距离需贴合到约 1.9 格：**P1**（有可达路径，但"用头咬"是可选演出，可算设计取舍）。
  - 寻路按 2 格宽处理：**P3**（当前无副作用——NPC 是站桩的，`walkTo` 只在摆位时用；且撑大碰撞箱是更坏的方案）。
  - 视锥剔除范围不随朝向：与渲染组重叠，我记 **P2** 并转交。

### C-M5 / C-M6（C 编号）`MobCategory.MISC` 的"持久"解释错 / `NoAI` 下兜底休眠

- **裁决**：**C-M5 成立**（D2）；**C-M6 成立但只值 P2**（是理论风险，不是真风险）；**C-M6 附带提到的"漏调 `super.customServerAiStep()`"成立**。
- **我的核实**：
  1. **MISC 的 `isPersistent` 与单实体存续无关**：闸门在 `Mob#checkDespawn`，`} else if (!this.isPersistenceRequired() && !this.requiresCustomPersistence()) {`（`Mob.java:749`）；而实体持久性字段是 `Mob.persistenceRequired`（`Mob.java:125`），由 `addAdditionalSaveData` 写 `compound.putBoolean("PersistenceRequired", ...)`（`Mob.java:392`）、由 `readAdditionalSaveData` 读回（`Mob.java:461`）——**全链路与 `MobCategory` 无任何交集**。C 引的 `MobCategory` 参数序（`("misc", -1, true, true, 128)`）与文档一致，但"持久"这个解释是错的。C 的结论（别因此删掉 `requiresCustomPersistence()`）正确。
  2. **`NoAI` ⇒ `serverAiStep` 整段不跑**：`LivingEntity#aiStep` 的 `if (this.isImmobile()) {...} else if (this.isEffectiveAi()) { ... this.serverAiStep(); }`（`LivingEntity.java:2761-2769`，调用在 `:2767`），`Mob#isEffectiveAi()` = `super.isEffectiveAi() && !this.isNoAi()`（`Mob.java:1419-1422`）。成立。
  3. **在本模组里 `NoAI` 由谁设置？我 grep 了整个 `src/main/java`：没有任何 `setNoAi(` 调用、没有任何 `NoAI` NBT 写入。** `NpcEntity.java:55-61`、`DihuangLoongEntity.java:18-22` 只是**注释**里在讨论"为什么不覆写 `isNoAi()`"。唯一的进入路径是 `Mob#readAdditionalSaveData` 读 NBT 的 `NoAI` 键（`Mob.java:443-445` 是写出侧）⇒ 只有管理员用 `/summon ... {NoAI:1b}` 或 `/data merge entity ... {NoAI:1b}` 才会触发。**⇒ 理论风险，非真风险。**
  4. C-M6 末尾的"`customServerAiStep()` 没有调 `super.customServerAiStep()`（父实现为空，`Mob.java:818-819`）"——我确认父实现为空，**当前无害**，但它是修 C-C1 的方案 (A) 顺手要做的事（见上）。
- **重定级**：MISC 解释错误 → **P3 + D2**（纯文档/注释与代码不符，无运行时影响）；`NoAI` 兜底休眠 → **P2**（需要异常输入/刻意操作才触发，且恢复 AI 后自动清理）。

### C 的其余条目（逐条裁决）

| C 条目 | 裁决 | 我的核实（一手） | 重定级 |
|---|---|---|---|
| `isInvulnerableTo` 四输入推演 | **成立** | `NpcEntity.java:190-193` 的表达式 `!source.is(BYPASSES) \|\| super.isInvulnerableTo(source)`（`:192`）逐项推演无误：普通伤害左侧 true ⇒ 短路返回 true（连创造也打不动）；`/kill`/虚空左侧 false ⇒ 走 `super`，而 `Entity.java:2681-2687` 里 `invulnerable && !BYPASSES && !isCreativePlayer()` 因 `!BYPASSES` 为 false 而整体 false ⇒ 放行。第四行（`isRemoved()` + BYPASSES）我确认 `super` 返回 true，与原版一致 | P3（注释级，无需改） |
| "非 BYPASSES 伤害不再触发 `EntityInvulnerabilityCheckEvent`" | **成立** | `Entity#isInvulnerableTo` 现在把四个 vanilla 条件算成 `isVanillaInvulnerable`，再交给 `CommonHooks.isEntityInvulnerableTo(this, source, isVanillaInvulnerable)`（`Entity.java:2682-2686`），后者 `post(new EntityInvulnerabilityCheckEvent(...))`（`CommonHooks.java:281-283`）。`NpcEntity` 在非 BYPASSES 上不调 `super` ⇒ 事件不发。**对"绝对无敌"是预期行为**，C 的登记合理 | P3（建议在 §3.2 记一句） |
| `requiresCustomPersistence` 闸门 | **成立** | `Mob.java:749` 精确命中；短路或运算下任一项 true 即跳过整套 despawn（`:755`/`:761` 的 `discard()`）。且**这一项必须有**：构造函数 `setPersistenceRequired()`（`NpcEntity.java:171`）会被 `load` 覆盖（`Mob.java:461` 无守卫） | P3（C 的叙述正确） |
| "约 11 tick 后递减到 0" 应改为"第 11 tick 起递减、第 20 tick 到 0" | **成立** | `BodyRotationControl#clientTick`：站桩分支 `this.headStableTime++`（`:31`），`if (this.headStableTime > 10) this.rotateHeadTowardsFront();`（`:32-34`）；`rotateHeadTowardsFront` 里 `int i = headStableTime - 10; float f = clamp(i/10.0F, 0, 1); f1 = getMaxHeadYRot() * (1 - f);`（`:48-53`）。⇒ 第 11 tick 进入该分支（允许量 67.5°）、`headStableTime == 20` 时 f=1、允许量 0 | P3 + D2（`NpcEntity.java:72-73`、`docs/NPC系统总设计.md:221-222`） |
| 优先级 3<5 "永久挡死" | **成立** | `WrappedGoal#canBeReplacedBy` 要求 `other.getPriority() < this.getPriority()`（`WrappedGoal.java:23-25`）；`GoalSelector.java:97-110` 逐个 Flag 检查。`MeleeAttackGoal` 占 MOVE+LOOK（`MeleeAttackGoal.java:31`）、`LookAtPlayerGoal` 占 LOOK（`LookAtPlayerGoal.java:38`）⇒ 数值更大的攻击 goal 在 look goal 运行期间**无法启动**且不报错。C 的补充也对：look goal 被挤掉是良性的（`MeleeAttackGoal#tick:106` 自己 setLookAt） | P3（C 与文档一致且正确） |
| `clampHeadRotationToBody` 条件 | **成立** | `LookControl#clampHeadRotationToBody` = `if (!this.mob.getNavigation().isDone()) { ... rotateIfNecessary(..., getMaxHeadYRot()) }`（`LookControl.java:76-80`，条件在 `:77`），由 `tick()` 末行无条件调用（`:73`）。站桩时 `isDone()` true ⇒ 不夹取 ⇒ "头先转"的基石成立 | P3 |
| `yBodyRot` 不同步 | **成立**，且我独立找到 E 漏掉的加强证据 | `LivingEntity#recreateFromPacket` 里 `this.yBodyRot = packet.getYHeadRot();`（`LivingEntity.java:3717`）——客户端只在**生成时**初始化一次，之后双端各自算。⇒ "服务端写 `yBodyRot` 对画面没用、覆写 `tickHeadTurn` 双端都生效"完全成立 | P3（E 的 m8 也报了，两报告一致） |
| 未启动 goal 不调 `stop()` | **成立** | `GoalSelector` 清理循环的前提是 `wrappedgoal.isRunning()`（`GoalSelector.java:87-91`），而 `isRunning` 只在 `WrappedGoal#start()` 置 true（`:43-48`）、`stop()` 置 false（`:51-56`）。⇒ `customServerAiStep` 兜底与 `NpcAttackGoal.stop()` 两条路径确实不冗余（文档 §3.7 的"分工"说法成立） | P3 |
| `flag && super.canUse()` 顺序更优 | **成立** | `MeleeAttackGoal#canUse` 首动作是 `long i = this.mob.level().getGameTime(); if (i - this.lastCanUseCheck < 20L) return false; else { this.lastCanUseCheck = i; ...}`（`MeleeAttackGoal.java:36-40`）。短路使"无攻击指令时不刷新限流时间戳"⇒ C 的推论正确：注释里"下令后最多等 1 秒"只在连续下令之间成立 | P4（注释措辞） |
| `attackCommandActive` 不持久化可接受 | **成立** | 字段 `NpcEntity.java:165` 是普通私有字段；全项目**只有** `TornadoEntity.java:370/380` 有 `addAdditionalSaveData`/`readAdditionalSaveData`（我 grep 过 `src/main/java`），`NpcEntity` 没有；`Mob#target` 本身也不持久化 | P3（文档补一句） |
| `createMobAttributes()` 实为 ~21 项、不 add `ATTACK_DAMAGE` 会抛 | **成立** | 见 E-M2 行（我独立数出 20+1=21）；`Mob#doHurtTarget` 首行 `getAttributeValue(Attributes.ATTACK_DAMAGE)`（`Mob.java:1491-1492`）⇒ 未注册属性的访问会抛 | P3 + D2 |
| 平方公式 `(档位×属性)²/0.1` | **成立（带定义域）** | 我核实了三个支点：`Mob#setSpeed` = `super.setSpeed(speed); this.setZza(speed);`（`Mob.java:556-560`）、`PathNavigation#doStuckDetection` 的 `float f = this.mob.getSpeed() >= 1.0F ? this.mob.getSpeed() : this.mob.getSpeed() * this.mob.getSpeed();`（`PathNavigation.java:312`，**仅在 <1.0 时平方**，C/E 都引对了这一行）、`LivingEntity#setSpeed` 在 `:2443`。C 额外给出的"`档位×属性 > 1` 时归一化使平方退化为线性"是正确的边界说明 | P3（C 的补充有价值，建议进文档） |
| `>` 比 `!=` 更对 | **成立** | `NpcEntity.java:363-364` 的 `getAttributeValue(...) > getAttributeBaseValue(...)`。C 的结论（缓慢/负面 modifier 不会误判成 run）正确；C 指出"净效果恰好等于基础值会漏判"也正确（`AttributeInstance#getValue` 是基础值+modifier 有序求和） | P4 |
| GeckoLib 过渡参数单位 | **无法静态确认（我）** | 本地缓存只有 GeckoLib 4.7.x 的 sources，项目用的是无源码的 4.9.2 编译产物（E 的越界发现 #5 已记录）。C 说"语义已核实为 tick"我**无法复现**；`NpcEntity.java:117-119` 自己写的是"动画之间的过渡时长（tick）" | 见"我无法静态确认的" |
| `isMoving()` 真实判据与最多 3 tick 滞后 | **部分成立（我无法核 GeckoLib 行号）** | C/E 都引 `GeoEntityRenderer.java:266-268`，同样缺 4.9.2 源码。C 给出的 `updateInterval` 默认值 `3`（`EntityType.Builder` 内 `private int updateInterval = 3`）我确认 `ModEntities.java:68-73` 未调 `updateInterval(...)`（对比 `TORNADO` 显式 `updateInterval(1)`，`ModEntities.java:49`）⇒ **"有约 3 tick 同步滞后"这一结论成立**，具体数值行号待有源码时核 | P4（注释） |
| `clientTrackingRange` 单位是区块 | **成立** | C 引 `ChunkMap` 的两处 `entitytype.clientTrackingRange() * 16` 我未逐行核，但 `ModEntities.java:71` 的 `clientTrackingRange(10)` 与"10 区块 = 160 格 ≫ 9 格模型"这个结论的**量级判断**成立 | P4 |
| `Vec3Argument.vec3()` 允许 `~` | **成立（结论）** | `NpcCommand.java:45` 用 `Vec3Argument.vec3()`，`NpcCommand.java:48` 用 `Vec3Argument.getVec3(ctx, "pos")`（相对**命令源**解析）。C 的"相对执行者而非 NPC"这一点正确且值得写进命令 Javadoc | P4（注释） |
| Brigadier 同名根字面量静默合并 | **部分成立（当前无风险）** | 我确认全项目只有 `NpcCommand.java:40` 注册 `beloong` 根（`BeLoongCore.java` 是唯一调用点）。⇒ 潜在风险成立、**不是现行缺陷** | P4（注释备一笔） |
| 先例行号（`Warden:555`/`Bat:85`/`Parrot:392` 等） | **成立** | 我抽验了 `Bat.java:84-87`（`public boolean isPushable() { return false; }`，覆写在 85）与 `Parrot.java:391-394`（覆写在 **392**，返回 true）。C 的这三处行号命中 | P3（C 的引用可用） |

### E-C2 `lookTime` 实为 20–40 而非 40–80

- **裁决**：**成立**；C-M2 与 E-C2 是同一发现（C 编号 `M2`、E 编号 `C2`），两人结论一致且都对。
- **我的核实**：`LookAtPlayerGoal#start` = `this.lookTime = this.adjustedTickDelay(40 + this.mob.getRandom().nextInt(40));`（`LookAtPlayerGoal.java:93`）；`Goal#adjustedTickDelay` = `this.requiresUpdateEveryTick() ? adjustment : reducedTickDelay(adjustment)`（`Goal.java:46-48`）、`reducedTickDelay` = `Mth.positiveCeilDiv(reduction, 2)`（`Goal.java:50-52`）；`Goal#requiresUpdateEveryTick()` 默认 false（`Goal.java:25-27`）且 `LookAtPlayerGoal` **全文 109 行内没有该覆写**（我通读确认，含 `tick()` 在 `:101-108`、无 `requiresUpdateEveryTick`）。
- **精确区间**：`nextInt(40) ∈ [0,39]` ⇒ 原式 ∈ `[40,79]` ⇒ 折半后 `lookTime ∈ [20, 40]`（tick），即 **1–2 秒**。
- **所有需要改的文字位置**：

  | 文件:行 | 现状 | 应改为 |
  |---|---|---|
  | `NpcEntity.java:246` | `// lookTime 40~80 tick 一轮、到期立刻重启 ⇒ 实际是持续跟随。` | `// 源码值 40~79，经 Goal#adjustedTickDelay 折半后实际 20~40 tick 一轮；到期立刻重启 ⇒ 实际是持续跟随。` |
  | `docs/NPC系统总设计.md:195` | `` `lookTime` 40~80 tick 一轮、到期立刻重启 `` | 同上口径 |
  | **决策 17（`docs/NPC系统总设计.md:824-825`）** | 只讲 `probability` 给 1.0 **与"原版默认 0.02 会让 NPC 平均 2.5 秒才看玩家一眼"，不含 40~80 数字** | **无需改**（我逐字核对了 824-825 行，E 的 C3 建议把决策 17 列为"同样的 40~80"是**不准确**的——决策 17 没有这个数字） |
  | `NpcEntity.java:247` | `LookAtPlayerGoal(this, Player.class, facePlayerDistance(), 1.0F)` | 无需改（代码正确） |

  ⇒ **需要改的只有 2 处（1 处源码注释 + 1 处文档），不是 3 处**。这是我对 E-C2 的修正。
- **重定级**：**P3 + D2**（此误不改变运行时行为，但会误导下一个人把 `lookTime` 当 2–4 秒来推理时间线）。
- **C 附带提的"每轮结束有 1 tick 无目标窗口"**：机制成立（`stop()` 把 `lookAt` 置 null，`LookAtPlayerGoal.java:97-99`；下一 tick `canUse()` 重新取最近玩家），实机可感知性需人眼判断（C 自己也列为 V5）。

### E-C3 三处原版行号指错

- **裁决**：**成立**；但两位审查者给出的"正确值"不一致，本报告给出**确定值**（含"声明行/逻辑行"区分）。
- **我的核实（逐行自读 jar 内源码）**：

  | 引用 | 引用处的原文实际内容 | 唯一正确值 |
  |---|---|---|
  | `LivingEntity.java:1084` | 属于 `onEffectRemoved` 附近的 `serverplayer.connection.send(new ClientboundRemoveMobEffectPacket(...))` 区段，**不是** `hurt` | **方法声明 `LivingEntity.java:1142`（`public boolean hurt(DamageSource source, float amount) {`）、第一道闸门 `:1143`（`if (this.isInvulnerableTo(source)) {`）**。引用"`hurt` 的第一道闸门"时写 **1143** 即可；想覆盖方法签名则写 **1142-1143**（C 的写法更完整，E 的 `:1143` 单值不够） |
  | `Mob.java:437` | `compound.putString("DeathLootTable", this.lootTable.location().toString());`（**写出**侧） | **`Mob.java:461`**：`this.persistenceRequired = compound.getBoolean("PersistenceRequired");`（无 `contains` 守卫）。写出侧是 **392** |
  | `Entity.java:1759` | `addAdditionalSaveData` 内的空行 | **`Entity.java:1854`**：`this.invulnerable = compound.getBoolean("Invulnerable");`（无守卫）。写出侧是 **1752** |
  | `Entity.java:2681-2686`（C 认为应为 `2681-2687`） | 2681 声明、2682-2685 四个 vanilla 条件、**2686 `CommonHooks.isEntityInvulnerableTo(...)`**、**2687 `}`** | **`Entity.java:2681-2687`**（方法体完整范围）。**`&& !source.isCreativePlayer()` 确定在 `:2683`**（该行为 `\|\| this.invulnerable && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) && !source.isCreativePlayer()`）⇒ **C 的 `2681-2687` 成立；E 给 `2681-2686 ✅` 是把方法体少算一行**（E 自己在表里也写了 2686 是 `CommonHooks...`，与它打 ✅ 的范围矛盾） |

- **被错误行号污染的所有位置**（我逐个 grep 确认，非转述）：

  | 错误值 | 污染位置 |
  |---|---|
  | `Entity.java:1759` | `NpcEntity.java:46`（类注释）、`docs/NPC系统总设计.md:166` |
  | `Mob.java:437` | `NpcEntity.java:47`（类注释）、`docs/NPC系统总设计.md:167` |
  | `LivingEntity.java:1084` | `NpcEntity.java:178`（`isInvulnerableTo` Javadoc）、`docs/NPC系统总设计.md:161`（§3.2 表） |
  | `Entity.java:2681-2686` | `NpcEntity.java:186`（Javadoc）、`docs/NPC系统总设计.md:87`（§1.3）、`docs/NPC系统总设计.md:795`（决策 4） |

  共 **8 处**（4 个错误值 × 各自的代码注释 + 文档）。E 的 C3 只列了 `NpcEntity.java:46-47`、`:161/178` 与文档 `:161/:166-167`，**漏了 `Entity.java:2681-2686` 在 `NpcEntity.java:186` + 文档 `:87`/`:795` 这三处**。
- **重定级**：**P3 + D2**（行号不改变任何行为，但项目约定"结论必须带证据"——错行号会让下一个人核实失败）。注意 C 把它列为 Minor（m4）、E 列为 Critical——两者阈值分歧的典型样本；按"仅文档/注释与代码不符"的定义，正确级别是 P3。

### E-C5 创造/旁观排除条件在 `canContinueToUse()` 与 `stop()`，不在 `canUse()`

- **裁决**：**成立**。
- **我的核实**：`MeleeAttackGoal#canUse`（`MeleeAttackGoal.java:35-60`）只检查 20 tick 限流、`target == null`、`!isAlive()`、寻路可达——**没有任何创造/旁观判断**。排除条件出现在：`canContinueToUse` 的 `:74`（`!(livingentity instanceof Player) || !livingentity.isSpectator() && !((Player)livingentity).isCreative();`）与 `stop` 的 `:89`（`if (!EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(livingentity)) this.mob.setTarget(null);`）。`EntitySelector.NO_CREATIVE_OR_SPECTATOR` 定义在 `EntitySelector.java:31`。
- **`NpcEntity.java:262` 那行注释该改成什么**：现状是

  ```java
  // 与 MeleeAttackGoal.canUse() 的排除条件保持一致
  ```

  应改为

  ```java
  // 与 MeleeAttackGoal.canContinueToUse()/:74、stop()/:89 同一谓词（EntitySelector.NO_CREATIVE_OR_SPECTATOR）保持一致
  ```

  （`NpcEntity.java:263` 的代码 `!EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target)` 本身是对的，只有注释指错了方法。）
- **同步要改的文档**：`docs/NPC系统总设计.md:310`（"`MeleeAttackGoal.canUse()` 返回 false"）应拆成"目标死亡时 `canUse()`/`canContinueToUse()` 返回 false；目标变成创造/旁观玩家时 `canContinueToUse()`（`:74`）与 `stop()`（`:89`）会把目标放掉"；`docs/NPC系统总设计.md:313`（"与 `canUse()` 一致的排除条件"）应改为"与 `stop()`/`canContinueToUse()` 同一谓词"。
- **重定级**：**P3 + D2**。

### E-M2 `createMobAttributes()` 项数

- **裁决**：**成立（E 的 21 项正确，C 的"20 项 + FOLLOW_RANGE"表述同样正确）**。文档的"只含 5 项"是错的。
- **准确项数与完整清单**（我逐行读 `LivingEntity.java:321-343` 后数出）：

  `LivingEntity.createLivingAttributes()`（`LivingEntity.java:321-343`）共 **20** 项，按源码顺序：
  1. `MAX_HEALTH`（`:323`）
  2. `KNOCKBACK_RESISTANCE`（`:324`）
  3. `MOVEMENT_SPEED`（`:325`）
  4. `ARMOR`（`:326`）
  5. `ARMOR_TOUGHNESS`（`:327`）
  6. `MAX_ABSORPTION`（`:328`）
  7. `STEP_HEIGHT`（`:329`）
  8. `SCALE`（`:330`）
  9. `GRAVITY`（`:331`）
  10. `SAFE_FALL_DISTANCE`（`:332`）
  11. `FALL_DAMAGE_MULTIPLIER`（`:333`）
  12. `JUMP_STRENGTH`（`:334`）
  13. `OXYGEN_BONUS`（`:335`）
  14. `BURNING_TIME`（`:336`）
  15. `EXPLOSION_KNOCKBACK_RESISTANCE`（`:337`）
  16. `WATER_MOVEMENT_EFFICIENCY`（`:338`）
  17. `MOVEMENT_EFFICIENCY`（`:339`）
  18. `ATTACK_KNOCKBACK`（`:340`）
  19. `NeoForgeMod.SWIM_SPEED`（`:341`）
  20. `NeoForgeMod.NAMETAG_DISTANCE`（`:342`）

  再加 `Mob.createMobAttributes()` 的 `.add(Attributes.FOLLOW_RANGE, 16.0)`（`Mob.java:159-161`）⇒ **`createMobAttributes()` 共 21 项**。`PathfinderMob` 不再追加（`PathfinderMob.java` 全文 78 行，无 `createMobAttributes` 覆写）。
- **"不含 `ATTACK_DAMAGE`"与"必须显式 add"都正确**：`ATTACK_DAMAGE` 的默认值是 `2.0`（`Attributes.java:27-29`），但**不在 supplier 里**⇒ 不 add 时 `Mob#doHurtTarget` 首行（`Mob.java:1491-1492`）读它会找不到属性实例。
- **需改位置**：`NpcEntity.java:137-139`、`docs/NPC系统总设计.md:343-345`。建议只留结论（"21 项，不含 `ATTACK_DAMAGE`"）而不枚举，避免下次清单又被原版增项打脸。
- **重定级**：**P3 + D2**。C 的对应条目编号是 `m1`（C 报告里"`C-M2`"不存在，C 的 `M2` 是 `lookTime`；C 用 `m1` 报属性清单）。

### E-M3 "11 tick 递减到 0"

- **裁决**：**成立**（与 C 报告"已验证正确"节第 7 条为同一发现，两人结论一致；C 把它放在"需修正的措辞"里，E 单列为 Major）。
- **我的核实**：见上表"约 11 tick 后递减到 0"一行（`BodyRotationControl.java:31-34` 与 `:48-53`）。精确表述：**`headStableTime` 从 0 起每 tick +1；`> 10` 即第 11 tick 起进入 `rotateHeadTowardsFront()`，此时允许量 `75° × (1 − i/10)`，`i = headStableTime − 10`；到 `headStableTime = 20`（i=10 ⇒ f=1）允许量降到 0，身体与头完全对齐。⇒ 共约 20 tick（1 秒），不是 11 tick。**
- **重定级**：**P3 + D2**（`NpcEntity.java:72-73`、`docs/NPC系统总设计.md:221-222`；C 与 E 都提到 §10.1 的"允许量会递减到 0"表述正确，无需改）。

---

## 原版行号核对表（确定值）

> 全部值来自本次自读 `sourcesAndCompiledWithNeoForge_0fdaf824..._output.jar`。
> "需要同步修改的文件:行"只列**引用错的那一处**；标 `—` 表示该引用正确、无需改。

| 被引用的位置 | 引用值 | 正确值（确定） | 需要同步修改的文件:行 |
|---|---|---|---|
| `hurt` 的第一道闸门 | `LivingEntity.java:1084`（C: m4 / E: C3） | 声明 **1142**、闸门 **1143**（完整写法 `1142-1143`） | `NpcEntity.java:178`、`docs/NPC系统总设计.md:161` |
| `load()` 读回 `Invulnerable` | `Entity.java:1759` | **`Entity.java:1854`**（写出侧 **1752**） | `NpcEntity.java:46`、`docs/NPC系统总设计.md:166` |
| `load()` 读回 `PersistenceRequired` | `Mob.java:437` | **`Mob.java:461`**（写出侧 **392**；437 确实是 `DeathLootTable` 的写出） | `NpcEntity.java:47`、`docs/NPC系统总设计.md:167` |
| 原版 `isInvulnerableTo` 方法体 | `Entity.java:2681-2686` | **`Entity.java:2681-2687`**；`&& !source.isCreativePlayer()` 在 **2683** | `NpcEntity.java:186`、`docs/NPC系统总设计.md:87`、`docs/NPC系统总设计.md:795` |
| `requiresCustomPersistence` 定义 | `Mob.java:736` | `Mob.java:736-738`（`return this.isPassenger();`） | — |
| `checkDespawn` 闸门 | `Mob.java:746-749` / `Mob.java:749` | 方法 `745`、NeoForge `EventHooks.checkMobDespawn` `746`、**闸门 `749`** | — |
| `tickHeadTurn` 覆写 | `Mob.java:377-381` | `Mob.java:377-381` | — |
| `setSpeed` 同时写 `zza` | `Mob.java:557-560` | `Mob.java:556-560`（`@Override` 556、`public void setSpeed` **557**、`setZza` **559**） | —（C 写 `557-560`、E 写 `557-560`，都可用） |
| `isEffectiveAi` | `Mob.java:1420` | `Mob.java:1419-1422`（`return super.isEffectiveAi() && !this.isNoAi();` 在 **1421**） | — |
| `isControlledByLocalInstance` | `Entity.java:3215-3217` | `Entity.java:3215-3217` | — |
| `travel` 被 `isControlledByLocalInstance()` 包住 | `LivingEntity.java:2219-2220` | `2219-2220` 起、`}` 在 **2344**，方法体只剩 **2346** 的 `calculateEntityAnimation` 在外 | —（C 的"整个方法体"已自行加了限定） |
| 玩家默认移速属性 0.1 | `Player.java:231` | 与之相符（C/E 均报命中，我未复读 `Player.java`——它不在本次抽取范围，且与实体/AI 无裁决关系） | — |
| `doStuckDetection` 平方速度 | `PathNavigation#doStuckDetection:312` | `PathNavigation.java:312`（且**仅 `getSpeed() < 1.0` 时**平方） | — |
| `MeleeAttackGoal.stop()` 自己停导航 | `MeleeAttackGoal.java:94` | **确认 `:94`**（`:87` 声明、`:88-91` 条件清 target、`:93` `setAggressive(false)`、`:94` `getNavigation().stop()`） | — |
| `MeleeAttackGoal.canContinueToUse` 的排除条件 | `:74` | **确认 `:74`** | — |
| `MobCategory.MISC` 元组 | `("misc", -1, true, true, 128)` | 与文档一致（C 未核字段序；E 核了 `MobCategory.java:15`） | — |
| `isPushable` 先例 | `Warden:555`、`Bat:85`、`Parrot:392` | 我抽验 `Bat.java:85`、`Parrot.java:392` 命中（`Warden:555` 未复读，属低风险） | — |
| `NodeEvaluator` 用 `getBbWidth()+1` | `NodeEvaluator.java:30-32` | **确认 `:30`（entityWidth）、`:32`（entityDepth）** | — |
| 准星拾取的膨胀量 | `ProjectileUtil.java:108-110`（6 参版 `0.3F`）与 `:123`（`inflate(0.3)`） | **准星路径走 `ProjectileUtil.java:68-76`，膨胀量来自 `:75` 的 `getPickRadius()`，`Entity#getPickRadius()` 默认 `0.0F`（`Entity.java:2231-2233`）**；`:108-110`/`:123` 是**弹射物**重载 | 引用本身（C-M4 表内）；结论应改为"裸盒、膨胀 0" |
| `getBoundingBoxForCulling` | `Entity.java:3030-3032` | **确认 `:3030-3032`**（直接返回 `getBoundingBox()`，无旋转） | — |
| `shouldRenderAtSqrDistance` | `Entity.java:1692-1700` | **确认 `:1692-1700`**（`getBoundingBox().getSize() * 64.0 * viewScale`） | — |
| `Explosion` 用 `EXPLOSION_KNOCKBACK_RESISTANCE` | `Explosion.java:293-297` / `:304` | **确认**：`:294` 读属性、`:304` `setDeltaMovement(add(vec31))` | — |
| `isPushedByFluid` 门禁 | `Entity.java:3358-3391` | **确认**：门禁 `:3358`、写入 `:3391`；默认实现在 `:2847-2850` | — |
| `createLivingAttributes` 项数 | 文档称 5 项 | **20 项（`LivingEntity.java:321-343`）+ FOLLOW_RANGE（`Mob.java:159-161`）= 21** | `NpcEntity.java:137-139`、`docs/NPC系统总设计.md:343-345` |
| `LookAtPlayerGoal` 的 `lookTime` | 40~80 | 源码 `[40,79]`（`LookAtPlayerGoal.java:93`），经 `Goal.java:46-52` 折半后 **`[20,40]`** | `NpcEntity.java:246`、`docs/NPC系统总设计.md:195` |
| `BodyRotationControl` 递减到 0 | "约 11 tick 后" | **第 11 tick 起递减（`BodyRotationControl.java:32`）、第 20 tick 到 0（`:48-53`）** | `NpcEntity.java:72-73`、`docs/NPC系统总设计.md:221-222` |
| `NO_CREATIVE_OR_SPECTATOR` 的用法 | "在 `canUse()` 里" | 在 `canContinueToUse()` `:74` 与 `stop()` `:89`；定义 `EntitySelector.java:31` | `NpcEntity.java:262`、`docs/NPC系统总设计.md:310`、`:313` |

---

## 合并后的去重清单

> 编号 `N*` 为本报告新编号，括号内标出对应的原报告编号。级别按本报告统一阈值重定。

| 编号 | 一句话 | 级别 | 涉及文件 |
|---|---|---|---|
| N1（C-C1） | `walkTo()` 后路径被攻击 goal 的 `stop()`（`MeleeAttackGoal:94`）抹掉 ⇒ walk 静默失效；**C 的修法无效**，正确修法见本报告 | **P1** | `NpcEntity.java:280-288`、`326-330`、`NpcAttackGoal.java:43-46` |
| N2（C-M1） | `/summon` 后 `invulnerable` 字段为 false ⇒ `canBeSeenAsEnemy()` true ⇒ 原版敌对生物把 NPC 当目标围打（打不动但会寻路+挥击） | **P1** | `NpcEntity.java:167-172`、`190-193`、`:44-48` 注释 |
| N3（C-M2 / E-C2） | `lookTime` 注释/文档写 40~80，实为 `[20,40]` tick（`adjustedTickDelay` 折半）。**只有 2 处需改，决策 17 不含此数字** | **P3 + D2** | `NpcEntity.java:246`、`docs/NPC系统总设计.md:195` |
| N4（C-M3） | "不可推动"有两条真缺口：爆炸位移（走 `EXPLOSION_KNOCKBACK_RESISTANCE`）、流体流（走 `isPushedByFluid`）。**补属性只堵一半，必须同时覆写 `isPushedByFluid()`** | **P1** | `NpcEntity.java:154-160`、`:195-199` |
| N5（C-M4 之①②） | 准星拾取**膨胀为 0**（`getPickRadius()` 默认 0.0，C 引的 `0.3F` 是弹射物重载）⇒ 头/尾点不中，挡在对话系统前面；近战需贴到约 1.9 格才判命中 | **P1** | `NpcEntity`（建议覆写 `getPickRadius()`）、`ModEntities.java:70` |
| N6（C-M4 之③） | 寻路按 `getBbWidth()+1 = 2` 格宽处理，长尾必然穿墙 —— **当前无副作用，不建议撑大碰撞箱** | **P3** | `ModEntities.java:70` |
| N7（C-M4 之④） | `getBoundingBoxForCulling()` 返回不随 `yRot` 旋转的轴对齐盒 ⇒ 头/尾在盒外时整条龙被剔除 | **P2**（转渲染组） | `ModEntities.java:70` |
| N8（C-M5） | 文档把 `MobCategory.MISC.isPersistent` 当"实体持久"是错的（只影响自然生成器；持久性靠 `requiresCustomPersistence()`） | **P3 + D2** | `ModEntities.java:60`、`docs/NPC系统总设计.md:406-407` |
| N9（C-M6） | `NoAI` 下 `serverAiStep` 整段不跑、兜底与 goal 一起休眠 —— **本模组无任何设置 `NoAI` 的路径，属理论风险**；顺带：漏调 `super.customServerAiStep()`（当前父实现为空） | **P2** | `NpcEntity.java:251-267` |
| N10（C-m1 / E-M2） | `createMobAttributes()` 清单描述错：实为 **21 项**（20 + FOLLOW_RANGE），不含 `ATTACK_DAMAGE`（后者结论正确） | **P3 + D2** | `NpcEntity.java:137-139`、`docs/NPC系统总设计.md:343-345` |
| N11（C-clean / E-M3） | "头停下约 11 tick 后允许量递减到 0" 应改为"第 11 tick 起递减、第 20 tick 到 0" | **P3 + D2** | `NpcEntity.java:72-73`、`docs/NPC系统总设计.md:221-222` |
| N12（C-m4 / E-C3） | 4 组原版行号错，污染 **8 处**（`NpcEntity.java:46/47/178/186` + 文档 `:87/:161/:166/:167/:795`）；`Entity.java:2681-2686` 应写 `2681-2687`（2683 是 `!isCreativePlayer()`） | **P3 + D2** | 见核对表 |
| N13（E-C5） | 创造/旁观排除在 `canContinueToUse()` `:74` 与 `stop()` `:89`，**不在** `canUse()`；`NpcEntity.java:262` 的注释与文档 `:310`/`:313` 指错方法 | **P3 + D2** | `NpcEntity.java:262`、`docs/NPC系统总设计.md:310`、`:313` |
| N14（C-m3 的注释面） | `clearAttackCommand()` 名为"清标志"实为"清 target + 停导航"，且 `public`——N1 的修法会同时改善这一点 | **P3** | `NpcEntity.java:325-330` |
| N15（C-N7） | `NpcAttackGoal.canUse()` 的 `flag && super.canUse()` 顺序确实是更优选择，"下令后最多等 1 秒"的注释应改为"只在连续下令之间成立" | **P4** | `NpcAttackGoal.java:20-21` |
| N16（C-m6 / E-m8 的实体面） | `attackCommandActive` 不持久化（设计上可接受，建议在文档明确写一句）；`yBodyRot` 只在生成包初始化一次 | **P3** | `NpcEntity.java:165`、`docs/NPC系统总设计.md:224-225` |
| N17（C-m5 部分） | GeckoLib `isMoving()` 判据表述（分量绝对值平均 / 阈值 0.015 / `updateInterval` 默认 3 的同步滞后）；**我无法核 4.9.2 行号** | **P4** | `NpcEntity.java:350`、`docs/NPC系统总设计.md:287-288` |
| N18（C-m2） | `npcsIn()` 静默丢弃混合选择里的非 NPC（`/beloong npc walk @e` 会只动 NPC 并回报数量 1） | **P2** | `NpcCommand.java:115-120`、`:24` 注释 |
| N19（C-m7） | Brigadier 同名根字面量的静默合并是潜在特权风险（当前全 mod 只有一处注册 `beloong`，非现行缺陷） | **P4** | `NpcCommand.java:40-41` |

**按级别汇总**：P1 = N1、N2、N4、N5（4 条，全部有可达触发路径）；P2 = N7、N9、N18（3 条）；P3 = 其余 9 条中的 8 条；P4 = 4 条。**P0 = 0 条**——没有任何一条达到"崩溃/数据损坏/玩家断线/核心语义被绕过/功能完全不可用"。

---

## 审查者报告中的错误

| # | 报告 | 位置 | 错误 | 我的更正 |
|---|---|---|---|---|
| 1 | C | C1"建议修法"（`:68-80`） | **修法无效**。C 在"安全性检查"里引用了 `MeleeAttackGoal.java:94`（`super.stop()` 自己停导航），却据此得出"去掉 `clearAttackCommand()` 的 `nav.stop()` 就够了" | 见 N1 的方案 (A)/(B) |
| 2 | C | M1 建议修法（`:107-108`） | "在 `readAdditionalSaveData` 里 `super` 之后重新 `setInvulnerable(true)`"**方向正确但缺关键前提**——没说为什么这次不会被覆盖 | 前提是 `Entity.java:1894` 的 `this.readAdditionalSaveData(compound)` **晚于** `:1854` 的字段覆写；这正是它有效的唯一原因 |
| 3 | C | M3 建议修法（`:141-143`） | 把 `isPushedByFluid()` 覆写写成"若连水流也要挡"的**可选项** | 爆炸与流体是两条独立缺口，属性**只**堵爆炸；两者必须一起做，否则"不可推动"的语义仍不成立 |
| 4 | C | M4 表（`:156`） | 引 `ProjectileUtil.java:108-110`（`0.3F`）与 `:123` 作为准星拾取的膨胀量 | **客户端准星走 `ProjectileUtil.java:68-76`，膨胀量是 `getPickRadius()`（默认 `0.0F`，`Entity.java:2231-2233`）**；`:108-110`/`:123` 是弹射物重载。C 的**结论**（头/尾点不中）反而更严重 |
| 5 | C | m4 / 行号核对表（`:235`、`:319`） | 把 `Entity.java:2681-2686` 的修正写成"范围略窄"⚠️，同时把 `LivingEntity.java:1084` 的正确值写 `1142-1143` | `isInvulnerableTo` 应写 **`2681-2687`**（2687 是闭合括号）；`hurt` 的闸门是 **1143**（声明 1142）——两者都要写清"声明行/逻辑行" |
| 6 | C | m1（`:205`） | 把 `LivingEntity.createLivingAttributes()` 数成 **20 项** 并明说 `Mob.createMobAttributes()` "再加 FOLLOW_RANGE" —— **表述正确**，但 C 的结论句写成"`createMobAttributes()` 的属性清单"容易与 E 的"21 项"看起来矛盾 | 统一口径：`createMobAttributes()` = **21 项**（20 + FOLLOW_RANGE） |
| 7 | C | 越界发现 3（`:517`）与 `:44-48` | 只把"构造函数 `setXxx` 只为让 NBT 一致"当作"收益表述偏差" | 在 `/summon` 路径上这句话**是错的**（写出的是 `Invulnerable:0b`），应升级为 N2 的一部分（D2） |
| 8 | C | 定级体系 | Critical/Major/Minor 与 E 的阈值不一致（同一 `lookTime` 问题：C=Major、E=Critical；同一行号问题：C=Minor、E=Critical） | 按运行时影响重定为 N3=P3、N12=P3 |
| 9 | E | C2 建议修法（`:36`） | 只说"改文档与注释" | 正确，但**决策 17（`docs/NPC系统总设计.md:824-825`）里并没有 40~80 这个数字**——E 在 C3 的"建议修法"里要求同步改决策 17，属**多报**一处 |
| 10 | E | C3 表（`:46`） | `LivingEntity.java:1143` 单值，并说"偏差 59 行" | 指出方法声明 1142 更完整（C 的 `1142-1143` 写法更可用）；"偏差 59 行"对单一逻辑行成立 |
| 11 | E | 行号核对表（`:184`） | 给 `Entity.java:2681-2686` 打 **✅**，但同一行的"实际位置"列又写 2686 是 `CommonHooks.isEntityInvulnerableTo`、2679 是注释 | **自相矛盾**：方法体到 2687 才闭合，应为 `2681-2687` |
| 12 | E | C3 建议修法（`:50`） | 污染位置只列 `NpcEntity.java:46-47`、`:161/178` 与文档 `:161/:166-167` | **漏了 3 处**：`NpcEntity.java:186`、`docs/NPC系统总设计.md:87`、`docs/NPC系统总设计.md:795`（同属 `Entity.java:2681-2686` 错误值） |
| 13 | E | M2（`:85`） | "18 项原版属性 + 2 项 NeoForge 属性" | 数法正确（我逐行数得 20），但与 C 的"20 项"表述差异是**总项 vs 分项**的口径问题，建议统一写"`createMobAttributes()` 共 21 项" |
| 14 | 两者 | M6 / E 未覆盖 | 两位都把 `NoAI` 兜底休眠当成需要登记的风险 | 我 grep 全项目：**没有任何 `setNoAi(` 调用或 `NoAI` NBT 写入** ⇒ 只有管理员手工命令能触发，应降为 P2 并注明"本模组无路径" |

---

## 我无法静态确认的

| # | 事项 | 为什么不能确认 | 建议怎么确认 |
|---|---|---|---|
| 1 | **N1 的实机复现** | 我只能在静态层面证明"路径被清"的因果链完整且必然；反向的"NPC 真的原地不动"仍属运行时观测 | 让 NPC 处于攻击中（`/beloong npc attack @e[type=...]` 并确认它在追打），紧跟 `/beloong npc walk @e[type=beloong:dihuang_loong] ~ ~ ~8`，观察是否完全不动；再单独 `walk` 一次作对照。实施 N1 的方案 (A) 后重测同样两组 |
| 2 | **N4 的两条位移缺口** | 属性与代码路径已确证，但"炸飞多少格 / 水流带多快"需要实机数值 | 在 NPC 旁爆 TNT 记录坐标差；用水桶在 NPC 旁放水观察漂移。修后重测（预期坐标差 0） |
| 3 | **N5 的模型几何与"头在原点前方几格"** | 属渲染组范围；我未解析 `dihuang_loong.geo.json`（340 个立方体、145 骨骼） | 见 C 的 V4；渲染组应有结论，我不重复 |
| 4 | **GeckoLib 4.9.2 的所有行号与语义** | 本地缓存只有 4.7.5.1 / 4.7.7 的 sources，项目用的是无源码的 `geckolib-388172:8350073` 编译产物（`META-INF/neoforge.mods.toml` 声明 `version="4.9.2"`） | 换带 sources 的坐标，或在 `build.gradle:139` 附近注明所依据的版本。**C 的 m5（`GeoEntityRenderer.java:266-268`）、过渡参数单位、N17 的判据细节全部落在此列** |
| 5 | **`updateInterval` 默认值 = 3** | 我确认了 `ModEntities.java:68-73` 未调 `updateInterval(...)`（对照 `TORNADO` 在 `:49` 显式设 1），但"默认 3"来自 C 引的 `EntityType.Builder` 私有字段行号，我未复读该类 | 若要写进注释，先复读 `EntityType$Builder` |
| 6 | **`clientTrackingRange` 的单位** | C 引 `ChunkMap.java:1113/1355` 的 `* 16`，我未抽该类核实 | 结论（160 格 ≫ 9 格）量级上无争议，但引用前应复读 |
| 7 | **`Warden:555` 与四个 `requiresCustomPersistence` 先例 / 四个 `createBodyControl` 先例** | 我只抽验了 `Bat:85`、`Parrot:392`；其余属"低风险引用"未逐一复读 | 若要保留在文档里当依据，建议一次性复读并记录 |
| 8 | **`Player.java:231`、`MinecraftServer.java:1511`、`Minecraft.java:491` 等非实体/AI 引用** | 本次任务范围是实体/AI，未抽取这些类 | 由其它核验者或后续轮次覆盖 |
