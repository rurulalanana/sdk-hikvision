package io.github.rurulalanana.hikvision.internal;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;

/**
 * 海康 ISUP SDK 的生命周期与设备句柄管理。
 *
 * <h3>三个库、三套初始化，缺一不可</h3>
 * <ul>
 *   <li>{@code HCISUPCMS} —— 信令：设备注册、预览/回放请求、ISAPI 透传、远程控制；</li>
 *   <li>{@code HCISUPStream} —— 流媒体：平台当 SMS，接收设备推来的码流；</li>
 *   <li>{@code HCISUPAlarm} —— 报警：接收设备上报的报警。</li>
 * </ul>
 * 三者是独立的 SDK，各自 {@code Init}、各自 {@code SetSDKInitCfg}。
 * 只初始化 CMS 会表现为「设备能注册、能控制，但看不到画面、收不到报警」。
 *
 * <h3>为什么懒加载</h3>
 * 与大华同样的理由：原生库缺失时若在类初始化阶段抛异常，调用方 try/catch 拦不住，
 * 会把整个 Spring 容器拖垮，连国标链路一起废掉。因此首次使用时加载，
 * 失败原因缓存下来供接口层展示。
 *
 * <h3>设备句柄</h3>
 * ISUP 的 {@code lUserID} 是 <b>SDK 分配的</b>（不是平台生成），
 * 每次设备注册都会给一个新值，设备重连后旧值作废。
 * 所以句柄表必须随注册/下线事件更新，不能缓存太久。
 */
public final class HikvisionSdk {

    private static final Logger log = LoggerFactory.getLogger(HikvisionSdk.class);

    /** 单例：SDK 全局只应初始化一次，句柄表必须全局唯一。 */
    private static final HikvisionSdk INSTANCE = new HikvisionSdk();

    public static HikvisionSdk get() {
        return INSTANCE;
    }

    /** SDK 路径配置项：crypto 库路径。 */
    private static final int CFG_CRYPTO_PATH = 0;
    /** SDK 路径配置项：ssl 库路径。 */
    private static final int CFG_SSL_PATH = 1;
    /** SDK 本地配置项：组件库目录。 */
    private static final int CFG_COM_DIR = 5;

    private volatile HikvisionCms cms;
    private volatile HikvisionStream stream;
    private volatile HikvisionAlarm alarm;

    /**
     * 图片服务器库。
     *
     * <p>它是<b>可选</b>的：只在启用「设备直传抓图」时才需要。
     * 加载失败不影响注册/取流/报警，所以单独初始化、失败也不置错。
     */
    private volatile HikvisionSs ss;

    /** SS 加载失败原因；与核心库的 {@link #loadError} 分开记。 */
    private volatile String ssError;

    /** 加载失败原因（供诊断展示）；{@code null} 表示没失败过。 */
    private volatile String loadError;

    /**
     * SDK 配置路径的内存缓冲（crypto/ssl/com 目录）。
     *
     * <p><b>必须强引用住</b>：SDK 会把指针存起来稍后再读，
     * 若被 GC 回收，后续访问就是野指针（表现为随机 SIGSEGV）。
     */
    private final java.util.List<Memory> cfgBuffers = new java.util.ArrayList<>();

    private volatile boolean initialized;

    private final Object initLock = new Object();

    private HikvisionSdk() {
    }

    // ==================== 初始化 ====================

    /**
     * 初始化三个 SDK（幂等，线程安全）。
     *
     * @return 是否全部可用
     */
    public boolean init() {
        if (initialized) {
            return true;
        }
        synchronized (initLock) {
            if (initialized) {
                return true;
            }
            if (!HikvisionNativeLibrary.available()) {
                loadError = HikvisionNativeLibrary.diagnose();
                return false;
            }
            Path dir = HikvisionNativeLibrary.libDir();
            if (dir == null) {
                loadError = "海康原生库解压失败（临时目录不可写？）";
                log.error("{}", loadError);
                return false;
            }
            try {
                if (!preloadOpenssl(dir)) {
                    return false;
                }
                if (!initCms(dir)) {
                    return false;
                }
                if (!initStream(dir)) {
                    return false;
                }
                if (!initAlarm(dir)) {
                    return false;
                }
                initialized = true;
                log.info("海康 ISUP SDK 初始化完成（平台目录 {}，库目录 {}）",
                        HikvisionNativeLibrary.platformDir(), dir);
                return true;
            } catch (Throwable t) {
                loadError = String.valueOf(t.getMessage());
                log.error("海康 ISUP SDK 初始化异常: {}", loadError, t);
                return false;
            }
        }
    }

