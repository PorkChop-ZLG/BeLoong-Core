package com.zonlong.beloong.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.zonlong.beloong.entity.NpcEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.List;

/**
 * 通用 NPC 的调试 / 摆位命令。
 * <p>
 * <b>定位：验收与手动摆位工具，不是玩法内容。</b>它存在的直接原因很具体 ——
 * {@link NpcEntity} 的四个能力（{@code walkTo} / {@code runTo} / {@code turnTo} / {@code attack}）
 * 是**纯 API、默认没有任何调用方**，没有这个命令，它们在本轮交付里**无法被证明**。
 * 将来不需要了，删本类 + {@code BeLoongCore} 里一行注册即可。
 * <p>
 * 只调实体 API，**不碰实体字段**；目标过滤 {@link NpcEntity}，因此对本模组**所有** NPC 生效，
 * 不只地黄龙。op 级（{@code hasPermission(2)}）：它改的是世界里的实体。
 */
public final class NpcCommand {

    private NpcCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("beloong")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("npc")
                        .then(Commands.literal("turn")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .then(Commands.argument("yaw", DoubleArgumentType.doubleArg())
                                                .executes(ctx -> turn(
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        (float) DoubleArgumentType.getDouble(ctx, "yaw"),
                                                        ctx.getSource())))))
                        .then(Commands.literal("walk")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .executes(ctx -> move(
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        Vec3Argument.getVec3(ctx, "pos"),
                                                        false,
                                                        ctx.getSource())))))
                        .then(Commands.literal("run")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .executes(ctx -> move(
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        Vec3Argument.getVec3(ctx, "pos"),
                                                        true,
                                                        ctx.getSource())))))
                        .then(Commands.literal("attack")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .then(Commands.argument("victim", EntityArgument.entity())
                                                .executes(ctx -> attack(
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        EntityArgument.getEntity(ctx, "victim"),
                                                        ctx.getSource())))))
                        .then(Commands.literal("stop")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(ctx -> stop(
                                                EntityArgument.getEntities(ctx, "targets"),
                                                ctx.getSource()))))));
    }

    /** 转身到绝对角度。 */
    private static int turn(Collection<? extends Entity> targets, float yaw, CommandSourceStack source) {
        List<NpcEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        for (NpcEntity npc : npcs) {
            npc.turnTo(yaw);
        }
        source.sendSuccess(() -> Component.translatable(
                "beloong.command.npc.turn", npcs.size(), String.format("%.1f", yaw)), true);
        return npcs.size();
    }

    /** 走过去或跑过去。 */
    private static int move(Collection<? extends Entity> targets, Vec3 pos, boolean sprint, CommandSourceStack source) {
        List<NpcEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        for (NpcEntity npc : npcs) {
            if (sprint) {
                npc.runTo(pos);
            } else {
                npc.walkTo(pos);
            }
        }
        String key = sprint ? "beloong.command.npc.run" : "beloong.command.npc.walk";
        source.sendSuccess(() -> Component.translatable(key, npcs.size(), pos.toString()), true);
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

    /** 停止转向与移动，回到站桩。 */
    private static int stop(Collection<? extends Entity> targets, CommandSourceStack source) {
        List<NpcEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        for (NpcEntity npc : npcs) {
            npc.stopAction();
        }
        source.sendSuccess(() -> Component.translatable("beloong.command.npc.stop", npcs.size()), true);
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
