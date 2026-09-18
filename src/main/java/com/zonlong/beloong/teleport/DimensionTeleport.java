package com.zonlong.beloong.teleport;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 跨维度传送门面：把「维度 + 1:1 坐标（+ 可选兜底 Y）」解析成一次可执行的
 * {@link DimensionTransition}。
 * <p>
 * 本类是本模组传送能力的<b>公开入口</b>。当前只有天灾传送门一个使用方；将来若要长成
 * 通用跨维度传送 API，邻居类（维度对注册表 / 落点策略 / 事件）放同一个包，
 * 本类的签名不需要改。
 * <p>
 * <b>契约（调用方必须遵守）</b>：
 * <ol>
 *   <li><b>仅限服务端主线程</b>：解析依赖 {@code ServerChunkCache#getChunkNow}，非主线程恒返回
 *       {@code null}，会静默退化为"永远拿不到落点"；</li>
 *   <li><b>非阻塞</b>：不加载区块、不等待、不 join、不申领票据；</li>
 *   <li><b>返回 {@code null} 表示"本次不传送"</b>，不是错误。调用方自行决定重试还是放弃，
 *       <b>有界超时上限由调用方持有</b>（本类不持有任何时间状态）；</li>
 *   <li><b>不写任何状态</b>：冷却、票据、失败提示、日志全部是调用方职责；</li>
 *   <li>{@link Target#fallbackY()} 为 {@code null} ⇒ 只走精确落点（拿不到就不传）；
 *       非 {@code null} ⇒ 拿不到精确落点时用它。</li>
 * </ol>
 * <p>
 * <b>不做玩家判定</b>：本类接受任意 {@link Entity}。本模组"只允许玩家传送"的策略留在
 * 传送门方块里（{@code DisasterPortalBlock#getPortalDestination} 的 {@code instanceof ServerPlayer}）。
 */
public final class DimensionTeleport {

    private DimensionTeleport() {}

    /**
     * 解析并构造本次传送。
     *
     * @param entity 被传送的实体（用于取朝向）
     * @param target 落点目标（见 {@link TeleportTarget}：坐标+高度图 / 世界出生点 / 读配置的龙宫落点）
     * @param post   传送完成后的回调（由调用方提供，例如清零摔落距离与写冷却；
     *               本类刻意不认识任何具体业务键）
     * @return 可直接交给 {@code Entity#changeDimension} 的过渡；<b>{@code null} = 本 tick 不传送</b>
     */
    @Nullable
    public static DimensionTransition toTarget(Entity entity, TeleportTarget target,
                                               DimensionTransition.PostDimensionTransition post) {
        Vec3 pos = switch (target) {
            case TeleportTarget.At at -> {
                Double y = CoordinateLanding.resolve(at.level(), Mth.floor(at.x()), Mth.floor(at.z()));
                if (y == null) y = at.fallbackY();
                if (y == null) yield null;          // 拿不到精确落点且无兜底 ⇒ 本 tick 不传送
                yield new Vec3(at.x(), y, at.z());
            }
            // 世界出生点：XZ 与 Y 取出生点自身；**玩家**还会在出生点周围扩散一个安全落点
            // （复刻原版登录/无床出生的行为，见 SpawnSpreadResolver）。
            // 非玩家实体没有"扩散"这一说（原版对非玩家走 adjustSpawnLocation 的出生点分支），
            // 直接落在出生点坐标。
            case TeleportTarget.Spawn spawn ->
                    (entity instanceof ServerPlayer player && !spawn.level().isClientSide())
                            ? SpawnSpreadResolver.resolve(spawn.level(), player).getBottomCenter()
                            : new Vec3(spawn.centerX(), spawn.fixedY(), spawn.centerZ());
        };
        if (pos == null) return null;

        return new DimensionTransition(target.level(), pos,
                Vec3.ZERO,                       // 速度清零，等价旧 teleportTo(..., Set.of(), yRot, xRot)
                entity.getYRot(), entity.getXRot(),
                post);
    }

    /**
     * 便捷入口：直取<b>实体当前坐标</b>作为落点（{@link TeleportTarget.At} 的最常见用法）。
     *
     * @param fallbackY 高度图取不到时的兜底 Y；{@code null} ⇒ 拿不到就不传送
     */
    @Nullable
    public static DimensionTransition toCurrentCoords(Entity entity, ServerLevel level,
                                                      @Nullable Double fallbackY,
                                                      DimensionTransition.PostDimensionTransition post) {
        return toTarget(entity, new TeleportTarget.At(level, entity.getX(), entity.getZ(), fallbackY), post);
    }
}
