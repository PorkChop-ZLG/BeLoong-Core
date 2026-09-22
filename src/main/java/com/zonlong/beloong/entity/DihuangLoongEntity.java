package com.zonlong.beloong.entity;

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
 *   <li><b>无 AI</b>：不覆写 {@code registerGoals()}，并在构造里 {@code setNoAi(true)}；
 *       {@code Mob#isEffectiveAi()} 会因此返回 false，目标选择器完全不跑；</li>
 *   <li><b>无敌</b>：{@code setInvulnerable(true)}，随 NBT 存取；{@code /kill} 与创造模式玩家
 *       仍能移除它 —— 这是**刻意留的管理后路**；</li>
 *   <li><b>不可推动、永不消失</b>：{@link #isPushable()} 覆写为 false；实体类别用
 *       {@code MobCategory.MISC}（不占刷怪上限）并显式 {@code setPersistenceRequired()}；</li>
 *   <li><b>站在地上</b>：不动重力，也不缩放（按模型默认尺寸渲染）。</li>
 * </ul>
 * 设计文档：{@code docs/plans/2026-09-21-dihuang-loong-npc-design.md}。
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
        // 无 AI。与"不覆写 registerGoals()"构成双保险。
        setNoAi(true);
        // 无敌。
        setInvulnerable(true);
        // 永不消失（MobCategory.MISC 本身已是 persistent，这里再显式声明一次）。
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
                .add(Attributes.MAX_HEALTH, 100.0D)
                // 打不动也推不动：免疫爆炸与攻击造成的击退；玩家挤压另由 isPushable() 兜住
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D);
    }

    /** 站桩 NPC 不该被玩家推着走。 */
    @Override
    public boolean isPushable() {
        return false;
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
