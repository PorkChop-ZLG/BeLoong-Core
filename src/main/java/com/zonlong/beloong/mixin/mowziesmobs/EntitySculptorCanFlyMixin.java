package com.zonlong.beloong.mixin.mowziesmobs;

import com.bobmowzie.mowziesmobs.server.entity.sculptor.EntitySculptor;
import com.zonlong.beloong.Config;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 放宽 Sculptor 跑酷试炼的失败判定：<b>只保留「离开试炼区」一条</b>。
 *
 * <p>实现方式：在 {@code EntitySculptor#checkIfPlayerCheats()} 的 HEAD 取消原方法，
 * 用只含水平距离检查的裁剪版替代原反作弊流程。</p>
 *
 * <p>相对原版被<b>移除</b>的判定：</p>
 * <ul>
 *   <li>{@code Abilities.flying} 飞行检测</li>
 *   <li>水中且未落地检测</li>
 *   <li>空中持续向上加速检测</li>
 *   <li>低于石柱底部检测 —— <b>掉下去不再判负，这是本改动的目的</b></li>
 *   <li>传送/瞬移位置跳变检测</li>
 * </ul>
 *
 * <p><b>保留</b>：水平距离检测（离场判负，阈值 {@code TEST_RADIUS + 4} = 16 格；
 * {@code TEST_RADIUS} 恒为 12，不受配置覆盖）。</p>
 *
 * <p><b>本 Mixin 不涉及 Mowzie 的第二套反作弊</b>
 * （{@code ServerEventHandler#cheatSculptor}，由放方块 / 破方块 / 使用水桶三个事件触发），
 * 因此试炼中这些行为<b>仍会立即判负</b>，属有意保留。</p>
 *
 * <p>另：判定阈值 16 格与 {@code isPlayerInTestZone()} 的约 12.37 格并不一致，
 * 两者之间存在约 3.6 格的环带（玩家"已不在试炼区"但"尚未判负"）。通关只要求
 * 在柱顶与雕刻家相距 4.47 格内右键，不受此环带影响。</p>
 */
@Mixin(EntitySculptor.class)
public abstract class EntitySculptorCanFlyMixin {

    /**
     * 替换原 {@code checkIfPlayerCheats()}：只保留水平距离（离场）检测。
     */
    @Inject(method = "checkIfPlayerCheats", at = @At("HEAD"), cancellable = true, remap = false)
    private void beloong$replaceCheckIfPlayerCheats(CallbackInfo ci) {
        if (!Config.REMOVE_SCULPTOR_ANTI_CHEAT.get()) {
            return; // 配置关闭：不取消原方法，走原版六条判定
        }

        EntitySculptor self = (EntitySculptor) (Object) this;
        Player player = self.getTestingPlayer();

        // 与原版一致：无试炼者 / 非试炼中 / 创造模式 → 不做任何判定
        if (player == null || !self.isTesting() || player.isCreative()) {
            ci.cancel();
            return;
        }

        // 唯一保留的判负：离开试炼区（水平距离）
        double horizontalDistance = player.position()
                .multiply(1.0, 0.0, 1.0)
                .distanceTo(self.position().multiply(1.0, 0.0, 1.0));
        if (horizontalDistance > EntitySculptor.TEST_RADIUS + 4) {
            self.playerCheated();
        }

        // 总是取消原方法，从而移除其余全部判定
        ci.cancel();
    }
}
