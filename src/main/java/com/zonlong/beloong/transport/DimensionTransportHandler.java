package com.zonlong.beloong.transport;

import com.mojang.logging.LogUtils;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.teleport.DimensionTeleport;
import com.zonlong.beloong.teleport.TeleportTarget;
import net.minecraft.resources.ResourceLocation;
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
 * 龙宫传送的<b>兜底安全网</b>：玩家在龙宫掉到 Y &lt; 0 以下时，送返主世界出生点。
 * <p>
 * <b>这是统一传送冷却的特例</b>：本路径<b>既不检查也不写</b>
 * {@link com.zonlong.beloong.teleport.TeleportCooldown}——
 * 它是"玩家掉出虚空"的抢救机制，不该被"刚用技能 / 刚从门出来"延迟，
 * 也不该占用那份统一冷却（否则掉一次虚空，3 秒内连技能都用不了）。
 * <p>
 * <b>2026-09-16 变更汇总</b>：
 * <ul>
 *   <li>原先还有"主世界飞到 Y &gt; 8848 → 龙宫"的反方向，已连同其配置一并删除
 *       （该条件在主世界恒为假，属死配置）；</li>
 *   <li>触发条件改为<b>硬编码</b>：恒启用、触发线固定 {@code Y < 0}（原先由
 *       {@code [dimension_transport.loongPalaceToOverworld]} 的 {@code enabled}/{@code triggerY} 控制）；</li>
 *   <li>落点与传送改走 {@code teleport} 包（与技能、天灾门同一套 API），
 *       落点策略为 {@link TeleportTarget.Spawn}（恒为世界出生点）；</li>
 *   <li>冷却由静态 {@code Map} → 统一 NBT → <b>最终完全移除</b>（本类是特例）。</li>
 * </ul>
 * 本类只负责"多久查一次 Y、低于 0 就送回"，传送本身交给 API。
 */
public class DimensionTransportHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 龙宫维度 ID（与技能 {@code TpLoongPalaceEffect} 里那个常量同源）。 */
    private static final ResourceLocation LOONG_PALACE_ID =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "loong_palace");

    /** 触发线（硬编码）：玩家在龙宫的 Y 低于此值即送回主世界。 */
    private static final int TRIGGER_Y = 0;

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

        // 间隔检查（唯一保留的配置项：多久查一次 Y）
        UUID uuid = player.getUUID();
        int interval = Config.DimensionTransport.checkIntervalTicks.get();
        int counter = TICK_COUNTERS.getOrDefault(uuid, 0) + 1;
        if (counter < interval) {
            TICK_COUNTERS.put(uuid, counter);
            return;
        }
        TICK_COUNTERS.put(uuid, 0);

        tryTransportToOverworldSpawn(player);
    }

    // ServerLevel 实现 AutoCloseable；此处仅作世界引用，不可关闭（close() 会关闭区块源），抑制 resource 检查
    @SuppressWarnings("resource")
    private void tryTransportToOverworldSpawn(ServerPlayer player) {
        if (!LOONG_PALACE_ID.equals(player.level().dimension().location())) return;
        if (player.getY() >= TRIGGER_Y) return;

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
                new TeleportTarget.Spawn(overworld), teleport -> teleport.fallDistance = 0);
        if (transition == null) {
            // Spawn 目标不会返回 null（不依赖高度图），保留分支仅为防御
            return;
        }

        player.changeDimension(transition);
        TICK_COUNTERS.remove(player.getUUID());

        LOGGER.debug("[BeLoongCore] {} fell out of {} and was returned to the overworld spawn ({}, {}, {})",
                player.getName().getString(), LOONG_PALACE_ID,
                transition.pos().x, transition.pos().y, transition.pos().z);
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        TICK_COUNTERS.remove(event.getEntity().getUUID());
    }
}
