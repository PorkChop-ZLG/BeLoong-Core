package com.zonlong.beloong.client.animation;

import com.github.L_Ender.lionfishapi.client.model.AdvancedAnimations.AdvancedAnimationChannel;
import com.github.L_Ender.lionfishapi.client.model.AdvancedAnimations.AdvancedAnimationDefinition;
import com.github.L_Ender.lionfishapi.client.model.AdvancedAnimations.AdvancedKeyframe;
import com.github.L_Ender.lionfishapi.client.model.AdvancedAnimations.AdvancedKeyframeAnimations;

/**
 * 地狱之门的开启动画 —— 灾变 {@code Door_Of_Seal_Animation} 的移植件（关键帧一字未改）。
 *
 * <p>两个动画定义：
 * <ul>
 *   <li>{@link #OPEN} —— 开门动画，总长 <b>7.25 s = 145 tick</b>（与
 *       {@code HellGateBlockEntity.TICK_FULLY_OPEN} 对应）。双扇门在 2.5→5.25 s 由 5° 转到 ±90°，
 *       锁在 0→1.5 s 抖动（左右旋转 + 上下位移），1.5833 s 时缩放归零（锁消失）。</li>
 *   <li>{@link #OPEN_IDLE} —— 开启后的待机姿态：双扇门保持 ±95°，锁保持不可见（循环动画，长度 0）。</li>
 * </ul>
 *
 * <p>插值全部是 {@code CATMULLROM}（锁的缩放是 {@code LINEAR}），与灾变逐帧一致。
 *
 * @see com.zonlong.beloong.client.model.HellGateModel
 */
