package com.zonlong.beloong.client.sky;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL14;

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

    // fabricskyboxes 对应 9 层配置使用的离散 fade 区间
    private static final int NIGHT_FADE_IN_START = 13333;
    private static final int NIGHT_FADE_IN_END = 13666;
    private static final int NIGHT_FADE_OUT_START = 22333;
    private static final int NIGHT_FADE_OUT_END = 22666;

    private static final int DAY_FADE_IN_START = 23666;
    private static final int DAY_FADE_IN_END = 333;
    private static final int DAY_FADE_OUT_START = 11666;
    private static final int DAY_FADE_OUT_END = 12333;

    private static final int SUNSET_FADE_IN_START = 11666;
    private static final int SUNSET_FADE_IN_END = 12333;
    private static final int SUNSET_FADE_OUT_START = 13333;
    private static final int SUNSET_FADE_OUT_END = 13666;

    private static final int SUNRISE_FADE_IN_START = 22333;
    private static final int SUNRISE_FADE_IN_END = 22666;
    private static final int SUNRISE_FADE_OUT_START = 23666;
    private static final int SUNRISE_FADE_OUT_END = 333;

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
            new SkyLayerConfig(NIGHT, SkyBlendMode.SCREEN, SkyAlphaSource.NIGHT, SkyRotation.DAY_ROTATION),
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
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);

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

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
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
            case NIGHT -> fadeAlpha(
                    dayTime,
                    NIGHT_FADE_IN_START, NIGHT_FADE_IN_END,
                    NIGHT_FADE_OUT_START, NIGHT_FADE_OUT_END
            );
            case DAY -> fadeAlpha(
                    dayTime,
                    DAY_FADE_IN_START, DAY_FADE_IN_END,
                    DAY_FADE_OUT_START, DAY_FADE_OUT_END
            );
            case SUNSET -> fadeAlpha(
                    dayTime,
                    SUNSET_FADE_IN_START, SUNSET_FADE_IN_END,
                    SUNSET_FADE_OUT_START, SUNSET_FADE_OUT_END
            );
            case SUNRISE -> fadeAlpha(
                    dayTime,
                    SUNRISE_FADE_IN_START, SUNRISE_FADE_IN_END,
                    SUNRISE_FADE_OUT_START, SUNRISE_FADE_OUT_END
            );
        };
    }

    /**
     * 参考 NeoForgeSkyboxes Utils.calculateFadeAlphaValue 的循环区间算法。
     */
    private static float fadeAlpha(long dayTime,
                                   int startFadeIn, int endFadeIn,
                                   int startFadeOut, int endFadeOut) {
        int time = (int) Math.floorMod(dayTime, 24000L);

        if (inInterval(time, endFadeIn, startFadeOut)) {
            return 1.0F;
        }
        if (inInterval(time, startFadeIn, endFadeIn)) {
            int duration = cyclicDistance(startFadeIn, endFadeIn);
            return duration == 0 ? 1.0F : (float) cyclicDistance(startFadeIn, time) / duration;
        }
        if (inInterval(time, startFadeOut, endFadeOut)) {
            int duration = cyclicDistance(startFadeOut, endFadeOut);
            return duration == 0 ? 0.0F : 1.0F - (float) cyclicDistance(startFadeOut, time) / duration;
        }
        return 0.0F;
    }

    private static boolean inInterval(int time, int start, int end) {
        return start <= end
                ? time >= start && time <= end
                : time >= start || time <= end;
    }

    private static int cyclicDistance(int start, int end) {
        return (end - start + 24000) % 24000;
    }

    private static ResourceLocation skyTexture(String name) {
        return ResourceLocation.fromNamespaceAndPath(
                BeLoongCore.MODID,
                "textures/skybox/" + name + ".png"
        );
    }
}
