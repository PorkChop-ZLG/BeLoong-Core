package com.zonlong.beloong.mixin.cataclysm;

import com.github.L_Ender.cataclysm.blocks.Altar_Of_Amethyst_Block;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 使 {@code cataclysm:altar_of_amethyst} 可被下界合金镐挖掘，并掉落自身方块。
 *
 * <p>原理：</p>
 * <ul>
 *   <li>通过 {@code @ModifyArg} 在构造时修改 {@code Properties}：
 *       {@code destroyTime} 从 {@code -1} 改为 {@code 50}（黑曜石级），
 *       并启用 {@code requiresCorrectToolForDrops}</li>
 *   <li>通过 {@code @Inject} 在构造后将 {@code this.drops} 设为 {@code null}，
 *       逆转 {@code noLootTable()} 的效果，使方块恢复默认 loot table 查找</li>
 * </ul>
 *
 * <p>工具判定由原版标签体系驱动：
 * {@code #mineable/pickaxe} + {@code #needs_netherite_tool}</p>
 *
 * <p>配套数据文件：
 * {@code data/cataclysm/loot_table/blocks/altar_of_amethyst.json}</p>
 */
@Mixin(value = Altar_Of_Amethyst_Block.class, remap = false)
public abstract class AltarOfAmethystMixin extends BaseEntityBlock {

    protected AltarOfAmethystMixin(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /**
     * 拦截构造函数的 {@code super(properties)} 调用，
     * 将 {@code destroyTime} 从 {@code -1}（不可破坏）修改为 {@code 50}（黑曜石级），
     * 并启用 {@code requiresCorrectToolForDrops}。
     *
     * @param properties 原始方块属性（来自 {@code ModBlocks} 注册）
     * @return 修改后的属性
     */
    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/BaseEntityBlock;<init>(Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;)V"
            ),
            index = 0
    )
    private static BlockBehaviour.Properties beloong$modifyProperties(BlockBehaviour.Properties properties) {
        return properties.strength(50.0F, 3600000.0F).requiresCorrectToolForDrops();
    }

    /**
     * 构造后清除 {@code noLootTable()} 效果。
     * <p>
     * {@code BlockBehaviour$Properties.noLootTable()} 将 {@code drops} 设为 {@code BuiltInLootTables.EMPTY}，
     * 导致 {@code getLootTable()} 返回空值。将其重置为 {@code null} 后，
     * 方块恢复默认行为，查找对应的 loot table JSON。
     * </p>
     */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void beloong$resetDrops(BlockBehaviour.Properties properties, CallbackInfo ci) {
        /*
         * drops is protected in BlockBehaviour. Setting it to null causes
         * getLootTable() to use the default supplier that constructs
         * the loot table key from the block's registry name.
         */
        this.drops = null;
    }
}
