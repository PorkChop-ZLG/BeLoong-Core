# 天灾维度传送门实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为天灾维度实现一个模仿 End Remastered 的传送门系统，使用固定的 12 种外部模组眼球激活。

**Architecture:** 照搬 End Remastered 的 `AncientPortalFrame` + `AncientPortalFrameEntity` 设计，但眼球校验逻辑从物品类移至框架方块的 `useItemOn()`，激活后放置自定义 `DisasterPortalBlock` 处理双向传送（下行 1:1 坐标+结构生成，上行原版末地返回逻辑）。

**Tech Stack:** Minecraft 1.21.1, NeoForge 21.1.219, Java 21, Mixin (none新增)

**参考模组:** End Remastered (`E:\Minecraft\End-Remastered`)

---

### Task 1: 创建 `registry/ModBlocks.java` — 方块和 BlockEntity 注册

**Files:**
- Create: `src/main/java/com/zonlong/beloong/registry/ModBlocks.java`
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java:32-33`

- [ ] **Step 1: 创建 `ModBlocks.java`**

```java
package com.zonlong.beloong.registry;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.block.DisasterPortalBlock;
import com.zonlong.beloong.block.DisasterPortalFrame;
import com.zonlong.beloong.block.DisasterPortalFrameEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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

    public static final DeferredRegister.DataEntry<BlockEntityType<DisasterPortalFrameEntity>> DISASTER_PORTAL_FRAME_ENTITY =
            BLOCK_ENTITIES.register("disaster_portal_frame_entity",
                    () -> BlockEntityType.Builder.of(
                            DisasterPortalFrameEntity::new,
                            DISASTER_PORTAL_FRAME.get()
                    ).build(null));

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        BLOCK_ENTITIES.register(bus);
    }
}
```

- [ ] **Step 2: 在 `BeLoongCore.java` 构造函数中注册 `ModBlocks`**

找到构造函数中的注册部分，在 `ModItems.register(modEventBus);` 之后添加：

```java
ModBlocks.register(modEventBus);
```

- [ ] **Step 3: 编译验证**

```bash
cd e:/Minecraft/BeLoong-Core && ./gradlew compileJava
```

预期：编译失败（因为引用了尚未创建的 `DisasterPortalFrame`、`DisasterPortalBlock`、`DisasterPortalFrameEntity` 类）。

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/zonlong/beloong/registry/ModBlocks.java src/main/java/com/zonlong/beloong/BeLoongCore.java
git commit -m "feat: 添加 ModBlocks 注册类，定义12种眼球常量映射"
```

---

### Task 2: 创建 `block/DisasterPortalFrameEntity.java` — 框架 BlockEntity

**Files:**
- Create: `src/main/java/com/zonlong/beloong/block/DisasterPortalFrameEntity.java`

- [ ] **Step 1: 创建 BlockEntity 类**

照搬 End Remastered 的 `AncientPortalFrameEntity`：

```java
package com.zonlong.beloong.block;

import com.zonlong.beloong.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class DisasterPortalFrameEntity extends BlockEntity {
    private String eyeId = "empty";

    public DisasterPortalFrameEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.DISASTER_PORTAL_FRAME_ENTITY.get(), pos, state);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("eye_id", this.eyeId);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.eyeId = tag.getString("eye_id");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    @Nullable
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    public void setEyeId(String eyeId) {
        this.eyeId = eyeId;
    }

    public String getEyeId() {
        return this.eyeId;
    }

    public boolean isEmpty() {
        return "empty".equals(this.eyeId);
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd e:/Minecraft/BeLoong-Core && ./gradlew compileJava
```

预期：`ModBlocks` 编译通过，`DisasterPortalFrame` 和 `DisasterPortalBlock` 尚缺失导致编译失败。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/zonlong/beloong/block/DisasterPortalFrameEntity.java
git commit -m "feat: 添加 DisasterPortalFrameEntity — 存储眼球ID的BlockEntity"
```

---

### Task 3: 创建 `block/DisasterPortalFrame.java` — 传送门框架方块

**Files:**
- Create: `src/main/java/com/zonlong/beloong/block/DisasterPortalFrame.java`

- [ ] **Step 1: 创建框架方块类**

照搬 `AncientPortalFrame`，核心差异：`EYE_TYPE` (EnumProperty<String>)、`useItemOn()` 校验外部眼球、激活后放 `DisasterPortalBlock`：

```java
package com.zonlong.beloong.block;

