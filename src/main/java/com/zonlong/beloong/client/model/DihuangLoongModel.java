package com.zonlong.beloong.client.model;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.entity.DihuangLoongEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.loading.math.MathParser;
import software.bernie.geckolib.model.GeoModel;

/**
 * 地黄龙模型：三个资源路径 + 头部跟随的 Molang 查询注册。
 * <p>
 * 三个文件由 {@code D:\Minecraft\Models\Geckolib\dihuang_loong_npc\} **原样移植**，内容未做任何改动：
 * <ul>
 *   <li>{@code geo/dihuang_loong.geo.json} —— 145 骨骼 / 340 立方体，
 *       其 {@code identifier} 是 {@code geometry.unknown}。**无需修正**：GeckoLib 按本类返回的
 *       <b>文件路径</b>取模型，{@code identifier} 只是可空元数据（{@code ModelProperties.java:35}）。</li>
 *   <li>{@code animations/dihuang_loong.animation.json} —— 96 个动画，整份放上来备用；
 *       目前只播 {@code idle}（32 骨骼 / 4.75s）与 {@code walk}（63 骨骼 / 1.375s），
 *       两者骨骼覆盖 **0 缺失**。</li>
 *   <li>{@code textures/entity/dihuang_loong.png} —— 256×256 贴图。</li>
 * </ul>
 * <b>已知的动画骨骼缺口</b>：该动画文件源自"龙之生存"的另一个龙种模型，其中 {@code jump} 等
 * 动作引用了本模型没有的骨骼（如 {@code Mustache*}、{@code Whisker*}）。GeckoLib 对缺失骨骼是
 * **优雅忽略**（{@code GeoModel#shouldCrashOnMissingBone()} 默认 false），因此只是观感问题、不会崩。
 * <p>
 * <b>头部跟随为什么在这里而不是通用 NPC 基类里</b>：它是 {@code GeoModel} 侧的东西，
 * 与实体侧的 AI 无关；用户裁定不抽模型基类（设计文档 D40）。
 */
public class DihuangLoongModel extends GeoModel<DihuangLoongEntity> {

    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "geo/dihuang_loong.geo.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "textures/entity/dihuang_loong.png");
    private static final ResourceLocation ANIMATION =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "animations/dihuang_loong.animation.json");

    /**
     * 头部朝向符号（实机调试常量）。
     * <p>
     * GeckoLib 对 **Molang** 值的 X/Y 轴会取负（{@code AnimationController.java:747-761}：
     * {@code toRadians(...)} 之后 {@code *= -1}），而 MC 的 yaw 正向与 Blockbench 骨骼旋转正向
     * 之间的关系只能实机确认。**若实机发现头朝反方向，把这里改成 {@code -1.0F} 即可**
     * —— 不需要动动画文件。
     */
    private static final float HEAD_YAW_SIGN = 1.0F;

    // 注：GeckoLib 4.9 里这三个单参版本标了 @Deprecated，但**仍是抽象方法**，必须实现；
    // 双参重载（带 renderer）默认转调它们 —— 与 BWG 的 PumpkinWardenModel 写法一致。

    @Override
    public ResourceLocation getModelResource(DihuangLoongEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(DihuangLoongEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(DihuangLoongEntity animatable) {
        return ANIMATION;
    }

    /**
     * 注册 {@code query.head_yaw} / {@code query.head_pitch} —— **资产早就等着这两个查询**。
     * <p>
     * {@code idle}、{@code walk} 等 **34 个动画**里写着这样的表达式：
     * <pre>
     * Head-Molang.rotation = ["math.clamp(query.head_pitch*0.5,-45,45)",
     *                         "-math.clamp(query.head_yaw*0.355556,-32,32)", 0]
     * Neck-Molang.rotation = [0, "-math.clamp(query.head_yaw*0.355556,-32,32)",
     *                         "math.clamp(query.head_yaw*0.111111,-10,10)"]
     * </pre>
     * 而 GeckoLib **不内置** head_yaw / head_pitch —— 未注册的查询会**静默取 0**
     * （{@code MolangQueries.java:148-150}）。这就是为什么此前那一整条
     * {@code Head-Molang} / {@code Neck-Molang} / {@code NeckA~D-Molang} 骨骼链完全是死的。
     * <p>
     * <b>两个值都必须是「度」</b>（{@code AnimationController.java:747-761}），
     * 且 {@code head_yaw} 必须是**相对角**（头相对身体）—— GeckoLib 内置的
     * {@code query.head_y_rotation} 是**绝对** yaw（{@code MolangQueries.java:286}），
     * 会把整体朝向叠进去，不能用。由上面表达式里的系数也可反推量纲：
     * {@code head_yaw*0.3556} 夹在 ±32 ⇒ {@code head_yaw ∈ ±90} 度。
     * <p>
     * <b>两点必须注意</b>：
     * <ol>
     *   <li>Molang 变量是**全局静态**的（{@code MolangQueries.VARIABLES}），每个实体每帧都要重设
     *       —— 本方法正是为此被调用的（{@code AnimationProcessor#preAnimationSetup} 在
     *       controller tick 之前逐实体调用一次）；</li>
     *   <li>此刻 {@code state.getController()} 是 {@code null}（{@code GeoEntityRenderer.java:268}
     *       才 new 出 state，{@code withController} 发生得更晚），所以只能用**惰性 supplier**。</li>
     * </ol>
     */
    @Override
    public void applyMolangQueries(AnimationState<DihuangLoongEntity> animationState, double animTime) {
        super.applyMolangQueries(animationState, animTime);

        DihuangLoongEntity entity = animationState.getAnimatable();
        float partialTick = animationState.getPartialTick();

        // 与 GeoEntityRenderer 相同的插值口径（它用 Mth.rotLerp(partialTick, yBodyRotO, yBodyRot)），
        // 否则 20Hz 的阶梯感会很明显。
        float bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        float headYaw = Mth.rotLerp(partialTick, entity.yHeadRotO, entity.yHeadRot);
        float headPitch = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());

        MathParser.setVariable("query.head_yaw",
                () -> (double) (Mth.wrapDegrees(headYaw - bodyYaw) * HEAD_YAW_SIGN));
        MathParser.setVariable("query.head_pitch", () -> (double) headPitch);
    }
}
