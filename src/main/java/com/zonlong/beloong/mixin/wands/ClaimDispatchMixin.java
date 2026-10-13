package com.zonlong.beloong.mixin.wands;

import com.zonlong.beloong.compat.ftbchunks.LoongPalaceProtectionPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.nicguzzo.compat.claims.ClaimDispatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让「建筑之杖」（Building Wands，modid {@code wands}）把 {@code beloong:loong_palace}
 * 视同<b>已被他人认领的区块</b>，从而沿用它<b>自身</b>的 FTB Chunks 认领保护。
 *
 * <h2>为什么本模组现有的龙宫保护拦不住它</h2>
 * 本模组的玩家编辑保护走 NeoForge 方块事件（{@code BlockEvent.BreakEvent} /
 * {@code BlockEvent.EntityPlaceEvent}，见
 * {@link com.zonlong.beloong.compat.ftbchunks.LoongPalaceProtectionHandler}）。
 * 建筑之杖不走这条路：它自己判一次认领，然后<b>直接</b>写方块。
 *
 * <p>{@code net.nicguzzo.wands.wand.Wand#place_block} 的字节码（3.0.5，CF 文件 8207219）：
 * <pre>
 *   0: iconst_0                     boolean r = false;
 *   3: getfield level
 *   6: checkcast ServerLevel        ← 硬转、无 instanceof 门 ⇒ 该方法只在服务端跑
 *  14: ClaimDispatch.claimCanInteract((ServerLevel) level, pos, player)   ← 认领门是第一条语句
 *  17: ifne 42
 *  20-37: displayClientMessage("wands.message.claimed_chunk".RED, true)   ← 它自带的红字提示
 *  40: ireturn                     return false
 *  42+: 之后才 setBlockAndUpdate / 调 this.destroyBlock
 * </pre>
 *
 * <p>{@code FTBChunks.canInteract} = {@code !manager.shouldPreventInteraction(player, MAIN_HAND, pos,
 * Protection.EDIT_BLOCK, null)}。龙宫<b>未被认领</b>且 {@code no_wilderness_dimensions} 为空时，
 * FTB 的判定链落到「未认领 + 未启用荒野保护 ⇒ 放行」⇒ 建筑之杖畅通无阻。
 * 我们<b>无法</b>通过改变自己调用的 API 来影响它问到的答案（决定权在调用方传入的
 * {@code Protection} 常量与 FTB 的判定链上），所以改由「把龙宫伪装成已认领」来达成对等。
 *
 * <h2>为什么注入这里</h2>
 * {@code ClaimDispatch.claimCanInteract} 是纯派发器（依次问 opac / ftbchunks / flan / goml，
 * 任一拒绝即 {@code false}），且<b>全 jar 只有一个调用者</b>：{@code Wand#place_block}。
 * 因此注入它 <b>只影响「放 / 挖」这一个动作</b>，不触碰任何右键交互
 * —— 正好满足「只不可破坏、放行部分交互」。
 *
 * <p>覆盖面（全 jar 161 个 class 扫过）：{@code Wand#place_block} 的门在所有写入之前，
 * 而 {@code Wand#destroyBlock}（不查门）全 jar 只有 {@code place_block} 调它 ⇒ 一并被挡住。
 *
 * <h2>可选依赖</h2>
 * 「建筑之杖」在 {@code build.gradle} 里是 {@code compileOnly} + {@code localRuntime}，
 * 并在 {@code neoforge.mods.toml} 里声明为 {@code type="optional"} +
 * {@code versionRange="[3.0.5,)"}（三段齐全）。{@code @Pseudo} 让本 Mixin 在该模组缺席时
 * 被整体跳过，而不是启动即崩。
 *
 * <h2>已知取舍</h2>
 * <ul>
 *   <li>按项目对可选依赖的既有规范用 {@code require = 0}：上游若重命名本方法或改签名，
 *       注入会<b>静默失效</b>（不崩，但兼容层不再起作用）。{@code versionRange} 让版本偏差可见。</li>
 *   <li>{@code UndoManager#undo/redo} 直接写方块且不查认领门，本注入覆盖不到。
 *       这是<b>刻意</b>的：建筑之杖在真实已认领区块里同样不拦撤销 ⇒ 不覆盖它才符合
 *       「和它原有的保护效果一样」这一验收口径。</li>
 *   <li>只覆盖建筑之杖这一个模组，不是通用方案。</li>
 * </ul>
 *
 * <h2>升级必查项</h2>
 * 若 {@code ClaimDispatch#claimCanInteract} 出现<b>新的调用者</b>（例如作者把「使用 / 交互」
 * 也接到这道门上），覆盖面会扩大到超出「只不可破坏」的要求，需要重新评估。
 */
@Pseudo
@Mixin(value = ClaimDispatch.class, remap = false)
public abstract class ClaimDispatchMixin {

    /**
     * 在 {@code claimCanInteract} 入口处拦截：目标维度是龙宫且该玩家无 FTB 绕过权限时，
     * 直接返回 {@code false}（= 「此处已被他人认领」），建筑之杖随后会显示它自带的
     * {@code wands.message.claimed_chunk} 红字提示并放弃本次放置。
     *
     * <p>目标方法是 {@code static} 且 {@code package-private} 可见 ⇒ 处理器必须是
     * {@code private static}，且<b>必须</b>以 {@code CallbackInfoReturnable<Boolean>} 结尾
     * （即使不使用也不能省，否则 {@code InvalidInjectionException} = 模组加载失败）。
     *
     * <p>{@code remap = false} 对模组方法同样是必需的（模组方法从未被 remap 过）。
     *
     * @param level  目标世界（调用方已硬转为 {@code ServerLevel}，故此处不会收到客户端世界）
     * @param pos    目标方块坐标
     * @param player 执行放置的玩家
     * @param cir    返回 {@code false} 表示「无权在此编辑」
     */
    @Inject(method = "claimCanInteract(Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/entity/player/Player;)Z",
            at = @At("HEAD"), remap = false, require = 0, cancellable = true)
    private static void beloong$loongPalaceAsClaimed(
            ServerLevel level, BlockPos pos, Player player, CallbackInfoReturnable<Boolean> cir) {
        if (LoongPalaceProtectionPolicy.shouldTreatAsClaimed(level, player)) {
            cir.setReturnValue(false);
        }
    }
}
