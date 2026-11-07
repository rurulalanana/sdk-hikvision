package io.github.rurulalanana.hikvision;

/**
 * 海康报警事件分类。
 *
 * <h3>为什么不能只靠 SDK 的 dwAlarmType</h3>
 * SDK 的 {@code dwAlarmType} 只到「大类」这一层：
 * <ul>
 *   <li>{@code EHOME_ALARM=1} —— 传统结构化报警（移动侦测、视频丢失等）；</li>
 *   <li>{@code EHOME_ISAPI_ALARM=13} —— 智能事件，载荷是 XML/JSON，<b>具体是什么事件在载荷里</b>。</li>
 * </ul>
 * 绊线、区域入侵、逆行、徘徊、人员聚集、声音异常全部落在 13 里，
 * 必须解载荷里的 {@code eventType} 才能区分。只认 dwAlarmType 会把这些
 * 全显示成「ISAPI 报警」，运维根本看不出发生了什么。
 *
 * <h3>eventType 取值从哪来</h3>
 * 海康 ISAPI 的事件类型是<b>字符串</b>（如 {@code linedetection}、
 * {@code fielddetection}），不是数字。这些名字在设备的能力集
 * （{@code /ISAPI/Event/notification/alertStream} 的 capability）里定义，
 * 不同固件略有差异，因此这里按「包含匹配」而非精确相等 ——
 * 固件加了后缀（{@code regionEntrance_v2}）也能命中。
 */
public enum HikvisionAlarmType {

    /** 移动侦测。 */
    MOTION("motiondetect", "移动侦测", 2),
    /** 视频遮挡。 */
    SHELTER("shelteralarm", "视频遮挡", 2),
    /** 视频丢失。 */
    VIDEO_LOSS("videoloss", "视频丢失", 4),
    /** 绊线检测。 */
    LINE_CROSS("linedetection", "绊线检测", 3),
    /** 区域入侵。 */
    FIELD_DETECTION("fielddetection", "区域入侵", 3),
    /** 进入区域。 */
    REGION_ENTRANCE("regionEntrance", "进入区域", 3),
    /** 离开区域。 */
    REGION_EXITING("regionExiting", "离开区域", 3),
    /** 逆行检测。 */
    LOITERING_REVERSE("reverseDetection", "逆行检测", 3),
    /** 徘徊检测。 */
    LOITERING("loitering", "徘徊检测", 3),
    /** 人员聚集。 */
    HUMAN_GATHERING("gathering", "人员聚集", 3),
    /** 快速移动。 */
    FAST_MOVING("fastmoving", "快速移动", 3),
    /** 停车检测。 */
    PARKING("parking", "停车检测", 3),
    /** 物品拿取。 */
    ITEM_TAKEN("takeaway", "物品拿取", 3),
    /** 物品遗留。 */
    ITEM_LEFT("leftdetection", "物品遗留", 3),
    /** 声音异常（声音陡升）。 */
    AUDIO_SUDDEN_RISE("audioSuddenRise", "声音陡升", 3),
    /** 声音异常（声音陡降）。 */
    AUDIO_SUDDEN_DROP("audioSuddenDrop", "声音陡降", 3),
    /** 声音异常（音频异常）。 */
    AUDIO_ABNORMAL("audioAbnormal", "声音异常", 3),
    /** 人脸抓拍。 */
    FACE_SNAP("facedetection", "人脸抓拍", 1),
    /** 人脸比对。 */
    FACE_COMPARE("faceContrast", "人脸比对", 2),
    /** 车牌识别。 */
    PLATE_RECOGNITION("vehicleDetection", "车辆检测", 1),
    /** 设备状态改变。 */
    DEVICE_STATUS("devicestatus", "设备状态", 4),
    /** 通道状态改变。 */
    CHANNEL_STATUS("channelstatus", "通道状态", 4),
    /** 磁盘状态改变。 */
    DISK_STATUS("diskstatus", "磁盘状态", 4),
    /** 录像异常。 */
    RECORD_ABNORMAL("recordabnormal", "录像异常", 4),
    /** 设备高温。 */
    DEVICE_HIGH_TEMP("deviceHighTemperature", "设备高温", 4),
    /** IP 冲突。 */
    IP_CONFLICT("ipconflict", "IP 冲突", 4),
    /** 网络断开。 */
    NETWORK_BROKEN("networkbroken", "网络断开", 4),
    /** 非法访问。 */
    ILLEGAL_ACCESS("illegalAccess", "非法访问", 4),
    /** 未识别的事件。 */
    UNKNOWN("", "未知事件", 3);

    private final String keyword;

    private final String label;

    /** 优先级：1 低、2 中、3 高、4 紧急。与平台报警级别口径一致。 */
    private final int priority;

    HikvisionAlarmType(String keyword, String label, int priority) {
        this.keyword = keyword;
        this.label = label;
        this.priority = priority;
    }

    /** 事件类型关键词（用于在 ISAPI 载荷里匹配）。 */
    public String keyword() {
        return keyword;
    }

    /** 中文显示名。 */
    public String label() {
        return label;
    }

    /** 优先级：1 低、2 中、3 高、4 紧急。 */
    public int priority() {
        return priority;
    }

    /**
     * 按 ISAPI 载荷里的事件名判断类型。
     *
     * <p>用<b>包含匹配且忽略大小写</b>：固件可能加后缀（{@code regionEntrance_v2}），
     * 也可能用不同大小写（{@code LineDetection}）。精确相等会漏掉大半。
     *
     * @param eventType 载荷里的 {@code eventType} 字段；为空时返回 {@link #UNKNOWN}
     */
    public static HikvisionAlarmType of(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return UNKNOWN;
        }
        String v = eventType.toLowerCase();
        for (HikvisionAlarmType t : values()) {
            if (t == UNKNOWN) {
                continue;
            }
            if (v.contains(t.keyword.toLowerCase())) {
                return t;
            }
        }
        return UNKNOWN;
    }

    /**
     * 按 SDK 的结构化报警类型判断。
     *
     * <p>只覆盖 SDK 明确定义的那几个大类；{@code EHOME_ISAPI_ALARM} 走
     * {@link #of(String)}，因为具体事件在载荷里。
     */
    public static HikvisionAlarmType ofSdkType(int sdkAlarmType) {
        return switch (sdkAlarmType) {
            case 700 -> DEVICE_STATUS;
            case 701 -> CHANNEL_STATUS;
            case 702, 705 -> DISK_STATUS;
            case 706 -> RECORD_ABNORMAL;
            default -> UNKNOWN;
        };
    }
}