import com.google.common.base.Predicates;
import com.zonlong.beloong.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.level.block.state.pattern.BlockPattern;
import net.minecraft.world.level.block.state.pattern.BlockPatternBuilder;
import net.minecraft.world.level.block.state.predicate.BlockStatePredicate;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class DisasterPortalFrame extends Block implements EntityBlock {
    public static final BooleanProperty HAS_EYE = BlockStateProperties.EYE;
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

    // 13 个值: "empty" + 12 种眼球短键
    private static final List<String> EYE_TYPE_NAMES = new ArrayList<>();
    public static final EnumProperty<String> EYE_TYPE;
    static {
        EYE_TYPE_NAMES.add("empty");
        EYE_TYPE_NAMES.addAll(ModBlocks.EYE_KEYS);
        EYE_TYPE = EnumProperty.create("eye_type", EYE_TYPE_NAMES);
    }

    protected static final VoxelShape BASE_SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 13.0D, 16.0D);
    protected static final VoxelShape EYE_SHAPE = Block.box(4.0D, 13.0D, 4.0D, 12.0D, 16.0D, 12.0D);
    protected static final VoxelShape FULL_SHAPE = Shapes.or(BASE_SHAPE, EYE_SHAPE);

    public static BlockPattern getCompletedPortalShape(boolean requireEyes) {
        Predicate<String> hasEyePredicate = (eyeType) -> requireEyes ? !"empty".equals(eyeType) : true;
        return BlockPatternBuilder.start()
                .aisle("?vvv?", ">???<", ">???<", ">???<", "?^^^?")
                .where('?', BlockInWorld.hasState(BlockStatePredicate.ANY))
                .where('^', BlockInWorld.hasState(
                        BlockStatePredicate.forBlock(ModBlocks.DISASTER_PORTAL_FRAME.get())
                                .where(HAS_EYE, Predicates.alwaysTrue())
                                .where(EYE_TYPE, hasEyePredicate)
                                .where(FACING, Predicates.equalTo(Direction.SOUTH))))
                .where('>', BlockInWorld.hasState(
                        BlockStatePredicate.forBlock(ModBlocks.DISASTER_PORTAL_FRAME.get())
                                .where(HAS_EYE, Predicates.alwaysTrue())
                                .where(EYE_TYPE, hasEyePredicate)
                                .where(FACING, Predicates.equalTo(Direction.WEST))))
                .where('v', BlockInWorld.hasState(
                        BlockStatePredicate.forBlock(ModBlocks.DISASTER_PORTAL_FRAME.get())
                                .where(HAS_EYE, Predicates.alwaysTrue())
                                .where(EYE_TYPE, hasEyePredicate)
                                .where(FACING, Predicates.equalTo(Direction.NORTH))))
                .where('<', BlockInWorld.hasState(
                        BlockStatePredicate.forBlock(ModBlocks.DISASTER_PORTAL_FRAME.get())
                                .where(HAS_EYE, Predicates.alwaysTrue())
                                .where(EYE_TYPE, hasEyePredicate)
                                .where(FACING, Predicates.equalTo(Direction.EAST))))
                .build();
    }

    public DisasterPortalFrame() {
        super(Properties.of()
                .mapColor(MapColor.COLOR_GREEN)
                .instrument(NoteBlockInstrument.BASEDRUM)
                .sound(SoundType.GLASS)
                .lightLevel(s -> 1)
                .strength(-1.0F, 3600000.0F)
                .noLootTable()
                .pushReaction(PushReaction.BLOCK));
        registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(HAS_EYE, false)
                .setValue(EYE_TYPE, "empty"));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return state.getValue(HAS_EYE) ? FULL_SHAPE : BASE_SHAPE;
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return this.defaultBlockState()
                .setValue(FACING, ctx.getHorizontalDirection().getOpposite())
                .setValue(HAS_EYE, false)
                .setValue(EYE_TYPE, "empty");
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HAS_EYE, FACING, EYE_TYPE);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DisasterPortalFrameEntity(pos, state);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                          BlockPos pos, Player player,
                                          net.minecraft.world.InteractionHand hand,
                                          net.minecraft.world.phys.BlockHitResult hitResult) {
        if (state.getValue(HAS_EYE)) {
            return InteractionResult.PASS;
        }

        String heldItemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
        String eyeKey = ModBlocks.FULL_ID_TO_EYE_KEY.get(heldItemId);

        if (eyeKey == null) {
            return InteractionResult.PASS;
        }

        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        // 去重检查
        if (!isEyeAbsent(level, pos, eyeKey)) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("block.beloong.disaster_portal_frame.eye_duplicate"),
                    true);
            return InteractionResult.FAIL;
        }

        // 设置方块状态
        BlockState newState = state.setValue(HAS_EYE, true).setValue(EYE_TYPE, eyeKey);
        level.setBlock(pos, newState, 3);
        level.updateNeighbourForOutputSignal(pos, this);

        // 同步 BlockEntity
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof DisasterPortalFrameEntity frameEntity) {
            frameEntity.setEyeId(ModBlocks.EYE_KEY_TO_FULL_ID.get(eyeKey));
            frameEntity.setChanged();
        }

        stack.consume(1, player);
        level.playSound(null, pos, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);

        // 检查是否全部激活
        BlockPattern.BlockPatternMatch match = getCompletedPortalShape(true).find(level, pos);
        if (match != null) {
            BlockPos frontTopLeft = match.getFrontTopLeft().offset(-3, 0, -3);
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    level.setBlock(frontTopLeft.offset(i, 0, j),
                            ModBlocks.DISASTER_PORTAL_BLOCK.get().defaultBlockState(), 3);
                }
            }
            level.globalLevelEvent(1038, frontTopLeft.offset(1, 0, 1), 0);
        }

        return InteractionResult.CONSUME;
    }

    /**
     * 检查 portal 中是否已存在相同的眼球类型。
     * @return true 表示该眼球尚未被使用，可以放置
     */
    private static boolean isEyeAbsent(Level level, BlockPos clickedPos, String eyeKeyToPlace) {
        BlockPattern.BlockPatternMatch match = getCompletedPortalShape(false).find(level, clickedPos);
        if (match == null) return true;

        BlockPos frontTopLeft = match.getFrontTopLeft().offset(-4, 0, -4);
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                BlockPos checkPos = frontTopLeft.offset(i, 0, j);
                BlockState checkState = level.getBlockState(checkPos);
                if (checkState.is(ModBlocks.DISASTER_PORTAL_FRAME.get())
                        && eyeKeyToPlace.equals(checkState.getValue(EYE_TYPE))) {
                    return false;
                }
            }
        }
        return true;
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd e:/Minecraft/BeLoong-Core && ./gradlew compileJava
```

预期：仅 `DisasterPortalBlock` 缺失导致编译失败。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/zonlong/beloong/block/DisasterPortalFrame.java
git commit -m "feat: 添加 DisasterPortalFrame — 传送门框架方块（眼球放置/去重/激活）"
```

