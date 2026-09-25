# 通用 NPC 基类 实施计划

**Date:** 2026-09-25
**Status:** 待执行
**Design:** [2026-09-25-npc-base-class-design.md](./2026-09-25-npc-base-class-design.md)
**行为规格与事实来源:** [2026-09-25-dihuang-loong-ai-design.md](./2026-09-25-dihuang-loong-ai-design.md) §三（继续有效）
**取代:** 本文档**取代**已作废的 `2026-09-25-dihuang-loong-ai-plan.md`
**Approach:** 抽象基类 `NpcEntity` 承载语义 + AI + API + 动画状态机；地黄龙缩为子类
**验证模型:** 本项目**无测试源集**，不引入测试框架。验证 = `gradlew build --console=plain` + 静态探针 + 实机清单（§四）。**重力、Molang、冲刺复位、攻击可达性都无法静态验证，实机是唯一手段。**

---

## 〇、写代码前已核实的签名

| API | 签名 | 出处 |
|---|---|---|
| `createMobAttributes()` | **不含** `ATTACK_DAMAGE`（`createLivingAttributes()` 只给 MAX_HEALTH / KNOCKBACK_RESISTANCE / MOVEMENT_SPEED / ARMOR / ARMOR_TOUGHNESS）⇒ 攻击力必须显式 `add` | `LivingEntity.java`、`Mob.java` |
| 玩家默认值 | `MOVEMENT_SPEED = 0.1F` | `Player.java:231` |
| `MoveControl.tick` | `setSpeed((float)speedModifier * getAttributeValue(MOVEMENT_SPEED))` | `MoveControl.java` |
| `LivingEntity#setSprinting` | 挂/摘 `SPEED_MODIFIER_SPRINTING` 瞬态修饰符（**挂上无人摘**） | `LivingEntity.java` |
| `PathNavigation#moveTo` | `boolean moveTo(double x, double y, double z, double speed)`、`isDone()`、`stop()` | `PathNavigation.java` |
| `MeleeAttackGoal` | 构造器 `(PathfinderMob, double speedModifier, boolean followingTargetEvenIfNotSeen)`；`setFlags(MOVE, LOOK)`；**`canUse()` 有 20 tick 限流**（`i - lastCanUseCheck < 20L → false`）；`stop()` 用 `EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target)` 决定是否清目标 | `MeleeAttackGoal.java:35-48,87-94` |
| `Mob#doHurtTarget` | 首行 `float f = (float)this.getAttributeValue(Attributes.ATTACK_DAMAGE);` | `Mob.java` |
| 其余（`applyMolangQueries` / `MathParser.setVariable` / `yBodyRotO` 等） | 见行为规格文档 §三 | — |

---

## 一、全局约束

1. **T1–T9 是同一个编译单元，一个原子提交。** 基类与三个 goal 与子类互相引用，中途任何提交都编译不过。
2. **不改** `beloong.mixins.json`、`ModEntities`、对话系统与 `NpcDialogue*`、`build.gradle`、网络包注册、资产。
3. **`ModAttributes` 预计不改**（它仍调 `DihuangLoongEntity.createAttributes()`，子类转发到基类工厂）；若编译报错再按实际调整。
4. 语言键**按字母序插入**（`beloong.command.*` 排在 `beloong.configuration.*` 之前）。
5. 提交格式 `feat(entity): …` / `docs(entity): …`（中文正文）。**不主动 push**。
6. Javadoc 说明**为什么**；被取代的旧结论（首版 D2/D5、上一版的组件放置）留痕。

---

## 二、实施步骤

### T1 新增 `entity/NpcEntity.java`（核心）

**文件：** 新增 `src/main/java/com/zonlong/beloong/entity/NpcEntity.java`

