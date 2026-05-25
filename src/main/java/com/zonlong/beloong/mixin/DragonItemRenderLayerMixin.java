package com.zonlong.beloong.mixin;

import com.mojang.blaze3d.vertex.*;
import com.zonlong.beloong.Config;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.Optional;

/**
 * 修复龙手持发光物品时，龙身体变隐形的 bug。
 *
 * <h2>问题背景</h2>
 * <p>当玩家手持带有"发光（Glowing）"效果的物品时（如附魔的物品、光灵箭等），
 * 这些物品会以特定的渲染方式显示一圈发光轮廓。在龙之生存模组中，
 * 龙手上持有的物品也是用同样的方式渲染的。</p>
 *
 * <h2>Bug 的根源</h2>
 * <p>Minecraft 使用 {@link OutlineBufferSource} 来渲染发光效果。
 * 它是一个特殊的渲染缓冲区，会将所有发光物体的轮廓数据收集起来，
 * 然后在一个单独的渲染通道中绘制发光轮廓。</p>
 *
 * <p>问题出在：当龙渲染手持物品时，物品的发光数据被写入了<b>与龙身体相同</b>的
 * OutlineBufferSource 中。这导致整个 OutlineBufferSource 的状态被破坏，
 * 最终龙身体的网格在发光通道中无法正确渲染——表现为龙的身体完全透明/隐形。</p>
 *
 * <p>简单来说：物品的发光污染了龙的发光缓冲区，导致龙消失。</p>
 *
 * <h2>修复原理</h2>
 * <p>我们创建一个<b>临时的、隔离的</b> Outline 缓冲区专门给物品使用。
 * 物品的发光数据写入这个独立缓冲区，不影响龙的发光缓冲区。
 * 物品渲染完成后，立即将这个独立缓冲区的数据刷到屏幕上。</p>
 *
 * <p>这就像给物品准备一个单独的"画板"——物品在它自己的画板上画画，
 * 画完后立即展示，而龙身体的主画板丝毫不受影响。</p>
 *
 * <h2>技术细节</h2>
 *
 * <h3>@Mixin 注解</h3>
 * <p>{@code targets = "by.dragonsurvival...DragonItemRenderLayer"} —
 * 目标是 Dragon Survival 的内部类，所以用完整类名字符串指定。
 * 这个类负责在龙模型上渲染手持物品。</p>
 *
 * <h3>@ModifyArgs vs @Inject</h3>
 * <ul>
 *   <li><b>@ModifyArgs：</b>在目标方法被调用<b>之前</b>运行，
 *       可以读取和修改传递给目标方法的参数。
 *       这里我们在调用 GeckoLib 的 {@code renderStackForBone()} 之前，
 *       替换掉第5个参数（MultiBufferSource），注入我们隔离后的缓冲区。</li>
 *   <li><b>@Inject(TAIL)：</b>在目标方法执行<b>之后</b>运行，
 *       用于清理工作。这里我们等待物品渲染完成，然后把独立缓冲区的数据刷出。</li>
 * </ul>
 *
 * <h3>@Unique 注解</h3>
 * <p>表示这个字段/方法是我们自己添加到目标类的，
 * 不是目标类原本就有的。Mixin 用这个注解来区分"新增"和"覆写"。</p>
 *
 * <h3>remap = false</h3>
 * <p>在所有注解中都设为 false，因为目标类（Dragon Survival 和 GeckoLib）
 * 不是 Mojang 混淆过的 Minecraft 原版类，不需要映射转换。</p>
 *
 * @see OutlineBufferSourceAccessor 用于读取 OutlineBufferSource 私有字段的访问器
 * @see Config#FIX_GLOWING_OUTLINE 此修复的开关配置
 */
@Mixin(targets = "by.dragonsurvivalteam.dragonsurvival.client.render.entity.dragon.DragonItemRenderLayer",
       remap = false)
public abstract class DragonItemRenderLayerMixin {

    /**
     * 临时隔离的 Outline 渲染缓冲区。
     *
     * <p>在本帧渲染龙手持物品之前创建，用于收集物品的发光轮廓数据。
     * 物品渲染完成后立即调用 {@code endBatch()} 将数据刷到屏幕，
     * 然后置为 {@code null}。</p>
     *
     * <p>每帧都会被重新创建，所以前一帧的数据不会残留。</p>
     *
     * <p>{@code @Unique} 因为这个字段是我们新加的，不是目标类原有的。</p>
     */
    @Unique
    private MultiBufferSource.BufferSource ds_bug_fix$itemOutlineBuf;

