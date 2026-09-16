package com.zonlong.beloong.teleport;

import net.minecraft.server.level.ServerLevel;
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
     * 传送目标描述。
     *
     * @param level     目标维度（服务端）
     * @param x         目标 X（保留玩家原值，1:1）
     * @param z         目标 Z（保留玩家原值，1:1）
     * @param fallbackY 精确落点拿不到时使用的脚底 Y；{@code null} 表示"不接受兜底、拿不到就不传"
     */
    public record Target(ServerLevel level, double x, double z, @Nullable Double fallbackY) {}

    /**
     * 解析并构造本次传送。
     *
     * @param entity 被传送的实体（用于取朝向）
     * @param target 目标描述
     * @param post   传送完成后的回调（由调用方提供，例如清零摔落距离与写冷却；
     *               本类刻意不认识任何具体业务键）
     * @return 可直接交给 {@code Entity#changeDimension} 的过渡；<b>{@code null} = 本 tick 不传送</b>
     */
    @Nullable
    public static DimensionTransition toTarget(Entity entity, Target target,
                                               DimensionTransition.PostDimensionTransition post) {
        Double y = CoordinateLanding.resolve(target.level(), Mth.floor(target.x()), Mth.floor(target.z()));
        if (y == null) y = target.fallbackY();
        if (y == null) return null;

        return new DimensionTransition(target.level(),
                new Vec3(target.x(), y, target.z()),
                Vec3.ZERO,                       // 速度清零，等价旧 teleportTo(..., Set.of(), yRot, xRot)
                entity.getYRot(), entity.getXRot(),
                post);
    }
}