public class HellGateAnimation {
    public static final AdvancedAnimationDefinition OPEN = AdvancedAnimationDefinition.Builder.withLength(7.25F)
            .addAnimation("left_door", new AdvancedAnimationChannel(AdvancedAnimationChannel.Targets.ROTATION,
                    new AdvancedKeyframe(0.0F, AdvancedKeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(1.75F, AdvancedKeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(2.5F, AdvancedKeyframeAnimations.degreeVec(0.0F, 5.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(5.25F, AdvancedKeyframeAnimations.degreeVec(0.0F, 90.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(6.0F, AdvancedKeyframeAnimations.degreeVec(0.0F, 95.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(6.875F, AdvancedKeyframeAnimations.degreeVec(0.0F, 95.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM)
            ))
            .addAnimation("right_door", new AdvancedAnimationChannel(AdvancedAnimationChannel.Targets.ROTATION,
                    new AdvancedKeyframe(0.0F, AdvancedKeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(1.75F, AdvancedKeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(2.5F, AdvancedKeyframeAnimations.degreeVec(0.0F, -5.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(5.25F, AdvancedKeyframeAnimations.degreeVec(0.0F, -90.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(6.0F, AdvancedKeyframeAnimations.degreeVec(0.0F, -95.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(6.875F, AdvancedKeyframeAnimations.degreeVec(0.0F, -95.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM)
            ))
            .addAnimation("lock", new AdvancedAnimationChannel(AdvancedAnimationChannel.Targets.ROTATION,
                    new AdvancedKeyframe(0.0F, AdvancedKeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(0.1667F, AdvancedKeyframeAnimations.degreeVec(0.0F, 0.0F, -2.5F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(0.3333F, AdvancedKeyframeAnimations.degreeVec(0.3898F, 0.3406F, 4.9743F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(0.5F, AdvancedKeyframeAnimations.degreeVec(0.0F, 0.0F, -2.5F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(0.6667F, AdvancedKeyframeAnimations.degreeVec(0.3898F, 0.3406F, 4.9743F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(0.8333F, AdvancedKeyframeAnimations.degreeVec(0.0F, 0.0F, -2.5F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(0.9167F, AdvancedKeyframeAnimations.degreeVec(0.3898F, 0.3406F, 4.9743F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(1.0F, AdvancedKeyframeAnimations.degreeVec(0.0F, 0.0F, -2.5F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(1.0833F, AdvancedKeyframeAnimations.degreeVec(0.3898F, 0.3406F, 4.9743F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(1.1667F, AdvancedKeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(1.2083F, AdvancedKeyframeAnimations.degreeVec(0.3898F, 0.3406F, 4.9743F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(1.25F, AdvancedKeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(1.2917F, AdvancedKeyframeAnimations.degreeVec(0.3898F, 0.3406F, 4.9743F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(1.3333F, AdvancedKeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(1.375F, AdvancedKeyframeAnimations.degreeVec(0.3898F, 0.3406F, 4.9743F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(1.4167F, AdvancedKeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(1.4583F, AdvancedKeyframeAnimations.degreeVec(0.3898F, 0.3406F, 4.9743F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(1.5F, AdvancedKeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM)
            ))
            .addAnimation("lock", new AdvancedAnimationChannel(AdvancedAnimationChannel.Targets.POSITION,
                    new AdvancedKeyframe(0.0F, AdvancedKeyframeAnimations.posVec(0.0F, 0.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(0.1667F, AdvancedKeyframeAnimations.posVec(0.0F, -1.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(0.4583F, AdvancedKeyframeAnimations.posVec(0.0F, 0.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(0.625F, AdvancedKeyframeAnimations.posVec(-1.0F, 0.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(0.875F, AdvancedKeyframeAnimations.posVec(0.0F, 0.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(1.1667F, AdvancedKeyframeAnimations.posVec(0.0F, -1.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM),
                    new AdvancedKeyframe(1.5F, AdvancedKeyframeAnimations.posVec(0.0F, -1.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM)
            ))
            .addAnimation("lock", new AdvancedAnimationChannel(AdvancedAnimationChannel.Targets.SCALE,
                    new AdvancedKeyframe(0.0F, AdvancedKeyframeAnimations.scaleVec(1.0F, 1.0F, 1.0F), AdvancedAnimationChannel.Interpolations.LINEAR),
                    new AdvancedKeyframe(1.499F, AdvancedKeyframeAnimations.scaleVec(1.0F, 1.0F, 1.0F), AdvancedAnimationChannel.Interpolations.LINEAR),
                    new AdvancedKeyframe(1.5F, AdvancedKeyframeAnimations.scaleVec(1.0F, 1.0F, 1.0F), AdvancedAnimationChannel.Interpolations.LINEAR),
                    new AdvancedKeyframe(1.5823F, AdvancedKeyframeAnimations.scaleVec(1.0F, 1.0F, 1.0F), AdvancedAnimationChannel.Interpolations.LINEAR),
                    new AdvancedKeyframe(1.5833F, AdvancedKeyframeAnimations.scaleVec(0.0F, 0.0F, 0.0F), AdvancedAnimationChannel.Interpolations.LINEAR)
            ))
            .build();

    public static final AdvancedAnimationDefinition OPEN_IDLE = AdvancedAnimationDefinition.Builder.withLength(0.0F).looping()
            .addAnimation("left_door", new AdvancedAnimationChannel(AdvancedAnimationChannel.Targets.ROTATION,
                    new AdvancedKeyframe(0.0F, AdvancedKeyframeAnimations.degreeVec(0.0F, 95.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM)
            ))
            .addAnimation("right_door", new AdvancedAnimationChannel(AdvancedAnimationChannel.Targets.ROTATION,
                    new AdvancedKeyframe(0.0F, AdvancedKeyframeAnimations.degreeVec(0.0F, -95.0F, 0.0F), AdvancedAnimationChannel.Interpolations.CATMULLROM)
            ))
            .addAnimation("lock", new AdvancedAnimationChannel(AdvancedAnimationChannel.Targets.SCALE,
                    new AdvancedKeyframe(0.0F, AdvancedKeyframeAnimations.scaleVec(0.0F, 0.0F, 0.0F), AdvancedAnimationChannel.Interpolations.LINEAR)
            ))
            .build();
}
