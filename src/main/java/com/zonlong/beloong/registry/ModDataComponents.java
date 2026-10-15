package com.zonlong.beloong.registry;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组的<b>数据组件</b>（1.21 取代 NBT 的物品数据载体）注册中心。
 * <p>
 * 在 {@link BeLoongCore} 构造函数中经由 {@link #register(IEventBus)} 绑定到 Mod 事件总线。
 *
 * <h2>{@link #ESSENCE_SKILL}：元素魔源封存的龙之生存技能</h2>
 *
 * 十件元素魔源靠这一个组件承载"里面封存了哪个技能"；<b>没有这个组件 = 空魔源</b>
 * （与铁魔法「卷轴」同构：卷轴本身也没有默认的法术组件，空卷轴就是"没有组件"的那件物品）。
 *
 * <h3>为什么值存 {@link ResourceLocation} 而不是 {@code Holder<DragonAbility>}</h3>
 *
 * 龙之生存自己提供了 {@code DragonAbility.CODEC}（{@code RegistryFixedCodec}），看上去更省事，
 * 但技能定义在<b>数据包注册表</b>里：一旦某个数据包被移除（整合包改版、作者删技能），
 * {@code RegistryFixedCodec} 会<b>解码失败</b>，而组件解码失败会让<b>整个 {@code ItemStack} 反序列化失败</b>
 * —— 落盘时那一格物品直接变成空气。
 * <p>
 * 存纯 {@code ResourceLocation} 则永不失败：查不到技能时只是右击给出"技能已不存在"的提示，
 * 物品本身**不会被销毁**。这也是铁魔法卷轴的做法（它的 {@code SpellSlot} codec 存的是 {@code ResourceLocation}，
 * 查不到时回退到 {@code noneSpell}）。
 *
 * <h3>为什么三个 builder 方法都要写</h3>
 *
 * <ul>
 *   <li>{@code persistent(ResourceLocation.CODEC)}：<b>必须</b>，否则组件不落盘、退出重进就丢。</li>
 *   <li>{@code networkSynchronized(ResourceLocation.STREAM_CODEC)}：客户端要用它渲染 tooltip
 *       （技能名）与决定附魔光效，不写就拿不到。</li>
 *   <li>{@code cacheEncoding()}：值是不可变的小对象，缓存编码结果可省掉重复序列化。</li>
 * </ul>
 *
 * @see com.zonlong.beloong.item.effect.EssenceItem
 */
public final class ModDataComponents {

    /** 数据组件延迟注册器。 */
    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, BeLoongCore.MODID);

    /**
     * 魔源里封存的技能 ID（{@code beloong:essence_skill}）。
     * <p>
     * 类型是技能的资源位置（如 {@code beloong:execute}）；在
     * {@code dragonsurvival:dragon_ability} 注册表里查得 {@code Holder<DragonAbility>}。
     * <p>
     * <b>没有该组件 = 空魔源</b>；使用成功后由 {@code EssenceItem} 把它移除，于是魔源"变空"。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> ESSENCE_SKILL =
            COMPONENTS.registerComponentType("essence_skill", builder -> builder
                    .persistent(ResourceLocation.CODEC)
                    .networkSynchronized(ResourceLocation.STREAM_CODEC)
                    .cacheEncoding());

    private ModDataComponents() {}

    /** 将数据组件注册到 Mod 事件总线。 */
    public static void register(IEventBus bus) {
        COMPONENTS.register(bus);
    }
}
