package com.zonlong.beloong.route;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * 一条 NPC 路线（**数据驱动**，从 {@code data/beloong/beloong/npc_route/<名>.json} 加载）。
 * <p>
 * 设计：{@code docs/plans/2026-09-30-npc-route-system-design.md}。
 * 「维度 + 一串路点」就是全部内容 —— 路线**不关心** NPC 是走还是飞
 * （那由 {@code NpcState} 决定，见该枚举的类注释），也**不含有**任何行为（路点上不做动作）。
 *
 * @param dimension     该路线所在的维度；不在这个维度的 NPC 会被**挂起**（设计 D4），不寻路但保留路线
 * @param waypoints     路点序列（至少一个）；**相邻两点的距离必须落在寻路探索半径内**
 *                      —— 半径就是 {@code Attributes.FOLLOW_RANGE}（默认 16），
 *                      原因是 {@code PathNavigation.createPath(...)} 用它当搜索半径
 *                      （{@code PathNavigation.java:151}），而项目**刻意没有调大**它
 *                      （它还决定节点预算 {@code floor(FOLLOW_RANGE * 16)}，构造时只算一次）。
 *                      超了不会报错，只会在复杂地形上反复寻路失败 ⇒ 表现为"卡在半路一直重试"。
 * @param arrivalRadius 抵达判定半径（格，缺省 {@value #DEFAULT_ARRIVAL_RADIUS}）；判定是
 *                      **水平距离** + 一个 Y 容差（见 {@code NpcRouteGoal}），
 *                      因为路点写的是方块坐标，而实体的 y 是脚底高度
 * @param endEmote      <b>可选</b>：走到**终点之后**播放的表情名（如 {@code "sit"}）。
 *                      省略即"什么都不播"（老数据行为一字不变）。播放时机刻意推迟到
 *                      **移动层真的停下**之后 —— 见 {@code NpcRouteGoal.tick()} 的说明。
 *                      名字不做校验：表情是**资产**（哪条动画存在由实体模型决定），
 *                      与 {@code /beloong npc … play} 同口径；名字对不上时由
 *                      {@code EmoteAnimationLookup} 在客户端打一条英文 WARN，不会崩。
 */
public record NpcRoute(ResourceLocation dimension, List<Vec3> waypoints, double arrivalRadius,
                       Optional<String> endEmote) {

    /** 抵达判定半径的缺省值（格）。 */
    public static final double DEFAULT_ARRIVAL_RADIUS = 2.0D;

    /**
     * 单个路点：{@code [x, y, z]}。
     * <p>
     * 用原版惯用的"三元数组"而不是 {@code {"x":…,"y":…,"z":…}} —— 与 {@code /setblock}、
     * {@code /summon} 的坐标写法一致，一行一个路点、肉眼可核对。
     * <p>
     * 刻意**不**用 {@code Vec3.CODEC}：那个 codec 的解码形态是 {@code {"x":…}} 之类的对象，
     * 与本文件想要的数组位形不符。
     */
    private static final Codec<Vec3> WAYPOINT_CODEC = Codec.DOUBLE.listOf().comapFlatMap(
            numbers -> numbers.size() == 3
                    ? DataResult.success(new Vec3(numbers.get(0), numbers.get(1), numbers.get(2)))
                    : DataResult.error(() -> "a waypoint must be [x, y, z], got "
                            + numbers.size() + " number(s)"),
            vec -> List.of(vec.x, vec.y, vec.z));

    public static final Codec<NpcRoute> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("dimension").forGetter(NpcRoute::dimension),
            WAYPOINT_CODEC.listOf().fieldOf("waypoints").forGetter(NpcRoute::waypoints),
            Codec.DOUBLE.optionalFieldOf("arrival_radius", DEFAULT_ARRIVAL_RADIUS)
                    .forGetter(NpcRoute::arrivalRadius),
            Codec.STRING.optionalFieldOf("end_emote").forGetter(NpcRoute::endEmote)
    ).apply(instance, NpcRoute::new));

    /** 路点总数。 */
    public int size() {
        return this.waypoints.size();
    }

    /** 第 {@code index} 个路点；越界返回 {@code null}（调用方据此判定"已抵达终点"）。 */
    public Vec3 waypoint(int index) {
        return index >= 0 && index < this.waypoints.size() ? this.waypoints.get(index) : null;
    }
}
