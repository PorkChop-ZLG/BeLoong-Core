package com.zonlong.beloong.client;

import com.zonlong.beloong.Config;
import com.zonlong.beloong.dialogue.NpcDialogueEntry;
import com.zonlong.beloong.dialogue.NpcDialogueLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * NPC 对话的右键入口（纯客户端）。
 * <p>
 * 触发链：{@code PlayerInteractEvent.EntityInteract} → 开关 → 主手 → 客户端侧 →
 * 查数据表命中 → 触发方式判定 → {@link #openDialogue}。
 * <p>
 * <b>为什么在客户端受理</b>：v1 的对话没有副作用（唯一选项是"离开"），
 * 在服务端受理反而要多一个"打开对话"的回包。见设计文档决策 D2。
 * <p>
 * <b>刻意不取消事件</b>（决策 D4）：空手右键铁傀儡在原版没有任何行为，
 * 取消没有收益，却会抢掉将来第三方模组的交互。
 * <p>
 * 由 {@code BeLoongCoreClient} 构造时注册到游戏总线（与 {@code LoongPalaceSkyTickHandler} 同构）。
 */
@OnlyIn(Dist.CLIENT)
public class NpcDialogueHandler {

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!Config.NpcDialogue.enabled.get()) {
            return;
        }
        // 只认主手：原版一次右键通常只派发主手事件（副手仅在主手 PASS 时才轮到），
        // 限定主手可保证"一次右键最多打开一次对话"。
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }

        Player player = event.getEntity();
        if (!player.level().isClientSide()) {
            return;
        }

        NpcDialogueEntry entry = NpcDialogueLoader.INSTANCE.get(event.getTarget().getType());
        if (entry == null) {
            return;
        }

        // 触发方式由数据文件决定（决策 D3）：EMPTY_HAND 要求主手为空（保住"手持铁锭修血"），
        // ANY 则不看手持。有意不做潜行判断 —— 隐式规则越少越不容易踩坑。
        if (entry.trigger() == NpcDialogueEntry.Trigger.EMPTY_HAND && !player.getMainHandItem().isEmpty()) {
            return;
        }

        openDialogue(player, event.getTarget(), entry);
    }

    /**
     * <b>唯一的"打开对话"入口</b> —— 将来接入第三方对话框模组时，只改这一个方法。
     * <p>
     * 把"谁在什么时候被右键"（触发 + 映射，本模组的资产）与"怎么显示"（渲染，将来会换掉）
     * 分开，正是设计文档 §11 留的接缝：本模组将来扮演"NPC 模组"那一侧，
     * 第三方模组扮演"对话库"那一侧。
     */
    public static void openDialogue(Player player, Entity speaker, NpcDialogueEntry entry) {
        Minecraft.getInstance().setScreen(new NpcDialogueScreen(entry, speaker));
    }
}
