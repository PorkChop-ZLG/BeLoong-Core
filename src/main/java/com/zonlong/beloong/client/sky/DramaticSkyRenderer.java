package com.zonlong.beloong.client.sky;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Dramatic Skys 完整天空多层渲染器。
 *
 * <p>包含 stars / mask_moon / mask / day / night / sunset / sunrise / flare 九层，
 * 支持完整昼夜 fade、图层旋转和逐 tick 平滑过渡。</p>
 */
public final class DramaticSkyRenderer {

    private static final boolean ENABLE_ROTATION = true;

    private static final int NORMAL_TRANSITION_TICKS = 20;
    private static final int UNEXPECTED_TRANSITION_TICKS = 200;

    private static final ResourceLocation STARS = skyTexture("stars");
    private static final ResourceLocation MASK_MOON = skyTexture("mask_moon");
    private static final ResourceLocation MASK = skyTexture("mask");
    private static final ResourceLocation DAY = skyTexture("day");
    private static final ResourceLocation NIGHT = skyTexture("night");
    private static final ResourceLocation SUN = skyTexture("sun");
    private static final ResourceLocation SUNFLARE = skyTexture("sunflare");

    private static final List<SkyLayerConfig> LAYERS = List.of(
            new SkyLayerConfig(STARS, SkyBlendMode.ALPHA, SkyAlphaSource.NIGHT, SkyRotation.STAR_ROTATION),
            new SkyLayerConfig(MASK_MOON, SkyBlendMode.ALPHA, SkyAlphaSource.NIGHT, SkyRotation.STAR_ROTATION),
            new SkyLayerConfig(MASK, SkyBlendMode.ALPHA, SkyAlphaSource.NIGHT, SkyRotation.DAY_ROTATION),
            new SkyLayerConfig(DAY, SkyBlendMode.SCREEN, SkyAlphaSource.DAY, SkyRotation.DAY_ROTATION),
            new SkyLayerConfig(NIGHT, SkyBlendMode.ADD, SkyAlphaSource.NIGHT, SkyRotation.DAY_ROTATION),
            new SkyLayerConfig(SUN, SkyBlendMode.SCREEN, SkyAlphaSource.SUNSET, SkyRotation.SUN_ROTATION),
            new SkyLayerConfig(SUN, SkyBlendMode.SCREEN, SkyAlphaSource.SUNRISE, SkyRotation.SUN_ROTATION),
            new SkyLayerConfig(SUNFLARE, SkyBlendMode.SCREEN, SkyAlphaSource.SUNSET, SkyRotation.FLARE_ROTATION),
            new SkyLayerConfig(SUNFLARE, SkyBlendMode.SCREEN, SkyAlphaSource.SUNRISE, SkyRotation.FLARE_ROTATION)
    );

    /** 每一层当前实际显示的平滑 alpha。 */
    private static final float[] DISPLAY_ALPHAS = new float[LAYERS.size()];
    private static boolean initialized;
    private static long lastDayTime = -1;
    private static int tickLogCounter;
    private static boolean unexpectedTransitionActive;

    private DramaticSkyRenderer() {
    }