    /**
     * 在物品渲染之前拦截调用，将渲染缓冲区替换为隔离版本。
     *
     * <h3>这个方法在什么时候运行？</h3>
     * <p>在 {@code renderStackForBone()} 方法即将调用
     * {@code BlockAndItemGeoLayer.renderStackForBone()} 之前。
     * {@code @ModifyArgs} 允许我们在方法调用前拦截并修改参数。</p>
     *
     * <h3>参数 args 的含义</h3>
     * <p>{@code args} 是一个参数列表，对应 GeckoLib 方法的 8 个参数。
     * 索引从 0 开始：
     * <ul>
     *   <li>args(0) — PoseStack（姿态矩阵栈）</li>
     *   <li>args(1) — GeoBone（骨骼对象）</li>
     *   <li>args(2) — ItemStack（物品堆）</li>
     *   <li>args(3) — GeoAnimatable（动画对象）</li>
     *   <li><b>args(4) — MultiBufferSource（渲染缓冲区，我们要替换的就是它！）</b></li>
     *   <li>args(5) — float（光照值）</li>
     *   <li>args(6) — int</li>
     *   <li>args(7) — int</li>
     * </ul></p>
     *
     * <h3>核心流程</h3>
     * <ol>
     *   <li>检查配置开关是否开启</li>
     *   <li>检查当前缓冲区是否为 OutlineBufferSource（如果不是说明没有发光效果，无需处理）</li>
     *   <li>从原始 OutlineBufferSource 中提取正常缓冲区和队伍颜色</li>
     *   <li>创建一个新的独立 Outline 缓冲区</li>
     *   <li>构造一个包装的 MultiBufferSource：发光内容 → 独立缓冲区，普通内容 → 正常缓冲区</li>
     *   <li>用包装后的缓冲区替换 args(4)</li>
     * </ol>
     *
     * @param args 即将传给 GeckoLib 渲染方法的 8 个参数的列表
     */
    @ModifyArgs(method = "renderStackForBone",
                at = @At(value = "INVOKE",
                         target = "Lsoftware/bernie/geckolib/renderer/layer/BlockAndItemGeoLayer;"
                                + "renderStackForBone("
                                + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                                + "Lsoftware/bernie/geckolib/cache/object/GeoBone;"
                                + "Lnet/minecraft/world/item/ItemStack;"
                                + "Lsoftware/bernie/geckolib/animatable/GeoAnimatable;"
                                + "Lnet/minecraft/client/renderer/MultiBufferSource;"
                                + "FII)V"),
                remap = false)
    private void ds_bug_fix$isolateOutlineBuffer(Args args) {
        // ========== 第1步：检查配置开关 ==========
        // 如果玩家在配置文件中关闭了此修复，直接返回，什么都不做
        if (!Config.FIX_GLOWING_OUTLINE.get()) {
            return;
        }

        // ========== 第2步：检查是否有发光效果 ==========
        // args.get(4) 获取第5个参数——MultiBufferSource（渲染缓冲区）
        MultiBufferSource bufferSource = args.get(4);
        // instanceof 检查：这个缓冲区是不是 OutlineBufferSource？
        // 如果不是（比如是普通的 BufferSource），说明当前没有发光效果，无需修复
        // 如果是，用 Java 16+ 的"模式匹配 instanceof"语法同时转型赋值给 outline 变量
        if (!(bufferSource instanceof OutlineBufferSource outline)) {
            return;
        }

        // ========== 第3步：提取原始缓冲区的信息 ==========
        // 把 outline 对象强转为我们的访问器接口，读取私有字段
        // normalBuf → 正常的（非发光）渲染缓冲区，普通外观数据存在这里
        MultiBufferSource.BufferSource normalBuf =
                ((OutlineBufferSourceAccessor) outline).ds_bug_fix$getNormalBufferSource();

        // 提取发光的队伍颜色（RGBA 四个分量）
        // FastColor.ARGB32.color(a, r, g, b) 将它们打包成一个 32 位整数
        // ARGB 顺序：Alpha（透明度）在高位，然后 Red, Green, Blue
        int color = net.minecraft.util.FastColor.ARGB32.color(
                ((OutlineBufferSourceAccessor) outline).ds_bug_fix$getTeamA(), // Alpha（透明度）
                ((OutlineBufferSourceAccessor) outline).ds_bug_fix$getTeamR(), // Red（红色）
                ((OutlineBufferSourceAccessor) outline).ds_bug_fix$getTeamG(), // Green（绿色）
                ((OutlineBufferSourceAccessor) outline).ds_bug_fix$getTeamB()  // Blue（蓝色）
        );

        // ========== 第4步：创建独立的 Outline 缓冲区 ==========
        // ByteBufferBuilder(1536)：分配 1536 字节的初始内存
        // MultiBufferSource.immediate()：创建一个"立即模式"的缓冲区
        //   这种缓冲区不会缓存数据，每次写入后可以立即通过 endBatch() 刷出
        //
        // 存入实例字段 ds_bug_fix$itemOutlineBuf 中，
        // 供下面的匿名 MultiBufferSource 和后面的 flushIsolatedOutline 使用
        ds_bug_fix$itemOutlineBuf =
                MultiBufferSource.immediate(new ByteBufferBuilder(1536));

        // ========== 第5步：构造包装后的 MultiBufferSource ==========
        // 用 args.set(4, ...) 替换第5个参数
        // 这里创建一个匿名类（没有名字的类，在代码里直接 new 出来）
        // 实现了 MultiBufferSource 接口，重写了 getBuffer() 方法
        args.set(4, new MultiBufferSource() {

            /**
             * 当渲染系统需要一个新的 VertexConsumer（顶点消费者）时调用。
             *
             * <p>根据渲染类型（RenderType）决定数据写入哪个缓冲区：</p>
             * <ul>
             *   <li><b>rt.isOutline() == true：</b>这是发光轮廓的渲染，
             *       数据写入我们独立的 itemOutlineBuf，不影响龙的缓冲区</li>
             *   <li><b>rt.isOutline() == false + rt.outline() 有值：</b>这是普通渲染，
             *       但有一个对应的发光变体。数据需要同时写入两个缓冲区：
             *       <ul>
             *         <li>正常数据 → normalBuf（龙的正常渲染缓冲区）</li>
             *         <li>发光相关数据 → itemOutlineBuf（我们的独立发光缓冲区），
             *             用 VertexMultiConsumer.create() 合并两个消费者</li>
             *       </ul>
             *   </li>
             *   <li><b>rt.isOutline() == false + rt.outline() 为空：</b>纯普通渲染，
             *       无发光变体。直接写入 normalBuf</li>
             * </ul>
             *
             * @param rt 渲染类型，描述了将要用什么方式绘制
             * @return VertexConsumer 顶点消费者，调用者向它写入顶点数据
             */
            @Override
            public VertexConsumer getBuffer(RenderType rt) {
                // 情况A：这是发光轮廓的渲染类型 → 写入独立缓冲区
                if (rt.isOutline()) {
                    return ds_bug_fix$itemOutlineBuf.getBuffer(rt);
                }

                // 先获取正常缓冲区的消费者（普通外观数据总是写入正常缓冲区）
                VertexConsumer normal = normalBuf.getBuffer(rt);

                // rt.outline() 返回 Optional<RenderType>：
                //   - 如果此渲染类型有对应的发光变体 → 返回包含该变体的 Optional
                //   - 如果没有 → 返回空的 Optional
                Optional<RenderType> outlineVariant = rt.outline();

                // 情况B：存在发光变体 → 使用 VertexMultiConsumer 同时写入两个缓冲区
                if (outlineVariant.isPresent()) {
                    // 从独立缓冲区获取发光变体的消费者
                    VertexConsumer outConsumer =
                            ds_bug_fix$itemOutlineBuf.getBuffer(outlineVariant.get());

                    // VertexMultiConsumer.create(consumer1, consumer2)：
                    //   创建一个"双重消费者"，对它的所有写入操作会同时转发给两个消费者
                    //
                    // consumer1 = colorReplacing(outConsumer, color)：
                    //   一个包装了 outConsumer 的消费者，强制覆盖顶点颜色为队伍颜色
                    // consumer2 = normal：正常的顶点消费者
                    //
                    // 这样每条顶点数据都会同时进入独立发光缓冲区和正常缓冲区
                    return VertexMultiConsumer.create(
                            colorReplacing(outConsumer, color), // 发光 → 独立缓冲区
                            normal                                // 普通 → 正常缓冲区
                    );
                }

                // 情况C：没有发光变体 → 纯普通渲染，直接用正常缓冲区
                return normal;
            }
        });
    }

