package com.zonlong.beloong.ability;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.entity_effects.AbilityEntityEffect;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import com.zonlong.beloong.teleport.DimensionTeleport;
import com.zonlong.beloong.teleport.TeleportCooldown;
import com.zonlong.beloong.teleport.TeleportTarget;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import org.slf4j.Logger;

/**
 * 龙之生存技能「龙宫传送」：在主世界与龙宫之间往返。
 * <p>
 * <b>落点与传送流程全部交给 {@code teleport} 包</b>（与天灾传送门同一套 API）：
 * <ul>
 *   <li><b>去龙宫</b>：{@link TeleportTarget#toLoongPalace} —— 落点读
 *       {@code [dimension_transport.overworldToLoongPalace]} 的 {@code targetX/targetZ/fallbackY}
 *       （龙宫是纯虚空维度，高度图必然取不到，实际落点恒为 {@code fallbackY}）；
 *       ⚠️ 该落点在<b>空中</b>（默认 {@code (0.5, 65.5, 0.5)}）—— <b>这是设计</b>：入口本就要求玩家飞行
 *       （与序章"学习飞行"的教学目标一致，见 {@code docs/龙宫维度总设计.md}）。
 *       <b>它不是</b>"玩家初次创建存档的出生点"：后者由 <b>LockDown</b> 模组加载的固定地图提供，在实地上
 *       ⇒ 需要"把人安全送回龙宫地面"时请用 {@link TeleportTarget.Spawn}（取该维度出生点自身）；</li>
 *   <li><b>回主世界</b>：{@link TeleportTarget.Spawn} —— <b>恒为世界出生点</b>，
 *       不再优先玩家重生点（床）；</li>
 *   <li><b>冷却</b>：{@link TeleportCooldown}（与天灾门、龙宫 Y&lt;0 兜底共用同一份 NBT 冷却）。</li>
 * </ul>
 * 本类只负责"按当前维度选方向、做冷却检查、给出玩家提示"。
 * <p>
 * ⚠️ <b>2026-10-14 说明（避免误读；本条曾被我误删为"错误结论"，现恢复并写清）</b>：
 * 龙宫侧落点 {@code (targetX, ?, targetZ)} 处没有方块 ⇒ 总是走 {@code fallbackY}，玩家落在空中；
 * 可站立面 {@code x[-54,8] y[68,77] z[-312,-272]} 与默认的 {@code z = 0.5} 完全不相交。
 * <b>这不是笔误，是设计</b>（见 {@code docs/龙宫维度总设计.md}："所有落点都没有地面可站，玩家必须靠飞行
 * —— 这与序章『学习飞行』的教学目标一致"；以及
 * {@code 2026-09-16-loong-palace-teleport-api-design.md} §7："龙宫是 flat + layers: [] 纯虚空维度…"）。
 * <p>
 * ⚠️ 但要分清<b>两个不同的点</b>，否则会得出相反结论：
 * <ul>
 *   <li><b>本技能/门的配置落点</b>：{@code (targetX, fallbackY, targetZ)} —— <b>空中</b>，玩家靠飞行；</li>
 *   <li><b>玩家初次创建存档的出生点</b>：由 <b>LockDown</b> 模组加载的固定地图提供 —— <b>实地</b>。</li>
 * </ul>
 * 需要"把人安全送回龙宫地面"（例如剧情把玩家送回来）时，用 {@link TeleportTarget.Spawn}。
 * 龙宫侧<b>门</b>的精确落点仍待定（见 API 设计 §7），那是门那一轮的事。
 */
public record TpLoongPalaceEffect() implements AbilityEntityEffect {

    private static final Logger LOGGER = LogUtils.getLogger();

    // 龙宫维度常量收口在 teleport/TeleportTarget.LOONG_PALACE_LEVEL（原先此处各写一份）

    public static final MapCodec<TpLoongPalaceEffect> CODEC = MapCodec.unit(new TpLoongPalaceEffect());

    @Override
    public void apply(final ServerPlayer dragon, final DragonAbilityInstance ability, final Entity target) {
        if (!(target instanceof ServerPlayer player)) {
            return;
        }

        // 冷却与天灾门共用同一份 NBT：被拦下时给出 actionbar 反馈，
        // 否则玩家只会看到"技能按了没反应"（技能本身的数据冷却较短，可能落在传送冷却之中）。
        if (TeleportCooldown.isOnCooldown(player)) {
            player.displayClientMessage(
                    Component.translatable("message.beloong.tp_loong_palace.on_cooldown"), true);
            return;
        }

        ResourceKey<Level> currentDim = target.level().dimension();

        TeleportTarget destination;
        if (Level.OVERWORLD.equals(currentDim)) {
            // 主世界 → 龙宫
            ServerLevel loongPalace = player.server.getLevel(TeleportTarget.LOONG_PALACE_LEVEL);
            if (loongPalace == null) {
                LOGGER.warn("[BeLoongCore] Target dimension not found: {}", TeleportTarget.LOONG_PALACE_LEVEL.location());
                player.sendSystemMessage(Component.translatable(
                        "message.beloong.tp_loong_palace.dimension_not_found",
                        TeleportTarget.LOONG_PALACE_LEVEL.location().toString()));
                return;
            }
            destination = TeleportTarget.toLoongPalace(loongPalace);
        } else if (TeleportTarget.LOONG_PALACE_LEVEL.equals(currentDim)) {
            // 龙宫 → 主世界（恒为世界出生点）
            ServerLevel overworld = player.server.getLevel(Level.OVERWORLD);
            if (overworld == null) {
                LOGGER.warn("[BeLoongCore] Overworld not found for teleport");
                player.sendSystemMessage(Component.translatable(
                        "message.beloong.tp_loong_palace.dimension_not_found",
                        Level.OVERWORLD.location().toString()));
                return;
            }
            destination = new TeleportTarget.Spawn(overworld);
        } else {
            // 既不在主世界也不在龙宫：数据包的 usage_blocked 已挡住，这里只做兜底不放行
            return;
        }

        // 骑乘时先下车（与既有行为一致；传送门那边是原版范式的必然要求）
        if (player.isPassenger()) {
            player.stopRiding();
        }

        DimensionTransition transition = DimensionTeleport.toTarget(player, destination, postTeleport());
        if (transition == null) {
            // 唯一会返回 null 的情形：At 目标既拿不到高度图、又没有兜底 Y。
            // 龙宫落点的兜底来自配置（默认 65.0），配置被改成极端值时才可能触发。
            LOGGER.warn("[BeLoongCore] teleport to {} is not possible right now (no landing point)",
                    TeleportTarget.LOONG_PALACE_LEVEL.location());
            return;
        }

        player.changeDimension(transition);
        LOGGER.debug("[BeLoongCore] {} teleported {} -> {}",
                player.getName().getString(), currentDim.location(), destination.level().dimension().location());
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

    @Override
    public MapCodec<? extends AbilityEntityEffect> entityCodec() {
        return CODEC;
    }
}
