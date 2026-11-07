package io.github.rurulalanana.hikvision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.Pointer;
import io.github.rurulalanana.hikvision.internal.HikvisionAlarm;
import io.github.rurulalanana.hikvision.internal.HikvisionSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 海康报警监听：接收设备上报的报警并归一化。
 *
 * <h3>两条载荷路径都要走</h3>
 * <ul>
 *   <li><b>结构化报警</b>（{@code EHOME_ALARM}）：固定结构体，覆盖移动侦测、
 *       视频丢失、设备/通道/磁盘状态等传统报警；</li>
 *   <li><b>ISAPI 报警</b>（{@code EHOME_ISAPI_ALARM}）：载荷是 XML 或 JSON，
 *       绊线、区域入侵、逆行、徘徊、人员聚集、声音异常等智能事件全在这里。</li>
 * </ul>
 * 只解结构化那套会漏掉<b>全部</b>智能事件 —— 这是接入时最容易犯的错。
 *
 * <h3>回调必须极快返回</h3>
 * 回调跑在 SDK 原生线程（默认还套了线程池）。解析大 JSON、写库都要丢到
 * 自己的执行器，否则会拖慢后续报警，设备侧表现为「报警延迟/丢包」。
 */
public class HikvisionAlarmListener {

    private static final Logger log = LoggerFactory.getLogger(HikvisionAlarmListener.class);

    /**
     * 归一化后的报警（与 {@code DeviceAlarmSink.DeviceAlarm} 对齐）。
     *
     * @param serial      设备序列号
     * @param channelNo   通道号（1-based；0 表示设备级事件）
     * @param type        事件类型
     * @param eventType   原始事件名（排障用，如 {@code linedetection}）
     * @param description 可读描述
     * @param alarmTime   报警时间（设备本地时间字符串）
     * @param ended       是否结束（报警恢复）
     * @param picCount    随报附带的图片数
     */
    public record HikvisionAlarmEvent(
            String serial,
            int channelNo,
            HikvisionAlarmType type,
            String eventType,
            String description,
            String alarmTime,
            boolean ended,
            int picCount) {
    }

    /** 报警事件接收方。 */
    public interface AlarmHandler {
        void onAlarm(HikvisionAlarmEvent event);
    }

    private final HikvisionRegisterTable table;

    private final AlarmHandler handler;

    /** 监听地址：{@code null} 表示由 SDK 自选。 */
    private final String bindIp;

    private final int port;

    /** 是否复用 CMS 端口（部署时只需放行一个端口）。 */
    private final boolean reuseCmsPort;

    /** 0-TCP，1-UDP。 */
    private final int protocolType;

