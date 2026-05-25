package com.zonlong.beloong.mixin;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * OutlineBufferSource 的私有字段访问器。
 *
 * <h2>这个类是做什么的？</h2>
 * Minecraft 有一个名叫 {@link OutlineBufferSource} 的类，负责在实体身上渲染"发光轮廓"效果。
 * 这个类内部有几个 {@code private}（私有）字段，正常情况下外部代码无法读取它们。
 * 但我们需要在 {@link DragonItemRenderLayerMixin} 中读取这些私有字段来做修复。
 *
 * <h2>Mixin 的 @Accessor 是什么？</h2>
 * 在 Java 中，私有字段只能在本类内部访问，外部代码无法直接读取。
 * Mixin（一个允许我们修改游戏代码的框架）提供了 {@code @Accessor} 注解，
 * 它可以"撬开"目标类的私有字段，生成对应的 getter 方法让我们调用。
 * 本质上就是：虽然字段是私有的，但 Mixin 帮我们生成了一个公开的读取方法。
 *
 * <h2>为什么是 interface 而不是 class？</h2>
 * Accessor mixin 必须定义为接口（interface），这是 Mixin 框架的要求。
 * 接口中只需要声明方法签名（返回值类型 + 方法名），Mixin 会在运行时自动生成实现代码。
 *
 * <h2>方法名为什么有 ds_bug_fix$ 前缀？</h2>
 * 在 Minecraft 模组生态中，可能有多个模组同时 mixin 到同一个类。
 * {@code ds_bug_fix$} 是一个命名空间前缀，来源于最初编写这些修复的模组名。
 * 保留此前缀能避免与其他模组的同名访问器方法发生冲突。
 * 例如：如果另一个模组也叫 {@code getTeamR()}，Mixin 会报"方法名重复"错误；
 * 加了前缀后 {@code ds_bug_fix$getTeamR()} 就不会冲突。
 *
 * <h2>每个字段的含义</h2>
 * <ul>
 *   <li><b>bufferSource</b> — 正常的渲染缓冲区，负责存储普通（非发光）的顶点数据</li>
 *   <li><b>outlineBufferSource</b> — 发光轮廓的渲染缓冲区，存储发光效果的顶点数据</li>
 *   <li><b>teamR / teamG / teamB / teamA</b> — 发光颜色的 RGBA 分量（红、绿、蓝、透明度）</li>
 * </ul>
 *
 * @see DragonItemRenderLayerMixin 使用此访问器的修复类
 */
@Mixin(OutlineBufferSource.class) // 告诉 Mixin："我要访问 OutlineBufferSource 这个类"
public interface OutlineBufferSourceAccessor {

    /**
     * 获取正常的（非发光）渲染缓冲区。
     * 这个缓冲区里存储了实体正常外观的所有顶点数据。
     *
     * @param outline 通过 {@code ((OutlineBufferSourceAccessor) outline)} 转换后传入
     * @return BufferSource 正常渲染缓冲区的引用
     */
    @Accessor("bufferSource") // 括号里的字符串必须是目标类中字段的精确名称
    MultiBufferSource.BufferSource ds_bug_fix$getNormalBufferSource();

    /**
     * 获取发光轮廓的渲染缓冲区。
     * 仅用于访问（当前修复中未直接使用，保留以备将来需要）。
     *
     * @return BufferSource 发光轮廓缓冲区的引用
     */
    @Accessor("outlineBufferSource")
    MultiBufferSource.BufferSource ds_bug_fix$getOutlineBufferSource();

    /**
     * 获取发光颜色的红色分量（0-255）。
     * 颜色来自玩家所属的"队伍"设置，在 Minecraft 中队伍颜色会决定发光轮廓的颜色。
     */
    @Accessor("teamR")
    int ds_bug_fix$getTeamR();

    /**
     * 获取发光颜色的绿色分量（0-255）。
     */
    @Accessor("teamG")
    int ds_bug_fix$getTeamG();

    /**
     * 获取发光颜色的蓝色分量（0-255）。
     */
    @Accessor("teamB")
    int ds_bug_fix$getTeamB();

    /**
     * 获取发光颜色的透明度/Alpha 分量（0-255）。
     * 值越高，发光轮廓越不透明。
     */
    @Accessor("teamA")
    int ds_bug_fix$getTeamA();
}
