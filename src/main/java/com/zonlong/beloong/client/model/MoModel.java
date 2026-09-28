package com.zonlong.beloong.client.model;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.entity.MoEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 末（Mo）模型：三个资源路径。
 * <p>
 * 资产由**本地模型暂存目录** {@code docs/models/} 迁移而来（该目录专门存放模型源文件、不入库）：
 * <ul>
 *   <li>{@code geo/mo.geo.json} —— 531 KB，<b>313 骨骼 / 1055 立方体 / 1 根根骨骼
 *       （{@code Root_Molang}，{@code Root} 是它的子骨）</b>，无重名骨骼、无悬空 parent，
 *       {@code texture_width/height} 声明 512×512（与贴图实测一致）。
 *       取自 {@code docs/models/末2/}（2026-09-27 换用，**逐字节拷贝**）。
 *       其 {@code identifier} 是 {@code geometry.unknown}，**无需修正**：
 *       GeckoLib 按本类返回的<b>文件路径</b>取模型，{@code identifier} 只是可空元数据。</li>
 *   <li>{@code textures/entity/mo.png} —— 512×512 贴图，同样取自 {@code 末2/}。</li>
 *   <li>{@code animations/mo.animation.json} —— <b>4.32 MB / 11 个动画</b>，取自 {@code 末/}
 *       并经多轮修整（用户提交 {@code c471461「简化动画」}把 29 条裁到 11 条：只留用得上的
 *       与打算用的）。代码目前播其中 6 条：{@code idle} / {@code walk} / {@code run}（主状态机）、
 *       {@code fly}（飞行态）、{@code sit}（坐下态）、{@code dance}（跳舞态，即 20.5 秒循环的
 *       星辉闪耀），外加 {@code wings_idle}（翅膀常驻层，注册顺序见
 *       {@link MoEntity#registerControllers}）；其余 5 条（{@code sit_1}、{@code descend}、
 *       {@code hold_sword_attack_1}、{@code hold_sword_attack_1_reset}）已备好、暂无代码引用。
 *       这些键名都可被 {@code NpcEntity} 的可覆写方法改写。</li>
 * </ul>
 *
 * <h2>⚠️ 2026-09-27 为什么换了模型：旧几何与动画不匹配</h2>
 * 旧几何是一份"精简导出"：只有 211 骨，翅膀只剩一条<b>左右对称压在同一批骨上</b>的链
 * （每根 {@code Right_Wing_*} 的立方体 x 范围是对称的），且没有脸骨与 Molang 代理骨。
 * 而动画是按完整 rig 写的 ⇒ 旧模型下我们自己的动画有 <b>92 个被引用的骨骼名在 geo 里不存在</b>，
 * 那些通道被 GeckoLib 静默跳过（见下），走 / 跑 / 飞都只有约 62% 的骨骼真正生效。
 * <p>
 * 换用 {@code 末2/} 后<b>只剩 17 个</b>，而且 {@code idle} / {@code walk} / {@code run} /
 * {@code fly} / {@code sit} / {@code descend} / {@code hold_sword_attack_1_reset}
 * 全部 <b>100% 命中</b>。
 * <p>
 * 换模型前已核对过两件最容易出事的事，都通过：
 * <ul>
 *   <li><b>整体几何尺寸没变</b>（旧 53.48 × 85.08 × 126.60 ⇒ 新 53.48 × 85.08 × 126.18）
 *       ⇒ 不存在缩放问题。{@code description} 里的 {@code visible_bounds} 从 16×6 变成 26×11
 *       只是**声明值**，不代表几何变大 —— 别照它去改渲染缩放；</li>
 *   <li><b>关键骨骼枢轴一致</b>，只有 3 根不同：{@code Weapen} 差 3.8（旧 x=−3.8 ⇒ 新 x=0，
 *       新的与 YSM 原版一致）、{@code Right_Wing_Root} 与 {@code Right_Wing_1} 各差 2.0。</li>
 * </ul>
 *
 * <h2>仍然对不上的 17 个骨骼名（分布在 4 条动画上）</h2>
 * <ul>
 *   <li>{@code wings_idle} —— 缺 <b>11</b> 个：{@code Right_Wing_6} / {@code _6_1} / {@code _6_2} /
 *       {@code _7} / {@code _7_1} / {@code _8} / {@code _9}、{@code LeftClaw1/2/3}、{@code RightClaw3}。
 *       <b>这 11 个只有旧模型才有</b> ⇒ 翅膀常驻层有一半通道失效（它是常驻层，所以最容易被看到）。
 *       新模型里可考虑改用的替代骨：{@code Left_Wing_1..5} / {@code Right_Wing_1..5}
 *       （各自还带 {@code _mo_} 子骨）与 {@code LeftClaw_Left} / {@code _Mid_1} / {@code _Right_1} /
 *       {@code _Thumb_1}。</li>
 *   <li>{@code dance}（星辉闪耀）—— 缺 {@code Skirt}（裙摆）与 {@code mouth}。
 *       新模型**根本没有**这两个名字，改不了名，只能接受裙摆不动。</li>
 *   <li>{@code hold_sword_attack_1} —— 缺 {@code Wave_1} 与两个发光波
 *       （{@code ysmGlowWave_1_1} / {@code _1_2}），新模型也没有。</li>
 *   <li>{@code sit_1} —— 缺 {@code Eyes}。新模型的眼睛是一族分开的骨
 *       （{@code EyeBalls} / {@code Eyebrows} / {@code ysmGlowLeftEyeball} …），没有单独叫
 *       {@code Eyes} 的。</li>
 * </ul>
 *
 * <h2>为什么没有 {@code applyMolangQueries}</h2>
 * 地黄龙的资产里写着 {@code query.head_yaw} 之类的表达式，所以它必须在这里注册；
 * <b>本模型的资产用的是 YSM 的私有命名空间</b>（{@code ysm.head_yaw}，见分析报告 §9.2），
 * 而用户裁定"先不管 molang"。未注册的变量 GeckoLib 会自动建一个值为 0 的实例
 * （{@code MolangQueries.java:149-150}），所以 {@code walk}/{@code run} 里的
 * {@code ysm.head_yaw/8} **不报错、只是恒为 0** ⇒ 走路时头不随视角转。
 * 将来若要接上，在这里 {@code MathParser.setVariable("ysm.head_yaw", ...)} 即可
 * （注意 {@code ysm.head_yaw} 是**绝对**视角 yaw，与地黄龙那个"头相对身体"的口径不同，
 * 符号要实机标定）。
 *
 * <h2>刻意不覆写 {@code crashIfBoneMissing()}</h2>
 * 本模型仍有 <b>17 个被动画引用、但 geo 里不存在的骨骼</b>（旧模型是 92 个，
 * 具体清单见上一节），分布在 4 条动画上。GeckoLib 默认
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
}
