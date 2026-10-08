package com.zonlong.beloong.compat.legendarymonsters;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * 传奇怪物 2.2.x 投射物"命中实体强转 {@link LivingEntity}"崩溃的共享守卫。
 *
 * <p><b>崩溃现象</b>（两份实机日志：2026-10-06 22:00:47、2026-10-08 12:19:07）：
 * {@code SmallAnnihilationBombEntity}（{@code legendary_monsters:small_dimensional_bomb}）的
 * {@code onHitEntity} 里为了给 {@code MathUtils.entityBasedHpDamage} 传参写了
 * {@code (LivingEntity) target}。一旦命中"部件实体"——冰火传说 {@code DragonPartEntity}、
 * Iron's Spellbooks {@code ShieldPart}、原版 {@code EnderDragonPart}、LM 自家的
 * {@code PartEntity} 等——因为它们都<b>不是</b> {@code LivingEntity}，直接抛
 * {@code ClassCastException}，在实体 tick 里把服务器带崩（{@code Description: Ticking entity}）。</p>
 *
 * <p><b>这是 2.2.x 的回归</b>：LM 2.1.15 此处只调用 {@code target.hurt(...)}，而部件自己的
 * {@code hurt} 会把伤害转给本体（冰火 {@code MultipartPartEntity#hurt} →
 * {@code parent.hurt(source, damage * damageMultiplier)}；NeoForge {@code PartEntity} 同理），
 * 所以"打中部件"原本是安全的；2.2.x 为加"按目标最大生命值加伤"才引入这个强转。</p>
 *
 * <p><b>为什么是"跳过整个 onHitEntity"而不是"把命中实体换成父实体"</b>：部件有三套互不兼容的
 * API（NeoForge {@code PartEntity}、冰火自家的 {@code MultipartPartEntity}、cerbons 的
 * {@code EntityPart}），而且 Iron's 的 {@code ShieldPart} 的父实体
 * {@code AbstractShieldEntity} 根本不是生物 ⇒ 换父实体既复杂又救不了它。
 * "命中实体不是生物"这一条判据则与部件类型完全无关，对以后新增的部件/非生物实体同样有效。</p>
 *
 * <p><b>取消为什么安全</b>：原版 {@code Projectile#onHitEntity} 是<b>空实现</b>，且异常消失后
 * {@code Projectile#onHit} 仍会继续执行（粒子 + {@code discard()} + 音效）⇒ 投射物照常爆炸消失，
 * 不会留下卡住的哑弹。代价是命中部件时不再造成伤害（相比 2.1.15 少一次伤害，这是最小取舍）。
 * 本修复<b>只注入传奇怪物自己的类</b>，不触碰原版与其它模组。</p>
 */
public final class ProjectileHitGuard {

    private ProjectileHitGuard() {}

    /** 日志节流（毫秒）：部件命中通常很罕见，但护盾类部件可能被连续命中。 */
    private static final long LOG_INTERVAL_MS = 5_000L;

    /** 仅用于诊断的累计计数与上次输出时间（允许竞态：最坏情况多打一行日志）。 */
    private static long skipped;
    private static long lastLogAt;

    /**
     * 命中实体不是 {@link LivingEntity} 时，应取消这次 {@code onHitEntity}（否则 LM 会强转崩溃）。
     *
     * <p>两端都会取消（保持行为一致），但<b>只在服务端记账/打日志</b>：客户端这次调用本来就会在
     * LM 的 {@code onHitEntity} 第二行 {@code if (level().isClientSide) return;} 立即返回，
     * 在渲染线程上做同步日志写入既无收益也会造成肉眼可见的卡帧（实机 spark 报告已确认无热点，
     * 这里只是不做无谓工作）。</p>
     *
     * @param hitEntity 被命中的实体（{@code EntityHitResult#getEntity()}）
     * @param projectile 触发它的投射物注册名（用于日志定位）
     * @return {@code true} = 调用方应 {@code ci.cancel()}
     */
    public static boolean shouldSkipNonLivingHit(Entity hitEntity, String projectile) {
        if (hitEntity instanceof LivingEntity) {
            return false;
        }
        if (!hitEntity.level().isClientSide) {
            skipped++;
            logSkipped(hitEntity, projectile);
        }
        return true;
    }

    /** 输出英文 ASCII 锚点（本项目约定：日志一律英文 ASCII，中文只进注释）。 */
    private static void logSkipped(Entity hitEntity, String projectile) {
        long now = System.currentTimeMillis();
        if (lastLogAt != 0L && now - lastLogAt < LOG_INTERVAL_MS) {
            return;
        }
        lastLogAt = now;
        // 部件实体的注册名通常是**父实体**的类型（冰火三种龙、LM 幻影模仿者），只打 id 会误读成
        // "打中了龙本身"，所以同时打出命中实体的类名（如 DragonPartEntity）。
        String hitClass = hitEntity.getClass().getSimpleName();
        if (hitClass.isEmpty()) {
            hitClass = hitEntity.getClass().getName();
        }
        BeLoongCore.LOGGER.warn("[BeLoong] lm-projectile-hit-guard: projectile={} hit={}(id={}) skipped={}",
                projectile,
                hitClass,
                BuiltInRegistries.ENTITY_TYPE.getKey(hitEntity.getType()),
                skipped);
    }
}