---

### Task 4: 创建 `block/DisasterPortalBlock.java` — 传送门方块

**Files:**
- Create: `src/main/java/com/zonlong/beloong/block/DisasterPortalBlock.java`

- [ ] **Step 1: 创建传送门方块类**

```java
package com.zonlong.beloong.block;

import com.zonlong.beloong.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.VoxelShape;

public class DisasterPortalBlock extends Block {
    protected static final VoxelShape SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 12.0D, 16.0D);

    public DisasterPortalBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .noCollission()
                .lightLevel(s -> 11)
                .strength(-1.0F, 3600000.0F)
                .noLootTable()
                .pushReaction(PushReaction.BLOCK)
                .sound(SoundType.GLASS));
    }

    @Override
    protected net.minecraft.world.phys.shapes.VoxelShape getShape(
            BlockState state, net.minecraft.world.level.BlockGetter level,
            BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (level.isClientSide) return;
        if (!(entity instanceof ServerPlayer player)) return;

        long cooldownEnd = player.getPersistentData().getLong("beloong_portal_cooldown");
        if (cooldownEnd > level.getGameTime()) return;

        String currentDim = level.dimension().location().toString();
        String sourceDim = Config.DisasterPortal.sourceDimension.get();

        if (currentDim.equals(sourceDim)) {
            // 下行：主世界 → 天灾
            ResourceLocation targetDimId = ResourceLocation.tryParse(
                    Config.DisasterPortal.disasterDimension.get());
            if (targetDimId == null) return;

            ServerLevel targetLevel = player.server.getLevel(
                    net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.DIMENSION, targetDimId));
            if (targetLevel == null) return;

            double targetX = player.getX();
            double targetZ = player.getZ();
            int blockX = (int) Math.floor(targetX);
            int blockZ = (int) Math.floor(targetZ);

            targetLevel.getChunk(blockX >> 4, blockZ >> 4);
            int topY = targetLevel.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
            double targetY = topY > targetLevel.getMinBuildHeight() ? topY + 1.0 : 64.5;

            if (player.isPassenger()) player.stopRiding();

            player.teleportTo(targetLevel, targetX, targetY, targetZ,
                    java.util.Set.of(), player.getYRot(), player.getXRot());
            player.fallDistance = 0;

            // 放置返回结构
            BlockPos structurePos = new BlockPos(blockX, topY, blockZ);
            if (targetLevel.getBlockState(structurePos).isAir()) {
                placeReturnStructure(targetLevel, structurePos);
            }
        } else {
            // 上行：天灾 → 主世界（原版末地返回逻辑）
            BlockPos respawnPos = player.getRespawnPosition();
            net.minecraft.resources.ResourceKey<Level> respawnDim = player.getRespawnDimension();

            ServerLevel overworld = player.server.getLevel(Level.OVERWORLD);
            if (respawnPos == null || respawnDim == null) {
                respawnPos = overworld != null
                        ? overworld.getSharedSpawnPos()
                        : new BlockPos(0, 64, 0);
                respawnDim = Level.OVERWORLD;
            }

            ServerLevel targetLevel = player.server.getLevel(respawnDim);
            if (targetLevel == null) {
                targetLevel = overworld;
            }
            if (targetLevel == null) return;

            if (player.isPassenger()) player.stopRiding();

            player.teleportTo(targetLevel,
                    respawnPos.getX() + 0.5, respawnPos.getY(), respawnPos.getZ() + 0.5,
                    java.util.Set.of(), player.getYRot(), player.getXRot());
            player.fallDistance = 0;
        }

        int cooldown = Config.DisasterPortal.teleportCooldownTicks.get();
        player.getPersistentData().putLong("beloong_portal_cooldown", level.getGameTime() + cooldown);
    }

    private void placeReturnStructure(ServerLevel level, BlockPos pos) {
        String templatePath = Config.DisasterPortal.returnStructureTemplate.get();
        ResourceLocation templateId = ResourceLocation.tryParse(templatePath);
        if (templateId == null) return;

        net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager
                templateManager = level.getStructureManager();
        var template = templateManager.get(templateId);
        if (template.isPresent()) {
            template.get().placeInWorld(level, pos, pos,
                    new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(),
                    level.getRandom(), 2);
        }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd e:/Minecraft/BeLoong-Core && ./gradlew compileJava
```

