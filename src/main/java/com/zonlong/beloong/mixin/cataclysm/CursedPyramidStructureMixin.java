package com.zonlong.beloong.mixin;

import com.github.L_Ender.cataclysm.structures.CataclysmStructure;
import com.github.L_Ender.cataclysm.structures.Cursed_Pyramid_Structure;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zonlong.beloong.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.Structure.GenerationContext;
import net.minecraft.world.level.levelgen.structure.Structure.GenerationStub;
import net.minecraft.world.level.levelgen.structure.Structure.StructureSettings;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * 修复 {@link Cursed_Pyramid_Structure} 无视数据包 {@code start_height} 配置的问题。
 */
@Mixin(value = Cursed_Pyramid_Structure.class, remap = false)
public abstract class CursedPyramidStructureMixin extends CataclysmStructure {

    @Unique
    private Optional<HeightProvider> beloong$startHeight = Optional.empty();

    @Unique
    private Optional<Heightmap.Types> beloong$projectStartToHeightmap = Optional.empty();

    @Unique
    private LiquidSettings beloong$liquidSettings = LiquidSettings.APPLY_WATERLOGGING;

    @Shadow @Final @Mutable
    private static MapCodec<Cursed_Pyramid_Structure> CODEC;

    // Shadows for private static ResourceLocations used in generatePieces
    @Shadow private static ResourceLocation LOWER1;
    @Shadow private static ResourceLocation LOWER2;
    @Shadow private static ResourceLocation LOWER3;
    @Shadow private static ResourceLocation LOWER4;
    @Shadow private static ResourceLocation UPPER1;
    @Shadow private static ResourceLocation UPPER2;
    @Shadow private static ResourceLocation UPPER3;
    @Shadow private static ResourceLocation UPPER4;
    @Shadow private static ResourceLocation OBELISK1;
    @Shadow private static ResourceLocation OBELISK2;

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
    private void beloong$setStartHeight(Optional<HeightProvider> h) { this.beloong$startHeight = h; }

    @Unique
    private void beloong$setProjectStartToHeightmap(Optional<Heightmap.Types> p) { this.beloong$projectStartToHeightmap = p; }

    @Unique
    private void beloong$setLiquidSettings(LiquidSettings l) { this.beloong$liquidSettings = l; }

    /**
     * 替换 {@code generatePieces} 方法的实现，使用数据包配置的 start_height 计算 Y。
     */
    @Inject(method = "generatePieces", at = @At("HEAD"), cancellable = true)
    private void onGeneratePieces(StructurePiecesBuilder builder, GenerationContext context, CallbackInfo ci) {
        if (!Config.FIX_CATACLYSM_STRUCTURE_HEIGHT.get()) return;
        if (this.beloong$startHeight == null || this.beloong$startHeight.isEmpty()) return;

        ci.cancel();

        StructureTemplateManager templateManager = context.structureTemplateManager();
        Rotation rotation = Rotation.values()[context.random().nextInt(Rotation.values().length)];
        int x = (context.chunkPos().x << 4) + 7;
        int z = (context.chunkPos().z << 4) + 7;

        int y = this.beloong$startHeight.get().sample(
                context.random(),
                new WorldGenerationContext(context.chunkGenerator(), context.heightAccessor())
        );
        if (this.beloong$projectStartToHeightmap.isPresent()) {
            Heightmap.Types heightmap = this.beloong$projectStartToHeightmap.get();
            int projectedY = context.chunkGenerator().getFirstOccupiedHeight(x, z,
                    heightmap, context.heightAccessor(), context.randomState());
            y = projectedY + y;
        }
        BlockPos spawncenterPos = new BlockPos(x, y, z);

        BlockPos obelisk1Offset = spawncenterPos.offset(new BlockPos(20, -4, 94).rotate(rotation));
        BlockPos obelisk2Offset = spawncenterPos.offset(new BlockPos(45, -4, 94).rotate(rotation));

        BlockPos lower1Offset = spawncenterPos.offset(0, -39, 0);
        BlockPos lower2Offset = spawncenterPos.offset(new BlockPos(0, -39, 47).rotate(rotation));
        BlockPos lower3Offset = spawncenterPos.offset(new BlockPos(47, -39, 0).rotate(rotation));
        BlockPos lower4Offset = spawncenterPos.offset(new BlockPos(47, -39, 47).rotate(rotation));

        BlockPos upper1Offset = spawncenterPos.offset(0, 9, 0);
        BlockPos upper2Offset = spawncenterPos.offset(new BlockPos(0, 9, 47).rotate(rotation));
        BlockPos upper3Offset = spawncenterPos.offset(new BlockPos(47, 9, 0).rotate(rotation));
        BlockPos upper4Offset = spawncenterPos.offset(new BlockPos(47, 9, 47).rotate(rotation));

        builder.addPiece(new Cursed_Pyramid_Structure.Piece(templateManager, LOWER1, lower1Offset, rotation));
        builder.addPiece(new Cursed_Pyramid_Structure.Piece(templateManager, LOWER2, lower2Offset, rotation));
        builder.addPiece(new Cursed_Pyramid_Structure.Piece(templateManager, LOWER3, lower3Offset, rotation));
        builder.addPiece(new Cursed_Pyramid_Structure.Piece(templateManager, LOWER4, lower4Offset, rotation));

        builder.addPiece(new Cursed_Pyramid_Structure.Piece(templateManager, UPPER1, upper1Offset, rotation));
        builder.addPiece(new Cursed_Pyramid_Structure.Piece(templateManager, UPPER2, upper2Offset, rotation));
        builder.addPiece(new Cursed_Pyramid_Structure.Piece(templateManager, UPPER3, upper3Offset, rotation));
        builder.addPiece(new Cursed_Pyramid_Structure.Piece(templateManager, UPPER4, upper4Offset, rotation));

        builder.addPiece(new Cursed_Pyramid_Structure.Piece(templateManager, OBELISK1, obelisk1Offset, rotation));
        builder.addPiece(new Cursed_Pyramid_Structure.Piece(templateManager, OBELISK2, obelisk2Offset, rotation));
    }
}
