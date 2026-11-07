package io.github.rurulalanana.hikvision.internal;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.List;

/**
 * HCISUPAlarm 的 JNA 绑定（报警接收）。
 *
 * <h3>报警有两套载荷，平台两种都要</h3>
 * <ol>
 *   <li><b>结构化报警</b>（{@link NetEhomeAlarmInfo}）：字段固定，
 *       覆盖设备状态、通道状态、磁盘、移动侦测等传统报警；</li>
 *   <li><b>ISAPI 报警</b>（{@link NetEhomeAlarmIsapiInfo}）：载荷是 XML/JSON，
 *       绊线、区域入侵、逆行、徘徊、人员聚集等智能事件全走这条 ——
 *       因为它们的字段随型号/固件变化，SDK 不定义固定结构体。</li>
 * </ol>
 * 只解结构化那套会漏掉全部智能事件，这是接入时最容易犯的错。
 */
public interface HikvisionAlarm extends Library {

    // ==================== 常量 ====================

    int MAX_DEVICE_ID_LEN = 256;
    int NET_EHOME_SERIAL_LEN = 12;
    int MAX_TIME_LEN = 32;
    int MAX_REMARK_LEN = 64;
    int MAX_URL_LEN = 512;
    int NAME_LEN = 32;

    /** 未知报警类型。 */
    int EHOME_ALARM_UNKNOWN = 0;
    /** EHome 基本报警（结构化载荷）。 */
    int EHOME_ALARM = 1;
    /** 热度图报告。 */
    int EHOME_ALARM_HEATMAP_REPORT = 2;
    /** 图片抓拍报告。 */
    int EHOME_ALARM_FACESNAP_REPORT = 3;
    /** GPS 信息。 */
    int EHOME_ALARM_GPS = 4;
    /** 报警主机 CID 告警。 */
    int EHOME_ALARM_CID_REPORT = 5;
    /** 图片 URL 上报。 */
    int EHOME_ALARM_NOTICE_PICURL = 6;
    /** 异步失败通知。 */
    int EHOME_ALARM_NOTIFY_FAIL = 7;
    /** 自定义报警。 */
    int EHOME_ALARM_SELFDEFINE = 9;
    /** 设备网络切换。 */
    int EHOME_ALARM_DEVICE_NETSWITCH_REPORT = 10;
    /** 门禁事件。 */
    int EHOME_ALARM_ACS = 11;
    /** 无线网络信息。 */
    int EHOME_ALARM_WIRELESS_INFO = 12;
    /**
     * ISAPI 报警：载荷为 XML/JSON。
     *
     * <p>绊线检测、区域入侵、移动侦测、逆行、徘徊、人员聚集、声音异常
     * 等智能事件都从这里来。**不处理这个类型等于没有智能报警。**
     */
    int EHOME_ISAPI_ALARM = 13;
    /** 信息发布私有报警（不再维护）。 */
    int EHOME_INFO_RELEASE_PRIVATE = 14;
    /** 车载客流数据。 */
    int EHOME_ALARM_MPDCDATA = 15;
    /** 二维码报警。 */
    int EHOME_ALARM_QRCODE = 20;
    /** 人脸测温报警。 */
    int EHOME_ALARM_FACETEMP = 21;

    /** 设备状态改变。 */
    int ALARM_TYPE_DEV_CHANGED_STATUS = 700;
    /** 通道状态改变。 */
    int ALARM_TYPE_CHAN_CHANGED_STATUS = 701;
    /** 磁盘状态改变。 */
    int ALARM_TYPE_HD_CHANGED_STATUS = 702;
    /** 定时上报设备状态。 */
    int ALARM_TYPE_DEV_TIMING_STATUS = 703;
    /** 定时上报通道状态。 */
    int ALARM_TYPE_CHAN_TIMING_STATUS = 704;
    /** 定时上报磁盘状态。 */
    int ALARM_TYPE_HD_TIMING_STATUS = 705;
    /** 录像异常。 */
    int ALARM_TYPE_RECORD_ABNORMAL = 706;

    // ==================== 结构体 ====================

