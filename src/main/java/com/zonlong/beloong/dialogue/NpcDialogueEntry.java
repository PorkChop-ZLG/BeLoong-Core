package com.zonlong.beloong.dialogue;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
// 只为 javadoc 的 {@link NpcEntity} 而导入（"stop_emote 只在被对话的实体是 NpcEntity 时生效"）
import com.zonlong.beloong.entity.NpcEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.List;
import java.util.Optional;

/**
 * 一条 NPC 对话的数据定义（**一个实体类型一条**）。
 * <p>
 * 数据文件位于 {@code data/beloong/beloong/npc_dialogue/<entity>.json}，随模组 jar 分发，
 * 由**服务端**的 {@link NpcDialogueLoader} 经 {@code AddReloadListenerEvent} 解析。
 * 客户端**不持有全表** —— 玩家右键命中时服务端才把**那一条**经 {@link NpcDialogueOpenPayload}
 * 下发给他（见 {@code docs/plans/2026-09-25-npc-dialogue-data-driven-design.md}）。
 * <p>
 * 与「对话树」不同，本结构刻意只有**一维的页列表**：同类型实体只有一段对话，
 * 线性播放完即弹出选项。这是"仅供整合包使用"这一前提换来的简化，
 * 详见 {@code docs/plans/2026-09-20-npc-dialogue-design.md}。
 * <p>
 * 字段（除 {@code entity} 与 {@code pages} 外均可省略）：
 * <ul>
 *   <li>{@code entity} —— 绑定到哪个实体类型；</li>
 *   <li>{@code trigger} —— {@code empty_hand}（缺省）/ {@code any}，见 {@link Trigger}；</li>
 *   <li>{@code name} —— 说话人名字的**翻译键**；缺省用实体自身的显示名；</li>
 *   <li>{@code pages} —— 逐页文本，每页一个翻译键；页数即数组长度（不设计数字段，避免两处不同步）；</li>
 *   <li>{@code replies} —— 播放完毕后出现在「离开」**上方**的回复选项（可选，缺省空表）；
 *       每项指向一段 ChatBox 对话，是本系统与 ChatBox 的**唯一数据耦合点**，
 *       见 {@code docs/plans/2026-09-29-npc-dialogue-chatbox-bridge-design.md}；
 *       每项还可声明**开始进度 / 结束进度**（原版 advancement）来当阶段闸门 —— 判定见
 *       {@link NpcDialogueStage}，设计见
 *       {@code docs/plans/2026-09-29-npc-advancement-stage-system-design.md}。</li>
 * </ul>
 *
 * @param entity  绑定的实体类型
 * @param trigger 触发方式
 * @param name    说话人名字的翻译键（缺省用实体显示名）
 * @param pages   逐页文本（非空；空数组由加载器丢弃并报错）
 * @param replies 回复选项（可为空表 ⇒ 与从前完全一致，只有「离开」）
 */
