package io.github.rurulalanana.hikvision;

import com.sun.jna.Pointer;
import io.github.rurulalanana.hikvision.internal.HikvisionCms;
import io.github.rurulalanana.hikvision.internal.HikvisionSdk;
import io.github.rurulalanana.hikvision.internal.HikvisionStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 海康回放监听：接收设备按时间/文件名推送的历史录像码流。
 *
 * <h3>与预览的关系</h3>
 * 两者是<b>平行的两条通道</b>：预览用 {@code NET_ESTREAM_StartListenPreview}，
 * 回放用 {@code NET_ESTREAM_StartListenPlayBack}，各有一套句柄与回调。
 * 但设备侧看到的是<b>同一个 SMS 端口</b>——SDK 按连接首包区分是预览还是回放，
 * 所以这里配的端口必须与预览一致，否则设备连不上。
 *
 * <h3>请求流程</h3>
 * <ol>
 *   <li>{@code NET_ECMS_StartPlayBack}：告诉设备「按这个时间段推历史流」；</li>
 *   <li>设备连到 SMS → 触发 {@code fnNewLinkCB} → 必须在回调里
 *       {@code NET_ESTREAM_SetPlayBackDataCB} 绑定数据回调；</li>
 *   <li>{@code NET_ECMS_StartPushPlayBack}：通知设备可以开始推；</li>
 *   <li>数据从 {@code fnPlayBackDataCB} 到达。</li>
 * </ol>
 *
 * <h3>回放控制</h3>
 * 暂停/恢复/倍速都走 {@code NET_ECMS_PlayBackOperate}，用会话 ID 定位。
 * 倍速是 0.25~4 的浮点数，SDK 用「小数放大 100 倍」的整数表示。
 */
public class HikvisionPlaybackListener {

    private static final Logger log = LoggerFactory.getLogger(HikvisionPlaybackListener.class);

    /** 回放数据接收方。 */
    public interface PlaybackSink {
        /**
         * 收到一包回放数据。
         *
         * @param serial    设备序列号
         * @param channelNo 通道号（1-based）
         * @param header    {@code true} 表示码流头
         * @param data      PS 数据
         */
        void onData(String serial, int channelNo, boolean header, byte[] data);

        /** 回放结束（设备推完了，或平台主动停）。 */
        default void onEnd(String serial, int channelNo) {
        }
    }

    /** 一路回放。 */
    private record ActivePlayback(String serial, int channelNo, int sessionId) {
    }

    /** {@code NET_ECMS_PlayBackOperate} 的操作码：暂停。 */
    public static final int OPERATE_PAUSE = 0;

    /** {@code NET_ECMS_PlayBackOperate} 的操作码：恢复。 */
    public static final int OPERATE_RESUME = 1;

    private final HikvisionRegisterTable table;

    private final PlaybackSink sink;

    private final String bindIp;

    private final int port;

    private final int linkMode;

    private final String smsIp;

    private final int smsPort;

    /** 回放连接句柄 → 回放信息。 */
    private final Map<Integer, ActivePlayback> links = new ConcurrentHashMap<>();

