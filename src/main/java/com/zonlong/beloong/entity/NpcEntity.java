package com.zonlong.beloong.entity;

import com.zonlong.beloong.entity.ai.NpcRouteGoal;
import com.zonlong.beloong.route.NpcRoute;
import com.zonlong.beloong.route.NpcRouteLoader;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.client.model.EmoteAnimationLookup;
import com.zonlong.beloong.entity.ai.NpcAttackGoal;
import com.zonlong.beloong.npcstory.NpcStory;
import com.zonlong.beloong.npcstory.NpcStoryLoader;
import com.zonlong.beloong.dialogue.NpcDialogueStage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
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
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.EnumMap;
import java.util.UUID;

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
 *   <li><b>永不消失</b>：{@link #requiresCustomPersistence()} 恒 true；</li>
 *   <li><b>按玩家可见</b>：{@link #broadcastToPlayer} 一律走 {@link #visibleTo}
 *       （见下面「多人兼容」一节）。**没有剧情的 NPC 恒为可见** ⇒ 现有子类行为一字不变。</li>
 * </ul>
 * 这些写成**覆写方法**而不是构造函数里 set：{@code /summon} 与刷怪蛋的流程是
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
 * <h2>多人兼容：公共锚点与私有分身（2026-10-01）</h2>
 * 本模组的 NPC **可以移动**（区别于传统站桩 NPC）⇒ 单人时"剧情让 NPC 走开"没问题，
 * 多人时却会把**共享实体**的位置当成某个玩家的剧情状态 ⇒ 其他玩家的剧情推不动。
 * <p>
 * 根因不是"NPC 会动"，而是"剧情需要的那部分状态是**每人一份**，承载它的实体却只有一份"：
 * 对话按**实体类型**查表、回复可见性按**玩家**判（{@code NpcDialogueStage}），
 * 而位置/路线/表情挂在**实体**上。解决办法是把"需要每人一份的那部分"也变成每人一份的实体。
 * <p>
 * <b>分类规则：按"这个实体承载谁的状态"</b>（不是按"它能不能动"）：
 * <ul>
 *   <li><b>公共锚点</b>（{@link #owner} == null）：承载**零玩家状态**，永不因某个玩家的剧情而移动；
 *       对"**尚未获得剧情起点进度**"的玩家可见；</li>
 *   <li><b>私有分身</b>（{@link #owner} != null）：承载**恰好一个玩家**的全部剧情状态，只对该玩家可见。</li>
 * </ul>
 * <b>不变量</b>：任一玩家眼里，同一类型的 NPC 永远**恰好只有一个** ——
 * 未开始（未获得剧情起点进度）⇒ 看到公共锚点；已开始 ⇒ 看到自己的分身。两条规则在同一刻互换。
 * <p>
 * <b>实现地基是原版钩子，不自造系统</b>：覆写 {@link #broadcastToPlayer}（{@code Entity.java:3021}，默认 true）
 * ⇒ {@code ChunkMap.TrackedEntity.updatePlayer}（{@code ChunkMap.java:1327-1343}）会对该玩家
 * {@code removePairing} 且永不 {@code addPairing} ⇒ 该玩家客户端上**这个实体根本不存在**
 * （无渲染、无碰撞箱、无法右键），且**零自定义网络包、零客户端改动**。
 * （原版同款用法：{@code ServerPlayer.java:957-961} 让旁观者不接收自己。）
 * <p>
 * ⚠️ <b>{@link #visibleTo} 必须是纯函数</b>：它**每个追踪周期、对范围内每个玩家**被调用，
 * 只能依赖"实体 + 该玩家"，不得写实体字段、不得发包（改其行为前先读这一段）。
 * ⚠️ 不要误用 {@code Entity#isInvisibleTo}（{@code Entity.java:2420}）：那只是**渲染层**，
 * 实体仍在客户端，碰撞与交互都还在。
 * <p>
 * 设计文档：{@code docs/plans/2026-10-01-multiplayer-npc-design.md}（D1–D21）。
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
     * 另注：非待机状态时**只播该状态这一条**，不进 idle/walk/run 分支 ——
     * 因为横向飞行会让 {@code walkAnimation} 非零（{@code LivingEntity.java:2373-2376}
     * 的 {@code includeHeight} 只决定是否算 Y 位移，X/Z 照算），
     * GeckoLib 的 {@code isMoving()} 会为真 ⇒ 不改的话腿会在空中走。
     * <p>
     * ⚠️ <b>但客户端只有"状态标志"，没有"飞行导航"</b>（{@link #setState} 在客户端直接
     * {@code return}，换字段是服务端独占的）⇒ GeckoLib 的
     * {@code query.can_fly / can_walk / can_swim / can_climb} 在客户端**恒为地面值**
     * （它们按 {@code getNavigation() instanceof ...} 现算）。
     * 本模组当前不受影响（{@code mo.animation.json} 里 {@code query.} 命中 0 次，用的是
     * 在 GeckoLib 下同样恒为 0 的 {@code ysm.*}）；但**将来若有人想在动画里用
     * {@code query.can_fly}，得先解决这件事**，别指望在客户端也换字段
     * —— 客户端不 tick 导航，换了只会引入状态分裂。
     */
    protected String flyAnimationName() {
        return "fly";
    }

    /**
     * 攻击动画名。默认 {@code "attack"}。
     * <p>
     * ⚠️ 与 {@link #flyAnimationName()} 是同一个坑：<b>资产里没有这条动画就不会播</b> ——
     * 客户端在触发前会预检（{@link #swing}），查不到就什么都不做。
     * 这比"静默塌成初始姿态"好，但仍不会报错；失败会留一条<b>英文 WARN</b>（见 {@code EmoteAnimationLookup}）。
     */
    protected String attackAnimationName() {
        return "attack";
    }

    /**
     * 状态 → 动画名。{@link NpcState#IDLE} 不参与（它走 idle/walk/run 三选一，见 {@link #registerControllers}）。
     * <p>
     * <b>为什么动画名放在这个方法里、而不是塞进 {@link NpcState} 的枚举常量</b>：
     * 原版 {@code Armadillo.java:410} 那样把数据挂在枚举上很诱人，但
     * <b>同一个逻辑状态在不同模型上叫法不同</b>（Mo 与地黄龙的资产各自命名）⇒
     * 枚举只承载**逻辑状态**，动画名留给每个 NPC 覆写。
     * 子类通常只需覆写上面那几个单项方法，不必覆写本方法。
     */
    protected String stateAnimationName(NpcState state) {
        return switch (state) {
            case FLYING -> this.flyAnimationName();
            case IDLE -> this.idleAnimationName();
        };
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
     * NPC 的**状态**（同步数据）。取值见 {@link NpcState}。
     * <p>
     * ⚠️ <b>它必须是同步数据，不能是普通字段。</b>
     * 状态有两个读者，一个在服务端、一个在<b>客户端</b>：
     * <ul>
     *   <li>服务端：{@link #switchState} 按状态换导航/移动控制与重力；{@link #tickMoveCommand()}
     *       判"是否到位"要用三维还是二维距离；</li>
     *   <li>客户端：{@link #registerControllers} 的动画谓词要据此选动画。</li>
     * </ul>
     * 普通字段只在服务端更新、不会发给客户端 ⇒ <b>客户端永远读到 {@link NpcState#IDLE}</b>
     * ⇒ 状态动画根本不会播，而且不报错（GeckoLib 静默失败那一族）。
     * <p>
     * <b>为什么存 {@code int} 而不用自定义序列化器</b>：模组被禁止调用
     * {@code EntityDataSerializers.registerSerializer}（{@code EntityDataSerializers.java:133-141}
     * 直接抛 {@code UnsupportedOperationException}，要求注册到
     * {@code NeoForgeRegistries.ENTITY_DATA_SERIALIZERS}）；用现成的 {@code INT} + {@code ByIdMap}
     * 可以完全绕开这件事。还原走 {@link NpcState#byId(int)}（越界回落待机）。
     * <p>
     * 范式：原版 {@code Allay} 的 {@code DATA_DANCING}（{@code Allay.java:84} 定义、
     * {@code :166-167} 注册、{@code :417,422} 读写）与 {@code Armadillo} 的状态访问器
     * （{@code Armadillo.java:55,82-85}）。{@code Entity.java:342} 的
     * {@code defineSynchedData(SynchedEntityData.Builder)} 是抽象方法，每个实体子类都要实现。
     */
    private static final EntityDataAccessor<Integer> DATA_STATE =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);

    /**
     * <b>表情</b> —— 一个动画名，<b>空串表示"没有表情"</b>。
     * <p>
     * 与 {@link #DATA_STATE} 一样走同步数据（<b>双端可读</b>）：{@link #registerControllers}
     * 的动画谓词在<b>客户端</b>读它。
     * <p>
     * 用原版现成的 {@link EntityDataSerializers#STRING}（{@code ByteBufCodecs.STRING_UTF8}），
     * 先例是原版 {@code MinecartCommandBlock} 的 {@code DATA_ID_COMMAND_NAME} ——
     * 本项目已确认<b>模组不能注册自定义序列化器</b>（理由见 {@link #DATA_STATE} 的注释）。
     * 用空串而不是 {@code Optional}：{@code STRING} 不接受 {@code null}，
     * 而"没有表情"只需要一个哨兵值。
     * <p>
     * ⚠️ <b>刻意不落盘</b>（设计 D5）：表情是纯表现层，重登后直接回到状态动画。
     * 因此它<b>不出现在</b> {@link #addAdditionalSaveData} / {@link #readAdditionalSaveData} 里
     * —— 这一条由探针 {@code tools/YSMParser/probe_emote_java.py} 守着。
     */
    private static final EntityDataAccessor<String> DATA_EMOTE =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);

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

    /**
     * 路线名（可为空 = 没有路线）。与 {@link #state} 一样**只在服务端有意义**：
     * 客户端不需要知道它在走什么路线（移动本身由原版同步）。
     */
    @Nullable
    private ResourceLocation routeName;

    /** 当前路点下标；等于路点数即"已抵达终点"（不另设"已完成"标记）。 */
    private int routeIndex;

    /**
     * 私有分身的归属玩家；{@code null} = **公共锚点**（见类注释「多人兼容」一节）。
     * <p>
     * 刻意不做成"每个玩家一份的字段"：一个实体只属于一个玩家 —— 这正是"每人一份"的落点。
     */
    @Nullable
    private UUID owner;

    /** 迄今离目标最近的水平距离平方；用于"卡住就放弃"的有界失败判断。 */
    private double moveBestDistSqr = Double.MAX_VALUE;

    /** 连续多少次重新寻路都没有更靠近目标。 */
    private int moveNoProgressCount;

    /**
     * 进入飞行**之前**那个原版 {@code PathNavigation} 实例。
     * <b>非 {@code null} 即表示"当前处于飞行模式"</b>（{@link #switchState} 用它判断要不要先退出飞行）。
     * <p>
     * ⚠️ <b>必须存原实例、不能"新建一个同款的"。</b>
     * 原版 {@code FloatGoal} 的<b>构造器</b>里就有
     * {@code mob.getNavigation().setCanFloat(true)}（{@code FloatGoal.java:13}），
     * 而它由 {@code Mob} 构造期调用的 {@code registerGoals()}（{@code Mob.java:152}）执行
     * ⇒ 这个副作用<b>只发生一次，打在最初那个实例上</b>。
     * 若关飞行时换成新建的实例，{@code canFloat} 会退回 {@code NodeEvaluator} 的默认
     * {@code false} 且**永远不会自己恢复**，入水行为随之改变：
     * {@code Mob#jumpInLiquidInternal} 会从 {@code +0.04 × SWIM_SPEED} 变成 {@code +0.3}
     * （约 7.5 倍，表现为在水里弹跳），地面寻路对水的 {@code PathType} 与
     * {@code GroundPathNavigation#getSurfaceY} 也跟着变。
     * <p>
     * 存原实例还顺带保住了任何别的既有状态。原版同一手法：
     * {@code Drowned.java:58-59} 持有 {@code waterNavigation} / {@code groundNavigation} 两个字段。
     * <p>
     * <b>⚠️ 但移动控制 {@code MoveControl} 刻意**不**这样暂存</b>（2026-09-27 修实机 bug 时改的）：
     * 它身上没有任何值得保留的状态，而"暂存再换回"可能让它带着一个**过期的 {@code MOVE_TO}**
     * 复活 ⇒ 详见 {@link #disableFlight()}。
     */
    @Nullable
    private PathNavigation groundNavigation;

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
     * 同步数据表。本类只加一项 {@link #DATA_STATE}，默认 {@link NpcState#IDLE}
     * —— <b>"状态默认是待机站桩"这条需求在数据层就体现为这个默认值</b>。
     */
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_STATE, NpcState.IDLE.id());

        // 表情的默认值是空串 = 没有表情。它不落盘，但**必须**在这里定义：
        // 同步数据表是客户端读表情的唯一来源（见 DATA_EMOTE 的注释）。
        builder.define(DATA_EMOTE, "");
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
        // 路线 goal 排在攻击 goal（3）**之后**：它只占 MOVE、不占 LOOK ⇒
        // 攻击中会被 lockedFlags 挡死（= "攻击期间暂停路线"，用户裁定 D7），
        // 而与 LookAtPlayerGoal（占 LOOK）无交集 ⇒ 从不挡"边走边看玩家"。
        this.goalSelector.addGoal(4, new NpcRouteGoal(this));
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
                this.stopAttacking();
            }
        }

        this.tickMoveCommand();
    }

    // ===================== 外部驱动 API（只在服务端生效）=====================

    /**
     * 移动到目标点 —— <b>唯一的移动入口</b>（旧的 {@code walkTo} 与 {@code flyTo} 已合并）。
     * <p>
     * <b>"怎么走"由当前状态决定</b>，调用方不需要知道也不该判断：
     * {@link NpcState#FLYING} ⇒ 空中飞；其它移动模式 ⇒ 地面走。这不需要特判 ——
     * 两者共用同一套"登记目标 + 续路"的机制，导航本身就是按状态换过的。
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
     * 2026-09-29：这里原本还有一条"处于姿态时先隐式退出到 IDLE"的约束（姿态会被移动指令挤出）。
     * 姿态已迁入表情系统、不再是状态（见 {@link NpcState} 的类注释），而<b>表情不影响移动</b>
     * ⇒ 本方法现在只登记目标，<b>不做任何状态切换</b>。
     * <p>
     * ⚠️ 它会**直接覆盖当前移动目标**，不排队、不报错。要取消就用 {@link #stopMoving()}。
     */
    public void moveTo(Vec3 pos) {
        if (this.level().isClientSide()) {
            return;
        }
        // 2026-09-29（用户裁定）：**任何 move 指令都清表情**。
        // 理由：表情会整层盖住状态动画，不清的话玩家分不清"指令到底生效没有"。
        // ⚠️ 这一句**必须在下面的幂等判断之前** —— 于是"已是 idle 再下发 state idle"
        // 同样会清表情（这正是用户要的语义）。
        // 代价（已确认接受）：表情不再能跨状态存在，"坐着飞"这类组合不再可能。
        this.clearEmote();
        this.setMoveTarget(pos);
    }

    /**
     * 停止移动（{@code move} 的反面）—— <b>只停寻路，不碰状态、不碰攻击</b>。
     * <p>
     * 于是"地面停下就是站桩待机、空中停下就是原地悬停"是**自然结果**：
     * 状态没变、只是没有移动目标了（飞行态的悬停本来就等于"飞行中 + 没有移动目标"，
     * 见 {@link #switchState}）。这里**不需要任何特判** —— 这也是它和 {@code reset} 的区别：
     * {@code reset} 会把状态也打回待机。
     */
    public void stopMoving() {
        if (this.level().isClientSide()) {
            return;
        }
        this.clearMotionCommands();
    }

    /**
     * 命令它去攻击某个目标。传 {@code null} 取消攻击（等价于 {@link #stopAttacking()}）。
     * <p>
     * ⚠️ <b>它会先取消当前移动指令</b>（"追着打"与"去某点"是两件事）。
     * <p>
     * 2026-09-29：原本这里还有"处于姿态时先隐式退出到 IDLE"这一条，已随姿态机制删除
     * （姿态迁入表情系统，且<b>表情不影响移动与攻击</b>）。
     */
    public void attack(@Nullable LivingEntity target) {
        if (this.level().isClientSide()) {
            return;
        }
        // 2026-09-29（用户裁定）：**任何 attack 指令都清表情**。
        // 理由：表情会整层盖住状态动画，不清的话玩家分不清"指令到底生效没有"。
        // ⚠️ 这一句**必须在下面的幂等判断之前** —— 于是"已是 idle 再下发 state idle"
        // 同样会清表情（这正是用户要的语义）。
        // 代价（已确认接受）：表情不再能跨状态存在，"坐着飞"这类组合不再可能。
        this.clearEmote();
        this.clearMotionCommands();
        this.attackCommandActive = target != null;
        this.setTarget(target);
    }

    /**
     * 停止攻击（{@code attack} 的反面）—— <b>只清攻击，不碰状态、不碰移动</b>。
     * <p>
     * 用户口径：地面攻击后停下就回到站桩待机、空中攻击后停下就回到悬停 ——
     * 同样是"状态没变、只是动作没了"的自然结果。
     * <p>
     * ⚠️ 它**不停寻路**：攻击轴与移动轴是**正交**的。
     * 旧的 {@code clearAttackCommand()} 里那句 {@code getNavigation().stop()} 已按新契约删除。
     */
    public void stopAttacking() {
        if (this.level().isClientSide()) {
            return;
        }
        this.attackCommandActive = false;
        this.setTarget(null);
    }

    // ===================== 攻击表现：挂在**原版挥砍事件**上的两个钩子 =====================

    /**
     * 每次挥砍都会被调用 —— 本模组的「攻击动画」与「攻击清表情」都挂在这**一个原版事件**上。
     *
     * <h4>为什么是它（而不是自己造一个信号）</h4>
     * <ol>
     *   <li><b>它是原版唯一的挥砍事件</b>：{@code MeleeAttackGoal.java:150-155} 的
     *       {@code checkAndPerformAttack} 里，{@code this.mob.swing(MAIN_HAND)}（{@code :153}）
     *       紧跟着 {@code this.mob.doHurtTarget(target)}（{@code :154}）
     *       ⇒ <b>与伤害结算同帧</b>（用户裁定的"挥砍同帧"）。</li>
     *   <li><b>它自带边沿判定</b>：{@code LivingEntity.java:1864} 的
     *       {@code if (!this.swinging || this.swingTime >= this.getCurrentSwingDuration() / 2 || this.swingTime < 0)}
     *       ⇒ <b>每次调用就是"一次新挥砍"</b>，我们不需要自己跟踪上升沿。</li>
     *   <li><b>它两侧都会走到</b>：服务端由 goal 调用（{@code :153}）；客户端由
     *       {@code ClientPacketListener#handleAnimate} 调用，而那个包由
     *       {@code LivingEntity.java:1868-1875} 在服务端广播
     *       ⇒ <b>零新增同步字段、零新增网络包</b>。</li>
     * </ol>
     * 于是"服务端清表情、客户端触发攻击动画"挂在**同一次事件**上，两侧天然一致。
     */
    @Override
    public void swing(InteractionHand hand) {
        super.swing(hand);
        if (this.level().isClientSide()) {
            // 客户端：点播攻击动画。**先预检** —— 资产里没有这条动画就不要触发：
            // 否则 main 会让位、而这里没有能播的东西 ⇒ 该帧没有任何控制器写骨骼
            // ⇒ GeckoLib 的复位分支把全部骨骼吸附到初始快照（详见 EmoteAnimationLookup 的注释）。
            if (EmoteAnimationLookup.find(this, this.attackAnimationName()) != null) {
                this.cache.getManagerForId(this.getId()).tryTriggerAnimation(ATTACK_TRIGGER);
            }
            return;
        }
        // 服务端：**攻击行为清掉当前表情**（用户裁定）—— 与 `attack` 指令的口径一致。
        // 这里的 clearEmote() 自带 isClientSide 守门，故上面客户端分支直接 return 更清楚。
        this.clearEmote();
    }

    // ========== 表情（一层动画覆盖：盖住状态动画；`state`/`move`/`attack`/`reset` 都会清掉它）==========

    /**
     * 当前表情动画名，<b>空串表示没有表情</b>。双端可读。
     * <p>
     * 有值时，{@link #registerControllers} 里的 {@code emote} 控制器会接管全身动画，
     * {@code main} 控制器主动让位 —— 这就是"表情覆盖状态动画"的实现方式。
     * <p>
     * ⚠️ 表情<b>只覆盖动画</b>，不干预移动与攻击（设计 D2）：所以"边走边播 sit"会呈现
     * "坐着滑行"，这是明确接受的代价。
     */
    public String emote() {
        return this.entityData.get(DATA_EMOTE);
    }

    /**
     * 设置表情。<b>只在服务端生效</b>（与 {@link #setState} 同构）。
     * <p>
     * ⚠️ <b>名字不做任何校验</b>：动画名是<b>客户端</b>的资产数据，服务端无从知道。
     * 客户端会在播放前预检、查不到就静默不播，因此拼错既不会报错也不会静默变成别的动画，
     * 但也不会有任何反馈 —— 这是设计 D4 明确接受的代价。
     */
    public void setEmote(String animationName) {
        if (this.level().isClientSide()) {
            return;
        }
        // ⚠️ 必须走三参 set（force = true）：两参版在"值没变"时会短路、**不发同步包**
        // （SynchedEntityData.java:81：`if (force || ObjectUtils.notEqual(value, dataitem.getValue()))`）。
        // 而"再播一次同一条一次性表情"（play attack → 播完 → 再 play attack，
        // 甚至 play attack → play stop → play attack）**正是值没变**的情形 ——
        // 不 force 的话客户端收不到通知，表现是**指令报成功、却什么都没发生**。
        this.entityData.set(DATA_EMOTE, animationName == null ? "" : animationName, true);
    }

    /**
     * 同步数据变化时，把表情的**客户端瞬态状态**清成"从没见过这条表情"。
     * <p>
     * 这是"再播一次同名表情"能生效的另一半：服务端用 {@code force = true} 强制发包后，
     * 客户端会**无条件**收到通知（{@code SynchedEntityData.assignValues} 对每个条目都调本方法、
     * 不比较新旧值）⇒ 下一个谓词帧就会重新走一遍"换名"分支、重新钉计时起点。
     * <p>
     * 在服务端被调用也无害 —— 这三个字段只在客户端的谓词里被读。
     */
    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (DATA_EMOTE.equals(accessor)) {
            this.emoteSeen = "";
            this.emoteDone = false;
            this.emoteStartTick = 0;
        }
    }

    /** 清除表情（等价于 {@code setEmote("")}）。{@code play … stop} 与 {@code reset} 都走它。 */
    public void clearEmote() {
        this.setEmote("");
    }

    // ===================== 状态机 =====================

    /**
     * 当前状态。<b>双端都可读</b> —— 值来自同步数据 {@link #DATA_STATE}，不是服务端独有字段。
     * 这一点是刻意的：{@link #registerControllers} 的动画谓词在<b>客户端</b>读它。
     */
    public NpcState state() {
        return NpcState.byId(this.entityData.get(DATA_STATE));
    }

    /** 是否处于飞行模式。便捷方法，等价于 {@code state() == NpcState.FLYING}。 */
    public boolean isFlying() {
        return this.state() == NpcState.FLYING;
    }

    /**
     * 切换状态。<b>只在服务端生效</b>（换导航/移动控制是服务端的事，AI 也只在服务端跑）。
     * <p>
     * <b>幂等</b>：与当前状态相同时<b>不重建</b>那两个字段。
     * 这不是洁癖 —— 重复执行等于把正在执行的移动路径丢掉，
     * 症状是"连按两次同一个状态指令，NPC 定在半空不动"。
     * <p>
     * ⚠️ <b>但"离开飞行"这一路即使在幂等分支里也要先 {@code setNoGravity(false)}。</b>
     * 因为 {@code NoGravity} 是原版持久化的、也能被外部写入
     * （{@code /data merge entity <npc> {NoGravity:1b}}、第三方模组），
     * 而状态不保证与它同步 ⇒ <b>"状态说没在飞"不等于"重力已经恢复"</b>。
     * 若把 {@code setNoGravity(false)} 只放在 {@link #disableFlight()} 里，
     * 那么处于脱钩状态的 NPC 执行本方法会变成**静默的 no-op**，只有 {@code reset} 能救。
     * <p>
     * {@code final}：这是不变式 `noGravity ⟺ state == FLYING` 的唯一入口，子类不许改写它。
     * 子类要定制状态的**表现**（动画名）请覆写 {@link #stateAnimationName(NpcState)}。
     */
    public final void setState(NpcState next) {
        if (this.level().isClientSide()) {
            return;
        }
        // 2026-09-29（用户裁定）：**任何 state 指令都清表情**。
        // 理由：表情会整层盖住状态动画，不清的话玩家分不清"指令到底生效没有"。
        // ⚠️ 这一句**必须在下面的幂等判断之前** —— 于是"已是 idle 再下发 state idle"
        // 同样会清表情（这正是用户要的语义）。
        // 代价（已确认接受）：表情不再能跨状态存在，"坐着飞"这类组合不再可能。
        this.clearEmote();
        if (next != NpcState.FLYING) {
            this.setNoGravity(false);      // 先闭合不变式，再做幂等判断
        }
        if (next == this.state()) {
            return;
        }
        this.entityData.set(DATA_STATE, next.id());
        this.switchState(next);
    }

    /**
     * 状态切换的**唯一**副作用集中点 —— 所有"进入某状态要做什么 / 离开某状态要收回什么"
     * 都写在这里，别处不许再散落一份。
     * <p>
     * ⚠️ <b>第一步无条件收掉上一个状态的副作用，且不留任何提前 return。</b>
     * 这是飞行那一轮的实机教训（当时的 I-3）：只要存在一条提前返回路径，
     * 就可能留下"状态说没飞、实体却浮着"的不一致 —— 那是最难查的一类 bug。
     * <p>
     * 当前只有 {@link NpcState#FLYING} 有副作用（换导航/移动控制 + 关重力）；
     * 其余状态只要求"不处于飞行"。**将来某个状态若需要更多副作用，加在这里。**
     * <p>
     * 2026-09-29：这里原本还有"进入姿态就取消移动与攻击指令"一条。姿态迁入表情系统后，
     * 剩下的两个状态都是移动模式 ⇒ <b>切换状态不再取消任何指令</b>
     * （移动指令保留并按新模式重新执行：同一条 {@code move} 在地面是"走"、在飞行是"飞"），
     * <b>表情会被状态切换清掉</b>（2026-09-29 用户裁定：`state`/`move`/`attack`/`reset` 都清表情）。
     */
    private void switchState(NpcState next) {
        // ① 无条件收掉"飞行"的副作用。判据是"暂存里还留着原导航"，
        //    而不是读 state()（此刻 entityData 已经是新状态了）。
        if (this.groundNavigation != null) {
            this.disableFlight();
        }

        // ② 装上新状态的副作用
        if (next == NpcState.FLYING) {
            this.enableFlight();
        }
        // 2026-09-29：这里原本还有一个"进入姿态就作废移动与攻击指令"的分支。
        // 姿态已迁入表情系统、不再是状态；而剩下的两态都是移动模式
        // ⇒ 切换状态**不再取消任何指令**（表情也不受状态切换影响）。
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
     *
     * <h4>⚠️ 子类注册 goal 的两条红线（因为本类会在运行期换导航）</h4>
     * <ol>
     *   <li><b>不得把 {@code getNavigation()} 强转成 {@code GroundPathNavigation}</b> ——
     *       飞行时它是 {@code FlyingPathNavigation}，会 {@code ClassCastException} 崩服。
     *       原版踩这个坑的 goal 不少：{@code RestrictSunGoal.java:22,28}、
     *       {@code DoorInteractGoal.java:57}、{@code MoveThroughVillageGoal.java:91}。
     *       判类型请用 {@code GoalUtils}（{@code GoalUtils.java:13} 用的是 {@code instanceof}）。</li>
     *   <li><b>不得在构造期把 {@code getNavigation()} 存进字段</b> ——
     *       换字段之后那些 goal 操作的是**孤儿对象**：不崩，但"以为在走路其实没人 tick"。
     *       原版先例：{@code AvoidEntityGoal.java:24,56}、{@code FollowOwnerGoal.java:17,26}、
     *       {@code FollowMobGoal.java:23,36}。</li>
     * </ol>
     * 本类自己注册的三个 goal（{@code FloatGoal} / {@link NpcAttackGoal} /
     * {@code LookAtPlayerGoal}）都不违反这两条。
     */
    private void enableFlight() {
        // 先把原导航暂存起来，关飞行时原样换回（理由见 groundNavigation 的注释）。
        // 移动控制**不暂存** —— 它没有值得保留的状态，且换回一个可能停在旧 MOVE_TO 上的实例
        // 会带来"状态切走了它还在往旧目标挪"的问题，见 disableFlight()。
        this.groundNavigation = this.navigation;

        this.moveControl = new FlyingMoveControl(this, FLY_MAX_TURN, true);
        final FlyingPathNavigation flying = new FlyingPathNavigation(this, this.level());
        // 三个开关照原版飞生物（Bee.java:565-567 / Allay.java:159-161）：
        // 站桩 NPC 不该开门、不该"浮在液体上"，但要能穿门（否则室内飞不动）。
        flying.setCanOpenDoors(false);
        flying.setCanFloat(false);
        flying.setCanPassDoors(true);
        this.navigation = flying;

        this.setNoGravity(true);
        // ⚠️ **刻意不设任何移动目标** —— "开启飞行"不等于"起飞"。
        // 用户 2026-09-27 改定的口径：状态切到 FLYING 就在**当前位置原地悬停**；
        // 要上天请再下一条 `move <空中坐标>`。
        // 这顺带从根上消灭了一整类问题：只要存在向上推力，在 noGravity 下
        // 重力恒为 0（{@code Entity.java:1173-1175} 的 {@code getGravity()}，且该方法
        // {@code final}、覆写不了）、竖直方向只剩 ×0.98 的衰减
        // （{@code LivingEntity.java:2341}）⇒ 推力一停要滑行约 50 倍速度的距离；
        // 而 {@code FlyingMoveControl.java:46} 对 Y 是 bang-bang（无比例项）、不会自行收力。
        // **不上升就没有这段滑行。**
    }

    /**
     * 切回地面模式。
     * <p>
     * ⚠️ <b>本方法刻意没有任何提前 return，保证 {@code setNoGravity(false)} 一定执行到。</b>
     * "浮空、但没人知道自己在飞"是最难查的一类状态：它不落地，又不接受地面移动控制。
     * <p>
     * <b>导航换回"原实例"、移动控制则用"新实例"</b>，两者不同是刻意的：
     * <ul>
     *   <li><b>导航</b>必须有原实例 —— {@code FloatGoal} 构造器打的 {@code setCanFloat(true)}
     *       只作用于最初那个对象（见 {@link #groundNavigation}）；</li>
     *   <li><b>移动控制</b>没有值得保留的状态（{@code operation} / {@code wantedX,Y,Z} /
     *       {@code speedModifier} 全是瞬态），而暂存实例**可能停在旧的一次 {@code MOVE_TO} 上**
     *       ⇒ 换回后它会朝那个过期的 {@code wantedPosition} 走一拍，必要时还会
     *       {@code getJumpControl().jump()}（{@code MoveControl.java:109}），
     *       表现为"状态已经切走了，它却还在往旧目标挪"。新实例从 {@code operation = WAIT} 起步，
     *       没有这类残留。</li>
     * </ul>
     * <p>
     * 顺序上"先停旧路径、再换字段"是有意的：否则旧 {@code FlyingPathNavigation} 还持有
     * 下一个路点，而 {@code moveControl} 已经换成地面版，会出现一拍
     * "地面移动控制执行空中路点"的错配。
     * 同理，换回的那个地面导航也要 {@code stop()} 一次 ——
     * 它在被换下时可能还留着起飞前的旧路径，不清掉的话 NPC 一落地就接着走那条旧路。
     * <p>
     * 另外 {@link #clearMotionCommands()} 现在会走 {@link Mob#stopInPlace()}，
     * 那一句清掉的是**实体的输入字段**（{@code xxa}/{@code yya}/{@code speed}），
     * 与这里换对象是两件事、缺一不可 —— 详见该方法的 javadoc。
     */
    private void disableFlight() {
        this.clearMotionCommands();      // moveTarget=null + 清冷却 + stopInPlace（含清输入字段）
        this.setNoGravity(false);

        this.moveControl = new MoveControl(this);

        if (this.groundNavigation != null) {
            this.groundNavigation.stop();     // 清掉它被换下时残留的旧路径
            this.navigation = this.groundNavigation;
        } else {
            // 理论上不可达（enableFlight 总会暂存），留作防御
            this.navigation = new GroundPathNavigation(this, this.level());
        }
        this.groundNavigation = null;
    }

    /**
     * 恢复到"刚被召唤出来的状态"：{@link NpcState#IDLE}、无移动、无攻击、地面站桩。
     * <p>
     * 它也是"路线"的终点：{@code reset} 会一并清掉路线（用户裁定 D6，与 {@code stop} 一致）。
     * <p>
     * 它是 {@code state … idle} + {@code stop} + {@code attack … stop} 三条的合并 ——
     * 存在的意义就是"一条命令回到默认"，不必记三条。
     * <p>
     * <b>末尾那句 {@code setNoGravity(false)} 是无条件执行的，不能写成 {@code if (isFlying())}。</b>
     * 理由见 {@link #setState}：{@code NoGravity} 是原版持久化的、也能被外部写入，
     * 而状态不保证与它同步 ⇒ 脱钩状态下加判断就正好修不回来。
     * （{@link #setState} 自己也会清一次，这里是**独立的第二道保险**，刻意不省。）
     */
    public void resetToDefault() {
        if (this.level().isClientSide()) {
            return;
        }
        this.stopMoving();
        // 与 stop 指令一致：reset 也要清路线（D6）。⚠️ 清路线放在这里与命令层，
        // **不放进 stopMoving()** —— 否则 attack() 的 clearMotionCommands() 会误伤路线（D7）。
        this.clearRoute();
        this.stopAttacking();
        // 2026-09-29：补上遗漏的一步 —— reset 也要清表情。
        // （原先漏了；现由探针 S2 的 B12 守着，防止再漏。）
        this.clearEmote();
        this.setState(NpcState.IDLE);
        this.getNavigation().stop();
        this.setNoGravity(false);
    }

    // ===================== 路线（设计见 docs/plans/2026-09-30-npc-route-system-design.md）=====================

    /** 当前路线名；没有路线时为空。 */
    @Nullable
    public ResourceLocation routeName() {
        return this.routeName;
    }

    /**
     * 当前路线的解析结果；**没有路线、或数据文件不存在时返回 {@code null}**。
     * <p>
     * 调用方（{@code NpcRouteGoal}）必须按 fail-closed 处理 {@code null} ⇒ **挂起**，
     * 而不是当成"已完成"（用户裁定 D8）。这也是"路线文件被改名/删除"时的唯一表现。
     */
    @Nullable
    public NpcRoute route() {
        return NpcRouteLoader.INSTANCE.get(this.routeName);
    }

    /** 当前路点下标；等于路点数即"已抵达终点"。 */
    public int routeIndex() {
        return this.routeIndex;
    }

    /** 已抵达终点？（路线缺失时返回 false —— 缺失走"挂起"那条路，不是"已完成"。） */
    public boolean routeFinished() {
        NpcRoute route = this.route();
        return route != null && this.routeIndex >= route.size();
    }

    /** 当前路点；没有路线 / 已抵达 / 数据缺失时为 {@code null}。 */
    @Nullable
    public Vec3 routeWaypoint() {
        NpcRoute route = this.route();
        return route == null ? null : route.waypoint(this.routeIndex);
    }

    /**
     * 指派路线。**下标一律归零**，即使指派的是同一条 —— 用户裁定：
     * 重新指派是一个明确的动作，就该重走一遍（设计 D5）。
     * <p>
     * ⚠️ 这里**不做**"路线是否存在"的判断：那是**命令层**的职责（两条命令都先查、未知即拒绝，
     * 与设计 D8 的"拒绝而不是静默写入"一致）。本方法只负责写状态 —— 所以它不校验、也不打日志。
     * 真正的"路线数据缺失"由 {@code NpcRouteGoal#canUse} 在运行期按名字报一次 WARN（那条路径
     * 还覆盖了**旧存档恢复**与 **{@code /reload} 之后文件消失**，它们都不经过命令层）。
     */
    public void setRoute(@Nullable ResourceLocation id) {
        if (this.level().isClientSide()) {
            return;
        }
        this.routeName = id;
        this.routeIndex = 0;
    }

    /**
     * 清掉路线（{@code stop} 与 {@code reset} 走这里 —— 用户裁定 D6）。
     * <p>
     * ⚠️ <b>刻意不放进 {@link #stopMoving()}</b>：{@code attack()} 会调 {@code clearMotionCommands()}，
     * 而"攻击不该毁掉路线"是刻意的（D7）。分工：{@code stopMoving} 只管移动，路线由命令层与
     * {@link #resetToDefault()} 清。
     */
    public void clearRoute() {
        if (this.level().isClientSide()) {
            return;
        }
        this.routeName = null;
        this.routeIndex = 0;
    }

    /** 推进到下一个路点；到终点后下标停在 {@code size}（"已完成"由下标越界表达，不另设标记）。 */
    public void advanceRouteIndex() {
        NpcRoute route = this.route();
        if (route != null && this.routeIndex < route.size()) {
            ++this.routeIndex;
        }
    }

    /** 登记移动目标，并让下一次 {@code customServerAiStep} 立刻寻路。 */
    private void setMoveTarget(Vec3 pos) {
        this.moveTarget = pos;
        this.moveRepathCooldown = 0;
        this.moveBestDistSqr = Double.MAX_VALUE;
        this.moveNoProgressCount = 0;
    }

    /**
     * 当前的移动目标；没有目标时为 {@code null}。
     * <p>
     * 存在的原因：{@link com.zonlong.beloong.entity.ai.NpcRouteGoal} 必须能判断
     * "<b>目标还在不在</b>" —— 因为<b>好几个地方</b>都会清掉它：到位判定、
     * {@link #clearMotionCommands()}（{@code stop}/{@code attack}）、以及
     * {@link #tickMoveCommand()} 里那条"连续 {@code MOVE_MAX_NO_PROGRESS} 次续路都更不靠近 ⇒ 放弃"
     * 的有界失败。<b>2026-09-30 实机 bug 就是最后那条造成的</b>（见 {@code NpcRouteGoal} 的类注释）。
     */
    @Nullable
    public Vec3 moveTarget() {
        return this.moveTarget;
    }

    /**
     * 地面的**到位判定半径**（格）—— 供路线 goal 给自己的抵达半径设下限。
     * <p>
     * 若路线的 {@code arrival_radius} 比它更小，就会出现：移动层判"已到位"并清掉目标，
     * 而 goal 判"还没到"又立刻补发 ⇒ **两者每 tick 打架**（并且每次都清一次表情）。
     * 故 goal 取 {@code max(路线半径, 本值 + 0.5)}。暴露成方法而不是把常量改 public，
     * 是为了让"这是移动层的语义"这件事留在移动层这边。
     */
    public static double groundArriveDistance() {
        return MOVE_ARRIVE_DISTANCE;
    }

    // ===================== 多人：归属与可见性 =====================

    /** 私有分身的归属玩家；{@code null} = 公共锚点。 */
    @Nullable
    public UUID owner() {
        return this.owner;
    }

    /**
     * 设置归属。**只在服务端生效**（与 {@link #setRoute} 同构：客户端不持有这类权威状态）。
     * <p>
     * 传 {@code null} 即"变回公共锚点"。
     */
    public void setOwner(@Nullable UUID owner) {
        if (this.level().isClientSide()) {
            return;
        }
        this.owner = owner;
    }

    /** 是否是**私有分身**（有主）。 */
    public boolean isPrivate() {
        return this.owner != null;
    }

    /**
     * 原版钩子：<b>这个实体要不要发给该玩家</b>。
     * <p>
     * {@code ChunkMap.TrackedEntity.updatePlayer}（{@code ChunkMap.java:1327-1343}）每个追踪周期
     * 逐玩家调用它：返回 {@code false} ⇒ 对该玩家 {@code removePairing} 且永不 {@code addPairing}
     * ⇒ 该玩家客户端上**这个实体根本不存在**（无渲染、无碰撞箱、无法右键）。
     * 本类只做转发，判定写在可覆写的 {@link #visibleTo} 里。
     */
    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        return this.visibleTo(player);
    }

    /**
     * 该玩家能不能看见本实体。**纯函数：不写实体字段、不发包、不查实体。**
     * <p>
     * 判定顺序**刻意是"先归属、后数据"**：
     * <ul>
     *   <li><b>有主人</b>（私有分身）：先判"是不是我的" —— 不是 ⇒ 直接不可见。
     *       ⚠️ 这一步**不查剧情数据**：私有性是实体自身的状态，数据缺失/loader 没跑时
     *       绝不能退化成"对所有人可见"（那会把私有分身暴露给全服）。</li>
     *   <li>是我的 ⇒ 还要求我**已进入剧情区间**（已获得起点进度）。
     *       ⚠️ 这条是为了守住不变量"任一玩家眼里恰好一个"：进度被撤回
     *       （对账器重置 / 手动 {@code /advancement revoke} / 存档回档）而分身还没被删掉时，
     *       若这里仍返回 true，玩家会**同时**看到锚点与自己的分身。
     *       加上这条之后，那个窗口里玩家只看到锚点，分身随后由对账器删掉（计划 T6/T7）。</li>
     *   <li><b>无主人</b>（公共锚点）：本类型没有剧情声明 ⇒ 恒可见
     *       —— 地黄龙等既有 NPC 行为一字不变；有声明 ⇒ 对"尚未获得起点进度"的玩家可见。</li>
     * </ul>
     * 三条都**只依赖实体自身的归属 + 该玩家的进度** ⇒ O(1)，不需要任何实体搜索。
     * <p>
     * ⚠️ 不可在这里写缓存/发通知/查实体：它每追踪周期对范围内每个玩家都被调用。
     */
    protected boolean visibleTo(ServerPlayer player) {
        NpcStory story = NpcStoryLoader.INSTANCE.get(this.getType());
        if (this.owner != null) {
            if (!player.getUUID().equals(this.owner)) {
                return false;
            }
            // 剧情数据缺失时保守放行（只对主人可见，不会外泄）；数据正常时要求"已进入剧情区间"。
            return story == null || NpcDialogueStage.isEarned(player, story.startAdvancement());
        }
        if (story == null) {
            return true;
        }
        return !NpcDialogueStage.isEarned(player, story.startAdvancement());
    }

    /**
     * 状态落盘用的 NBT 键。
     * <p>
     * <b>带模组前缀是刻意的</b>：实体 NBT 是最容易跨模组撞名的地方
     * （本模组另两处先例风格还不统一：{@code TornadoEntity.java:376} 用 {@code "Life"}、
     * {@code DisasterPortalFrameEntity.java:47} 用 {@code "eye_id"}）。
     * 加前缀后零撞名风险，代价只是键名长一点。
     */
    private static final String STATE_NBT_KEY = "BeloongState";

    /**
     * 路线名（{@code beloong:xxx}）。**这是"NPC 身上只有一个路线"的结构性保证** ——
     * 只有一个键，所以不可能同时存在两条（用户裁定）。
     */
    private static final String ROUTE_NBT_KEY = "BeloongRoute";

    /** 当前走到第几个路点。**必须落盘**：否则重启后会倒着走回第 0 个点。 */
    private static final String ROUTE_INDEX_NBT_KEY = "BeloongRouteIndex";

    /**
     * 私有分身的**归属玩家**（UUID）。**没有这个键 = 公共锚点**。
     * <p>
     * 用原版的 {@code putUUID}/{@code hasUUID}/{@code getUUID} 而不是自己拆字符串：
     * UUID 是原版实体 NBT 的既有类型，拆字符串只会多一处解析失败的可能。
     * <p>
     * ⚠️ 只有**非空**才写：公共锚点的存档里不该出现这个键（旧存档读到的就是"空"
     * ⇒ 公共锚点 ⇒ 行为与引入本系统之前一致）。
     */
    private static final String OWNER_NBT_KEY = "BeloongOwner";

    /**
     * 状态落盘。
     * <p>
     * <b>写名字而不是 ordinal</b>：枚举顺序将来变了也不会把旧存档读错
     * （原版同做法：{@code Armadillo.java:251} 写的是 {@code getState().getSerializedName()}）。
     * <p>
     * 只落盘<b>状态</b>，**不落盘移动目标**（{@code moveTarget}）——
     * "去某点"是**指令**不是**状态**（与攻击目标同类），重登后原地待命即可。
     */
    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putString(STATE_NBT_KEY, this.state().getSerializedName());
        // 有路线才写这两个键：没路线的 NPC 存档里不该出现它们（旧存档读到的就是"空" ⇒ 无路线 ✓）。
        if (this.routeName != null) {
            compound.putString(ROUTE_NBT_KEY, this.routeName.toString());
            compound.putInt(ROUTE_INDEX_NBT_KEY, this.routeIndex);
        }
        // 同理：只有私有分身才写归属键（公共锚点的存档里不该出现它）。
        if (this.owner != null) {
            compound.putUUID(OWNER_NBT_KEY, this.owner);
        }
    }

    /**
     * 状态读盘 —— <b>必须经 {@link #setState} 恢复，不能直接写 {@code entityData}</b>。
     * <p>
     * 因为"进入某个状态"是有**副作用**的（飞行要换导航与移动控制并关重力，见
     * {@link #switchState}）；只写同步数据的话，重登后会得到"状态说在飞、实际却是地面导航
     * 且重力照旧"的分裂实体 —— 正是飞行那一轮 I-3 那类最难查的不一致。
     * <p>
     * <b>名字对不上时回落 {@link NpcState#IDLE}</b>（{@link NpcState#byNameLenient}）：
     * 旧存档、降级、玩家手改存档都会出现对不上的名字，崩掉等于坏档。
     * ⚠️ 这与**指令参数**的处理是**刻意相反**的（那边必须严格报错，
     * 见 {@link NpcState#byNameStrict}）—— 两种场合的正确行为相反，不许合并。
     * <p>
     * <b>向后兼容</b>：旧存档没有这个键 ⇒ {@code getString} 返回空串 ⇒ 回落 {@code IDLE}
     * ⇒ 与"还没有状态系统之前"的行为一致；同时也会把旧存档里可能残留的
     * {@code NoGravity} 清掉（{@link #setState} 对非飞行状态会清）。
     * <p>
     * ⚠️ 读取**只发生在这里** ⇒ 运行期用 {@code /data merge} 改这个键**不会即时生效**，
     * 要重登一次。这与 {@code /data merge} 改 {@code NoGravity} 立刻生效不同，别混淆。
     */
    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        this.setState(NpcState.byNameLenient(compound.getString(STATE_NBT_KEY)));
        // 宽容解析（对应 NpcState.byNameLenient 的口径）：解析不出来的名字当"没有路线"，绝不因坏档崩掉。
        String route = compound.getString(ROUTE_NBT_KEY);
        this.routeName = route.isEmpty() ? null : ResourceLocation.tryParse(route);
        this.routeIndex = Math.max(0, compound.getInt(ROUTE_INDEX_NBT_KEY));
        // 向后兼容：旧存档没有这个键 ⇒ hasUUID 为 false ⇒ owner 保持 null ⇒ 公共锚点。
        this.owner = compound.hasUUID(OWNER_NBT_KEY) ? compound.getUUID(OWNER_NBT_KEY) : null;
        // 下标 clamp：路线数据是**热的**（/reload 可改），两次加载之间它可能变短。
        // 此刻若该路线已加载 ⇒ 直接 clamp 并提示；尚未加载 ⇒ 原样保留，等它可用时
        // routeFinished() 会按"越界即已完成"处理（坏数据不崩）。
        NpcRoute loaded = this.route();
        if (loaded != null && this.routeIndex > loaded.size()) {
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] npc route index {} is out of range for '{}' ({} waypoint(s)) — clamped",
                    this.routeIndex, this.routeName, loaded.size());
            this.routeIndex = loaded.size();
        }
    }

    /**
     * 清掉"位移类"指令，不碰攻击指令。
     * <p>
     * 不再有冲刺复位：奔跑已取消（用户裁定"不要额外的奔跑状态"），
     * 速度差异改由原版进度/效果经移速属性体现。
     *
     * <h4>⚠️ 为什么必须用 {@link Mob#stopInPlace()} 而不是裸的 {@code getNavigation().stop()}</h4>
     * <b>这是 2026-09-27 一个实机 bug 的修复，不要改回去。</b>
     * 现象：NPC 飞行中收到 {@code move}、正在寻路时执行 {@code state … idle}，
     * 落地后**站在地上一一直跳**，而且**退出重进就恢复正常**。
     * <p>
     * 根因是实体的**输入字段**没人清 —— {@code FlyingMoveControl} 每 tick 会写竖直输入
     * {@code Mob#yya}（{@code FlyingMoveControl.java:41,46}），而它是**粘性字段**：
     * <ul>
     *   <li>{@code MoveControl} 的 WAIT 分支**只清 {@code zza}，从不清 {@code yya}**
     *       （{@code MoveControl.java:117-118} 的 {@code } else { this.mob.setZza(0.0F); }}）；</li>
     *   <li>每 tick 衰减的只有 {@code xxa} 与 {@code zza}，**{@code yya} 不在其中**
     *       （{@code LivingEntity.java:2806-2807}）；</li>
     *   <li>全仓唯一清 {@code yya} 的地方，除了飞行那个控制对象**自己的** WAIT 分支
     *       （{@code FlyingMoveControl.java:53}，而我们正要把它丢掉），就只有
     *       {@link Mob#stopInPlace()}（{@code Mob.java:562-567}）。</li>
     * </ul>
     * ⇒ 不清理的话，实体每 tick 都被加一个向上的输入 ⇒ 离地、被重力拉回、再推上去 ⇒ **一直跳**。
     * 而 {@code yya} 与 {@code speed} 都是**瞬态字段**，所以重登即恢复 —— 极易被误判成
     * "存档坏了"或"某个 goal 有问题"。
     * <p>
     * 顺带把 {@code speed} 也归零有两个理由：地面分支的加速度系数是
     * {@code getFrictionInfluencedSpeed} = {@code getSpeed() × 0.216/f³}
     * （{@code LivingEntity.java:2428-2429}），而 {@code FlyingMoveControl} 把它写过
     * （{@code setSpeed}；{@code Mob.java:557-560} 会连带写 {@code zza}）⇒ 不归零会**放大**上面那个推力；
     * 另外 {@code stopInPlace()} 的名字与语义本就与"停止移动"完全一致。
     * <p>
     * 放在这里而不是只放在 {@link #disableFlight()} 里：本方法是**所有**"取消移动"路径的
     * 唯一出口（{@code stop} / {@code attack} / 离开飞行），修一处即全覆盖。
     */
    private void clearMotionCommands() {
        this.moveTarget = null;
        this.moveRepathCooldown = 0;
        this.stopInPlace();      // 原版：停导航 + 清 xxa / yya / speed(连带 zza)
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
     * （{@code MeleeAttackGoal.java:94}）；它现在只造成最多 1 tick 的停顿 ——
     * 因为 {@code nav.stop()} 会让 {@code isDone()} 立刻为真，下一 tick 本方法就把路续回来。
     * <p>
     * （新契约下 {@code stopAttacking()} **不再**停寻路：攻击轴与移动轴是正交的。）
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

    // 注：原来还有一个供 NpcAttackGoal 调用的 clearAttackCommand()，已删除 ——
    // 它与 stopAttacking() 做的是同一件事，而后者才是"攻击轴的反面"这个对外语义的正名。
    // NpcAttackGoal 现在直接调 stopAttacking()。

    // ===================== GeckoLib =====================

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // ===================== 表情的客户端瞬态状态（不同步、不落盘）=====================
    // 这三个字段只被 emote 控制器在**客户端**读写，因此不进同步数据、也不进存档。
    // 服务端只知道 DATA_EMOTE（那个名字），"播完了没有"只有客户端能判断
    // —— 因为"这支动画有多长、是不是循环动画"都是**资产数据**（见 EmoteAnimationLookup）。

    /**
     * 攻击动画的**点播键** —— 只是 GeckoLib 里查表用的键，与动画**名字**无关
     * （动画名由 {@link #attackAnimationName()} 给，默认 {@code "attack"}）。
     */
    private static final String ATTACK_TRIGGER = "swing";

    /** 上一次看到的 {@link #emote()} 值，用来检测"换了一条表情"。 */
    private String emoteSeen = "";

    /** 一次性表情是否已播完。置真后 {@code main} 控制器重新接管，表现即"回落到状态动画"。 */
    private boolean emoteDone;

    /** 一次性表情的**计时起点**（单位 tick，取自 {@link #tickCount}）。 */
    private int emoteStartTick;

    /**
     * 主控制器：**按状态**选动画 —— 非待机状态播该状态那一条；待机时才走 idle/walk/run 三选一。
     * <p>
     * <b>{@code RawAnimation} 刻意在这里构建而不在构造函数里</b>：动画名来自**可覆写**方法
     * （{@link #idleAnimationName()} / {@link #stateAnimationName(NpcState)}），
     * 构造函数里调虚方法会踩"子类字段尚未初始化"的经典坑。
     * 而 {@code registerControllers} 由 {@code AnimatableManager} **惰性**调用，那时子类早已构造完。
     * <p>
     * <b>为什么非待机状态要"整体替换"而不是"叠一层"</b>：横向飞行会让 {@code walkAnimation} 非零
     * （{@code LivingEntity.java:2373-2376}：{@code includeHeight} 只决定是否把 Y 位移算进去，
     * X/Z 照算）⇒ GeckoLib 的 {@code isMoving()} 会为真 ⇒ 不拦住的话腿会在空中走路。
     * 改成状态分支后，这一条**不再是一条特判**，而是"非待机状态不走那一套"的自然结果。
     * <p>
     * <b>「跑」不是一种状态，而是「有额外移速加成」的表现</b>（用户裁定）：
     * 判据是 {@code getAttributeValue(MOVEMENT_SPEED) > getAttributeBaseValue(MOVEMENT_SPEED)}
     * —— 迅捷效果就是往这个属性挂修饰符（{@code MobEffect#addAttributeModifiers:166-174}），
     * 属性值又会同步给客户端（{@code ClientboundUpdateAttributesPacket}），
     * 因此**效果一生效自动切 run、一结束自动回 walk**，不需要状态机、也不需要网络包。
     * 口径是"**任何**额外移动加成"（迅捷 / 信标 / 食物 / 其它模组的 modifier 都算），不只迅捷。
     * <p>
     * 移动判据用 GeckoLib 的 {@code isMoving()}（横向速度 ≥ 0.015/tick 且 {@code walkAnimation} 在动）。
     * 它要求实体**真的在位移** —— 所以"原地转身/扭头"不算移动，仍播 idle。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        final RawAnimation idle = RawAnimation.begin().thenLoop(this.idleAnimationName());
        final RawAnimation walk = RawAnimation.begin().thenLoop(this.walkAnimationName());
        final RawAnimation run = RawAnimation.begin().thenLoop(this.runAnimationName());
        // 非待机状态各建一条。用 EnumMap 而不是"每个状态一个字段"：
        // 将来加状态时**这里一行都不用改**（见 NpcState 的类注释）。
        final EnumMap<NpcState, RawAnimation> posedAnimations = new EnumMap<>(NpcState.class);
        for (NpcState candidate : NpcState.values()) {
            if (candidate != NpcState.IDLE) {
                posedAnimations.put(candidate, RawAnimation.begin().thenLoop(this.stateAnimationName(candidate)));
            }
        }

        // ⚠️⚠️ 下面两个 add 的**顺序是承重结构，不要调换** ⚠️⚠️
        // GeckoLib 按**注册顺序**遍历控制器（AnimationProcessor.java:80；保序表见
        // AnimatableManager.java:202-208），而每个控制器对骨骼是**直接赋值**、不是累加
        // （AnimationProcessor.java:107-131）⇒ 同一骨骼上**最后写入者覆盖前者**。
        // "表情覆盖状态动画"因此由两件事**共同**实现：emote 注册在**后** + main 主动让位。
        // 若把 emote 挪到前面，表现是「play 没反应」——表情会被状态动画盖掉。
        controllers.add(new AnimationController<>(this, "main", this.animationTransitionTicks(), state -> {
            NpcEntity npc = state.getAnimatable();
            if (npc.hasActiveEmote()) {
                // 让位：本控制器这一帧不写任何骨骼，全身交给 emote 控制器。
                // 表情清空/播完后这里不再命中 ⇒ 下一帧自动重选 idle/fly
                // —— **没有任何"恢复原动画"的代码**，这是刻意的（见设计文档 §2.3）。
                state.getController().stop();
                return PlayState.STOP;
            }
            RawAnimation posed = posedAnimations.get(npc.state());
            if (posed != null) {
                return state.setAndContinue(posed);      // 非待机：整体替换，不进下面那套
            }
            if (!state.isMoving()) {
                return state.setAndContinue(idle);
            }
            boolean speedBoosted = npc.getAttributeValue(Attributes.MOVEMENT_SPEED)
                    > npc.getAttributeBaseValue(Attributes.MOVEMENT_SPEED);
            return state.setAndContinue(speedBoosted ? run : walk);
        }));

        // 表情控制器（顺序警告见上）。全部判断都在客户端做，因为动画名/长度/loop 类型都是资产数据。
        controllers.add(new AnimationController<>(this, "emote", this.animationTransitionTicks(), state -> {
            NpcEntity npc = state.getAnimatable();
            String name = npc.emote();
            if (name.isEmpty() || npc.emoteDone) {
                // 顺手清掉 emoteSeen：万一同步通知因故没到（force 发包这条链路我们只有静态依据），
                // 下次 play 同名时这里仍能凭"名字 != emoteSeen"重新起表。
                npc.emoteSeen = "";
                state.getController().forceAnimationReset();
                return PlayState.STOP;
            }
            if (!name.equals(npc.emoteSeen)) {
                // 换了一条表情：重置本地状态，并把计时起点钉在**当前 tick**上。
                npc.emoteSeen = name;
                npc.emoteDone = false;
                npc.emoteStartTick = npc.tickCount;
            }
            Animation animation = EmoteAnimationLookup.find(npc, name);
            if (animation == null) {
                // 资产里没有这条动画（或名字拼错）：静默不播（设计 D4）。
                // **不做负缓存** —— 下一帧还会再查，于是 F3+T 重载资源后能自愈。
                return PlayState.STOP;
            }
            if (animation.loopType() == Animation.LoopType.LOOP) {
                return state.setAndContinue(RawAnimation.begin().thenLoop(name));
            }
            // 非循环（play_once / hold_on_last_frame / 自定义）⇒ 一次性：
            // 播满"动画自身长度"就本地收工，回落到状态动画。
            // ⚠️ 判据必须用**自记的 tick 计时**，绝不能用 state.getAnimationTick()：
            //    后者是**全局动画时钟**（GeoModel.java:217 赋的是 this.animTime），
            //    拿它去比长度会立刻判成"播完"。
            // 长度单位是 tick（BakedAnimationsAdapter.java:59：animation_length * 20d）。
            if (npc.tickCount - npc.emoteStartTick >= animation.length()) {
                // ⚠️ 收工：**照常写这一帧的骨骼**，不要 return STOP！
                // 理由：main 注册在前，本帧已经跑过（那时 emoteDone 还是 false ⇒ 它让位了）。
                // 若这里也 STOP，则该帧**没有任何控制器写骨骼** ⇒ GeckoLib 的复位分支会把
                // 全部骨骼吸附到初始快照（AnimationProcessor.java:174-176，配合 :217,236-237
                // 每帧清标记 ⇒ percentageReset = 1）⇒ 表现为"收工瞬间整具模型闪一下"。
                // 照常 setAndContinue 就没有这个空档，下一帧 main 自然接管。
                npc.emoteDone = true;
                return state.setAndContinue(RawAnimation.begin().thenPlay(name));
            }
            return state.setAndContinue(RawAnimation.begin().thenPlay(name));
        }));

        // 攻击控制器：**注册在最后** ⇒ 最后写骨骼 ⇒ **攻击动画优先于表情**（用户裁定）。
        // 它不承载任何状态：谓词恒返回 STOP，只播由 swing() 点播的那一条；
        // 而 tryTriggerAnimation 会把 STOPPED 的控制器重新拉起
        // （AnimationController.java:402-406），所以"恒 STOP"不会妨碍点播。
        controllers.add(new AnimationController<>(this, "attack", this.animationTransitionTicks(),
                        state -> PlayState.STOP)
                .triggerableAnim(ATTACK_TRIGGER, RawAnimation.begin().thenPlay(this.attackAnimationName())));
    }

    /**
     * 是否有"正在生效"的表情 —— {@code main} 控制器据此让位（顺序理由见 {@link #registerControllers}）。
     * <p>
     * 判据里带 {@link #emoteDone}：一次性表情播完后就不该再压着状态动画，否则
     * {@code main} 会一直让位、而 {@code emote} 又返回 STOP，两边都不写骨骼。
     */
    private boolean hasActiveEmote() {
        String name = this.emote();
        if (name.isEmpty() || this.emoteDone) {
            return false;
        }
        // ⚠️ **还必须查得到这条动画**，否则会出现"两个控制器一起返回 STOP"的帧：
        // main 让位了、emote 又因为查不到而 STOP ⇒ **没有任何控制器写骨骼** ⇒
        // GeckoLib 的复位分支把全部骨骼吸附到初始快照（AnimationProcessor.java:174-176；
        // 标记每帧被清、lastResetRotationTick 陈旧 ⇒ percentageReset = 1）
        // ⇒ 表现是**永久塌成初始姿态**，而不是设计 D4 想要的"状态动画照旧"。
        // 这条是 2026-09-29 代码审查抓到的 Critical，必须在**这一侧**也挡住 ——
        // 光让 emote 谓词返回 STOP 是不够的。
        // 代价：表情生效期间每帧多一次哈希查找（刻意不复缓存，以便 F3+T 后自愈）。
        return EmoteAnimationLookup.find(this, name) != null;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
