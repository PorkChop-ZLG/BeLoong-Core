package com.zonlong.beloong.compat.ironsspellbooks;

import com.zonlong.beloong.Config;
import com.zonlong.beloong.compat.ftbchunks.LoongPalaceProtectionPolicy;
import io.redspace.ironsspellbooks.api.events.SpellPreCastEvent;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.util.Utils;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;

/**
 * 龙宫维度内禁止铁魔法的<b>常规施法</b>（法术书/法杖/剑右键、快捷施法、卷轴），
 * 但<b>放行 {@code /cast} 命令</b>（整合包的「龙技能」经 mcfunction 走这条），
 * 并为未来的「铁魔法 × 龙之生存」联动模组预留放行。
 *
 * <h2>为什么拦得住</h2>
 * 铁魔法在 {@code AbstractSpell#attemptInitiateCast} 里发可取消的 {@link SpellPreCastEvent}，
 * 而那一句同时就是它的判定门（参考源 {@code AbstractSpell.java:281}）：
 * <pre>
 * 276  CastResult castResult = canBeCastedBy(...);          // 蓝量 / 冷却 / 学习状态
 * 281  if (!castResult.isSuccess() || !checkPreCastConditions(...)
 * 281          || EVENT_BUS.post(new SpellPreCastEvent(...)).isCanceled()) return false;
 * 288  initiateCast(...);                                    // 进入「正在施法」
 * </pre>
 * <b>关键在于顺序</b>：蓝量扣减在 {@code castSpell}（{@code :316-320}）、冷却在 {@code :328}、
 * 卷轴移除在施法<b>完成之后</b> ⇒ 在这里取消 = <b>不扣蓝、不进冷却、不消耗卷轴、客户端也不会进入施法状态</b>
 * （客户端状态由 {@code UpdateCastingStatePacket} / {@code CancelCastPacket} 下发）。
 * 该方法还有 {@code if (level.isClientSide) return false;}（{@code :268}）⇒ 本事件<b>只在服务端发</b>。
 *
 * <h2>判据：{@link CastSource} 黑名单（不是白名单）</h2>
 * 禁 {@code SPELLBOOK / SCROLL / SWORD}（覆盖 法术书/法杖右键、按键施法、快捷施法、剑、卷轴），
 * <b>其余一律放行</b> —— 包括 {@code COMMAND}（{@code /cast} 与 mcfunction ⇒ 龙技能）以及
 * <b>未来新增的任何 source</b>，这正是为联动模组预留的放行口。
 *
 * <h2>跨维度打断</h2>
 * 施法时间结束后由 {@code MagicManager#tick} <b>直接</b>调 {@code castSpell}（不再过 pre-cast 事件）
 * ⇒ 在龙宫外起手、进来后完成仍会生效 ⇒ 需要主动打断：监听 {@link EntityTravelToDimensionEvent}
 * （此时玩家仍在出发维度、目标维度也在事件里 ⇒ 可同时判「进」与「出」）并调
 * {@code Utils#serverSideCancelCast(ServerPlayer, boolean)}。
 * 其内部 {@code CancelCastPacket#cancelCast} <b>已自判 {@code isCasting()}</b> ⇒ 未施法时无副作用。
 * 传 {@code false} 表示不因此额外加冷却（这是被我们禁止/打断的施法，不该惩罚玩家）。
 * ⚠️ 铁魔法自己<b>不做</b>这件事（它的 {@code onChangeDimensions} 只拦召唤物跨维度、并清掉施法者的召唤物）。
 *
 * <h2>已知取舍</h2>
 * <ul>
 *   <li><b>怪物/NPC 施法不受约束</b>：{@code SpellPreCastEvent extends PlayerEvent}，非玩家施法不发此事件；
 *       要覆盖需 mixin 其施法路径。</li>
 *   <li>{@link CastSource} <b>无法区分</b>「法术书右键」与「快捷施法」（两者同源）⇒ 只能一起禁。</li>
 *   <li>「禁止施法」≠「禁止被法术破坏」：已存在的法术效果仍会作用（由既有环境保护门部分兜住）。</li>
 * </ul>
 *
 * <h2>接口约定（留给未来联动模组）</h2>
 * 代码级为龙技能施放铁魔法时请用 {@link CastSource#COMMAND}（或自注册一个新的 {@code CastSource}）——
 * 黑名单之外一律放行；若传 {@code SPELLBOOK/SCROLL/SWORD} 会被一并拦下。
 */
public final class LoongPalaceSpellBanHandler {

    /** 被禁的施法来源：玩家手持施法工具（法术书/法杖/剑）、快捷施法、卷轴。 */
    private static final Set<CastSource> BANNED_SOURCES =
            EnumSet.of(CastSource.SPELLBOOK, CastSource.SCROLL, CastSource.SWORD);

    @SubscribeEvent
    public void onSpellPreCast(SpellPreCastEvent event) {
        if (!Config.LoongPalaceProtection.environmentProtectionEnabled.get()) {
            return;
        }

        Player player = event.getEntity();

        if (!LoongPalaceProtectionPolicy.isLoongPalace(player.level())) {
            return;
        }

        // 黑名单之外（COMMAND / MOB / NONE / 未来新增的 source）一律放行 —— 龙技能与联动模组走这里。
        if (!BANNED_SOURCES.contains(event.getCastSource())) {
            return;
        }

        if (LoongPalaceProtectionPolicy.hasBypass(player)) {
            return;
        }

        event.setCanceled(true);
        notifyPlayer(player);
    }

    /**
     * 进出龙宫维度时打断正在进行的施法（见类文档「跨维度打断」）。
     */
    @SubscribeEvent
    public void onTravelToDimension(EntityTravelToDimensionEvent event) {
        if (!Config.LoongPalaceProtection.environmentProtectionEnabled.get()) {
            return;
        }

        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // 此时玩家仍在出发维度，目标维度在事件里 ⇒ 两端任一为龙宫都算「进出龙宫」。
        if (!involvesLoongPalace(player.level().dimension(), event.getDimension())) {
            return;
        }

        if (LoongPalaceProtectionPolicy.hasBypass(player)) {
            return; // 绕过权限的玩家本就被允许在龙宫施法，无需打断。
        }

        Utils.serverSideCancelCast(player, false);
    }

    private static boolean involvesLoongPalace(ResourceKey<Level> from, ResourceKey<Level> to) {
        return LoongPalaceProtectionPolicy.DIMENSION.equals(from)
                || LoongPalaceProtectionPolicy.DIMENSION.equals(to);
    }

    private static void notifyPlayer(Player player) {
        if (player instanceof FakePlayer) {
            // 假玩家没有真实客户端（与 LoongPalaceProtectionHandler#notifyPlayer 同一先例）。
            return;
        }
        // 颜色码写在语言值里（沿用 message.beloong.* 的既有习惯）。
        player.displayClientMessage(
                Component.translatable("message.beloong.loong_palace.spell_banned"), true);
    }
}
