package io.github.rurulalanana.hikvision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import io.github.rurulalanana.hikvision.internal.HikvisionCms;
import io.github.rurulalanana.hikvision.internal.HikvisionSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 海康 ISAPI 客户端：通过 ISUP 透传调用设备的 REST 接口。
 *
 * <h3>为什么大部分功能都走 ISAPI 而不是 SDK 专用接口</h3>
 * ISUP SDK 只把「信令与流」做成了专用接口，其余（资源获取、云台、图像参数、
 * 编码配置、抓图）全部通过 {@code NET_ECMS_ISAPIPassThrough} 转发到设备的
 * ISAPI（就是设备 Web 界面背后那套 REST）。好处是：
 * <ul>
 *   <li>覆盖全：设备 Web 能做的，这里都能做；</li>
 *   <li>不用为每个型号写一套 SDK 调用；</li>
 *   <li>返回的是 JSON/XML，字段名与官方 ISAPI 文档一致，可查可对。</li>
 * </ul>
 *
 * <h3>请求格式</h3>
 * {@code pRequestUrl} 不是纯 URL，而是 <b>HTTP 请求行</b>：
 * <pre>
 *   GET /ISAPI/System/deviceInfo?format=json HTTP/1.1
 *   PUT /ISAPI/Image/channels/1/color?format=json HTTP/1.1
 * </pre>
 * 少了 {@code HTTP/1.1} 设备会返回 400。
 *
 * <h3>响应</h3>
 * 设备返回完整 HTTP 响应（含状态行与头），平台要跳过头部取 body；
 * 状态码从 {@code pStatusBuffer} 取。
 */
public class HikvisionIsapi {

    private static final Logger log = LoggerFactory.getLogger(HikvisionIsapi.class);

    /** 响应缓冲区大小。设备资源列表在通道多时能到几百 KB。 */
    private static final int OUT_BUF_SIZE = 1024 * 1024;

    /** 请求缓冲区大小（写配置时用）。 */
    private static final int IN_BUF_SIZE = 256 * 1024;

    private final HikvisionRegisterTable table;

    private final ObjectMapper json = new ObjectMapper();

    public HikvisionIsapi(HikvisionRegisterTable table) {
        this.table = table;
    }

    /** ISAPI 调用结果。 */
    public record IsapiResult(boolean ok, int statusCode, String body, String error) {

        public static IsapiResult fail(String error) {
            return new IsapiResult(false, -1, null, error);
        }
    }

    // ==================== 基础调用 ====================

    /** 发一个 GET 请求。 */
    public IsapiResult get(String serial, String path) {
        return request(serial, "GET", path, null);
    }

    /** 发一个 PUT 请求（写配置）。 */
    public IsapiResult put(String serial, String path, String body) {
        return request(serial, "PUT", path, body);
    }

    /** 发一个 POST 请求。 */
    public IsapiResult post(String serial, String path, String body) {
        return request(serial, "POST", path, body);
    }

