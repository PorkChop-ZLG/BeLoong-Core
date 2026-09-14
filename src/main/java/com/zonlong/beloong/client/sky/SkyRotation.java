package com.zonlong.beloong.client.sky;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;

/**
 * 天空盒图层旋转配置。
 *
 * <p>参考 NeoForgeSkyboxes 的 Rotation 实现，支持轴旋转、时间旋转和静态旋转。</p>
 */
public final class SkyRotation {

    public static final SkyRotation STAR_ROTATION = new SkyRotation(0, 0, 1, 0, 0, 0, false, 0, 0, 0, 0, 0, 0);
    public static final SkyRotation DAY_ROTATION = new SkyRotation(0, 1, 0, 0, 0, 0, true, 0, 0, 0, 0, 0, 0);
    public static final SkyRotation SUN_ROTATION = new SkyRotation(0, 1, 0, 0, 0, 0, true, 0, 0, 0, 0, 0, 0);
    public static final SkyRotation FLARE_ROTATION = new SkyRotation(0, 0, 1, 0, 0, 0, false, 0, 0, 0, 0, 0, 0);
    public static final SkyRotation DECORATION_ROTATION = new SkyRotation(0, 0, 1, 0, 0, 0, false, 0, 0, 0, 0, 0, 0);

    private final float rotationSpeedX;
    private final float rotationSpeedY;
    private final float rotationSpeedZ;
    private final int timeShiftX;
    private final int timeShiftY;
    private final int timeShiftZ;
    private final boolean skyboxRotation;
    private final float axisX;
    private final float axisY;
    private final float axisZ;
    private final float staticX;
    private final float staticY;
    private final float staticZ;

    public SkyRotation(float rotationSpeedX,
                       float rotationSpeedY,
                       float rotationSpeedZ,
                       int timeShiftX,
                       int timeShiftY,
                       int timeShiftZ,
                       boolean skyboxRotation,
                       float axisX,
                       float axisY,
                       float axisZ,
                       float staticX,
                       float staticY,
                       float staticZ) {
        this.rotationSpeedX = rotationSpeedX;
        this.rotationSpeedY = rotationSpeedY;
        this.rotationSpeedZ = rotationSpeedZ;
        this.timeShiftX = timeShiftX;
        this.timeShiftY = timeShiftY;
        this.timeShiftZ = timeShiftZ;
        this.skyboxRotation = skyboxRotation;
        this.axisX = axisX;
        this.axisY = axisY;
        this.axisZ = axisZ;
        this.staticX = staticX;
        this.staticY = staticY;
        this.staticZ = staticZ;
    }

    public void apply(PoseStack poseStack, ClientLevel level) {
        float timeRotationX = calculateAxisRotation(level, rotationSpeedX, timeShiftX);
        float timeRotationY = calculateAxisRotation(level, rotationSpeedY, timeShiftY);
        float timeRotationZ = calculateAxisRotation(level, rotationSpeedZ, timeShiftZ);

        poseStack.mulPose(Axis.XP.rotationDegrees(axisX));
        poseStack.mulPose(Axis.YP.rotationDegrees(axisY));
        poseStack.mulPose(Axis.ZP.rotationDegrees(axisZ));

        poseStack.mulPose(Axis.XP.rotationDegrees(timeRotationX));
        poseStack.mulPose(Axis.YP.rotationDegrees(timeRotationY));
        poseStack.mulPose(Axis.ZP.rotationDegrees(timeRotationZ));

        poseStack.mulPose(Axis.ZN.rotationDegrees(axisZ));
        poseStack.mulPose(Axis.YN.rotationDegrees(axisY));
        poseStack.mulPose(Axis.XN.rotationDegrees(axisX));

        poseStack.mulPose(Axis.XP.rotationDegrees(staticX));
        poseStack.mulPose(Axis.YP.rotationDegrees(staticY));
        poseStack.mulPose(Axis.ZP.rotationDegrees(staticZ));
    }

    private float calculateAxisRotation(ClientLevel level, float speed, int timeShift) {
        if (speed == 0.0F) {
            return 0.0F;
        }
        long timeOfDay = level.getDayTime() + timeShift;
        double fraction = timeOfDay / (24000.0 / speed);
        double skyAngle = Mth.frac(fraction);
        if (skyboxRotation) {
            return (float) (360.0 * skyAngle);
        } else {
            return 360.0F * level.dimensionType().timeOfDay((long) (24000 * skyAngle));
        }
    }
}
