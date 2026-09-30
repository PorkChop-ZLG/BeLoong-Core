# 通用 NPC 飞行 AI 实施计划

**Date:** 2026-09-27
**Status:** **待批准**（批准后才动代码）
**Design:** [2026-09-27-npc-flight-design.md](./2026-09-27-npc-flight-design.md)
**分支:** `NPC`（不 push）
**Approach:** brainstorming 方案 A —— 换 `Mob` 的两个非 final 字段（`moveControl`/`navigation`）+ 复用 P1-1 的持续续路。备选方案 B（只用 `MoveControl` 直线飞、不寻路）与 C（拆成两个实体）已在 brainstorming 阶段比较并否决，理由见设计文档。
**任务切分:** 方案 **β（按"可实机验收的最小闭环"切）**，而非按能力切 —— 理由：设计里最没把握的一项是"换字段是否即插即用"，β 能让它在第 2 步就被实机验证，而不是拖到最后。
**验证模型:** 本项目**无测试源集**，不引入测试框架（见 `memory/project-context.md`）。因此本计划的每个任务**不写单元测试**，改用「`gradlew build` + 静态探针（§四）+ 实机清单（§五）」。**换字段是否生效、悬停是否悬得住、末的 `fly` 姿势像不像飞行，都只能实机验证。**

---

## 〇、写代码前已确认的签名与事实

| API / 事实 | 内容 | 出处 |
|---|---|---|
| `FlyingMoveControl` | `FlyingMoveControl(Mob, int maxTurn, boolean hoversInPlace)`；`hoversInPlace=true` 时空闲**不**关重力 | `FlyingMoveControl.java:11-15,49-51` |
| 空中读的属性 | `getAttributeValue(Attributes.FLYING_SPEED)`，**属性不存在会抛异常** | `FlyingMoveControl.java:38` |
| `FlyingPathNavigation` | `FlyingPathNavigation(Mob, Level)`；用 `FlyNodeEvaluator`；`canUpdatePath()` 几乎恒真 | `FlyingPathNavigation.java:14-24,34-37` |
| 导航开关 | `setCanOpenDoors` / `setCanFloat` / `setCanPassDoors` | `Bee.java:565-567`、`Allay.java:159-161` |
| 两个字段可换 | `protected MoveControl moveControl` / `protected PathNavigation navigation`，**非 final**，构造器 `:143,146` 赋值 | `Mob.java:109,112,143,146` |
| 每 tick 现读 | `getMoveControl()`/`getNavigation()` 只判载具，否则返回字段；`:797`/`:804` 直接读字段 | `Mob.java:205,213,797,804` |
| 距限 | `PathNavigation` 用 `FOLLOW_RANGE` 作 `maxRange`；`Mob.java:160` 给 16 | `PathNavigation.java:151,169`、`Mob.java:160` |
| `FLYING_SPEED` | 默认 `0.4`，范围 `[0,1024]`，`setSyncable(true)` | `Attributes.java:65` |
| **同步数据** | `defineSynchedData(SynchedEntityData.Builder)` 是**抽象方法**；vanilla 范式 `SynchedEntityData.defineId(Clazz.class, EntityDataSerializers.BOOLEAN)` + `builder.define(...)` | `Entity.java:342`、`Allay.java:84,166-167,417,422` |
| `NoGravity` 持久化 | `compound.putBoolean("NoGravity", ...)` / `setNoGravity(compound.getBoolean("NoGravity"))` | `Entity.java:1769,1878` |
| 现有控制器 | 主控制器 id `"main"`，谓词里 `state.isMoving()` 三选一 | `NpcEntity.java:548-561` |
| 现有命令 | `walk <targets> <pos>` / `attack <targets> <victim>` / `stop <targets>`；`hasPermission(2)`；`fail()` 报 `no_targets` | `NpcCommand.java:39-113` |
| 现有 lang 键 | `beloong.command.npc.{attack,no_targets,not_living,stop,walk}`，**按字母序**排列 | `lang/zh_cn.json:4-8` |
| 末的资产 | `待机动画`(`:1310`) / `翅膀默认（展开）`(`:995`) / `翅膀默认（收起）`(`:1068`)；非法表达式只在 `fly`/`swim`/`swim_stand` 的 `AllBody_Molang`、`Head_Molang` 上，**两根都不在 geo 里** | 本次实测 |

