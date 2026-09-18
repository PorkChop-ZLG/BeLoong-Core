package com.zonlong.beloong.ability;

import by.dragonsurvivalteam.dragonsurvival.registry.DSAttributes;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.entity_effects.AbilityEntityEffect;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zonlong.beloong.entity.TornadoEntity;
import com.zonlong.beloong.registry.ModEntities;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 「龙卷风」技能效果：向施法者准星方向发射一个 {@link TornadoEntity}。
 * <p>
 * 与 {@code AirStrikeEffect} 同构：{@code record implements AbilityEntityEffect} + {@code MapCodec}，
 * 所有数值字段都是 {@link LevelBasedValue}，让 6 个玩法参数全部随技能等级缩放。
 * 伤害在<b>这里</b>乘上 {@code DSAttributes.DRAGON_ABILITY_DAMAGE}，实体本身不碰属性系统。
 * <p>
 * <b>调用契约：一次调用恰好生成一个龙卷风，因此必须配合单目标行动使用
 * （出货 JSON 用的是 {@code dragonsurvival:self}）。</b>
 * {@link #apply} 故意不使用它的 {@code Entity target} 参数——龙卷风总是从施法者自身的位置
 * 与准星方向生成，与命中的是谁无关；在 {@code dragonsurvival:self} 下 DS 的 {@code SelfTarget}
 * 只会调用 {@code apply} 一次，所以忽略该参数是安全的。危险在于本效果类型
 * {@code beloong:tornado} 是数据包可派发的公开类型：换用 {@code area} / {@code disc} /
 * {@code dragon_breath} 等多目标行动时，每个匹配到的实体都会调用一次 {@code apply}，
 * 一次施法就会放出 N 个龙卷风。这是数据包作者需要知道的契约，代码里不做拦截：
 * 加运行时守卫会让「单目标复用」这条路也付出代价。
 */
public record TornadoEffect(
        LevelBasedValue damagePerHit,
        LevelBasedValue lifetime,
        LevelBasedValue pullRadius,
        LevelBasedValue damageRadius,
        LevelBasedValue speed,
        LevelBasedValue pullStrength
) implements AbilityEntityEffect {

    /** 同时支持纯数字和 LevelBasedValue 对象格式（与 AirStrikeEffect 一致） */
    private static final Codec<LevelBasedValue> FLEXIBLE_LBV = Codec.either(
            LevelBasedValue.CODEC,
            Codec.DOUBLE
    ).xmap(
            either -> either.map(lbv -> lbv, d -> LevelBasedValue.constant((float) (double) d)),
            Either::left
    );

    public static final MapCodec<TornadoEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            FLEXIBLE_LBV.fieldOf("damage_per_hit").forGetter(TornadoEffect::damagePerHit),
            FLEXIBLE_LBV.fieldOf("lifetime").forGetter(TornadoEffect::lifetime),
            FLEXIBLE_LBV.fieldOf("pull_radius").forGetter(TornadoEffect::pullRadius),
            FLEXIBLE_LBV.fieldOf("damage_radius").forGetter(TornadoEffect::damageRadius),
            FLEXIBLE_LBV.fieldOf("speed").forGetter(TornadoEffect::speed),
            FLEXIBLE_LBV.fieldOf("pull_strength").forGetter(TornadoEffect::pullStrength)
    ).apply(instance, TornadoEffect::new));

    @Override
    public void apply(final ServerPlayer dragon, final DragonAbilityInstance ability, final Entity target) {
        int level = ability.level();

        double abilityScale = dragon.getAttributeValue(DSAttributes.DRAGON_ABILITY_DAMAGE);
        float damage = (float) (this.damagePerHit.calculate(level) * abilityScale);
        int life = (int) this.lifetime.calculate(level);
        double pullR = this.pullRadius.calculate(level);
        double damageR = this.damageRadius.calculate(level);
        double strength = this.pullStrength.calculate(level);
        float projectileSpeed = this.speed.calculate(level);

        Vec3 look = dragon.getLookAngle();
        // 发射高度照抄灾变：眼高 − 0.5。灾变原版把生成点偏到玩家侧面，那是它的 bug，不复刻。
        Vec3 spawn = new Vec3(
                dragon.getX() + look.x,
                dragon.getEyeY() - 0.5D,
                dragon.getZ() + look.z);

        TornadoEntity tornado = new TornadoEntity(
                ModEntities.TORNADO.get(), dragon.serverLevel(), dragon,
                damage, pullR, damageR, strength, life);
        tornado.setPos(spawn);
        tornado.setDeltaMovement(look.scale(projectileSpeed));
        dragon.serverLevel().addFreshEntity(tornado);
    }

    @Override
    public MapCodec<? extends AbilityEntityEffect> entityCodec() {
        return CODEC;
    }

    /**
     * 技能详情里那几行动态数值。语言键是单条格式串，四个 {@code %s} 依次为
     * 每次伤害 / 持续时间（秒）/ 吸引半径 / 伤害半径。
     * <p>
     * <b>数值刻意不设任何颜色，让它继承工具提示的默认灰色。</b>
     * 语言文件控制不了它：{@code Component.translatable} 把每个 {@code %s} 参数作为<b>独立的子组件</b>
     * 渲染（非组件参数被包成 {@code Component.literal(字符串)}，样式为空），而 {@code §} 颜色码由
     * {@code StringDecomposer} <b>逐个叶子文本</b>解析、<b>不跨组件边界传递</b> —— 所以写在 {@code %s}
     * <b>之前</b>的 {@code §a} 后面没有任何文本可作用，只会落到继承来的灰色上。
     * <p>
     * 代码里同样不去硬编码颜色：`TornadoEffect` 与 `AirStrikeEffect` 的详情外观应当一致，
     * 单独把龙卷风的数值染绿会让两个技能看起来不像同一套。详情里的配色由语言键负责
     * （标签 {@code §6}、公式术语 {@code §9}），数值统一留灰。
     */
    @Override
    public List<MutableComponent> getDescription(final Player dragon, final DragonAbilityInstance ability) {
        // LevelBasedValue.Lookup 不接受 level 0（会索引 values.get(-1) 崩溃），
        // 未学习能力时按 1 级展示数值。
        int level = Math.max(DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS, ability.level());
        return List.of(
                Component.translatable("dragon_ability.beloong.tornado.dynamic_desc",
                        String.format("%.1f", this.damagePerHit.calculate(level)),
                        formatSeconds((int) this.lifetime.calculate(level)),
                        String.format("%.1f", this.pullRadius.calculate(level)),
                        String.format("%.1f", this.damageRadius.calculate(level))));
    }

    /**
     * tick 换算成秒，最多两位小数并去掉末尾的 0。
     * <p>
     * 100 tick → {@code "5"}；115 → {@code "5.75"}；130 → {@code "6.5"}；175 → {@code "8.75"}。
     */
    private static String formatSeconds(final int ticks) {
        double seconds = ticks / 20.0D;
        if (seconds == Math.floor(seconds)) {
            return String.format("%.0f", seconds);
        }
        String text = String.format("%.2f", seconds);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
