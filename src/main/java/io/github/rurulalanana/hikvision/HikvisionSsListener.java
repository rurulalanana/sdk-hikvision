package io.github.rurulalanana.hikvision;

import com.sun.jna.Pointer;
import io.github.rurulalanana.hikvision.internal.HikvisionSdk;
import io.github.rurulalanana.hikvision.internal.HikvisionSs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 海康抓图直传监听（SS）：接收设备主动推来的 JPEG。
 *
 * <h3>为什么比「平台反向抓图」好</h3>
 * 反向抓图要平台连设备（NAT 下未必通），且图片要设备编码后完整传一遍。
 * 这里反过来：设备连平台上传，平台只落盘。设备本来就会为「报警联动抓图」
 * 上传图片，平台顺势收下，等于零额外流量 —— 这正是用户要的
 * 「设备直接上传快照图片到服务器，流量消耗低，无需服务端拉流解码」。
 *
 * <h3>握手流程</h3>
 * <ol>
 *   <li>设备连上来，在 {@code fnSSMsgCb} 里问「N 张图该传到哪个 URL」；</li>
 *   <li>平台填好 N 个 URL（每个定长 {@code 4096} 字节）返回；</li>
 *   <li>设备按 URL 上传，SDK 收完后回调 {@code fnSStorageCb} 给出文件名与内容。</li>
 * </ol>
 *
 * <h3>回调里绝不能调 SDK</h3>
 * 官方注释明确警告：存储/消息回调中调用其它 {@code NET_ESS_*} 接口会崩溃或堵死。
 * 所以回调里只拷数据、丢线程池。
 */
public class HikvisionSsListener {

    private static final Logger log = LoggerFactory.getLogger(HikvisionSsListener.class);

    /** 图片接收方。 */
    public interface PictureSink {
        /**
         * 收到一张图。
         *
         * @param serial  设备序列号（从 URL 里解析，可能为空）
         * @param name    文件名
         * @param content JPEG 字节
         */
        void onPicture(String serial, String name, byte[] content);
    }

    /**
     * 上传 URL 的路径前缀。
     *
     * <p>设备会往这个 URL POST 图片。我们把设备序列号与文件名编进路径，
     * 这样落盘时不需要额外的映射表 —— 单机部署够用，多节点时才需要外部存储。
     */
    private static final String URL_PREFIX = "/hikvision/picture";

    private final PictureSink sink;

    private final String bindIp;

    private final int port;

