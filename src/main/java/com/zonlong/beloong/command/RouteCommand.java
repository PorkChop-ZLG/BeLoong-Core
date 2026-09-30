package com.zonlong.beloong.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.dialogue.LastDialogueNpc;
import com.zonlong.beloong.entity.NpcEntity;
import com.zonlong.beloong.route.NpcRoute;
import com.zonlong.beloong.route.NpcRouteLoader;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * {@code /beloong route <路线名>} —— <b>由对话触发的</b>路线指派。
 * <p>
 * 为什么它不是 {@code /beloong npc <targets> route <名>} 的一条捷径，而是**另一条命令**：
 * 既有的指令形状是 {@code npc} 之后第一个节点就是**实体参数**，若把 {@code route} 放在同一位置，
 * Brigadier 会把它当成"一个名叫 route 的实体"（同名歧义，见 {@code NpcCommand} 的类注释）；
 * 而 {@code /beloong npc route <名>} 更是直接解析失败。故必须另起一条路径。
 * <p>
 * <b>它是以玩家身份执行的命令</b>（ChatBox 的选项 {@code click} 就是那样执行的，权限等级 2），
 * 目标由「该玩家最近对话过的 NPC」决定 —— 见 {@link LastDialogueNpc}。因此在命令方块/控制台里
 * 执行会**明确报错**（没有"该玩家"这回事），而不是随便挑一只 NPC 写上去。
 *
 * <h2>与 {@code NpcCommand} 共享 {@code beloong} 根字面量</h2>
 * Brigadier 会按下标名合并两棵子树（{@code CommandNode.addChild}），所以两处都能注册；
 * ⚠️ 但合并**不带走**后注册者的 {@code requires} 谓词 ⇒ 两处必须写同样的权限等级（都是 2，这里已一致）。
 */
public final class RouteCommand {

    private RouteCommand() {}

    private static final SuggestionProvider<CommandSourceStack> ROUTE_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    NpcRouteLoader.INSTANCE.nameStrings(), builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("beloong")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("route")
                        // ⚠️ 必须用 ResourceLocationArgument：路线名形如 beloong:mo_route_1，
                        // 而 StringArgumentType.word() 的字符集不含冒号 ⇒ 补全可用、解析必失败。
                        .then(Commands.argument("route", ResourceLocationArgument.id())
                                .suggests(ROUTE_SUGGESTIONS)
                                .executes(ctx -> assignToLastDialogueNpc(
                                        ctx, ResourceLocationArgument.getId(ctx, "route"))))));
    }

    /**
     * 路线维度与 NPC 当前维度不一致时，照写但**留一条 WARN**（挂起是设计 D4 的刻意行为，
     * 但不打日志的话，作者看到的症状是"对话演完了、NPC 一动不动、日志里什么都没有"）。
     * <p>
     * 放在这里而不是各写一份：{@code NpcCommand} 的手工指派形式也调它（原先两处重复）。
     */
    public static void warnDimensionMismatch(NpcEntity npc, ResourceLocation id, NpcRoute route) {
        if (!npc.level().dimension().location().equals(route.dimension())) {
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] npc '{}' was assigned route '{}' but is in {} (route expects {})"
                            + " — suspended until it returns",
                    npc.getUUID(), id, npc.level().dimension().location(), route.dimension());
        }
    }

    private static int assignToLastDialogueNpc(CommandContext<CommandSourceStack> ctx, ResourceLocation id) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            // 命令方块/控制台没有"该玩家" ⇒ 明确报错，不猜目标。
            source.sendFailure(Component.translatable("beloong.command.route.not_a_player"));
            return 0;
        }
        NpcRoute route = NpcRouteLoader.INSTANCE.get(id);
        if (route == null) {
            source.sendFailure(Component.translatable("beloong.command.route.unknown",
                    id.toString(), String.join(", ", NpcRouteLoader.INSTANCE.nameStrings())));
            return 0;
        }
        Entity target = LastDialogueNpc.resolve(player);
        if (!(target instanceof NpcEntity npc)) {
            // 没对话过、或那只实体已经不在了 —— 都不能悄悄换一只 NPC 顶替。
            source.sendFailure(Component.translatable("beloong.command.route.no_recent_npc"));
            return 0;
        }
        npc.setRoute(id);
        warnDimensionMismatch(npc, id, route);
        source.sendSuccess(() -> Component.translatable(
                "beloong.command.route.set", 1, id.toString()), true);
        return 1;
    }
}
