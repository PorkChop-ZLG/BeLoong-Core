package com.zonlong.beloong.dreadking;

import by.dragonsurvivalteam.dragonsurvival.registry.DSBlocks;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.entity.DreadKingRitualMarker;
import com.zonlong.beloong.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 黯影宝库「死王仪式」的编排层：结构判定 → 计算刷怪点 → 生成标记实体。
 *
 * <p>三层架构中的中间层（见 {@code docs/plans/2026-09-20-dark-vault-dread-king-ritual-design.md} §三）：
 * <ul>
 *   <li>上游（检测层）{@code mixin/minecraft/DreadKingRitualTriggerMixin} 只负责回答
 *       「暗影钥匙是否被真正接受」，并把 {@code (level, pos, player)} 交到这里</li>
 *   <li>下游（执行层）{@link DreadKingRitualMarker} 只负责「计时 + 播音 + 召唤」</li>
 * </ul>
 * 因此<b>本类是全功能唯一持有配置与结构知识的地方</b>——「宝库改放到别的结构」只需改配置。
 *
 * <h2>为什么锚点必须是宝库顶</h2>
 * 标记实体自己就是锚点（它召出的死王落在它脚下），所以坐标不需要跨 17.6 秒携带——
 * 这正是本方案取代第一版「药水效果」设计后省掉的最大一块复杂度。
 */
public final class DreadKingRitualStarter {

    private static final Logger LOGGER = LoggerFactory.getLogger(DreadKingRitualStarter.class);

    /**
     * 死者之王的碰撞箱宽高。
     * <p>
     * 与铁魔法 {@code EntityRegistry.DEAD_KING} 的 {@code .sized(.9f, 3.5f)} 保持一致。
     * 它决定「宝库顶要有多高净空才放得下」——城堡室内常见 3 格天花板，故需要净空扫描。
     */
    private static final double BOSS_WIDTH = 0.9D;
    private static final double BOSS_HEIGHT = 3.5D;

    /** 自宝库顶向上寻找净空的最大格数，超过则退回宝库顶并告警。 */
    private static final int MAX_UPWARD_SCAN = 8;

    private DreadKingRitualStarter() {}

    /**
     * 在某次「暗影钥匙被接受」之后尝试开启仪式。
     * <p>
     * <b>三道闸门，缺一不可</b>（顺序即代码顺序）：
     * <ol>
     *   <li>配置开关 {@code enabled}；</li>
     *   <li><b>方块身份</b>：必须真的是龙之生存的黯影宝库（{@link DSBlocks#DARK_VAULT}）——
     *       见下方「为什么必须判方块身份」；</li>
     *   <li><b>结构</b>：宝库必须落在配置的结构拼图块内。</li>
     * </ol>
     *
     * <h2>为什么必须判方块身份（2026-09-21 缺陷修复）</h2>
     * 上游检测层注入的是 {@code VaultBlockEntity.Server} —— 那是<b>所有宝库共用的</b>方块实体。
     * 龙之生存注册了<b>三个</b>宝库方块（黯影宝库 {@code dragonsurvival:dark_vault}、
     * 圣辉宝库 {@code light_vault}、猎人宝库 {@code hunter_vault}），并且通过 NeoForge 的
     * {@code BlockEntityTypeAddBlocksEvent} 把它们全部挂到 {@code BlockEntityType.VAULT} 上
     * ⇒ 这三个方块开箱时都会走到本方法，原版试炼宝库（{@code minecraft:vault}）同样如此。
     * <p>
     * 修复前本方法<b>只</b>判结构，于是「在目标结构内开任意宝库都会召唤不祥死者之王」——
     * 与需求「只有玩家开启黯影宝库时才召唤」不符。现在补上身份判据。
     *
     * <p><b>无副作用保证</b>：开关关闭、不是黯影宝库、结构不匹配、结构 ID 无法解析时都直接返回，
     * 宝库照常开箱，不产生任何标记实体。
     *
     * @param level     发生开箱的服务端世界
     * @param vaultPos  宝库方块自身的位置（不是宝库顶）
     * @param vaultState 该宝库的方块状态（用于判定它是不是龙之生存的黯影宝库）
     * @param player    真正插入钥匙的玩家
     */
    public static void start(ServerLevel level, BlockPos vaultPos, BlockState vaultState, ServerPlayer player) {
        if (!Config.DreadKingRitual.enabled.get()) {
            return;
        }

        // 先算好方块身份（一次方块查表），但把日志留在结构判定之后 —— 只在「目标结构内开了别的宝库」
        // 这种真正值得注意的情况下才出声，避免世界各处的宝库都刷一条日志。
        boolean isDarkVault = vaultState.is(DSBlocks.DARK_VAULT.get());

        if (!isInTargetStructure(level, vaultPos)) {
            return;
        }
        if (!isDarkVault) {
            LOGGER.info("[BeLoong] dread_king_ritual: opened vault at {} {} is not the dragon survival"
                            + " dark vault, skipping the ritual", level.dimension().location(), vaultPos);
            return;
        }

        BlockPos spawnPos = findSpawnPos(level, vaultPos);

        DreadKingRitualMarker marker =
                new DreadKingRitualMarker(ModEntities.DREAD_KING_RITUAL_MARKER.get(), level);
        // 标记实体永不发送客户端（clientTrackingRange = 0），故只需服务端位置正确。
        marker.moveTo(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D);

        if (level.addFreshEntity(marker)) {
            LOGGER.info("[BeLoong] dread_king_ritual: ritual started at {} {} (dark vault opened by {})",
                    level.dimension().location(), spawnPos, player.getName().getString());
        }
    }

