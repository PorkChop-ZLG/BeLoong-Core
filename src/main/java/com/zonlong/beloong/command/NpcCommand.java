package com.zonlong.beloong.command;

import com.mojang.brigadier.CommandDispatcher;
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
 * {@link NpcEntity} 的 {@code walkTo} / {@code attack} 是**纯 API、默认没有任何调用方**，
 * 没有这两个命令，它们就无法被当场验证。将来不需要了，删本类 + {@code BeLoongCore} 里一行注册即可。
 * <p>
 * 只调实体 API，**不碰实体字段**；目标过滤 {@link NpcEntity}，因此对本模组**所有** NPC 生效。
 * op 级（{@code hasPermission(2)}）：它改的是世界里的实体。
 * <p>
 * <b>刻意没有的子命令</b>（用户裁定）：
 * <ul>
 *   <li>{@code run} —— 奔跑状态已取消，速度差异改由移速属性的加成体现
 *       （见 {@link NpcEntity#registerControllers()}）；</li>
 *   <li>{@code turn} —— 效果不好（站桩转向依赖"头带身体"的原版链，玩家在场时还会被让位），
 *       而"旋转"本身可以直接从行为上看出来，不需要指令演示。</li>
 * </ul>
 */
public final class NpcCommand {

    private NpcCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("beloong")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("npc")
                        .then(Commands.literal("walk")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .executes(ctx -> walk(
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        Vec3Argument.getVec3(ctx, "pos"),
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

    /** 走到目标点（速度即该 NPC 的基础移速，约为走路玩家的九成）。 */
    private static int walk(Collection<? extends Entity> targets, Vec3 pos, CommandSourceStack source) {
        List<NpcEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        for (NpcEntity npc : npcs) {
            npc.walkTo(pos);
        }
        source.sendSuccess(() -> Component.translatable(
                "beloong.command.npc.walk", npcs.size(), pos.toString()), true);
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

    /** 停止移动与攻击，回到站桩。 */
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
