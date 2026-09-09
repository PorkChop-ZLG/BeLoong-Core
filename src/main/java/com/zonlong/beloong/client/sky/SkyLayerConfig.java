package com.zonlong.beloong.client.sky;

import net.minecraft.resources.ResourceLocation;

/**
 * 单层天空渲染配置。
 *
 * @param texture     3×2 六面图集
 * @param blend       混合模式
 * @param alphaSource 透明度来源
 * @param rotation    旋转配置
 */
public record SkyLayerConfig(ResourceLocation texture,
                             SkyBlendMode blend,
                             SkyAlphaSource alphaSource,
                             SkyRotation rotation) {
}