    private final ObjectMapper json = new ObjectMapper();

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "hikvision-alarm");
        t.setDaemon(true);
        return t;
    });

    private volatile int listenHandle = -1;

    private final AtomicBoolean started = new AtomicBoolean(false);

    /** 回调对象必须强引用住：JNA 只持弱引用，被 GC 掉会直接崩 JVM。 */
    private MsgCallback callback;

    public HikvisionAlarmListener(HikvisionRegisterTable table,
                                  AlarmHandler handler,
                                  String bindIp, int port,
                                  boolean reuseCmsPort, int protocolType) {
        this.table = table;
        this.handler = handler;
        this.bindIp = bindIp;
        this.port = port;
        this.reuseCmsPort = reuseCmsPort;
        this.protocolType = protocolType;
    }

    public boolean started() {
        return started.get();
    }

    /** 启动报警监听。 */
    public boolean start() {
        if (!started.compareAndSet(false, true)) {
            return true;
        }
        HikvisionSdk sdk = HikvisionSdk.get();
        if (!sdk.init()) {
            log.warn("海康报警监听未启动：{}", sdk.diagnose());
            started.set(false);
            return false;
        }
        HikvisionAlarm alarm = sdk.alarm();
        if (alarm == null) {
            started.set(false);
            return false;
        }
        try {
            callback = new MsgCallback();
            HikvisionAlarm.NetEhomeAlarmListenParam param = new HikvisionAlarm.NetEhomeAlarmListenParam();
            if (reuseCmsPort) {
                // 复用 CMS 端口时地址必须填回环，SDK 会自己转到 CMS 那条连接
                byte[] loop = "127.0.0.1".getBytes(StandardCharsets.UTF_8);
                System.arraycopy(loop, 0, param.struAddress.szIP, 0, loop.length);
                param.byUseCmsPort = 1;
            } else if (bindIp != null && !bindIp.isBlank()) {
                byte[] raw = bindIp.getBytes(StandardCharsets.UTF_8);
                System.arraycopy(raw, 0, param.struAddress.szIP, 0,
                        Math.min(raw.length, param.struAddress.szIP.length - 1));
            }
            param.struAddress.wPort = (short) port;
            param.fnMsgCb = callback;
            param.byProtocolType = (byte) protocolType;
            param.write();

            int handle = alarm.NET_EALARM_StartListen(param);
            if (handle < 0) {
                log.error("海康报警监听启动失败，错误码 {}（端口 {} 可能被占用）",
                        alarm.NET_EALARM_GetLastError(), port);
                started.set(false);
                return false;
            }
            listenHandle = handle;
            log.info("海康报警监听已启动: {}:{}（句柄 {}，复用CMS端口={}）",
                    bindIp == null || bindIp.isBlank() ? "0.0.0.0" : bindIp,
                    port, handle, reuseCmsPort);
            return true;
        } catch (Throwable t) {
            log.error("海康报警监听启动异常: {}", t.getMessage(), t);
            started.set(false);
            return false;
        }
    }

    /** 停止监听。 */
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        HikvisionAlarm alarm = HikvisionSdk.get().alarm();
        if (alarm != null && listenHandle >= 0) {
            alarm.NET_EALARM_StopListen(listenHandle);
        }
        listenHandle = -1;
        executor.shutdownNow();
        log.info("海康报警监听已停止");
    }

    // ==================== 回调 ====================

    private final class MsgCallback implements HikvisionAlarm.EhomeMsgCallback {

        @Override
        public boolean invoke(int iHandle, HikvisionAlarm.NetEhomeAlarmMsg pAlarmMsg,
                              Pointer pUser) {
            try {
                if (pAlarmMsg == null) {
                    return true;
                }
                pAlarmMsg.read();
                int type = pAlarmMsg.dwAlarmType;
                if (type == HikvisionAlarm.EHOME_ISAPI_ALARM) {
                    // 智能事件：载荷是 XML/JSON，要解析具体事件类型
                    handleIsapiAlarm(pAlarmMsg);
                } else if (type == HikvisionAlarm.EHOME_ALARM) {
                    handleStructuredAlarm(pAlarmMsg);
                } else if (type == HikvisionAlarm.EHOME_ALARM_NOTICE_PICURL) {
                    handlePicUrl(pAlarmMsg);
                } else {
                    log.debug("海康报警: 未处理的类型 {}（载荷 {} 字节）",
                            type, pAlarmMsg.dwAlarmInfoLen);
                }
            } catch (Throwable t) {
                // 回调里抛异常会崩 JVM（JNA 原生栈），必须全兜住
                log.error("海康报警回调处理异常: {}", t.getMessage(), t);
            }
            return true;
        }

        /**
         * 结构化报警（{@code EHOME_ALARM}）。
         *
         * <p>{@code dwAlarmAction}：0=开始，1=停止。停止必须传下去，
         * 否则平台上的报警永远不会恢复。
         */
        private void handleStructuredAlarm(HikvisionAlarm.NetEhomeAlarmMsg msg) {
            if (msg.pAlarmInfo == null || msg.dwAlarmInfoLen <= 0) {
                return;
            }
            HikvisionAlarm.NetEhomeAlarmInfo info = new HikvisionAlarm.NetEhomeAlarmInfo(msg.pAlarmInfo);
            String serial = resolveSerial(msg, info.szDeviceID);
            int channelNo = info.dwVideoChannel;
            boolean ended = info.dwAlarmAction == 1;
            HikvisionAlarmType type = HikvisionAlarmType.ofSdkType(info.dwAlarmType);

            String desc = buildStructuredDescription(info, type, channelNo);
            HikvisionAlarmEvent event = new HikvisionAlarmEvent(serial, channelNo, type,
                    "sdk-" + info.dwAlarmType, desc, cstr(info.szAlarmTime), ended, 0);
            dispatch(event);
        }

        /**
         * ISAPI 报警（{@code EHOME_ISAPI_ALARM}）。
         *
         * <p>载荷可能是 JSON 或 XML，{@code byDataType} 标明：1-XML，2-JSON。
         * 海康新固件基本都用 JSON，XML 只在老设备上。
         */
        private void handleIsapiAlarm(HikvisionAlarm.NetEhomeAlarmMsg msg) {
            if (msg.pAlarmInfo == null || msg.dwAlarmInfoLen <= 0) {
                return;
            }
            HikvisionAlarm.NetEhomeAlarmIsapiInfo info =
                    new HikvisionAlarm.NetEhomeAlarmIsapiInfo(msg.pAlarmInfo);
            String serial = resolveSerial(msg, null);
            int picCount = info.byPicturesNumber & 0xFF;

            if (info.pAlarmData == null || info.dwAlarmDataLen <= 0) {
                log.debug("海康 ISAPI 报警无载荷: serial={}", serial);
                return;
            }
            byte[] raw = info.pAlarmData.getByteArray(0, info.dwAlarmDataLen);
            String payload = new String(raw, StandardCharsets.UTF_8);

            if (info.byDataType == 2) {
                parseJsonAlarm(serial, payload, picCount);
            } else {
                // XML 载荷：老设备用，字段名与 JSON 一致，直接抽标签值
                parseXmlAlarm(serial, payload, picCount);
            }
        }

        /** 解析 JSON 形式的 ISAPI 报警。 */
        private void parseJsonAlarm(String serial, String payload, int picCount) {
            JsonNode root;
            try {
                root = json.readTree(payload);
            } catch (Exception e) {
                log.debug("海康 ISAPI 报警 JSON 解析失败: serial={} {}", serial, e.getMessage());
                return;
            }
            if (root == null) {
                return;
            }
            // 事件类型可能在顶层，也可能在 eventType / EventNotification.Alert 里
            String eventType = firstText(root, "eventType", "event_type", "EventType");
            JsonNode alert = root.path("EventNotification").path("Alert");
            if (eventType == null && !alert.isMissingNode()) {
                eventType = firstText(alert, "eventType", "event_type");
            }
            // 有的固件把事件类型直接当键名（如 {"linedetection": {...}}）
            if (eventType == null) {
                var names = root.fieldNames();
                while (names.hasNext()) {
                    String n = names.next();
                    if (HikvisionAlarmType.of(n) != HikvisionAlarmType.UNKNOWN) {
                        eventType = n;
                        break;
                    }
                }
            }

            HikvisionAlarmType type = HikvisionAlarmType.of(eventType);
            int channelNo = firstInt(root, alert, "channelID", "dynChannelID", "channel");
            String time = firstText(root, "dateTime", "time", "eventTime");
            if (time == null) {
                time = firstText(alert, "dateTime", "time");
            }
            boolean ended = isEnded(root, alert);
            String desc = type.label();
            String name = firstText(root, "name", "eventDescription");
            if (name != null && !name.isBlank()) {
                desc = desc + "：" + name;
            }
            dispatch(new HikvisionAlarmEvent(serial, channelNo, type,
                    eventType == null ? "unknown" : eventType, desc,
                    time == null ? "" : time, ended, picCount));
        }

        /**
         * 解析 XML 形式的 ISAPI 报警。
         *
         * <p>不引 XML 库：载荷结构简单（一层标签），正则抽值足够，
         * 而且能容忍命名空间前缀（{@code <ns:eventType>}）。
         */
        private void parseXmlAlarm(String serial, String payload, int picCount) {
            String eventType = xmlValue(payload, "eventType");
            if (eventType == null) {
                eventType = xmlValue(payload, "event_type");
            }
            HikvisionAlarmType type = HikvisionAlarmType.of(eventType);
            String channelText = xmlValue(payload, "channelID");
            int channelNo = parseInt(channelText, 0);
            String time = xmlValue(payload, "dateTime");
            String state = xmlValue(payload, "eventState");
            boolean ended = state != null && state.toLowerCase().contains("inactive");
            dispatch(new HikvisionAlarmEvent(serial, channelNo, type,
                    eventType == null ? "unknown" : eventType, type.label(),
                    time == null ? "" : time, ended, picCount));
        }

        /** 图片 URL 上报（抓图直传场景）。 */
        private void handlePicUrl(HikvisionAlarm.NetEhomeAlarmMsg msg) {
            if (msg.pAlarmInfo == null || msg.dwAlarmInfoLen <= 0) {
                return;
            }
            HikvisionAlarm.NetEhomeNoticePicUrl info =
                    new HikvisionAlarm.NetEhomeNoticePicUrl(msg.pAlarmInfo);
            String serial = resolveSerial(msg, info.byDeviceID);
            String url = cstr(info.byPicUrl);
            log.debug("海康图片上报: serial={} channel={} url={}",
                    serial, info.dwCaptureChan, url);
            // 抓图直传由平台侧单独处理（写入抓图目录），这里不当作报警事件
        }

        /** 优先用注册表里的序列号（更可靠），回调里给的字段只作兜底。 */
        private String resolveSerial(HikvisionAlarm.NetEhomeAlarmMsg msg, byte[] fallback) {
            String sn = cstr(msg.sSerialNumber);
            if (!sn.isBlank()) {
                HikvisionRegisteredDevice d = table.byDeviceId(sn);
                if (d != null) {
                    return d.serial();
                }
                return sn;
            }
            if (fallback != null) {
                String v = cstr(fallback);
                if (!v.isBlank()) {
                    return v;
                }
            }
            return "";
        }

        private void dispatch(HikvisionAlarmEvent event) {
            if (event.serial() == null || event.serial().isBlank()) {
                log.debug("海康报警缺少序列号，丢弃: {}", event);
                return;
            }
            log.debug("海康报警: serial={} type={}({}) channel={} ended={} pics={}",
                    event.serial(), event.type().label(), event.eventType(),
                    event.channelNo(), event.ended(), event.picCount());
            executor.execute(() -> {
                try {
                    handler.onAlarm(event);
                } catch (Exception e) {
                    log.warn("海康报警处理失败: serial={} {}",
                            event.serial(), e.getMessage());
                }
            });
        }

        private String buildStructuredDescription(HikvisionAlarm.NetEhomeAlarmInfo info,
                                                  HikvisionAlarmType type, int channelNo) {
            StringBuilder sb = new StringBuilder(type.label());
            if (channelNo > 0) {
                sb.append("（通道 ").append(channelNo).append("）");
            }
            if (info.dwAlarmType == HikvisionAlarm.ALARM_TYPE_CHAN_CHANGED_STATUS) {
                // 通道状态是位图，翻译成人话
                HikvisionAlarm.NetEhomeChanStatusChanged st =
                        new HikvisionAlarm.NetEhomeChanStatusChanged(info.getPointer());
                int bits = st.byChanStatus & 0xFF;
                sb.append("：").append((bits & 0x02) != 0 ? "在线" : "离线");
                if ((bits & 0x04) == 0) {
                    sb.append("、无信号");
                }
            }
            return sb.toString();
        }

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

    // ==================== 载荷字段抽取 ====================

    private static String firstText(JsonNode node, String... names) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        for (String n : names) {
            JsonNode v = node.get(n);
            if (v != null && v.isValueNode() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return null;
    }

    private static int firstInt(JsonNode root, JsonNode alert, String... names) {
        for (String n : names) {
            JsonNode v = root.get(n);
            if (v != null && v.isValueNode()) {
                return v.asInt(0);
            }
            if (!alert.isMissingNode()) {
                JsonNode a = alert.get(n);
                if (a != null && a.isValueNode()) {
                    return a.asInt(0);
                }
            }
        }
        return 0;
    }

    /**
     * 判断报警是否已结束。
     *
     * <p>ISAPI 的 {@code eventState} 用 {@code active} / {@code inactive}；
     * 部分固件用布尔 {@code eventStatus}。两者都要认，否则报警永远不恢复。
     */
    private static boolean isEnded(JsonNode root, JsonNode alert) {
        for (JsonNode node : new JsonNode[]{root, alert}) {
            if (node == null || node.isMissingNode()) {
                continue;
            }
            String state = firstText(node, "eventState", "event_state");
            if (state != null) {
                return state.toLowerCase().contains("inactive");
            }
            JsonNode status = node.get("eventStatus");
            if (status != null && status.isBoolean()) {
                return !status.asBoolean();
            }
        }
        return false;
    }

    /** 从 XML 里抽某个标签的值（容忍命名空间前缀）。 */
    private static String xmlValue(String xml, String tag) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<(?:\\w+:)?" + tag + ">([^<]*)</(?:\\w+:)?" + tag + ">")
                .matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    private static int parseInt(String text, int def) {
        if (text == null || text.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