    /**
     * 打印进程里 OpenSSL 的实际状态，供排障用。
     *
     * <p>{@code NET_ECMS_StartListen} 内部崩在 {@code libssl} 的
     * {@code load_builtin_compressions}（{@code si_addr=0x8}），
     * 直接原因是 {@code COMP_zlib()} 返回 NULL。这里把
     * 「加载了哪几份 OpenSSL」「COMP_zlib 返回什么」都打出来，
     * 一眼就能看出是符号没解析还是加载了多份。
     */
    public static void dumpOpensslState() {
        try {
            Path dir = HikvisionNativeLibrary.libDir();
            if (dir == null) {
                log.warn("OpenSSL 状态: 库目录为空");
                return;
            }
            // 1) /proc/self/maps 里有多少份 libssl / libcrypto
            StringBuilder maps = new StringBuilder();
            int sslCount = 0;
            int cryptoCount = 0;
            java.util.Set<String> paths = new java.util.LinkedHashSet<>();
            try {
                for (String line : Files.readAllLines(Paths.get("/proc/self/maps"))) {
                    if (!line.contains("libssl.so") && !line.contains("libcrypto.so")) {
                        continue;
                    }
                    if (line.contains("libssl.so")) {
                        sslCount++;
                    }
                    if (line.contains("libcrypto.so")) {
                        cryptoCount++;
                    }
                    int slash = line.indexOf('/');
                    if (slash > 0) {
                        paths.add(line.substring(slash));
                    }
                }
            } catch (IOException ignored) {
                // 非 Linux 平台没有 /proc
            }
            log.info("OpenSSL 状态: libssl 映射 {} 条, libcrypto 映射 {} 条, 实际文件 {}",
                    sslCount, cryptoCount, paths);

            // 2) COMP_zlib 的返回值：NULL 就会崩
            HikvisionCrypto crypto = crypto();
            if (crypto == null) {
                log.warn("OpenSSL 状态: libcrypto 未加载");
                return;
            }
            Pointer m = crypto.COMP_zlib();
            if (m == null) {
                log.error("OpenSSL 状态: COMP_zlib() = NULL ← StartListen 会 SIGSEGV");
                return;
            }
            int type = m.getInt(0);
            Pointer namePtr = m.getPointer(8);
            log.info("OpenSSL 状态: COMP_zlib={} type={} name={}",
                    m, type, namePtr == null ? "NULL ← 会崩" : namePtr.getString(0));
            Pointer ver = crypto.SSLeay_version(0);
            log.info("OpenSSL 状态: 版本 {}", ver == null ? "NULL" : ver.getString(0));
        } catch (Throwable t) {
            log.warn("OpenSSL 状态检查失败: {}", t.getMessage());
        }
    }

