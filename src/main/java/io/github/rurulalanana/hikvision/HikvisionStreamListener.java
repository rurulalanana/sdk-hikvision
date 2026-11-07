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
 * 海康流媒体监听：平台当 SMS，接收设备推来的 PS 码流。
 *
 * <h3>与国标/大华的取流方向相反</h3>
 * <ul>
 *   <li>国标：平台 INVITE，设备把 RTP 推到平台端口；</li>
 *   <li>大华：平台调 SDK 拉流，SDK 回调裸流给平台；</li>
 *   <li><b>ISUP</b>：平台起监听端口，<b>设备主动连上来推码流</b>。</li>
 * </ul>
 * 所以部署时必须让设备能访问到这里的监听端口（NAT 要端口映射）。
 *
 * <h3>两段式：先请求，再收流</h3>
 * <ol>
 *   <li>平台调 {@code NET_ECMS_StartGetRealStream} 告诉设备「请往 SMS 推流」；</li>
 *   <li>设备连到 SMS 端口 → 触发 {@code fnNewLinkCB} →
 *       平台必须在此回调里 {@code NET_ESTREAM_SetPreviewDataCB} 绑定数据回调，
 *       否则收不到任何数据（这是最容易漏的一步）；</li>
 *   <li>平台再调 {@code NET_ECMS_StartPushRealStream} 通知设备「可以开始推了」；</li>
 *   <li>数据从 {@code fnPreviewDataCB} 到达，类型 {@code SYSHEAD}/{@code STREAMDATA}。</li>
 * </ol>
 *
 * <h3>码流是 PS 封装</h3>
 * 设备推的是 PS（Program Stream），不是裸 H264/H265。平台不自己解 PS：
 * 交给 ZLM 的 {@code mk_ps_decoder_*}，它输出 Annex-B 裸流，
 * 再喂给 {@code mk_media_input_h264/265} —— 复用已有的 ES 发布通路。
 */
public class HikvisionStreamListener {

    private static final Logger log = LoggerFactory.getLogger(HikvisionStreamListener.class);

    /** 一路流的数据接收方。 */
    public interface StreamDataSink {
        /**
         * 收到一包 PS 数据。
         *
         * @param serial    设备序列号
         * @param channelNo 通道号（1-based）
         * @param streamType 0-主码流，1-子码流
         * @param sysHead   {@code true} 表示这是码流头（PS system header）
         * @param data      数据（PS 封装）
         */
        void onData(String serial, int channelNo, int streamType, boolean sysHead, byte[] data);

        /** 流结束。 */
        default void onEnd(String serial, int channelNo, int streamType) {
        }
    }

    /** 一路已建立的流。 */
    private record ActiveLink(String serial, int channelNo, int streamType, int linkHandle) {
    }

    private final HikvisionRegisterTable table;

    private final StreamDataSink sink;

    private final String bindIp;

    private final int port;

    /** 0-TCP，1-UDP。 */
    private final int linkMode;

    /**
     * 预览请求所需的 SMS 公网地址（设备会往这个地址连）。
     *
     * <p>与监听地址不同：监听可能是 {@code 0.0.0.0}，但告诉设备必须是
     * <b>设备能访问到的地址</b>。NAT 后要填公网 IP/映射后的地址。
     */
    private final String smsIp;

    private final int smsPort;

    /** 连接句柄 → 流信息。 */
    private final Map<Integer, ActiveLink> links = new ConcurrentHashMap<>();