public record NpcDialogueEntry(
        EntityType<?> entity,
        Trigger trigger,
        Optional<String> name,
        List<Page> pages,
        List<Reply> replies
) {

    /**
     * 触发方式。
     * <p>
     * <b>缺省 {@link #EMPTY_HAND}</b>：这样"手持铁锭右键铁傀儡 = 给铁傀儡回血"这类原版交互不会被本功能吃掉。
     * 需要"手持任何物品都能对话"时，在数据文件里显式写 {@code "trigger": "any"}，
     * 代价是该实体的那类原版物品交互会被本功能抢走 —— 这是**逐实体**的显式选择。
     * <p>
     * 有意不提供"潜行豁免"之类的隐式规则（用户裁定）：规则越少越不容易踩坑。
     */
    public enum Trigger {
        /** 仅空手时触发（缺省）。 */
        EMPTY_HAND,
        /** 手持任意物品也触发。 */
        ANY
    }

    /**
     * 一页对话。
     *
     * @param text  文本的翻译键；值内可用 {@code \n} 分行，可用 {@code §} 颜色代码
     * @param sound 预留的声音事件 ID（可选）。<b>当前版本只解析不播放</b>，
     *              用于将来接入配音：schema 先定型，实装时只需加一行播放调用
     */
    public record Page(String text, Optional<ResourceLocation> sound) {
        public static final Codec<Page> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("text").forGetter(Page::text),
                ResourceLocation.CODEC.optionalFieldOf("sound").forGetter(Page::sound)
        ).apply(instance, Page::new));

        /**
         * 声音字段的线格式。
         * <p>
         * <b>刻意不用 {@link ResourceLocation#STREAM_CODEC}</b>：它是
         * {@code STRING_UTF8.map(ResourceLocation::parse, …)}，而 {@code parse} 对畸形输入会**抛**，
         * 解码期抛异常会中止连接（不是丢一个包）。{@link ResourceLocation#tryParse} 则返回
         * {@code null} 而**不抛**，换成它之后"线格式全函数"这条不变量才真正成立。
         * <p>
         * 实践中畸形值不可能出现（唯一的生产者是我们自己的服务端，而它读的是非抛的 JSON codec），
         * 这里纯属防御 —— 但正是因为"线载荷永不抛"是 {@link NpcDialogueOpenPayload}
         * 整个形状的设计依据，这条不变量必须是**真的**，不能只是听起来对。
         */
        private static final StreamCodec<ByteBuf, Optional<ResourceLocation>> SOUND_STREAM_CODEC =
                ByteBufCodecs.STRING_UTF8.map(
                        s -> Optional.ofNullable(ResourceLocation.tryParse(s)),
                        o -> o.map(ResourceLocation::toString).orElse(""));

        /**
         * 线格式（网络下发用），与 {@link #CODEC} 语义一致、只是载体不同。
         * <p>
         * 本 codec 是**全函数**：两个字段都能无条件解出，没有任何会抛的分支。
         * 这是刻意的 —— {@code StreamCodec} 解码失败**无法优雅降级**（NeoForge 会中止连接），
         * 所以线载荷里不能出现"可能查不到/可能抛"的东西。详见
         * {@link NpcDialogueOpenPayload} 的类注释。
         */
        public static final StreamCodec<ByteBuf, Page> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Page::text,
                SOUND_STREAM_CODEC, Page::sound,
                Page::new);
    }

    /**
     * 一条「回复」选项 —— NPC 对话播放完毕后出现在「离开」**上方**，点击后进入 ChatBox 的某段对话。
     * <p>
     * <b>这是本模组对话系统与 ChatBox 的唯一数据耦合点。</b>{@code chatbox} + {@code group} + {@code index}
     * 指向 {@code data/<ns>/chatbox/dialogues/} 里的一段对话；能否真的打开由服务端在点击时预检
     * （ChatBox 那侧几乎没有服务端失败信号：服务端 util 无校验无日志 {@code ChatBoxCommandUtil.java:65-69}，
     * 客户端只在"组缺失或为空"时打一条 WARN {@code ChatBoxUtil.java:143-154}）。设计见
     * {@code docs/plans/2026-09-29-npc-dialogue-chatbox-bridge-design.md}。
     * <p>
     * <b>为什么 {@code index} 是 {@link Optional} 而不是 {@code int} 缺省 0</b>：ChatBox 那侧的页序号
     * <b>绝不可为 null</b> —— 它会被编码成字符串 {@code "null"}，让客户端 {@code Integer.parseInt} 抛异常。
     * 用 {@code Optional} 把"未填写"与"填了 0"分开表达，缺省在**我们这一侧**就折成 0，
     * 这样那条约束在类型层面就不可违反。
     * <p>
     * <b>标签文本与 {@code pages} 一样是翻译键</b>；而页序与目标**不会发给客户端** ——
     * 客户端点击后只回传"实体网络 id + 回复下标"，目标由服务端用自己的表解析
     * （客户端因此无法让服务端播放任意对话；见设计的 D1）。
     *
     * <p>
     * <b>两个进度字段是这个回复的"阶段闸门"</b>（都可省略；省略 = 无该约束）：
     * <ul>
     *   <li>{@code start_advancement} —— 玩家**已完成**它时，这条回复才有资格显示；</li>
     *   <li>{@code end_advancement} —— 玩家一旦完成它，这条回复**永久不再显示**（结束优先于开始）。</li>
     * </ul>
     * 完整规则、以及"未知进度 id 一律视为不可见"（fail-closed）的取舍见 {@link NpcDialogueStage}。
     * <b>本模组不发放、也不撤销进度</b>（只查询完成状态）：结束进度由 ChatBox 那段对话**最后一页的
     * 「好的」选项在点击时**发放 —— 见 {@code data/beloong/chatbox/dialogues/mo.json} 的
     * {@code options[0].click}（**不是**"文字播完时"：页级 {@code renderEvents} 收不到 {@code ON_END}）。
     *
     * <p>
     * <b>{@code stop_emote}（可选，缺省 {@code false} = 不清）</b>：点这条回复时**停掉该 NPC 当前的表情动画**。
     * 用在"NPC 正坐着/站着摆姿势，玩家一开口就该收起来"这类场合。缺省不清是刻意的 ——
     * 大多数回复不该打断 NPC 的姿态，尤其是路线终点用 {@code end_emote} 播出来的那个姿势。
     * 只在被对话的实体确实是 {@link NpcEntity} 时生效（对话表按实体类型挂，可以是任意实体类型）。
     *
     * @param text             标签的翻译键
     * @param chatbox          ChatBox 对话文件的 ResourceLocation（如 {@code beloong:mo}）
     * @param group            该文件里的组名（如 {@code start}）
     * @param index            页序号（0 基；缺省 0）
     * @param startAdvancement 开始进度（缺省无约束）
     * @param endAdvancement   结束进度（缺省无约束）
     * @param stopEmote        点击时是否停掉该 NPC 的表情动画（缺省 false = 不清）
     */
    public record Reply(String text, ResourceLocation chatbox, String group, Optional<Integer> index,
                        Optional<ResourceLocation> startAdvancement,
                        Optional<ResourceLocation> endAdvancement,
                        boolean stopEmote) {
        public static final Codec<Reply> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("text").forGetter(Reply::text),
                ResourceLocation.CODEC.fieldOf("chatbox").forGetter(Reply::chatbox),
                Codec.STRING.fieldOf("group").forGetter(Reply::group),
                Codec.INT.optionalFieldOf("index").forGetter(Reply::index),
                ResourceLocation.CODEC.optionalFieldOf("start_advancement").forGetter(Reply::startAdvancement),
                ResourceLocation.CODEC.optionalFieldOf("end_advancement").forGetter(Reply::endAdvancement),
                // 可选：缺省 false = 不清表情（与 arrival_radius 同款的"带默认值的 optionalFieldOf"）
                Codec.BOOL.optionalFieldOf("stop_emote", false).forGetter(Reply::stopEmote)
        ).apply(instance, Reply::new));
    }

    /**
     * 实体类型编解码。
     * <p>
     * 用 {@code flatXmap}（**双向**都可失败）而 {@code comapFlatMap}（只有解码可失败）
     * 或 {@code xmap}（都不可失败）：未知实体类型必须变成 {@link DataResult#error}，
     * 由加载器把**这一个文件**隔离掉；若在 {@code xmap} 里抛异常，异常会穿透 codec 框架，
     * 变成整个数据包重载失败。与 {@code StructureEffectEntry:21-25} 同款做法。
     * <p>
     * 两个方向都写成**显式签名的方法**而不是内联 lambda：{@code flatXmap} 的通配符签名
     * 会让内联 lambda 的类型推断失败。
     */
    private static final Codec<EntityType<?>> ENTITY_CODEC =
            ResourceLocation.CODEC.flatXmap(NpcDialogueEntry::decodeEntity, NpcDialogueEntry::encodeEntity);

    private static DataResult<EntityType<?>> decodeEntity(ResourceLocation location) {
        Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(location);
        if (type.isEmpty()) {
            return DataResult.error(() -> "Unknown entity type: " + location);
        }
        return DataResult.success(type.get());
    }

    /**
     * 反向（编码）。未注册的实体类型 {@code getKey} 返回 {@code null}，
     * 直接交给 codec 会得到 null / NPE —— 当前没有序列化路径，但 schema 将来会被
     * 校验工具或配置导出复用，显式报错比静默产出 null 好。
     */
    private static DataResult<ResourceLocation> encodeEntity(EntityType<?> entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity);
        if (id == null) {
            return DataResult.error(() -> "Entity type is not registered: " + entity);
        }
        return DataResult.success(id);
    }

    /** 触发方式编解码：取值只有 {@code empty_hand} 与 {@code any}，未知取值报错而非静默回退。 */
    private static final Codec<Trigger> TRIGGER_CODEC = Codec.STRING.comapFlatMap(
            name -> switch (name) {
                case "empty_hand" -> DataResult.success(Trigger.EMPTY_HAND);
                case "any" -> DataResult.success(Trigger.ANY);
                default -> DataResult.error(() ->
                        "Unknown trigger: " + name + " (expected empty_hand|any)");
            },
            trigger -> trigger == Trigger.ANY ? "any" : "empty_hand");

    public static final Codec<NpcDialogueEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ENTITY_CODEC.fieldOf("entity").forGetter(NpcDialogueEntry::entity),
            TRIGGER_CODEC.optionalFieldOf("trigger", Trigger.EMPTY_HAND).forGetter(NpcDialogueEntry::trigger),
            Codec.STRING.optionalFieldOf("name").forGetter(NpcDialogueEntry::name),
            Codec.list(Page.CODEC).fieldOf("pages").forGetter(NpcDialogueEntry::pages),
            // 缺省空表 ⇒ 已有数据文件（都没有 replies 字段）的行为完全不变
            Codec.list(Reply.CODEC).optionalFieldOf("replies", List.of()).forGetter(NpcDialogueEntry::replies)
    ).apply(instance, NpcDialogueEntry::new));

    /** 供日志使用：本条绑定的实体 id。 */
    public ResourceLocation entityId() {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity);
    }
}
