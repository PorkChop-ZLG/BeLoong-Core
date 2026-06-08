package com.zonlong.beloong.registry;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.network.flight.SyncWingsSpread;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.FlightData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * "禁空"状态效果——每级降低 1 点飞行等级，若等级降至负数则强制收翅。
 *
 * <h3>博弈设计</h3>
 * 敌方或 BOSS 可对龙施加此效果来限制飞行能力，龙则可通过升级翅膀技能反制：
 * <ul>
 *   <li>禁空 I（amp 0）: -1 飞行等级</li>
 *   <li>禁空 II（amp 1）: -2 飞行等级</li>
 *   <li>以此类推——每级多降 1 点</li>
 * </ul>
 * 龙的翅膀技能等级 2（成年龙）提供 +1 飞行等级，可抵消 1 级禁空。
 *
 * <h3>实现机制</h3>
 * <ol>
 *   <li><b>属性修饰符：</b>由 {@link ModMobEffects} 注册时通过
 *       {@code addAttributeModifier} 添加到 {@code dragonsurvival:flight_level}。
 *       原版自动按 {@code amount × (amplifier + 1)} 缩放。</li>
 *   <li><b>强制收翅：</b>{@link #onEffectStarted} 在属性修饰符生效后检查飞行等级，
 *       若 &lt; 0 且玩家正在飞行，则强制收起翅膀并发送提示消息。</li>
 *   <li><b>效果结束后：</b>不自动恢复飞行——玩家需手动按 G 键重新展翅。</li>
 * </ol>
 *
 * <h3>与 BROKEN_WINGS 的区别</h3>
 * BROKEN_WINGS 无条件强制收翅（任何等级均触发）。禁空效果仅在飞行等级降至负数
 * 时才收翅，保留了"低等级禁空只能削弱悬停但不能阻止飞行"的梯度设计。
 *
 * @see ModAttributes#getFlightLevel(Player)
 */
public class FlightBanEffect extends MobEffect {

    /** 深红色 (#8B0000)，表示飞行受限的危险状态 */
    public FlightBanEffect() {
        super(MobEffectCategory.HARMFUL, 0x8B0000);
    }

    /**
     * 效果生效时的回调（新施加 / 等级变化时触发）。
     *
     * <p>调用时机：vanilla 在 {@code MobEffectInstance.applyEffect()} 中
     * <b>先应用属性修饰符</b>再调用此方法。因此此处的
     * {@code getFlightLevel(player)} 已经包含了刚生效的属性修饰符。</p>
     *
     * @param entity    受影响实体
     * @param amplifier 效果放大器（0 = I 级, 1 = II 级, ...）
     */
    @Override
    public void onEffectStarted(LivingEntity entity, int amplifier) {
        // 仅在服务端处理，仅对龙玩家生效
        if (!entity.level().isClientSide()
                && entity instanceof Player player
                && DragonStateProvider.isDragon(player)) {

            FlightData flightData = FlightData.getData(player);

            // 正在飞行 且 有效飞行等级 < 0 → 强制收翅
            if (flightData.areWingsSpread && ModAttributes.getFlightLevel(player) < 0.0) {
                flightData.areWingsSpread = false;

                // 同步展翅状态到所有追踪该玩家的客户端
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                        new SyncWingsSpread(player.getId(), false));

                // 提示玩家为何无法飞行
                player.sendSystemMessage(Component.translatable("message.beloong.flight_banned"));
            }
        }
    }
}
