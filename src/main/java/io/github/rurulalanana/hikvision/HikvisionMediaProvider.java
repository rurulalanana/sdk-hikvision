package io.github.rurulalanana.hikvision;

import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.deviceaccess.DeviceMediaProvider;
import io.github.rurulalanana.hikvision.internal.HikvisionSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 海康取流实现（{@link DeviceMediaProvider}）。
 *
 * <h3>取流模型</h3>
 * 平台调 {@code NET_ECMS_StartGetRealStream} → 设备连到平台 SMS 端口推 PS 码流 →
 * 平台把 PS 交给 ZLM 解码成裸流 → 输出 FLV/HLS/WebRTC。
 *
 * <h3>为什么 transport 用 {@code "ISUP"}</h3>
 * 平台的 {@code gb_stream.transport} 原本只有 UDP/TCP/SDK。
 * 海康既不是国标 RTP、也不是 SDK 拉流，是「设备推 PS」，
 * 单列一个值便于排障时一眼看出走的是哪条路。
 *
 * <h3>一路流一个句柄，重复点播必须复用</h3>
 * ISUP 每次 StartGetRealStream 都会新建一路推流，而设备并发推流上限很低
 * （常见 4~8 路）。重复请求会把设备打满，表现为「点播一闪就失败」。
 */
public class HikvisionMediaProvider implements DeviceMediaProvider {

    private static final Logger log = LoggerFactory.getLogger(HikvisionMediaProvider.class);

    /** 传输方式标识（落库用）。 */
    public static final String TRANSPORT_ISUP = "ISUP";

    private final HikvisionRegisterTable table;

    private final HikvisionStreamListener streamListener;

    /** 已启动的流，key = {@code deviceId|channelId|streamNumber}。 */
    private final Map<String, StreamHandle> handles = new ConcurrentHashMap<>();

    public HikvisionMediaProvider(HikvisionRegisterTable table,
                                  HikvisionStreamListener streamListener) {
        this.table = table;
        this.streamListener = streamListener;
    }

    @Override
    public DeviceKind kind() {
        return DeviceKind.HIKVISION;
    }

    /** SDK 可用且设备在注册表里。 */
    @Override
    public boolean available(String deviceId) {
        return HikvisionSdk.get().available() && table.byDeviceId(deviceId) != null;
    }

    /**
     * 开一路实时流。
     *
     * <p>已在跑就复用：ISUP 的设备并发推流数很有限，重复请求会被设备侧拒绝。
     */
    @Override
    public StreamHandle start(String deviceId, String channelId, Integer streamNumber) {
        String key = keyOf(deviceId, channelId, streamNumber);
        StreamHandle existing = handles.get(key);
        if (existing != null) {
            log.debug("海康取流已存在，复用: {} {}", key, deviceId);
            return existing;
        }
        if (!available(deviceId)) {
            throw new IllegalStateException(HikvisionSdk.get().available()
                    ? "海康设备未注册：" + deviceId
                    : HikvisionSdk.get().diagnose());
        }
        int channelNo = HikvisionDeviceAccessProvider.channelNoOf(deviceId, channelId);
        // 0 主码流，1 子码流（与国标口径一致）
        int streamType = streamNumber == null ? 0 : Math.max(0, Math.min(1, streamNumber));

        int sessionId = streamListener.startPreview(deviceId, channelNo, streamType);
        if (sessionId < 0) {
            throw new IllegalStateException("海康取流失败：设备未响应预览请求（"
                    + deviceId + " 通道 " + channelNo + "）");
        }
        StreamHandle handle = new StreamHandle(deviceId, channelId, TRANSPORT_ISUP);
        handles.put(key, handle);
        log.info("海康取流已启动: device={} channel={} streamType={} session={}",
                deviceId, channelNo, streamType, sessionId);
        return handle;
    }

    @Override
    public void stop(String deviceId, String channelId) {
        // 主/子码流都要停：调用方不一定记得自己是哪一路
        int channelNo = HikvisionDeviceAccessProvider.channelNoOf(deviceId, channelId);
        for (int streamType = 0; streamType <= 1; streamType++) {
            if (handles.remove(keyOf(deviceId, channelId, streamType)) != null) {
                streamListener.stopPreview(deviceId, channelNo, streamType);
            }
        }
    }

    @Override
    public boolean running(String deviceId, String channelId) {
        return handles.containsKey(keyOf(deviceId, channelId, 0))
                || handles.containsKey(keyOf(deviceId, channelId, 1));
    }

    /**
     * 空闲回收。
     *
     * <p>ISUP 的流由设备主动维持，平台无法从「有没有数据」判断是否空闲
     * （设备不发数据时和没人看时一样）。真正的回收由平台的
     * 「无人观看」逻辑驱动 {@link #stop}，这里只清掉设备已下线的残留。
     */
    @Override
    public void reapIdle() {
        handles.entrySet().removeIf(e -> {
            String deviceId = e.getValue().app();
            if (table.byDeviceId(deviceId) == null) {
                log.info("海康设备已下线，回收其残留流: {}", deviceId);
                return true;
            }
            return false;
        });
    }

    /** 当前活跃流数（诊断用）。 */
    public int activeCount() {
        return handles.size();
    }

    private static String keyOf(String deviceId, String channelId, Integer streamNumber) {
        return deviceId + "|" + channelId + "|" + (streamNumber == null ? 0 : streamNumber);
    }
}
