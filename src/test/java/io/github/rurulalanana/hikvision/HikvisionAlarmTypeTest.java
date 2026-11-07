package io.github.rurulalanana.hikvision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 报警类型识别的单元测试。
 *
 * <p>重点是 ISAPI 事件名的匹配：海康的事件类型是字符串，固件会加后缀、
 * 大小写也不统一，精确相等会漏掉大半。
 */
class HikvisionAlarmTypeTest {

    @Test
    void 按事件名识别智能报警() {
        assertEquals(HikvisionAlarmType.LINE_CROSS,
                HikvisionAlarmType.of("linedetection"));
        assertEquals(HikvisionAlarmType.FIELD_DETECTION,
                HikvisionAlarmType.of("fielddetection"));
        assertEquals(HikvisionAlarmType.LOITERING,
                HikvisionAlarmType.of("loitering"));
        assertEquals(HikvisionAlarmType.HUMAN_GATHERING,
                HikvisionAlarmType.of("gathering"));
    }

    @Test
    void 事件名大小写不敏感() {
        assertEquals(HikvisionAlarmType.LINE_CROSS,
                HikvisionAlarmType.of("LineDetection"));
        assertEquals(HikvisionAlarmType.MOTION,
                HikvisionAlarmType.of("MOTIONDETECT"));
    }

    @Test
    void 固件加后缀也能识别() {
        // 实测部分固件会在事件名后加版本后缀
        assertEquals(HikvisionAlarmType.REGION_ENTRANCE,
                HikvisionAlarmType.of("regionEntrance_v2"));
        assertEquals(HikvisionAlarmType.LINE_CROSS,
                HikvisionAlarmType.of("linedetection_1"));
    }

    @Test
    void 未知事件名返回UNKNOWN() {
        assertEquals(HikvisionAlarmType.UNKNOWN, HikvisionAlarmType.of("someNewEvent"));
        assertEquals(HikvisionAlarmType.UNKNOWN, HikvisionAlarmType.of(null));
        assertEquals(HikvisionAlarmType.UNKNOWN, HikvisionAlarmType.of(""));
    }

    @Test
    void SDK结构化类型映射() {
        assertEquals(HikvisionAlarmType.DEVICE_STATUS,
                HikvisionAlarmType.ofSdkType(700));
        assertEquals(HikvisionAlarmType.CHANNEL_STATUS,
                HikvisionAlarmType.ofSdkType(701));
        assertEquals(HikvisionAlarmType.DISK_STATUS,
                HikvisionAlarmType.ofSdkType(702));
        assertEquals(HikvisionAlarmType.RECORD_ABNORMAL,
                HikvisionAlarmType.ofSdkType(706));
        // 1 = EHOME_ALARM，具体是什么要看结构化载荷，不是这里定的
        assertEquals(HikvisionAlarmType.UNKNOWN,
                HikvisionAlarmType.ofSdkType(1));
    }

    @Test
    void 优先级覆盖四个档位() {
        // 视频丢失/设备状态是紧急
        assertEquals(4, HikvisionAlarmType.VIDEO_LOSS.priority());
        // 智能事件是高
        assertEquals(3, HikvisionAlarmType.LINE_CROSS.priority());
        // 移动侦测是中
        assertEquals(2, HikvisionAlarmType.MOTION.priority());
        // 人脸抓拍是低（属于正常业务事件）
        assertEquals(1, HikvisionAlarmType.FACE_SNAP.priority());
    }

    @Test
    void 每个类型都有中文名与关键词() {
        for (HikvisionAlarmType t : HikvisionAlarmType.values()) {
            assertNotEquals("", t.label(), t.name() + " 缺中文名");
            if (t != HikvisionAlarmType.UNKNOWN) {
                assertNotEquals("", t.keyword(), t.name() + " 缺关键词");
            }
        }
    }
}