---

## 一、全局约束

1. **T1 与 T2–T6 是两个提交**：T1 = 资产 + 末的引用改名（必须原子，否则动画名对不上）；T2–T6 = 飞行能力 + 指令 + lang + 翅膀 STOP。
2. **不改**：geo / 贴图 / 其余 26 条动画 / 另 6 个代码不引用的中文键名 / `MoRenderer` 的 `MODEL_SCALE` / 碰撞箱 / 剔除盒 / 影子 / `NpcAttackGoal` / `MoModel` 的逻辑。
3. **`docs/models/` 保持 gitignore**，模型源文件不入库（`git ls-files docs/models` 必须为空）。
4. lang 键**按字母序插入**两个文件，且 `zh_cn` 与 `en_us` 的键集合必须**完全一致**（各 227 → 232）。
5. 提交格式 `feat(entity): …` / `fix(entity): …`（中文正文，讲清**为什么**）。**不 push**。
6. Javadoc 说明**为什么**；把"刻意不这么做"的理由留在代码旁边（沿用本仓惯例）。
7. `memory/` 已在 `.gitignore:46`，不入库。

---

## 二、实施步骤

### T1 资产改名 + 删死数据；`MoEntity` 同步改引用名

**文件：**
- 改 `src/main/resources/assets/beloong/animations/mo.animation.json`
- 改 `src/main/java/com/zonlong/beloong/entity/MoEntity.java`
- 改 `src/main/java/com/zonlong/beloong/client/model/MoModel.java`（仅 javadoc 里的旧名）

**步骤：**
1. 资产：键名 `待机动画` → `idle`、`翅膀默认（展开）` → `wings_idle`（**只改这两个**）。
2. 资产：在 `fly` / `swim` / `swim_stand` 三条里各删掉骨骼条目 `AllBody_Molang` 与 `Head_Molang`（共 6 个）。
   —— 这两根骨骼不在 geo 里，GeckoLib 本就 `if (bone == null) continue` 跳过 ⇒ **纯删死数据**，但删掉后这三条动画不再踩 `MathParser.java:46` 的 `EXPRESSION_FORMAT`，**从此能加载**。
3. `MoEntity`：`idleAnimationName()` 返回 `"idle"`；翅膀层常量改 `"wings_idle"`。
4. `MoEntity` / `MoModel` 的 javadoc 里所有旧动画名一并更新（`MoEntity` 有 8 处、`MoModel` 3 处）。

**验证：**
```
gradlew build --console=plain                                  # BUILD SUCCESSFUL
# 见 §四 A4 / A5 / A6：重新解析资产断言
grep -rn "待机动画\|翅膀默认" src/                              # 应为 0 命中
```

**风险：** 中 —— 资产是 9.5 MB 单行密集 JSON，改名/删条目必须**结构化处理**（解析 JSON → 修改 → 回写），不能做朴素字符串替换（`翅膀默认（展开）` 与 `翅膀默认（收起）` 前缀相同，且要确认没有同名骨骼）。回写后必须重新解析校验、并核对动画总数仍为 29。

---

### T2 `NpcEntity` 飞行状态层

**文件：** 改 `src/main/java/com/zonlong/beloong/entity/NpcEntity.java`

**步骤：**

