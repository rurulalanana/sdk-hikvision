package io.github.rurulalanana.hikvision;

import io.github.rurulalanana.deviceaccess.ConnectMode;
import io.github.rurulalanana.deviceaccess.DeviceAccessProvider;
import io.github.rurulalanana.deviceaccess.DeviceContext;
import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.hikvision.internal.HikvisionSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 海康设备接入实现（{@link DeviceAccessProvider}）。
 *
 * <h3>与国标/大华的根本差别：平台不主动连设备</h3>
 * ISUP 是<b>设备连平台</b>：设备按配置的地址主动注册到 CMS 端口，
 * 平台拿到的 {@code lUserID} 就是后续所有操作的句柄。
 * 因此：
 * <ul>
 *   <li>{@link #supportsServerMode()} 返回 {@code true}；</li>
 *   <li>{@link #probe} 查的是「注册表里在不在」，不是「能不能连上」；</li>
 *   <li>没有「登录」动作 —— 设备注册时 SDK 已经完成认证。</li>
 * </ul>
 *
 * <h3>拉流地址这条路对 ISUP 不适用</h3>
 * {@link #liveStreamUri} 对海康返回 {@code null}：设备推的是 PS 到平台 SMS 端口，
 * 不是 RTSP。要播放必须走 {@code DeviceMediaProvider}（平台收流 → ZLM）。
 * 这与大华相反（大华能走 RTSP，只是 NAT 后不通）。
 */
public class HikvisionDeviceAccessProvider implements DeviceAccessProvider {

    private static final Logger log = LoggerFactory.getLogger(HikvisionDeviceAccessProvider.class);

    private final HikvisionRegisterTable table;

    private final HikvisionIsapi isapi;

    public HikvisionDeviceAccessProvider(HikvisionRegisterTable table, HikvisionIsapi isapi) {
        this.table = table;
        this.isapi = isapi;
    }

    @Override
    public DeviceKind kind() {
        return DeviceKind.HIKVISION;
    }

    /** ISUP 是设备主动注册，平台必须监听。 */
    @Override
    public boolean supportsServerMode() {
        return true;
    }

    /**
     * 探测设备是否在线。
     *
     * <p>不发起网络请求：ISUP 没有「连接」这个动作，
     * 「注册表里有且未过期」就是在线的充要条件。
     */
    @Override
    public boolean probe(DeviceContext ctx) {
        if (ctx == null || ctx.deviceId() == null) {
            return false;
        }
        return table.byDeviceId(ctx.deviceId()) != null;
    }

    /** 拉通道目录（ISAPI）。 */
    @Override
    public List<ChannelInfo> listChannels(DeviceContext ctx) {
        if (ctx == null || ctx.deviceId() == null) {
            return List.of();
        }
        List<HikvisionIsapi.ChannelInfo> channels = isapi.listChannels(ctx.deviceId());
        List<ChannelInfo> out = new ArrayList<>(channels.size());
        HikvisionRegisteredDevice dev = table.byDeviceId(ctx.deviceId());
        String model = dev == null ? null : dev.model();
        for (HikvisionIsapi.ChannelInfo c : channels) {
            out.add(new ChannelInfo(
                    // 通道编码 = 设备序列号-通道号，与平台既有规则一致
                    ctx.deviceId() + "-" + c.channelNo(),
                    c.name(),
                    c.online(),
                    // 海康的云台类型不通过资源接口暴露，统一给 0（未知），
                    // 平台据 DeviceControl 能力表判断能不能控
                    0,
                    "Hikvision",
                    model));
        }
        return out;
    }

    /**
     * 实时流地址：ISUP 不提供。
     *
     * <p>设备推 PS 到平台 SMS 端口，没有可交给媒体服务去拉的地址。
     * 取流走 {@code HikvisionMediaProvider}。
     */
    @Override
    public String liveStreamUri(DeviceContext ctx, String channelCode, Integer streamNumber) {
        log.debug("海康不支持拉流地址（设备推流到平台），应走 DeviceMediaProvider: {}",
                ctx == null ? null : ctx.deviceId());
        return null;
    }

    /** 回放流地址：同 {@link #liveStreamUri}，走 CMS 请求 + SMS 收流。 */
    @Override
    public String playbackStreamUri(DeviceContext ctx, String channelCode,
                                    String startTime, String endTime) {
        log.debug("海康回放走 CMS 请求 + SMS 收流，不支持拉流地址: {}",
                ctx == null ? null : ctx.deviceId());
        return null;
    }

    /** 云台（ISAPI）。 */
    @Override
    public void ptz(DeviceContext ctx, String channelCode, String command, int speed) {
        if (ctx == null || ctx.deviceId() == null) {
            return;
        }
        int channelNo = channelNoOf(ctx.deviceId(), channelCode);
        if (!isapi.ptz(ctx.deviceId(), channelNo, command, speed)) {
            log.debug("海康云台命令未生效: {} {} {}", ctx.deviceId(), channelCode, command);
        }
    }

    /** 从通道编码里解出通道号（{@code serial-N} 或纯数字）。 */
    public static int channelNoOf(String deviceId, String channelCode) {
        if (channelCode == null || channelCode.isBlank()) {
            return 1;
        }
        String code = channelCode.trim();
        if (deviceId != null && code.startsWith(deviceId)) {
            code = code.substring(deviceId.length());
            if (code.startsWith("-")) {
                code = code.substring(1);
            }
        }
        // 取最后一个连字符后的部分；dash > 0 是因为开头的 '-' 是负号不是分隔符
        int dash = code.lastIndexOf('-');
        if (dash > 0 && dash < code.length() - 1) {
            code = code.substring(dash + 1);
        }
        try {
            int n = Integer.parseInt(code.trim());
            // ISAPI 通道号 1-based；0 是设备自身
            return n <= 0 ? 1 : n;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** SDK 是否可用（供平台判断要不要展示「不支持」原因）。 */
    public boolean available() {
        return HikvisionSdk.get().available();
    }

    /** 不可用原因。 */
    public String unavailableReason() {
        return HikvisionSdk.get().diagnose();
    }
}
