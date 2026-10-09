package com.zonlong.beloong.compat.mowziesmobs;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * 「太阳祝福」三招的伤害类型常量与伤害源转换工具。
 *
 * <p><b>背景</b>：Mowzie's Mobs 全库<b>没有任何自定义伤害类型</b>，太阳三招用的是原版通用类型
 * （太阳耀斑 = {@code player_attack}；太阳射线 / 太阳打击 = {@code mob_projectile} + {@code on_fire} 两段混合）。
 * 原版类型无法充当「破防入口」——把它们写进 tag 等于让<b>所有</b>玩家攻击 / 弹射物都变成破防伤害。
 * 因此本模组自建三个伤害类型（注册文件 {@code data/mowziesmobs/damage_type/*.json}），
 * 再由 {@code mixin/mowziesmobs/*} 把三招的伤害源换过来。
 *
 * <p><b>为什么注册文件借用了 {@code mowziesmobs} 命名空间</b>：让 tag 内容读起来就是
 * 「太阳耀斑 / 太阳射线 / 太阳打击」这三种太阳伤害（用户选择）。
 * 代价是<b>命名空间遮蔽风险</b>：若将来 Mowzie 自己加了同名 id，资源栈里优先级高的一方静默胜出。
 * 回退方案是整体改名到 {@code beloong:} 命名空间（类型 json / tag / {@code message_id} / 翻译键四处同步）。
 *
 * <p><b>注册项与代码的隔离</b>：三个伤害类型只出现在本类 + 数据文件 + 死亡信息翻译里；
 * 骑士侧只认 tag（{@code registry/ModDamageTypeTags}），不认识本类，
 * 因此「谁可以破防」这件事完全由数据决定。
 */
public final class SolarDamageTypes {

    /** 太阳耀斑（{@code SolarFlareAbility}：太阳祝福下潜行左键的范围攻击）。 */
    public static final ResourceKey<DamageType> SOLAR_FLARE = key("solar_flare");

    /** 太阳射线（{@code EntitySolarBeam}：太阳祝福下潜行右键射出的持续光束）。 */
    public static final ResourceKey<DamageType> SOLAR_BEAM = key("solar_beam");

    /** 太阳打击（{@code EntitySunstrike}：太阳鸟 / 太阳祝福的范围打击）。 */
    public static final ResourceKey<DamageType> SUN_STRIKE = key("sun_strike");

    /** 缺失类型的警告日志节流间隔（毫秒）。 */
    private static final long WARN_THROTTLE_MS = 5000L;

    /** 上次打印缺失警告的时间戳（毫秒）；只在服务端主线程读写，无需同步。 */
    private static long lastWarnMillis = 0L;

    private SolarDamageTypes() {
    }

    private static ResourceKey<DamageType> key(String path) {
        return ResourceKey.create(
                Registries.DAMAGE_TYPE,
                ResourceLocation.fromNamespaceAndPath("mowziesmobs", path));
    }

    /**
     * 把原伤害源换成我们的太阳伤害类型，并<b>保留原来的 direct / causing 实体</b>。
     *
     * <p><b>保留实体引用是必须的</b>：Mowzie 的 {@code DamageUtil.dealMixedDamage} 会用
     * {@code target.isAlliedTo(source.getEntity())} 判断友军免伤；光束 / 打击的
     * {@code LeaderSunstrikeImmune} 检查、击杀归属、经验与掉落判定也都依赖这两个实体。
     * 只换伤害类型（而非重建一个空源）才能让这些语义全部保持不变。
     *
     * <p><b>{@code on_fire} 段直通</b>：太阳射线 / 太阳打击是「弹射物 + 燃烧」两段混合伤害。
     * 保留第二段为原版 {@code on_fire} 有两个好处：① 燃烧效果与火焰保护附魔语义不变；
     * ② 只有第一段命中破防 tag ⇒ 一次命中只扣 1 层免疫层数（不会一次扣 2 层）。
     *
     * <p><b>失败降级</b>：注册表里找不到目标类型时（例如数据文件被禁用 / 写错）不抛异常打断伤害流程，
     * 而是按原版类型继续并打一条节流警告 —— 表现退化为「太阳伤害被护盾挡下」，不会崩。
     *
     * @param original 原伤害源（Mowzie 构造的 {@code player_attack} / {@code mob_projectile} / {@code on_fire}）
     * @param target   目标太阳伤害类型（{@link #SOLAR_FLARE} / {@link #SOLAR_BEAM} / {@link #SUN_STRIKE}）
     * @return 换成目标类型的新伤害源；不满足条件时原样返回 {@code original}
     */
    public static DamageSource convert(DamageSource original, ResourceKey<DamageType> target) {
        // 燃烧段不换：换掉会丢燃烧与火焰保护语义，并让一次命中扣掉两层免疫层数
        if (original.is(DamageTypes.ON_FIRE)) {
            return original;
        }

        Entity direct = original.getDirectEntity();
        Entity causing = original.getEntity();
        Level level = direct != null ? direct.level() : (causing != null ? causing.level() : null);
        if (level == null) {
            return original;
        }

        try {
            Holder<DamageType> holder = level.registryAccess()
                    .registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(target);
            return new DamageSource(holder, direct, causing);
        } catch (Exception e) {
            warnMissingType(target, e);
            return original;
        }
    }

    private static void warnMissingType(ResourceKey<DamageType> target, Exception cause) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMillis < WARN_THROTTLE_MS) {
            return;
        }
        lastWarnMillis = now;
        BeLoongCore.LOGGER.warn(
                "[BeLoong] solar-guard-break: damage type {} is unavailable, keeping the original source ({})",
                target.location(), cause.toString());
    }
}