1. **同步数据**（关键，见设计文档 D13）：`flying` 必须走 `entityData`，否则客户端永远读到 `false`、`fly` 动画不播。
```java
private static final EntityDataAccessor<Boolean> DATA_FLYING =
        SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BOOLEAN);

@Override
protected void defineSynchedData(SynchedEntityData.Builder builder) {
    super.defineSynchedData(builder);
    builder.define(DATA_FLYING, false);          // 默认不飞
}

public boolean isFlying() { return this.entityData.get(DATA_FLYING); }
```
2. **可覆写默认值**：`flyAnimationName()` → `"fly"`（javadoc 必须写明"名字不存在会静默塌成 T-pose"）；`takeoffHeight()` → `2.0`。
3. **新增常量**：`FLY_ARRIVE_DISTANCE`（三维到位阈值，与寻路 `accuracy` 口径一致）。
4. **`setFlying(boolean)`**：服务端守卫 + **幂等**（`flying == isFlying()` 直接 return，避免重建字段丢掉正在走的飞行路径）。
5. **`enableFlight()`**：换 `moveControl = new FlyingMoveControl(this, 20, true)`、`navigation = new FlyingPathNavigation(this, level())`（`setCanOpenDoors(false)` / `setCanFloat(false)` / `setCanPassDoors(true)`，照 `Bee.java:565-567`）→ `setNoGravity(true)` → 把 `moveTarget` 设为 `position().add(0, takeoffHeight(), 0)`。
6. **`disableFlight()`**：**不设任何提前 return 路径**，保证 `setNoGravity(false)` 一定执行到；换回 `new MoveControl(this)` + `new GroundPathNavigation(this, level())`；先停旧路径再换字段。
7. **`tickWalkCommand()` → `tickMoveCommand()`**：目标判定的距离口径按状态分叉 ——
   - 地面：水平距离平方（寻路会把 Y 吸附到可站立面，`GroundPathNavigation.java:47-84`）
   - 空中：**三维**距离平方（`FlyingPathNavigation` 不做吸附）
   其余（`isDone()` 或冷却到期续路、有界失败）不变。
8. **`flyTo(Vec3)`**：非飞行状态**不隐式开启**，由 T4 的命令层先行校验；本方法只登记目标。
9. **`resetToDefault()`**：清 `moveTarget`/冷却/计数 → `attackCommandActive=false` → `setTarget(null)` → 若在飞则落同步标志+`disableFlight()` → `getNavigation().stop()` → **无条件** `setNoGravity(false)`。
10. **载入归一化**：新增 `readAdditionalSaveData`（`super` 之后）把 `NoGravity` 拉回不变式，并显式把 `DATA_FLYING` 置 false。

**验证：**
```
gradlew build --console=plain
# §四 A1：javap 断言 DATA_FLYING / setFlying / isFlying / flyTo / resetToDefault / defineSynchedData 存在
```

**风险：** 中高 —— 这是本轮唯一"原版没有生物这么干过"的地方（运行期换导航/移动控制）。若实机 `fly on` 毫无反应，问题必在此。

---

### T3 `FLYING_SPEED` 属性 + 控制器飞行分支

**文件：** 改 `src/main/java/com/zonlong/beloong/entity/NpcEntity.java`

**步骤：**
1. `createNpcAttributes()` 增加 `.add(Attributes.FLYING_SPEED, 0.6D)`。
   —— **不加会在空中抛 `IllegalArgumentException`**（`FlyingMoveControl.java:38` 读它）。取值依据：不重写 `travel` 时空中加速度 `= 0.02 × FLYING_SPEED`，Bee 用 0.6。**一行可调。**