```java
package com.zonlong.beloong.entity;

import com.zonlong.beloong.entity.ai.NpcAttackGoal;
import com.zonlong.beloong.entity.ai.NpcFacePlayerGoal;
import com.zonlong.beloong.entity.ai.NpcTurnGoal;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 本模组**通用 NPC 基类**。
 * <p>
 * 定位：整合包里的"站桩 NPC"。默认**不自主行动** —— 不游走、不转圈、不索敌；
 * 转身 / 走路 / 奔跑 / 攻击都只作为**能力**，由外部（命令、对话、将来的脚本）通过
 * {@link #turnTo}/{@link #walkTo}/{@link #runTo}/{@link #attack} 驱动。
 *
 * <h2>定义性语义（子类要例外请自己覆写方法，这里不提供开关）</h2>
 * <ul>
 *   <li><b>无敌</b>：{@link #isInvulnerableTo} 只放行 {@code BYPASSES_INVULNERABILITY}
 *       （{@code /kill} 与虚空）—— 刻意留的管理后路；</li>
 *   <li><b>不可推动</b>：{@link #isPushable()} 恒 false；</li>
 *   <li><b>永不消失</b>：{@link #isPersistenceRequired()} 恒 true。</li>
 * </ul>
 * 这三个写成**覆写 getter** 而不是构造函数里 set：{@code /summon} 的流程是
 * "先 {@code create()} 再 {@code load(标签)}"，而 {@code load()} 会把
 * {@code Invulnerable}/{@code PersistenceRequired} 从标签读回、缺键即覆盖为 false。
 *
 * <h2>为什么基类不覆写 {@code isNoAi()}</h2>
 * 首版地黄龙是"无 AI 雕像"，靠覆写 {@code isNoAi()} 恒 true 实现。那个覆写不只关掉了 goal：
 * {@code LivingEntity#travel()} 的**整个方法体**被 {@code isControlledByLocalInstance()} 包住
 * （{@code LivingEntity.java:2219-2220}），而它 = {@code isEffectiveAi()} = {@code !isNoAi()}
 * ⇒ **连重力与位移积分一起没了**，实体被"钉"在召唤点。
 * 本基类因此**不碰** {@code isNoAi()}，重力自然生效。
 *
 * <h2>子类必须提供</h2>
 * 实体类型绑定（{@code ModEntities}）、碰撞箱、渲染器、模型，以及属性表
 * （用 {@link #createNpcAttributes()} 作起点）。
 */
public abstract class NpcEntity extends PathfinderMob implements GeoEntity {

    // ===================== 可覆写的默认值（用户裁定：约定 + 可覆写）=====================

    /** 待机动画名。子类资产里若叫别的名字就覆写。 */
    protected String idleAnimationName() {
        return "idle";
    }

    /** 移动动画名。 */
    protected String walkAnimationName() {
        return "walk";
    }

    /** {@code idle} ↔ {@code walk} 的过渡时长（tick）。0 = 硬切。 */
    protected int animationTransitionTicks() {
        return 5;
    }

    /** 看向 / 面朝玩家的距离（格）。 */
    protected float facePlayerDistance() {
        return 8.0F;
    }

    /** 转身角速度上限（度/tick）。 */
    protected float maxTurnPerTick() {
        return 10.0F;
    }

    /**
     * 走路档位，乘在 {@code MOVEMENT_SPEED} 上。
     * <p>
     * 默认 1.0 而**不是** {@code MoveControl} 的初值 0.25 —— 那个初值会让 NPC 只有 1/4 速度。
     * 配合 {@link #createNpcAttributes()} 的 0.1，正好是**玩家走路速度**。
     */
    protected double walkSpeedModifier() {
        return 1.0D;
    }

    // ===================== 属性默认值 =====================

    /**
     * 通用 NPC 的默认属性表。子类用它作起点追加/覆盖。
     * <p>
     * <b>注意 {@code ATTACK_DAMAGE} 必须显式 add</b>：{@code createMobAttributes()} 只含
     * MAX_HEALTH / KNOCKBACK_RESISTANCE / MOVEMENT_SPEED / ARMOR / ARMOR_TOUGHNESS，
     * 没有攻击力（原版是 {@code Monster.createMonsterAttributes()} 才加）。
     * <p>
     * 移速 0.1 = 玩家默认走路速度（{@code Player.java:231}）；奔跑不靠更高的属性值，
     * 而是走**原版冲刺机制**（见 {@link #runTo}）。
     */
    public static AttributeSupplier.Builder createNpcAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1000.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)
                .add(Attributes.ATTACK_DAMAGE, 100.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.1D);
    }

    // ===================== 外部指令状态 =====================

    /** API 指定的目标朝向（度）；null = 无转身指令。**不写 NBT**：外部指令不跨存档。 */
    @Nullable
    private Float turnTargetYaw;

    /** 是否处于"被命令攻击"状态。只有它为 true 时 {@code NpcAttackGoal} 才会运行。 */
    private boolean attackCommandActive;

    protected NpcEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        // 与 isInvulnerableTo / isPersistenceRequired 两个覆写并存：让字段本身与保存出的 NBT 一致，
        // 但语义由覆写保证（构造函数里的 set 会被 /summon 的 load() 覆盖）。
        setInvulnerable(true);
        setPersistenceRequired();
    }

    // ===================== 定义性语义 =====================

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || super.isInvulnerableTo(source);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isPersistenceRequired() {
        return true;
    }

    // ===================== 基础 AI =====================

    /**
     * 基础 AI。**刻意不含自主游走 / 自主转圈 / 自主索敌**。
     * <p>
     * 优先级与 {@code Goal.Flag} 的关系（这是本设计最容易踩的地方，见设计文档 3.6~3.9）：
     * <ul>
     *   <li>{@code NpcAttackGoal}（{@code MeleeAttackGoal} 子类）占用 <b>MOVE + LOOK</b>，
     *       {@code LookAtPlayerGoal} 占用 <b>LOOK</b>。若攻击 goal 优先级更低，
     *       则"玩家在 8 格内"时它会被 look goal **永久挡死** ⇒ 永远打不到人。
     *       所以攻击 goal 排在 **3**，比 look goal 的 5 更靠前。</li>
     *   <li>两个自定义 goal（面朝玩家 / 转身）**不占用任何 Flag**，因此不与原版 look goal 互斥；
     *       它们之间的互斥由 {@code canUse()} 的前置条件表达。</li>
     * </ul>
     */
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(3, new NpcAttackGoal(this, 1.0D, true));
        // probability 给 1.0：默认的 0.02 会让它平均 2.5 秒才看你一眼。
        // lookTime 40~80 tick 一轮、到期立刻重启 ⇒ 实际是持续跟随。
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, facePlayerDistance(), 1.0F));
        this.goalSelector.addGoal(10, new NpcFacePlayerGoal(this));
        this.goalSelector.addGoal(20, new NpcTurnGoal(this));
    }

    /**
     * 每 tick 的服务端 AI 收尾。
     * <p>
     * <b>冲刺复位</b>：{@code LivingEntity#setSprinting(true)} 会往 {@code MOVEMENT_SPEED}
     * 挂一个瞬态修饰符，而**原版没有任何代码会在寻路结束时摘掉它** ⇒ 走完一段奔跑后会
     * 永久快一档、还带着冲刺粒子。这里在寻路结束时显式复位。
     * <p>
     * <b>攻击指令的失效兜底</b>：目标死亡、或目标变成了创造/旁观玩家时，
     * {@code MeleeAttackGoal.canUse()} 会返回 false；但若该 goal **从未启动过**，
     * 它的 {@code stop()} 就不会被调用 ⇒ {@code attackCommandActive} 会一直挂着，
     * 把 {@code NpcFacePlayerGoal} 永久挡住。所以在这里兜底清理。
     */
    @Override
    protected void customServerAiStep() {
        if (this.isSprinting() && this.getNavigation().isDone()) {
            this.setSprinting(false);
        }

        if (this.attackCommandActive) {
            LivingEntity target = this.getTarget();
            // 与 MeleeAttackGoal.canUse() 的排除条件保持一致
            if (target == null || !target.isAlive() || !EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target)) {
                this.clearAttackCommand();
            }
        }
    }

    // ===================== 外部驱动 API（只在服务端生效）=====================

    /**
     * 原地转身到**绝对**朝向（度）。会先取消其它外部指令 —— 语义确定：下令即接管。
     * <p>
     * 只写 {@code yRot}，不碰 {@code yBodyRot}：原版 {@code LivingEntity#tickHeadTurn} 会以
     * 0.3 插值让身体追上来（{@code LivingEntity.java:2700-2714}），手写反而会与它的夹取对冲。
     */
    public void turnTo(float yaw) {
        if (this.level().isClientSide()) {
            return;
        }
        this.clearMotionCommands();
        this.attackCommandActive = false;
        this.setTarget(null);
        this.turnTargetYaw = yaw;
    }

    /** 走到目标点（玩家走路速度）。 */
    public void walkTo(Vec3 pos) {
        this.moveTo(pos, false);
    }

    /**
     * 跑到目标点。
     * <p>
     * 不用更高的移速属性，而是 {@code setSprinting(true)} —— 冲刺加速是
     * {@code LivingEntity} 自带的瞬态修饰符（不是玩家专属），因此"与玩家奔跑同源"，
     * 将来原版改倍率我们也自动跟上。
     */
    public void runTo(Vec3 pos) {
        this.moveTo(pos, true);
    }

    /** 命令它去攻击某个目标（会先取消其它外部指令）。传 {@code null} 取消攻击。 */
    public void attack(@Nullable LivingEntity target) {
        if (this.level().isClientSide()) {
            return;
        }
        this.clearMotionCommands();
        this.attackCommandActive = target != null;
        this.setTarget(target);
    }

    /** 取消全部外部指令（转身 / 移动 / 冲刺 / 攻击），回到站桩。 */
    public void stopAction() {
        if (this.level().isClientSide()) {
            return;
        }
        this.clearMotionCommands();
        this.attackCommandActive = false;
        this.setTarget(null);
    }

    private void moveTo(Vec3 pos, boolean sprint) {
        if (this.level().isClientSide()) {
            return;
        }
        this.clearMotionCommands();
        this.attackCommandActive = false;
        this.setTarget(null);
        if (sprint) {
            this.setSprinting(true);
        }
        this.getNavigation().moveTo(pos.x, pos.y, pos.z, this.walkSpeedModifier());
    }

    /** 清掉"位移类"指令（转身 / 寻路 / 冲刺），不碰攻击指令。 */
    private void clearMotionCommands() {
        this.turnTargetYaw = null;
        this.getNavigation().stop();
        this.setSprinting(false);
    }

    /**
     * 是否有外部指令在进行中（转身 / 攻击 / 寻路）。
     * <p>
     * {@code NpcFacePlayerGoal} 用它整条让位 —— 显式指令优先于自主反应，
     * 同时避免"边走边转身体"与寻路抢 {@code yRot}。
     */
    public boolean isExternallyCommanded() {
        return this.turnTargetYaw != null || this.attackCommandActive || !this.getNavigation().isDone();
    }

    @Nullable
    public Float getTurnTargetYaw() {
        return this.turnTargetYaw;
    }

    /** 供 {@code NpcTurnGoal} 在到位后清空目标。 */
    public void clearTurnTarget() {
        this.turnTargetYaw = null;
    }

    /** 供 {@code NpcAttackGoal} 查询是否被命令攻击。 */
    public boolean isAttackCommandActive() {
        return this.attackCommandActive;
    }

    /** 供 {@code NpcAttackGoal} 在停止时清理。 */
    public void clearAttackCommand() {
        this.attackCommandActive = false;
        this.setTarget(null);
        this.getNavigation().stop();
    }

    // ===================== GeckoLib =====================

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    /**
     * 主控制器：待机 / 移动二选一。
     * <p>
     * <b>{@code RawAnimation} 刻意在这里构建而不是在构造函数里</b>：动画名来自**可覆写**方法
     * （{@link #idleAnimationName()} 等），而构造函数里调虚方法会踩"子类字段尚未初始化"的经典坑。
     * {@code registerControllers} 由 {@code AnimatableManager} **惰性**调用，那时子类早已构造完。
     * <p>
     * 判据用 GeckoLib 的 {@code isMoving()}（横向速度 ≥ 0.015/tick 且 {@code walkAnimation} 在动）。
     * **原地转身不算移动**，所以转圈时仍播 idle。走路档位是 0.1（玩家速度），比多数原版怪慢，
     * 若实测读不到移动，在 renderer 覆写 {@code getMotionAnimThreshold} 调低。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        final RawAnimation idle = RawAnimation.begin().thenLoop(this.idleAnimationName());
        final RawAnimation walk = RawAnimation.begin().thenLoop(this.walkAnimationName());
        controllers.add(new AnimationController<>(this, "main", this.animationTransitionTicks(),
                state -> state.setAndContinue(state.isMoving() ? walk : idle)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
```

