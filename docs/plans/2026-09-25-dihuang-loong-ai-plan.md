# 地黄龙 NPC 基础 AI 与基础动画 实施计划

**Date:** 2026-09-25
**Status:** 待执行
**Design:** [2026-09-25-dihuang-loong-ai-design.md](./2026-09-25-dihuang-loong-ai-design.md)
**Approach:** goal 驱动 + API 门面（`FloatGoal`/`LookAtPlayerGoal`/`FacePlayerGoal` 常驻，`TurnGoal` 默认不触发）
**验证模型:** 本项目**无测试源集**（`gradlew build` 输出 `compileTestJava NO-SOURCE` / `test NO-SOURCE`），不引入测试框架。每个任务的验证 = `gradlew build --console=plain` + 静态探针 + 实机清单（§四）。**本轮重力与 Molang 都无法静态验证，实机是唯一的验收手段。**

> ⚠️ **本计划已作废（2026-09-25，用户裁定）。**
> 基础 AI 改为写在**通用基类 `NpcEntity`** 里、地黄龙继承之。
> 请改用 **`docs/plans/2026-09-25-npc-base-class-plan.md`**。
> 本文件保留以便追溯差异，**不要照此实施**。

---

## 〇、实现期修订（相对设计文档）

| # | 设计文档写的 | 实际要做 | 原因 |
|---|---|---|---|
| **R-impl-1** | §五「不改语言文件（命令无 lang 键需求）」 | **新增 4 个命令反馈语言键**（中英各 4 条） | 命令反馈用 `Component.translatable` 就必须有键，这是本项目"所有面向玩家的文本走语言文件"的既有约定。原句是设计期的一处疏忽（且它引用错了 §九 的编号） |
| **R-impl-2** | 两个自定义 goal「按优先级共存」 | 两个自定义 goal **都不占用任何 `Goal.Flag`** | `LookAtPlayerGoal` 占用 `Goal.Flag.LOOK`。若 `FacePlayerGoal` 也占 LOOK，则优先级更高的 look goal 会把它挡死（身体永远不转）；若 `TurnGoal` 占 LOOK，则显式转身指令会被 look goal 挡掉。互斥改由 `canUse()` 的前置条件表达（D23） |

### 已核实的签名（写代码前逐条查过，避免"凭记忆写 API"）

| API | 签名 | 出处 |
|---|---|---|
| `yBodyRotO` / `yBodyRot` / `yHeadRotO` / `yHeadRot` | 均为 `public float` 字段 | `LivingEntity.java` |
| `Mth.rotLerp` / `Mth.lerp` / `Mth.wrapDegrees` / `Mth.clamp` | `float` 版本 | `Mth.java` |
| `PathNavigation#moveTo` | `boolean moveTo(double x, double y, double z, double speed)`；另有 `isDone()` / `stop()` | `PathNavigation.java` |
| 最近的玩家（排除旁观者） | `EntityGetter#getNearestPlayer(Entity, double)`；以及 `getNearestPlayer(TargetingConditions, LivingEntity, double, double, double)` | `EntityGetter.java` |
| `TargetingConditions` | `forNonCombat()`、`range(double)` | `TargetingConditions.java` |
| `LookAtPlayerGoal` | `(Mob, Class<? extends LivingEntity>, float lookDistance, float probability)`；`tick()` 里 `lookTime--`，`start()` 置 40~80 | `LookAtPlayerGoal.java` |
| `GeoModel#applyMolangQueries` | `void applyMolangQueries(AnimationState<T>, double animTime)` | `GeoModel.java:249` |
| `MathParser.setVariable` | `static void setVariable(String, DoubleSupplier)` | `MathParser.java:143-145` |
| `AnimationState` | `getAnimatable()` / `getPartialTick()` / `isMoving()` | `AnimationState.java` |
| `RegisterCommandsEvent` | `CommandDispatcher<CommandSourceStack> getDispatcher()` | NeoForge `event/RegisterCommandsEvent.java` |
| `Commands.literal` / `EntityArgument.entities()` / `Vec3Argument.vec3()` / `Vec3Argument.getVec3(ctx,name)` / `DoubleArgumentType` | 均如常 | 原版命令包 |

