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
 *       {@code [dimension_transport.overworldToLoongPalace]} 的 {@code targetX/targetZ/fallbackY}。
 *       <b>该落点必定是实地</b>：龙宫的<b>实际地图</b>由 <b>LockDown</b> 模组加载（整合包预建地图），
 *       而这个落点就是<b>玩家初次创建存档时的出生点</b> ⇒ 玩家不会掉进虚空。
 *       （{@code fallbackY} 的含义只是"本模组的生成器不生成方块 ⇒ 给不出高度图"时的<b>显式兜底</b>，
 *       并不代表落点在空中 —— 那是把"生成器层不生成"误当成"世界里没有地面"。）；</li>
 *   <li><b>回主世界</b>：{@link TeleportTarget.Spawn} —— <b>恒为世界出生点</b>，
 *       不再优先玩家重生点（床）；</li>
 *   <li><b>冷却</b>：{@link TeleportCooldown}（与天灾门、龙宫 Y&lt;0 兜底共用同一份 NBT 冷却）。</li>
 * </ul>
 * 本类只负责"按当前维度选方向、做冷却检查、给出玩家提示"。
 * <p>
 * ⚠️ <b>2026-10-14（防误改，请读完再动）</b>：本节曾被两度改错。原因是把两件<b>不同的事实层</b>
 * 读成了同一件：
 * <ol>
 *   <li><b>生成器层</b>：本模组的群系是 {@code flat + layers: []} ⇒ 生成器<b>不放置任何方块</b>
 *       ⇒ 落点的 Y 拿不到高度图，必须显式给 —— 这才是 {@code fallbackY} 的来由
 *       （{@code docs/龙宫维度总设计.md} §1.2/§2.2 讲的是这一层）；</li>
 *   <li><b>实际地图层</b>：龙宫的<b>真正地图由 LockDown 复制模板进去</b>
 *       （{@code 2026-08-27-lockdown-template-migration-design.md:11} 反证：没有模板数据时进入才是虚空）
 *       ⇒ <b>世界里是有地面的</b>。</li>
 * </ol>
 * 历史上确实出现过"落点处无地面、进门坠落"的现象
 * （{@code 2026-09-16-loong-palace-portal-design.md:249} 实测记录，当时列为可用性缺陷），
 * 后经<b>改配置坐标 / 龙宫内建平台</b>解决 ⇒ <b>现在落点是实地</b>，玩家不会掉进虚空，
 * 也不需要"靠飞行才能进入"。<b>勿再据旧说法（或据"文档写过"）改本条。</b>
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