2. 控制器加飞行分支（**必须是整体替换，不是叠加**）：
```java
final RawAnimation fly = RawAnimation.begin().thenLoop(this.flyAnimationName());
controllers.add(new AnimationController<>(this, "main", this.animationTransitionTicks(), state -> {
    NpcEntity npc = state.getAnimatable();
    if (npc.isFlying()) {                       // ← 飞行时不进 idle/walk/run
        return state.setAndContinue(fly);       //    否则腿会在空中走
    }
    if (!state.isMoving()) {
        return state.setAndContinue(idle);
    }
    boolean speedBoosted = npc.getAttributeValue(Attributes.MOVEMENT_SPEED)
            > npc.getAttributeBaseValue(Attributes.MOVEMENT_SPEED);
    return state.setAndContinue(speedBoosted ? run : walk);
}));
```
3. 更新该控制器的 javadoc（说明为什么飞行要整体替换而不是叠加，依据 `LivingEntity.java:2373-2376` 的 `includeHeight` 语义）。

**验证：** `gradlew build` + §四 A2/A3。

---

### T4 `NpcCommand`：`fly on|off|to` 与 `reset`

**文件：** 改 `src/main/java/com/zonlong/beloong/command/NpcCommand.java`

**步骤：**
1. 在 `npc` 下新增两棵子树（沿用既有"先选实体再给参数"的风格）：
```java
.then(Commands.literal("fly")
        .then(Commands.argument("targets", EntityArgument.entities())
                .then(Commands.literal("on").executes(ctx -> flyOn(...)))
                .then(Commands.literal("off").executes(ctx -> flyOff(...)))
                .then(Commands.literal("to")
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(ctx -> flyTo(...))))))
.then(Commands.literal("reset")
        .then(Commands.argument("targets", EntityArgument.entities())
                .executes(ctx -> reset(...))))
```
2. `flyTo` 的前置校验：只要有**任意一个**选中目标不在飞行状态，就整体 `sendFailure("beloong.command.npc.not_flying")` 并返回 0 —— 不隐式开启，也**不对一部分静默生效**（那会让玩家以为命令成功了）。
   > 本文档最初把这句写成"全部选中目标**都**不在飞行状态时"，与实现相反。2026-09-27 按实现改正：实现符合设计 §3.4② 与提交说明。
3. 各有 `sendSuccess` 反馈（新 lang 键，见 T5）。
4. 更新类 javadoc 里"刻意没有的子命令"一节（`fly`/`reset` 现已加入），避免与既有记述矛盾。

**验证：** `gradlew build` + §五 B5（未开飞行时 `fly to` 必须明确报错）。

---

### T5 lang 键（`zh_cn` + `en_us` 同步，按字母序）

**文件：** 改 `src/main/resources/assets/beloong/lang/zh_cn.json`、`.../en_us.json`

**步骤：** 在两个文件里按字母序插入 5 个键，使顺序为
`attack → fly_off → fly_on → fly_to → no_targets → not_flying → not_living → reset → stop → walk`：

| 键 | 用途 |
|---|---|
| `beloong.command.npc.fly_on` | 「已让 %s 个 NPC 开启飞行」 |
| `beloong.command.npc.fly_off` | 「已让 %s 个 NPC 关闭飞行」 |
| `beloong.command.npc.fly_to` | 「已让 %s 个 NPC 飞向 %s」 |
| `beloong.command.npc.reset` | 「已让 %s 个 NPC 恢复到默认状态」 |
| `beloong.command.npc.not_flying` | 「该 NPC 未开启飞行（先执行 fly … on）」 |

**验证：** 解析两个 JSON，断言键集合**完全一致**且各为 232 个；`not_flying` 等 5 个键都存在。

---

### T6 `MoEntity` 翅膀控制器飞行时 `PlayState.STOP`

**文件：** 改 `src/main/java/com/zonlong/beloong/entity/MoEntity.java`

**步骤：** 翅膀控制器谓词加一条：飞行时返回 `PlayState.STOP`。
```java
controllers.add(new AnimationController<>(this, "wings", 0, state -> {
    if (state.getAnimatable().isFlying()) {
        return PlayState.STOP;      // fly 动画已含翅膀动作，不能叠加，否则两套翅膀姿态互相拉扯
    }
    return state.setAndContinue(WINGS_IDLE);
}));
```
并写明 `PlayState.STOP` 为什么是安全的：停掉该控制器后，被 `fly` 驱动的骨骼由主控制器写、两者都没驱动的骨骼按既有复位机制回到 initial snapshot，不会残留上一个姿态。

