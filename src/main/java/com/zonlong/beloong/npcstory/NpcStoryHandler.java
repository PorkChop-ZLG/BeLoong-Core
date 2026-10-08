package com.zonlong.beloong.npcstory;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.cg.CgRegistry;
import com.zonlong.beloong.entity.NpcEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * <b>私有分身的管家</b> —— 让世界与 {@link NpcStory} 的声明保持一致。
 * <p>
 * 本类目前只负责**事件路径**：玩家获得 {@code start_advancement} 的那一刻
 * ⇒ 幂等确保"只属于他"的分身存在 ⇒ 再由**分身**播放该剧情声明的 CG。
 * （声明路径的对账器 —— 登录/进度变化/低频巡检 —— 是计划 T6，尚未落地。）
 *
 * <h2>为什么生成时机是"获得起点进度那一刻"</h2>
 * 路线指派是靠 {@code LastDialogueNpc}（玩家**最近对话过的那个实体**）解析目标的
 * ⇒ 必须保证"玩家将来对话的那个实体就是他的分身" ⇒ 分身的生成必须**早于**第一次对话。
 * 而起点进度（{@code beloong:npc/root}）由位置触发自动发放 ⇒ 它正好是"玩家走进场景、
 * 还没开口"的那个时刻（设计 D5/D21 的补充）。
 *
 * <h2>为什么在锚点的位置与朝向生成</h2>
 * 玩家视角里那只 mo **一直在原地没动** ⇒ 从"看到公共锚点"切到"看到自己的分身"毫无感知。
 * 这也是"不变量：任一玩家眼里恰好一个"能成立的关键。
 * <p>
 * ⚠️ <b>兜底：找不到锚点时生成在玩家身前 4 格，而不是玩家脚下</b> ——
 * {@code CgContext.of} 在"观察者与锚点水平重合"时判方向退化并返回 {@code null}
 * ⇒ 那样 CG 会直接中止（{@code CgContext} 的退化分支）。
 *
 * <h2>为什么删掉了旧的"±48 就近搜索 {@code MoEntity}"</h2>
 * 旧实现（{@code npcstory/NpcStoryHandler}）是"在世界里碰运气找一个演员"：找不到就只打 WARN 不播，
 * 而且分身出现后还可能挑到**别人的**分身。现在演员是**按需构造**的、引用就在手里
 * ⇒ 那两类问题一起消失（设计 D9）。
 */
public class NpcStoryHandler {

    /** 找不到锚点时，在玩家身前多少格生成（水平方向，保持 y 不变）。 */
    private static final double FALLBACK_FORWARD = 4.0D;

    @SubscribeEvent
    public void onAdvancementEarned(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ResourceLocation earned = event.getAdvancement().id();
        ServerLevel level = player.serverLevel();

        for (Map.Entry<EntityType<?>, NpcStory> entry : NpcStoryLoader.INSTANCE.all().entrySet()) {
            NpcStory story = entry.getValue();
            if (!story.startAdvancement().equals(earned)) {
                continue;
            }

            NpcEntity npc = ensureDouble(player, level, entry.getKey());
            if (npc == null) {
                continue;
            }

            // CG 与"生成"解耦：名字解析不到只留 WARN，分身照常存在（设计 D12）。
            story.cg().ifPresent(name -> CgRegistry.byName(name).ifPresentOrElse(
                    cg -> cg.play(player, npc),
                    () -> BeLoongCore.LOGGER.warn(
                            "[BeLoong] npc story for '{}' names unknown cg '{}' — the double was spawned"
                                    + " but no cg was played", entry.getKey(), name)));
        }
    }

    /**
     * 幂等确保"该玩家的私有分身"存在，返回它。
     * <p>
     * 一次 {@code getAllEntities()} 同时完成两件事：查重（已有 ⇒ 直接复用）与找锚点
     * （{@code owner == null} 的同类实例，多个取离玩家最近的那个）。事件路径很少触发，
     * 这里 O(实体数) 是可接受的；**每个追踪周期都会跑的 {@code visibleTo} 里绝不能这么做**。
     */
    @Nullable
    private NpcEntity ensureDouble(ServerPlayer player, ServerLevel level, EntityType<?> type) {
        NpcEntity anchor = null;
        double anchorDistance = Double.MAX_VALUE;
        int anchors = 0;

        for (Entity entity : level.getAllEntities()) {
            if (entity.getType() != type || !(entity instanceof NpcEntity npc)) {
                continue;
            }
            if (player.getUUID().equals(npc.owner())) {
                return npc;                     // 幂等：已经有自己的分身了，复用它（不重复生成、不重播 CG）
            }
            if (npc.owner() == null) {
                anchors++;
                double distance = player.distanceToSqr(npc);
                if (distance < anchorDistance) {
                    anchorDistance = distance;
                    anchor = npc;
                }
            }
        }

        Entity created = type.create(level);
        if (!(created instanceof NpcEntity npc)) {
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] npc story: entity type '{}' could not be created as an NpcEntity"
                            + " — no double was spawned", type);
            return null;
        }

        if (anchor != null) {
            if (anchors > 1) {
                // 设计 §2.4：锚点应当唯一。多于一个说明世界被摆错了（或旧分身丢了归属）
                // ⇒ 取离玩家最近的那个，并把这件事留在日志里。
                BeLoongCore.LOGGER.warn(
                        "[BeLoong] npc story: {} ownerless '{}' found in {} — using the nearest to player '{}'",
                        anchors, type, level.dimension().location(), player.getGameProfile().getName());
            }
            npc.moveTo(anchor.getX(), anchor.getY(), anchor.getZ(), anchor.getYRot(), anchor.getXRot());
            npc.setYHeadRot(anchor.getYRot());
        } else {
            // 兜底：身前 4 格（水平方向）。刻意不用"玩家脚下" —— 见类注释。
            Vec3 look = player.getLookAngle();
            Vec3 forward = new Vec3(look.x, 0.0D, look.z).normalize().scale(FALLBACK_FORWARD);
            Vec3 spot = player.position().add(forward);
            npc.moveTo(spot.x, player.getY(), spot.z, player.getYRot(), 0.0F);
            npc.setYHeadRot(player.getYRot());
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] npc story: no anchor (ownerless '{}') was found in {}"
                            + " — spawned the double {} blocks in front of player '{}' instead",
                    type, level.dimension().location(), (int) FALLBACK_FORWARD,
                    player.getGameProfile().getName());
        }

        npc.setOwner(player.getUUID());
        level.addFreshEntity(npc);
        BeLoongCore.LOGGER.info(
                "[BeLoong] npc story: spawned private double of '{}' for player '{}' at {}",
                type, player.getGameProfile().getName(), npc.blockPosition());
        return npc;
    }
}
