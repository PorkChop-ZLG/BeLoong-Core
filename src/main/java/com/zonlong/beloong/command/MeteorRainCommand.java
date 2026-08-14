package com.zonlong.beloong.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.zonlong.beloong.network.MeteorRainSyncPayload;
import com.zonlong.beloong.weather.MeteorRainHandler;
import com.zonlong.beloong.weather.MeteorRainManager;
import com.zonlong.beloong.weather.MeteorRainState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 流星火雨命令。
 * <p>
 * 命令结构：{@code beloongcore weather meteorrain [start|stop|status]}，
 * 无子命令时默认等同 {@code status}。需要权限等级 2（OP）。
 */
public class MeteorRainCommand {

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("beloongcore")
                .then(Commands.literal("weather")
                        .then(Commands.literal("meteorrain")
                                .requires(src -> src.hasPermission(2))
                                .executes(this::status)
                                .then(Commands.literal("start").executes(this::start))
                                .then(Commands.literal("stop").executes(this::stop))
                                .then(Commands.literal("status").executes(this::status))
                        )
                )
        );
    }

    private int start(CommandContext<CommandSourceStack> ctx) {
        ServerLevel disaster = ctx.getSource().getServer().getLevel(MeteorRainHandler.DISASTER);
        if (disaster == null) {
            ctx.getSource().sendFailure(Component.translatable("command.beloong.meteorrain.only_disaster"));
            return 0;
        }
        MeteorRainManager.INSTANCE.start(disaster);
        broadcast(disaster, true);
        ctx.getSource().sendSuccess(() -> Component.translatable("command.beloong.meteorrain.started"), true);
        return 1;
    }

    private int stop(CommandContext<CommandSourceStack> ctx) {
        ServerLevel disaster = ctx.getSource().getServer().getLevel(MeteorRainHandler.DISASTER);
        if (disaster == null) {
            ctx.getSource().sendFailure(Component.translatable("command.beloong.meteorrain.only_disaster"));
            return 0;
        }
        MeteorRainManager.INSTANCE.stop(disaster);
        broadcast(disaster, false);
        ctx.getSource().sendSuccess(() -> Component.translatable("command.beloong.meteorrain.stopped"), true);
        return 1;
    }

    private int status(CommandContext<CommandSourceStack> ctx) {
        MeteorRainState state = MeteorRainManager.INSTANCE.stateFor(MeteorRainHandler.DISASTER);
        Component msg = switch (state.phase()) {
            case ACTIVE -> Component.translatable("command.beloong.meteorrain.status.active",
                    state.ticksRemaining() / 20);
            case COOLDOWN -> Component.translatable("command.beloong.meteorrain.status.cooldown",
                    state.ticksRemaining() / 20);
            case INACTIVE -> Component.translatable("command.beloong.meteorrain.status.inactive");
        };
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private void broadcast(ServerLevel level, boolean active) {
        for (ServerPlayer player : level.players()) {
            PacketDistributor.sendToPlayer(player, new MeteorRainSyncPayload(active));
        }
    }
}
