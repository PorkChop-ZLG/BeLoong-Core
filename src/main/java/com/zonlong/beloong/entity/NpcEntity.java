package com.zonlong.beloong.entity;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.entity.ai.NpcAttackGoal;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
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
     * 飞行动画名。默认 {@code "fly"} —— 原版飞生物也是这个命名习惯。
     * <p>
     * ⚠️ <b>这个名字必须真的存在于子类的动画资产里，否则会静默塌成 T-pose。</b>
     * GeckoLib 对"动画名找不到"是<b>不报错、不打日志</b>的
     * （{@code AnimationProcessor.java:44-64}：查不到返回 {@code null}、不置 {@code error}），
     * 于是该控制器拿到的是**空动画列表** ⇒ 骨骼不再被写 ⇒ 按复位机制回到
     * <b>静止姿态</b>。这不是"少一段动画"，是整体姿势垮掉。
     * <p>
     * 本类**刻意不加运行时回落保护**（用户裁定）：那需要引入客户端侧的
     * {@code GeckoLibCache} 耦合，代价大于收益。所以新增 NPC 时请自己确认资产里有这条动画。
     * <p>
     * 另注：飞行时**只播这一条**，不进 idle/walk/run 分支 ——
     * 因为横向飞行会让 {@code walkAnimation} 非零（{@code LivingEntity.java:2373-2376}
     * 的 {@code includeHeight} 只决定是否算 Y 位移，X/Z 照算），
     * GeckoLib 的 {@code isMoving()} 会为真 ⇒ 不改的话腿会在空中走。
     */
    protected String flyAnimationName() {
        return "fly";
    }

    /**
     * {@code fly on} 时飞到"当前 Y + 这个值"再悬停（格）。
     * <p>
     * 取 {@code 2.0} 是因为它约等于一个 NPC 的身高：起飞后脚底离开地面、头顶仍有净空，
     * 玩家抬头就能看到它悬在那里。
     */
    protected double takeoffHeight() {
        return DEFAULT_TAKEOFF_HEIGHT;
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

    /**
     * 脚下阴影半径（格）。默认 <b>0.5 = 原版玩家</b>。
     * <p>
     * <b>为什么这个默认值放在实体类里、而不是各个渲染器里</b>：
     * 阴影半径是"这个 NPC 看起来该有多大"的一部分，与
     * {@link #idleAnimationName()}、{@link #facePlayerDistance()} 同类，
     * 属于**通用 NPC 基类该给的默认**。子类要例外就在这里覆写，渲染器不用管。
     * <p>
     * <b>为什么必须有默认值（不能靠原版默认）</b>：
     * {@code EntityRenderer.java:31} 的 {@code shadowRadius} 字段<b>没有初值</b>（默认 0.0F），
     * 而阴影只在半径 &gt; 0 时才绘制（{@code EntityRenderDispatcher.java:168-169}
     * 的 {@code float f = entityrenderer.getShadowRadius(entity); if (f > 0.0F)}）。
     * GeckoLib 全仓不设这个值 ⇒ <b>"GeckoLib 实体没有影子"是默认现象</b>，
     * 曾让地黄龙与末都没有影子（代码审查 P1-7）。
     * <p>
     * <b>取值 0.5 的依据</b>：原版 {@code shadowRadius} 大致跟碰撞箱宽度走，实测：
     * 玩家/僵尸/骷髅（宽 0.6）是 {@code 0.5}（{@code PlayerRenderer.java:49}），
     * 马（宽 1.4）{@code 0.75}，蜘蛛（宽 1.4）{@code 0.8}，不死马 {@code 1.0}。
     * 通用 NPC 的默认碰撞箱就是玩家尺寸，故直接取玩家那一档。
     * <p>
     * 由 {@link com.zonlong.beloong.client.NpcRenderer#getShadowRadius} 读取 ——
     * 那条链路能成立是因为阴影绘制用的是<b>方法</b>
     * （{@code EntityRenderDispatcher.java:168}）而不是字段本身。
     * <p>
     * 注：数值与渲染缩放（如 {@code MoRenderer.MODEL_SCALE}）**无关** ——
     * 阴影由原版按碰撞箱画，不随模型缩放走。
     */
    public float shadowRadius() {
        return 0.5F;
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
     * <p>
     * <b>"站桩不动"在属性层要两件东西，缺一不可</b>：
     * {@code KNOCKBACK_RESISTANCE}（挡攻击/近战击退）与
     * {@code EXPLOSION_KNOCKBACK_RESISTANCE}（挡爆炸位移）—— 两者各挡一条**互不相通**的
     * 代码路径，详见 {@code EXPLOSION_KNOCKBACK_RESISTANCE} 那行的注释。
     * <p>
     * ⚠️ <b>但"被水流冲走"是设计目的，不要当成漏挡去"修"</b> ——
     * 流体推动是第三条路径，本类**刻意不挡**，理由与机理见
     * {@link #isPushable()} 之后那段注释。
     */
    public static AttributeSupplier.Builder createNpcAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1000.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)
                // 爆炸推动走的是**另一个**属性，KNOCKBACK_RESISTANCE 挡不住它：
                // Explosion.java:294 `d10 = d13 * (1.0 - getAttributeValue(EXPLOSION_KNOCKBACK_RESISTANCE))`
                // —— 该属性默认 0（Attributes.java:52-53，范围 [0,1]），取 1.0 即把爆炸位移乘 0。
                .add(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, 1.0D)
                .add(Attributes.ATTACK_DAMAGE, 100.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D)
                // 飞行速度是**独立属性**：FlyingMoveControl.java:38 在空中读它，
                // 而属性不存在会抛异常（与 ATTACK_DAMAGE 同一个坑）⇒ 必须显式 add。
                // 0.6 的依据：本类**不重写 travel**，空中加速度 = 0.02 × FLYING_SPEED；
                // 原版 Bee 用 0.6（Bee.java:544），Allay 用 0.1 但它自己重写了 travel 补偿
                // （Allay.java:184 的 moveRelative(getSpeed())）。属一行可调。
                .add(Attributes.FLYING_SPEED, 0.6D);
    }

    // ===================== 状态 =====================

    /** 是否处于"被下令攻击"状态。只有它为 true 时 {@link NpcAttackGoal} 才会运行。 */
    private boolean attackCommandActive;

    /**
     * 是否处于飞行模式。
     * <p>
     * ⚠️ <b>它必须是同步数据，不能是普通字段。</b>
     * 飞行状态有两个读者，一个在服务端、一个在<b>客户端</b>：
     * <ul>
     *   <li>服务端：{@link #tickMoveCommand()} 判"是否到位"要用三维还是二维距离；</li>
     *   <li>客户端：{@link #registerControllers} 的动画谓词要据此播 {@code fly}。</li>
     * </ul>
     * 普通字段只在服务端更新、不会发给客户端 ⇒ 客户端永远读到 {@code false}
     * ⇒ <b>{@code fly} 动画根本不会播</b>，而且不报错（GeckoLib 静默失败那一族）。
     * <p>
     * 同步数据的范式照原版 {@code Allay} 的 {@code DATA_DANCING}：
     * {@code Allay.java:84}（定义）、{@code :166-167}（{@code defineSynchedData}）、
     * {@code :417,422}（读写）。{@code Entity.java:342} 的
     * {@code defineSynchedData(SynchedEntityData.Builder)} 是抽象方法，每个实体子类都要实现。
     * <p>
     * 附带好处：同步数据<b>天然不持久化</b>（只有 {@code addAdditionalSaveData} 才落盘），
     * 正好符合"飞行状态不进存档"的裁定 —— 见 {@link #readAdditionalSaveData} 的归一化。
     */
    private static final EntityDataAccessor<Boolean> DATA_FLYING =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BOOLEAN);

    /**
     * 移动指令的目标点；{@code null} = 没有移动指令。<b>地面与飞行共用同一套。</b>
     * <p>
     * <b>为什么要把目标点存下来而不是只调一次 {@code getNavigation().moveTo(...)}</b>：
     * 原版寻路是**有距离上限**的，一次 {@code moveTo} 走不到远处。见
     * {@link #tickMoveCommand()} 的说明。飞行<b>同样</b>吃这个上限。
     */
    @Nullable
    private Vec3 moveTarget;

    /** 距下次重新寻路的 tick 数（节流用）。 */
    private int moveRepathCooldown;

    /** 迄今离目标最近的水平距离平方；用于"卡住就放弃"的有界失败判断。 */
    private double moveBestDistSqr = Double.MAX_VALUE;

    /** 连续多少次重新寻路都没有更靠近目标。 */
    private int moveNoProgressCount;

    /** 到位判定（水平距离，格）。与寻路 {@code accuracy=1} 的口径一致。 */
    private static final double MOVE_ARRIVE_DISTANCE = 1.0D;

    /**
     * 重新寻路的节流间隔（tick）。
     * <p>
     * 20 = 每秒一次。取这个量级是因为：路走完会<b>立刻</b>续（见 {@link #tickMoveCommand()}），
     * 这个间隔只是兜住"路径还在走但我已经偏离"的情况，不需要太频繁。
     */
    private static final int MOVE_REPATH_INTERVAL = 20;

    /**
     * 连续多少次重新寻路都没更靠近目标就判定"不可达"并放弃。
     * <p>
     * 5 次 × 20 tick ≈ 5 秒。这是刻意的**有界失败**：目标点在虚空 / 墙里 / 无路可达时，
     * 不能让 NPC 永远每 20 tick 白跑一次寻路。
     */
    private static final int MOVE_MAX_NO_PROGRESS = 5;

    /**
     * 飞行时的到位判定半径（格）。比地面那个 1.0 略宽 ——
     * 空中没有"贴地"这个约束，窄了会让 NPC 在目标点附近来回蹭。
     */
    private static final double FLY_ARRIVE_DISTANCE = 1.5D;

    /**
     * 起飞偏移的默认值：{@code fly on} 时把目标点设成"当前 Y + 这个值"。
     * <p>
     * 子类要改就覆写 {@link #takeoffHeight()}。
     */
    private static final double DEFAULT_TAKEOFF_HEIGHT = 2.0D;

    /**
     * 飞行移动控制的转向速率（度/tick）。
     * <p>
     * 20 是原版飞生物的值：{@code Bee.java:139}、{@code Allay.java:122} 都传 20。
     */
    private static final int FLY_MAX_TURN = 20;

    protected NpcEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        // 与下面两个覆写并存：让字段本身与保存出的 NBT 一致；语义由覆写保证。
        setInvulnerable(true);
        setPersistenceRequired();
    }

    /**
     * 同步数据表。本类只加一项 {@link #DATA_FLYING}，且默认 {@code false}
     * —— <b>"飞行默认不开启"这条需求在数据层就体现为这个默认值</b>。
     */
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_FLYING, false);
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

    // ===================== 刻意【不】阻挡的位移来源 =====================

    /*
     * 流体流：**被水流冲走是设计目的**（用户裁定 2026-09-27），故此处刻意不覆写
     * isPushedByFluid，本类也不对它做任何处理。
     *
     * 留这段注释是因为：代码审查总报告 P1-3 曾把"流体能推动 NPC"列为待修缺陷，
     * 而这个位置正是将来有人想去"修"它时会找的地方。不要再加这个覆写。
     *
     * 机理备查（若哪天需求变了，改法就在这里）：流体推动走的是独立路径 ——
     * Entity#updateFluidHeightAndDoFluidPushing() 直接把流速矢量 add 进 deltaMovement
     * （Entity.java:3391 的 setDeltaMovement(...add(interim.flowVector))），
     * 与 isPushable() / KNOCKBACK_RESISTANCE 都无关。真正做判断的是
     * Entity.java:3358 的 isPushedByFluid(fluidType)（带 FluidType 的重载；
     * 无参版在 NeoForge 已 @Deprecated 且本版本里无人引用），
     * IEntityExtension 的默认实现是 self().isPushedByFluid() && type.canPushEntity(self())。
     */

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
     * 每 tick 的服务端 AI 兜底（{@code Mob#serverAiStep} 内部调用，需要 AI 生效）。
     * <p>
     * <b>调用时机很关键</b>：{@code Mob.java:792 goalSelector.tick()} →
     * {@code :797 navigation.tick()} → <b>{@code :800} 本方法</b>。
     * 也就是说本方法在 <b>goal 仲裁之后</b>运行 —— 下面续行路指令正是靠这一点
     * 才不会被任何 goal 的拆解抹掉。
     *
     * <h4>① 攻击指令的失效兜底</h4>
     * 目标死亡、或目标变成创造 / 旁观玩家时，{@code MeleeAttackGoal.canUse()} 会返回 false；
     * 但若该 goal **从未启动过**，它的 {@code stop()} 就不会被调用 ⇒
     * {@code attackCommandActive} 与 {@code getTarget()} 会一直挂着
     * （实体永久保留一个已死的目标，{@link NpcAttackGoal} 还会每 20 tick 白轮询一次）。
     *
     * <h4>② 移动指令的持续续路（地面与飞行共用）</h4>
     * 见 {@link #tickMoveCommand()}。
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

        this.tickMoveCommand();
    }

    // ===================== 外部驱动 API（只在服务端生效）=====================

    /**
     * 走到目标点。
     * <p>
     * <b>本方法只登记目标点，不直接下发路径</b> —— 真正的 {@code getNavigation().moveTo(...)}
     * 在 {@link #tickMoveCommand()} 里做，原因是原版寻路有距离上限（见该方法）。
     * <p>
     * 档位固定给原版惯例的 {@code 1.0}（= 该生物的基础速度，即
     * {@link #createNpcAttributes()} 里那个 0.3，约为走路玩家的九成）。
     * 原版 goal 也是这么用的：各自挑一个档位交给寻路
     * （{@code MeleeAttackGoal} 给 1.0、{@code FollowParentGoal}/{@code TemptGoal} 给 1.25、
     * {@code PanicGoal} 给 2.0），而位移对档位是**平方**关系 —— 这就是原版的速度语义。
     * <p>
     * <b>飞行状态下它等同于 {@link #flyTo}</b>：导航已经是飞行版，路径自然走空中。
     * 这不需要特判 —— 两者共用同一套"登记目标 + 续路"的机制。
     */
    public void walkTo(Vec3 pos) {
        if (this.level().isClientSide()) {
            return;
        }
        this.clearMotionCommands();
        this.attackCommandActive = false;
        this.setTarget(null);
        this.setMoveTarget(pos);
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

    /** 取消全部外部指令（移动 / 攻击），回到站桩。注意它**不改飞行状态** —— 要回到默认状态用 {@link #resetToDefault()}。 */
    public void stopAction() {
        if (this.level().isClientSide()) {
            return;
        }
        this.clearMotionCommands();
        this.attackCommandActive = false;
        this.setTarget(null);
    }

    // ===================== 飞行 =====================

    /**
     * 是否处于飞行模式。<b>双端都可读</b> —— 值来自同步数据 {@link #DATA_FLYING}，
     * 不是服务端独有字段。这一点是刻意的：{@link #registerControllers} 的动画谓词
     * 在<b>客户端</b>读它。
     */
    public boolean isFlying() {
        return this.entityData.get(DATA_FLYING);
    }

    /**
     * 开关飞行。<b>只在服务端生效</b>（AI 只在服务端跑，客户端调它没有意义）。
     * <p>
     * <b>幂等</b>：与当前状态相同时直接返回、**不重建那两个字段**。
     * 这不是洁癖 —— {@link #enableFlight()} 会重建
     * {@code moveControl}/{@code navigation}，重复执行等于把正在执行的飞行路径丢掉，
     * 症状是"连按两次 {@code fly on}，NPC 定在半空不动"。
     */
    public void setFlying(boolean flying) {
        if (this.level().isClientSide()) {
            return;
        }
        if (flying == this.isFlying()) {
            return;
        }
        this.entityData.set(DATA_FLYING, flying);
        if (flying) {
            this.enableFlight();
        } else {
            this.disableFlight();
        }
    }

    /**
     * 切到飞行模式 —— 走原版飞生物那套配方，我们只多做了"运行期换字段"这一件事。
     *
     * <h4>配方来源</h4>
     * NeoForge 21.1.236 里只有 4 个生物用 {@code FlyingMoveControl}：
     * {@code Bee} / {@code Parrot} / {@code Allay} / {@code WitherBoss}，
     * <b>全部是 {@code PathfinderMob} 后裔</b>，与本类同源。它们是在**构造期**换字段，
     * 我们挪到运行期；换字段本身是合法的，因为那两个字段不是 final，且每 tick 现读：
     * <pre>
     *   Mob.java:109   protected MoveControl moveControl;      ← 非 final
     *   Mob.java:112   protected PathNavigation navigation;   ← 非 final
     *   Mob.java:205,213   getMoveControl()/getNavigation() 现读字段（只额外判载具）
     *   Mob.java:797,804   serverAiStep 里也是现读 ⇒ 换掉当 tick 生效
     * </pre>
     *
     * <h4>为什么必须显式 setNoGravity(true)</h4>
     * {@code FlyingMoveControl} 只在<b>有移动目标时</b>才自己置 true
     * （{@code FlyingMoveControl.java:21}），而 {@code hoversInPlace=true} 时它
     * <b>空闲也不会关掉</b>（{@code :49-51} 的 {@code if (!this.hoversInPlace)}）。
     * ⇒ <b>"悬停" = 飞行中 + 没有移动目标</b>，不需要独立状态；
     * 但**关飞行时必须自己收回来**，那是 {@link #disableFlight()} 的责任。
     */
    private void enableFlight() {
        this.moveControl = new FlyingMoveControl(this, FLY_MAX_TURN, true);
        final FlyingPathNavigation flying = new FlyingPathNavigation(this, this.level());
        // 三个开关照原版飞生物（Bee.java:565-567 / Allay.java:159-161）：
        // 站桩 NPC 不该开门、不该"浮在液体上"，但要能穿门（否则室内飞不动）。
        flying.setCanOpenDoors(false);
        flying.setCanFloat(false);
        flying.setCanPassDoors(true);
        this.navigation = flying;

        this.setNoGravity(true);
        // 立刻起飞：把目标点放到头顶上方，之后交给同一套续路逻辑（tickMoveCommand）
        this.setMoveTarget(this.position().add(0.0D, this.takeoffHeight(), 0.0D));
    }

    /**
     * 切回地面模式。
     * <p>
     * ⚠️ <b>本方法刻意没有任何提前 return，保证 {@code setNoGravity(false)} 一定执行到。</b>
     * "浮空、但没人知道自己在飞"是最难查的一类状态：它不落地，又不接受地面移动控制。
     * <p>
     * "先停旧路径、再换字段"的顺序是有意的：否则旧 {@code FlyingPathNavigation} 还持有
     * 下一个路点，而 {@code moveControl} 已经换成地面版，会出现一拍
     * "地面移动控制执行空中路点"的错配。
     */
    private void disableFlight() {
        this.clearMotionCommands();      // moveTarget = null + 清冷却 + navigation.stop()
        this.setNoGravity(false);
        this.moveControl = new MoveControl(this);
        this.navigation = new GroundPathNavigation(this, this.level());
    }

    /**
     * 飞到指定点（需先处于飞行状态）。
     * <p>
     * 与 {@link #walkTo} 共用 {@link #moveTarget} 与 {@link #tickMoveCommand()} 的续路循环，
     * 差别只在"是否到位"的口径（空中用三维）。
     * <b>飞行状态下 {@code walkTo} 与它等价</b> —— 因为导航已经是飞行版了。
     * <p>
     * 前置校验交给命令层做（未开飞行时明确报错）；本方法**不**自作主张开启飞行。
     */
    public void flyTo(Vec3 pos) {
        if (this.level().isClientSide()) {
            return;
        }
        this.attackCommandActive = false;
        this.setTarget(null);
        this.setMoveTarget(pos);
    }

    /**
     * 恢复到"刚被召唤出来的状态"：无飞行、无移动、无攻击、地面站桩。
     * <p>
     * <b>{@code setNoGravity(false)} 是无条件执行的，不能写成 {@code if (isFlying())}。</b>
     * 理由见 {@link #DATA_FLYING} 与 {@link #readAdditionalSaveData}：
     * {@code NoGravity} 会被原版写进 NBT（{@code Entity.java:1769,1878}）而本类的飞行标志
     * 不持久化 ⇒ 两者**可能脱钩**。脱钩状态下若还加判断，就正好修不回来。
     */
    public void resetToDefault() {
        if (this.level().isClientSide()) {
            return;
        }
        this.clearMotionCommands();
        this.attackCommandActive = false;
        this.setTarget(null);
        if (this.isFlying()) {
            this.entityData.set(DATA_FLYING, false);
            this.disableFlight();
        }
        this.getNavigation().stop();
        this.setNoGravity(false);
    }

    /** 登记移动目标，并让下一次 {@code customServerAiStep} 立刻寻路。 */
    private void setMoveTarget(Vec3 pos) {
        this.moveTarget = pos;
        this.moveRepathCooldown = 0;
        this.moveBestDistSqr = Double.MAX_VALUE;
        this.moveNoProgressCount = 0;
    }

    /**
     * 载入时把 {@code NoGravity} 拉回不变式：<b>{@code isFlying() == false ⇒ isNoGravity() == false}</b>。
     * <p>
     * 为什么需要这一步：{@code NoGravity} 是原版持久化的
     * （{@code Entity.java:1769} 写、{@code :1878} 读），而本类的飞行标志在同步数据里、
     * 不会被 {@code addAdditionalSaveData} 写出 ⇒ 飞行中存档再进游戏，会得到
     * "<b>永久浮空、但 {@code isFlying() == false}</b>"的实体，
     * 而且 {@link #disableFlight()} 与 {@link #resetToDefault()} 里的
     * {@code if (isFlying())} 分支**都不会**被触发。
     * <p>
     * 既然加载后 {@code flying} 必然是 {@code false}（{@link #defineSynchedData} 的默认值），
     * 那就顺手把重力也拉回来，让不变式成立。
     */
    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        this.setNoGravity(false);
    }

    /**
     * 清掉"位移类"指令，不碰攻击指令。
     * <p>
     * 不再有冲刺复位：奔跑已取消（用户裁定"不要额外的奔跑状态"），
     * 速度差异改由原版进度/效果经移速属性体现。
     */
    private void clearMotionCommands() {
        this.moveTarget = null;
        this.moveRepathCooldown = 0;
        this.getNavigation().stop();
    }

    /**
     * 移动指令的持续续路 —— <b>这是 walk / {@link #flyTo} 能真正抵达远距离目标点的关键</b>。
     * <p>
     * 地面与飞行<b>共用这一套</b>，而且**必须**共用：{@code FlyingPathNavigation} 同样继承
     * {@code PathNavigation}，下面那条距离上限在飞行上会**一模一样**地复现。
     * 唯一的分叉是"是否到位"的口径（空中三维、地面二维），见方法内注释。
     *
     * <h4>问题：原版寻路一次走不到远处</h4>
     * {@code PathNavigation#moveTo(x,y,z,speed)} 只做一次寻路，而那次寻路的
     * <b>探索半径就是 {@code Attributes.FOLLOW_RANGE}</b>：
     * <pre>
     *   PathNavigation.java:151  createPath(..., (float)this.mob.getAttributeValue(Attributes.FOLLOW_RANGE))
     *   PathNavigation.java:169  this.pathFinder.findPath(..., followRange, accuracy, ...)
     *   PathFinder.java:91,99    只展开 distanceTo/walkedDistance &lt; maxRange 的节点
     *   PathFinder.java:116-122  到不了目标时用 getBestNode() 重建**部分路径**，
     *                            并带 reachesTarget = false 返回
     * </pre>
     * 而 {@code Mob.createMobAttributes()} 给的 {@code FOLLOW_RANGE} 默认是 <b>16</b>。
     * ⇒ 目标超过约 16 格时，NPC 只会走到离目标最近的**可达**节点就停下 ——
     * 实机症状正是"走了一半就停"。
     * <p>
     * 之所以**不**直接把 {@code FOLLOW_RANGE} 调大：它在 {@code PathNavigation.java:65}
     * 还决定访问节点预算（{@code floor(FOLLOW_RANGE * 16)}），且在构造时只算一次；
     * 调大等于同时把搜索区域放大到三次方级别，反而更容易撞上预算而寻路失败。
     * 原版 mob 也正是靠 goal 定期重新寻路来解决长距离移动的。
     *
     * <h4>为什么放在这里而不是放在 goal 里</h4>
     * {@code customServerAiStep} 在 {@code Mob.java:800}，位于
     * {@code :792 goalSelector.tick()} 与 {@code :797 navigation.tick()} <b>之后</b>。
     * 于是本方法续的路不会被任何 goal 的拆解抹掉 —— 顺带解决了另一处已知缺陷：
     * {@code MeleeAttackGoal.stop()} 会<b>无条件</b>调 {@code nav.stop()}
     * （{@code MeleeAttackGoal.java:94}），而 {@code NpcAttackGoal#clearAttackCommand()}
     * 也会停寻路；两者现在都只造成最多 1 tick 的停顿——因为 {@code nav.stop()} 会让
     * {@code isDone()} 立刻为真，下一 tick 本方法就会把路续回来。
     */
    private void tickMoveCommand() {
        if (this.moveTarget == null) {
            return;
        }

        // 到位判定的口径**按状态分叉**：
        //  · 地面：只看水平。因为寻路会把 Y 规整到可站立面
        //    （GroundPathNavigation#createPath:54-84 会找地面或抬到方块上方），
        //    拿 Y 参与比较只会因为落差错判。
        //  · 空中：必须三维。FlyingPathNavigation **没有**那套吸附
        //    （它不覆写 createPath(BlockPos)），Y 本身就是目标的一部分 ——
        //    "起飞"这件事就是靠 Y 完成的。
        final double dx = this.moveTarget.x - this.getX();
        final double dy = this.moveTarget.y - this.getY();
        final double dz = this.moveTarget.z - this.getZ();
        final boolean flying = this.isFlying();
        final double distSqr = flying ? dx * dx + dy * dy + dz * dz : dx * dx + dz * dz;
        final double arriveDistance = flying ? FLY_ARRIVE_DISTANCE : MOVE_ARRIVE_DISTANCE;

        if (distSqr <= arriveDistance * arriveDistance) {
            this.moveTarget = null;
            this.getNavigation().stop();
            return;
        }

        if (this.moveRepathCooldown > 0) {
            --this.moveRepathCooldown;
        }
        // 两个续路时机：路径已经走完（含被 nav.stop() 抹掉），或节流到期
        if (this.moveRepathCooldown > 0 && !this.getNavigation().isDone()) {
            return;
        }

        // 有界失败：连续若干次续路都没有更靠近目标 ⇒ 判定不可达，放弃
        if (distSqr < this.moveBestDistSqr - 1.0E-4D) {
            this.moveBestDistSqr = distSqr;
            this.moveNoProgressCount = 0;
        } else if (++this.moveNoProgressCount > MOVE_MAX_NO_PROGRESS) {
            BeLoongCore.LOGGER.debug(
                    "[BeLoong] npc move target unreachable, giving up at {} for {}",
                    this.blockPosition(), this.moveTarget);
            this.moveTarget = null;
            this.getNavigation().stop();
            return;
        }

        this.moveRepathCooldown = MOVE_REPATH_INTERVAL;
        this.getNavigation().moveTo(this.moveTarget.x, this.moveTarget.y, this.moveTarget.z, 1.0D);
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
     * 主控制器：飞行 / 待机 / 走路 / 奔跑 四选一（飞行优先，且是**整体替换**）。
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
        final RawAnimation fly = RawAnimation.begin().thenLoop(this.flyAnimationName());
        controllers.add(new AnimationController<>(this, "main", this.animationTransitionTicks(), state -> {
            NpcEntity npc = state.getAnimatable();
            // 飞行时**整体替换**，不进 idle/walk/run 分支。
            // 为什么不能"只叠一层"就完事：横向飞行会让 walkAnimation 非零
            // （LivingEntity.java:2373-2376：includeHeight 只决定是否把 Y 位移算进去，
            //  X/Z 照算）⇒ GeckoLib 的 isMoving() 会为真 ⇒ 不拦住的话腿会在空中走路。
            if (npc.isFlying()) {
                return state.setAndContinue(fly);
            }
            if (!state.isMoving()) {
                return state.setAndContinue(idle);
            }
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
