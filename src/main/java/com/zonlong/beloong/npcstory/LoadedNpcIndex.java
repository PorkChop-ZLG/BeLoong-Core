package com.zonlong.beloong.npcstory;

import com.zonlong.beloong.entity.NpcEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>已加载 {@link com.zonlong.beloong.entity.NpcEntity} 的索引</b>（性能优化 S1，2026-10-14）。
 *
 * <h2>为什么需要它</h2>
 * 对账器原来用 {@code ServerLevel#getAllEntities()} 找分身 —— 那是**该维度全部已加载实体**
 * （掉落物、弹射物、成群怪物…），而我们只要其中几只。它每 100 tick 对**每条剧情**都要跑一遍。
 * 本索引让它变成"只手指数得过来的几只 NPC"。
 *
 * <h2>⚠️⚠️ 死锁红线：join 处理器里只许动内存</h2>
 * {@code EntityJoinLevelEvent} 的 javadoc 明写：
 * <i>"This event may be called before the underlying {@code LevelChunk} is promoted to
 * {@link net.minecraft.world.level.chunk.status.ChunkStatus#FULL}. You will cause chunk loading
 * deadlocks if you do not delay your world interactions."</i>
 * ⇒ 因此 {@link #onEntityJoin} 里**只做一次 Map 写入**：
 * 不得访问 level / chunk / blockState，不得查询实体，不得做任何需要世界状态的判断。
 * 需要判租约、删实体之类的动作一律放到 **tick 阶段**（对账器/序章）去做。
 *
 * <h2>正确性：不依赖 leave 事件</h2>
 * 两个事件都只是**加速**。区块卸载路径是
 * {@code PersistentEntitySectionManager.unloadEntity} → {@code setRemoved(UNLOADED_TO_CHUNK)}
 * ⇒ 读取时丢掉 {@code isRemoved()} 的条目即可保证正确：
 * 即便 {@code EntityLeaveLevelEvent} 一次都没触发，本索引也不会给出"已经不在世界里的 NPC"。
 *
 * <h2>⚠️ 它给出的仍然是"已加载"的意思</h2>
 * 被区块卸载的实体**不在**本索引里 —— 这与 {@code getAllEntities()} 一样。
 * 因此调用方**绝不许**把"索引里没有"当成"那只分身不存在"（设计 D2：
 * 一切清理与撤回都只在**手里真有那只实体**时发生）。
 */
public final class LoadedNpcIndex {

    /**
     * 已加载的 NPC，键为实体 UUID。
     * <p>
     * ⚠️ 只在**服务端**被读写：join/leave 两个处理器都先闸掉客户端（{@code isClientSide}），
     * 客户端线程因此永远不会碰它（单机 = 集成服务端，两端同进程，这一点必须守住 —— 审查 C1）。
     * 在服务端内部只有主线程访问，故用普通 HashMap。
     */
    private static final Map<UUID, NpcEntity> LOADED = new HashMap<>();

    /** 实体加入世界（含**从存档恢复**：那条路同样会派发本事件，已核对原版与 NeoForge 源码）。 */
    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        // ⚠️ 死锁红线：这里只许碰内存（见类注释）。连 level 都不查，只看实体自身。
        if (event.getEntity() instanceof NpcEntity npc && !npc.level().isClientSide()) {
            LOADED.put(npc.getUUID(), npc);
        }
    }

    /**
     * 实体离开世界 —— <b>仅作加速</b>，不作为正确性依赖（见类注释）。
     * <p>
     * 注意：区块卸载走的是 {@code setRemoved(UNLOADED_TO_CHUNK)}，这条路**会**派发本事件
     * （{@code Entity#setRemoved} → {@code PersistentEntitySectionManager.Callback#onRemove}），
     * 但 {@link #loaded()} 的 {@code isRemoved()} 过滤仍是**正确性的最终保证**（事件只是加速：
     * 例如世界/维度卸载、异常路径都可能让某次 remove 落空）。
     * <p>
     * ⚠️ <b>必须闸掉客户端</b>（见方法体首句的注释）——这是审查 C1 抓到的真实缺陷。
     */
    @SubscribeEvent
    public void onEntityLeave(EntityLeaveLevelEvent event) {
        // ★ 审查 C1：必须与 join 同样闸掉客户端。BeLoongCore 把本类注册在**公共**总线上，
        // 而单机/局域网就是"集成服务端"——客户端会在自己的线程上派发同一个事件，
        // 且客户端实体的 UUID 与服务端一致 ⇒ 不闸的话，客户端区块卸载会删掉**服务端仍在加载**的条目：
        // 表现为"对账器看不到分身"（租约到期这条唯一的删+撤永不触发）、退出清理漏掉、序章清场漏掉
        // （于是 ensureDouble 可能再生一只），并且客户端线程的 remove 与服务端线程的遍历争用同一个 HashMap。
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof NpcEntity npc) {
            LOADED.remove(npc.getUUID());
        }
    }

    /**
     * 此刻<b>确实还在世界里</b>的 NPC（顺带清掉已移除的条目）。
     * <p>
     * ⚠️ 返回的是"已加载"的集合，不是"存在"的集合（见类注释）。
     */
    public static List<NpcEntity> loaded() {
        List<NpcEntity> out = new ArrayList<>(LOADED.size());
        for (Iterator<Map.Entry<UUID, NpcEntity>> it = LOADED.entrySet().iterator(); it.hasNext(); ) {
            NpcEntity npc = it.next().getValue();
            if (npc.isRemoved()) {
                // 正常情况由 onEntityLeave 清；这里是"leave 没触发"时的兜底，也是正确性的真正来源。
                it.remove();
                continue;
            }
            out.add(npc);
        }
        return out;
    }

    /**
     * 某一个维度里此刻还在世界里的 NPC（对账按剧情声明的维度取用）。
     * <p>
     * ⚠️ 维度用<b>身份比较</b>（{@code npc.level() == level}）而不是维度键：
     * 这样世界被卸载重建后，属于旧 {@code ServerLevel} 对象的陈旧条目不会被误判成"在新世界里"。
     */
    public static List<NpcEntity> loadedIn(ServerLevel level) {
        List<NpcEntity> out = new ArrayList<>();
        for (NpcEntity npc : loaded()) {
            if (npc.level() == level) {
                out.add(npc);
            }
        }
        return out;
    }

    /** 仅供诊断：当前索引条数（未过滤）。 */
    public static int rawSize() {
        return LOADED.size();
    }

    /** 该实体是否已被索引（诊断用）。 */
    public static boolean contains(Entity entity) {
        return entity instanceof NpcEntity npc && LOADED.containsKey(npc.getUUID());
    }
}
