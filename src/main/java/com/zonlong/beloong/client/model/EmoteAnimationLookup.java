package com.zonlong.beloong.client.model;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.entity.NpcEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「这个 NPC 到底有没有这条动画」的查询口 —— 表情系统的<b>预检</b>。
 *
 * <h2>为什么需要它</h2>
 * 表情是<b>按名字</b>播的（{@code /beloong npc <targets> play <动画名>}），而名字来自玩家。
 * 若把一个<b>不存在</b>的名字丢给 GeckoLib，{@code AnimationProcessor} 会打一条
 * {@code ERROR "Unable to find animation: ..."} + {@code printStackTrace()}，并<b>把该控制器停掉</b>
 * —— 功能上下一帧就会恢复，但**日志会很脏**，而且那不是我们能控制的输出。
 * 所以在设置动画之前先查一次：查不到就什么都不做（设计 D4：静默不播）。
 *
 * <h2>为什么只能放在客户端</h2>
 * 动画名是<b>资产数据</b>，只有客户端烘焙了 {@code BakedAnimations}
 * （{@code GeckoLibCache.java:113-133}）⇒ 服务端<b>不可能</b>校验名字。
 * 因此本类在 {@code client.model} 包里，只应在客户端调用。
 *
 * <h2>⚠️ 两个必须知道的事实</h2>
 * <ol>
 *   <li><b>名字缺失只是返回 {@code null}；文件缺失却会抛异常。</b>
 *       {@code GeoModel.java:160-165}：查到最后一个 fallback 仍没找到<b>文件</b>时抛
 *       {@code "Unable to find animation file."}（或路径不含 {@code animations/} 时抛另一句），
 *       而<b>名字</b>不存在只是让 {@code getAnimation} 返回 {@code null}。
 *       ⇒ 所以本方法<b>必须 try/catch</b>：一个写错的 fallback 路径不该变成每帧异常。</li>
 *   <li><b>失败不做负缓存。</b> 查不到就返回 {@code null}，下一帧还会再查 ——
 *       这样 F3+T 重载资源后能<b>自愈</b>（新增的动画立刻可用）。
 *       代价只是每帧几次哈希查找，可忽略。</li>
 * </ol>
 *
 * <h2>为什么 catch {@code Throwable} 而不是 {@code Exception}</h2>
 * 本类引用的是<b>客户端专属</b>类（{@code Minecraft}/{@code GeoEntityRenderer}）。
 * 虽然 {@code registerControllers} 的谓词在专用服务端不会运行，但万一有别的路径调到，
 * 我们宁可退化成"查不到（于是不播表情）"，也不要让一个 {@code NoClassDefFoundError}
 * 把服务端带崩。⇒ 一次性警告，然后当作不存在。
 */
public final class EmoteAnimationLookup {

    /** 只在第一次失败时警告一次 —— 这条路径若每帧都刷日志就失去了"预检"的意义。 */
    private static boolean warned;

    private EmoteAnimationLookup() {}

    /**
     * 查这条动画是否存在，存在则返回它（顺带提供 {@link Animation#length()} 与
     * {@link Animation#loopType()}，供谓词的两条分支使用）。
     *
     * @param npc  要查询的 NPC（用它去渲染派发器里取模型）
     * @param name 动画名；{@code null} 或空串一律视为"没有"
     * @return 该动画；不存在、或查询过程出任何问题，都返回 {@code null}
     */
    @Nullable
    public static Animation find(NpcEntity npc, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            EntityRenderer<?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(npc);
            if (!(renderer instanceof GeoEntityRenderer<?> geoRenderer)) {
                return null;
            }
            // 未检查的窄化：本模组的 NPC 都由 GeoEntityRenderer<NpcEntity> 的子类渲染
            // （NpcRenderer / MoRenderer / DihuangLoongRenderer），故这里的转换是安全的。
            @SuppressWarnings("unchecked")
            GeoModel<NpcEntity> model = (GeoModel<NpcEntity>) geoRenderer.getGeoModel();
            // getGeoModel() 是 public（GeoEntityRenderer.java:79）。
            // getAnimation 见 GeoModel.java:144：主文件优先，miss 才依次查 fallback。
            return model.getAnimation(npc, name);
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                BeLoongCore.LOGGER.warn("[BeLoong] 表情预检查询失败（只报这一次），将按「该动画不存在」处理：{}",
                        t.toString());
            }
            return null;
        }
    }
}
