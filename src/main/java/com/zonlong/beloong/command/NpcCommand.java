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
 * <p>
 * <b>飞行相关（2026-09-27 加入）</b>：{@code fly <targets> on|off|to <pos>} 与
 * {@code reset <targets>}。飞行默认关闭，只有 {@code fly on} 才开；
 * {@code reset} 把它恢复到"刚被召唤出来的状态"。
 * 注意 {@code stop} <b>不管飞行</b>（它只停移动与攻击），要回到默认状态请用 {@code reset}。
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
                                                ctx.getSource()))))
                        .then(Commands.literal("fly")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .then(Commands.literal("on")
                                                .executes(ctx -> flyToggle(
                                                        EntityArgument.getEntities(ctx, "targets"), true, ctx.getSource())))
                                        .then(Commands.literal("off")
                                                .executes(ctx -> flyToggle(
                                                        EntityArgument.getEntities(ctx, "targets"), false, ctx.getSource())))
                                        .then(Commands.literal("to")
                                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                                        .executes(ctx -> flyTo(
                                                                EntityArgument.getEntities(ctx, "targets"),
                                                                Vec3Argument.getVec3(ctx, "pos"),
                                                                ctx.getSource()))))))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(ctx -> reset(
                                                EntityArgument.getEntities(ctx, "targets"),
                                                ctx.getSource()))))));
    }

    /**
     * 开关飞行。
     * <p>
     * 幂等性由 {@code NpcEntity#setFlying} 保证：重复 {@code on} 不会重建导航/移动控制
     * （那会把正在执行的飞行路径丢掉）。
     */
    private static int flyToggle(Collection<? extends Entity> targets, boolean flying, CommandSourceStack source) {
        List<NpcEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        for (NpcEntity npc : npcs) {
            npc.setFlying(flying);
        }
        source.sendSuccess(() -> Component.translatable(
                flying ? "beloong.command.npc.fly_on" : "beloong.command.npc.fly_off", npcs.size()), true);
        return npcs.size();
    }

    /**
     * 飞到指定点。
     * <p>
     * <b>未开飞行时明确报错，不隐式开启</b> —— 用户裁定"开启飞行后用 fly to 控制"，
     * 所以这是**前置条件**而不是副作用。
     * <p>
     * 判定用 {@code anyMatch}：选中的 NPC 里只要有<b>任意一个</b>没在飞行就整体报错，
     * 不做"对其中一部分静默生效"——那会让玩家以为命令成功了。
     */
    private static int flyTo(Collection<? extends Entity> targets, Vec3 pos, CommandSourceStack source) {
        List<NpcEntity> npcs = npcsIn(targets);
        if (npcs.isEmpty()) {
            return fail(source);
        }
        if (npcs.stream().anyMatch(npc -> !npc.isFlying())) {
            source.sendFailure(Component.translatable("beloong.command.npc.not_flying"));
            return 0;
        }
        for (NpcEntity npc : npcs) {
            npc.flyTo(pos);
        }
        source.sendSuccess(() -> Component.translatable(
                "beloong.command.npc.fly_to", npcs.size(), pos.toString()), true);
        return npcs.size();
    }

    /** 恢复到默认状态：无飞行、无移动、无攻击、地面站桩（等于刚被召唤出来的样子）。 */
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
