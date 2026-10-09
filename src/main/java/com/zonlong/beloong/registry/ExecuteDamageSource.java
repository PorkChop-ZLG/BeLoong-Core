package com.zonlong.beloong.registry;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateHandler;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.datagen.Translation;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.stage.DragonStage;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 斩杀专用的伤害来源：把死亡消息换成<b>跟随击杀者实际成长阶段</b>的版本。
 *
 * <h3>为什么不靠语言文件</h3>
 * 原版 {@code DamageSource#getLocalizedDeathMessage} 只给 {@code .player} 变体传两个参数
 * （受害者、击杀者），语言值里注入不了任何额外动态文本。而「远古」在 Dragon Survival 里
 * <b>是一个具体的成长阶段</b>（{@code dragon_stage.dragonsurvival.ancient}，来自内置
 * {@code ancient_stage} 数据包，「远古龙碾压 / 连锁采掘」也都是该阶段专属）——
 * 一条新生龙用斩杀打死怪却报「被远古龙被动斩杀」是错的。
 *
 * <p>所以这里<b>覆写</b> {@link DamageSource#getLocalizedDeathMessage(LivingEntity)}
 * （它是 public 非 final，见 1.21.1 {@code DamageSource.java:78}），把击杀者的真实阶段名作为
 * 第 3 个参数传进去。<b>不需要 mixin</b>。</p>
 *
 * <h3>三种分支</h3>
 * <table>
 *   <tr><th>情况</th><th>语言键</th><th>中文示例</th></tr>
 *   <tr><td>拿得到击杀者且他是龙</td><td>{@link #KEY_WITH_SOURCE}（3 参）</td>
 *       <td>僵尸被 Steve 的<b>成年龙</b>被动斩杀了</td></tr>
 *   <tr><td>拿得到击杀者但不是龙（例如中途变回人形）</td><td>{@link #KEY_UNKNOWN_SOURCE}（2 参）</td>
 *       <td>僵尸被 Steve 的龙族被动斩杀了</td></tr>
 *   <tr><td>拿不到击杀者</td><td>{@link #KEY_NO_SOURCE}（1 参）</td>
 *       <td>僵尸被龙族被动斩杀了</td></tr>
 * </table>
 *
 * <p>「击杀者」取自 {@code LivingEntity#getKillCredit()}（先看 {@code lastHurtByPlayer}），
 * 而 {@link ExecuteThresholdEffect} 在结算前会显式 {@code setLastHurtByPlayer} 钉死它，
 * 因此正常路径必然走第一分支。</p>
 *
 * @see ExecuteThresholdEffect#execute
 */
public class ExecuteDamageSource extends DamageSource {

    /** 拿不到击杀者时的死亡消息键（对应原版 {@code .player} 变体缺失的情形）。 */
    public static final String KEY_NO_SOURCE = "death.attack.beloong.execute";

    /** 击杀者是龙时的死亡消息键：{@code %s被%s的%s被动斩杀了}。 */
    public static final String KEY_WITH_SOURCE = "death.attack.beloong.execute.player";

    /** 击杀者存在但不是龙时的死亡消息键。 */
    public static final String KEY_UNKNOWN_SOURCE = "death.attack.beloong.execute.unknown_source";

    /**
     * 内置成长阶段的词条键前缀：{@code death.attack.beloong.execute.stage.<阶段路径>}。
     *
     * <p><b>为什么不用 DS 的 {@code dragon_stage.dragonsurvival.*} 再加一个「龙」字后缀</b>：
     * 那样一旦客户端把它译成带「龙」的名字（汉化资源包很常见），就会拼出「成年龙龙」。
     * 本模组自己出这四个词条，译文与拼接规则都归自己管，任何语言下都不会重复。
     * 内置阶段只有 newborn / young / adult / ancient 四个
     * （DS 的 {@code DragonStages}：newborn / young / adult，外加可选数据包 ancient_stage 的 ancient）。</p>
     */
    public static final String KEY_STAGE_PREFIX = "death.attack.beloong.execute.stage.";

    /** 阶段路径 → 本模组词条键。数据包自定义的阶段不在此表里，回退到 DS 自己的阶段名。 */
    private static final Map<String, String> BUILT_IN_STAGES = Map.of(
            "newborn", KEY_STAGE_PREFIX + "newborn",
            "young", KEY_STAGE_PREFIX + "young",
            "adult", KEY_STAGE_PREFIX + "adult",
            "ancient", KEY_STAGE_PREFIX + "ancient");

    public ExecuteDamageSource(final Holder<DamageType> type) {
        super(type);
    }

    @Override
    public Component getLocalizedDeathMessage(final LivingEntity victim) {
        LivingEntity killer = victim.getKillCredit();

        if (killer == null) {
            return Component.translatable(KEY_NO_SOURCE, victim.getDisplayName());
        }

        if (!(killer instanceof Player player)) {
            return Component.translatable(KEY_UNKNOWN_SOURCE, victim.getDisplayName(), killer.getDisplayName());
        }

        Component stage = dragonStageWord(player);
        if (stage == null) {
            return Component.translatable(KEY_UNKNOWN_SOURCE, victim.getDisplayName(), player.getDisplayName());
        }

        return Component.translatable(KEY_WITH_SOURCE, victim.getDisplayName(), player.getDisplayName(), stage);
    }

    /**
     * 击杀者的阶段词，形如「远古龙」/「Ancient dragon」。
     *
     * <p>阶段名走 DS 自己的语言键（{@code Translation.Type.STAGE.wrap}，会按阶段条目的命名空间
     * 替换前缀），所以数据包自定义的成长阶段也能正确显示，不需要在本模组里硬编码枚举。</p>
     *
     * @return 击杀者不是龙 / 拿不到阶段条目时返回 {@code null}
     */
    private static @Nullable Component dragonStageWord(final Player player) {
        DragonStateHandler handler = DragonStateProvider.getData(player);

        if (!handler.isDragon()) {
            return null;
        }

        Holder<DragonStage> stage = handler.stage();
        if (stage == null) {
            return null;
        }

        ResourceLocation id = stage.unwrapKey().map(key -> key.location()).orElse(null);
        if (id == null) {
            return null;
        }

        String ownKey = BUILT_IN_STAGES.get(id.getPath());
        if (ownKey != null) {
            // 「成年龙」/「远古龙」/「Adult dragon」……整词由本模组提供，不拼接、不会重复。
            return Component.translatable(ownKey);
        }

        // 数据包自定义的成长阶段：直接用 DS 自己的阶段名，**不再补字**
        // （补了就会跟对方译名里的「龙」重起来）。
        return Component.translatable(Translation.Type.STAGE.wrap(id));
    }
}
