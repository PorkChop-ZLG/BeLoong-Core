package com.zonlong.beloong.mixin.minecraft;

import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.raid.Raider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让<b>全部袭击者</b>把「龙之生存猎人」视为同伴。
 *
 * <h2>要解决的问题</h2>
 * 给 {@code #minecraft:illager_friends} 加上 {@code dragonsurvival:hunter_leader} 之后，
 * 原版掠夺者不再攻击猎人，但<b>「袭击增强」（Raids:Enhanced）的袭击者照旧攻击</b>。
 *
 * <p>原因是这套阵营判定在全原版<b>只有一处消费</b> —— {@code AbstractIllager#isAlliedTo}
 * （{@code AbstractIllager.java:34-41}）：
 * <pre>
 *   if (super.isAlliedTo(entity)) return true;
 *   else return !entity.getType().is(EntityTypeTags.ILLAGER_FRIENDS) ? false
 *        : this.getTeam() == null &amp;&amp; entity.getTeam() == null;
 * </pre>
 * 凡是不继承 {@code AbstractIllager} 的袭击者都读不到这个标签。
 * 袭击增强的 4 个袭击者走的是 {@code FDRaider extends Raider}（{@code FDRaider.java:14}），
 * 而 {@code Raider} <b>自己并没有覆写 {@code isAlliedTo}</b>（继承自 {@code Entity}），
 * 于是它们落到 {@code Entity#isAlliedTo} —— <b>只看记分板队伍</b>
 * （{@code Entity.java:2444-2452}）⇒ 标签对它们完全无效。
 * 该模组全库<b>没有任何一处</b>覆写 {@code isAlliedTo}（已 grep 确认）。
 *
 * <h2>为什么注入 {@code Entity} 而不是 {@code Raider}</h2>
 * {@code Raider} 没有<b>声明</b> {@code isAlliedTo}，Mixin 无法向「未声明该方法的类」注入；
 * 而在 {@code Raider} 里新增一个覆写又会在本项目开一个没有先例的口子。
 * 注入声明该方法的祖先类、再用 {@code instanceof Raider} 把作用域收窄，
 * 效果等价，且不必复制原版逻辑。
 *
 * <h2>覆盖范围</h2>
 * 原版 4 种灾厄村民 + 劫掠兽 + 女巫 + <b>任意模组袭击者</b>（含袭击增强的 4 个）。
 * 命中后 {@code TargetingConditions#test}（{@code TargetingConditions.java:73}）会把该目标判为非法
 * ⇒ 不再主动锁定；{@code HurtByTargetGoal} 也不再因被打而还手。
 *
 * <p><b>刻意不做的</b>：不提供伤害/范围效果/投射物免疫。语义严格限定为「不把它选为目标」。
 *
 * <h2>与数据包的分工</h2>
 * 名单来源就是 {@code #minecraft:illager_friends}：本模组自带一份默认值
 * （{@code data/minecraft/tags/entity_type/illager_friends.json}，6 个猎人，{@code "replace": false}），
 * 整合包可以在同一标签上继续追加，多份文件在 {@code replace:false} 下<b>合并为并集</b>。
 *
 * <p><b>已知副作用</b>：因为标签是共享的，装了本模组后<b>原版</b>灾厄村民也会一并把 6 个猎人当同伴。
 * 实际可观测的只有 {@code hunter_leader}（它是 {@code AbstractVillager}），另外 5 个本就不在
 * 灾厄村民的目标类型里。
 *
 * @see com.zonlong.beloong.mixin.raidsenhanced.RaidBlimpTargetMixin 飞艇走的是另一条硬编码白名单，需单独处理
 */
@Mixin(Entity.class)
public abstract class RaiderHunterAllyMixin {

    /**
     * 只「增加一个返回 true 的分支」：不满足条件时直接返回，让原版逻辑照常执行。
     *
     * <p><b>注入点</b>：{@code isAlliedTo(Entity)} 的 HEAD。描述符必须写全 ——
     * {@code Entity} 上有 {@code isAlliedTo(Entity)} 与 {@code isAlliedTo(Team)} 两个重载。
     *
     * <p><b>{@code remap = false} 是必需的</b>：NeoForge 运行时即用 Mojang 官方名、
     * dev 命名空间 == 运行时命名空间，留默认 {@code true} 会直接构建失败
     * （{@code Unable to locate obfuscation mapping for @Inject target}）。
     * 项目内先例：{@code DreadKingRitualTriggerMixin}、{@code PossibleBiomesFilterMixin}。
     *
     * @param other 判定对象（可能是任意实体，包括非生物）
     * @param cir   返回 {@code true} 表示「是同伴」
     */
    @Inject(method = "isAlliedTo(Lnet/minecraft/world/entity/Entity;)Z",
            at = @At("HEAD"), remap = false, cancellable = true)
    private void beloong$raidersTreatHuntersAsAllies(Entity other, CallbackInfoReturnable<Boolean> cir) {
        // 本方法挂在整个 Entity 上，每个实体每 tick 都会经过这里，
        // 所以第一道门用最便宜的 instanceof 把非袭击者全部挡掉。
        // 注：mixin 里 this 的静态类型是混入类，取实体方法需显式转型。
        if (!((Object) this instanceof Raider self)) {
            return;
        }
        // 与 AbstractIllager#isAlliedTo 同构：任一方有记分板队伍时不介入，
        // 把队伍语义完全留给原版，避免和记分板插件打架。
        if (self.getTeam() != null || other.getTeam() != null) {
            return;
        }
        if (other.getType().is(EntityTypeTags.ILLAGER_FRIENDS)) {
            cir.setReturnValue(true);
        }
    }
}
