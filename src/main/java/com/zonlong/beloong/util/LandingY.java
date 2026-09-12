package com.zonlong.beloong.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 落点 Y 的<b>非阻塞</b>解析（仅服务端主线程）。
 * <p>
 * 供"目标坐标已固定"的传送使用（龙宫 ↔ 主世界）：它们只需要一个安全的落脚 Y，
 * <b>不需要</b>为了拿高度图而同步加载目的地区块。
 * <p>
 * 语义与旧实现（{@code level.getChunk(...)} + {@code Level#getHeight(MOTION_BLOCKING, ...)}）<b>逐字等价</b>：
 * <ul>
 *   <li>区块已在内存中 → 取"地表上方 1 格"（≡ {@code Level#getHeight} 再 +1.0）；</li>
 *   <li>区块未加载 → 返回 {@code fallbackY}。旧实现在 {@code Level#getHeight} 内部走 {@code hasChunk}
 *       为假的分支、返回 {@code getMinBuildHeight()}，谓词为假后同样落到 {@code fallbackY}；</li>
 *   <li>坐标超出 ±3000 万 → 沿用 {@code Level#getHeight} 的 {@code seaLevel + 1} 分支语义。</li>
 * </ul>
 * <p>
 * <b>为什么不能阻塞</b>：旧写法在主线程上同步等待目的地区块生成；一旦该区块的生成 future 永不完成
 * （实机发生过工作线程 {@code StackOverflowError} 导致 future 被永久挂起），主线程会在
 * {@code managedBlock} 上永久自旋，且不产生 crash report。
 * 详见 {@code docs/天灾传送门主线程死锁-根因与修复复盘.md}。
 * <p>
 * 这里<b>不</b>申领票据、不等待、不重试：传送本身
 * （{@code ServerPlayer#teleportTo(ServerLevel, ...)}）自带 {@code TicketType.POST_TELEPORT} 票据，
 * 落点区块随后必然被加载；本类只负责"能不能拿到精确 Y"。
 * <p>
 * <b>仅限服务端主线程调用</b>：{@code ServerChunkCache#getChunkNow} 在非主线程恒返回 {@code null}，
 * 会静默退化为兜底值。
 */
public final class LandingY {

    private LandingY() {}

    /**
     * 解析传送落脚 Y。
     *
     * @param level     目标维度（服务端）
     * @param x         传送目标 X（内部按 {@code Mth.floor} 求区块与高度图列，与旧实现的 {@code (int) Math.floor} 一致）
     * @param z         传送目标 Z
     * @param fallbackY 落点区块未加载（或该列为虚空）时使用的兜底 Y
     * @return 可用的落脚 Y
     */
    public static double resolveOrFallback(ServerLevel level, double x, double z, double fallbackY) {
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);

        // 非阻塞探测：命中则直接用内存中的区块读高度图；未命中直接兜底（绝不触发加载或等待）
        LevelChunk chunk = level.getChunkSource().getChunkNow(blockX >> 4, blockZ >> 4);
        if (chunk == null) return fallbackY;

        int topBlockY;
        if (blockX >= -30000000 && blockZ >= -30000000 && blockX < 30000000 && blockZ < 30000000) {
            // ChunkAccess#getHeight 比 Level#getHeight 小 1；这里 +1 补齐，与旧代码逐字等价
            topBlockY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX & 15, blockZ & 15) + 1;
        } else {
            // 与 Level#getHeight 的越界分支一致
            topBlockY = level.getSeaLevel() + 1;
        }

        // 虚空列（getFirstAvailable == minBuildHeight）同样落到兜底
        return topBlockY > level.getMinBuildHeight() ? topBlockY + 1.0D : fallbackY;
    }
}