    /**
     * 每个客户端 tick 调用一次，将各层 alpha 平滑趋近目标值。
     */
    public static void tick(ClientLevel level) {
        long dayTime = Math.floorMod(level.getDayTime(), 24000L);
        boolean timeJump = initialized
                && lastDayTime >= 0
                && Math.abs(dayTime - lastDayTime) > 1;
        if (timeJump) {
            unexpectedTransitionActive = true;
        }
        boolean useUnexpected = timeJump || unexpectedTransitionActive;
        int duration = useUnexpected
                ? UNEXPECTED_TRANSITION_TICKS
                : NORMAL_TRANSITION_TICKS;

        boolean allReachedTarget = true;
        for (int i = 0; i < LAYERS.size(); i++) {
            float target = alphaFor(LAYERS.get(i).alphaSource(), dayTime);
            if (!initialized) {
                DISPLAY_ALPHAS[i] = target;
            } else {
                DISPLAY_ALPHAS[i] = moveTowards(DISPLAY_ALPHAS[i], target, duration);
                if (Math.abs(DISPLAY_ALPHAS[i] - target) > 0.001F) {
                    allReachedTarget = false;
                }
            }
        }
        if (unexpectedTransitionActive && allReachedTarget) {
            unexpectedTransitionActive = false;
        }
        initialized = true;
        lastDayTime = dayTime;

        if (timeJump || tickLogCounter % 20 == 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < LAYERS.size(); i++) {
                float target = alphaFor(LAYERS.get(i).alphaSource(), dayTime);
                sb.append(i)
                        .append("=")
                        .append(String.format("%.3f/%.3f", DISPLAY_ALPHAS[i], target))
                        .append(' ');
            }
            BeLoongCore.LOGGER.info(
                    "[SkyDebug] dayTime={} timeJump={} duration={} alphas[display/target]={}",
                    dayTime, timeJump, duration, sb.toString().trim());
        }
        tickLogCounter++;
    }

    public static void render(ClientLevel level,
                              Matrix4f modelViewMatrix,
                              Matrix4f projectionMatrix) {
        if (!initialized) {
            long dayTime = Math.floorMod(level.getDayTime(), 24000L);
            for (int i = 0; i < LAYERS.size(); i++) {
                DISPLAY_ALPHAS[i] = alphaFor(LAYERS.get(i).alphaSource(), dayTime);
            }
            initialized = true;
            BeLoongCore.LOGGER.info("[SkyDebug] render initialized at dayTime={}", dayTime);
        }

        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(modelViewMatrix);

        RenderSystem.enableBlend();
        for (int i = 0; i < LAYERS.size(); i++) {
            SkyLayerConfig layer = LAYERS.get(i);
            float alpha = DISPLAY_ALPHAS[i];
            if (alpha <= 0.0F) {
                continue;
            }
            layer.blend().apply(alpha);

            poseStack.pushPose();
            if (ENABLE_ROTATION) {
                layer.rotation().apply(poseStack, level);
            }
            CubeAtlasSkyRenderer.render(poseStack, projectionMatrix, layer.texture());
            poseStack.popPose();
        }

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    private static float moveTowards(float current, float target, int ticks) {
        if (Float.compare(current, target) == 0) {
            return target;
        }
        float step = 1.0F / ticks;
        if (current < target) {
            return Math.min(target, current + step);
        } else {
            return Math.max(target, current - step);
        }
    }

    private static float alphaFor(SkyAlphaSource source, long dayTime) {
        return switch (source) {
            case ALWAYS -> 1.0F;
            case NIGHT -> nightFade(dayTime);
            case DAY -> dayFade(dayTime);
            case SUNSET -> sunsetFade(dayTime);
            case SUNRISE -> sunriseFade(dayTime);
        };
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

    private static float sunsetFade(long dayTime) {
        if (dayTime > 10500 && dayTime < 11500) {
            return (dayTime - 10500) / 1000.0F;
        }
        if (dayTime >= 11500 && dayTime < 12500) {
            return 1.0F;
        }
        if (dayTime >= 12500 && dayTime < 13500) {
            return (13500 - dayTime) / 1000.0F;
        }
        return 0.0F;
    }

    private static float sunriseFade(long dayTime) {
        if (dayTime > 500 && dayTime < 1500) {
            return (1500 - dayTime) / 1000.0F;
        }
        if (dayTime >= 1500 && dayTime < 22500) {
            return 0.0F;
        }
        if (dayTime >= 22500 && dayTime < 23500) {
            return (dayTime - 22500) / 1000.0F;
        }
        return 1.0F;
    }

    private static ResourceLocation skyTexture(String name) {
        return ResourceLocation.fromNamespaceAndPath(
                BeLoongCore.MODID,
                "textures/skybox/" + name + ".png"
        );
    }
}
