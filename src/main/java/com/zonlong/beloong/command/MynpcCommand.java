package com.zonlong.beloong.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.entity.NpcEntity;
import com.zonlong.beloong.npcstory.NpcStoryLoader;
import com.zonlong.beloong.route.NpcRoute;
import com.zonlong.beloong.route.NpcRouteLoader;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * {@code /beloong mynpc <实体类型> …} —— <b>以"执行者自己那只 NPC"为目标</b>的命令族。
 *
 * <h2>为什么必须有这一族（而不是让内容作者用 {@code @n[type=...]}）</h2>
 * NPC 剧情的私有分身是**每人一份**（设计见 {@code docs/plans/2026-10-01-multiplayer-npc-design.md}），
 * 而地图作者能用的选择器都表达不出"**属于我的**那只"：
 * <ul>
 *   <li>{@code @n[type=beloong:mo]} 是原版 {@code EntitySelectorParser} 的"**最近**实体"
 *       （{@code SELECTOR_NEAREST_ENTITY = 'n'}）—— 它可能选中**别的玩家的分身**，
 *       也可能选中那只**对玩家不可见的公共锚点**（玩家刚获得起点进度时，分身就生成在锚点原地，
 *       两者同坐标 ⇒ "最近"是谁完全看遍历顺序）⇒ 命令看起来"什么也没发生"。</li>
 *   <li>{@code @s} 会被 {@code NpcCommand.npcsIn()} 过滤掉（{@code @s} 是玩家，不是 NPC）。</li>
 *   <li>{@code /beloong route <名>} 虽然也是玩家作用域，但它靠 {@link com.zonlong.beloong.dialogue.LastDialogueNpc}
 *       解析"最近对话过的 NPC" ⇒ 在<b>跟 A 说话却要动 B</b> 的场合必然指错
 *       （典型的真实场景：玩家在跟「帝」对话，而选项要停掉「末」的坐姿）。</li>
 * </ul>
 * 本族的解析规则是「<b>归属执行者、且类型匹配</b>」：{@code type == X ∧ BeloongOwner == 执行者 UUID}。
 * 它 O(实体数)、**不依赖对话历史**、且**结构上不可能选中公共锚点**（锚点没有归属）。
 *
 * <h2>指令面</h2>
 * <pre>
 *   /beloong mynpc &lt;type&gt; route  &lt;route&gt;                        ← 指派路线
 *   /beloong mynpc &lt;type&gt; play   &lt;animation&gt; | play stop      ← 播/停表情
 *   /beloong mynpc &lt;type&gt; effect &lt;effect&gt; [seconds] [amplifier] [hideParticles]
 *   /beloong mynpc &lt;type&gt; tp     &lt;pos&gt; [yaw] [pitch]           ← 瞬移（**仅当前维度**）
 *                                              ⚠️ {@code pos} 里的 {@code ~} 相对的是**执行者**（命令源），
 *                                              不是被传送的那只 NPC —— 想"就地"请写绝对坐标。
 * </pre>
 *
 * <h2>为什么与 {@code npc} / {@code route} 平级</h2>
 * 既有的指令形状是 {@code npc} 之后第一个节点就是**实体参数**，若把本族放在同一位置，
 * Brigadier 会把它当成"一个名叫 mynpc 的实体"（同名歧义，见 {@link RouteCommand} 的类注释）。
 * 因此它必须是 {@code beloong} 的**直接子字面量**。
 *
 * <h2>权限与执行者</h2>
 * op 级（{@code hasPermission(2)}），与 {@code npc} / {@code route} 一致 —— 三处共享同一个
 * {@code beloong} 根字面量，Brigadier 合并子树时**不带走** {@code requires} 谓词 ⇒ 必须各写一遍。
 * <p>
 * <b>它是以玩家身份执行的命令</b>（ChatBox 的选项 {@code click} 就是这样执行的）。命令方块/控制台
 * 没有"该玩家"这回事 ⇒ 明确报错，绝不退化成"随便挑一只"。
 *
 * <h2>刻意没做的</h2>
 * <ul>
 *   <li><b>跨维度 {@code tp}</b>：整合包场景里 NPC 与目标点同维度。真需要跨维度再单独设计，
 *       不要在这里悄悄加一条 {@code teleportTo} 分支（跨维度传送要处理乘客、区块加载、坐标对齐）。</li>
 *   <li>{@code state} / {@code move} / {@code attack} / {@code reset} 的玩家作用域版本（YAGNI）。</li>
 *   <li>{@code effect infinite}（原版有）：本族只接受正数秒数。</li>
 * </ul>
 */
public final class MynpcCommand {

    private MynpcCommand() {}