    /**
     * 在物品渲染完成后，将独立 Outline 缓冲区的数据刷出到屏幕。
     *
     * <h3>这个方法在什么时候运行？</h3>
     * <p>{@code @Inject(method = "renderStackForBone", at = @At("TAIL"))}
     * 表示在 {@code renderStackForBone()} 方法执行完毕（TAIL = 末尾）时调用。</p>
     *
     * <p>此时物品已经渲染完成，所有的顶点数据已经写入了我们的独立缓冲区。
     * 现在调用 {@code endBatch()} 将缓冲区中的所有发光数据提交到 GPU 渲染。</p>
     *
     * <h3>为什么要设为 null？</h3>
     * <p>防止下一次调用时误用旧缓冲区。下一帧会由 {@code isolateOutlineBuffer}
     * 重新创建新的缓冲区。</p>
     *
     * @param ci Mixin 注入框架传入的回调信息（此方法中未使用）
     */
    @Inject(method = "renderStackForBone", at = @At("TAIL"), remap = false)
    private void ds_bug_fix$flushIsolatedOutline(CallbackInfo ci) {
        // 如果独立缓冲区存在（说明本帧创建了它）
        if (ds_bug_fix$itemOutlineBuf != null) {
            ds_bug_fix$itemOutlineBuf.endBatch(); // 将所有缓存的顶点数据提交渲染
            ds_bug_fix$itemOutlineBuf = null;     // 清空引用，为下一帧做准备
        }
    }

