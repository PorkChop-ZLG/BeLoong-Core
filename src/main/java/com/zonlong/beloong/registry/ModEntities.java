package com.zonlong.beloong.registry;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.entity.DihuangLoongEntity;
import com.zonlong.beloong.entity.MoEntity;
import com.zonlong.beloong.entity.DreadKingRitualMarker;
import com.zonlong.beloong.entity.TornadoEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 化龙核心模组的实体注册中心。
 * <p>
 * 注册的实体：
 * <ul>
 *   <li>{@link #TORNADO} — 龙卷风（{@code beloong:tornado}），由龙技能「龙卷风」发射</li>
 *   <li>{@link #DIHUANG_LOONG} — 地黄龙 NPC（{@code beloong:dihuang_loong}），站桩生物 NPC</li>
 *   <li>{@link #MO} — 末 NPC（{@code beloong:mo}），站桩生物 NPC（YSM 移植模型）</li>
 *   <li>{@link #DREAD_KING_RITUAL_MARKER} — 死王仪式标记（{@code beloong:dread_king_ritual_marker}），
 *       在黯影宝库顶部计时 352 tick 后召唤不祥死者之王；<b>永不发送客户端，无需渲染器</b></li>
 * </ul>
 *
 * @see BeLoongCore
 * @see TornadoEntity
 * @see DreadKingRitualMarker
 */
public final class ModEntities {

    private ModEntities() {}

    /** 实体类型延迟注册器 */
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, BeLoongCore.MODID);

    /**
     * 龙卷风实体。
     * <p>
     * 碰撞箱设为 3x 模型的视觉包围盒（约 2.8 格宽 × 3.3 格高）。
     * <p>
     * {@code updateInterval(1)} 让服务端每 tick 都下发位置包——因为本实体是
     * {@link net.minecraft.world.entity.projectile.Projectile} 的子类，
     * 客户端侧 {@code Entity#lerpTo} 是硬吸附而非插值。客户端并非「不跑位移」：
     * 它在两次硬吸附之间用同步下来的速度自行外推一 tick（否则渲染插值退化成每秒 20 次跳变），
     * 且外推同样做方块碰撞，见 {@code TornadoEntity#advance()}。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<TornadoEntity>> TORNADO =
            ENTITIES.register("tornado", () -> EntityType.Builder
                    .<TornadoEntity>of(TornadoEntity::new, MobCategory.MISC)
                    .sized(2.8F, 3.3F)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .fireImmune()
                    .build("beloong:tornado"));

    /**
     * 地黄龙 NPC（{@code beloong:dihuang_loong}）。
     * <p>
     * 站桩生物：**有基础 AI 但默认不自主行动**、无敌、不可推动、永不消失。
     * AI 与全部定义性语义都在通用基类 {@link com.zonlong.beloong.entity.NpcEntity} 里，
     * 本类只负责类型绑定与碰撞箱；详见 {@link DihuangLoongEntity}。
     * <ul>
     *   <li>{@code MobCategory.MISC} = {@code ("misc", -1, true, true, 128)}：**不占刷怪上限**且持久；</li>
     *   <li>碰撞箱 1.5×2.5 取**身体主体段** —— 模型约 9 格长（含长尾），
     *       而 Minecraft 的碰撞箱是轴对齐方块，不可能贴合整条龙；</li>
     *   <li>{@code fireImmune()}：免疫火焰（无敌之外再省掉火焰伤害的结算）；</li>
     *   <li>刻意**不做刷怪蛋、不注册自然生成**（设计裁定 5）：只用 {@code /summon} 或结构放置。</li>
     * </ul>
     */
    public static final DeferredHolder<EntityType<?>, EntityType<DihuangLoongEntity>> DIHUANG_LOONG =
            ENTITIES.register("dihuang_loong", () -> EntityType.Builder
                    .<DihuangLoongEntity>of(DihuangLoongEntity::new, MobCategory.MISC)
                    .sized(1.5F, 2.5F)
                    .clientTrackingRange(10)
                    .fireImmune()
                    .build("beloong:dihuang_loong"));

    /**
     * 末（Mo）NPC（{@code beloong:mo}）。
     * <p>
     * 与地黄龙同为站桩生物：AI 与全部定义性语义都在通用基类
     * {@link com.zonlong.beloong.entity.NpcEntity}，本处只负责类型绑定与碰撞箱。
     * 资产由**本地模型暂存目录** {@code docs/models/} 迁移而来（该目录专门存放模型源文件、
     * 不入库）：几何与贴图取自 {@code docs/models/末2/}（2026-09-27 换用，逐字节拷贝），
     * 动画取自 {@code docs/models/末/} 并经多轮修整 —— YSM 玩家模型，
     * 逐项事实见 {@link com.zonlong.beloong.client.model.MoModel} 的类注释。
     * <ul>
     *   <li>{@code MobCategory.MISC} = 不占刷怪上限且持久 —— 布景不该和玩家的刷怪塔抢名额；</li>
     *   <li>碰撞箱 {@code 0.6 × 1.8} 取<b>玩家尺寸</b>：模型身体实测约 2 格高
     *       （脚底 y ≈ 0、头顶 y ≈ 32 单位），这是"贴身体"的取值。
     *       注意模型的**几何远大于碰撞箱**（缩放 0.80 后翅膀仍向后伸约 5.9 格），
     *       因此 {@link com.zonlong.beloong.entity.MoEntity#getBoundingBoxForCulling()}
     *       另做了扩大，否则翅膀会在画面里凭空消失；</li>
     *   <li>{@code fireImmune()}：无敌之外再省掉火焰伤害的结算；</li>
     *   <li>同样**不做刷怪蛋、不注册自然生成**，只用 {@code /summon} 或结构放置。</li>
     * </ul>
     */
    public static final DeferredHolder<EntityType<?>, EntityType<MoEntity>> MO =
            ENTITIES.register("mo", () -> EntityType.Builder
                    .<MoEntity>of(MoEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(10)
                    .fireImmune()
                    .build("beloong:mo"));

    /**
     * 死王仪式标记实体。
     * <p>
     * ⚠️ <b>{@code .sized(0.0F, 0.0F)} 与 {@code .clientTrackingRange(0)} 是承重结构，不是可调参数。</b>
     * {@code ChunkMap.addEntity} 的逻辑是
     * {@code int i = entitytype.clientTrackingRange() * 16; if (i != 0) { ...创建 TrackedEntity... }}
     * （{@code ChunkMap.java:1110-1121}）⇒ 传 0 时 <b>{@code ChunkMap} 连 {@code TrackedEntity} 都不会创建</b>，
     * 本实体从结构上不可能被发送到任何客户端（与玩家距离、人数、维度全无关），因此
     * <b>不需要注册渲染器、不需要模型层、也不需要覆写 {@code broadcastToPlayer}</b>。
     * <p>
     * 若有人照抄上面的龙卷风改成非 0（或加上 {@code .updateInterval(1)}），本实体会被发送，
     * 而 {@link net.minecraft.world.entity.Marker#getAddEntityPacket} 会直接 {@code throw}
     * ⇒ <b>服务端运行期崩溃</b>。那是刻意的响亮失败，不是待修的 bug。
     * <p>
     * 同理<b>不要</b>加 {@code .updateInterval(1)} —— 那是龙卷风为投射物同步加的，
     * 对永不发送客户端的本实体毫无意义。
     *
     * @see DreadKingRitualMarker
     */
    public static final DeferredHolder<EntityType<?>, EntityType<DreadKingRitualMarker>> DREAD_KING_RITUAL_MARKER =
            ENTITIES.register("dread_king_ritual_marker", () -> EntityType.Builder
                    .<DreadKingRitualMarker>of(DreadKingRitualMarker::new, MobCategory.MISC)
                    .sized(0.0F, 0.0F)
                    .clientTrackingRange(0)
                    .build("beloong:dread_king_ritual_marker"));

    /** 将实体注册到 Mod 事件总线。在 {@link BeLoongCore} 构造函数中调用。 */
    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
    }
}