    /**
     * 发一个 ISAPI 请求。
     *
     * @param method GET / PUT / POST / DELETE
     * @param path   资源路径，如 {@code /ISAPI/System/deviceInfo}
     * @param body   请求体；{@code null} 表示无体
     */
    public IsapiResult request(String serial, String method, String path, String body) {
        HikvisionSdk sdk = HikvisionSdk.get();
        if (!sdk.available()) {
            return IsapiResult.fail(sdk.diagnose());
        }
        HikvisionRegisteredDevice dev = table.byDeviceId(serial);
        if (dev == null) {
            return IsapiResult.fail("设备未注册：" + serial);
        }
        HikvisionCms cms = sdk.cms();
        if (cms == null) {
            return IsapiResult.fail("CMS 未初始化");
        }
        // 设备默认返回 XML；带 format=json 才是 JSON，便于解析
        String url = path + (path.contains("?") ? "&" : "?") + "format=json";
        String requestLine = method + " " + url + " HTTP/1.1";

        Memory urlBuf = null;
        Memory inBuf = null;
        Memory outBuf = null;
        Memory statusBuf = null;
        try {
            HikvisionCms.NetEhomePtxmlParam param = new HikvisionCms.NetEhomePtxmlParam();

            urlBuf = new Memory(requestLine.length() + 1);
            urlBuf.setString(0, requestLine);
            param.pRequestUrl = urlBuf;
            param.dwRequestUrlLen = requestLine.length();

            if (body != null && !body.isEmpty()) {
                byte[] raw = body.getBytes(StandardCharsets.UTF_8);
                inBuf = new Memory(raw.length);
                inBuf.write(0, raw, 0, raw.length);
                param.pInBuffer = inBuf;
                param.dwInSize = raw.length;
            } else {
                param.pInBuffer = null;
                param.dwInSize = 0;
            }

            outBuf = new Memory(OUT_BUF_SIZE);
            outBuf.clear();
            param.pOutBuffer = outBuf;
            param.dwOutSize = OUT_BUF_SIZE;

            statusBuf = new Memory(1024);
            statusBuf.clear();
            param.pStatusBuffer = statusBuf;
            param.dwStatusSize = 1024;

            param.write();
            if (!cms.NET_ECMS_ISAPIPassThrough(dev.userId(), param)) {
                int err = cms.NET_ECMS_GetLastError();
                return IsapiResult.fail("ISAPI 调用失败（SDK 错误码 " + err + "）: "
                        + method + " " + path);
            }
            param.read();
            int status = parseStatus(statusBuf.getString(0));
            String raw = outBuf.getString(0);
            String responseBody = extractBody(raw);
            if (status >= 200 && status < 300) {
                return new IsapiResult(true, status, responseBody, null);
            }
            return new IsapiResult(false, status, responseBody,
                    "设备返回 " + status + ": " + abbreviate(responseBody, 200));
        } catch (Throwable t) {
            return IsapiResult.fail("ISAPI 调用异常: " + t.getMessage());
        } finally {
            // JNA Memory 由 GC 回收；这里只是让作用域清晰
            urlBuf = null;
            inBuf = null;
            outBuf = null;
            statusBuf = null;
        }
    }

    // ==================== 资源获取 ====================

    /**
     * 拉取通道列表。
     *
     * <p>走 {@code /ISAPI/ContentMgmt/InputProxy/channels}（模拟通道）
     * 与 {@code /ISAPI/System/Video/inputs/channels}（本地通道）两条：
     * NVR 上是前者，IPC 上是后者。只查一条会漏掉一半设备。
     */
    public List<ChannelInfo> listChannels(String serial) {
        List<ChannelInfo> out = new ArrayList<>();
        // 优先 InputProxy（NVR 的 IPC 通道）
        IsapiResult r = get(serial, "/ISAPI/ContentMgmt/InputProxy/channels");
        if (r.ok() && r.body() != null) {
            parseChannelList(r.body(), out, true);
        }
        if (out.isEmpty()) {
            // IPC 本地通道
            r = get(serial, "/ISAPI/System/Video/inputs/channels");
            if (r.ok() && r.body() != null) {
                parseChannelList(r.body(), out, false);
            }
        }
        if (out.isEmpty()) {
            log.info("海康通道列表为空: serial={}（设备可能不支持 ISAPI 或未配置通道）", serial);
        }
        return out;
    }

    /**
     * 通道信息。
     *
     * @param channelNo 通道号（1-based）
     * @param name      通道名
     * @param online    是否在线（IPC 本地通道恒 true）
     * @param ip        通道对应设备的 IP（NVR 的 IPC 通道才有）
     */
    public record ChannelInfo(int channelNo, String name, boolean online, String ip) {
    }

