package com.zonlong.beloong.mixin.ironsspellbooks;

import com.zonlong.beloong.Config;
import com.zonlong.beloong.util.CustomDeathSequence;
import io.redspace.ironsspellbooks.effect.FrostbiteEffect;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复「霜噬击杀 ⇒ 别的模组的 Boss 永久假死」。
 *
 * <p><b>根因</b>：{@code FrostbiteEffect#handleFrostbiteDeathEffects}（源码
 * {@code FrostbiteEffect.java:23-39}）在"击杀者带 {@code frostbitten_strikes} 且死者完全冻结"时，
 * 生成冰雕克隆后执行 {@code entity.deathTime = 1000}（{@code :35}）。本意是让原尸体
 * 立刻被原版 {@code LivingEntity.tickDeath()} 的 {@code deathTime >= 20} 移除
 * （{@code LivingEntity.java:565-571}），只留冰雕。
 *
 * <p>但<b>覆写了 {@code tickDeath()} 的生物根本不会走原版那条收尾</b>，而它们自己的收尾常写成
 * {@code if (deathTime == N)} 的精确相等：祸乱鬼魅的虚空之花 {@code LightBlockRemover:32}（{@code ==70}）、
 * 熔炉魔像 {@code ServerGauntletDeathHandler:39}（{@code ==50}）；永恒星光的
 * {@code Permafrost:202}（{@code ==70}）、{@code LunarMonstrosity:216}（{@code ==75}）、
 * {@code SolarCreeper:399}（{@code ==100}）。{@code deathTime} 被拨到 1000 之后这些比较永不成立
 * ⇒ 0 血尸体永不 {@code remove()} ⇒ 假死；且此时 {@code isDeadOrDying()} 为真使
 * {@code hurt()} 直接返回 false ⇒ {@code /kill} 也无效。
 *
 * <p><b>本修复只做一件事</b>：在 {@code handleFrostbiteDeathEffects} 的 TAIL，
 * 若该生物<b>自己声明了 {@code tickDeath()}</b>（见 {@link CustomDeathSequence}），
 * 就把 {@code deathTime} 交还给它（置 0），其余全部保持原样——<b>冰雕照生成</b>，
 * 原尸体按该模组自己的死亡演出走完再消失。未覆写 {@code tickDeath} 的生物<b>一个字节都不动</b>，
 * 仍保持"原尸体立刻消失、只留冰雕"的原行为。
 *
 * <p><b>为什么不用白名单</b>：判据是"是否覆写 {@code tickDeath}"，与 modid 无关，
 * 现有受害者（BOMD ×2、永恒星光 ×3）与以后新装的模组一并覆盖。
 *
 * <p><b>为什么在这里修而不是改祸乱鬼魅</b>：写出 1000 的只有铁魔法这一处
 * （参考区里对 {@code LivingEntity.deathTime} 的唯一外部写），而 {@code deathTime}
 * 被当成私有计时器在第三方模组里是普遍写法；改第三方要复制它的收尾逻辑，违反项目约定。
 *
 * <p><b>注入约定</b>：铁魔法是<b>必需</b>依赖（{@code neoforge.mods.toml} 里 {@code type="required"}），
 * 所以不需要 {@code @Pseudo}；但用 {@code require = 0}，让上游改方法名时退化为"修复失效"
 * 而不是"整合包起不来"。可察觉性由锚点日志
 * {@code [BeLoong] frostbite-death: restored custom death sequence for ...} 提供
 * ——它只在修复真正动手时输出，完全缺席即意味着注入没挂上。
 *
 * <p>⚠️ <b>升级必查项</b>：上游若改了 {@code handleFrostbiteDeathEffects} 的方法名/描述符，
 * 或改掉了 {@code deathTime} 的写入，本 Mixin 会静默失效（{@code require = 0}）。
 * 复核方式：`javap -p -c` 打铁魔法 jar 的 {@code FrostbiteEffect}，看该方法里是否还有
 * {@code PUTFIELD net/minecraft/world/entity/LivingEntity.deathTime : I}。
 */
@Mixin(value = FrostbiteEffect.class, remap = false)
public abstract class FrostbiteDeathSequenceMixin {

    /**
     * 把 {@code deathTime} 还给"自己管死亡序列"的生物。
     *
     * <p>判据用 {@code deathTime > 0} 而不是 {@code == 1000}：该方法在 {@code die()} 里由
     * {@code LivingDeathEvent} 触发，此刻原版尚未跑过一次 {@code tickDeath()}，
     * 正常情况下 {@code deathTime} 必然是 0；因此 TAIL 处只要 >0，就只可能是刚被上游写入的值
     * ——这样上游把 1000 改成别的数字时本修复依然有效，且日志会把实际值打出来。
     *
     * @param event 死亡事件（与目标方法同一个参数）
     * @param ci    回调（注入到 void 方法，按约定必须以它结尾）
     */
    @Inject(
            method = "handleFrostbiteDeathEffects(Lnet/neoforged/neoforge/event/entity/living/LivingDeathEvent;)V",
            at = @At("TAIL"),
            remap = false,
            require = 0
    )
    private static void beloong$restoreCustomDeathSequence(LivingDeathEvent event, CallbackInfo ci) {
        // 开关是 Config 顶层的 fixFrostbiteDeath；关闭后完全恢复上游行为（可复现假死）
        if (!Config.FIX_FROSTBITE_DEATH.get()) {
            return;
        }
        LivingEntity entity = event.getEntity();
        int deathTime = entity.deathTime;
        if (deathTime <= 0) {
            return;
        }
        if (!CustomDeathSequence.ownsDeathSequence(entity.getClass())) {
            return;
        }
        entity.deathTime = 0;
        CustomDeathSequence.logRestored(entity, deathTime);
    }
}
