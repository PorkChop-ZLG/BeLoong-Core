package com.zonlong.beloong.block;

import com.mojang.logging.LogUtils;
import com.zonlong.beloong.registry.ModItemTags;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import org.slf4j.Logger;

/**
 * 地狱之门钥匙 tag（{@link ModItemTags#HELL_GATE_KEYS}）的**加载期体检**。
 *
 * <h2>为什么需要它</h2>
 * 钥匙改成 tag 匹配之后，唯一的新失败面是「tag 被删了或清空了」——
 * 这**不会报错**：{@code Holder#is(TagKey)} 对未绑定的 tag 一律返回 {@code false}，
 * 表现只是「拿着钥匙点门没反应」，玩家与整合包作者都无从排查。
 * 所以这里在 tag 加载完成后检查一次，缺了/空了就往日志里丢一条**英文**告警
 * （项目规范：日志不得含中文）。
 *
 * <h2>为什么只处理 SERVER_DATA_LOAD</h2>
 * {@link TagsUpdatedEvent} 在「服务端加载数据包」与「客户端收到 tag 包」两种情况下都会触发
 * （见 {@link TagsUpdatedEvent.UpdateCause}），两者是同一份 tag 数据。
 * 只认前者可以避免同一条告警在日志里出现两遍；
 * 单人游戏里服务端加载同样会经过本方法，所以照样能看见告警。
 */
public class HellGateKeyWatcher {

    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            return;
        }

        HolderSet.Named<Item> keys = event.getRegistryAccess()
                .lookupOrThrow(Registries.ITEM)
                .get(ModItemTags.HELL_GATE_KEYS)
                .orElse(null);

        if (keys == null) {
            LOGGER.warn("[BeLoong] hell gate keys: tag #{} is not defined;"
                            + " no item can open the hell gate",
                    ModItemTags.HELL_GATE_KEYS.location());
        } else if (keys.size() == 0) {
            LOGGER.warn("[BeLoong] hell gate keys: tag #{} is empty;"
                            + " no item can open the hell gate",
                    ModItemTags.HELL_GATE_KEYS.location());
        }
    }
}
