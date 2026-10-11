package com.zonlong.beloong.compat.healthbars;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端侧「斩杀标记」缓存：实体网络 id → 标记的 amplifier。
 *
 * <h3>⚠️ 客户端专属</h3>
 * 它只被两个客户端路径引用：{@link ExecuteMarkSyncPayload#handleClient}（收包写入）与
 * {@link ExecuteLineOverlay#draw}（绘制时读）。<b>不允许</b>被 {@code Config}、注册表类或任何双端
 * 类引用 —— 那些类在专用服务端也会加载。{@code tools/validate_execute_line.py} 的第 ⑤ 条断言守这条。
 *
 * <h3>为什么需要缓存</h3>
 * 客户端拿不到生物的 {@code MobEffectInstance}（见 {@link ExecuteMarkSyncPayload} 的类注释），
 * 所以只能由服务端把 amplifier 发过来存在这里，绘制时按实体 id 取。
 */
public final class ExecuteMarkCache {

    private static final Map<Integer, Integer> AMPLIFIERS = new HashMap<>();

    private ExecuteMarkCache() {}

    /** 收包写入；{@link ExecuteMarkSyncPayload#NO_MARK} 表示该实体已无标记，删掉条目。 */
    public static void apply(final int entityId, final int amplifier) {
        if (amplifier == ExecuteMarkSyncPayload.NO_MARK) {
            AMPLIFIERS.remove(entityId);
        } else {
            AMPLIFIERS.put(entityId, amplifier);
        }
    }

    /** 取该实体的标记 amplifier；没有标记返回 {@link ExecuteMarkSyncPayload#NO_MARK}。 */
    public static int get(final int entityId) {
        Integer amplifier = AMPLIFIERS.get(entityId);
        return amplifier == null ? ExecuteMarkSyncPayload.NO_MARK : amplifier;
    }

    /**
     * 清空。由 {@code BeLoongCoreClient} 在<b>登录时</b>调用。
     * <p>
     * 必须清：实体网络 id 只在一次连接内唯一，掉线重连后会被回收。不清的话，
     * 一个陈旧条目可能落到另一只新实体身上，在血条上显出一条<b>凭空出现的斩杀线</b>。
     */
    public static void clear() {
        AMPLIFIERS.clear();
    }
}
