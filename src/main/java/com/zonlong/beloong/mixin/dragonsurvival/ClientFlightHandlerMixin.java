package com.zonlong.beloong.mixin.dragonsurvival;

import by.dragonsurvivalteam.dragonsurvival.client.handlers.ClientFlightHandler;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.DSAttributes;
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
 *   <li><b>滑翔</b>（本 mixin 的 TAIL 最前段）：去重力 + 跟随视线 + <b>补回重力原本（直接与间接）
 *       提供的那部分能量</b>，<b>不受</b>本模组开关、DS 的 {@code stableHover} 与 {@code flight_level}
 *       门控；<b>但受"是不是龙"门控</b>——判据与 DS 自己的 {@code ClientFlightHandler:343}
 *       ({@code handler.isDragon()}) 同源。</li>
 *   <li><b>非滑翔悬停</b>（TAIL 后段）：{@code FLIGHT_LEVEL >= 1} 时锁定高度并取消重力，
 *       {@code FLIGHT_LEVEL < 1} 时模拟 DS 原版的非稳定悬停（鞘翅式下坠）。</li>
 * </ol>
 *
 * <h3>与旧实现的区别</h3>
 * <ul>
 *   <li><b>重力闸门集中在 HEAD</b>：不再 mixin 原版 {@code LivingEntity.travel}，改为在
 *       {@code flightControl} 的 <b>HEAD</b> 用 {@code ADD_MULTIPLIED_TOTAL = -1.0} 的瞬时修饰符把
 *       重力乘 0。顺序固定为<b>先摘除 → 读基准重力 → 按需挂</b>：先摘是为了读到"未被我们归零"的
 *       真值（修饰符会跨 tick 存活，直接读将恒为 0），该真值供滑翔的重力等效下压使用；
 *       不满足条件时保持摘除。每 tick 必有一次写操作，故不存在残留。
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
 *   <li><b>滑翔由本 mixin 接管</b>：去重力（HEAD）+ 速度方向跟随视线 + 补回重力能量（TAIL），
 *       不再"一字不动"。动因有两层：① DS 只在抬头时提供竖直推力（{@code :430} 的
 *       {@code ay = viewVector.y / 4}，低头只累加水平的 {@code ax/az}），单纯去重力会让玩家飞不下去；
 *       ② DS 原版的滑翔速度谱<b>依赖重力</b>——直接的是 {@code :408} 与 {@code travel} 的 {@code -g}，
 *       间接的是下坠经 {@code :410-413} 的 {@code dM} 换成前向速度。所以"贴合原版速度"要把这两层
 *       分别补回，而<b>不能</b>加常数：rev 3/4 的常数底座既比 DS 累加器的饱和值大 2 倍、
 *       又绕过了它的爬升路径，正是"过快且无缓加速"的来源。</li>
 *   <li><b>只对龙生效</b>：{@code isGliding}/{@code isFlying} 读的是人类也持有的 {@code FlightData}，
 *       而 {@code revertToHumanForm} 不清它、{@code FlightEffect.apply/remove} 又对非龙直接 return
 *       ⇒ "曾滑翔过的龙切成人后"仍满足 {@code isGliding}。HEAD 的零重力与 TAIL 的滑翔分流
 *       因此都必须补上与 {@code ClientFlightHandler:343} 同源的 {@code isDragon} 门，
 *       否则人类按 Ctrl 即可飞行（本缺陷已实测复现）。</li>
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
 * 每客户端 tick 在 {@code flightControl} 首尾各执行一次。HEAD 每次做两次属性写（先摘、再按需挂）
 * 并读一次重力基准；TAIL 在玩家非龙或暂停时立即 {@code return}，滑翔分支次早返回
 * （**不受** {@code Config} / {@code stableHover} / 飞行等级门控），其余情形在未开启稳定悬停
 * 或任一守卫不满足时早期返回。
 *
 * @see com.zonlong.beloong.registry.ModAttributes#getFlightLevel
 */
@Mixin(value = ClientFlightHandler.class, remap = false)
public abstract class ClientFlightHandlerMixin {

