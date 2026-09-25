package com.zonlong.beloong.entity;

import com.zonlong.beloong.entity.ai.NpcAttackGoal;
import com.zonlong.beloong.entity.ai.NpcFacePlayerGoal;
import com.zonlong.beloong.entity.ai.NpcTurnGoal;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
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
 * 转身 / 走路 / 奔跑 / 攻击都只作为**能力**，由外部（命令、对话、将来的脚本）经
 * {@link #turnTo} / {@link #walkTo} / {@link #runTo} / {@link #attack} 驱动。
 *
 * <h2>定义性语义（子类要例外就自己覆写方法，这里不提供开关）</h2>
 * <ul>
 *   <li><b>无敌</b>：{@link #isInvulnerableTo} 只放行 {@code BYPASSES_INVULNERABILITY}
 *       （{@code /kill} 与虚空）—— 刻意留的管理后路；</li>
 *   <li><b>不可推动</b>：{@link #isPushable()} 恒 false；</li>
 *   <li><b>永不消失</b>：{@link #isPersistenceRequired()} 恒 true。</li>
 * </ul>
 * 这三条写成**覆写 getter** 而不是构造函数里 set：{@code /summon} 与刷怪蛋的流程是
 * "先 {@code create()}（构造函数在此运行）再 {@code load(标签)}"，而 {@code load()} 会把
 * {@code Invulnerable}（{@code Entity.java:1759}）、{@code PersistenceRequired}
 * （{@code Mob.java:437}）从标签读回，**标签里没有对应键时就覆盖成 false**。
 * 构造函数里的 {@code setXxx} 仍然保留，只为让字段本身与保存出的 NBT 一致。
 *
 * <h2>为什么基类不覆写 {@code isNoAi()}</h2>
 * 首版地黄龙是"无 AI 雕像"，靠覆写 {@code isNoAi()} 恒 true 实现。那个覆写不只关掉了 goal：
 * {@code LivingEntity#travel()} 的**整个方法体**被 {@code isControlledByLocalInstance()} 包住
 * （{@code LivingEntity.java:2219-2220}），而它 = {@code isEffectiveAi()} = {@code !isNoAi()}
 * （{@code Entity.java:3215-3217}、{@code Mob.java:1420}）⇒ **连重力与位移积分一起没了**，
 * 实体其实是被"钉"在召唤点的。
 * 本基类因此**不碰** {@code isNoAi()}，重力自然生效。
 *
 * <h2>身朝为什么要覆写 {@code tickHeadTurn}（"turn 指令无效"的根因）</h2>
 * 直觉上"设了 {@code yRot}，身体自然会转"—— 对 {@code Mob} **并不成立**：
 * <ul>
 *   <li>{@code Mob#tickHeadTurn}（{@code Mob.java:377-381}）覆写后**不调用 super**，
 *       只调 {@code bodyRotationControl.clientTick()}。也就是说 {@code LivingEntity} 里那套
 *       "身体以 0.3 插值追目标"的逻辑，对 {@code Mob} 是**死代码**。</li>
 *   <li>{@code BodyRotationControl} 的真实行为：**移动时** {@code yBodyRot = yRot}（硬贴、无插值）；
 *       <b>没移动时</b>仅在"头相对上次稳定位置转过 15°"时把身体拖到与头相差
 *       {@code getMaxHeadYRot()}（{@code Mob} 默认 <b>75°</b>）以内
 *       ⇒ **站桩的身体永远转不到指定朝向**。</li>
 *   <li>而且 {@code yBodyRot} <b>不参与网络同步</b>（只有 {@code yRot} 与 {@code yHeadRot} 会同步），
 *       客户端的身朝是它自己算的 ⇒ 服务端写 {@code yBodyRot} 对画面**没有用**。</li>
 * </ul>
 * 本类因此覆写 {@link #tickHeadTurn} 恢复 {@code LivingEntity} 语义（身体 0.3 插值追 {@code yRot}），
 * 让 {@code yRot} 成为身朝的唯一真源、且客户端与服务端同构；{@link #setFacing} 是唯一的转向入口。
 *
 * <h2>子类必须提供</h2>
 * 实体类型绑定（见 {@code registry/ModEntities}）、碰撞箱（那里）、渲染器与模型
 * （{@code client/} 侧），以及属性表 —— 用 {@link #createNpcAttributes()} 作起点。
 *
 * <p>设计文档：{@code docs/plans/2026-09-25-npc-base-class-design.md}。
 */
public abstract class NpcEntity extends PathfinderMob implements GeoEntity {

    // ===================== 可覆写的默认值（约定 + 可覆写）=====================

    /** 待机动画名。子类资产里若叫别的名字就覆写本方法。 */
    protected String idleAnimationName() {
        return "idle";
    }

    /** 移动动画名。 */
    protected String walkAnimationName() {
        return "walk";
    }

    /**
     * 奔跑动画名。
     * <p>
     * 只在**真的在移动且处于冲刺状态**时播。资产里的 {@code run} 引用了 3 根本模型没有的骨骼
     * （{@code Drip1-3}），GeckoLib 对缺失骨骼是优雅忽略，只是观感略有缺失、不会崩。
     */
    protected String runAnimationName() {
        return "run";
    }

    /** {@code idle} ↔ {@code walk} 的过渡时长（tick）。0 = 硬切。 */
    protected int animationTransitionTicks() {
        return 5;
    }

    /**
     * 看向 / 面朝玩家的距离（格）。
     * <p>
     * <b>public 而非 protected</b>：实体侧的 goal 在 {@code entity.ai} 包，
     * 不是本类的子类，读不到 {@code protected} 成员 —— 这类"要被 goal 读取"的参数必须 public。
     */
    public float facePlayerDistance() {
        return 8.0F;
    }

    /** 转身角速度上限（度/tick）。public 的理由同 {@link #facePlayerDistance()}。 */
    public float maxTurnPerTick() {
        return 10.0F;
    }

    /**
     * 寻路速度修正系数 —— **必须取 {@code 1 / sqrt(MOVEMENT_SPEED)}，不是 1.0**。
     * <p>
     * <b>为什么</b>：生物的实际位移正比于 {@code speed²}，而玩家正比于 {@code speed¹}：
     * <ul>
     *   <li>{@code Mob#setSpeed(s)} 除了记录 speed，还把它写进 <b>zza（前进输入）</b>：
     *       {@code super.setSpeed(speed); this.setZza(speed);}（{@code Mob.java:557-560}）；</li>
     *   <li>{@code LivingEntity#travel} 用 {@code moveRelative(速度, (xxa, yya, zza))} 算位移，
     *       而 {@code zza = s} ⇒ 生物位移 ∝ {@code s²}；</li>
     *   <li>玩家**不是** {@code Mob} 的子类（{@code Player extends LivingEntity}），
     *       它的 {@code zza} 来自输入、幅度为 1.0 ⇒ 位移 ∝ {@code s¹}。</li>
     * </ul>
     * 于是"把属性值 0.1 直接当 speed 用、系数给 1.0"时，生物只有玩家的
     * {@code 0.1² / 0.1 = 1/10} 速度 —— 这正是首版"走路特别慢"的根因。
     * <p>
     * 取 {@code 1/sqrt(属性)} 后生物位移 ∝ {@code (系数 × 属性)² = 属性}，与玩家的 {@code 属性}
     * 相等 ⇒ **走路对上走路、冲刺对上冲刺**：冲刺时属性被 {@code SPEED_MODIFIER_SPRINTING}
     * 抬高，本方法随属性自动跟随，不需要硬编码倍数。
     * <p>
     * （另注：{@code MoveControl} 的 {@code speedModifier} 初值是 0.25，所以无论如何都得显式给值。）
     */
    protected double navigationSpeedModifier() {
        double speedAttribute = this.getAttributeValue(Attributes.MOVEMENT_SPEED);
        // 防 0 除（属性被改成 0 时不能给 Infinity 喂进 setSpeed）
        return speedAttribute <= 0.0D ? 1.0D : 1.0D / Math.sqrt(speedAttribute);
    }

    // ===================== 属性默认值 =====================

    /**
     * 通用 NPC 的默认属性表。子类用它作起点追加或覆盖。
     * <p>
     * <b>{@code ATTACK_DAMAGE} 必须显式 add</b>：{@code createMobAttributes()} 只含
     * MAX_HEALTH / KNOCKBACK_RESISTANCE / MOVEMENT_SPEED / ARMOR / ARMOR_TOUGHNESS，
     * 没有攻击力（原版要 {@code Monster.createMonsterAttributes()} 才加）。
     * <p>
     * 移速 0.1 = 玩家默认走路速度（{@code Player.java:231}）。奔跑**不**靠更高的属性值，
     * 而是走原版冲刺机制 —— 见 {@link #runTo}。
     */
    public static AttributeSupplier.Builder createNpcAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1000.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)
                .add(Attributes.ATTACK_DAMAGE, 100.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.1D);
    }

    // ===================== 状态 =====================

    /**
     * 外部指定的目标朝向（度）；{@code null} = 无转身指令。
     * <p>
     * <b>刻意不写 NBT</b>：外部指令是运行期意图，不该跨存档 / 跨重载残留 ——
     * 重载后回到"站桩"是期望行为。
     */
    @Nullable
    private Float turnTargetYaw;

    /** 是否处于"被下令攻击"状态。只有它为 true 时 {@link NpcAttackGoal} 才会运行。 */
    private boolean attackCommandActive;

    protected NpcEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        // 与下面两个覆写并存：让字段本身与保存出的 NBT 一致；语义由覆写保证。
        setInvulnerable(true);
        setPersistenceRequired();
    }

    // ===================== 定义性语义 =====================

    /**
     * 无敌 —— 这是 {@code LivingEntity#hurt} 的**第一道闸门**
     * （{@code LivingEntity.java:1084}），覆写它即可挡住一切普通伤害，
     * 且不依赖会被 NBT 覆盖的 {@code invulnerable} 字段。
     * <p>
     * 放行的只有 {@code bypasses_invulnerability} 标签里的两项
     * （{@code minecraft:out_of_world} 与 {@code minecraft:generic_kill}）
     * ⇒ {@code /kill} 与虚空仍能移除它。
     */
    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || super.isInvulnerableTo(source);
    }

    /** 站桩 NPC 不该被玩家推着走。 */
    @Override
    public boolean isPushable() {
        return false;
    }

    /**
     * 永不消失。
     * <p>
     * {@code Mob#checkDespawn}（{@code Mob.java:703-716}）的闸门就是本方法
     * （只看 {@code persistenceRequired} / {@code requiresCustomPersistence()}，
     * **不看** {@code MobCategory} 的 {@code isPersistent}），所以必须在这里保证。
     */
    @Override
    public boolean isPersistenceRequired() {
        return true;
    }

    // ===================== 基础 AI =====================

    /**
     * 基础 AI。**刻意不含自主游走 / 自主转圈 / 自主索敌**。
     * <p>
     * 优先级与 {@code Goal.Flag} 的关系是本设计最容易踩的地方
     * （{@code GoalSelector} 用 {@code lockedFlags} 做仲裁：低优先级 goal 只要与正在运行的
     * 高优先级 goal 有 Flag 交集，就**无法启动**）：
     * <ul>
     *   <li>{@link NpcAttackGoal}（{@code MeleeAttackGoal} 子类）占用 <b>MOVE + LOOK</b>，
     *       而 {@code LookAtPlayerGoal} 占用 <b>LOOK</b>。若攻击 goal 优先级更低，
     *       "玩家在跟随距离内"时它会被 look goal **永久挡死** ⇒ 永远打不到人（且不报错）。
     *       所以攻击 goal 排在 <b>3</b>，比 look goal 的 5 更靠前。</li>
     *   <li>{@link NpcFacePlayerGoal} 与 {@link NpcTurnGoal} **不占用任何 Flag**（刻意，不是遗漏），
     *       因此不与原版 look goal 互斥；它们之间的互斥由 {@code canUse()} 的前置条件表达。</li>
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
     * 服务端 AI 的每 tick 收尾（{@code Mob#serverAiStep} 内部调用，需要 AI 生效）。
     * <p>
     * <b>冲刺复位</b>：{@code LivingEntity#setSprinting(true)} 会往 {@code MOVEMENT_SPEED}
     * 挂一个瞬态修饰符，而**原版没有任何代码会在寻路结束时摘掉它**
     * ⇒ 走完一段奔跑后会永久快一档、还带着冲刺粒子。这里在寻路结束时显式复位。
     * <p>
     * <b>攻击指令的失效兜底</b>：目标死亡、或目标变成创造 / 旁观玩家时，
     * {@code MeleeAttackGoal.canUse()} 会返回 false；但若该 goal **从未启动过**，
     * 它的 {@code stop()} 就不会被调用 ⇒ {@code attackCommandActive} 会一直挂着，
     * 把 {@link NpcFacePlayerGoal} 永久挡住。所以在这里兜底清理。
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
     * <b>转到位即释放，之后照旧响应"玩家靠近时面朝玩家"</b>（用户裁定）。
     * 也就是说：若玩家就在 {@link #facePlayerDistance()} 之内，身体转到位后会被转回去 ——
     * 这是**有意**的，显式指令只负责"把它转过去"，之后仍由 NPC 自己的反应规则接管。
     * 想让它停在某个朝向，请在玩家离开后再执行。
     * <p>
     * 实际的角度推进由 {@link NpcTurnGoal} 每 tick 完成，且走 {@link #setFacing}
     * —— 站桩实体的身体**不会**自动跟随 {@code yRot}（见类注释那一节）。
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
     * {@code LivingEntity} 自带的瞬态修饰符（**不是玩家专属**），因此与玩家奔跑**同源**：
     * 将来原版改倍率，我们自动跟上。
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
        this.getNavigation().moveTo(pos.x, pos.y, pos.z, this.navigationSpeedModifier());
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
     * {@link NpcFacePlayerGoal} 用它整条让位 —— 显式指令优先于自主反应，
     * 同时避免"边走边转身体"与寻路抢 {@code yRot}。
     */
    public boolean isExternallyCommanded() {
        return this.turnTargetYaw != null
                || this.attackCommandActive
                || !this.getNavigation().isDone();
    }

    /**
     * 身体朝向的驱动 —— **刻意绕开 {@code Mob} 默认的 {@code BodyRotationControl}**。
     * <p>
     * {@code Mob#tickHeadTurn}（{@code Mob.java:377-381}）覆写后只调
     * {@code bodyRotationControl.clientTick()} 并直接返回，**不调用** {@code LivingEntity} 里那套
     * "身体以 0.3 插值追目标"的逻辑。而 {@code BodyRotationControl} 的实际行为是：
     * <ul>
     *   <li><b>移动时</b>：{@code yBodyRot = yRot}（硬贴，无插值）；</li>
     *   <li><b>没移动时</b>：仅当"头相对上次稳定位置转过 15°"时，把身体拖到与头相差
     *       {@code getMaxHeadYRot()}（{@code Mob} 默认 <b>75°</b>）以内 ——
     *       **它永远不会让站桩的身体真正转到 {@code yRot}**。</li>
     * </ul>
     * ⇒ 站桩 NPC 想把身体转到指定朝向（{@link #turnTo} / {@link NpcFacePlayerGoal}）时，
     * 默认机制给不了："turn 指令看起来无效"就是这么来的。
     * <p>
     * 这里改成与 {@code LivingEntity} 原生一致的语义：**身体以 0.3 插值追 {@code yRot}**，
     * 于是 {@code yRot} 成为身朝的唯一真源。
     * <p>
     * 传入的 {@code targetYRot} 刻意忽略：它的取值来自 {@code LivingEntity#tick()} 里
     * "本 tick 的位移方向"，而站住不动时它恰好等于当前 {@code yBodyRot}（一个原地不动的空目标）。
     * <p>
     * 本方法在**客户端与服务端都会跑**，两端都从已同步的 {@code yRot} 推导身朝 ⇒ 表现一致
     * （{@code yBodyRot} 本身**不参与同步**，客户端的身朝是它自己算的）。
     */
    @Override
    protected float tickHeadTurn(float targetYRot, float animStep) {
        this.yBodyRot += Mth.wrapDegrees(this.getYRot() - this.yBodyRot) * 0.3F;
        return animStep;
    }

    /**
     * 设置朝向：同时写 {@code yRot} 与 {@code yBodyRot} —— **转向走这里，不要只 {@code setYRot}**。
     * <p>
     * 只写 {@code yRot} 不够用，原因有两层：
     * <ol>
     *   <li>{@code Mob} 默认把身朝交给 {@code BodyRotationControl}，而它在"没移动"时最多把身体拖到
     *       与头相差 75°（见 {@link #tickHeadTurn} 的说明）；</li>
     *   <li>{@code yBodyRot} <b>不参与网络同步</b>（只有 {@code yRot} 与 {@code yHeadRot} 会同步），
     *       客户端的身朝由它自己算 —— 服务端写 {@code yBodyRot} 只影响服务端状态，
     *       真正让客户端转过去的是 {@code yRot}。</li>
     * </ol>
     * 本类覆写了 {@link #tickHeadTurn} 让身体以 0.3 插值追 {@code yRot}（双端同构），
     * 因此写 {@code yRot} 就会转、且两端一致。
     * <p>
     * <b>刻意不写 {@code yHeadRot}</b>：头交给原版 {@code LookControl} / {@code LookAtPlayerGoal}，
     * 这样"头先转过去、身体随后跟上"的观感才是原生的 —— 一写头，头部 Molang 的相对角就恒为 0，
     * 那条 {@code -Molang} 骨骼链又白写了。
     */
    public void setFacing(float yaw) {
        this.setYRot(yaw);
        this.yBodyRot = yaw;   // 服务端侧保持一致；客户端由覆写的 tickHeadTurn 自行插值
    }

    /** 供 {@link NpcTurnGoal} 读取当前目标朝向。 */
    @Nullable
    public Float getTurnTargetYaw() {
        return this.turnTargetYaw;
    }

    /** 供 {@link NpcTurnGoal} 在到位后清空目标。 */
    public void clearTurnTarget() {
        this.turnTargetYaw = null;
    }

    /** 供 {@link NpcAttackGoal} 查询是否被下令攻击。 */
    public boolean isAttackCommandActive() {
        return this.attackCommandActive;
    }

    /** 供 {@link NpcAttackGoal} 在停止时清理。 */
    public void clearAttackCommand() {
        this.attackCommandActive = false;
        this.setTarget(null);
        this.getNavigation().stop();
    }

    // ===================== GeckoLib =====================

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    /**
     * 主控制器：待机 / 移动（走或跑）三选一。
     * <p>
     * <b>{@code RawAnimation} 刻意在这里构建而不在构造函数里</b>：动画名来自**可覆写**方法
     * （{@link #idleAnimationName()} 等），构造函数里调虚方法会踩"子类字段尚未初始化"的经典坑。
     * 而 {@code registerControllers} 由 {@code AnimatableManager} **惰性**调用，那时子类早已构造完。
     * <p>
     * 判据用 GeckoLib 的 {@code isMoving()}（横向速度 ≥ 0.015/tick 且 {@code walkAnimation} 在动）。
     * <b>注意这个阈值</b>：移动太慢（例如属性没配上正确的速度系数）时它会一直是 false ⇒
     * 走了却只播 idle。**原地转身不算移动**，所以转圈时仍播 idle。
     * <p>
     * 走 / 跑的分野用 {@code isSprinting()}：那是**共享标志位**、会同步给客户端，
     * 而冲刺修饰符只存在于服务端属性实例上，客户端读不到。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        final RawAnimation idle = RawAnimation.begin().thenLoop(this.idleAnimationName());
        final RawAnimation walk = RawAnimation.begin().thenLoop(this.walkAnimationName());
        final RawAnimation run = RawAnimation.begin().thenLoop(this.runAnimationName());
        controllers.add(new AnimationController<>(this, "main", this.animationTransitionTicks(), state -> {
            if (!state.isMoving()) {
                return state.setAndContinue(idle);
            }
            return state.setAndContinue(state.getAnimatable().isSprinting() ? run : walk);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
