package com.zonlong.beloong.mixin.cataclysm;

import com.github.L_Ender.cataclysm.structures.Sunken_City_Structure;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zonlong.beloong.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.Structure.GenerationContext;
import net.minecraft.world.level.levelgen.structure.Structure.GenerationStub;
import net.minecraft.world.level.levelgen.structure.Structure.StructureSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * 修复 {@link Sunken_City_Structure} 无视数据包 {@code start_height} 配置的问题。
 *
 * <p>原理：</p>
 * <ul>
 *   <li>替换原始 CODEC，增加 {@code start_height}、{@code project_start_to_heightmap}、
 *       {@code liquid_settings} 三个字段的解析和序列化</li>
 *   <li>重写 {@code findGenerationPoint}：当配置启用时，
 *       使用 {@code startHeight.sample()} 动态计算 Y 坐标，
 *       替代硬编码的 {@code y=19}</li>
 * </ul>
 *
 * <p>配置键：{@code Config.FIX_CATACLYSM_STRUCTURE_HEIGHT}</p>
 */
@Mixin(value = Sunken_City_Structure.class, remap = false)
public abstract class SunkenCityStructureMixin extends Structure {

    /** 解析自 JSON 的 start_height */
    @Unique
    private Optional<HeightProvider> beloong$startHeight = Optional.empty();

    /** 高度图投射类型 */
    @Unique
    private Optional<Heightmap.Types> beloong$projectStartToHeightmap = Optional.empty();

    /**
     * 液体处理设置。
     * <p>注意：该字段已从 JSON 解析，但尚未接入结构生成逻辑，
     * 因为 {@code Sunken_City_Structure.start()} 不接受液体设置参数。此为延期特性。</p>
     */
    @Unique
    private LiquidSettings beloong$liquidSettings = LiquidSettings.APPLY_WATERLOGGING;

    /** 目标类的原始 CODEC 字段，替换为支持 start_height 的新版本 */
    @Shadow
    @Final
    @Mutable
    private static MapCodec<Sunken_City_Structure> CODEC;

    static {
        CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Structure.settingsCodec(instance),
                HeightProvider.CODEC.optionalFieldOf("start_height").forGetter(
                        s -> ((SunkenCityStructureMixin) (Object) s).beloong$startHeight
                ),
                Heightmap.Types.CODEC.optionalFieldOf("project_start_to_heightmap").forGetter(
                        s -> ((SunkenCityStructureMixin) (Object) s).beloong$projectStartToHeightmap
                ),
                LiquidSettings.CODEC.optionalFieldOf("liquid_settings", LiquidSettings.APPLY_WATERLOGGING).forGetter(
                        s -> ((SunkenCityStructureMixin) (Object) s).beloong$liquidSettings
                )
        ).apply(instance, (settings, startHeight, proj, liquid) -> {
            Sunken_City_Structure s = new Sunken_City_Structure(settings);
            ((SunkenCityStructureMixin) (Object) s).beloong$setStartHeight(startHeight);
            ((SunkenCityStructureMixin) (Object) s).beloong$setProjectStartToHeightmap(proj);
            ((SunkenCityStructureMixin) (Object) s).beloong$setLiquidSettings(liquid);
            return s;
        }));
    }

    protected SunkenCityStructureMixin(StructureSettings settings) {
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
     * 使用数据包配置的 {@code start_height} 计算 Y 坐标，
     * 替代原版硬编码的 {@code y=19}。
     */
    @Inject(method = "findGenerationPoint", at = @At("HEAD"), cancellable = true)
    private void beloong$redirectFindGenerationPoint(
            GenerationContext context,
            CallbackInfoReturnable<Optional<GenerationStub>> cir
    ) {
        if (!Config.FIX_CATACLYSM_STRUCTURE_HEIGHT.get()) {
            return;
        }
        // 防御性检查：如果 mixin 字段未被 CODEC 路径初始化（例如通过其他构造路径创建），回退到原版行为
        if (this.beloong$startHeight == null || this.beloong$startHeight.isEmpty()) {
            return;
        }

        ChunkPos chunkpos = context.chunkPos();
        int y = this.beloong$startHeight.get().sample(
                context.random(),
                new WorldGenerationContext(context.chunkGenerator(), context.heightAccessor())
        );
        // 如果配置了高度图投射，将 start_height 视为偏移量叠加到地形高度上
        if (this.beloong$projectStartToHeightmap.isPresent()) {
            Heightmap.Types heightmap = this.beloong$projectStartToHeightmap.get();
            int projectedY = context.chunkGenerator().getFirstOccupiedHeight(
                    chunkpos.getMiddleBlockX(), chunkpos.getMiddleBlockZ(),
                    heightmap, context.heightAccessor(), context.randomState()
            );
            y = projectedY + y;
        }
        BlockPos blockpos = new BlockPos(chunkpos.getMinBlockX(), y, chunkpos.getMinBlockZ());

        cir.setReturnValue(Optional.of(new GenerationStub(blockpos, piecesBuilder -> {
            Rotation rotation = Rotation.getRandom(context.random());
            Sunken_City_Structure.start(
                    context.structureTemplateManager(),
                    blockpos,
                    rotation,
                    piecesBuilder,
                    context.random()
            );
        })));
    }
}
