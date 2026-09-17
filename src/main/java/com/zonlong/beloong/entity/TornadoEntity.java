package com.zonlong.beloong.entity;

import by.dragonsurvivalteam.dragonsurvival.registry.attachments.DSDataAttachments;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.SummonData;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 龙卷风实体：匀速飞行、**撞方块镜面反弹**、**穿透生物**，每 tick 把周围敌人吸向中心，
 * 每 {@link #DAMAGE_INTERVAL_TICKS} 刻结算一次伤害。
 * <p>
 * <b>关键设计：位移与玩法逻辑只在服务端跑，客户端只额外外推一次。</b>
 * 本类继承 {@link Projectile}（不是 {@code LivingEntity}），因此客户端侧的
 * {@code Entity#lerpTo} 是硬吸附（{@code setPos}）而不是插值——客户端位置由服务端的
 * {@code ClientboundMoveEntityPacket} 决定，服务端位置始终是权威的。
 * <p>
 * <b>唯一同步到客户端的自定义数据是「生成时的初始寿命」</b>（{@link #DATA_INITIAL_LIFE}），
 * 供渲染器倒推剩余寿命、在消散阶段缩小模型。它一生只同步一次（跨区块重载时再同步一次），
 * <b>不</b>每 tick 更新。伤害/半径/引力等玩法字段仍然不同步——渲染器不得读取它们。
 * <p>
 * 服务端每 tick 把速度写进 {@code hurtMarked}，由 {@code ServerEntity#sendChanges} 收成
 * {@code ClientboundSetEntityMotionPacket} 下发；客户端收到后每 tick 用这个速度外推一 tick。
 * 这不会与硬吸附打架：原版客户端的包队列在实体 {@code tick()} <b>之前</b>就被排空，
 * 而吸附点恰好等于上一 tick 外推后的位置，故随后的吸附是空操作，不产生抖动或速度翻倍。
 * 反过来，若客户端不外推，{@code setOldPosAndRot()} 发生在 {@code tick()} 之前，
 * 吸附又只改位置不改 {@code xOld}，于是渲染时 {@code xOld == getX()}，
 * {@code Mth.lerp(partialTick, xOld, getX())} 退化成常量——本技能的 speed 在 0.15～0.3 格/tick 量级，
 * 会肉眼可见地每秒跳 20 次。
 * 外推的目的正是让 {@code xOld != getX()}，使原版渲染插值重新生效。
 * <p>
 * <b>移动会撞方块反弹</b>（见 {@link #advance()}）：逐轴碰撞检测 + 该轴速度取反，不衰减、不设弹跳上限；
 * 但<b>不撞生物</b>——生物必须能穿过风柱，否则没法把它们吸到身上。客户端也执行同样的碰撞位移，
 * 否则服务端已经弹回来时它还在沿直线外推，视觉上会穿墙。
 * <p>
 * <b>「无视攻击冷却」靠伤害类型 tag 实现</b>，不是 hack {@code invulnerableTime}：
 * {@code minecraft:bypasses_cooldown} 让 {@code LivingEntity#hurt} 跳过
 * {@code invulnerableTime > 10.0F} 的差额分支，每次命中都走全额伤害；
 * {@code minecraft:no_knockback} 则压掉击退，避免和吸引力互相顶。
 * 注意该 tag <b>同时</b>使每次命中都播一次受伤音效并广播一个伤害事件包，且没有任何
 * tag 能压掉音效，故伤害按 {@link #DAMAGE_INTERVAL_TICKS} 刻批量结算、单次伤害等比例放大，
 * 详见该常量的注释。
 */
public class TornadoEntity extends Projectile {

    /** 自定义伤害类型 {@code beloong:tornado}。 */
    public static final ResourceKey<DamageType> TORNADO_DAMAGE = ResourceKey.create(
            Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "tornado"));

    /** 水平阻尼：每 tick 保留多少已有水平速度，防止越吸越快 */
    private static final double PULL_DAMPING = 0.82D;
    /** 近中心限速系数：允许的最大水平速度 = pullStrength * 该系数 * min(1, 距离/2) */
    private static final double PULL_SPEED_FACTOR = 3.0D;
    /** 每 tick 附加的上卷速度 */
    private static final double PULL_LIFT = 0.055D;
    /** 上卷速度下限 */
    private static final double LIFT_MIN = -0.6D;
    /** 上卷速度上限 */
    private static final double LIFT_MAX = 0.45D;
    /** 候选收集范围相对实体位置的下探距离（实体悬空约 1.1 格，下探 1 格可覆盖到地面） */
    private static final double AREA_DOWN = 1.0D;
    /** 候选收集范围相对实体位置的上探距离 */
    private static final double AREA_UP = 4.0D;
    /** 低于世界最低建筑高度这么多格就自行销毁 */
    private static final int VOID_MARGIN = 8;
    /**
     * 每次结算的间隔（刻）。每 tick 结算会让 {@code LivingEntity.hurt} 每 tick 都播一次
     * 受伤音效并广播一个伤害事件包（{@code bypasses_cooldown} 使其每次都走 {@code flag1 = true}
     * 分支，且没有任何伤害类型 tag 能压掉音效）；10 只生物 = 约 200 次/秒。改为每 4 刻结算
     * 1 次、每次 4 倍伤害后 DPS 不变（无视冷却靠的是 {@code bypasses_cooldown} tag，
     * 不是结算频率），开销降到 1/4。吸引仍是每 tick 生效，不受本间隔影响。
     * <p>
     * 实际节奏：每 {@code DAMAGE_INTERVAL_TICKS} 刻命中一次，即 20 / 4 = 5 次/秒；
     * 首次命中在生成后的第一个 tick，此后间隔恒为本值。{@link #damageTimer} 的自减与重置
     * 写法必须与这一节奏配套，改动时要连同重新推演——历史实现曾因命中后重置成 {@code N - 1}，
     * 使间隔变成 5 刻（4 次/秒、DPS 低 20%）。
     */
    private static final int DAMAGE_INTERVAL_TICKS = 4;

    /**
     * 生成时同步给客户端的初始寿命（刻）；{@code -1} 表示尚未同步。
     * <p>
     * 客户端据此<b>倒推</b>剩余寿命，而不是每 tick 同步剩余值——两端每 tick 都自增
     * {@code tickCount}，相减即可，一生只需一个数据包（跨区块重载时
     * {@link #readAdditionalSaveData} 会用恢复出的剩余寿命再同步一次）。唯一偏差是生成包
     * 到达客户端的那一点延迟，不到一 tick，20 刻的缩小过程看不出来。
     */
    private static final EntityDataAccessor<Integer> DATA_INITIAL_LIFE =
            SynchedEntityData.defineId(TornadoEntity.class, EntityDataSerializers.INT);

    private float damagePerHit;
    private double pullRadius;
    private double damageRadius;
    private double pullStrength;
    private int life;

    /** 伤害计时器：在 {@code applyPullAndDamage} 里先自减再判定，减到 0 的那一 tick 结算并重置为 {@link #DAMAGE_INTERVAL_TICKS}。 */
    private int damageTimer;

    /** EntityFactory 与反序列化用。 */
    public TornadoEntity(EntityType<TornadoEntity> type, Level level) {
        super(type, level);
    }

    /** 技能发射用。 */
    public TornadoEntity(EntityType<TornadoEntity> type, Level level, LivingEntity owner,
                         float damagePerHit, double pullRadius, double damageRadius,
                         double pullStrength, int lifetime) {
        this(type, level);
        this.setOwner(owner);
        this.damagePerHit = damagePerHit;
        this.pullRadius = pullRadius;
        this.damageRadius = damageRadius;
        this.pullStrength = pullStrength;
        this.life = lifetime;
        this.entityData.set(DATA_INITIAL_LIFE, lifetime);
    }

    /**
     * 本实体<b>只</b>同步一个自定义值：{@link #DATA_INITIAL_LIFE}（生成时的初始寿命），
     * 供渲染器倒推剩余寿命、在消散阶段缩小模型。伤害/半径/引力等玩法字段一律不同步——
     * 渲染器不得读取它们，它只能读位置、{@code tickCount} 与 {@link #getRemainingLife()}。
     * <p>
     * 必须实现——{@code Entity} 把它声明为 {@code protected abstract}，而 {@link Projectile} 没有实现。
     */
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // 只同步「生成时的初始寿命」这一个值：渲染器要靠它倒推剩余寿命才能做消散缩小。
        // -1 表示尚未同步（客户端实体刚创建、数据包还没到），渲染器据此按“寿命充足”处理，
        // 免得刚生成就被缩成 0。伤害/半径/引力等玩法字段一律不同步。
        builder.define(DATA_INITIAL_LIFE, -1);
    }

    @Override
    public void tick() {
        super.tick();

        // 客户端不做玩法逻辑，但要用同步下来的速度外推一 tick（见类注释）：
        // 服务端每 tick 都会用真实位置硬吸附一次（Entity#lerpTo 对非 LivingEntity 就是 setPos），
        // 而吸附发生在 tick() 之前，所以 xOld == getX()，渲染插值等于没做。
        // 让客户端用同步下来的速度自行外推一 tick：吸附点恰好等于上一 tick 外推后的位置，
        // 因此不会来回抖，同时渲染插值有了 xOld != getX()，运动变连续。
        if (this.level().isClientSide()) {
            // 没有收到过运动包（速度仍为 0）时保持原样：宁可不外推，也不要凭空传送
            if (this.getDeltaMovement().lengthSqr() < 1.0E-7D) {
                return;
            }
            this.advance();
            return;
        }

        if (--this.life <= 0) {
            this.discard();
            return;
        }

        // 逐轴碰撞 + 镜面反弹（见 advance()）：撞方块反射，生物可穿透
        this.advance();
        // 把上面这个速度下发给客户端，客户端才能自行外推（见类注释）。
        // ServerEntity#sendChanges 会消费 hurtMarked 并发一个 ClientboundSetEntityMotionPacket。
        this.hurtMarked = true;

        if (this.getY() < this.level().getMinBuildHeight() - VOID_MARGIN) {
            this.discard();
            return;
        }

        this.applyPullAndDamage();
    }

    /**
     * 向前推进一步；撞到方块时按碰撞轴做镜面反射。
     * <p>
     * 用 {@link Entity#move} 而不是 {@code setPos}：前者会做碰撞解算，于是可以拿
     * 「实际位移 vs 意图位移」<b>逐轴</b>判断是哪一轴被挡住。刻意不用
     * {@code horizontalCollision} 之类的水位标志——它把 x/z 合并成一个布尔，
     * 撞一面墙会错误地同时翻转两个水平轴。
     * <p>
     * <b>只撞方块，不撞生物</b>：不走 {@code ProjectileUtil} 的命中检测、也不触发 {@code onHit}，
     * 所以生物可以穿过风柱。速度不因反弹衰减，保持恒定速率便于预判；弹跳次数不设上限，
     * 仍由寿命决定消亡。
     * <p>
     * 客户端与服务端都调用本方法：服务端是权威，客户端若不自行碰撞，服务端已经弹回来时
     * 它还在沿直线外推，视觉上会穿墙。
     */
    private void advance() {
        Vec3 delta = this.getDeltaMovement();
        if (delta.lengthSqr() < 1.0E-7D) {
            return;
        }

        // 【审查修正】清掉「卡在方块里」的速度倍率：蜘蛛网 makeStuckInBlock(0.25,0.05,0.25)、
        // 细雪 (0.9,1.5,0.9)。它由上一次 move() 末尾的 checkInsideBlocks → Block#entityInside 写入，
        // 并在本次 move() 开头（Entity.java:632-634）缩放置移、随后清零。若不清，下面
        // 「实际位移 vs 意图位移」的逐轴判据会把这个缩放假当成碰撞，把每一轴都取反 ——
        // 实测表现为龙卷风在蜘蛛网里以 0.055 / 0 的位移原地振荡到寿命结束。
        // 顺带也符合直觉：一阵风不该被蛛网粘住。
        this.stuckSpeedMultiplier = Vec3.ZERO;

        Vec3 before = this.position();
        this.move(MoverType.SELF, delta);
        Vec3 moved = this.position().subtract(before);

        // 某轴实际位移明显小于意图位移 => 该轴撞到了方块，把该分量取反
        double tolerance = 1.0E-4D;
        this.setDeltaMovement(
                Math.abs(moved.x - delta.x) > tolerance ? -delta.x : delta.x,
                Math.abs(moved.y - delta.y) > tolerance ? -delta.y : delta.y,
                Math.abs(moved.z - delta.z) > tolerance ? -delta.z : delta.z);
    }

    /**
     * 关掉移动音效与游戏事件。
     * <p>
     * 【审查修正】原先用 {@code setPos} 直推时不会触发这些；改用 {@link Entity#move} 后，原版会在
     * 落地那一 tick 通过 {@code getMovementEmission()} 播方块踩踏音并发 {@code GameEvent.STEP}，
     * 另外 {@code checkFallDamage} 会发 {@code GameEvent.HIT_GROUND}。一个飞行中的风柱不该一路
     * 踩出声响，也不该被幽匿感测体当成脚步。
     */
    @Override
    protected Entity.MovementEmission getMovementEmission() {
        return Entity.MovementEmission.NONE;
    }

    /**
     * 玩家重新进入追踪范围（或跨维度返回）时，客户端会新建实体、{@code tickCount} 从 0 开始，
     * 而同步数据里的初始寿命仍是生成时那个值——照用会让模型突然跳到 {@code life / 20} 的缩放。
     * <p>
     * 【审查修正】这里按当前剩余寿命重发一次；{@code force = true} 才会在值未变化时也下发。
     */
    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        this.entityData.set(DATA_INITIAL_LIFE, this.life, true);
    }

    /** 收集范围内的候选目标：每 tick 按 3D 距离决定吸引，每 {@link #DAMAGE_INTERVAL_TICKS} 刻结算一次伤害。 */
    private void applyPullAndDamage() {
        // 先自减再判定，命中后重置为完整的 N：这样两次命中之间恰好经过 N 个 tick
        // （重置成 N-1 会让间隔变成 N+1，即 5 刻 / 4 次/秒、DPS 低 20%）。
        boolean dealDamage = --this.damageTimer <= 0;
        if (dealDamage) {
            this.damageTimer = DAMAGE_INTERVAL_TICKS;
        }

        // 候选盒的水平边长取两者较大值。只按 pullRadius 取的话，damageRadius 更大时
        // 多出来的那一圈实体连候选都进不来，永远打不到；各自的距离判定维持不变。
        double radius = Math.max(this.pullRadius, this.damageRadius);
        // 【审查修正】竖直上探也要跟着半径走：半径调到 12~16 格后，固定上探 4 格会把
        // 「水平在半径内、但高了 5 格以上」的实体排除在候选之外（距离判定用的是 3D 距离）。
        AABB area = new AABB(
                this.getX() - radius, this.getY() - AREA_DOWN, this.getZ() - radius,
                this.getX() + radius, this.getY() + Math.max(AREA_UP, radius), this.getZ() + radius);

        for (LivingEntity target : this.level().getEntitiesOfClass(LivingEntity.class, area, this::canAffect)) {
            double distance = Math.sqrt(target.distanceToSqr(this));
            if (distance <= this.pullRadius) {
                this.pull(target);
            }
            if (dealDamage && distance <= this.damageRadius) {
                this.hurtTarget(target, this.damagePerHit);
            }
        }
    }

    /**
     * 把目标向中心拉。
     * <p>
     * 朴素的「每 tick 朝中心加力」会越吸越快，然后高速穿过中心来回抖动。
     * 这里用<b>阻尼 + 近中心限速</b>：先保留 82% 的既有水平速度，再加一个恒定牵引，
     * 最后按「离中心越近、允许速度越小」封顶。
     */
    private void pull(LivingEntity target) {
        // pullStrength 非正数（0 或负数）视为「关闭吸引」：既不拉也不减速。
        // 若没有这道守卫，maxSpeed = pullStrength * PULL_SPEED_FACTOR * min(1, 距离/2) <= 0，
        // 末尾的 normalize().scale(maxSpeed) 会把目标的水平速度清零（0）甚至反向（负数）。
        if (this.pullStrength <= 0.0D) {
            return;
        }

        Vec3 toCenter = new Vec3(this.getX() - target.getX(), 0.0D, this.getZ() - target.getZ());
        double distance = toCenter.length();
        if (distance <= 0.05D) {
            return;
        }

        Vec3 direction = toCenter.scale(1.0D / distance);
        Vec3 current = target.getDeltaMovement();

        Vec3 horizontal = new Vec3(current.x, 0.0D, current.z)
                .scale(PULL_DAMPING)
                .add(direction.scale(this.pullStrength));

        double maxSpeed = this.pullStrength * PULL_SPEED_FACTOR * Math.min(1.0D, distance / 2.0D);
        if (horizontal.length() > maxSpeed) {
            horizontal = horizontal.normalize().scale(maxSpeed);
        }

        double vertical = Mth.clamp(current.y * 0.85D + PULL_LIFT, LIFT_MIN, LIFT_MAX);
        target.setDeltaMovement(horizontal.x, vertical, horizontal.z);
        // 必须：把服务端设的速度下发给客户端，否则客户端不会应用这个速度
        target.hurtMarked = true;
    }

    private void hurtTarget(LivingEntity target, float damage) {
        Holder<DamageType> type = this.level().registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(TORNADO_DAMAGE);
        // 直接实体 = 龙卷风，间接实体 = 施法者
        target.hurt(new DamageSource(type, this, this.getOwner()), damage);
    }

    /**
     * 判断目标是否应该被吸引/伤害。
     * <p>
     * 排除：施法者本人、同队成员、主人的宠物、Dragon Survival 召唤物、创造模式玩家、
     * 已死亡或旁观者。
     */
    private boolean canAffect(LivingEntity target) {
        if (!target.isAlive() || target.isSpectator()) {
            return false;
        }
        if (target instanceof Player player && player.isCreative()) {
            return false;
        }

        Entity owner = this.getOwner();
        if (owner == null) {
            return true;
        }
        if (target == owner) {
            return false;
        }
        // Entity#isAlliedTo 只看记分板队伍
        if (owner.isAlliedTo(target)) {
            return false;
        }
        // 原版宠物
        if (target instanceof OwnableEntity ownable && owner.getUUID().equals(ownable.getOwnerUUID())) {
            return false;
        }
        // Dragon Survival 召唤物。必须用 getExistingDataOrNull：getData 在附件缺失时会
        // 创建并挂上默认 SummonData，而它是 serializable 的，会给每个被扫到的生物写 NBT。
        SummonData summon = target.getExistingDataOrNull(DSDataAttachments.SUMMON.get());
        return summon == null || !summon.isOwner(owner);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putFloat("DamagePerHit", this.damagePerHit);
        compound.putDouble("PullRadius", this.pullRadius);
        compound.putDouble("DamageRadius", this.damageRadius);
        compound.putDouble("PullStrength", this.pullStrength);
        compound.putInt("Life", this.life);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        this.damagePerHit = compound.getFloat("DamagePerHit");
        this.pullRadius = compound.getDouble("PullRadius");
        this.damageRadius = compound.getDouble("DamageRadius");
        this.pullStrength = compound.getDouble("PullStrength");
        this.life = compound.getInt("Life");
        // 跨区块重载后 tickCount 归零，故把恢复出的剩余寿命当作「初始寿命」重新同步一次
        this.entityData.set(DATA_INITIAL_LIFE, this.life);
    }

    /**
     * 还剩多少刻消散。渲染器用它做消散阶段的缩小。
     * <p>
     * 由「生成时的初始寿命 − 本地 {@code tickCount}」推出，因此不需要每 tick 同步；
     * 尚未同步时返回 {@link Integer#MAX_VALUE}，渲染器按「寿命充足」处理。
     */
    public int getRemainingLife() {
        int initial = this.entityData.get(DATA_INITIAL_LIFE);
        if (initial < 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, initial - this.tickCount);
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSqr) {
        double size = this.getBoundingBox().getSize() * 4.0D;
        if (Double.isNaN(size)) {
            size = 4.0D;
        }
        size *= 64.0D;
        return distanceSqr < size * size;
    }
}
