package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.registry.DSAttributes;
import by.dragonsurvivalteam.dragonsurvival.registry.projectile.entity_effects.ProjectileDamageEffect;
import com.zonlong.beloong.Config;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复 Dragon Survival {@link ProjectileDamageEffect#apply(Projectile, Entity, int)}
 * 中因弹射物主人缺失 {@code dragonsurvival:dragon_ability_damage} 属性而导致的崩溃。
 *
 * <p>DS 仅在 {@link DSAttributes#attachAttributes} 中将该属性注册给 {@code EntityType.PLAYER}。
 * 当弹射物主人为非玩家实体时，调用 {@code owner.getAttributeValue(DSAttributes.DRAGON_ABILITY_DAMAGE)}
 * 会抛出 {@code IllegalArgumentException: Can't find attribute dragonsurvival:dragon_ability_damage}。</p>
 *
 * <p>此 Mixin 在方法入口处检测该情况：若主人存在但不具备该属性，则使用默认倍率（1.0）计算伤害并提前返回，
 * 跳过原始方法中的属性查询，避免崩溃。</p>
 *
 * @see <a href="https://github.com/DragonSurvivalTeam/DragonSurvival">Dragon Survival 源码</a>
 */
@Mixin(value = ProjectileDamageEffect.class, remap = false)
public abstract class ProjectileDamageEffectMixin {

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true, remap = false)
    private void beloong$handleMissingAttribute(Projectile projectile, Entity target, int level, CallbackInfo ci) {
        if (!Config.FIX_DS_PROJECTILE_CRASH.get()) {
            return;
        }

        LivingEntity owner = projectile.getOwner() instanceof LivingEntity entity ? entity : null;

        if (owner != null && !owner.getAttributes().hasAttribute(DSAttributes.DRAGON_ABILITY_DAMAGE)) {
            ProjectileDamageEffect self = (ProjectileDamageEffect) (Object) this;
            float damageAmount = self.amount().calculate(level);
            target.hurt(new DamageSource(self.damageType(), projectile, owner), damageAmount);
            owner.setLastHurtMob(target);
            ci.cancel();
        }
    }
}
