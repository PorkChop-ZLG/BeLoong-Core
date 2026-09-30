package com.zonlong.beloong.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.zonlong.beloong.cg.CgAnimation;
import com.zonlong.beloong.cg.CgRegistry;
import com.zonlong.beloong.entity.NpcEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Optional;

/**
 * 过场动画（CG）的播放命令。
 *
 * <pre>
 *   /beloong cg &lt;target&gt; play &lt;name&gt;
 * </pre>
 * 其中 {@code target} **只能是单个实体**（用户 2026-09-30 要求），用
 * {@link EntityArgument#entity()} 而非 {@code entities()}；{@code name} 是
 * {@link CgRegistry} 里的 CG 名（如 {@code mo_entrance}）。
 *
 * <h2>刻意没有的子命令（用户 2026-09-30 裁定）</h2>
 * <b>{@code stop}</b>。虽然本项目对指令面有一条"对称是刻意的"口径
 * （见 {@link NpcCommand} 的类注释：{@code move ↔ stop}、{@code attack ↔ stop}、{@code play ↔ stop}），
 * 但用户明确要求**严格按给定签名**，中途中断交给 fdlib 自带的
 * {@code /fdlib fix cutscene}（它内部就是 {@code FDLibCalls.stopCutsceneForPlayer}）。
 * <p>
 * 顺带一提：CG **不需要**停止命令来收尾 —— {@code StopMode.AUTOMATIC} 会让 fdlib 在总时长走满时
 * 自行归还相机；{@code stop} 只是"想提前打断"时才需要。
 *
 * <h2>⚠️ 为什么本类可以再注册一个 {@code literal("beloong")}</h2>
 * {@link NpcCommand} 已经注册过同一个根字面量。这不是笔误 —— Brigadier 会**按下标名合并子树**，
 * 已核实到源码（依赖 {@code com.mojang:brigadier:1.3.10}，sources jar 里的
 * {@code com/mojang/brigadier/tree/CommandNode.java} 的 {@code addChild}）：
 * <pre>
 *   final CommandNode&lt;S&gt; child = children.get(node.getName());
 *   if (child != null) {
 *       // We've found something to merge onto
 *       if (node.getCommand() != null) child.command = node.getCommand();
 *       for (final CommandNode&lt;S&gt; grandchild : node.getChildren()) child.addChild(grandchild);
 *   } else { children.put(...); }
 * </pre>
 * 于是第二次注册的 {@code cg} 子节点被并进已有的 {@code beloong} 节点，与 {@code npc} 并列。
 * <p>
 * ⚠️ <b>但有一个不显眼的后果</b>：合并只带走新节点的 {@code command} 与 {@code children}，
 * <b>不带走它的 {@code requires} 谓词</b> ⇒ 生效的是**先注册那个**的 {@code requires}。
 * 本类与 {@link NpcCommand} 都写 {@code hasPermission(2)}，所以没有行为差异；
 * 将来若有人只改其中一处的权限等级，**改第二个是无效的** —— 这条值得留在这里。
 *
 * <p>设计文档：{@code docs/plans/2026-09-30-cg-system-design.md}（§2 组件 / §5 错误处理）。
 */
public final class CgCommand {

    /**
     * {@code name} 参数的补全：列出全部 CG 名。
     * <p>
     * 用 {@code StringArgumentType.word()} + 补全，而**不是**为每条 CG 写一个
     * {@code Commands.literal(...)} —— 后者每加一条 CG 都要改指令树，
     * 而 {@link CgRegistry} 里加一行时这里自动跟上（同 {@code NpcCommand.STATE_SUGGESTIONS} 的理由）。
     */
    private static final SuggestionProvider<CommandSourceStack> CG_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(CgRegistry.names(), builder);

    private CgCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("beloong")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("cg")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .then(Commands.literal("play")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .suggests(CG_SUGGESTIONS)
                                                .executes(ctx -> play(
                                                        ctx.getSource(),
                                                        EntityArgument.getEntity(ctx, "target"),
                                                        StringArgumentType.getString(ctx, "name"))))))));
    }

    /**
     * 播放一条 CG。
     * <p>
     * 三条前置校验各自给一句明确的中文反馈，**都不静默**（同 {@code NpcCommand.fail()} 的口径）：
     * <ol>
     *   <li><b>必须由玩家执行</b> —— 相机接管的是玩家的视角，命令方块/控制台没有意义。
     *       这里<b>不自己写反馈文案</b>，直接让 {@link CommandSourceStack#getPlayerOrException()} 抛
     *       Brigadier 的内建异常 ⇒ 由原版给出标准的"仅玩家可用"提示（省一条语言键，且与其他模组一致）。</li>
     *   <li><b>CG 名必须存在</b> —— fail-closed，附上可用名列表。</li>
     *   <li><b>目标必须是 {@link NpcEntity}</b> —— 只有它有表情系统（{@code setEmote}）。</li>
     * </ol>
     * 第 4 关（{@code play} 内部的四条预检）由 {@link CgAnimation#play} 负责，
     * 它返回 0 时这里只给一句通用失败提示，具体原因在日志里（英文）。
     */
    private static int play(CommandSourceStack source, Entity target, String name) throws CommandSyntaxException {

        // ① 必须由玩家执行（非玩家时抛原版内建异常，由原版给提示）
        ServerPlayer viewer = source.getPlayerOrException();

        // ② CG 名必须存在
        Optional<CgAnimation> found = CgRegistry.byName(name);
        if (found.isEmpty()) {
            source.sendFailure(Component.translatable(
                    "beloong.command.cg.unknown", name, String.join(", ", CgRegistry.names())));
            return 0;
        }
        CgAnimation cg = found.get();

        // ③ 目标必须是本模组的 NPC（表情系统在 NpcEntity 上）
        if (!(target instanceof NpcEntity npc)) {
            source.sendFailure(Component.translatable(
                    "beloong.command.cg.not_npc", target.getDisplayName()));
            return 0;
        }

        // ④ 交给 CG 自己 —— 它内部的预检失败时返回 0，原因已在日志里
        if (cg.play(viewer, npc) == 0) {
            source.sendFailure(Component.translatable("beloong.command.cg.failed", cg.name()));
            return 0;
        }

        // 参数顺序刻意是 (CG 名, 目标) —— 中英文案共用同一顺序，不必用 %2$s 这类显式索引
        // （与 NpcCommand 的既有文案保持同一风格）。
        source.sendSuccess(() -> Component.translatable(
                "beloong.command.cg.play", cg.name(), npc.getDisplayName()), true);
        return 1;
    }
}
