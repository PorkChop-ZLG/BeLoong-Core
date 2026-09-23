package com.zonlong.beloong.registry;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.common.handlers.magic.ManaHandler;
import by.dragonsurvivalteam.dragonsurvival.network.syncing.SyncMana;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 处理魔力流逝（mana_loss）效果的每 tick 法力扣除。
 *
 * <p>在 {@link PlayerTickEvent.Post} 中检查玩家是否拥有
 * {@link ModMobEffects#MANA_LOSS} 效果，若有则调用 Dragon Survival 的
 * {@link ManaHandler#consumeMana} 扣除法力。</p>
 *
 * <p>扣除量：{@code 0.025 × (amplifier + 1)} 每 tick。</p>
 *
 * <h3>⚠️ 本效果只扣法力，绝不消耗玩家经验</h3>
 * DS 的 {@code ManaHandler.consumeMana:113} 有一个「法力不足 → 扣经验」的分支
 * （{@code pureMana < manaCost} 时 {@code giveExperiencePoints(convertMana(...))}）。
 * 由于法力耗尽后每 tick 的请求量都会大于可用量，那个分支会被持续命中，
 * 表现为约 1 点经验/tick（≈20/秒）；而 2.0.70 起该转换由玩家自己的
 * {@code usesExperienceForMana} 开关控制、默认开启，且数据包级的关闭途径已被 DS 删除。
 *
 * <p>因此这里把<b>请求量夹到可用法力以内</b>（见 {@link #onPlayerTick}），
 * 使扣经验的条件在<b>结构上不可能</b>成立。顺带的效果是：连 DS 每 tick 回的那点法力
 * 也会被一并扣掉，法力条稳定压在 0 —— 这才是「魔力流逝」应有的语义。</p>
 */
public class ManaLossHandler {

    /** 每级效果等级扣除的法力量（tick） */
    private static final float BASE_DRAIN = 0.025f;

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();

        if (player.level().isClientSide()) {
            return;
        }

        if (!player.isAlive()) {
            return;
        }

        if (!DragonStateProvider.isDragon(player)) {
            return;
        }

        MobEffectInstance instance = player.getEffect(ModMobEffects.MANA_LOSS);
        if (instance == null) {
            return;
        }

        float deduction = BASE_DRAIN * (instance.getAmplifier() + 1);

        // 把请求量夹到「可用法力」以内：request <= pureMana 使 consumeMana:113 的
        // `pureMana < manaCost` 恒为假，于是必然走 :118 的 adjustMana(-request)，
        // 而不是 :114-116 的扣经验路径。这不是「判断后跳过」，而是让扣经验的条件
        // 结构上不可能成立。
        //
        // 边界：getAvailableMana() 已 clamp 到 >= 0（MagicData.java:94），不会出现负请求；
        // available == 0 时 request == 0，DS 会在 consumeMana:97 的 `manaCost == 0` 处
        // 直接早退（那是它原有的行为，非本处特判）。创造模式的 hasInfiniteMaterials()
        // 与 SOURCE_OF_MAGIC 效果的早退同样在 :97，照旧生效。
        MagicData magic = MagicData.getData(player);
        float request = Math.min(deduction, magic.getAvailableMana());
        ManaHandler.consumeMana(player, request);

        if (player.tickCount % 5 == 0) {
            PacketDistributor.sendToPlayer((ServerPlayer) player,
                    new SyncMana(magic.getCurrentMana(), true));
        }
    }
}
