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
 */
public final class LockdownTemplateMigration {
    private static final String[] OVERWORLD_DIRECTORIES = {"data", "entities", "poi", "region"};

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

    private boolean copyPinnedDimension(Path templateDir, Path saveDir, String dimensionId) {
        ResourceLocation location = ResourceLocation.tryParse(dimensionId);
        if (location == null) {
            BeLoongCore.LOGGER.warn("[LockdownMigration] Invalid pinned dimension id '{}'; skipping.", dimensionId);
            return false;
        }

        ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, location);
        Path source = DimensionType.getStorageFolder(dimensionKey, templateDir);
        Path target = DimensionType.getStorageFolder(dimensionKey, saveDir);

        if (dimensionKey.equals(Level.OVERWORLD)) {
            return copyOverworldDirectories(source, target);
        }

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

    private boolean copyOverworldDirectories(Path templateWorld, Path targetWorld) {
        boolean copiedAny = false;

        for (String directoryName : OVERWORLD_DIRECTORIES) {
            Path sourceDir = templateWorld.resolve(directoryName);
            if (!Files.isDirectory(sourceDir)) {
                continue;
            }

            Path targetDir = targetWorld.resolve(directoryName);
            try {
                if (Files.exists(targetDir)) {
                    FileUtils.deleteDirectory(targetDir.toFile());
                }
                FileUtils.copyDirectory(sourceDir.toFile(), targetDir.toFile());
                copiedAny = true;
            } catch (IOException exception) {
                BeLoongCore.LOGGER.error("[LockdownMigration] Failed to copy overworld directory {} from {} to {}.", directoryName, sourceDir, targetDir, exception);
            }
        }

        if (!copiedAny) {
            BeLoongCore.LOGGER.warn("[LockdownMigration] No overworld template subdirectories found under {}; skipping overworld pin.", templateWorld);
        }
        return copiedAny;
    }
}
