package io.github.rurulalanana.hikvision.internal;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.List;

/**
 * 海康 ISUP 图片服务器（SS）的 JNA 绑定。
 *
 * <h3>它解决什么</h3>
 * 平台要拿设备的抓图，常规做法是「平台发 ISAPI 请求 → 设备回 JPEG」。
 * 这条路径有两个代价：每次都要反向连设备（NAT 下还要能连通），
 * 而且设备要把图片编码后完整传一遍。
 *
 * <p>SS 是反过来的：<b>设备主动把图片推到平台</b>。
 * 平台只需开一个 HTTP 监听，设备连上来上传，平台落盘即可。
 * 设备侧本来就要为「报警联动抓图」上传图片，平台顺势收下来，等于零额外流量。
 *
 * <h3>上传握手</h3>
 * <ol>
 *   <li>设备连上来，问「我要传 N 张图，该传到哪个 URL」；</li>
 *   <li>平台在 {@code fnSSMsgCb} 里收到 {@code SS_MSG_TOMCAT}，
 *       填好 N 个 URL 返回（每个 URL 定长 {@value #MAX_URL_LEN} 字节）；</li>
 *   <li>设备按 URL 上传，SDK 收完后回调 {@code fnSStorageCb}，给出文件名与内容。</li>
 * </ol>
 *
 * <h3>回调里不能调 SDK</h3>
 * 官方注释明确警告：存储/消息回调中严禁调用其它 HCISUPSDK 接口，否则会崩溃或堵死。
 * 所以回调里只拷数据、丢线程池，不要在这里调 {@code NET_ESS_*}。
 */
public interface HikvisionSs extends Library {

    /** 单个 URL 的定长缓冲。 */
    int MAX_URL_LEN = 4096;

    int MAX_KMS_USER_LEN = 512;

    int MAX_KMS_PWD_LEN = 512;

    int MAX_AK_SK_LEN = 64;

    int MAX_PATH = 260;

    // ==================== 消息类型 ====================

    /** 设备请求上传 URL（我们只用这一种）。 */
    int SS_MSG_TOMCAT = 1;

    int SS_MSG_KMS_USER_PWD = 2;

    int SS_MSG_CLOUD_AK = 3;

    int SS_MSG_ADDR = 4;

    // ==================== 初始化配置类型 ====================

    /** 组件目录（仅 Linux 需要，指向 HCAapSDKCom）。 */
    int SS_INIT_CFG_SDK_PATH = 1;

    int SS_INIT_CFG_CLOUD_TIME_DIFF = 2;

    /** 内外网映射时的公网地址。 */
    int SS_INIT_CFG_PUBLIC_IP_PORT = 3;

    int SS_INIT_CFG_LIBEAY_PATH = 4;

    int SS_INIT_CFG_SSLEAY_PATH = 5;

    int SS_INIT_CFG_SQLITE3_PATH = 6;

    /** 组件路径结构。 */
    class NetEhomeSsLocalSdkPath extends Structure {
        public byte[] sPath = new byte[MAX_PATH];
        public byte[] byRes = new byte[128];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("sPath", "byRes");
        }
    }

    /** 设备请求上传时给的消息体。 */
    class NetEhomeSsTomcatMsg extends Structure {
        /** 设备请求的 URI。 */
        public byte[] szDevUri = new byte[MAX_URL_LEN];

        /** 要上传几张图。 */
        public int dwPicNum;

        /** 指向 {@code dwPicNum × MAX_URL_LEN} 字节的缓冲，平台往里填 URL。 */
        public Pointer pPicURLs;

        public byte[] byRes = new byte[64];

        public NetEhomeSsTomcatMsg() {
            super();
        }

        public NetEhomeSsTomcatMsg(Pointer p) {
            super(p);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("szDevUri", "dwPicNum", "pPicURLs", "byRes");
        }
    }

    /** 监听参数。 */
    class NetEhomeSsListenParam extends Structure {
        public HikvisionCms.NetEhomeIpAddress struAddress = new HikvisionCms.NetEhomeIpAddress();

        public byte[] szKMSUserName = new byte[MAX_KMS_USER_LEN];

        public byte[] szKMSPassword = new byte[MAX_KMS_PWD_LEN];

        /** 图片落盘回调：真正的文件内容从这里来。 */
        public StorageCallback fnSStorageCb;

        /** 消息回调：设备请求上传 URL 走这里。 */
        public MsgCallback fnSSMsgCb;

        public byte[] szAccessKey = new byte[MAX_AK_SK_LEN];

        public byte[] szSecretKey = new byte[MAX_AK_SK_LEN];

        public Pointer pUserData;

        public byte byHttps;

        public byte[] byRes1 = new byte[3];

        /** 读写回调（不实现，用扩展版）。 */
        public Pointer fnSSRWCb;

        /** 读写回调扩展版：四种存储协议共用，我们不用。 */
        public Pointer fnSSRWCbEx;

        /** 安全模式：0-开启（默认），1-关闭。 */
        public byte bySecurityMode;

        public byte[] byRes = new byte[51];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("struAddress", "szKMSUserName", "szKMSPassword",
                    "fnSStorageCb", "fnSSMsgCb", "szAccessKey", "szSecretKey",
                    "pUserData", "byHttps", "byRes1", "fnSSRWCb", "fnSSRWCbEx",
                    "bySecurityMode", "byRes");
        }
    }

    // ==================== 回调 ====================

    /**
     * 消息回调：设备请求上传地址。
     *
     * <p>{@code pOutBuffer} 是可写的出参缓冲，{@code enumType=}{@value #SS_MSG_TOMCAT} 时
     * 它是 {@link NetEhomeSsTomcatMsg}。
     */
    interface MsgCallback extends Callback {
        boolean invoke(int iHandle, int enumType, Pointer pOutBuffer, int dwOutLen,
                       Pointer pInBuffer, int dwInLen, Pointer pUser);
    }

    /**
     * 存储回调：图片内容到达。
     *
     * @param pFileName 文件名
     * @param pFileBuf  文件内容
     * @param dwFileLen 文件长度
     * @param pFilePath 输出参数：平台可以把落盘路径写回去
     */
    interface StorageCallback extends Callback {
        boolean invoke(int iHandle, Pointer pFileName, Pointer pFileBuf, int dwFileLen,
                       Pointer pFilePath, Pointer pUser);
    }

    // ==================== 方法 ====================

    boolean NET_ESS_Init();

    boolean NET_ESS_Fini();

    int NET_ESS_GetLastError();

    int NET_ESS_GetBuildVersion();

    boolean NET_ESS_SetLogToFile(int iLogLevel, String strLogDir, boolean bAutoDel);

    boolean NET_ESS_SetSDKInitCfg(int enumType, Pointer lpInBuff);

    int NET_ESS_StartListen(NetEhomeSsListenParam pSSListenParam);

    boolean NET_ESS_StopListen(int lListenHandle);
}
