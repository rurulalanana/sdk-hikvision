package io.github.rurulalanana.hikvision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 通道号解析的单元测试。
 *
 * <p>平台内部用 {@code serial-N} 形式的通道编码，ISAPI 只认数字通道号。
 * 这条转换错了，云台/抓图/录像全部会打到不存在的通道上。
 */
class ChannelNoOfTest {

    private static final String SN = "DS-2CD3T46WDV3-I3-20200101AAAG12345678";

    @Test
    void 完整编码解析() {
        assertEquals(1, HikvisionDeviceAccessProvider.channelNoOf(SN, SN + "-1"));
        assertEquals(8, HikvisionDeviceAccessProvider.channelNoOf(SN, SN + "-8"));
        assertEquals(32, HikvisionDeviceAccessProvider.channelNoOf(SN, SN + "-32"));
    }

    @Test
    void 纯数字直接返回() {
        assertEquals(3, HikvisionDeviceAccessProvider.channelNoOf(SN, "3"));
        assertEquals(1, HikvisionDeviceAccessProvider.channelNoOf(SN, "1"));
    }

    @Test
    void 空编码默认主通道() {
        // ISAPI 的通道号从 1 开始，0 是非法值
        assertEquals(1, HikvisionDeviceAccessProvider.channelNoOf(SN, null));
        assertEquals(1, HikvisionDeviceAccessProvider.channelNoOf(SN, ""));
        assertEquals(1, HikvisionDeviceAccessProvider.channelNoOf(SN, "   "));
    }

    @Test
    void 非数字尾部回退主通道() {
        assertEquals(1, HikvisionDeviceAccessProvider.channelNoOf(SN, SN + "-abc"));
        assertEquals(1, HikvisionDeviceAccessProvider.channelNoOf(SN, "abc"));
    }

    @Test
    void 零或负数回退主通道() {
        // 通道号 0 和负数在 ISAPI 里都不合法
        assertEquals(1, HikvisionDeviceAccessProvider.channelNoOf(SN, SN + "-0"));
        assertEquals(1, HikvisionDeviceAccessProvider.channelNoOf(SN, "-5"));
    }

    @Test
    void 设备号为null时不剥离前缀() {
        assertEquals(5, HikvisionDeviceAccessProvider.channelNoOf(null, "5"));
        assertEquals(1, HikvisionDeviceAccessProvider.channelNoOf(null, SN + "-1"));
    }

    @Test
    void 前后空白被容忍() {
        assertEquals(2, HikvisionDeviceAccessProvider.channelNoOf(SN, "  " + SN + "-2  "));
        assertEquals(4, HikvisionDeviceAccessProvider.channelNoOf(SN, " 4 "));
    }
}
