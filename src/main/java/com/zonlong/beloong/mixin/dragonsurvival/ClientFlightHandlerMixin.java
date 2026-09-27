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
 * 两条彼此独立的干预：
 *
 * <ol>
 *   <li><b>滑翔</b>（本 mixin 的 TAIL 最前段）：去重力 + 跟随视线，<b>不受</b>本模组开关、
 *       DS 的 {@code stableHover} 与 {@code flight_level} 门控。</li>
 *   <li><b>非滑翔悬停</b>（TAIL 后段）：{@code FLIGHT_LEVEL >= 1} 时锁定高度并取消重力，
 *       {@code FLIGHT_LEVEL < 1} 时模拟 DS 原版的非稳定悬停（鞘翅式下坠）。</li>
 * </ol>
 *
 * <h3>与旧实现的区别</h3>
 * <ul>
 *   <li><b>重力闸门集中在 HEAD</b>：不再 mixin 原版 {@code LivingEntity.travel}，改为在
 *       {@code flightControl} 的 <b>HEAD</b> 按 {@code isGliding()} 挂/摘一个
 *       {@code ADD_MULTIPLIED_TOTAL = -1.0} 的瞬时修饰符。<b>滑翔挂、非滑翔摘</b>，
 *       每 tick 必有一次写操作，故不存在残留。
 *       <p>之所以一个修饰符够用：DS 读的 {@code Attributes.GRAVITY}
 *       （{@code ClientFlightHandler:395} → {@code :408}）与原版 {@code LivingEntity.travel}
 *       读的 {@code d0} 是<b>同一个</b>属性实例，两者同 tick 一起归零。</p>
 *       <p>而"非滑翔悬停"分支仍在 TAIL 挂、下一 tick 的 HEAD 摘：DS 自己要读<b>真实</b>重力
 *       来算 {@code ay = max(ay, gravity * 1.1)}（{@code :481}）与
 *       {@code -gravity + ay}（{@code :525}），若在它计算前就置 0，稳定悬停与非稳定模拟会双双失效。</p></li>
 *   <li><b>{@code stableHover} 直接读 DS 的字段</b>：判定与 DS 自己的飞行物理读的是
 *       <b>同一个</b> {@code ServerFlightHandler.stableHover}。这是刻意的——非稳定悬停的模拟是
 *       <b>叠加在 DS 输出之上</b>的，「DS 到底施加了什么」必须与 DS 读到的值同源；
 *       若改用任何副本（例如自行同步一份到客户端），一旦两者更新时机不同就会分叉，
 *       表现为下坠速度既可能偏快（重复叠加）也可能偏慢（该叠加时没叠加）。
 *       DS 的该配置虽是 {@code ConfigSide.SERVER}，但 NeoForge 在连接配置阶段会自动把
 *       SERVER 配置同步给客户端（{@code net.neoforged.neoforge.network.ConfigSync}），
 *       且 DS 自己也在 {@code ConfigHandler.handleConfigReloading} 里随热重载更新该字段。</li>
 *   <li><b>滑翔由本 mixin 接管</b>：去重力（HEAD）+ 速度方向跟随视线（TAIL），不再"一字不动"。
 *       改动的动因是 DS 只在抬头时提供竖直推力（{@code :430} 的 {@code ay = viewVector.y / 4}，
 *       低头只累加水平的 {@code ax/az}），所以单纯去重力会让玩家飞不下去；
 *       补上的向下来源就是这里的方向插值。</li>
 *   <li><b>水中零重力收紧</b>：补上 DS 同款的 {@code isAffectedByFluids} /
 *       {@code canStandOnFluid} 检查（对齐 {@code mixins/LivingEntityMixin.java:196}），
 *       避免"站在水面也不下沉"。</li>
 * </ul>
 *
 * <h3>垂直输入</h3>
 * 非滑翔时按下跳跃/下潜键完全不干预，升降交给 DS 自己处理（其稳定悬停分支用
 * {@code y = 0.4} / {@code y = -0.5}）。滑翔时升降由视线决定。
 *
 * <h3>性能</h3>
 * 每客户端 tick 在 {@code flightControl} 首尾各执行一次。HEAD 只在 {@code player != null} 时写一次
 * 属性；TAIL 的滑翔分支最早 {@code return}（**不受** {@code Config} / {@code stableHover} /
 * 飞行等级门控），其余情形在非龙、未开启稳定悬停或任一守卫不满足时早期返回。
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
     * 滑翔时速度方向向视线插值的系数。
     *
     * <p><b>必须 {@code 0 < T < 0.5}</b>：{@code delta.lerp(target, T)} 结果为零向量要求
     * {@code (1-T)/T == 1}，即恰好 {@code T == 0.5}——那时 180° 掉头会让
     * {@code normalize()} 除零。{@code T = 0.10} 对应约 22 tick（≈1.1 s）转过 90%。</p>
     */
    private static final double beloong$GLIDE_TURN = 0.10;

    /**
     * 速度下限：低于此值不做方向插值。
     *
     * <p>必须 <b>≥</b> {@code Vec3.normalize()} 内部的零向量阈值 {@code 1.0E-4}（该实现低于此值
     * 直接返回 {@code ZERO}）。若守卫取得更小，落在两者之间的速度会被 {@code normalize} 清零，
     * 与"大小严格保持"矛盾。</p>
     */
    private static final double beloong$NORMALIZE_EPSILON = 1.0E-4;

    /**
     * 滑翔时让速度方向跟随视线，**大小严格保持**。
     *
     * <p>去重力后 DS 只提供向上的竖直分量（{@code ClientFlightHandler:430} 仅在抬头时给
     * {@code ay = viewVector.y / 4}，低头只累加水平的 {@code ax/az}），所以"低头能否下降"
     * 完全由这里提供——这正是上一版"只去重力"方案飞不下去的原因。</p>
     *
     * <p>末尾 {@code normalize().scale(speed)} 只抵消"两个等长向量插值会缩短弦长"这一几何效应，
     * <b>不动</b> DS 在 TAIL 之前已施加的 {@code ELYTRA_FLY_DRAG} 与
     * {@code ClientFlightHandler:410-413} 的俯冲阻尼——后者用的是 {@code deltaMovement.y}
     * 而非重力，所以去重力后"俯冲换速度"与自限终端速度都照常工作。</p>
     */
    private static void beloong$followLook(LocalPlayer player) {
        Vec3 delta = player.getDeltaMovement();
        double speed = delta.length();
        if (speed <= beloong$NORMALIZE_EPSILON) {
            // 速度过零时"方向"无意义，且 normalize 不可用
            return;
        }
        Vec3 target = player.getLookAngle().scale(speed);
        player.setDeltaMovement(delta.lerp(target, beloong$GLIDE_TURN).normalize().scale(speed));
    }

    /**
     * 在 {@code flightControl} 计算之前设置重力闸门：**滑翔 → 挂零重力修饰符；非滑翔 → 摘除**。
     *
     * <p>DS 的 {@code ClientFlightHandler:408} 与原版 {@code LivingEntity.travel} 的 {@code d0}
     * 读的是<b>同一个</b> {@code Attributes.GRAVITY}，所以这一个修饰符就能同时消灭两个向下来源
     * ——这正是本模组不必复制 DS 任何算式的原因。</p>
     *
     * <p><b>必须每 tick 无条件执行一次写操作</b>（挂或摘，二者必居其一）：修饰符的挂载点只有这里
     * 与 TAIL 的悬停分支，若把它放在任何 early-return 之后，用户关闭 {@code fixStableHoverDrift}
     * 或退出滑翔时会残留修饰符，导致重力永久为 0。</p>
     *
     * <p><b>同 tick 配对的前提</b>：HEAD 与 TAIL 夹在<b>同一次</b> {@code flightControl} 调用内，
     * 而 {@code flightControl} 方法体不写 {@code isGliding()} 的任何判定输入（sprint / 翅膀 /
     * onGround / 食物均只读；sprint 的翻转在 {@code LocalPlayer.aiStep}，属同一 tick 但更晚的
     * {@code player.tick()}），故本处与 TAIL 处调 {@code ServerFlightHandler.isGliding} 必然同值。
     * 若 DS 日后改动这一点，最坏表现是"一 tick 内挂了又摘"，不会崩溃。</p>
     */
    @Inject(method = "flightControl", at = @At("HEAD"), remap = false)
    private static void beloong$glideGravityGate(CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            beloong$setZeroGravity(player, ServerFlightHandler.isGliding(player));
        }
    }

    /**
     * 在 {@code flightControl} 完成所有飞行动力学计算后注入。
     *
     * <p>分流顺序即语义优先级：<b>滑翔最先</b>（去重力已由 HEAD 完成，这里只做视线跟随），
     * 之后才是 {@code fixStableHoverDrift} 的悬停锁定与非稳定模拟。</p>
     */
    @Inject(method = "flightControl", at = @At("TAIL"), remap = false)
    private static void beloong$fixStableHoverDrift(CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        // ===== 滑翔：独立于本模组开关、DS 的 stableHover 与飞行等级 =====
        // 需求要求滑翔"不受重力影响，且无论 stable_hover 是否开启、flight_level 是否支持稳定悬停"。
        // 这里用最优先 return 来保证该性质由**控制流**承担：后来谁改下面的 Config 或 isEligible
        // 条件，都不会把滑翔重新圈进去。
        if (ServerFlightHandler.isGliding(player)) {
            // 旋转攻击保持 DS 原版动力学：只跳过滤线，重力仍已由 HEAD 归零
            if (!ServerFlightHandler.isSpin(player)) {
                beloong$followLook(player);
            }
            return;
        }

        if (!Config.FIX_STABLE_HOVER.get()) {
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
     * 无飞行能力、旋转攻击、以及竖直操作输入。飞行等级与「空中/水中」由调用方判断。</p>
     *
     * <p><b>滑翔不在此列</b>：滑翔已在 TAIL 最前段 `return`（去重力 + 视线跟随均在那里完成），
     * 本方法不可能再遇到滑翔态。保持"只有一处滑翔判定"可避免与 DS 的定义分叉。</p>
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

        // 注：滑翔<b>不</b>在这里排除——它已在 TAIL 的最前段 return（含去重力与视线跟随）。
        // 把排除项留在本方法里会让"什么算滑翔"出现第二处判定，一旦与 DS 的定义分叉就会
        // 重新落到 -g 追加分支上（这正是上一轮的 bug 形态）。

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