**验证：** `gradlew build` + §五 B1/B4（末飞行时翅膀不被拉扯）。

---

## 三、提交切分

| 提交 | 内容 | 可独立验收的点 |
|---|---|---|
| **1** `fix(entity): 末的动画键名英文化，并让 fly/swim 恢复加载` | T1 | 静态探针即可证明三条动画能加载；实机可确认 `idle` 与 `wings_idle` 仍在播 |
| **2** `feat(entity): 通用 NPC 飞行 AI（fly on/off/to + reset）` | T2–T6 | §五 全部实机项 |

---

## 四、静态探针（A1–A8）

| # | 断言 | 手段 |
|---|---|---|
| A1 | 新成员已生成 | `javap -p` 查 `DATA_FLYING`、`defineSynchedData`、`setFlying`、`isFlying`、`flyTo`、`resetToDefault`、`flyAnimationName`、`takeoffHeight` |
| A2 | 换字段用的是原版类 | `javap -c` 常量池含 `FlyingMoveControl`、`FlyingPathNavigation`、`GroundPathNavigation`、`MoveControl` |
| A3 | `FLYING_SPEED` 进了属性表 | 常量池 + `Attributes.FLYING_SPEED` 引用 |
| A4 | **三条动画真的能加载** | 解析 `mo.animation.json`，断言 `fly`/`swim`/`swim_stand` 全部字符串中 `'` 与非 ASCII 计数**均为 0** |
| A5 | 改名彻底 | 断言 `idle`/`wings_idle` 在、`待机动画`/`翅膀默认（展开）` 不在；全仓 `grep` 旧名 0 命中 |
| A6 | 死骨骼条目已删 | 断言 `AllBody_Molang`/`Head_Molang` 不再出现于那三条；动画总数仍为 29；记录字节数变化 |
| A7 | 模型源文件仍未入库 | `git ls-files docs/models` 为空 |
| A8 | 构建 + lang 一致性 | `gradlew build` BUILD SUCCESSFUL；两语言文件键集合一致 |

---

## 五、实机验收清单（B1–B8）

| # | 动作 | 期望 |
|---|---|---|
| B1 | `/beloong npc fly @e on` | 起飞约 2 格并**悬停不掉**，播 `fly`；末的翅膀**不被翅膀层拉扯** |
| B2 | `/beloong npc fly @e to <50 格外坐标>` | **能飞到**（P1-1 那个 16 格上限在飞行上的回归测试） |
| B3 | `/beloong npc fly @e off`（半空） | 直接掉下落地、无摔落伤害（绝对无敌）、恢复地面行为 |
| B4 | `/beloong npc reset @e` | 回到地面站桩 + `idle`；末翅膀回 `wings_idle` |
| B5 | 未开飞行时 `fly @e to ...` | 明确报错并提示先 `on` |
| B6 | **存档 → 退出 → 重进** | NPC 必须**站在地上**、不浮空（`NoGravity` 持久化陷阱的回归测试） |
| B7 | 飞行中 `walk` | 以空中方式移动（已定为等同 `fly to`） |
| B8 | 飞行中 `attack` | 以飞行方式接近目标；若卡住需反馈 |

---

## 六、本计划最没把握的三件事

1. **换 `navigation`/`moveControl` 是否真即插即用** —— 已核对无缓存，但原版无先例。若 `fly on` 无反应，问题在此。
2. **`hoversInPlace=true` 是否真悬得住** —— 若缓慢下沉，需补周期性 `setWantedPosition`（Bee 的悬停就是每 N tick 重发，`Bee.java:1230`）。
3. **末的 `fly` 姿势** —— 加载问题已解决，但它是导入的玩家状态动画、缺 75 根骨骼，**像不像飞行只能看**。