预期：`Config.DisasterPortal` 尚不存在导致编译失败。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/zonlong/beloong/block/DisasterPortalBlock.java
git commit -m "feat: 添加 DisasterPortalBlock — 传送门方块（双向传送+结构生成）"
```

---

### Task 5: 更新 `Config.java` — 添加传送门配置

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/Config.java`

- [ ] **Step 1: 添加 `DisasterPortal` 内部类**

在 `Config.java` 中 `TreasureGrowth` 内部类之后（约第 87 行）、`static {}` 块之前，添加：

```java
public static final class DisasterPortal {
    private DisasterPortal() {}

    public static ConfigValue<List<? extends String>> eyeItems;
    public static ConfigValue<String> sourceDimension;
    public static ConfigValue<String> disasterDimension;
    public static ConfigValue<String> returnStructureTemplate;
    public static ConfigValue<Integer> teleportCooldownTicks;
}
```

- [ ] **Step 2: 在 `static {}` 块末尾（`dimension_transport` pop 之后）添加配置赋值**

```java
// 添加到灾难传送门部分
SERVER_BUILDER.push("disaster_portal");

DisasterPortal.eyeItems = SERVER_BUILDER
        .comment("激活传送门所需的 12 种眼球物品 ID（顺序可任意）")
        .defineList("eyeItems",
                List.of(
                        "minecraft:ender_eye",
                        "cataclysm:mech_eye",
                        "cataclysm:flame_eye",
                        "cataclysm:void_eye",
                        "cataclysm:monstrous_eye",
                        "cataclysm:abyss_eye",
                        "cataclysm:desert_eye",
                        "cataclysm:cursed_eye",
                        "cataclysm:storm_eye",
                        "fdbosses:eye_of_chesed",
                        "fdbosses:eye_of_malkuth",
                        "fdbosses:eye_of_geburah"
                ),
                s -> s instanceof String str && str.contains(":"));

DisasterPortal.sourceDimension = SERVER_BUILDER
        .comment("源维度（从此维度使用传送门将传送到天灾维度）")
        .define("sourceDimension", "minecraft:overworld");

DisasterPortal.disasterDimension = SERVER_BUILDER
        .comment("天灾维度 ID")
        .define("disasterDimension", "beloong:disaster");

DisasterPortal.returnStructureTemplate = SERVER_BUILDER
        .comment("返回传送门结构模板 ID")
        .define("returnStructureTemplate", "beloong:disaster/return_portal");

DisasterPortal.teleportCooldownTicks = SERVER_BUILDER
        .comment("传送冷却时间（ticks），防止循环传送")
        .defineInRange("teleportCooldownTicks", 100, 0, 72000);

SERVER_BUILDER.pop(); // disaster_portal
```

