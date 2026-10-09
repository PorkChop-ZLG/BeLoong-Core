package com.zonlong.beloong.registry;

import com.mojang.serialization.Codec;
import com.zonlong.beloong.BeLoongCore;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 化龙核心的数据附件（NeoForge {@link AttachmentType}）注册中心。
 *
 * <p>NeoForge 1.21 的附件是「挂在实体/方块实体/区块/等级上的、可选择序列化的强类型数据」，
 * 取代了 1.20 时代 {@code Capability} 的多数用法。注册表键是
 * {@link NeoForgeRegistries.Keys#ATTACHMENT_TYPES}（该注册表由
 * {@code NeoForgeRegistries} 静态创建，不需要 {@code NewRegistryEvent}）。</p>
 *
 * <h3>为什么斩杀冷却不用 DS 的能力冷却</h3>
 * DS 的 {@code DragonAbilityInstance#tickActions} 末尾硬编码了
 * 「被动能力只要 {@code activation.cooldown > 0}，每次被触发就立刻
 * {@code release()} 进满冷却」。斩杀需要的是「触发斩杀后才进冷却」，
 * 因此能力 JSON 不声明 {@code activation.cooldown}，冷却由本模组自行记账。
 *
 * @see ExecuteCooldown
 */
public final class ModAttachments {

    /** 附件类型延迟注册器。 */
    public static final DeferredRegister<AttachmentType<?>> REGISTRY =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, BeLoongCore.MODID);

    /**
     * 斩杀冷却票据：值为「可以再次触发斩杀」的<b>游戏刻</b>（{@code Level#getGameTime}）。
     * <p>
     * {@code 0} = 从未触发过，随时可用。存时间戳而不是倒计时，是为了不必每 tick 递减：
     * 判定只需一次整数比较，且天然在存档重载后仍然正确。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> EXECUTE_READY_AT =
            REGISTRY.register("execute_ready_at",
                    () -> AttachmentType.builder(() -> 0L)
                            .serialize(Codec.LONG)
                            .build());

    private ModAttachments() {}

    /** 将附件注册到 Mod 事件总线。 */
    public static void register(IEventBus bus) {
        REGISTRY.register(bus);
    }
}
