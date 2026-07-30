package com.zonlong.beloong.compat.ftbchunks;

import com.zonlong.beloong.BeLoongCore;
import dev.architectury.event.CompoundEventResult;
import dev.ftb.mods.ftbchunks.api.ClaimResult;
import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.event.ClaimedChunkEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Protects player-driven block edits in the Loong Palace dimension. */
public final class LoongPalaceProtectionHandler {

    private static final ResourceKey<Level> LOONG_PALACE = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "loong_palace"));

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
        if (event.getEntity() instanceof ServerPlayer player
                && shouldPreventEdit(player, event.getLevel())) {
            event.setCanceled(true);
            notifyPlayer(player);
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
                .filter(pos -> LOONG_PALACE.equals(pos.dimension()))
                .count();
        if (historicalClaims > 0) {
            BeLoongCore.LOGGER.warn(
                    "Found {} existing FTB Chunks claim(s) in {}. They were not removed; clear them manually to ensure unrestricted block interaction.",
                    historicalClaims,
                    LOONG_PALACE.location());
        }
    }

    private CompoundEventResult<ClaimResult> beforeClaim(CommandSourceStack source, ClaimedChunk chunk) {
        if (LOONG_PALACE.equals(chunk.getPos().dimension())) {
            return CompoundEventResult.interruptFalse(ClaimResult.StandardProblem.DIMENSION_FORBIDDEN);
        }
        return CompoundEventResult.pass();
    }

    private boolean shouldPreventEdit(ServerPlayer player, LevelAccessor eventLevel) {
        ResourceKey<Level> dimension = eventLevel instanceof Level level
                ? level.dimension()
                : player.level().dimension();
        if (!LOONG_PALACE.equals(dimension)) {
            return false;
        }

        FTBChunksAPI.API api = FTBChunksAPI.api();
        if (!api.isManagerLoaded()) {
            logManagerUnavailable();
            return true;
        }

        managerWarningLogged = false;
        return !api.getManager().getBypassProtection(player.getUUID());
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