    /**
     * 判定宝库是否落在配置的限定结构内。
     *
     * <p>用 {@link net.minecraft.world.level.StructureManager#getStructureWithPieceAt(BlockPos, HolderSet)}
     * 而非 {@code getStructureAt}：前者按<b>结构拼图块</b>判定，与原版 {@code LocationPredicate} 内部
     * 同款语义（{@code LocationPredicate.java:54}），误判面最小。
     *
     * <p>⚠️ <b>已知回退路径</b>：拼图块级判定比包围盒级更严格。若验收时发现整合包放进去的黯影宝库
     * 恰好落在拼图块之外（例如落在结构包围盒内的空隙），改调
     * {@code structureManager().getStructureAt(pos, structure).isValid()}（包围盒级）即可——
     * 项目内先例见 {@code structure/StructureEffectHandler.java:76}。
     *
     * <p>每次<b>现取</b> {@link Holder}，不做缓存：结构是数据包注册表，{@code /reload} 之后
     * 旧 Holder 会陈旧失效。
     */
    private static boolean isInTargetStructure(ServerLevel level, BlockPos pos) {
        String configured = Config.DreadKingRitual.structure.get();
        ResourceLocation id = ResourceLocation.tryParse(configured);
        if (id == null) {
            LOGGER.warn("[BeLoong] dread_king_ritual: configured structure id cannot be parsed,"
                    + " the ritual will never trigger: {}", configured);
            return false;
        }

        Holder<Structure> holder = level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE)
                .getHolder(ResourceKey.create(Registries.STRUCTURE, id))
                .orElse(null);
        if (holder == null) {
            LOGGER.warn("[BeLoong] dread_king_ritual: configured structure is not present in the registry,"
                    + " the ritual will never trigger: {}", id);
            return false;
        }

        boolean hit = level.structureManager()
                .getStructureWithPieceAt(pos, HolderSet.direct(holder))
                .isValid();
        if (!hit) {
            // 用 debug 而非 info：世界里的每个黯影宝库都会被走到这条分支，info 会刷屏。
            LOGGER.debug("[BeLoong] dread_king_ritual: {} is not inside a piece of structure {},"
                    + " skipping the ritual", pos, id);
        }
        return hit;
    }

    /**
     * 计算刷怪点：自宝库顶向上找第一个能容纳死者之王碰撞箱的位置。
     *
     * <p>死王高 3.5 格（{@code .sized(.9f, 3.5f)}），而城堡室内常见 3 格净高，直接放在宝库顶
     * 可能穿模/窒息。扫描上限 {@link #MAX_UPWARD_SCAN} 格；全部不满足时<b>照旧返回宝库顶</b>
     * 并告警——「出现在黯影宝库顶部」是需求，不擅自改到别处，只把「放得下」作为偏好。
     *
     * @return 死王脚底所在的方块位置
     */
    private static BlockPos findSpawnPos(ServerLevel level, BlockPos vaultPos) {
        BlockPos base = vaultPos.above();
        for (int dy = 0; dy <= MAX_UPWARD_SCAN; dy++) {
            BlockPos candidate = base.above(dy);
            if (hasClearance(level, candidate)) {
                if (dy > 0) {
                    LOGGER.info("[BeLoong] dread_king_ritual: not enough clearance above the vault,"
                                    + " moved the spawn point from {} up {} block(s) to {}",
                            base, dy, candidate);
                }
                return candidate;
            }
        }
        LOGGER.warn("[BeLoong] dread_king_ritual: no clearance for a {}x{} boss within {} block(s) above"
                        + " the vault top {}, spawning there anyway as required (the Dead King may clip into blocks)",
                BOSS_HEIGHT, BOSS_WIDTH, MAX_UPWARD_SCAN, base);
        return base;
    }

    /** 该位置能否容纳死王的碰撞箱（0.9 宽 × 3.5 高，底面中心对齐方块中心）。 */
    private static boolean hasClearance(ServerLevel level, BlockPos pos) {
        double cx = pos.getX() + 0.5D;
        double cz = pos.getZ() + 0.5D;
        AABB box = new AABB(
                cx - BOSS_WIDTH / 2.0D, pos.getY(), cz - BOSS_WIDTH / 2.0D,
                cx + BOSS_WIDTH / 2.0D, pos.getY() + BOSS_HEIGHT, cz + BOSS_WIDTH / 2.0D);
        return level.noCollision(box);
    }
}
