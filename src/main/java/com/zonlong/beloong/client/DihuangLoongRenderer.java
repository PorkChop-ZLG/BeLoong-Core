package com.zonlong.beloong.client;

import com.zonlong.beloong.client.model.DihuangLoongModel;
import com.zonlong.beloong.entity.DihuangLoongEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 地黄龙渲染器。
 * <p>
 * 刻意保持最小：**不缩放**（用户裁定：按模型默认尺寸渲染），也不加渲染层。
 * <p>
 * 将来若实机发现模型"陷地"或"悬空"，在此覆写 {@code preRender} 加一次平移即可 ——
 * 那是纯渲染层修正，**不动模型文件、也不动碰撞箱**（设计文档风险 R1）。
 */
@OnlyIn(Dist.CLIENT)
public class DihuangLoongRenderer extends GeoEntityRenderer<DihuangLoongEntity> {

    public DihuangLoongRenderer(EntityRendererProvider.Context context) {
        super(context, new DihuangLoongModel());
    }
}
