package com.zonlong.beloong.mixin;

import com.github.L_Ender.cataclysm.structures.CataclysmStructure;
import com.github.L_Ender.cataclysm.structures.Cursed_Pyramid_Structure;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zonlong.beloong.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.Structure.GenerationContext;
import net.minecraft.world.level.levelgen.structure.Structure.StructureSettings;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Optional;

/**
 * 修复 {@link Cursed_Pyramid_Structure} 无视数据包 {@code start_height} 配置的问题。
 *
 * <p>原理：</p>
 * <ul>
 *   <li>替换原始 CODEC，增加 {@code start_height}、{@code project_start_to_heightmap}、
 *       {@code liquid_settings} 三个字段的解析和序列化</li>
 *   <li>修改 {@code generatePieces} 方法中的 {@code spawncenterPos} 变量：
 *       当配置启用时，使用 {@code startHeight.sample()} 动态计算 Y 坐标，
 *       替代 {@code posToSurface} 硬编码的地表高度计算结果</li>
 * </ul>
 *
 * <p>与 BurningArena / RuinedCitadel / SunkenCity 不同，Cursed_Pyramid 没有独立的 {@code start()} 方法，
 * 所有结构片段在 {@code generatePieces} 中内联生成，且以 {@code spawncenterPos} 为基准。
 * 因此使用 {@code @ModifyVariable} 直接替换 {@code spawncenterPos}，
 * 而非重写 {@code findGenerationPoint}。</p>
 *
 * <p>配置键：{@code Config.FIX_CATACLYSM_STRUCTURE_HEIGHT}</p>
 */
@Mixin(value = Cursed_Pyramid_Structure.class, remap = false)
public abstract class CursedPyramidStructureMixin extends CataclysmStructure {

    /** 解析自 JSON 的 start_height */
    @Unique
    private Optional<HeightProvider> beloong$startHeight = Optional.empty();

    /** 高度图投射类型 */
    @Unique
    private Optional<Heightmap.Types> beloong$projectStartToHeightmap = Optional.empty();

    /**
     * 液体处理设置。
     * <p>注意：该字段已从 JSON 解析，但尚未接入结构生成逻辑，
     * 因为 {@code Cursed_Pyramid_Structure.generatePieces()} 不接受液体设置参数。此为延期特性。</p>
     */
    @Unique
    private LiquidSettings beloong$liquidSettings = LiquidSettings.APPLY_WATERLOGGING;

    /** 目标类的原始 CODEC 字段，替换为支持 start_height 的新版本 */
    @Shadow
    @Final
    @Mutable
    private static MapCodec<Cursed_Pyramid_Structure> CODEC;

    static {
        CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Structure.settingsCodec(instance),
                HeightProvider.CODEC.optionalFieldOf("start_height").forGetter(
                        s -> ((CursedPyramidStructureMixin) (Object) s).beloong$startHeight
                ),
                Heightmap.Types.CODEC.optionalFieldOf("project_start_to_heightmap").forGetter(
                        s -> ((CursedPyramidStructureMixin) (Object) s).beloong$projectStartToHeightmap
                ),
                LiquidSettings.CODEC.optionalFieldOf("liquid_settings", LiquidSettings.APPLY_WATERLOGGING).forGetter(
                        s -> ((CursedPyramidStructureMixin) (Object) s).beloong$liquidSettings
                )
        ).apply(instance, (settings, startHeight, proj, liquid) -> {
            Cursed_Pyramid_Structure s = new Cursed_Pyramid_Structure(settings);
            ((CursedPyramidStructureMixin) (Object) s).beloong$setStartHeight(startHeight);
            ((CursedPyramidStructureMixin) (Object) s).beloong$setProjectStartToHeightmap(proj);
            ((CursedPyramidStructureMixin) (Object) s).beloong$setLiquidSettings(liquid);
            return s;
        }));
    }

    protected CursedPyramidStructureMixin(StructureSettings settings) {
        super(settings);
    }

    @Unique
    private void beloong$setStartHeight(Optional<HeightProvider> h) {
        this.beloong$startHeight = h;
    }

    @Unique
    private void beloong$setProjectStartToHeightmap(Optional<Heightmap.Types> p) {
        this.beloong$projectStartToHeightmap = p;
    }

    @Unique
    private void beloong$setLiquidSettings(LiquidSettings l) {
        this.beloong$liquidSettings = l;
    }

    /**
     * 当 {@code fixCataclysmStructureHeight} 启用时，
     * 修改 {@code generatePieces} 中 {@code spawncenterPos} 的 Y 坐标，
     * 使用数据包配置的 {@code start_height} 替代 {@code posToSurface} 的计算结果。
     *
     * <p>{@code spawncenterPos} 是 {@code generatePieces} 中第二个 BlockPos 本地变量
     * （第一个是 {@code centerPos}），因此使用 {@code @At(value = "STORE", ordinal = 1)}
     * 定位其赋值点。</p>
     */
    @ModifyVariable(method = "generatePieces", at = @At(value = "STORE", ordinal = 1))
    private BlockPos adjustSpawnPos(BlockPos original, StructurePiecesBuilder builder, GenerationContext context) {
        if (!Config.FIX_CATACLYSM_STRUCTURE_HEIGHT.get()) return original;
        if (this.beloong$startHeight == null || this.beloong$startHeight.isEmpty()) return original;

        int x = original.getX();
        int z = original.getZ();
        int y = this.beloong$startHeight.get().sample(
                context.random(),
                new WorldGenerationContext(context.chunkGenerator(), context.heightAccessor())
        );
        if (this.beloong$projectStartToHeightmap.isPresent()) {
            Heightmap.Types heightmap = this.beloong$projectStartToHeightmap.get();
            int projectedY = context.chunkGenerator().getFirstOccupiedHeight(x, z, heightmap, context.heightAccessor(), context.randomState());
            y = projectedY + y;
        }
        return new BlockPos(x, y, z);
    }
}
