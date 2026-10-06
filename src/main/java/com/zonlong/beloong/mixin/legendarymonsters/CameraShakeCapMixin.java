package com.zonlong.beloong.mixin.legendarymonsters;

import com.zonlong.beloong.perf.EffectEntityCap;
import net.miauczel.legendary_monsters.entity.AnimatedMonster.Effect.CameraShakeEntity;
import net.miauczel.legendary_monsters.entity.ModEntities;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给传奇怪物的特效实体 {@code legendary_monsters:camera_shake} 加"每维度在存量"上限。
 *
 * <p>注入点是静态工厂 {@code CameraShakeEntity#cameraShake} —— 它是<b>唯一漏斗</b>：
 * 源码树里 182 处调用点（41 个文件）全部经由它入世界。</p>
 *
 * <p><b>为什么必须限流而不是"等它自己销毁"</b>：该实体的销毁路径只有
 * {@code tick()} 里的 {@code tickCount > duration + fadeDuration -> discard()}，
 * 而 1.21.1 的 {@code tickCount} 由 {@code ServerLevel#tickNonPassenger} 写入，
 * 实体 tick 还受 {@code inEntityTickingRange} 门控 —— 不在实体刻范围内的实例
 * <b>永不自毁</b>，却仍被 {@code ChunkMap.entityMap} 跟踪，进而被"每个移动包一次"的
 * {@code ChunkMap.move} 全量遍历。详见 {@link EffectEntityCap} 与
 * docs/plans/2026-10-06-lm-camera-shake-entity-flood-handover.md。</p>
 *
 * <p>传奇怪物为可选依赖：{@code @Pseudo} + {@code require = 0}（与同包的
 * {@link AnnihilationPursuerDamageCapMixin} 同约定），未安装时本 Mixin 整体跳过。</p>
 *
 * <p>注意 ①：本 Mixin 只是<b>超限预筛</b>（超限时提前取消，省掉实体构造），<b>不记账</b>——
 * 放行与拒绝的记账统一由 {@code perf/EffectEntityJoinGate} 挂在
 * {@code EntityJoinLevelEvent} 上完成，因此 {@code /summon}、数据包、其它模组的
 * {@code addFreshEntity} 同样受同一个上限约束。</p>
 *
 * <p>注意 ②：本 Mixin 覆盖模组自身的召唤路径；即便它因上游改名而静默失效，
 * 入世界闸门仍会兜住这些实体（代价只是多构造一次实体）。</p>
 */
@Pseudo
@Mixin(value = CameraShakeEntity.class, remap = false)
public abstract class CameraShakeCapMixin {

    /**
     * 目标签名（已对线上 2.2.3 的 jar 核对）：
     * {@code cameraShake(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;FFII)V}。
     * 目标是<b>静态</b>方法，因此处理函数也必须是静态的。
     */
    @Inject(method = "cameraShake", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void beloong$capCameraShake(Level level, Vec3 position, float radius, float magnitude,
                                               int duration, int fadeDuration, CallbackInfo ci) {
        if (EffectEntityCap.shouldRefuseSpawn(level, ModEntities.CAMERA_SHAKE.get())) {
            ci.cancel();
        }
    }
}
