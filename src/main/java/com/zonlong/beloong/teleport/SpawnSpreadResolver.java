package com.zonlong.beloong.teleport;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

/**
 * 「世界出生点周围扩散」的<b>非阻塞</b>实现（仅服务端主线程）。
 * <p>
 * 复刻原版 {@code ServerPlayer#adjustSpawnLocation} 的主体（随机起点 + 共素数步长的候选遍历
 * + {@code PlayerRespawnLogic#getOverworldRespawnPos} 的安全落点判定 + 玩家碰撞箱复核），
 * 但把其中的阻塞调用换掉：
 * <ul>
 *   <li>原版用 {@code Level#getChunk}（阻塞，会等区块生成）——本类改用
 *       {@code ServerChunkCache#getChunkNow}，<b>区块不在内存就跳过该候选</b>；</li>
 *   <li>因此候选的枚举顺序（逐块扫描 + 取第一个命中）与原版一致，只是"未加载的候选"被跳过 ⇒
 *       结果可能落在稍远的候选上，或最终一个都不命中而走兜底。</li>
 * </ul>
 * <b>主线程绝不阻塞</b>：这是本项目 2026-09-12 死锁事故后立下的硬约束
 * （见 {@code docs/天灾传送门主线程死锁-根因与修复复盘.md}）。
 * <p>
 * <b>用在哪</b>：<b>只有</b>「龙宫 → 主世界」这一条线（见 {@link DimensionTeleport} 的
 * {@link TeleportTarget.Spawn} 分支）。「其他维度 → 龙宫」仍是固定坐标，不扩散。
 * <p>
 * <b>与原版的三处刻意差异</b>：
 * <ol>
 *   <li><b>忽略游戏模式</b>：原版在冒险模式下会整段跳过扩散，本类一律扩散（用户指定）；</li>
 *   <li><b>忽略天空光判断</b>：原版要求 {@code dimensionType().hasSkyLight()}，本类不判
 *       （调用方只在主世界用）；</li>
 *   <li><b>失败不做"上顶/下踩"</b>：原版扩散失败后会把玩家从出生点向上顶出方块、再向下踩到贴地，
 *       本类按用户要求<b>原样返回出生点坐标</b>。</li>
 * </ol>
 * 其余（共素数步长 17、随机起点、水面排除、世界边界夹取、{@code spawnRadius} 口径）与原版一致。
 */
final class SpawnSpreadResolver {

    private SpawnSpreadResolver() {}

    /**
     * 在出生点周围扩散出一个安全落点；扩散失败时返回出生点<b>原样坐标</b>。
     *
     * @param level  目标维度（主世界）
     * @param player 被放置的玩家（用于碰撞箱复核；其 {@code level()} 仍是旧维度，这不影响判定）
     * @return 落点脚底坐标（永不 {@code null}）
     */
    static BlockPos resolve(ServerLevel level, ServerPlayer player) {
        BlockPos sharedSpawn = level.getSharedSpawnPos();

        // 扩散半径口径 = gamerule spawnRadius（原版 getSpawnRadius），再被世界边界距离夹一次
        int radius = Math.max(0, level.getGameRules().getInt(net.minecraft.world.level.GameRules.RULE_SPAWN_RADIUS));
        int borderDistance = Mth.floor(level.getWorldBorder().getDistanceToBorder(sharedSpawn.getX(), sharedSpawn.getZ()));
        radius = Math.min(radius, borderDistance);
        if (borderDistance <= 1) radius = 1;                 // 与原版一致：贴边时仍给 1 格

        long side = (long) (radius * 2 + 1);
        long candidateCount = side * side;
        int count = candidateCount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) candidateCount;
        int coprime = count <= 16 ? count - 1 : 17;          // 原版 getCoprime
        int start = RandomSource.create().nextInt(count);     // 原版：随机起点

        var box = player.getDimensions(player.getPose()).makeBoundingBox(net.minecraft.world.phys.Vec3.ZERO);

        for (int step = 0; step < count; step++) {
            int idx = (start + coprime * step) % count;
            int dx = idx % (radius * 2 + 1);
            int dz = idx / (radius * 2 + 1);
            BlockPos candidate = safeColumn(level,
                    sharedSpawn.getX() + dx - radius, sharedSpawn.getZ() + dz - radius);
            if (candidate != null && level.noCollision(player, box.move(candidate.getBottomCenter()))) {
                return candidate;
            }
        }

        // 扩散失败：用户指定"用当前出生点坐标兜底"（原版此处会做上顶/下踩，本类刻意不做）
        return sharedSpawn;
    }

    /**
     * 复刻 {@code PlayerRespawnLogic#getOverworldRespawnPos} 的判定，但区块用非阻塞探测。
     *
     * @return 该列的安全脚底坐标；<b>区块不在内存</b>、越界、虚空列或水面列都返回 {@code null}
     */
    @Nullable
    private static BlockPos safeColumn(ServerLevel level, int x, int z) {
        // 非阻塞探测：区块不在内存就放弃该候选（原版此处是阻塞 getChunk）
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) return null;

        if (x < -30000000 || z < -30000000 || x >= 30000000 || z >= 30000000) return null;

        int motionBlocking = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
        if (motionBlocking < level.getMinBuildHeight()) return null;      // 虚空列

        int worldSurface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        int oceanFloor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x & 15, z & 15);
        // 水面列：原版明确排除（世界表面高过"含流体高度"且仍高于海底高）
        if (worldSurface > motionBlocking && worldSurface > oceanFloor) return null;

        // 自"含流体高度"向下找第一个上方为完整碰撞面的方块
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = motionBlocking + 1; y >= level.getMinBuildHeight(); y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (!state.getFluidState().isEmpty()) break;                  // 撞到流体：该列不可用
            if (Block.isFaceFull(state.getCollisionShape(level, cursor), Direction.UP)) {
                return cursor.above().immutable();
            }
        }
        return null;
    }
}
