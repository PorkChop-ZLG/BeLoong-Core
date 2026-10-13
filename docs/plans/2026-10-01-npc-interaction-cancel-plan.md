# NPC 不可被移动 实施计划（交互取消 + 禁牵引 + 传送门 + 钓竿）

> ⚠️ 文件名沿用立项时的名字，但**范围已扩大**：本计划覆盖"**NPC 在生存无作弊下不可被玩家移动**"
> 的四条改动 —— ① 取消它身上的原版/第三方右键交互 ② 禁止牵引 ③ 禁止传送门把它送走 ④ 禁止投射物（含钓竿）命中它。
> 依据是一次完整的原版机制排查（结论与出处见下"探明的前置事实"）。
>
> **状态：已实施**（T1–T10 全部完成；守卫 ⑱ 七条全 PASS；四套验证 + 三探针全绿）。

**目标**：本模组的 NPC 在**生存无作弊**下**不可被玩家以任何方式移动**（创造模式与指令不受限），
具体落成上述四条；**不改动原版代码（零 Mixin）**。

**架构**：两处纯模组侧改动 —— ① `NpcDialogueHandler` 对 `NpcEntity` 目标**取消** `PlayerInteractEvent.EntityInteract`（两侧都取消，早于现有 `isClientSide()` 早退），我们自己的对话逻辑一行不动继续执行；② `NpcEntity` 覆写原版官方闸门 `Mob#canBeLeashed()` 返回 `false`。

**选定方案**：**B —— 只做两层**（取消交互 + `canBeLeashed=false`），**不做**每 tick 自愈。用户裁定：存档里已经上绳的 NPC 由用户手动处理。

---

## 探明的前置事实（实施依据，勿凭记忆改写）

| 事实 | 出处 |
|---|---|
| 我们的 NPC 是 `Mob` | `NpcEntity.java:150`（`extends PathfinderMob implements GeoEntity`）|
| `Mob` 实现 `Leashable`（牵引机制长在 `Mob` 上）| `Mob.java:89` |
| **默认闸门**：`canBeLeashed()` = `!(this instanceof Enemy)` ⇒ 我们**没覆写** ⇒ 可被牵 | `Mob.java:1404-1407` |
| 右键上绳的入口是 `Entity#interact`，**唯一条件** `canHaveALeashAttachedToIt()` | `Entity.java:2014-2037`，条件在 `:2026` |
| 该条件 = `canBeLeashed() && !isLeashed()` | `Leashable.java:33-35` |
| 被牵的后果很重：`leashTooFarBehaviour()` 会 `goalSelector.disableControlFlag(Goal.Flag.MOVE)`；`Leashable.tickLeash` 每 tick 拉扯 | `Mob.java:1398-1402`、`Entity.java:491-492` |
| **取消事件确实能拦住原版交互** | `Player.java:1720`、`:1765`（`if (post(event).isCanceled()) return;`）|
| 我们的对话受理**自己做主**、不看原版交互的返回值 ⇒ 取消不会影响它 | `NpcDialogueHandler.java:37-80` |
| 该类注释**明文写着"刻意不取消事件"**（首版 D4）⇒ 本轮结论相反，必须改写 | `NpcDialogueHandler.java:26-27` |
| 该事件**两侧都会派发** ⇒ 只取消服务端那侧不够 | `NpcDialogueHandler.java:40-46` |
| ✅ **已确认**：`EntityInteract` **实现 `ICancellableEvent`** ⇒ 可取消；取消后默认返回 `PASS`（"未处理"），语义正合需要 | `PlayerInteractEvent.java:118-119`、`:216-217` |
| **传送门**：`handlePortal` 用 `canUsePortal(false)` 决定要不要走传送（**另一道** `canChangeDimensions` 在目标解析**之后**，改它会连指令一起挡掉）| `Entity.java:2274`、`:2281`；`canUsePortal` 默认 `(allowPassengers \|\| !isPassenger()) && isAlive()` 在 `:2781-2783` |
| **投射物**：`Projectile#canHitEntity` 第一句就是 `!target.canBeHitByProjectile()` ⇒ 挡这里最准 | `Projectile.java:257-258`；`Entity#canBeHitByProjectile()` = `isAlive() && isPickable()` 在 `:1663-1665` |
| ⚠️ **陷阱一**：**不能**改用 `isPickable()` 挡投射物 —— 它同时是**客户端准星选取**的依据 ⇒ 改 false 后玩家**点不到 NPC**，对话直接废 | `Entity.java:1667`（默认 false，`LivingEntity` 覆写为 true 才有今天的右键）|
| ⚠️ **陷阱二**：钓竿钩中实体那一步**没有任何排除条件**（直接 `setHookedEntity(result.getEntity())`）| `FishingHook.java:270-275` |
| **药水**：`addEffect` 与 `forceAddEffect` **都**先过 NeoForge 的同一个钩子 ⇒ `canBeAffected` 已被 `MobEffectEvent.Applicable` 取代（本轮**不做**，见"非目标"）| `LivingEntity.java:971-972`、`:1014-1015`；`CommonHooks.java:1548-1550` |
| 我们全局**只有一处**给 NPC 上效果（将来要放行就从这里改）| `MynpcCommand.java:270` |

