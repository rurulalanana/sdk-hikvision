package io.github.rurulalanana.hikvision;

import io.github.rurulalanana.hikvision.internal.HikvisionCms;
import io.github.rurulalanana.hikvision.internal.HikvisionSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 海康 CMS 注册监听：接收设备主动注册，维护注册表。
 *
 * <h3>流程</h3>
 * <pre>
 *   设备上线 → ENUM_DEV_ON  → 填 ServerInfo（告诉设备报警推哪、图片存哪）→ 落注册表
 *   设备认证 → ENUM_DEV_AUTH → 写 ISUP Key（设备端配的校验值）
 *   会话密钥 → ENUM_DEV_SESSIONKEY → 同步给 Alarm 模块（加密链路）
 *   设备下线 → ENUM_DEV_OFF → 从注册表移除
 * </pre>
 *
 * <h3>回调里不能直接调 SDK</h3>
 * 官方手册明确要求「回调里调 SDK 接口必须另开线程，否则崩溃」。
 * 因此所有对 SDK 的写操作都丢到单线程执行器里，回调本身只做数据拷贝。
 *
 * <h3>为什么必须填 ServerInfo</h3>
 * 只返回 {@code true} 不填 {@code pInBuffer} 时，设备<b>注册成功但不知道往哪推报警</b>，
 * 表现就是「设备在线、能控制，但一条报警都收不到」。这是最容易漏的一步。
 */
public class HikvisionCmsListener {

    private static final Logger log = LoggerFactory.getLogger(HikvisionCmsListener.class);

    /** 设备上下线事件的接收方（平台侧用它落库/记日志）。 */
    public interface DeviceEventListener {
        /** 设备上线。 */
        void onOnline(HikvisionRegisteredDevice device);

        /** 设备下线。 */
        void onOffline(String serial);

        /** 设备地址变化（设备换网/换 IP 后重新注册）。 */
        default void onAddressChanged(HikvisionRegisteredDevice device) {
            onOnline(device);
        }
    }

    private final HikvisionRegisterTable table;

    private final DeviceEventListener listener;

    /** 监听地址：{@code null} 表示由 SDK 自选网卡。 */
    private final String bindIp;

    private final int port;

    /** 设备端配置的 ISUP 校验值；为空表示不校验（部分固件要求必填）。 */
    private final String isupKey;

    /** 报警服务器地址与端口（设备会把报警推到这里）。 */
    private final String alarmIp;
    private final int alarmTcpPort;
    private final int alarmUdpPort;
    /** 0-只 UDP，1-UDP+TCP。 */
    private final int alarmServerType;

    /** 图片存储服务器（SS）地址与端口；未启用时留空。 */
    private final String picIp;
    private final int picPort;
    /** 0-Tomcat，1-VRB，2-云存储，3-KMS，4-ISUP5.0。 */
    private final int picServerType;

