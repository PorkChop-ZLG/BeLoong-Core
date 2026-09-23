package com.zonlong.beloong.network;

/**
 * 客户端飞行状态缓存。
 *
 * <p>服务端在玩家登录时通过 {@link FlightStatusSyncPayload} 把 Dragon Survival 的
 * 服务端配置 {@code stable_hover} 同步过来。客户端侧的稳定悬停判定读此缓存，
 * 而<b>不是</b>直接读 {@code ServerFlightHandler.stableHover} —— 后者标注为
 * {@code ConfigSide.SERVER}，远端专用服务器的客户端未必能读到权威值。</p>
 *
 * <p>默认值为 {@code false}：网络包未送达（旧客户端、连接异常）时退化为"不干预"，
 * 即完整保持 DS 原版飞行行为，这是安全方向。</p>
 */
public class ClientFlightStatusCache {
    public static final ClientFlightStatusCache INSTANCE = new ClientFlightStatusCache();

    /** 服务端同步过来的 {@code ServerFlightHandler.stableHover} 值。 */
    private volatile boolean stableHover = false;

    /**
     * 从网络包写入缓存。
     *
     * @param stableHover 服务端读取到的 DS {@code stable_hover} 配置值
     */
    public void loadFromSync(boolean stableHover) {
        this.stableHover = stableHover;
    }

    /** @return DS 服务端是否启用了稳定悬停；未同步到时为 {@code false} */
    public boolean isStableHover() {
        return stableHover;
    }
}
