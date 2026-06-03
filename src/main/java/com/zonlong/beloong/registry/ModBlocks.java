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

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(BeLoongCore.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE, BeLoongCore.MODID);

    // 12 种眼球短键（用于 BlockState EnumProperty，不可包含冒号）
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

    // 有眼球时的 EYE_TYPE 可选值（不含 "empty"）
    public static final Set<String> EYE_TYPE_VALUES = Set.copyOf(EYE_KEYS);

    // 短键 -> 完整物品 ID 的映射（用于校验手持物品）
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

    // 完整物品 ID -> 短键
    public static final Map<String, String> FULL_ID_TO_EYE_KEY =
            EYE_KEY_TO_FULL_ID.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

    public static final DeferredBlock<Block> DISASTER_PORTAL_FRAME =
            BLOCKS.register("disaster_portal_frame", DisasterPortalFrame::new);

    public static final DeferredBlock<Block> DISASTER_PORTAL_BLOCK =
            BLOCKS.register("disaster_portal_block", DisasterPortalBlock::new);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DisasterPortalFrameEntity>> DISASTER_PORTAL_FRAME_ENTITY =
            BLOCK_ENTITIES.register("disaster_portal_frame_entity",
                    () -> BlockEntityType.Builder.of(
                            DisasterPortalFrameEntity::new,
                            DISASTER_PORTAL_FRAME.get()
                    ).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DisasterPortalBlockEntity>> DISASTER_PORTAL_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("disaster_portal_block_entity",
                    () -> BlockEntityType.Builder.of(
                            DisasterPortalBlockEntity::new,
                            DISASTER_PORTAL_BLOCK.get()
                    ).build(null));

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        BLOCK_ENTITIES.register(bus);
    }
}
