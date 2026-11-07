package io.github.rurulalanana.hikvision;

import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.deviceaccess.DevicePresetList;
import io.github.rurulalanana.deviceaccess.DeviceRecordSearch;
import io.github.rurulalanana.deviceaccess.DeviceSession;
import io.github.rurulalanana.deviceaccess.DeviceSnapshot;
import io.github.rurulalanana.hikvision.internal.HikvisionSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 海康的扩展能力：抓图 / 录像检索 / 预置位清单 / 会话释放。
 *
 * <p>一个类实现多个接口：{@code DeviceExtensionRegistry} 会按接口分别登记，
 * 拆成多个类只会重复「取设备句柄」这段代码。
 *
 * <h3>不做局域网发现</h3>
 * ISUP 设备主动注册到平台，没有「平台去广播找设备」这一步，
 * 因此不实现 {@code DeviceDiscovery}。这是与大华的本质差异：
 * 大华可以平台主动连设备，所以能搜；海康只能等设备来注册。
 */
public class HikvisionDeviceExtensions implements DeviceSnapshot, DeviceRecordSearch,
        DevicePresetList, DeviceSession {

    private static final Logger log = LoggerFactory.getLogger(HikvisionDeviceExtensions.class);

    private final HikvisionRegisterTable table;

    private final HikvisionIsapi isapi;

    public HikvisionDeviceExtensions(HikvisionRegisterTable table, HikvisionIsapi isapi) {
        this.table = table;
        this.isapi = isapi;
    }

    @Override
    public DeviceKind kind() {
        return DeviceKind.HIKVISION;
    }

    // ==================== 抓图 ====================

    @Override
    public boolean available(String deviceId) {
        return HikvisionSdk.get().available() && table.byDeviceId(deviceId) != null;
    }

    /**
     * 抓一张 JPEG。
     *
     * <p>走 ISAPI 的 {@code /ISAPI/Streaming/channels/N01/picture}，
     * 由设备直接编码好图片返回，不需要平台解码视频帧 ——
     * 比「拉流截帧」省带宽也省 CPU。
     */
    @Override
    public byte[] jpeg(String deviceId, String channelId) {
        int channelNo = HikvisionDeviceAccessProvider.channelNoOf(deviceId, channelId);
        byte[] jpeg = isapi.snapshot(deviceId, channelNo);
        if (jpeg == null) {
            throw new IllegalStateException("海康抓图失败：设备未返回图片（"
                    + deviceId + " 通道 " + channelNo + "）");
        }
        return jpeg;
    }

    // ==================== 录像检索 ====================

    /**
     * 查录像。
     *
     * <p>时间格式是这里最大的坑：ISAPI 要 {@code 2026-11-05T10:00:00Z}（UTC，带 T 与 Z），
     * 传 {@code 2026-11-05 10:00:00} 会被判非法并<b>返回空结果而不是报错</b> ——
     * 看起来就像「这段时间没录像」。与大华踩过同一个坑（见排障实录）。
     */
    @Override
    public List<FoundRecord> search(String deviceId, String channelId,
                                    String startTime, String endTime) {
        int channelNo = HikvisionDeviceAccessProvider.channelNoOf(deviceId, channelId);
        List<HikvisionIsapi.RecordFile> files =
                isapi.searchRecords(deviceId, channelNo, startTime, endTime);
        List<FoundRecord> out = new ArrayList<>(files.size());
        for (HikvisionIsapi.RecordFile f : files) {
            // 契约用 int 存字节数：单段录像超过 2 GB 的情况极罕见，
            // 真遇到时按 Integer.MAX_VALUE 截断，不影响播放（只影响展示的大小）
            int bytes = (int) Math.min(f.size(), Integer.MAX_VALUE);
            out.add(new FoundRecord(f.fileName(), f.startTime(), f.endTime(), bytes));
        }
        return out;
    }

    // ==================== 预置位清单 ====================

    @Override
    public List<PresetInfo> list(String deviceId, String channelId) {
        int channelNo = HikvisionDeviceAccessProvider.channelNoOf(deviceId, channelId);
        List<HikvisionIsapi.PresetInfo> presets = isapi.listPresets(deviceId, channelNo);
        List<PresetInfo> out = new ArrayList<>(presets.size());
        for (HikvisionIsapi.PresetInfo p : presets) {
            // 海康 ISAPI 的预置位接口不返回坐标，只返回编号与名称
            out.add(new PresetInfo(p.index(), p.name(), null, null, null, 0));
        }
        return out;
    }

    // ==================== 会话释放 ====================

    /**
     * 释放设备会话。
     *
     * <p>ISUP 是设备连平台，平台「登出」只是让 SDK 主动断开这条注册连接。
     * 不释放的后果：设备侧一直显示已连接，且 SDK 内部句柄泄漏。
     *
     * <p>失败只记日志不抛：设备可能已离线，但删除档案仍应成功。
     */
    @Override
    public void release(String deviceId) {
        HikvisionRegisteredDevice dev = table.byDeviceId(deviceId);
        if (dev == null) {
            return;
        }
        try {
            HikvisionSdk sdk = HikvisionSdk.get();
            if (sdk.cms() != null && dev.userId() >= 0) {
                sdk.cms().NET_ECMS_ForceLogout(dev.userId());
                log.info("已释放海康设备会话: serial={} userId={}", dev.serial(), dev.userId());
            }
            table.offline(dev.serial());
        } catch (Exception e) {
            log.warn("释放海康设备会话失败（不影响删除档案）: serial={} {}",
                    dev.serial(), e.getMessage());
        }
    }
}
