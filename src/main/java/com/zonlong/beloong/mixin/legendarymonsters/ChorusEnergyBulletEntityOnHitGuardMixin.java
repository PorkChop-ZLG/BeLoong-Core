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
 * {@code ClassCastException} 崩服 —— 与本模组原先为"小型湮灭炸弹"打的守卫是同一类缺陷，
 * 但上游**只修了炸弹那一条**（2.2.4），紫颂弹这条至今未修。
 *
 * <p>字节码取证（2026-11-05，jar 2.2.3 / 2.2.4 逐条目 SHA1 对比）：本类在 2.2.4
 * <b>一个字节都没变</b>，{@code onHitEntity} 仍是第二句就
 * {@code LivingEntity entity = (LivingEntity)pResult.getEntity();}（<b>连中间变量都不存</b>，
 * 路径上没有任何 {@code instanceof} 保护）⇒ 命中冰火 {@code DragonPartEntity}、
 * Iron's {@code ShieldPart}、原版 {@code EnderDragonPart}、LM 自家 {@code PartEntity}
 * 等非生物实体时必崩。崩溃与机制详见 {@link ProjectileHitGuard}。</p>
 *
 * <p>传奇怪物为可选依赖：{@code @Pseudo} + {@code require = 0}，未安装时本 Mixin 整体跳过。
 * 上游一旦改签名，守卫会静默失效 ⇒ 观测点是 {@link ProjectileHitGuard} 的锚点日志是否还在。</p>
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