- [ ] **Step 3: 编译验证**

```bash
cd e:/Minecraft/BeLoong-Core && ./gradlew compileJava
```

预期：编译通过。

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/zonlong/beloong/Config.java
git commit -m "feat: 添加 DisasterPortal 配置节（12眼球ID/维度/结构模板/冷却）"
```

---

### Task 6: 创建资源文件 — 模型、BlockState、纹理、语言

**Files:**
- Create: `src/main/resources/assets/beloong/blockstates/disaster_portal_frame.json`
- Create: `src/main/resources/assets/beloong/blockstates/disaster_portal_block.json`
- Create: `src/main/resources/assets/beloong/models/block/disaster_portal_frame.json`
- Create: `src/main/resources/assets/beloong/models/block/disaster_portal_block.json`
- Create: `src/main/resources/assets/beloong/models/item/disaster_portal_frame.json`
- Create: `src/main/resources/assets/beloong/textures/block/disaster_portal_frame.png`
- Create: `src/main/resources/assets/beloong/textures/block/disaster_portal_frame_eye.png` (默认眼球纹理)
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`

- [ ] **Step 1: 创建框架 BlockState JSON**

`blockstates/disaster_portal_frame.json`：
```json
{
  "variants": {
    "facing=north,eye=false,eye_type=empty": { "model": "beloong:block/disaster_portal_frame" },
    "facing=east,eye=false,eye_type=empty": { "model": "beloong:block/disaster_portal_frame", "y": 90 },
    "facing=south,eye=false,eye_type=empty": { "model": "beloong:block/disaster_portal_frame", "y": 180 },
    "facing=west,eye=false,eye_type=empty": { "model": "beloong:block/disaster_portal_frame", "y": 270 },
    "facing=north,eye=true,eye_type=ender_eye": { "model": "beloong:block/disaster_portal_frame_ender_eye" },
    "facing=east,eye=true,eye_type=ender_eye": { "model": "beloong:block/disaster_portal_frame_ender_eye", "y": 90 },
    "facing=south,eye=true,eye_type=ender_eye": { "model": "beloong:block/disaster_portal_frame_ender_eye", "y": 180 },
    "facing=west,eye=true,eye_type=ender_eye": { "model": "beloong:block/disaster_portal_frame_ender_eye", "y": 270 },
    "facing=north,eye=true,eye_type=mech_eye": { "model": "beloong:block/disaster_portal_frame_mech_eye" },
    "facing=east,eye=true,eye_type=mech_eye": { "model": "beloong:block/disaster_portal_frame_mech_eye", "y": 90 },
    "facing=south,eye=true,eye_type=mech_eye": { "model": "beloong:block/disaster_portal_frame_mech_eye", "y": 180 },
    "facing=west,eye=true,eye_type=mech_eye": { "model": "beloong:block/disaster_portal_frame_mech_eye", "y": 270 },
    "facing=north,eye=true,eye_type=flame_eye": { "model": "beloong:block/disaster_portal_frame_flame_eye" },
    "facing=east,eye=true,eye_type=flame_eye": { "model": "beloong:block/disaster_portal_frame_flame_eye", "y": 90 },
    "facing=south,eye=true,eye_type=flame_eye": { "model": "beloong:block/disaster_portal_frame_flame_eye", "y": 180 },
    "facing=west,eye=true,eye_type=flame_eye": { "model": "beloong:block/disaster_portal_frame_flame_eye", "y": 270 },
    "facing=north,eye=true,eye_type=void_eye": { "model": "beloong:block/disaster_portal_frame_void_eye" },
    "facing=east,eye=true,eye_type=void_eye": { "model": "beloong:block/disaster_portal_frame_void_eye", "y": 90 },
    "facing=south,eye=true,eye_type=void_eye": { "model": "beloong:block/disaster_portal_frame_void_eye", "y": 180 },
    "facing=west,eye=true,eye_type=void_eye": { "model": "beloong:block/disaster_portal_frame_void_eye", "y": 270 },
    "facing=north,eye=true,eye_type=monstrous_eye": { "model": "beloong:block/disaster_portal_frame_monstrous_eye" },
    "facing=east,eye=true,eye_type=monstrous_eye": { "model": "beloong:block/disaster_portal_frame_monstrous_eye", "y": 90 },
    "facing=south,eye=true,eye_type=monstrous_eye": { "model": "beloong:block/disaster_portal_frame_monstrous_eye", "y": 180 },
    "facing=west,eye=true,eye_type=monstrous_eye": { "model": "beloong:block/disaster_portal_frame_monstrous_eye", "y": 270 },
    "facing=north,eye=true,eye_type=abyss_eye": { "model": "beloong:block/disaster_portal_frame_abyss_eye" },
    "facing=east,eye=true,eye_type=abyss_eye": { "model": "beloong:block/disaster_portal_frame_abyss_eye", "y": 90 },
    "facing=south,eye=true,eye_type=abyss_eye": { "model": "beloong:block/disaster_portal_frame_abyss_eye", "y": 180 },
    "facing=west,eye=true,eye_type=abyss_eye": { "model": "beloong:block/disaster_portal_frame_abyss_eye", "y": 270 },
    "facing=north,eye=true,eye_type=desert_eye": { "model": "beloong:block/disaster_portal_frame_desert_eye" },
    "facing=east,eye=true,eye_type=desert_eye": { "model": "beloong:block/disaster_portal_frame_desert_eye", "y": 90 },
    "facing=south,eye=true,eye_type=desert_eye": { "model": "beloong:block/disaster_portal_frame_desert_eye", "y": 180 },
    "facing=west,eye=true,eye_type=desert_eye": { "model": "beloong:block/disaster_portal_frame_desert_eye", "y": 270 },
    "facing=north,eye=true,eye_type=cursed_eye": { "model": "beloong:block/disaster_portal_frame_cursed_eye" },
    "facing=east,eye=true,eye_type=cursed_eye": { "model": "beloong:block/disaster_portal_frame_cursed_eye", "y": 90 },
    "facing=south,eye=true,eye_type=cursed_eye": { "model": "beloong:block/disaster_portal_frame_cursed_eye", "y": 180 },
    "facing=west,eye=true,eye_type=cursed_eye": { "model": "beloong:block/disaster_portal_frame_cursed_eye", "y": 270 },
    "facing=north,eye=true,eye_type=storm_eye": { "model": "beloong:block/disaster_portal_frame_storm_eye" },
    "facing=east,eye=true,eye_type=storm_eye": { "model": "beloong:block/disaster_portal_frame_storm_eye", "y": 90 },
    "facing=south,eye=true,eye_type=storm_eye": { "model": "beloong:block/disaster_portal_frame_storm_eye", "y": 180 },
    "facing=west,eye=true,eye_type=storm_eye": { "model": "beloong:block/disaster_portal_frame_storm_eye", "y": 270 },
    "facing=north,eye=true,eye_type=eye_of_chesed": { "model": "beloong:block/disaster_portal_frame_eye_of_chesed" },
    "facing=east,eye=true,eye_type=eye_of_chesed": { "model": "beloong:block/disaster_portal_frame_eye_of_chesed", "y": 90 },
    "facing=south,eye=true,eye_type=eye_of_chesed": { "model": "beloong:block/disaster_portal_frame_eye_of_chesed", "y": 180 },
    "facing=west,eye=true,eye_type=eye_of_chesed": { "model": "beloong:block/disaster_portal_frame_eye_of_chesed", "y": 270 },
    "facing=north,eye=true,eye_type=eye_of_malkuth": { "model": "beloong:block/disaster_portal_frame_eye_of_malkuth" },
    "facing=east,eye=true,eye_type=eye_of_malkuth": { "model": "beloong:block/disaster_portal_frame_eye_of_malkuth", "y": 90 },
    "facing=south,eye=true,eye_type=eye_of_malkuth": { "model": "beloong:block/disaster_portal_frame_eye_of_malkuth", "y": 180 },
    "facing=west,eye=true,eye_type=eye_of_malkuth": { "model": "beloong:block/disaster_portal_frame_eye_of_malkuth", "y": 270 },
    "facing=north,eye=true,eye_type=eye_of_geburah": { "model": "beloong:block/disaster_portal_frame_eye_of_geburah" },
    "facing=east,eye=true,eye_type=eye_of_geburah": { "model": "beloong:block/disaster_portal_frame_eye_of_geburah", "y": 90 },
    "facing=south,eye=true,eye_type=eye_of_geburah": { "model": "beloong:block/disaster_portal_frame_eye_of_geburah", "y": 180 },
    "facing=west,eye=true,eye_type=eye_of_geburah": { "model": "beloong:block/disaster_portal_frame_eye_of_geburah", "y": 270 }
  }
}
```