    private void parseChannelList(String body, List<ChannelInfo> out, boolean proxy) {
        JsonNode root = parseJson(body);
        if (root == null) {
            return;
        }
        // 结构有两种：{"InputProxyChannelList":{"InputProxyChannel":[...]}}
        // 或 {"VideoInputChannelList":{"VideoInputChannel":[...]}}
        JsonNode list = null;
        for (String wrapper : new String[]{"InputProxyChannelList", "VideoInputChannelList",
                "VideoInputChannel", "InputProxyChannel"}) {
            JsonNode node = root.path(wrapper);
            if (!node.isMissingNode()) {
                list = node;
                break;
            }
        }
        if (list == null) {
            return;
        }
        JsonNode array = list.isArray() ? list : list.path("InputProxyChannel");
        if (array.isMissingNode()) {
            array = list.path("VideoInputChannel");
        }
        Iterable<JsonNode> items = array.isArray() ? array : List.of(list);
        for (JsonNode item : items) {
            JsonNode id = item.path("id");
            int channelNo = id.isValueNode() ? id.asInt(0) : item.path("channelID").asInt(0);
            if (channelNo <= 0) {
                continue;
            }
            String name = text(item, "name", "channelName");
            boolean online = true;
            JsonNode status = item.path("online");
            if (status.isValueNode()) {
                online = status.asBoolean(true);
            }
            String ip = null;
            JsonNode src = item.path("sourceInputPortDescriptor");
            if (!src.isMissingNode()) {
                ip = text(src, "ipAddress");
            }
            out.add(new ChannelInfo(channelNo, name == null ? "通道 " + channelNo : name,
                    online, ip));
        }
    }

    // ==================== 设备信息 ====================

    /** 设备信息。 */
    public record DeviceInfo(String model, String serial, String firmware,
                             String deviceName, int channelCount) {
    }

    /** 读设备信息。 */
    public DeviceInfo deviceInfo(String serial) {
        IsapiResult r = get(serial, "/ISAPI/System/deviceInfo");
        if (!r.ok()) {
            log.debug("读海康设备信息失败: serial={} {}", serial, r.error());
            return null;
        }
        JsonNode root = parseJson(r.body());
        if (root == null) {
            return null;
        }
        JsonNode info = root.has("DeviceInfo") ? root.path("DeviceInfo") : root;
        return new DeviceInfo(
                text(info, "model"),
                text(info, "serialNumber"),
                text(info, "firmwareVersion"),
                text(info, "deviceName"),
                info.path("channelNum").asInt(0));
    }

    /** 读软件/硬件版本明细。 */
    public JsonNode versionInfo(String serial) {
        IsapiResult r = get(serial, "/ISAPI/System/deviceInfo");
        return r.ok() ? parseJson(r.body()) : null;
    }

    // ==================== 云台 ====================

    /**
     * 云台方向控制。
     *
     * <p>ISAPI 的云台是「开始/停止」两段式：{@code /ISAPI/PTZCtrl/channels/N/continuous}
     * 发一次开始，停止要再发一次 {@code speed=0}。
     * 平台侧的方向词 → ISAPI 的 {@code pan/tilt/zoom} 速度值。
     *
     * @param command up/down/left/right/upleft/upright/downleft/downright/zoomin/zoomout/stop
     * @param speed   0~255（与国标口径一致，内部折算到 ISAPI 的 -100~100）
     */
    public boolean ptz(String serial, int channelNo, String command, int speed) {
        int pan = 0;
        int tilt = 0;
        int zoom = 0;
        // ISAPI 速度范围 -100~100，0 表示停
        int s = Math.max(1, Math.min(100, Math.round(speed * 100f / 255f)));
        switch (command == null ? "" : command.toLowerCase()) {
            case "up" -> tilt = s;
            case "down" -> tilt = -s;
            case "left" -> pan = -s;
            case "right" -> pan = s;
            case "upleft" -> { tilt = s; pan = -s; }
            case "upright" -> { tilt = s; pan = s; }
            case "downleft" -> { tilt = -s; pan = -s; }
            case "downright" -> { tilt = -s; pan = s; }
            case "zoomin" -> zoom = s;
            case "zoomout" -> zoom = -s;
            case "stop" -> { pan = 0; tilt = 0; zoom = 0; }
            default -> {
                log.debug("海康云台不支持的命令: {}", command);
                return false;
            }
        }
        String body = "{\"pan\":" + pan + ",\"tilt\":" + tilt + ",\"zoom\":" + zoom + "}";
        IsapiResult r = put(serial, "/ISAPI/PTZCtrl/channels/" + channelNo + "/continuous", body);
        if (!r.ok()) {
            log.debug("海康云台控制失败: serial={} channel={} cmd={} {}",
                    serial, channelNo, command, r.error());
        }
        return r.ok();
    }

