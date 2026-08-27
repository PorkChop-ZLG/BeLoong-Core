package com.zonlong.beloong.compat.lockdown;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import org.apache.commons.io.FileUtils;

/**
 * Migrates old saves by copying LockDown's pinned template dimensions into the
 * active save when the stored template version does not match the configured one.
 *
 * <p>This runs on {@link ServerAboutToStartEvent}, which fires before
 * {@code MinecraftServer#loadLevel()}, so no dimension is loaded yet.</p>
 *
 * <p>Only custom dimensions stored under {@code <save>/dimensions/} are handled.
 * Vanilla dimensions (overworld/nether/end) are skipped because they are not
 * part of the {@code dimensions} folder.</p>
 */
public final class LockdownTemplateMigration {

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        if (!Config.TemplateUpdate.enabled.get()) {
            return;
        }

        MinecraftServer server = event.getServer();
        if (!com.xfw.lockdown.Config.pinDimensionsEnabled.get()) {
            BeLoongCore.LOGGER.info("[LockdownMigration] LockDown pin-dimensions mode is disabled; skipping template migration.");
            return;
        }

        List<? extends String> dimensions = com.xfw.lockdown.Config.pinnedDimensions.get();
        if (dimensions.isEmpty()) {
            return;
        }

        Path saveDir = server.getWorldPath(LevelResource.ROOT).normalize();
        Path templateDir = server.getServerDirectory().toAbsolutePath().normalize()
                .resolve(com.xfw.lockdown.Config.templateDirectory.get());

        if (!isTemplateUsable(templateDir)) {
            BeLoongCore.LOGGER.warn("[LockdownMigration] Template directory {} is missing or empty; skipping template migration.", templateDir);
            return;
        }

        if (!isSafeTemplatePath(templateDir, saveDir)) {
            BeLoongCore.LOGGER.error(
                    "[LockdownMigration] Template directory {} is not safe to use with save directory {}; aborting template migration.",
                    templateDir, saveDir);
            return;
        }

        int stored = TemplateVersionStore.read(saveDir);
        int expected = Config.TemplateUpdate.templateVersion.get();
        if (stored == expected) {
            BeLoongCore.LOGGER.debug("[LockdownMigration] Template version is already up to date ({}).", expected);
            return;
        }

        BeLoongCore.LOGGER.info("[LockdownMigration] Applying template dimensions to save (stored={}, expected={}).", stored, expected);

        boolean success = true;
        for (String dimensionId : dimensions) {
            if (!copyPinnedDimension(templateDir, saveDir, dimensionId)) {
                success = false;
            }
        }

        if (!success) {
            BeLoongCore.LOGGER.error("[LockdownMigration] One or more dimensions failed to copy; version marker was NOT updated.");
            return;
        }

        try {
            TemplateVersionStore.write(saveDir, expected);
            BeLoongCore.LOGGER.info("[LockdownMigration] Template migration complete; version marker updated to {}.", expected);
        } catch (IOException exception) {
            BeLoongCore.LOGGER.error("[LockdownMigration] Template copy succeeded but failed to write version marker.", exception);
        }
    }

    private boolean isTemplateUsable(Path templateDir) {
        if (!Files.isDirectory(templateDir)) {
            return false;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(templateDir)) {
            return stream.iterator().hasNext();
        } catch (IOException exception) {
            BeLoongCore.LOGGER.warn("[LockdownMigration] Failed to inspect template directory {}: {}", templateDir, exception.toString());
            return false;
        }
    }

    private boolean isSafeTemplatePath(Path templateDir, Path saveDir) {
        Path template = templateDir.toAbsolutePath().normalize();
        Path save = saveDir.toAbsolutePath().normalize();

        if (template.equals(save)) {
            return false;
        }
        if (template.startsWith(save) || save.startsWith(template)) {
            return false;
        }
        return true;
    }

    private boolean isUnderDimensionsFolder(Path saveDir, ResourceKey<Level> dimensionKey) {
        Path target = DimensionType.getStorageFolder(dimensionKey, saveDir).normalize();
        Path dimensionsRoot = saveDir.resolve("dimensions").normalize();
        return target.startsWith(dimensionsRoot);
    }

    private boolean copyPinnedDimension(Path templateDir, Path saveDir, String dimensionId) {
        ResourceLocation location = ResourceLocation.tryParse(dimensionId);
        if (location == null) {
            BeLoongCore.LOGGER.warn("[LockdownMigration] Invalid pinned dimension id '{}'; skipping.", dimensionId);
            return false;
        }

        ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, location);
        if (!isUnderDimensionsFolder(saveDir, dimensionKey)) {
            BeLoongCore.LOGGER.warn("[LockdownMigration] Dimension {} is not under the save's dimensions folder; skipping.", dimensionId);
            return true;
        }

        Path source = DimensionType.getStorageFolder(dimensionKey, templateDir);
        Path target = DimensionType.getStorageFolder(dimensionKey, saveDir);

        if (!Files.isDirectory(source)) {
            BeLoongCore.LOGGER.warn("[LockdownMigration] Template dimension {} does not exist at {}; skipping.", dimensionId, source);
            return false;
        }

        try {
            if (Files.exists(target)) {
                FileUtils.deleteDirectory(target.toFile());
            }
            FileUtils.copyDirectory(source.toFile(), target.toFile());
            BeLoongCore.LOGGER.info("[LockdownMigration] Copied dimension {} from template.", dimensionId);
            return true;
        } catch (IOException exception) {
            BeLoongCore.LOGGER.error("[LockdownMigration] Failed to copy dimension {} from {} to {}.", dimensionId, source, target, exception);
            return false;
        }
    }
}
