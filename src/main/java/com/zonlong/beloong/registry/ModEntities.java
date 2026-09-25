package com.zonlong.beloong.registry;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.entity.DihuangLoongEntity;
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

    /** 将实体注册到 Mod 事件总线。在 {@link BeLoongCore} 构造函数中调用。 */
    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
    }
}
