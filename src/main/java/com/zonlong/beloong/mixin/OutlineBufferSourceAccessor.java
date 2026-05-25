package com.zonlong.beloong.mixin;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问 {@link OutlineBufferSource} 的私有字段。
 * 供 {@link DragonItemRenderLayerMixin} 使用。
 *
 * <p>方法名使用 {@code ds_bug_fix$} 前缀，避免与其他模组的 accessor 冲突。
 */
@Mixin(OutlineBufferSource.class)
public interface OutlineBufferSourceAccessor {

    /** 正常的（非发光）渲染缓冲区 */
    @Accessor("bufferSource")
    MultiBufferSource.BufferSource ds_bug_fix$getNormalBufferSource();

    /** 发光轮廓的渲染缓冲区（当前修复中未直接使用，保留备用） */
    @Accessor("outlineBufferSource")
    MultiBufferSource.BufferSource ds_bug_fix$getOutlineBufferSource();

    /** 发光颜色 - 红色分量 */
    @Accessor("teamR")
    int ds_bug_fix$getTeamR();

    /** 发光颜色 - 绿色分量 */
    @Accessor("teamG")
    int ds_bug_fix$getTeamG();

    /** 发光颜色 - 蓝色分量 */
    @Accessor("teamB")
    int ds_bug_fix$getTeamB();

    /** 发光颜色 - 透明度分量 */
    @Accessor("teamA")
    int ds_bug_fix$getTeamA();
}
