package com.zonlong.beloong.mixin.minecraft;

import com.zonlong.beloong.registry.ExecuteMarkSyncHandler;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让斩杀标记跟着原版的效果生命周期一起同步到客户端。
 *
 * <p><b>项目规范</b>：本 mixin 的目标是<b>原版</b>类，所以放在 {@code mixin/minecraft/} 包下、
 * {@code beloong.mixins.json} 条目写作 {@code "minecraft.LivingEntityExecuteMarkSyncMixin"}，
 * 且注入处显式 {@code remap = false}（{@code @Inject} 与其 {@code @At} 都要）——
 * 与 {@link DreadKingRitualTriggerMixin}、{@code RaiderHunterAllyMixin} 同款。
 * 见 {@code memory/project-context.md} 与 decisions-log 第 11 条。
 *
 * <h3>为什么注入这三个方法</h3>
 * 原版把生物的 {@code MobEffectInstance} 留在服务端（客户端只拿到粒子外观），
 * 所以要想「客户端能看见斩杀线」，必须在标记变化时主动发一条我们自己的包
 * （{@link com.zonlong.beloong.compat.healthbars.ExecuteMarkSyncPayload}）。
 * 而「标记何时变化」在服务端<b>只有这三个出口</b>：
 * <ul>
 *   <li>{@code onEffectAdded} —— 挂上（本功能的 {@code ExecuteMarkHandler} 与指令都走这里）；</li>
 *   <li>{@code onEffectUpdated} —— 刷新/改等级；</li>
 *   <li>{@code onEffectRemoved} —— 移除。{@code LivingEntity:807-811} 表明<b>自然到期也走这里</b>
 *       （且只在服务端触发），所以斩杀结算的 {@code removeEffect}、{@code /effect clear}、
 *       牛奶、死亡等路径全部被覆盖，不需要在每个业务调用点散落发送语句。</li>
 * </ul>
 * 三处注入都在 {@code TAIL}：此时 {@code activeEffects} 已完成增删，重算出来的就是新状态。
 * <p>
 * 这也是<b>不用</b> NeoForge {@code MobEffectEvent.Added/Remove/Expired} 的原因：
 * 那几个事件触发于状态变更<b>之前</b>（{@code Added} 在 {@code LivingEntity:977}、
 * {@code Remove}/{@code Expired} 还可被取消），读到的是旧值；而 {@code Added} 拿到的是
 * 「未合并的新实例」，vanilla {@code MobEffectInstance.update} 取的是 {@code max(旧, 新)} 等级，
 * 直接用会得出偏小的斩杀线。TAIL 注入读到的则是终态。
 *
 * <h3>为什么不需要 {@code @Pseudo} / {@code require = 0}</h3>
 * 目标是原版 {@code LivingEntity}，永远存在。本仓库全局 {@code injectors.defaultRequire = 1}，
 * 所以一旦 Mojang 改了这三个方法的签名，会<b>直接崩</b>而不是静默失效 —— 这正是我们要的：
 * 那时同步会整体失灵（斩杀线又不显示了），必须立刻发现。
 *
 * <h3>为什么必须 {@code remap = false}</h3>
 * 这三个方法都带 <b>NeoForge 补的参数</b>（{@code onEffectAdded} 的第 2 个 {@code Entity}、
 * {@code onEffectUpdated} 的第 3 个），其描述符在<b>原版混淆表里不存在</b>，注解处理器会直接报
 * {@code Unable to locate obfuscation mapping for @Inject target}（实测三个都报）。
 * NeoForge 1.21 运行时即用 Mojang 官方名（无混淆），dev 命名空间 == 运行时命名空间。
 *
 * @see DreadKingRitualTriggerMixin 同包同规范的原版目标 mixin 先例
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityExecuteMarkSyncMixin {

    @Inject(
            method = "onEffectAdded(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)V",
            at = @At(value = "TAIL", remap = false),
            remap = false)
    private void beloong$syncExecuteMarkOnAdded(final MobEffectInstance instance, final Entity source,
                                                final CallbackInfo ci) {
        ExecuteMarkSyncHandler.onEffectChanged((LivingEntity) (Object) this, instance);
    }

    @Inject(
            method = "onEffectUpdated(Lnet/minecraft/world/effect/MobEffectInstance;ZLnet/minecraft/world/entity/Entity;)V",
            at = @At(value = "TAIL", remap = false),
            remap = false)
    private void beloong$syncExecuteMarkOnUpdated(final MobEffectInstance instance, final boolean forced,
                                                  final Entity source, final CallbackInfo ci) {
        ExecuteMarkSyncHandler.onEffectChanged((LivingEntity) (Object) this, instance);
    }

    @Inject(
            method = "onEffectRemoved(Lnet/minecraft/world/effect/MobEffectInstance;)V",
            at = @At(value = "TAIL", remap = false),
            remap = false)
    private void beloong$syncExecuteMarkOnRemoved(final MobEffectInstance instance, final CallbackInfo ci) {
        ExecuteMarkSyncHandler.onEffectChanged((LivingEntity) (Object) this, instance);
    }
}
