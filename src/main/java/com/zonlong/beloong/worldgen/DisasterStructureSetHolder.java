package com.zonlong.beloong.worldgen;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * 承载覆写后 {@link StructureSet} 的注册表风格引用。
 * <p>
 * 原版 {@link HolderLookup#listElements()} 声明返回
 * {@code Stream<Holder.Reference<StructureSet>>}，而
 * {@link Holder#direct(Object)} 返回的是非引用实现——无法通过该签名。
 * 这里以子类复用 {@link Holder.Reference} 的受保护构造器，在保持
 * "引用"类型身份（{@code key()}、{@code kind()} 语义完整）的同时携带
 * 天灾维度专用的覆写值。
 * <p>
 * 本类<b>只</b>存在于天灾维度的结构状态机里，不进入注册表，不影响其它维度。
 */
public final class DisasterStructureSetHolder extends Holder.Reference<StructureSet> {

    /**
     * @param owner 数据来源注册表（用于保持 {@code canSerializeIn} 语义），
     *              传 {@code BuiltInRegistries.STRUCTURE_SET}
     * @param key   原结构集的注册键（保持白名单日志与身份语义）
     * @param value 覆写 placement 后的 {@link StructureSet}
     */
    public DisasterStructureSetHolder(HolderOwner<StructureSet> owner,
                                      ResourceKey<StructureSet> key,
                                      StructureSet value) {
        super(Holder.Reference.Type.STAND_ALONE, owner, key, value);
    }
}
