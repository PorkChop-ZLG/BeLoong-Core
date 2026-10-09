package com.zonlong.beloong.npcstory;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * 一条 NPC 剧情的**私人化声明**（**数据驱动**，从 {@code data/beloong/beloong/npc_story/<实体类型路径>.json} 加载）。
 * <p>
 * 设计：{@code docs/plans/2026-10-01-multiplayer-npc-design.md}（D8–D21）与
 * {@code docs/plans/2026-10-01-lease-offline-settlement-design.md}（D1–D14）。
 * 文件**按实体类型命名**（{@code mo.json} ↔ {@code beloong:mo}）⇒ 与 {@code npc_dialogue} 同构。
 * <p>
 * 本类只描述**意图**，不含行为：谁在什么时候应该拥有一个"只属于自己"的私有分身，它活多久，
 * 以及它出现时播什么演出。让世界与这份声明保持一致的，是 {@code NpcStoryHandler} 里的对账器。
 *
 * <h2>字段按语义分两组（2026-10-01 重组）</h2>
 * 顶层只留四个**基础**字段（起点/终点进度、出现位置、登场 CG），其余按语义归入：
 * <ul>
 *   <li>{@link Lease} —— <b>租约</b>：活多久、退出是否清、通关后是否保留、到期前怎么提醒；</li>
 *   <li>{@link Dimension} —— <b>维度</b>：剧情在哪个维度（= 对账器去哪个维度巡检）、
 *       离开要不要清、离开时怎么提醒。</li>
 * </ul>
 * ⚠️ 两个组是 {@code optionalFieldOf(..., DEFAULT)} ⇒ **整组可省**（省了就是全缺省）。
 *
 * <h2>为什么把"存在"和"演出"分开表达</h2>
 * "该玩家应当有一个分身"是**状态**（持续为真）⇒ 声明式，对账器可以反复跑、幂等、自愈；
 * "登场 CG 要播一次"是**一次性演出** ⇒ 事件式，由"起点进度**被获得**"这个事件驱动。
 * 混在一起写就必然要自己发明一个"CG 播过没有"的持久状态 —— 而**起点进度本身就是那个标记**。
 *
 * @param startAdvancement 必需的**起点进度**：玩家获得它的那一刻 ⇒ 该玩家应当拥有私有分身（并播 {@link #cg}）
 * @param endAdvancement   必需的**终点进度**：它是"剧情已完成"的判据，**同时**是撤回进度的上溯起点
 *                         （见 {@code NpcStoryHandler#revokeStory}：沿 {@code parent()} 一路走回起点）。
 *                         这两个 id 必须与对话数据里的阶段闸门**逐字一致**（有不变量守着）；
 *                         且它们**必须真实存在** —— 错了剧情根本不可能触发（加载后有一次自检报 ERROR）。
 * @param spawn            分身在**哪里**出现，缺省 {@value #SPAWN_ANCHOR}。目前只接受这个值：
 *                         分身在**公共锚点**的位置与朝向出现 ⇒ 玩家视角"那只 mo 一直在原地"，
 *                         切换无感（这是整套设计里最讨巧的一点）。未知值 ⇒ **整文件拒绝**，不静默失效。
 * @param cg               <b>可选</b>：分身出现时由**它**播放的 CG 名（如 {@code "mo_entrance"}）。
 *                         名字解析不到时由调用方打一条英文 ERROR，但**不影响分身生成**
 *                         —— 生成与演出解耦，坏 CG 数据不毁剧情（设计 D9/D12）。
 * @param lease            租约组，见 {@link Lease}
 * @param dimension        维度组，见 {@link Dimension}
 */
public record NpcStory(ResourceLocation startAdvancement, ResourceLocation endAdvancement,
                       String spawn, Optional<String> cg, Lease lease, Dimension dimension) {

    /** 分身出现位置的唯一取值：公共锚点处（位置与朝向都复制）。 */
    public static final String SPAWN_ANCHOR = "anchor";

    // ⚠️ 下面四个常量必须在 CODEC 之前声明：CODEC 的初始化会触发嵌套 record 的静态初始化，
    // 而它们的 DEFAULT 要读这几个常量 —— 声明在后的话读到的是 0。

    /** 租约缺省：72000 tick = 1 小时。 */
    public static final long DEFAULT_LIFETIME_TICKS = 72000L;

    /** 租约取这个值表示**永久**（此时"到期提醒"与离线结算都无意义）。 */
    public static final long PERMANENT_LIFETIME_TICKS = -1L;

    /**
     * 离开维度的宽限缺省：**6000 tick = 5 分钟**。
     * <p>
     * ⚠️ 2026-10-01 由 1200（60 秒）改为 6000：60 秒太紧，玩家在龙宫里死一次重生到主世界就会被判成弃坑。
     */
    public static final long DEFAULT_DIMENSION_GRACE_TICKS = 6000L;

    /** 提醒提前量的缺省（tick）= 600（30 秒）。租约那侧缺省时改用全局配置 {@code Config.NpcStory.expiryWarningTicks}。 */
    public static final long DEFAULT_WARN_BEFORE_TICKS = 600L;

    public static final Codec<NpcStory> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("start_advancement").forGetter(NpcStory::startAdvancement),
            ResourceLocation.CODEC.fieldOf("end_advancement").forGetter(NpcStory::endAdvancement),
            Codec.STRING.optionalFieldOf("spawn", SPAWN_ANCHOR).forGetter(NpcStory::spawn),
            Codec.STRING.optionalFieldOf("cg").forGetter(NpcStory::cg),
            // 整组可省：省了 = 全缺省（老文件里没有 lease 组时不会因此被拒）
            Lease.CODEC.optionalFieldOf("lease", Lease.DEFAULT).forGetter(NpcStory::lease),
            Dimension.CODEC.optionalFieldOf("dimension", Dimension.DEFAULT).forGetter(NpcStory::dimension)
    ).apply(instance, NpcStory::new));

    /**
     * <b>租约组</b>：私有分身活多久、退出服务器时是否直接清、通关后是否保留、到期前怎么提醒。
     *
     * @param ticks            存活时长（tick），缺省 {@value NpcStory#DEFAULT_LIFETIME_TICKS}（1 小时）；
     *                         {@value NpcStory#PERMANENT_LIFETIME_TICKS} = 永久。
     *                         ⚠️ 刻意**不用 0 表示永久**：数值上 0 更像"立刻过期"，容易写反。
     *                         ⚠️ 时长只在**生成那一刻**折算成绝对时刻存进实体（{@code BeloongExpireAt}）⇒
     *                         以后改这里的值**不会**追溯影响已存在的分身（设计 D14 的承诺）。
     * @param clearOnLogout    玩家退出服务器时是否直接清理，缺省 {@code false} = **不清理** ——
     *                         意外断线（崩溃、掉线）之后还能接着走。真正的兜底是 {@code ticks}：
     *                         离线期间计时照走（游戏时间），超时由巡检清掉（见下）。
     * @param keepAfterFinish  剧情**完成之后**是否保留分身，缺省 {@code false} = **不保留**（通关即回收，
     *                         玩家会重新看到公共锚点）。默认 false 是刻意的：**新剧情默认干净**。
     *                         短剧情若需要"结尾它坐在那里"的观感（设计 D6），**显式写 true**。
     *                         ⚠️ 取 {@code true} 时该剧情**不参与离线结算** —— 离线判不出"他是否已通关"，
     *                         而 true 的意义正是"通关后永久保留"⇒ 宁可不清理，也不误删（设计 D6）。
     * @param warnBeforeTicks  <b>可选</b>：到期前多久开始提醒；缺省（不写）⇒ 用全局配置
     *                         {@code Config.NpcStory.expiryWarningTicks}。写 {@code 0} = 不提醒。
     *                         ⚠️ 声明成 Optional 而不是"带缺省值"：否则无法区分"没写"与"显式写 600"，
     *                         全局兜底就永远不会生效。
     * @param warnText         <b>可选</b>：提醒用的**字面文本**（优先）。写 {@code %s} 则填入剩余秒数。
     * @param warnKey          <b>可选</b>：提醒用的**翻译键**（次之）。都不写 ⇒ 用模组内置默认键。
     *                         ⚠️ 键是否存在**无法在服务端校验**（{@code lang} 是客户端资源）⇒
     *                         写错时玩家屏幕上会直接出现原始键名（这是唯一无法硬报错的一类）。
     */
    public record Lease(long ticks, boolean clearOnLogout, boolean keepAfterFinish,
                        Optional<Long> warnBeforeTicks, Optional<String> warnText, Optional<String> warnKey) {

        public static final Lease DEFAULT = new Lease(
                DEFAULT_LIFETIME_TICKS, false, false,
                Optional.empty(), Optional.empty(), Optional.empty());

        public static final Codec<Lease> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.optionalFieldOf("ticks", DEFAULT_LIFETIME_TICKS).forGetter(Lease::ticks),
                Codec.BOOL.optionalFieldOf("clear_on_logout", Boolean.FALSE).forGetter(Lease::clearOnLogout),
                Codec.BOOL.optionalFieldOf("keep_after_finish", Boolean.FALSE).forGetter(Lease::keepAfterFinish),
                Codec.LONG.optionalFieldOf("warn_before_ticks").forGetter(Lease::warnBeforeTicks),
                Codec.STRING.optionalFieldOf("warn_text").forGetter(Lease::warnText),
                Codec.STRING.optionalFieldOf("warn_key").forGetter(Lease::warnKey)
        ).apply(instance, Lease::new));

        /** 是否永久（不做任何基于时长的清理，也不提醒到期）。 */
        public boolean isPermanent() {
            return this.ticks == PERMANENT_LIFETIME_TICKS;
        }
    }

    /**
     * <b>维度组</b>：剧情在哪个维度、离开要不要清、离开时怎么提醒。
     * <p>
     * ⚠️ 两个字段各管一件事，**刻意拆开**（2026-10-01）：
     * <ul>
     *   <li>{@code host} 决定<b>对账器去哪个维度巡检</b> —— 离线结算能不能扫到分身全靠它；
     *       省略 ⇒ 退化为"扫在线玩家所在维度" ⇒ <b>0 人在线时该剧情的离线结算不生效</b>（合法退化）。</li>
     *   <li>{@code enforce} 才决定"**离开这个维度就算弃坑**"（清分身 + 撤进度）。缺省 {@code false}。</li>
     * </ul>
     * 合成一个字段时会出现"要巡检目标就必须接受超时清理"的两难 —— 长流程剧情恰好是
     * "需要 host、不能要 enforce"。
     *
     * @param host            剧情所在维度（<b>可选</b>）。见上。
     * @param enforce         是否启用"离开 host 就清理"，缺省 {@code false}。⚠️ 为 true 时必须写 host
     *                        （否则不知道该"离开谁"）—— 加载期会拒绝这种组合。
     * @param graceTicks      离开到清理之间的**宽限**（tick），缺省
     *                        {@value NpcStory#DEFAULT_DIMENSION_GRACE_TICKS}（5 分钟）。
     * @param warnBeforeTicks <b>可选</b>：清理前多久开始提醒。**不写**时由 handler 用常量
     *                        {@value NpcStory#DEFAULT_WARN_BEFORE_TICKS}（30 秒）兜底
     *                        —— 与 {@link Lease#warnBeforeTicks()} 同一个口径：缺省值在**消费端**，
     *                        codec 里刻意不带缺省（否则无法区分"没写"与"显式写"）。写 0 = 不提醒。
     * @param warnText        <b>可选</b>：字面文本（优先）；{@code %s} 填剩余秒数。
     * @param warnKey         <b>可选</b>：翻译键（次之）；都不写 ⇒ 内置默认键。
     */
    public record Dimension(Optional<ResourceLocation> host, boolean enforce, long graceTicks,
                            Optional<Long> warnBeforeTicks, Optional<String> warnText, Optional<String> warnKey) {

        public static final Dimension DEFAULT = new Dimension(
                Optional.empty(), false, DEFAULT_DIMENSION_GRACE_TICKS,
                Optional.empty(), Optional.empty(), Optional.empty());

        public static final Codec<Dimension> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("host").forGetter(Dimension::host),
                Codec.BOOL.optionalFieldOf("enforce", Boolean.FALSE).forGetter(Dimension::enforce),
                Codec.LONG.optionalFieldOf("grace_ticks", DEFAULT_DIMENSION_GRACE_TICKS)
                        .forGetter(Dimension::graceTicks),
                Codec.LONG.optionalFieldOf("warn_before_ticks").forGetter(Dimension::warnBeforeTicks),
                Codec.STRING.optionalFieldOf("warn_text").forGetter(Dimension::warnText),
                Codec.STRING.optionalFieldOf("warn_key").forGetter(Dimension::warnKey)
        ).apply(instance, Dimension::new));

        /** 这条维度规则是否真的会生效（`enforce` 且写了 `host`）。 */
        public boolean enforced() {
            return this.enforce && this.host.isPresent();
        }
    }

    // ===================== 便捷访问器 =====================
    // 只为让既有调用点读起来仍是同一句话；**数据源只有一处**（lease / dimension 组内）。

    /** @see Lease#ticks() */
    public long lifetimeTicks() {
        return this.lease.ticks();
    }

    /** @see Lease#clearOnLogout() */
    public boolean clearOnLogout() {
        return this.lease.clearOnLogout();
    }

    /** @see Lease#keepAfterFinish() */
    public boolean keepAfterFinish() {
        return this.lease.keepAfterFinish();
    }

    /** @see Dimension#host() */
    public Optional<ResourceLocation> requiredDimension() {
        return this.dimension.host();
    }

    /** @see Dimension#graceTicks() */
    public long dimensionGraceTicks() {
        return this.dimension.graceTicks();
    }

    /** 是否永久（不做任何基于时长的清理）。 */
    public boolean isPermanent() {
        return this.lease.isPermanent();
    }

    /**
     * 由"生成时刻"折算出的**绝对到期时刻**（游戏时间 tick）。
     * <p>
     * 生成时算一次并**存进实体**（{@code BeloongExpireAt}）而不是每次现读 {@link Lease#ticks()} ——
     * 否则改数据里的时长会追溯影响已经存在的分身，设计 D14 的承诺就失效了。
     */
    public long expiryAt(long now) {
        return this.isPermanent() ? Long.MAX_VALUE : now + this.lease.ticks();
    }

    /** 分身存活时长的人类可读描述（日志用；日志一律英文）。 */
    public String describeLifetime() {
        return this.isPermanent() ? "permanent" : this.lease.ticks() + " ticks";
    }
}
