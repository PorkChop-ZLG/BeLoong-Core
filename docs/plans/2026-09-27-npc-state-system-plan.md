# 通用 NPC 状态系统 实施计划

**Date:** 2026-09-27
**Status:** **已批准**（2026-09-27；用户确认 §七 选 **B**：各状态默认名即 `fly`/`sit`/`dance`）
**后续追加：** 设计 **D17'** —— 指令格式统一为 `npc <targets> <action> …`（目标移到动作之前），
已实施；下文 T7 的片段与 §五 验收清单均已按新格式更新。
**Design:** [2026-09-27-npc-state-system-design.md](./2026-09-27-npc-state-system-design.md)
**取代:** [2026-09-27-npc-flight-plan.md](./2026-09-27-npc-flight-plan.md) 的任务切分与实机清单
（该文档里的**飞行平台事实**仍然有效，本文引用不重复）
**分支:** `NPC`（不 push）
**Approach:** 单一互斥状态枚举 + 三轴正交指令面（状态 / 移动 / 攻击）+ 全清。
**这是对已通过实机验收的飞行实现的**重构**：`DATA_FLYING` 布尔 → `DATA_STATE` 枚举、指令面全换。
飞行行为本身一行不变（原地悬停、`FLYING_SPEED 0.6`、换字段配方、`FOLLOW_RANGE` 续路、
`hoversInPlace` 悬停），但飞行那套实机项必须按新指令面重写并重跑。**
**验证模型:** 本项目**无测试源集**，每个任务不写单元测试。验证 = `gradlew build` + 静态探针（§四）+ 实机清单（§五）。

---

## 〇、写代码前已核实的签名与事实

### 0.1 重构的爆炸半径（已全仓 grep，确认只有这些调用方）

| 调用方 | 用到的 API | 处置 |
|---|---|---|
| `NpcCommand.java:100,121,126,140,154,186` | `setFlying` / `isFlying` / `flyTo` / `resetToDefault` / `walkTo` / `stopAction` | **本次重写整个指令层** |
| `NpcAttackGoal.java:34,39,45` | `isAttackCommandActive()` / `clearAttackCommand()` | **保留**（属攻击轴） |
| `MoEntity.java:164` | `isFlying()` | 改为读状态；`isFlying()` 作为便捷方法**保留** |
| `NpcEntity` 自身 `:921` | `isFlying()`（控制器谓词） | 控制器改为按状态分支 |
| `ClientFlightHandlerMixin.java:110` | `ServerFlightHandler.isFlying(player)` | **无关**（Dragon Survival 的玩家飞行，同名） |

⇒ 只动 5 个文件 + 1 个新枚举 + lang。**没有对话系统或其它调用方**。

### 0.2 沿用的平台事实（依据见设计文档与飞行设计文档）

| 事实 | 出处 |
|---|---|
| `moveControl`/`navigation` 非 final、每 tick 现读 ⇒ 运行期可换 | `Mob.java:109,112,205,213,797,804` |
| `FlyingMoveControl(mob, 20, true)`；`hoversInPlace=true` 时空闲**不**关重力 | `Bee.java:139`、`Allay.java:122`、`FlyingMoveControl.java:49-51` |
| 空中读 `FLYING_SPEED`，属性不存在会抛异常 | `FlyingMoveControl.java:38` |
| `PathNavigation` 用 `FOLLOW_RANGE`(=16) 作 `maxRange` ⇒ **飞行也要续路** | `PathNavigation.java:151,169`、`Mob.java:160` |
| `defineSynchedData(SynchedEntityData.Builder)` 是抽象方法 | `Entity.java:342` |
| `NoGravity` 会被原版写进 NBT | `Entity.java:1769,1878` |
| **模组禁止**调 `EntityDataSerializers.registerSerializer` | `EntityDataSerializers.java:133-141` |
| `StringRepresentable` + `byName(name, 默认)` 的安全回落 | `Armadillo.java:429,446-447` |
| `ByIdMap.continuous(::id, values(), OutOfBoundsStrategy.ZERO)` | `Armadillo.java:430-432` |
| 换回导航要恢复**原实例**（`FloatGoal` 构造器设过 `canFloat(true)`） | `FloatGoal.java:13`、`Drowned.java:58-59` |

---

## 一、全局约束

1. **T1–T9 是同一个编译单元，必须一个原子提交。** 指令层直接引用实体 API（删掉 `walkTo`/`setFlying`
   后 `NpcCommand` 立刻编译不过），中途任何提交都编不过 —— 与基类那一轮同样的理由。
   第二个提交只放文档。
