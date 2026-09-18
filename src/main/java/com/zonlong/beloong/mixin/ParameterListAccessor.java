package com.zonlong.beloong.mixin;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * {@link Climate.ParameterList} 的字段访问器。
 * <p>
 * 存在意义：{@code ParameterList} 的两个字段在字节码中都是 {@code private final}
 * （{@code javap -p} 已核对），而天灾维度剔除原版群系需要<strong>整体替换 {@code values}</strong>——
 * 把兜底树里的原版群系换成 BWG 群系。
 * <p>
 * <b>为什么必须换列表实例，而不能原地 {@code List.set()}：</b>
 * {@code ParameterList.clone()} 是浅拷贝，主世界与天灾维度的副本<strong>共享同一个
 * {@code values} 对象</strong>（TerraBlender 的 {@code MixinParameterList} 只把 {@code clone()}
 * 重写为 {@code super.clone()}）。原地修改会同时改掉主世界的参数列表，导致 BWG 群系泄漏回主世界。
 * <p>
 * {@code @Mutable @Accessor} 是 Mixin 处理 final 字段的标准手段；TerraBlender 自己的
 * {@code MultiNoiseBiomeSourceAccess} 就用同样手法改 {@code MultiNoiseBiomeSource.parameters}
 * 这个同样是 final 的字段。
 * <p>
 * <b>本接口只做字段暴露，不含任何业务逻辑。</b>业务逻辑放在
 * {@link com.zonlong.beloong.worldgen.DisasterBiomeSubstitution}——Mixin 禁止在
 * {@code beloong.mixins.json} 声明的 mixin 包内直接引用非 mixin 类。
 *
 * @see CloneParameterListMixin 消费方
 */
@Mixin(Climate.ParameterList.class)
public interface ParameterListAccessor {

    /**
     * 读取参数列表的底层 {@code (参数点, 群系)} 序列。
     *
     * @return 当前 {@code values} 列表
     */
    @Accessor("values")
    List<Pair<Climate.ParameterPoint, Holder<Biome>>> beloong$getValues();

    /**
     * 整体替换参数列表的底层 {@code (参数点, 群系)} 序列。
     * <p>
     * <b>调用方有责任传入一个新列表</b>，而不是就地修改后回传——见类文档。
     *
     * @param values 新的参数点序列
     */
    @Mutable
    @Accessor("values")
    void beloong$setValues(List<Pair<Climate.ParameterPoint, Holder<Biome>>> values);
}
