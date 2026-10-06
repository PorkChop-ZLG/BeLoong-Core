package com.zonlong.beloong.perf;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * <b>入世界闸门</b>：特效实体上限护栏的<b>唯一记账点</b>，同时管住两支。
 *
 * <p>事故背景与机制见 {@link EffectEntityCap} 的类注释。这里强调四点：</p>
 * <ol>
 *   <li><b>为什么挂在 {@code EntityJoinLevelEvent}</b>：NeoForge 把该事件 post 在
 *       {@code PersistentEntitySectionManager#addEntity} 的<b>第一条语句</b>
 *       （UUID 登记、区块段插入、跟踪登记、入 tick 表之前）。取消它 = 该实体
 *       <b>从未进入世界</b>：不进 {@code ChunkMap.entityMap}（因此不会被"每个移动包跑一次"的
 *       {@code ChunkMap.move} 遍历）、不进 tick 表、也不下发客户端。</li>
 *   <li><b>两支都管</b>：{@code loadedFromDisk=true}（读盘，含
 *       {@code EntityStorage#read → processPendingLoads} 与
 *       {@code ChunkSerializer → addLegacyChunkEntities}）与 {@code false}
 *       （任何来源的新生成：模组工厂、{@code /summon}、数据包、其它模组 {@code addFreshEntity}、
 *       世界生成放置的实体）。因此"已加载数 ≤ 上限"对所有经实体管理器的入世界路径成立；
 *       模组工厂上的两个 Mixin 只是超限预筛，不记账。</li>
 *   <li><b>为什么它能修旧存档</b>：被取消的实体不会在下次存盘时被写回
 *       （{@code EntityStorage#storeEntities} 只写内存里的实体；列表为空时直接删除文件），
 *       所以那 31 万个残骸会在第一次进入世界并保存后从 {@code entities/c.x.z.mcc} 里消失。</li>
 *   <li><b>读盘支多一条 not-ticking 规则</b>：所在区块不在实体刻范围 ⇒ 永不 {@code tickCount++}、
 *       永不自毁，直接丢弃（新生成支不做这条判定，理由见
 *       {@code EffectEntityCap#shouldRefuseJoin} 的注释）。</li>
 * </ol>
 *
 * <p>实测代价（2026-10-06，某 31 万残骸的旧存档）：首次进入时服务器仍要解析该区块的
 * 108 MB NBT 并构造实体对象（约数百 MB 分配、数秒卡顿），但实体不再被登记、客户端不再收包，
 * 因此不会卡死；之后存档自愈，再次进入即正常。</p>
 */
public final class EffectEntityJoinGate {

    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        // 事件双端都会发；护栏只在服务端判定（集成服客户端一侧也会走到这里）。
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (EffectEntityCap.shouldRefuseJoin(level, event.getEntity(), event.loadedFromDisk())) {
            event.setCanceled(true);
        }
    }
}