2. **不改**：`MoRenderer` 的模型缩放、影子、剔除盒、碰撞箱、模型/贴图资产、
   `MoEntity` 的视锥剔除、`NpcEntity` 的 `moveControl`/`navigation` 换字段配方与续路参数。
3. `docs/models/` 保持 gitignore；模型源文件不入库（`git ls-files docs/models` 必须为空）。
4. lang 键**按字母序插入**，`zh_cn` 与 `en_us` 键集合必须完全一致（当前各 232）。
5. 提交格式 `refactor(entity): …`（中文正文，讲清**为什么**）。**不 push**。
6. Javadoc 说明**为什么**；把"刻意不这么做"的理由留在代码旁边（沿用本仓惯例）。
7. `memory/` 已在 `.gitignore:46`，不入库。

---

## 二、任务分解

### T1 新增 `entity/NpcState.java`

**文件：** 新增 `src/main/java/com/zonlong/beloong/entity/NpcState.java`

```java
public enum NpcState implements StringRepresentable {
    IDLE   ("idle",    0, true),      // 默认；唯一会走 idle/walk/run 三选一的状态
    FLYING ("flying",  1, true),      // 移动模式
    SITTING("sitting", 2, false),     // 姿态
    DANCING("dancing", 3, false);     // 姿态
    ...
}
```

必须提供（**四种用途，行为各不相同，见设计文档 §3.5**）：

| 成员 | 用途 | 越界/未知名时 |
|---|---|---|
| `id()` / `ByIdMap` / **`byId(int)`** | 从同步数据还原 | **回落 `IDLE`**（`OutOfBoundsStrategy.ZERO`） |
| `getName()`（= `getSerializedName()`） | 落盘与显示 | — |
| **`byNameLenient(String)`** | 从 NBT 还原 | **回落 `IDLE`**（`CODEC.byName(name, IDLE)`） |
| **`byNameStrict(String)` → `Optional<NpcState>`** | 指令参数校验 | **空 ⇒ 上层报错** |
| `isMovementMode()` | 状态类别 | — |
| `NAMES`（字符串列表） | Brigadier 补全 | — |

> ⚠️ **严格版与宽松版必须在同一个文件里并排实现，并各写一句"另一种场合要反过来"的注释。**
> 后人极可能图省事合成一个 —— 而这两种场合的正确行为是**相反**的（设计文档 §3.5）。

**验证：** `gradlew build` + `javap` 确认枚举与四个静态入口已生成。

---

### T2 `NpcEntity`：状态层（`DATA_STATE` 取代 `DATA_FLYING`）

**文件：** 改 `src/main/java/com/zonlong/beloong/entity/NpcEntity.java`

1. `DATA_FLYING`（`BOOLEAN`）→ `DATA_STATE`（`INT`，存 `state.id()`）；
   `defineSynchedData` 里 `builder.define(DATA_STATE, NpcState.IDLE.id())`。
2. `public NpcState state()` = `NpcState.byId(this.entityData.get(DATA_STATE))`。
3. **`public final void setState(NpcState next)`**（服务端专用；**幂等**：同状态直接 return，
   避免重建导航丢掉正在走的路径）→ 内部调 `switchState`。
4. **`private void switchState(NpcState next)`** —— **唯一**的副作用集中点：
   - **第一步无条件收掉上一个状态的副作用**（不留任何提前 return —— 教训来自飞行那轮的 I-3）
   - `FLYING`：缓存原 `moveControl`/`navigation`（`Drowned` 式）→ 换
     `FlyingMoveControl(this, 20, true)` + `FlyingPathNavigation` → `setNoGravity(true)`
     → **不设任何移动目标**（`fly on` 改为原地悬停，见设计文档 D2'）
   - 非 `FLYING`：`setNoGravity(false)` → 换回**缓存的**原实例（`groundMoveControl`/`groundNavigation`
     在换回前 `stop()` 一次，清掉换下时残留的旧路径）
   - **进入姿态（`!isMovementMode()`）时取消移动与攻击指令**（设计文档契约表 D9'）
5. `public boolean isFlying()` **保留**为 `state() == NpcState.FLYING`（`MoEntity` 与既有文档在用）。
6. **删除**：`setFlying`、`takeoffHeight`、`DEFAULT_TAKEOFF_HEIGHT`、以及 I-2 那处
   "目标 + `FLY_ARRIVE_DISTANCE`"的补偿（不再起飞就不需要）。
