package com.zonlong.beloong.mixin;

import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

/**
 * 只读暴露 {@link StructurePlacement} 的五个保护字段。
 * <p>
 * 天灾维度结构集覆写（{@link com.zonlong.beloong.worldgen.DisasterStructureSetLookup}）
 * 需要读取原 placement 的全部参数后用公开构造器重建副本——其中
 * {@code locateOffset}/{@code frequencyReductionMethod}/{@code frequency}/
 * {@code salt}/{@code exclusionZone} 是 protected 的，无法从模组包访问。
 * 访问器只读，零状态污染。
 */
@Mixin(StructurePlacement.class)
public interface StructurePlacementAccessor {

    @Accessor("locateOffset")
    Vec3i beloong$locateOffset();

    @Accessor("frequencyReductionMethod")
    StructurePlacement.FrequencyReductionMethod beloong$frequencyReductionMethod();

    @Accessor("frequency")
    float beloong$frequency();

    @Accessor("salt")
    int beloong$salt();

    @Accessor("exclusionZone")
    Optional<StructurePlacement.ExclusionZone> beloong$exclusionZone();
}
