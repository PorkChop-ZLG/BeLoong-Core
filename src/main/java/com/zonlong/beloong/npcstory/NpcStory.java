package com.zonlong.beloong.npcstory;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * 一条 NPC 剧情的**私人化声明**（**数据驱动**，从 {@code data/beloong/beloong/npc_story/<实体类型路径>.json} 加载）。
 * <p>
 * 设计：{@code docs/plans/2026-10-01-multiplayer-npc-design.md}（D8–D21）。
 * 文件**按实体类型命名**（{@code mo.json} ↔ {@code beloong:mo}）⇒ 与 {@code npc_dialogue} 同构，
 * "哪个 NPC"不必写进文件；给另一个 NPC 接剧情只需再放一个文件。
 * <p>
 * 本类只描述**意图**，不含行为：谁在什么时候应该拥有一个"只属于自己"的私有分身，
 * 它活多久，以及它出现时播什么演出。让世界与这份声明保持一致的，是 {@code NpcStoryHandler} 里的对账器。
 *
 * <h2>为什么把"存在"和"演出"分开表达</h2>
 * "该玩家应当有一个分身"是**状态**（持续为真）⇒ 声明式，对账器可以反复跑、幂等、自愈；
 * "登场 CG 要播一次"是**一次性演出** ⇒ 事件式，由"起点进度**被获得**"这个事件驱动。
 * 混在一起写就必然要自己发明一个"CG 播过没有"的持久状态 —— 而**起点进度本身就是那个标记**。
 *
 * @param startAdvancement    必需的**起点进度**：玩家获得它的那一刻 ⇒ 该玩家应当拥有私有分身（并播 {@link #cg}）
 * @param endAdvancement      必需的**终点进度**：它是"剧情已完成"的判据，**同时**是撤回进度的上溯起点
 *                            （见 {@code NpcStoryHandler#revokeStory}：沿 {@code parent()} 一路走回起点）。
 *                            这两个 id 必须与对话数据里的阶段闸门**逐字一致**（有不变量守着）。
 * @param spawn               分身在**哪里**出现，缺省 {@value #SPAWN_ANCHOR}。目前只接受这个值：
 *                            分身在**公共锚点**的位置与朝向出现 ⇒ 玩家视角"那只 mo 一直在原地"，
 *                            切换无感（这是整套设计里最讨巧的一点）。未知值 ⇒ **整文件拒绝**，不静默失效。
 * @param cg                  <b>可选</b>：分身出现时由**它**播放的 CG 名（如 {@code "mo_entrance"}）。
 *                            名字不做校验（CG 是代码侧注册的），解析不到时由调用方打一条英文 WARN，
 *                            **不影响分身生成** —— 生成与演出解耦，坏数据不毁剧情。
 * @param lifetimeTicks       分身的存活时长（tick），缺省 {@value #DEFAULT_LIFETIME_TICKS}（1 小时）；
 *                            {@value #PERMANENT_LIFETIME_TICKS} = 永久。
 *                            ⚠️ 刻意**不用 0 表示永久**：数值上 0 更像"立刻过期"，容易写反。
 *                            ⚠️ 时长只在**生成那一刻**折算成绝对时刻存进实体（{@code BeloongExpireAt}）⇒
 *                            以后改这里的值**不会**追溯影响已存在的分身（D14 的承诺）。
 * @param clearOnLogout       玩家退出服务器时是否直接清理分身，缺省 {@code false} =
 *                            **不清理** —— 意外断线（崩溃、掉线）之后还能接着走。真正的兜底是
 *                            {@link #lifetimeTicks}：离线期间计时照走（游戏时间），超时照样清。
 * @param requiredDimension   <b>可选</b>：分身的有效维度。省略 = **不限制**（维度是**内容**，
 *                            不写进框架默认值；龙宫写在 {@code mo.json} 里）。
 *                            玩家离开该维度超过 {@link #dimensionGraceTicks} ⇒ 清理 + 撤回。
 * @param dimensionGraceTicks 离开维度到清理之间的**宽限**（tick），缺省 {@value #DEFAULT_DIMENSION_GRACE_TICKS}。
 *                            ⚠️ 没有它，在龙宫死一次（重生回主世界）就会被判成"弃坑"⇒ 进度被清。
 * @param keepAfterFinish    剧情**完成之后**是否保留私有分身，缺省 {@code false} = **不保留**
 *                            （通关即清理 ⇒ 玩家会重新看到公共锚点）。
 *                            ⚠️ 默认取 {@code false} 是刻意的：**新剧情默认干净** —— 长流程
 *                            （例如整合包那条横跨"变龙"的支线）不可能给一个短租约，
 *                            若不回收，通关玩家的分身会永久留场（只对本人可见，但会持续累积）。
 *                            短剧情若需要"结尾它坐在那里"的观感（设计 D6），**显式写 true**。
 */
public record NpcStory(ResourceLocation startAdvancement, ResourceLocation endAdvancement,
                       String spawn, Optional<String> cg, long lifetimeTicks,
                       boolean clearOnLogout, Optional<ResourceLocation> requiredDimension,
                       long dimensionGraceTicks, boolean keepAfterFinish) {

    /** 分身出现位置的唯一取值：公共锚点处（位置与朝向都复制）。 */
    public static final String SPAWN_ANCHOR = "anchor";

    /** 时长缺省值：72000 tick = 1 小时。 */
    public static final long DEFAULT_LIFETIME_TICKS = 72000L;

    /** 时长取这个值表示**永久**（除非被其它规则清理）。 */
    public static final long PERMANENT_LIFETIME_TICKS = -1L;

    /** 宽限缺省值：1200 tick = 60 秒。 */
    public static final long DEFAULT_DIMENSION_GRACE_TICKS = 1200L;

    public static final Codec<NpcStory> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("start_advancement").forGetter(NpcStory::startAdvancement),
            ResourceLocation.CODEC.fieldOf("end_advancement").forGetter(NpcStory::endAdvancement),
            Codec.STRING.optionalFieldOf("spawn", SPAWN_ANCHOR).forGetter(NpcStory::spawn),
            Codec.STRING.optionalFieldOf("cg").forGetter(NpcStory::cg),
            Codec.LONG.optionalFieldOf("lifetime_ticks", DEFAULT_LIFETIME_TICKS)
                    .forGetter(NpcStory::lifetimeTicks),
            Codec.BOOL.optionalFieldOf("clear_on_logout", Boolean.FALSE).forGetter(NpcStory::clearOnLogout),
            ResourceLocation.CODEC.optionalFieldOf("required_dimension")
                    .forGetter(NpcStory::requiredDimension),
            Codec.LONG.optionalFieldOf("dimension_grace_ticks", DEFAULT_DIMENSION_GRACE_TICKS)
                    .forGetter(NpcStory::dimensionGraceTicks),
            Codec.BOOL.optionalFieldOf("keep_after_finish", Boolean.FALSE)
                    .forGetter(NpcStory::keepAfterFinish)
    ).apply(instance, NpcStory::new));

    /** 是否永久（不做任何基于时长的清理）。 */
    public boolean isPermanent() {
        return this.lifetimeTicks == PERMANENT_LIFETIME_TICKS;
    }

    /**
     * 由"生成时刻"折算出的**绝对到期时刻**（游戏时间 tick）。
     * <p>
     * 生成时算一次并**存进实体**（{@code BeloongExpireAt}）而不是每次现读 {@link #lifetimeTicks} ——
     * 否则改数据里的时长会追溯影响已经存在的分身，D14 的承诺就失效了。
     */
    public long expiryAt(long now) {
        return this.isPermanent() ? Long.MAX_VALUE : now + this.lifetimeTicks;
    }

    /** 分身存活时长的人类可读描述（日志用；日志一律英文）。 */
    public String describeLifetime() {
        return this.isPermanent() ? "permanent" : this.lifetimeTicks + " ticks";
    }
}
