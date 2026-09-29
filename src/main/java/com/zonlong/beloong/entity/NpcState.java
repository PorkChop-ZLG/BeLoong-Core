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
 *   <li>这里加一个常量（名字、id）；</li>
 *   <li>{@link NpcEntity} 里按需覆写它的 {@code xxxAnimationName()}（子类的资产叫什么就写什么）；</li>
 *   <li>两个语言文件各补一条 {@code beloong.npc.state.<名字>}。</li>
 * </ol>
 * 同步、落盘、状态机、控制器骨架<b>一行都不用动</b>。
 *
 * <h2>为什么是「互斥」而不是「移动模式 × 姿态」两个正交轴</h2>
 * 用户裁定互斥（vanilla 的 {@code Armadillo}/{@code Sniffer} 也都是互斥枚举），代价是
 * "一边飞一边跳"做不到 —— 已明确列为非目标。
 * <p>
 * 📌 2026-09-29：本节的"判据"（{@code isMovementMode()}）已随姿态迁出而删除，
 * 讨论对象只剩 {@link #IDLE} 与 {@link #FLYING} 两个**都是移动模式**的状态。
 *
 * <h2>⚠️ 两种状态**都是移动模式** —— 姿态已迁出本枚举</h2>
 * 这里曾经还有 {@code SITTING} / {@code DANCING} 两个「姿态」常量，并靠 {@code isMovementMode()}
 * 区分两族（进入姿态要取消移动与攻击指令）。2026-09-29 起它们已迁入**表情系统**
 * （{@code docs/plans/2026-09-29-npc-emote-system-design.md}）：{@code sit} / {@code dance}
 * 的本质只是"播哪条动画"，与"能不能走"本就无关；把它们混进状态枚举，会让
 * 「怎么动」与「长什么样」互相牵制。
 * <p>
 * 于是现在两态**都是移动模式**：在它们之间切换时<b>移动指令保留</b>，并按新模式重新执行
 * （同一条 {@code move} 在地面是"走"、在飞行是"飞"），切换也<b>不会</b>取消移动或攻击。
 * <p>
 * 将来若要再加状态，先问一句：它改变的是「怎么动」还是「长什么样」？
 * <b>后者属于表情轴，不该进这个枚举</b>。
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
    IDLE("idle", 0),

    /** 飞行模式：换飞行导航与移动控制、关重力、原地悬停（**不起飞**）。 */
    FLYING("flying", 1);

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

    NpcState(String name, int id) {
        this.name = name;
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }

    /** 同步数据里存的 id。 */
    public int id() {
        return this.id;
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
