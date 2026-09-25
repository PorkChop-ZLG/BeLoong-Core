package com.zonlong.beloong.dialogue;

import com.zonlong.beloong.Config;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * NPC 对话的右键受理（**纯服务端**）。
 * <p>
 * 触发链：{@code PlayerInteractEvent.EntityInteract} → 开关 → 主手 → **服务端侧** →
 * 查表命中 → 触发方式判定 → {@link NpcDialogueOpenPayload} 下发给该玩家。
 * <p>
 * <b>为什么在服务端受理</b>（首版在客户端，本次反转）：对话的决策点必须在服务端 ——
 * 数据本身是服务端的（{@code data/} 树）、将来"按原版进度判据筛选该看到的内容"也只能在
 * 服务端做。客户端只负责把收到的这一条渲染出来。
 * <p>
 * <b>为什么只认主手</b>：原版一次右键通常只派发主手事件（副手仅在主手 PASS 时才轮到），
 * 限定主手可保证"一次右键最多打开一次对话"。
 * <p>
 * <b>刻意不取消事件</b>（首版 D4 不变）：空手右键本无任何原版行为，取消没有收益，
 * 却会抢掉将来第三方模组的交互。
 * <p>
 * <b>刻意不手写距离校验</b>：原版服务端在交互包处理里已做交互距离校验，
 * 这里再加一层是重复的。
 * <p>
 * 由 {@code BeLoongCore} 构造时注册到游戏总线。**不得**放进 {@code client/} 包、
 * **不得**标注 {@code @OnlyIn(Dist.CLIENT)} —— 专用服务器必须加载它。
 */
public class NpcDialogueHandler {

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        // 该事件两侧都会派发（首版 §8.10）：本功能只受理服务端那一次。
        // **这一条必须排在读配置之前** —— enabled 现在是**服务端配置**，在物理客户端上读它
        // 等于依赖配置同步的时机（未加载时 get() 会抛 IllegalStateException），而客户端
        // 反正也不会用这个值做任何事。
        if (player.level().isClientSide()) {
            return;
        }

        if (!Config.NpcDialogue.enabled.get()) {
            return;
        }
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }

        Entity target = event.getTarget();
        NpcDialogueEntry entry = NpcDialogueLoader.INSTANCE.get(target.getType());
        if (entry == null) {
            return;
        }

        // 触发方式由数据文件决定（首版 D3）：EMPTY_HAND 要求主手为空
        //（保住"手持铁锭右键铁傀儡 = 修血"的原版语义）；ANY 则不看手持。
        // 有意不做潜行判断 —— 隐式规则越少越不容易踩坑。
        if (entry.trigger() == NpcDialogueEntry.Trigger.EMPTY_HAND && !player.getMainHandItem().isEmpty()) {
            return;
        }

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        PacketDistributor.sendToPlayer(serverPlayer, new NpcDialogueOpenPayload(
                entry.name(),
                target.getType().getDescriptionId(),
                entry.pages(),
                target.getId()));
    }
}
