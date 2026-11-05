package com.zonlong.beloong.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /beloong cleandead [confirm]} —— 清理存档里「假死」的尸体实体。
 *
 * <h2>为什么需要这条命令</h2>
 *
 * 原版选择器**选不到**这些实体：`EntitySelectorParser` 解析 {@code @e} 时 {@code yield true}
 * （`:228-233`），随后 `:261` 执行 {@code predicates.add(Entity::isAlive)}，而
 * {@code LivingEntity.isAlive()} 要求 {@code getHealth() > 0}（`LivingEntity.java:1632-1634`）
 * ⇒ 0 血但未被移除的尸体对 {@code /kill}、{@code /data}、{@code /tp} 全部不可见。
 *
 * <p>本项目已实测的一种来源：铁魔法「霜噬」在"击杀者带 {@code frostbitten_strikes} 且死者完全冻结"时
 * 把 {@code deathTime} 写成 1000（`FrostbiteEffect.java:35`），而祸乱鬼魅 / 永恒星光的收尾是
 * {@code deathTime == N} 的精确相等 ⇒ 永不命中 ⇒ 尸体永久留场。
 * 那条根因已在 {@code mixin/ironsspellbooks/FrostbiteDeathSequenceMixin} 从源头修掉
 * （开关 {@code Config.FIX_FROSTBITE_DEATH}，即 {@code fixFrostbiteDeath}，默认开）；
 * 本命令处理的是**已经卡住的老存档**。
 *
 * <h2>判定</h2>
 *
 * <pre>
 * LivingEntity 且非玩家、未移除、health &lt;= 0、deathTime &gt;= {@value #STUCK_DEATH_TIME}
 * </pre>
 *
 * 阈值取 400 tick（20 秒）的依据：原版死亡动画 20；祸乱鬼魅 50/70；永恒星光 70/75/100；
 * 灾变的收尾走 {@code >=}（≤ 约 123）；末影龙 200 ⇒ **正常死亡不会超过 400**，
 * 而实测的假死尸体停在四位数。已由"假死修复"类模组覆盖的 NaN 型假死不在此列（不重复实现）。
 *
 * <h2>动作：只移除，不补发任何奖励</h2>
 *
 * 对每个命中实体执行 {@code remove(RemovalReason.KILLED)}。
 *
 * <p><b>刻意不重放死亡结算</b>（委托方 2026-11-05 裁定）：{@code die()} 早在假死那一刻就跑完了，
 * 战利品与死亡事件该发的都发过；本命令不再把 {@code deathTime} 拨回去重放
 * ——那样会额外补发该模组"死亡 tick"里给的经验/战利品，属于凭空造物。
 *
 * <p>代价（如实记录）：祸乱鬼魅的死亡 tick 里还有收尾动作，直接移除会跳过它们
 * ——虚空之花会留下一个它自己放的 {@code minecraft:light} 方块（不可见、徒手打不掉，
 * 可用 {@code /setblock <pos> air} 清）；下界铁掌则不会再炸一次、也不再掉远古残骸。
 *
 * <h2>为什么是命令而不是定时检测</h2>
 *
 * 这类 bug 极罕见，周期性扫描纯属浪费；命令只在管理员发现后执行一次，
 * 遍历一次 {@code 所有维度 × 已加载实体}（{@code server.getAllLevels()}），不接受不必要的常驻开销。
 *
 * <h2>权限与执行者</h2>
 *
 * {@code hasPermission(2)}（与 {@code /beloong} 下其余命令一致）；控制台天然可用
 * ——本命令<b>不依赖玩家</b>（不需要坐标、不需要手持物）。
 * ⚠️ {@code /beloong} 根字面量由多个命令共享，Brigadier 合并子树**不带走** {@code requires} 谓词
 * ⇒ 每个注册点都必须重写权限等级（见 {@link EssenceCommand} 的同类说明）。
 *
 * <h2>无状态</h2>
 *
 * {@code confirm} 会**重新扫描**，不复用 {@code list} 的结果：不占内存、也不会误伤
 * "列出之后才出现"的实体；代价是两次之间的变化会体现在最终汇总里。
 */
public final class CleanDeadCommand {

    private CleanDeadCommand() {
    }

    /**
     * 「已经卡住」的判定阈值（tick）。
     *
     * <p>取值理由见类注释：正常死亡演出最长约 200 tick（末影龙），400 是安全余量。
     */
    private static final int STUCK_DEATH_TIME = 400;

    /** 聊天框里最多列多少条，其余只进服务端日志（防刷屏）。 */
    private static final int MAX_LISTED = 20;

    /**
     * 注册命令。
     *
     * @param dispatcher 命令分发器
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("beloong")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("cleandead")
                        // 不带参数 = 只列清单（默认不给破坏性行为）
                        .executes(CleanDeadCommand::list)
                        .then(Commands.literal("confirm")
                                .executes(CleanDeadCommand::confirm))));
    }

    /**
     * 扫描结果的一条快照。
     *
     * <p>把 id / 坐标 / 维度 / deathTime 在扫描时就抄下来：执行阶段实体已被 {@code remove()} 掉，
     * 事后再读它的 {@code level()} / 坐标是不安全的。
     */
    private record Candidate(LivingEntity entity, String typeId, String pos, String dimension, int deathTime) {
    }

    /**
     * 只列清单（默认行为）。
     *
     * @param ctx 命令上下文
     * @return 命中的实体数
     */
    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        List<Candidate> candidates = scan(source);
        if (candidates.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("beloong.command.cleandead.none"), false);
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("beloong.command.cleandead.header",
                candidates.size()), false);

        int shown = 0;
        for (Candidate candidate : candidates) {
            if (shown >= MAX_LISTED) {
                break;
            }
            shown++;
            source.sendSuccess(() -> entry(candidate), false);
        }
        if (candidates.size() > MAX_LISTED) {
            source.sendSuccess(() -> Component.translatable("beloong.command.cleandead.more",
                    candidates.size() - MAX_LISTED), false);
        }

        // 确认入口做成可点击（项目约定：可点击项用金色 §e）
        source.sendSuccess(() -> Component.translatable("beloong.command.cleandead.confirm_hint")
                .withStyle(style -> style
                        .withColor(ChatFormatting.GOLD)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/beloong cleandead confirm"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("beloong.command.cleandead.confirm_hover")))), false);

        logAll(candidates, "listed");
        return candidates.size();
    }

    /**
     * 执行清理（{@code confirm}）：逐个移除。
     *
     * @param ctx 命令上下文
     * @return 移除的实体数
     */
    private static int confirm(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        List<Candidate> candidates = scan(source);
        if (candidates.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("beloong.command.cleandead.none"), false);
            return 0;
        }

        for (Candidate candidate : candidates) {
            candidate.entity().remove(Entity.RemovalReason.KILLED);
        }

        logAll(candidates, "applied");
        int removed = candidates.size();
        BeLoongCore.LOGGER.info("[BeLoong] cleandead: removed={} by={}", removed, source.getTextName());
        source.sendSuccess(() -> Component.translatable("beloong.command.cleandead.done", removed), false);
        return removed;
    }

    /**
     * 遍历**所有维度**的已加载实体，收集候选。
     *
     * <p>只收集、不动手：{@code getAllEntities()} 是实体存储的视图，边遍历边 {@code remove()}
     * 有并发修改风险，因此执行阶段统一在收集完成之后进行。
     *
     * @param source 命令源，用于取 {@code MinecraftServer}
     * @return 候选快照列表
     */
    private static List<Candidate> scan(CommandSourceStack source) {
        List<Candidate> candidates = new ArrayList<>();
        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof LivingEntity living)) {
                    continue;
                }
                // 玩家不可能 0 血存活，这里只是防御（且绝不该 remove 玩家）
                if (living instanceof Player) {
                    continue;
                }
                if (living.isRemoved() || living.getHealth() > 0.0F || living.deathTime < STUCK_DEATH_TIME) {
                    continue;
                }
                candidates.add(new Candidate(
                        living,
                        BuiltInRegistries.ENTITY_TYPE.getKey(living.getType()).toString(),
                        String.format(Locale.ROOT, "%.0f %.0f %.0f", living.getX(), living.getY(), living.getZ()),
                        level.dimension().location().toString(),
                        living.deathTime));
            }
        }
        return candidates;
    }

    /** 单条清单：类型 @ 坐标（维度，deathTime）→ 移除。 */
    private static Component entry(Candidate candidate) {
        return Component.translatable("beloong.command.cleandead.entry",
                Component.literal(candidate.typeId()),
                Component.literal(candidate.pos()),
                Component.literal(candidate.dimension()),
                Component.literal(Integer.toString(candidate.deathTime())));
    }

    /** 把全部候选写进服务端日志（聊天框只列前 {@value #MAX_LISTED} 条）。 */
    private static void logAll(List<Candidate> candidates, String phase) {
        for (Candidate candidate : candidates) {
            BeLoongCore.LOGGER.info("[BeLoong] cleandead({}): {} @ {} (dim={}, deathTime={}) -> remove",
                    phase,
                    candidate.typeId(),
                    candidate.pos(),
                    candidate.dimension(),
                    candidate.deathTime());
        }
    }
}
