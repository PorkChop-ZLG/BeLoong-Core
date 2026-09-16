package com.zonlong.beloong.transport;

import com.mojang.logging.LogUtils;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.teleport.DimensionTeleport;
import com.zonlong.beloong.teleport.TeleportCooldown;
import com.zonlong.beloong.teleport.TeleportTarget;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 龙宫传送的<b>兜底安全网</b>：玩家在龙宫掉到触发 Y 以下时，送返主世界出生点。
 * <p>
 * 这是 Y 阈值轮询实现（每 {@code checkIntervalTicks} 检查一次，非事件驱动）。
 * <p>
 * <b>2026-09-16 变更</b>：
 * <ul>
 *   <li>原先还有"主世界飞到 Y &gt; 8848 → 龙宫"的反方向，已连同其 6 个配置项一并删除
 *       ——该条件在主世界恒为假（{@code maxBuildHeight = 320}），属死配置；</li>
 *   <li>落点与传送流程改走 {@code teleport} 包（与技能、天灾门同一套 API），
 *       落点策略为 {@link TeleportTarget.Spawn}（恒为世界出生点）；</li>
 *   <li>冷却由本类的静态 {@code Map} 改为 {@link TeleportCooldown}（NBT，与技能/天灾门共用）。
 *       原先的 {@code cooldownTicks} 配置键随之废弃。</li>
 * </ul>
 * 本类只负责"多久查一次 Y、什么条件下触发"，传送本身交给 API。
 */
public class DimensionTransportHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 龙宫维度 ID（与技能 {@code TpLoongPalaceEffect} 里那个常量同源）。 */
    private static final ResourceLocation LOONG_PALACE_ID =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "loong_palace");

    /** 每个玩家的检查间隔计数器 */
    private static final Map<UUID, Integer> TICK_COUNTERS = new HashMap<>();

    public DimensionTransportHandler() {}

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        // 仅服务端处理
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // 死亡或已移除的玩家跳过
        if (!player.isAlive() || player.isRemoved()) {
            return;
        }

        // 冷却检查：与技能、天灾门共用同一份 NBT 冷却
        if (TeleportCooldown.isOnCooldown(player)) {
            return;
        }

        // 间隔检查
        UUID uuid = player.getUUID();
        int interval = Config.DimensionTransport.checkIntervalTicks.get();
        int counter = TICK_COUNTERS.getOrDefault(uuid, 0) + 1;
        if (counter < interval) {
            TICK_COUNTERS.put(uuid, counter);
            return;
        }
        TICK_COUNTERS.put(uuid, 0);

        // 检查方向：龙宫 → 主世界（主世界 → 龙宫那条已在 2026-09-16 删除）
        tryTransportToOverworldSpawn(player,
                player.level().dimension().location().toString(),
                LOONG_PALACE_ID.toString(),
                Config.DimensionTransport.lpToOw_enabled.get(),
                Config.DimensionTransport.lpToOw_triggerY.get());
    }

    // ServerLevel 实现 AutoCloseable；此处仅作世界引用，不可关闭（close() 会关闭区块源），抑制 resource 检查
    @SuppressWarnings("resource")
    private void tryTransportToOverworldSpawn(ServerPlayer player,
            String currentDim, String sourceDim,
            boolean enabled, int triggerY) {
        if (!enabled) return;
        if (!currentDim.equals(sourceDim)) return;
        if (player.getY() >= triggerY) return;

        ServerLevel overworld = player.server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            LOGGER.warn("[BeLoongCore] Overworld not found for void transport");
            return;
        }

        if (player.isPassenger()) {
            player.stopRiding();
        }

        // 落点与传送交给 API：Spawn 直取世界出生点（XZ 中心对齐 + Y 取出生点自身）。
        // 本路径不需要高度图，也不需要申领票据：落点区块的加载由玩家的 TicketType.PLAYER 票据长期保证；
        // changeDimension 另带一张 TicketType.POST_TELEPORT，寿命只有 5 tick（TicketType.java:18），
        // 只覆盖传送瞬间。
        DimensionTransition transition = DimensionTeleport.toTarget(player,
                new TeleportTarget.Spawn(overworld), postTeleport());
        if (transition == null) {
            // Spawn 目标不会返回 null（不依赖高度图），保留分支仅为防御
            return;
        }

        player.changeDimension(transition);
        TICK_COUNTERS.remove(player.getUUID());

        LOGGER.debug("[BeLoongCore] {} transported from {} to the overworld spawn ({}, {}, {})",
                player.getName().getString(), sourceDim,
                transition.pos().x, transition.pos().y, transition.pos().z);
    }

    /** 传送完成后的收尾：清零摔落距离 + 记一次共用冷却。 */
    private static DimensionTransition.PostDimensionTransition postTeleport() {
        return entity -> {
            entity.fallDistance = 0;
            if (entity instanceof ServerPlayer player) {
                TeleportCooldown.mark(player);
            }
        };
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        TICK_COUNTERS.remove(event.getEntity().getUUID());
    }
}