    /** 报警监听参数。 */
    class NetEhomeAlarmListenParam extends Structure {
        public HikvisionCms.NetEhomeIpAddress struAddress = new HikvisionCms.NetEhomeIpAddress();
        public EhomeMsgCallback fnMsgCb;
        public Pointer pUserData;
        /** 0-TCP，1-UDP。 */
        public byte byProtocolType;
        /** 非 0 表示复用 CMS 端口（此时地址填回环地址）。 */
        public byte byUseCmsPort;
        /** 0-回调走 SDK 线程池（默认），1-不走。 */
        public byte byUseThreadPool;
        public byte[] byRes = new byte[29];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("struAddress", "fnMsgCb", "pUserData",
                    "byProtocolType", "byUseCmsPort", "byUseThreadPool", "byRes");
        }
    }

    /**
     * 报警消息总包。
     *
     * <p>注意载荷是<b>二选一</b>：结构化报警用 {@code pAlarmInfo}，
     * ISAPI 报警用 {@code pXmlBuf}（名字叫 Xml，实际可能是 JSON）。
     */
    class NetEhomeAlarmMsg extends Structure {
        /** 见 {@code EHOME_*} 常量。 */
        public int dwAlarmType;
        /** 结构化载荷。 */
        public Pointer pAlarmInfo;
        public int dwAlarmInfoLen;
        /** ISAPI 载荷（XML 或 JSON 文本）。 */
        public Pointer pXmlBuf;
        public int dwXmlBufLen;
        public byte[] sSerialNumber = new byte[NET_EHOME_SERIAL_LEN];
        public Pointer pHttpUrl;
        public int dwHttpUrlLen;
        public byte[] byRes = new byte[12];

        public NetEhomeAlarmMsg() {
            super();
        }

        public NetEhomeAlarmMsg(Pointer p) {
            super(p);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("dwAlarmType", "pAlarmInfo", "dwAlarmInfoLen",
                    "pXmlBuf", "dwXmlBufLen", "sSerialNumber", "pHttpUrl",
                    "dwHttpUrlLen", "byRes");
        }
    }

    /** ISAPI 报警载荷。 */
    class NetEhomeAlarmIsapiInfo extends Structure {
        public Pointer pAlarmData;
        public int dwAlarmDataLen;
        /** 0-无效，1-XML，2-JSON。 */
        public byte byDataType;
        public byte byPicturesNumber;
        public byte[] byRes = new byte[2];
        /** 图片变长部分，{@code byPicturesNumber} 个 {@link NetEhomeAlarmIsapiPicData}。 */
        public Pointer pPicPackData;
        public byte[] byRes1 = new byte[32];

        public NetEhomeAlarmIsapiInfo() {
            super();
        }

        public NetEhomeAlarmIsapiInfo(Pointer p) {
            super(p);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("pAlarmData", "dwAlarmDataLen", "byDataType",
                    "byPicturesNumber", "byRes", "pPicPackData", "byRes1");
        }
    }

    /** ISAPI 报警附带的图片。 */
    class NetEhomeAlarmIsapiPicData extends Structure {
        /** 图片序号。 */
        public byte byPicSerialNum;
        public byte byPicType;
        public byte[] byRes = new byte[2];
        public int dwPicLen;
        public Pointer pPicData;

        public NetEhomeAlarmIsapiPicData() {
            super();
        }

        public NetEhomeAlarmIsapiPicData(Pointer p) {
            super(p);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("byPicSerialNum", "byPicType", "byRes", "dwPicLen", "pPicData");
        }
    }

    /** 结构化报警内容（{@code EHOME_ALARM} 的载荷）。 */
    class NetEhomeAlarmInfo extends Structure {
        public int dwSize;
        /** 报警触发时间，格式 {@code yyyy-MM-dd HH:mm:ss}。 */
        public byte[] szAlarmTime = new byte[MAX_TIME_LEN];
        public byte[] szDeviceID = new byte[MAX_DEVICE_ID_LEN];
        public int dwAlarmType;
        /** 0-开始，1-停止。 */
        public int dwAlarmAction;
        public int dwVideoChannel;
        public int dwAlarmInChannel;
        public int dwDiskNumber;
        public byte[] byRemark = new byte[MAX_REMARK_LEN];
        public byte byRetransFlag;
        public byte byTimeDiffH;
        public byte byTimeDiffM;
        public byte byRes1;
        public byte[] szAlarmUploadTime = new byte[MAX_TIME_LEN];
        /** 状态类报警的联合体，这里只保留原始字节（平台按 dwAlarmType 解读）。 */
        public byte[] byStatusUnion = new byte[16];
        public byte[] byRes2 = new byte[16];

        public NetEhomeAlarmInfo() {
            super();
        }

        public NetEhomeAlarmInfo(Pointer p) {
            super(p);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("dwSize", "szAlarmTime", "szDeviceID", "dwAlarmType",
                    "dwAlarmAction", "dwVideoChannel", "dwAlarmInChannel", "dwDiskNumber",
                    "byRemark", "byRetransFlag", "byTimeDiffH", "byTimeDiffM", "byRes1",
                    "szAlarmUploadTime", "byStatusUnion", "byRes2");
        }
    }

    /** 通道状态改变报警的载荷。 */
    class NetEhomeChanStatusChanged extends Structure {
        public short wChanNo;
        /** bit0 启用，bit1 在线，bit2 有信号，bit3 在录像，bit4 配置改变。 */
        public byte byChanStatus;
        public byte[] byRes = new byte[9];

        public NetEhomeChanStatusChanged() {
            super();
        }

        public NetEhomeChanStatusChanged(Pointer p) {
            super(p);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("wChanNo", "byChanStatus", "byRes");
        }
    }

    /** 图片 URL 上报（抓图直传用）。 */
    class NetEhomeNoticePicUrl extends Structure {
        public int dwSize;
        public byte[] byDeviceID = new byte[MAX_DEVICE_ID_LEN];
        public short wPicType;
        public short wAlarmType;
        public int dwAlarmChan;
        public byte[] byAlarmTime = new byte[MAX_TIME_LEN];
        public int dwCaptureChan;
        public byte[] byPicTime = new byte[MAX_TIME_LEN];
        public byte[] byPicUrl = new byte[MAX_URL_LEN];
        public int dwManualSnapSeq;
        public byte byRetransFlag;
        public byte byTimeDiffH;
        public byte byTimeDiffM;
        public byte[] byRes = new byte[29];

        public NetEhomeNoticePicUrl() {
            super();
        }

        public NetEhomeNoticePicUrl(Pointer p) {
            super(p);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("dwSize", "byDeviceID", "wPicType", "wAlarmType",
                    "dwAlarmChan", "byAlarmTime", "dwCaptureChan", "byPicTime",
                    "byPicUrl", "dwManualSnapSeq", "byRetransFlag",
                    "byTimeDiffH", "byTimeDiffM", "byRes");
        }
    }

    // ==================== 回调 ====================

    /**
     * 报警回调。
     *
     * <p>运行在 SDK 原生线程（默认还套了线程池）。必须快速返回：
     * 解析大 JSON 或写库都要丢到自己的线程池，否则会拖慢后续报警。
     */
    interface EhomeMsgCallback extends Callback {
        boolean invoke(int iHandle, NetEhomeAlarmMsg pAlarmMsg, Pointer pUser);
    }

    // ==================== 接口方法 ====================

    boolean NET_EALARM_Init();

    boolean NET_EALARM_Fini();

    boolean NET_EALARM_SetSDKInitCfg(int enumType, Pointer lpInBuff);

    boolean NET_EALARM_SetSDKLocalCfg(int enumType, Pointer lpInBuffer);

    int NET_EALARM_GetLastError();

    boolean NET_EALARM_SetLogToFile(int iLogLevel, String strLogDir, boolean bAutoDel);

    /** 启动报警监听。返回监听句柄，{@code <0} 表示失败。 */
    int NET_EALARM_StartListen(NetEhomeAlarmListenParam pAlarmListenParam);

    boolean NET_EALARM_StopListen(int iListenHandle);

    /** EHome5.0 加密链路：把 CMS 收到的会话密钥同步给报警模块。 */
    boolean NET_EALARM_SetDeviceSessionKey(Pointer pDeviceKey);
}
