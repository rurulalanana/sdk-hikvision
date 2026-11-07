package io.github.rurulalanana.hikvision.internal;

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.List;

/**
 * HCISUPCMS 的 JNA 绑定（信令：设备注册、预览/回放请求、远程控制）。
 *
 * <p>只声明平台真正用到的部分。官方 demo 的绑定文件混入了 Windows 专有类型
 * （{@code com.sun.jna.examples.win32.*}），那些在 Linux 上根本加载不了，
 * 所以这里全部剔除 —— 平台侧不做本地窗口渲染，用不到 GDI。
 *
 * <p>结构体字段顺序必须与 SDK 头文件 {@code HCISUPCMS.h} 严格一致：
 * JNA 按声明顺序算偏移，错一个字段后面全错，而且不会报错，只会读到垃圾值。
 */
public interface HikvisionCms extends Library {

    // ==================== 常量 ====================

    /** 设备 ID 最大长度。 */
    int MAX_DEVICE_ID_LEN = 256;
    /** 数字序列号长度（设备标签上那个短 SN）。 */
    int NET_EHOME_SERIAL_LEN = 12;
    /** 完整序列号长度（部分新型号才有）。 */
    int MAX_FULL_SERIAL_NUM_LEN = 64;
    /** 设备名最大长度。 */
    int MAX_DEVNAME_LEN_EX = 64;
    /** 用户名 / 密码长度。 */
    int NAME_LEN = 32;
    int PASSWD_LEN = 16;
    /** EHome5.0 会话密钥长度。 */
    int MAX_MASTER_KEY_LEN = 16;
    /** SDK 路径缓冲区长度。 */
    int NET_SDK_MAX_FILE_PATH = 256;

    // ==================== 注册回调类型 ====================

    /** 设备上线。此时可从出参拿到设备地址、型号、序列号。 */
    int ENUM_DEV_ON = 0;
    /** 设备下线。 */
    int ENUM_DEV_OFF = 1;
    /** 设备地址变化。 */
    int ENUM_DEV_ADDRESS_CHANGED = 2;
    /** EHome5.0 设备认证：平台要在此写入校验值。 */
    int ENUM_DEV_AUTH = 3;
    /** EHome5.0 会话密钥下发。 */
    int ENUM_DEV_SESSIONKEY = 4;
    /** 重定向请求。 */
    int ENUM_DEV_DAS_REQ = 5;
    /** 会话密钥请求。 */
    int ENUM_DEV_SESSIONKEY_REQ = 6;
    /** 设备重注册。 */
    int ENUM_DEV_DAS_REREGISTER = 7;
    /** 注册心跳。 */
    int ENUM_DEV_DAS_PINGREO = 8;
    /** 校验密码失败。 */
    int ENUM_DEV_DAS_EHOMEKEY_ERROR = 9;
    /** SessionKey 交互异常。 */
    int ENUM_DEV_SESSIONKEY_ERROR = 10;
    /** 设备休眠（休眠时无法预览/回放/对讲/配置）。 */
    int ENUM_DEV_SLEEP = 11;

    // ==================== 结构体 ====================

    /** IP + 端口。 */
    class NetEhomeIpAddress extends Structure {
        public byte[] szIP = new byte[128];
        public short wPort;
        public byte[] byRes = new byte[2];

        public NetEhomeIpAddress() {
            super();
        }

        public NetEhomeIpAddress(Pointer p) {
            super(p);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("szIP", "wPort", "byRes");
        }
    }

