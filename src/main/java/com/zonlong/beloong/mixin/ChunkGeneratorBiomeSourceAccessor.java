package com.zonlong.beloong.mixin;

import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 只读暴露 {@link ChunkGenerator} 的 {@code biomeSource}（1.21.1 中为 protected，无公开访问器）。
 * <p>
 * 天灾维度的过滤器需要读取<b>主世界</b>来源（Lithostitched 合并后的 InjectorBiomeSource）
 * 的参数表——主世界的 {@link net.minecraft.world.level.dimension.LevelStem} 由本模组代码
 * 自行取回，无法像天灾维度那样借道 TerraBlender 的调用参数。
 */
@Mixin(ChunkGenerator.class)
public interface ChunkGeneratorBiomeSourceAccessor {

    @Accessor("biomeSource")
    BiomeSource beloong$biomeSource();
}
