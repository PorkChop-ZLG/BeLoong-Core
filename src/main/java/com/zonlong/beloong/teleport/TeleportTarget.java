package com.zonlong.beloong.teleport;

import com.zonlong.beloong.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * 传送<b>落点目标</b>的类型建模：把"落点怎么算"从调用方手里收进类型。
 * <p>
 * 三种目标的 Y 策略各不相同，这是本接口存在的唯一理由：
 * <ul>
 *   <li>{@link At} —— 坐标固定，<b>Y 走高度图</b>（拿不到时用 {@code fallbackY}）；</li>
 *   <li>{@link Spawn} —— 世界出生点，<b>XZ 与 Y 全部直取出生点自身</b>，不查高度图；</li>
 *   <li>{@link #toLoongPalace} —— 读配置的 {@code At} 特例（其他世界 → 龙宫的落点）。</li>
 * </ul>
 * 解析与构造由 {@link DimensionTeleport#toTarget} 完成；本接口只描述"目标是什么"，
 * <b>不持有任何状态、不申领票据、不做维度加载</b>。
 * <p>
 * 与 {@link CoordinateLanding} 的分工：本接口是"目标描述"，{@code CoordinateLanding} 是
 * "给定坐标求 Y" 的实现细节；调用方只应依赖本接口与 {@link DimensionTeleport}。
 */
public sealed interface TeleportTarget {

    /** 目标维度（服务端）。 */
    ServerLevel level();

    /**
     * ① 指定坐标：X/Z 原样使用，<b>Y 走高度图</b>。
     *
     * @param fallbackY 高度图取不到时使用的脚底 Y；<b>为 {@code null} 表示"不接受兜底"</b>
     *                  ⇒ 拿不到精确落点就返回 {@code null}（本 tick 不传送），由调用方决定重试或放弃
     */
    record At(ServerLevel level, double x, double z, @Nullable Double fallbackY) implements TeleportTarget {}

    /**
     * ② 世界出生点：<b>无配置</b>，落点恒为 {@code level.getSharedSpawnPos()}。
     * <p>
     * XZ 取出生点中心（{@code +0.5} 对齐），Y 取出生点自身的 Y —— <b>刻意不查高度图</b>：
     * 出生点是维度自身的数据，与"那一列现在有什么方块"无关（这是需求明确的口径）。
     */
    record Spawn(ServerLevel level) implements TeleportTarget {

        /** 出生点方块坐标（每次调用实时读取，不缓存）。 */
        public BlockPos pos() {
            return level.getSharedSpawnPos();
        }

        public double centerX() {
            return pos().getX() + 0.5D;
        }

        public double centerZ() {
            return pos().getZ() + 0.5D;
        }

        /** 落点 Y：直取出生点自身的 Y。 */
        public double fixedY() {
            return pos().getY();
        }
    }

    /**
     * 龙宫维度（编译期常量，不配置）。
     * <p>
     * <b>为什么放在本接口里</b>：龙宫维度 ID 曾被复制到多处（技能、Y&lt;0 兜底、以及后来的传送门方块）
     * 各写一份；本包本就因 {@link #toLoongPalace} 而"认识龙宫"，故把常量收口在这里，
     * 所有调用方（`ability.TpLoongPalaceEffect`、`transport.DimensionTransportHandler`、
     * `block.LoongPalacePortal*`）统一引用它，避免再出现第四份。
     */
    ResourceKey<Level> LOONG_PALACE_LEVEL = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("beloong", "loong_palace"));

    /**
     * ③ 其他世界 → 龙宫的落点：读
     * {@code [dimension_transport.overworldToLoongPalace]} 的 {@code targetX} / {@code targetZ} /
     * {@code fallbackY}。
     * <p>
     * 这是本包内<b>唯一读配置</b>的目标，也是"龙宫入口落点可调"这一需求的落点。
     * 龙宫维度 ID <b>不在配置里</b>（见 {@link #LOONG_PALACE_LEVEL}）。
     *
     * @param loongPalace 龙宫维度（由调用方用 {@link #LOONG_PALACE_LEVEL} 解析后给出）
     */
    static TeleportTarget toLoongPalace(ServerLevel loongPalace) {
        return new At(loongPalace,
                Config.DimensionTransport.loongPalace_targetX.get(),
                Config.DimensionTransport.loongPalace_targetZ.get(),
                Config.DimensionTransport.loongPalace_fallbackY.get());
    }
}
