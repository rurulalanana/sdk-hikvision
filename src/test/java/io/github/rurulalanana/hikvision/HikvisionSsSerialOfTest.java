package io.github.rurulalanana.hikvision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抓图直传的序列号解析测试。
 *
 * <p>设备上传的 URL 与文件名格式随固件变化，解析失败不能让图丢了 ——
 * 所以这里的约定是「尽力解析，失败返回空串」。
 */
class HikvisionSsSerialOfTest {

    @Test
    void 从URL参数解析() {
        assertEquals("SN123456",
                HikvisionSsListener.serialOf("/hikvision/picture?serial=SN123456&index=0"));
        assertEquals("SN123456",
                HikvisionSsListener.serialOf("/hikvision/picture?serial=SN123456"));
    }

    @Test
    void 从文件名解析() {
        // 海康典型文件名：型号_序列号_时间.jpg
        assertEquals("DS2CD3T46WDV3I3G0120250101",
                HikvisionSsListener.serialOf("DS-2CD3T46WDV3-I3_DS2CD3T46WDV3I3G0120250101_"
                        + "20240101120000.jpg"));
    }

    @Test
    void 带路径的文件名也能解析() {
        assertEquals("DS2CD3T46WDV3I3G0120250101",
                HikvisionSsListener.serialOf("/tmp/pic/DS-2CD3T46WDV3-I3_"
                        + "DS2CD3T46WDV3I3G0120250101_20240101120000.jpg"));
    }

    @Test
    void 解析不出来返回空串() {
        // 不能让解析失败把图丢了，也不能抛异常
        assertEquals("", HikvisionSsListener.serialOf(null));
        assertEquals("", HikvisionSsListener.serialOf(""));
        assertEquals("", HikvisionSsListener.serialOf("   "));
        assertEquals("", HikvisionSsListener.serialOf("nodash.jpg"));
        assertEquals("", HikvisionSsListener.serialOf("a_b.jpg"), "第二段太短，不算序列号");
    }

    @Test
    void URL参数优先于文件名() {
        // 两者都带时以 serial= 为准：那是平台自己填的，最可靠
        assertEquals("FROMURL",
                HikvisionSsListener.serialOf("serial=FROMURL&index=0"));
    }

    @Test
    void 长序列号被接受() {
        // 海康序列号通常 20 位以上
        String sn = "DS2CD3T46WDV3I3G0120250101A";
        assertTrue(sn.length() > 8);
        assertEquals(sn, HikvisionSsListener.serialOf("MODEL_" + sn + "_20240101.jpg"));
    }
}
