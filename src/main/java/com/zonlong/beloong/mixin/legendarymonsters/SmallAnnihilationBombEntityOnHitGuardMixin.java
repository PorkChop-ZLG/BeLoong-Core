package com.zonlong.beloong.mixin.legendarymonsters;

import com.zonlong.beloong.compat.legendarymonsters.ProjectileHitGuard;
import net.miauczel.legendary_monsters.entity.AnimatedMonster.Projectile.SmallAnnihilationBombEntity;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复"小型湮灭炸弹"（{@code legendary_monsters:small_dimensional_bomb}）命中部件实体时的
 * {@code ClassCastException} 崩服。
 *
 * <p>2.2.3 字节码：{@code onHitEntity} 第 20 字节取 {@code EntityHitResult#getEntity()}，
 * 第 87 字节对该值 {@code checkcast LivingEntity}（用于
 * {@code MathUtils.entityBasedHpDamage}），而路径上唯一的检查是
 * {@code instanceof TamableAnimal} ⇒ 命中任何非生物实体（冰火 {@code DragonPartEntity}、
 * Iron's {@code ShieldPart}、原版 {@code EnderDragonPart}、LM 自家 {@code PartEntity}）必崩。
 * 崩溃与机制详见 {@link ProjectileHitGuard}。</p>
 *
 * <p>传奇怪物为可选依赖：{@code @Pseudo} + {@code require = 0}，未安装时本 Mixin 整体跳过；
 * {@code require = 0} 也让上游改实现时不至于让整合包起不来（届时守卫失效，日志会有迹可循）。</p>
 */
@Pseudo
@Mixin(value = SmallAnnihilationBombEntity.class, remap = false)
public abstract class SmallAnnihilationBombEntityOnHitGuardMixin {

    @Inject(
            method = "onHitEntity(Lnet/minecraft/world/phys/EntityHitResult;)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void beloong$skipNonLivingHit(EntityHitResult result, CallbackInfo ci) {
        if (ProjectileHitGuard.shouldSkipNonLivingHit(result.getEntity(), "small_dimensional_bomb")) {
            ci.cancel();
        }
    }
}