---

## 一、全局约束

1. **T1–T7 是同一个编译单元，一个原子提交。** 两个 goal 与实体互相引用、命令引用实体 API ⇒ 中途任何一次提交都编译不过。同上一轮，**不留"编译过但跑起来哑"的中间态**。
2. **不改** `beloong.mixins.json`、`ModEntities`、对话系统与 `NpcDialogue*`、`build.gradle`、网络包注册。
3. **不改资产**（geo / animations / texture 一律不动）。头部跟随靠 Java 侧注册 Molang 查询，**不碰动画文件**。
4. 语言键**按字母序插入**（`zh_cn.json` / `en_us.json` 都严格有序），不得追加到末尾。
5. 提交格式 `feat(entity): …` / `docs(entity): …`（中文正文）。**不主动 push**。
6. Javadoc 说明**为什么**；被本次推翻的旧结论（首版 D2 / D5）要留痕。

---

## 二、实施步骤

### T1 新增 `entity/ai/DihuangLoongTurnGoal.java`

**文件：** 新增 `src/main/java/com/zonlong/beloong/entity/ai/DihuangLoongTurnGoal.java`

```java
package com.zonlong.beloong.entity.ai;

import com.zonlong.beloong.entity.DihuangLoongEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * 「原地转到指定朝向」—— **只由 API 驱动的能力，默认永不触发**。
 * <p>
 * 触发方式是 {@link DihuangLoongEntity#turnTo(float)} 写入目标角度；
 * 目标为 {@code null} 时 {@link #canUse()} 恒 false，所以外人不调 API 就永远不会动。
 * <p>
 * <b>刻意不占用任何 {@link Goal.Flag}</b>：本 goal 只写 {@code yRot}（身体朝向），
 * 不碰导航与 {@code lookControl}。若占用 {@code Flag.LOOK}，会与优先级更高的
 * {@code LookAtPlayerGoal} 互斥，显式转身指令会被头部的 look goal 挡掉。
 * 与 {@code FacePlayerGoal} 的互斥由 {@code canUse()} 前置条件表达（设计文档 D23）。
 * <p>
 * {@code yBodyRot} 不在这里写 —— 原版 {@code LivingEntity#tickHeadTurn} 会以 0.3 插值
 * 自动让它追 {@code yRot}（{@code LivingEntity.java:2700-2714}），手写反而会与它对冲。
 */
public class DihuangLoongTurnGoal extends Goal {

    /** 每 tick 最大转身角度（度）。起始值，实机可调（设计文档 R12）。 */
    private static final float MAX_TURN_PER_TICK = 10.0F;
    /** 与目标的角差小于此值即认为到位（度）。 */
    private static final float ARRIVE_EPSILON = 1.0F;

    private final DihuangLoongEntity mob;

    public DihuangLoongTurnGoal(DihuangLoongEntity mob) {
        this.mob = mob;
    }

    @Override
    public boolean canUse() {
        return this.mob.getTurnTargetYaw() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.mob.getTurnTargetYaw() != null;
    }

    @Override
    public void tick() {
        Float target = this.mob.getTurnTargetYaw();
        if (target == null) {
            return;
        }

        float delta = Mth.wrapDegrees(target - this.mob.getYRot());
        if (Math.abs(delta) <= ARRIVE_EPSILON) {
            this.mob.setYRot(target);
            this.mob.clearTurnTarget();   // 到位即结束，goal 随之自然停止
            return;
        }

        this.mob.setYRot(this.mob.getYRot() + Mth.clamp(delta, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK));
    }
}
```

**验证：** 稍后随 T7 一起 `gradlew build`（本任务单独无法编译 —— 依赖 T3 的实体方法）。

---

### T2 新增 `entity/ai/DihuangLoongFacePlayerGoal.java`