    /**
     * {@code type} 参数的补全：只列**有剧情声明**（{@code npc_story}）的实体类型。
     * <p>
     * 理由：只有这些类型才可能拥有私有分身 ⇒ 补全列表就是"这条命令能用的全部取值"，
     * 比列全部实体类型更有用，也顺带告诉了作者"还有哪些 NPC 接了剧情"。
     */
    private static final SuggestionProvider<CommandSourceStack> TYPE_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggestResource(
                    NpcStoryLoader.INSTANCE.all().keySet().stream()
                            .map(BuiltInRegistries.ENTITY_TYPE::getKey)
                            .filter(Objects::nonNull)
                            .toList(),
                    builder);

    /** {@code route} 参数的补全：列出已加载的全部路线名（与 {@code NpcCommand} / {@code RouteCommand} 同一取舍）。 */
    private static final SuggestionProvider<CommandSourceStack> ROUTE_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    NpcRouteLoader.INSTANCE.nameStrings(), builder);

    /**
     * @param context 供 {@code effect} 的 {@code ResourceArgument.resource(context, Registries.MOB_EFFECT)} 使用
     *                —— 照原版 {@code EffectCommands#register} 的形状，两处必须一致。
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        dispatcher.register(Commands.literal("beloong")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("mynpc")
                        // ⚠️ 必须用 ResourceLocationArgument：实体类型形如 beloong:mo，
                        // 而 StringArgumentType.word() 的字符集不含冒号（NpcCommand 里踩过同样的坑）。
                        .then(Commands.argument("type", ResourceLocationArgument.id())
                                .suggests(TYPE_SUGGESTIONS)
                                .then(Commands.literal("route")
                                        .then(Commands.argument("route", ResourceLocationArgument.id())
                                                .suggests(ROUTE_SUGGESTIONS)
                                                .executes(ctx -> route(
                                                        ctx,
                                                        ResourceLocationArgument.getId(ctx, "type"),
                                                        ResourceLocationArgument.getId(ctx, "route")))))
                                .then(Commands.literal("play")
                                        // 与 NpcCommand 的 play 同形：先接参数，再接 stop 字面量。
                                        .then(Commands.argument("animation", StringArgumentType.word())
                                                .executes(ctx -> play(
                                                        ctx,
                                                        ResourceLocationArgument.getId(ctx, "type"),
                                                        StringArgumentType.getString(ctx, "animation"))))
                                        .then(Commands.literal("stop")
                                                .executes(ctx -> stopEmote(
                                                        ctx, ResourceLocationArgument.getId(ctx, "type")))))
                                // 参数形状与默认值照抄原版 EffectCommands#giveEffect：
                                // 省略 seconds ⇒ 非瞬时 600 tick（30 秒）、瞬时 1 tick；amplifier 默认 0；
                                // hideParticles 省略 ⇒ 显示粒子（即 showParticles = true）。
                                .then(Commands.literal("effect")
                                        .then(Commands.argument("effect",
                                                        ResourceArgument.resource(context, Registries.MOB_EFFECT))
                                                .executes(ctx -> effect(
                                                        ctx,
                                                        ResourceLocationArgument.getId(ctx, "type"),
                                                        ResourceArgument.getMobEffect(ctx, "effect"),
                                                        null, 0, true))
                                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 1000000))
                                                        .executes(ctx -> effect(
                                                                ctx,
                                                                ResourceLocationArgument.getId(ctx, "type"),
                                                                ResourceArgument.getMobEffect(ctx, "effect"),
                                                                IntegerArgumentType.getInteger(ctx, "seconds"),
                                                                0, true))
                                                        .then(Commands.argument("amplifier", IntegerArgumentType.integer(0, 255))
                                                                .executes(ctx -> effect(
                                                                        ctx,
                                                                        ResourceLocationArgument.getId(ctx, "type"),
                                                                        ResourceArgument.getMobEffect(ctx, "effect"),
                                                                        IntegerArgumentType.getInteger(ctx, "seconds"),
                                                                        IntegerArgumentType.getInteger(ctx, "amplifier"),
                                                                        true))
                                                                .then(Commands.argument("hideParticles", BoolArgumentType.bool())
                                                                        .executes(ctx -> effect(
                                                                                ctx,
                                                                                ResourceLocationArgument.getId(ctx, "type"),
                                                                                ResourceArgument.getMobEffect(ctx, "effect"),
                                                                                IntegerArgumentType.getInteger(ctx, "seconds"),
                                                                                IntegerArgumentType.getInteger(ctx, "amplifier"),
                                                                                !BoolArgumentType.getBool(ctx, "hideParticles"))))))))
                                .then(Commands.literal("tp")
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .executes(ctx -> tp(
                                                        ctx,
                                                        ResourceLocationArgument.getId(ctx, "type"),
                                                        Vec3Argument.getVec3(ctx, "pos"),
                                                        null, null))
                                                .then(Commands.argument("yaw", FloatArgumentType.floatArg(-180.0F, 180.0F))
                                                        // 只给 yaw 也成立：pitch 省略 ⇒ 沿用当前俯仰。
                                                        .executes(ctx -> tp(
                                                                ctx,
                                                                ResourceLocationArgument.getId(ctx, "type"),
                                                                Vec3Argument.getVec3(ctx, "pos"),
                                                                FloatArgumentType.getFloat(ctx, "yaw"),
                                                                null))
                                                        .then(Commands.argument("pitch", FloatArgumentType.floatArg(-90.0F, 90.0F))
                                                                .executes(ctx -> tp(
                                                                        ctx,
                                                                        ResourceLocationArgument.getId(ctx, "type"),
                                                                        Vec3Argument.getVec3(ctx, "pos"),
                                                                        FloatArgumentType.getFloat(ctx, "yaw"),
                                                                        FloatArgumentType.getFloat(ctx, "pitch"))))))))));
    }

    // ===================== 各子命令 =====================

    /** 指派路线。校验顺序照 {@code NpcCommand#setRoute}：先查路线是否存在，再写。 */
    private static int route(CommandContext<CommandSourceStack> ctx, ResourceLocation typeId, ResourceLocation routeId) {
        CommandSourceStack source = ctx.getSource();
        NpcEntity npc = resolveTarget(source, typeId);
        if (npc == null) {
            return 0;
        }
        NpcRoute route = NpcRouteLoader.INSTANCE.get(routeId);
        if (route == null) {
            source.sendFailure(Component.translatable("beloong.command.route.unknown",
                    routeId.toString(), String.join(", ", NpcRouteLoader.INSTANCE.nameStrings())));
            return 0;
        }
        npc.setRoute(routeId);
        RouteCommand.warnDimensionMismatch(npc, routeId, route);   // 与另两处共用一份，避免第三份重复实现
        source.sendSuccess(() -> Component.translatable(
                "beloong.command.route.set", 1, routeId.toString()), true);
        return 1;
    }

    /** 播放表情。名字不校验（动画名是客户端资产，服务端无从知道）—— 与 {@code NpcCommand#play} 同口径。 */
    private static int play(CommandContext<CommandSourceStack> ctx, ResourceLocation typeId, String animation) {
        CommandSourceStack source = ctx.getSource();
        NpcEntity npc = resolveTarget(source, typeId);
        if (npc == null) {
            return 0;
        }
        npc.setEmote(animation);
        source.sendSuccess(() -> Component.translatable(
                "beloong.command.npc.play", 1, animation), true);
        return 1;
    }

    /** 停止表情。**幂等**：本来就没有表情时也报成功（与 {@code NpcCommand} 的三条 stop 同口径）。 */
    private static int stopEmote(CommandContext<CommandSourceStack> ctx, ResourceLocation typeId) {
        CommandSourceStack source = ctx.getSource();
        NpcEntity npc = resolveTarget(source, typeId);
        if (npc == null) {
            return 0;
        }
        npc.clearEmote();
        source.sendSuccess(() -> Component.translatable("beloong.command.npc.play_stop", 1), true);
        return 1;
    }

    /**
     * 施加药水效果。
     * <p>
     * 时长换算**照抄原版** {@code EffectCommands#giveEffect}：瞬时效果直接用秒数，其余 {@code seconds * 20}；
     * 省略 {@code seconds} 时非瞬时给 600 tick（30 秒）、瞬时给 1 tick。
     * <p>
     * ⚠️ 不传 {@code infinite}：本族只接受正数秒数（见类注释"刻意没做的"）。
     */
    private static int effect(CommandContext<CommandSourceStack> ctx, ResourceLocation typeId,
                              Holder<MobEffect> effect, @Nullable Integer seconds, int amplifier,
                              boolean showParticles) {
        CommandSourceStack source = ctx.getSource();
        NpcEntity npc = resolveTarget(source, typeId);
        if (npc == null) {
            return 0;
        }
        MobEffect mobEffect = effect.value();
        int duration;
        if (seconds != null) {
            duration = mobEffect.isInstantenous() ? seconds : seconds * 20;
        } else {
            duration = mobEffect.isInstantenous() ? 1 : 600;
        }
        // 与 /effect give 同口径：目标身上已有更强的同类效果时 addEffect 返回 false ⇒ 报失败，不谎报成功。
        if (!npc.addEffect(new MobEffectInstance(effect, duration, amplifier, false, showParticles),
                source.getEntity())) {
            source.sendFailure(Component.translatable("beloong.command.mynpc.effect_failed",
                    npc.getDisplayName(), mobEffect.getDisplayName()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("beloong.command.mynpc.effect",
                npc.getDisplayName(), mobEffect.getDisplayName(), Math.max(0, duration / 20)), true);
        return 1;
    }

    /**
     * 瞬移（**仅当前维度**）。
     * <p>
     * ⚠️ 三个细节都不能省：
     * <ol>
     *   <li>{@code stopMoving()} 清掉移动指令 —— 否则 {@code tickMoveCommand} 会继续往旧目标寻路，
     *       看起来就是"传过去又自己走回来"；</li>
     *   <li>{@code getNavigation().stop()} 停掉**已经算好正在执行**的那条路径 ——
     *       清移动目标不会撤销已经在走的路径；</li>
     *   <li>用 {@link Entity#moveTo(double, double, double, float, float)}（定位 + 转向），
     *       <b>不是</b> {@link NpcEntity#moveTo(Vec3)}（那是"登记移动目标"，同名不同义）。</li>
     * </ol>
     * 朝向省略时沿用当前朝向（不强制看向某处）。
     */
    private static int tp(CommandContext<CommandSourceStack> ctx, ResourceLocation typeId, Vec3 pos,
                          @Nullable Float yaw, @Nullable Float pitch) {
        CommandSourceStack source = ctx.getSource();
        NpcEntity npc = resolveTarget(source, typeId);
        if (npc == null) {
            return 0;
        }
        float y = yaw != null ? yaw : npc.getYRot();
        float p = pitch != null ? pitch : npc.getXRot();
        npc.stopMoving();
        npc.getNavigation().stop();
        npc.moveTo(pos.x, pos.y, pos.z, y, p);
        npc.setYHeadRot(y);
        source.sendSuccess(() -> Component.translatable("beloong.command.mynpc.tp",
                npc.getDisplayName(), pos.x, pos.y, pos.z), true);
        return 1;
    }

    // ===================== 目标解析（本族的核心）=====================

    /**
     * 解析"执行者自己那只该类型 NPC"。
     * <p>
     * 规则：{@code type == X ∧ BeloongOwner == 执行者 UUID}，只查执行者**当前维度**
     * （命令都是由玩家在 NPC 旁边触发的；跨维度找不到就明确报错，不做跨维度传送）。
     * <p>
     * 命中多只（异常：重复分身，正常会被对账器收敛掉）⇒ 取 {@code bornAt} 最大者并留一条 WARN ——
     * 与对账器"重复分身保留最近出生的"是**同一个取舍**，免得命令动的那只和留下来的那只不是同一只。
     *
     * @return 目标；解析不出时**已经报错**并返回 {@code null}（调用方直接 return 0）
     */
    @Nullable
    private static NpcEntity resolveTarget(CommandSourceStack source, ResourceLocation typeId) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            // 命令方块/控制台没有"该玩家" ⇒ 明确报错，不猜目标。
            source.sendFailure(Component.translatable("beloong.command.mynpc.not_a_player"));
            return null;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(typeId).orElse(null);
        if (type == null) {
            source.sendFailure(Component.translatable("beloong.command.mynpc.unknown_type",
                    typeId.toString(), availableTypes()));
            return null;
        }

        List<NpcEntity> mine = new ArrayList<>();
        for (Entity entity : player.serverLevel().getAllEntities()) {
            if (entity.getType() == type && entity instanceof NpcEntity npc
                    && player.getUUID().equals(npc.owner())) {
                mine.add(npc);
            }
        }
        if (mine.isEmpty()) {
            // 这里**必须**报错：退化成"最近的那只"正是本族要消灭的行为。
            source.sendFailure(Component.translatable("beloong.command.mynpc.no_owned_npc",
                    typeId.toString(), player.serverLevel().dimension().location().toString()));
            return null;
        }
        if (mine.size() > 1) {
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] player '{}' owns {} '{}' npcs — picking the newest for this command",
                    player.getGameProfile().getName(), mine.size(), typeId);
            // ⚠️ 必须与对账器**逐字同一取舍**（NpcStoryHandler 的重复分身处理）：
            // bornAt 优先，并列（旧实体的 0、或同 tick 生成）时取**离玩家最近**的那个 ——
            // 否则命令动的那只可能正是对账器马上要删掉的那只，而类注释还宣称"同一个取舍"。
            mine.sort(Comparator
                    .comparingLong(NpcEntity::bornAt).reversed()
                    .thenComparingDouble(player::distanceToSqr));
        }
        return mine.get(0);
    }

    /** 已接剧情的实体类型清单（错误提示用）。 */
    private static String availableTypes() {
        List<String> names = new ArrayList<>();
        NpcStoryLoader.INSTANCE.all().keySet().stream()
                .map(BuiltInRegistries.ENTITY_TYPE::getKey)
                .filter(Objects::nonNull)
                .map(ResourceLocation::toString)
                .sorted()
                .forEach(names::add);
        return names.isEmpty() ? "(none)" : String.join(", ", names);
    }
}
