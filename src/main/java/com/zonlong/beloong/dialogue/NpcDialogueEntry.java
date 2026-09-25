package com.zonlong.beloong.dialogue;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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
 *   <li>{@code pages} —— 逐页文本，每页一个翻译键；页数即数组长度（不设计数字段，避免两处不同步）。</li>
 * </ul>
 *
 * @param entity  绑定的实体类型
 * @param trigger 触发方式
 * @param name    说话人名字的翻译键（缺省用实体显示名）
 * @param pages   逐页文本（非空；空数组由加载器丢弃并报错）
 */
public record NpcDialogueEntry(
        EntityType<?> entity,
        Trigger trigger,
        Optional<String> name,
        List<Page> pages
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
            Codec.list(Page.CODEC).fieldOf("pages").forGetter(NpcDialogueEntry::pages)
    ).apply(instance, NpcDialogueEntry::new));

    /** 供日志使用：本条绑定的实体 id。 */
    public ResourceLocation entityId() {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity);
    }
}
