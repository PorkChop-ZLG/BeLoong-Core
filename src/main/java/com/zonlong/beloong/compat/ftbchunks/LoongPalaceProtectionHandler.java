package com.zonlong.beloong.compat.ftbchunks;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import dev.architectury.event.CompoundEventResult;
import dev.ftb.mods.ftbchunks.api.ClaimResult;
import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.event.ClaimedChunkEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.jetbrains.annotations.Nullable;

/** Protects configured player and environment block edits in Loong Palace. */
public final class LoongPalaceProtectionHandler {

    private boolean managerWarningLogged;

    private LoongPalaceProtectionHandler() {}

    public static void register() {
        LoongPalaceProtectionHandler handler = new LoongPalaceProtectionHandler();
        NeoForge.EVENT_BUS.register(handler);
        ClaimedChunkEvent.BEFORE_CLAIM.register(handler::beforeClaim);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player
                && shouldPreventEdit(player, event.getLevel())) {
            event.setCanceled(true);
            notifyPlayer(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        Entity actor = event.getEntity();
        if (actor instanceof ServerPlayer player) {
            if (shouldPreventEdit(player, event.getLevel())) {
                event.setCanceled(true);
                notifyPlayer(player);
            }
        } else if (shouldPreventEnvironment(
                event.getLevel(), actor, Config.LoongPalaceProtection.protectNonPlayerBlockPlacement)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onExplosionDetonate(ExplosionEvent.Detonate event) {
        Entity actor = event.getExplosion().getIndirectSourceEntity();
        if (shouldPreventEnvironment(
                event.getLevel(), actor, Config.LoongPalaceProtection.protectExplosions)) {
            event.getAffectedBlocks().clear();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLivingDestroyBlock(LivingDestroyBlockEvent event) {
        if (shouldPreventEnvironment(
                event.getEntity().level(),
                event.getEntity(),
                Config.LoongPalaceProtection.protectLivingBlockDestruction)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onMobGriefing(EntityMobGriefingEvent event) {
        if (shouldPreventEnvironment(
                event.getEntity().level(),
                event.getEntity(),
                Config.LoongPalaceProtection.protectMobGriefing)) {
            event.setCanGrief(false);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onFarmlandTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (shouldPreventEnvironment(
                event.getLevel(),
                event.getEntity(),
                Config.LoongPalaceProtection.protectFarmlandTrampling)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBlockToolModification(BlockEvent.BlockToolModificationEvent event) {
        if (!event.isSimulated()
                && shouldPreventEnvironment(
                        event.getLevel(),
                        event.getPlayer(),
                        Config.LoongPalaceProtection.protectToolModifications)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onCropGrow(CropGrowEvent.Pre event) {
        if (shouldPreventEnvironment(
                event.getLevel(), null, Config.LoongPalaceProtection.protectCropGrowth)) {
            event.setResult(CropGrowEvent.Pre.Result.DO_NOT_GROW);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBonemeal(BonemealEvent event) {
        if (shouldPreventEnvironment(
                event.getLevel(), event.getPlayer(), Config.LoongPalaceProtection.protectCropGrowth)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBlockGrowFeature(BlockGrowFeatureEvent event) {
        if (shouldPreventEnvironment(
                event.getLevel(), null, Config.LoongPalaceProtection.protectFeatureGrowth)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPortalSpawn(BlockEvent.PortalSpawnEvent event) {
        if (shouldPreventEnvironment(
                event.getLevel(), null, Config.LoongPalaceProtection.protectPortalCreation)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (!FTBChunksAPI.api().isManagerLoaded()) {
            logManagerUnavailable();
            return;
        }

        long historicalClaims = FTBChunksAPI.api().getManager().getAllClaimedChunks().stream()
                .map(ClaimedChunk::getPos)
                .filter(pos -> LoongPalaceProtectionPolicy.DIMENSION.equals(pos.dimension()))
                .count();
        if (historicalClaims > 0) {
            BeLoongCore.LOGGER.warn(
                    "Found {} existing FTB Chunks claim(s) in {}. They were not removed; clear them manually to ensure unrestricted block interaction.",
                    historicalClaims,
                    LoongPalaceProtectionPolicy.DIMENSION.location());
        }
    }

    private CompoundEventResult<ClaimResult> beforeClaim(CommandSourceStack source, ClaimedChunk chunk) {
        if (LoongPalaceProtectionPolicy.DIMENSION.equals(chunk.getPos().dimension())) {
            return CompoundEventResult.interruptFalse(ClaimResult.StandardProblem.DIMENSION_FORBIDDEN);
        }
        return CompoundEventResult.pass();
    }

    private boolean shouldPreventEdit(ServerPlayer player, LevelAccessor eventLevel) {
        if (!LoongPalaceProtectionPolicy.isLoongPalace(eventLevel)) {
            return false;
        }

        FTBChunksAPI.API api = FTBChunksAPI.api();
        if (!api.isManagerLoaded()) {
            logManagerUnavailable();
            return true;
        }

        managerWarningLogged = false;
        return !LoongPalaceProtectionPolicy.hasBypass(player);
    }

    private static boolean shouldPreventEnvironment(
            LevelAccessor level, @Nullable Entity actor,
            ModConfigSpec.BooleanValue category) {
        return LoongPalaceProtectionPolicy.shouldPrevent(level, actor)
                && Config.LoongPalaceProtection.environmentProtectionEnabled.get()
                && category.get();
    }

    private void logManagerUnavailable() {
        if (!managerWarningLogged) {
            managerWarningLogged = true;
            BeLoongCore.LOGGER.warn(
                    "FTB Chunks manager is not ready; Loong Palace block edits will remain blocked until bypass status is available.");
        }
    }

    private static void notifyPlayer(ServerPlayer player) {
        if (!(player instanceof FakePlayer)) {
            player.displayClientMessage(
                    Component.translatable("ftbchunks.action_prevented").withStyle(ChatFormatting.GOLD),
                    true);
        }
    }
}
