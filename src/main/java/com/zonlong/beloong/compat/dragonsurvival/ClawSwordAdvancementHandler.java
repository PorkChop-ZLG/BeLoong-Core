package com.zonlong.beloong.compat.dragonsurvival;

import by.dragonsurvivalteam.dragonsurvival.registry.attachments.ClawInventoryData;
import com.zonlong.beloong.registry.ModCriteria;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

/**
 * 爪牙槽教学进度的判据触发点。
 *
 * <p>龙之生存的爪牙槽机制会在<b>两条路径</b>上把爪牙槽里的剑临时换入主手：</p>
 * <ol>
 *   <li>玩家攻击 —— {@code PlayerStartMixin} 在 {@code Player#attack} 的 HEAD 处调用
 *       {@code swapStart}，RETURN 处调用 {@code swapFinish}；</li>
 *   <li>龙技能 —— {@code DamageEffect}（{@code use_claw = true}）在 {@code target.hurt} 前
 *       换入、之后换回。</li>
 * </ol>
 *
 * <p><b>为什么需要两个事件来做「换手」判定</b>：换手在<b>单次方法调用内闭合</b>，tick 末尾读
 * {@code switchedTool} 永远是 false；而 {@code LivingEquipmentChangeEvent} 靠比对上一 tick 的
 * 装备快照，同样 diff 不出这种往返。两条路径里，{@code LivingIncomingDamageEvent} 的触发点位于
 * {@code LivingEntity#hurt} 内部，<b>必然处于换手窗口内</b>，因此它是主力；{@code AttackEntityEvent}
 * 则是「换手瞬间」的最近似表达，额外覆盖<b>打空</b>的情形。两者在一次命中里会各触发一次判据，
 * 但授予是幂等的、且进度完成后监听器即被注销，重复触发无害。</p>
 *
 * <p>所有判定都读 DS {@code ClawInventoryData} 的 public 字段，<b>不需要任何 mixin</b>。</p>
 */
public class ClawSwordAdvancementHandler {

    /**
     * 该玩家此刻是否「爪牙槽中的剑正被换在主手」。
     *
     * <p>只认 {@code SWORD} 槽：爪牙槽还有镐/斧/锹三个槽，它们与本教学进度无关。</p>
     *
     * @param player 待判定的玩家
     * @return 换手正在进行且换的是剑
     */
    private static boolean hasClawSwordSwappedIn(ServerPlayer player) {
        ClawInventoryData data = ClawInventoryData.getData(player);
        return data.switchedTool
                && data.switchedToolSlot == ClawInventoryData.Slot.SWORD.ordinal();
    }

    /**
     * 玩家攻击时判定换手。
     *
     * <p>DS 的 HEAD 注入先于 {@code Player#attack} 的方法体执行，所以这里读到
     * {@code switchedTool == true} 就说明剑刚被换入主手。这一路覆盖<b>打空</b>——
     * 攻击未命中时不会走到下面的伤害事件。</p>
     *
     * <p>过滤 {@link ServerPlayer} 是为了排掉客户端的预测执行（客户端侧是 {@code LocalPlayer}）。</p>
     */
    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && hasClawSwordSwappedIn(player)) {
            ModCriteria.CLAW_SWORD_SWAP.get().trigger(player);
        }
    }

    /**
     * 造成伤害时判定换手——覆盖攻击与龙技能两条路径。
     *
     * <p><b>注意检的是攻击者、不是受害者</b>：{@code ClawInventoryData} 挂在玩家身上，
     * 而本事件的主体是挨打的一方，换手状态要去 {@code getSource().getEntity()} 上读。</p>
     */
    @SubscribeEvent
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer attacker
                && hasClawSwordSwappedIn(attacker)) {
            ModCriteria.CLAW_SWORD_SWAP.get().trigger(attacker);
        }
    }

    /**
     * 生物死亡时判定「爪剑击杀」。
     *
     * <p>条件与 {@link #onIncomingDamage} 完全相同——刻意<b>不</b>限制伤害类型，因为带
     * {@code use_claw} 的龙技能击杀同样属于「用爪中的剑击杀」，教学意义一致。</p>
     *
     * <p>顺序保证：本事件由 {@code LivingEntity#die} 触发，而 {@code die} 是在
     * {@code LivingEntity#hurt} 内部（{@code actuallyHurt} 之后）调用的，
     * 晚于 {@link #onIncomingDamage} 所在的同一方法前段。因此同一伤害实例内
     * <b>换手判据必然先于击杀判据</b>，父/子进度不会断链，也就不需要任何门控代码。</p>
     */
    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer attacker
                && hasClawSwordSwappedIn(attacker)) {
            ModCriteria.CLAW_SWORD_KILL.get().trigger(attacker);
        }
    }
}
