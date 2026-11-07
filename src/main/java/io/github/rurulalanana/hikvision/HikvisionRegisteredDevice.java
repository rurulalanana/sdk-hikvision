package io.github.rurulalanana.hikvision;

/**
 * 海康注册表里的一条记录。
 *
 * <p>比通用契约 {@code DeviceRegisterTable.RegisteredDevice} 多两个字段：
 * <ul>
 *   <li>{@code userId} —— SDK 分配的设备句柄，预览/云台/配置都要用它，
 *       且<b>随每次注册变化</b>，必须跟着记录走；</li>
 *   <li>{@code deviceId} —— 设备 ID，部分型号与短序列号不同。</li>
 * </ul>
 * 通用契约不能为某一家厂商加字段，所以这里单开一个 record。
 *
 * @param serial       设备序列号（注册上报，作为平台设备编号）
 * @param deviceId     设备 ID（为空时等于 serial）
 * @param ip           设备地址
 * @param port         设备端口
 * @param userId       SDK 分配的句柄，预览/云台/配置都要用
 * @param channelCount 通道数（注册时不带，由资源获取补齐；0 表示未知）
 * @param model        型号（注册时不带，由资源获取补齐）
 * @param firmware     固件版本
 * @param registeredAt 首次注册时间（毫秒）
 * @param lastSeenAt   最近一次注册/心跳时间（毫秒）
 */
public record HikvisionRegisteredDevice(
        String serial,
        String deviceId,
        String ip,
        int port,
        int userId,
        int channelCount,
        String model,
        String firmware,
        long registeredAt,
        long lastSeenAt) {
}
