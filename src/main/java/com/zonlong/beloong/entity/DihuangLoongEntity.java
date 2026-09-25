package com.zonlong.beloong.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.level.Level;

/**
 * 地黄龙 NPC —— 本模组**第一个生物 NPC**，也是 {@link NpcEntity} 的第一个子类。
 * <p>
 * 它只负责"是什么"：实体类型绑定与碰撞箱（{@code registry/ModEntities}）、
 * 模型与贴图（{@code client/} 侧）、属性表，以及（将来若要）覆写基类的动画名 / 跟随距离 /
 * 转身角速度等默认值。
 * <p>
 * <b>AI、无敌 / 不可推动 / 不消失、转向 / 移动 / 攻击能力、动画状态机全部在基类里</b> ——
 * 这正是做通用基类的意义：再加一个 NPC 只需类型绑定 + 模型，不再重复这一整套。
 *
 * <h2>历史（两处被推翻的旧结论）</h2>
 * <ul>
 *   <li><b>首版是"无 AI 雕像"</b>（覆写 {@code isNoAi()} 恒 true 且不注册 goal）。
 *       那个覆写顺带把重力也关掉了：{@code LivingEntity#travel()} 的整个方法体被
 *       {@code isControlledByLocalInstance()} 包住，而它 = {@code isEffectiveAi()} =
 *       {@code !isNoAi()} ⇒ 连位移积分一起没了，实体其实被"钉"在召唤点。
 *       详见 {@link NpcEntity} 的类注释。</li>
 *   <li>同一条的补充：首版设计里"不调用 {@code setNoGravity} 所以重力正常"这个理由**是空的** ——
 *       {@code setNoGravity} 确实从没被调用过，但重力同样从没生效过。</li>
 * </ul>
 *
 * <p>设计文档：{@code docs/plans/2026-09-21-dihuang-loong-npc-design.md}（首版，R9 / R10 修订）、
 * {@code docs/plans/2026-09-25-npc-base-class-design.md}（本架构）。
 */
public class DihuangLoongEntity extends NpcEntity {

    public DihuangLoongEntity(EntityType<? extends DihuangLoongEntity> entityType, Level level) {
        super(entityType, level);
    }

    /**
     * 属性表。全部基础值来自 {@link NpcEntity#createNpcAttributes()}
     * （血量 1000 / 击退抗性 1.0 / 攻击力 100 / 移速 0.1）—— 地黄龙目前不需要改动其中任何一项。
     * <p>
     * 保留这个方法（而不是让 {@code ModAttributes} 直接调基类工厂）是为了两点：
     * ① 不动既有的注册结构；② 给"子类如何追加属性"留一个活样例 ——
     * 例如某个 NPC 想更肉，就在这里 {@code .add(Attributes.MAX_HEALTH, 3000.0D)} 覆盖基类默认值。
     * <p>
     * 注册点仍是 {@code registry/ModAttributes}：属性是**服务端权威**的，必须放双端都加载的类。
     */
    public static AttributeSupplier.Builder createAttributes() {
        return NpcEntity.createNpcAttributes();
    }
}
