package com.zonlong.beloong.mixin.dragonsurvival;

import by.dragonsurvivalteam.dragonsurvival.client.handlers.ClientFlightHandler;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.FlightData;
import by.dragonsurvivalteam.dragonsurvival.server.handlers.ServerFlightHandler;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.registry.ModAttributes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 飞行等级控制的稳定悬停：{@code FLIGHT_LEVEL >= 1} 时锁定高度并取消重力，
 * {@code FLIGHT_LEVEL < 1} 时模拟 DS 原版的非稳定悬停（鞘翅式下坠）。
 *
 * <h3>与旧实现的区别</h3>
 * <ul>
 *   <li><b>重力改由属性实现</b>：不再 mixin 原版 {@code LivingEntity.travel}，改为在
 *       {@code flightControl} 的 TAIL 给玩家挂一个
 *       {@code ADD_MULTIPLIED_TOTAL = -1.0} 的瞬时修饰符，使
 *       {@code LivingEntity.getGravity()} 返回 0。修饰符在下一 tick 的 HEAD 被摘除，
 *       因此窗口恰好只在这一次 {@code travel} 内 —— 这一点是必须的：DS 自己也读
 *       {@code Attributes.GRAVITY}（{@code ClientFlightHandler:395}）来算
 *       {@code ay}/{@code yMotion}，若在它计算前就把属性置 0，稳定悬停与非稳定模拟会双双失效。</li>
 *   <li><b>{@code stableHover} 直接读 DS 的字段</b>：判定与 DS 自己的飞行物理读的是
 *       <b>同一个</b> {@code ServerFlightHandler.stableHover}。这是刻意的——非稳定悬停的模拟是
 *       <b>叠加在 DS 输出之上</b>的，「DS 到底施加了什么」必须与 DS 读到的值同源；
 *       若改用任何副本（例如自行同步一份到客户端），一旦两者更新时机不同就会分叉，
 *       表现为下坠速度既可能偏快（重复叠加）也可能偏慢（该叠加时没叠加）。
 *       DS 的该配置虽是 {@code ConfigSide.SERVER}，但 NeoForge 在连接配置阶段会自动把
 *       SERVER 配置同步给客户端（{@code net.neoforged.neoforge.network.ConfigSync}），
 *       且 DS 自己也在 {@code ConfigHandler.handleConfigReloading} 里随热重载更新该字段。</li>
 *   <li><b>滑翔不再被接管</b>：{@code isGliding} 被排除出判定，DS 原版滑翔物理一字不动。</li>
 *   <li><b>水中零重力收紧</b>：补上 DS 同款的 {@code isAffectedByFluids} /
 *       {@code canStandOnFluid} 检查（对齐 {@code mixins/LivingEntityMixin.java:196}），
 *       避免"站在水面也不下沉"。</li>
 * </ul>
 *
 * <h3>垂直输入</h3>
 * 按下跳跃/下潜键时完全不干预，升降交给 DS 自己处理（其稳定悬停分支用
 * {@code y = 0.4} / {@code y = -0.5}）。
 *
 * <h3>性能</h3>
 * 每客户端 tick 在 {@code flightControl} 首尾各执行一次；非龙玩家、未开启稳定悬停、
 * 或任一守卫不满足时通过早期返回跳过。
 *
 * @see com.zonlong.beloong.registry.ModAttributes#getFlightLevel
 */
@Mixin(value = ClientFlightHandler.class, remap = false)
public abstract class ClientFlightHandlerMixin {

    /** 零重力修饰符的 id，需与 {@link #beloong$ZERO_GRAVITY} 一致以便摘除。 */
    private static final ResourceLocation beloong$ZERO_GRAVITY_ID =
            ResourceLocation.fromNamespaceAndPath("beloong", "stable_hover_zero_gravity");

    /**
     * 把重力乘成 0 的瞬时修饰符。
     *
     * <p>用 {@code ADD_MULTIPLIED_TOTAL = -1.0} 而非 {@code ADD_VALUE = -0.08}：前者的算法是
     * {@code d1 *= 1.0 + amount}（{@code AttributeInstance.java:158-160}），
     * 因此无论基础值与其它修饰符如何叠加，结果恒为 0。</p>
     */
    private static final AttributeModifier beloong$ZERO_GRAVITY =
            new AttributeModifier(beloong$ZERO_GRAVITY_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);

