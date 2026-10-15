package com.zonlong.beloong.command;

import by.dragonsurvivalteam.dragonsurvival.commands.arguments.DragonAbilityArgument;
import by.dragonsurvivalteam.dragonsurvival.registry.datagen.Translation;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.zonlong.beloong.item.effect.EssenceItem;
import com.zonlong.beloong.registry.ModDataComponents;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * {@code /beloong essence <龙之生存技能> hand} —— 把技能注入<b>主手</b>魔源。
 * <p>
 * 这是「封存技能」功能的<b>注入端</b>（op 工具）：玩家端的使用/习得在
 * {@link EssenceItem#use}。本次不做生存获取途径，所以魔源的填充只能靠这条命令
 * （设计决策见 {@code docs/plans/2026-10-15-essence-skill-storage-design.md} 的 N1 / D8）。
 *
 * <h2>为什么参数直接复用 {@link DragonAbilityArgument}</h2>
 *
 * 它是龙之生存自己的参数类型，并且是 {@code public} 的 —— 直接 new 就能得到
 * <b>全量技能 ID 的 tab 补全</b>（它内部对注册表 {@code listElementIds()} 做建议），
 * 没必要自己写一个等价物。
 * <p>
 * ⚠️ 但它在**未知 ID 时会返回 {@code null} 且不报错**（{@code DragonAbilityArgument.parse} 的
 * {@code optional.orElse(null)}）。龙之生存自己的 {@code /dragon-ability add} 没判这个空，
 * 那是它的 NPE 隐患 —— 我们**显式判空**并给一条自己的提示，不继承那个坑。
 *
 * <h2>⚠️ {@code requires} 必须在这里重写</h2>
 *
 * 本命令与 {@link NpcCommand} / {@link CgCommand} / {@link RouteCommand} 共享
 * {@code /beloong} 根字面量。Brigadier 会按下标名合并子树（{@code CommandNode.addChild}），
 * 但合并**不带走**后注册者的 {@code requires} 谓词 ⇒ 每个注册处都要写同样的权限等级。
 * 漏写不会报错，只会让权限判断变得依赖注册顺序 —— 这是本项目已经踩过并记录在
 * {@link RouteCommand} 类注释里的坑。
 *
 * <h2>两个有意为之的行为</h2>
 *
 * <ul>
 *   <li><b>覆盖</b>：主手魔源若已封存别的技能，直接覆盖（设计文档 D13）。
 *       理由是<b>目前没有别的办法清空魔源</b>（只能被龙用掉），若这里拒绝，操作者会走进死路。</li>
 *   <li><b>整摞一起填充</b>（设计文档 D12）：{@code hand} 指向的是那个 {@code ItemStack}，
 *       数量 64 时 64 件都会被注入同一技能。只想填 1 件时请先分摞。</li>
 * </ul>
 *
 * <p>改完<b>不需要手工发包</b>同步客户端：{@code ServerPlayer.tick()} 每 tick 都会
 * {@code containerMenu.broadcastChanges()}（{@code ServerPlayer.java:518}），
 * 手持槽的差异会在下一 tick 自动推给客户端（tooltip 随即更新）。
 */
public final class EssenceCommand {

    private EssenceCommand() {}

    /**
     * 注册命令。
     *
     * @param dispatcher   命令分发器
     * @param buildContext 命令构建上下文 —— 只有它能提供数据包注册表的查找入口，
     *                     因此 {@link DragonAbilityArgument} 只能在这里构造
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        dispatcher.register(Commands.literal("beloong")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("essence")
                        .then(Commands.argument(DragonAbilityArgument.ID, new DragonAbilityArgument(buildContext))
                                // hand 是**字面量**而不是参数：将来要支持副手，再挂一个兄弟字面量即可
                                .then(Commands.literal("hand")
                                        .executes(EssenceCommand::injectIntoMainHand)))));
    }

    /**
     * 五步校验后写入组件（设计文档 §4.2）。任一步失败都只返回 0 并给出提示，<b>不修改物品</b>。
     */
    private static int injectIntoMainHand(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        // ① 命令方块/控制台没有"该玩家" ⇒ 明确报错，不猜目标
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("beloong.command.essence.not_a_player"));
            return 0;
        }

        // ② 主手必须是魔源。判定用 instanceof（精确到类），**不读 tag** —— 见 ModItemTags.ESSENCES 的注释
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof EssenceItem)) {
            source.sendFailure(Component.translatable("beloong.command.essence.not_essence"));
            return 0;
        }

        // ③ 技能必须真的存在（未知 ID 时该参数返回 null）
        Holder<DragonAbility> ability = DragonAbilityArgument.get(ctx);
        if (ability == null) {
            source.sendFailure(Component.translatable("beloong.command.essence.unknown_ability"));
            return 0;
        }

        // ④ 写入（覆盖语义）。技能名复用龙之生存自己的键推导
        stack.set(ModDataComponents.ESSENCE_SKILL.get(), ability.getKey().location());

        // ⑤ 回执。false = 不回显给其它 op
        source.sendSuccess(() -> Component.translatable("beloong.command.essence.success",
                Component.translatable(Translation.Type.ABILITY.wrap(ability.getKey()))), false);
        return 1;
    }
}