**验证：** 随 T9 一起编译。

---

### T2–T4 三个 goal

**T2 — 新增 `entity/ai/NpcTurnGoal.java`**

```java
package com.zonlong.beloong.entity.ai;

import com.zonlong.beloong.entity.NpcEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * 「原地转到 API 指定的朝向」——**只由 {@link NpcEntity#turnTo} 驱动的能力，默认永不触发**。
 * <p>
 * <b>刻意不占用任何 {@link Goal.Flag}</b>（不是遗漏）：{@code LookAtPlayerGoal} 占 {@code Flag.LOOK}，
 * 若本 goal 也占 LOOK，则只要玩家在跟随距离内，优先级更高的 look goal 就会让它**永远启动不了**
 * —— 显式转身指令会被静默吃掉。{@code GoalSelector} 用 {@code lockedFlags} 做这种互斥仲裁
 * （见设计文档 3.7）。
 */
public class NpcTurnGoal extends Goal {

    /** 与目标的角差小于此值即认为到位（度）。 */
    private static final float ARRIVE_EPSILON = 1.0F;

    private final NpcEntity npc;

    public NpcTurnGoal(NpcEntity npc) {
        this.npc = npc;
    }

    @Override
    public boolean canUse() {
        return this.npc.getTurnTargetYaw() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.npc.getTurnTargetYaw() != null;
    }

    @Override
    public void tick() {
        Float target = this.npc.getTurnTargetYaw();
        if (target == null) {
            return;
        }

        float delta = Mth.wrapDegrees(target - this.npc.getYRot());
        if (Math.abs(delta) <= ARRIVE_EPSILON) {
            this.npc.setYRot(target);
            this.npc.clearTurnTarget();   // 到位即结束
            return;
        }

        float maxTurn = this.npc.maxTurnPerTick();
        this.npc.setYRot(this.npc.getYRot() + Mth.clamp(delta, -maxTurn, maxTurn));
    }
}
```