- [ ] **Step 2: 创建传送门方块 BlockState JSON**

`blockstates/disaster_portal_block.json`：
```json
{
  "variants": {
    "": { "model": "beloong:block/disaster_portal_block" }
  }
}
```

- [ ] **Step 3: 创建框架基础模型**

`models/block/disaster_portal_frame.json`（基础空框架模型 — 照搬原版 `end_portal_frame.json`）：
```json
{
  "parent": "minecraft:block/end_portal_frame",
  "textures": {
    "particle": "beloong:block/disaster_portal_frame_side",
    "bottom": "beloong:block/disaster_portal_frame_bottom",
    "top": "beloong:block/disaster_portal_frame_top",
    "side": "beloong:block/disaster_portal_frame_side"
  }
}
```

- [ ] **Step 4: 为每种眼球创建带眼球纹理的框架模型**

创建 `models/block/disaster_portal_frame_ender_eye.json`（照搬 `end_portal_frame_filled.json`）：
```json
{
  "parent": "minecraft:block/end_portal_frame_filled",
  "textures": {
    "particle": "beloong:block/disaster_portal_frame_side",
    "bottom": "beloong:block/disaster_portal_frame_bottom",
    "top": "beloong:block/disaster_portal_frame_top_ender_eye",
    "side": "beloong:block/disaster_portal_frame_side",
    "eye": "beloong:block/disaster_portal_frame_eye_ender_eye"
  }
}
```