    /**
     * SDK 调用执行器。
     *
     * <p>单线程：回调顺序必须与设备上报顺序一致（上线→认证→密钥），
     * 并发处理会让「先收到密钥、后收到上线」这种乱序发生。
     */
    private final ExecutorService sdkExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hikvision-cms-cb");
        t.setDaemon(true);
        return t;
    });

    private volatile int listenHandle = -1;

    private final AtomicBoolean started = new AtomicBoolean(false);

    /** 回调对象必须强引用住：JNA 只持弱引用，被 GC 掉会直接崩 JVM。 */
    private RegisterCallback callback;

    public HikvisionCmsListener(HikvisionRegisterTable table,
                                DeviceEventListener listener,
                                String bindIp, int port,
                                String isupKey,
                                String alarmIp, int alarmTcpPort, int alarmUdpPort,
                                int alarmServerType,
                                String picIp, int picPort, int picServerType) {
        this.table = table;
        this.listener = listener;
        this.bindIp = bindIp;
        this.port = port;
        this.isupKey = isupKey;
        this.alarmIp = alarmIp;
        this.alarmTcpPort = alarmTcpPort;
        this.alarmUdpPort = alarmUdpPort;
        this.alarmServerType = alarmServerType;
        this.picIp = picIp;
        this.picPort = picPort;
        this.picServerType = picServerType;
    }

    /** 当前监听端口（配置为 0 时由 SDK 分配，实际值要读这里）。 */
    public int port() {
        return port;
    }

    /** 是否已启动。 */
    public boolean started() {
        return started.get();
    }

    /**
     * 启动监听。
     *
     * @return 是否成功
     */
    public boolean start() {
        if (!started.compareAndSet(false, true)) {
            return true;
        }
        HikvisionSdk sdk = HikvisionSdk.get();
        if (!sdk.init()) {
            log.warn("海康 CMS 监听未启动：{}", sdk.diagnose());
            started.set(false);
            return false;
        }
        HikvisionCms cms = sdk.cms();
        if (cms == null) {
            started.set(false);
            return false;
        }
        try {
            callback = new RegisterCallback();
            HikvisionCms.NetEhomeCmsListenParam param = new HikvisionCms.NetEhomeCmsListenParam();
            if (bindIp != null && !bindIp.isBlank()) {
                byte[] raw = bindIp.getBytes(StandardCharsets.UTF_8);
                System.arraycopy(raw, 0, param.struAddress.szIP, 0,
                        Math.min(raw.length, param.struAddress.szIP.length - 1));
            }
            param.struAddress.wPort = (short) port;
            param.fnCB = callback;
            param.write();

            int handle = cms.NET_ECMS_StartListen(param);
            if (handle < 0) {
                log.error("海康 CMS 监听启动失败，错误码 {}（端口 {} 可能被占用）",
                        cms.NET_ECMS_GetLastError(), port);
                started.set(false);
                return false;
            }
            listenHandle = handle;
            log.info("海康 CMS 注册监听已启动: {}:{}（句柄 {}）",
                    bindIp == null || bindIp.isBlank() ? "0.0.0.0" : bindIp, port, handle);
            return true;
        } catch (Throwable t) {
            log.error("海康 CMS 监听启动异常: {}", t.getMessage(), t);
            started.set(false);
            return false;
        }
    }

    /** 停止监听。 */
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        HikvisionSdk sdk = HikvisionSdk.get();
        HikvisionCms cms = sdk.cms();
        if (cms != null && listenHandle >= 0) {
            cms.NET_ECMS_StopListen(listenHandle);
        }
        listenHandle = -1;
        sdkExecutor.shutdownNow();
        log.info("海康 CMS 注册监听已停止");
    }

    // ==================== 回调 ====================

    /**
     * 注册回调实现。
     *
     * <p>回调本身<b>只做数据拷贝与分发</b>：读结构体、转字符串、丢执行器。
     * 任何 SDK 调用（如 SetDeviceSessionKey）都放到执行器里做。
     */
    private final class RegisterCallback implements HikvisionCms.DeviceRegisterCb {

        @Override
        public boolean invoke(int lUserID, int dwDataType,
                              com.sun.jna.Pointer pOutBuffer, int dwOutLen,
                              com.sun.jna.Pointer pInBuffer, int dwInLen,
                              com.sun.jna.Pointer pUser) {
            try {
                switch (dwDataType) {
                    case HikvisionCms.ENUM_DEV_ON -> handleOnline(lUserID, pOutBuffer, dwOutLen,
                            pInBuffer, dwInLen);
                    case HikvisionCms.ENUM_DEV_OFF -> handleOffline(lUserID);
                    case HikvisionCms.ENUM_DEV_ADDRESS_CHANGED -> handleAddressChanged(lUserID,
                            pOutBuffer, dwOutLen);
                    case HikvisionCms.ENUM_DEV_AUTH -> handleAuth(pInBuffer, dwInLen);
                    case HikvisionCms.ENUM_DEV_SESSIONKEY -> handleSessionKey(pOutBuffer, dwOutLen);
                    case HikvisionCms.ENUM_DEV_DAS_EHOMEKEY_ERROR ->
                            log.warn("海康设备 ISUP 校验值错误（设备端配置的 Key 与平台不一致）");
                    case HikvisionCms.ENUM_DEV_SLEEP ->
                            log.info("海康设备进入休眠（休眠期间无法预览/回放/配置）");
                    default -> log.debug("海康注册回调: type={} userId={}", dwDataType, lUserID);
                }
            } catch (Throwable t) {
                // 回调里抛异常会直接崩 JVM（JNA 原生栈），必须全兜住
                log.error("海康注册回调处理异常: type={} {}", dwDataType, t.getMessage(), t);
            }
            return true;
        }

        private void handleOnline(int userId, com.sun.jna.Pointer out, int outLen,
                                  com.sun.jna.Pointer in, int inLen) {
            HikvisionCms.NetEhomeDevRegInfoV12 info = readRegInfo(out);
            if (info == null) {
                log.warn("海康设备上线但注册信息读取失败: userId={}", userId);
                return;
            }
            String serial = cstr(info.struRegInfo.sDeviceSerial);
            String deviceId = cstr(info.struRegInfo.byDeviceID);
            String fullSerial = cstr(info.byDeviceFullSerial);
            String ip = cstr(info.struRegAddr.szIP);
            int port = info.struRegAddr.wPort & 0xFFFF;
            String firmware = cstr(info.struRegInfo.byFirmwareVersion);
            String name = cstr(info.sDevName);

            // 序列号优先级：短 SN（设备标签）> 完整序列号 > 设备 ID
            String key = !serial.isBlank() ? serial
                    : (!fullSerial.isBlank() ? fullSerial : deviceId);
            if (key.isBlank()) {
                log.warn("海康设备上线但拿不到序列号: userId={} ip={}", userId, ip);
                return;
            }

            table.online(key, deviceId, ip, port, userId, firmware);
            log.info("海康设备上线: serial={} deviceId={} {}:{} userId={} 型号信息待补 fw={} name={}",
                    key, deviceId, ip, port, userId, firmware, name);

            // 必须填 ServerInfo 并写回，否则设备不知道往哪推报警
            fillServerInfo(in, inLen);

            HikvisionRegisteredDevice dev = table.byUserId(userId);
            if (dev != null) {
                sdkExecutor.execute(() -> {
                    try {
                        listener.onOnline(dev);
                    } catch (Exception e) {
                        log.warn("海康上线事件处理失败: serial={} {}", key, e.getMessage());
                    }
                });
            }
        }

        private void handleOffline(int userId) {
            HikvisionRegisteredDevice dev = table.byUserId(userId);
            if (dev == null) {
                log.debug("海康设备下线但注册表里没有: userId={}", userId);
                return;
            }
            table.offline(dev.serial());
            log.info("海康设备下线: serial={} userId={}", dev.serial(), userId);
            sdkExecutor.execute(() -> {
                try {
                    listener.onOffline(dev.serial());
                } catch (Exception e) {
                    log.warn("海康下线事件处理失败: serial={} {}", dev.serial(), e.getMessage());
                }
            });
        }

        private void handleAddressChanged(int userId, com.sun.jna.Pointer out, int outLen) {
            HikvisionCms.NetEhomeDevRegInfoV12 info = readRegInfo(out);
            if (info == null) {
                return;
            }
            String serial = cstr(info.struRegInfo.sDeviceSerial);
            String ip = cstr(info.struRegAddr.szIP);
            int port = info.struRegAddr.wPort & 0xFFFF;
            if (serial.isBlank()) {
                return;
            }
            table.online(serial, cstr(info.struRegInfo.byDeviceID), ip, port, userId,
                    cstr(info.struRegInfo.byFirmwareVersion));
            log.info("海康设备地址变更: serial={} → {}:{} userId={}", serial, ip, port, userId);
            HikvisionRegisteredDevice dev = table.byUserId(userId);
            if (dev != null) {
                sdkExecutor.execute(() -> listener.onAddressChanged(dev));
            }
        }

        /**
         * 设备认证：把平台配置的 ISUP Key 写回给设备。
         *
         * <p>设备端「ISUP 平台接入」里填的校验值必须与这里一致，
         * 否则会收到 {@code ENUM_DEV_DAS_EHOMEKEY_ERROR} 并反复重连。
         */
        private void handleAuth(com.sun.jna.Pointer in, int inLen) {
            if (isupKey == null || isupKey.isEmpty()) {
                log.debug("海康设备认证：平台未配置 ISUP Key，按不校验处理");
                return;
            }
            byte[] raw = isupKey.getBytes(StandardCharsets.UTF_8);
            in.write(0, raw, 0, raw.length);
            log.debug("海康设备认证：已写入 ISUP Key（{} 字节）", raw.length);
        }

        /** EHome5.0 加密链路：CMS 收到的会话密钥要同步给 Alarm 模块。 */
        private void handleSessionKey(com.sun.jna.Pointer out, int outLen) {
            HikvisionCms.NetEhomeDevRegInfoV12 info = readRegInfo(out);
            if (info == null) {
                return;
            }
            HikvisionCms.NetEhomeDevSessionKey key = new HikvisionCms.NetEhomeDevSessionKey();
            System.arraycopy(info.struRegInfo.byDeviceID, 0, key.sDeviceID, 0,
                    info.struRegInfo.byDeviceID.length);
            System.arraycopy(info.struRegInfo.bySessionKey, 0, key.sSessionKey, 0,
                    info.struRegInfo.bySessionKey.length);
            key.write();
            // SDK 调用放到执行器：回调里直接调 SDK 有崩溃风险
            sdkExecutor.execute(() -> {
                try {
                    HikvisionSdk sdk = HikvisionSdk.get();
                    if (sdk.cms() != null) {
                        sdk.cms().NET_ECMS_SetDeviceSessionKey(key.getPointer());
                    }
                    if (sdk.alarm() != null) {
                        sdk.alarm().NET_EALARM_SetDeviceSessionKey(key.getPointer());
                    }
                    log.debug("海康会话密钥已同步到 CMS 与 Alarm 模块");
                } catch (Exception e) {
                    log.warn("海康会话密钥同步失败: {}", e.getMessage());
                }
            });
        }

        /** 把注册信息从出参缓冲里读出来。 */
        private HikvisionCms.NetEhomeDevRegInfoV12 readRegInfo(com.sun.jna.Pointer out) {
            if (out == null) {
                return null;
            }
            try {
                HikvisionCms.NetEhomeDevRegInfoV12 info = new HikvisionCms.NetEhomeDevRegInfoV12();
                // 先按本地结构体大小算，再用 outLen 兜底，避免读到界外
                int size = Math.min(info.size(), out.getByteArray(0, info.size()).length);
                byte[] raw = out.getByteArray(0, size);
                info.getPointer().write(0, raw, 0, raw.length);
                info.read();
                return info;
            } catch (Exception e) {
                log.warn("读取海康注册信息失败: {}", e.getMessage());
                return null;
            }
        }

        /**
         * 填写并写回 {@code ServerInfo}。
         *
         * <p>{@code pInBuffer} 由 SDK 预分配（大小是 ServerInfoV50），
         * 平台只需按字段填值后写回。{@code dwSize} 必须设成结构体实际大小，
         * 否则 SDK 会按 0 解析、所有字段被忽略。
         */
        private void fillServerInfo(com.sun.jna.Pointer in, int inLen) {
            if (in == null) {
                return;
            }
            try {
                HikvisionCms.NetEhomeServerInfoV50 info = new HikvisionCms.NetEhomeServerInfoV50();
                int size = info.size();
                if (inLen > 0 && inLen < size) {
                    size = inLen;
                }
                byte[] current = in.getByteArray(0, size);
                info.getPointer().write(0, current, 0, current.length);
                info.read();

                info.dwSize = size;
                if (alarmIp != null && !alarmIp.isBlank()) {
                    byte[] ip = alarmIp.getBytes(StandardCharsets.UTF_8);
                    System.arraycopy(ip, 0, info.struTCPAlarmSever.szIP, 0,
                            Math.min(ip.length, info.struTCPAlarmSever.szIP.length - 1));
                    System.arraycopy(ip, 0, info.struUDPAlarmSever.szIP, 0,
                            Math.min(ip.length, info.struUDPAlarmSever.szIP.length - 1));
                }
                info.struTCPAlarmSever.wPort = (short) alarmTcpPort;
                info.struUDPAlarmSever.wPort = (short) alarmUdpPort;
                info.dwAlarmServerType = alarmServerType;

                if (picIp != null && !picIp.isBlank() && picPort > 0) {
                    byte[] ip = picIp.getBytes(StandardCharsets.UTF_8);
                    System.arraycopy(ip, 0, info.struPictureSever.szIP, 0,
                            Math.min(ip.length, info.struPictureSever.szIP.length - 1));
                    info.struPictureSever.wPort = (short) picPort;
                    info.dwPicServerType = picServerType;
                }

                info.write();
                byte[] out = info.getPointer().getByteArray(0, size);
                in.write(0, out, 0, out.length);
                log.debug("已向设备下发服务器信息: 报警 {}:{}（type={}）图片 {}:{}",
                        alarmIp, alarmTcpPort, alarmServerType, picIp, picPort);
            } catch (Exception e) {
                log.warn("填写海康 ServerInfo 失败（设备将收不到报警）: {}", e.getMessage());
            }
        }

        /** 读定长字节数组里的 C 字符串。 */
        private String cstr(byte[] raw) {
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
}