**T3 — 新增 `entity/ai/NpcFacePlayerGoal.java`**

```java
package com.zonlong.beloong.entity.ai;

import com.zonlong.beloong.entity.NpcEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * 「玩家靠近时把身体转过去」——常驻的自主旋转来源。
 * <p>
 * 与 {@code LookAtPlayerGoal} 的分工：那个只动 {@code yHeadRot}（头/颈，进而喂给 Molang），
 * 本 goal 只动 {@code yRot}（身体）。两者互不触碰对方的量，因此可以**同时运行** ——
 * 效果是**头先转过去、身体随后慢慢跟上**。
 * <p>
 * <b>刻意不占用任何 {@link Goal.Flag}</b>，理由同 {@link NpcTurnGoal}。
 * <p>
 * <b>外部指令优先</b>：有转身/攻击/寻路在进行时整条让位。
 */
public class NpcFacePlayerGoal extends Goal {

    private final NpcEntity npc;

    /** 排除旁观者：复用原版的目标筛选条件。 */
    private final TargetingConditions targetConditions;

    @Nullable
    private Player player;

    public NpcFacePlayerGoal(NpcEntity npc) {
        this.npc = npc;
        this.targetConditions = TargetingConditions.forNonCombat().range(npc.facePlayerDistance());
    }

    @Override
    public boolean canUse() {
        if (this.npc.isExternallyCommanded()) {
            return false;
        }
        this.player = this.npc.level().getNearestPlayer(
                this.targetConditions, this.npc, this.npc.getX(), this.npc.getEyeY(), this.npc.getZ());
        return this.player != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.npc.isExternallyCommanded() || this.player == null || !this.player.isAlive()) {
            return false;
        }
        float distance = this.npc.facePlayerDistance();
        return this.npc.distanceToSqr(this.player) <= (double) (distance * distance);
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
                this.player.getZ() - this.npc.getZ(),
                this.player.getX() - this.npc.getX()) * (180.0D / Math.PI)) - 90.0F;

        float delta = Mth.wrapDegrees(target - this.npc.getYRot());
        float maxTurn = this.npc.maxTurnPerTick();
        this.npc.setYRot(this.npc.getYRot() + Mth.clamp(delta, -maxTurn, maxTurn));
    }
}
```

