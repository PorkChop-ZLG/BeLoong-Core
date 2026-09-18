package com.zonlong.beloong.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 落点 Y 的<b>非阻塞</b>解析（仅服务端主线程）。
 * <p>
 * ⚠️ <b>2026-09-16 起本类没有调用方</b>：原先使用它的两处（龙宫 ↔ 主世界的技能传送、
 * 龙宫 Y&lt;0 兜底）都已改走 {@code teleport} 包——前者用
 * {@code TeleportTarget.toLoongPalace}（走 {@link com.zonlong.beloong.teleport.CoordinateLanding}），
 * 后者用 {@code TeleportTarget.Spawn}（不查高度图）。本类<b>刻意保留</b>，供将来
 * "落点坐标固定、但不介意 Y 精度、也不想引入等待状态"的场景使用。
 * <p>
 * 与 {@link com.zonlong.beloong.teleport.CoordinateLanding} 的分工见其类 javadoc；
 * 新代码若需要"精确落点、可以等一 tick"，请用 {@code CoordinateLanding}。
 * <p>
 * 供"目标坐标已固定"的传送使用：它们只需要一个安全的落脚 Y，
 * <b>不需要</b>为了拿高度图而同步加载目的地区块。
 * <p>
 * <b>与旧实现的关系</b>：公式逐分支等价，但<b>不是</b>逐字等价——旧实现在取高度图之前会
 * {@code getChunk(...)} 强制加载该区块，所以旧实现在"未加载"时走不到它的 {@code hasChunk == false} 分支；
 * 本类的两处差异都是有意的（设计 B-1）：
 * <ul>
 *   <li>区块未加载 → 返回 {@code fallbackY}（旧实现会先同步加载、拿到真实地表高度）；</li>
 *   <li>区块已加载但该列为虚空 → 也返回 {@code fallbackY}（旧实现的 {@code Level#getHeight}
 *       会返回 {@code minBuildHeight}，再加 1 得到 {@code minBuildHeight + 1}，
 *       人会被放在虚空上方自由落体）；</li>
 *   <li>坐标超出 ±3000 万 → 沿用 {@code Level#getHeight} 的 {@code seaLevel + 1} 分支语义
 *       （该分支要求 {@code getChunkNow} 命中一个边界外的区块，实际不可达）。</li>
 * </ul>
 * <p>
 * <b>为什么不能阻塞</b>：旧写法在主线程上同步等待目的地区块生成；一旦该区块的生成 future 永不完成
 * （实机发生过工作线程 {@code StackOverflowError} 导致 future 被永久挂起），主线程会在
 * {@code managedBlock} 上永久自旋，且不产生 crash report。
 * 详见 {@code docs/天灾传送门主线程死锁-根因与修复复盘.md}。
 * <p>
 * 这里<b>不</b>申领票据、不等待、不重试：{@code ServerPlayer#teleportTo(ServerLevel, ...)} 只自带一张
 * {@code TicketType.POST_TELEPORT} 票据，而它的寿命<b>只有 5 tick</b>（{@code TicketType.java:18}），
 * 落点区块的长期加载保证来自传送后玩家自身的 {@code TicketType.PLAYER} 票据（无寿命）。本类只负责
 * "能不能拿到精确 Y"。
 * <p>
 * <b>仅限服务端主线程调用</b>：{@code ServerChunkCache#getChunkNow} 在非主线程恒返回 {@code null}，
 * 会静默退化为兜底值；其 {@code currentlyLoading} 旁路（{@code ServerChunkCache.java:191}）在主线程
 * 不可观测（该字段只在派发到主线程的任务体内读写），即便返回在途区块，本类也只在同一 tick 内读高度图、
 * 不跨 tick 持有引用，因此不构成竞态（此点未做实机验证，仅为源码推理）。
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
            // ChunkAccess#getHeight 比 Level#getHeight 小 1；这里 +1 补齐，使公式与旧实现逐分支一致
            topBlockY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX & 15, blockZ & 15) + 1;
        } else {
            // 与 Level#getHeight 的越界分支一致
            topBlockY = level.getSeaLevel() + 1;
        }

        // 虚空列（getFirstAvailable == minBuildHeight）落到兜底：与旧实现的行为差异，见类 javadoc
        return topBlockY > level.getMinBuildHeight() ? topBlockY + 1.0D : fallbackY;
    }
}
