package com.zonlong.beloong.mixin.dragonsurvival;

import by.dragonsurvivalteam.dragonsurvival.client.render.entity.dragon.DragonArmorRenderLayer;
import com.zonlong.beloong.Config;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 隐藏龙之生存中未专门绘制贴图的盔甲，不再回退到通用默认盔甲。
 *
 * <p>当 {@link Config#HIDE_UNDEVELOPED_DRAGON_ARMOR} 开启时，如果 DS 只能生成
 * {@code default/uncommon/rare/epic_<slot>.png} 这类通用回退贴图，则返回一个不存在的
 * 贴图位置，让 {@code renderArmorSlot} 的 {@code hasResource} 检查跳过该槽位。</p>
 */
@Mixin(value = DragonArmorRenderLayer.class, remap = false)
public abstract class DragonArmorRenderLayerMixin {

    @Inject(
            method = "generateArmorTextureResourceLocation",
            at = @At("RETURN"),
            cancellable = true,
            remap = false
    )
    private static void beloong$hideGenericArmorFallback(
            Player player,
            EquipmentSlot equipmentSlot,
            CallbackInfoReturnable<ResourceLocation> cir
    ) {
        if (!Config.HIDE_UNDEVELOPED_DRAGON_ARMOR.get()) {
            return;
        }

        ResourceLocation location = cir.getReturnValue();
        if (isGenericArmorFallback(location, equipmentSlot)) {
            // 返回一个不存在的贴图位置，renderArmorSlot 会因 hasResource=false 跳过该槽位
            cir.setReturnValue(ResourceLocation.fromNamespaceAndPath(
                    "beloong",
                    "textures/armor/__hidden__/" + location.getPath()
            ));
        }
    }

    @Unique
    private static boolean isGenericArmorFallback(ResourceLocation location, EquipmentSlot slot) {
        if (location == null) {
            return false;
        }

        String prefix = "textures/armor/";
        String path = location.getPath();
        if (!path.startsWith(prefix)) {
            return false;
        }

        String relative = path.substring(prefix.length());
        int slash = relative.indexOf('/');
        if (slash <= 0 || slash != relative.lastIndexOf('/')) {
            return false;
        }

        String file = relative.substring(slash + 1);
        String slotName = slot.getName();
        return file.equals("default_" + slotName + ".png")
                || file.equals("uncommon_" + slotName + ".png")
                || file.equals("rare_" + slotName + ".png")
                || file.equals("epic_" + slotName + ".png");
    }
}
