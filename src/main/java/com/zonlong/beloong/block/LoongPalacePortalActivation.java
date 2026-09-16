package com.zonlong.beloong.block;

import com.mojang.logging.LogUtils;
import com.zonlong.beloong.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.slf4j.Logger;

/**
 * 龙宫传送门的<b>注水激活</b>：框内出现水（任意水，含流动水）即点亮。
 * <p>
 * <b>为什么走方块事件而不是 tick 轮询</b>：水的进入必然伴随邻居更新
 * （{@link BlockEvent.NeighborNotifyEvent}），这是<b>方块驱动、零开销</b>的；
 * 项目里既有的轮询（{@code DimensionTransportHandler}）是为"每 tick 查玩家 Y"设计的，
 * 用在这里纯属浪费。
 * <p>
 * <b>维度限制的第一层</b>：只有主世界与龙宫里的水能点亮（"其他维度不生效"）。
 * 第二层在门方块的 {@code getPortalDestination}——防止有人用指令/结构把门放进下界后当传送器用。
 * <p>
 * <b>刻意不取消该邻居事件</b>（天境会 {@code setCanceled(true)}）：取消会吞掉这次方块更新，
 * 而原版水与荧石的后续结算依赖邻居更新。
 */
public class LoongPalacePortalActivation {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 水进入框内 → 尝试点亮。 */
    @SubscribeEvent
    public void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!LoongPalacePortalBlock.isAllowedDimension(level.dimension())) return;

        BlockPos pos = event.getPos();
        // 任意水：倒下去的水在方块层面常常是"空气 + 流体状态"，只认水源方块会漏掉大部分情形
        if (!level.getFluidState(pos).is(FluidTags.WATER)) return;

        LoongPalacePortalShape.findEmpty(level, pos, Direction.Axis.X).ifPresent(shape -> {
            shape.createPortalBlocks();
            level.playSound(null, pos, ModSounds.LOONG_PALACE_PORTAL_TRIGGER.get(),
                    SoundSource.BLOCKS, 1.0F, 1.0F);
            LOGGER.debug("[BeLoongCore] loong palace portal lit at {} in {}", pos, level.dimension().location());
        });
    }

    /** 玩家登出：清理提示节流状态。 */
    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LoongPalacePortalNotifier.clear(event.getEntity().getUUID());
    }
}