**T4 — 新增 `entity/ai/NpcAttackGoal.java`**

```java
package com.zonlong.beloong.entity.ai;

import com.zonlong.beloong.entity.NpcEntity;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

/**
 * 攻击 goal —— 以 {@link NpcEntity#attack} 的命令标志位把关。
 * <p>
 * 直接注册原版 {@code MeleeAttackGoal} 是不行的：任何来源只要给实体设了 {@code target}
 * （别的模组、将来的 goal）它就会开打，那就不是"只作 API"了。
 * <p>
 * <b>优先级必须比 {@code LookAtPlayerGoal} 更靠前</b>：{@code MeleeAttackGoal} 占用
 * {@code Goal.Flag.MOVE + LOOK}，而 look goal 占 LOOK；若本 goal 优先级更低，
 * "玩家在跟随距离内"时它会被 look goal 永久挡死 ⇒ 永远打不到人（设计文档 3.6~3.8）。
 * <p>
 * 伤害由 {@code Mob#doHurtTarget} 读 {@code ATTACK_DAMAGE} 得出（基类默认 100）。
 * <p>
 * 注意原版 {@code canUse()} 有 **20 tick 限流**：下令后最多可能等 1 秒才起步。
 */
public class NpcAttackGoal extends MeleeAttackGoal {

    private final NpcEntity npc;

    public NpcAttackGoal(NpcEntity npc, double speedModifier, boolean followingTargetEvenIfNotSeen) {
        super(npc, speedModifier, followingTargetEvenIfNotSeen);
        this.npc = npc;
    }

    @Override
    public boolean canUse() {
        return this.npc.isAttackCommandActive() && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return this.npc.isAttackCommandActive() && super.canContinueToUse();
    }

    @Override
    public void stop() {
        super.stop();
        this.npc.clearAttackCommand();
    }
}
```

**验证：** 随 T9 一起编译。

---

### T5 把 `entity/DihuangLoongEntity.java` 缩成子类

**文件：** 修改 `src/main/java/com/zonlong/beloong/entity/DihuangLoongEntity.java`（**整体替换**）

**必须删干净**（否则"有 AI"不成立）：
`setNoAi(true)`、`isNoAi()` 覆写、`registerGoals` 缺失、动画控制器、`isInvulnerableTo` / `isPushable` / `isPersistenceRequired`（**上移到基类**）。

```java
package com.zonlong.beloong.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/**
 * 地黄龙 NPC —— 本模组**第一个生物 NPC**，也是 {@link NpcEntity} 的第一个子类。
 * <p>
 * 它只负责"是什么"：实体类型绑定（{@code ModEntities}）、碰撞箱（那里）、模型与贴图
 * （{@code client/} 侧）、属性表，以及（可选）覆写基类的动画名/距离/转速等默认值 ——
 * **AI、无敌/不可推动/不消失、转向/移动/攻击能力、动画状态机全部在基类里**。
 * <p>
 * <b>历史</b>：首版它是"无 AI 雕像"（覆写 {@code isNoAi()} 恒 true 且不注册 goal），
 * 那个覆写顺带把重力也关掉了（{@code LivingEntity#travel()} 整个方法体被
 * {@code isControlledByLocalInstance()} 包住）—— 详见 {@link NpcEntity} 的类注释与
 * {@code docs/plans/2026-09-25-npc-base-class-design.md}。
 * <p>
 * 设计文档：{@code docs/plans/2026-09-21-dihuang-loong-npc-design.md}（首版，R9/R10 修订）、
 * {@code docs/plans/2026-09-25-npc-base-class-design.md}（本架构）。
 */
public class DihuangLoongEntity extends NpcEntity {

    public DihuangLoongEntity(EntityType<? extends DihuangLoongEntity> entityType, Level level) {
        super(entityType, level);
    }

    /**
     * 属性表。基础值全部来自 {@link NpcEntity#createNpcAttributes()}
     * （1000 血 / 1.0 击退抗性 / 100 攻击力 / 0.1 移速）—— 地黄龙目前不需要改动其中任何一项，
     * 这个方法保留是为了"子类可以追加自己的属性"这一惯例不断掉。
     * <p>
     * 注册点仍是 {@code registry/ModAttributes}（属性是服务端权威的，必须放双端类）。
     */
    public static AttributeSupplier.Builder createAttributes() {
        return NpcEntity.createNpcAttributes()
                .add(Attributes.MAX_HEALTH, 1000.0D);
    }
}
```