    /**
     * 零重力修饰符的 id，需与 {@link #beloong$ZERO_GRAVITY} 一致以便摘除。
     *
     * <p>取中性的 {@code zero_gravity}：同一个修饰符既服务滑翔（HEAD 挂），也服务飞行等级
     * ≥1 的悬停锁定（TAIL 挂），不再只对应"稳定悬停"。</p>
     */
    private static final ResourceLocation beloong$ZERO_GRAVITY_ID =
            ResourceLocation.fromNamespaceAndPath("beloong", "zero_gravity");

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
     * 滑翔时速度方向向视线插值的系数。<b>本系数只管方向</b>；速度大小由"重力等效下压"与
     * {@link #beloong$GLIDE_LEVEL_FORWARD_TARGET} 共同决定（见 {@code beloong$followLook}）。
     *
     * <p><b>必须 {@code 0 < T < 0.5}</b>：{@code delta.lerp(target, T)} 结果为零向量要求
     * {@code (1-T)/T == 1}，即恰好 {@code T == 0.5}——那时 180° 掉头会让
     * {@code normalize()} 除零。{@code T = 0.10} 对应约 22 tick（≈1.1 s）转过 90%。</p>
     */
    private static final double beloong$GLIDE_TURN = 0.10;

    /**
     * 速度下限：低于此值不做方向插值。
     *
     * <p>由 {@code Vec3.normalize()} 的零向量阈值决定：该实现长度低于 {@code 1.0E-4} 时直接返回
     * {@code ZERO}。而插值后的长度下界是 {@code (1 - 2·GLIDE_TURN)·speed = 0.8·speed}
     * （两个等长向量插值的取模最小值为 {@code |1-2T|}），所以守卫必须留出这个折扣——
     * 否则 {@code speed ∈ (1.0E-4, 1.25E-4]} 时 {@code normalize} 仍返回零向量，
     * 速度被随后的注入顶替，"大小严格保持"在该窗口不成立。</p>
     */
    private static final double beloong$NORMALIZE_EPSILON = 1.0E-4 / (1.0 - 2.0 * beloong$GLIDE_TURN);

    /**
     * "近水平带"：{@code |look.y|} 小于此值即视为平视（约 ±11.5°），改用<b>前向等效</b>替代下压。
     *
     * <p>为什么需要它：重力时代平视会缓沉（终端约 {@code 0.79} 格/tick），那个下坠经 DS
     * {@code :410-413} 的 {@code dM} 换成前向速度。而需求（rev 1）要求"滑翔不受重力影响"、
     * 不让它缓沉，于是必须把"那笔没发生的下坠本会换来的前向速度"直接补上——即"前向等效"。</p>
     *
     * <p>带内下压与带外下压是<b>互补</b>的（带内乘 {@code 1-levelness}，带外 {@code levelness = 0}），
     * 因此俯角扫过带边界时前向速度连续：带外由 DS 自己的 {@code dM} 给出（约 {@code 0.08}），
     * 带内由 {@link #beloong$GLIDE_LEVEL_FORWARD_TARGET}（{@code 0.079}）给出。</p>
     */
    private static final double beloong$GLIDE_LEVEL_BAND = 0.2;

    /**
     * 平视前向等效的目标推力（在 {@code FLIGHT_SPEED = 1} 下标定）。
     *
     * <p>不是新发明的数：重力时代平视的稳态下坠约 {@code 0.79} 格/tick，经 DS {@code :410-413} 的
     * {@code dM = 0.1·vd·FS·|y|} 换算即 {@code 0.1 × 1 × 1 × 0.79 ≈ 0.079}/tick；
     * 再除以水平刹车率 {@code 0.91 × 0.99 ≈ 9.91%/tick} 得终端 {@code ≈0.80} 格/tick，
     * 与 DS 原版平视一致。</p>
     *
     * <p>刹车率的前提已核实：DS 的滑翔<b>不</b>走原版 {@code isFallFlying()} 分支
     * （DS 全源码无 {@code setSharedFlag}/{@code startFallFlying}，而 {@code isFallFlying()} 只是
     * {@code getSharedFlag(7)}），故原版 {@code travel} 的 {@code f3 = 0.91} 与 {@code -g} 都照常生效。</p>
     */
    private static final double beloong$GLIDE_LEVEL_FORWARD_TARGET = 0.079;

    /**
     * 前向等效的爬升斜率，等于 DS {@code :427-428} 的 {@code FS × 2 / 500}。
     *
     * <p>DS 的前向推力是<b>线性爬升</b>的累加器（约 20 tick 从 0 爬到目标），这正是"缓缓加速"的观感来源。
     * 常数注入会变成阶跃（rev 3/4 的实测症状），指数缓动则与 DS 不同构，故按 DS 的斜率复刻。</p>
     */
    private static final double beloong$GLIDE_FORWARD_RAMP = 0.004;

    /**
     * 最近一次读到的<b>未被归零</b>的重力值（已含 DS 体型对重力的修饰）。
     *
     * <p>必须在挂零重力修饰符<b>之前</b>读：一旦归零，{@code getValue()} 恒为 {@code 0}，
     * 重力等效下压会退化成 0。实现上刻意"先摘除、再读、再按需挂"——因为修饰符会跨 tick 存活，
     * 若直接读，滑翔中永远读到 0。</p>
     */
    private static double beloong$gravityBaseline;

    /** 平视前向等效的累加器状态（沿 DS 的斜率上升，非激活时按 0.98 衰减）。 */
    private static double beloong$levelForwardAccumulator;

    /**
     * 滑翔时让速度跟随视线：<b>先转方向，再按"重力等效"补能量</b>。
     *
     * <h4>第一步：方向插值（大小严格保持）</h4>
     * <p>DS 的竖直能量<b>只有向上</b>：{@code :449} 的 {@code ay} 与 {@code :415-419} 的
     * {@code 3.2·delta} 都要求抬头；低头分支 {@code :426-428} 只累加水平 {@code ax/az}，
     * 且 {@code :446-447} 明确 {@code add(ax, 0, az)} 不消费 {@code ay}；{@code :410-413} 名为
     * {@code downwardMomentum} 实为<b>向上</b>的回收阻尼。向下的唯一能源是重力，已被 HEAD 归零。</p>
     *
     * <h4>第二步：补回重力原本（直接与间接）提供的那部分</h4>
     * <p><b>关键认识</b>：DS 原版的滑翔速度谱<b>依赖重力</b>，而且是两层的——
     * ① 直接：DS {@code :408} 的 {@code g·(−1+0.75·vd)} 与原版 {@code travel:2331} 的 {@code −g}；
     * ② 间接：下坠速度经 DS {@code :410-413} 的 {@code dM} 换成<b>前向</b>速度（平视约 {@code 0.079}/tick）。
     * 所以"贴合原版速度"不能靠调常数，必须把这两层分别补回来：</p>
     *
     * <pre>
     *   直接（带外，|look.y| >= GLIDE_LEVEL_BAND）：
     *       y -= g_baseline · (2 − 0.75·vd)        // vd = cos²θ；= :408 的 g·(−1+0.75vd) 加 原版 −g
     *   间接（带内，|look.y| &lt;  GLIDE_LEVEL_BAND，因为不缓沉所以 dM 不会发生）：
     *       沿视线的水平方向补 levelForwardAccumulator · levelness
     *       levelForwardAccumulator 沿 DS :427-428 的斜率线性爬升到 GLIDE_LEVEL_FORWARD_TARGET
     * </pre>
     *
     * <p>两项<b>互补</b>（带内下压乘 {@code 1-levelness}、带外 {@code levelness = 0}），
     * 因此俯角扫过带边界时前向速度连续——这既保住了 rev 4 修掉的"阶跃"不再复发，
     * 也避免了与 DS 自己的 {@code dM}/{@code ax/az} 重复叠加。</p>
     *
     * <h4>为什么不再用"沿视线的加速"</h4>
     * <p>rev 3/4 曾用一个<b>常开、第 1 tick 就满额的常数</b>（{@code 0.25}）沿视线加速，它是
     * "速度远快于原版 + 起步无缓加速"两个症状的共同来源：DS 自己的前向推力是
     * {@code :426-428} 的<b>线性爬升累加器</b>（{@code 0.004·FS}/tick、约 30 tick 饱和于 {@code 0.12·FS}、
     * 只在低头时累加），而 {@code 0.25} 既比它的饱和值大 2 倍、又绕过了整条爬升路径，
     * 还叠加在它之上。</p>
     *
     * <h4>两处有意的取舍</h4>
     * <ul>
     *   <li><b>抬头完全不碰</b>：DS 的抬头是"向上加速 + 水平略减速"，并非沿视线加速；
     *       不介入才能保住其既有手感，也避免双重加速（只按 DS 的规则让前向累加器 {@code ×0.98} 衰减）。</li>
     *   <li><b>不加正反馈、不设上限</b>：rev 3 的 {@code 0.128·FS·h} 与 rev 5 的封顶一并删除。
     *       它们是为"只有抬头有加速"打的替代品，在补回重力链之后就是双重计入；
     *       而且源封顶只在 {@code h > 1} 后生效，{@code h < 1} 区间的环增益随 {@code FS} 平方增长
     *       （45° 约 {@code 0.51·FS²}，{@code FS ≈ 1.4} 即临界）⇒ 删除顺带消除了该发散路径。</li>
     * </ul>
     *
     * <h4>量级（一阶解析，FS = 1）</h4>
     * <p>水平刹车率 {@code 0.91 × 0.99 ≈ 9.91%/tick}；竖直刹车率 {@code 0.98 × 0.98 = 3.96%/tick}
     * 再加 {@code :410-413} 的 {@code 0.1·vd}。</p>
     * <ul>
     *   <li><b>平视</b>：下压被让位，前向 {@code 0.079} ⇒ 终端 {@code ≈0.80} 格/tick（≈16 格/秒）</li>
     *   <li><b>低头 45°</b>：下压 {@code 0.143} ⇒ 竖直 {@code ≈1.60}；DS 的 {@code dM} 与 {@code ax/az}
     *       把下坠换成前向 {@code ≈2.02} ⇒ 总 {@code ≈2.58} 格/tick（≈52 格/秒）</li>
     *   <li><b>竖直向下</b>：下压 {@code 0.176} ⇒ 竖直 {@code ≈4.44}；{@code dM} 因 {@code horizontalView = 0}
     *       被跳过，水平只剩 {@code ax/az} 的 {@code 0.12} ⇒ {@code ≈1.21}</li>
     * </ul>
     * <p>以上与"重力时代 DS 原版"逐项一致——因为注入量与刹车率都和那个时代相同。</p>
     */
    private static void beloong$followLook(LocalPlayer player) {
        Vec3 delta = player.getDeltaMovement();
        double speed = delta.length();
        if (speed <= beloong$NORMALIZE_EPSILON) {
            // 速度过零时"方向"无意义，且 normalize 不可用
            return;
        }

        Vec3 look = player.getLookAngle();
        double flightSpeed = player.getAttributeValue(DSAttributes.FLIGHT_SPEED);

        // 1) 方向插值：方向向视线靠 GLIDE_TURN，大小严格保持
        Vec3 dir = delta.lerp(look.scale(speed), beloong$GLIDE_TURN).normalize();
        Vec3 result = dir.scale(speed);

        // 2) 补能量（仅"非抬头"；抬头完全交给 DS 自己的 ay 与 3.2·delta）
        if (look.y <= 0.0) {
            double vd = 1.0 - look.y * look.y;                    // cos²θ，即 DS 的 verticalDelta
            double levelness = 1.0 - Math.abs(look.y) / beloong$GLIDE_LEVEL_BAND;
            if (levelness < 0.0) {
                levelness = 0.0;                                   // |look.y| >= 0 ⇒ 不会超过 1
            }

            // 2a) 重力等效下压：A(θ) = g·(2 − 0.75·vd)，即 DS :408 加原版 travel 的 −g。
            //     带内按 (1-levelness) 让位给 2b，保证俯角扫过带边界时前向速度连续
            double down = beloong$gravityBaseline * (2.0 - 0.75 * vd) * (1.0 - levelness);
            result = result.add(0.0, -down, 0.0);

            // 2b) 平视前向等效：补上"因不缓沉而没发生的那次下坠本会经 dM 换来的前向速度"
            if (levelness > 0.0) {
                // 沿 DS :427-428 的斜率线性爬升，上限即目标（带 FS，与 DS 同构）
                beloong$levelForwardAccumulator = Math.min(
                        beloong$levelForwardAccumulator + beloong$GLIDE_FORWARD_RAMP * flightSpeed,
                        beloong$GLIDE_LEVEL_FORWARD_TARGET * flightSpeed);
            } else {
                beloong$levelForwardAccumulator *= 0.98;           // 与 DS 非低头时对 ax/az 的衰减一致
            }

            double forward = beloong$levelForwardAccumulator * levelness;
            Vec3 lookH = new Vec3(look.x, 0.0, look.z);
            if (forward > 0.0 && lookH.length() > beloong$NORMALIZE_EPSILON) {
                result = result.add(lookH.normalize().scale(forward));
            }
        } else {
            // 抬头：DS 自己给竖直推力，这里只按 DS 的规则衰减前向累加器
            beloong$levelForwardAccumulator *= 0.98;
        }

        player.setDeltaMovement(result);
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
     * 若 DS 日后改动这一点，最坏表现是 TAIL 这一 tick 按"非滑翔"处理（在已归零的重力之上
     * 多追一次 {@code -g}，或白挂一 tick 修饰符）——不会崩溃、不会残留，因为 HEAD 是唯一的
     * 摘除点且每 tick 必复位。</p>
     *
     * <p><b>龙判定必须与 DS 同源</b>：{@code isGliding}/{@code isFlying} 只读 {@code FlightData}
     * （人类同样持有的 NeoForge 附件）与 {@code Player} 状态，<b>不含"是不是龙"</b>。DS 自己在每个
     * 消费者处都补了这道门（{@code ClientFlightHandler:343} 的 {@code handler.isDragon()}、
     * {@code ServerFlightHandler:151} 的坠落伤害、{@code :313} 的饥饿收翅、{@code :117} 的落地收翅），
     * 而 {@code revertToHumanForm}（{@code DragonStateHandler:872-888}）<b>不清 {@code FlightData}</b>、
     * {@code FlightEffect.apply/remove} 又对非龙直接 return ⇒ "曾滑翔过的龙切成人类后"仍满足
     * {@code isGliding}。故本模组必须自己补这道门，否则人类按 Ctrl 即可飞行。</p>
     */
    @Inject(method = "flightControl", at = @At("HEAD"), remap = false)
    private static void beloong$glideGravityGate(CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        // 先摘除（幂等）再读：修饰符会跨 tick 存活，直接读的话滑翔中永远读到 0，
        // 重力等效下压（beloong$followLook 的 2a）就会退化成 0。
        beloong$setZeroGravity(player, false);
        beloong$gravityBaseline = player.getAttributeValue(Attributes.GRAVITY);

        // 门 = "是龙"（与 ClientFlightHandler:343 同源）且 isGliding
        if (DragonStateProvider.isDragon(player) && ServerFlightHandler.isGliding(player)) {
            beloong$setZeroGravity(player, true);
        }
    }

    /**
     * 在 {@code flightControl} 完成所有飞行动力学计算后注入，按状态分流：滑翔 → 悬停锁定 → 非稳定模拟。
     *
     * <p>分流顺序即语义优先级：<b>滑翔最先</b>（去重力已由 HEAD 完成，这里做视线跟随与加速），
     * 之后才是悬停锁定与非稳定模拟。</p>
     */
    @Inject(method = "flightControl", at = @At("TAIL"), remap = false)
    private static void beloong$flightTweaks(CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        // ===== 暂停守卫 =====
        // ClientTickEvent 在 Minecraft.tick() 开头无条件触发（fireClientTickPre 排在 !pause 之前），
        // 而 level.tickEntities() / level.tick() 被暂停挡住、DS 自己的方法体也因 isPaused() 提前返回。
        // 于是暂停期间：我们的注入照跑，DS 的 ELYTRA_FLY_DRAG 与原版 travel 的 0.91 摩擦却全都不执行
        // ⇒ 推力无任何对冲地逐 tick 累积（单机滑翔中按 Esc 数秒即可到几十格/tick），恢复后被一次性
        // 消费（瞬移数千格，或被服务端判 moved too quickly）。必须在这里直接返回。
        if (Minecraft.getInstance().isPaused()) {
            return;
        }

        // ===== 龙判定（与 HEAD、与 ClientFlightHandler:343 同源） =====
        // 人类在此直接返回：不给方向、不给加速、不碰重力。这是"曾滑翔过的龙切成人后
        // 仍能 Ctrl 飞行"这一缺陷的修复点之一（另一半在 HEAD 的零重力门）。
        // 注：不能用"有 FlightData / hasFlight / 翅膀展开"代替——三者对人类同样为真。
        if (!DragonStateProvider.isDragon(player)) {
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
            //
            // 注：早先这里还通过一个 @Accessor 把 DS 的 ay 清零，该调用与访问器均已删除——
            // 那是语义空操作：本分支生效时 stableHover 必为真，DS 下一 tick 会在 :481
            // 把 ay 抬回 max(ay, 1.1g)（快时），或走 :531 自行清零（慢时），两种结果都不取决于该调用。
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
        // 龙判定：TAIL 的统一前置守卫（与 ClientFlightHandler:343 同源）已经挡掉非龙，
        // 此处保留为**本方法自己的前置条件**（自包含、防后来者移动那处守卫），不是第二处语义来源。
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