    /**
     * 设备注册信息（V1，嵌在 V12 里）。
     *
     * <p>{@code byDeviceID} 是平台的逻辑 ID（通常等于序列号），
     * {@code sDeviceSerial} 是设备标签上的短 SN —— 两者不一定相同，
     * 平台侧优先用 byDeviceID，因为它跨重启稳定。
     */
    class NetEhomeDevRegInfo extends Structure {
        public int dwSize;
        public int dwNetUnitType;
        public byte[] byDeviceID = new byte[MAX_DEVICE_ID_LEN];
        public byte[] byFirmwareVersion = new byte[24];
        public NetEhomeIpAddress struDevAdd = new NetEhomeIpAddress();
        public int dwDevType;
        public int dwManufacture;
        /** 设备密码：官方是 32 字节。 */
        public byte[] byPassWord = new byte[32];
        public byte[] sDeviceSerial = new byte[NET_EHOME_SERIAL_LEN];
        public byte byReliableTransmission;
        public byte byWebSocketTransmission;
        public byte bySupportRedirect;
        public byte[] byDevProtocolVersion = new byte[6];
        /** EHome5.0 SessionKey：官方是 16 字节。 */
        public byte[] bySessionKey = new byte[16];
        public byte byMarketType;
        public byte[] byRes = new byte[26];

        public NetEhomeDevRegInfo() {
            super();
        }

        public NetEhomeDevRegInfo(Pointer p) {
            super(p);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("dwSize", "dwNetUnitType", "byDeviceID", "byFirmwareVersion",
                    "struDevAdd", "dwDevType", "dwManufacture", "byPassWord",
                    "sDeviceSerial", "byReliableTransmission", "byWebSocketTransmission",
                    "bySupportRedirect", "byDevProtocolVersion", "bySessionKey",
                    "byMarketType", "byRes");
        }
    }

    /** 注册信息 V12：在 V1 之上补了设备名与完整序列号。 */
    class NetEhomeDevRegInfoV12 extends Structure {
        public NetEhomeDevRegInfo struRegInfo = new NetEhomeDevRegInfo();
        public NetEhomeIpAddress struRegAddr = new NetEhomeIpAddress();
        public byte[] sDevName = new byte[MAX_DEVNAME_LEN_EX];
        public byte[] byDeviceFullSerial = new byte[MAX_FULL_SERIAL_NUM_LEN];
        public byte[] byRes = new byte[128];

        public NetEhomeDevRegInfoV12() {
            super();
        }

        public NetEhomeDevRegInfoV12(Pointer p) {
            super(p);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("struRegInfo", "struRegAddr", "sDevName",
                    "byDeviceFullSerial", "byRes");
        }
    }

