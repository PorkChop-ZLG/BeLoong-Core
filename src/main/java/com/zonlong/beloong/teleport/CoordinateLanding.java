package com.zonlong.beloong.teleport;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

/**
 * 「坐标固定」的落点解析：1:1 坐标 + 高度图 + 非阻塞票据预热。
 * <p>
 * 供<b>需要精确落点、且可以等一 tick</b>的传送使用（天灾传送门双向）。判定是纯读操作，
 * <b>不加载区块、不等待、不 join</b>；拿不到落脚点时以 {@code null} 表达，由调用方决定
 * "下一 tick 重试"还是"用兜底 Y 直接走"。
 * <p>
 * <b>与 {@link com.zonlong.beloong.util.LandingY} 的分工（不要合并）</b>：
 * <ul>
 *   <li>{@code LandingY}：落点坐标固定、<b>不需要</b>精确 Y（龙宫 ↔ 主世界的
 *       {@code ServerPlayer#teleportTo}），拿不到时返回调用方给的兜底值——<b>一定给一个值</b>；</li>
 *   <li>本类：必须精确落点、<b>可以等</b>，拿不到时返回 {@code null}——由调用方决定等还是兜底。</li>
 * </ul>
 * 两者的差异集中在"拿不到值时怎么办"：前者是"精度可以让步"，后者是"落点不能猜"。
 * 合并会丢掉这个区别（详见 {@code memory/learned-patterns.md} 的逐分支对照表）。
 * <p>
 * <b>仅限服务端主线程调用</b>：{@code ServerChunkCache#getChunkNow} 在非主线程恒返回 {@code null}，
 * 会静默退化为"永远拿不到落点"——表现为传送永不成功，而不是报错。
 * <p>
 * <b>禁止</b>改用 {@code Level#getChunk} / {@code Level#getHeight}（阻塞版）：主线程一旦在
 * {@code managedBlock} 上等待一个永不完成的生成 future，就会永久静默卡死（无 crash report）。
 * 见 {@code docs/天灾传送门主线程死锁-根因与修复复盘.md}。
 */
public final class CoordinateLanding {

    /**
     * 落点预热票据的半径：0 = 只加载落点所在的那一个区块
     * （原版下界门落点用 3；我们只需要 1 个区块的高度图）。
     */
    private static final int TICKET_RADIUS = 0;

    /** 方块坐标的合法范围，与 {@code Level#getHeight} 的越界分支同源。 */
    private static final int MAX_BLOCK_COORDINATE = 30000000;

    private CoordinateLanding() {}

    /**
     * 解析 1:1 坐标处的落脚 Y（脚底精确 Y）。
     * <p>
     * 判定顺序（<b>顺序本身是行为的一部分</b>，不要调换）：
     * <ol>
     *   <li>落点区块不在内存 → {@code null}（"还没好"，应重试）；</li>
     *   <li>坐标越界（{@code |x|} 或 {@code |z|} ≥ 3000 万）→ {@code null}；</li>
     *   <li>取高度图：{@code MOTION_BLOCKING} 含流体，因此<b>海洋列得到的是水面</b>
     *       ——本模组刻意"水面就放水面"，不做接地搜索；</li>
     *   <li>列为虚空（高度图取不到任何方块）→ {@code null}。</li>
     * </ol>
     *
     * @param target 目标维度（服务端）
     * @param blockX 方块 X（调用方已 {@code floor}）
     * @param blockZ 方块 Z（调用方已 {@code floor}）
     * @return 脚底精确 Y；拿不到则 {@code null}
     */
    @Nullable
    public static Double resolve(ServerLevel target, int blockX, int blockZ) {
        // 非阻塞探测：命中则直接用内存中的区块读高度图；未命中返回 null，绝不触发加载或等待
        LevelChunk chunk = target.getChunkSource().getChunkNow(blockX >> 4, blockZ >> 4);
        if (chunk == null) return null;

        // 越界与"未加载"的优先级与改动前的下行实现一致：先看区块，再看坐标范围
        if (blockX < -MAX_BLOCK_COORDINATE || blockZ < -MAX_BLOCK_COORDINATE
                || blockX >= MAX_BLOCK_COORDINATE || blockZ >= MAX_BLOCK_COORDINATE) {
            return null;
        }

        // ChunkAccess#getHeight 比 Level#getHeight 小 1；这里 +1 补齐，再 +1 得到"地表上方 1 格"
        int topBlockY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX & 15, blockZ & 15) + 1;
        // 虚空列：高度图找不到方块时 getFirstAvailable == minBuildHeight，故补齐后不会大于它
        return topBlockY > target.getMinBuildHeight() ? topBlockY + 1.0D : null;
    }

    /**
     * 非阻塞预热落点区块：已在内存则什么都不做，否则申领一张原版传送门区域票据
     * （{@link TicketType#PORTAL}，寿命 300 tick、重复申领幂等并刷新寿命），
     * 由区块系统在后台完成生成/加载。
     * <p>
     * 本方法不等待、不 join、不检查返回值，绝不会阻塞主线程。刷新职责在调用方：
     * 门方块在 {@code entityInside}（每 tick）与"落点未就绪"分支里各调一次。
     *
     * @param anchor 票据的所有者，用碰撞箱所在方块位置（与改动前一致）
     */
    public static void requestChunk(ServerLevel target, int blockX, int blockZ, BlockPos anchor) {
        if (blockX < -MAX_BLOCK_COORDINATE || blockZ < -MAX_BLOCK_COORDINATE
                || blockX >= MAX_BLOCK_COORDINATE || blockZ >= MAX_BLOCK_COORDINATE) {
            return;
        }
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        // getChunkNow 只查内存/缓存，不触发加载（ServerChunkCache#getChunkNow 不阻塞）
        if (target.getChunkSource().getChunkNow(chunkX, chunkZ) != null) return;
        target.getChunkSource().addRegionTicket(TicketType.PORTAL,
                new ChunkPos(chunkX, chunkZ), TICKET_RADIUS, anchor);
    }
}
