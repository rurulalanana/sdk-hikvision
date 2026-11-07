package io.github.rurulalanana.hikvision.internal;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.List;

/**
 * HCISUPStream 的 JNA 绑定（流媒体：平台充当 SMS 接收设备推来的码流）。
 *
 * <h3>取流模型与国标/大华都不同</h3>
 * <ul>
 *   <li><b>国标</b>：平台 INVITE，设备把 RTP 推到平台端口；</li>
 *   <li><b>大华</b>：平台调 SDK 拉流，SDK 回调给平台裸流；</li>
 *   <li><b>ISUP</b>：平台起 {@code NET_ESTREAM_StartListenPreview} 监听端口，
 *       设备<b>主动连上来</b>推码流，平台通过回调收包。</li>
 * </ul>
 * 也就是说本模块必须监听一个对外可达的端口，部署时要放行防火墙。
 *
 * <h3>回调线程</h3>
 * {@link PreviewDataCb#invoke} 运行在 SDK 原生线程上，必须极快返回 ——
 * 里面只做「拷字节 + 丢队列」，解码与推 ZLM 都放别的线程。
 */
public interface HikvisionStream extends Library {

    /** 码流头（PS 的 system header），只在流开始时来一次。 */
    int NET_EHOME_SYSHEAD = 1;
    /** 码流数据（PS 封装的复合流）。 */
    int NET_EHOME_STREAMDATA = 2;
    /** 码流结束。 */
    int NET_EHOME_STREAMEND = 3;

    /** 设备 ID 长度。 */
    int NET_EHOME_DEVICEID_LEN = 256;
    /** 数字序列号长度。 */
    int NET_EHOME_SERIAL_LEN = 12;

    // ==================== 结构体 ====================

