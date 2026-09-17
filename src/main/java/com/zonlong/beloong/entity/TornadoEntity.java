package com.zonlong.beloong.entity;

import by.dragonsurvivalteam.dragonsurvival.registry.attachments.DSDataAttachments;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.SummonData;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 龙卷风实体：匀速直线飞行，每 tick 把周围敌人吸向中心，每 {@link #DAMAGE_INTERVAL_TICKS} 刻结算一次伤害。
 * <p>
 * <b>关键设计：位移与玩法逻辑只在服务端跑，客户端只额外外推一次。</b>
 * 本类继承 {@link Projectile}（不是 {@code LivingEntity}），因此客户端侧的
 * {@code Entity#lerpTo} 是硬吸附（{@code setPos}）而不是插值——客户端位置由服务端的
 * {@code ClientboundMoveEntityPacket} 决定，服务端位置始终是权威的。
 * <p>
 * 服务端每 tick 把速度写进 {@code hurtMarked}，由 {@code ServerEntity#sendChanges} 收成
 * {@code ClientboundSetEntityMotionPacket} 下发；客户端收到后每 tick 用这个速度外推一 tick。
 * 这不会与硬吸附打架：原版客户端的包队列在实体 {@code tick()} <b>之前</b>就被排空，
 * 而吸附点恰好等于上一 tick 外推后的位置，故随后的吸附是空操作，不产生抖动或速度翻倍。
 * 反过来，若客户端不外推，{@code setOldPosAndRot()} 发生在 {@code tick()} 之前，
 * 吸附又只改位置不改 {@code xOld}，于是渲染时 {@code xOld == getX()}，
 * {@code Mth.lerp(partialTick, xOld, getX())} 退化成常量——0.45 格/tick 会肉眼可见地每秒跳 20 次。
 * 外推的目的正是让 {@code xOld != getX()}，使原版渲染插值重新生效。
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
    }

    /**
     * 本实体不同步任何自定义数据：客户端渲染只需要位置与 {@code tickCount}，原版自动同步。
     * <p>
     * 必须实现——{@code Entity} 把它声明为 {@code protected abstract}，而 {@link Projectile} 没有实现。
     */
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // 无自定义同步数据
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
            this.setPos(this.position().add(this.getDeltaMovement()));
            return;
        }

        if (--this.life <= 0) {
            this.discard();
            return;
        }

        // 匀速直线穿透：不调用 move()，所以不与方块碰撞
        this.setPos(this.position().add(this.getDeltaMovement()));
        // 把上面这个速度下发给客户端，客户端才能自行外推（见类注释）。
        // ServerEntity#sendChanges 会消费 hurtMarked 并发一个 ClientboundSetEntityMotionPacket。
        this.hurtMarked = true;

        if (this.getY() < this.level().getMinBuildHeight() - VOID_MARGIN) {
            this.discard();
            return;
        }

        this.applyPullAndDamage();
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
        AABB area = new AABB(
                this.getX() - radius, this.getY() - AREA_DOWN, this.getZ() - radius,
                this.getX() + radius, this.getY() + AREA_UP, this.getZ() + radius);

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