**文件：** 新增 `src/main/java/com/zonlong/beloong/entity/ai/DihuangLoongFacePlayerGoal.java`

```java
package com.zonlong.beloong.entity.ai;

import com.zonlong.beloong.entity.DihuangLoongEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * 「玩家靠近时，把身体转过去」—— 本实体**唯一常驻的自主旋转来源**。
 * <p>
 * 与 {@code LookAtPlayerGoal} 的分工：那个只动 {@code yHeadRot}（头/颈，进而喂给 Molang），
 * 本 goal 只动 {@code yRot}（身体）。两者都不动对方的量，因此可以同时运行 ——
 * 效果是**头先转过去、身体随后慢慢跟上**。
 * <p>
 * <b>刻意不占用任何 {@link Goal.Flag}</b>，理由同 {@link DihuangLoongTurnGoal}：
 * 占用 {@code Flag.LOOK} 会被优先级更高的 {@code LookAtPlayerGoal} 挡死，身体就永远不转。
 * <p>
 * <b>外部指令优先</b>（设计文档 D23）：有 {@code turnTargetYaw} 或正在寻路时整条让位 ——
 * 既保证显式指令生效，也避免"边走边转身体"与寻路抢 {@code yRot}。
 */
public class DihuangLoongFacePlayerGoal extends Goal {

    /** 触发距离（格）。与 {@code LookAtPlayerGoal} 用的 8 格保持一致。 */
    private static final float LOOK_DISTANCE = 8.0F;
    /** 每 tick 最大转身角度（度）。起始值，实机可调（设计文档 R12）。 */
    private static final float MAX_TURN_PER_TICK = 10.0F;

    private final DihuangLoongEntity mob;

    /** 排除旁观者：复用原版的目标筛选条件。 */
    private final TargetingConditions targetConditions =
            TargetingConditions.forNonCombat().range(LOOK_DISTANCE);

    @Nullable
    private Player player;

    public DihuangLoongFacePlayerGoal(DihuangLoongEntity mob) {
        this.mob = mob;
    }

    @Override
    public boolean canUse() {
        if (!externalActionsIdle()) {
            return false;
        }
        this.player = this.mob.level().getNearestPlayer(
                this.targetConditions, this.mob, this.mob.getX(), this.mob.getEyeY(), this.mob.getZ());
        return this.player != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!externalActionsIdle() || this.player == null || !this.player.isAlive()) {
            return false;
        }
        return this.mob.distanceToSqr(this.player) <= (double) (LOOK_DISTANCE * LOOK_DISTANCE);
    }

    @Override
    public void stop() {
        this.player = null;
    }

    @Override
    public void tick() {
        if (this.player == null) {
            return;
        }
        // 与原版 Mob#lookAt 同一条公式：yaw = atan2(dz, dx) * 180/PI - 90
        float target = (float) (Mth.atan2(
                this.player.getZ() - this.mob.getZ(),
                this.player.getX() - this.mob.getX()) * (180.0D / Math.PI)) - 90.0F;

        float delta = Mth.wrapDegrees(target - this.mob.getYRot());
        this.mob.setYRot(this.mob.getYRot() + Mth.clamp(delta, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK));
    }

    /** 没有外部指令、且没在寻路。 */
    private boolean externalActionsIdle() {
        return this.mob.getTurnTargetYaw() == null && this.mob.getNavigation().isDone();
    }
}
```

**验证：** 随 T7 一起编译。

---

### T3 改 `entity/DihuangLoongEntity.java`

**文件：** 修改 `src/main/java/com/zonlong/beloong/entity/DihuangLoongEntity.java`

**改动 1 —— 类注释重写（现在写的是"无 AI"这一硬约束，已不成立）**：四条约束改为
"有基础 AI / 无敌 / 不可推动 / 永不消失"，并注明：

