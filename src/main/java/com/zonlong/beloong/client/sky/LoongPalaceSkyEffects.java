package com.zonlong.beloong.client.sky;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;

/**
 * 龙宫维度自定义天空特效。
 *
 * <p>使用 Dramatic Skys 九层天空，并绘制太阳/月亮。
 * 客户端隐藏云、雨雪、雨声，使龙宫始终呈现晴天外观。
 * 水中/岩浆/细雪/失明/黑暗时参考 NeoForgeSkyboxes 不绘制自定义天空。</p>
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
        if (shouldRenderSky(camera)) {
            DramaticSkyRenderer.render(level, modelViewMatrix, projectionMatrix);
            if (ENABLE_DECORATIONS) {
                SkyDecorationsRenderer.render(level, modelViewMatrix, projectionMatrix);
            }
        }
        return true;
    }

    private static boolean shouldRenderSky(Camera camera) {
        FogType fogType = camera.getFluidInCamera();
        if (fogType == FogType.WATER
                || fogType == FogType.LAVA
                || fogType == FogType.POWDER_SNOW) {
            return false;
        }
        if (camera.getEntity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS)
                    || living.hasEffect(MobEffects.DARKNESS))) {
            return false;
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
