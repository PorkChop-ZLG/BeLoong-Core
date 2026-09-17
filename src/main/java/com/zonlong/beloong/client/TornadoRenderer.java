package com.zonlong.beloong.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.client.model.TornadoModel;
import com.zonlong.beloong.entity.TornadoEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 龙卷风渲染器。
 * <p>
 * <b>缩放与锚点</b>：灾变原本是 {@code scale(-0.5,-0.5,0.5)} + {@code translate(0,-1.5,0)}。
 * 模型空间是「16 单位 = 1 格」——{@code ModelPart} 对枢轴（{@code translateAndRotate}）
 * 与立方体顶点（{@code Cube.compile}）都做 {@code /16}，而 PoseStack 上后调用的 translate
 * 与它们同处这个空间。模型底面在模型空间 y=24，落到 PoseStack 单位即 24/16 = 1.5，
 * 所以平移量必须是 {@code -MODEL_ROOT_Y / 16}：它把底面精确对齐到实体位置，且与缩放倍数
 * 无关（底面在缩放前的坐标 1.5 不随缩放变化——灾变那个 {@code -1.5} 正是此值）。
 * 若照字面写成 {@code -24}，平移量会大 16 倍，整根风柱会被顶到实体上方约 34 格。
 * <p>
 * <b>消散</b>：寿命只剩 {@link #DESPAWN_TICKS} 刻时开始线性缩小，缩到 0 时服务端同时销毁，
 * 避免「寿命一到整根凭空消失」。剩余寿命来自 {@link TornadoEntity#getRemainingLife()}——
 * 那是本实体<b>唯一</b>同步到客户端的自定义数据（生成时的初始寿命，一生一次），
 * 其余玩法字段（伤害/半径/引力）仍然不同步，渲染器不得读取。
 * <p>
 * <b>RenderType</b>：{@code entityCutoutNoCull}。贴图 alpha 是二值的（实测 3030 个不透明、
 * 13354 个全透明、无半透明），cutout 既保住镂空又不会进半透明排序队列；{@code NoCull}
 * 必须有——风柱是空心的，需要看到内壁。
 */
public class TornadoRenderer extends EntityRenderer<TornadoEntity> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "textures/entity/tornado.png");

    /** 在灾变原尺寸基础上再放大的倍数 */
    private static final float MODEL_SCALE = 3.0F;
    /** 模型根枢轴 y，即模型空间的地面平面（模型单位：16 单位 = 1 格） */
    private static final float MODEL_ROOT_Y = 24.0F;
    /** 动画时间取模上限，避免长时间累加导致浮点精度下降 */
    private static final float ANIM_MODULO = 3600.0F;
    /**
     * 消散阶段时长（刻）：寿命只剩这么多时开始线性缩小，缩到 0 时服务端也正好销毁。
     * 想消散得更慢就调大这个值。
     * <p>
     * 之所以不做透明度渐隐：贴图 alpha 是二值的（只有 0 / 255），渐隐必须换成半透明
     * RenderType，那会重新引入半透明排序问题，也会破坏当前「硬边镂空 + 不进排序队列」的取舍。
     */
    private static final int DESPAWN_TICKS = 20;

    private final TornadoModel model;

    public TornadoRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.model = new TornadoModel(context.bakeLayer(TornadoModel.LAYER));
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(TornadoEntity entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int packedLight) {
        // 消散缩小。底面锚点不受影响：v_world = S * (v_pose + t)，底面 v_pose 恒为 1.5、
        // t 恒为 -1.5，故任意 S 都贴地，视觉上是「原地往下塌缩」。
        float shrink = 1.0F;
        int remainingLife = entity.getRemainingLife();
        if (remainingLife < DESPAWN_TICKS) {
            shrink = Mth.clamp(remainingLife / (float) DESPAWN_TICKS, 0.0F, 1.0F);
        }

        pose.pushPose();
        pose.scale(-0.5F * MODEL_SCALE * shrink, -0.5F * MODEL_SCALE * shrink, 0.5F * MODEL_SCALE * shrink);
        // MODEL_ROOT_Y 是模型单位（16 单位 = 1 格；见 ModelPart.translateAndRotate 与
        // Cube.compile 里的 /16），必须除以 16 换算到 PoseStack 单位。顺序不能反：
        // v_world = S * (v_pose + t)，底面的 v_pose 恒为 24/16 = 1.5，故 t = -1.5 对任意 S 都贴地。
        pose.translate(0.0F, -MODEL_ROOT_Y / 16.0F, 0.0F);

        float ageInTicks = (entity.tickCount + partialTick) % ANIM_MODULO;
        this.model.setupAnim(ageInTicks);

        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        this.model.renderToBuffer(pose, vertexConsumer, packedLight, OverlayTexture.NO_OVERLAY);
        pose.popPose();

        super.render(entity, entityYaw, partialTick, pose, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(TornadoEntity entity) {
        return TEXTURE;
    }
}
