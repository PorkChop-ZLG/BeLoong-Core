package com.zonlong.beloong.registry;

import com.zonlong.beloong.BeLoongCore;
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
 * </ul>
 *
 * @see BeLoongCore
 * @see TornadoEntity
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

    /** 将实体注册到 Mod 事件总线。在 {@link BeLoongCore} 构造函数中调用。 */
    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
    }
}
