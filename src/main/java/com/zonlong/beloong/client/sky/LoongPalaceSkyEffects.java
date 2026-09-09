package com.zonlong.beloong.client.sky;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;

/**
 * 龙宫维度自定义天空特效。
 *
 * <p>使用 Dramatic Skys 九层天空，并绘制太阳/月亮。
 * 客户端隐藏云、雨雪、雨声，使龙宫始终呈现晴天外观。</p>
 */
public class LoongPalaceSkyEffects extends DimensionSpecialEffects.OverworldEffects {

    private static final boolean ENABLE_DECORATIONS = true;

    @Override
    public boolean renderSky(ClientLevel level,
                             int ticks,
                             float partialTick,
                             Matrix4f modelViewMatrix,
                             Camera camera,
                             Matrix4f projectionMatrix,
                             boolean isFoggy,
                             Runnable setupFog) {
        DramaticSkyRenderer.render(level, modelViewMatrix, projectionMatrix);
        if (ENABLE_DECORATIONS) {
            SkyDecorationsRenderer.render(level, modelViewMatrix, projectionMatrix, partialTick);
        }
        return true;
    }

    @Override
    public boolean renderClouds(ClientLevel level,
                                int ticks,
                                float partialTick,
                                PoseStack poseStack,
                                double camX,
                                double camY,
                                double camZ,
                                Matrix4f modelViewMatrix,
                                Matrix4f projectionMatrix) {
        return true;
    }

    @Override
    public boolean renderSnowAndRain(ClientLevel level,
                                     int ticks,
                                     float partialTick,
                                     LightTexture lightTexture,
                                     double camX,
                                     double camY,
                                     double camZ) {
        return true;
    }

    @Override
    public boolean tickRain(ClientLevel level, int ticks, Camera camera) {
        return true;
    }
}
