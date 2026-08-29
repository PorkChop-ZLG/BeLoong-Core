package com.zonlong.beloong.compat.betterendisland;

import com.yungnickyoung.minecraft.betterendisland.world.IBetterDragonFight;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * 末影龙手动召唤仪式事件处理器。
 *
 * <p>监听方块放置事件：当 4 个仪式位置同时放满配置方块后，
 * 删除方块、生成末地水晶，并显式触发 BEI 的首次召唤或复活流程。</p>
 */
public final class DragonSummonHandler {

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!DragonSummonHelper.isAvailable()) {
            return;
        }

        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!serverLevel.dimension().equals(Level.END)) {
            return;
        }
        if (!event.getPlacedBlock().is(DragonSummonHelper.getSummonBlock())) {
            return;
        }

        EndDragonFight fight = DragonSummonHelper.getDragonFight(serverLevel);
        if (fight == null) {
            return;
        }
        if (!DragonSummonHelper.isRitualPosition(fight, event.getPos())) {
            return;
        }
        if (!DragonSummonHelper.areAllSummonBlocksPlaced(serverLevel, fight)) {
            // 单块放置成功反馈
            DragonSummonHelper.playSingleBlockPlacedEffects(serverLevel, event.getPos());
            return;
        }

        // 仪式完成反馈
        DragonSummonHelper.playRitualCompleteEffects(serverLevel, fight);

        // 仪式完成：方块 → 末地水晶
        DragonSummonHelper.removeSummonBlocks(serverLevel, fight);
        DragonSummonHelper.spawnRitualCrystals(serverLevel, fight);

        // 显式触发 BEI 流程
        IBetterDragonFight betterFight = (IBetterDragonFight) fight;
        if (betterFight.hasDragonEverSpawned()) {
            fight.tryRespawn();
        } else {
            betterFight.doInitialDragonSpawn();
        }
    }

    /**
     * 每 20 tick 向仪式中心附近玩家发送空位提醒粒子。
     */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!DragonSummonHelper.isAvailable()) {
            return;
        }
        if (event.getServer().getTickCount() % 20 != 0) {
            return;
        }

        ServerLevel end = event.getServer().getLevel(Level.END);
        if (end == null) {
            return;
        }

        EndDragonFight fight = DragonSummonHelper.getDragonFight(end);
        if (fight == null) {
            return;
        }

        BlockPos center = DragonSummonHelper.getRitualCenter(fight);
        if (center == null) {
            return;
        }

        BlockPos[] positions = DragonSummonHelper.getRitualPositions(fight);
        if (positions.length == 0) {
            return;
        }

        double centerX = center.getX() + 0.5D;
        double centerY = center.getY();
        double centerZ = center.getZ() + 0.5D;
        double radiusSq = 32.0D * 32.0D;

        for (ServerPlayer player : end.players()) {
            if (player.isSpectator()) {
                continue;
            }
            if (player.blockPosition().distToCenterSqr(centerX, centerY, centerZ) > radiusSq) {
                continue;
            }

            for (BlockPos pos : positions) {
                if (!end.getBlockState(pos).is(DragonSummonHelper.getSummonBlock())) {
                    DragonSummonHelper.sendEmptySlotParticle(end, player, pos);
                }
            }
        }
    }

}
