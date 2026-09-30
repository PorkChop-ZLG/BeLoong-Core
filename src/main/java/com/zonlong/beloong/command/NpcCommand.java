package com.zonlong.beloong.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.route.NpcRoute;
import com.zonlong.beloong.route.NpcRouteLoader;
import net.minecraft.resources.ResourceLocation;
import com.zonlong.beloong.entity.NpcEntity;
import com.zonlong.beloong.entity.NpcState;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 通用 NPC 的调试 / 摆位命令。
 * <p>
 * <b>定位：验收与手动摆位工具，不是玩法内容。</b>{@link NpcEntity} 的能力是
 * <b>纯 API、默认没有任何调用方</b>，没有这些命令就无法被当场验证。
 * 将来不需要了，删本类 + {@code BeLoongCore} 里一行注册即可。
 * <p>
 * 只调实体 API，**不碰实体字段**；目标过滤 {@link NpcEntity}，因此对本模组**所有** NPC 生效。
 * op 级（{@code hasPermission(2)}）：它改的是世界里的实体。
 *
 * <h2>指令面：四条轴 + 一条全清（2026-09-29 增补「表情」轴）</h2>
 * <b>目标一律在 {@code npc} 之后、动作之前</b>（2026-09-27 统一格式，见 {@link #register}）：
 * <pre>
 *   /beloong npc &lt;targets&gt; state &lt;state&gt;     ← idle | flying（可扩展）
 *   /beloong npc &lt;targets&gt; move &lt;pos&gt;        ← 移动。走法（地面/空中）由当前状态决定
 *   /beloong npc &lt;targets&gt; stop              ← 停止寻路（move 的反面）
 *   /beloong npc &lt;targets&gt; attack &lt;victim&gt;   ← 攻击
 *   /beloong npc &lt;targets&gt; attack stop        ← 停止攻击
 *   /beloong npc &lt;targets&gt; play &lt;animation&gt;  ← 播放表情（任意动画名，覆盖状态动画）
 *   /beloong npc &lt;targets&gt; play stop          ← 停止表情
 *   /beloong npc &lt;targets&gt; reset              ← 回到"刚被召唤出来的样子"（含清表情）
 * </pre>
 * <b>对称是刻意的</b>：{@code move ↔ stop}、{@code attack [victim] ↔ attack stop}、
 * {@code play [animation] ↔ play stop}。
 * 三条 {@code stop} 语义完全一致 —— <b>只取消各自轴上的指令，绝不碰别的轴</b>。
 * <p>
 * ⚠️ <b>但"清表情"是刻意的例外</b>：{@code state} / {@code move} / {@code attack} / {@code reset}
 * 都会顺带清掉表情（用户 2026-09-29 裁定：表情整层盖住状态动画，玩家分不清指令是否生效）。
 * {@code stop}（停移动）**不清** —— 它只是 {@code move} 的反面。
 * 于是"地面停下就是站桩待机、空中停下就是原地悬停"是"状态没变 + 动作没了"的**自然结果**，
 * <b>不需要为它们写任何特判</b>。
 * <p>
 * ⚠️ <b>{@code play} 的参数刻意没有补全</b>：动画名是<b>客户端</b>的资产数据，服务端不知道有哪些名字，
 * 无法像 {@code state} 那样从枚举列候选（对照 {@link #STATE_SUGGESTIONS}）。
 * 拼错时客户端预检会静默不播（设计 D4），而指令本身仍报成功 —— 这是明确接受的代价。
 * <p>
 * <b>被删除的旧指令</b>：{@code walk}（→ {@code move}）、{@code fly on|off}
 * （→ {@code state … flying} / {@code state … idle}）、{@code fly to}（→ {@code move}）。
 * 合并的理由：用户不需要记"现在该用 walk 还是 fly" —— 同一条 {@code move}
 * 在地面是"走"、在飞行是"飞"。
 * <p>
 * <b>刻意没有的子命令</b>（用户裁定）：
 * <ul>
 *   <li>{@code run} —— 奔跑状态已取消，速度差异改由移速属性的加成体现
 *       （见 {@link NpcEntity#registerControllers()}）；</li>
 *   <li>{@code turn} —— 效果不好（站桩转向依赖"头带身体"的原版链，玩家在场时还会被让位），
 *       而"旋转"本身可以直接从行为上看出来，不需要指令演示。</li>
 * </ul>
 * <p>
 * ⚠️ <b>{@code npc <targets> attack stop} 里 {@code stop} 落在"本该是实体参数"的位置</b>，
 * Brigadier 会同时尝试"字面量 {@code stop}"与"一个名叫 {@code stop} 的实体"。
 * <b>这是原版接受的形状</b>（{@code /tag <targets> add|remove|list} 是同一构造，
 * {@code add} 也落在 targets 之后），实际风险只在"真有玩家/实体叫 stop"时可忽略。
 */
public final class NpcCommand {

    private NpcCommand() {}

    /**
     * {@code state} 参数的补全：列出所有状态名。
     * <p>
     * 用 {@code StringArgumentType.word()} + 补全，而<b>不是</b>为每个状态写一个
     * {@code Commands.literal(...)} —— 后者每加一个状态都要改指令树，
     * 而枚举加一个常量时这里自动跟上（见 {@link NpcState} 的类注释）。
     */
    private static final SuggestionProvider<CommandSourceStack> STATE_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(NpcState.NAMES, builder);

    /**
     * {@code route} 参数的补全：列出已加载的全部路线名。
     * <p>
     * 与 {@code state} 同一取舍：用 {@code word()} + 补全，而不是一条路线一个 literal
     * （路线是**数据**，随时可能增删 —— 照 {@code CgCommand} 的形态）。
     */
    private static final SuggestionProvider<CommandSourceStack> ROUTE_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    NpcRouteLoader.INSTANCE.nameStrings(), builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("beloong")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("npc")
                        // ⚠️ <b>目标一律紧跟在 npc 之后、动作之前</b>（2026-09-27 统一）。
                        // 于是"选择谁"与"让它做什么"在指令里各占固定位置，读法一致：
                        //     /beloong npc @e state flying
                        //     /beloong npc Mo attack @e[type=zombie]
                        //     /beloong npc @e move 100 70 100
                        // 不必每条子命令各记一遍参数顺序（旧格式是"动作在前、目标在后"）。
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.literal("state")
                                        .then(Commands.argument("state", StringArgumentType.word())
                                                .suggests(STATE_SUGGESTIONS)
                                                .executes(ctx -> setState(
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        StringArgumentType.getString(ctx, "state"),
                                                        ctx.getSource()))))
                                .then(Commands.literal("move")
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .executes(ctx -> move(
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        Vec3Argument.getVec3(ctx, "pos"),
                                                        ctx.getSource()))))
                                .then(Commands.literal("stop")
                                        .executes(ctx -> stopMoving(
                                                EntityArgument.getEntities(ctx, "targets"),
                                                ctx.getSource())))
                                .then(Commands.literal("attack")
                                        // 顺序有讲究：先接实体参数，再接 stop 字面量。
                                        // 两者落在同一 token 位置 ⇒ 见类注释里的歧义说明。
                                        .then(Commands.argument("victim", EntityArgument.entity())
                                                .executes(ctx -> attack(
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        EntityArgument.getEntity(ctx, "victim"),
                                                        ctx.getSource())))
                                        .then(Commands.literal("stop")
                                                .executes(ctx -> stopAttacking(
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        ctx.getSource()))))
                                .then(Commands.literal("play")
                                        // 与 attack 同形：先接参数（动画名），再接 stop 字面量。
                                        // 两者落在同一 token 位置 ⇒ 歧义说明见类注释。
                                        .then(Commands.argument("animation", StringArgumentType.word())
                                                .executes(ctx -> play(
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        StringArgumentType.getString(ctx, "animation"),
                                                        ctx.getSource())))
                                        .then(Commands.literal("stop")
                                                .executes(ctx -> stopEmote(
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        ctx.getSource()))))
                                .then(Commands.literal("route")
                                        // 带参数 = 指派；不带 = 回报每只 NPC 当前的路线（验收与排查靠它）。
                                        //
                                        // ⚠️ 参数类型**必须**是 ResourceLocationArgument，不能用
                                        // StringArgumentType.word()：路线名形如 beloong:mo_route_1，
                                        // 而 word() 的允许字符集**不含冒号**（vanilla 的
                                        // StringReader.isAllowedInUnquotedString）⇒ 补全能补出来、
                                        // 一敲回车就变红、ChatBox 里执行则直接语法错 —— 2026-09-30 实机踩到。
                                        .then(Commands.argument("route", ResourceLocationArgument.id())
                                                .suggests(ROUTE_SUGGESTIONS)
                                                .executes(ctx -> setRoute(
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        ResourceLocationArgument.getId(ctx, "route"),
                                                        ctx.getSource())))
                                        .executes(ctx -> queryRoute(
                                                EntityArgument.getEntities(ctx, "targets"),
                                                ctx.getSource())))
                                .then(Commands.literal("reset")
                                        .executes(ctx -> reset(
                                                EntityArgument.getEntities(ctx, "targets"),
                                                ctx.getSource()))))));
    }

    /**
     * 切换状态。状态名可补全。
     * <p>
     * ⚠️ <b>状态名拼错必须报错，不能静默回落。</b>
     * 读存档时对不上的名字要回落 {@link NpcState#IDLE}（否则坏档），
     * 但<b>指令这边是相反的</b>：玩家把 {@code flying} 敲成 {@code fliing} 时若静默变成"待机"，
     * 他会以为命令成功了。两套入口见 {@link NpcState#byNameStrict} / {@link NpcState#byNameLenient}。
     */
    private static int setState(Collection<? extends Entity> targets, String rawState, CommandSourceStack source) {
        List<NpcEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        Optional<NpcState> parsed = NpcState.byNameStrict(rawState);
        if (parsed.isEmpty()) {
            source.sendFailure(Component.translatable(
                    "beloong.command.npc.state.unknown", rawState, String.join(", ", NpcState.NAMES)));
            return 0;
        }
        NpcState state = parsed.get();
        for (NpcEntity npc : npcs) {
            npc.setState(state);
        }
        source.sendSuccess(() -> Component.translatable(
                "beloong.command.npc.state",
                npcs.size(),
                Component.translatable("beloong.npc.state." + state.getSerializedName())), true);
        return npcs.size();
    }

    /**
     * 移动到目标点。
     * <p>
     * <b>命令层不判断"该怎么走"</b> —— 地面走还是空中飞由该 NPC 的**当前状态**决定
     * （见 {@code NpcEntity#moveTo}）。处于姿态时会先隐式退出姿态，那也在实体侧做。
     */
    private static int move(Collection<? extends Entity> targets, Vec3 pos, CommandSourceStack source) {
        List<NpcEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        for (NpcEntity npc : npcs) {
            npc.moveTo(pos);
        }
        source.sendSuccess(() -> Component.translatable(
                "beloong.command.npc.move", npcs.size(), pos.toString()), true);
        return npcs.size();
    }

    /**
     * 停止移动 —— <b>并且清掉路线</b>（用户裁定 D6：{@code stop} 是"终止"，不是"暂停"）。
     * <p>
     * 不改状态、不停攻击（{@code move} 的反面）。⚠️ 清路线在这里、而**不在**
     * {@code NpcEntity#stopMoving()} 里 —— 因为 {@code attack()} 会调
     * {@code clearMotionCommands()}，而"攻击不该毁掉路线"是刻意的（D7）。
     */
    private static int stopMoving(Collection<? extends Entity> targets, CommandSourceStack source) {
        List<NpcEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        for (NpcEntity npc : npcs) {
            npc.stopMoving();
            npc.clearRoute();
        }
        source.sendSuccess(() -> Component.translatable("beloong.command.npc.stop", npcs.size()), true);
        return npcs.size();
    }

    /**
     * 指派路线（手工/验收用）—— 与 {@code /beloong route <名>} 的区别只是"目标由参数给定"。
     * <p>
     * 校验顺序刻意是"<b>先查路线是否存在</b>，再写"：路线名拼错时**拒绝**并列出可用名单，
     * 而不是静默写一个永远不会生效的名字（照 {@code CgCommand} 的口径：逐条报错、不静默）。
     */
    private static int setRoute(Collection<? extends Entity> targets, ResourceLocation id,
                                CommandSourceStack source) {
        List<NpcEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        // 参数已经是 ResourceLocation（由指令层解析）⇒ 这里只需查它是否真的存在。
        if (NpcRouteLoader.INSTANCE.get(id) == null) {
            source.sendFailure(Component.translatable("beloong.command.route.unknown",
                    id.toString(), String.join(", ", NpcRouteLoader.INSTANCE.nameStrings())));
            return 0;
        }
        for (NpcEntity npc : npcs) {
            npc.setRoute(id);
            warnDimensionMismatch(npc, id, source);
        }
        source.sendSuccess(() -> Component.translatable(
                "beloong.command.route.set", npcs.size(), id.toString()), true);
        return npcs.size();
    }

    /**
     * 回报每只 NPC 当前的路线（验收与排查都靠它）。
     * <p>
     * 用 {@code sendSuccess} 逐条发（而不是拼成一句）：一只 NPC 一行，读起来与
     * {@code routeIndex} 一一对应；没有路线时用另一条键，明确说"无"而不是留空。
     */
    private static int queryRoute(Collection<? extends Entity> targets, CommandSourceStack source) {
        List<NpcEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        for (NpcEntity npc : npcs) {
            ResourceLocation id = npc.routeName();
            if (id == null) {
                source.sendSuccess(() -> Component.translatable(
                        "beloong.command.route.query_none", npc.getDisplayName()), false);
                continue;
            }
            NpcRoute route = npc.route();
            int total = route == null ? 0 : route.size();
            source.sendSuccess(() -> Component.translatable(
                    "beloong.command.route.query", npc.getDisplayName(), id.toString(),
                    npc.routeIndex(), total), false);
        }
        return npcs.size();
    }

    /**
     * 路线维度与 NPC 当前维度不一致时，照写但**必须留一条 WARN**。
     * <p>
     * 挂起本身是刻意的行为（用户裁定 D4）；但若不打日志，作者看到的症状是
     * "对话演完了、NPC 一动不动、日志里什么都没有" —— 那是最难排查的一类失败。
     */
    private static void warnDimensionMismatch(NpcEntity npc, ResourceLocation id, CommandSourceStack source) {
        NpcRoute route = NpcRouteLoader.INSTANCE.get(id);
        if (route == null) {
            return;
        }
        if (!npc.level().dimension().location().equals(route.dimension())) {
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] npc '{}' was assigned route '{}' but is in {} (route expects {}) — suspended until it returns",
                    npc.getUUID(), id, npc.level().dimension().location(), route.dimension());
        }
    }

    /** 下令攻击某个目标。 */
    private static int attack(Collection<? extends Entity> targets, Entity victim, CommandSourceStack source) {
        List<NpcEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        if (!(victim instanceof LivingEntity living)) {
            source.sendFailure(Component.translatable("beloong.command.npc.not_living"));
            return 0;
        }
        for (NpcEntity npc : npcs) {
            npc.attack(living);
        }
        source.sendSuccess(() -> Component.translatable(
                "beloong.command.npc.attack", npcs.size(), living.getDisplayName()), true);
        return npcs.size();
    }

    /**
     * 停止攻击 —— 只清攻击，不改状态、不停移动（{@code attack} 的反面）。
     * <p>
     * 于是"地面攻击后停下就回站桩待机、空中攻击后停下就回悬停"同样是自然结果。
     */
    private static int stopAttacking(Collection<? extends Entity> targets, CommandSourceStack source) {
        List<NpcEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        for (NpcEntity npc : npcs) {
            npc.stopAttacking();
        }
        source.sendSuccess(() -> Component.translatable("beloong.command.npc.attack_stop", npcs.size()), true);
        return npcs.size();
    }

    /**
     * 播放一个表情。
     * <p>
     * <b>不校验名字，也不声称该动画存在</b>：动画名是客户端的资产数据，服务端无从知道
     * （见类注释里 {@code play} 那条警告）。客户端会在真正播放前预检。
     * <p>
     * ⚠️ <b>表情不是正交的</b>（2026-09-29 用户裁定）：它只是一层动画覆盖、不取消任何指令，
     * 但<b>会被</b> {@code state} / {@code move} / {@code attack} / {@code reset} 清掉。
     * 能改变它的入口：{@code play} 换名、{@code play stop}、{@code state}、{@code move}、
     * {@code attack}、{@code reset}，以及<b>一次挥砍</b>（服务端那次 `swing` 也会清）。
     */
    private static int play(Collection<? extends Entity> targets, String animation, CommandSourceStack source) {
        List<NpcEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        for (NpcEntity npc : npcs) {
            npc.setEmote(animation);
        }
        source.sendSuccess(() -> Component.translatable(
                "beloong.command.npc.play", npcs.size(), animation), true);
        return npcs.size();
    }

    /**
     * 停止表情（{@code play} 的反面）。
     * <p>
     * <b>幂等</b>：本来就没有表情时也报成功 —— 与 {@link #stopAttacking} / {@link #stopMoving}
     * 的口径一致，三条 {@code stop} 都不做"有没有东西可停"的判断。
     */
    private static int stopEmote(Collection<? extends Entity> targets, CommandSourceStack source) {
        List<NpcEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        for (NpcEntity npc : npcs) {
            npc.clearEmote();
        }
        source.sendSuccess(() -> Component.translatable(
                "beloong.command.npc.play_stop", npcs.size()), true);
        return npcs.size();
    }

    /** 恢复到默认状态：状态→待机、无移动、无攻击、**无表情**（等于刚被召唤出来的样子）。 */
    private static int reset(Collection<? extends Entity> targets, CommandSourceStack source) {
        List<NpcEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        for (NpcEntity npc : npcs) {
            npc.resetToDefault();
        }
        source.sendSuccess(() -> Component.translatable(
                "beloong.command.npc.reset", npcs.size()), true);
        return npcs.size();
    }

    /** 选到的不是 NPC 时明确报错，不静默。 */
    private static int fail(CommandSourceStack source) {
        source.sendFailure(Component.translatable("beloong.command.npc.no_targets"));
        return 0;
    }

    private static List<NpcEntity> npcsIn(Collection<? extends Entity> targets) {
        return targets.stream()
                .filter(NpcEntity.class::isInstance)
                .map(NpcEntity.class::cast)
                .toList();
    }
}
