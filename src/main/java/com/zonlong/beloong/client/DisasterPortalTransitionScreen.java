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
 * 做法与原版 {@code NETHER_PORTAL} 分支同构：整屏拉伸 + z = -90 + 一层轻压暗。
 * <p>
 * 由 {@code RegisterDimensionTransitionScreenEvent}（见 {@link BeLoongCoreClient}）
 * 注册为"进入/离开 {@code beloong:disaster} 时使用"，因此不依赖原版对维度 ID 的硬编码判断。
 */
@OnlyIn(Dist.CLIENT)
public class DisasterPortalTransitionScreen extends ReceivingLevelScreen {

    /** 与 BlockEntity 渲染器共用同一张传送门贴图。 */
    private static final ResourceLocation PORTAL_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "textures/disaster_portal.png");

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

        // 整屏拉伸：以屏幕尺寸作为 UV 基准（uv 0..1 正好覆盖整张贴图），z = -90 与原版 NETHER_PORTAL 分支一致
        guiGraphics.blit(PORTAL_TEXTURE, 0, 0, -90, 0.0F, 0.0F,
                this.width, this.height, this.width, this.height);
        // 轻压暗：在贴图之上、文字之下
        guiGraphics.fill(0, 0, this.width, this.height, DARKEN_COLOR);
    }
}
