package com.zonlong.beloong.item.effect;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.network.magic.SyncMagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.datagen.Translation;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.registry.ModDataComponents;
import com.zonlong.beloong.registry.ModSounds;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 元素魔源 —— 十件共用这一个类，差异只来自 {@link Element} 数据表。
 *
 * <h2>它做什么</h2>
 *
 * 魔源可以<b>封存一个龙之生存技能</b>（技能 ID 存在数据组件
 * {@link ModDataComponents#ESSENCE_SKILL} 里）。龙玩家<b>潜行 + 右键</b>即可习得该技能，
 * 之后魔源<b>变空但不会消失</b>（只移除组件）。空魔源就是一件普通物品。
 *
 * <h2>为什么是一个具名类，而不是十件各写一个</h2>
 *
 * ⚠️ 这不是回到被用户否决过的 V1/V2 方案。那两版是「**十个**子类」与「一个只放属性的**基类**」，
 * 被否的理由是"只是简单注册物品，没必要额外写十个类"；而 V3（现行）本身就是
 * "十件共用**一个**匿名 `Item` 子类"。本类把那个匿名子类**具名化**，十件仍然共用同一份字节码 ——
 * 结构上仍是 V3，只是从匿名变具名。详见
 * {@code docs/plans/2026-09-13-element-essence-design.md} 的 §3 与 §11 rev 2。
 *
 * <h2>两个入口都要覆写（重要）</h2>
 *
 * 潜行右键<b>对着方块</b>时，原版走的是 {@code useOn} 而<b>不会</b>调 {@code use}
 * （{@code ServerPlayerGameMode.useItemOn}：潜行时方块交互整段被跳过，随后调 {@code stack.useOn}，
 * 其返回值直接 return，**不回落**到 {@code use()}）。
 * ⇒ 只写 {@code use} 的症状是"对着空气能用、对着方块用不出来"。
 * 两个入口因此都委托给同一个私有方法 {@link #tryLearn}。
 *
 * <h2>客户端只预测、不动手</h2>
 *
 * 客户端一律返回 {@code pass}，判定完全由服务端做。这是安全的：原版在本地预测<b>之前</b>就把
 * 交互包发出去了（{@code MultiPlayerGameMode.startPrediction} 无条件 {@code connection.send}），
 * 所以"客户端 pass、服务端办事"不会丢事件，还能避免"客户端先播放动作、服务端随后回绝"的观感问题
 * （本仓库 {@code docs/自定义物品总设计.md} §9.2 记过那个症状）。
 */
public class EssenceItem extends Item {

    /** Title 动画：淡入 tick（0.5 秒）。 */
    private static final int TITLE_FADE_IN = 10;

    /** Title 动画：停留 tick（3 秒）。音效的爆发点在 1.3 秒，落在停留期内。 */
    private static final int TITLE_STAY = 60;

    /** Title 动画：淡出 tick（1 秒）。 */
    private static final int TITLE_FADE_OUT = 20;

    /**
     * 元素数据表：十件魔源的行为差异只有这两项。
     * <p>
     * 做成<b>嵌套</b>枚举而不是独立类，是为了不新增"为了几项数据而存在的类"——
     * 元素魔源设计文档 D25 记过用户对这类抽象的反感（当时的理由是"只为了写 tooltip"，
     * 而现在已经不止 tooltip：还有 Title 颜色与粒子，所以这张表是<b>被需要的</b>，只是不独立成文件）。
     * <p>
     * 顺序 = {@code ModItems} 的声明顺序 = 创造页签顺序 = 校验脚本顺序
     * （金 → 木 → 水 → 火 → 土 → 风 → 雷 → 冰 → 光 → 暗），**不要重排**。
     */
    public enum Element {

        /** 金 —— 淡金（贴图最亮带）。 */
        METAL(0xF4F380),
        /** 木 —— 绿。 */
        WOOD(0x05BE05),
        /** 水 —— 青。 */
        WATER(0x0EC9DD),
        /** 火 —— 橙红。 */
        FIRE(0xEF551B),
        /** 土 —— 浅沙（低饱和，与金分开）。 */
        EARTH(0xFED8AA),
        /** 风 —— 深青（与水的亮青靠明度分开）。 */
        WIND(0x06B3BE),
        /** 雷 —— 紫（避开金系四元素挤色；呼应设计文档 D9「紫承身份」）。 */
        THUNDER(0x7E08BE),
        /** 冰 —— 极浅冰蓝（低饱和）。 */
        ICE(0xB9EDFA),
        /** 光 —— 金橙。 */
        LIGHT(0xF19D1D),
        /** 暗 —— 暗紫。 */
        DARK(0x6444BE);

        private final int titleColor;

        Element(int titleColor) {
            this.titleColor = titleColor;
        }

        /**
         * 习得时 Title 主标题的硬编码颜色（RGB）。
         * <p>
         * 颜色全部<b>从该元素的贴图里实测</b>得出（把不透明像素按亮度切成暗 30% / 中 40% / 亮 30% 三档，
         * 取其中一档的均值，再对过暗者按比例提亮），工具见 {@code tools/extract_essence_colors.py}。
         * 取值与残留重合的完整说明见
         * {@code docs/plans/2026-10-15-essence-skill-storage-design.md} §6.1。
         */
        public int titleColor() {
            return this.titleColor;
        }

        /**
         * 习得时播撒的原版粒子（每件不同）。
         * <p>
         * ⚠️ <b>在使用时才解析</b>，不在枚举类初始化期引用 {@code ParticleTypes}：后者的静态字段
         * 来自注册表引导，虽然通常在模组构造之前就已完成，但延后解析没有代价、也省掉一个顺序假设。
         *
         * @return 该元素的粒子选项；土的 {@code falling_dust} 需要附带一个方块状态
         */
        public ParticleOptions particle() {
            return switch (this) {
                case METAL -> ParticleTypes.CRIT;
                case WOOD -> ParticleTypes.COMPOSTER;
                case WATER -> ParticleTypes.SPLASH;
                case FIRE -> ParticleTypes.FLAME;
                case EARTH -> new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.DIRT.defaultBlockState());
                case WIND -> ParticleTypes.GUST;
                case THUNDER -> ParticleTypes.ELECTRIC_SPARK;
                case ICE -> ParticleTypes.SNOWFLAKE;
                case LIGHT -> ParticleTypes.END_ROD;
                case DARK -> ParticleTypes.SCULK_SOUL;
            };
        }
    }

    /** 该物品所属的元素。 */
    private final Element element;

    /** 该物品的 tooltip 翻译键（每件一条完整句子，见元素魔源设计文档 D26）。 */
    private final String tooltipKey;

    /**
     * @param element    元素（决定 Title 颜色与粒子）
     * @param tooltipKey 该物品的 tooltip 翻译键，形如 {@code item.beloong.metal_essence.tooltip}
     */
    public EssenceItem(Element element, String tooltipKey) {
        super(new Item.Properties().rarity(Rarity.UNCOMMON));
        this.element = element;
        this.tooltipKey = tooltipKey;
    }

    /** 该物品的元素。 */
    public Element element() {
        return this.element;
    }

    // ==================== 交互 ====================

    /** 右键<b>空中</b>的入口。 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        return switch (tryLearn(level, player, stack)) {
            case SUCCESS -> InteractionResultHolder.success(stack);
            case FAIL -> InteractionResultHolder.fail(stack);
            default -> InteractionResultHolder.pass(stack);
        };
    }

    /** 右键<b>方块</b>的入口。潜行时原版只会走这里，见类注释。 */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            // 命令/发射器等没有玩家的场景：本物品不提供那种用法
            return InteractionResult.PASS;
        }
        return tryLearn(context.getLevel(), player, context.getItemInHand());
    }

    /**
     * 两个入口共用的判定与授予流程。
     * <p>
     * 顺序见 {@code docs/plans/2026-10-15-essence-skill-storage-design.md} §4.1：
     * 客户端 → 潜行门 → 有没有封存技能 → 技能还在不在 → 是不是龙 → 是不是已经会了 → 授予 → 变空 + 同步。
     * <p>
     * <b>只有授予成功之后才移除组件</b> ⇒ 任何一条拒绝路径都不会让玩家白亏一件魔源。
     *
     * @return {@code PASS} = 本物品不管这次交互；{@code FAIL} = 受理了但拒绝（已给出提示）；
     *         {@code SUCCESS} = 习得成功，魔源已变空
     */
    private InteractionResult tryLearn(Level level, Player player, ItemStack stack) {
        // ① 客户端不做判定，也不做预测
        if (level.isClientSide()) {
            return InteractionResult.PASS;
        }

        // ② 潜行门：不潜行就完全不管，把右键让给原版与其它模组
        if (!player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }

        // ③ 空魔源就是普通物品
        ResourceLocation abilityId = stack.get(ModDataComponents.ESSENCE_SKILL.get());
        if (abilityId == null) {
            return InteractionResult.PASS;
        }

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }

        // ④ 技能必须还在数据包注册表里。查不到时**保留组件**（不清空），否则等于把玩家的东西销毁了
        Holder<DragonAbility> ability = serverPlayer.registryAccess()
                .registryOrThrow(DragonAbility.REGISTRY)
                .getHolder(ResourceKey.create(DragonAbility.REGISTRY, abilityId))
                .orElse(null);
        if (ability == null) {
            hint(player, "item.beloong.essence.unknown_skill");
            return InteractionResult.FAIL;
        }

        // ⑤ 只有龙才能习得
        if (!DragonStateProvider.isDragon(player)) {
            hint(player, "item.beloong.essence.only_dragon");
            return InteractionResult.FAIL;
        }

        MagicData magic = MagicData.getData(player);

        // ⑥ 已经会了 ⇒ 拒绝，且**不清空魔源**（玩家可以把这件转给别人）
        //    注：getAbility 在 MagicData.currentSpecies 为 null 时会安全地返回 null，不会抛
        if (magic.getAbility(ability.getKey()) != null) {
            hint(player, "item.beloong.essence.already_known");
            return InteractionResult.FAIL;
        }

        // ⑦ 授予。技能的**等级由使用者自己的成长度决定**（DS 内部按 upgrade 现算），不冻结在魔源里。
        boolean granted;
        try {
            granted = magic.addAbility(serverPlayer, ability);
        } catch (UnsupportedOperationException e) {
            // MagicData.currentSpecies 未被填充时，getAbilities() 返回不可变 Map.of()，
            // addAbility 内部的 .put() 会抛这个异常。不改动 DS 的任何状态来"修"它：
            // setCurrentSpecies 会让被动技能闪一下，refresh 会抹掉玩家已学的技能（设计文档 §5.2）。
            BeLoongCore.LOGGER.warn("[BeLoong] essence: MagicData.currentSpecies is not populated for {}, "
                    + "refusing to grant {}", serverPlayer.getGameProfile().getName(), abilityId);
            hint(player, "item.beloong.essence.state_error");
            return InteractionResult.FAIL;
        }

        if (!granted) {
            // 与 ⑥ 同因（并发/重复），按"已经会了"处理，同样不清空
            hint(player, "item.beloong.essence.already_known");
            return InteractionResult.FAIL;
        }

        // ⑧ 魔源变空 + 把新的技能数据同步给客户端
        //    ⚠️ addAbility **不自动同步**；不发这个包，技能不会出现在客户端的技能列表里
        clearStoredSkill(serverPlayer, stack);
        PacketDistributor.sendToPlayer(serverPlayer,
                new SyncMagicData(magic.serializeNBT(serverPlayer.registryAccess())));

        // ⑨ 反馈：Title（按元素着色）+ 粒子 + 音效
        celebrate(serverPlayer, abilityId);

        return InteractionResult.SUCCESS;
    }

    /**
     * 习得成功的反馈。只有真的授予成功后才调用 —— 五条拒绝路径都不发反馈。
     * <p>
     * 音效与 Title 在<b>本 tick 立即</b>发生；粒子交给 {@link EssenceLearnRitual}，
     * 它按与音效对齐的时间轴推进（法阵 → 高潮爆发 → 收尾），<b>不需要自定义同步代码</b>。
     *
     * @param abilityId 刚习得的技能 ID（用来取技能名填进主标题）
     */
    private void celebrate(ServerPlayer player, ResourceLocation abilityId) {
        // 主标题：文案来自翻译键，技能名作为 %s 参数（技能名本身也是翻译键，复用 DS 的键推导）
        Component title = Component.translatable("item.beloong.essence.learned.title",
                        Component.translatable(Translation.Type.ABILITY.wrap(abilityId)))
                .withStyle(style -> style.withColor(TextColor.fromRgb(this.element.titleColor())));
        Component subtitle = Component.translatable("item.beloong.essence.learned.subtitle");

        // 三个包一起发；顺序与提示一致（先动画，再副标题，最后主标题）
        player.connection.send(new ClientboundSetTitlesAnimationPacket(TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT));
        player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        player.connection.send(new ClientboundSetTitleTextPacket(title));

        // 音效先起（它决定整条时间轴的长度）：粒子仪式的节奏就是照这段音效实测编排的
        player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                ModSounds.ESSENCE_LEARN.get(), SoundSource.PLAYERS, 1.0F, 1.0F);

        // 粒子仪式：脚下法阵（元素色）→ 高潮时的球壳爆发（该元素的粒子）
        EssenceLearnRitual.start(player, this.element);
    }

    /** 给玩家一条 actionbar 提示（不刷聊天栏）。 */
    private static void hint(Player player, String translationKey) {
        player.displayClientMessage(Component.translatable(translationKey), true);
    }

    /**
     * 用掉<b>一个</b>魔源：把封存的技能从<b>手里那一个</b>上移除。
     *
     * <h3>⚠️ 为什么不能直接对整摞移除组件</h3>
     *
     * 数据组件是<b>整摞 {@code ItemStack} 共有</b>的，不是逐个物品独立的。所以直接
     * {@code stack.remove(ESSENCE_SKILL)} 会把一摞 64 个里封存的技能<b>全部清掉</b>，
     * 而玩家只该用掉一个 —— 实测踩过：一摞装着技能的魔源用一次，**整摞都变空**。
     * <p>
     * 正确做法：手里只有一个就就地移除；多于一个就<b>拆出 1 个</b>、只移除它，再把那 1 个空的放回背包
     * （背包满则掉在脚下，<b>不静默吞掉玩家物品</b>）。
     *
     * @param player 使用者（服务端）
     * @param stack  触发这次交互的那一摞（主手或副手）
     */
    private static void clearStoredSkill(ServerPlayer player, ItemStack stack) {
        // 创造模式不消耗：技能照样习得，魔源照旧是满的（与其它"可无限使用"的原版物品一致）
        if (player.hasInfiniteMaterials()) {
            return;
        }

        if (stack.getCount() <= 1) {
            stack.remove(ModDataComponents.ESSENCE_SKILL.get());
            return;
        }

        ItemStack emptied = stack.copyWithCount(1);
        emptied.remove(ModDataComponents.ESSENCE_SKILL.get());
        stack.shrink(1);

        if (!player.getInventory().add(emptied)) {
            player.drop(emptied, false);
        }
    }

    // ==================== 展示 ====================

    /** 封存了技能时带附魔光效，一眼可与空魔源区分。 */
    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.has(ModDataComponents.ESSENCE_SKILL.get());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        // 整个 tooltip 都用该元素的硬编码色（与 Title、粒子法阵同一个值）——
        // 十件魔源的 tooltip 因此各有自己的颜色，一眼能认出是哪种元素
        TextColor elementColor = TextColor.fromRgb(this.element.titleColor());

        tooltipComponents.add(Component.translatable(this.tooltipKey)
                .withStyle(style -> style.withColor(elementColor)));

        ResourceLocation abilityId = stack.get(ModDataComponents.ESSENCE_SKILL.get());
        if (abilityId == null) {
            return;
        }

        // 技能名复用龙之生存自己的键推导（dragon_ability.<命名空间>.<路径>），不要在这里自己拼字符串
        tooltipComponents.add(Component.translatable("item.beloong.essence.stored",
                        Component.translatable(Translation.Type.ABILITY.wrap(abilityId)))
                .withStyle(style -> style.withColor(elementColor)));
        tooltipComponents.add(Component.translatable("item.beloong.essence.hint")
                .withStyle(style -> style.withColor(elementColor)));
    }
}
