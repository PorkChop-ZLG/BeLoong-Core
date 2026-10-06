package com.zonlong.beloong.mixin.legendarymonsters;

import com.zonlong.beloong.perf.EffectEntityCap;
import net.miauczel.legendary_monsters.entity.AnimatedMonster.Effect.DynamicCameraZoomEntity;
import net.miauczel.legendary_monsters.entity.ModEntities;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给传奇怪物的特效实体 {@code legendary_monsters:dynamic_camera_zoom} 加"每维度在存量"上限。
 *
 * <p>它是 {@code camera_shake} 的<b>同族第二个隐患，而且销毁条件更糟</b>：注册方式逐字对称
 * （{@code MobCategory.MISC} + 裸 {@code Entity}），但 {@code tick()} 的销毁条件是
 * {@code tickCount > duration + zoomFreeze && zoomIncrement == 0} ——
 * {@code zoomIncrement} 是反复 {@code -= zoomSpeed} 的 <b>float</b>，一旦越过 0 就永远不等于 0，
 * 于是<b>即使被正常 tick 也可能永不销毁</b>。它的调用点比 camera_shake 少得多
 * （源码树 3 处，事故普查里未出现），但同属"无界特效实体"，故一并设上限。</p>
 *
 * <p>传奇怪物为可选依赖：{@code @Pseudo} + {@code require = 0}，未安装时本 Mixin 整体跳过。</p>
 *
 * <p>注意：本 Mixin 只覆盖模组自身的召唤路径；{@code /summon} 等运维手段不受限。</p>
 */
@Pseudo
@Mixin(value = DynamicCameraZoomEntity.class, remap = false)
public abstract class DynamicCameraZoomCapMixin {

    /**
     * 目标签名（已对线上 2.2.3 的 jar {@code javap -s} 核对）：
     * {@code dynamicCameraZoom(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;FFIIFZLnet/minecraft/world/entity/LivingEntity;)V}。
     * <p>两个静态重载同名，因此 {@code method} <b>必须写全描述符</b>，否则 Mixin 解析有歧义。</p>
     */
    @Inject(
            method = "dynamicCameraZoom(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;FFIIFZLnet/minecraft/world/entity/LivingEntity;)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void beloong$capZoomWithCamera(Level level, Vec3 position, float radius, float maxZoom,
                                                  int duration, int zoomFreeze, float zoomSpeed, boolean cameraLocked,
                                                  LivingEntity cameraEntity, CallbackInfo ci) {
        if (EffectEntityCap.shouldSuppress(level, ModEntities.DYNAMIC_CAMERA_ZOOM.get())) {
            ci.cancel();
        }
    }

    /**
     * 目标签名：{@code dynamicCameraZoom(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;FFIIF)V}。
     */
    @Inject(
            method = "dynamicCameraZoom(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;FFIIF)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void beloong$capZoom(Level level, Vec3 position, float radius, float maxZoom,
                                        int duration, int zoomFreeze, float zoomSpeed, CallbackInfo ci) {
        if (EffectEntityCap.shouldSuppress(level, ModEntities.DYNAMIC_CAMERA_ZOOM.get())) {
            ci.cancel();
        }
    }
}
