package com.zonlong.beloong.mixin;

import com.zonlong.beloong.worldgen.DisasterBiomeSourceFactory;
import com.zonlong.beloong.worldgen.DisasterStructureSetLookup;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 天灾维度结构集过滤。
 * <p>
 * {@code ChunkMap} 在构造时（每个维度一次）调用
 * {@code generator.createState(structureSetLookup, this.randomState, i)}。
 * 此处把注册表查询包装成 {@link DisasterStructureSetLookup}——
 * 仅当服务端世界是天灾维度且 {@code disaster_biomes.enabled} 时生效。
 * 其它维度走原路。
 * <p>
 * 原版 {@code createForNormal} 按结构集与群系的交集筛掉不可放置的结构集；
 * 包一层后白名单之外的结构集（要塞/矿井/村庄等残留在
 * {@code #is_overworld}/{@code #is_ocean} 标签上的）在天灾维度彻底消失，
 * 且支持按配置覆写 spacing/separation/frequency（生成概率）。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    /** 捕获构造参数中的世界实例，用于维度判断。 */
    @Final
    @Shadow
    private ServerLevel level;

    @Redirect(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;createState(Lnet/minecraft/core/HolderLookup;Lnet/minecraft/world/level/levelgen/RandomState;J)Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;"
        )
    )
    private ChunkGeneratorStructureState beloong$wrapStructureState(
            ChunkGenerator generator,
            HolderLookup<StructureSet> structureSetLookup,
            RandomState randomState,
            long seed) {
        if (DisasterBiomeSourceFactory.DISASTER_DIMENSION.equals(level.dimension().location())) {
            structureSetLookup = DisasterStructureSetLookup.wrap(structureSetLookup);
        }
        return generator.createState(structureSetLookup, randomState, seed);
    }
}
