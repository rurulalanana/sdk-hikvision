package io.github.rurulalanana.hikvision;

import io.github.rurulalanana.hikvision.internal.HikvisionSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 海康 ISUP 的装配体：把注册/报警/流媒体三个监听与各能力实现组装起来。
 *
 * <h3>为什么要有这一层</h3>
 * 三个监听都要在容器启动时拉起、关闭时停止，而且要共享同一张注册表。
 * 让 Spring 直接管这些类会把「SDK 生命周期」泄漏到业务配置里；
 * 这里收成一个对象，starter 侧只声明一个 Bean 即可。
 *
 * <h3>三个监听可以独立启停</h3>
 * 端口被占用、或只想要信令不要报警时，可以让某个监听失败而不影响其余 ——
 * 例如报警端口与别的服务冲突，取流照样能用。
 * 因此 {@link #start()} 逐个启动、逐个记日志，不因一个失败就全盘放弃。
 */
public class HikvisionPlatform {

    private static final Logger log = LoggerFactory.getLogger(HikvisionPlatform.class);

    private final HikvisionRegisterTable registerTable;

    private final HikvisionCmsListener cmsListener;

    private final HikvisionAlarmListener alarmListener;

    private final HikvisionStreamListener streamListener;

    private final HikvisionPlaybackListener playbackListener;

    /**
     * 抓图直传监听。
     *
     * <p>可为 {@code null}：SS 组件不在所有 SDK 包里，
     * 缺失时退回 ISAPI 反向抓图。
     */
    private final HikvisionSsListener ssListener;

    private final HikvisionIsapi isapi;

    private final HikvisionDeviceAccessProvider accessProvider;

    private final HikvisionMediaProvider mediaProvider;

    private final HikvisionDeviceExtensions extensions;

    public HikvisionPlatform(HikvisionRegisterTable registerTable,
                             HikvisionCmsListener cmsListener,
                             HikvisionAlarmListener alarmListener,
                             HikvisionStreamListener streamListener,
                             HikvisionPlaybackListener playbackListener,
                             HikvisionSsListener ssListener,
                             HikvisionIsapi isapi,
                             HikvisionDeviceAccessProvider accessProvider,
                             HikvisionMediaProvider mediaProvider,
                             HikvisionDeviceExtensions extensions) {
        this.registerTable = registerTable;
        this.cmsListener = cmsListener;
        this.alarmListener = alarmListener;
        this.streamListener = streamListener;
        this.playbackListener = playbackListener;
        this.ssListener = ssListener;
        this.isapi = isapi;
        this.accessProvider = accessProvider;
        this.mediaProvider = mediaProvider;
        this.extensions = extensions;
    }

    /**
     * 启动全部监听。
     *
     * <p>SDK 不可用时只打一条告警就返回 —— 平台照常启动，国标与大华链路不受影响。
     * 这与大华的降级策略一致：缺厂商 SDK 不该拖垮整个平台。
     */
    public void start() {
        HikvisionSdk sdk = HikvisionSdk.get();
        if (!sdk.init()) {
            log.warn("海康 ISUP 未启用：{}", sdk.diagnose());
            return;
        }
        boolean cms = cmsListener.start();
        boolean stream = streamListener.start();
        boolean playback = playbackListener.start();
        boolean alarm = alarmListener.start();
        boolean ss = ssListener != null && ssListener.start();
        log.info("海康 ISUP 启动结果: 注册={} 取流={} 回放={} 报警={} 抓图直传={}",
                cms, stream, playback, alarm, ss);
        if (!cms) {
            log.warn("海康设备注册监听未启动，设备将无法注册到平台");
        }
        if (!stream) {
            log.warn("海康流媒体监听未启动，设备即使注册成功也播不了画面");
        }
        if (!playback) {
            log.warn("海康回放监听未启动，历史录像回放不可用");
        }
        if (!alarm) {
            log.warn("海康报警监听未启动，报警不会上报（设备状态与智能事件都收不到）");
        }
        if (!ss) {
            log.info("海康抓图直传未启用，抓图将走 ISAPI 反向请求（功能不受影响）");
        }
    }

    /** 停止全部监听。 */
    public void stop() {
        // 顺序：先停报警与取流（不再收数据），最后停注册（设备仍连着也无所谓）
        safeStop("报警", alarmListener::stop);
        safeStop("抓图直传", () -> {
            if (ssListener != null) {
                ssListener.stop();
            }
        });
        safeStop("回放", playbackListener::stop);
        safeStop("取流", streamListener::stop);
        safeStop("注册", cmsListener::stop);
    }

    private void safeStop(String name, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.debug("停止海康{}监听时出错: {}", name, e.getMessage());
        }
    }

    // ==================== 访问器 ====================

    public HikvisionRegisterTable registerTable() {
        return registerTable;
    }

    public HikvisionIsapi isapi() {
        return isapi;
    }

    public HikvisionDeviceAccessProvider accessProvider() {
        return accessProvider;
    }

    public HikvisionMediaProvider mediaProvider() {
        return mediaProvider;
    }

    public HikvisionDeviceExtensions extensions() {
        return extensions;
    }

    public HikvisionCmsListener cmsListener() {
        return cmsListener;
    }

    public HikvisionAlarmListener alarmListener() {
        return alarmListener;
    }

    public HikvisionStreamListener streamListener() {
        return streamListener;
    }

    public HikvisionPlaybackListener playbackListener() {
        return playbackListener;
    }

    /** 抓图直传监听；未启用时为 {@code null}。 */
    public HikvisionSsListener ssListener() {
        return ssListener;
    }

    /** SDK 是否可用（供接口层判断要不要展示「不支持」原因）。 */
    public boolean available() {
        return HikvisionSdk.get().available();
    }

    /** 不可用原因。 */
    public String unavailableReason() {
        return HikvisionSdk.get().diagnose();
    }
}
