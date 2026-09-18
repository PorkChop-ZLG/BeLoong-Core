package com.zonlong.beloong.registry;

import com.zonlong.beloong.compat.dragonsurvival.ClawSwordKillTrigger;
import com.zonlong.beloong.compat.dragonsurvival.ClawSwordSwapTrigger;
import com.zonlong.beloong.compat.ironsspellbooks.DeadKingKillTrigger;
import com.zonlong.beloong.compat.ironsspellbooks.OminousDeadKingKillTrigger;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 化龙核心的进度判据注册中心。
 *
 * <p>判据（{@code CriterionTrigger}）属于冻结注册表 {@link Registries#TRIGGER_TYPE}，
 * 必须在模组构造期通过 mod 事件总线注册；注册失败会在启动期直接报错，不会静默失效。</p>
 *
 * <p>当前注册两组判据：</p>
 * <ul>
 *   <li><b>爪牙槽教学进度</b>：{@link ClawSwordSwapTrigger} 与 {@link ClawSwordKillTrigger}；</li>
 *   <li><b>死者之王击杀进度</b>：{@link DeadKingKillTrigger}（普通变种）与
 *       {@link OminousDeadKingKillTrigger}（不祥变种），由
 *       {@code compat.ironsspellbooks.DeadKingAdvancementHandler} 触发。</li>
 * </ul>
 *
 * <p>注册名即数据包在进度 JSON 里书写的形式，例如
 * {@code "trigger": "beloong:claw_sword_swap"} —— <b>改名会让已有进度失效</b>。</p>
 */
public final class ModCriteria {

    public static final DeferredRegister<CriterionTrigger<?>> REGISTRY =
            DeferredRegister.create(Registries.TRIGGER_TYPE, "beloong");

    /** 爪牙槽中的剑被换入主手时触发。 */
    public static final Supplier<ClawSwordSwapTrigger> CLAW_SWORD_SWAP =
            REGISTRY.register("claw_sword_swap", ClawSwordSwapTrigger::new);

    /** 在爪牙槽中的剑换入主手期间击杀生物时触发。 */
    public static final Supplier<ClawSwordKillTrigger> CLAW_SWORD_KILL =
            REGISTRY.register("claw_sword_kill", ClawSwordKillTrigger::new);

    /**
     * 参与击败<b>普通</b>状态死者之王时触发。
     *
     * <p>口径：<b>死亡时刻</b> 60 格内、<b>只排除旁观 —— 创造模式计入</b>。
     * 创造模式计入是本项目对铁魔法口径的有意偏离，不是笔误，详见
     * {@code compat.ironsspellbooks.DeadKingAdvancementHandler} 的类 javadoc。</p>
     */
    public static final Supplier<DeadKingKillTrigger> DEAD_KING_KILL =
            REGISTRY.register("dead_king_kill", DeadKingKillTrigger::new);

    /** 参与击败<b>不祥</b>状态死者之王时触发（同上口径：60 格、只排除旁观、创造计入；但 Boss 处于不祥变种）。 */
    public static final Supplier<OminousDeadKingKillTrigger> OMINOUS_DEAD_KING_KILL =
            REGISTRY.register("ominous_dead_king_kill", OminousDeadKingKillTrigger::new);

    private ModCriteria() {
    }
}
