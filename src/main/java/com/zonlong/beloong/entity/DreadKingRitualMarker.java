package com.zonlong.beloong.entity;

import com.zonlong.beloong.Config;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import io.redspace.ironsspellbooks.entity.mobs.dead_king_boss.DeadKingBoss;
import io.redspace.ironsspellbooks.registries.SoundRegistry;
import io.redspace.ironsspellbooks.util.ParticleHelper;
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
 *   <li>首个 tick：向周围广播一次 {@code irons_spellbooks:entity.dead_king.music.suspense}
 *       （半径与响度由 {@link Config.DreadKingRitual#musicVolume} 同时决定）</li>
 *   <li>之后每 tick 倒数 {@link #LIFETIME_TICKS}，并每 {@link #PARTICLE_INTERVAL_TICKS} tick
 *       在自身周围半径 {@link #PARTICLE_RADIUS} 格的圆盘内铺一层
 *       {@code irons_spellbooks:blood_ground} 血渍</li>
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
     * 仪式时长，单位 tick，等于仪式音乐
     * {@code irons_spellbooks:entity.dead_king.music.suspense} 的长度。
     * <p>
     * 取值依据：实际依赖 jar 内 {@code dead_king/music/suspense.ogg} 的 Ogg <b>完整页扫描</b>
     * （57 页、末页 type=4 即 EOS）—— 实测 <b>7.01 s</b>（granule / 44100 = 140.2 tick）。
     * 用户要求「凑整数」⇒ 取 {@code 140}（与实测差 0.01 s，不可闻）。
     * 本条是<b>客观音频长度</b>而非玩法参数，故硬编码、不做配置。
     * <p>
     * ⚠️ <b>换音轨时必须同步改这里</b>，否则「仪式时长 = 音效长度」这条设计前提就断了（设计文档 D3）。
     */
    public static final int LIFETIME_TICKS = 140;

    /** 仪式期间铺血粒子的发射间隔（tick）。140 / 5 = 28 次，约 160 颗/秒。 */
    private static final int PARTICLE_INTERVAL_TICKS = 5;

    /** 每次发射的粒子数。 */
    private static final int PARTICLES_PER_BURST = 40;

    /** 血渍圆盘半径（格）。用户指定「以标记实体为中心、半径 4 格的圆」。 */
    private static final double PARTICLE_RADIUS = 4.0D;

    /**
     * 血渍的生成高度偏移（格）：在标记实体<b>上方</b>这么高处生成，靠重力落成「血向下滴落」的效果。
     * <p>
     * {@code BloodGroundParticle} 自带 {@code gravity = 1.0F} 且 {@code Particle.hasPhysics} 默认为
     * {@code true}，因此从高处生成后会自然下坠并停在下方的表面上。
     */
    private static final double PARTICLE_HEIGHT_OFFSET = 2.0D;

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
            playRitualAudio();
            musicPlayed = true;
        }

        // 仪式全程在周围铺血。用 lifeTicks 取模而不是另开计数器：它就是本次仪式的进度，
        // 且读档后仍连续（lifeTicks 落 NBT）。
        if (lifeTicks % PARTICLE_INTERVAL_TICKS == 0) {
            emitRitualParticles();
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
     * 广播仪式音乐（{@code entity.dead_king.music.suspense}），在实体生命周期内<b>只播一次</b>。
     * <p>
     * 选这条音轨的理由：IS 的 Dead King 音轨里**每一条可用的都被 IS 自己用了**，而 suspense 的
     * 重复频率最低 —— 它只在「阶段转换（半血）」时作为 {@code transitionMusic} 播放
     * （{@code DeadKingMusicHandler:42}），因此不会与 Boss <b>出场</b>时的 {@code intro} 撞车。
     * 全部候选音轨的实测长度与使用点盘点见设计文档 §一 与 §八。
     * <p>
     * 用 {@code Level.playSound} 而非 {@code ServerPlayer.playNotifySound}：本仪式面向
     * 「周围所有玩家」（唱片机语义），只有开启者听得到是不对的。
     * <p>
     * <b>已知并接受的局限</b>：音效包只在生成瞬间发一次 ⇒ 迟到或重进的玩家听不到。
     * 这是结构性的（{@code SoundInstance} 只有 {@code getDelay()}、<b>没有 seek</b>，任何"补发"
     * 都只能从头重播），用户已确认接受。**不要**试图靠"重发"来修它。
     * <p>
     * ⚠️ {@code musicVolume} 同时决定响度与可闻半径，二者无法解耦，推导见设计文档 §五。
     */
    private void playRitualAudio() {
        level().playSound(null, getX(), getY(), getZ(),
                SoundRegistry.DEAD_KING_SUSPENSE.get(),
                SoundSource.RECORDS,
                Config.DreadKingRitual.musicVolume.get().floatValue(),
                1.0F);
    }

    /**
     * 在自身周围半径为 {@link #PARTICLE_RADIUS} 格的<b>圆盘</b>内、自上方
     * {@link #PARTICLE_HEIGHT_OFFSET} 格高处铺一层 {@code irons_spellbooks:blood_ground} 血渍，
     * 让它靠重力滴落并铺在地上。
     * <p>
     * 两个刻意的实现选择：
     * <ul>
     *   <li><b>逐颗定点发送（{@code count = 1}）</b>而不是一次发 {@code count = N} —— 因为
     *       {@code ServerLevel.sendParticles} 对 {@code count > 1} 的处理是「在 ±offset 的
     *       <b>长方体</b>内随机」，那会摊成一个方阵而不是圆。逐颗发送才能保证落在半径 4 以内。
     *       <br>代价：每批 {@link #PARTICLES_PER_BURST} 个包、整场 28 批 ⇒ **1120 个小包**（约 160 包/秒）。
     *       这些包只发给 32 格内的玩家，且每个仅数十字节，实测开销可忽略 —— 这是为了"圆盘而非方阵"
     *       刻意接受的代价，**不要**改成 {@code count > 1}。</li>
     *   <li><b>半径取 {@code R × sqrt(u)}</b> 而非 {@code R × u} —— 前者才保证圆盘内<b>面密度均匀</b>；
     *       后者会让粒子向圆心堆积。</li>
     * </ul>
     * 生成高度是「自身 Y + {@link #PARTICLE_HEIGHT_OFFSET}」，<b>不做高度图采样</b>：
     * {@code BloodGroundParticle} 继承 {@code TextureSheetParticle}，而 {@code Particle.hasPhysics}
     * 默认为 {@code true}，每颗血会各自落到自己脚下的表面并停住 ⇒ 地形不平也自然。
     */
    private void emitRitualParticles() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        double cx = getX();
        double cy = getY() + PARTICLE_HEIGHT_OFFSET;
        double cz = getZ();
        for (int i = 0; i < PARTICLES_PER_BURST; i++) {
            double angle = getRandom().nextDouble() * Math.PI * 2.0D;
            double radius = PARTICLE_RADIUS * Math.sqrt(getRandom().nextDouble());
            serverLevel.sendParticles(ParticleHelper.BLOOD_GROUND,
                    cx + Math.cos(angle) * radius, cy, cz + Math.sin(angle) * radius,
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /**
     * 在自身位置召唤<b>不祥</b>状态的死者之王。
     * <p>
     * 序列除下述第一处外，逐字复刻铁魔法自己的复活路径 {@code DeadKingCorpseEntity.java:75-90}。
     * <ul>
     *   <li><b>刻意不调 {@code setSpawnPos}（D20）</b> —— {@code DeadKingBoss.tickDeath()} 只在
     *       {@code spawnPos != null} 时才生成 {@code dead_king_soul}（{@code DeadKingBoss.java:706-718}），
     *       而 {@code spawnPos} 对 {@code DeadKingBoss} 的<b>唯一行为用途</b>就是这个灵魂闸门
     *       （其余只有存取器与 NBT 存读）。让 {@code spawnPos} 保持 {@code null} ⇒ 仪式死王死后
     *       <b>不留灵魂</b>，后续前来探索的玩家不会被误导成「这里有个可复活的死王」。
     *       <p>
     *       ⚠️ 这是<b>刻意</b>的，不是漏写：初版设计反而要求必须调它（否则护命匣复活链会静默断裂）。
     *       需求改为「仪式死王不留灵魂」之后，同一个失效模式从缺陷变成了设计意图。
     *       <p>
     *       <b>代价（已接受）</b>：仪式死王<b>不可再战</b>。战利品与进度触发器不读 {@code spawnPos}，
     *       不受影响。<br>
     *       <b>风险</b>：本做法依赖「铁魔法用 {@code spawnPos != null} 当灵魂闸门」这一实现事实。
     *       若将来 IS 换掉闸门，「无灵魂」会<b>静默</b>退回「有灵魂」。因此验收是<b>双侧</b>的：
     *       仪式死王无灵魂（设计文档 §七 B 用例 7a）+ 尸体复活死王仍有灵魂（用例 7b）。
     *       <p>
     *       <b>改动此处前请先读设计文档的 D20。</b></li>
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
        // 刻意不调 boss.setSpawnPos(...) —— 见本方法 javadoc 与设计文档 D20：
        // spawnPos 保持 null ⇒ tickDeath() 不生成灵魂。仪式死王是一次性遭遇，不应留下可复活的灵魂。
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
