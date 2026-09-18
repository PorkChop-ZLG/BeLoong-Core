package com.zonlong.beloong.registry;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组的音效注册中心。
 * <p>
 * 1.21.1 的 {@link SoundEvent} 是<b>注册表对象</b>，必须用
 * {@link SoundEvent#createVariableRangeEvent(ResourceLocation)} 之类的工厂创建
 * （构造器不可见）；注册路径同时就是 {@code sounds.json} 里的<b>键名</b>。
 * <p>
 * 键名规则：{@code <类型>.<命名空间>.<方块 ID>.<用途>} ⇒ 例如
 * {@code block.beloong.loong_palace_portal.ambient}
 * （与天境的 {@code block.aether_portal.ambient} 同构——天境的 modid 即 {@code aether}）。
 * <p>
 * 音频文件在 {@code assets/beloong/sounds/portal/}，由 {@code sounds.json} 引用。
 * 三条门音效取自<b>天境</b>（已获授权）。
 */
public final class ModSounds {

    /** 音效延迟注册器 */
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, BeLoongCore.MODID);

    // ==================== 龙宫传送门 ====================

    /** 门内环境音（由 {@code LoongPalacePortalBlock#animateTick} 低频播放）。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> LOONG_PALACE_PORTAL_AMBIENT =
            register("block.beloong.loong_palace_portal.ambient");

    /** 门被点亮时播放。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> LOONG_PALACE_PORTAL_TRIGGER =
            register("block.beloong.loong_palace_portal.trigger");

    /** 换维度瞬间播放。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> LOONG_PALACE_PORTAL_TRAVEL =
            register("block.beloong.loong_palace_portal.travel");

    private ModSounds() {}

    /**
     * 注册一个可变范围音效（范围由 {@code sounds.json} 的 {@code attenuation_distance} 控制）。
     *
     * @param path 注册路径，同时也是 {@code sounds.json} 的键名
     */
    private static DeferredHolder<SoundEvent, SoundEvent> register(String path) {
        return SOUNDS.register(path,
                () -> SoundEvent.createVariableRangeEvent(
                        ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, path)));
    }

    /** 将音效注册到 Mod 事件总线。 */
    public static void register(IEventBus bus) {
        SOUNDS.register(bus);
    }
}