```
 * <b>本轮变更（2026-09-25，设计文档 2026-09-25-dihuang-loong-ai-design.md）：</b>
 * 首版是"无 AI 雕像"（覆写 {@code isNoAi()} 恒 true）。该覆写不只关掉了 goal，
 * 还经 {@code isEffectiveAi()} → {@code isControlledByLocalInstance()} 把
 * {@code LivingEntity#travel()} 的整个方法体一起挡掉了 ⇒ **连重力也没有**
 * （首版 D5"不调 setNoGravity 所以重力正常"的理由是错的，见 R9/R10）。
 * 现在改为有基础 AI：{@code FloatGoal} + {@code LookAtPlayerGoal} + {@code FacePlayerGoal}
 * 常驻，{@code TurnGoal} 与移动能力只作 API、默认不触发。
```

**改动 2 —— 构造函数删一行**：删 `setNoAi(true);`，保留 `setInvulnerable(true); setPersistenceRequired();`。

**改动 3 —— 删 `isNoAi()` 覆写**（整段方法）。**这两处必须一起删**（R8：构造函数标志位会被 `load()` 覆盖，覆写才是真闸门）。

**改动 4 —— 新增状态字段与 3 个 API 方法**：

```java
    /**
     * API 指定的目标朝向（度）；{@code null} = 无外部转身指令。
     * <p>
     * <b>刻意不写 NBT</b>：外部指令是运行期意图，不该跨存档/跨重载残留 ——
     * 重载后回到"站桩"是期望行为（设计文档 §七）。
     */
    @Nullable
    private Float turnTargetYaw;

    /** 转身到**绝对**朝向（度）。会先停掉寻路 —— 转身与走动互斥，语义确定。 */
    public void turnTo(float yaw) {
        if (this.level().isClientSide()) {
            return;   // 只在服务端生效（D27）：朝向本来就在原版同步范围内，不需要发包
        }
        this.getNavigation().stop();
        this.turnTargetYaw = yaw;
    }

    /** 走到目标点。会先清掉转身指令。{@code speedModifier} 乘在 MOVEMENT_SPEED 上。 */
    public void walkTo(Vec3 pos, double speedModifier) {
        if (this.level().isClientSide()) {
            return;
        }
        this.turnTargetYaw = null;
        this.getNavigation().moveTo(pos.x, pos.y, pos.z, speedModifier);
    }

    /** 取消转身与寻路，回到站桩。 */
    public void stopAction() {
        if (this.level().isClientSide()) {
            return;
        }
        this.turnTargetYaw = null;
        this.getNavigation().stop();
    }

    /** 供 {@code DihuangLoongTurnGoal} 读取当前目标朝向。 */
    @Nullable
    public Float getTurnTargetYaw() {
        return this.turnTargetYaw;
    }

    /** 供 {@code DihuangLoongTurnGoal} 在到位后清空目标。 */
    public void clearTurnTarget() {
        this.turnTargetYaw = null;
    }
```
（新增 import：`net.minecraft.world.phys.Vec3`、`org.jetbrains.annotations.Nullable`。）

**改动 5 —— 新增 `registerGoals()`**：

```java
    /**
     * 基础 AI。
     * <p>
     * <b>刻意不含任何自主移动 / 自主转圈 goal</b>（用户裁定）：默认永远站桩、朝向不变；
     * "转圈"与"移动"只经 {@link #turnTo}/{@link #walkTo} 由外部驱动。
     */
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));

        // 头：驱动 yHeadRot —— 它是头颈 Molang 跟随的**唯一数据源**。
        // LookControl 只有在存在 look 目标时才会让 yHeadRot 离开 yBodyRot；
        // probability 给 1.0，否则默认的 0.02 会让它平均 2.5 秒才想起看你一眼
        //（lookTime 40~80 tick 一轮，到期立刻重启，因此实际是持续跟随）。
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 8.0F, 1.0F));

        // 身体：玩家靠近时把 yRot 转过去
        this.goalSelector.addGoal(10, new DihuangLoongFacePlayerGoal(this));

        // 「转圈」能力：turnTargetYaw == null ⇒ canUse() 恒 false ⇒ 永不触发
        this.goalSelector.addGoal(20, new DihuangLoongTurnGoal(this));
    }
```
（新增 import：`net.minecraft.world.entity.ai.goal.FloatGoal`、`LookAtPlayerGoal`、`Player`、
`com.zonlong.beloong.entity.ai.DihuangLoongFacePlayerGoal`、`DihuangLoongTurnGoal`。）

