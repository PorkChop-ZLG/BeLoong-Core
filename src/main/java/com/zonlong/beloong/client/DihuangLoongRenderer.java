package com.zonlong.beloong.client;

import com.zonlong.beloong.client.model.DihuangLoongModel;
import com.zonlong.beloong.entity.DihuangLoongEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 地黄龙渲染器。
 * <p>
 * 刻意保持最小：**不缩放**（用户裁定：按模型默认尺寸渲染），也不加渲染层。
 * <p>
 * 继承 {@link NpcRenderer} 而非直接继承 {@code GeoEntityRenderer}，只为了拿到
 * "阴影半径由实体侧 {@code shadowRadius()} 决定"这条通用链路 —— 地黄龙把这个值
 * 覆写成了 {@code 0.8F}（体型比通用默认的玩家尺寸大），见
 * {@link DihuangLoongEntity#shadowRadius()}。所以**本类里没有阴影相关代码**。
 * <p>
 * 将来若实机发现模型"陷地"或"悬空"，在此覆写 {@code preRender} 加一次平移即可 ——
 * 那是纯渲染层修正，**不动模型文件、也不动碰撞箱**（设计文档风险 R9）。
 * <p>
 * <b>2026-09-25 实机结论：不需要偏移</b>（模型落地后无陷地/悬空），故此处保持最小实现。
 * 若将来换了模型或碰撞箱，需重新确认这一条。
 */
@OnlyIn(Dist.CLIENT)
public class DihuangLoongRenderer extends NpcRenderer<DihuangLoongEntity> {

    public DihuangLoongRenderer(EntityRendererProvider.Context context) {
        super(context, new DihuangLoongModel());
    }
}
