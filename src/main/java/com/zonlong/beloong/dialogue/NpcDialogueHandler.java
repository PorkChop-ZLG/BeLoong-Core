package com.zonlong.beloong.dialogue;

import com.zonlong.beloong.entity.NpcEntity;
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
 * <b>刻意取消事件</b>（2026-10-13 反转首版 D4）：
 * 旧理由是"空手右键本无任何原版行为，取消没有收益，却会抢掉第三方模组的交互"——
 * 但它只对**普通生物**成立；对<b>剧情 NPC</b> 恰恰相反：原版交互在它身上是 bug 而非收益
 * （栓绳能牵走它 —— 牵引会 {@code disableControlFlag(Goal.Flag.MOVE)}，直接掐死路线系统；
 * 公共锚点被牵走更会连累所有玩家的剧情）。
 * <p>
 * <b>有意接受的代价</b>：第三方模组想通过"右键本 NPC"触发的交互也会一并失效。
 * 若日后确有这种需求，再引入"白名单物品/条件"的口子（<b>现在不做</b> —— YAGNI）。
 * 注意取消**不影响我们自己的对话**：本处理器完全自己做主（读事件 → 查表 → 下发 payload），
 * 从不依赖原版交互的返回值。
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

        // ⚠️ 对本模组的 NPC **取消一切原版/第三方右键交互**（2026-10-13 的反转决定，见类注释）：
        // 原版交互在它身上不是"收益"而是 bug —— 栓绳能把它牵走（牵引会 disableControlFlag(MOVE)，
        // 直接掐死路线系统；公共锚点被牵走更会连累所有玩家的剧情）。
        // ⚠️ 必须放在**下面的客户端早退之前**：该事件两侧都会派发，只取消服务端那侧的话，
        // 客户端仍会跑一遍原版逻辑（例：栓绳在客户端被 shrink(1)，造成短暂不同步）。
        // ⚠️ 只取消**我们的** NPC，别去动别人的实体。
        if (event.getTarget() instanceof NpcEntity) {
            event.setCanceled(true);
        }
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
        // 交互闸门：**私有分身只认主人**。正常玩家看不到别人的分身（NpcEntity#visibleTo），
        // 所以走不到这里；但改包/去同步的客户端可以凭实体 id 直接发交互 ⇒ 服务端必须自己判一次。
        if (target instanceof NpcEntity npc && npc.isPrivate()
                && !player.getUUID().equals(npc.owner())) {
            return;
        }
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

        // 记下"该玩家最近对话过的 NPC" —— ChatBox 的选项 click 只能以玩家身份执行命令，
        // 命令里选不出"正在对话的那个 NPC"，所以由这里记、由 /beloong route <名> 用（设计 D2）。
        LastDialogueNpc.remember(serverPlayer, target);
        PacketDistributor.sendToPlayer(serverPlayer, new NpcDialogueOpenPayload(
                entry.name(),
                target.getType().getDescriptionId(),
                entry.pages(),
                target.getId(),
                // 只把**当前可见**的回复发下去，且带"它在 replies[] 里的原始下标"；
                // 目标（chatbox RL / 组 / 页号）留在服务端（设计 D1），阶段判定见 NpcDialogueStage。
                NpcDialogueStage.visibleIndices(serverPlayer, entry).stream()
                        .map(i -> new NpcDialogueOpenPayload.VisibleReply(
                                entry.replies().get(i).text(), i))
                        .toList()));
    }
}
