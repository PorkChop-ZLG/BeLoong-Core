package com.zonlong.beloong.block;

import com.zonlong.beloong.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class DisasterPortalBlock extends Block implements EntityBlock {
    private static final String DISASTER_DIM = "beloong:disaster";
    protected static final VoxelShape SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 12.0D, 16.0D);

    public DisasterPortalBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .noCollission()
                .lightLevel(s -> 11)
                .strength(-1.0F, 3600000.0F)
                .noLootTable()
                .pushReaction(PushReaction.BLOCK)
                .sound(SoundType.GLASS));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level,
                                  BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext ctx) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DisasterPortalBlockEntity(pos, state);
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (level.isClientSide) return;
        if (!(entity instanceof ServerPlayer player)) return;

        long cooldownEnd = player.getPersistentData().getLong("beloong_portal_cooldown");
        if (cooldownEnd > level.getGameTime()) return;

        String currentDim = level.dimension().location().toString();

        if (!currentDim.equals(DISASTER_DIM)) {
            // 任何维度 → 天灾维度（1:1 坐标 + 结构生成）
            ResourceLocation targetDimId = ResourceLocation.tryParse(DISASTER_DIM);
            if (targetDimId == null) return;

            ServerLevel targetLevel = player.server.getLevel(
                    net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.DIMENSION, targetDimId));
            if (targetLevel == null) return;

            double targetX = player.getX();
            double targetZ = player.getZ();
            int blockX = (int) Math.floor(targetX);
            int blockZ = (int) Math.floor(targetZ);

            targetLevel.getChunk(blockX >> 4, blockZ >> 4);
            int topY = targetLevel.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
            double targetY = topY + 1.0;

            if (player.isPassenger()) player.stopRiding();

            player.teleportTo(targetLevel, targetX, targetY, targetZ,
                    java.util.Set.of(), player.getYRot(), player.getXRot());
            player.fallDistance = 0;

            // 放置返回结构，使用配置偏移
            int offsetX = Config.DisasterPortal.structureOffsetX.get();
            int offsetY = Config.DisasterPortal.structureOffsetY.get();
            int offsetZ = Config.DisasterPortal.structureOffsetZ.get();
            BlockPos structurePos = new BlockPos(blockX + offsetX, topY + offsetY, blockZ + offsetZ);
            placeReturnStructure(targetLevel, structurePos);
        } else {
            // 天灾维度 → 主世界（原版末地返回逻辑：出生点或世界出生点）
            BlockPos respawnPos = player.getRespawnPosition();
            net.minecraft.resources.ResourceKey<Level> respawnDim = player.getRespawnDimension();

            ServerLevel overworld = player.server.getLevel(Level.OVERWORLD);
            if (respawnPos == null || respawnDim == null) {
                respawnPos = overworld != null
                        ? overworld.getSharedSpawnPos()
                        : new BlockPos(0, 64, 0);
                respawnDim = Level.OVERWORLD;
            }

            ServerLevel targetLevel = player.server.getLevel(respawnDim);
            if (targetLevel == null) {
                targetLevel = overworld;
            }
            if (targetLevel == null) return;

            if (player.isPassenger()) player.stopRiding();

            player.teleportTo(targetLevel,
                    respawnPos.getX() + 0.5, respawnPos.getY(), respawnPos.getZ() + 0.5,
                    java.util.Set.of(), player.getYRot(), player.getXRot());
            player.fallDistance = 0;
        }

        int cooldown = Config.DisasterPortal.teleportCooldownTicks.get();
        player.getPersistentData().putLong("beloong_portal_cooldown", level.getGameTime() + cooldown);
    }

    private void placeReturnStructure(ServerLevel level, BlockPos pos) {
        String templatePath = Config.DisasterPortal.returnStructureTemplate.get();
        ResourceLocation templateId = ResourceLocation.tryParse(templatePath);
        if (templateId == null) return;

        net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager
                templateManager = level.getStructureManager();
        var template = templateManager.get(templateId);
        if (template.isPresent()) {
            template.get().placeInWorld(level, pos, pos,
                    new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(),
                    level.getRandom(), 2);
        }
    }
}
