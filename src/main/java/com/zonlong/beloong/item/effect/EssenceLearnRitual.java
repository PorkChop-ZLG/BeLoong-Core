package com.zonlong.beloong.item.effect;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 「从魔源习得技能」的粒子仪式 —— 一条**与音效时间轴对齐**的服务端编排。
 *
 * <h2>它画什么</h2>
 *
 * <ol>
 *   <li><b>法阵</b>（脚下，贴地）：<b>最外围一圈 + 中间一个五角星</b>（星的 5 个尖落在外圈上）。
 *       全部用 {@code minecraft:dust}（原版唯一可任意着色的普通粒子），颜色取该元素魔源的硬编码色
 *       —— 与 Title、tooltip 用的是同一个值。</li>
 *   <li><b>高潮爆发</b>：以玩家为球心向外膨胀的球壳，用的是<b>该元素对应的粒子类型</b>。</li>
 * </ol>
 *
 * <h2>时间轴（tick = 1/20 秒；音效总长 3.0199 秒）</h2>
 *
 * 音效的实测形状（逐 100 ms RMS，见 {@code tools/audio_rms_profile.py}）：0.00→1.20 s 从 -52 dB
 * 单调爬到 -9 dB 的<b>渐强</b>，<b>1.30 s 是峰值</b>，主体在 1.20–1.90 s，之后衰减到 ~2.85 s。
 * 编排照这个形状走：
 *
 * <table border="1">
 *   <caption>时间轴</caption>
 *   <tr><th>tick</th><th>秒</th><th>音频</th><th>粒子</th></tr>
 *   <tr><td>0–23</td><td>0.00–1.20</td><td>渐强（爬到 -9 dB）</td><td>法阵被"画"出来 —— <b>1.20 s 刚好画完</b>，与高潮同拍</td></tr>
 *   <tr><td>24–37</td><td>1.20–1.90</td><td><b>高潮（主体）</b></td><td>法阵完整并旋转 + <b>球壳爆发</b>（半径线性外扩）</td></tr>
 *   <tr><td>38–55</td><td>1.90–2.80</td><td>衰减到尾音</td><td>爆发密度降到 0（冲击波余韵）；法阵继续</td></tr>
 *   <tr><td>56–71</td><td>2.80–3.60</td><td>已结束</td><td>只剩法阵，密度线性降到 0</td></tr>
 *   <tr><td>72–79</td><td>3.60–4.00</td><td>—</td><td><b>不再产生新粒子</b>，已有粒子在存活期内散尽（约 4.1 s）</td></tr>
 * </table>
 *
 * ⇒ <b>法阵比音效多留约 1 秒</b>（用户裁定：不必与音效长度完全贴合）；<b>爆发不延长</b>，
 * 在 2.80 s 停止生成，与音频的衰减段一起收尾。
 *
 * <h2>尺寸随体型缩放</h2>
 *
 * 法阵与爆发的半径都乘以一个"体型倍数"：直接取原版自己的定义
 * {@link LivingEntity#getScale()}（即 {@code minecraft:generic.scale} 属性的值，默认 1.0 ⇒ 1.0 = 正常体型），
 * 再钳到 <b>1× ~ 4×</b>（用户裁定：<b>只放大不缩小，最多 4 倍</b>）。
 * 默认体型 ⇒ 外圈<b>直径 9 格</b>（用户指定）。
 * <p>
 * 用 {@code getScale()} 而不是别的东西，是因为它<b>就是</b>原版"体型"的定义 —— 渲染器
 * （{@code LivingEntityRenderer} 的 {@code poseStack.scale(f, f, f)}）与碰撞箱
 * （{@code getDimensions(pose).scale(getScale())}）用的是同一个调用 ⇒ 法阵与身体严格同比例。
 * <p>
 * ⚠️ 用不了 DS 的视觉体型：{@code DragonStateHandler#getVisualScale} 有硬守卫
 * （非渲染线程直接告警并回退），服务端<b>拿不到</b>；而 DS 龙体的碰撞箱 {@code scaling_proportions.width}
 * 生成值全是 0.6、与普通玩家相同 ⇒ 碰撞箱也区分不出龙与人。{@code getScale()} 是服务端可见且语义正确的量
 * （放大护符 +1.0 就体现在它上面）。

 *
 * <h2>为什么不给粒子设"向外的速度"（重要）</h2>
 *
 * 原版<b>没有</b>提供定向速度的入口：{@code ServerLevel.sendParticles(type, x, y, z, count, dx, dy, dz, speed)}
 * 里 {@code dx/dy/dz} 是随机<b>位置</b>偏移、{@code speed} 是<b>随机方向</b>的速度大小
 * （内部 {@code random.nextGaussian()}）。
 * ⇒ 用"<b>逐 tick 外扩的球壳</b>"来等价表达向外爆发：每 tick 在半径 {@code r(t)} 的球面上撒点，
 * 由于粒子有存活时间，后一壳叠在前一壳之外，视觉上就是一个向外膨胀的实心球。
 * 这个做法对<b>全部 10 种粒子类型都成立</b>（不依赖它们支持速度参数），也不需要自定义粒子。
 *
 * <h2>不需要任何自定义同步代码</h2>
 *
 * 粒子由服务端 {@code sendParticles} 产生，原版会自动广播给附近客户端；本类只做服务端 tick 编排。
 *
 * <h2>性能口径</h2>
 *
 * 默认体型下峰值约 <b>206 颗/tick 画法阵</b>（外圈 81 + 五角星 5 条弦 × 25），
 * <b>+ 112 颗/tick 的球壳</b>。点数按<b>线长</b>反算，所以体型放大时粒子量按比例涨
 * （2× 时法阵约 407 颗/tick）—— <b>这是"密到成实线"的必然代价</b>：线是这个长度，
 * 间距就是这个数，点数 = 长度 ÷ 间距。
 * 全部是逐颗定点发（{@code count = 1}）—— 本项目记过 {@code sendParticles} 的
 * {@code count > 1} 是**长方体随机**，那样画不出圆和球。
 */
@EventBusSubscriber(modid = BeLoongCore.MODID)
public final class EssenceLearnRitual {

    // ==================== 时间轴 ====================

    /** 仪式总长（tick）：80 = 4.00 秒。音效是 3.0199 秒，法阵比它多留约 1 秒。 */
    private static final int TOTAL_TICKS = 80;

    /** 法阵画完 / 爆发开始（tick）：24 = 1.20 秒 —— 实测音频"主体"的起点，绘制正好在此收笔。 */
    private static final int CLIMAX_START_TICK = 24;

    /** 高潮结束（tick）：38 = 1.90 秒 —— 实测音频"主体"的终点。 */
    private static final int CLIMAX_END_TICK = 38;

    /** 法阵停止生成（tick）：72 = 3.60 秒。之后靠粒子的自然存活时间收尾（约 4.1 秒散尽）。 */
    private static final int CIRCLE_SPAWN_END_TICK = 72;

    /** 爆发停止生成（tick）：56 = 2.80 秒。爆发<b>不</b>跟着法阵一起延长（用户裁定）。 */
    private static final int BURST_SPAWN_END_TICK = 56;

    /** 法阵在最初多少 tick 内被"画"出来 —— 与 {@link #CLIMAX_START_TICK} 对齐，到高潮时刚好画完。 */
    private static final int DRAW_TICKS = CLIMAX_START_TICK;

    // ==================== 体型缩放 ====================

    /** 缩放下限：<b>只放大、不缩小</b>（用户裁定）。 */
    private static final double MIN_SIZE_FACTOR = 1.0D;

    /** 缩放上限（用户裁定）：最多 4 倍。 */
    private static final double MAX_SIZE_FACTOR = 4.0D;

    // ==================== 法阵几何（贴地；下列数值 = 默认体型，外圈直径 9 格） ====================

    /** 外圈半径（格）—— 直径 9 格（用户指定）。 */
    private static final double BASE_OUTER_RADIUS = 4.5D;

    /**
     * 法阵所有线条的采样间距（格）。
     * <p>
     * <b>这是"线密不密"的唯一旋钮</b>。取 <b>0.35</b> 是照第一版的实测量级
     * （第一版外圈 r=1.75、32 点 ⇒ 0.34 格/颗）—— 那个密度下粒子会**堆叠成一条实线**，
     * 也是作者认可的观感。
     * <p>
     * ⚠️ <b>这里走过一次弯路</b>：曾经为了"省粒子"把外圈压到 16 点（1.77 格/颗），
     * 结果线条变成点串，观感明显不如密集堆叠的线（作者裁定）。⇒ 结论：
     * <b>法阵这类"画线"的效果，密度优先于节省</b>；要调就调这一个数
     * （往小 = 更实更费，往大 = 更虚更省）。
     * <p>
     * 点数<b>按线长反算</b>（{@code ceil(长度 / 间距)}），因此体型放大时密度**自动恒定**，
     * 不需要另外乘倍数。
     */
    private static final double LINE_SPACING = 0.35D;

    /**
     * 五角星外接圆半径 = 外圈半径 × 此比例。
     * <p>
     * 取 <b>1.0</b> 是标准五芒星 —— 星的 5 个顶点正好落在外圈上。
     */
    private static final double STAR_RADIUS_RATIO = 1.0D;

    /** 法阵距玩家脚底的高度（格）—— 贴地画，读作"脚下的法阵"。 */
    private static final double CIRCLE_Y_OFFSET = 0.12D;

    /**
     * 法阵粒子的尺寸（dust 的 scale，合法区间 0.01–4.0）。
     * <p>
     * 取 <b>1.2</b>（≈ 第一版的值）：<b>小点 + 密排 = 细而实的一根线</b>。
     * 点数已经很密，再放大点只会把线糊粗（曾经取 2.5 + 稀疏点，观感反而更差）。
     */
    private static final float DUST_SCALE = 1.2F;

    /** 外圈每 tick 旋转角度（度）。 */
    private static final double OUTER_SPIN_DEGREES = 2.5D;

    /** 五角星每 tick 旋转角度（度）。 */
    private static final double STAR_SPIN_DEGREES = 1.2D;

    // ==================== 爆发几何 ====================

    /** 球心相对玩家脚底的高度（格）—— 大致取身体中心。 */
    private static final double BURST_Y_OFFSET = 1.0D;

    /** 爆发起始半径（格）。 */
    private static final double BURST_START_RADIUS = 0.35D;

    /** 球壳半径每 tick 增长量（格）—— 到高潮末（14 tick 后）约 6.6 格，再乘体型倍数。 */
    private static final double BURST_RADIUS_PER_TICK = 0.45D;

    /** 单层球壳的采样点数。 */
    private static final int BURST_SHELL_POINTS = 56;

    /** 同时画几层壳（错开半径，让膨胀看着更"厚"）。 */
    private static final int BURST_SHELLS = 2;

    /** 相邻两层壳的半径差（格）。 */
    private static final double BURST_SHELL_GAP = 0.45D;

    /** 黄金角 —— 球面均匀布点用（Fibonacci 球）。 */
    private static final double GOLDEN_ANGLE = Math.PI * (3.0D - Math.sqrt(5.0D));

    /** 正在播放的仪式。同一玩家最多一个。 */
    private static final List<Ritual> ACTIVE = new ArrayList<>();

    private EssenceLearnRitual() {}

    /**
     * 开始一次习得仪式。
     * <p>
     * 同一玩家已有仪式时<b>替换</b>它（连续使用两件魔源不会叠出两套法阵）。
     *
     * @param player  习得者（服务端玩家）
     * @param element 元素 —— 决定法阵颜色与爆发用的粒子
     */
    public static void start(ServerPlayer player, EssenceItem.Element element) {
        ACTIVE.removeIf(ritual -> ritual.player == player);
        ACTIVE.add(new Ritual(player, element));
    }

    /** 服务端每 tick 推进所有仪式。 */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (ACTIVE.isEmpty()) {
            return;
        }

        Iterator<Ritual> iterator = ACTIVE.iterator();
        while (iterator.hasNext()) {
            if (!iterator.next().advance()) {
                iterator.remove();
            }
        }
    }

    /** 一次仪式的状态。 */
    private static final class Ritual {

        private final ServerPlayer player;
        private final ParticleOptions burstParticle;
        private final DustParticleOptions circleParticle;
        /** 法阵 / 爆发的尺寸倍数（1× ~ 4×）。整场仪式固定，启动时算一次。 */
        private final double sizeFactor;
        private int tick;

        Ritual(ServerPlayer player, EssenceItem.Element element) {
            this.player = player;
            this.burstParticle = element.particle();
            this.sizeFactor = resolveSizeFactor(player);
            // 法阵用同一个硬编码元素色 —— 与 Title、物品 tooltip 同源
            Vector3f color = Vec3.fromRGB24(element.titleColor()).toVector3f();
            this.circleParticle = new DustParticleOptions(color, DUST_SCALE);
        }

        /**
         * 体型倍数 = 原版的 {@link LivingEntity#getScale()}，再钳到 {@link #MIN_SIZE_FACTOR} ~
         * {@link #MAX_SIZE_FACTOR}（用户裁定：<b>只放大、不缩小、最多 4 倍</b>）。
         * <p>
         * 直接调 {@code getScale()}，<b>不是</b>自己读属性再换算。理由（已逐条核对源码）：
         * <ul>
         *   <li>{@code getScale()} 就是"体型倍数"的<b>原版定义</b>：属性 {@code minecraft:generic.scale}
         *       默认 1.0、范围 0.0625–16，{@code getScale()} 返回的就是它，所以 <b>1.0 = 正常体型</b>；</li>
         *   <li>渲染（{@code LivingEntityRenderer} 的 {@code poseStack.scale(f, f, f)}）与碰撞箱
         *       （{@code getDimensions(pose).scale(getScale())}）用的是<b>同一个调用</b>
         *       ⇒ 法阵与身体严格同比例；</li>
         *   <li>自带兜底：属性表尚未建立时返回 {@code 1.0F}；并经过 {@code sanitizeScale}
         *       （原版只有末影龙与潜影贝覆写它，玩家是原样返回）；</li>
         *   <li>不需要自己"除以默认值"—— 那个换算对玩家恒等于 1.0。</li>
         * </ul>
         * <p>
         * ⚠️ <b>为什么不用 DS 的视觉体型</b>：{@code DragonStateHandler#getVisualScale} 有硬守卫，
         * 非渲染线程调用会告警并回退，服务端拿不到；而龙体碰撞箱的生成宽度全是 0.6（与玩家相同）
         * ⇒ 碰撞箱区分不出龙与人。{@code getScale()} 是服务端可见且语义正确的那个量。
         */
        private static double resolveSizeFactor(ServerPlayer player) {
            return Mth.clamp(player.getScale(), MIN_SIZE_FACTOR, MAX_SIZE_FACTOR);
        }

        /**
         * 推进一 tick。
         *
         * @return {@code false} 表示仪式结束，可以从活动列表里摘掉
         */
        boolean advance() {
            // 玩家已消失（退出 / 换维度 / 死亡重生）⇒ 立即收手，不要在别人的世界里继续撒粒子
            if (this.player.isRemoved() || this.tick >= TOTAL_TICKS) {
                return false;
            }

            ServerLevel level = this.player.serverLevel();
            double centerX = this.player.getX();
            double centerY = this.player.getY();
            double centerZ = this.player.getZ();

            double circleDensity = this.circleDensity();
            if (circleDensity > 0.0D) {
                this.drawMagicCircle(level, centerX, centerY, centerZ, circleDensity);
            }

            if (this.tick >= CLIMAX_START_TICK) {
                double burstDensity = this.burstDensity();
                if (burstDensity > 0.0D) {
                    this.drawBurst(level, centerX, centerY, centerZ, burstDensity);
                }
            }

            this.tick++;
            return true;
        }

        /**
         * 法阵密度：高潮结束前恒为 1，之后线性衰减到 0（{@link #CIRCLE_SPAWN_END_TICK} 处为 0）。
         * 这条曲线就是"粒子在音效结束后自然收尾"的实现 —— <b>不需要任何额外的同步代码</b>。
         */
        private double circleDensity() {
            if (this.tick <= CLIMAX_END_TICK) {
                return 1.0D;
            }
            if (this.tick >= CIRCLE_SPAWN_END_TICK) {
                return 0.0D;
            }

            return (CIRCLE_SPAWN_END_TICK - this.tick)
                    / (double) (CIRCLE_SPAWN_END_TICK - CLIMAX_END_TICK);
        }

        /**
         * 爆发密度：与法阵<b>分开</b> —— 爆发不延长，在音频的衰减段（1.90–2.80 s）里淡完。
         */
        private double burstDensity() {
            if (this.tick <= CLIMAX_END_TICK) {
                return 1.0D;
            }
            if (this.tick >= BURST_SPAWN_END_TICK) {
                return 0.0D;
            }

            return (BURST_SPAWN_END_TICK - this.tick)
                    / (double) (BURST_SPAWN_END_TICK - CLIMAX_END_TICK);
        }

        /**
         * 贴地法阵：<b>外圈 + 五角星</b>（原实现还有一个反向旋转的内圈，作者裁定**不要** ——
         * 只要"中间一个五角星 + 最外围一圈"）。
         * <p>
         * 点数一律<b>按线长反算</b>（{@code ceil(长度 / LINE_SPACING)}）⇒ 体型放大时线密度自动恒定，
         * 不需要另外乘倍数；也是"密集堆叠成实线"而不是点串的保证。
         * <p>
         * 前 {@link #DRAW_TICKS} tick 按进度只画一部分 ⇒ 读作"法阵被画出来"；
         * 之后全量重画（粒子有存活时间，必须每 tick 补，否则法阵会自己消失）。
         */
        private void drawMagicCircle(ServerLevel level, double centerX, double centerY, double centerZ,
                                     double density) {
            double y = centerY + CIRCLE_Y_OFFSET;
            double progress = Math.min(1.0D, (this.tick + 1) / (double) DRAW_TICKS);

            // 半径随体型放大（点数由线长反算 ⇒ 密度自动保持）
            double outerRadius = BASE_OUTER_RADIUS * this.sizeFactor;
            double starRadius = outerRadius * STAR_RADIUS_RATIO;
            int outerPoints = Math.max(8, (int) Math.ceil(2.0D * Math.PI * outerRadius / LINE_SPACING));

            // 五角星的弦长 = 2R·sin72° ≈ 1.902R —— 比外圈的一段弧长得多，所以点数要单独反算
            double chordLength = 2.0D * starRadius * Math.sin(Math.toRadians(72.0D));
            int starPointsPerChord = Math.max(4, (int) Math.ceil(chordLength / LINE_SPACING));

            // 外圈（顺时针方向旋转）
            double outerBase = Math.toRadians(this.tick * OUTER_SPIN_DEGREES);
            int outerCount = (int) Math.ceil(outerPoints * progress * density);
            for (int i = 0; i < outerCount; i++) {
                double angle = outerBase + 2.0D * Math.PI * i / outerPoints;
                this.dust(level, centerX + Math.cos(angle) * outerRadius, y,
                        centerZ + Math.sin(angle) * outerRadius);
            }

            // 五角星：正五边形的顶点按"隔一个连"的顺序相接，得到 5 条弦；
            // 顶点半径 = 外圈半径 ⇒ 星的 5 个尖正好落在外圈上
            double starBase = Math.toRadians(this.tick * STAR_SPIN_DEGREES) - Math.PI / 2.0D;
            int pointsPerChord = (int) Math.ceil(starPointsPerChord * progress * density);
            for (int edge = 0; edge < 5; edge++) {
                double from = starBase + edge * (2.0D * Math.PI / 5.0D);
                double to = starBase + ((edge + 2) % 5) * (2.0D * Math.PI / 5.0D);
                double fromX = Math.cos(from) * starRadius;
                double fromZ = Math.sin(from) * starRadius;
                double toX = Math.cos(to) * starRadius;
                double toZ = Math.sin(to) * starRadius;

                for (int i = 0; i < pointsPerChord; i++) {
                    // 除以 (n-1) 而不是 n：两端都要采到 ⇒ 星的**尖**才会被画出来（尖是五角星的身份）
                    double t = i / (double) (starPointsPerChord - 1);
                    this.dust(level, centerX + fromX + (toX - fromX) * t, y,
                            centerZ + fromZ + (toZ - fromZ) * t);
                }
            }
        }

        /**
         * 以玩家为球心的向外爆发 —— 用<b>逐 tick 外扩的球壳</b>表达（见类注释：原版无法给单颗粒子
         * 指定定向速度）。球面上的点用 Fibonacci 球均匀分布，两层壳错开半径与相位。
         * <p>
         * 半径同样乘体型倍数 —— 否则大体型玩家的爆发会显得比他的法阵小得多。
         */
        private void drawBurst(ServerLevel level, double centerX, double centerY, double centerZ,
                               double density) {
            double age = this.tick - CLIMAX_START_TICK;
            double outerRadius = (BURST_START_RADIUS + age * BURST_RADIUS_PER_TICK) * this.sizeFactor;
            double shellGap = BURST_SHELL_GAP * this.sizeFactor;
            double centerYWithOffset = centerY + BURST_Y_OFFSET;
            int points = (int) Math.ceil(BURST_SHELL_POINTS * density);
            int totalPoints = BURST_SHELL_POINTS * BURST_SHELLS;

            for (int shell = 0; shell < BURST_SHELLS; shell++) {
                double radius = outerRadius - shell * shellGap;
                if (radius <= 0.05D) {
                    continue;
                }

                for (int i = 0; i < points; i++) {
                    // 两层壳用不同的相位起点，避免重叠时看起来只有一层
                    double index = i + shell * BURST_SHELL_POINTS * 0.5D;
                    double y = 1.0D - 2.0D * index / totalPoints;
                    double ringRadius = Math.sqrt(Math.max(0.0D, 1.0D - y * y));
                    double theta = GOLDEN_ANGLE * index;

                    level.sendParticles(this.burstParticle,
                            centerX + Math.cos(theta) * ringRadius * radius,
                            centerYWithOffset + y * radius,
                            centerZ + Math.sin(theta) * ringRadius * radius,
                            1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
            }
        }

        /** 法阵的一颗粒子（{@code count = 1}：必须逐颗定点发，否则是长方体随机）。 */
        private void dust(ServerLevel level, double x, double y, double z) {
            level.sendParticles(this.circleParticle, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }
}
