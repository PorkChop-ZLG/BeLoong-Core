package com.zonlong.beloong.registry;

import com.zonlong.beloong.compat.healthbars.ExecuteMarkSyncPayload;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 把「谁身上有斩杀标记、标记的 amplifier 是多少」发给能看见该生物的玩家。
 *
 * <h3>为什么需要它</h3>
 * 原版<b>不</b>把生物的 {@code MobEffectInstance} 同步给旁观者（详见 {@link ExecuteMarkSyncPayload}
 * 的类注释）。这个类负责把那几个字节补上，用法上等价于「原版对玩家自己的效果所做的那件事」。
 *
 * <h3>两条触发路径，缺一不可</h3>
 * <ol>
 *   <li><b>标记本身变化</b>（挂上 / 刷新 / 到期 / 被移除）——
 *       由 {@code LivingEntityExecuteMarkSyncMixin} 注入原版
 *       {@code onEffectAdded / onEffectUpdated / onEffectRemoved} 后调用
 *       {@link #onEffectChanged}。这三条覆盖了<b>所有</b>移除路径（含到期，
 *       {@code LivingEntity:807-811}）与所有施加路径（含指令直接给效果），
 *       所以本类不需要在任何业务代码里散落发送调用。</li>
 *   <li><b>玩家才开始追踪这只生物</b> —— {@link PlayerEvent.StartTracking}。
 *       原版在此时会补发它自己那套 effect 包（{@code ServerPlayer:2104}），但不会补发我们的，
 *       所以后进入视野的玩家必须由这里补一次。</li>
 * </ol>
 */
public final class ExecuteMarkSyncHandler {

    /** 发送给「正在追踪该实体的所有玩家」当前的真实状态（无标记则发 {@link ExecuteMarkSyncPayload#NO_MARK}）。 */
    public static void sendCurrent(final LivingEntity entity) {
        if (entity.level().isClientSide) {
            return;
        }

        MobEffectInstance mark = entity.getEffect(ModMobEffects.EXECUTE_THRESHOLD);
        int amplifier = mark == null ? ExecuteMarkSyncPayload.NO_MARK : mark.getAmplifier();
        PacketDistributor.sendToPlayersTrackingEntity(
                entity, new ExecuteMarkSyncPayload(entity.getId(), amplifier));
    }

    /**
     * 某个效果在实体身上发生变化时调用（由 {@code LivingEntityExecuteMarkSyncMixin} 的三处注入）。
     * <p>
     * 只关心我们自己的效果；其他效果一律早退，所以这个钩子虽然挂在
     * {@code LivingEntity} 上，实际开销只是每次效果变化一次 Holder 比较。
     * <p>
     * 三处注入统一「重算当前状态再发」，因此是幂等的：挂上与刷新都发同样的包，
     * 移除时 {@code getEffect} 已返回 null（注入点在原版移除之后），自然发出 `NO_MARK`。
     */
    public static void onEffectChanged(final LivingEntity entity, final MobEffectInstance instance) {
        if (entity.level().isClientSide || !instance.is(ModMobEffects.EXECUTE_THRESHOLD)) {
            return;
        }

        sendCurrent(entity);
    }

    /** 玩家开始追踪某实体时补发一次（原版只补发它自己那套）。 */
    @SubscribeEvent
    public void onStartTracking(final PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof LivingEntity living) {
            sendCurrent(living);
        }
    }
}
