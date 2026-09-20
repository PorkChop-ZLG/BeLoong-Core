package com.zonlong.beloong.block;

import com.github.L_Ender.cataclysm.entity.effect.ScreenShake_Entity;
import com.zonlong.beloong.registry.ModBlocks;
import com.zonlong.beloong.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

/**
 * 地狱之门的方块实体 —— 灾变 {@code Door_Of_Seal_BlockEntity} 的移植件。
 *
 * <h2>职责</h2>
 * 推进开门时序（与灾变逐 tick 对齐）：
 * <table border="1">
 *   <caption>时序</caption>
 *   <tr><th>tick</th><th>动作</th></tr>
 *   <tr><td>1</td><td>{@link ScreenShake_Entity#ScreenShake} 屏震（半径 20、强度 0.05、渐隐 120）</td></tr>
 *   <tr><td>28</td><td>播 {@link ModSounds#HELL_GATE_OPEN}（音量 4、音高 1±0.2）+ 一次
 *       {@code ExplosionInteraction.TRIGGER} 爆炸（半径 2，<b>不破坏方块</b>）</td></tr>
 *   <tr><td>≥145</td><td>把基准格与全部 39 个部件格置 {@code OPEN = true}（碰撞箱清空＝放行）</td></tr>
 * </table>
 * 到位后进入 {@code open} 待机动画。
 *
 * <h2>移植说明</h2>
 * <ul>
 *   <li>方块实体类型换成本模组的 {@link ModBlocks#HELL_GATE_BLOCK_ENTITY}。
 *       <b>无法</b>继承灾变的 BE：其构造函数把类型写死为灾变的注册项，而
 *       {@code LevelChunk} 会用 {@code type.isValid(state)} 校验并**静默拒绝**类型不匹配的 BE。</li>
 *   <li>字段 {@code animationTicks}/{@code tickCount} 与原版同名；NBT 键仍是 {@code animationTicks}
 *       （与灾变兼容）。{@code animation} 与 {@code facing} 在原版中就未被使用，此处保留以便日后与灾变对比。</li>
 *   <li>⚠️ <b>唯一一处有意的行为偏离</b>：原版在第 28 tick 的 {@code level.playSound(...)} **没有**
 *       {@code !isClientSide} 守卫，而本 BE 双端都会 tick ⇒ 客户端会本地播一次、再收到服务端的广播，
 *       结果是**同一音效播两遍**。这里补上了守卫（服务端广播一次即可）。
 *       如需与灾变**逐字节**一致，删掉那个判断即可。</li>
 * </ul>
 *
 * @see HellGateBlock
 */
public class HellGateBlockEntity extends BlockEntity {

    /** 方块事件 id：启动开启动画。 */
    private static final int EVENT_START_OPENING = 1;

    /** 开始屏震的 tick。 */
    private static final int TICK_SCREEN_SHAKE = 1;
    /** 播音效 + 爆炸的 tick。 */
    private static final int TICK_SOUND_AND_BLAST = 28;
    /** 全门置 OPEN 的 tick（动画总长 7.25 s = 145 tick）。 */
    private static final int TICK_FULLY_OPEN = 145;

    /** 已推进的开门动画 tick 数。落 NBT（键名与灾变一致）。 */
    public int animationTicks;
    /** 实体存活 tick 数（动画时间基准）。 */
    public int tickCount;
    /** 原版保留但未使用的字段，移植时一并保留。 */
    public int animation = 0;
    /** 原版保留但未使用的字段，移植时一并保留。 */
    public Direction facing;

    public AnimationState openingAnimationState = new AnimationState();
    public AnimationState openAnimationState = new AnimationState();

    public HellGateBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.HELL_GATE_BLOCK_ENTITY.get(), pos, state);
        facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
    }

    public AnimationState getAnimationState(String input) {
        if ("opening".equals(input)) {
            return this.openingAnimationState;
        } else if ("open".equals(input)) {
            return this.openAnimationState;
        } else {
            return new AnimationState();
        }
    }

    @Override
    public boolean triggerEvent(int id, int param) {
        if (id == EVENT_START_OPENING) {
            this.openingAnimationState.start(this.tickCount);
            return true;
        }
        return super.triggerEvent(id, param);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, HellGateBlockEntity entity) {
        entity.tickCount++;
        if (!(state.getBlock() instanceof HellGateBlock)) {
            return;
        }
        if (!state.getValue(HellGateBlock.LIT)) {
            return;
        }

        if (state.getValue(HellGateBlock.OPEN)) {
            // 已完全打开：停掉开启动画、切到待机动画。
            entity.animationTicks = 0;
            if (level.isClientSide) {
                entity.openingAnimationState.stop();
                entity.openAnimationState.startIfStopped(entity.tickCount);
            }
            return;
        }

        ++entity.animationTicks;

        if (entity.animationTicks == TICK_SCREEN_SHAKE) {
            ScreenShake_Entity.ScreenShake(level, Vec3.atCenterOf(pos), 20, 0.05f, 0, 120);
        }

        if (entity.animationTicks == TICK_SOUND_AND_BLAST) {
            // 见类 javadoc「移植说明」：原版此处没有 isClientSide 守卫，会双端各播一次。
            if (!level.isClientSide) {
                level.playSound(null, pos, ModSounds.HELL_GATE_OPEN.get(), SoundSource.BLOCKS, 4F,
                        level.random.nextFloat() * 0.2F + 1.0F);
                level.explode(null, pos.getX() + 0.5F, pos.getY() + 1.0F, pos.getZ() + 0.5F,
                        2.0F, Level.ExplosionInteraction.TRIGGER);
            }
        }

        if (entity.animationTicks >= TICK_FULLY_OPEN && !level.isClientSide) {
            level.setBlock(pos, state.setValue(HellGateBlock.OPEN, Boolean.TRUE), 2);
            level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(null, state));

            for (int i = 0; i < HellGateBlock.GATE_HEIGHT; i++) {
                BlockPos abovePos = pos.above(i);
                BlockPos[] partPoses = {
                        abovePos.relative(state.getValue(HellGateBlock.FACING).getClockWise()),
                        abovePos,
                        abovePos.relative(state.getValue(HellGateBlock.FACING).getCounterClockWise()),
                        abovePos.relative(state.getValue(HellGateBlock.FACING).getClockWise(), 2),
                        abovePos.relative(state.getValue(HellGateBlock.FACING).getCounterClockWise(), 2)
                };
                for (BlockPos partPos : partPoses) {
                    BlockState partState = level.getBlockState(partPos);
                    if (partState.is(ModBlocks.HELL_GATE.get())) {
                        level.setBlock(partPos, partState.setValue(HellGateBlock.OPEN, Boolean.TRUE), 2);
                        level.gameEvent(GameEvent.BLOCK_CHANGE, partPos, GameEvent.Context.of(null, partState));
                    }
                }
            }
        }
    }

    /** 由 {@link HellGateBlock#attemptToRing} 调用：置 {@code LIT} 并派发动画事件。 */
    public void onHit(Level level) {
        BlockPos pos = this.getBlockPos();
        BlockState state = this.getBlockState();
        if (!state.getValue(HellGateBlock.LIT)) {
            level.setBlock(pos, state.setValue(HellGateBlock.LIT, Boolean.TRUE), 2);
            level.blockEvent(pos, this.getBlockState().getBlock(), EVENT_START_OPENING, 0);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.animationTicks = tag.getInt("animationTicks");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("animationTicks", this.animationTicks);
    }
}
