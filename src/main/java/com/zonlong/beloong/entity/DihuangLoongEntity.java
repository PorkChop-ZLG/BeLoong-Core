package com.zonlong.beloong.entity;

import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 地黄龙 NPC —— 本模组**第一个生物 NPC**（也是第一个 GeckoLib 实体）。
 * <p>
 * 定位是"站桩 NPC"，四条硬性约束：
 * <ul>
 *   <li><b>无 AI</b>：不覆写 {@code registerGoals()}，且 {@link #isNoAi()} 恒返回 true；</li>
 *   <li><b>无敌</b>：{@link #isInvulnerableTo(DamageSource)} 只放行"穿透无敌"的伤害
 *       （{@code /kill} 与虚空）—— 这是**刻意留的管理后路**；</li>
 *   <li><b>不可推动、永不消失</b>：{@link #isPushable()} 与 {@link #isPersistenceRequired()}
 *       恒为 false / true；实体类别用 {@code MobCategory.MISC}（不占刷怪上限）；</li>
 *   <li><b>站在地上</b>：不动重力，也不缩放（按模型默认尺寸渲染）。</li>
 * </ul>
 * <b>为什么这些语义写成"覆写 getter"而不是"构造函数里 set"</b>：{@code /summon} 与刷怪蛋的
 * 流程是"先 {@code create()}（构造函数在此运行）→ 再 {@code load(标签)}"，而 {@code load()}
 * 会从标签逐个读回 {@code Invulnerable}（{@code Entity.java:1759}）、{@code PersistenceRequired}
 * （{@code Mob.java:437}）、{@code NoAI}（{@code Mob.java:486}）—— 标签里没有对应键时就覆盖成
 * false。**实机第一次测试正是因为这一点而"能被打"**。覆写 getter 与任何加载路径无关。
 * <p>
 * 设计文档：{@code docs/plans/2026-09-21-dihuang-loong-npc-design.md}（修订 R8）。
 */
public class DihuangLoongEntity extends PathfinderMob implements GeoEntity {

    /**
     * v1 唯一使用的动画。
     * <p>
     * 动画文件里另有约 100 个动作（含带 {@code 2} 后缀的一整套），但无 AI 的站桩 NPC 只需要待机；
     * 将来要加动作只改这里，**不需要换资源文件**。
     */
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public DihuangLoongEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        // 这三行对"不经 NBT 的创建路径"是对的，也让字段本身与保存出的 NBT 保持一致；
        // 但它们**不足以保证语义** —— /summon 会先 create() 再 load(标签)，而 load() 会把这些
        // 字段从标签读回、缺键即覆盖为 false。真正的保证是下面那三个覆写。
        setNoAi(true);
        setInvulnerable(true);
        setPersistenceRequired();
    }

    // ===================== 属性 =====================

    /**
     * 属性表在 {@code ModAttributes#onEntityAttributeCreation} 中注册。
     * <p>
     * <b>必须放在双端都加载的类里</b>（{@code ModAttributes} 正是这样的类）：
     * 属性是服务端权威的，若只在客户端类注册，专用服务器上的地黄龙会没有属性表。
     */
    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                // 血量 1000。原版 generic.max_health 的属性上限是 1024，故此值不会被夹断。
                // 无敌之下血量只是设定值，但留足余量，将来做"解除无敌/多阶段"的演出时不用再改
                .add(Attributes.MAX_HEALTH, 1000.0D)
                // 打不动也推不动：免疫爆炸与攻击造成的击退；玩家挤压另由 isPushable() 兜住
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D);
    }

    /** 站桩 NPC 不该被玩家推着走。 */
    @Override
    public boolean isPushable() {
        return false;
    }

    // ===================== 无法被 NBT 覆盖的硬性语义 =====================

    /**
     * 无敌。
     * <p>
     * 这是 {@code LivingEntity#hurt} 的<b>第一道闸门</b>
     * （{@code LivingEntity.java:1084}：{@code if (this.isInvulnerableTo(source)) return false;}），
     * 覆写它即可挡住一切普通伤害，且**不依赖会被 NBT 覆盖的 {@code invulnerable} 字段**。
     * <p>
     * 放行的只有 {@code bypasses_invulnerability} 标签里的两项
     * （{@code minecraft:out_of_world} 与 {@code minecraft:generic_kill}）
     * ⇒ {@code /kill} 与虚空**仍能移除它**（刻意留的管理后路）。
     * <p>
     * 参考实现：传奇怪物（Legendary Monsters）的休眠怪一律覆写 {@code hurt()} 按状态判定
     * （如 {@code Frostbitten_GolemEntity:528-532}），同样不依赖那个字段。
     */
    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || super.isInvulnerableTo(source);
    }

    /**
     * 无 AI。
     * <p>
     * 覆写而不是只 {@code setNoAi(true)}：{@code Mob#isEffectiveAi()} 依赖本方法，
     * 恒为 false ⇒ 目标选择器永不运行。
     */
    @Override
    public boolean isNoAi() {
        return true;
    }

    /**
     * 永不消失。
     * <p>
     * {@code Mob#checkDespawn}（{@code Mob.java:703-716}）的闸门就是本方法
     * （只看 {@code persistenceRequired} / {@code requiresCustomPersistence()}，
     * <b>不看</b> {@code MobCategory} 的 {@code isPersistent}），所以必须在这里保证。
     */
    @Override
    public boolean isPersistenceRequired() {
        return true;
    }

    // ===================== GeckoLib =====================

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // transitionTickTime = 0：只有待机一个动作，不需要过渡
        controllers.add(new AnimationController<>(this, "main", 0, this::idle));
    }

    /** 永远返回待机动画。 */
    private <E extends GeoEntity> PlayState idle(AnimationState<E> state) {
        return state.setAndContinue(IDLE);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
