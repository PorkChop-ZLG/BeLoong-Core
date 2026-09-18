package com.zonlong.beloong.registry;

import com.zonlong.beloong.compat.dragonsurvival.ClawSwordKillTrigger;
import com.zonlong.beloong.compat.dragonsurvival.ClawSwordSwapTrigger;
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
 * <p>当前注册的是爪牙槽教学进度用的一对判据（见 {@link ClawSwordSwapTrigger}
 * 与 {@link ClawSwordKillTrigger}）。注册名即数据包在进度 JSON 里书写的形式，例如
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

    private ModCriteria() {
    }
}
