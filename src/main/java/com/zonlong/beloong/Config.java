package com.zonlong.beloong;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * BeLoong-Core 模组的配置文件定义。
 *
 * <h2>这个类是如何工作的？</h2>
 *
 * <h3>什么是 ModConfigSpec？</h3>
 * <p>{@link ModConfigSpec} 是 NeoForge 提供的配置系统。它的工作方式是：</p>
 * <ol>
 *   <li>我们使用 {@link ModConfigSpec.Builder} 声明所有配置项（名称、类型、默认值、注释）</li>
 *   <li>调用 {@code BUILDER.build()} 生成一个不可变的 {@code ModConfigSpec} 对象</li>
 *   <li>将这个 {@code SPEC} 注册到模组容器（在 {@link BeLoongCore} 的构造函数中完成）</li>
 *   <li>NeoForge 自动处理以下工作：
 *     <ul>
 *       <li>生成 TOML 配置文件（存放在 {@code config/beloong-common.toml}）</li>
 *       <li>启动时读取配置文件内容</li>
 *       <li>在模组配置界面显示可交互的选项</li>
 *       <li>保存玩家对配置的修改</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h3>BooleanValue 是什么？</h3>
 * <p>{@link ModConfigSpec.BooleanValue} 是一个"配置值的引用"。
 * 它不是布尔值本身，而是一个指向配置文件中某个布尔条目的引用。
 * 调用 {@code .get()} 方法可以读取当前实际值。
 * 这种设计的好处是：无需重启游戏，修改配置文件后值会自动更新。</p>
 *
 * <h3>使用示例</h3>
 * <p>在其他类中读取配置：</p>
 * <pre>{@code
 * if (Config.FIX_STABLE_HOVER.get()) {
 *     // 修复已启用，执行相关逻辑
 * }
 * }</pre>
 *
 * <h3>为什么用 static final 初始化？</h3>
 * <p>配置项必须在类加载时（static 初始化阶段）向 Builder 注册，
 * 然后调用 {@code build()} 锁定所有配置项。这是 NeoForge 配置 API 的要求。
 * 如果先 {@code build()} 再添加配置项，会抛出异常。</p>
 *
 * @see ModConfigSpec 配置规范的 JavaDoc
 * @see ModConfigSpec.Builder 配置构建器的 JavaDoc
 */
public class Config {

    /**
     * 配置构建器。
     *
     * <p>所有配置项都通过这个 Builder 声明。
     * 构建器收集所有声明后，通过 {@code build()} 生成最终配置规范。
     * 构建器本身是私有的——只有本类能添加配置项。
     * 外部类只能通过下面定义的公开静态字段来读取配置值。</p>
     */
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /**
     * 是否修复"发光效果导致龙身体隐形"的 bug。
     *
     * <p>当玩家手持带有 Glowing 效果（发光/高亮）的物品时，
     * 龙的身体模型可能会完全透明化，只留下物品可见。
     * 启用此选项后，物品的发光轮廓将在独立缓冲区中渲染，
     * 不影响龙身体的主渲染。</p>
     *
     * <p>默认值：{@code true}（启用修复）<br>
     * 配置文件中的键名：{@code fixGlowingOutline}</p>
     *
     * @see com.zonlong.beloong.mixin.DragonItemRenderLayerMixin 实现此修复的 Mixin
     */
    public static final ModConfigSpec.BooleanValue FIX_GLOWING_OUTLINE = BUILDER
            .comment("修复手持发光物品时龙身体变隐形的问题")
            .define("fixGlowingOutline", true); // define(TOML键名, 默认值)

    /**
     * 是否修复"稳定悬停时漂移"的 bug。
     *
     * <p>在龙之生存模组中，当龙处于"稳定悬停"状态（展开翅膀悬停在空中，
     * 且没有按任何方向键）时，龙应该在原地保持静止。但实际上：
     * <ul>
     *   <li>生存模式下，龙会缓慢水平漂移</li>
     *   <li>创造模式下，龙会缓慢向上飘升</li>
     * </ul>
     * 启用此选项后，悬停时龙的加速度被强制归零，确保完全静止。</p>
     *
     * <p>默认值：{@code true}（启用修复）<br>
     * 配置文件中的键名：{@code fixStableHover}</p>
     *
     * @see com.zonlong.beloong.mixin.ClientFlightHandlerMixin 实现此修复的 Mixin
     */
    public static final ModConfigSpec.BooleanValue FIX_STABLE_HOVER = BUILDER
            .comment("修复稳定悬停时的漂移问题（生存模式水平漂移，创造模式向上漂移）")
            .define("fixStableHover", true); // define(TOML键名, 默认值)

    /**
     * 最终的配置规范。
     *
     * <p>由 {@code BUILDER.build()} 生成，包含上面声明的所有配置项。
     * 此对象被 {@link BeLoongCore} 的构造函数注册到 NeoForge 的配置系统中。
     * 注册后，NeoForge 自动管理配置文件的读写和界面显示。</p>
     *
     * <p>注意：{@code build()} 调用后不能再向 BUILDER 添加新的配置项。
     * 如果需要添加新配置，必须在 {@code build()} 之前声明。</p>
     */
    public static final ModConfigSpec SPEC = BUILDER.build();
}
