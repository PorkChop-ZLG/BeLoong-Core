package com.zonlong.beloong.client;

import by.dragonsurvivalteam.dragonsurvival.client.sounds.FollowEntitySound;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.entity.TornadoEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 龙卷风实体的持续音效（<b>纯客户端</b>）：给每一股风柱挂一条循环播放的鞘翅滑翔音。
 *
 * <h3>为什么是客户端实例，而不是 {@code playSound}</h3>
 * <ul>
 *   <li>原版鞘翅滑翔音（{@link SoundEvents#ELYTRA_FLYING}）是<b>循环素材</b>，靠服务端定时重播
 *       必然出现断口或重叠；只有 {@code looping} 的音效实例才能无缝循环。</li>
 *   <li>服务端重播还要每间隔给每个玩家发一个声音包；客户端实例是<b>零网络开销</b>。</li>
 * </ul>
 *
 * <h3>为什么直接用 DS 的 {@link FollowEntitySound}</h3>
 * 它已经是「构造器收任意 {@link Entity} + {@code looping = true} + 每 tick 跟随位置」，
 * 且默认音量 / 音高正是 {@code 1.0F} / {@code 1.0F}、衰减 {@code LINEAR}（见
 * {@code AbstractSoundInstance:17-18}）⇒ <b>不需要新增实体、不需要音频资产、不需要语言键</b>。
 *
 * <p>代价两条，都是刻意接受的：① 复用原版条目 ⇒ <b>没有字幕</b>，且可听距离受原版该条目的
 * {@code attenuation_distance} 限制；② 引用的是 DS 的<b>内部类</b> ⇒ DS 若改名/改签名会在
 * <b>编译期</b>暴露（不会静默失效），届时照抄它 25 行自建一个即可。</p>
 *
 * <h3>同一施法者最多一条循环（用户 2026-10-11 裁定）</h3>
 * 冷却随等级递减、寿命随等级递增 ⇒ 高等级会<b>同时存在两股风柱</b>。两条一模一样的循环相位不同，
 * 叠加起来是 +6dB 的「打夯」感，故按<b>施法者</b>去重：只跟<b>剩余寿命最长</b>的那一股
 * （DS 自己的 {@code ClientProxy#queueTickingSound} 也是「同 id 换新前先停旧的」这一写法）。
 *
 * <h3>停止只有一条路径：每 tick 兜底清扫</h3>
 * {@code AbstractTickableSoundInstance#stop()} 是 {@code protected final}，外部停不掉，
 * 只能用 {@code SoundManager#stop(SoundInstance)}（DS 同款）。而 {@link FollowEntitySound#tick()}
 * <b>不会自己停</b>，漏一次就留下一只永远在响的风 ⇒ 这里不用 {@code EntityLeaveLevelEvent}
 * 另记一套账，而是每 tick 检查「跟的那股风还在不在」：不在就停 + 清账，
 * 然后（<b>仅当真的停了一条</b>时）在场上去找同一施法者剩下的最新一股顶上。
 * 代价是最多 1 刻的延迟，收益是<b>没有第二条可能漏停的路径</b>。
 *
 * <h3>⚠️ 已知风险</h3>
 * 本仓库实测过 {@code minecraft:item.elytra.flying} 在 DS 的 {@code activation.sound.start}
 * 路径下静音（那条路绑在 {@code currentTick == castTime} 的精确相等上，且服务端那支按原版约定
 * 排除施法者本人）。此处走的是完全不同的路径（客户端音效实例），但若进游戏仍然无声，
 * <b>先换一个对照音效确认是音效问题还是路径问题</b>，不要在此类里绕。
 *
 * @see com.zonlong.beloong.entity.TornadoEntity
 * @see FollowEntitySound
 */
public final class TornadoSoundHandler {

    /** 施法者 → 当前正在跟的那股风柱（键见 {@link #keyOf}）。 */
    private static final Map<UUID, Loop> LOOPS = new HashMap<>();

    /**
     * 当前跟的龙卷风实体 id + 音效实例。
     *
     * @param tornadoId 被跟随的实体网络 id（只用来查它是否还在，不持有实体引用）
     * @param sound     该风柱的音效实例
     */
    private record Loop(int tornadoId, FollowEntitySound sound) {}

    /**
     * 供 {@code BeLoongCoreClient}（<b>不同包</b>）在客户端构造期实例化并注册。
     *
     * <p>刻意显式写成 {@code public}：本类没有可用的静态入口，实例化是唯一用法，
     * 而默认构造器在本类被声明为 {@code public} 时才是 {@code public}——写 {@code private}
     * 会直接编译失败（2026-10-11 实际踩过）。</p>
     */
    public TornadoSoundHandler() {}

    /**
     * 风柱进入客户端世界时开始（或改跟）一条循环。
     *
     * <p>事件双端都会发（见 {@code perf/EffectEntityJoinGate} 的 javadoc），这里只认客户端。</p>
     */
    @SubscribeEvent
    public void onEntityJoinLevel(final EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ClientLevel level && event.getEntity() instanceof TornadoEntity tornado) {
            consider(level, tornado);
        }
    }

    /**
     * 每 tick 兜底：跟丢就停。
     *
     * <p>也是唯一的停止路径（见类注释）。{@code LOOPS} 为空时立即返回，常态开销为一次判空。</p>
     */
    @SubscribeEvent
    public void onLevelTick(final LevelTickEvent.Post event) {
        if (LOOPS.isEmpty() || !(event.getLevel() instanceof ClientLevel level)) {
            return;
        }

        List<UUID> dropped = null;
        Iterator<Map.Entry<UUID, Loop>> iterator = LOOPS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Loop> entry = iterator.next();
            if (level.getEntity(entry.getValue().tornadoId()) instanceof TornadoEntity tornado
                    && !tornado.isRemoved()) {
                continue;
            }

            stopSound(entry.getValue().sound());
            iterator.remove();
            if (dropped == null) {
                dropped = new ArrayList<>();
            }
            dropped.add(entry.getKey());
        }

        if (dropped == null) {
            return;
        }

        // 只在真的停掉了一条时才扫全场（罕见路径，不在每 tick 的常态开销里）：
        // 被停的若是「最新的那股」，同一施法者可能还有一股活着的风需要顶上。
        for (UUID key : dropped) {
            TornadoEntity next = findLatest(level, key);
            if (next != null) {
                start(key, next);
            }
        }
    }

    /** 世界卸载（掉线/换存档）时清账：音效实例已随世界一起没了，留着只会让陈旧 id 落到新世界。 */
    @SubscribeEvent
    public void onClientLoggingIn(final ClientPlayerNetworkEvent.LoggingIn event) {
        LOOPS.clear();
    }

    /**
     * 决定是否要为这只风柱（重）起一条循环。
     *
     * <ul>
     *   <li>该施法者还没有循环 ⇒ 起；</li>
     *   <li>跟的就是同一只实体 id ⇒ <b>重开</b>——跨区块重载后实体换了对象，
     *       旧实例跟的是那个已经不在世界里的旧对象，会僵在原地；</li>
     *   <li>连发：只在新的这只<b>剩余寿命更长</b>时才换（避免重载时旧风柱把新风柱顶掉）。</li>
     * </ul>
     */
    private static void consider(final ClientLevel level, final TornadoEntity tornado) {
        UUID key = keyOf(tornado);
        Loop current = LOOPS.get(key);
        if (current == null || current.tornadoId() == tornado.getId()) {
            start(key, tornado);
            return;
        }

        if (level.getEntity(current.tornadoId()) instanceof TornadoEntity tracked
                && tracked.getRemainingLife() >= tornado.getRemainingLife()) {
            return;
        }

        start(key, tornado);
    }

    /** 起一条循环（同键已有实例就先停掉，保证同一施法者只有一条在响）。 */
    private static void start(final UUID key, final TornadoEntity tornado) {
        Loop previous = LOOPS.get(key);
        if (previous != null) {
            stopSound(previous.sound());
        }

        FollowEntitySound sound = new FollowEntitySound(SoundEvents.ELYTRA_FLYING, SoundSource.NEUTRAL, tornado);
        Minecraft.getInstance().getSoundManager().play(sound);
        LOOPS.put(key, new Loop(tornado.getId(), sound));

        BeLoongCore.LOGGER.debug("[BeLoong] tornado: ambient loop started for entity {} (caster {})",
                tornado.getId(), key);
    }

    /** 场上同一施法者剩余寿命最长的那股风柱；没有就返回 {@code null}。 */
    private static TornadoEntity findLatest(final ClientLevel level, final UUID key) {
        TornadoEntity best = null;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof TornadoEntity tornado && !tornado.isRemoved()
                    && keyOf(tornado).equals(key)
                    && (best == null || tornado.getRemainingLife() > best.getRemainingLife())) {
                best = tornado;
            }
        }

        return best;
    }

    /**
     * 去重键：施法者 UUID。
     *
     * <p>正常施法必定有主（{@code TornadoEffect#apply} 里 {@code setOwner(owner)}）。无主时
     * （理论上只可能出现在施法者已不在场的读盘实体上）退化成「每只风柱各自一键」的合成 UUID，
     * 于是它们互不顶掉——那种情况下没有「同一施法者」可言。</p>
     */
    private static UUID keyOf(final TornadoEntity tornado) {
        Entity owner = tornado.getOwner();
        return owner != null ? owner.getUUID() : new UUID(0L, tornado.getId());
    }

    /** 外部停止的唯一入口（{@code AbstractTickableSoundInstance#stop()} 是 {@code protected final}）。 */
    private static void stopSound(final FollowEntitySound sound) {
        Minecraft.getInstance().getSoundManager().stop(sound);
    }
}
