package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.client.render.entity.dragon.DragonItemRenderLayer;
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

/**
 * 修复龙手持发光物品时龙身体变隐形的问题。
 *
 * <p>原理：为物品创建独立的 Outline 缓冲区，避免物品的发光数据污染龙的 {@link OutlineBufferSource}。
 * 通过 {@link ModifyArgs} 拦截渲染调用替换缓冲区，
 * 通过 {@link Inject} 在渲染完成后刷出独立缓冲区。</p>
 *
 * <p>详见 {@code doc/ds-bug-fix-mixins.md}</p>
 */
@Mixin(value = DragonItemRenderLayer.class, remap = false)
public abstract class DragonItemRenderLayerMixin {

    /** 临时隔离的 Outline 渲染缓冲区，每帧重建 */
    @Unique
    private MultiBufferSource.BufferSource ds_bug_fix$itemOutlineBuf;

    /**
     * 在 GeckoLib 渲染物品前拦截，替换 args(4) 为隔离后的 MultiBufferSource。
     * args 索引 4 对应 MultiBufferSource 参数（共 8 个参数）。
     */
    @ModifyArgs(method = "renderStackForBone",
                at = @At(value = "INVOKE",
                         target = "Lsoftware/bernie/geckolib/renderer/layer/BlockAndItemGeoLayer;"
                                + "renderStackForBone("
                                + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                                + "Lsoftware/bernie/geckolib/cache/object/GeoBone;"
                                + "Lnet/minecraft/world/item/ItemStack;"
                                + "Lsoftware/bernie/geckolib/animatable/GeoAnimatable;"
                                + "Lnet/minecraft/client/renderer/MultiBufferSource;"
                                + "FII)V"),
                remap = false)
    private void ds_bug_fix$isolateOutlineBuffer(Args args) {
        if (!Config.FIX_GLOWING_OUTLINE.get()) {
            return;
        }

        MultiBufferSource bufferSource = args.get(4);
        if (!(bufferSource instanceof OutlineBufferSource outline)) {
            return; // 无发光效果，无需处理
        }

        MultiBufferSource.BufferSource normalBuf =
                ((OutlineBufferSourceAccessor) outline).ds_bug_fix$getNormalBufferSource();
        int color = net.minecraft.util.FastColor.ARGB32.color(
                ((OutlineBufferSourceAccessor) outline).ds_bug_fix$getTeamA(),
                ((OutlineBufferSourceAccessor) outline).ds_bug_fix$getTeamR(),
                ((OutlineBufferSourceAccessor) outline).ds_bug_fix$getTeamG(),
                ((OutlineBufferSourceAccessor) outline).ds_bug_fix$getTeamB());

        ds_bug_fix$itemOutlineBuf = MultiBufferSource.immediate(new ByteBufferBuilder(1536));

        args.set(4, new MultiBufferSource() {
            @Override
            public VertexConsumer getBuffer(RenderType rt) {
                // 发光类型 → 独立缓冲区，避免污染龙的主 OutlineBufferSource
                if (rt.isOutline()) {
                    return ds_bug_fix$itemOutlineBuf.getBuffer(rt);
                }

                VertexConsumer normal = normalBuf.getBuffer(rt);
                Optional<RenderType> outlineVariant = rt.outline();

                // 有发光变体的普通类型 → 双写（正常缓冲区 + 独立发光缓冲区）
                if (outlineVariant.isPresent()) {
                    VertexConsumer outConsumer =
                            ds_bug_fix$itemOutlineBuf.getBuffer(outlineVariant.get());
                    return VertexMultiConsumer.create(
                            colorReplacing(outConsumer, color), normal);
                }

                // 纯普通类型 → 直接写入正常缓冲区
                return normal;
            }
        });
    }

    /** 物品渲染完成后刷出独立 Outline 缓冲区 */
    @Inject(method = "renderStackForBone", at = @At("TAIL"), remap = false)
    private void ds_bug_fix$flushIsolatedOutline(CallbackInfo ci) {
        if (ds_bug_fix$itemOutlineBuf != null) {
            ds_bug_fix$itemOutlineBuf.endBatch();
            ds_bug_fix$itemOutlineBuf = null;
        }
    }

    /**
     * 返回一个强制覆盖顶点颜色的 VertexConsumer 包装器。
     * 强制所有顶点使用指定的队伍颜色，忽略外部 setColor 调用。
     * setUv1、setUv2、setNormal 被忽略的数据会在 VertexMultiConsumer 的另一路正常处理。
     */
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