7. 不变式改为双向：**`noGravity == (state() == FLYING)`**。

**验证：** `gradlew build`；`javap` 确认 `DATA_STATE`/`state`/`setState`/`switchState` 已生成、
`DATA_FLYING`/`setFlying`/`takeoffHeight` 已消失。

---

### T3 `NpcEntity`：三个轴的 API

**文件：** 同上

| 新 API | 由谁变来 | 语义 |
|---|---|---|
| `public void moveTo(Vec3 pos)` | `walkTo` + `flyTo` **合并** | **若是姿态 ⇒ 先隐式切 `IDLE`，再登记目标**（顺序不能反 —— 反了就被那次切换取消掉） |
| `public void stopMoving()` | `clearMotionCommands` 公开化 | **只停寻路**，不碰状态、不碰攻击 |
| `public void attack(@Nullable LivingEntity)` | 保留 | 若当前是姿态 ⇒ 先隐式切 `IDLE` |
| `public void stopAttacking()` | 新增 | **只清攻击**，**不停寻路**（新契约：攻击轴与移动轴正交） |
| `public void resetToDefault()` | 保留 | `setState(IDLE)` + `stopMoving()` + `stopAttacking()` |

**删除**：`walkTo`、`flyTo`、`stopAction`。

⚠️ `clearAttackCommand()` 里现有的 `this.getNavigation().stop()` **要去掉** ——
它违反新契约（取消攻击不该停移动）。`NpcAttackGoal.stop()` 那边 `MeleeAttackGoal.stop():94`
的无条件 `nav.stop()` 由 `tickMoveCommand()` 的续路在 1 tick 内自愈（P1-1 已验证过）。

**验证：** `gradlew build` + §四 A4（指令树）——但注意本任务只改 API，指令层在 T7。

---

### T4 `NpcEntity`：持久化

**文件：** 同上

```java
@Override public void addAdditionalSaveData(CompoundTag tag) {
    super.addAdditionalSaveData(tag);
    tag.putString("BeloongState", this.state().getSerializedName());   // 名字而非 ordinal
}
@Override public void readAdditionalSaveData(CompoundTag tag) {
    super.readAdditionalSaveData(tag);
    this.switchState(NpcState.byNameLenient(tag.getString("BeloongState")));  // 宽松回落 IDLE
}
```

**三条要点：**

1. **恢复必须经 `switchState`**，不能直接写 `entityData` —— 否则飞行所需的换字段/重力副作用会漏掉。
2. **不再无条件 `setNoGravity(false)`**（那是"不持久化"时代的归一化）。新不变式由
   `switchState` 保证：读到 `IDLE` 就会 `setNoGravity(false)`，读到 `FLYING` 就会 `setNoGravity(true)`。
   那条**无条件**清重力的保险仍然保留在 `resetToDefault()` 里。
3. **向后兼容**：旧存档没有这个键 ⇒ `getString` 返回 `""` ⇒ `byNameLenient` 回落 `IDLE` ⇒
   与"未加状态系统之前"行为一致（顺便把旧存档里可能残留的 `NoGravity` 清掉）。

**验证：** `gradlew build` + 实机 §五 B9'/B10'/B12'。

---

### T5 `NpcEntity`：动画映射

**文件：** 同上（`registerControllers` 与可覆写默认值两处）

```java
/** 状态 → 动画名。默认见 §七；子类按资产实际命名覆写。 */
protected String stateAnimationName(NpcState state) { ... }
protected String sitAnimationName()   { ... }
protected String danceAnimationName() { ... }
```

控制器改为：

```java
// registerControllers 里为每个"姿态/模式"状态预建 RawAnimation（放在这里是为了避开
// 构造期调虚方法的坑 —— 与原实现的理由相同，见该方法现有 javadoc）
controllers.add(new AnimationController<>(this, "main", this.animationTransitionTicks(), state -> {
    NpcEntity npc = state.getAnimatable();
    RawAnimation posed = posedAnimations.get(npc.state());   // IDLE 不在表里
    if (posed != null) {
        return state.setAndContinue(posed);
    }
    // IDLE 才走 idle/walk/run 三选一
    ...
}));
```

**副作用（正面）**：现有那句"飞行时不进 idle/walk/run，否则腿会在空中走"的**特判消失** ——
它变成"状态分支"的自然结果。

