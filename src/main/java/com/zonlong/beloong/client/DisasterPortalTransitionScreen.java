package com.zonlong.beloong.client;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.BooleanSupplier;

/**
 * 天灾维度的「加载地形中」界面。
 * <p>
 * 原版 {@link ReceivingLevelScreen#renderBackground} 按 {@code Reason} 分支：下界门用下界门的粒子贴图铺满、
 * 末地门用 {@code RenderType.endPortal()} 铺满，**其余维度一律走"模糊后处理 + 半透明菜单底图"**。
 * 本类把天灾维度的那一支替换为<b>天灾传送门贴图</b>（与
 * {@link DisasterPortalRenderer} 使用同一张 {@code beloong:textures/disaster_portal.png}），
 * 做法与原版 {@code NETHER_PORTAL} 分支同构：铺满整屏 + z = -90 + 一层轻压暗；
 * 区别是本贴图是「四方连续」的，因此按 {@link #TEXTURE_WIDTH 原始尺寸} 用 GL_REPEAT
 * <b>平铺拼接</b>（1 贴图像素 = 1 GUI 像素），而不是把整张贴图拉伸到全屏（那样会明显发糊）。
 * <p>
 * 由 {@code RegisterDimensionTransitionScreenEvent}（见 {@link BeLoongCoreClient}）
 * 注册为"进入/离开 {@code beloong:disaster} 时使用"，因此不依赖原版对维度 ID 的硬编码判断。
 * <p>
 * <b>取用优先级</b>（NeoForge {@code DimensionTransitionScreenManager}）：
 * {@code conditional > 进入(to) > 离开(from) > 默认}。本类注册的是 to/from 两档，
 * 因此"回程背景"可能被第三方对目的维度（主世界）的进入注册顶掉。
 * <p>
 * <b>不会生效的路径</b>：死亡重生时 {@code ClientPacketListener} 给
 * {@code DimensionTransitionScreenManager#getScreen} 传 {@code (null, null)}，按原版设计走通用背景。
 */
@OnlyIn(Dist.CLIENT)
public class DisasterPortalTransitionScreen extends ReceivingLevelScreen {

    /** 与 BlockEntity 渲染器共用同一张传送门贴图。 */
    private static final ResourceLocation PORTAL_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "textures/disaster_portal.png");

    /**
     * 贴图原始尺寸（像素）。
     * <p>
     * <b>必须与 PNG 实际尺寸一致</b>：{@code GuiGraphics#blit} 用它做 UV 基准
     * （{@code maxU = (uOffset + width) / textureWidth}），传原始尺寸就会让 uv 超过 1，
     * 由 GL_REPEAT 把这张「四方连续」贴图按 <b>1 贴图像素 = 1 GUI 像素</b>平铺。
     * 若改传屏幕尺寸，整张贴图会被拉伸到全屏（画面发糊）。
     */
    private static final int TEXTURE_WIDTH = 256;
    private static final int TEXTURE_HEIGHT = 256;

    /** 贴图之上的轻压暗层（ARGB），保证居中显示的「加载地形中」白字可读。 */
    private static final int DARKEN_COLOR = 0x40000000;

    /** 父类字段是 private，这里自己留一份用于分支判断。 */
    private final ReceivingLevelScreen.Reason reason;

    public DisasterPortalTransitionScreen(BooleanSupplier levelReceived, ReceivingLevelScreen.Reason reason) {
        super(levelReceived, reason);
        this.reason = reason;
    }

    /**
     * 绘制背景。
     * <p>
     * 只有 {@code Reason.OTHER}（即原版"没有专用背景"的那一支）才用天灾门贴图；
     * 若该界面被下界/末地的过渡复用（例如从下界穿门进入天灾维度），交回父类以保留原版专用背景。
     */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (this.reason != ReceivingLevelScreen.Reason.OTHER) {
            super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
            return;
        }

        // 原尺寸平铺：destination 仍铺满整屏，UV 基准取贴图原始尺寸 ⇒ uv > 1，
        // 由 GL_REPEAT 把这张「四方连续」贴图按 1 贴图像素 = 1 GUI 像素拼接
        // （与原版 Screen#renderMenuBackgroundTexture 的菜单底图做法一致）。z = -90 与原版 NETHER_PORTAL 分支一致。
        guiGraphics.blit(PORTAL_TEXTURE, 0, 0, -90, 0.0F, 0.0F,
                this.width, this.height, TEXTURE_WIDTH, TEXTURE_HEIGHT);
        // 轻压暗：在贴图之上、文字之下
        guiGraphics.fill(0, 0, this.width, this.height, DARKEN_COLOR);
    }
}
