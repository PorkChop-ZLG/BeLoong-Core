package com.zonlong.beloong.entity;

import com.zonlong.beloong.Config;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import io.redspace.ironsspellbooks.entity.mobs.dead_king_boss.DeadKingBoss;
import io.redspace.ironsspellbooks.registries.SoundRegistry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 黯影宝库「死王仪式」的标记实体（{@code beloong:dread_king_ritual_marker}）。
 *
 * <h2>职责</h2>
 * 由 {@code dreadking/DreadKingRitualStarter} 在黯影宝库顶部生成，然后：
 * <ol>
 *   <li>首个 tick：向周围广播一次 {@code irons_spellbooks:entity.dead_king.music.intro}
 *       （半径与响度由 {@link Config.DreadKingRitual#musicVolume} 同时决定）</li>
 *   <li>之后每 tick 倒数 {@link #LIFETIME_TICKS}</li>
 *   <li>倒数归零：在自身位置召唤<b>不祥</b>状态的死者之王，然后移除自身</li>
 * </ol>
 *
 * <h2>为什么继承原版 {@link Marker}</h2>
 * {@code Marker} 是「玩家看不见的锚点」的原版官方载体，其 {@code getAddEntityPacket} 直接抛异常
 * （{@code Marker.java:38-41}）。而「不发到客户端」的<b>硬保证</b>其实在 {@code ChunkMap.addEntity}：
 * <pre>
 *   int i = entitytype.clientTrackingRange() * 16;
 *   if (i != 0) { ...创建 TrackedEntity... }        // ChunkMap.java:1110-1121
 * </pre>
 * ⇒ 配合 {@code .sized(0.0F, 0.0F).clientTrackingRange(0)}（见 {@code registry/ModEntities}），
 * 连 {@code TrackedEntity} 都不会被创建，与玩家距离、人数、维度<b>全都无关</b>。
 * 因此本实体不需要渲染器、不需要注册模型层、也不需要覆写 {@code broadcastToPlayer}。
 * <p>
 * <b>⚠️ 警告：{@code ModEntities} 里那两个 Builder 参数是承重结构，不是可调参数。</b>
 * 若有人把 {@code clientTrackingRange} 改成非 0，本实体会被发送到客户端，
 * {@code Marker.getAddEntityPacket} 的 {@code throw} 会让<b>服务端崩溃</b>——这是刻意的响亮失败。
 *
 * <h2>⚠️ {@link #tick()} 刻意不调用 {@code super.tick()} / {@code baseTick()}</h2>
 * {@code Marker.tick()} 是空实现且不调 {@code Entity.baseTick()}（{@code Marker.java:20-22} vs
 * {@code Entity.java:430-432}），本类<b>有意继承</b>这一语义。收益是免疫 {@code baseTick()} 内的
 * {@code handlePortal()}（{@code Entity.java:448}）⇒ 标记实体不会被传送门搬走，避免
 * 「宝库顶旁边就是传送门 ⇒ 仪式在传送门另一端触发」。代价均已逐条排除：
 * <ul>
 *   <li>{@code firstTick} 永远为 {@code true}（只在 {@code baseTick()} 里被清，{@code Entity.java:490}）
 *       —— 其全部消费者中唯一实际生效的是 {@code isInLava()} 恒为 false，而这对
 *       {@code noPhysics} 实体本就是正确答案（{@code Entity.java:1394}）</li>
 *   <li>{@code checkBelowWorld()} 不执行 ⇒ 不会被虚空移除；锚点取自真实方块坐标，无实际影响</li>
 * </ul>
 * <b>改动此处前请先读设计文档 {@code docs/plans/2026-09-20-dark-vault-dread-king-ritual-design.md}
 * §五 的「原版 Marker 源码核对结论」表。</b>
 *
 * <h2>计时</h2>
 * 唯一合法计时源是落 NBT 的 {@link #lifeTicks}。<b>不要</b>改用 {@code tickCount}：
 * 它虽然会照常自增（其自增点在 {@code ServerLevel.tickNonPassenger:772}，<b>不在</b>
 * {@code Entity.baseTick()}），但 {@code Entity.saveWithoutId} 并不把它写进 NBT ⇒ 读档归零。
 * 同理，{@link #musicPlayed} 也必须落盘：区块重载后重放音乐会让「音乐从头发、倒计时从中间走」，
 * 违背「实体生命周期 = 音乐长度」这一设计前提。
 *
 * @see com.zonlong.beloong.dreadking.DreadKingRitualStarter
 * @see com.zonlong.beloong.registry.ModEntities#DREAD_KING_RITUAL_MARKER
 */
public class DreadKingRitualMarker extends Marker {

    private static final Logger LOGGER = LoggerFactory.getLogger(DreadKingRitualMarker.class);

    /**
     * 仪式时长，单位 tick，等于 intro 音乐的长度。
     * <p>
     * 取值依据（两路独立取证）：铁魔法 {@code DeadKingMusicHandler.INTRO_LENGTH_MILIS = 17600}
     * （17.600 s）；实际依赖 jar 内 {@code dead_king/music/intro.ogg} 的 Ogg 末页 granule
     * 778368 @ 44100 Hz（17.650 s）。本条是<b>客观音乐长度</b>而非玩法参数，故硬编码、不做配置。
     */
    public static final int LIFETIME_TICKS = 352;

    /** 剩余 tick 数。必须落 NBT，见类 javadoc「计时」。 */
    private int lifeTicks = LIFETIME_TICKS;

    /** 音乐是否已广播过。必须落 NBT，防止区块重载/服务器重启后重放。 */
    private boolean musicPlayed;

    /** NBT 键名。 */
    private static final String TAG_LIFE_TICKS = "LifeTicks";
    private static final String TAG_MUSIC_PLAYED = "MusicPlayed";

    public DreadKingRitualMarker(EntityType<? extends DreadKingRitualMarker> type, Level level) {
        super(type, level);
    }

    @Override
    public void tick() {
        // 本实体永不发送到客户端，故 isClientSide 理论恒为 false；保留判断以免将来误用。
        if (level().isClientSide) {
            return;
        }

        if (!musicPlayed) {
            playIntroMusic();
            musicPlayed = true;
        }

        if (--lifeTicks > 0) {
            return;
        }

        // 恰好尝试一次：无论成败都在 finally 里移除自身。
        //
        // 本 try/catch 不是防御性冗余，而是必需的：Level.guardEntityTick（Level.java:607-621）
        // 捕获 tick 内的异常后，会依 NeoForgeConfig.SERVER.removeErroringEntities 行事 ——
        // 该配置默认 false ⇒ 抛 ReportedException 崩掉整个服务端；被管理员设为 true ⇒
        // 静默 discard 掉出错实体、仪式无声消失。两种结局都不可接受，故自己兜住：
        // 留下一条带坐标与维度的 ERROR 日志，且不会在 tick 里反复抛。
        try {
            summonDeadKing();
        } catch (Exception e) {
            LOGGER.error("[BeLoong] dread_king_ritual: failed to summon the ominous Dead King at {} {}",
                    level().dimension().location(), position(), e);
        } finally {
            discard();
        }
    }

    /**
     * 广播仪式音乐。
     * <p>
     * 用 {@code Level.playSound} 而非 {@code ServerPlayer.playNotifySound}：本仪式面向
     * 「周围所有玩家」（唱片机语义），只有开启者听得到是不对的。
     * <p>
     * ⚠️ {@code musicVolume} 同时决定响度与可闻半径，二者无法解耦，推导见设计文档 §五。
     */
    private void playIntroMusic() {
        level().playSound(null, getX(), getY(), getZ(),
                SoundRegistry.DEAD_KING_MUSIC_INTRO.get(),
                SoundSource.RECORDS,
                Config.DreadKingRitual.musicVolume.get().floatValue(),
                1.0F);
    }

    /**
     * 在自身位置召唤<b>不祥</b>状态的死者之王。
     * <p>
     * 序列逐字复刻铁魔法自己的复活路径 {@code DeadKingCorpseEntity.java:75-90}，
     * 其中两处是<b>必须</b>的：
     * <ul>
     *   <li>{@code setSpawnPos} —— {@code DeadKingBoss.tickDeath()} 只在 {@code spawnPos != null}
     *       时才生成灵体（{@code DeadKingBoss.java:706-718}）。漏掉它 ⇒ 打死死王后没有灵体可右键，
     *       <b>护命匣复活链静默断裂</b>，且要打到第二条命才会发现</li>
     *   <li>{@code onOminousTrigger()} —— 它是 {@code IOminousEntity} 的 public API，
     *       内部即 {@code setIsOminous(true)} + 6 项属性 modifier + {@code setBaseValue(1000)} + 满血，
     *       与尸体路径同一条码，且重复调用幂等。
     *       <b>不要</b>改用「给玩家挂 Trial Omen」：那条路要求玩家非创造非旁观、24 格内、
     *       且只在实体加入那一瞬判定（{@code ServerPlayerEvents.java:705-738}）</li>
     * </ul>
     */
    private void summonDeadKing() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }

        DeadKingBoss boss = new DeadKingBoss(serverLevel);
        boss.moveTo(position());
        boss.setSpawnPos(boss.position());
        boss.finalizeSpawn(serverLevel,
                serverLevel.getCurrentDifficultyAt(boss.getOnPos()),
                MobSpawnType.TRIGGERED, null);
        boss.setPersistenceRequired();
        serverLevel.addFreshEntity(boss);
        boss.onOminousTrigger();

        MagicManager.spawnParticles(serverLevel, ParticleTypes.SCULK_SOUL,
                getX(), getY() + 2.5, getZ(), 80, .2, .2, .2, .25, true);
        serverLevel.playSound(null, getX(), getY(), getZ(),
                SoundRegistry.DEAD_KING_SPAWN.get(), SoundSource.MASTER, 20, 1);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        // 必须调 super：Marker 用它保存自己的 data 标签。
        super.addAdditionalSaveData(tag);
        tag.putInt(TAG_LIFE_TICKS, lifeTicks);
        tag.putBoolean(TAG_MUSIC_PLAYED, musicPlayed);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        // 缺键时保留默认值（例如来自其它来源的 NBT），避免被清零后立即触发。
        if (tag.contains(TAG_LIFE_TICKS)) {
            lifeTicks = tag.getInt(TAG_LIFE_TICKS);
        }
        musicPlayed = tag.getBoolean(TAG_MUSIC_PLAYED);
    }
}
