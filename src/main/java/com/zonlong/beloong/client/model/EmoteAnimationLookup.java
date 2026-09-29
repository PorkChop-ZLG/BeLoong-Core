package com.zonlong.beloong.client.model;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.entity.NpcEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * <h2>⚠️ 静默是**有代价**的：失败必须留下线索</h2>
 * "查不到就静默不播"意味着<b>玩家看不出是"名字拼错"还是"预检链路本身坏了"</b> ——
 * 2026-09-29 实机就撞上过：末的 {@code attack} 完全不播、日志里却一行都没有，
 * 排查时无法区分这两种情况。故本类对**每一种失败**都打一条<b>英文 WARN</b>：
 * <ul>
 *   <li>渲染器不是 {@link GeoEntityRenderer}（含 {@code null}）；</li>
 *   <li>渲染器没有 {@code GeoModel}；</li>
 *   <li>动画名在主文件与 fallback 里都找不到；</li>
 *   <li>查询过程抛出任何东西（含 {@code Throwable}）。</li>
 * </ul>
 * 前三种按<b>动画名</b>去重（同一个名字只报一次），第四种只报一次 —— 都不会刷屏。
 * <p>
 * 📌 本项目的日志一律<b>纯英文</b>（用户裁定）。
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
 *       代价只是每帧几次哈希查找，可忽略。（{@link #REPORTED_MISSING} 只去重<b>日志</b>，
 *       不影响这个自愈行为。）</li>
 * </ol>
 *
 * <h2>为什么 catch {@code Throwable} 而不是 {@code Exception}</h2>
 * 本类引用的是<b>客户端专属</b>类（{@code Minecraft}/{@code GeoEntityRenderer}）。
 * 虽然 {@code registerControllers} 的谓词在专用服务端不会运行，但万一有别的路径调到，
 * 我们宁可退化成"查不到（于是不播表情）"，也不要让一个 {@code NoClassDefFoundError}
 * 把服务端带崩。
 */
public final class EmoteAnimationLookup {

    /** 已经报过"解析不了"的动画名 —— 同一个坏名字只留一条 WARN，不刷屏。 */
    private static final Set<String> REPORTED_MISSING = ConcurrentHashMap.newKeySet();

    /** 查询过程本身抛过异常没有（只报一次，避免每帧一条）。 */
    private static boolean reportedFailure;

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
                warnMissing(name, npc, "renderer is not a GeoEntityRenderer (got "
                        + (renderer == null ? "null" : renderer.getClass().getName()) + ")");
                return null;
            }
            // 未检查的窄化：本模组的 NPC 都由 GeoEntityRenderer<NpcEntity> 的子类渲染
            // （NpcRenderer / MoRenderer / DihuangLoongRenderer），故这里的转换是安全的。
            @SuppressWarnings("unchecked")
            GeoModel<NpcEntity> model = (GeoModel<NpcEntity>) geoRenderer.getGeoModel();
            if (model == null) {
                warnMissing(name, npc, "renderer " + geoRenderer.getClass().getName() + " has no GeoModel");
                return null;
            }
            // getGeoModel() 是 public（GeoEntityRenderer.java:79）。
            // getAnimation 见 GeoModel.java:144：主文件优先，miss 才依次查 fallback。
            Animation animation = model.getAnimation(npc, name);
            if (animation == null) {
                warnMissing(name, npc, "not present in the primary animation file nor any fallback"
                        + " (model " + model.getClass().getSimpleName() + ")");
            }
            return animation;
        } catch (Throwable t) {
            if (!reportedFailure) {
                reportedFailure = true;
                BeLoongCore.LOGGER.warn("[BeLoong] emote pre-check threw for animation '{}'"
                        + " (reporting this once, treating as missing): {}", name, t.toString());
            }
            return null;
        }
    }

    /** 按动画名去重的英文 WARN —— 让"为什么没播"在 latest.log 里一眼可见。 */
    private static void warnMissing(String name, NpcEntity npc, String reason) {
        if (REPORTED_MISSING.add(name)) {
            BeLoongCore.LOGGER.warn("[BeLoong] emote pre-check: cannot resolve animation '{}' for {} ({}): {}",
                    name, npc.getType(), npc.getUUID(), reason);
        }
    }
}
