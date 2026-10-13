# NPC 右键交互策略：取消原版交互 + 禁止牵引 实施计划

**目标**：本模组的 NPC **不再能被原版栓绳牵引**，且它身上的**一切原版/第三方右键交互一律不生效**，只保留我们自己的对话交互；**不改动原版代码（零 Mixin）**。

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

## 非目标（用户已裁定）

- **不做**每 tick 自愈（不加 `dropLeash` 巡检）；存档里已上绳的 NPC 由用户手动处理
- **不改** `MobCategory`（保持 `MISC`：本就 `isFriendly=true`、持久、不占刷怪上限）
- **不做**"阵营"（`isAlliedTo` / 积分板队伍）—— 现状既非 `Enemy` 也非 `Monster`，已不在任何原版敌对名单里
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

### T6：完整验证与提交

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

## 下一步

计划批准后交 `task-management` 执行；本计划不改任何原版代码。

---

# 执行记录

## T1 —— ✅ 已完成（立项当日，只读核查）

见上方 T1 条目：`EntityInteract` 确认可取消，计划前提成立。
