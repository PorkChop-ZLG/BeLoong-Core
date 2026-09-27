package com.zonlong.beloong.client.model;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.entity.MoEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * 末（Mo）模型：三个资源路径 + <b>转头看向玩家</b>。
 * <p>
 * 三份资产由**本地模型暂存目录** {@code docs/models/末/} 迁移而来（该目录专门存放模型源文件、不入库）：
 * <ul>
 *   <li>{@code geo/mo.geo.json} —— 388 KB，<b>211 骨骼 / 752 立方体 / 1 根根骨骼（{@code Root}）</b>，
 *       无重名骨骼、无悬空 parent，{@code texture_width/height} 声明 256×256
 *       （与贴图实测一致）。其 {@code identifier} 是 {@code geometry.unknown}，**无需修正**：
 *       GeckoLib 按本类返回的<b>文件路径</b>取模型，{@code identifier} 只是可空元数据。</li>
 *   <li>{@code textures/entity/mo.png} —— 256×256 贴图。</li>
 *   <li>{@code animations/mo.animation.json} —— <b>9.1 MB / 29 个动画</b>，整份放上来备用；
 *       目前播四个：{@code 待机动画}、{@code walk}、{@code run}（主状态机）与
 *       {@code 翅膀默认（展开）}（翅膀常驻层，注册顺序见 {@link MoEntity#registerControllers}）。
 *       <p>
 *       ⚠️ <b>这一个文件被改过</b>（其余两份与源文件逐字节相同）：2026-09-27 从
 *       {@code 翅膀默认（展开）} 里删掉了 5 个泄漏键
 *       （{@code Root.position} / {@code Root.scale} / {@code Weapen.position} /
 *       {@code Weapen.scale} / {@code Tail.scale}，共 188 字节、24 → 22 骨骼）。
 *       缘由与证据见 {@link MoEntity} 的类注释 —— 那 5 个通道让整个模型被放大 1.8 倍、
 *       整体位移、武器脱手。29 条动画的数量与其余内容未变。</li>
 * </ul>
 *
 * <h2>转头看向玩家：为什么走 Java 而不是资产里的 Molang</h2>
 * 地黄龙的做法是"资产里写 {@code query.head_yaw} 表达式 + 这里注册变量"，
 * <b>本模型照搬不了</b>，因为那条链有三个必要条件，Mo 缺了后两个：
 * <ol>
 *   <li>资产里有 head 跟随表达式 —— Mo <b>有</b>，但只在 {@code walk}/{@code run}/{@code riptide} 等
 *       <b>导入的玩家状态动画</b>里，且用的是 YSM 私有命名空间 {@code ysm.head_yaw}；</li>
 *   <li>表达式挂在<b>存在于 geo 的骨骼</b>上 —— Mo 里那些表达式挂在
 *       {@code Root_Molang} / {@code MHead} / {@code MAllBody} / {@code Head_Molang} /
 *       {@code AllBody_Molang} 上，而<b>这五根骨骼一根都不在 geo 里</b>
 *       （属 155 根缺失骨骼）⇒ 变量注册了也没人读；</li>
 *   <li><b>待机动画</b>里有跟随 —— Mo <b>完全没有</b>：{@code 待机动画} 的 {@code Head}
 *       只有 18 帧固定点头曲线。而站桩 NPC 绝大多数时间就只在播这一条。</li>
 * </ol>
 * Mo 全部的 {@code _Molang} 骨骼只有 6 根，且全是<b>眼睛</b>（pupil / Iris / light ×左右）。
 * <p>
 * ⇒ 改用 GeckoLib 官方指定的钩子 {@link #setCustomAnimations}，它在
 * {@code GeoModel.java:226} 被调用，**紧跟在 {@code :224} 的
 * {@code processor.tickAnimation(...)} 之后** —— 正是"动画刚写进骨骼"的那一刻，
 * 官方 javadoc 里点名它就是用来做 head rotation 的。
 *
 * <h2>为什么"加增量"不会累积</h2>
 * {@code AnimationProcessor.java:139-167}：对<b>当前动画没有驱动</b>的骨骼，
 * 每帧都会按 {@code percentageReset} 把它<b>绝对写回</b> initial snapshot
 * （{@code percentageReset} 在 {@code getBoneResetTime()} 后收敛到 1）。
 * ⇒ 每帧起点都是干净的绝对值，本类只在此基础上<b>叠加</b>一个增量，不会越转越多。
 * <p>
 * 对 {@code Head} 尤其稳：{@code 待机动画} / {@code walk} / {@code run} 三条都会
 * 绝对写 {@code Head} 的旋转。
 *
 * <h2>已知的表达力上限</h2>
 * Mo <b>没有脖子链</b>（{@code UpperBody → AllHead → Head} 直接到头，{@code AllHead} 与
 * {@code Head} 各只有 1~2 个立方体），做不到地黄龙 `NeckA~D-Molang` 那种五节颈链的柔顺分层。
 * 这里退而求其次：把角度按 {@link #ALL_HEAD_SHARE}/{@link #HEAD_SHARE} 两段分摊，
 * 近似"先颈后头"。
 *
 * <h2>刻意不覆写 {@code crashIfBoneMissing()}</h2>
 * 本模型有 <b>155 根被动画引用、但 geo 里不存在的骨骼</b>（翅膀 / 头发 / 披风 / 发光件等），
 * 分布在 14 条动画上。GeckoLib 默认
 * {@code crashIfBoneMissing() == false}（{@code GeoModel.java:91-93}），
 * 配合 {@code AnimationController.java:529-534} 的
 * {@code if (bone == null) continue;} ⇒ 只是那些骨骼不动、**不崩也不打日志**。
 * <p>
 * ⚠️ <b>不要"为了尽早发现问题"把它覆写成 {@code true}</b>：那会让本实体在**运行期**
 * 第一次播这些动画时直接抛 {@code RuntimeException("Could not find bone: ...")}。
 * 覆写是编译期就能改的，而崩是运行期才发生的 —— 这类"防御性收紧"在这里是纯粹的负收益。
 */
public class MoModel extends GeoModel<MoEntity> {

    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "geo/mo.geo.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "textures/entity/mo.png");
    private static final ResourceLocation ANIMATION =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "animations/mo.animation.json");

    // 注：GeckoLib 4.9 里这三个单参版本标了 @Deprecated，但**仍是抽象方法**，必须实现；
    // 双参重载（带 renderer）默认转调它们 —— 与地黄龙模型、BWG 的 PumpkinWardenModel 写法一致。

    @Override
    public ResourceLocation getModelResource(MoEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(MoEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(MoEntity animatable) {
        return ANIMATION;
    }

    // ===================== 转头看向玩家 =====================

    /**
     * 相对偏航角的钳制上限（度）。
     * <p>
     * 原版 {@code Mob#getMaxHeadYRot()} 默认 75 —— 站桩时 {@code BodyRotationControl}
     * 会把身体稳定夹在"离头不超过 75°"，所以 {@code yHeadRot - yBodyRot} 实际很少超过它。
     * 这里再钳一次是防御：换任何 goal / 被别的模组改过 head 速度时都不会甩出怪异角度。
     */
    private static final float MAX_RELATIVE_YAW = 75.0F;

    /** 俯仰钳制（度）。取 30 是因为 NPC 常被贴脸看，不钳的话头会仰得离谱。 */
    private static final float MAX_PITCH = 30.0F;

    /** {@code AllHead}（"脖子"）分摊的比例。 */
    private static final float ALL_HEAD_SHARE = 0.35F;

    /** {@code Head} 分摊的比例。两者相加约 0.85 —— 不取 1.0 是留一点余地，避免观感过冲。 */
    private static final float HEAD_SHARE = 0.50F;

    /**
     * 偏航符号（<b>实机标定</b>）。
     * <p>
     * 量纲推导：Bedrock 骨骼旋转是 {@code [x, y, z] = [pitch, yaw, roll]}，所以<b>yaw 走 Y 轴</b>；
     * {@code BakedAnimationsAdapter.java:205-207} 把 Bedrock 常量旋转转成 GeckoLib 弧度时
     * <b>X/Y 取负、Z 不取负</b>，所以本类直接写 {@code setRotY} 时符号必须自己定。
     * <p>
     * 初值取 {@code -1.0F}，与地黄龙那条链的**净效果**一致
     * （那边资产写 {@code -clamp(head_yaw*...)}、GeckoLib 再对 Molang 的 Y 取负，
     * 净下来也是负号；其 {@code HEAD_YAW_SIGN} 注释同样写着"原本取 +1.0F 时方向是反的"）。
     * <p>
     * <b>若实机发现头转向与玩家所在方向相反，把它翻成 {@code +1.0F} 即可</b>，
     * 不需要动资产、也不需要动别处。
     */
    private static final float HEAD_YAW_SIGN = -1.0F;

    /**
     * 每渲染帧调用一次，时机在动画已写入骨骼之后（{@code GeoModel.java:224 → :226}）。
     * <p>
     * 三个量都按<b>插值后</b>取值，口径与 {@code GeoEntityRenderer.java:218} 完全一致
     * （它就是这么做 {@code lerpBodyRot} 的），否则 20Hz 的阶梯感会很明显。
     */
    @Override
    public void setCustomAnimations(MoEntity animatable, long instanceId, AnimationState<MoEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        final float partialTick = animationState.getPartialTick();

        // 模型是按 yBodyRot 绘制的（GeoEntityRenderer.java:218 算 lerpBodyRot、:250 传给
        // applyRotations），所以"头相对身体"就是下面这个差 —— 与地黄龙同一个公式。
        final float bodyYaw = Mth.rotLerp(partialTick, animatable.yBodyRotO, animatable.yBodyRot);
        final float headYaw = Mth.rotLerp(partialTick, animatable.yHeadRotO, animatable.yHeadRot);
        final float headPitch = Mth.lerp(partialTick, animatable.xRotO, animatable.getXRot());

        final float relativeYaw = Mth.clamp(Mth.wrapDegrees(headYaw - bodyYaw), -MAX_RELATIVE_YAW, MAX_RELATIVE_YAW);
        final float pitch = Mth.clamp(headPitch, -MAX_PITCH, MAX_PITCH);

        // 两段分摊，近似"先颈后头"
        addHeadTurn("AllHead", relativeYaw * ALL_HEAD_SHARE, pitch * ALL_HEAD_SHARE);
        addHeadTurn("Head", relativeYaw * HEAD_SHARE, pitch * HEAD_SHARE);
    }

    /**
     * 在动画已写入的值之上<b>叠加</b>一个头部姿态增量。
     * <p>
     * 用 {@code getRotY() + delta} 而不是 {@code setRotY(delta)}：后者会抹掉待机动画
     * 本身的点头/摆动曲线。
     * <p>
     * 单位是<b>弧度</b>（{@code GeoBone#setRotY} 收的是弧度：
     * {@code AnimationProcessor.java:109} 写进去的就是 {@code Math.toRadians} 的结果）。
     *
     * @param boneName 目标骨骼名；不存在时静默跳过（GeckoLib 的一贯口径）
     */
    private void addHeadTurn(String boneName, float yawDeg, float pitchDeg) {
        final GeoBone bone = getBone(boneName).orElse(null);
        if (bone == null) {
            return;
        }

        if (yawDeg != 0.0F) {
            bone.setRotY(bone.getRotY() + yawDeg * Mth.DEG_TO_RAD * HEAD_YAW_SIGN);
        }
        if (pitchDeg != 0.0F) {
            // 俯仰走 X 轴；符号与 yaw 无关，与地黄龙一样先按正号来（实机若上下反了翻这里）
            bone.setRotX(bone.getRotX() + pitchDeg * Mth.DEG_TO_RAD);
        }
    }
}