    /** 告诉设备的公网地址（NAT 时必须填公网 IP）。 */
    private final String publicIp;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "hikvision-ss");
        t.setDaemon(true);
        return t;
    });

    private volatile int listenHandle = -1;

    private final AtomicBoolean started = new AtomicBoolean(false);

    /** 回调对象必须强引用住，否则被 GC 掉会崩 JVM。 */
    private MsgCallback msgCallback;

    private StorageCallback storageCallback;

    public HikvisionSsListener(PictureSink sink, String bindIp, int port, String publicIp) {
        this.sink = sink;
        this.bindIp = bindIp;
        this.port = port;
        this.publicIp = publicIp;
    }

    public boolean started() {
        return started.get();
    }

    /** 启动抓图监听。SS 库缺失时返回 {@code false}，不影响其它链路。 */
    public boolean start() {
        if (!started.compareAndSet(false, true)) {
            return true;
        }
        HikvisionSdk sdk = HikvisionSdk.get();
        HikvisionSs ss = sdk.ss();
        if (ss == null) {
            log.info("海康抓图直传未启动：{}", sdk.ssError());
            started.set(false);
            return false;
        }
        try {
            msgCallback = new MsgCallback();
            storageCallback = new StorageCallback();

            HikvisionSs.NetEhomeSsListenParam cfg = new HikvisionSs.NetEhomeSsListenParam();
            if (bindIp != null && !bindIp.isBlank()) {
                byte[] raw = bindIp.getBytes(StandardCharsets.UTF_8);
                System.arraycopy(raw, 0, cfg.struAddress.szIP, 0,
                        Math.min(raw.length, cfg.struAddress.szIP.length - 1));
            }
            cfg.struAddress.wPort = (short) port;
            cfg.fnSSMsgCb = msgCallback;
            cfg.fnSStorageCb = storageCallback;
            // 0 = 开启安全模式（默认）。关掉会让设备侧不用配 KMS 账号，
            // 但也失去校验，只在调试时考虑关。
            cfg.bySecurityMode = 0;
            cfg.write();

            int handle = ss.NET_ESS_StartListen(cfg);
            if (handle < 0) {
                log.error("海康抓图监听启动失败，错误码 {}（端口 {} 可能被占用）",
                        ss.NET_ESS_GetLastError(), port);
                started.set(false);
                return false;
            }
            listenHandle = handle;
            log.info("海康抓图直传监听已启动: {}:{}（句柄 {}），上传地址 {}{}",
                    bindIp == null || bindIp.isBlank() ? "0.0.0.0" : bindIp, port, handle,
                    resolvePublicIp(), URL_PREFIX);
            return true;
        } catch (Throwable t) {
            log.error("海康抓图监听启动异常: {}", t.getMessage(), t);
            started.set(false);
            return false;
        }
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        HikvisionSs ss = HikvisionSdk.get().ss();
        if (ss != null && listenHandle >= 0) {
            ss.NET_ESS_StopListen(listenHandle);
        }
        listenHandle = -1;
        executor.shutdownNow();
        log.info("海康抓图直传监听已停止");
    }

    /** 告诉设备的地址：优先公网 IP，否则监听地址。 */
    private String resolvePublicIp() {
        if (publicIp != null && !publicIp.isBlank()) {
            return publicIp;
        }
        if (bindIp != null && !bindIp.isBlank() && !"0.0.0.0".equals(bindIp)) {
            return bindIp;
        }
        return "127.0.0.1";
    }

    // ==================== 回调 ====================

    /**
     * 设备请求上传地址。
     *
     * <p>{@code pPicURLs} 是一块 {@code dwPicNum × 4096} 的缓冲，
     * 要按「每段定长 4096、以 {@code \0} 结尾」逐段写入。
     */
    private final class MsgCallback implements HikvisionSs.MsgCallback {

        @Override
        public boolean invoke(int iHandle, int enumType, Pointer pOutBuffer, int dwOutLen,
                              Pointer pInBuffer, int dwInLen, Pointer pUser) {
            try {
                if (enumType != HikvisionSs.SS_MSG_TOMCAT) {
                    // KMS / 云存储 / 地址查询我们都不用，回 false 让设备走别的协议
                    log.debug("海康抓图收到未处理的消息类型: {}", enumType);
                    return false;
                }
                if (pOutBuffer == null) {
                    return false;
                }
                HikvisionSs.NetEhomeSsTomcatMsg msg = new HikvisionSs.NetEhomeSsTomcatMsg(pOutBuffer);
                int count = msg.dwPicNum;
                String devUri = cstr(msg.szDevUri);
                if (count <= 0 || msg.pPicURLs == null) {
                    log.warn("海康抓图请求参数异常: 图片数={} uri={}", count, devUri);
                    return false;
                }

                // 从设备请求的 URI 里尽力解析序列号：不同固件带的参数不一样，
                // 解析不出来也不影响上传，落盘时序列号留空即可
                String serial = serialOf(devUri);
                String base = "http://" + resolvePublicIp() + ":" + port + URL_PREFIX
                        + "?serial=" + (serial.isBlank() ? "unknown" : serial);

                for (int i = 0; i < count; i++) {
                    String url = base + "&index=" + i;
                    byte[] raw = url.getBytes(StandardCharsets.UTF_8);
                    int len = Math.min(raw.length, HikvisionSs.MAX_URL_LEN - 1);
                    // 每段定长 4096、以 \0 结尾：SDK 按 C 字符串逐段读
                    byte[] slot = new byte[HikvisionSs.MAX_URL_LEN];
                    System.arraycopy(raw, 0, slot, 0, len);
                    msg.pPicURLs.write((long) i * HikvisionSs.MAX_URL_LEN, slot, 0, slot.length);
                }
                log.info("海康抓图请求已受理: 设备={} 图片数={}", serial, count);
                return true;
            } catch (Throwable t) {
                log.error("海康抓图消息回调异常: {}", t.getMessage(), t);
                return false;
            }
        }
    }

    /**
     * 图片内容到达。
     *
     * <p>只拷字节丢线程池：这里调 SDK 会崩（官方警告）。
     */
    private final class StorageCallback implements HikvisionSs.StorageCallback {

        @Override
        public boolean invoke(int iHandle, Pointer pFileName, Pointer pFileBuf, int dwFileLen,
                              Pointer pFilePath, Pointer pUser) {
            try {
                if (pFileBuf == null || dwFileLen <= 0) {
                    return false;
                }
                String name = pFileName == null ? "" : pFileName.getString(0, "UTF-8");
                byte[] copy = pFileBuf.getByteArray(0, dwFileLen);
                String serial = serialOf(name);
                executor.execute(() -> {
                    try {
                        sink.onPicture(serial, name, copy);
                    } catch (Exception e) {
                        log.warn("海康抓图落盘失败: {} {}", name, e.getMessage());
                    }
                });
                return true;
            } catch (Throwable t) {
                log.error("海康抓图存储回调异常: {}", t.getMessage());
                return false;
            }
        }
    }

    /**
     * 从 URL 或文件名里尽力解析设备序列号。
     *
     * <p>海康的文件名形如 {@code DS-2CD3T46_20200101AAAG12345678_20240101120000.jpg}，
     * 序列号在中间；URL 里我们自己也带了 {@code serial=} 参数。
     * 解析不出来就返回空串 —— 落盘时序列号留空，不影响图片本身。
     */
    static String serialOf(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int idx = text.indexOf("serial=");
        if (idx >= 0) {
            String rest = text.substring(idx + 7);
            int amp = rest.indexOf('&');
            return amp >= 0 ? rest.substring(0, amp) : rest;
        }
        // 文件名：取第 2 段（下划线分隔）
        String base = text;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        String[] parts = base.split("_");
        if (parts.length >= 2 && parts[1].length() >= 8) {
            return parts[1];
        }
        return "";
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

    /** 供装配体判断是否可用。 */
    public static boolean available() {
        return HikvisionSdk.get().ss() != null;
    }

    /** 未启用的原因。 */
    public static String unavailableReason() {
        HikvisionSdk sdk = HikvisionSdk.get();
        return sdk.ss() == null ? sdk.ssError() : null;
    }
}
