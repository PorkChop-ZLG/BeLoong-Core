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
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
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
 * <h2>法阵是"被画出来的"，而且<b>不旋转</b></h2>
 *
 * 圆和五角星<b>同时</b>从 0 开始绘制、都在 <b>1.20 s 收笔</b>（正好接上爆发的第一 tick）。
 * 两者的<b>起笔点是同一个</b>：正前方（俯视的"上"）—— 圆的起笔方向 = 星的起笔顶点。
 * <ul>
 *   <li><b>外圈</b>：从"上"起笔，沿俯视<b>顺时针</b>画满一圈。</li>
 *   <li><b>五角星</b>：<b>一笔勾勒</b> —— 5 段连续笔迹，每段跨 144°，
 *       依次 <b>上 → 右下 → 左上 → 右上 → 左下 → 上</b>（作者口述的常见画法）。</li>
 * </ul>
 *
 * 法阵整体<b>静止</b>（作者裁定）。⚠️ 这一条有硬理由：dust 粒子的寿命是
 * {@code 8/[0.2,1.0] × scale} ⇒ 默认 scale 1.0 时最长 <b>40 tick（2 秒）</b>；
 * 只要法阵每 tick 转一点，一颗粒子从生到灭就会扫过「每 tick 角度 × 40」那么大的一片扇面，
 * 把细线糊成一条 <b>又粗又乱</b>的带子 —— 实机截图里五角星糊成一团、而外圈却干净，
 * 正是因为<b>圆的旋转对称性掩盖了拖影，星不是</b>。
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
 *   <tr><td>0–23</td><td>0.00–1.20</td><td>渐强（爬到 -9 dB）</td><td>圆与星<b>同时</b>被"画"出来 —— <b>1.20 s 刚好收笔</b>，与高潮同拍</td></tr>
 *   <tr><td>24–37</td><td>1.20–1.90</td><td><b>高潮（主体）</b></td><td>法阵已完整、静止 + <b>球壳爆发</b>（半径线性外扩）</td></tr>
 *   <tr><td>38–55</td><td>1.90–2.80</td><td>衰减到尾音</td><td>爆发密度降到 0（冲击波余韵）；法阵继续</td></tr>
 *   <tr><td>56–71</td><td>2.80–3.60</td><td>已结束</td><td>只剩法阵，密度线性降到 0</td></tr>
 *   <tr><td>72–79</td><td>3.60–4.00</td><td>—</td><td><b>不再产生新粒子</b>；最后一批粒子最迟在 tick 111（<b>≈5.6 s</b>）散尽</td></tr>
 * </table>
 *
 * ⇒ <b>法阵比音效多留约 1 秒</b>（用户裁定：不必与音效长度完全贴合）；<b>爆发不延长</b>，
 * 在 2.80 s 停止生成，与音频的衰减段一起收尾。
 * <p>
 * ⚠️ 粒子场的**真正清空时刻是 tick 111 ≈ 5.6 s**，不是 4.0 s：dust 的寿命是
 * {@code max(8 / (0.2..1.0) × scale, 1)} = <b>[8, 40] tick</b>（{@code DustParticleBase.java:32-33}），
 * 而最后一批粒子发射于 tick 71 ⇒ 71 + 40 = 111。
 *
 * <h2>密度衰减用"均匀抽稀"，不用"截断点数"</h2>
 *
 * 两条密度曲线都是通过 {@code densityStep()} 换算成**间隔步长**（{@code density 0.5 ⇒ 每隔一个画一个}），
 * 让整条线一起变稀。⚠️ 曾经是按前缀**截断点数**：那样收尾时法阵会**从收笔处向起笔处蚀退**
 * （圆只剩起笔那一段弧、星的最后一笔先消失），读起来像"被擦掉"而不是"整体变稀"。
 * 注意：**绘制进度 {@code progress} 仍然按前缀截断**（那是"笔走到哪"的语义，必须截断）。
 *
 * <h2>⚠️ 爆发的半径有硬上限（原版投递门 32 格）</h2>
 *
 * 公开的 {@code ServerLevel#sendParticles(type, x, y, z, count, …)} 恒以 {@code longDistance = false}
 * 投递，门槛是 **32 格**（{@code ServerLevel.java:1268,1295-1302}）。球壳以**玩家自己为球心**
 * ⇒ 壳上每点到玩家的距离都等于半径 ⇒ 半径一旦 &gt; 32，**施法者本人一格都收不到**。
 * 因此半径被钳在 {@link #BURST_MAX_RADIUS}（30 格）。详见该常量的说明。
 *
 * <h2>法阵是"脱手效果"，爆发跟人走</h2>
 *
 * 法阵的位置在<b>使用魔源那一刻定死</b>（锚点 = 当时的坐标 + 维度 + <b>朝向</b>，存在每个仪式实例自己的
 * {@code final} 字段里）⇒ 玩家在仪式期间走动、跳跃、被打飞，法阵都留在原地不乱。
 * <b>爆发仍然绑在玩家身上</b>（每 tick 取实时坐标），所以它是"从自己身上炸开"。
 * <p>
 * 朝向也一起锚定：法阵不旋转，但"星的'上'朝哪边"需要基准 —— 取使用时的 yaw，
 * 这样<b>施法者看到的永远是正立的五角星</b>（作者裁定）；别的玩家从别的方向看依然是斜的（无解）。
 * <p>
 * 附带一条：玩家若在仪式期间<b>换维度</b>，锚点已不在当前世界 ⇒ 仪式直接结束（不在新维度里拿旧坐标乱画）。
 *
 * <h2>尺寸随体型缩放</h2>
 *
 * 法阵与爆发的半径都乘以一个"体型倍数"：直接取原版自己的定义
 * {@link LivingEntity#getScale()}（即 {@code minecraft:generic.scale} 属性的值，默认 1.0 ⇒ 1.0 = 正常体型），
 * 再钳到 <b>1× ~ 4×</b>（用户裁定：<b>只放大不缩小，最多 4 倍</b>）。
 * 默认体型 ⇒ 外圈<b>直径 7 格</b>（作者指定）。
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
 * 默认体型下峰值约 <b>189 颗/tick 画法阵</b>（外圈 74 + 五角星 5 段 × 23），
 * <b>+ 112 颗/tick 的球壳</b>。点数按<b>线长</b>反算，所以直径/体型变化时粒子量按比例涨落
 * —— <b>这是"密到成实线"的必然代价</b>：线是这个长度，间距就是这个数，点数 = 长度 ÷ 间距。
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

    // ==================== 法阵几何（贴地；下列数值 = 默认体型，外圈直径 7 格） ====================

    /** 外圈半径（格）—— 直径 <b>7</b> 格（作者先定 9 格、实机后改为 7 格）。 */
    private static final double BASE_OUTER_RADIUS = 3.5D;

    /**
     * 法阵所有线条的采样间距（格）。
     * <p>
     * <b>这是"线密不密"的唯一旋钮</b>。取 <b>0.30</b>：配合 {@link #DUST_SCALE} = 1.0
     * （点直径 0.15–0.30 格）⇒ 相邻点刚好相接，读作一条细而连续的线。
     * <p>
     * ⚠️ <b>这里走过一次弯路</b>：曾经为了"省粒子"把外圈压到 16 点（1.77 格/颗），
     * 结果线条变成点串，观感明显不如密集堆叠的线（作者裁定）。⇒ 结论：
     * <b>法阵这类"画线"的效果，密度优先于节省</b>；要调就调这一个数
     * （往小 = 更实更费，往大 = 更虚更省）。
     * <p>
     * 点数<b>按线长反算</b>（{@code ceil(长度 / 间距)}），因此体型放大时密度**自动恒定**，
     * 不需要另外乘倍数。
     */
    private static final double LINE_SPACING = 0.30D;

    /**
     * 五角星外接圆半径 = 外圈半径 × 此比例。
     * <p>
     * 取 <b>1.0</b> 是标准五芒星 —— 星的 5 个顶点正好落在外圈上。
     */
    private static final double STAR_RADIUS_RATIO = 1.0D;

    /**
     * 一笔画五角星：每一段跨过的圆心角（度）。
     * <p>
     * <b>144° = 2 × 72°</b>（"隔一个顶点连"）⇒ 5 段正好闭合，这就是"一笔勾勒"的数学形式：
     * 从 {@code 0°}（正前方 = 俯视的"上"）起笔，依次
     * <b>上 → 右下 → 左上 → 右上 → 左下 → 上</b>（作者口述的常见画法）。
     * <p>
     * 角度约定：{@code 0°} = 玩家使用魔源时的<b>正前方</b>，角度<b>沿俯视顺时针</b>增大
     * （72° = 右上、144° = 右下、216° = 左下、288° = 左上）。
     */
    private static final double STAR_STROKE_STEP_DEGREES = 144.0D;

    /** 一笔画的段数（五角星 = 5 段）。 */
    private static final int STAR_STROKE_SEGMENTS = 5;

    /** 法阵距玩家脚底的高度（格）—— 贴地画，读作"脚下的法阵"。 */
    private static final double CIRCLE_Y_OFFSET = 0.12D;

    /**
     * 法阵粒子的尺寸（dust 的 scale，合法区间 0.01–4.0）。
     * <p>
     * 取 <b>1.0</b>（作者实机裁定）：<b>小点 + 密排 = 细而实的一根线</b>。
     * 点数已经很密，再放大点只会把线糊粗（曾经取 2.5 + 稀疏点，观感反而更差）。
     * <p>
     * 附带：{@code scale} <b>同时决定粒子寿命</b>（{@code DustParticleBase}：
     * {@code lifetime = 8/[0.2,1.0] × scale}）⇒ 1.0 时寿命 8–40 tick。
     */
    private static final float DUST_SCALE = 1.0F;

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

    /**
     * 爆发半径的**上限（格）** —— 由原版的投递门倒推出来的硬约束，不能随便加大。
     * <p>
     * ⚠️ {@code ServerLevel#sendParticles(type, x, y, z, count, …)} 这个公开重载
     * <b>恒以 {@code longDistance = false} 调内部投递</b>（{@code ServerLevel.java:1268}），
     * 而投递门是
     * {@code player.blockPosition().closerToCenterThan(point, longDistance ? 512.0 : 32.0)}
     * （{@code ServerLevel.java:1295-1302}）⇒ <b>32 格</b>。
     * <p>
     * 球壳是以<b>玩家自己为球心</b>的：壳上每一点到玩家的距离都等于该壳半径 ⇒
     * 一旦半径 &gt; 32，<b>施法者本人收到的包数为 0</b>（外层壳会整层静默消失）。
     * 实测：半径公式 {@code (0.35 + age × 0.45) × 体型倍数} 在 2× 时最大 28.6 格（安全），
     * 但 3× 时 tick 48–55、4× 时 tick 42–55 会越过 32 格。
     * <p>
     * ⇒ 取 <b>30</b>（留 2 格余量给"玩家在方块内的偏移"，因为门是按 {@code blockPosition} 中心算的）。
     * 代价：3×/4× 体型的爆发只能长到 30 格（约等于 2.1× 的观感），不再线性放大。
     */
    private static final double BURST_MAX_RADIUS = 30.0D;

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

    /**
     * 服务器停止时把活动列表清空。
     * <p>
     * 正常路径下不需要它：玩家退出 / 死亡重生 / 换维度都会让 {@code ServerPlayer} 被移除，
     * 于是下一次 tick 的 {@code advance()} 里 {@code player.isRemoved()} 为真、实例被回收。
     * 但**服务器停在世界里**（关单机存档、停专服）时不会再 tick ⇒ 那些实例会带着
     * {@code ServerPlayer} 引用**挂在静态列表里**（有界、不崩，但没必要留着）。
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ACTIVE.clear();
    }

    /**
     * 一次仪式的状态。
     * <p>
     * ⚠️ <b>锚点坐标必须是本实例的 {@code final} 字段</b> —— 法阵是"脱手效果"：位置在使用魔源的
     * <b>那一刻</b>定死，之后再不动。绝不能把它放进任何 {@code static} 字段（例如"当前法阵中心"），
     * 否则<b>多个玩家同时使用魔源时会互相覆盖坐标</b>，画出错位的法阵。
     * 活动列表 {@link #ACTIVE} 里每个元素都是独立实例，各自持有自己的锚点。
     */
    private static final class Ritual {

        private final ServerPlayer player;
        private final ParticleOptions burstParticle;
        private final DustParticleOptions circleParticle;
        /** 法阵 / 爆发的尺寸倍数（1× ~ 4×）。整场仪式固定，启动时算一次。 */
        private final double sizeFactor;

        // ---- 法阵的锚点：使用魔源那一刻的位置、维度与朝向，整场不变（脱手效果）----
        /** 锚点所在维度 —— 玩家换维度后旧锚点已不在这个世界里，仪式直接结束。 */
        private final ServerLevel anchorLevel;
        private final double anchorX;
        private final double anchorY;
        private final double anchorZ;
        /**
         * 锚点朝向 = 玩家使用魔源那一刻的 yaw。
         * <p>
         * 法阵<b>不旋转</b>，但"五角星的'上'朝哪边"需要一个基准（作者裁定：按使用时的朝向，
         * 这样施法者看到的永远是正立的五角星）。
         * <p>
         * ⚠️ 用 {@code getYRot()} 而不是 {@code getLookAngle()} 的水平投影：玩家垂直仰视/俯视时
         * 后者会退化成零向量，而 yaw **任何时候都有定义**。
         */
        private final float anchorYaw;

        private int tick;

        Ritual(ServerPlayer player, EssenceItem.Element element) {
            this.player = player;
            this.burstParticle = element.particle();
            this.sizeFactor = resolveSizeFactor(player);
            // 法阵用同一个硬编码元素色 —— 与 Title、物品 tooltip 同源
            Vector3f color = Vec3.fromRGB24(element.titleColor()).toVector3f();
            this.circleParticle = new DustParticleOptions(color, DUST_SCALE);
            // 锚点在这里定一次（构造发生在 use/useOn 的服务端分支里，即玩家真正使用魔源的那一刻）
            this.anchorLevel = player.serverLevel();
            this.anchorX = player.getX();
            this.anchorY = player.getY();
            this.anchorZ = player.getZ();
            this.anchorYaw = player.getYRot();
        }

        /**
         * 把"俯视角 φ"（{@code 0° = 正前方}，沿俯视顺时针增大）换算成世界坐标的单位方向，
         * 写进 {@code out}（{@code out[0] = x, out[1] = z}）。
         * <p>
         * 推导：原版 yaw 的朝向向量是 {@code F = (-sin(yaw), cos(yaw))}（yaw 0 = +Z = 南）。
         * 俯视看下去，屏幕右方 = {@code F × up = (-F.z, F.x)}（验证：面朝南时右手边是西 ✓）。
         * 于是 {@code φ} 方向 = {@code cos(φ)·F + sin(φ)·R}。
         */
        private void direction(double phi, double[] out) {
            double yaw = Math.toRadians(this.anchorYaw);
            double forwardX = -Math.sin(yaw);
            double forwardZ = Math.cos(yaw);
            double rightX = -forwardZ;
            double rightZ = forwardX;

            double cos = Math.cos(phi);
            double sin = Math.sin(phi);
            out[0] = cos * forwardX + sin * rightX;
            out[1] = cos * forwardZ + sin * rightZ;
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
            // 玩家已消失（退出 / 死亡重生）⇒ 立即收手，不要在别人的世界里继续撒粒子
            if (this.player.isRemoved() || this.tick >= TOTAL_TICKS) {
                return false;
            }

            ServerLevel level = this.player.serverLevel();
            // 换维度后锚点已经不在这个世界里 —— 不要拿着旧坐标在新维度里画法阵
            if (level != this.anchorLevel) {
                return false;
            }

            double circleDensity = this.circleDensity();
            if (circleDensity > 0.0D) {
                // 法阵：用**锚点**坐标（脱手，不跟人走）
                this.drawMagicCircle(level, circleDensity);
            }

            if (this.tick >= CLIMAX_START_TICK) {
                double burstDensity = this.burstDensity();
                if (burstDensity > 0.0D) {
                    // 爆发：仍然绑在玩家身上，取**实时**坐标（用户裁定）
                    this.drawBurst(level, this.player.getX(), this.player.getY(), this.player.getZ(),
                            burstDensity);
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
         * 画在<b>锚点</b>（{@link #anchorX} / {@link #anchorY} / {@link #anchorZ}）上，<b>不跟人走</b> ——
         * 玩家在仪式期间移动不会把法阵拖乱。
         * <p>
         * 点数一律<b>按线长反算</b>（{@code ceil(长度 / LINE_SPACING)}）⇒ 体型放大时线密度自动恒定，
         * 不需要另外乘倍数；也是"密集堆叠成实线"而不是点串的保证。
         * <p>
         * 前 {@link #DRAW_TICKS} tick 按进度只画一部分 ⇒ 读作"法阵被画出来"；
         * 之后全量重画（粒子有存活时间，必须每 tick 补，否则法阵会自己消失）。
         */
        private void drawMagicCircle(ServerLevel level, double density) {
            double centerX = this.anchorX;
            double centerY = this.anchorY;
            double centerZ = this.anchorZ;
            double y = centerY + CIRCLE_Y_OFFSET;
            double progress = Math.min(1.0D, (this.tick + 1) / (double) DRAW_TICKS);

            // 半径随体型放大（点数由线长反算 ⇒ 密度自动保持）
            double outerRadius = BASE_OUTER_RADIUS * this.sizeFactor;
            double starRadius = outerRadius * STAR_RADIUS_RATIO;

            // ---- 外圈：从"正前方"（俯视的"上"）起笔、沿俯视顺时针画一圈 ----
            int ringPoints = Math.max(8, (int) Math.ceil(2.0D * Math.PI * outerRadius / LINE_SPACING));
            int ringDrawn = (int) Math.ceil(ringPoints * progress);
            int thinStep = densityStep(density);
            double[] dir = new double[2];
            for (int i = 0; i < ringDrawn; i++) {
                if (i % thinStep != 0) {
                    continue;
                }

                this.direction(2.0D * Math.PI * i / ringPoints, dir);
                this.dust(level, centerX + dir[0] * outerRadius, y, centerZ + dir[1] * outerRadius);
            }

            // ---- 五角星：一笔勾勒 ----
            // 弦长 = 2R·sin72° ≈ 1.902R（比外圈的一段弧长得多，所以点数单独反算）
            double chordLength = 2.0D * starRadius * Math.sin(Math.toRadians(72.0D));
            int pointsPerSegment = Math.max(4, (int) Math.ceil(chordLength / LINE_SPACING));

            // 把 5 段拼成**一条**连续笔迹：索引 → (第几段, 段内第几点)
            int strokePoints = pointsPerSegment * STAR_STROKE_SEGMENTS;
            int strokeDrawn = (int) Math.ceil(strokePoints * progress);
            double step = Math.toRadians(STAR_STROKE_STEP_DEGREES);
            for (int index = 0; index < strokeDrawn; index++) {
                if (index % thinStep != 0) {
                    continue;
                }

                int segment = index / pointsPerSegment;
                int i = index % pointsPerSegment;
                // 除以 (n-1) 而不是 n：两端都要采到 ⇒ 星的**尖**才会被画出来（尖是五角星的身份）
                double t = i / (double) (pointsPerSegment - 1);

                // 第 segment 段：从 (segment × 144°) 连到 ((segment+1) × 144°)。
                // 起笔点 = 0° = 正前方 = 外圈的起笔点 ⇒ 圆与星"从同一个端点开始画"（作者裁定）
                double fromPhi = segment * step;
                double toPhi = fromPhi + step;
                this.direction(fromPhi, dir);
                double fromX = dir[0] * starRadius;
                double fromZ = dir[1] * starRadius;
                this.direction(toPhi, dir);
                double toX = dir[0] * starRadius;
                double toZ = dir[1] * starRadius;

                this.dust(level, centerX + fromX + (toX - fromX) * t, y,
                        centerZ + fromZ + (toZ - fromZ) * t);
            }
        }

        /**
         * 以玩家为球心的向外爆发 —— 用<b>逐 tick 外扩的球壳</b>表达（见类注释：原版无法给单颗粒子
         * 指定定向速度）。球面上的点用 Fibonacci 球均匀分布，两层壳错开半径与相位。
         * <p>
         * 半径同样乘体型倍数 —— 否则大体型玩家的爆发会显得比他的法阵小得多；
         * 但**必须钳在 {@link #BURST_MAX_RADIUS} 以内**（原版投递门 32 格，见该常量）。
         */
        private void drawBurst(ServerLevel level, double centerX, double centerY, double centerZ,
                               double density) {
            double age = this.tick - CLIMAX_START_TICK;
            double outerRadius = Math.min(
                    (BURST_START_RADIUS + age * BURST_RADIUS_PER_TICK) * this.sizeFactor,
                    BURST_MAX_RADIUS);
            double shellGap = BURST_SHELL_GAP * this.sizeFactor;
            double centerYWithOffset = centerY + BURST_Y_OFFSET;
            int thinStep = densityStep(density);

            for (int shell = 0; shell < BURST_SHELLS; shell++) {
                double radius = outerRadius - shell * shellGap;
                if (radius <= 0.05D) {
                    continue;
                }

                for (int i = 0; i < BURST_SHELL_POINTS; i++) {
                    if (i % thinStep != 0) {
                        continue;
                    }

                    // ⚠️ y 用**本层自己的** BURST_SHELL_POINTS 归一 ⇒ 每层各自覆盖完整球面 y ∈ (-1, 1)。
                    // 曾经用 (BURST_SHELL_POINTS × BURST_SHELLS) 作分母、并给索引加"半层"偏移，
                    // 那让两层的索引区间重叠、只用掉 84/112 个索引 ⇒ **外层壳整个落在上半球**、
                    // 下极帽 25.9% 的球面永不采样（渲染证据 docs/pictures/preview/burst_shells.png）。
                    double y = 1.0D - 2.0D * (i + 0.5D) / BURST_SHELL_POINTS;
                    double ringRadius = Math.sqrt(Math.max(0.0D, 1.0D - y * y));
                    // 两层壳的"相位错开"只作用在 theta 上（错开半层，避免重叠时看起来只有一层）
                    double theta = GOLDEN_ANGLE * (i + shell * BURST_SHELL_POINTS * 0.5D);

                    level.sendParticles(this.burstParticle,
                            centerX + Math.cos(theta) * ringRadius * radius,
                            centerYWithOffset + y * radius,
                            centerZ + Math.sin(theta) * ringRadius * radius,
                            1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
            }
        }

        /**
         * 把"密度"换算成**均匀抽稀**的步长：{@code density = 1 ⇒ 1}（全画）、{@code 0.5 ⇒ 2}（隔一个画一个）……
         * <p>
         * ⚠️ 这里刻意**不用"截断点数"**来表达衰减（曾经如此）：截断是从索引起点砍到上界
         * ⇒ 收尾时法阵会**从收笔处向起笔处蚀退**（圆只剩起笔那一段弧、星的最后一笔先消失），
         * 读起来像"被擦掉"而不是"整体变稀"。均匀抽稀让整条线一起变稀（审查后按作者裁定改掉）。
         * <p>
         * 只在 {@code density ∈ (0, 1]} 被调用（两条密度曲线在 0 处已由 {@code advance()} 短路）。
         */
        private static int densityStep(double density) {
            if (density <= 0.0D) {
                return Integer.MAX_VALUE;
            }

            return Math.max(1, (int) Math.round(1.0D / density));
        }

        /** 法阵的一颗粒子（{@code count = 1}：必须逐颗定点发，否则是长方体随机）。 */
        private void dust(ServerLevel level, double x, double y, double z) {
            level.sendParticles(this.circleParticle, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }
}