**改动 6 —— 属性加移动速度**（`createAttributes()` 内）：

```java
                // 起始值：createMobAttributes() 的注册表默认是 0.7，对 1.5×2.5 的巨龙是"贴地飞"。
                // 只有外部 walkTo() 会用到它（设计文档 D25 / R13）。
                .add(Attributes.MOVEMENT_SPEED, 0.2D)
```

**改动 7 —— 动画改为 idle↔walk**：

```java
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    /**
     * 移动时的动画。
     * <p>
     * 资产里 {@code walk} 是 63 骨骼 / 1.375s / loop，**骨骼覆盖 0 缺失**（已核）。
     */
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
```
```java
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // transition 5 tick：idle ↔ walk 不再硬切
        controllers.add(new AnimationController<>(this, "main", 5, this::walkOrIdle));
    }

    /**
     * 待机 / 移动 二选一。
     * <p>
     * 判据用 GeckoLib 的 {@code isMoving()}：横向速度 ≥ 0.015/tick **且** {@code walkAnimation} 在动
     * （{@code GeoEntityRenderer.java:266-268}）。**原地转身不算移动** ⇒ 转圈时仍播 idle，
     * 正合"移动时才切 walk"。阈值若对慢速行走不敏感，在 renderer 覆写
     * {@code getMotionAnimThreshold} 调低（设计文档 R11）。
     */
    private <E extends GeoEntity> PlayState walkOrIdle(AnimationState<E> state) {
        return state.setAndContinue(state.isMoving() ? WALK : IDLE);
    }
```

**改动 8 —— 三个覆写保持不动**：`isInvulnerableTo` / `isPushable` / `isPersistenceRequired`。

**验证：** 随 T7 一起编译。

---

### T4 改 `client/model/DihuangLoongModel.java`（头部跟随）

**文件：** 修改 `src/main/java/com/zonlong/beloong/client/model/DihuangLoongModel.java`

```java
    /**
     * 头部朝向符号（实机调试常量）。
     * <p>
     * GeckoLib 对 **Molang** 值的 X/Y 轴会取负（{@code AnimationController.java:747-761}：
     * {@code toRadians(...)} 后 {@code *= -1}），而 MC 的 yaw 正向与 Blockbench 骨骼旋转正向
     * 之间的关系只能实机确认。**若实机发现头朝反方向，把这里改成 {@code -1.0F} 即可**
     * —— 不需要动动画文件（设计文档 R10）。
     */
    private static final float HEAD_YAW_SIGN = 1.0F;

    /**
     * 注册 {@code query.head_yaw} / {@code query.head_pitch} —— **资产早就等着这两个查询**。
     * <p>
     * {@code idle} 等 34 个动画里写着
     * {@code Head-Molang.rotation = ["math.clamp(query.head_pitch*0.5,-45,45)",
     * "-math.clamp(query.head_yaw*0.355556,-32,32)", 0]} 这样的表达式；
     * 而 GeckoLib **不内置** head_yaw / head_pitch —— 未注册的查询会**静默取 0**
     * （{@code MolangQueries.java:148-150}），所以此前那一整条 {@code -Molang} 骨骼链是死的。
     * <p>
     * 两个值都必须是**度**（{@code AnimationController.java:747-761}），且 {@code head_yaw} 必须是
     * **相对角**（头相对身体）—— GeckoLib 内置的 {@code query.head_y_rotation} 是**绝对** yaw，
     * 会把整体朝向叠进去，不能用（{@code MolangQueries.java:286}）。
     * <p>
     * <b>注意两点</b>：①Molang 变量是全局静态的，每个实体每帧必须重设 —— 本方法正是为此被调用；
     * ②此刻 {@code state.getController()} 是 {@code null}（{@code GeoEntityRenderer.java:268} 才 new
     * 出 state，{@code withController} 更晚），所以只能用**惰性 supplier**。
     */
    @Override
    public void applyMolangQueries(AnimationState<DihuangLoongEntity> animationState, double animTime) {
        super.applyMolangQueries(animationState, animTime);

        DihuangLoongEntity entity = animationState.getAnimatable();
        float partialTick = animationState.getPartialTick();

        // 与 GeoEntityRenderer 相同的插值口径（它用 Mth.rotLerp(partialTick, yBodyRotO, yBodyRot)），
        // 否则 20Hz 的阶梯感会很明显。
        float bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        float headYaw = Mth.rotLerp(partialTick, entity.yHeadRotO, entity.yHeadRot);
        float headPitch = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());

        MathParser.setVariable("query.head_yaw",
                () -> (double) (Mth.wrapDegrees(headYaw - bodyYaw) * HEAD_YAW_SIGN));
        MathParser.setVariable("query.head_pitch", () -> (double) headPitch);
    }
```
（新增 import：`net.minecraft.util.Mth`、`software.bernie.geckolib.animation.AnimationState`、
`software.bernie.geckolib.loading.math.MathParser`。）

