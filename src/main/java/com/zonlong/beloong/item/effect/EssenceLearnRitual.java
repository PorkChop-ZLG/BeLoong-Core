package com.zonlong.beloong.item.effect;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 *   <li><b>法阵</b>（脚下，贴地）：外圈 + 反向旋转的内圈 + <b>五角星</b>。全部用
 *       {@code minecraft:dust}（原版唯一可任意着色的普通粒子），颜色取该元素魔源的硬编码色 ——
 *       与 Title、tooltip 用的是同一个值。</li>
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
 *   <tr><td>0–11</td><td>0.00–0.60</td><td>渐强起点</td><td>法阵被"画"出来（按进度逐点出现）</td></tr>
 *   <tr><td>12–23</td><td>0.60–1.20</td><td>渐强末段</td><td>法阵完整并旋转（两圈反向转 + 星缓慢自转）</td></tr>
 *   <tr><td>24–37</td><td>1.20–1.90</td><td><b>高潮（主体）</b></td><td>法阵保留 + <b>球壳爆发</b>（半径线性外扩）</td></tr>
 *   <tr><td>38–51</td><td>1.90–2.60</td><td>衰减</td><td>密度线性降到 0（球壳继续外扩变稀 = 冲击波余韵）</td></tr>
 *   <tr><td>52–59</td><td>2.60–3.00</td><td>尾音</td><td><b>不再产生新粒子</b>，已有粒子在存活期内散尽</td></tr>
 * </table>
 *
 * ⇒ 最后一个粒子在 2.60 s 产生、存活至多约 0.5 s ⇒ <b>约 3.1 s 时粒子场清空</b>，
 * 与音效 3.02 s 的收尾同步。
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
 * 峰值约 <b>200 颗/tick</b>（法阵约 91 + 球壳约 112），持续 3 秒。全部是逐颗定点发
 * （{@code count = 1}）—— 本项目记过 {@code sendParticles} 的 {@code count > 1} 是**长方体随机**，
 * 那样画不出圆和球。
 */
@EventBusSubscriber(modid = BeLoongCore.MODID)
public final class EssenceLearnRitual {

    // ==================== 时间轴 ====================

    /** 仪式总长（tick）：60 = 3.00 秒，对齐音效的 3.0199 秒。 */
    private static final int TOTAL_TICKS = 60;

    /** 法阵成形结束 / 爆发开始（tick）：24 = 1.20 秒 —— 实测音频"主体"的起点。 */
    private static final int CLIMAX_START_TICK = 24;

    /** 高潮结束（tick）：38 = 1.90 秒 —— 实测音频"主体"的终点。 */
    private static final int CLIMAX_END_TICK = 38;

    /** 停止产生新粒子（tick）：52 = 2.60 秒。之后靠粒子的自然存活时间收尾。 */
    private static final int SPAWN_END_TICK = 52;

    /** 法阵在最初多少 tick 内被"画"出来。 */
    private static final int DRAW_TICKS = 12;

    // ==================== 法阵几何（贴地） ====================

    /** 外圈半径（格）。 */
    private static final double OUTER_RADIUS = 1.75D;

    /** 外圈采样点数。 */
    private static final int OUTER_POINTS = 32;

    /** 内圈半径（格）。 */
    private static final double INNER_RADIUS = 0.65D;

    /** 内圈采样点数。 */
    private static final int INNER_POINTS = 14;

    /** 五角星外接圆半径（格）—— 必须小于外圈半径。 */
    private static final double STAR_RADIUS = 1.35D;

    /** 五角星每条边上的采样点数（5 条边）。 */
    private static final int STAR_POINTS_PER_EDGE = 9;

    /** 法阵距玩家脚底的高度（格）—— 贴地画，读作"脚下的法阵"。 */
    private static final double CIRCLE_Y_OFFSET = 0.12D;

    /** 法阵粒子的尺寸（dust 的 scale，合法区间 0.01–4.0）。 */
    private static final float DUST_SCALE = 1.1F;

    /** 外圈每 tick 旋转角度（度）。 */
    private static final double OUTER_SPIN_DEGREES = 2.5D;

    /** 内圈每 tick 旋转角度（度，负值 = 反向）。 */
    private static final double INNER_SPIN_DEGREES = -4.0D;

    /** 五角星每 tick 旋转角度（度）。 */
    private static final double STAR_SPIN_DEGREES = 1.2D;

    // ==================== 爆发几何 ====================

    /** 球心相对玩家脚底的高度（格）—— 大致取身体中心。 */
    private static final double BURST_Y_OFFSET = 1.0D;

    /** 爆发起始半径（格）。 */
    private static final double BURST_START_RADIUS = 0.35D;

    /** 球壳半径每 tick 增长量（格）—— 到高潮末（14 tick 后）约 6.6 格。 */
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
        private int tick;

