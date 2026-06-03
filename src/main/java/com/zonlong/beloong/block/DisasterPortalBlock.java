package com.zonlong.beloong.block;

import com.zonlong.beloong.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 天灾传送门方块。
 * <p>
 * 当玩家接触到该方块时（{@link #entityInside}），执行双向传送：
 * <ul>
 *   <li><b>下行（任意维度 → 天灾维度）</b>：1:1 坐标传送，
 *       并在目标位置生成一个可配置的结构模板（通常包含一个返回传送门）。</li>
 *   <li><b>上行（天灾维度 → 主世界）</b>：照搬原版末地返回逻辑，
 *       传送到玩家的重生点（床/重生锚），无重生点时使用世界出生点。</li>
 * </ul>
 * <p>
 * 该方块继承自 {@link EntityBlock}，每个方块实例绑定一个
 * {@link DisasterPortalBlockEntity}，由 {@link com.zonlong.beloong.client.DisasterPortalRenderer}
 * 使用原版末地传送门的着色器（{@code RenderType.endPortal()}）来渲染旋转星空效果。
 * 方块自身的渲染形状设为 {@link RenderShape#INVISIBLE}，
 * 确保只显示 BlockEntity 渲染器的输出，不显示方块模型。
 */
public class DisasterPortalBlock extends Block implements EntityBlock {

    /** 天灾维度的硬编码 ID，所有非天灾维度的传送目标。 */
    private static final String DISASTER_DIM = "beloong:disaster";

    /** 碰撞箱：16×12×16 像素，和原版末地传送门一致。 */
    protected static final VoxelShape SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 12.0D, 16.0D);

    public DisasterPortalBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .noCollission()                          // 无碰撞，玩家可以穿过
                .lightLevel(s -> 11)                     // 发光等级 11，和原版末地传送门一致
                .strength(-1.0F, 3600000.0F)             // 不可破坏（基岩级别）
                .noLootTable()                            // 无掉落物
                .pushReaction(PushReaction.BLOCK)         // 不能被活塞推动
                .sound(SoundType.GLASS));                 // 玻璃音效
    }

    /**
     * 返回 {@link RenderShape#INVISIBLE}，禁止渲染方块模型。
     * 传送门的视觉效果完全由 {@link com.zonlong.beloong.client.DisasterPortalRenderer} 负责。
     */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level,
                                  BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext ctx) {
        return SHAPE;
    }

    /**
     * 为该方块创建对应的 BlockEntity。
     * 虽然方块本身不存储数据，但 BlockEntity 是渲染器（{@code DisasterPortalRenderer}）的载体。
     */
    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DisasterPortalBlockEntity(pos, state);
    }

    /**
     * 玩家接触到传送门方块时触发。
     * <p>
     * <b>传送逻辑：</b>
     * <ol>
     *   <li>仅服务端处理，客户端忽略。</li>
     *   <li>检查冷却（{@code beloong_portal_cooldown}），防止循环传送。</li>
     *   <li>判断当前维度：
     *     <ul>
     *       <li><b>非天灾维度</b> → 传送到 {@code beloong:disaster}，
     *           保持 X/Z 坐标不变（1:1），Y 使用目标维度的高度图查找安全落脚点。
     *           传送完成后在目标位置放置"返回传送门"结构模板。</li>
     *       <li><b>天灾维度</b> → 传送到主世界的玩家重生点（床/重生锚），
     *           无重生点时回退到世界出生点（和原版末地返回传送门行为一致）。</li>
     *     </ul>
     *   </li>
     *   <li>设置冷却时间，防止短时间内的重复传送。</li>
     * </ol>
     */
    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        // 仅服务端处理传送逻辑
        if (level.isClientSide) return;
        if (!(entity instanceof ServerPlayer player)) return;

        // 冷却检查：NBT 中存储冷却结束的游戏刻，当前游戏刻小于它则跳过
        long cooldownEnd = player.getPersistentData().getLong("beloong_portal_cooldown");
        if (cooldownEnd > level.getGameTime()) return;

        String currentDim = level.dimension().location().toString();

        if (!currentDim.equals(DISASTER_DIM)) {
            // ==========================================
            // 下行：任意维度 → 天灾维度（beloong:disaster）
            // 坐标 1:1 同步，并在目标位置生成结构模板
            // ==========================================
            ResourceLocation targetDimId = ResourceLocation.tryParse(DISASTER_DIM);
            if (targetDimId == null) return;

            // 获取天灾维度对应的 ServerLevel
            ServerLevel targetLevel = player.server.getLevel(
                    net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.DIMENSION, targetDimId));
            if (targetLevel == null) return;

            // 1:1 坐标映射：保持 X/Z 不变
            double targetX = player.getX();
            double targetZ = player.getZ();
            int blockX = (int) Math.floor(targetX);
            int blockZ = (int) Math.floor(targetZ);

            // 确保目标区块已加载，否则高度图查询不到数据
            targetLevel.getChunk(blockX >> 4, blockZ >> 4);
            // MOTION_BLOCKING：返回最高非空气方块的 Y（例如草方块顶部）
            int topY = targetLevel.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
            // 玩家传送到地表上方 1 格
            double targetY = topY + 1.0;

            // 解除骑乘（如骑马、骑龙），防止传送异常
            if (player.isPassenger()) player.stopRiding();

            // 执行传送（保留朝向）
            player.teleportTo(targetLevel, targetX, targetY, targetZ,
                    java.util.Set.of(), player.getYRot(), player.getXRot());
            player.fallDistance = 0; // 重置摔落距离，防止传送前的坠落伤害带到目标维度

            // 在目标位置放置返回传送门结构模板
            // 结构放置位置 = 玩家落地坐标 + 配置偏移
            int offsetX = Config.DisasterPortal.structureOffsetX.get();
            int offsetY = Config.DisasterPortal.structureOffsetY.get();
            int offsetZ = Config.DisasterPortal.structureOffsetZ.get();
            BlockPos structurePos = new BlockPos(blockX + offsetX, topY + offsetY, blockZ + offsetZ);
            placeReturnStructure(targetLevel, structurePos);
        } else {
            // ==========================================
            // 上行：天灾维度 → 主世界
            // 使用原版末地返回传送门逻辑：回到玩家出生点
            // ==========================================
            BlockPos respawnPos = player.getRespawnPosition();        // 床或重生锚位置
            net.minecraft.resources.ResourceKey<Level> respawnDim = player.getRespawnDimension(); // 重生维度

            ServerLevel overworld = player.server.getLevel(Level.OVERWORLD);
            // 如果玩家没有设置重生点（例如从未睡过觉），回退到世界出生点
            if (respawnPos == null || respawnDim == null) {
                respawnPos = overworld != null
                        ? overworld.getSharedSpawnPos()               // 世界出生点
                        : new BlockPos(0, 64, 0);                      // 最终兜底
                respawnDim = Level.OVERWORLD;
            }

            // 获取重生维度的 ServerLevel
            ServerLevel targetLevel = player.server.getLevel(respawnDim);
            if (targetLevel == null) {
                targetLevel = overworld; // 回退到主世界
            }
            if (targetLevel == null) return;

            if (player.isPassenger()) player.stopRiding();

            // 传送到重生点（中心对齐 +0.5）
            player.teleportTo(targetLevel,
                    respawnPos.getX() + 0.5, respawnPos.getY(), respawnPos.getZ() + 0.5,
                    java.util.Set.of(), player.getYRot(), player.getXRot());
            player.fallDistance = 0;
        }

        // 设置冷却：将冷却结束时间写入玩家持久化 NBT
        int cooldown = Config.DisasterPortal.teleportCooldownTicks.get();
        player.getPersistentData().putLong("beloong_portal_cooldown", level.getGameTime() + cooldown);
    }

    /**
     * 在指定位置放置返回传送门结构模板。
     * <p>
     * 结构模板是使用原版结构方块导出并保存为 {@code .nbt} 文件的预制建筑。
     * 模板路径由配置文件 {@code disaster_portal.returnStructureTemplate} 指定，
     * 默认值为 {@code beloong:disaster/return_portal}，
     * 对应资源文件 {@code data/beloong/structure/disaster/return_portal.nbt}。
     * <p>
     * 放置使用 {@link net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings}
     * 的默认设置（不对结构进行旋转或镜像），使用 {@code level.getRandom()} 作为随机源。
     *
     * @param level 目标维度（天灾维度）的 ServerLevel
     * @param pos   结构模板的放置原点（对应结构方块的锚点位置）
     */
    private void placeReturnStructure(ServerLevel level, BlockPos pos) {
        // 从配置读取结构模板资源路径，如 "beloong:disaster/return_portal"
        String templatePath = Config.DisasterPortal.returnStructureTemplate.get();
        ResourceLocation templateId = ResourceLocation.tryParse(templatePath);
        if (templateId == null) return;

        // 通过 StructureTemplateManager 加载 .nbt 结构文件
        net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager
                templateManager = level.getStructureManager();
        var template = templateManager.get(templateId);

        // 如果模板存在，将其放置到世界中
        // placeInWorld 的参数：目标World, 放置位置, 锚点位置, 放置设置, 随机源, 更新标志(2=发送方块更新)
        if (template.isPresent()) {
            template.get().placeInWorld(level, pos, pos,
                    new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(),
                    level.getRandom(), 2);
        }
    }
}
