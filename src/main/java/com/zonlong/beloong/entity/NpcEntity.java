package com.zonlong.beloong.entity;

import com.zonlong.beloong.entity.ai.NpcAttackGoal;
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
 * 定位：整合包里的"站桩 NPC"。默认**不自主行动** —— 不游走、不索敌；走路与攻击只作为**能力**，
 * 由外部（命令、对话、将来的脚本）经 {@link #walkTo} / {@link #attack} 驱动。
 * 「玩家靠近时面朝玩家」**不是**我们写的，而是原版 goal 链的默认结果（见下面"身朝"一节）。
 *
 * <h2>尽量用原版机制（2026-09-25 用户裁定）</h2>
 * 移动刻度、动画档位、持久性全部交回原版；只有三项原版确实没有的才自研
 * （绝对无敌 / 命令式 API / GeckoLib 离散动画）。设计文档：
 * {@code docs/plans/2026-09-25-npc-vanilla-ai-design.md}。
 *
 * <h2>定义性语义（子类要例外就自己覆写方法，这里不提供开关）</h2>
 * <ul>
 *   <li><b>无敌</b>：{@link #isInvulnerableTo} 只放行 {@code BYPASSES_INVULNERABILITY}
 *       （{@code /kill} 与虚空）—— 刻意留的管理后路；</li>
 *   <li><b>不可推动</b>：{@link #isPushable()} 恒 false；</li>
 *   <li><b>永不消失</b>：{@link #requiresCustomPersistence()} 恒 true。</li>
 * </ul>
 * 这三条写成**覆写方法**而不是构造函数里 set：{@code /summon} 与刷怪蛋的流程是
 * "先 {@code create()}（构造函数在此运行）再 {@code load(标签)}"，而 {@code load()} 会把
 * {@code Invulnerable}（{@code Entity.java:1759}）、{@code PersistenceRequired}
 * （{@code Mob.java:437}）从标签读回，**标签里没有对应键时就覆盖成 false**。
 * 构造函数里的 {@code setXxx} 仍然保留，只为让字段本身与保存出的 NBT 一致。
 * <p>
 * 「永不消失」用的是**原版官方钩子** {@code Mob#requiresCustomPersistence()}（{@code Mob.java:736}）
 * —— {@code Mob#checkDespawn} 的闸门是 `!isPersistenceRequired() && !requiresCustomPersistence()`
 * （{@code Mob.java:749}），而前者正是为子类"我该不该按刷怪规则消失"设计的，
 * 原版先例：{@code AbstractFish:45}、{@code Axolotl:423}、{@code Raider:248}、{@code EnderMan:434}。
 *
 * <h2>为什么基类不覆写 {@code isNoAi()}</h2>
 * 首版地黄龙是"无 AI 雕像"，靠覆写 {@code isNoAi()} 恒 true 实现。那个覆写不只关掉了 goal：
 * {@code LivingEntity#travel()} 的**整个方法体**被 {@code isControlledByLocalInstance()} 包住
 * （{@code LivingEntity.java:2219-2220}），而它 = {@code isEffectiveAi()} = {@code !isNoAi()}
 * （{@code Entity.java:3215-3217}、{@code Mob.java:1420}）⇒ **连重力与位移积分一起没了**，
 * 实体其实是被"钉"在召唤点的。
 * 本基类因此**不碰** {@code isNoAi()}，重力自然生效。
 *
 * <h2>身朝：完全交给原版，**不要**覆写 {@code tickHeadTurn}</h2>
 * 「玩家靠近时面朝玩家」+「先扭头、后转身」都是原版机制**自带**的，前提是**不插手**：
 * <ul>
 *   <li><b>头</b>：{@code LookAtPlayerGoal} → {@code LookControl}，以 {@code getHeadRotSpeed()}
 *       （{@code Mob} 默认 10°/tick）把头转向玩家。站桩时头**不受**"不得偏离身体"的夹取
 *       （{@code LookControl#clampHeadRotationToBody} 只在有寻路时生效），所以头能先转过去。</li>
 *   <li><b>身体</b>：{@code Mob#tickHeadTurn} → {@code BodyRotationControl#clientTick()}。
 *       站桩时：头相对"上次稳定位置"转过 &gt;15° 就把身体夹到**离头不超过
 *       {@code getMaxHeadYRot()}（默认 75°）** ⇒ 身体稳定地滞后头最多 75°，这就是"头先转"；
 *       头停下约 11 tick 后 {@code rotateHeadTowardsFront} 的允许量**递减到 0**，
 *       身体被**逐步收到与头完全对齐** ⇒ "身体随后跟上"、最终整体面向玩家。
 *       （移动时则是 {@code yBodyRot = yRot}，贴行进方向。）</li>
 *   <li><b>同步</b>：{@code yBodyRot} <b>不参与网络同步</b>（只同步 {@code yRot}/{@code yHeadRot}），
 *       客户端的身朝是它自己用同一套 {@code BodyRotationControl} 算的 ⇒ 服务端写 {@code yBodyRot}
 *       对画面没有用，而"覆写 {@code tickHeadTurn}"在双端都会生效。</li>
 * </ul>
 * ⚠️ <b>不要为了"让站桩身体也能转到指定朝向"去覆写 {@code tickHeadTurn}</b>（本类干过，代价很大）：
 * 无论改成"身体以 0.3 插值追 {@code yRot}"还是"追 {@code yHeadRot}"，都会把上面那套
 * 75° 滞后关系一起废掉 —— 头与身体的相对角变得很小或恒为 0，而**头部 Molang
 * （{@code query.head_yaw}）吃的正是这个相对角**，于是症状是"不扭脖子、头身一体转"。
 * <p>
 * 将来若确实需要定制身朝，正统扩展点是覆写 {@code Mob#createBodyControl()} 返回
 * {@code BodyRotationControl} 的子类（原版自己在用：{@code Phantom:63}、{@code Armadillo:392}、
 * {@code Camel:635}、{@code Shulker:146}），而不是 {@code tickHeadTurn}。
 *
 * <h2>子类必须提供</h2>
 * 实体类型绑定（见 {@code registry/ModEntities}）、碰撞箱（那里）、渲染器与模型
 * （{@code client/} 侧），以及属性表 —— 用 {@link #createNpcAttributes()} 作起点。
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
     * 奔跑动画名 —— 只在"**有额外移速加成**时移动"才播，见 {@link #registerControllers}。
     * <p>
     * 资产里的 {@code run} 引用了 3 根本模型没有的骨骼（{@code Drip1-3}），
     * GeckoLib 对缺失骨骼是优雅忽略，只是观感略有缺失、不会崩。
     */
    protected String runAnimationName() {
        return "run";
    }

    /** 动画之间的过渡时长（tick）。0 = 硬切。 */
    protected int animationTransitionTicks() {
        return 5;
    }

    /**
     * 看向 / 面朝玩家的距离（格）。
     * <p>
     * 由 {@link #registerGoals()} 里的 {@code LookAtPlayerGoal} 读取。
     * 曾经是 {@code public}（因为当时有一个在 {@code entity.ai} 包的自研 goal 要读它）；
     * 那个 goal 已删除，现在只有本类自己用，因此收回 {@code protected} —— 对外接口越小越好。
     */
    protected float facePlayerDistance() {
        return 8.0F;
    }

    // ===================== 属性默认值 =====================

    /**
     * 通用 NPC 的默认属性表。子类用它作起点追加或覆盖。
     * <p>
     * <b>{@code ATTACK_DAMAGE} 必须显式 add</b>：{@code createMobAttributes()} 只含
     * MAX_HEALTH / KNOCKBACK_RESISTANCE / MOVEMENT_SPEED / ARMOR / ARMOR_TOUGHNESS，
     * 没有攻击力（原版要 {@code Monster.createMonsterAttributes()} 才加）。
     * <p>
     * <b>移速 0.3 是"原版刻度"的值，含义与原版生物一致</b>（僵尸 0.23、铁傀儡 0.25、村民 0.5）。
     * 它与玩家的关系不是线性的：因为 {@code Mob#setSpeed} 会把速度值**同时**写进
     * {@code zza}（前进输入），生物的实际位移正比于 `(导航档位 × 属性)²`，而玩家正比于
     * `属性 × 输入幅度(1.0)`（{@code Player} 不是 {@code Mob} 的子类，{@code zza} 来自键盘）。
     * 所以：
     * <pre>
     *   位移比 = (档位 × 属性)² / 0.1        // 0.1 = 玩家默认移速属性（Player.java:231）
     *   档位 = 1.0 时，0.3² / 0.1 = 0.9     // ⇒ 约为走路玩家的九成，略慢
     * </pre>
     * 想要与玩家同速就把属性改成 {@code √0.1 ≈ 0.3162}；想更慢就继续减小
     * （比值恒等于 `属性² / 0.1`，只需改这一个数）。原版自己也承认这个平方：
     * {@code PathNavigation#doStuckDetection:312} 估算预期位移时显式平方了速度。
     */
    public static AttributeSupplier.Builder createNpcAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1000.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)
                .add(Attributes.ATTACK_DAMAGE, 100.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D);
    }

    // ===================== 状态 =====================

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
     * <p>
     * <b>为什么不能改用原版的 {@code setInvulnerable(true)}</b>：原版
     * {@code Entity#isInvulnerableTo}（{@code Entity.java:2681-2686}）在无敌判断里**显式放行创造模式玩家**
     * （{@code && !source.isCreativePlayer()}），即"创造玩家能打无敌实体"是**有意设计**；
     * 而本 NPC 要的是连创造也打不动。
     */
    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || super.isInvulnerableTo(source);
    }

    /** 站桩 NPC 不该被玩家推着走。原版先例：{@code Warden:555}、{@code Bat:85}、{@code Parrot:392}。 */
    @Override
    public boolean isPushable() {
        return false;
    }

    /**
     * 永不消失 —— 用原版**官方钩子**而不是覆写 {@code isPersistenceRequired()}。
     * <p>
     * {@code Mob#checkDespawn}（{@code Mob.java:746-749}）的闸门是
     * {@code !isPersistenceRequired() && !requiresCustomPersistence()}，
     * 而这个方法正是原版留给子类表达"我的存续不该由刷怪规则决定"的
     * （先例：{@code AbstractFish:45}、{@code Axolotl:423}、{@code Raider:248}、{@code EnderMan:434}）。
     * <p>
     * 构造函数里仍然 {@code setPersistenceRequired()}：那是**字段**，让保存出的 NBT 与语义一致。
     */
    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    // ===================== 基础 AI（全部是原版 goal）=====================

    /**
     * 基础 AI —— **全部由原版 goal 组成，没有一个是自研的**（攻击 goal 除外，见下）。
     * <p>
     * 优先级与 {@code Goal.Flag} 的关系是最容易踩的地方
     * （{@code GoalSelector} 用 {@code lockedFlags} 仲裁：低优先级 goal 只要与正在运行的
     * 高优先级 goal 有 Flag 交集，就**无法启动**）：
     * <ul>
     *   <li>{@code NpcAttackGoal}（{@code MeleeAttackGoal} 子类）占 <b>MOVE + LOOK</b>，
     *       而 {@code LookAtPlayerGoal} 占 <b>LOOK</b>。若攻击 goal 优先级更低，
     *       "玩家在跟随距离内"时它会被 look goal **永久挡死** ⇒ 永远打不到人（且不报错）。
     *       所以攻击 goal 排在 <b>3</b>，比 look goal 的 5 更靠前。</li>
     *   <li><b>刻意没有 {@code RandomLookAroundGoal}</b>（原版被动生物的标准项，本类加过又移除）：
     *       它表面上"只动头、不产生位移"，但站桩时原版 {@code BodyRotationControl} 会**把身体拖向头**
     *       （见类注释"身朝"一节）⇒ 净效果是 NPC **自主间歇性转身**，与
     *       "默认面朝一个方向、不自主转动"直接冲突。实机验证后由用户裁定移除。
     *       <b>将来若要"站着四处张望"，先想清楚要不要连身体一起转</b> —— 只转头而不动身体
     *       做不到，除非覆写 {@code createBodyControl()}（与"尽量用原版"相悖）。</li>
     * </ul>
     * <b>这里刻意没有"把身体转向玩家"的 goal</b>：站桩时身体由原版
     * {@code BodyRotationControl} 追着头走（见类注释"身朝"一节），
     * {@code LookAtPlayerGoal} 把头转向玩家后身体会滞后跟上 —— 于是"先扭头、后转身"与
     * 头部 Molang 的输入都是白拿的。
     */
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(3, new NpcAttackGoal(this, 1.0D, true));
        // probability 给 1.0：默认的 0.02 会让它平均 2.5 秒才看你一眼。
        // lookTime 40~80 tick 一轮、到期立刻重启 ⇒ 实际是持续跟随。
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, facePlayerDistance(), 1.0F));
    }

    /**
     * 攻击指令的失效兜底（{@code Mob#serverAiStep} 内部调用，需要 AI 生效）。
     * <p>
     * 目标死亡、或目标变成创造 / 旁观玩家时，{@code MeleeAttackGoal.canUse()} 会返回 false；
     * 但若该 goal **从未启动过**，它的 {@code stop()} 就不会被调用 ⇒
     * {@code attackCommandActive} 与 {@code getTarget()} 会一直挂着
     * （实体永久保留一个已死的目标，{@link NpcAttackGoal} 还会每 20 tick 白轮询一次）。
     */
    @Override
    protected void customServerAiStep() {
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
     * 走到目标点。
     * <p>
     * 档位固定给原版惯例的 {@code 1.0}（= 该生物的基础速度，即
     * {@link #createNpcAttributes()} 里那个 0.3，约为走路玩家的九成）。
     * 原版 goal 也是这么用的：各自挑一个档位交给寻路
     * （{@code MeleeAttackGoal} 给 1.0、{@code FollowParentGoal}/{@code TemptGoal} 给 1.25、
     * {@code PanicGoal} 给 2.0），而位移对档位是**平方**关系 —— 这就是原版的速度语义。
     */
    public void walkTo(Vec3 pos) {
        if (this.level().isClientSide()) {
            return;
        }
        this.clearMotionCommands();
        this.attackCommandActive = false;
        this.setTarget(null);
        this.getNavigation().moveTo(pos.x, pos.y, pos.z, 1.0D);
    }

    /** 命令它去攻击某个目标（会先取消移动指令）。传 {@code null} 取消攻击。 */
    public void attack(@Nullable LivingEntity target) {
        if (this.level().isClientSide()) {
            return;
        }
        this.clearMotionCommands();
        this.attackCommandActive = target != null;
        this.setTarget(target);
    }

    /** 取消全部外部指令（移动 / 攻击），回到站桩。 */
    public void stopAction() {
        if (this.level().isClientSide()) {
            return;
        }
        this.clearMotionCommands();
        this.attackCommandActive = false;
        this.setTarget(null);
    }

    /**
     * 清掉"位移类"指令，不碰攻击指令。
     * <p>
     * 不再有冲刺复位：奔跑已取消（用户裁定"不要额外的奔跑状态"），
     * 速度差异改由原版进度/效果经移速属性体现。
     */
    private void clearMotionCommands() {
        this.getNavigation().stop();
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
     * 主控制器：待机 / 走路 / 奔跑 三选一。
     * <p>
     * <b>{@code RawAnimation} 刻意在这里构建而不在构造函数里</b>：动画名来自**可覆写**方法
     * （{@link #idleAnimationName()} 等），构造函数里调虚方法会踩"子类字段尚未初始化"的经典坑。
     * 而 {@code registerControllers} 由 {@code AnimatableManager} **惰性**调用，那时子类早已构造完。
     * <p>
     * <b>「跑」不是一种状态，而是「有额外移速加成」的表现</b>（用户裁定）：
     * 判据是 {@code getAttributeValue(MOVEMENT_SPEED) > getAttributeBaseValue(MOVEMENT_SPEED)}
     * —— 迅捷效果就是往这个属性挂修饰符（{@code MobEffect#addAttributeModifiers:166-174}），
     * 属性值又会同步给客户端（{@code ClientboundUpdateAttributesPacket}），
     * 因此**效果一生效自动切 run、一结束自动回 walk**，不需要状态机、也不需要网络包。
     * 口径是"**任何**额外移速加成"（迅捷 / 信标 / 食物 / 其它模组的 modifier 都算），不只迅捷。
     * <p>
     * 移动判据用 GeckoLib 的 {@code isMoving()}（横向速度 ≥ 0.015/tick 且 {@code walkAnimation} 在动）。
     * 它要求实体**真的在位移** —— 所以"原地转身/扭头"不算移动，仍播 idle。
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
            NpcEntity npc = state.getAnimatable();
            boolean speedBoosted = npc.getAttributeValue(Attributes.MOVEMENT_SPEED)
                    > npc.getAttributeBaseValue(Attributes.MOVEMENT_SPEED);
            return state.setAndContinue(speedBoosted ? run : walk);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
