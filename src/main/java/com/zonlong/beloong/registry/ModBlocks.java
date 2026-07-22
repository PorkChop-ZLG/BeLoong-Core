package com.zonlong.beloong.registry;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.block.DisasterPortalBlock;
import com.zonlong.beloong.block.DisasterPortalBlockEntity;
import com.zonlong.beloong.block.DisasterPortalFrame;
import com.zonlong.beloong.block.DisasterPortalFrameEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

/**
 * 化龙核心模组的方块和 BlockEntity 注册中心。
 * <p>
 * 使用 NeoForge 的 {@link DeferredRegister} 机制进行懒加载注册，
 * 在 {@link BeLoongCore} 构造函数中通过 {@link #register(IEventBus)} 绑定到 Mod 事件总线。
 * <p>
 * <b>注册的方块：</b>
 * <ul>
 *   <li>{@link #DISASTER_PORTAL_FRAME} — 天灾传送门框架</li>
 *   <li>{@link #DISASTER_PORTAL_BLOCK} — 天灾传送门方块（接触传送）</li>
 * </ul>
 * <b>注册的 BlockEntity：</b>
 * <ul>
 *   <li>{@link #DISASTER_PORTAL_FRAME_ENTITY} — 框架 BlockEntity（存储眼球 ID）</li>
 *   <li>{@link #DISASTER_PORTAL_BLOCK_ENTITY} — 传送门 BlockEntity（渲染器载体）</li>
 * </ul>
 * <b>眼球槽位：</b>
 * <ul>
 *   <li>{@link #getEyeSlot} — 在配置列表中查找物品对应的槽位编号 (1~12)</li>
 * </ul>
 *
 * @see BeLoongCore
 * @see com.zonlong.beloong.block.DisasterPortalFrame
 * @see com.zonlong.beloong.block.DisasterPortalBlock
 */
public class ModBlocks {

    /** 方块延迟注册器 */
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(BeLoongCore.MODID);

    /** BlockEntity 类型延迟注册器 */
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE, BeLoongCore.MODID);

    /**
     * 在配置 {@code eyeItems} 列表中查找物品对应的槽位编号 (1~12)。
     * <p>
     * 遍历 {@link Config.DisasterPortal#eyeItems} 列表，找到物品完整 ID（如 "cataclysm:mech_eye"）
     * 后返回其在列表中的位置 +1（即 1-based 槽位编号）。
     * 未找到返回 0，表示该物品不是有效的传送门眼球。
     *
     * @param stack 玩家手持的物品堆
     * @return 槽位编号 1~12，无效物品返回 0
     */
    public static int getEyeSlot(ItemStack stack) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
        List<? extends String> items = Config.DisasterPortal.eyeItems.get();
        int idx = items.indexOf(id);
        return idx >= 0 ? idx + 1 : 0;
    }

    // ==================== 方块注册 ====================

    /**
     * 天灾传送门框架方块。
     * <p>
     * 5×5 环形结构中的框架方块，接受 12 种眼球嵌入。
     * 方块 ID：{@code beloong:disaster_portal_frame}
     */
    public static final DeferredBlock<Block> DISASTER_PORTAL_FRAME =
            BLOCKS.register("disaster_portal_frame", DisasterPortalFrame::new);

    /**
     * 天灾传送门方块。
     * <p>
     * 传送门激活后自动填充在 5×5 框架的中间 3×3 区域。
     * 玩家接触时触发双向传送。
     * 方块 ID：{@code beloong:disaster_portal_block}
     */
    public static final DeferredBlock<Block> DISASTER_PORTAL_BLOCK =
            BLOCKS.register("disaster_portal_block", DisasterPortalBlock::new);

    /** 化龙池水的液体方块。 */
    public static final DeferredBlock<LiquidBlock> BELOONG_WATER =
            BLOCKS.register("beloong_water", () -> new LiquidBlock(
                    ModFluids.BELOONG_WATER.get(),
                    BlockBehaviour.Properties.ofFullCopy(Blocks.WATER)));

    // ==================== BlockEntity 注册 ====================

    /**
     * 天灾传送门框架的 BlockEntity 类型。
     * 绑定到 {@link #DISASTER_PORTAL_FRAME}。
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DisasterPortalFrameEntity>>
            DISASTER_PORTAL_FRAME_ENTITY =
            BLOCK_ENTITIES.register("disaster_portal_frame_entity",
                    () -> BlockEntityType.Builder.of(
                            DisasterPortalFrameEntity::new,
                            DISASTER_PORTAL_FRAME.get()
                    ).build(null));

    /**
     * 天灾传送门方块的 BlockEntity 类型。
     * 绑定到 {@link #DISASTER_PORTAL_BLOCK}，作为
     * {@link com.zonlong.beloong.client.DisasterPortalRenderer} 的渲染载体。
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DisasterPortalBlockEntity>>
            DISASTER_PORTAL_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("disaster_portal_block_entity",
                    () -> BlockEntityType.Builder.of(
                            DisasterPortalBlockEntity::new,
                            DISASTER_PORTAL_BLOCK.get()
                    ).build(null));

    /**
     * 将方块和 BlockEntity 注册到 Mod 事件总线。
     * 在 {@link BeLoongCore} 构造函数中调用。
     */
    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        BLOCK_ENTITIES.register(bus);
    }
}
