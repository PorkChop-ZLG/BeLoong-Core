package com.zonlong.beloong.mixin.mowziesmobs;

import com.bobmowzie.mowziesmobs.server.entity.MowzieEntity;
import com.bobmowzie.mowziesmobs.server.entity.MowzieLLibraryEntity;
import com.bobmowzie.mowziesmobs.server.entity.wroughtnaut.EntityWroughtnaut;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.compat.mowziesmobs.MowzieMobsCompat;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让钢铁守护者（Ferrous Wroughtnaut）可被原版重锤直接造成伤害。
 *
 * <p>Mowzie 的 {@link EntityWroughtnaut#hurt} 默认在大部分情况下直接返回 {@code false}。
 * 本 Mixin 在其 {@code hurt()} 入口处检测：
 * <ul>
 *   <li>配置 {@link Config#ENABLE_MOWZIE_MACE_DAMAGE} 已开启；</li>
 *   <li>钢铁守护者已激活（{@code isActive()}）且有目标（{@code getTarget() != null}）；</li>
 *   <li>伤害来源是玩家手持 {@code minecraft:mace} 的原版近战攻击。</li>
 * </ul>
 * 全部满足时直接调用父类 {@link MowzieLLibraryEntity#hurt}，以正常伤害结算，
 * 不判断方向、不依赖 vulnerable 窗口、也不打断当前动作。</p>
 */
@Mixin(EntityWroughtnaut.class)
public abstract class EntityWroughtnautMaceMixin extends MowzieLLibraryEntity {

    protected EntityWroughtnautMaceMixin(EntityType<? extends MowzieEntity> type, Level level) {
        super(type, level);
    }

    @Inject(
            method = "hurt",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void beloong$allowMaceDamage(
            DamageSource source,
            float amount,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!Config.ENABLE_MOWZIE_MACE_DAMAGE.get()) {
            return;
        }

        // 仅“已激活且有目标”时生效
        if (!((EntityWroughtnaut) (Object) this).isActive() || getTarget() == null) {
            return;
        }

        // 只有玩家手持原版重锤的近战攻击才绕过免伤
        if (!MowzieMobsCompat.isMaceAttack(source)) {
            return;
        }

        // 正常伤害：不秒杀、不打断动作、不判断方向
        cir.setReturnValue(super.hurt(source, amount));
    }
}
