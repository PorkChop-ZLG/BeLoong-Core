package com.zonlong.beloong.client.sky;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import org.joml.Matrix4f;

/**
 * 龙宫维度自定义天空特效。
 *
 * <p>使用 Dramatic Skys 的昼夜天空盒：stars / mask / day / night 四层。
 * 龙宫保留原版天气与完整天空光照。</p>
 */
public class LoongPalaceSkyEffects extends DimensionSpecialEffects.OverworldEffects {

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
        return true;
    }
}