## 非目标（用户已裁定）

- **不做**每 tick 自愈（不加 `dropLeash` 巡检）；存档里已上绳的 NPC 由用户手动处理
- **不改** `MobCategory`（保持 `MISC`：本就 `isFriendly=true`、持久、不占刷怪上限）
- **不做**"阵营"（`isAlliedTo` / 积分板队伍）—— 现状既非 `Enemy` 也非 `Monster`，已不在任何原版敌对名单里
- **本轮不做药水防护**（用户裁定"丙"）：`MobEffectEvent.Applicable` + 单 tick 许可标志的方案已调研清楚，
  但本轮不实施。可行性与残留风险见文末"待办与残留风险"
- **明确不做**（本轮排查中发现、但用户未选）：活塞/粘性活塞 · 流水与岩浆流 · `FloatGoal` 的水中自我游动 ·
  "挖掉脚下导致掉落"（世界机制，是否算"被玩家移动"由用户日后再定）
- **不改**原版代码（无 Mixin）；**不新增**交互白名单机制（YAGNI，将来要留口子再加）

---

### T1：确认 `EntityInteract` 可取消 —— ✅ **已完成（2026-10-01，立项当日）**

**结论**：`EntityInteract` 就是 `ICancellableEvent`（`PlayerInteractEvent.java:118`），
`setCanceled(true)` 可用（`:216-217`），且取消后事件的 `cancellationResult` 默认是
`InteractionResult.PASS`（`:119`）= "未处理" —— 正是我们要的语义。
NeoForge 在 `Player#interactOn` 里检查它（`Player.java:1720`、`:1765`：`if (post(event).isCanceled()) return;`）
⇒ **取消即拦住原版交互**。

⇒ 本计划的**前提成立**，无需改走 `EntityInteractSpecific` 链。

### T2：`NpcDialogueHandler` 取消对 NPC 的原版交互

**Files:** Modify `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueHandler.java`

**Steps:**
1. 在 `onEntityInteract` 的**最开头**（早于 `player.level().isClientSide()` 早退）加：
   `if (event.getTarget() instanceof NpcEntity) { event.setCanceled(true); }` —— **只针对我们的 NPC**，且**两侧都取消**
2. **其余逻辑一行不动**（继续走开关/主手/闸门/查表/发包）—— 对话照常打开
3. 加注释说明两条：① 为什么必须早于客户端早退；② 为什么取消不影响我们自己的对话

**Verification:** `gradlew build` 成功；守卫新断言通过。

### T3：`NpcEntity` 禁止被牵引

**Files:** Modify `src/main/java/com/zonlong/beloong/entity/NpcEntity.java`

**Steps:**
1. 加：
   ```java
   @Override
   public boolean canBeLeashed() {
       return false;
   }
   ```
2. 类注释那条「定义性语义」清单加第 5 条：**不可牵引** —— 并写明依据：
   原版 `Entity#interact` 的上绳分支只看 `canHaveALeashAttachedToIt()`（`Entity.java:2026`），
   而它 = `canBeLeashed() && !isLeashed()`（`Leashable.java:33-35`）；`Mob` 的默认实现是
   `!(this instanceof Enemy)`（`Mob.java:1404-1407`）⇒ 必须显式覆写才能拒绝。
3. 注释顺带说明**本轮刻意不做**自愈（`dropLeash` 巡检），以及为什么：用户裁定存量手动处理

**Verification:** `gradlew build` 成功；守卫断言"覆写存在且返回 false"通过。

### T4：改写 `NpcDialogueHandler` 的类注释（反转旧决定）

**Files:** Modify `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueHandler.java`（类 javadoc）

**Steps:** 把第 26-27 行那段「刻意不取消事件（首版 D4 不变）：空手右键本无任何原版行为，取消没有收益，却会抢掉将来第三方模组的交互」改写为反向结论，并说明：
① 为什么现在必须取消 —— **NPC 不是普通生物**：原版交互在它身上不是"收益"而是 bug（栓绳可牵走、命名牌可改名，且被牵会 `disableControlFlag(MOVE)` 直接掐死路线系统）；
② **有意接受的代价** —— 第三方模组通过右键本 NPC 触发的交互也会失效；将来若确有需要，再引入"白名单物品/条件"的口子（不要现在做）。

