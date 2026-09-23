package com.zonlong.beloong.mixin.raidsenhanced;

import com.finderfeed.raids_enhanced.content.entities.raid_blimp.RaidBlimp;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把「龙之生存猎人」从「袭击增强」飞艇的候选目标里剔除。
 *
 * <h2>为什么 {@link com.zonlong.beloong.mixin.minecraft.RaiderHunterAllyMixin RaiderHunterAllyMixin} 管不到它</h2>
 * {@code RaidBlimp#checkTargetClass} 是一段<b>硬编码的类型白名单</b>
 * （{@code RaidBlimp.java:344-346}）：
 * <pre>
 *   target instanceof AbstractVillager
 *     || (target instanceof Player &amp;&amp; !spectator &amp;&amp; !creative)
 *     || target instanceof IronGolem
 * </pre>
 * 它由 {@code FDTargetFinder.getEntitiesInCylinder(..., e -> checkTargetClass(e))} 调用，
 * 有两处调用方：投弹（{@code RaidBlimp.java:498}）与 6 门炮
 * （{@code RaidBlimpCannonsController.java:54}）。
 * 这条路径<b>既不经过 {@code TargetingConditions}，也不经过 {@code Mob#setTarget}</b>
 * ⇒ 它是直接筛实体列表后自行开火，基于 {@code isAlliedTo} 的修复碰不到它。
 * 这正是「改了标签、飞艇照旧打」的第二个独立原因。
 *
 * <h2>为什么不改 {@code RaidDrill}</h2>
 * {@code RaidDrill} 没有任何以村民为目标的 goal（已核对），不在问题范围内。
 * （该模组自己也用 {@code LivingChangeTargetEvent} 阻止别的生物锁 {@code RaidDrill}，
 * 见 {@code RaidDrill.java:462-467}。）
 *
 * <h2>可选依赖</h2>
 * 「袭击增强」在 {@code build.gradle} 里是 {@code compileOnly} + {@code localRuntime}。
 * {@code @Pseudo} 让本 Mixin 在该模组缺席时被整体跳过，而不是启动即崩。
 *
 * <h2>已知取舍</h2>
 * 按项目对可选依赖的既有规范用 {@code require = 0}：上游若重命名或改了
 * {@code checkTargetClass} 的签名，本注入会<b>静默失效</b> —— 不崩，但兼容层不再起作用。
 * 为让这种偏差至少在部署时可见，{@code neoforge.mods.toml} 里给 raidsenhanced 声明了
 * {@code versionRange="[1.0.2,)"}（版本不匹配会在日志里报出来）。
 */
@Pseudo
@Mixin(value = RaidBlimp.class, remap = false)
public abstract class RaidBlimpTargetMixin {

    /**
     * 在 {@code checkTargetClass} 入口处拦截：目标属于 {@code #minecraft:illager_friends} 时强制返回 {@code false}。
     *
     * <p>{@code remap = false} 对模组方法同样是必需的（模组方法从未被 remap 过）。
     *
     * @param target 候选目标
     * @param cir    返回 {@code false} 表示「不是合法目标」
     */
    @Inject(method = "checkTargetClass(Lnet/minecraft/world/entity/Entity;)Z",
            at = @At("HEAD"), remap = false, require = 0, cancellable = true)
    private void beloong$ignoreHunters(Entity target, CallbackInfoReturnable<Boolean> cir) {
        // 注：mixin 里 this 的静态类型是混入类，取实体方法需显式转型。
        // RaidBlimp 经 FDRaider → Raider → … → Entity，该转型成立。
        Entity self = (Entity) (Object) this;
        // 与 P1 同一口径：任一方有记分板队伍时不介入。
        if (self.getTeam() != null || target.getTeam() != null) {
            return;
        }
        if (target.getType().is(EntityTypeTags.ILLAGER_FRIENDS)) {
            cir.setReturnValue(false);
        }
    }
}
