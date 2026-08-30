package com.zonlong.beloong.compat.betterendisland;

import com.yungnickyoung.minecraft.betterendisland.mixin.accessor.EndDragonFightAccessor;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Optional;

/**
 * 自定义末地返回传送门/祭坛外观。
 *
 * <p>在 YUNG's Better End Island 放置底部传送门后，将中央区域覆盖为自定义 NBT：</p>
 * <ul>
 *   <li>龙死亡后（active）→ {@code end_return_portal_activated}</li>
 *   <li>复活完成后（inactive）→ {@code end_return_portal_deactivated}</li>
 * </ul>
 *
 * <p>对齐方式：使用硬编码偏移 {@code portalLocation.offset(-7, -1, -7)}，
 * 不依赖 NBT 内的 bedrock 锚点。</p>
 */
public final class CustomEndPortalAppearance {

    private static final ResourceLocation ACTIVATED = ResourceLocation.fromNamespaceAndPath(
            BeLoongCore.MODID, "end_return_portal_activated");
    private static final ResourceLocation DEACTIVATED = ResourceLocation.fromNamespaceAndPath(
            BeLoongCore.MODID, "end_return_portal_deactivated");

    private CustomEndPortalAppearance() {}

    /**
     * 在指定末地龙战实例上覆盖放置自定义返回传送门外观。
     *
     * @param level  末地服务端世界
     * @param fight  当前龙战实例
     * @param active true 表示龙死亡后的激活状态，false 表示复活后的未激活状态
     */
    public static void apply(ServerLevel level, EndDragonFight fight, boolean active) {
        BlockPos portalLocation = ((EndDragonFightAccessor) fight).getPortalLocation();
        if (portalLocation == null) {
            BeLoongCore.LOGGER.warn("Cannot apply custom end portal appearance: portalLocation is null");
            return;
        }

        ResourceLocation id = active ? ACTIVATED : DEACTIVATED;
        Optional<StructureTemplate> template = level.getStructureManager().get(id);
        if (template.isEmpty()) {
            BeLoongCore.LOGGER.warn("Cannot apply custom end portal appearance: missing structure {}", id);
            return;
        }

        // NBT size: 15 x 7 x 15。
        // 使用硬编码偏移：结构原点为 portalLocation.offset(-7, -1, -7)。
        // 该偏移是在旧“四基岩中心对齐 portalLocation”的基础上整体上移一格得到的。
        BlockPos origin = portalLocation.offset(-7, -1, -7);
        StructurePlaceSettings settings = new StructurePlaceSettings();

        try {
            template.get().placeInWorld(level, origin, portalLocation, settings, level.random,
                    Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
        } catch (Exception e) {
            BeLoongCore.LOGGER.error("Failed to place custom end portal appearance {}", id, e);
        }
    }
}