    /**
     * 预置位清单。
     *
     * <p>ISAPI 的预置位是 {@code /ISAPI/PTZCtrl/channels/N/presets}，
     * 返回的 {@code enabled} 为 false 的是空槽位，要过滤掉。
     */
    public List<PresetInfo> listPresets(String serial, int channelNo) {
        IsapiResult r = get(serial, "/ISAPI/PTZCtrl/channels/" + channelNo + "/presets");
        if (!r.ok()) {
            log.debug("读海康预置位失败: serial={} channel={} {}", serial, channelNo, r.error());
            return List.of();
        }
        JsonNode root = parseJson(r.body());
        if (root == null) {
            return List.of();
        }
        JsonNode list = root.path("PTZPresetList");
        JsonNode array = list.isMissingNode() ? root.path("PTZPreset") : list.path("PTZPreset");
        if (array.isMissingNode()) {
            return List.of();
        }
        List<PresetInfo> out = new ArrayList<>();
        Iterable<JsonNode> items = array.isArray() ? array : List.of(array);
        for (JsonNode item : items) {
            int id = item.path("id").asInt(0);
            if (id <= 0) {
                continue;
            }
            JsonNode enabled = item.path("enabled");
            if (enabled.isValueNode() && !enabled.asBoolean(true)) {
                continue;
            }
            String name = text(item, "presetName", "name");
            out.add(new PresetInfo(id, name == null || name.isBlank() ? "预置点 " + id : name));
        }
        return out;
    }

    /** 一个预置位。 */
    public record PresetInfo(int index, String name) {
    }

    /** 调用预置位。 */
    public boolean gotoPreset(String serial, int channelNo, int presetId) {
        IsapiResult r = put(serial,
                "/ISAPI/PTZCtrl/channels/" + channelNo + "/presets/" + presetId + "/goto",
                "<PTZPreset><id>" + presetId + "</id></PTZPreset>");
        return r.ok();
    }

    /** 设置预置位（把当前位置存为预置位）。 */
    public boolean setPreset(String serial, int channelNo, int presetId, String name) {
        String body = "<PTZPreset><id>" + presetId + "</id><presetName>"
                + (name == null ? "" : name) + "</presetName></PTZPreset>";
        IsapiResult r = put(serial,
                "/ISAPI/PTZCtrl/channels/" + channelNo + "/presets/" + presetId, body);
        return r.ok();
    }

    /** 删除预置位。 */
    public boolean deletePreset(String serial, int channelNo, int presetId) {
        IsapiResult r = request(serial, "DELETE",
                "/ISAPI/PTZCtrl/channels/" + channelNo + "/presets/" + presetId, null);
        return r.ok();
    }

    // ==================== 抓图 ====================