**验证：** `gradlew build` + 实机 B1'/B5'。

---

### T6 `NpcAttackGoal`：加状态门（**防御性**）

**文件：** 改 `src/main/java/com/zonlong/beloong/entity/ai/NpcAttackGoal.java`

`canUse()` / `canContinueToUse()` 各加一项：**当前状态必须是移动模式**。

**为什么需要**：契约表已规定"进入姿态会取消攻击指令"⇒ 正常路径下不存在"姿态 + 攻击指令"。
但 `/data merge` 直接把状态改成 `sitting` 会造出这个组合，届时攻击 goal 会去移动一个坐着的 NPC。
加一行门即可封死，并把这个不变式写在注释里。

> ⚠️ 设计文档非目标里有"不动 `NpcAttackGoal` 的攻击语义"。**本任务只加"何时运行"的门，
> 不动它怎么攻击**（伤害、可达性、限流一概不碰）—— 属非目标的字面之外、精神之内。
> **若你认为这算越界，删掉本任务即可**（代价只是在 `/data merge` 的边角情况下会看到坐着的 NPC 滑行）。

**验证：** `gradlew build` + 实机 B6'（坐下后不再追打）。

---

### T7 `NpcCommand`：指令层重写

**文件：** 改 `src/main/java/com/zonlong/beloong/command/NpcCommand.java`

```java
// ⚠️ 目标一律紧跟在 npc 之后、动作之前（2026-09-27 统一格式，见设计 D17'）：
//     /beloong npc @e state flying   /beloong npc Mo attack @e[type=zombie]
.then(Commands.argument("targets", EntityArgument.entities())
        .then(Commands.literal("state")
                .then(Commands.argument("state", StringArgumentType.word())
                        .suggests(STATE_SUGGESTIONS)              // 枚举名补全
                        .executes(ctx -> setState(...))))
        .then(Commands.literal("move")
                .then(Commands.argument("pos", Vec3Argument.vec3())
                        .executes(ctx -> move(...))))
        .then(Commands.literal("stop")
                .executes(ctx -> stopMoving(...)))
        .then(Commands.literal("attack")
                // 顺序有讲究：先接实体参数，再接 stop 字面量（两者落在同一 token 位置）
                .then(Commands.argument("victim", EntityArgument.entity())
                        .executes(ctx -> attack(...)))
                .then(Commands.literal("stop")
                        .executes(ctx -> stopAttacking(...))))
        .then(Commands.literal("reset")
                .executes(ctx -> reset(...))));
```

**删除**：`walk`、`fly`（`on`/`off`/`to`）整棵子树。

**`state` 参数用严格校验**：`NpcState.byNameStrict(name)` 为空 ⇒
`sendFailure("beloong.command.npc.state.unknown", name, 可用列表)` 并返回 0
—— **绝不允许静默回落成 `IDLE`**（设计文档 §3.5）。

**反馈消息里的状态名走 lang**：`Component.translatable("beloong.npc.state." + state.getSerializedName())`
—— 枚举的序列化名正好当键后缀，加状态时只需补两条 lang 键。

⚠️ **`npc <targets> attack stop` 的 Brigadier 歧义**：`stop` 落在"本该是实体参数"的位置，
Brigadier 会同时尝试字面量与"名为 `stop` 的实体"。**这是原版接受的形状**
（`/tag <targets> add|remove|list` 同构造），实际风险可忽略。**代码注释里记一句。**

**验证：** `gradlew build` + 实机 B3'/B7'/B11'。

---

### T8 lang 键

**文件：** 改 `src/main/resources/assets/beloong/lang/zh_cn.json`、`.../en_us.json`

**删除**（4 个）：`beloong.command.npc.walk`、`.fly_on`、`.fly_off`、`.fly_to`
**另删**（1 个，随 `fly to` 的前置校验一起消失）：`beloong.command.npc.not_flying`

**新增**：

| 键 | 用途 |
|---|---|
| `beloong.command.npc.state` | 「已让 %s 个 NPC 切换到 %s」 |
| `beloong.command.npc.state.unknown` | 「未知状态「%s」（可用：%s）」← 严格报错 |
| `beloong.command.npc.move` | 「已让 %s 个 NPC 移动到 %s」 |
| `beloong.command.npc.stop` | **改文案**为「已让 %s 个 NPC 停止移动」 |
| `beloong.command.npc.attack_stop` | 「已让 %s 个 NPC 停止攻击」 |
| `beloong.npc.state.idle` / `.flying` / `.sitting` / `.dancing` | 状态显示名（4 条） |

