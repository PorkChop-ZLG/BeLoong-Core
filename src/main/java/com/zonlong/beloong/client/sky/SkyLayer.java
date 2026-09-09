package com.zonlong.beloong.client.sky;

import net.minecraft.resources.ResourceLocation;

/**
 * 单层天空渲染配置。
 *
 * @param texture 3×2 六面图集
 * @param blend   混合模式
 * @param alpha   当前帧透明度
 */
public record SkyLayer(ResourceLocation texture, SkyBlendMode blend, float alpha) {
}