    /**
     * 抓一张 JPEG。
     *
     * <p>走 {@code /ISAPI/Streaming/channels/N01/picture}，返回二进制 JPEG。
     * 注意这条路径<b>不能带 format=json</b>，否则设备返回 400。
     */
    public byte[] snapshot(String serial, int channelNo) {
        HikvisionSdk sdk = HikvisionSdk.get();
        if (!sdk.available()) {
            return null;
        }
        HikvisionRegisteredDevice dev = table.byDeviceId(serial);
        if (dev == null) {
            return null;
        }
        HikvisionCms cms = sdk.cms();
        if (cms == null) {
            return null;
        }
        String requestLine = "GET /ISAPI/Streaming/channels/" + channelNo
                + "01/picture HTTP/1.1";
        Memory urlBuf = null;
        Memory outBuf = null;
        Memory statusBuf = null;
        try {
            HikvisionCms.NetEhomePtxmlParam param = new HikvisionCms.NetEhomePtxmlParam();
            urlBuf = new Memory(requestLine.length() + 1);
            urlBuf.setString(0, requestLine);
            param.pRequestUrl = urlBuf;
            param.dwRequestUrlLen = requestLine.length();
            param.pInBuffer = null;
            param.dwInSize = 0;
            outBuf = new Memory(OUT_BUF_SIZE);
            outBuf.clear();
            param.pOutBuffer = outBuf;
            param.dwOutSize = OUT_BUF_SIZE;
            statusBuf = new Memory(1024);
            statusBuf.clear();
            param.pStatusBuffer = statusBuf;
            param.dwStatusSize = 1024;
            param.write();

            if (!cms.NET_ECMS_ISAPIPassThrough(dev.userId(), param)) {
                log.debug("海康抓图失败: serial={} channel={} 错误码 {}",
                        serial, channelNo, cms.NET_ECMS_GetLastError());
                return null;
            }
            int status = parseStatus(statusBuf.getString(0));
            if (status < 200 || status >= 300) {
                log.debug("海康抓图返回 {}: serial={} channel={}", status, serial, channelNo);
                return null;
            }
            // 二进制响应：不能按字符串取，要按 HTTP 头的 Content-Length 截
            byte[] all = outBuf.getByteArray(0, OUT_BUF_SIZE);
            return extractBinaryBody(all);
        } catch (Throwable t) {
            log.warn("海康抓图异常: serial={} channel={} {}", serial, channelNo, t.getMessage());
            return null;
        } finally {
            urlBuf = null;
            outBuf = null;
            statusBuf = null;
        }
    }

    // ==================== 录像查询 ====================

    /** 一段录像。 */
    public record RecordFile(String fileName, String startTime, String endTime, long size) {
    }

