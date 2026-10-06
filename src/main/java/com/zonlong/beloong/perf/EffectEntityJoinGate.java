package com.zonlong.beloong.perf;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * <b>旧存档读盘闸门</b>：把"修复前就已堆积在存档里的特效实体"挡在世界之外。
 *
 * <p>事故背景与机制见 {@link EffectEntityCap} 的类注释。这里只强调三点：</p>
 * <ol>
 *   <li><b>为什么挂在 {@code EntityJoinLevelEvent}</b>：NeoForge 把该事件 post 在
 *       {@code PersistentEntitySectionManager#addEntity} 的<b>第一条语句</b>
 *       （UUID 登记、区块段插入、跟踪登记、入 tick 表之前）。取消它 = 该实体
 *       <b>从未进入世界</b>：不进 {@code ChunkMap.entityMap}（因此不会被"每个移动包跑一次"的
 *       {@code ChunkMap.move} 遍历）、不进 tick 表、也不下发客户端。
 *       两条磁盘读取路径都会触发它且标记为 {@code loadedFromDisk=true}：
 *       {@code EntityStorage#read → processPendingLoads → addEntity(e, true)}
 *       与 {@code ChunkSerializer → addLegacyChunkEntities → addEntity(e, true)}。</li>
 *   <li><b>为什么它能修旧存档</b>：被取消的实体不会在下次存盘时被写回
 *       （{@code EntityStorage#storeEntities} 只写内存里的实体；列表为空时直接删除文件），
 *       所以那 31 万个残骸会在第一次进入世界并保存后从 {@code entities/c.x.z.mcc} 里消失。</li>
 *   <li><b>本闸门只治读盘</b>（{@code loadedFromDisk=true}）。"新生成"由模组静态工厂入口的
 *       {@code CameraShakeCapMixin}/{@code DynamicCameraZoomCapMixin} 负责；两条路径共用
 *       {@link EffectEntityCap} 的同一份账，因此上限是统一的。</li>
 * </ol>
 *
 * <p>实测代价（2026-10-06，某 31 万残骸的旧存档）：首次进入时服务器仍要解析该区块的
 * 108 MB NBT 并构造实体对象（约数百 MB 分配、数秒卡顿），但实体不再被登记、客户端不再收包，
 * 因此不会卡死；之后存档自愈，再次进入即正常。</p>
 */
public final class EffectEntityJoinGate {

    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!event.loadedFromDisk()) {
            return;
        }
        // 事件双端都会发；护栏只在服务端判定（集成服客户端一侧也会走到这里）。
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (EffectEntityCap.shouldRefuseLoadedEntity(level, event.getEntity())) {
            event.setCanceled(true);
        }
    }
}