> 注：`createAttributes()` 里重复写 1000 是**刻意的占位**——它让"子类如何追加属性"这件事有活样例；
> 若实现时觉得冗余，可改为直接 `return NpcEntity.createNpcAttributes();` 并删掉 `Attributes` import。
> **二选一，不要留一个空方法。**

**验证：** 随 T9 一起编译 + 静态探针（§三）。

---

### T6 `client/model/DihuangLoongModel.java` 加 Molang 头部跟随

**文件：** 修改 `src/main/java/com/zonlong/beloong/client/model/DihuangLoongModel.java`

（**D40：不进基类**，用户裁定 Molang 跟随留在各自模型里。）

```java
    /**
     * 头部朝向符号（实机调试常量）。
     * <p>
     * GeckoLib 对 **Molang** 值的 X/Y 轴会取负（{@code AnimationController.java:747-761}：
     * {@code toRadians(...)} 后 {@code *= -1}），而 MC 的 yaw 正向与 Blockbench 骨骼旋转正向
     * 之间的关系只能实机确认。**若实机发现头朝反方向，把这里改成 {@code -1.0F} 即可**
     * —— 不需要动动画文件。
     */
    private static final float HEAD_YAW_SIGN = 1.0F;

    /**
     * 注册 {@code query.head_yaw} / {@code query.head_pitch} —— **资产早就等着这两个查询**。
     * <p>
     * {@code idle} 等 34 个动画里写着
     * {@code Head-Molang.rotation = ["math.clamp(query.head_pitch*0.5,-45,45)",
     * "-math.clamp(query.head_yaw*0.355556,-32,32)", 0]} 这类表达式；
     * 而 GeckoLib **不内置** head_yaw / head_pitch —— 未注册的查询会**静默取 0**
     * （{@code MolangQueries.java:148-150}），所以此前那一整条 {@code -Molang} 骨骼链是死的。
     * <p>
     * 两个值都必须是**度**，且 {@code head_yaw} 必须是**相对角**（头相对身体）——
     * GeckoLib 内置的 {@code query.head_y_rotation} 是**绝对** yaw，会把整体朝向叠进去，不能用。
     * <p>
     * <b>两点必须注意</b>：①Molang 变量是全局静态的，每个实体每帧都要重设（本方法正是为此被调用）；
     * ②此刻 {@code state.getController()} 是 {@code null}，只能用**惰性 supplier**。
     */
    @Override
    public void applyMolangQueries(AnimationState<DihuangLoongEntity> animationState, double animTime) {
        super.applyMolangQueries(animationState, animTime);

        DihuangLoongEntity entity = animationState.getAnimatable();
        float partialTick = animationState.getPartialTick();

        // 与 GeoEntityRenderer 相同的插值口径（它用 Mth.rotLerp(partialTick, yBodyRotO, yBodyRot)）
        float bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        float headYaw = Mth.rotLerp(partialTick, entity.yHeadRotO, entity.yHeadRot);
        float headPitch = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());

        MathParser.setVariable("query.head_yaw",
                () -> (double) (Mth.wrapDegrees(headYaw - bodyYaw) * HEAD_YAW_SIGN));
        MathParser.setVariable("query.head_pitch", () -> (double) headPitch);
    }
```

**新增 import**：`net.minecraft.util.Mth`、`software.bernie.geckolib.animation.AnimationState`、
`software.bernie.geckolib.loading.math.MathParser`。同时更新类注释里"v1 只播 idle"那一段。

**验证：** 随 T9 一起编译。

---

### T7 新增 `command/NpcCommand.java`

**文件：** 新增 `src/main/java/com/zonlong/beloong/command/NpcCommand.java`

