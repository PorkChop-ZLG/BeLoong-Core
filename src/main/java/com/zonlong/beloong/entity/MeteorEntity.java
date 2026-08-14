package com.zonlong.beloong.entity;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.registry.ModEntities;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * 流星火雨中的陨石实体。
 * <p>
 * 从天际高处生成后受重力下坠，沿途喷发火焰尾焰粒子；
 * 触地（或垂直碰撞、或兜底计时归零）后引发爆炸：
 * <ul>
 *   <li>破坏方块（{@code Explosion.BlockInteraction.DESTROY}）</li>
 *   <li>对范围内所有生物（含玩家）造成原版爆炸伤害</li>
 *   <li>叠加 {@link Config.MeteorRain#entityDamage} 的额外直接伤害</li>
 * </ul>
 * 服务端权威：逻辑仅在服务端执行，客户端只负责渲染。
 */
public class MeteorEntity extends Entity {

    /** 陨石伤害类型（数据驱动，见 {@code data/beloong/damage_type/meteor.json}）。 */
    static final ResourceKey<DamageType> METEOR = ResourceKey.create(
            Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "meteor"));

    /** 兜底自爆计时，防止陨石卡在空中永不落地。 */
    private int fuseTicks = 200;

    public MeteorEntity(EntityType<? extends MeteorEntity> type, Level level) {
        super(type, level);
    }

    public MeteorEntity(Level level, double x, double y, double z) {
        this(ModEntities.METEOR.get(), level);
        this.setPos(x, y, z);
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        // 陨石无需要同步的字段，服务端权威
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("fuse", fuseTicks);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.fuseTicks = tag.getInt("fuse");
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            return; // 服务端权威
        }

        if (level() instanceof ServerLevel serverLevel) {
            // 火焰尾焰粒子
            serverLevel.sendParticles(ParticleTypes.FLAME, getX(), getY(), getZ(), 4,
                    0.3, 0.3, 0.3, 0.0);
            serverLevel.sendParticles(ParticleTypes.SMOKE, getX(), getY(), getZ(), 2,
                    0.3, 0.3, 0.3, 0.0);
        }

        // 重力下坠
        setDeltaMovement(getDeltaMovement().add(0.0, -0.06, 0.0));
        move(MoverType.SELF, getDeltaMovement());

        // 落地判定：触地 / 垂直碰撞 / 兜底计时
        if (onGround() || verticalCollision || --fuseTicks <= 0) {
            explode();
        }
    }

    /** 陨石爆炸：破坏方块 + 原版爆炸伤害 + 额外直接伤害。 */
    private void explode() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            discard();
            return;
        }

        Holder<DamageType> type = serverLevel.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(METEOR);
        DamageSource source = new DamageSource(type, this);

        float power = Config.MeteorRain.explosionPower.get().floatValue();
        boolean fire = Config.MeteorRain.fire.get();
        serverLevel.explode(this, source, null, getX(), getY(), getZ(),
                power, fire, Level.ExplosionInteraction.TNT);

        // 额外直接伤害，保证“巨大伤害”
        float extraDamage = Config.MeteorRain.entityDamage.get().floatValue();
        if (extraDamage > 0) {
            double r = 6.0;
            for (LivingEntity target : serverLevel.getEntitiesOfClass(LivingEntity.class,
                    new AABB(getX() - r, getY() - r, getZ() - r,
                            getX() + r, getY() + r, getZ() + r))) {
                if (target.isAlive()) {
                    target.hurt(source, extraDamage);
                }
            }
        }

        discard();
    }
}