        Ritual(ServerPlayer player, EssenceItem.Element element) {
            this.player = player;
            this.burstParticle = element.particle();
            // 法阵用同一个硬编码元素色 —— 与 Title、物品 tooltip 同源
            Vector3f color = Vec3.fromRGB24(element.titleColor()).toVector3f();
            this.circleParticle = new DustParticleOptions(color, DUST_SCALE);
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
            double density = this.density();

            if (density > 0.0D) {
                this.drawMagicCircle(level, centerX, centerY, centerZ, density);

                if (this.tick >= CLIMAX_START_TICK) {
                    this.drawBurst(level, centerX, centerY, centerZ, density);
                }
            }

            this.tick++;
            return true;
        }

        /**
         * 粒子密度倍率：高潮结束前恒为 1，之后线性衰减到 0（{@link #SPAWN_END_TICK} 处为 0）。
         * 这条曲线就是"音效收尾时粒子也散尽"的实现 —— <b>不需要任何额外的同步代码</b>。
         */
        private double density() {
            if (this.tick <= CLIMAX_END_TICK) {
                return 1.0D;
            }
            if (this.tick >= SPAWN_END_TICK) {
                return 0.0D;
            }

            return (SPAWN_END_TICK - this.tick) / (double) (SPAWN_END_TICK - CLIMAX_END_TICK);
        }

        /**
         * 贴地法阵：外圈 + 反向内圈 + 五角星，全部是逐颗定点发的 dust。
         * <p>
         * 前 {@link #DRAW_TICKS} tick 按进度只画一部分 ⇒ 读作"法阵被画出来"；
         * 之后全量重画（粒子有存活时间，必须每 tick 补，否则法阵会自己消失）。
         */
        private void drawMagicCircle(ServerLevel level, double centerX, double centerY, double centerZ,
                                     double density) {
            double y = centerY + CIRCLE_Y_OFFSET;
            double progress = Math.min(1.0D, (this.tick + 1) / (double) DRAW_TICKS);

            // 外圈（顺指针方向旋转）
            double outerBase = Math.toRadians(this.tick * OUTER_SPIN_DEGREES);
            int outerCount = (int) Math.ceil(OUTER_POINTS * progress * density);
            for (int i = 0; i < outerCount; i++) {
                double angle = outerBase + 2.0D * Math.PI * i / OUTER_POINTS;
                this.dust(level, centerX + Math.cos(angle) * OUTER_RADIUS, y,
                        centerZ + Math.sin(angle) * OUTER_RADIUS);
            }

            // 内圈（反向旋转，与外圈形成对转）
            double innerBase = Math.toRadians(this.tick * INNER_SPIN_DEGREES);
            int innerCount = (int) Math.ceil(INNER_POINTS * progress * density);
            for (int i = 0; i < innerCount; i++) {
                double angle = innerBase + 2.0D * Math.PI * i / INNER_POINTS;
                this.dust(level, centerX + Math.cos(angle) * INNER_RADIUS, y,
                        centerZ + Math.sin(angle) * INNER_RADIUS);
            }

            // 五角星：正五边形的顶点按"隔一个连"的顺序相接，得到 5 条弦
            double starBase = Math.toRadians(this.tick * STAR_SPIN_DEGREES) - Math.PI / 2.0D;
            int pointsPerEdge = (int) Math.ceil(STAR_POINTS_PER_EDGE * progress * density);
            for (int edge = 0; edge < 5; edge++) {
                double from = starBase + edge * (2.0D * Math.PI / 5.0D);
                double to = starBase + ((edge + 2) % 5) * (2.0D * Math.PI / 5.0D);
                double fromX = Math.cos(from) * STAR_RADIUS;
                double fromZ = Math.sin(from) * STAR_RADIUS;
                double toX = Math.cos(to) * STAR_RADIUS;
                double toZ = Math.sin(to) * STAR_RADIUS;

                for (int i = 0; i < pointsPerEdge; i++) {
                    double t = i / (double) STAR_POINTS_PER_EDGE;
                    this.dust(level, centerX + fromX + (toX - fromX) * t, y,
                            centerZ + fromZ + (toZ - fromZ) * t);
                }
            }
        }

        /**
         * 以玩家为球心的向外爆发 —— 用<b>逐 tick 外扩的球壳</b>表达（见类注释：原版无法给单颗粒子
         * 指定定向速度）。球面上的点用 Fibonacci 球均匀分布，两层壳错开半径与相位。
         */
        private void drawBurst(ServerLevel level, double centerX, double centerY, double centerZ,
                               double density) {
            double age = this.tick - CLIMAX_START_TICK;
            double outerRadius = BURST_START_RADIUS + age * BURST_RADIUS_PER_TICK;
            double centerYWithOffset = centerY + BURST_Y_OFFSET;
            int points = (int) Math.ceil(BURST_SHELL_POINTS * density);
            int totalPoints = BURST_SHELL_POINTS * BURST_SHELLS;

            for (int shell = 0; shell < BURST_SHELLS; shell++) {
                double radius = outerRadius - shell * BURST_SHELL_GAP;
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
