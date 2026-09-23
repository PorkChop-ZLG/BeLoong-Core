package com.zonlong.beloong.mixin.minecraft;

import by.dragonsurvivalteam.dragonsurvival.common.entity.creatures.Hunter;
import com.zonlong.beloong.registry.ModEntityTypeTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.raid.Raider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让「袭击者」与「龙之生存猎人」<b>互相</b>视为同伴。两个方向各由一份数据包名单驱动：
 *
 * <table border="1">
 *   <caption>两个方向</caption>
 *   <tr><th>方向</th><th>谁在判定</th><th>名单</th><th>默认内容</th></tr>
 *   <tr><td>袭击者 → 猎人</td><td>{@code instanceof Raider}</td>
 *       <td>{@code #minecraft:illager_friends}</td><td>6 个 {@code dragonsurvival:hunter_*}</td></tr>
 *   <tr><td>猎人 → 袭击者</td><td>{@code instanceof Hunter}（龙之生存的猎人职业类）</td>
 *       <td>{@code #beloong:hunter_allies}</td>
 *       <td>{@code #minecraft:illager_friends} + 袭击增强 4 个 id</td></tr>
 * </table>
 *
 * <h2>为什么只注入 {@code Entity#isAlliedTo} 一处就够</h2>
 * 目标选择与还手<b>两条路径都汇到这个方法</b>，已在字节码里逐一核对：
 * <ul>
 *   <li>{@code TargetingConditions#test} → {@code LivingEntity.isAlliedTo(Entity)}
 *       ⇒ 主动锁定被排除；</li>
 *   <li>{@code HurtByTargetGoal#canUse} → {@code Mob.isAlliedTo(Entity)}
 *       ⇒ 被打了也不还手。</li>
 * </ul>
 * 另外 {@code HurtByTargetGoalExtended#alertOthers}（龙之生存的援军逻辑）也会查
 * {@code otherMob.isAlliedTo(lastHurtBy)}，所以「同伴」之间不会互相喊援军 —— 这正是想要的。
 *
 * <h2>方向一：袭击者 → 猎人</h2>
 * 原版这套阵营判定<b>只有一处消费</b> —— {@code AbstractIllager#isAlliedTo}
 * （{@code AbstractIllager.java:34-41}）：
 * <pre>
 *   if (super.isAlliedTo(entity)) return true;
 *   else return !entity.getType().is(EntityTypeTags.ILLAGER_FRIENDS) ? false
 *        : this.getTeam() == null &amp;&amp; entity.getTeam() == null;
 * </pre>
 * 凡是不继承 {@code AbstractIllager} 的袭击者都读不到这个标签。
 * 「袭击增强」（Raids:Enhanced）的 4 个袭击者走的是 {@code FDRaider extends Raider}
 * （{@code FDRaider.java:14}），而 {@code Raider} <b>自己并没有覆写 {@code isAlliedTo}</b>
 * （继承自 {@code Entity}，字节码已核对），于是它们落到 {@code Entity#isAlliedTo} ——
 * <b>只看记分板队伍</b>（{@code Entity.java:2444-2452}）⇒ 标签对它们完全无效。
 *
 * <h2>方向二：猎人 → 袭击者</h2>
 * 猎人的目标选择是<b>另一套机制</b>，与上面那个标签无关
 * （{@code Hunter.java:56}）：
 * <pre>
 *   targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, Monster.class, 0, false, false,
 *           living -&gt; living.getType().is(DSEntityTypeTags.HUNTER_TARGETS)));
 * </pre>
 * 即「只打 {@code Monster} 里在 {@code #dragonsurvival:hunter_targets} 白名单上的」，
 * 而那份白名单里<b>本来就有</b> {@code pillager}/{@code evoker}/{@code vindicator}/
 * {@code ravager}/{@code witch} ⇒ 猎人会主动打灾厄村民。
 * 因为 {@code Hunter} 没有覆写 {@code isAlliedTo}，这里补上的这个 {@code true} 分支会让
 * {@code TargetingConditions} 把名单内目标判为非法，<b>不必改 {@code hunter_targets}</b>
 * （改白名单只能解决「主动攻击」，还手仍会发生）。
 *
 * <h2>为什么注入 {@code Entity} 而不是分别注入 {@code Raider} / {@code Hunter}</h2>
 * 两者都<b>没有声明</b> {@code isAlliedTo}（都继承自 {@code Entity}），Mixin 无法向
 * 「未声明该方法的类」注入；而在它们里面新增覆写又会在本项目开一个没有先例的口子。
 * 注入声明该方法的祖先类、再用 {@code instanceof} 把作用域收窄，效果等价，且不复制原版逻辑。
 * 已对实际 jar 核对：{@code LivingEntity} / {@code Mob} / {@code PathfinderMob} /
 * {@code Monster} / {@code Raider} / {@code Hunter} <b>全都没有</b>覆写 {@code isAlliedTo}。
 *
 * <h2>刻意不做的</h2>
 * <ul>
 *   <li>不提供伤害 / 范围效果 / 投射物免疫。语义严格限定为「不把它选为目标」。</li>
 *   <li><b>不按 {@code #dragonsurvival:hunter_faction} 判定「谁是猎人」</b>：
 *       那份 tag 里还有 {@code minecraft:villager} 与 {@code minecraft:iron_golem}
 *       （DS 自己的 datagen 就是这么写的，注释是 {@code // Used in 'curse_of_kindness' enchantment}）。
 *       若按它判定，铁傀儡也会跟着把灾厄村民当同伴 —— 那会拆掉「铁傀儡保卫村庄」这个原版行为。
 *       改用 {@code instanceof Hunter}（只有 5 个猎人战斗职业类），直击要害。</li>
 *   <li>不改 {@code #dragonsurvival:hunter_targets}（改它治不了还手）。</li>
 * </ul>
 *
 * <h2>与数据包的分工</h2>
 * 两份名单都是本模组自带默认值、整合包可继续追加，多份同路径文件在 {@code replace:false} 下
 * <b>合并为并集</b>。袭击增强的 4 个生物已写进 {@code #beloong:hunter_allies}
 * （各自的 {@code required: false} 是必须的：该模组是可选依赖，见 {@code ModEntityTypeTags} 的说明）。
 * 它们本来也不会被猎人主动攻击（不在 {@code hunter_targets} 里），写进名单是为了
 * <b>「被它们的范围伤害溅到后也不还手」</b>。
 *
 * <p><b>已知覆盖范围</b>：{@code #beloong:hunter_allies} 里引用的 {@code #minecraft:illager_friends}
 * 等于 {@code #minecraft:illager}（evoker / illusioner / pillager / vindicator）+ 本模组写入的 6 个猎人，
 * <b>不含</b> {@code ravager} 与 {@code witch} ⇒ 猎人<b>仍会</b>攻击劫掠兽与女巫。
 * 要覆盖它们，把该文件第一行换成 {@code "#minecraft:raiders"}（一行改动）。
 * 注意 {@code hunter_targets} 里那 5 个条目仍原样保留 —— 我们靠 {@code isAlliedTo} 让它们失效，
 * 而不是删白名单，这样「不主动攻击」与「不还手」能一起解决。
 *
 * <p><b>已知副作用</b>：因为 {@code #minecraft:illager_friends} 是共享的，装了本模组后
 * <b>原版</b>灾厄村民也会一并把 6 个猎人当同伴。实际可观测的只有 {@code hunter_leader}
 * （它是 {@code AbstractVillager}），另外 5 个本就不在灾厄村民的目标类型里。
 *
 * @see com.zonlong.beloong.registry.ModEntityTypeTags#HUNTER_ALLIES
 * @see com.zonlong.beloong.mixin.raidsenhanced.RaidBlimpTargetMixin 飞艇走的是另一条硬编码白名单，需单独处理
 */
@Mixin(Entity.class)
public abstract class RaiderHunterAllyMixin {

    /**
     * 只「增加一个返回 true 的分支」：不满足条件时直接返回，让原版逻辑照常执行。
     *
     * <p>两个方向共用这次注入，所以「同伴判定只定义一处」这条原则继续成立。
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
    private void beloong$treatFactionAllies(Entity other, CallbackInfoReturnable<Boolean> cir) {
        // 本方法挂在整个 Entity 上，在目标选择里被高频调用（每个候选目标每 tick 一次），
        // 所以第一道门必须是最便宜的 instanceof：把无关实体在这里就挡掉，
        // 不要让它们走到下面的 getTeam()（那是按记分板名字符串查 Map，不便宜）。
        // 注：mixin 里 this 的静态类型是混入类，取实体方法需显式转型。
        boolean attackerIsRaider = (Object) this instanceof Raider;
        boolean attackerIsHunter = (Object) this instanceof Hunter;
        if (!attackerIsRaider && !attackerIsHunter) {
            return;
        }

        // 与 AbstractIllager#isAlliedTo 同构：任一方有记分板队伍时不介入，
        // 把队伍语义完全留给原版，避免和记分板插件打架。
        Entity self = (Entity) (Object) this;
        if (self.getTeam() != null || other.getTeam() != null) {
            return;
        }

        // 方向一：袭击者 → 猎人（名单 = 数据包可追加的 #minecraft:illager_friends）
        if (attackerIsRaider && other.getType().is(EntityTypeTags.ILLAGER_FRIENDS)) {
            cir.setReturnValue(true);
            return;
        }

        // 方向二：龙之生存猎人 → 袭击者（名单 = 数据包可追加的 #beloong:hunter_allies）
        if (attackerIsHunter && other.getType().is(ModEntityTypeTags.HUNTER_ALLIES)) {
            cir.setReturnValue(true);
        }
    }
}
