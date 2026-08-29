package com.zonlong.beloong.mixin.betterendisland;

import com.zonlong.beloong.compat.betterendisland.DragonSummonHelper;
import com.yungnickyoung.minecraft.betterendisland.world.feature.BetterEndPodiumFeature;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 禁用 BEI 中央塔自动生成 4 颗召唤水晶，并把仪式位置下方的基岩替换为强化深板岩。
 *
 * <p>仅修改 BEI 的 {@link BetterEndPodiumFeature}，不修改原版 Minecraft 类。</p>
 */
@Mixin(BetterEndPodiumFeature.class)
public abstract class DragonSummonMixin {

    /**
     * 阻止 BEI 在中央塔生成时添加召唤水晶实体。
     */
    @Redirect(
            method = "place",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/ServerLevelAccessor;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"
            ),
            remap = false
    )
    private static boolean beloong$skipInitialSummonCrystals(ServerLevelAccessor level, Entity entity) {
        if (DragonSummonHelper.isAvailable() && entity instanceof EndCrystal) {
            return true;
        }
        return level.addFreshEntity(entity);
    }

    /**
     * 中央塔生成后，把 4 个仪式位置下方的基岩替换为强化深板岩。
     */
    @Inject(method = "place", at = @At("RETURN"), remap = false)
    private void beloong$replaceCrystalSupport(FeaturePlaceContext<NoneFeatureConfiguration> ctx,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (!DragonSummonHelper.isAvailable()) {
            return;
        }
        if (!(ctx.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        EndDragonFight fight = DragonSummonHelper.getDragonFight(serverLevel);
        if (fight == null) {
            return;
        }
        DragonSummonHelper.replaceCrystalSupportWithReinforcedDeepslate(serverLevel, fight);
    }
}
