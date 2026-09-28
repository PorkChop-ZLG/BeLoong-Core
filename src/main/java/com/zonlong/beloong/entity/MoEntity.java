package com.zonlong.beloong.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * 末（Mo）NPC —— {@link NpcEntity} 的第二个子类。
 * <p>
 * AI、无敌 / 不可推动 / 不消失、移动与攻击能力全部继承基类；本类只回答"是什么"，
 * 外加三处**资产决定的**必要适配（见下）。碰撞箱与实体类型绑定在
 * {@code registry/ModEntities}，模型 / 贴图 / 渲染器在 {@code client/} 侧。
 *
 * <h2>资产来源</h2>
 * 三份资产（geo / animation / texture）由**本地模型暂存目录** {@code docs/models/末/} 迁移而来
 * （该目录专门存放模型源文件、**不入库**——模型是第三方作者的资产）。
 * 该模型原本是给 **Yes Steve Model（YSM）** 用的玩家模型——这一点决定了下面几处适配，
 * 完整分析见 {@code docs/末模型分析.md}（含 GeckoLib 与 YSM 双侧源码证据）。
 *
 * <h2>2026-09-27 实机反馈与修复：三个症状同一个根因</h2>
 * 实机报了三件事：① 模型比碰撞箱大太多；② 武器位置偏移、不在手上；③ 模型整体偏离碰撞箱。
 * 三者**同源**，而且都不是"模型做得太大"，是我把 {@code wings_idle}（当时还叫 {@code 翅膀默认（展开）}）当叠加层用错了。
 * <p>
 * 该动画**不是"翅膀姿势层"，而是一整套「有翼形态」的全身姿态**：它给
 * {@code Root} 设了 {@code scale = 1.8} 与 {@code position = [-13, -7.98, 16.89]}，
 * 给 {@code Weapen} 设了 {@code position} 与 {@code scale = 1.2}。
 * 而 {@code AnimationProcessor.java:107-127} 对每根骨骼是**按通道独立**写值的：
 * 某控制器**没设**的通道，它**不会**把骨骼复位。
 * {@code idle} 里**根本没有 {@code Root} 这一轨**，也不给 {@code Weapen} 设位移/缩放 ⇒
 * 那三个通道从翅膀层**原样泄漏**到最终姿态：
 * <ul>
 *   <li>{@code Root.scale = 1.8} ⇒ 整个模型被放大 1.8 倍（症状 ①）；</li>
 *   <li>{@code Root.position} ⇒ 整个模型被平移约 0.8 格横 / 0.5 格下 / 1.1 格前（症状 ③）；</li>
 *   <li>{@code Weapen.position} + {@code Weapen.scale} ⇒ 武器被推离手并放大 1.2 倍；
 *       而武器的**旋转**来自 {@code idle} ⇒ 混合姿态（症状 ②）。</li>
 * </ul>
 * <b>修法</b>（用户裁定"保留翅膀张开"）：从资产 {@code animations/mo.animation.json} 的
 * {@code wings_idle} 里删掉 5 个泄漏键 —— {@code Root.position}、{@code Root.scale}、
 * {@code Weapen.position}、{@code Weapen.scale}、{@code Tail.scale}（共 188 字节，
 * 括号内的 24 → 22 根骨骼）。当时其余 29 条动画一字未动
 * （{@code fly}/{@code swim}/{@code swim_stand} 是 2026-09-27 另一次改动修的，见下），
 * {@code Root}/{@code Tail}/{@code Weapen}
 * 三根骨骼本身当然仍在 geo 中，只是不再被翅膀层改。
 * 另加了 0.80 的渲染缩放（见 {@code MoRenderer}）——模型按原版玩家的骨架尺寸算是偏大的，
 * 缩放后头顶与原版玩家齐平。这是**与上述泄漏无关的另一件事**。
 *
 * <h2>来自资产的其余缺陷</h2>
 * GeckoLib 对"动画/骨骼找不到"**完全静默**，因此这些不会报错，只会表现为"某些东西不动"：
 * <ul>
 *   <li>✅ <b>已修（2026-09-27）</b>：{@code fly} / {@code swim} / {@code swim_stand} 原先在加载期
 *       就被**整条丢弃** —— 它们的 Molang 表达式含单引号与中文，违反 GeckoLib 的
 *       {@code MathParser.EXPRESSION_FORMAT}（{@code MathParser.java:46} 那个既不含 {@code '}
 *       也不含 CJK 的字符类），而 {@code BakedAnimationsAdapter} 是**按条 try/catch、失败即丢整条**。
 *       症状是"代码请求它们时毫无反应也不报错"。
 *       <p>
 *       修法是**删掉那 10 条表达式所在的 6 个骨骼条目**（{@code AllBody_Molang} 与
 *       {@code Head_Molang}，三条各一对）—— 这两根骨骼**都不在 geo 里**，GeckoLib 本来就会
 *       {@code if (bone == null) continue} 跳过 ⇒ 删它们是**纯删死数据，不改变任何可见姿态**，
 *       却让三条动画从此能加载。{@code fly} 因此可用于飞行 AI（见 {@code NpcEntity#flyAnimationName()}）。
 *       <br>⚠️ <b>{@code run} 里也有一条 {@code AllBody_Molang}，但那条内容合法，未动</b> ——
 *       改这类问题时不要按骨骼名全局替换，要按「所在动画 + 是否真的违规」逐个确认。</li>
 *   <li>{@code walk} / {@code run} 各引用了 74 / 75 根模型不存在的骨骼
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
     * 待机动画名 —— 资产里这条叫 {@code idle}（117 骨骼 / 4 秒 / <b>0 缺失骨骼</b>）。
     * {@code walk} / {@code run} 的名字与基类默认一致，故不覆写。
     * <p>
     * <b>2026-09-27：键名已从中文改为 ASCII，那条字符集陷阱就此闭环。</b>
     * 原名是 {@code 待机动画}，与 {@code 翅膀默认（展开）}（→ {@code wings_idle}）一起英文化。
     * 依据留档如下，<b>不要再改回中文</b>。
     * <p>
     * GeckoLib 读 json 用的是
     * {@code IOUtils.toString(inputStream, Charset.defaultCharset())}
     * （{@code FileLoader.java:73}）—— **平台默认字符集，不是 UTF-8**。
     * Java 21（JEP 400）起该默认值就是 UTF-8，本机实测也是
     * {@code file.encoding=UTF-8}（{@code native.encoding=GBK}），所以中文键名**平时**能对上；
     * 迁移时也曾用字节比对验证过类文件与 json 里的名字一致。
     * <p>
     * 但若启动器额外传了 {@code -Dfile.encoding=GBK}（部分旧版中文启动器会这么干），
     * 资产里的中文键名会被按 GBK 解码、与类里的 UTF-8 字面量**对不上**；
     * 而 GeckoLib 对"找不到动画"是**静默跳过**（{@code AnimationProcessor.java:60-61} 的
     * {@code if (animation != null)}）⇒ 症状是**待机动画永远不播、且没有任何日志**，极难排查。
     * <p>
     * 改成 ASCII 后，编译期字面量与资产字节**都不再依赖任何字符集**，该隐患彻底消失。
     * 资产的另 6 个中文键名（{@code 躯体选择备份} 等）代码从不引用，故未改。
     */
    @Override
    protected String idleAnimationName() {
        return "idle";
    }

    /**
     * 翅膀常驻层：{@code wings_idle}（22 骨骼）。
     * <p>
     * 做成 {@code static final} 常量而不是每 tick 构造：它不依赖任何可覆写方法
     * （与基类里那三个"名字来自虚方法"的情况不同），没有构造期求值问题。
     * <p>
     * 键名与 {@link #idleAnimationName()} 一起英文化，理由见那里。
     */
    private static final RawAnimation WINGS_IDLE =
            RawAnimation.begin().thenLoop("wings_idle");

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
     * 两条动画至今仍在这些骨骼上重叠：{@code Weapen}、{@code LeftHand}、{@code RightArm}、
     * {@code RightForeArm}（翅膀层给的是"有翼形态"的手臂姿势，主状态机给的是待机姿势）。
     * 把主状态机放在后面 ⇒ 手臂与武器一律以**待机姿势**为准；翅膀骨骼主状态机根本不碰
     * （{@code idle} 0 个翅膀骨骼）⇒ 由翅膀层提供姿态，不会被覆盖。
     * <p>
     * 若把顺序调成"主在前、翅膀在后"，翅膀层那套手臂/武器姿势会盖掉待机姿势
     * —— 那正是 2026-09-27 那批症状的来源之一（当时的 {@code Root}/{@code Weapen}
     * 位移缩放泄漏已另行从资产里删掉，但**手臂旋转仍在重叠**，所以顺序依然必须保持）。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "wings", 0, state -> {
            // 飞行时**停掉整个翅膀层**：fly 动画本身已经含翅膀动作，两层同时写同一批骨骼
            // 会互相拉扯（GeckoLib 对同一根骨骼是绝对赋值、后注册者覆盖前者，
            // 见 AnimationProcessor.java:107-127）。
            // 用 STOP 而不是"换成一条空动画"：停掉该控制器后，被 fly 驱动的骨骼由主控制器写，
            // 两者都没驱动的骨骼按既有复位机制回到 geo 的静止姿态，**不会永久残留旧姿态**。
            // ⚠️ 机制细节（2026-09-27 核 4.9.2 字节码）：STOP 那一帧
            // AnimationController.process 会提前 return，**不重建**该控制器的骨骼队列
            // （队列此时已空 ⇒ 不写骨骼）；而"复位"并非瞬时，是 AnimationProcessor 用
            // getBoneResetTime()（GeoAnimatable 默认 5.0）做的**限时插值**
            // ⇒ 翅膀层停写后，那批骨骼会在约 5 tick 内插值回静止姿态。
            // 想让过渡立刻完成可以覆写 getBoneResetTime() → 0，本类没这么做。
            if (state.getAnimatable().isFlying()) {
                return PlayState.STOP;
            }
            return state.setAndContinue(WINGS_IDLE);
        }));
        super.registerControllers(controllers);
    }

    // ===================== 视锥剔除 =====================

    /**
     * 模型最远几何延伸（格），**按渲染缩放 0.80 折算后**。
     * <p>
     * 原始包围盒（16 单位 = 1 格）：{@code X ±26.74 / Y -27.04~+58.04 / Z -8.02~+118.58} 单位，
     * 乘 0.80 后为 {@code X ±1.34 / Y -1.35~+2.90 / Z -0.40~+5.93} 格 ——
     * 最远是翅膀向后 <b>5.93 格</b>，而碰撞箱只有 {@code 0.6 × 1.8}。
     * 取 {@code 7.0} 以盖住 5.93 + 半个箱宽并留余量
     * （geo 里作者标的 {@code visible_bounds_width: 16} 也与此量级一致，
     * 但那个字段在 GeckoLib 4.x 里是**死数据**、全仓无消费者，改 geojson 没有用）。
     * <p>
     * 注意：这个外扩量**依赖 {@code MoRenderer.MODEL_SCALE}**。若将来改缩放，
     * 这里要按同一比例跟着改，否则会出现"翅膀在画面里消失"的回归。
     */
    private static final double CULLING_INFLATE = 7.0D;

    /**
     * 扩大视锥剔除盒 —— <b>不这么做，翅膀会在画面里凭空消失。</b>
     * <p>
     * 原版剔除用的是 {@code EntityRenderer#shouldRender} 里的
     * <b>{@code livingEntity.getBoundingBoxForCulling().inflate(0.5)}</b>
     * （{@code EntityRenderer.java:58}，另见 {@code :76} 的距离判断）——
     * 取的是**实体上的这个方法**，不是渲染器上的，所以覆写点在本类而不在 {@code MoRenderer}。
     * 默认实现返回碰撞箱，于是"身体离开视锥"就等价于"整个模型不画"，
     * 而本模型的翅膀在身体之外还有近 6 格。
     * <p>
     * <b>本仓先例</b>：地黄龙没有这个问题（它 9 格长但碰撞箱 1.5×2.5，且是"贴地"造型，
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