`reset` 与 `attack`、`no_targets`、`not_living` 保留不动。**按字母序插入两个文件。**

**验证：** §四 A5（两语言键集合一致、旧键已删、新键齐全）。

---

### T9 子类覆写

**文件：** 改 `entity/DihuangLoongEntity.java`、`entity/MoEntity.java`

| 子类 | 覆写 | 依据 |
|---|---|---|
| `DihuangLoongEntity` | `flyAnimationName()`→`"fly"`、`sitAnimationName()`→`"sit"`、`danceAnimationName()`→`"dance"` | 其资产 96 条动画里三条都在（`fly`/`sit`/`dance`） |
| `MoEntity` | `flyAnimationName()`→`"fly"`；**不覆写** `sit`、也不覆写 `dance` | 其资产 29 条动画里有 `fly` 与 **`sit`（261 骨骼）**、**没有 `dance`**。`sit` 无需覆写 —— 基类 `sitAnimationName()` 默认名正好是 `"sit"`，**开箱可用**。按 §七 选 B，对它执行 `state … dancing` **会塌成 T-pose，这是预期行为**；`sitting` 正常播放 |

**验证：** `gradlew build` + 实机 B14'（两个 NPC 的 `fly` 姿势都正常）、B13''（末 `sitting` 正常播 `sit`、`dancing` 塌掉属预期）。

---

## 三、提交切分

| 提交 | 内容 | 说明 |
|---|---|---|
| **1** | `refactor(entity): …` = T1–T9 | **原子**：指令层引用实体 API，拆开就编不过 |
| **2** | `docs: …` = 设计文档补记 §七 那条动画名默认值的修订 + 本计划 | — |

---

## 四、静态探针

| # | 断言 | 手段 |
|---|---|---|
| A1 | `NpcState` 枚举 + `id/ByIdMap/byId/byNameLenient/byNameStrict/isMovementMode/NAMES` 已生成 | `javap` |
| A2 | `NpcEntity`：`DATA_STATE`/`state`/`setState`/`switchState`/`isFlying`/`moveTo`/`stopMoving`/`stopAttacking` 在；`DATA_FLYING`/`setFlying`/`walkTo`/`flyTo`/`takeoffHeight` **已消失** | `javap -p` |
| A3 | `switchState` 字节码里：`FLYING` 分支引用 `FlyingMoveControl`/`FlyingPathNavigation`，其它分支引用 `GroundPathNavigation`/`MoveControl`；`setNoGravity` 出现在两个分支 | `javap -c` |
| A4 | NBT：`"BeloongState"` 读写成对；`byNameLenient` 在 `readAdditionalSaveData` 里被调用 | `javap -c` + 常量池 |
| A5 | 指令树：`state`/`move`/`stop`/`attack`(含 `stop`)/`reset` 均注册；旧字面量 `walk`/`fly` 已消失 | `javap -c` 常量池 |
| A6 | lang：两文件键集合 `Compare-Object` 无差异；4+1 个旧键已删、新键齐全 | 解析 JSON |
| A7 | `docs/models` 未被入库 | `git ls-files docs/models` 为空 |
| A8 | 构建通过 | `gradlew build` BUILD SUCCESSFUL |

---

## 五、实机验收清单（**旧的 B1–B8 全部作废**）

| # | 动作 | 期望 |
|---|---|---|
| B1' | `@e state flying` | **原地悬停、不上升**（D2'） |
| B2' | `@e move <50 格外坐标>`（飞行态） | **能飞到**（`FOLLOW_RANGE` 续路的回归） |
| B3' | `@e stop`（飞行中） | **原地继续悬停**，状态不变 |
| B4' | `@e move <坐标>`（`idle` 态） | 地面走过去 |
| B5' | `@e state sitting` → `@e move <坐标>` | **先站起来再走**（隐式退出姿态） |
| B6' | 正在走时 `@e state sitting` | **停下并坐下**；且**不再追打**（D9' + T6） |
| B7' | `@e attack <victim>` → `@e attack stop` | 停止攻击；地面回站桩、空中回悬停 |
| B8' | 飞行中 `@e state idle` | 落到地面；若此前有 `move` 指令，**以地面方式继续走** |
| B9' | **存档→退出→重进** | 状态保持：存档时在飞 ⇒ 重登后仍原地悬停；坐着 ⇒ 仍坐着；未飞 ⇒ 仍在地面 |
| B10' | 关服重开 | 同上 |
| B11' | `@e state fliing`（拼错） | **明确报错**并列出可用状态（严格策略） |
| B12' | 手改存档状态名为非法值 | **回落 `IDLE`，不崩**（宽松策略） |
| B13'' | 末 `@e state sitting` / `@e state dancing` | `sitting` **正常播 `sit`**（资产里有，261 骨骼）；`dancing` **会塌成 T-pose，属预期行为** |
| B14' | 两个 NPC 各 `@e state flying` | 都播 `fly` 且姿势正常 |

