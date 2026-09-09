package com.zonlong.beloong.client.sky;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * 龙宫维度自定义天空特效。
 *
 * <p>龙宫的天气由数据包中的 {@code has_skylight: false} 禁用，
 * 本类只负责替换天空画面，不干预云、雨雪和雨声。</p>
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
        CubeSkyRenderer.render(
                modelViewMatrix,
                projectionMatrix,
                skyTexture("up"),
                skyTexture("down"),
                skyTexture("north"),
                skyTexture("south"),
                skyTexture("east"),
                skyTexture("west")
        );
        return true;
    }

    private static ResourceLocation skyTexture(String name) {
        return ResourceLocation.fromNamespaceAndPath(
                BeLoongCore.MODID,
                "textures/environment/loong_palace_sky/" + name + ".png"
        );
    }
}
