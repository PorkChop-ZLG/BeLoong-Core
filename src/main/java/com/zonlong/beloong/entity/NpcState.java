package com.zonlong.beloong.entity;

import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * 通用 NPC 的<b>状态</b> —— <b>单一互斥枚举</b>。
 * <p>
 * 设计：{@code docs/plans/2026-09-27-npc-state-system-design.md}。
 * 「飞行、坐下、跳舞」本质是同一件事：<b>由指令设定、直到指令终止才恢复、需要持续播动画、
 * 而且要活过存档重登</b>。用多个布尔装它们的话，每加一个状态都要动四处
 * （同步字段、落盘键、状态方法、控制器分支），组合语义也会失控 —— 故用一个枚举。
 *
 * <h2>加一个状态要改什么</h2>
 * <ol>
 *   <li>这里加一个常量（名字、id、是否移动模式）；</li>
 *   <li>{@link NpcEntity} 里按需覆写它的 {@code xxxAnimationName()}（子类的资产叫什么就写什么）；</li>
 *   <li>两个语言文件各补一条 {@code beloong.npc.state.<名字>}。</li>
 * </ol>
 * 同步、落盘、状态机、控制器骨架<b>一行都不用动</b>。
 *
 * <h2>为什么是「互斥」而不是「移动模式 × 姿态」两个正交轴</h2>
 * 用户裁定互斥（vanilla 的 {@code Armadillo}/{@code Sniffer} 也都是互斥枚举），代价是
 * "一边飞一边跳"做不到 —— 已明确列为非目标。判据见 {@link #isMovementMode()}。
 *
 * <h2>两种状态、两族行为（本枚举最容易被改错的地方）</h2>
 * <ul>
 *   <li><b>移动模式</b>（{@link #IDLE}、{@link #FLYING}）—— 在它们之间切换时，
 *       <b>移动指令保留</b>，并按新模式重新执行（同一条 {@code move} 在地面是"走"、在飞行是"飞"）；</li>
 *   <li><b>姿态</b>（{@link #SITTING}、{@link #DANCING}）—— 进入它们时<b>必须取消移动与攻击指令</b>，
 *       因为"姿态"这个类别的定义就是"不走、不动手"，留着指令只会让 NPC 一边坐着一边滑行。</li>
 * </ul>
 *
 * <h2>⚠️ 四种还原入口，行为<b>各不相同</b> —— 不要合并</h2>
 * 同一个"从外部还原状态"的需求，在四个场合的正确行为是<b>相反</b>的：
 * <table border="1">
 *   <tr><th>入口</th><th>来源</th><th>遇到非法值</th><th>为什么</th></tr>
 *   <tr><td>{@link #byId(int)}</td><td>网络同步数据</td><td>回落 {@link #IDLE}</td>
 *       <td>数据来自网络，宁可回落也绝不能崩</td></tr>
 *   <tr><td>{@link #byNameLenient(String)}</td><td>实体存档 NBT</td><td>回落 {@link #IDLE}</td>
 *       <td>旧存档 / 降级 / 玩家手改存档都会出现对不上的名字，崩掉等于坏档</td></tr>
 *   <tr><td>{@link #byNameStrict(String)}</td><td><b>指令参数</b></td><td><b>返回空 ⇒ 调用方报错</b></td>
 *       <td>玩家把 {@code flying} 敲成 {@code fliing} 时，静默变成"待机"会让他以为命令成功了</td></tr>
 *   <tr><td>{@code getSerializedName()}</td><td>写存档 / 显示</td><td>—</td>
 *       <td>写<b>名字</b>而不是 ordinal：枚举顺序将来变了也不会把旧存档读错</td></tr>
 * </table>
 * 「严格版与宽松版必须并存」这一条是刻意写进设计文档的禁令，理由是后人极可能图省事合并成一个。
 *
 * <h2>实现说明</h2>
 * {@link #CODEC} 用的是 {@link StringRepresentable.EnumCodec}，与 vanilla 的
 * {@code Armadillo}（{@code Armadillo.java:429,446-447} 的 {@code CODEC.byName(name, IDLE)}）
 * 完全同构。该类在 1.21.1 标了 {@code @Deprecated}，但 vanilla 自己仍在用；
 * 之所以不换成手写循环，是为了让"我们的回落语义"和原版逐字一致、便于对照。
 */
public enum NpcState implements StringRepresentable {

    /** 默认状态：地面站桩。**唯一**会走 idle / walk / run 三选一的状态。 */
    IDLE("idle", 0, true),

    /** 飞行模式：换飞行导航与移动控制、关重力、原地悬停（**不起飞**）。 */
    FLYING("flying", 1, true),

    /** 姿态：坐下。资产里有对应动画的 NPC 才覆写 {@code sitAnimationName()}。 */
    SITTING("sitting", 2, false),

    /** 姿态：跳舞。同上。 */
    DANCING("dancing", 3, false);

    private static final StringRepresentable.EnumCodec<NpcState> CODEC =
            StringRepresentable.fromEnum(NpcState::values);

    /**
     * id → 状态。{@code OutOfBoundsStrategy.ZERO} 是刻意的：越界回落到下标 0，
     * 而 {@link #IDLE} 正是第一个常量 ⇒ 「越界即待机」，与 {@link #byNameLenient} 的语义一致。
     * （这与 {@code Armadillo.java:430-432} 的用法相同。）
     */
    private static final IntFunction<NpcState> BY_ID =
            ByIdMap.continuous(NpcState::id, values(), ByIdMap.OutOfBoundsStrategy.ZERO);

    /** 全部状态名（声明顺序）。供 Brigadier 补全与"可用状态"错误提示使用。 */
    public static final List<String> NAMES =
            Arrays.stream(values()).map(NpcState::getSerializedName).toList();

    private final String name;
    private final int id;
    private final boolean movementMode;

    NpcState(String name, int id, boolean movementMode) {
        this.name = name;
        this.id = id;
        this.movementMode = movementMode;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }

    /** 同步数据里存的 id。 */
    public int id() {
        return this.id;
    }

    /**
     * 是否属于「移动模式」这一族。
     * <p>
     * <b>它决定两件事，别只看名字：</b>
     * <ol>
     *   <li>移动指令到来时，是"保留并按新模式执行"（移动模式）还是"先隐式退出到 {@link #IDLE}"（姿态）；</li>
     *   <li>进入本状态时，要不要取消移动与攻击指令（姿态要，移动模式不要）。</li>
     * </ol>
     */
    public boolean isMovementMode() {
        return this.movementMode;
    }

    // ===================== 四种还原入口（行为各不相同 —— 见类注释的表格）=====================

    /**
     * 从<b>同步数据</b>的 id 还原。越界回落 {@link #IDLE}。
     * <p>
     * ⚠️ 不要拿它去校验指令参数 —— 那样 {@code fliing} 会静默变成"待机"。
     * 指令用 {@link #byNameStrict(String)}。
     */
    public static NpcState byId(int id) {
        return BY_ID.apply(id);
    }

    /**
     * 从<b>存档</b>里的名字还原，未知名字回落 {@link #IDLE}。
     * <p>
     * ⚠️ 不要拿它去校验指令参数，理由同上。
     */
    public static NpcState byNameLenient(@Nullable String name) {
        return CODEC.byName(name, IDLE);
    }

    /**
     * 从<b>指令参数</b>还原，未知名字返回空 ⇒ <b>调用方必须报错</b>。
     * <p>
     * ⚠️ 这个"返回空"与上面两个"回落 {@link #IDLE}"是<b>刻意相反</b>的：
     * 存档里对不上要宽容（否则坏档），指令里拼错要严格（否则玩家以为命令成功了）。
     * 这两件事<b>不能合并成一个方法</b>。
     */
    public static Optional<NpcState> byNameStrict(@Nullable String name) {
        return Optional.ofNullable(CODEC.byName(name));
    }
}
