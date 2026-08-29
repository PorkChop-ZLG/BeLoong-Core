package com.zonlong.beloong.compat.betterendisland;

import com.zonlong.beloong.Config;
import com.yungnickyoung.minecraft.betterendisland.world.IBetterDragonFight;
import com.yungnickyoung.minecraft.betterendisland.mixin.accessor.EndDragonFightAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.neoforged.fml.ModList;

import javax.annotation.Nullable;

/**
 * 末影龙手动召唤仪式辅助类。
 *
 * <p>负责计算 BEI 中央塔的 4 个仪式坐标、检查/删除召唤方块、生成仪式末地水晶，
 * 以及把仪式位置下方的基岩替换为强化深板岩。</p>
 */
public final class DragonSummonHelper {

    /** BEI 中央塔水晶围绕中心的水平偏移，与 BetterEndPodiumFeature 保持一致。 */
    private static final int RITUAL_RADIUS = 8;

    private DragonSummonHelper() {}

    /**
     * 功能是否可用：BEI 已加载且配置启用。
     */
    public static boolean isAvailable() {
        return ModList.get().isLoaded("betterendisland")
                && Config.DragonSummon.enabled.get();
    }

    /**
     * 获取配置的召唤方块。
     */
    public static Block getSummonBlock() {
        String id = Config.DragonSummon.summonBlock.get();
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(id));
        return block == null ? Blocks.AIR : block;
    }

    /**
     * 获取当前末地龙战斗实例。
     */
    @Nullable
    public static EndDragonFight getDragonFight(ServerLevel level) {
        if (!level.dimension().equals(net.minecraft.world.level.Level.END)) {
            return null;
        }
        return level.getDragonFight();
    }

    /**
     * 计算 4 个仪式坐标。若传送门位置尚未初始化，返回空数组。
     */
    public static BlockPos[] getRitualPositions(EndDragonFight fight) {
        EndDragonFightAccessor accessor = (EndDragonFightAccessor) fight;
        BlockPos portalLocation = accessor.getPortalLocation();
        if (portalLocation == null) {
            return new BlockPos[0];
        }

        BlockPos center = portalLocation.above(1);
        BlockPos[] positions = new BlockPos[4];
        int i = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            positions[i++] = center.relative(direction, RITUAL_RADIUS);
        }
        return positions;
    }

    /**
     * 获取仪式中心坐标（即 BEI 的传送门位置）。未初始化时返回 null。
     */
    @Nullable
    public static BlockPos getRitualCenter(EndDragonFight fight) {
        return ((EndDragonFightAccessor) fight).getPortalLocation();
    }


    /**
     * 判断某个坐标是否属于 4 个仪式坐标之一。
     */
    public static boolean isRitualPosition(EndDragonFight fight, BlockPos pos) {
        for (BlockPos ritualPos : getRitualPositions(fight)) {
            if (ritualPos.equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查 4 个仪式位置是否同时为配置的召唤方块。
     */
    public static boolean areAllSummonBlocksPlaced(ServerLevel level, EndDragonFight fight) {
        Block summonBlock = getSummonBlock();
        if (summonBlock == Blocks.AIR) {
            return false;
        }
        for (BlockPos pos : getRitualPositions(fight)) {
            if (!level.getBlockState(pos).is(summonBlock)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 删除 4 个仪式位置上的召唤方块（设为空气，不掉落）。
     */
    public static void removeSummonBlocks(ServerLevel level, EndDragonFight fight) {
        for (BlockPos pos : getRitualPositions(fight)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /**
     * 在 4 个仪式位置生成末地水晶。
     *
     * <p>首次战斗的水晶设为无敌（与 BEI 初始水晶一致）；复活时保持可被破坏，
     * 以保留“破坏水晶中止复活”的原版语义。</p>
     */
    public static void spawnRitualCrystals(ServerLevel level, EndDragonFight fight) {
        boolean firstFight = !((IBetterDragonFight) fight).hasDragonEverSpawned();
        for (BlockPos pos : getRitualPositions(fight)) {
            EndCrystal crystal = new EndCrystal(
                    level,
                    pos.getX() + 0.5D,
                    pos.getY(),
                    pos.getZ() + 0.5D);
            crystal.setShowBottom(false);
            crystal.setInvulnerable(firstFight);
            level.addFreshEntity(crystal);
        }
    }

    /**
     * 把 4 个仪式位置下方的基岩替换为强化深板岩。
     *
     * <p>用于在首次进入末地时（BEI 生成中央塔后）立即替换，防止玩家手动放置末地水晶。</p>
     */
    public static void replaceCrystalSupportWithReinforcedDeepslate(ServerLevel level, EndDragonFight fight) {
        for (BlockPos pos : getRitualPositions(fight)) {
            BlockPos supportPos = pos.below();
            if (level.getBlockState(supportPos).is(Blocks.BEDROCK)) {
                level.setBlock(supportPos, Blocks.REINFORCED_DEEPSLATE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    /**
     * 单个召唤方块放置成功：播放信标选择音，并生成村民喜悦绿色粒子。
     */
    public static void playSingleBlockPlacedEffects(ServerLevel level, BlockPos pos) {
        level.playSound(null, pos, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.sendParticles(
                ParticleTypes.HAPPY_VILLAGER,
                pos.getX() + 0.5D,
                pos.getY() + 0.5D,
                pos.getZ() + 0.5D,
                12,
                0.5D,
                0.5D,
                0.5D,
                0.0D);
    }

    /**
     * 仪式完成：播放信标激活音，并在 4 个仪式位置生成龙息粒子。
     */
    public static void playRitualCompleteEffects(ServerLevel level, EndDragonFight fight) {
        BlockPos[] positions = getRitualPositions(fight);
        for (BlockPos pos : positions) {
            level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0F, 1.0F);
            level.sendParticles(
                    ParticleTypes.DRAGON_BREATH,
                    pos.getX() + 0.5D,
                    pos.getY() + 0.5D,
                    pos.getZ() + 0.5D,
                    20,
                    0.5D,
                    0.5D,
                    0.5D,
                    0.0D);
        }
    }

    /**
     * 向指定玩家发送某个空位仪式位置的提醒粒子。
     */
    public static void sendEmptySlotParticle(ServerLevel level, ServerPlayer player, BlockPos pos) {
        level.sendParticles(
                player,
                ParticleTypes.END_ROD,
                false,
                pos.getX() + 0.5D,
                pos.getY() + 0.5D,
                pos.getZ() + 0.5D,
                5,
                0.3D,
                0.3D,
                0.3D,
                0.0D);
    }

}
