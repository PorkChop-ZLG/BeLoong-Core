package com.zonlong.beloong.client.model;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.entity.MoEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 末（Mo）模型：三个资源路径。
 * <p>
 * 资产由**本地模型暂存目录** {@code docs/models/} 迁移而来（该目录专门存放模型源文件、不入库）：
 * <ul>
 *   <li>{@code geo/mo.geo.json} —— 454 KB，<b>271 骨骼 / 858 立方体 / 1 根根骨骼
 *       （{@code Root_Molang}，{@code Root} 是它的子骨）</b>，无重名骨骼、无悬空 parent，
 *       {@code texture_width/height} 声明 512×512（与贴图实测一致）。
 *       来源是 {@code docs/models/末2/}（2026-09-27 换用，逐字节拷贝），
 *       之后用户又**手工删掉 43 根 NPC 用不到的道具骨**（313 → 270）：
 *       {@code Ender_Sword_Root} / {@code Ender_Sword_1..5} / {@code Ender_Sword_fire_1..5}
 *       及其发光件 {@code ysmGlowEnder_Sword_*}（共 38 根）、{@code Chair}（61 个立方体）、
 *       {@code ysmGlow_Ender_Sword_5}。其中 31 根带立方体 ⇒ 那是**可见部件**（模型上不再挂剑与椅子）。
 *       已复核这次删除不影响渲染缩放：{@code Head} 骨自身立方体顶端仍是 {@code 39.8218}、
 *       整体包围盒仍是 53.48 × 85.08 × 126.18 ⇒ {@code MoRenderer.MODEL_SCALE = 0.80} 继续成立。
 *       <p>
 *       📌 <b>2026-09-27 又补上了嘴</b>（270 → 271 骨）：新增一根名为 {@code mouth} 的骨
 *       （{@code parent = Head}）与它的 4 个立方体 —— 几何**照搬旧模型 {@code Head_Mouth_8}
 *       的定义，整条 z + 0.425**（新模型五官几何相对旧模型整体前移 0.425，已用 6 处独立数据验证：
 *       5 根同名五官骨的立方体 origin 差都是 {@code dx=0 dy=0 dz=+0.425}，且新旧 {@code Head}
 *       自身立方体的前面也正好差 0.425）。贴图里只写 **1 个像素** {@code RGBA(239,149,149,255)}
 *       —— 旧模型的嘴本来就只是 {@code north} 面那 4 个粉色像素，其余 20 个面采样的是全透明像素。
 *       <b>骨名刻意取 {@code mouth} 而不是旧名 {@code Head_Mouth_8}</b>：{@code dance}（星辉闪耀）
 *       里有一条 {@code "mouth": {"scale": 0}}，正是靠这个名字生效（跳舞时把嘴缩为 0 = 隐藏）；
 *       若沿用旧名，那条通道会**静默失效**。
 *       <p>
 *       其 {@code identifier} 是 {@code geometry.unknown}，**无需修正**：
 *       GeckoLib 按本类返回的<b>文件路径</b>取模型，{@code identifier} 只是可空元数据。</li>
 *   <li>{@code textures/entity/mo.png} —— 512×512 贴图，来自 {@code 末2/} 并经用户精简；
 *       2026-09-27 补嘴时只改了 1 个像素（{@code (511,511)}：透明 → 粉色），
 *       已逐像素校验"除该像素外与前一版完全相同"，PNG 头属性也未变。</li>
 *   <li>{@code animations/mo.animation.json} —— <b>5.44 MB / 9 个动画</b>，取自 {@code 末/}
 *       并经多轮修整（29 → 11 → 9 条）。代码目前播其中 6 条：{@code idle} / {@code walk} /
 *       {@code run}（主状态机）、{@code fly}（飞行态）、{@code sit}（坐下态）、
 *       {@code dance}（跳舞态，即 20.5 秒循环的星辉闪耀）；
 *       其余 3 条（{@code descend}、{@code attack}、{@code idle_old}）已备好、暂无代码引用。
 *       这些键名都可被 {@code NpcEntity} 的可覆写方法改写。</li>
 * </ul>
 * <p>
 * ⚠️ <b>本模型曾经还有一个独立的"翅膀常驻层"</b>（动画 {@code wings_idle} + {@code MoEntity}
 * 里一个单独的控制器）。换用新几何后已**整层删除**：新几何的翅膀是完整的两条链，
 * 且 {@code idle}/{@code walk}/{@code run}/{@code fly}/{@code sit}/{@code descend}/{@code attack}
 * 每一条都自带 54~55 根翅膀骨的通道 ⇒ 独立层不但多余，还会与主控制器争同一批骨骼。
 * 因此**不要再假设存在 {@code wings_idle} 这条动画**。
 *
 * <h2>⚠️ 2026-09-27 为什么换了模型：旧几何与动画不匹配</h2>
 * 旧几何是一份"精简导出"：只有 211 骨，翅膀只剩一条<b>左右对称压在同一批骨上</b>的链
 * （每根 {@code Right_Wing_*} 的立方体 x 范围是对称的），且没有脸骨与 Molang 代理骨。
 * 而动画是按完整 rig 写的 ⇒ 旧模型下我们自己的动画有 <b>92 个被引用的骨骼名在 geo 里不存在</b>，
 * 那些通道被 GeckoLib 静默跳过（见下），走 / 跑 / 飞都只有约 62% 的骨骼真正生效。
 * <p>
 * 换用与动画匹配的几何后<b>只剩 5 个</b>（分布在 3 条动画上），而
 * {@code idle} / {@code walk} / {@code run} / {@code fly} / {@code descend} / {@code idle_old}
 * 全部 <b>100% 命中</b>。
 * <p>
 * 换模型前已核对过两件最容易出事的事，都通过：
 * <ul>
 *   <li><b>整体几何尺寸没变</b>（旧 53.48 × 85.08 × 126.60 ⇒ 新 53.48 × 85.08 × 126.18）
 *       ⇒ 不存在缩放问题。{@code description} 里的 {@code visible_bounds} 从 16×6 变成 26×11
 *       只是**声明值**，不代表几何变大 —— 别照它去改渲染缩放；</li>
 *   <li><b>关键骨骼枢轴一致</b>，只有 3 根不同：{@code Weapen} 差 3.8（旧 x=−3.8 ⇒ 新 x=0，
 *       新的与 YSM 原版一致）、{@code Right_Wing_Root} 与 {@code Right_Wing_1} 各差 2.0。</li>
 * </ul>
 *
 * <h2>仍然对不上的 5 个骨骼名（分布在 3 条动画上）</h2>
 * 都是**新几何里根本没有这些名字**，改不了名、只能接受对应通道不生效：
 * <ul>
 *   <li>{@code dance}（星辉闪耀）—— 只缺 {@code Skirt}（裙摆，25 关键帧）⇒ 影响只有"裙摆不动"。
 *       （它引用的 {@code mouth} 已于 2026-09-27 补上，见上文"又补上了嘴"。）</li>
 *   <li>{@code attack}（由 {@code hold_sword_attack_1} + {@code _reset} 合并而来）——
 *       缺 {@code Wave_1} 与两个发光波 {@code ysmGlowWave_1_1} / {@code _1_2}
 *       ⇒ 缺的是挥砍特效件，主体动作与武器（{@code Weapen}）都在；</li>
 *   <li>{@code sit} —— 缺 {@code Eyes}（1 根）。新模型的眼睛是一族分开的骨
 *       （{@code EyeBalls} / {@code Eyebrows} / {@code ysmGlowLeftEyeball} …），
 *       没有单独叫 {@code Eyes} 的。</li>
 * </ul>
 *
 * <h2>曾经修过的一类"整条动画被丢弃"（会复发的坑，留档）</h2>
 * GeckoLib 对"动画里写了它认不出的 Molang 表达式"不是跳过该表达式，而是**整条动画丢掉**：
 * <ol>
 *   <li>{@code MathParser.java:46} 的字符白名单 {@code ^[\w\s_+-/*%^&|<>=!?:;.,(){}]+$}
 *       —— Java 的 {@code \w} **只匹配 ASCII 词字符**，白名单里既没有单引号也没有中文；</li>
 *   <li>{@code MathParser.java:187-188} 不匹配即抛 {@code CompoundException}；</li>
 *   <li>{@code BakedAnimationsAdapter.java:39-53} 的 per-animation catch 捕获后**只打一行日志**，
 *       把该动画从烘焙结果里去掉 ⇒ 运行期表现为"这条动画不存在"。</li>
 * </ol>
 * 而且**光删掉引号救不回来**：未注册的函数（如 YSM 的 {@code ysm.second_order}）会在
 * {@code MathParser.java:117} 直接 {@code return null} ⇒ 仍然编译失败。
 * <p>
 * 2026-09-27 踩过两次：一次是旧资产的 {@code fly} / {@code swim} / {@code swim_stand}，
 * 一次是本轮给 {@code fly} 新加的 {@code AllBody_Molang} / {@code Head_Molang} 两个
 * "Molang 代理骨"通道（其 rotation 值是 {@code ysm.second_order('飞行身体前倾', …)} 这类
 * 带中文与单引号的表达式）。后者已**整条通道删除** —— 顺带说明这两个通道在本模组里
 * 本来就是死的：{@code ysm.input_vertical} / {@code ysm.ground_speed2} 是未注册变量（恒为 0），
 * {@code second_order} 也不存在。
 *
 * <h2>为什么没有 {@code applyMolangQueries}</h2>
 * 地黄龙的资产里写着 {@code query.head_yaw} 之类的表达式，所以它必须在这里注册；
 * <b>本模型的资产用的是 YSM 的私有命名空间</b>（{@code ysm.head_yaw}，见分析报告 §9.2），
 * 而用户裁定"先不管 molang"。未注册的变量 GeckoLib 会自动建一个值为 0 的实例
 * （{@code MolangQueries.java:149-150}），所以 {@code walk}/{@code run} 里的
 * {@code ysm.head_yaw/8} **不报错、只是恒为 0** ⇒ 走路时头不随视角转。
 * 将来若要接上，在这里 {@code MathParser.setVariable("ysm.head_yaw", ...)} 即可
 * （注意 {@code ysm.head_yaw} 是**绝对**视角 yaw，与地黄龙那个"头相对身体"的口径不同，
 * 符号要实机标定）。
 *
 * <h2>刻意不覆写 {@code crashIfBoneMissing()}</h2>
 * 本模型仍有 <b>5 个被动画引用、但 geo 里不存在的骨骼</b>（旧模型是 92 个，
 * 具体清单见上一节），分布在 3 条动画上。GeckoLib 默认
 * {@code crashIfBoneMissing() == false}（{@code GeoModel.java:91-93}），
 * 配合 {@code AnimationController.java:529-534} 的
 * {@code if (bone == null) continue;} ⇒ 只是那些骨骼不动、**不崩也不打日志**。
 * <p>
 * ⚠️ <b>不要"为了尽早发现问题"把它覆写成 {@code true}</b>：那会让本实体在**运行期**
 * 第一次播这些动画时直接抛 {@code RuntimeException("Could not find bone: ...")}。
 * 覆写是编译期就能改的，而崩是运行期才发生的 —— 这类"防御性收紧"在这里是纯粹的负收益。
 */
public class MoModel extends GeoModel<MoEntity> {

    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "geo/mo.geo.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "textures/entity/mo.png");
    private static final ResourceLocation ANIMATION =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "animations/mo.animation.json");

    /**
     * <b>扩展动画文件</b> —— 末的表情动画（{@code sit} / {@code dance} / {@code descend}）放在这里，
     * 而不是主文件里。
     * <p>
     * 这是 GeckoLib <b>原生</b>的"一个模型多个动画文件"机制：{@code GeoModel.java:76-84} 的
     * {@code getAnimationResourceFallbacks}，查找顺序见 {@code GeoModel.java:144-158}
     * —— <b>主文件优先，名字 miss 才依次查 fallback</b>。因此不需要自定义加载器、也不需要合并脚本。
     * <p>
     * ⚠️ <b>同名时主文件赢，extra 里那条会静默失效</b>（没有任何日志）。所以两文件的动画名
     * <b>必须互不相交</b> —— 原先 {@code sit} 两边都有，已从主文件删掉。该不变量由探针
     * {@code tools/YSMParser/probe_emote_assets.py} 的 A1 守着。
     * <p>
     * ⚠️ <b>它既不省内存也不省启动</b>：{@code GeckoLibCache.java:113-133} 在资源重载时把
     * {@code assets/<ns>/animations/} 下<b>所有</b> json 全量烘焙。拆文件的收益只是
     * "文件组织 + 主文件体积"，这正是本设计要的（不做按需加载、不做二进制编译缓存）。
     */
    private static final ResourceLocation EXTRA_ANIMATION =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "animations/mo.extra.animation.json");

    // 注：GeckoLib 4.9 里这三个单参版本标了 @Deprecated，但**仍是抽象方法**，必须实现；
    // 双参重载（带 renderer）默认转调它们 —— 与地黄龙模型、BWG 的 PumpkinWardenModel 写法一致。

    @Override
    public ResourceLocation getModelResource(MoEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(MoEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(MoEntity animatable) {
        return ANIMATION;
    }

    /**
     * 把 {@link #EXTRA_ANIMATION} 挂成主动画文件的<b>备用查找位置</b>。
     * <p>
     * 对调用方而言动画名就是"逻辑上合并"的一池：{@code idle}/{@code fly}/{@code walk}/
     * {@code run}/{@code attack} 在主文件命中，{@code sit}/{@code dance}/{@code descend}
     * 落到 extra 命中；代价是每次查找最多多一次哈希查询。
     * <p>
     * 父类默认实现返回<b>空数组</b>，此处刻意覆写。将来若 extra 再拆出第三份文件，
     * <b>数组顺序就是查找优先级</b> —— 把最常用的放前面。
     */
    @Override
    public ResourceLocation[] getAnimationResourceFallbacks(MoEntity animatable) {
        return new ResourceLocation[]{EXTRA_ANIMATION};
    }
}
