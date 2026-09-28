package com.zonlong.beloong.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.zonlong.beloong.entity.NpcEntity;
import com.zonlong.beloong.entity.NpcState;
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
 * <h2>指令面：三条正交的轴 + 一条全清（2026-09-27 重构）</h2>
 * <pre>
 *   state  &lt;targets&gt; &lt;state&gt;     ← idle | flying | sitting | dancing | …（可扩展）
 *   move   &lt;targets&gt; &lt;pos&gt;       ← 移动。走法（地面/空中）由当前状态决定
 *   stop   &lt;targets&gt;             ← 停止寻路（move 的反面）
 *   attack &lt;targets&gt; &lt;victim&gt;    ← 攻击
 *   attack &lt;targets&gt; stop        ← 停止攻击
 *   reset  &lt;targets&gt;             ← 回到"刚被召唤出来的样子"
 * </pre>
 * <b>对称是刻意的</b>：{@code move ↔ stop}、{@code attack <victim> ↔ attack stop}。
 * 两条 {@code stop} 语义完全一致 —— <b>只取消各自轴上的指令，绝不碰状态</b>。
 * 于是"地面停下就是站桩待机、空中停下就是原地悬停"是"状态没变 + 动作没了"的**自然结果**，
 * <b>不需要为它们写任何特判</b>。
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
 * ⚠️ <b>{@code attack <targets> stop} 里 {@code stop} 落在"本该是实体参数"的位置</b>，
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

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("beloong")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("npc")
                        .then(Commands.literal("state")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .then(Commands.argument("state", StringArgumentType.word())
                                                .suggests(STATE_SUGGESTIONS)
                                                .executes(ctx -> setState(
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        StringArgumentType.getString(ctx, "state"),
                                                        ctx.getSource())))))
                        .then(Commands.literal("move")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .executes(ctx -> move(
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        Vec3Argument.getVec3(ctx, "pos"),
                                                        ctx.getSource())))))
                        .then(Commands.literal("stop")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(ctx -> stopMoving(
                                                EntityArgument.getEntities(ctx, "targets"),
                                                ctx.getSource()))))
                        .then(Commands.literal("attack")
                                .then(Commands.argument("targets", EntityArgument.entities())
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
                                                        ctx.getSource())))))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("targets", EntityArgument.entities())
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

    /** 停止移动 —— 只停寻路，不改状态、不停攻击（{@code move} 的反面）。 */
    private static int stopMoving(Collection<? extends Entity> targets, CommandSourceStack source) {
        List<NpcEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        for (NpcEntity npc : npcs) {
            npc.stopMoving();
        }
        source.sendSuccess(() -> Component.translatable("beloong.command.npc.stop", npcs.size()), true);
        return npcs.size();
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

    /** 恢复到默认状态：状态→待机、无移动、无攻击（等于刚被召唤出来的样子）。 */
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
