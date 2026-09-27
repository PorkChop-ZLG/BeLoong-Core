package com.zonlong.beloong.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * 末（Mo）NPC —— {@link NpcEntity} 的第二个子类。
 * <p>
 * AI、无敌 / 不可推动 / 不消失、移动与攻击能力全部继承基类；本类只回答"是什么"，
 * 外加三处**资产决定的**必要适配（见下）。碰撞箱与实体类型绑定在
 * {@code registry/ModEntities}，模型 / 贴图 / 渲染器在 {@code client/} 侧。
 *
 * <h2>资产来源</h2>
 * 三份资产（geo / animation / texture）由 {@code docs/models/末/} **原样迁移**，
 * 内容未做任何改动（迁移时逐文件 SHA256 比对一致）。该模型原本是给
 * **Yes Steve Model（YSM）** 用的玩家模型——这一点决定了下面几处适配，
 * 完整分析见 {@code docs/models/末/mo-模型分析.md}（含 GeckoLib 与 YSM 双侧源码证据）。
 *
 * <h2>本类相对地黄龙多做的三件事</h2>
 * <ol>
 *   <li><b>待机动画名是中文</b>（{@code 待机动画}）—— 资产里没有 {@code idle}。
 *       见 {@link #idleAnimationName()} 里关于**字符集**的警告。</li>
 *   <li><b>多一个常驻的翅膀控制器</b> —— 待机动画完全不含翅膀骨骼（0 个），
 *       若不额外播翅膀层，静止姿态下右翼会插进地面 1.7 格。见 {@link #registerControllers}。</li>
 *   <li><b>扩大视锥剔除盒</b> —— 几何体远超碰撞箱（见 {@link #getBoundingBoxForCulling()}）。</li>
 * </ol>
 *
 * <h2>已知的、来自资产的缺陷（本次刻意不修）</h2>
 * GeckoLib 对"动画/骨骼找不到"**完全静默**，因此这些不会报错，只会表现为"某些东西不动"：
 * <ul>
 *   <li>{@code fly} / {@code swim} / {@code swim_stand} 三条动画在加载期就被丢弃
 *       （其 Molang 表达式含单引号与中文，违反 GeckoLib 的
 *       {@code MathParser.EXPRESSION_FORMAT}）—— 代码请求它们时**毫无反应也不报错**；</li>
 *   <li>{@code walk} / {@code run} 各引用了 74 / 75 根本模型不存在的骨骼
 *       （翅膀、头发、披风、发光件），这些骨骼只是**不动**，不影响身体；</li>
 *   <li>{@code walk} / {@code run} 里的 {@code ysm.head_yaw} 是 YSM 私有变量，
 *       在 GeckoLib 下未注册 ⇒ **恒为 0** ⇒ 走路时头不随视角转（有意先不处理）。</li>
 * </ul>
 */
public class MoEntity extends NpcEntity {

    public MoEntity(EntityType<? extends MoEntity> entityType, Level level) {
        super(entityType, level);
    }

    /**
     * 属性表。全部基础值来自 {@link NpcEntity#createNpcAttributes()}
     * （血量 1000 / 击退抗性 1.0 / 攻击力 100 / 移速 0.3）。
     * <p>
     * 注册点在 {@code registry/ModAttributes}（属性是服务端权威的，必须放双端都加载的类）。
     */
    public static AttributeSupplier.Builder createAttributes() {
        return NpcEntity.createNpcAttributes();
    }

    // ===================== 动画名 =====================

    /**
     * 待机动画名 —— 资产里这条叫 {@code 待机动画}（117 骨骼 / 4 秒 / <b>0 缺失骨骼</b>），
     * 没有 {@code idle}。{@code walk} / {@code run} 的名字与基类默认一致，故不覆写。
     * <p>
     * ⚠️ <b>这里有一个字符集陷阱，动它之前先读完。</b>
     * GeckoLib 读取 json 用的是
     * {@code IOUtils.toString(inputStream, Charset.defaultCharset())}
     * （{@code FileLoader.java:73}）—— **平台默认字符集，不是 UTF-8**。
     * 而 Java 21（JEP 400）起该默认值就是 UTF-8，本机实测也是
     * {@code file.encoding=UTF-8}（{@code native.encoding=GBK}），因此现在能对上。
     * <p>
     * 但若启动器额外传了 {@code -Dfile.encoding=GBK}（部分旧版中文启动器会这么干），
     * 资产里的中文动画名会被按 GBK 解码、与这里的 UTF-8 字面量**对不上**；
     * 而 GeckoLib 对"找不到动画"是**静默跳过**（{@code AnimationProcessor.java:60-61} 的
     * {@code if (animation != null)}）⇒ 症状是**待机动画永远不播，且没有任何日志**。
     * <p>
     * 彻底免疫的唯一办法是把资产里的动画名改成 ASCII（如 {@code 待机动画} → {@code idle}），
     * 但那会改动"原样迁移"的资产，故留待用户裁定。
     */
    @Override
    protected String idleAnimationName() {
        return "待机动画";
    }

    /**
     * 翅膀常驻层：{@code 翅膀默认（展开）}（24 骨骼 / 0 缺失骨骼）。
     * <p>
     * 做成 {@code static final} 常量而不是每 tick 构造：它不依赖任何可覆写方法
     * （与基类里那三个"名字来自虚方法"的情况不同），没有构造期求值问题。
     */
    private static final RawAnimation WINGS_EXPANDED =
            RawAnimation.begin().thenLoop("翅膀默认（展开）");

    /**
     * 两个控制器：<b>翅膀层在前、主状态机在后</b>。
     * <p>
     * <b>顺序是刻意反的，不要"顺手"调换。</b>
     * GeckoLib 按**注册顺序**应用控制器
     * （{@code AnimatableManager.ControllerRegistrar#build} 用保序的
     * {@code Object2ObjectArrayMap}，{@code AnimationProcessor.java:80} 顺序遍历），
     * 而对同一根骨骼是**绝对赋值**而非叠加
     * （{@code AnimationProcessor.java:107-110} 的 {@code bone.setRotX(...)}）
     * ⇒ **后注册者覆盖前者**。
     * <p>
     * 两条动画在 {@code Tail} 与 {@code Weapen} 上有重叠（翅膀那条 24 骨骼里含这两个）。
     * 把主状态机放在后面，效果就是：
     * <ul>
     *   <li>{@code Tail} / {@code Weapen} 归主状态机 ⇒ <b>待机时尾巴会动</b>；</li>
     *   <li>翅膀骨骼主状态机根本不碰（待机动画 0 个翅膀骨骼）⇒ 由翅膀层提供姿态，
     *       不会被覆盖。</li>
     * </ul>
     * 若把顺序调成"主在前、翅膀在后"，结果是翅膀层每帧都把尾巴盖成固定姿态，
     * 尾巴就不动了。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "wings", 0,
                state -> state.setAndContinue(WINGS_EXPANDED)));
        super.registerControllers(controllers);
    }

    // ===================== 视锥剔除 =====================

    /**
     * 模型最远几何延伸（格）。实测包围盒为
     * {@code X ±1.67 / Y -1.69~+3.63 / Z -0.50~+7.41}（16 单位 = 1 格），
     * 即翅膀向后伸约 <b>7.4 格</b>；碰撞箱只有 {@code 0.6 × 1.8}。
     * <p>
     * 取 {@code 8.0} 是为了盖住 7.41 + 半个箱宽；geojson 里作者自己标的
     * {@code visible_bounds_width: 16}（= 16 格宽）与此基本一致，可作旁证
     * —— 但要注意那个字段在 GeckoLib 4.x 里是**死数据**（全仓无消费者），
     * 所以这里必须自己写，改 geojson 没用。
     */
    private static final double CULLING_INFLATE = 8.0D;

    /**
     * 扩大视锥剔除盒 —— <b>不这么做，翅膀会在画面里凭空消失。</b>
     * <p>
     * 原版剔除用的是 {@code EntityRenderer#shouldRender} 里的
     * <b>{@code livingEntity.getBoundingBoxForCulling().inflate(0.5)}</b>
     * （{@code EntityRenderer.java:58}，另见 {@code :76} 的距离判断）——
     * 取的是**实体上的这个方法**，不是渲染器上的，所以覆写点在本类而不在 {@code MoRenderer}。
     * 默认实现返回碰撞箱，于是"身体离开视锥"就等价于"整个模型不画"，
     * 而本模型的翅膀在身体之外还有 7 格。
     * <p>
     * <b>本仓先例</b>：{@code TornadoRenderer} 走的是另一条路（把 {@code shadowRadius} 设 0），
     * 与本条无关；地黄龙没有这个问题（它 9 格长但碰撞箱 1.5×2.5，且是"贴地"造型，
     * 该缺口记在代码审查 P1-8）。
     * <p>
     * 对称外扩而不是"按模型方向外扩"是必须的：本实体会被
     * {@code LookAtPlayerGoal} 转向玩家，翅膀的朝向随之旋转。
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        return super.getBoundingBoxForCulling().inflate(CULLING_INFLATE);
    }
}