    /**
     * 创建一个强制覆盖顶点颜色的 VertexConsumer 包装器。
     *
     * <h3>为什么要覆盖颜色？</h3>
     * <p>发光轮廓的颜色应该与队伍颜色一致，而不是物品自身的纹理颜色。
     * 这个包装器拦截了 {@code setColor()} 调用（丢弃传入的颜色），
     * 并在每次添加顶点时强制使用我们指定的队伍颜色。</p>
     *
     * <h3>设计和行为说明</h3>
     * <ul>
     *   <li>{@code addVertex(x,y,z)} — 正常添加顶点，但调用 {@code setColor(color)}
     *       强制覆盖为我们指定的队伍颜色</li>
     *   <li>{@code setColor(r,g,b,a)} — 故意忽略（return this），
     *       不接受外部传来的颜色设置</li>
     *   <li>{@code setUv(u,v)} — 正常转发给被包装的消费者</li>
     *   <li>{@code setUv1(u,v)} — 忽略（发光轮廓不需要 UV1 数据）</li>
     *   <li>{@code setUv2(u,v)} — 忽略（发光轮廓不需要 UV2 数据）</li>
     *   <li>{@code setNormal(x,y,z)} — 忽略（发光轮廓不需要法线数据）</li>
     * </ul>
     *
     * <p>注意：setUv1、setUv2、setNormal 返回 this（自己）而不是 delegate，
     * 这不会导致数据丢失——调用者实际是通过 {@link VertexMultiConsumer} 来使用这个包装器，
     * VertexMultiConsumer 会同时向两个消费者转发调用，所以被忽略的数据
     * 会在 normal 消费者那边被正确处理。</p>
     *
     * @param delegate 被包装的真实 VertexConsumer（负责实际写入顶点数据）
     * @param color    要强制使用的 ARGB 颜色值（从队伍颜色提取的发光轮廓颜色）
     * @return 一个新的 VertexConsumer，它会强制所有顶点使用指定颜色
     */
    @Unique
    private static VertexConsumer colorReplacing(VertexConsumer delegate, int color) {
        return new VertexConsumer() {

            /**
             * 添加一个顶点（3D 空间中的一个点）。
             * 先调用 delegate 添加顶点，然后立即设置颜色为我们指定的值。
             *
             * @param x 顶点的 X 坐标
             * @param y 顶点的 Y 坐标
             * @param z 顶点的 Z 坐标
             * @return this（支持链式调用）
             */
            public VertexConsumer addVertex(float x, float y, float z) {
                // delegate.addVertex(x,y,z) 先添加顶点
                // .setColor(color) 立即覆盖颜色为队伍颜色
                delegate.addVertex(x, y, z).setColor(color);
                return this;
            }

            /**
             * 设置顶点颜色——我们故意忽略这个方法。
             * 外部调用者想设置它们的颜色，但我们不允许。
             * 颜色只能由 addVertex() 中预设的队伍颜色决定。
             */
            public VertexConsumer setColor(int r, int g, int b, int a) {
                return this; // 不接受，返回自身
            }

            /**
             * 设置纹理坐标（UV 映射）。
             * 正常转发给 delegate——纹理坐标不需要修改。
             */
            public VertexConsumer setUv(float u, float v) {
                delegate.setUv(u, v);
                return this;
            }

            /**
             * 设置第二层纹理坐标。发光轮廓不使用，直接忽略。
             */
            public VertexConsumer setUv1(int u, int v) {
                return this;
            }

            /**
             * 设置第二层纹理坐标（浮点版本）。发光轮廓不使用，直接忽略。
             */
            public VertexConsumer setUv2(int u, int v) {
                return this;
            }

            /**
             * 设置顶点法线（用于光照计算）。发光轮廓不需要法线，直接忽略。
             */
            public VertexConsumer setNormal(float x, float y, float z) {
                return this;
            }
        };
    }
}