    /**
     * 单独绑定 libcrypto 用于探针。
     *
     * <p>只有 {@code dumpOpensslState} 用，生产路径不依赖它。
     */
    private static HikvisionCrypto crypto() {
        Path dir = HikvisionNativeLibrary.libDir();
        if (dir == null) {
            return null;
        }
        try {
            return Native.load(findLib(dir, "crypto").toString(), HikvisionCrypto.class);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 排障用的 libcrypto 最小绑定。 */
    interface HikvisionCrypto extends com.sun.jna.Library {
        Pointer COMP_zlib();

        Pointer SSLeay_version(int type);
    }

    /**
     * 预加载 OpenSSL 的 crypto 与 ssl 库。
     *
     * <h3>为什么必须预加载</h3>
     * 海康自带的 {@code libssl.so} <b>没有 {@code $ORIGIN} RPATH</b>，
     * 它按 SONAME {@code libcrypto.so.1.0.0} 找依赖时只查系统路径 ——
     * 找不到就 {@code dlopen} 失败。而 SDK 内部不检查这个失败，
     * 继续调 {@code SSL_library_init()}，直接 SIGSEGV：
     * <pre>
     *   libssl.so  load_builtin_compressions+0x106   ← 空指针
     *   ← SSL_COMP_get_compression_methods
     *   ← SSL_library_init
     *   ← HCISUPCMS::LoadOpenSSLLib
     * </pre>
     * 实测证据（Python ctypes）：单独加载 libssl.so 报
     * {@code libcrypto.so.1.0.0: cannot open shared object file}；
     * 先加载 libcrypto.so 再加载就一切正常。
     *
     * <h3>为什么用 RTLD_GLOBAL</h3>
     * 只有 {@code RTLD_GLOBAL} 才能让后加载的 libssl 在<b>已加载的</b>
     * libcrypto 里解析符号。默认的 {@code RTLD_LOCAL} 会另开一份，符号仍不可见。
     *
     * <h3>为什么不改 LD_LIBRARY_PATH</h3>
     * 它必须在 JVM 启动前设，已经跑着的进程改不了。
     * 预加载是进程内可行的等价手段。
     */
    private boolean preloadOpenssl(Path dir) {
        Path crypto = findLib(dir, "crypto");
        Path ssl = findLib(dir, "ssl");
        try {
            /*
             * 顺序与文件名都很关键：
             * 1) libcrypto 必须在前：libssl 依赖它的符号。
             * 2) 文件名必须与 SetSDKInitCfg 传的完全一致（都用不带版本号的
             *    libcrypto.so / libssl.so），glibc 才能按路径去重复用同一份。
             *
             * 用 RTLD_DEEPBIND 而不是 RTLD_GLOBAL：
             * 平台里 ZLMediaKit（zlm4j 的 libmk_api.so）静态链接了 OpenSSL 3.4.0
             * 并导出 520 个 SSL_* / 92 个 CRYPTO_* / 14 个 COMP_* 符号。
             * 它先于海康加载且是全局可见的，于是海康 libssl.so(1.0.2t) 的
             * COMP_zlib 等符号会被绑到 ZLM 的 3.4.0 实现上 —— ABI 完全不同，
             * COMP_zlib() 返回不兼容的结构体，comp->method 为 NULL，
             * 在 load_builtin_compressions 的 mov 0x8(%rax) 处 SIGSEGV。
             *
             * RTLD_DEEPBIND 让本库及其依赖优先在自身作用域内解析符号，
             * 彻底隔离掉外部 OpenSSL 的干扰。
             */
            dlopenGlobal(crypto.toString());
            dlopenGlobal(ssl.toString());
            log.info("海康 OpenSSL 预加载完成: {} {}", crypto.getFileName(), ssl.getFileName());
            return true;
        } catch (Throwable t) {
            loadError = "海康 OpenSSL 预加载失败（" + crypto.getFileName()
                    + " / " + ssl.getFileName() + "）：" + t.getMessage();
            log.error("{}", loadError);
            return false;
        }
    }

    /**
     * 用 {@code dlopen(path, RTLD_LAZY|RTLD_DEEPBIND)} 加载库。
     *
     * <p>JNA 没有暴露 {@code RTLD_DEEPBIND}，只能直接调 {@code libdl}。
     * 句柄不关闭（故意）：进程生命周期内都需要这些符号。
     *
     * <p>用 {@code RTLD_DEEPBIND} 而<b>不是</b> {@code RTLD_GLOBAL}：
     * 平台里 ZLMediaKit 静态链接了 OpenSSL 3.4.0 并导出大量 SSL_/CRYPTO_ 符号，
     * 全局可见。海康的 OpenSSL 是 1.0.2t，符号被劫持到 3.4.0 上会 ABI 不匹配，
     * 直接崩在 {@code load_builtin_compressions}。
     * {@code RTLD_DEEPBIND} 让符号优先在本库作用域内解析，把两者隔离开。
     */
    private static void dlopenGlobal(String path) {
        if (isWindows()) {
            // Windows 的 LoadLibrary 天生进程全局，用 JNA 常规加载即可
            Native.load(path, com.sun.jna.Library.class);
            return;
        }
        DlLibrary dl = Native.load("dl", DlLibrary.class);
        Pointer handle = dl.dlopen(path, RTLD_LAZY | RTLD_DEEPBIND);
        if (handle == null) {
            Pointer err = dl.dlerror();
            throw new IllegalStateException(err == null ? "dlopen 失败" : err.getString(0));
        }
    }

    private static boolean isWindows() {
        String p = HikvisionNativeLibrary.platformDir();
        return p != null && p.startsWith("win");
    }

    /** {@code libdl} 的最小绑定。 */
    interface DlLibrary extends com.sun.jna.Library {
        Pointer dlopen(String filename, int flags);

        Pointer dlerror();
    }

    /** {@code RTLD_LAZY}。 */
    private static final int RTLD_LAZY = 1;

    /**
     * {@code RTLD_DEEPBIND}：符号优先在<b>本库及其依赖</b>里解析，
     * 而不是优先用全局符号表里已有的。
     *
     * <p>glibc 的常量值是 0x8。用它把海康的 OpenSSL 1.0.2t 与
     * ZLMediaKit 静态链接的 OpenSSL 3.4.0 隔离开。
     */
    private static final int RTLD_DEEPBIND = 0x8;

    private boolean initCms(Path dir) {
        HikvisionCms lib = Native.load(libPath(dir, "HCISUPCMS").toString(), HikvisionCms.class);
        cms = lib;
        // 顺序照官方 demo：SetSDKInitCfg(0/1) → Init() → SetSDKLocalCfg(5)
        applyInitCfg(dir, (type, ptr) -> lib.NET_ECMS_SetSDKInitCfg(type, ptr));
        if (!lib.NET_ECMS_Init()) {
            loadError = "NET_ECMS_Init 失败，错误码 " + lib.NET_ECMS_GetLastError();
            log.error("海康 CMS 初始化失败: {}", loadError);
            return false;
        }
        applyComPath(dir, (type, ptr) -> lib.NET_ECMS_SetSDKLocalCfg(type, ptr));
        log.debug("海康 CMS 就绪，SDK 版本 {}", lib.NET_ECMS_GetBuildVersion());
        return true;
    }

    private boolean initStream(Path dir) {
        HikvisionStream lib = Native.load(libPath(dir, "HCISUPStream").toString(),
                HikvisionStream.class);
        stream = lib;
        applyInitCfg(dir, (type, ptr) -> lib.NET_ESTREAM_SetSDKInitCfg(type, ptr));
        if (!lib.NET_ESTREAM_Init()) {
            loadError = "NET_ESTREAM_Init 失败，错误码 " + lib.NET_ESTREAM_GetLastError();
            log.error("海康 Stream 初始化失败: {}", loadError);
            return false;
        }
        log.debug("海康 Stream 就绪，SDK 版本 {}", lib.NET_ESTREAM_GetBuildVersion());
        applyComPath(dir, (type, ptr) -> lib.NET_ESTREAM_SetSDKLocalCfg(type, ptr));
        return true;
    }

    private boolean initAlarm(Path dir) {
        HikvisionAlarm lib = Native.load(libPath(dir, "HCISUPAlarm").toString(),
                HikvisionAlarm.class);
        alarm = lib;
        applyInitCfg(dir, (type, ptr) -> lib.NET_EALARM_SetSDKInitCfg(type, ptr));
        if (!lib.NET_EALARM_Init()) {
            loadError = "NET_EALARM_Init 失败，错误码 " + lib.NET_EALARM_GetLastError();
            log.error("海康 Alarm 初始化失败: {}", loadError);
            return false;
        }
        applyComPath(dir, (type, ptr) -> lib.NET_EALARM_SetSDKLocalCfg(type, ptr));
        log.debug("海康 Alarm 就绪");
        return true;
    }

    /**
     * 设置 crypto / ssl 路径（必须在 {@code Init()} 之前）。
     *
     * <p>照官方 demo 的做法：路径写进 <b>256 字节的定长缓冲</b>再传指针。
     * 缓冲长度不能随意加大 —— demo 用的是 256，与 SDK 内部约定一致。
     */
    private void applyInitCfg(Path dir, CfgSetter initCfg) {
        /*
         * 用不带版本号的文件名（libcrypto.so / libssl.so）。
         *
         * 实测：传 libcrypto.so.1.0.0 时 SDK 会崩在 load_builtin_compressions，
         * 传 libcrypto.so 则 StartListen 返回 0（strace 确认 openat 命中的就是
         * 这个路径）。原因是 SDK 内部有自己的一套「先拼名字再打开」逻辑，
         * 带版本号的名字会让它先按 soname 打开另一份，于是进程里有两套 OpenSSL。
         */
        String crypto = findLib(dir, "crypto").toString();
        String ssl = findLib(dir, "ssl").toString();
        boolean okCrypto = initCfg.set(CFG_CRYPTO_PATH, hold(pathBuffer(crypto)));
        boolean okSsl = initCfg.set(CFG_SSL_PATH, hold(pathBuffer(ssl)));
        log.info("海康 SDK 配置: crypto={}({}) ssl={}({})",
                crypto, okCrypto ? "ok" : "失败", ssl, okSsl ? "ok" : "失败");
    }

    /**
     * 设置组件库目录（必须在 {@code Init()} <b>之后</b>）。
     *
     * <p>这是 demo 里最容易看错的一点：{@code SetSDKLocalCfg(COM_PATH)} 在
     * {@code NET_ECMS_Init()} 之后调用。放前面不会报错，但组件库加载不上，
     * 后续建立 SSL 连接时崩。
     */
    private void applyComPath(Path dir, CfgSetter localCfg) {
        String comDir = dir.resolve("HCAapSDKCom").toString();
        boolean ok = localCfg.set(CFG_COM_DIR, hold(pathBuffer(comDir)));
        log.info("海康 SDK 组件目录: {}（{}）", comDir, ok ? "ok" : "失败");
    }

    /**
     * 把绝对路径写成 256 字节定长缓冲（与官方 demo 的 {@code BYTE_ARRAY(256)} 一致）。
     */
    private static Pointer pathBuffer(String path) {
        Memory mem = new Memory(PATH_BUF_LEN);
        mem.clear();
        byte[] raw = path.getBytes(StandardCharsets.UTF_8);
        mem.write(0, raw, 0, Math.min(raw.length, PATH_BUF_LEN - 1));
        return mem;
    }

    /** 路径缓冲长度，与 demo 一致。 */
    private static final int PATH_BUF_LEN = 256;

    /** 把缓冲登记进强引用列表，返回同一个指针。 */
    private Pointer hold(Pointer mem) {
        if (mem instanceof Memory m) {
            cfgBuffers.add(m);
        }
        return mem;
    }

    /**
     * 定位某个库的实际文件名。
     *
     * <p>Linux 上是 {@code libcrypto.so} / {@code libssl.so}，
     * Windows 上是 {@code libeay32.dll} / {@code ssleay32.dll} —— 名字完全不同，
     * 所以按「候选名依次试」而不是拼字符串。
     */
    private static Path findLib(Path dir, String kind) {
        String[] candidates = "crypto".equals(kind)
                ? new String[]{"libcrypto.so", "libcrypto.so.1.0.0", "libcrypto.so.1.1",
                        "libcrypto.so.3", "libeay32.dll"}
                : new String[]{"libssl.so", "libssl.so.1.0.0", "libssl.so.1.1",
                        "libssl.so.3", "ssleay32.dll"};
        for (String name : candidates) {
            Path p = dir.resolve(name);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        // 找不到也返回第一个候选：让 SDK 自己去报错，错误信息里会带路径，便于排障
        return dir.resolve(candidates[0]);
    }

    private static Path libPath(Path dir, String base) {
        boolean win = HikvisionNativeLibrary.platformDir() != null
                && HikvisionNativeLibrary.platformDir().startsWith("win");
        return dir.resolve(win ? base + ".dll" : "lib" + base + ".so");
    }

    // ==================== 访问器 ====================

    /** CMS 库；未初始化时返回 {@code null}。 */
    public HikvisionCms cms() {
        return cms;
    }

    /** Stream 库；未初始化时返回 {@code null}。 */
    public HikvisionStream stream() {
        return stream;
    }

    /** Alarm 库；未初始化时返回 {@code null}。 */
    public HikvisionAlarm alarm() {
        return alarm;
    }

    /**
     * 图片服务器库（懒加载）。
     *
     * <p>核心三库就绪后才尝试；它缺失只影响「设备直传抓图」这一项，
     * 平台仍可用 ISAPI 反向抓图。
     *
     * @return 库对象；不可用时 {@code null}
     */
    public HikvisionSs ss() {
        if (ss != null) {
            return ss;
        }
        if (!initialized) {
            return null;
        }
        synchronized (initLock) {
            if (ss != null) {
                return ss;
            }
            if (ssError != null) {
                return null;
            }
            Path dir = HikvisionNativeLibrary.libDir();
            if (dir == null) {
                ssError = "原生库目录不可用";
                return null;
            }
            Path lib = libPath(dir, "HCISUPSS");
            if (!Files.isRegularFile(lib)) {
                ssError = "jar 内缺少 " + lib.getFileName()
                        + "（SDK 包里未包含图片服务器组件）";
                log.info("海康抓图直传不可用：{}", ssError);
                return null;
            }
            try {
                HikvisionSs lib2 = Native.load(lib.toString(), HikvisionSs.class);
                /*
                 * SS 的配置枚举与 CMS 完全不同（见 HCISUPSS.h）：
                 *   1 = SDK_PATH（组件目录，仅 Linux）
                 *   4 = LIBEAY_PATH（libcrypto）
                 *   5 = SSLEAY_PATH（libssl）
                 * 不能套用 CMS 的 0/1，否则路径写到了错误的配置项上。
                 */
                lib2.NET_ESS_SetSDKInitCfg(HikvisionSs.SS_INIT_CFG_SDK_PATH,
                        hold(pathBuffer(dir.toString())));
                lib2.NET_ESS_SetSDKInitCfg(HikvisionSs.SS_INIT_CFG_LIBEAY_PATH,
                        hold(pathBuffer(findLib(dir, "crypto").toString())));
                lib2.NET_ESS_SetSDKInitCfg(HikvisionSs.SS_INIT_CFG_SSLEAY_PATH,
                        hold(pathBuffer(findLib(dir, "ssl").toString())));
                if (!lib2.NET_ESS_Init()) {
                    ssError = "NET_ESS_Init 失败，错误码 " + lib2.NET_ESS_GetLastError();
                    log.warn("海康图片服务器初始化失败：{}", ssError);
                    return null;
                }
                ss = lib2;
                log.info("海康图片服务器（SS）已就绪，SDK 版本 {}",
                        lib2.NET_ESS_GetBuildVersion());
                return ss;
            } catch (Throwable t) {
                ssError = String.valueOf(t.getMessage());
                log.warn("海康图片服务器加载失败：{}", ssError);
                return null;
            }
        }
    }

    /** SS 不可用原因；可用或未尝试时为 {@code null}。 */
    public String ssError() {
        return ssError;
    }

    /** SDK 是否可用。 */
    public boolean available() {
        return initialized;
    }

    /** 不可用原因；可用时为 {@code null}。 */
    public String loadError() {
        return loadError;
    }

    /** 一行诊断摘要。 */
    public String diagnose() {
        if (initialized) {
            return HikvisionNativeLibrary.diagnose();
        }
        return loadError == null ? "海康 ISUP SDK 未初始化" : loadError;
    }

    /** 统一的配置写入回调（三个 SDK 的方法签名一致，只是分属不同接口）。 */
    private interface CfgSetter {
        boolean set(int type, Pointer ptr);
    }
}
