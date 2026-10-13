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
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
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
                syncHeldItemSlots(player);
                notifyPlayer(player);
            }
        } else if (shouldPreventEnvironment(
                event.getLevel(), actor, Config.LoongPalaceProtection.protectNonPlayerBlockPlacement)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        Item heldItem = player.getItemInHand(event.getHand()).getItem();
        boolean flowerPotEdit = player.level().getBlockState(event.getPos()).getBlock() instanceof FlowerPotBlock
                && shouldPreventPlayerInteraction(player, Config.LoongPalaceProtection.protectFlowerPotEdits);
        boolean fluidEdit = isFluidContainer(heldItem)
                && shouldPreventPlayerInteraction(player, Config.LoongPalaceProtection.protectFluidContainerEdits);
        boolean hangingPlacement = heldItem instanceof HangingEntityItem
                && shouldPreventPlayerInteraction(player, Config.LoongPalaceProtection.protectHangingEntityEdits);

        if (flowerPotEdit || fluidEdit || hangingPlacement) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            denyPlayerInteraction(player, event.getHand(), flowerPotEdit);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer player
                && isFluidContainer(player.getItemInHand(event.getHand()).getItem())
                && shouldPreventPlayerInteraction(player, Config.LoongPalaceProtection.protectFluidContainerEdits)) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            denyPlayerInteraction(player, event.getHand(), false);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getEntity() instanceof ServerPlayer player
                && shouldPreventHangingEntityEdit(player, event.getTarget())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            denyPlayerInteraction(player, event.getHand(), true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer player
                && shouldPreventHangingEntityEdit(player, event.getTarget())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            denyPlayerInteraction(player, event.getHand(), true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAttackEntity(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && shouldPreventHangingEntityEdit(player, event.getTarget())) {
            event.setCanceled(true);
            denyPlayerInteraction(player, InteractionHand.MAIN_HAND, true);
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

    private boolean shouldPreventPlayerInteraction(
            ServerPlayer player, ModConfigSpec.BooleanValue category) {
        return Config.LoongPalaceProtection.environmentProtectionEnabled.get()
                && category.get()
                && shouldPreventEdit(player, player.level());
    }

    private boolean shouldPreventHangingEntityEdit(ServerPlayer player, Entity target) {
        return target instanceof HangingEntity
                && shouldPreventPlayerInteraction(player, Config.LoongPalaceProtection.protectHangingEntityEdits);
    }

    private static boolean isFluidContainer(Item item) {
        return item instanceof BucketItem || item instanceof SolidBucketItem;
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

    /**
     * 回滚客户端对"放置消耗"的预测（双手一起，见下）。
     *
     * <h2>为什么必须回滚</h2>
     * NeoForge 取消放置时**只回滚方块、不回滚玩家物品栏**：{@code CommonHooks#onPlaceItemIntoWorld}
     * 在发事件前先跑了一遍 {@code Item#useOn}（其中 {@code BlockItem#place} 会
     * {@code itemstack.consume(1, player)}），取消分支只做
     * {@code snap.restore(flags | Block.UPDATE_CLIENTS)}（所以方块侧客户端会被自动纠正），
     * 而数量是靠事件前那句 {@code itemstack.setCount(size)} 还原的 —— 那**只作用于服务端那份 ItemStack，
     * 不产生任何网络包**。
     *
     * <p>与此同时，客户端走的是 {@code ItemStack#useOn} 的**另一条分支**
     * （{@code ItemStack.java:366} 只在 {@code !isClientSide} 时才进 {@code onPlaceItemIntoWorld}），
     * 它直接执行原版 {@code BlockItem#place}，而那里的 {@code consume(1, player)}
     * 只看 {@code hasInfiniteMaterials()}（创造模式）、**不判 side**。
     * ⇒ 客户端少 1、服务端不少，且没有任何一方会自动纠正，只有重登时的整体同步才恢复。
     * 堆叠为 1 且带数据组件的物品（如龙之生存的**龙魂**）会让整个槽位视觉上消失。
     *
     * <h2>为什么是双手</h2>
     * {@link BlockEvent.EntityPlaceEvent} **不携带 hand**（其 API 只有 {@code getEntity} /
     * {@code getBlockSnapshot} / {@code getPlacedBlock} / {@code getPlacedAgainst}）
     * ⇒ 无法知道是哪只手发起的放置，因此主手当前槽与副手一起同步。
     *
     * <p>时机上不必自己 +1：事件触发**之前** NeoForge 已把服务端那份还原为放置前的数量，
     * 所以这里读到的 {@code getItem(slot)} 就是"应有"的正确值。
     */
    private static void syncHeldItemSlots(ServerPlayer player) {
        if (player instanceof FakePlayer) {
            // 假玩家没有真实客户端（与 notifyPlayer 同一先例）。
            return;
        }
        syncSlot(player, player.getInventory().selected);
        syncSlot(player, Inventory.SLOT_OFFHAND);
    }

    private static void syncSlot(ServerPlayer player, int slot) {
        player.connection.send(new ClientboundContainerSetSlotPacket(
                -2, 0, slot, player.getInventory().getItem(slot)));
    }

    private static void denyPlayerInteraction(
            ServerPlayer player, InteractionHand hand, boolean syncFullInventory) {
        if (syncFullInventory) {
            player.inventoryMenu.sendAllDataToRemote();
        } else {
            int slot = hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : 40;
            player.connection.send(new ClientboundContainerSetSlotPacket(
                    -2, 0, slot, player.getItemInHand(hand)));
        }
        notifyPlayer(player);
    }
}
