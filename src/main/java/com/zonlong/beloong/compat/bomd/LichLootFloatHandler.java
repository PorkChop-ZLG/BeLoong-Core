package com.zonlong.beloong.compat.bomd;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import java.util.Collection;

/**
 * 暗夜巫妖（祸乱鬼魅 / BOMD）死亡时，把掉落物<b>悬停在死亡点</b>并加上<b>原版描边</b>。
 *
 * <h3>为什么需要</h3>
 * 暗夜巫妖是会飞的 Boss，但它的掉落走<b>普通战利品表 + 普通 ItemEntity</b>
 * （{@code data/bosses_of_mass_destruction/loot_table/entities/lich.json}：{@code ancient_anima}
 * ×1、{@code bone} 3–6、{@code diamond} 5–7、附魔书池 {@code rolls: 5} ⇒ 一次死亡共 <b>8 件</b>）。
 * 死在空中时它们照常受重力下落并向上抛散，落到结构外或被地形挡住就很难再找到。
 *
 * <h3>为什么挂 {@link LivingDropsEvent} 而不是 mixin</h3>
 * 原版掉落管线的顺序是（NeoForge 21.1.236 源码 {@code LivingEntity.java:1462-1476}）：
 * {@code dropAllDeathLoot} → {@code captureDrops(new ArrayList<>())} → {@code dropFromLootTable}
 * （逐件 {@code spawnAtLocation}，<b>只进捕获列表、不进世界</b>）→ <b>派发本事件</b> → 未取消才
 * {@code drops.forEach(level::addFreshEntity)}。
 * <p>
 * ⇒ 本事件正处在「<b>实体已建好、但还没进世界</b>」这一点上，改位置、关重力、设描边都无需
 * {@code addFreshEntity} 之后再补救；也就<b>不需要任何 mixin</b>（本项目已多次因 mixin 的应用期错误复发）。
 *
 * <h3>为什么用注册 id 比较，而不是 {@code instanceof LichEntity}</h3>
 * {@code bosses_of_mass_destruction} 在本项目里是 <b>{@code localRuntime} 的可选依赖</b>。
 * 直接 import 它的类会让本类在<b>类校验期</b>解析该类型，BOMD 缺席时抛
 * {@code NoClassDefFoundError}（发生在进入方法体之前，外层 {@code try/catch} 捕获不到）。
 * 用 {@code BuiltInRegistries.ENTITY_TYPE.getKey(...)} 比对字符串则完全不需要那个类存在。
 *
 * <h3>为什么必须主动排布</h3>
 * {@code Entity#spawnAtLocation}（{@code Entity.java:1972-1983}）生成 ItemEntity 时
 * <b>没有随机水平偏移</b>——全部落在 {@code this.getX()/getY()/getZ()} 同一个点，平时靠构造器给的
 * 随机初速度散开。而本类会把速度归零、关掉重力 ⇒ 若不再排布，<b>8 件会精确重叠成一件</b>。
 *
 * <h3>三项改造的口径来自铁魔法</h3>
 * 铁魔法的 {@code DragonskinItem#createEntity} 给末影龙的龙鳞用
 * {@code setNoGravity(true)} + {@code setDeltaMovement(0,-0.01,0)} + {@code setGlowingTag(true)}。
 * 本类沿用同一组数值：{@code -0.01} 让物品保留一丝下沉手感（且很快被阻尼吸收，肉眼几乎静止），
 * {@code setGlowingTag} 置共享标志位 6 ⇒ 客户端渲染<b>原版白色描边</b>（颜色走
 * {@code Entity#getTeamColor} 默认值；本功能不写任何客户端代码）。
 *
 * <h3>刻意不做的事</h3>
 * <ul>
 *   <li><b>不改 {@code lifespan}</b>：保持原版 6000 tick（5 分钟）自然消失（用户裁定）。</li>
 *   <li><b>不 cancel 事件、不重建掉落</b>：数量、附魔、{@code looting} 全部维持原版。</li>
 *   <li><b>不加配置项</b>：默认生效（§十「默认不加配置项」）。</li>
 * </ul>
 *
 * <h3>异常安全</h3>
 * 事件派发之后紧接着就是 {@code drops.forEach(level::addFreshEntity)}（同一方法内）。
 * 若本 handler 抛异常，那一步不会执行 ⇒ <b>整批掉落丢失</b>。故整个方法体包
 * {@code try/catch(Throwable)}，失败只记 ERROR、让掉落原样落地。
 *
 * @see <a href="../../../../../docs/plans/2026-11-05-lich-loot-float-design.md">设计文档</a>
 */
public final class LichLootFloatHandler {

    /** 暗夜巫妖的注册 id；BOMD 缺席时该值永远匹配不到任何实体。 */
    private static final ResourceLocation LICH_ID =
            ResourceLocation.fromNamespaceAndPath("bosses_of_mass_destruction", "lich");

    /**
     * 圆环半径（格）。
     * <p>
     * 初版取 0.45（物品渲染约 0.25 宽，刚好互不遮挡），实机验收后用户认为**太挤**，
     * 按默认 8 件裁定改为 <b>2.0</b>（2026-11-05）：圆周 12.6 格 / 8 件 ⇒ 相邻间距约 1.57 格。
     */
    private static final double RING_RADIUS = 2.0D;

    /** 相对死亡点的抬高量（格）：略高于脚底，避免与方块面共面闪烁。 */
    private static final double RING_Y_OFFSET = 0.2D;

    /** 停滞后的残余下沉速度（格/tick）：与铁魔法同口径。 */
    private static final double RESIDUAL_SINK = -0.01D;

    @SubscribeEvent
    public void onLivingDrops(LivingDropsEvent event) {
        try {
            LivingEntity dead = event.getEntity();
            if (dead == null || !LICH_ID.equals(BuiltInRegistries.ENTITY_TYPE.getKey(dead.getType()))) {
                return;
            }
            Collection<ItemEntity> drops = event.getDrops();
            if (drops == null || drops.isEmpty()) {
                return;
            }

            int count = drops.size();
            // 单件时直接落在圆心：没有任何理由把它偏出去。
            double radius = count <= 1 ? 0.0D : RING_RADIUS;
            double centerX = dead.getX();
            double centerY = dead.getY() + RING_Y_OFFSET;
            double centerZ = dead.getZ();

            int index = 0;
            for (ItemEntity drop : drops) {
                if (drop != null) {
                    double angle = 2.0D * Math.PI * index / count;
                    drop.setPos(
                            centerX + radius * Math.cos(angle),
                            centerY,
                            centerZ + radius * Math.sin(angle));
                    drop.setNoGravity(true);
                    drop.setDeltaMovement(0.0D, RESIDUAL_SINK, 0.0D);
                    drop.setGlowingTag(true);
                }
                index++;
            }

            // 日志一律英文 ASCII（§十一）；用方块坐标便于按数值 grep。
            BeLoongCore.LOGGER.info("[BeLoong] lich loot: floated {} drop(s) at ({}, {}, {})",
                    count, dead.getBlockX(), dead.getBlockY(), dead.getBlockZ());
        } catch (Throwable t) {
            BeLoongCore.LOGGER.error(
                    "[BeLoong] lich loot: failed to float the drops, they will drop normally", t);
        }
    }
}