同时更新类注释里"v1 只播 `idle`"那一段（现在还有 `walk`）。

**验证：** 随 T7 一起编译。**专服安全**：本类在 `client/model/` 且 `@OnlyIn` 一系，专服不加载（设计文档 §七）。

---

### T5 新增 `command/NpcCommand.java`

**文件：** 新增 `src/main/java/com/zonlong/beloong/command/NpcCommand.java`

```java
package com.zonlong.beloong.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.zonlong.beloong.entity.DihuangLoongEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.List;

/**
 * 地黄龙的调试 / 摆位命令。
 * <p>
 * <b>定位：验收与手动摆位工具，不是玩法内容。</b>它存在的直接原因是
 * {@link DihuangLoongEntity#turnTo}/{@link DihuangLoongEntity#walkTo} 是纯 API、
 * 默认没有任何调用方 —— 没有它，这两个能力在本轮交付里**无法被证明**
 * （设计文档 D29）。若将来不想留，删本类 + {@code BeLoongCore} 里的一行注册即可。
 * <p>
 * 只调实体 API，**不碰实体字段**（D31），顺带证明那三个 API 的签名够用。
 * 权限门槛 op（{@code hasPermission(2)}）：它改的是世界里的实体。
 */
public final class NpcCommand {

    private NpcCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("beloong")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("npc")
                        .then(Commands.literal("turn")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .then(Commands.argument("yaw", DoubleArgumentType.doubleArg())
                                                .executes(ctx -> turn(
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        (float) DoubleArgumentType.getDouble(ctx, "yaw"),
                                                        ctx.getSource())))))
                        .then(Commands.literal("walk")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .executes(ctx -> walk(
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        Vec3Argument.getVec3(ctx, "pos"),
                                                        1.0D,
                                                        ctx.getSource()))
                                                .then(Commands.argument("speed", DoubleArgumentType.doubleArg(0.0D))
                                                        .executes(ctx -> walk(
                                                                EntityArgument.getEntities(ctx, "targets"),
                                                                Vec3Argument.getVec3(ctx, "pos"),
                                                                DoubleArgumentType.getDouble(ctx, "speed"),
                                                                ctx.getSource()))))))
                        .then(Commands.literal("stop")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(ctx -> stop(
                                                EntityArgument.getEntities(ctx, "targets"),
                                                ctx.getSource()))))));
    }

    /** 转身到绝对角度。 */
    private static int turn(Collection<? extends Entity> targets, float yaw, CommandSourceStack source) {
        List<DihuangLoongEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        for (DihuangLoongEntity npc : npcs) {
            npc.turnTo(yaw);
        }
        source.sendSuccess(() -> Component.translatable(
                "beloong.command.npc.turn", npcs.size(), String.format("%.1f", yaw)), true);
        return npcs.size();
    }

    /** 走到坐标。 */
    private static int walk(Collection<? extends Entity> targets, Vec3 pos, double speed, CommandSourceStack source) {
        List<DihuangLoongEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        for (DihuangLoongEntity npc : npcs) {
            npc.walkTo(pos, speed);
        }
        source.sendSuccess(() -> Component.translatable(
                "beloong.command.npc.walk", npcs.size(), pos.toString(), String.format("%.2f", speed)), true);
        return npcs.size();
    }

    /** 停止转向与寻路。 */
    private static int stop(Collection<? extends Entity> targets, CommandSourceStack source) {
        List<DihuangLoongEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        for (DihuangLoongEntity npc : npcs) {
            npc.stopAction();
        }
        source.sendSuccess(() -> Component.translatable("beloong.command.npc.stop", npcs.size()), true);
        return npcs.size();
    }

    /** 命中**空**说明选到的不是地黄龙 —— 明确报错，不静默。 */
    private static int fail(CommandSourceStack source) {
        source.sendFailure(Component.translatable("beloong.command.npc.no_targets"));
        return 0;
    }

    private static List<DihuangLoongEntity> npcsIn(Collection<? extends Entity> targets) {
        return targets.stream()
                .filter(DihuangLoongEntity.class::isInstance)
                .map(DihuangLoongEntity.class::cast)
                .toList();
    }
}
```