    /** {@code serial|channel} → 会话 ID。 */
    private final Map<String, Integer> sessions = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "hikvision-playback");
        t.setDaemon(true);
        return t;
    });

    private volatile int listenHandle = -1;

    private final AtomicBoolean started = new AtomicBoolean(false);

    /** 回调对象必须强引用住，否则被 GC 掉会崩 JVM。 */
    private NewLinkCallback newLinkCallback;

    private DataCallback dataCallback;

    public HikvisionPlaybackListener(HikvisionRegisterTable table,
                                     PlaybackSink sink,
                                     String bindIp, int port, int linkMode,
                                     String smsIp, int smsPort) {
        this.table = table;
        this.sink = sink;
        this.bindIp = bindIp;
        this.port = port;
        this.linkMode = linkMode;
        this.smsIp = smsIp;
        this.smsPort = smsPort;
    }

    public boolean started() {
        return started.get();
    }

    /** 启动回放监听。端口必须与预览监听一致。 */
    public boolean start() {
        if (!started.compareAndSet(false, true)) {
            return true;
        }
        HikvisionSdk sdk = HikvisionSdk.get();
        if (!sdk.init()) {
            log.warn("海康回放监听未启动：{}", sdk.diagnose());
            started.set(false);
            return false;
        }
        HikvisionStream stream = sdk.stream();
        if (stream == null) {
            started.set(false);
            return false;
        }
        try {
            newLinkCallback = new NewLinkCallback();
            dataCallback = new DataCallback();

            HikvisionStream.NetEhomePlaybackListenParam cfg =
                    new HikvisionStream.NetEhomePlaybackListenParam();
            if (bindIp != null && !bindIp.isBlank()) {
                byte[] raw = bindIp.getBytes(StandardCharsets.UTF_8);
                System.arraycopy(raw, 0, cfg.struIPAdress.szIP, 0,
                        Math.min(raw.length, cfg.struIPAdress.szIP.length - 1));
            }
            cfg.struIPAdress.wPort = (short) port;
            cfg.fnNewLinkCB = newLinkCallback;
            cfg.byLinkMode = (byte) linkMode;
            cfg.write();

            int handle = stream.NET_ESTREAM_StartListenPlayBack(cfg);
            if (handle < 0) {
                log.error("海康回放监听启动失败，错误码 {}（端口 {} 与预览共用，"
                                + "预览已启动时失败属正常）",
                        stream.NET_ESTREAM_GetLastError(), port);
                started.set(false);
                return false;
            }
            listenHandle = handle;
            log.info("海康回放监听已启动: {}:{}（句柄 {}）",
                    bindIp == null || bindIp.isBlank() ? "0.0.0.0" : bindIp, port, handle);
            return true;
        } catch (Throwable t) {
            log.error("海康回放监听启动异常: {}", t.getMessage(), t);
            started.set(false);
            return false;
        }
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        HikvisionStream stream = HikvisionSdk.get().stream();
        if (stream != null && listenHandle >= 0) {
            stream.NET_ESTREAM_StopListenPlayBack(listenHandle);
        }
        listenHandle = -1;
        links.clear();
        sessions.clear();
        executor.shutdownNow();
        log.info("海康回放监听已停止");
    }

    // ==================== 回放控制 ====================

    /**
     * 按时间请求回放。
     *
     * @param serial    设备序列号
     * @param channelNo 通道号（1-based）
     * @param start     起始时间（{@code yyyy-MM-dd HH:mm:ss}）
     * @param end       结束时间（同格式）
     * @return 会话 ID；失败返回 {@code -1}
     */
    public int startPlayback(String serial, int channelNo, String start, String end) {
        HikvisionSdk sdk = HikvisionSdk.get();
        if (!sdk.available()) {
            log.warn("海康回放不可用：{}", sdk.diagnose());
            return -1;
        }
        HikvisionRegisteredDevice dev = table.byDeviceId(serial);
        if (dev == null) {
            log.warn("海康回放失败：设备未注册 serial={}", serial);
            return -1;
        }
        HikvisionCms cms = sdk.cms();
        if (cms == null) {
            return -1;
        }
        try {
            HikvisionCms.NetEhomePlaybackInfoIn in = new HikvisionCms.NetEhomePlaybackInfoIn();
            in.dwSize = in.size();
            in.dwChannel = channelNo;
            in.byPlayBackMode = 1;  // 按时间
            in.byStreamPackage = 0; // PS（设备推的就是 PS）
            in.unionPlayBackMode.struStartTime = toSdkTime(start);
            in.unionPlayBackMode.struStopTime = toSdkTime(end);
            in.unionPlayBackMode.byLocalOrUTC = 0;  // 本地时间：设备侧时间就是本地时区
            byte[] ip = smsIp.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(ip, 0, in.struStreamSever.szIP, 0,
                    Math.min(ip.length, in.struStreamSever.szIP.length - 1));
            in.struStreamSever.wPort = (short) smsPort;
            in.write();

            HikvisionCms.NetEhomePlaybackInfoOut out = new HikvisionCms.NetEhomePlaybackInfoOut();
            if (!cms.NET_ECMS_StartPlayBack(dev.userId(), in, out)) {
                log.warn("海康请求回放失败: serial={} channel={} 错误码 {}",
                        serial, channelNo, cms.NET_ECMS_GetLastError());
                return -1;
            }
            out.read();
            int sessionId = out.lSessionID;

            HikvisionCms.NetEhomePushPlaybackIn pushIn = new HikvisionCms.NetEhomePushPlaybackIn();
            pushIn.dwSize = pushIn.size();
            pushIn.lSessionID = sessionId;
            pushIn.write();
            HikvisionCms.NetEhomePushPlaybackOut pushOut =
                    new HikvisionCms.NetEhomePushPlaybackOut();
            pushOut.dwSize = pushOut.size();
            pushOut.write();
            if (!cms.NET_ECMS_StartPushPlayBack(dev.userId(), pushIn, pushOut)) {
                log.warn("海康通知回放推流失败: serial={} session={} 错误码 {}",
                        serial, sessionId, cms.NET_ECMS_GetLastError());
                cms.NET_ECMS_StopPlayBack(dev.userId(), sessionId);
                return -1;
            }
            sessions.put(key(serial, channelNo), sessionId);
            log.info("海康回放已请求: serial={} channel={} {}~{} session={}",
                    serial, channelNo, start, end, sessionId);
            return sessionId;
        } catch (Throwable t) {
            log.error("海康请求回放异常: serial={} {}", serial, t.getMessage(), t);
            return -1;
        }
    }

    /** 停止回放。 */
    public void stopPlayback(String serial, int channelNo) {
        Integer sessionId = sessions.remove(key(serial, channelNo));
        HikvisionSdk sdk = HikvisionSdk.get();
        if (sdk.stream() != null) {
            for (Map.Entry<Integer, ActivePlayback> e : links.entrySet()) {
                ActivePlayback p = e.getValue();
                if (p.serial().equals(serial) && p.channelNo() == channelNo) {
                    sdk.stream().NET_ESTREAM_StopPlayBack(e.getKey());
                    links.remove(e.getKey());
                }
            }
        }
        if (sessionId != null && sdk.cms() != null) {
            HikvisionRegisteredDevice dev = table.byDeviceId(serial);
            if (dev != null) {
                sdk.cms().NET_ECMS_StopPlayBack(dev.userId(), sessionId);
            }
        }
        log.info("海康回放已停止: serial={} channel={}", serial, channelNo);
    }

    /**
     * 暂停 / 恢复回放。
     *
     * <p><b>不支持倍速</b>：{@code NET_ECMS_PlayBackOperate} 的参数结构
     * {@code NET_EHOME_PLAYBACK_PAUSE_RESTART_PARAM} 只有
     * {@code lSessionID + lHandle}，没有速度字段（官方 Java demo 也只用了暂停）。
     * 早期实现自己加了个 {@code lSpeed}，导致结构体错位。
     *
     * @param mode {@link #OPERATE_PAUSE} 或 {@link #OPERATE_RESUME}
     * @return 是否成功
     */
    public boolean operate(String serial, int channelNo, int mode) {
        Integer sessionId = sessions.get(key(serial, channelNo));
        if (sessionId == null) {
            log.warn("海康回放控制失败：没有活跃回放 serial={} channel={}", serial, channelNo);
            return false;
        }
        HikvisionSdk sdk = HikvisionSdk.get();
        HikvisionCms cms = sdk.cms();
        HikvisionRegisteredDevice dev = table.byDeviceId(serial);
        if (cms == null || dev == null) {
            return false;
        }
        HikvisionCms.NetEhomePlaybackPauseRestartParam param =
                new HikvisionCms.NetEhomePlaybackPauseRestartParam();
        param.lSessionID = sessionId;
        param.write();
        boolean ok = cms.NET_ECMS_PlayBackOperate(dev.userId(), mode, param.getPointer());
        if (!ok) {
            log.warn("海康回放控制失败: serial={} mode={} 错误码 {}",
                    serial, mode, cms.NET_ECMS_GetLastError());
        }
        return ok;
    }

    private static String key(String serial, int channelNo) {
        return serial + "|" + channelNo;
    }

    /** {@code yyyy-MM-dd HH:mm:ss} 或 {@code yyyy-MM-ddTHH:mm:ss} → SDK 时间结构。 */
    private static HikvisionCms.NetEhomeSdkTime toSdkTime(String text) {
        HikvisionCms.NetEhomeSdkTime t = new HikvisionCms.NetEhomeSdkTime();
        if (text == null || text.isBlank()) {
            return t;
        }
        String s = text.trim().replace('T', ' ').replace('Z', ' ');
        try {
            // 只取 "yyyy-MM-dd HH:mm:ss" 这一段，忽略毫秒与时区
            t.wYear = (short) Integer.parseInt(s.substring(0, 4));
            t.byMonth = (byte) Integer.parseInt(s.substring(5, 7));
            t.byDay = (byte) Integer.parseInt(s.substring(8, 10));
            t.byHour = (byte) Integer.parseInt(s.substring(11, 13));
            t.byMinute = (byte) Integer.parseInt(s.substring(14, 16));
            t.bySecond = (byte) Integer.parseInt(s.substring(17, 19));
        } catch (RuntimeException e) {
            log.warn("海康回放时间格式无法解析: {}", text);
        }
        return t;
    }

    // ==================== 回调 ====================

    /** 设备连上来时回调：必须在这里绑定数据回调。 */
    private final class NewLinkCallback implements HikvisionStream.PlaybackNewLinkCb {

        @Override
        public boolean invoke(int lPlaybackLinkHandle,
                              HikvisionStream.NetEhomePlaybackNewLinkCbInfo info,
                              Pointer pUserData) {
            try {
                info.read();
                String deviceId = cstr(info.szDeviceID);
                String serial = cstr(info.sDeviceSerial);
                HikvisionRegisteredDevice dev = table.byDeviceId(
                        serial.isBlank() ? deviceId : serial);
                String resolved = dev != null ? dev.serial()
                        : (serial.isBlank() ? deviceId : serial);
                int channelNo = info.dwChannelNo;

                links.put(lPlaybackLinkHandle,
                        new ActivePlayback(resolved, channelNo, info.lSessionID));

                // 关键：不绑定数据回调就收不到任何数据
                HikvisionStream.NetEhomePlaybackDataCbParam param =
                        new HikvisionStream.NetEhomePlaybackDataCbParam();
                param.fnPlayBackDataCB = dataCallback;
                param.byStreamFormat = info.byStreamFormat;
                param.write();
                HikvisionStream stream = HikvisionSdk.get().stream();
                if (stream == null
                        || !stream.NET_ESTREAM_SetPlayBackDataCB(lPlaybackLinkHandle, param)) {
                    log.warn("海康绑定回放数据回调失败: link={} serial={}",
                            lPlaybackLinkHandle, resolved);
                    return false;
                }
                log.info("海康回放已连上 SMS: serial={} channel={} link={} session={}",
                        resolved, channelNo, lPlaybackLinkHandle, info.lSessionID);
                return true;
            } catch (Throwable t) {
                log.error("海康回放新连接回调异常: {}", t.getMessage(), t);
                return false;
            }
        }
    }

    /**
     * 回放数据回调：运行在 SDK 原生线程。
     *
     * <p>只做「拷字节 + 丢执行器」，解码与推流在别的线程做，
     * 否则会阻塞 SDK 收包，表现为回放卡顿。
     */
    private final class DataCallback implements HikvisionStream.PlaybackDataCb {

        @Override
        public boolean invoke(int iPlaybackLinkHandle,
                              HikvisionStream.NetEhomePlaybackDataCbInfo info,
                              Pointer pUserData) {
            try {
                info.read();
                ActivePlayback p = links.get(iPlaybackLinkHandle);
                if (p == null) {
                    return true;
                }
                // 1-头信息，2-码流数据，3-回放停止
                if (info.dwType == 3) {
                    log.info("海康回放已结束: serial={} channel={}", p.serial(), p.channelNo());
                    links.remove(iPlaybackLinkHandle);
                    sessions.remove(key(p.serial(), p.channelNo()));
                    executor.execute(() -> sink.onEnd(p.serial(), p.channelNo()));
                    return true;
                }
                if (info.pData == null || info.dwDataLen <= 0) {
                    return true;
                }
                byte[] copy = info.pData.getByteArray(0, info.dwDataLen);
                boolean header = info.dwType == 1;
                executor.execute(() -> {
                    try {
                        sink.onData(p.serial(), p.channelNo(), header, copy);
                    } catch (Exception e) {
                        log.warn("海康回放数据处理失败: {} {}", p.serial(), e.getMessage());
                    }
                });
                return true;
            } catch (Throwable t) {
                log.error("海康回放数据回调异常: {}", t.getMessage());
                return true;
            }
        }
    }

    /** 定长字节数组转字符串（截到首个 {@code \0}）。 */
    private static String cstr(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        int end = 0;
        while (end < raw.length && raw[end] != 0) {
            end++;
        }
        return new String(raw, 0, end, StandardCharsets.UTF_8).trim();
    }
}