```java
package com.zonlong.beloong.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.zonlong.beloong.entity.NpcEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.List;

/**
 * 通用 NPC 的调试 / 摆位命令。
 * <p>
 * <b>定位：验收与手动摆位工具，不是玩法内容。</b>直接原因是 NPC 的能力
 * （{@code walkTo}/{@code runTo}/{@code turnTo}/{@code attack}）是纯 API、默认没有调用方 ——
 * 没有它，这些能力在本轮交付里**无法被证明**。将来不要了，删本类 + {@code BeLoongCore} 里一行注册。
 * <p>
 * 只调实体 API，**不碰实体字段**；目标过滤 {@link NpcEntity}（对所有 NPC 生效，不只地黄龙）。
 * op 级：它改的是世界里的实体。
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
                                                .executes(ctx -> move(
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        Vec3Argument.getVec3(ctx, "pos"),
                                                        false, ctx.getSource())))))
                        .then(Commands.literal("run")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .executes(ctx -> move(
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        Vec3Argument.getVec3(ctx, "pos"),
                                                        true, ctx.getSource())))))
                        .then(Commands.literal("attack")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .then(Commands.argument("victim", EntityArgument.entity())
                                                .executes(ctx -> attack(
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        EntityArgument.getEntity(ctx, "victim"),
                                                        ctx.getSource())))))
                        .then(Commands.literal("stop")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(ctx -> stop(
                                                EntityArgument.getEntities(ctx, "targets"),
                                                ctx.getSource()))))));
    }

    private static int turn(Collection<? extends Entity> targets, float yaw, CommandSourceStack source) {
        List<NpcEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        for (NpcEntity npc : npcs) {
            npc.turnTo(yaw);
        }
        source.sendSuccess(() -> Component.translatable(
                "beloong.command.npc.turn", npcs.size(), String.format("%.1f", yaw)), true);
        return npcs.size();
    }

    private static int move(Collection<? extends Entity> targets, Vec3 pos, boolean sprint, CommandSourceStack source) {
        List<NpcEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        for (NpcEntity npc : npcs) {
            if (sprint) {
                npc.runTo(pos);
            } else {
                npc.walkTo(pos);
            }
        }
        String key = sprint ? "beloong.command.npc.run" : "beloong.command.npc.walk";
        source.sendSuccess(() -> Component.translatable(key, npcs.size(), pos.toString()), true);
        return npcs.size();
    }

    private static int attack(Collection<? extends Entity> targets, Entity victim, CommandSourceStack source) {
        List<NpcEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        if (!(victim instanceof LivingEntity living)) {
            source.sendFailure(Component.translatable("beloong.command.npc.not_living"));
            return 0;
        }
        for (NpcEntity npc : npcs) {
            npc.attack(living);
        }
        source.sendSuccess(() -> Component.translatable(
                "beloong.command.npc.attack", npcs.size(), living.getDisplayName()), true);
        return npcs.size();
    }

    private static int stop(Collection<? extends Entity> targets, CommandSourceStack source) {
        List<NpcEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        for (NpcEntity npc : npcs) {
            npc.stopAction();
        }
        source.sendSuccess(() -> Component.translatable("beloong.command.npc.stop", npcs.size()), true);
        return npcs.size();
    }

    /** 选到的不是 NPC 时明确报错，不静默。 */
    private static int fail(CommandSourceStack source) {
        source.sendFailure(Component.translatable("beloong.command.npc.no_targets"));
        return 0;
    }

    private static List<NpcEntity> npcsIn(Collection<? extends Entity> targets) {
        return targets.stream()
                .filter(NpcEntity.class::isInstance)
                .map(NpcEntity.class::cast)
                .toList();
    }
}
```

**验证：** 随 T9 一起编译。

---

### T8 `BeLoongCore.java` + 两个语言文件

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
该类已经是 `NeoForge.EVENT_BUS.register(this)` 的监听者。）

**文件 2：** 修改 `zh_cn.json`（**插到第一个 `beloong.configuration.*` 之前**，字母序：`command` < `configuration`）

```json
  "beloong.command.npc.attack": "已让 %s 个 NPC 攻击 %s",
  "beloong.command.npc.no_targets": "没有找到 NPC（本命令只作用于本模组注册的 NPC）",
  "beloong.command.npc.not_living": "攻击目标必须是生物",
  "beloong.command.npc.run": "已让 %s 个 NPC 跑向 %s",
  "beloong.command.npc.stop": "已让 %s 个 NPC 停止动作",
  "beloong.command.npc.turn": "已让 %s 个 NPC 转向 %s°",
  "beloong.command.npc.walk": "已让 %s 个 NPC 走向 %s",
```

**文件 3：** 修改 `en_us.json`（同 7 条，同位置）

```json
  "beloong.command.npc.attack": "%s NPC(s) ordered to attack %s",
  "beloong.command.npc.no_targets": "No NPC found (this command only affects NPCs registered by this mod)",
  "beloong.command.npc.not_living": "The attack victim must be a living entity",
  "beloong.command.npc.run": "%s NPC(s) running to %s",
  "beloong.command.npc.stop": "Stopped %s NPC(s)",
  "beloong.command.npc.turn": "Turning %s NPC(s) to %s degrees",
  "beloong.command.npc.walk": "%s NPC(s) walking to %s",
```

**验证：** 随 T9；另静态确认两文件键集合一致。

---

