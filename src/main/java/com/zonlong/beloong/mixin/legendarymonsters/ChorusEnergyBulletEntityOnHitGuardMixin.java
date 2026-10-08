package com.zonlong.beloong.mixin.legendarymonsters;

import com.zonlong.beloong.compat.legendarymonsters.ProjectileHitGuard;
import net.miauczel.legendary_monsters.entity.AnimatedMonster.Projectile.ChorusEnergyBulletEntity;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复"紫颂能量弹"（{@code legendary_monsters:chorus_energy_bullet}）命中部件实体时的
 * {@code ClassCastException} 崩服 —— 与
 * {@link SmallAnnihilationBombEntityOnHitGuardMixin} 同一类缺陷，只是还没被玩家撞出来。
 *
 * <p>2.2.3 字节码：{@code onHitEntity} 第 16 字节 {@code EntityHitResult#getEntity()}，
 * 紧接着第 19 字节就 {@code checkcast LivingEntity}（<b>连中间变量都不存</b>，路径上没有任何
 * {@code instanceof} 保护）⇒ 命中冰火 {@code DragonPartEntity}、Iron's {@code ShieldPart}、
 * 原版 {@code EnderDragonPart}、LM 自家 {@code PartEntity} 等非生物实体时必崩。
 * 崩溃与机制详见 {@link ProjectileHitGuard}。</p>
 *
 * <p>传奇怪物为可选依赖：{@code @Pseudo} + {@code require = 0}，未安装时本 Mixin 整体跳过。</p>
 */
@Pseudo
@Mixin(value = ChorusEnergyBulletEntity.class, remap = false)
public abstract class ChorusEnergyBulletEntityOnHitGuardMixin {

    @Inject(
            method = "onHitEntity(Lnet/minecraft/world/phys/EntityHitResult;)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void beloong$skipNonLivingHit(EntityHitResult result, CallbackInfo ci) {
        if (ProjectileHitGuard.shouldSkipNonLivingHit(result.getEntity(), "chorus_energy_bullet")) {
            ci.cancel();
        }
    }
}