    /** 会话 ID → 流信息（停止时要用）。 */
    private final Map<String, Integer> sessionIds = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "hikvision-stream");
        t.setDaemon(true);
        return t;
    });

    private volatile int listenHandle = -1;

    private final AtomicBoolean started = new AtomicBoolean(false);

    /** 回调对象必须强引用住，否则被 GC 掉会崩 JVM。 */
    private NewLinkCallback newLinkCallback;

    private DataCallback dataCallback;

    public HikvisionStreamListener(HikvisionRegisterTable table,
                                   StreamDataSink sink,
                                   String bindIp, int port,
                                   int linkMode,
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

    /** 启动预览监听。 */
    public boolean start() {
        if (!started.compareAndSet(false, true)) {
            return true;
        }
        HikvisionSdk sdk = HikvisionSdk.get();
        if (!sdk.init()) {
            log.warn("海康流媒体监听未启动：{}", sdk.diagnose());
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

            HikvisionStream.NetEhomeListenPreviewCfg cfg =
                    new HikvisionStream.NetEhomeListenPreviewCfg();
            if (bindIp != null && !bindIp.isBlank()) {
                byte[] raw = bindIp.getBytes(StandardCharsets.UTF_8);
                System.arraycopy(raw, 0, cfg.struIPAdress.szIP, 0,
                        Math.min(raw.length, cfg.struIPAdress.szIP.length - 1));
            }
            cfg.struIPAdress.wPort = (short) port;
            cfg.fnNewLinkCB = newLinkCallback;
            cfg.byLinkMode = (byte) linkMode;
            cfg.write();

            int handle = stream.NET_ESTREAM_StartListenPreview(cfg);
            if (handle < 0) {
                log.error("海康流媒体监听启动失败，错误码 {}（端口 {} 可能被占用）",
                        stream.NET_ESTREAM_GetLastError(), port);
                started.set(false);
                return false;
            }
            listenHandle = handle;
            log.info("海康流媒体监听已启动: {}:{}（句柄 {}），设备将连接 {}:{}",
                    bindIp == null || bindIp.isBlank() ? "0.0.0.0" : bindIp, port, handle,
                    smsIp, smsPort);
            return true;
        } catch (Throwable t) {
            log.error("海康流媒体监听启动异常: {}", t.getMessage(), t);
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
            stream.NET_ESTREAM_StopListenPreview(listenHandle);
        }
        listenHandle = -1;
        links.clear();
        sessionIds.clear();
        executor.shutdownNow();
        log.info("海康流媒体监听已停止");
    }

    // ==================== 预览控制 ====================

    /**
     * 请求设备推流。
     *
     * <p>三步缺一不可：StartGetRealStream → （设备连上来触发 newLink）→ StartPushRealStream。
     * 漏掉最后一步设备不会推数据，表现为「连上了但一直没画面」。
     *
     * @param serial     设备序列号
     * @param channelNo  通道号（1-based）
     * @param streamType 0-主码流，1-子码流
     * @return 会话 ID；失败返回 {@code -1}
     */
    public int startPreview(String serial, int channelNo, int streamType) {
        HikvisionSdk sdk = HikvisionSdk.get();
        if (!sdk.available()) {
            log.warn("海康取流不可用：{}", sdk.diagnose());
            return -1;
        }
        HikvisionRegisteredDevice dev = table.byDeviceId(serial);
        if (dev == null) {
            dev = table.byDeviceId(serial);
        }
        if (dev == null) {
            log.warn("海康取流失败：设备未注册 serial={}", serial);
            return -1;
        }
        HikvisionCms cms = sdk.cms();
        if (cms == null) {
            return -1;
        }
        try {
            HikvisionCms.NetEhomePreviewInfoIn in = new HikvisionCms.NetEhomePreviewInfoIn();
            in.iChannel = channelNo;
            in.dwLinkMode = linkMode;
            in.dwStreamType = streamType;
            byte[] ip = smsIp.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(ip, 0, in.struStreamSever.szIP, 0,
                    Math.min(ip.length, in.struStreamSever.szIP.length - 1));
            in.struStreamSever.wPort = (short) smsPort;
            in.write();

            HikvisionCms.NetEhomePreviewInfoOut out = new HikvisionCms.NetEhomePreviewInfoOut();
            if (!cms.NET_ECMS_StartGetRealStream(dev.userId(), in, out)) {
                log.warn("海康请求预览失败: serial={} channel={} 错误码 {}",
                        serial, channelNo, cms.NET_ECMS_GetLastError());
                return -1;
            }
            out.read();
            int sessionId = out.lSessionID;

            HikvisionCms.NetEhomePushStreamIn pushIn = new HikvisionCms.NetEhomePushStreamIn();
            pushIn.dwSize = pushIn.size();
            pushIn.lSessionID = sessionId;
            pushIn.write();
            HikvisionCms.NetEhomePushStreamOut pushOut = new HikvisionCms.NetEhomePushStreamOut();
            pushOut.dwSize = pushOut.size();
            pushOut.write();
            if (!cms.NET_ECMS_StartPushRealStream(dev.userId(), pushIn, pushOut)) {
                log.warn("海康通知推流失败: serial={} session={} 错误码 {}",
                        serial, sessionId, cms.NET_ECMS_GetLastError());
                cms.NET_ECMS_StopGetRealStream(dev.userId(), sessionId);
                return -1;
            }
            sessionIds.put(key(serial, channelNo, streamType), sessionId);
            log.info("海康预览已请求: serial={} channel={} streamType={} session={}",
                    serial, channelNo, streamType, sessionId);
            return sessionId;
        } catch (Throwable t) {
            log.error("海康请求预览异常: serial={} {}", serial, t.getMessage(), t);
            return -1;
        }
    }

    /** 停止推流。 */
    public void stopPreview(String serial, int channelNo, int streamType) {
        String k = key(serial, channelNo, streamType);
        Integer sessionId = sessionIds.remove(k);
        HikvisionSdk sdk = HikvisionSdk.get();
        if (sdk.stream() != null) {
            for (Map.Entry<Integer, ActiveLink> e : links.entrySet()) {
                ActiveLink link = e.getValue();
                if (link.serial().equals(serial) && link.channelNo() == channelNo
                        && link.streamType() == streamType) {
                    sdk.stream().NET_ESTREAM_StopPreview(e.getKey());
                    links.remove(e.getKey());
                }
            }
        }
        if (sessionId != null && sdk.cms() != null) {
            HikvisionRegisteredDevice dev = table.byDeviceId(serial);
            if (dev != null) {
                sdk.cms().NET_ECMS_StopGetRealStream(dev.userId(), sessionId);
            }
        }
        log.info("海康预览已停止: serial={} channel={} streamType={}", serial, channelNo, streamType);
    }

    private static String key(String serial, int channelNo, int streamType) {
        return serial + "|" + channelNo + "|" + streamType;
    }

    // ==================== 回调 ====================

    /** 设备连上来时回调：必须在这里绑定数据回调。 */
    private final class NewLinkCallback implements HikvisionStream.PreviewNewLinkCb {

        @Override
        public boolean invoke(int lLinkHandle, HikvisionStream.NetEhomeNewLinkCbMsg msg,
                              Pointer pUserData) {
            try {
                msg.read();
                String deviceId = cstr(msg.szDeviceID);
                String serial = cstr(msg.sDeviceSerial);
                HikvisionRegisteredDevice dev = table.byDeviceId(
                        serial.isBlank() ? deviceId : serial);
                String resolved = dev != null ? dev.serial()
                        : (serial.isBlank() ? deviceId : serial);
                int channelNo = msg.dwChannelNo;
                int streamType = msg.byStreamType & 0xFF;

                links.put(lLinkHandle, new ActiveLink(resolved, channelNo, streamType, lLinkHandle));

                // 关键：不绑定数据回调就收不到任何数据
                HikvisionStream.NetEhomePreviewDataCbParam param =
                        new HikvisionStream.NetEhomePreviewDataCbParam();
                param.fnPreviewDataCB = dataCallback;
                param.write();
                HikvisionStream stream = HikvisionSdk.get().stream();
                if (stream == null
                        || !stream.NET_ESTREAM_SetPreviewDataCB(lLinkHandle, param)) {
                    log.warn("海康绑定数据回调失败: link={} serial={}",
                            lLinkHandle, resolved);
                    return false;
                }
                log.info("海康设备已连上 SMS: serial={} channel={} streamType={} link={}",
                        resolved, channelNo, streamType, lLinkHandle);
                return true;
            } catch (Throwable t) {
                log.error("海康新连接回调异常: {}", t.getMessage(), t);
                return false;
            }
        }
    }

    /**
     * 数据回调：运行在 SDK 原生线程。
     *
     * <p>只做「拷字节 + 丢执行器」：解码 PS、推 ZLM 都在别的线程做，
     * 否则会阻塞 SDK 收包，表现为丢帧/花屏。
     */
    private final class DataCallback implements HikvisionStream.PreviewDataCb {

        @Override
        public void invoke(int iPreviewHandle, HikvisionStream.NetEhomePreviewCbMsg msg,
                           Pointer pUserData) {
            try {
                msg.read();
                if (msg.pRecvdata == null || msg.dwDataLen <= 0) {
                    return;
                }
                ActiveLink link = links.get(iPreviewHandle);
                if (link == null) {
                    return;
                }
                int type = msg.byDataType & 0xFF;
                if (type == HikvisionStream.NET_EHOME_STREAMEND) {
                    executor.execute(() -> sink.onEnd(link.serial(), link.channelNo(),
                            link.streamType()));
                    return;
                }
                byte[] data = msg.pRecvdata.getByteArray(0, msg.dwDataLen);
                boolean sysHead = type == HikvisionStream.NET_EHOME_SYSHEAD;
                executor.execute(() -> {
                    try {
                        sink.onData(link.serial(), link.channelNo(), link.streamType(),
                                sysHead, data);
                    } catch (Exception e) {
                        log.warn("海康码流处理失败: serial={} {}", link.serial(), e.getMessage());
                    }
                });
            } catch (Throwable t) {
                // 回调里抛异常会崩 JVM，必须全兜住
                log.error("海康数据回调异常: {}", t.getMessage());
            }
        }
    }

    private static String cstr(byte[] raw) {
        if (raw == null) {
            return "";
        }
        int end = 0;
        while (end < raw.length && raw[end] != 0) {
            end++;
        }
        return new String(raw, 0, end, StandardCharsets.UTF_8).trim();
    }
}
