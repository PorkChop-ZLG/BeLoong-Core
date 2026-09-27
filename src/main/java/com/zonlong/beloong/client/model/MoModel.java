package com.zonlong.beloong.client.model;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.entity.MoEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 末（Mo）模型：三个资源路径。
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
 * <h2>为什么没有 {@code applyMolangQueries}</h2>
 * 地黄龙的资产里写着 {@code query.head_yaw} 之类的表达式，所以它必须在这里注册；
 * <b>本模型的资产用的是 YSM 的私有命名空间</b>（{@code ysm.head_yaw} 等，见分析报告 §9.2），
 * 而用户裁定"先不管 molang"。未注册的变量 GeckoLib 会自动建一个值为 0 的实例
 * （{@code MolangQueries.java:149-150}），所以 {@code walk}/{@code run} 里的
 * {@code ysm.head_yaw/8} **不报错、只是恒为 0** ⇒ 走路时头不随视角转。
 * 将来若要接上，在这里 {@code MathParser.setVariable("ysm.head_yaw", ...)} 即可
 * （注意 {@code ysm.head_yaw} 是**绝对**视角 yaw，与地黄龙那个"头相对身体"的口径不同，
 * 符号要实机标定）。
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
}