    /**
     * 查录像。
     *
     * <p>ISAPI：{@code POST /ISAPI/ContentMgmt/search}，body 是 CMSearchDescription。
     * 时间格式必须带 {@code T} 与 {@code Z}（UTC），
     * 用空格分隔会被设备判非法而返回空结果 —— 与大华踩过同一个坑。
     */
    public List<RecordFile> searchRecords(String serial, int channelNo,
                                          String startTime, String endTime) {
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <CMSearchDescription>
                  <searchID>%s</searchID>
                  <trackList><trackID>%d</trackID></trackList>
                  <timeSpanList><timeSpan>
                    <startTime>%s</startTime><endTime>%s</endTime>
                  </timeSpan></timeSpanList>
                  <maxResults>100</maxResults>
                  <searchResultPostion>0</searchResultPostion>
                  <metadataList><metadataDescriptor>//recordType.meta.std-cgi.com</metadataDescriptor></metadataList>
                </CMSearchDescription>
                """.formatted(java.util.UUID.randomUUID(), channelNo,
                toIsapiTime(startTime), toIsapiTime(endTime));
        IsapiResult r = post(serial, "/ISAPI/ContentMgmt/search", body);
        if (!r.ok()) {
            log.debug("海康录像查询失败: serial={} channel={} {}", serial, channelNo, r.error());
            return List.of();
        }
        JsonNode root = parseJson(r.body());
        if (root == null) {
            return List.of();
        }
        JsonNode items = root.path("CMSearchResult").path("matchList").path("searchMatchItem");
        if (items.isMissingNode()) {
            return List.of();
        }
        List<RecordFile> out = new ArrayList<>();
        Iterable<JsonNode> array = items.isArray() ? items : List.of(items);
        for (JsonNode item : array) {
            String name = text(item.path("mediaSegmentDescriptor"), "contentType");
            String start = text(item.path("timeSpan"), "startTime");
            String end = text(item.path("timeSpan"), "endTime");
            long size = item.path("mediaSegmentDescriptor").path("size").asLong(0);
            out.add(new RecordFile(name == null ? "" : name, start, end, size));
        }
        return out;
    }

    /** 把 {@code yyyy-MM-dd HH:mm:ss} 转成 ISAPI 要的 UTC 格式。 */
    private static String toIsapiTime(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        // 已经是 ISO 格式就原样返回
        if (text.contains("T")) {
            return text;
        }
        return text.trim().replace(' ', 'T') + "Z";
    }

    // ==================== 配置 ====================

    /** 读编码配置（主/子码流分辨率、码率、帧率）。 */
    public JsonNode encodingConfig(String serial, int channelNo) {
        IsapiResult r = get(serial, "/ISAPI/Streaming/channels/" + channelNo + "01");
        return r.ok() ? parseJson(r.body()) : null;
    }

    /** 读图像参数（亮度、对比度、饱和度、色调）。 */
    public JsonNode imageConfig(String serial, int channelNo) {
        IsapiResult r = get(serial, "/ISAPI/Image/channels/" + channelNo + "/color");
        return r.ok() ? parseJson(r.body()) : null;
    }

    /** 写图像参数。 */
    public boolean setImageConfig(String serial, int channelNo, JsonNode config) {
        if (config == null) {
            return false;
        }
        IsapiResult r = put(serial, "/ISAPI/Image/channels/" + channelNo + "/color",
                config.toString());
        return r.ok();
    }

    /** 读设备名。 */
    public String deviceName(String serial) {
        DeviceInfo info = deviceInfo(serial);
        return info == null ? null : info.deviceName();
    }

    /** 改设备名。 */
    public boolean setDeviceName(String serial, String name) {
        DeviceInfo info = deviceInfo(serial);
        if (info == null) {
            return false;
        }
        String body = "{\"deviceName\":\"" + (name == null ? "" : name) + "\"}";
        IsapiResult r = put(serial, "/ISAPI/System/deviceInfo", body);
        return r.ok();
    }

    /** 读录像计划。 */
    public JsonNode recordPlan(String serial, int channelNo) {
        IsapiResult r = get(serial, "/ISAPI/ContentMgmt/record/records/" + channelNo);
        return r.ok() ? parseJson(r.body()) : null;
    }

    // ==================== 工具 ====================

    private JsonNode parseJson(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return json.readTree(body);
        } catch (Exception e) {
            log.debug("海康 ISAPI 响应不是合法 JSON: {}", abbreviate(body, 120));
            return null;
        }
    }

    private static String text(JsonNode node, String... names) {
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

    /**
     * 从 HTTP 状态行里取状态码。
     *
     * <p>格式 {@code HTTP/1.1 200 OK}；部分设备只回 {@code 200}。
     */
    private static int parseStatus(String statusLine) {
        if (statusLine == null || statusLine.isBlank()) {
            return -1;
        }
        String s = statusLine.trim();
        int idx = s.indexOf(' ');
        if (idx < 0) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        String rest = s.substring(idx + 1).trim();
        int end = 0;
        while (end < rest.length() && Character.isDigit(rest.charAt(end))) {
            end++;
        }
        try {
            return end == 0 ? -1 : Integer.parseInt(rest.substring(0, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 跳过 HTTP 头取 body（文本）。 */
    private static String extractBody(String response) {
        if (response == null) {
            return null;
        }
        int idx = response.indexOf("\r\n\r\n");
        if (idx < 0) {
            idx = response.indexOf("\n\n");
            if (idx < 0) {
                return response.trim();
            }
            return response.substring(idx + 2).trim();
        }
        return response.substring(idx + 4).trim();
    }

    /**
     * 跳过 HTTP 头取 body（二进制）。
     *
     * <p>必须按字节找分隔符，不能先转字符串 —— JPEG 里的任意字节都可能被
     * 误当字符处理，导致图片损坏。
     */
    private static byte[] extractBinaryBody(byte[] response) {
        if (response == null || response.length == 0) {
            return null;
        }
        int headerEnd = -1;
        for (int i = 0; i < response.length - 3; i++) {
            if (response[i] == '\r' && response[i + 1] == '\n'
                    && response[i + 2] == '\r' && response[i + 3] == '\n') {
                headerEnd = i + 4;
                break;
            }
        }
        if (headerEnd < 0) {
            return null;
        }
        int len = response.length - headerEnd;
        // 去掉尾部补零（缓冲区未用满）
        while (len > 0 && response[headerEnd + len - 1] == 0) {
            len--;
        }
        if (len <= 0) {
            return null;
        }
        byte[] out = new byte[len];
        System.arraycopy(response, headerEnd, out, 0, len);
        return out;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