为其余 11 种眼球创建类似模型，替换 `_ender_eye` 后缀为对应眼球键名。

- [ ] **Step 5: 创建传送门方块模型**

`models/block/disaster_portal_block.json`：
```json
{
  "parent": "block/end_portal",
  "textures": {
    "particle": "block/obsidian",
    "portal": "beloong:block/disaster_portal"
  }
}
```

- [ ] **Step 6: 创建框架物品模型**

`models/item/disaster_portal_frame.json`：
```json
{
  "parent": "beloong:block/disaster_portal_frame"
}
```

- [ ] **Step 7: 创建纹理占位文件**

创建以下纹理文件（先用纯色占位，后续替换为美术资源）：
- `textures/block/disaster_portal_frame_side.png`
- `textures/block/disaster_portal_frame_bottom.png`
- `textures/block/disaster_portal_frame_top.png`
- `textures/block/disaster_portal.png`

并为每种眼球创建 top 纹理：
- `textures/block/disaster_portal_frame_top_ender_eye.png`
- `textures/block/disaster_portal_frame_eye_ender_eye.png`
- ... (其余 11 种)

- [ ] **Step 8: 更新语言文件**

`lang/zh_cn.json`（已有文件追加）：
```json
{
  "block.beloong.disaster_portal_frame": "天灾传送门框架",
  "block.beloong.disaster_portal_block": "天灾传送门",
  "block.beloong.disaster_portal_frame.eye_duplicate": "该眼球已放置在传送门中"
}
```