### T5：守卫（stage_invariants ⑱ 或并入 ⑯）

**Files:** Modify `D:\Minecraft\tools\YSMParser\stage_invariants.py`（仓库外）

**Steps:** 断言三条：
1. `NpcEntity` 覆写了 `canBeLeashed()` 且**返回 false**
2. `NpcDialogueHandler` 里存在 `event.setCanceled(true)`，且其**行号早于** `isClientSide()` 早退那一行（**按行号比较**，防后人挪错位置）
3. 取消语句作用域里有 `instanceof NpcEntity`（只取消我们自己的 NPC）

**Verification:** `python stage_invariants.py` 全绿（新断言 PASS）。

### T7：`NpcEntity` 禁止被传送门送走

**Files:** Modify `src/main/java/com/zonlong/beloong/entity/NpcEntity.java`

**Steps:** 覆写 `public boolean canUsePortal(boolean allowPassengers) { return false; }`；
类注释"定义性语义"清单再加一条「**不被传送门送走**」并注明依据（`Entity.java:2274` 的判定点），
同时写明**为什么不动 `canChangeDimensions`**（`:2281` 会让创造/指令/模组强制传送一起失效 ✗）。

**Verification:** `gradlew build`；守卫断言通过。

### T8：`NpcEntity` 禁止被投射物命中（钓竿由此失效）

**Files:** Modify `src/main/java/com/zonlong/beloong/entity/NpcEntity.java`

**Steps:** 覆写 `public boolean canBeHitByProjectile() { return false; }`；
类注释注明依据（`Projectile.java:257-258` 首先问它）**并明确写下"不要改用 `isPickable()`"**及其原因
（准星选取 ⇒ 会让玩家点不到 NPC ⇒ 对话失效 ✗）。

**Verification:** `gradlew build`；守卫断言通过。

### T9：守卫（⑱ 或并入 ⑯）

**Files:** Modify `D:\Minecraft\tools\YSMParser\stage_invariants.py`（仓库外）

**Steps:** 在 T5 三条之外再断言两条：
① `NpcEntity` 覆写了 `canUsePortal` 且返回 false，**且没有**覆写 `canChangeDimensions`
（后者是陷阱 ✗ —— 断言它**不在**源码里）；
② `NpcEntity` 覆写了 `canBeHitByProjectile` 且返回 false，**且没有**覆写 `isPickable`
（同样是陷阱 ✗）。

**Verification:** `python stage_invariants.py` 全绿。

### T10：完整验证与提交

**Verification:** `gradlew build` ✓ + `stage_invariants.py` ✓ + `route_invariants.py` ✓ + 三个探针 ✓ ⇒ 提交（代码 + 计划回填）。

---

## 风险

| 风险 | 说明 | 应对 |
|---|---|---|
| `EntityInteract` 不可取消 | 同族的 `EntityInteractSpecific` 是 `ICancellableEvent`，但 `EntityInteract` 我**尚未读到那一行** | T1 第一件事就确认；不可取消则停下重新设计（可能改走 `EntityInteractSpecific` 链或加其它拦截点）|
| 一刀切取消挡掉第三方玩法 | 旧注释当初正是为此不取消 | 用户已**有意接受**；T4 把"如何加口子"写进注释备查 |
| 只取消一侧 | 事件两侧都派发 ⇒ 客户端不取消的话，客户端仍会跑一遍原版（例：绳子在客户端被 `shrink(1)`，短暂不同步）| T2 明确"早于客户端早退"；T5 用行号断言钉住 |
| 存量不治 | 用户选 B（不做自愈）| 验收清单里写明**手动处理办法**；T3 注释也写明这是刻意取舍 |

## 实机验收清单（交给用户）

1. **空手**右键 NPC ⇒ 对话照常打开（回归）
2. **手持栓绳**右键 ⇒ 对话照常打开（`trigger: any`）· **绳子不被消耗** · NPC **不被牵引**
3. **手持命名牌**右键 ⇒ **不改名**（被取消）
4. **回归**：路线 / 表情 / 通关回收 / 登场 CG 一切照旧
5. ⚠️ **存量**：存档里那只如果已经被上绳 ⇒ 本轮**不会**自动治愈（刻意）⇒ 手动处理（引到栅栏解开，或换一只新生成的）
6. **传送门**：在 NPC 旁边搭下界门（或把它围在门里）⇒ 它**不被送走** ✓；同时**指令仍可移动它**
   （`/execute in the_nether run tp @e[type=beloong:mo] …` 照常生效 ✓）—— 这一条**同时验证了没误伤 `canChangeDimensions`** ✓