    /** 黑名单服务器配置。 */
    class NetEhomeBlacklistServer extends Structure {
        public NetEhomeIpAddress struAdd = new NetEhomeIpAddress();
        public byte[] byServerName = new byte[32];
        public byte[] byUserName = new byte[32];
        public byte[] byPassWord = new byte[32];
        public byte[] byRes = new byte[64];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("struAdd", "byServerName", "byUserName", "byPassWord", "byRes");
        }
    }

    /**
     * 注册应答：平台告诉设备「报警推给谁、图片存哪、NTP 用谁」。
     *
     * <p>必须在 {@code ENUM_DEV_ON} 回调里填好写回，否则设备不知道往哪推报警 ——
     * 表现为「设备注册成功但一条报警都收不到」。
     */
    class NetEhomeServerInfoV50 extends Structure {
        public int dwSize;
        public int dwKeepAliveSec;
        public int dwTimeOutCount;
        public NetEhomeIpAddress struTCPAlarmSever = new NetEhomeIpAddress();
        public NetEhomeIpAddress struUDPAlarmSever = new NetEhomeIpAddress();
        public int dwAlarmServerType;
        public NetEhomeIpAddress struNTPSever = new NetEhomeIpAddress();
        public int dwNTPInterval;
        public NetEhomeIpAddress struPictureSever = new NetEhomeIpAddress();
        public int dwPicServerType;
        public NetEhomeBlacklistServer struBlackListServer = new NetEhomeBlacklistServer();
        public NetEhomeIpAddress struRedirectSever = new NetEhomeIpAddress();
        public byte[] byClouldAccessKey = new byte[64];
        public byte[] byClouldSecretKey = new byte[64];
        public byte byClouldHttps;
        public byte[] byRes1 = new byte[3];
        public int dwAlarmKeepAliveSec;
        public int dwAlarmTimeOutCount;
        public int dwClouldPoolId;
        public byte[] byRes = new byte[368];

        public NetEhomeServerInfoV50() {
            super();
        }

        public NetEhomeServerInfoV50(Pointer p) {
            super(p);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("dwSize", "dwKeepAliveSec", "dwTimeOutCount",
                    "struTCPAlarmSever", "struUDPAlarmSever", "dwAlarmServerType",
                    "struNTPSever", "dwNTPInterval", "struPictureSever", "dwPicServerType",
                    "struBlackListServer", "struRedirectSever", "byClouldAccessKey",
                    "byClouldSecretKey", "byClouldHttps", "byRes1",
                    "dwAlarmKeepAliveSec", "dwAlarmTimeOutCount", "dwClouldPoolId", "byRes");
        }
    }

    /** CMS 监听参数。 */
    /**
     * 注册监听参数（{@code NET_ECMS_StartListen} 的入参）。
     *
     * <p>字段布局必须严格照官方 Java demo 的
     * {@code NET_EHOME_CMS_LISTEN_PARAM}：
     * <pre>
     *   struAddress, fnCB, pUserData,
     *   dwKeepAliveSec(int), dwTimeOutCount(int), byRes[24]
     * </pre>
     * 早期版本把后三个写成 {@code byLinkMode(byte) + byRes(byte) + wImmediate(short)
     * + byRes[124]}，字段错位 → SDK 读到垃圾参数 → 真去建 SSL 连接 → SIGSEGV。
     * 结构体错位是 native 开发最典型的崩溃原因，且编译器不会报任何错。
     */
    class NetEhomeCmsListenParam extends Structure {
        public NetEhomeIpAddress struAddress = new NetEhomeIpAddress();
        public DeviceRegisterCb fnCB;
        public Pointer pUserData;
        /** 心跳间隔（秒），0 = 默认 30 秒。 */
        public int dwKeepAliveSec;
        /** 心跳超时次数，0 = 默认 3 次。 */
        public int dwTimeOutCount;
        public byte[] byRes = new byte[24];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("struAddress", "fnCB", "pUserData",
                    "dwKeepAliveSec", "dwTimeOutCount", "byRes");
        }
    }

    /** 会话密钥（EHome5.0 加密链路用）。 */
    class NetEhomeDevSessionKey extends Structure {
        public byte[] sDeviceID = new byte[MAX_DEVICE_ID_LEN];
        public byte[] sSessionKey = new byte[MAX_MASTER_KEY_LEN];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("sDeviceID", "sSessionKey");
        }
    }

    // ==================== 预览 / 回放 ====================

    /**
     * 预览请求入参。
     *
     * <p>字段顺序必须照官方 {@code NET_EHOME_PREVIEWINFO_IN}：
     * {@code iChannel, dwStreamType, dwLinkMode, struStreamSever}。
     * 把 {@code dwLinkMode} 与 {@code dwStreamType} 写反会导致
     * 「要主码流却给 UDP」之类的错乱，且不报错。
     */
    class NetEhomePreviewInfoIn extends Structure {
        /** 通道号，从 1 开始。 */
        public int iChannel;
        /** 0-主码流，1-子码流，2-第三码流。 */
        public int dwStreamType;
        /** 0-TCP，1-UDP，2-HRUDP。 */
        public int dwLinkMode;
        /** 平台 SMS 的地址（设备会往这里推流）。 */
        public NetEhomeIpAddress struStreamSever = new NetEhomeIpAddress();

        @Override
        protected List<String> getFieldOrder() {
            return List.of("iChannel", "dwStreamType", "dwLinkMode", "struStreamSever");
        }
    }

    /** 预览请求出参。 */
    class NetEhomePreviewInfoOut extends Structure {
        public int lSessionID;
        public byte[] byRes = new byte[128];

        public NetEhomePreviewInfoOut() {
            super();
        }

        public NetEhomePreviewInfoOut(Pointer p) {
            super(p);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("lSessionID", "byRes");
        }
    }

    /** 通知设备「可以开始推流了」。 */
    class NetEhomePushStreamIn extends Structure {
        public int dwSize;
        public int lSessionID;
        public byte[] byRes = new byte[128];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("dwSize", "lSessionID", "byRes");
        }
    }

    /** 推流确认出参。 */
    class NetEhomePushStreamOut extends Structure {
        public int dwSize;
        public int lHandle;
        public byte[] byRes = new byte[128];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("dwSize", "lHandle", "byRes");
        }
    }

    /**
     * 回放请求入参。
     *
     * <p>字段顺序照官方 {@code NET_EHOME_PLAYBACK_INFO_IN}：
     * {@code dwSize, dwChannel, byPlayBackMode, byStreamPackage, byRes[2],
     * unionPlayBackMode, struStreamSever}。
     */
    class NetEhomePlaybackInfoIn extends Structure {
        public int dwSize;
        public int dwChannel;
        /** 0-按文件名，1-按时间。 */
        public byte byPlayBackMode;
        /** 码流封装：0-PS，1-RTP。 */
        public byte byStreamPackage;
        public byte[] byRes = new byte[2];
        /** 按时间/文件名回放的联合体（两成员互斥，按最大者占位）。 */
        public NetEhomePlaybackByTime unionPlayBackMode = new NetEhomePlaybackByTime();
        /** 平台 SMS 的地址。 */
        public NetEhomeIpAddress struStreamSever = new NetEhomeIpAddress();

        @Override
        protected List<String> getFieldOrder() {
            return List.of("dwSize", "dwChannel", "byPlayBackMode", "byStreamPackage",
                    "byRes", "unionPlayBackMode", "struStreamSever");
        }
    }

    /**
     * 回放的起止时间。
     *
     * <p>照官方 {@code NET_EHOME_PLAYBACKBYTIME}：两段时间 + 两个标志位。
     */
    class NetEhomePlaybackByTime extends Structure {
        public NetEhomeSdkTime struStartTime = new NetEhomeSdkTime();
        public NetEhomeSdkTime struStopTime = new NetEhomeSdkTime();
        /** 0-本地时间，1-UTC。 */
        public byte byLocalOrUTC;
        /** 是否支持重复片段。 */
        public byte byDuplicateSegment;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("struStartTime", "struStopTime",
                    "byLocalOrUTC", "byDuplicateSegment");
        }
    }

    /**
     * SDK 时间结构。
     *
     * <p>照官方 {@code NET_EHOME_TIME}：比早期实现多了 {@code byRes1} 与
     * {@code wMSecond}，少了这两个字段会导致整个结构体错位 2 字节。
     */
    class NetEhomeSdkTime extends Structure {
        public short wYear;
        public byte byMonth;
        public byte byDay;
        public byte byHour;
        public byte byMinute;
        public byte bySecond;
        public byte byRes1;
        public short wMSecond;
        public byte[] byRes2 = new byte[4];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("wYear", "byMonth", "byDay", "byHour", "byMinute",
                    "bySecond", "byRes1", "wMSecond", "byRes2");
        }
    }

    /** 回放请求出参（照官方：多一个 {@code lHandle}）。 */
    class NetEhomePlaybackInfoOut extends Structure {
        public int lSessionID;
        public int lHandle;
        public byte[] byRes = new byte[128];

        public NetEhomePlaybackInfoOut() {
            super();
        }

        public NetEhomePlaybackInfoOut(Pointer p) {
            super(p);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("lSessionID", "lHandle", "byRes");
        }
    }

    /** 回放推流请求入参（官方多了 {@code byKeyMD5}）。 */
    class NetEhomePushPlaybackIn extends Structure {
        public int dwSize;
        public int lSessionID;
        /** 密钥 MD5（加密链路用，普通链路全 0）。 */
        public byte[] byKeyMD5 = new byte[16];
        public byte[] byRes = new byte[112];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("dwSize", "lSessionID", "byKeyMD5", "byRes");
        }
    }

    /** 回放推流确认出参（官方多了 {@code lHandle}）。 */
    class NetEhomePushPlaybackOut extends Structure {
        public int dwSize;
        public int lHandle;
        public byte[] byRes = new byte[128];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("dwSize", "lHandle", "byRes");
        }
    }

    /** 回放暂停/恢复/倍速参数（官方多了 {@code lHandle}）。 */
    class NetEhomePlaybackPauseRestartParam extends Structure {
        public int lSessionID;
        public int lHandle;
        public byte[] byRes = new byte[128];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("lSessionID", "lHandle", "byRes");
        }
    }

    // ==================== ISAPI 透传 / 远程控制 ====================

    /** ISAPI 透传参数（XML/JSON 双向缓冲）。 */
    class NetEhomePtxmlParam extends Structure {
        public Pointer pRequestUrl;
        public int dwRequestUrlLen;
        public Pointer pInBuffer;
        public int dwInSize;
        public Pointer pOutBuffer;
        public int dwOutSize;
        public Pointer pStatusBuffer;
        public int dwStatusSize;
        public byte[] byRes = new byte[32];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("pRequestUrl", "dwRequestUrlLen", "pInBuffer", "dwInSize",
                    "pOutBuffer", "dwOutSize", "pStatusBuffer", "dwStatusSize", "byRes");
        }
    }

    /** 远程控制参数（云台、重启等）。 */
    class NetEhomeRemoteCtrlParam extends Structure {
        public int dwSize;
        public Pointer pInBuffer;
        public int dwInSize;
        public Pointer pOutBuffer;
        public int dwOutSize;
        public byte[] byRes = new byte[128];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("dwSize", "pInBuffer", "dwInSize", "pOutBuffer", "dwOutSize", "byRes");
        }
    }

    /** 按条件查录像：入参。 */
    class NetEhomeFindCond extends Structure {
        public int dwSize;
        public int dwChannel;
        public int dwSearchType;
        public NetEhomeSdkTime struStartTime = new NetEhomeSdkTime();
        public NetEhomeSdkTime struStopTime = new NetEhomeSdkTime();
        public byte[] byRes = new byte[128];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("dwSize", "dwChannel", "dwSearchType",
                    "struStartTime", "struStopTime", "byRes");
        }
    }

    /** 录像文件信息（查录像出参）。 */
    class NetEhomeRecordFile extends Structure {
        public int dwSize;
        public byte[] sFileName = new byte[256];
        public NetEhomeSdkTime struStartTime = new NetEhomeSdkTime();
        public NetEhomeSdkTime struStopTime = new NetEhomeSdkTime();
        public int dwFileSize;
        public byte byFileType;
        public byte[] byRes = new byte[127];

        public NetEhomeRecordFile() {
            super();
        }

        public NetEhomeRecordFile(Pointer p) {
            super(p);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("dwSize", "sFileName", "struStartTime", "struStopTime",
                    "dwFileSize", "byFileType", "byRes");
        }
    }

    // ==================== 回调 ====================

    /**
     * 设备注册回调。
     *
     * <p><b>回调里不能直接调 SDK 接口</b>（官方手册明确要求另开线程），
     * 否则可能崩溃。实现方只做「拷贝数据 + 丢给线程池」。
     *
     * @param lUserID   设备句柄，后续所有针对该设备的调用都要用它
     * @param dwDataType 见 {@code ENUM_DEV_*} 常量
     * @param pOutBuffer 出参（如 {@link NetEhomeDevRegInfoV12}）
     * @param dwOutLen   出参长度
     * @param pInBuffer  入参（如 {@link NetEhomeServerInfoV50}、认证校验值）
     * @param dwInLen    入参长度
     */
    interface DeviceRegisterCb extends com.sun.jna.Callback {
        boolean invoke(int lUserID, int dwDataType,
                       Pointer pOutBuffer, int dwOutLen,
                       Pointer pInBuffer, int dwInLen,
                       Pointer pUser);
    }

    // ==================== 接口方法 ====================

    boolean NET_ECMS_Init();

    boolean NET_ECMS_Fini();

    boolean NET_ECMS_SetSDKInitCfg(int enumType, Pointer lpInBuff);

    boolean NET_ECMS_SetSDKLocalCfg(int enumType, Pointer lpInBuff);

    int NET_ECMS_GetLastError();

    int NET_ECMS_GetBuildVersion();

    boolean NET_ECMS_SetLogToFile(int iLogLevel, String strLogDir, boolean bAutoDel);

    boolean NET_ECMS_SetDeviceSessionKey(Pointer pDeviceKey);

    /** 启动设备注册监听。返回监听句柄，{@code <0} 表示失败。 */
    int NET_ECMS_StartListen(NetEhomeCmsListenParam lpCmsListenPara);

    boolean NET_ECMS_StopListen(int iHandle);

    /** 请求设备开始实时预览（设备随后会连到 SMS 推流）。 */
    boolean NET_ECMS_StartGetRealStream(int lUserID,
                                        NetEhomePreviewInfoIn pPreviewInfoIn,
                                        NetEhomePreviewInfoOut pPreviewInfoOut);

    /** 通知设备可以开始推流。 */
    boolean NET_ECMS_StartPushRealStream(int lUserID,
                                         NetEhomePushStreamIn pPushInfoIn,
                                         NetEhomePushStreamOut pPushInfoOut);

    boolean NET_ECMS_StopGetRealStream(int lUserID, int lSessionID);

    /** 请求设备回放。 */
    boolean NET_ECMS_StartPlayBack(int lUserID,
                                   NetEhomePlaybackInfoIn pPlaybackInfoIn,
                                   NetEhomePlaybackInfoOut pPlaybackInfoOut);

    /** 通知设备可以开始推回放流。 */
    boolean NET_ECMS_StartPushPlayBack(int lUserID,
                                       NetEhomePushPlaybackIn struPushPlayBackIn,
                                       NetEhomePushPlaybackOut struPushPlayBackOut);

    boolean NET_ECMS_StopPlayBack(int lUserID, int lSessionID);

    /** 回放暂停 / 恢复 / 倍速。{@code enumMode}：0-暂停，1-恢复，2-倍速。 */
    boolean NET_ECMS_PlayBackOperate(int lUserID, int enumMode, Pointer pOperateParam);

    /** ISAPI 透传：设备配置、资源获取、抓图都走它。 */
    boolean NET_ECMS_ISAPIPassThrough(int lUserID, NetEhomePtxmlParam lpParam);

    /** 远程控制（云台、重启、校时等）。 */
    boolean NET_ECMS_RemoteControl(int lUserID, int dwCommand,
                                   NetEhomeRemoteCtrlParam lpCtrlParam);

    /** 开始查录像文件。返回查找句柄，{@code <0} 表示失败。 */
    int NET_ECMS_StartFindFile_V11(int lUserID, int lSearchType,
                                   Pointer pFindCond, int dwCondSize);

    /** 取下一条录像文件。返回 {@code 1} 表示取到，{@code 0} 表示取完，{@code <0} 表示失败。 */
    int NET_ECMS_FindNextFile_V11(int lHandle, Pointer pFindData, int dwDataSize);

    boolean NET_ECMS_StopFindFile(int lHandle);

    boolean NET_ECMS_ForceLogout(int lUserID);

    boolean NET_ECMS_GetDevConfig(int lUserID, int dwCommand, Pointer lpConfig, int dwConfigSize);
}
