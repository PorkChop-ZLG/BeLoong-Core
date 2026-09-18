package com.zonlong.beloong.client.model;

import com.zonlong.beloong.BeLoongCore;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 龙卷风模型：四个从下往上逐层变宽的方盒，各自以不同角速度绕 Y 轴自转。
 * <p>
 * 全部枢轴与贴图偏移逐个照抄灾变 {@code Sandstorm_Projectile_Model}，
 * 贴图尺寸 128×128 也一致，因此 UV 布局直接对应得上。
 * <p>
 * <b>注意朝向</b>：模型空间 y 向下增大、y=24 是地面，所以 8 宽的 {@code storm}
 * （枢轴 y=20，盒子 16…24）在<b>底部</b>，30 宽的 {@code storm4}（枢轴 y=-7，盒子 -11…-3）
 * 在<b>顶部</b>——灾变原版是「窄底宽顶」的沙尘柱。
 * <p>
 * <b>不继承</b> {@code net.minecraft.client.model.Model}/{@code EntityModel}：
 * 1.21.1 的 {@code Model} 只有 renderType 与抽象 5 参 {@code renderToBuffer}，
 * 既不持有 {@code ModelPart} 也没有 {@code (ModelPart)} 构造函数。
 */
public class TornadoModel {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "tornado"), "main");

    /** 各层绝对自转角速度（弧度 / tick），直接取自灾变的差速自转 */
    private static final float SPIN_STORM = 1.0F;
    private static final float SPIN_STORM2 = 0.5F;
    private static final float SPIN_STORM3 = 0.3F;
    private static final float SPIN_STORM4 = 0.6F;
    /** 链式摆动近似（灾变的 chainFlap） */
    private static final float SWAY_SPEED = 0.25F;
    private static final float SWAY_AMOUNT = 0.1F;

    private final ModelPart root;
    private final ModelPart storm;
    private final ModelPart storm2;
    private final ModelPart storm3;
    private final ModelPart storm4;

    public TornadoModel(ModelPart root) {
        this.root = root;
        this.storm = root.getChild("storm");
        this.storm2 = this.storm.getChild("storm2");
        this.storm3 = this.storm2.getChild("storm3");
        this.storm4 = this.storm3.getChild("storm4");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        // 枢轴与 texOffs 逐个照抄灾变 Sandstorm_Projectile_Model
        PartDefinition storm = root.addOrReplaceChild("storm",
                CubeListBuilder.create().texOffs(65, 72).addBox(-4.0F, -4.0F, -4.0F, 8.0F, 8.0F, 8.0F),
                PartPose.offset(0.0F, 20.0F, 0.0F));

        PartDefinition storm2 = storm.addOrReplaceChild("storm2",
                CubeListBuilder.create().texOffs(0, 72).addBox(-8.0F, -4.0F, -8.0F, 16.0F, 8.0F, 16.0F),
                PartPose.offset(0.0F, -9.0F, 0.0F));

        PartDefinition storm3 = storm2.addOrReplaceChild("storm3",
                CubeListBuilder.create().texOffs(0, 39).addBox(-12.0F, -4.0F, -12.0F, 24.0F, 8.0F, 24.0F),
                PartPose.offset(0.0F, -9.0F, 0.0F));

        storm3.addOrReplaceChild("storm4",
                CubeListBuilder.create().texOffs(0, 0).addBox(-15.0F, -4.0F, -15.0F, 30.0F, 8.0F, 30.0F),
                PartPose.offset(0.0F, -9.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    /**
     * 更新各层旋转。
     * <p>
     * vanilla {@code ModelPart.yRot} 会被父级累加，所以子层要写<b>差值</b>才能得到
     * 灾变的绝对转速 1.0 / 0.5 / 0.3 / 0.6 rad·tick⁻¹。
     *
     * @param ageInTicks 已对 3600 取模的动画时间，避免长时间累加导致浮点精度下降
     */
    public void setupAnim(float ageInTicks) {
        this.storm.yRot = SPIN_STORM * ageInTicks;
        this.storm2.yRot = (SPIN_STORM2 - SPIN_STORM) * ageInTicks;
        this.storm3.yRot = (SPIN_STORM3 - SPIN_STORM2) * ageInTicks;
        this.storm4.yRot = (SPIN_STORM4 - SPIN_STORM3) * ageInTicks;

        // 链式摆动近似：每层给一点相位偏移的俯仰抖动，避免看起来像四个死板的方盒
        this.storm.xRot = Mth.sin(ageInTicks * SWAY_SPEED) * SWAY_AMOUNT;
        this.storm2.xRot = Mth.sin(ageInTicks * SWAY_SPEED - 0.6F) * SWAY_AMOUNT;
        this.storm3.xRot = Mth.sin(ageInTicks * SWAY_SPEED - 1.2F) * SWAY_AMOUNT;
        this.storm4.xRot = Mth.sin(ageInTicks * SWAY_SPEED - 1.8F) * SWAY_AMOUNT;
    }

    public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int packedLight, int packedOverlay) {
        this.root.render(pose, buffer, packedLight, packedOverlay);
    }
}
