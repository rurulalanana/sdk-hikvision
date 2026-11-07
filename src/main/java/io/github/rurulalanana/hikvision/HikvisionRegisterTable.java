package io.github.rurulalanana.hikvision;

import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.deviceaccess.DeviceRegisterTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 海康注册表的默认实现。
 *
 * <p>过期判据与大华一致：超过 {@code expireMs} 没心跳就视为离线。
 * ISUP 的心跳由 SDK 内部维持，平台看不到「心跳包」，只能靠
 * 「设备重注册 / 地址变更」事件刷新 {@code lastSeenAt} ——
 * 因此过期时间要给得比心跳间隔宽裕（默认 90 秒）。
 */
public class HikvisionRegisterTable implements DeviceRegisterTable {

    private final Map<String, HikvisionRegisteredDevice> bySerial = new ConcurrentHashMap<>();

    private final long expireMs;

    public HikvisionRegisterTable() {
        this(90_000L);
    }

    public HikvisionRegisterTable(long expireMs) {
        this.expireMs = expireMs;
    }

    @Override
    public DeviceKind kind() {
        return DeviceKind.HIKVISION;
    }

    /** 设备上线：登记或刷新。保留首次注册时间，避免重连后「注册时间」跳变。 */
    public void online(String serial, String deviceId, String ip, int port,
                       int userId, String firmware) {
        if (serial == null || serial.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        bySerial.compute(serial.trim(), (k, old) -> {
            long first = old == null ? now : old.registeredAt();
            int channels = old == null ? 0 : old.channelCount();
            String model = old == null ? null : old.model();
            return new HikvisionRegisteredDevice(serial.trim(),
                    deviceId == null || deviceId.isBlank() ? serial.trim() : deviceId,
                    ip, port, userId, channels, model, firmware, first, now);
        });
    }

    /** 资源获取完成后回填通道数与型号（注册事件里没有这两项）。 */
    public void enrich(String serial, int channelCount, String model) {
        if (serial == null || serial.isBlank()) {
            return;
        }
        bySerial.computeIfPresent(serial.trim(), (k, old) ->
                new HikvisionRegisteredDevice(old.serial(), old.deviceId(), old.ip(),
                        old.port(), old.userId(), channelCount, model, old.firmware(),
                        old.registeredAt(), old.lastSeenAt()));
    }

    /** 刷新心跳时间。 */
    public void touch(String serial) {
        if (serial == null || serial.isBlank()) {
            return;
        }
        bySerial.computeIfPresent(serial.trim(), (k, old) ->
                new HikvisionRegisteredDevice(old.serial(), old.deviceId(), old.ip(),
                        old.port(), old.userId(), old.channelCount(), old.model(),
                        old.firmware(), old.registeredAt(), System.currentTimeMillis()));
    }

    /** 设备下线：移除。 */
    public void offline(String serial) {
        if (serial != null && !serial.isBlank()) {
            bySerial.remove(serial.trim());
        }
    }

    /**
     * 通用契约的上线入口。
     *
     * <p>{@code extra} 里带 {@code userId=N}（由本类 {@link #toRegistered} 写出），
     * 用于跨重启/跨实例恢复句柄。正常路径走 {@link #online} 那个带句柄的重载。
     */
    @Override
    public void register(String serial, String ip, int port, String extra) {
        int userId = parseUserId(extra);
        online(serial, serial, ip, port, userId, null);
    }

    /** 通用契约的下线入口。 */
    @Override
    public void unregister(String serial) {
        offline(serial);
    }

    private static int parseUserId(String extra) {
        if (extra == null) {
            return -1;
        }
        int idx = extra.indexOf("userId=");
        if (idx < 0) {
            return -1;
        }
        String rest = extra.substring(idx + "userId=".length()).trim();
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

    @Override
    public RegisteredDevice lookup(String serial) {
        if (serial == null || serial.isBlank()) {
            return null;
        }
        HikvisionRegisteredDevice d = bySerial.get(serial.trim());
        if (d == null) {
            return null;
        }
        if (isExpired(d)) {
            bySerial.remove(serial.trim());
            return null;
        }
        return toRegistered(d);
    }

    @Override
    public List<RegisteredDevice> list() {
        pruneExpired();
        List<RegisteredDevice> out = new ArrayList<>(bySerial.size());
        for (HikvisionRegisteredDevice d : bySerial.values()) {
            out.add(toRegistered(d));
        }
        return out;
    }

    /** 按 SDK 句柄反查（收到预览/报警回调时只有句柄，没有序列号）。 */
    public HikvisionRegisteredDevice byUserId(int userId) {
        for (HikvisionRegisteredDevice d : bySerial.values()) {
            if (d.userId() == userId) {
                return d;
            }
        }
        return null;
    }

    /** 按设备 ID 查（回调里可能给的是 deviceId 而非 serial）。 */
    public HikvisionRegisteredDevice byDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        String key = deviceId.trim();
        HikvisionRegisteredDevice direct = bySerial.get(key);
        if (direct != null) {
            return direct;
        }
        for (HikvisionRegisteredDevice d : bySerial.values()) {
            if (key.equals(d.deviceId())) {
                return d;
            }
        }
        return null;
    }

    @Override
    public int pruneExpired() {
        int before = bySerial.size();
        bySerial.entrySet().removeIf(e -> isExpired(e.getValue()));
        return before - bySerial.size();
    }

    private boolean isExpired(HikvisionRegisteredDevice d) {
        return System.currentTimeMillis() - d.lastSeenAt() > expireMs;
    }

    private static RegisteredDevice toRegistered(HikvisionRegisteredDevice d) {
        // extra 里带句柄：DeviceRegisterTable 是通用契约，不能为海康加字段
        return new RegisteredDevice(d.serial(), d.ip(), d.port(),
                d.registeredAt(), d.lastSeenAt(), "userId=" + d.userId());
    }
}