> **B13' 已改写为 B13''**：原文是"末执行 `@e state sitting` 不得塌成 T-pose"，它**基于一个错误前提**
> —— 我当时只看了资产里"飞 / 翅膀"相关的动画子集，就断言"末没有 `sit`"。
> 📌 **2026-09-27 核对全部 29 条动画后更正：末的资产里 `sit` 是存在的（261 骨骼），实测也能正常播放；
> 只有 `dance` 确实没有。** 故 B13' 改为正向验证"末 `sitting` 正常播 `sit`"（见 B13''），
> 并把"塌 T-pose 属预期"限定到 `dancing`。
> 依据：`AnimationProcessor.java:44-64`（名字查不到不报错、得到空动画列表、骨骼复位到静止姿态）。

---

## 六、本计划最没把握的三件事

1. **`switchState` 的"收旧 + 装新"是否真的无残留** —— 尤其是"从 `FLYING` 切到 `SITTING`"
   这条同时涉及换导航、重力、与取消指令的路径。飞行那轮的 I-3 就是在这类地方翻的车。
2. **指令层的隐式退出与取消的顺序** —— `move`/`attack` 里的"先退出姿态再登记"若写反，
   表现为"命令看起来成功了但 NPC 不动"，且**不报错**。
3. **资产缺动画时的表现是"预期行为"而不是"待修缺陷"** —— 按 §七 选 B，末执行
   `state … dancing` **会塌成 T-pose**；而 `state … flying` / `state … sitting` 都正常
   （它有 `fly` 与 `sit`）。实机时**别把 `dancing` 那个当 bug 报**；真要让它也能跳，
   得先在资产里补一条 `dance`。
   <br>📌 **2026-09-27 更正**：本条此前写作"`sitting`/`dancing` 都会塌"，是**基于错误前提**
   —— 我当时只看了资产里"飞 / 翅膀"相关的动画子集就断言"末没有 `sit`"。

---

## 七、✅ 已结案：动画名默认值 —— 用户选 **B**

**问题**：`stateAnimationName(state)` 的**默认值**取什么？

**结论（用户裁定 2026-09-27）：选 B —— 各状态默认名就是 `"fly"` / `"sit"` / `"dance"`。**

⇒ 与飞行那一轮的口径一致，**资产有动画时开箱即用**。
⇒ **代价（已明确接受）**：资产里没有该动画的 NPC 会**静默塌成 T-pose**，且无日志。
末的情况是：**`fly` 与 `sit` 都有（`sit` 是 261 骨骼），只有 `dance` 没有**
⇒ 对它执行 `state … dancing` 会塌，`sitting`/`flying` 都正常。
<br>📌 **2026-09-27 更正**：本条此前写作"末没有 `sit`/`dance`"，那是我**只看了资产里
"飞 / 翅膀"相关的动画子集就下的断言**；核对全部 29 条后确认 `sit` 存在（实测也能正常播）。
⇒ **只有 `dancing` 会塌，这是预期行为、不要当缺陷报**（验收清单里对应 B13'' 的后半句；
`flying`/`sitting` 都正常，见 B13''/B14'）。

**T9 因此定为**：`MoEntity` 只覆写 `flyAnimationName()`；`DihuangLoongEntity` 覆写
`flyAnimationName()` / `sitAnimationName()` / `danceAnimationName()` 三个（其资产三条都有）。

> 留档：被否决的选项 A 是"所有状态默认回落 `idleAnimationName()`"——
> 它能把"灾难级静默失败（整个模型垮掉）"降级成"轻微静默失败（姿态看不出区别）"。
> 注意 A **不是**运行时探测（用户此前否决的是运行时检测 `GeckoLibCache`），只是更安全的默认值。
> 将来若有人报"某个 NPC 坐下就塌了"，**原因就是本条选择**，不是 bug。
