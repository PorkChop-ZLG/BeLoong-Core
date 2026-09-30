package com.zonlong.beloong.client.model;

import com.github.L_Ender.lionfishapi.client.model.tools.AdvancedEntityModel;
import com.github.L_Ender.lionfishapi.client.model.tools.AdvancedModelBox;
import com.github.L_Ender.lionfishapi.client.model.tools.BasicModelPart;
import com.google.common.collect.ImmutableList;
import com.zonlong.beloong.block.HellGateBlockEntity;
import com.zonlong.beloong.client.animation.HellGateAnimation;
import net.minecraft.world.entity.Entity;

/**
 * 地狱之门的盒状模型 —— 灾变 {@code Door_Of_Seal_Model} 的移植件（几何数值一字未改）。
 *
 * <p>用 <b>LionfishAPI</b> 的 {@link AdvancedEntityModel}（不是 GeckoLib），模型是<b>手写盒子</b>、
 * 没有 geo 文件。贴图 256×256，含四个部件：
 * <ul>
 *   <li>{@code roots} —— 根，位于 y=24（贴地）</li>
 *   <li>{@code left_door} / {@code right_door} —— 双扇门，各 40×128×16（单位 1/16 格）⇒ 合起来
 *       <b>5 格宽 × 8 格高</b>，与多方块尺寸一致；旋转点分别在 x=+40 / x=-40（即门轴）</li>
 *   <li>{@code lock} —— 门锁，位于 y=-24、z=-9（门前）；带一个 45° 旋转的子盒 {@code cube_r1}（32×32×2）</li>
 * </ul>
 *
 * <p>{@link #animate} 把两个 {@link com.zonlong.beloong.client.animation.HellGateAnimation} 定义
 * 分别绑到实体的 {@code opening} / {@code open} 动画状态上 —— 这正是「开门中」与「已敞开」两种姿态的来源。
 *
 * @see HellGateAnimation
 * @see com.zonlong.beloong.client.HellGateRenderer
 */
public class HellGateModel extends AdvancedEntityModel<Entity> {
    public final AdvancedModelBox roots;
    public final AdvancedModelBox left_door;
    public final AdvancedModelBox right_door;
    public final AdvancedModelBox lock;
    public final AdvancedModelBox cube_r1;

    public HellGateModel() {
        texWidth = 256;
        texHeight = 256;

        roots = new AdvancedModelBox(this, "roots");
        roots.setRotationPoint(0.0F, 24.0F, 0.0F);

        left_door = new AdvancedModelBox(this, "left_door");
        left_door.setRotationPoint(40.0F, -64.0F, 0.0F);
        roots.addChild(left_door);
        left_door.setTextureOffset(0, 0).addBox(-40.0F, -64.0F, -8.0F, 40.0F, 128.0F, 16.0F, 0.0F, true);

        right_door = new AdvancedModelBox(this, "right_door");
        right_door.setRotationPoint(-40.0F, -64.0F, 0.0F);
        roots.addChild(right_door);
        right_door.setTextureOffset(0, 0).addBox(0.0F, -64.0F, -8.0F, 40.0F, 128.0F, 16.0F, 0.0F, false);

        lock = new AdvancedModelBox(this, "lock");
        lock.setRotationPoint(0.0F, -24.0F, -9.0F);
        roots.addChild(lock);

        cube_r1 = new AdvancedModelBox(this, "cube_r1");
        cube_r1.setRotationPoint(0.0F, 7.9F, 0.0F);
        lock.addChild(cube_r1);
        setRotationAngle(cube_r1, 0.0F, 0.0F, 0.7854F);
        cube_r1.setTextureOffset(0, 144).addBox(-21.6F, -21.6F, -1.0F, 32.0F, 32.0F, 2.0F, 0.0F, false);
        this.updateDefaultPose();
    }

    public BasicModelPart root() {
        return this.roots;
    }

    @Override
    public Iterable<AdvancedModelBox> getAllParts() {
        return ImmutableList.of(roots, left_door, right_door, lock, cube_r1);
    }

    @Override
    public void setupAnim(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                          float netHeadYaw, float headPitch) {
        this.resetToDefaultPose();
    }

    /** 按实体当前的动画状态摆姿（{@code partialTick} 用于帧间插值）。 */
    public void animate(HellGateBlockEntity entity, float partialTick) {
        this.resetToDefaultPose();
        float ageInTicks = entity.tickCount + partialTick;
        this.animate(entity.getAnimationState("opening"), HellGateAnimation.OPEN, ageInTicks, 1.0F);
        this.animate(entity.getAnimationState("open"), HellGateAnimation.OPEN_IDLE, ageInTicks, 1.0F);
    }

    /** 原版照搬的辅助方法（参数名由 {@code AdvancedModelBox} 改为 {@code box}，避免遮蔽类型名）。 */
    public void setRotationAngle(AdvancedModelBox box, float x, float y, float z) {
        box.rotateAngleX = x;
        box.rotateAngleY = y;
        box.rotateAngleZ = z;
    }
}
