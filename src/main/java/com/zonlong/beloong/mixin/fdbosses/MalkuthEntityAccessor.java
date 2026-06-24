package com.zonlong.beloong.mixin.fdbosses;

import com.finderfeed.fdbosses.content.entities.malkuth_boss.MalkuthEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = MalkuthEntity.class, remap = false)
public interface MalkuthEntityAccessor {

    @Accessor("hits")
    void setHits(int hits);
}
