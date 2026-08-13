package com.zonlong.beloong.waystoneplacement;

import com.zonlong.beloong.BeLoongCore;
import net.blay09.mods.waystones.api.MutableWaystone;
import net.blay09.mods.waystones.api.Waystone;
import net.blay09.mods.waystones.api.WaystoneOrigin;
import net.blay09.mods.waystones.api.WaystoneStyle;
import net.blay09.mods.waystones.api.WaystoneStyles;
import net.blay09.mods.waystones.api.WaystoneTypes;
import net.blay09.mods.waystones.block.WaystoneBlockBase;
import net.blay09.mods.waystones.block.entity.WaystoneBlockEntityBase;
import net.blay09.mods.waystones.core.WaystoneImpl;
import net.blay09.mods.waystones.core.WaystoneManagerImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 龙宫预设传送石碑处理器。
 * <p>
 * 核心思想：龙宫是一张固定地图，石碑的「位置 + 名字 + 类型 + UUID」都是确定的常量，
 * 因此不依赖存档里的 {@code waystones.dat}。每当龙宫区块加载（或玩家进入龙宫）时，
 * 对预设石碑做一次幂等校正：
 * <ul>
 *   <li>方块缺失 → 放置并注入固定 UUID + 固定名字（首次放置）；</li>
 *   <li>固定 UUID 记录丢失 → 清理孤儿并重建（存档迁移后的自愈）；</li>
 *   <li>名字被清空 → 补回预设名（不锁定，玩家改名会被尊重）。</li>
 * </ul>
 * <p>
 * Waystones 为必选依赖。
 * <p>
 * <b>防卡死设计</b>：校正一律延迟到下一个 tick 执行（{@link MinecraftServer#tell}），
 * 并跳过未完全加载的区块、用重入标志防止嵌套校正——避免在区块加载调用栈上修改方块，
 * 从而杜绝「setBlock → 邻居更新加载相邻区块 → ChunkEvent.Load → 再次校正」的加载风暴。
 */
public final class WaystonePlacementHandler {

    private static final ResourceKey<Level> LOONG_PALACE = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "loong_palace"));

    /** 正在执行校正的石碑位置，防止区块加载链上的重入。 */
    private static final Set<BlockPos> ENSURING = Collections.synchronizedSet(new HashSet<>());

    /** 玩家进入龙宫维度时，对全部预设石碑做一次幂等校正。 */
    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        if (!LOONG_PALACE.equals(level.dimension())) {
            return;
        }
        ensureAll(level);
    }

    /** 龙宫区块加载时兜底，校正落在该区块内的预设石碑。 */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!LOONG_PALACE.equals(level.dimension())) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }

        int minX = event.getChunk().getPos().getMinBlockX();
        int minZ = event.getChunk().getPos().getMinBlockZ();
        int maxX = event.getChunk().getPos().getMaxBlockX();
        int maxZ = event.getChunk().getPos().getMaxBlockZ();

        // 延迟到下一个 tick 执行：彻底脱离区块加载调用栈，
        // 避免在主线程的 ChunkEvent.Load 栈上直接 setBlock 引发递归加载风暴
        server.tell(new TickTask(server.getTickCount() + 1, () -> {
            for (WaystonePlacementEntry entry : WaystonePlacementLoader.INSTANCE.getEntries()) {
                BlockPos pos = entry.pos();
                if (pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ) {
                    ensureWaystone(level, entry);
                }
            }
        }));
    }

    private static void ensureAll(ServerLevel level) {
        for (WaystonePlacementEntry entry : WaystonePlacementLoader.INSTANCE.getEntries()) {
            ensureWaystone(level, entry);
        }
    }

    private static void ensureWaystone(ServerLevel level, WaystonePlacementEntry entry) {
        BlockPos lowerPos = entry.pos();

        // 区块未完全加载时跳过：此时 getBlockEntity 会返回 null，
        // 若直接放置会触发同步区块加载（可能再次进入 ChunkEvent.Load 链路）。
        // 该区块自身的 ChunkEvent.Load 兜底会在加载完成后处理。
        if (!level.isLoaded(lowerPos)) {
            return;
        }

        // 防重入：同一个石碑的校正不能嵌套执行
        if (!ENSURING.add(entry.pos())) {
            return;
        }
        try {
            doEnsureWaystone(level, entry);
        } finally {
            ENSURING.remove(entry.pos());
        }
    }

    private static void doEnsureWaystone(ServerLevel level, WaystonePlacementEntry entry) {
        BlockPos lowerPos = entry.pos();
        BlockPos upperPos = lowerPos.above();
        WaystoneManagerImpl manager = WaystoneManagerImpl.get(level.getServer());

        // 1. 方块不存在 → 首次放置
        if (!(level.getBlockEntity(lowerPos) instanceof WaystoneBlockEntityBase lowerBe)) {
            placeWaystone(level, lowerPos, entry);
            return;
        }

        Waystone current = lowerBe.getWaystone();

        // 2. 固定 UUID 记录不存在（数据丢失）→ 清理孤儿 + 重建
        if (manager.getWaystoneById(entry.fixedUid()).isEmpty()) {
            BeLoongCore.LOGGER.info(
                    "WaystonePlacement: restoring missing waystone record '{}' at {}", entry.id(), lowerPos);
            if (current.isValid() && !current.getWaystoneUid().equals(entry.fixedUid())) {
                manager.removeWaystone(current);
            }
            injectPreset(level, lowerBe, upperPos, entry);
            return;
        }

        // 3. 方块实体与固定 UUID 脱节（如数据丢失后 onLoad 重建成随机 UUID）→ 重新绑定
        if (!current.isValid() || !current.getWaystoneUid().equals(entry.fixedUid())) {
            Waystone fixed = manager.getWaystoneById(entry.fixedUid()).orElse(null);
            if (fixed instanceof WaystoneImpl fixedImpl) {
                lowerBe.initializeFromExisting(level, fixedImpl, ItemStack.EMPTY);
                syncUpper(level, lowerBe, upperPos);
            }
            return;
        }

        // 4. 正常：仅在名字被清空时补回预设名，尊重玩家改名
        if (current.getName().getString().isEmpty()) {
            if (current instanceof MutableWaystone mutable) {
                mutable.setName(entry.nameComponent());
            }
            manager.updateWaystone(current);
        }
    }

    private static void placeWaystone(ServerLevel level, BlockPos lowerPos, WaystonePlacementEntry entry) {
        WaystoneStyle style = resolveStyle(entry.style());
        Block block = BuiltInRegistries.BLOCK.get(style.getBlockRegistryName());

        BlockState lower = block.defaultBlockState()
                .setValue(WaystoneBlockBase.HALF, DoubleBlockHalf.LOWER)
                .setValue(WaystoneBlockBase.ORIGIN, WaystoneOrigin.UNKNOWN);
        BlockState upper = block.defaultBlockState()
                .setValue(WaystoneBlockBase.HALF, DoubleBlockHalf.UPPER)
                .setValue(WaystoneBlockBase.ORIGIN, WaystoneOrigin.UNKNOWN);

        BeLoongCore.LOGGER.info("WaystonePlacement: placing waystone '{}' at {}", entry.id(), lowerPos);

        level.setBlock(lowerPos, lower, Block.UPDATE_ALL);
        level.setBlock(lowerPos.above(), upper, Block.UPDATE_ALL);

        if (level.getBlockEntity(lowerPos) instanceof WaystoneBlockEntityBase lowerBe) {
            injectPreset(level, lowerBe, lowerPos.above(), entry);
        }
    }

    private static void injectPreset(ServerLevel level, WaystoneBlockEntityBase lowerBe,
                                     BlockPos upperPos, WaystonePlacementEntry entry) {
        WaystoneImpl preset = new WaystoneImpl(
                WaystoneTypes.WAYSTONE,
                entry.fixedUid(),
                level.dimension(),
                entry.pos(),
                WaystoneOrigin.UNKNOWN,
                null,
                null);
        preset.setName(entry.nameComponent());
        lowerBe.initializeFromExisting(level, preset, ItemStack.EMPTY);
        syncUpper(level, lowerBe, upperPos);
    }

    private static void syncUpper(ServerLevel level, WaystoneBlockEntityBase lowerBe, BlockPos upperPos) {
        if (level.getBlockEntity(upperPos) instanceof WaystoneBlockEntityBase upperBe) {
            upperBe.initializeFromBase(lowerBe);
        }
    }

    private static WaystoneStyle resolveStyle(String styleKey) {
        ResourceLocation styleId = styleKey.contains(":")
                ? ResourceLocation.tryParse(styleKey)
                : ResourceLocation.fromNamespaceAndPath("waystones", styleKey);
        WaystoneStyle style = styleId != null ? WaystoneStyles.getStyle(styleId) : null;
        if (style == null) {
            BeLoongCore.LOGGER.warn(
                    "Unknown waystone style '{}' in loong palace waystones, falling back to default", styleKey);
            return WaystoneStyles.DEFAULT;
        }
        return style;
    }
}
