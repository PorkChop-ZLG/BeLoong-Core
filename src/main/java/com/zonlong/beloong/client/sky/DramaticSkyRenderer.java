package com.zonlong.beloong.client.sky;

import com.mojang.blaze3d.systems.RenderSystem;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Dramatic Skys 昼夜天空多层渲染器。
 *
 * <p>第一阶段实现 stars / mask / day / night 四层，
 * 暂不处理太阳、月亮、阳光 flare 和层旋转。</p>
 */
public final class DramaticSkyRenderer {

    private static final ResourceLocation STARS = skyTexture("stars");
    private static final ResourceLocation MASK = skyTexture("mask");
    private static final ResourceLocation DAY = skyTexture("day");
    private static final ResourceLocation NIGHT = skyTexture("night");

    private DramaticSkyRenderer() {
    }

    public static void render(ClientLevel level,
                              Matrix4f modelViewMatrix,
                              Matrix4f projectionMatrix) {
        long dayTime = Math.floorMod(level.getDayTime(), 24000L);
        float dayFade = dayFade(dayTime);
        float nightFade = nightFade(dayTime);

        List<SkyLayer> layers = List.of(
                new SkyLayer(STARS, SkyBlendMode.ALPHA, nightFade),
                new SkyLayer(MASK, SkyBlendMode.ALPHA, nightFade),
                new SkyLayer(DAY, SkyBlendMode.SCREEN, dayFade),
                new SkyLayer(NIGHT, SkyBlendMode.ADD, nightFade)
        );

        RenderSystem.enableBlend();
        for (SkyLayer layer : layers) {
            if (layer.alpha() <= 0.0F) {
                continue;
            }
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, layer.alpha());
            layer.blend().apply();
            CubeAtlasSkyRenderer.render(modelViewMatrix, projectionMatrix, layer.texture());
        }

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    private static float dayFade(long dayTime) {
        if (dayTime >= 500 && dayTime < 1500) {
            return (dayTime - 500) / 1000.0F;
        }
        if (dayTime >= 1500 && dayTime < 10500) {
            return 1.0F;
        }
        if (dayTime >= 10500 && dayTime < 11500) {
            return (11500 - dayTime) / 1000.0F;
        }
        return 0.0F;
    }

    private static float nightFade(long dayTime) {
        if (dayTime >= 12500 && dayTime < 13500) {
            return (dayTime - 12500) / 1000.0F;
        }
        if (dayTime >= 13500 && dayTime < 22500) {
            return 1.0F;
        }
        if (dayTime >= 22500 && dayTime < 23500) {
            return (23500 - dayTime) / 1000.0F;
        }
        return 0.0F;
    }

    private static ResourceLocation skyTexture(String name) {
        return ResourceLocation.fromNamespaceAndPath(
                BeLoongCore.MODID,
                "textures/skybox/" + name + ".png"
        );
    }
}
