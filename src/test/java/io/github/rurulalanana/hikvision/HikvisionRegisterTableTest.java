package io.github.rurulalanana.hikvision;

import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.deviceaccess.DeviceRegisterTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注册表的单元测试。
 *
 * <p>重点验证「句柄随注册变化」与「过期判据」：
 * 前者决定预览/云台能不能用，后者决定设备掉线后多久被判定为离线。
 */
class HikvisionRegisterTableTest {

    @Test
    void 上线后能按序列号查到() {
        HikvisionRegisterTable table = new HikvisionRegisterTable(60_000);
        table.online("SN001", "dev-1", "192.168.1.10", 8000, 100, "v1.0");

        DeviceRegisterTable.RegisteredDevice d = table.lookup("SN001");
        assertNotNull(d);
        assertEquals("SN001", d.serial());
        assertEquals("192.168.1.10", d.ip());
        assertEquals(8000, d.port());
    }

    @Test
    void 句柄随重注册更新但注册时间保持首次() {
        HikvisionRegisterTable table = new HikvisionRegisterTable(60_000);
        table.online("SN001", "dev-1", "192.168.1.10", 8000, 100, "v1.0");
        long firstRegisteredAt = table.byDeviceId("SN001").registeredAt();

        // 设备重连：句柄变了，地址也变了
        table.online("SN001", "dev-1", "192.168.1.11", 8000, 200, "v1.0");

        HikvisionRegisteredDevice d = table.byDeviceId("SN001");
        assertEquals(200, d.userId(), "句柄必须跟着重注册更新");
        assertEquals("192.168.1.11", d.ip());
        assertEquals(firstRegisteredAt, d.registeredAt(), "首次注册时间不该跳变");
    }

    @Test
    void 按句柄反查() {
        HikvisionRegisterTable table = new HikvisionRegisterTable(60_000);
        table.online("SN001", "dev-1", "192.168.1.10", 8000, 100, null);
        table.online("SN002", "dev-2", "192.168.1.20", 8000, 200, null);

        assertEquals("SN001", table.byUserId(100).serial());
        assertEquals("SN002", table.byUserId(200).serial());
        assertNull(table.byUserId(999));
    }

    @Test
    void 下线后查不到() {
        HikvisionRegisterTable table = new HikvisionRegisterTable(60_000);
        table.online("SN001", "dev-1", "192.168.1.10", 8000, 100, null);
        table.offline("SN001");
        assertNull(table.lookup("SN001"));
        assertTrue(table.list().isEmpty());
    }

    @Test
    void 过期后自动移除() throws InterruptedException {
        // 过期阈值 1 毫秒：构造后立刻过期
        HikvisionRegisterTable table = new HikvisionRegisterTable(1);
        table.online("SN001", "dev-1", "192.168.1.10", 8000, 100, null);
        Thread.sleep(20);

        assertNull(table.lookup("SN001"), "过期设备应查不到");
        assertTrue(table.list().isEmpty(), "过期设备不该出现在列表里");
    }

    @Test
    void 刷新心跳后不过期() throws InterruptedException {
        HikvisionRegisterTable table = new HikvisionRegisterTable(200);
        table.online("SN001", "dev-1", "192.168.1.10", 8000, 100, null);
        Thread.sleep(100);
        table.touch("SN001");
        Thread.sleep(120);

        assertNotNull(table.lookup("SN001"), "刷新过心跳的设备不该被判过期");
    }

    @Test
    void 回填通道数与型号() {
        HikvisionRegisterTable table = new HikvisionRegisterTable(60_000);
        table.online("SN001", "dev-1", "192.168.1.10", 8000, 100, "v1.0");
        assertEquals(0, table.byDeviceId("SN001").channelCount(), "注册事件不带通道数");

        table.enrich("SN001", 8, "DS-2CD3T46");
        HikvisionRegisteredDevice d = table.byDeviceId("SN001");
        assertEquals(8, d.channelCount());
        assertEquals("DS-2CD3T46", d.model());
        assertEquals(100, d.userId(), "回填不该丢句柄");
    }

    @Test
    void 契约入口与具体入口等价() {
        HikvisionRegisterTable table = new HikvisionRegisterTable(60_000);
        // 通用契约的 register 只能传 extra 字符串，句柄要能从里面解析出来
        table.register("SN001", "192.168.1.10", 8000, "userId=123");

        assertEquals(123, table.byDeviceId("SN001").userId(),
                "register 必须能从句柄字符串里解析出 userId");
        assertEquals(DeviceKind.HIKVISION, table.kind());

        table.unregister("SN001");
        assertNull(table.lookup("SN001"));
    }

    @Test
    void 列表不返回过期设备() {
        HikvisionRegisterTable table = new HikvisionRegisterTable(60_000);
        table.online("SN001", "dev-1", "192.168.1.10", 8000, 100, null);
        table.online("SN002", "dev-2", "192.168.1.20", 8000, 200, null);

        List<DeviceRegisterTable.RegisteredDevice> list = table.list();
        assertEquals(2, list.size());
        // extra 里要带句柄，否则平台侧拿不到（通用契约没有 userId 字段）
        assertTrue(list.stream().allMatch(d -> d.extra().startsWith("userId=")));
    }

    @Test
    void 空序列号不登记() {
        HikvisionRegisterTable table = new HikvisionRegisterTable(60_000);
        table.online("", "dev-1", "192.168.1.10", 8000, 100, null);
        table.online(null, "dev-2", "192.168.1.20", 8000, 200, null);
        assertTrue(table.list().isEmpty(), "没有序列号的设备无法索引，不该入表");
    }
}
