package com.zonlong.beloong;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.zonlong.beloong.client.DisasterPortalRenderer;
import com.zonlong.beloong.registry.ModBlocks;
import com.zonlong.beloong.registry.ModFluids;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import java.io.IOException;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * 化龙核心模组的客户端初始化类。
 * <p>
 * 仅在客户端（物理客户端或单人游戏的内置服务端客户端）加载，
 * 专用服务器不会加载此类 —— 由 {@code dist = Dist.CLIENT} 保证。
 * <p>
 * <b>客户端特有注册：</b>
 * <ul>
 *   <li>配置文件 GUI（NeoForge 模组菜单集成）</li>
 *   <li>{@link DisasterPortalRenderer} — 天灾传送门方块的 BlockEntity 渲染器绑定</li>
 * </ul>
 *
 * @see BeLoongCore 主模组类
 * @see DisasterPortalRenderer 传送门自定义渲染器
 */
@Mod(value = BeLoongCore.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = BeLoongCore.MODID, value = Dist.CLIENT)
public class BeLoongCoreClient {

    private static final int BELOONG_WATER_TINT = 0xFF40E0D0;
    private static final ResourceLocation WATER_STILL =
            ResourceLocation.withDefaultNamespace("block/water_still");
    private static final ResourceLocation WATER_FLOW =
            ResourceLocation.withDefaultNamespace("block/water_flow");
    private static final ResourceLocation WATER_OVERLAY =
            ResourceLocation.withDefaultNamespace("block/water_overlay");
    private static final ResourceLocation UNDERWATER_OVERLAY =
            ResourceLocation.withDefaultNamespace("textures/misc/underwater.png");

    /** 配置 GUI 扩展点注册。允许在 NeoForge 模组菜单中直接编辑配置。 */
    public BeLoongCoreClient(IEventBus modEventBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    /** 天灾传送门自定义着色器实例。由 RegisterShadersEvent 回调设置。 */
    @Nullable
    public static ShaderInstance disasterPortalShader;

    /** FML 客户端设置事件。 */
    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        BeLoongCore.LOGGER.info("HELLO FROM CLIENT SETUP");
        BeLoongCore.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        event.enqueueWork(() -> {
            ItemBlockRenderTypes.setRenderLayer(ModFluids.BELOONG_WATER.get(), RenderType.solid());
            ItemBlockRenderTypes.setRenderLayer(ModFluids.FLOWING_BELOONG_WATER.get(), RenderType.solid());
        });
    }

    @SubscribeEvent
    static void registerClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerFluidType(new IClientFluidTypeExtensions() {
            @Override
            public ResourceLocation getStillTexture() {
                return WATER_STILL;
            }

            @Override
            public ResourceLocation getFlowingTexture() {
                return WATER_FLOW;
            }

            @Override
            public ResourceLocation getOverlayTexture() {
                return WATER_OVERLAY;
            }

            @Override
            public ResourceLocation getRenderOverlayTexture(Minecraft minecraft) {
                return UNDERWATER_OVERLAY;
            }

            @Override
            public int getTintColor() {
                return BELOONG_WATER_TINT;
            }

            @Override
            public int getTintColor(FluidState state, BlockAndTintGetter getter, BlockPos pos) {
                return BELOONG_WATER_TINT;
            }

            @Override
            public Vector3f modifyFogColor(
                    Camera camera,
                    float partialTick,
                    ClientLevel level,
                    int renderDistance,
                    float darkenWorldAmount,
                    Vector3f fluidFogColor) {
                return new Vector3f(
                        64.0F / 255.0F,
                        224.0F / 255.0F,
                        208.0F / 255.0F);
            }
        }, ModFluids.BELOONG_WATER_TYPE.get());
    }

    /**
     * 注册 BlockEntity 渲染器（BER）。
     * <p>
     * 将自定义的 {@link DisasterPortalRenderer} 绑定到
     * {@link ModBlocks#DISASTER_PORTAL_BLOCK_ENTITY}，
     * 实现天灾传送门方块的旋转隧道视觉效果。
     * <p>
     * 该渲染器是原版 {@code TheEndPortalRenderer} 的重新实现，
     * 使用自定义着色器（{@code rendertype_disaster_portal}）和自定义贴图，
     * 通过 {@link #onRegisterShaders(RegisterShadersEvent)} 注册。
     * 由于原版渲染器内部将 BlockEntity 硬转型为 {@code TheEndPortalBlockEntity}，
     * 无法直接复用，因此需要自定义实现。
     */
    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                ModBlocks.DISASTER_PORTAL_BLOCK_ENTITY.get(),
                DisasterPortalRenderer::new);
    }

    @SubscribeEvent
    static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "rendertype_disaster_portal"),
                            DefaultVertexFormat.POSITION),
                    shader -> disasterPortalShader = shader);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load disaster portal shader", e);
        }
    }

}
