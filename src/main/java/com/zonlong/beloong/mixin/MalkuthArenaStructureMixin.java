package com.zonlong.beloong.mixin;

import com.finderfeed.fdbosses.FDBosses;
import com.finderfeed.fdbosses.content.structures.MalkuthArenaStructure;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zonlong.beloong.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
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
 * 修复 {@link MalkuthArenaStructure} 硬编码 Y=-3 且无视 start_height 的问题。
 */
@Mixin(value = MalkuthArenaStructure.class, remap = false)
public abstract class MalkuthArenaStructureMixin extends Structure {

    @Unique
    private Optional<HeightProvider> beloong$startHeight = Optional.empty();

    @Unique
    private Optional<Heightmap.Types> beloong$projectStartToHeightmap = Optional.empty();

    @Shadow
    @Final
    private Holder<StructureTemplatePool> startPool;

    @Shadow
    @Final
    @Mutable
    private static MapCodec<MalkuthArenaStructure> CODEC;

    static {
        CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Structure.settingsCodec(instance),
                StructureTemplatePool.CODEC.fieldOf("start_pool").forGetter(
                        s -> ((MalkuthArenaStructureMixin) (Object) s).startPool
                ),
                HeightProvider.CODEC.optionalFieldOf("start_height").forGetter(
                        s -> ((MalkuthArenaStructureMixin) (Object) s).beloong$startHeight
                ),
                Heightmap.Types.CODEC.optionalFieldOf("project_start_to_heightmap").forGetter(
                        s -> ((MalkuthArenaStructureMixin) (Object) s).beloong$projectStartToHeightmap
                )
        ).apply(instance, (settings, startPool, startHeight, proj) -> {
            MalkuthArenaStructure s = new MalkuthArenaStructure(settings, startPool);
            ((MalkuthArenaStructureMixin) (Object) s).beloong$startHeight = startHeight;
            ((MalkuthArenaStructureMixin) (Object) s).beloong$projectStartToHeightmap = proj;
            return s;
        }));
    }

    protected MalkuthArenaStructureMixin(StructureSettings settings) {
        super(settings);
    }

    /**
     * 替换 findGenerationPoint：使用 start_height 计算 Y 替代硬编码 -3。
     */
    @Inject(method = "findGenerationPoint", at = @At("HEAD"), cancellable = true)
    private void onFindGenerationPoint(GenerationContext ctx,
            CallbackInfoReturnable<Optional<GenerationStub>> cir) {
        if (!Config.FIX_FDBOSSES_STRUCTURE_HEIGHT.get()) return;

        ChunkPos chunkpos = ctx.chunkPos();
        int x = chunkpos.getMinBlockX() + 8;
        int z = chunkpos.getMinBlockZ() + 8;

        int y;
        if (this.beloong$startHeight.isPresent()) {
            y = this.beloong$startHeight.get().sample(
                    ctx.random(),
                    new WorldGenerationContext(ctx.chunkGenerator(), ctx.heightAccessor())
            );
        } else {
            y = -3; // 保持原版默认
        }

        BlockPos blockpos = new BlockPos(x, y, z);

        cir.setReturnValue(JigsawPlacement.addPieces(
                ctx, this.startPool,
                Optional.of(FDBosses.location("malkuth_arena_part_1")),
                20, blockpos, false,
                this.beloong$projectStartToHeightmap.isPresent()
                        ? this.beloong$projectStartToHeightmap
                        : Optional.of(Heightmap.Types.WORLD_SURFACE_WG),
                128,
                PoolAliasLookup.create(new ArrayList<>(), blockpos, ctx.seed()),
                DimensionPadding.ZERO, LiquidSettings.IGNORE_WATERLOGGING
        ));
    }
}
