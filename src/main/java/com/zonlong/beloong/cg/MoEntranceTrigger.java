package com.zonlong.beloong.cg;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.cg.instances.MoEntrance;
import com.zonlong.beloong.entity.MoEntity;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;

import java.util.Comparator;

/**
 * <b>「获得 {@code beloong:npc/root} 进度 ⇒ 播放末的登场 CG」</b> —— 把进度系统与 CG 系统接起来的那一环。
 * <p>
 * 设计：{@code docs/plans/2026-09-30-npc-route-system-design.md} 的联动一节。
 * 流程：玩家在龙宫走进末所在区域 ⇒ {@code root} 由原版 {@code minecraft:location} 自动获得
 * ⇒ 本处理器放 {@code mo_entrance} ⇒ 玩家上前交互 ⇒ 我们的对话 ⇒ ChatBox ⇒ 「好的」⇒ 末开始寻路。
 *
 * <h2>为什么用 Java 事件而不是进度的 {@code rewards.function}</h2>
 * <ol>
 *   <li><b>权限</b>：进度 reward 以**玩家**的命令源执行，而 {@code Entity#getPermissionLevel()} 的字节码是
 *       {@code iconst_0}（返回 0）⇒ 非 op 玩家跑不动 {@code /beloong cg}（它要求权限 2）。
 *       本处理器不经过命令层，没有这个问题。</li>
 *   <li><b>选不中目标时能说话</b>：mcfunction 里只能写 {@code @e[type=beloong:mo,…]}，找不到就**静默失败**；
 *       这里能打一条英文 WARN，把"该放没放"这件事留在日志里。</li>
 * </ol>
 *
 * <h2>为什么找"离玩家最近的末"而不是"区域里的任意一只"</h2>
 * {@code root} 的触发盒是水平 ±16 格（见 {@code advancement/npc/root.json}），
 * 而搜索半径取 48 —— 足够宽松（末的碰撞箱/站位差异、玩家停在盒边缘都不会漏），
 * 又不至于把远处另一只末挑来当演出对象（按最近距离取，语义就是"玩家看到的那个"）。
 *
 * <h2>已知取舍（用户裁定）</h2>
 * 若这一刻附近确实没有末（例如整合包还没召唤它），本处理器**只打一条 WARN、不重试** ——
 * 那种情况下玩家会拿到 {@code root} 但看不到登场演出。这是刻意接受的：触发盒本就围绕末的站位，
 * 正常流程里两者同时存在；加一轮"每 20 tick 重试 30 秒"的状态机被认为不值得。
 */
public class MoEntranceTrigger {

    /** {@code root} 进度的 id。 */
    private static final ResourceLocation ROOT =
            ResourceLocation.parse("beloong:npc/root");

    /** 找末的半径（格）。见类注释"为什么找离玩家最近的末"。 */
    private static final double SEARCH_RADIUS = 48.0D;

    @SubscribeEvent
    public void onAdvancementEarned(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        AdvancementHolder advancement = event.getAdvancement();
        if (!ROOT.equals(advancement.id())) {
            return;
        }

        MoEntity mo = player.serverLevel()
                .getEntitiesOfClass(MoEntity.class,
                        player.getBoundingBox().inflate(SEARCH_RADIUS),
                        Entity::isAlive)
                .stream()
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
        if (mo == null) {
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] player '{}' earned '{}' but no beloong:mo is within {} blocks"
                            + " — the mo_entrance CG was NOT played",
                    player.getGameProfile().getName(), ROOT, (int) SEARCH_RADIUS);
            return;
        }

        CgRegistry.byName(MoEntrance.NAME).ifPresentOrElse(
                cg -> cg.play(player, mo),
                () -> BeLoongCore.LOGGER.warn(
                        "[BeLoong] cg '{}' is not registered — the mo_entrance CG was NOT played",
                        MoEntrance.NAME));
    }
}
