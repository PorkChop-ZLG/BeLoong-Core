package com.zonlong.beloong.mixin;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(OutlineBufferSource.class)
public interface OutlineBufferSourceAccessor {

    @Accessor("bufferSource")
    MultiBufferSource.BufferSource ds_bug_fix$getNormalBufferSource();

    @Accessor("outlineBufferSource")
    MultiBufferSource.BufferSource ds_bug_fix$getOutlineBufferSource();

    @Accessor("teamR")
    int ds_bug_fix$getTeamR();

    @Accessor("teamG")
    int ds_bug_fix$getTeamG();

    @Accessor("teamB")
    int ds_bug_fix$getTeamB();

    @Accessor("teamA")
    int ds_bug_fix$getTeamA();
}
