package com.zonlong.beloong.mixin.mowziesmobs;

import com.bobmowzie.mowziesmobs.server.entity.effects.geomancy.EntityPillar.EntityPillarSculptor;
import com.bobmowzie.mowziesmobs.server.entity.sculptor.EntitySculptor;
import com.zonlong.beloong.Config;
import java.util.Optional;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 BeLoong-Core 中放宽 Sculptor 跑酷试炼的“飞行/空中移动”反作弊。
 *
 * <p>与 1.20.1 分支中注释掉的 {@code /* Can Fly *}{@code /} 块保持一致：</p>
 * <ul>
 *   <li>移除：{@code Abilities.flying} 飞行检测</li>
 *   <li>移除：水中且未落地检测</li>
 *   <li>移除：空中持续向上加速检测</li>
 *   <li>保留：横向距离检测</li>
 *   <li>保留：低于石柱底部检测</li>
 *   <li>保留：传送/瞬移位置跳变检测</li>
 * </ul>
 *
 * <p>实现方式：在 {@code EntitySculptor#checkIfPlayerCheats()} 的 HEAD 取消原方法，
 * 用本 Mixin 自带的裁剪版逻辑替代原反作弊流程。</p>
 */
@Mixin(EntitySculptor.class)
public abstract class EntitySculptorCanFlyMixin {

    @Unique
    private Optional<Vec3> beloong$prevPlayerPosition = Optional.empty();

    /**
     * 新试炼开始时清空上一轮的位置追踪，避免把旧位置误判为传送。
     */
    @Inject(method = "setTestingPlayer", at = @At("HEAD"), remap = false)
    private void beloong$resetTracking(Player player, CallbackInfo ci) {
        this.beloong$prevPlayerPosition = Optional.empty();
    }

    /**
     * 替换原 {@code checkIfPlayerCheats()}：去掉飞行/水中/向上加速检测。
     */
    @Inject(method = "checkIfPlayerCheats", at = @At("HEAD"), cancellable = true, remap = false)
    private void beloong$replaceCheckIfPlayerCheats(CallbackInfo ci) {
        if (!Config.REMOVE_SCULPTOR_ANTI_CHEAT.get()) {
            return;
        }

        EntitySculptor self = (EntitySculptor) (Object) this;
        Player player = self.getTestingPlayer();

        if (player == null || !self.isTesting() || player.isCreative()) {
            ci.cancel();
            return;
        }

        // 保留：横向距离检测
        double horizontalDistance = player.position()
                .multiply(1.0, 0.0, 1.0)
                .distanceTo(self.position().multiply(1.0, 0.0, 1.0));
        if (horizontalDistance > EntitySculptor.TEST_RADIUS + 4) {
            self.playerCheated();
            ci.cancel();
            return;
        }

        // 保留：低于石柱底部检测
        EntityPillarSculptor pillar = self.getPillar();
        if (pillar != null && player.getY() < pillar.getY() - 10.0) {
            self.playerCheated();
            ci.cancel();
            return;
        }

        // 保留：传送/瞬移检测
        Vec3 current = player.position();
        if (this.beloong$prevPlayerPosition.isPresent()) {
            Vec3 predicted = this.beloong$prevPlayerPosition.get().add(player.getDeltaMovement());
            if (current.distanceTo(predicted) > 3.0) {
                self.playerCheated();
                ci.cancel();
                return;
            }
        }
        this.beloong$prevPlayerPosition = Optional.of(current);

        // 总是取消原方法，从而移除飞行/水中/持续向上加速检测
        ci.cancel();
    }
}
