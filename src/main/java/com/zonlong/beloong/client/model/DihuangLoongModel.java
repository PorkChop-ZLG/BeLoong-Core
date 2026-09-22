package com.zonlong.beloong.client.model;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.entity.DihuangLoongEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 地黄龙模型：只负责给出三个资源路径。
 * <p>
 * 三个文件由 {@code D:\Minecraft\Models\Geckolib\dihuang_loong_npc\} **原样移植**，内容未做任何改动：
 * <ul>
 *   <li>{@code geo/dihuang_loong.geo.json} —— 145 骨骼 / 340 立方体，
 *       其 {@code identifier} 是 {@code geometry.unknown}。**无需修正**：GeckoLib 按本类返回的
 *       <b>文件路径</b>取模型，{@code identifier} 只是可空元数据（{@code ModelProperties.java:35}）。</li>
 *   <li>{@code animations/dihuang_loong.animation.json} —— 约 100 个动画，整份放上来备用。</li>
 *   <li>{@code textures/entity/dihuang_loong.png} —— 256×256 贴图。</li>
 * </ul>
 * <b>已知的动画骨骼缺口</b>：该动画文件源自"龙之生存"的另一个龙种模型，其中 {@code jump} 等
 * 动作引用了本模型没有的骨骼（如 {@code Mustache*}、{@code Whisker*}）。GeckoLib 对缺失骨骼是
 * **优雅忽略**（{@code GeoModel#shouldCrashOnMissingBone()} 默认 false），因此只是观感问题、不会崩。
 * v1 只播 {@code idle}（其引用骨骼 100% 存在）。
 */
public class DihuangLoongModel extends GeoModel<DihuangLoongEntity> {

    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "geo/dihuang_loong.geo.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "textures/entity/dihuang_loong.png");
    private static final ResourceLocation ANIMATION =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "animations/dihuang_loong.animation.json");

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
}
