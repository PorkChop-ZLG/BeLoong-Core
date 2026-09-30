package com.zonlong.beloong.cg;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.cg.instances.MoEntrance;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CG 名 → {@link CgAnimation} 的**唯一映射**，也是本系统"有哪些 CG"的唯一事实来源。
 *
 * <h2>为什么是一张静态表而不是注册表（Registry）</h2>
 * 用户 2026-09-30 要求：**每条 CG 一个 Java 类、写死、不数据驱动**。于是"注册"这件事退化成
 * 静态块里的一行 —— 不需要 NeoForge 的 {@code DeferredRegister}（那会引入注册时机与序列化问题），
 * 也不需要数据包。加一条新 CG = 写一个类 + 在这里加一行。
 *
 * <h2>为什么用 {@link LinkedHashMap}</h2>
 * 保序 ⇒ {@link #names()} 的顺序与源码里 {@code register(...)} 的书写顺序一致
 * ⇒ 指令补全列表可预期、可 diff。用 {@code HashMap} 的话补全顺序每次运行都可能不同。
 *
 * <h2>两条失败路径都 fail-closed，且都留英文 WARN</h2>
 * <ul>
 *   <li><b>名字不存在</b> ⇒ {@link #byName} 返回空。命令层据此报错、**不播放任何东西**
 *       —— 与 {@code NpcDialogueStage} 对未知进度 id 的口径一致：宁可什么都不做，也不要
 *       静默退化成"播了别的什么"。日志**每个名字只报一次**（避免连点刷屏）。</li>
 *   <li><b>名字撞车</b>（两个 CG 类取了同一个 {@link CgAnimation#name()}）⇒ {@link #register}
 *       **保留先注册的那个**并报一次 WARN。静默覆盖会让"我改了 A 类却表现成 B"这种问题
 *       极难定位。</li>
 * </ul>
 *
 * <h2>为什么用普通 {@code HashSet} 而不是并发集合</h2>
 * 本类只在**服务端主线程**被调用（命令执行、静态初始化）。同一取舍的先例是
 * {@code NpcDialogueStage}；{@code EmoteAnimationLookup} 用 {@code ConcurrentHashMap}
 * 是因为它在**客户端每帧**的动画谓词里跑，两者场景不同。
 *
 * <p>设计文档：{@code docs/plans/2026-09-30-cg-system-design.md}（§2 组件）。
 */
public final class CgRegistry {

    /** 全部 CG，按注册顺序。 */
    private static final Map<String, CgAnimation> BY_NAME = new LinkedHashMap<>();

    /** 已经报过"这个 CG 名不存在"的名字 —— 每个名字只留一条 WARN。 */
    private static final Set<String> WARNED_UNKNOWN = new HashSet<>();

    static {
        // ⚠️ 新增一条 CG 就在这里加一行。顺序 = 指令补全顺序。
        register(new MoEntrance());
    }

    private CgRegistry() {
    }

    /**
     * 登记一条 CG。**名字撞车时保留先注册的**并报一次英文 WARN（见类注释）。
     */
    private static void register(CgAnimation cg) {
        CgAnimation previous = BY_NAME.putIfAbsent(cg.name(), cg);
        if (previous != null) {
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] cg registry: name '{}' is already taken by {}, so {} was ignored",
                    cg.name(), previous.getClass().getSimpleName(), cg.getClass().getSimpleName());
        }
    }

    /**
     * 按名字查一条 CG。
     *
     * @return 该 CG；**名字不存在时返回空**（调用方必须 fail-closed），并留一条**每名一次**的英文 WARN
     */
    public static Optional<CgAnimation> byName(String name) {
        CgAnimation cg = BY_NAME.get(name);
        if (cg == null && WARNED_UNKNOWN.add(name)) {
            BeLoongCore.LOGGER.warn("[BeLoong] cg registry: unknown cg '{}' (available: {})", name, names());
        }
        return Optional.ofNullable(cg);
    }

    /** 全部 CG 名（按注册顺序）。供 Brigadier 补全与错误提示使用。 */
    public static List<String> names() {
        return List.copyOf(BY_NAME.keySet());
    }
}