`lang/en_us.json`（已有文件追加）：
```json
{
  "block.beloong.disaster_portal_frame": "Disaster Portal Frame",
  "block.beloong.disaster_portal_block": "Disaster Portal",
  "block.beloong.disaster_portal_frame.eye_duplicate": "This eye is already placed in the portal"
}
```

- [ ] **Step 9: 编译验证并运行测试**

```bash
cd e:/Minecraft/BeLoong-Core && ./gradlew compileJava
```

预期：编译通过。

- [ ] **Step 10: 提交**

```bash
git add src/main/resources/
git commit -m "feat: 添加天灾传送门资源文件（BlockState/模型/纹理/语言）"
```

---

### Task 7: 最终编译验证与修复

- [ ] **Step 1: 完整编译**

```bash
cd e:/Minecraft/BeLoong-Core && ./gradlew build
```

修复所有编译错误。

- [ ] **Step 2: 启动游戏验证**

在开发环境中启动 Minecraft，测试以下场景：
1. `/give @p beloong:disaster_portal_frame` — 获取传送门框架
2. 摆放 5×5 环形框架，用 12 种眼球右键激活 — 传送门方块是否出现
3. 同种眼球重复放置 — 是否提示"已放置"
4. 走进传送门 — 是否传送到天灾维度，坐标是否 1:1
5. 结构模板是否在目标位置生成
6. 从天灾维度走进返回传送门 — 是否回到出生点

- [ ] **Step 3: 提交修复**

```bash
git add -A
git commit -m "fix: 最终编译和运行时修复"
```

---

### Task 8: 结构模板（返回传送门）

**Files:**
- Create: `src/main/resources/data/beloong/structure/disaster/return_portal.nbt`

此 Task 使用原版结构方块生成和导出 `.nbt` 模板。操作步骤：

- [ ] **Step 1: 在开发环境中搭建返回传送门**

1. 启动 Minecraft，进入一个超平坦世界
2. 用 `/give` 获取命令方块和结构方块
3. 在目标位置搭建一个 5×5 环形天灾传送门框架，12 帧均已激活
4. 中间 3×3 为天灾传送门方块
5. 使用结构方块选中整个传送门结构（含框架 + 传送门方块）
6. 保存为 `beloong:disaster/return_portal`

- [ ] **Step 2: 导出 .nbt 文件**

将结构方块导出的 `.nbt` 文件放入：
```
src/main/resources/data/beloong/structure/disaster/return_portal.nbt
```

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/data/beloong/structure/disaster/return_portal.nbt
git commit -m "feat: 添加返回传送门结构模板"
```
