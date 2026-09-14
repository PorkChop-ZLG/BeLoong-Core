package com.zonlong.beloong.client.sky;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL14;

/**
 * Dramatic Skys 图层使用的混合模式。
 *
 * <p>SCREEN 模式必须把 alpha 缩放到 RGB，否则日出日落层不会随 alpha 淡出。
 * 所有模式都显式设置加法混合方程，避免其他模组残留的 blend equation 影响天空。</p>
 */
public enum SkyBlendMode {

    ALPHA {
        @Override
        public void apply(float alpha) {
            RenderSystem.blendFuncSeparate(
                    GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
            );
            RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        }
    },

    ADD {
        @Override
        public void apply(float alpha) {
            RenderSystem.blendFuncSeparate(
                    GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE,
                    GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ONE
            );
            RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        }
    },

    SCREEN {
        @Override
        public void apply(float alpha) {
            // screen: result = src + dst - src * dst
            RenderSystem.blendFuncSeparate(
                    GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
                    GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
            );
            RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
            // 必须将 alpha 缩放到 RGB，否则 screen 层不会随 alpha 变淡
            RenderSystem.setShaderColor(alpha, alpha, alpha, 1.0F);
        }
    };

    public abstract void apply(float alpha);
}
