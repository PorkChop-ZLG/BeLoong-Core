package com.zonlong.beloong.mixin.minecraft;

import com.zonlong.beloong.dreadking.DreadKingRitualStarter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultConfig;
import net.minecraft.world.level.block.entity.vault.VaultServerData;
import net.minecraft.world.level.block.entity.vault.VaultSharedData;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 黯影宝库「死王仪式」的检测层：判定「暗影钥匙是否被<b>真正接受</b>」。
 *
 * <h2>为什么注入这里</h2>
 * 原版宝库（{@code minecraft:vault}，龙之生存的 {@code dragonsurvival:dark_vault} 就是它）的状态机是：
 * <pre>
 *   INACTIVE / ACTIVE --updateStateForConnectedPlayers--> ACTIVE   // 附近有持钥匙玩家 ≠ 已开箱
 *   UNLOCKING         --20t--> EJECTING                            // 唯一入口：unlock()
 *   EJECTING          --逐件喷出--> 回到 ACTIVE/INACTIVE
 * </pre>
 * 私有方法 {@code unlock(...)} 全类<b>只有一处</b>调用 —— {@code tryInsertKey()} 的成功分支
 * （{@code VaultBlockEntity.java:289}），其前置条件是
 * {@code canEjectReward} ✓ → {@code isValidToInsert} ✓ → {@code !hasRewardedPlayer} ✓
 * → 战利品非空 ✓。因此「{@code unlock} 被调用」等价于「钥匙被真正接受」。
 *
 * <p>这个精确性不是洁癖，而是硬需求：{@code hunter_knight} 会掉
 * {@code dragonsurvival:dark_key}（{@code loot_table/entities/hunter_knight.json}）⇒ 玩家<b>能囤钥匙</b>，
 * 于是「拿备用钥匙对已开过的宝库再右键」是一个真实可复现的操作。若改用
 * {@code PlayerInteractEvent.RightClickBlock} 或 {@code item_used_on_block} 判据，
 * 它们只看到「右键了 ACTIVE 状态的宝库」——{@code VaultBlock.useItemOn} 只要有方块实体就
 * 恒返回 SUCCESS（{@code VaultBlock.java:50-64}）——于是被拒绝的插入也会触发，
 * 玩家可以<b>刷死王</b>。而在本注入点，被拒绝的路径根本走不到这里。
 *
 * <p>另外，包私有 API 也堵死了零 mixin 的精确判定：{@code VaultServerData.hasRewardedPlayer}
 * 与 {@code getRewardedPlayers} 都是包私有、{@code canEjectReward} 是 private，mixin 类不在
 * {@code net.minecraft.world.level.block.entity.vault} 包内，跨类包私有成员<b>无法访问</b>
 * （{@code @Shadow} 只管目标类自己的成员）。
 *
 * <h2>为什么选 {@code unlock} 的 INVOKE 而不是 {@code tryInsertKey} 的 TAIL</h2>
 * TAIL 处判不出成功还是被拒（同样受上面那条包私有约束），而 {@code unlock} 的 INVOKE 点上，
 * {@code level / pos / player} <b>三个都是 {@code tryInsertKey} 的形参</b>，无需任何 {@code @Shadow}。
 * 注意处理器签名必须逐字匹配 <b>{@code tryInsertKey}</b> 的形参表，<b>不是 {@code unlock} 的</b> ——
 * {@code unlock} 的顺序是 {@code (level, state, pos, ...)} 且<b>不含 player</b>。
 *
 * <p>本类<b>只做转发</b>，不含任何判定逻辑；结构与配置判定在
 * {@link DreadKingRitualStarter}。
 *
 * @see DreadKingRitualStarter
 */
@Mixin(VaultBlockEntity.Server.class)
public abstract class DreadKingRitualTriggerMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger(DreadKingRitualTriggerMixin.class);

    /**
     * 在钥匙被接受、战利品即将发放的那一刻转发给编排层。
     *
     * <p><b>注入点</b>：{@code tryInsertKey} 内唯一一次 {@code unlock(...)} 调用之前
     * （描述符由 {@code javap -c} 对 {@code neoforge-21.1.236-merged.jar} 核对过）。
     * <p>此刻 {@code stack.consume(...)} 已执行、{@code addToRewardedPlayers(...)} 尚未执行 ——
     * 我们不依赖其中任何一个状态。
     *
     * <p><b>为什么自己兜异常</b>：本方法运行在原版宝库的开箱流程内部。若我们的代码抛出，
     * 会连带<b>破坏原版开箱</b>并把异常抛穿到网络包处理层。仪式是我们加的附加行为，
     * 不应该有能力弄坏原版流程，所以这里刻意兜住并只留一条 ERROR 日志。
     */
    @Inject(
            method = "tryInsertKey",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/vault/VaultBlockEntity$Server;unlock("
                            + "Lnet/minecraft/server/level/ServerLevel;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;"
                            + "Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/block/entity/vault/VaultConfig;"
                            + "Lnet/minecraft/world/level/block/entity/vault/VaultServerData;"
                            + "Lnet/minecraft/world/level/block/entity/vault/VaultSharedData;"
                            + "Ljava/util/List;)V",
                    shift = At.Shift.BEFORE,
                    // 原版目标必须显式 remap = false：NeoForge 运行时即用 Mojang 官方名（无混淆），
                    // dev 命名空间 == 运行时命名空间，无需 remap。若留默认 true，mixin 注解处理器会去
                    // 查找混淆映射并直接报错 "Unable to locate obfuscation mapping for @Inject target"。
                    // 项目内先例：PossibleBiomesFilterMixin（针对原版 BiomeSource）同样写 remap = false。
                    remap = false
            ),
            remap = false
    )
    private static void beloong$onKeyAccepted(ServerLevel level, BlockPos pos, BlockState state,
                                             VaultConfig config, VaultServerData serverData,
                                             VaultSharedData sharedData, Player player,
                                             ItemStack stack, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        try {
            DreadKingRitualStarter.start(level, pos, serverPlayer);
        } catch (Exception e) {
            LOGGER.error("[BeLoong] dread_king_ritual: starter failed at {} {}"
                            + " (the vanilla vault opening is unaffected)",
                    level.dimension().location(), pos, e);
        }
    }
}
