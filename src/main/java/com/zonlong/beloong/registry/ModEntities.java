package com.zonlong.beloong.registry;

import com.zonlong.beloong.BeLoongCore;
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