    /** 预览监听参数。 */
    class NetEhomeListenPreviewCfg extends Structure {
        /** 本地监听地址；IP 为 0.0.0.0 时由 SDK 自选网卡。 */
        public HikvisionCms.NetEhomeIpAddress struIPAdress = new HikvisionCms.NetEhomeIpAddress();
        /** 设备连上来时回调。 */
        public PreviewNewLinkCb fnNewLinkCB;
        public Pointer pUser;
        /** 0-TCP，1-UDP，2-HRUDP。 */
        public byte byLinkMode;
        public byte[] byRes = new byte[127];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("struIPAdress", "fnNewLinkCB", "pUser", "byLinkMode", "byRes");
        }
    }

    /** 设备连上来时回调的出参：哪个设备、哪个通道、哪路码流。 */
    class NetEhomeNewLinkCbMsg extends Structure {
        public byte[] szDeviceID = new byte[HikvisionCms.MAX_DEVICE_ID_LEN];
        public int iSessionID;
        public int dwChannelNo;
        /** 0-主码流，1-子码流。 */
        public byte byStreamType;
        public byte[] byRes1 = new byte[3];
        public byte[] sDeviceSerial = new byte[HikvisionCms.NET_EHOME_SERIAL_LEN];
        public byte[] byRes = new byte[112];

        public NetEhomeNewLinkCbMsg() {
            super();
        }

        public NetEhomeNewLinkCbMsg(Pointer p) {
            super(p);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("szDeviceID", "iSessionID", "dwChannelNo", "byStreamType",
                    "byRes1", "sDeviceSerial", "byRes");
        }
    }

    /** 一包码流数据。 */
    class NetEhomePreviewCbMsg extends Structure {
        /** 见 {@code NET_EHOME_SYSHEAD} / {@code NET_EHOME_STREAMDATA}。 */
        public byte byDataType;
        public byte[] byRes1 = new byte[3];
        public Pointer pRecvdata;
        public int dwDataLen;
        public byte[] byRes2 = new byte[128];

        public NetEhomePreviewCbMsg() {
            super();
        }

        public NetEhomePreviewCbMsg(Pointer p) {
            super(p);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("byDataType", "byRes1", "pRecvdata", "dwDataLen", "byRes2");
        }
    }

    /** 绑定数据回调的参数。 */
    class NetEhomePreviewDataCbParam extends Structure {
        public PreviewDataCb fnPreviewDataCB;
        public Pointer pUserData;
        public byte[] byRes = new byte[128];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("fnPreviewDataCB", "pUserData", "byRes");
        }
    }

    /** 回放监听参数。 */
    class NetEhomePlaybackListenParam extends Structure {
        public HikvisionCms.NetEhomeIpAddress struIPAdress = new HikvisionCms.NetEhomeIpAddress();
        public PlaybackNewLinkCb fnNewLinkCB;
        public Pointer pUser;
        public byte byLinkMode;
        public byte[] byRes = new byte[127];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("struIPAdress", "fnNewLinkCB", "pUser", "byLinkMode", "byRes");
        }
    }

    /** 回放连上来时回调的出参。 */
    class NetEhomePlaybackNewLinkCbInfo extends Structure {
        public byte[] szDeviceID = new byte[NET_EHOME_DEVICEID_LEN];
        public int lSessionID;
        public int dwChannelNo;
        public byte[] sDeviceSerial = new byte[NET_EHOME_SERIAL_LEN];
        /** 0-PS，1-RTP。 */
        public byte byStreamFormat;
        public byte[] byRes1 = new byte[3];
        public PlaybackDataCb fnPlayBackDataCB;
        public Pointer pUserData;
        public byte[] byRes = new byte[88];

        public NetEhomePlaybackNewLinkCbInfo() {
            super();
        }

        public NetEhomePlaybackNewLinkCbInfo(Pointer p) {
            super(p);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("szDeviceID", "lSessionID", "dwChannelNo", "sDeviceSerial",
                    "byStreamFormat", "byRes1", "fnPlayBackDataCB", "pUserData", "byRes");
        }
    }

    /** 绑定回放数据回调的参数。 */
    class NetEhomePlaybackDataCbParam extends Structure {
        public PlaybackDataCb fnPlayBackDataCB;
        public Pointer pUserData;
        /** 0-PS，1-RTP。 */
        public byte byStreamFormat;
        public byte[] byRes = new byte[127];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("fnPlayBackDataCB", "pUserData", "byStreamFormat", "byRes");
        }
    }

    /** 一包回放数据。 */
    class NetEhomePlaybackDataCbInfo extends Structure {
        /** 1-头信息，2-码流数据，3-回放停止。 */
        public int dwType;
        public Pointer pData;
        public int dwDataLen;
        public byte[] byRes = new byte[128];

        public NetEhomePlaybackDataCbInfo() {
            super();
        }

        public NetEhomePlaybackDataCbInfo(Pointer p) {
            super(p);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("dwType", "pData", "dwDataLen", "byRes");
        }
    }

    // ==================== 回调 ====================

    /** 设备请求建立预览连接。必须在这里 {@code NET_ESTREAM_SetPreviewDataCB} 才能收数据。 */
    interface PreviewNewLinkCb extends Callback {
        boolean invoke(int lLinkHandle, NetEhomeNewLinkCbMsg pNewLinkCbMsg, Pointer pUserData);
    }

    /** 预览数据到达。运行在 SDK 原生线程，必须快速返回。 */
    interface PreviewDataCb extends Callback {
        void invoke(int iPreviewHandle, NetEhomePreviewCbMsg pPreviewCbMsg, Pointer pUserData);
    }

    /** 设备请求建立回放连接。 */
    interface PlaybackNewLinkCb extends Callback {
        boolean invoke(int lPlaybackLinkHandle, NetEhomePlaybackNewLinkCbInfo pNewLinkCbInfo,
                       Pointer pUserData);
    }

    /** 回放数据到达。 */
    interface PlaybackDataCb extends Callback {
        boolean invoke(int iPlaybackLinkHandle, NetEhomePlaybackDataCbInfo pDataCbInfo,
                       Pointer pUserData);
    }

    // ==================== 接口方法 ====================

    boolean NET_ESTREAM_Init();

    boolean NET_ESTREAM_Fini();

    boolean NET_ESTREAM_SetSDKInitCfg(int enumType, Pointer lpInBuff);

    boolean NET_ESTREAM_SetSDKLocalCfg(int enumType, Pointer lpInBuff);

    int NET_ESTREAM_GetLastError();

    int NET_ESTREAM_GetBuildVersion();

    boolean NET_ESTREAM_SetLogToFile(int iLogLevel, String strLogDir, boolean bAutoDel);

    /** 启动预览监听。返回监听句柄，{@code <0} 表示失败。 */
    int NET_ESTREAM_StartListenPreview(NetEhomeListenPreviewCfg pListenParam);

    boolean NET_ESTREAM_StopListenPreview(int iListenHandle);

    /** 给某个连接绑定数据回调。收到 {@code fnNewLinkCB} 后必须调它。 */
    boolean NET_ESTREAM_SetPreviewDataCB(int iHandle, NetEhomePreviewDataCbParam pStruCbParam);

    /** 停止某一路预览转发。 */
    boolean NET_ESTREAM_StopPreview(int iPreviewHandle);

    /** 启动回放监听。 */
    int NET_ESTREAM_StartListenPlayBack(NetEhomePlaybackListenParam pListenParam);

    boolean NET_ESTREAM_StopListenPlayBack(int iPlaybackListenHandle);

    boolean NET_ESTREAM_SetPlayBackDataCB(int iPlaybackLinkHandle,
                                          NetEhomePlaybackDataCbParam pDataCbParam);

    boolean NET_ESTREAM_StopPlayBack(int iPlaybackLinkHandle);
}