    /**
     * 在 {@code flightControl} 计算之前摘掉零重力修饰符。
     *
     * <p><b>必须无条件执行</b>（不得放在任何 early-return 之后）：否则用户关闭
     * {@code fixStableHoverDrift} 时会残留修饰符，导致重力永久为 0。</p>
     */
    @Inject(method = "flightControl", at = @At("HEAD"), remap = false)
    private static void beloong$clearZeroGravity(CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            beloong$setZeroGravity(player, false);
        }
    }

    /**
     * 在 {@code flightControl} 完成所有飞行动力学计算后注入，按飞行等级决定悬停行为。
     */
    @Inject(method = "flightControl", at = @At("TAIL"), remap = false)
    private static void beloong$fixStableHoverDrift(CallbackInfo ci) {
        if (!Config.FIX_STABLE_HOVER.get()) {
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        if (!beloong$isEligible(player)) {
            return;
        }

        boolean flying = ServerFlightHandler.isFlying(player);
        Input movement = player.input;
        boolean noMoveInput = movement.forwardImpulse == 0 && movement.leftImpulse == 0;

        if (ModAttributes.getFlightLevel(player) >= 1.0) {
            // ===== 稳定悬停：锁定高度 + 取消重力 =====
            // 空中、或水中且符合 DS 同款检查；其余状态不干预。
            if (!flying && !beloong$stableWaterHover(player)) {
                return;
            }

            // ⚠️ 水平方向（ax/az）刻意完全不碰，交给 DS 自己的加减速。
            // 早先这里会在无水平输入时清零 ax/az，等于把 DS 的推力累加器一次性抹掉：
            // 滑翔结束后残余推力消失，表现为"立刻停下"，而 DS 原版是"慢慢减速然后停下"。
            // 用户实测后裁定保留 DS 的手感，故本 Mixin 只负责竖直方向与重力。
            ClientFlightHandlerAccessor.beloong$setAy(0.0);

            Vec3 delta = player.getDeltaMovement();
            player.setDeltaMovement(delta.x, 0, delta.z);

            beloong$setZeroGravity(player, true);
            return;
        }

        // ===== 非稳定悬停：模拟 DS 的 stableHover = false =====
        // 只处理空中：水中时 DS 的 flightControl 走 else 分支把 ax/ay/az 清零，
        // 根本没有 -g 可模拟，保持原版水中行为。
        if (!flying) {
            return;
        }

        // DS 只在两处施加 -g：水平移动分支（ClientFlightHandler.java:499）与
        // else 分支中 wasFlying 为真时（:525）。按同样范围追加 -g：
        //   - 移动时 DS 给 -g + deltaMovement.y，追加后为 -2g + y（即 DS 的 stableHover=false）
        //   - 不移动且 wasFlying 时 DS 给 -g + ay（ay >= 0.99g ⇒ 约 0），追加后约 -1g
        // 若不做 wasFlying 守卫，起飞首 tick（wasFlying == false）会被多减一个 -g，出现一次轻微下沉。
        if (noMoveInput && !ClientFlightHandler.wasFlying) {
            return;
        }

        double gravity = player.getAttributeValue(Attributes.GRAVITY);
        Vec3 delta = player.getDeltaMovement();
        player.setDeltaMovement(delta.x, delta.y - gravity, delta.z);
    }

    /**
     * 与飞行等级无关的共同前置条件。
     *
     * <p>已排除：未同步到 {@code stableHover}、骑乘、暂停、漂浮效果、非龙、翅膀未展开、
     * 无飞行能力、旋转攻击、滑翔、以及竖直操作输入。飞行等级与「空中/水中」由调用方判断。</p>
     */
    private static boolean beloong$isEligible(LocalPlayer player) {
        // 刻意读 DS 自己的字段：判定前提必须与 DS 飞行物理的前提同源（详见类 javadoc）
        if (!ServerFlightHandler.stableHover) {
            return false;
        }
        if (player.isPassenger() || Minecraft.getInstance().isPaused()) {
            return false;
        }
        if (player.hasEffect(MobEffects.LEVITATION)) {
            return false;
        }
        if (!DragonStateProvider.isDragon(player)) {
            return false;
        }

        FlightData flightData = FlightData.getData(player);
        if (!flightData.isWingsSpread() || !flightData.hasFlight()) {
            return false;
        }

        // 旋转攻击保持 DS 原版行为，不锁定高度
        if (ServerFlightHandler.isSpin(player)) {
            return false;
        }

        // 滑翔完全交给 DS 原版（含重力），本 Mixin 不接管
        if (ServerFlightHandler.isGliding(player)) {
            return false;
        }

        // 跳跃/下潜是主动升降操作，交给 DS
        return !player.input.jumping && !player.input.shiftKeyDown;
    }

    /**
     * 水中是否应当锁定高度。
     *
     * <p>对齐 DS 自己的检查（{@code mixins/LivingEntityMixin.java:196}）：必须受流体影响、
     * 且不能是站在流体表面上，否则会变成"站在水面也不下沉"。</p>
     */
    private static boolean beloong$stableWaterHover(LocalPlayer player) {
        if (!player.isInWater() || !player.isAffectedByFluids()) {
            return false;
        }
        return !player.canStandOnFluid(player.level().getFluidState(player.blockPosition()));
    }

    /**
     * 挂载或摘除零重力修饰符。
     *
     * <p>使用瞬时修饰符（{@code addOrUpdateTransientModifier}）：不写入 NBT、不参与同步。
     * 服务端属性同步会调用 {@code removeModifiers()} 清掉客户端自加的修饰符，
     * 但每 tick 的 TAIL 都会幂等重挂，最坏只丢一个 tick。</p>
     */
    private static void beloong$setZeroGravity(LocalPlayer player, boolean on) {
        AttributeInstance gravity = player.getAttribute(Attributes.GRAVITY);
        if (gravity == null) {
            return;
        }
        if (on) {
            gravity.addOrUpdateTransientModifier(beloong$ZERO_GRAVITY);
        } else {
            gravity.removeModifier(beloong$ZERO_GRAVITY_ID);
        }
    }
}