7. **钓竿**：对着它抛竿收杆 ⇒ **钩不上、拉不动** ✓ 且**右键对话照常** ✓（验证没误伤 `isPickable` ✓）

## 下一步

计划批准后交 `task-management` 执行；本计划不改任何原版代码。

---

# 执行记录

## T1 —— ✅ 已完成（立项当日，只读核查）

见上方 T1 条目：`EntityInteract` 确认可取消，计划前提成立。

---

# 决策记录（全部由用户裁定）

| 事项 | 决定 |
|---|---|
| 交互策略 | **取消 NPC 身上的原版/第三方右键交互**，放行我们自己的对话（用户明确不希望用 Mixin）|
| 牵引封堵 | **方案 B**：取消交互 + `canBeLeashed()=false`；**不做**每 tick 自愈（存量手动处理）|
| 生物类别 | **保持 `MISC`**（本就 `isFriendly=true`、持久、不占刷怪上限）|
| 阵营 | **不做**（现状已不在任何原版敌对名单里）|
| 传送门 | **做**（覆写 `canUsePortal`，不动 `canChangeDimensions`）|
| 钓鱼竿 | **做**（覆写 `canBeHitByProjectile`，不动 `isPickable`）|
| 药水效果 | **本轮不做**（选项"丙"）|

# 待办与残留风险（本轮未处理，留档）

1. **药水防护**（下轮可做 ✓）：用 `MobEffectEvent.Applicable`（`CommonHooks.java:1548-1550`）+ 我们自己的
   "单 tick 许可"标志（全局唯一加效果处在 `MynpcCommand.java:270`）。⚠️ 硬约束：**原版 `/effect give` 与
   "玩家扔喷溅药水"在事件里无法区分**（source 都是那个玩家）⇒ 只能二选一。
   ✅ 残留风险评估：本轮挡掉投射物后，**潜影贝子弹（原版唯一的漂浮来源）也被挡** ✓；风弹的"风充能"同样走
   投射物命中 ⇒ 一并被挡 ✓ ⇒ **原版生存里已无手段给 NPC 上"会移动的效果"**；残留面只有**别的模组**新增的
   漂浮类药水/效果。
2. **活塞 / 粘性活塞**（未处理）：`PistonMovingBlockEntity#moveCollidedEntities` **不看任何实体标志**
   （`:152-195`、`moveEntityByPiston:190-195`）⇒ 原版**没有**官方钩子；要挡只能自己发明
   （"被活塞位移后复位"之类），代价与观感需单独设计。
3. **流水 / 岩浆流**（未处理）：`Entity#isPushedByFluid()` 默认 `true`（`:2848-2850`），覆写它即可挡 ✓
   （注意同一条判定可能也管气泡柱，待确认）。
4. **`FloatGoal` 的水中自我游动**（未处理）：与"绝对站桩"冲突；去掉该 goal 会让它落水沉底（观感变化）。
5. **"挖掉脚下导致掉落"**（未处理）：纯世界机制，是否算"被玩家移动"需用户定义。

---

# 执行记录（T1–T10）

| 任务 | 结果 |
|---|---|
| T1 确认 `EntityInteract` 可取消 | ✅ 立项当日完成（`PlayerInteractEvent.java:118`、`:216-217`）|
| T2 取消对 NPC 的原版/第三方右键交互 | ✅ 加在方法最开头（**早于**客户端早退），只针对 `NpcEntity` |
| T3 `canBeLeashed()` 恒 false | ✅ |
| T4 类注释反转旧决定 | ✅ 写清"为什么现在结论相反"与"有意接受的代价" |
| T5 守卫（取消 + 牵引）| ✅ 并入 ⑱ |
| T7 `canUsePortal()` 恒 false | ✅ **未**碰 `canChangeDimensions`（陷阱 ✗）|
| T8 `canBeHitByProjectile()` 恒 false | ✅ **未**碰 `isPickable`（陷阱 ✗）|
| T9 守卫（含两条陷阱断言）| ✅ ⑱ 共 7 条 |
| T6/T10 验证与提交 | ✅ 四套不变量 + 三探针 + build 全绿 |

**守卫 ⑱ 的七条**：三个"恒 false"覆写 · ★`canChangeDimensions` 未被覆写 · ★`isPickable` 未被覆写 ·
`setCanceled` 存在且**行号早于**客户端早退 · 取消只针对 `NpcEntity` · 类注释清单含三条新语义。

⚠️ 两条陷阱断言是查**方法声明**而不是查关键词 —— 因为实现它们的 javadoc 里**正解释着**"不要用 `isPickable`"、
"不要动 `canChangeDimensions`"；查关键词会被自己的注释绊倒（本项目在这件事上栽过三次）。
