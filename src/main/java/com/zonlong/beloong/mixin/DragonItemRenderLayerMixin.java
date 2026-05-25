package com.zonlong.beloong.mixin;

import com.mojang.blaze3d.vertex.*;
import com.zonlong.beloong.Config;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.Optional;

@Mixin(targets = "by.dragonsurvivalteam.dragonsurvival.client.render.entity.dragon.DragonItemRenderLayer", remap = false)
public abstract class DragonItemRenderLayerMixin {

    @Unique
    private MultiBufferSource.BufferSource ds_bug_fix$itemOutlineBuf;

    @ModifyArgs(method = "renderStackForBone", at = @At(value = "INVOKE", target = "Lsoftware/bernie/geckolib/renderer/layer/BlockAndItemGeoLayer;renderStackForBone(Lcom/mojang/blaze3d/vertex/PoseStack;Lsoftware/bernie/geckolib/cache/object/GeoBone;Lnet/minecraft/world/item/ItemStack;Lsoftware/bernie/geckolib/animatable/GeoAnimatable;Lnet/minecraft/client/renderer/MultiBufferSource;FII)V"), remap = false)
    private void ds_bug_fix$isolateOutlineBuffer(Args args) {
        if (!Config.FIX_GLOWING_OUTLINE.get()) {
            return;
        }
        MultiBufferSource bufferSource = args.get(4);
        if (!(bufferSource instanceof OutlineBufferSource outline)) {
            return;
        }

        MultiBufferSource.BufferSource normalBuf = ((OutlineBufferSourceAccessor) outline).ds_bug_fix$getNormalBufferSource();
        int color = net.minecraft.util.FastColor.ARGB32.color(
                ((OutlineBufferSourceAccessor) outline).ds_bug_fix$getTeamA(),
                ((OutlineBufferSourceAccessor) outline).ds_bug_fix$getTeamR(),
                ((OutlineBufferSourceAccessor) outline).ds_bug_fix$getTeamG(),
                ((OutlineBufferSourceAccessor) outline).ds_bug_fix$getTeamB());

        ds_bug_fix$itemOutlineBuf = MultiBufferSource.immediate(new ByteBufferBuilder(1536));

        args.set(4, new MultiBufferSource() {
            @Override
            public VertexConsumer getBuffer(RenderType rt) {
                if (rt.isOutline()) {
                    return ds_bug_fix$itemOutlineBuf.getBuffer(rt);
                }
                VertexConsumer normal = normalBuf.getBuffer(rt);
                Optional<RenderType> outlineVariant = rt.outline();
                if (outlineVariant.isPresent()) {
                    VertexConsumer outConsumer = ds_bug_fix$itemOutlineBuf.getBuffer(outlineVariant.get());
                    return VertexMultiConsumer.create(colorReplacing(outConsumer, color), normal);
                }
                return normal;
            }
        });
    }

    @Inject(method = "renderStackForBone", at = @At("TAIL"), remap = false)
    private void ds_bug_fix$flushIsolatedOutline(CallbackInfo ci) {
        if (ds_bug_fix$itemOutlineBuf != null) {
            ds_bug_fix$itemOutlineBuf.endBatch();
            ds_bug_fix$itemOutlineBuf = null;
        }
    }

    @Unique
    private static VertexConsumer colorReplacing(VertexConsumer delegate, int color) {
        return new VertexConsumer() {
            public VertexConsumer addVertex(float x, float y, float z) {
                delegate.addVertex(x, y, z).setColor(color);
                return this;
            }
            public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
            public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); return this; }
            public VertexConsumer setUv1(int u, int v) { return this; }
            public VertexConsumer setUv2(int u, int v) { return this; }
            public VertexConsumer setNormal(float x, float y, float z) { return this; }
        };
    }
}