### T9 构建 + 静态探针 + 原子提交

见 §三、§五。

---

## 三、静态探针清单（提交前一次跑完）

| # | 检查 | 期望 |
|---|---|---|
| 1 | `.\gradlew.bat build --console=plain` | `BUILD SUCCESSFUL` |
| 2 | `DihuangLoongEntity.java` 里搜 `isNoAi` / `setNoAi` / `registerGoals` / `isInvulnerableTo` / `isPushable` / `isPersistenceRequired` / `RawAnimation` | **全部无输出**（都已上移基类） |
| 3 | `NpcEntity.java` 里搜 `isInvulnerableTo` / `isPushable` / `isPersistenceRequired` / `registerGoals` / `customServerAiStep` / `createNpcAttributes` | 各命中 |
| 4 | 三个 goal 的构造参数类型 | 是 `NpcEntity` |
| 5 | `NpcEntity.registerGoals()` 里攻击 goal 的优先级 | `3`（且小于 look goal 的 5） |
| 6 | `DihuangLoongModel.java` 搜 `applyMolangQueries`、`query.head_yaw`、`query.head_pitch` | 各命中 |
| 7 | `git diff --stat HEAD -- src/main/resources/beloong.mixins.json src/main/java/com/zonlong/beloong/registry/ModEntities.java build.gradle` | **无输出** |
| 8 | `NpcCommand.java` 搜 `hasPermission(2)`；两个 lang 文件键集合一致 | 各 1 处 / 一致 |

---

## 四、实机验收清单（需用户执行）

| # | 步骤 | 预期 | 验证 |
|---|---|---|---|
| 1 | 地面上方几格 `/summon beloong:dihuang_loong` | **下落并落地** | 基类不碰 noAi ⇒ 重力回来 |
| 2 | 观察落地后模型 | 脚是否陷地 —— **记下偏移量** | R9（首版遗留，现在才暴露） |
| 3 | 静置数分钟 | 不动、**不自主转圈**、朝向不变、不消失 | 默认零自主行动 |
| 4 | 走进 8 格 | **头/颈先转过来**，身体随后转正 | Molang + 面朝 goal |
| 5 | 绕它走一圈 | 身体持续面朝你 | 顺便定 `HEAD_YAW_SIGN` 正负 |
| 6 | 走出 8 格 | 停止跟随，停在最后朝向 | 不做回正 |
| 7 | 站高处/低处看它 | 头随俯仰动、方向正确 | `query.head_pitch` |
| 8 | 攻击它 / 挤压它 | 无伤害反馈、不位移 | 定义性语义 |
| 9 | `/kill @e[type=beloong:dihuang_loong]` | 可移除 | 管理后路 |
| 10 | 退档重进 | 仍在、仍无敌、不消失 | 无存档数据 |
| 11 | 推到水里 | `FloatGoal` 上浮 | — |
| 12 | `/beloong npc walk @e[…] ~ ~ ~5` | 以**玩家走路速度**过去；播 `walk` | D47 |
| 13 | `/beloong npc run @e[…] ~ ~ ~10` | **明显更快**；到达后**恢复常速、冲刺粒子消失** | 冲刺机制 + D46 复位 |
| 14 | `/beloong npc turn @e[…] 90` | 平滑转到 90° | 转圈能力 |
| 15 | `/beloong npc attack @e[…] <一头牛>` | 走过去、**一下 100 伤害** | D42/D43/D44 |
| 16 | `attack` 后 `stop` | 立刻放弃追击、回到站桩 | API |
| 17 | **攻击时玩家站在旁边** | **仍然打得到**（不是被 look goal 钉在原地） | **D43 的核心验证点** |
| 18 | `/beloong npc attack @e[…] <创造模式玩家>` | 不追（原版排除），且**之后身体仍会面朝玩家**（标志位被兜底清掉） | `customServerAiStep` 的失效兜底 |
| 19 | 对牛执行 `/beloong npc walk` | 报"没有找到 NPC"，不崩 | 命令过滤 |
| 20 | 空手右键它 | 对话无回归 | — |
| 21 | 专服启动 + 执行一次命令 | 无 `NoClassDefFoundError` | Molang 在客户端类里 |
| 22 | 攻击全程 | 无挥击动画（已知缺口 D45） | 记录观感，不阻塞 |

---

## 五、收尾

1. **提交**：`git add src/` → 一个 `feat(entity): …` 原子提交（T1–T9）。
2. 实机验收后回填：
   - 设计文档补「实现期修订」：`HEAD_YAW_SIGN` / 转速 / 移速 / 攻击 goal 优先级的**最终实测取值**；
   - `memory/project-context.md` 子系统 11：由"实现未开始"改为"已实现"；
   - `memory/decisions-log.md`：若实机又发现新的「标志位/Flag 生命周期」类坑，补进去。
3. 若实机确认 R9 偏移量：在 `DihuangLoongRenderer#preRender` 加 y 平移，并回填设计文档。
