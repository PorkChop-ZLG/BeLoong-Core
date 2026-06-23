package com.zonlong.beloong.mixin;

import com.finderfeed.fdbosses.content.structures.GeburahArenaStructure;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zonlong.beloong.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.Structure.GenerationContext;
import net.minecraft.world.level.levelgen.structure.Structure.GenerationStub;
import net.minecraft.world.level.levelgen.structure.Structure.StructureSettings;
import net.minecraft.world.level.levelgen.structure.pools.DimensionPadding;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Optional;

/**
 * 修复 {@link GeburahArenaStructure} 硬编码 Y=140 且无视 start_height 的问题。
 * 原始代码中 {@code pos.getY() != -1} 检查导致结构在正常世界中无法生成。
 */
@Mixin(value = GeburahArenaStructure.class, remap = false)
public abstract class GeburahArenaStructureMixin extends Structure {

    @Unique
    private Optional<HeightProvider> beloong$startHeight = Optional.empty();

    @Unique
    private Optional<Heightmap.Types> beloong$projectStartToHeightmap = Optional.empty();

    /** 原始 start_pool 字段，CODEC 序列化时需要 */
    @Shadow
    @Final
    private Holder<StructureTemplatePool> startPool;

    @Shadow
    @Final
    @Mutable
    private static MapCodec<GeburahArenaStructure> CODEC;

    static {
        CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Structure.settingsCodec(instance),
                StructureTemplatePool.CODEC.fieldOf("start_pool").forGetter(
                        s -> ((GeburahArenaStructureMixin) (Object) s).startPool
                ),
                HeightProvider.CODEC.optionalFieldOf("start_height").forGetter(
                        s -> ((GeburahArenaStructureMixin) (Object) s).beloong$startHeight
                ),
                Heightmap.Types.CODEC.optionalFieldOf("project_start_to_heightmap").forGetter(
                        s -> ((GeburahArenaStructureMixin) (Object) s).beloong$projectStartToHeightmap
                )
        ).apply(instance, (settings, startPool, startHeight, proj) -> {
            GeburahArenaStructure s = new GeburahArenaStructure(settings, startPool);
            ((GeburahArenaStructureMixin) (Object) s).beloong$startHeight = startHeight;
            ((GeburahArenaStructureMixin) (Object) s).beloong$projectStartToHeightmap = proj;
            return s;
        }));
    }

    protected GeburahArenaStructureMixin(StructureSettings settings) {
        super(settings);
    }

    /**
     * 替换 findGenerationPoint：使用 start_height 计算 Y 替代硬编码 140，
     * 并移除原版 {@code pos.getY() != -1} 的异常检查。
     */
    @Inject(method = "findGenerationPoint", at = @At("HEAD"), cancellable = true)
    private void onFindGenerationPoint(GenerationContext ctx,
            CallbackInfoReturnable<Optional<GenerationStub>> cir) {
        if (!Config.FIX_FDBOSSES_STRUCTURE_HEIGHT.get()) return;

        BlockPos pos = this.getLowestYIn5by5BoxOffset7Blocks(ctx, Rotation.NONE);

        int y;
        if (this.beloong$startHeight.isPresent()) {
            y = this.beloong$startHeight.get().sample(
                    ctx.random(),
                    new WorldGenerationContext(ctx.chunkGenerator(), ctx.heightAccessor())
            );
            if (this.beloong$projectStartToHeightmap.isPresent()) {
                Heightmap.Types heightmap = this.beloong$projectStartToHeightmap.get();
                int projectedY = ctx.chunkGenerator().getFirstOccupiedHeight(
                        pos.getX(), pos.getZ(), heightmap, ctx.heightAccessor(), ctx.randomState());
                y = projectedY + y;
            }
        } else {
            y = 140; // 保持原版默认
        }

        BlockPos blockpos = new BlockPos(pos.getX(), y, pos.getZ());

        cir.setReturnValue(JigsawPlacement.addPieces(
                ctx, this.startPool, Optional.empty(), 20, blockpos, false,
                this.beloong$projectStartToHeightmap, 200,
                PoolAliasLookup.create(new ArrayList<>(), blockpos, ctx.seed()),
                DimensionPadding.ZERO, LiquidSettings.IGNORE_WATERLOGGING
        ));
    }
}
