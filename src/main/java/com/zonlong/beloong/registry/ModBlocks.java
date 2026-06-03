package com.zonlong.beloong.registry;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.block.DisasterPortalBlock;
import com.zonlong.beloong.block.DisasterPortalBlockEntity;
import com.zonlong.beloong.block.DisasterPortalFrame;
import com.zonlong.beloong.block.DisasterPortalFrameEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
 * <b>眼球映射常量：</b>
 * <ul>
 *   <li>{@link #EYE_KEYS} — 12 种眼球短键列表（不可含冒号，用于 BlockState 序列化）</li>
 *   <li>{@link #EYE_KEY_TO_FULL_ID} — 短键 → 完整物品 ID 的正向映射</li>
 *   <li>{@link #FULL_ID_TO_EYE_KEY} — 完整物品 ID → 短键的反向映射（用于手持物品校验）</li>
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
     * 12 种眼球短键列表。
     * <p>
     * 短键用于 {@link com.zonlong.beloong.block.EyeType} 枚举的序列化名称
     * 和 BlockState JSON 中的 {@code eye_type} 值。
     * 短键不可包含冒号，因为冒号在 BlockState 属性值中可能引起解析歧义。
     */
    public static final List<String> EYE_KEYS = List.of(
            "ender_eye",
            "mech_eye",
            "flame_eye",
            "void_eye",
            "monstrous_eye",
            "abyss_eye",
            "desert_eye",
            "cursed_eye",
            "storm_eye",
            "eye_of_chesed",
            "eye_of_malkuth",
            "eye_of_geburah"
    );

    /** EYE_KEYS 的不可变 Set 副本，用于快速查找。 */
    public static final Set<String> EYE_TYPE_VALUES = Set.copyOf(EYE_KEYS);

    /**
     * 短键 → 完整物品 ID 的正向映射。
     * <p>
     * 当 BlockEntity 存储眼球信息时，使用短键作为 BlockState 的 EYE_TYPE 值，
     * 但 BlockEntity 中持久化完整的物品 ID（带命名空间前缀），
     * 以便后续查询和校验。
     */
    public static final Map<String, String> EYE_KEY_TO_FULL_ID = Map.ofEntries(
            Map.entry("ender_eye", "minecraft:ender_eye"),
            Map.entry("mech_eye", "cataclysm:mech_eye"),
            Map.entry("flame_eye", "cataclysm:flame_eye"),
            Map.entry("void_eye", "cataclysm:void_eye"),
            Map.entry("monstrous_eye", "cataclysm:monstrous_eye"),
            Map.entry("abyss_eye", "cataclysm:abyss_eye"),
            Map.entry("desert_eye", "cataclysm:desert_eye"),
            Map.entry("cursed_eye", "cataclysm:cursed_eye"),
            Map.entry("storm_eye", "cataclysm:storm_eye"),
            Map.entry("eye_of_chesed", "fdbosses:eye_of_chesed"),
            Map.entry("eye_of_malkuth", "fdbosses:eye_of_malkuth"),
            Map.entry("eye_of_geburah", "fdbosses:eye_of_geburah")
    );

    /**
     * 完整物品 ID → 短键的反向映射。
     * <p>
     * 在 {@link com.zonlong.beloong.block.DisasterPortalFrame#useItemOn}
     * 中使用：玩家手持物品时，通过此映射快速判断该物品是否为允许的眼球。
     * 由 {@link #EYE_KEY_TO_FULL_ID} 自动推导。
     */
    public static final Map<String, String> FULL_ID_TO_EYE_KEY =
            EYE_KEY_TO_FULL_ID.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

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