> 注：`EntityArgument.getEntities` 返回 `Collection<? extends Entity>`，因此上面签名用通配符接收。

**验证：** 随 T7 一起编译。

---

### T6 改 `BeLoongCore.java`（注册命令）+ 两个语言文件

**文件 1：** 修改 `src/main/java/com/zonlong/beloong/BeLoongCore.java`

```java
    /**
     * 注册命令。
     * <p>
     * {@code RegisterCommandsEvent} 在每次服务端启动（含单人世界）时触发，
     * 命令注册在**该次**的 dispatcher 上。
     */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        NpcCommand.register(event.getDispatcher());
    }
```
（新增 import：`com.zonlong.beloong.command.NpcCommand`、`net.neoforged.neoforge.event.RegisterCommandsEvent`。
该类已经是 `NeoForge.EVENT_BUS.register(this)` 的监听者，无需改注册方式。）

**文件 2：** 修改 `src/main/resources/assets/beloong/lang/zh_cn.json`（4 条，**按字母序插到第一个 `beloong.configuration.*` 之前**）

```json
  "beloong.command.npc.no_targets": "没有找到地黄龙（本命令只作用于 beloong:dihuang_loong）",
  "beloong.command.npc.stop": "已让 %s 个地黄龙停止动作",
  "beloong.command.npc.turn": "已让 %s 个地黄龙转向 %s°",
  "beloong.command.npc.walk": "已让 %s 个地黄龙走向 %s（速度 %s）",
```

**文件 3：** 修改 `src/main/resources/assets/beloong/lang/en_us.json`（同 4 条，同位置）

```json
  "beloong.command.npc.no_targets": "No Dihuang Loong found (this command only affects beloong:dihuang_loong)",
  "beloong.command.npc.stop": "Stopped %s Dihuang Loong",
  "beloong.command.npc.turn": "Turning %s Dihuang Loong to %s degrees",
  "beloong.command.npc.walk": "Walking %s Dihuang Loong to %s (speed %s)",
```

**验证：** 随 T7 一起；另外静态确认两文件的键集合一致。

---

### T7 构建 + 静态探针

```
cd D:\Minecraft\BeLoong-Core-NPC
.\gradlew.bat build --console=plain
```
再跑 §三 的 7 条探针。

---

### T8 原子提交

T1–T7 一个提交：

```
git add src/
git commit -m "feat(entity): 地黄龙 NPC 基础 AI 与基础动画（身体转向 + 头颈 Molang 跟随）
...
```

---

## 三、静态探针清单（提交前一次跑完）

