package com.zonlong.beloong.compat.ironsspellbooks;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Method;

/**
 * 「这个生物自己接管了死亡序列吗？」的判定 + 霜噬事件的锚点日志。
 *
 * <p><b>为什么需要这个判定</b>：铁魔法的「霜噬」在击杀者带 {@code frostbitten_strikes}、
 * 且死者处于完全冻结时，会执行 {@code entity.deathTime = 1000}
 * （{@code io.redspace.ironsspellbooks.effect.FrostbiteEffect#handleFrostbiteDeathEffects}，
 * 源码树 {@code FrostbiteEffect.java:35}）。这一招的本意是<b>让原尸体立刻消失、只留冰雕克隆</b>——
 * 依赖的是原版 {@code LivingEntity.tickDeath()} 的 {@code deathTime >= 20} 收尾
 * （{@code LivingEntity.java:565-571}）。
 *
 * <p><b>它为什么会把别的模组打死</b>：凡是**覆写了 {@code tickDeath()}** 的生物，
 * 死亡收尾就由它自己那套计时器负责，而这些计时器常常写成
 * {@code if (deathTime == N)} 的<b>精确相等</b>：
 * <ul>
 *   <li>祸乱鬼魅：{@code LightBlockRemover:32}（{@code ==70}）、
 *       {@code ServerGauntletDeathHandler:39}（{@code ==50}）；</li>
 *   <li>永恒星光：{@code Permafrost:202}（{@code ==70}）、
 *       {@code LunarMonstrosity:216}（{@code ==75}）、{@code SolarCreeper:399}（{@code ==100}）。</li>
 * </ul>
 * 被写成 1000 之后这些比较<b>永远不成立</b> ⇒ 0 血尸体永不 {@code remove()} ⇒ 「假死」，
 * 且因 {@code isDeadOrDying()} 为真，{@code hurt()} 直接返回 false ⇒ 连 {@code /kill} 都杀不掉。
 *
 * <p><b>判定方式</b>：沿继承链找第一个<b>声明</b>了 {@code void tickDeath()} 的类，
 * 只要不是 {@link LivingEntity} 本身，就认为"它自己接管了死亡序列"。
 * 这条判据与 modid 无关，能一次覆盖所有模组（含以后新装的），因此不需要白名单。
 * 结果按 {@link ClassValue} 缓存，每个类只反射一次。
 *
 * <p><b>失败时的取向</b>：反射异常一律返回 {@code false}（**不动别人的 {@code deathTime}**）——
 * 宁可少修一个，也不要在不确定的实体上改写死亡状态。
 *
 * <p>⚠️ <b>升级必查项</b>：本判据依赖"覆写 {@code tickDeath} ⇒ 自有死亡计时器"这一约定。
 * 若某模组覆写了 {@code tickDeath} 却仍用原版 {@code >= 20} 收尾，本类不会造成问题
 * （最多让它的尸体按自己节奏消失）；反之若有模组<b>不覆写</b> {@code tickDeath}
 * 却仍用 {@code deathTime} 精确判等，本类覆盖不到。
 */
public final class CustomDeathSequence {

    private CustomDeathSequence() {
    }

    /** 锚点日志节流（毫秒）：同一次战斗里可能连续击杀多个同类怪。 */
    private static final long LOG_INTERVAL_MS = 5_000L;

    /** 累计"救回"的次数，仅用于锚点。 */
    private static long restored;

    /** 上次输出锚点的时间戳（毫秒）。 */
    private static long lastLogAt;

    private static final ClassValue<Boolean> OWNS_DEATH_SEQUENCE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return scan(type);
        }
    };

    /**
     * 该生物类型是否自己声明了 {@code tickDeath()}（即死亡收尾不归原版管）。
     *
     * @param type 生物类型
     * @return {@code true} = 覆写了 {@code tickDeath}，其 {@code deathTime} 语义由该模组自定义
     */
    public static boolean ownsDeathSequence(Class<?> type) {
        return OWNS_DEATH_SEQUENCE.get(type);
    }

    private static boolean scan(Class<?> type) {
        try {
            for (Class<?> current = type; current != null && current != LivingEntity.class; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    if (method.getName().equals("tickDeath")
                            && method.getParameterCount() == 0
                            && method.getReturnType() == void.class) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 见类注释「失败时的取向」：不确定就不动。
        }
        return false;
    }

    /**
     * 输出"已把 {@code deathTime} 还给该生物自己的死亡序列"的锚点。
     *
     * <p>日志里有这一行 ⇒ 本修复确实生效过；连一次都没有，而玩家又复现了假死，
     * 就说明上游改了实现（{@code require = 0} 下注入器会静默跳过）⇒ 查
     * {@code FrostbiteEffect} 是否还写 {@code deathTime}。
     *
     * @param entity    被击杀的生物
     * @param deathTime 铁魔法写入的原值（正常是 1000）
     */
    public static void logRestored(LivingEntity entity, int deathTime) {
        restored++;
        long now = System.currentTimeMillis();
        if (lastLogAt != 0L && now - lastLogAt < LOG_INTERVAL_MS) {
            return;
        }
        lastLogAt = now;
        BeLoongCore.LOGGER.info(
                "[BeLoong] frostbite-death: restored custom death sequence for {} (deathTime {} -> 0, total {})",
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()),
                deathTime,
                restored);
    }
}