| # | 命令 / 检查 | 期望 |
|---|---|---|
| 1 | `.\gradlew.bat build --console=plain` | `BUILD SUCCESSFUL`，无新警告 |
| 2 | 在 `DihuangLoongEntity.java` 里搜 `isNoAi` / `setNoAi` | **无输出**（D16 的两处都删干净） |
| 3 | 搜 `registerGoals` | 1 处（新增） |
| 4 | 搜 `isInvulnerableTo` / `isPushable` / `isPersistenceRequired` | 各 1 处（未被误删） |
| 5 | 在 `DihuangLoongModel.java` 里搜 `applyMolangQueries`、`query.head_yaw`、`query.head_pitch` | 各命中 |
| 6 | `git diff --stat HEAD -- src/main/resources/beloong.mixins.json src/main/java/com/zonlong/beloong/registry/ModEntities.java build.gradle` | 无输出 |
| 7 | `NpcCommand.java` 里搜 `hasPermission(2)`；两个 lang 文件的键集合一致 | 各 1 处 / 一致 |

---

## 四、实机验收清单（需用户执行）

| # | 步骤 | 预期 | 验证什么 |
|---|---|---|---|
| 1 | 在地面上方几格 `/summon beloong:dihuang_loong` | **下落并落地** | D16/D17（改前它悬在召唤点不动） |
| 2 | 观察落地后的模型 | 脚是否陷地 —— **记下偏移量** | R9（首版遗留风险，现在才暴露） |
| 3 | 静置数分钟 | 不动、**不自主转圈**、朝向不变、不消失 | 需求 2 前半 |
| 4 | 走进 8 格 | **头/颈先转过来**，身体随后慢慢转正 | D20 + D19 |
| 5 | 绕它走一圈 | 身体持续面朝你 | D19；顺便定 `HEAD_YAW_SIGN` 正负 |
| 6 | 走出 8 格 | 停止跟随，**停在最后朝向** | §七 口径 |
| 7 | 站高处/低处看它 | 头随俯仰动、方向正确 | `query.head_pitch` |
| 8 | 攻击它 / 挤压它 | 无伤害反馈、不位移 | 需求 3 |
| 9 | `/kill @e[type=beloong:dihuang_loong]` | 可移除 | 管理后路 |
| 10 | 退档重进 | 仍在、仍无敌、不消失；记下重载后朝向 | 无存档数据 |
| 11 | 把它推到水里 | `FloatGoal` 上浮 | D18 |
| 12 | `/beloong npc turn @e[type=beloong:dihuang_loong] 90` | 平滑转到 90° | D21/D29 |
| 13 | `/beloong npc walk @e[type=beloong:dihuang_loong] ~ ~ ~5` | 走过去；**走时播 `walk`、停下回 `idle`** | D22/D24；R11（阈值）、R13（速度） |
| 14 | `/beloong npc stop @e[…]` | 停下并停在当前朝向 | D21 |
| 15 | 对牛执行同一命令 | 报"没有找到地黄龙"，不崩 | §七 |
| 16 | 空手右键它 | 对话无回归 | — |
| 17 | 专服启动 + 执行一次命令 | 无 `NoClassDefFoundError` | D20 的客户端归属 |

---

## 五、回填（实现并验收后）

1. 设计文档补「实现期修订」小节：R-impl-1 / R-impl-2，以及实机定下的
   `HEAD_YAW_SIGN` / 转身速度 / `MOVEMENT_SPEED` 三个常量的最终取值。
2. `memory/project-context.md` 子系统 10：把"实现未开始"改为口径落地。
3. `memory/decisions-log.md`：补一条"Molang 变量是全局静态的 ⇒ 必须每帧重设"，
   以及"自定义 goal 不要随手占用 `Goal.Flag`"（占用 LOOK 会被原版 look goal 挡死）两条通用教训。
4. 若实机确认了 R9 偏移量：在 `DihuangLoongRenderer#preRender` 加平移，并回填设计文档 R9。
