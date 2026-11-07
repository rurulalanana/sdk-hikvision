package io.github.rurulalanana.hikvision.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 海康 ISUP 原生库的探测、解压与加载。
 *
 * <h3>为什么要自己解压，而不像大华那样交给 SDK</h3>
 * 海康的 ISUP SDK 没有「按 os.name 自动找库」的加载器 —— 官方 demo 直接写死
 * {@code System.getProperty("user.dir") + "/lib/libHCISUPCMS.so"}，
 * 要求库在<b>工作目录</b>下。单 jar 部署时工作目录是 {@code /opt/gb28181}，
 * 把 6 个 .so 摆在那里既难管理也容易漏。
 *
 * <p>本类把 jar 内的 {@code <platform>/*.so} 解压到临时目录，再交给 JNA 按绝对路径加载。
 *
 * <h3>加载顺序有依赖</h3>
 * {@code libHCISUPCMS.so} 运行时依赖 {@code libcrypto.so} / {@code libssl.so} /
 * {@code HCAapSDKCom/} 下的组件。所以：
 * <ol>
 *   <li>先把<b>整个目录</b>解压出来（不是单个文件）；</li>
 *   <li>再逐个 {@code Native.load}，且父库先于子库；</li>
 *   <li>把目录路径通过 {@code NET_ECMS_SetSDKInitCfg} 告诉 SDK，
 *       它自己去那个目录找 crypto/ssl 与组件库。</li>
 * </ol>
 *
 * <h3>缺库时的行为</h3>
 * 与大华一致：{@link #available()} 返回 {@code false}，所有海康 Bean 降级为不可用，
 * 平台照常启动、国标链路不受影响。
 */
public final class HikvisionNativeLibrary {

    private static final Logger log = LoggerFactory.getLogger(HikvisionNativeLibrary.class);

    /** 当前平台在 jar 内的目录名；无法识别时为 {@code null}。 */
    private static final String PLATFORM_DIR = resolvePlatformDir();

    /** 解压后的本地目录，首次成功加载后固定。 */
    private static volatile Path extractedDir;

    /** 探测结果缓存。 */
    private static volatile Boolean available;

    private HikvisionNativeLibrary() {
    }

    /**
     * jar 内是否含当前平台的原生库。
     *
     * <p>为 {@code false} 时所有海康功能快速失败，避免反复抛底层加载异常刷满日志。
     */
    public static boolean available() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        synchronized (HikvisionNativeLibrary.class) {
            if (available != null) {
                return available;
            }
            available = probe();
            return available;
        }
    }

    /**
     * 解压后的原生库目录；不可用时返回 {@code null}。
     *
     * <p>SDK 需要这个路径去定位 crypto/ssl 与 {@code HCAapSDKCom/} 组件。
     */
    public static Path libDir() {
        if (!available()) {
            return null;
        }
        Path dir = extractedDir;
        if (dir != null) {
            return dir;
        }
        synchronized (HikvisionNativeLibrary.class) {
            if (extractedDir == null) {
                extractedDir = extract();
            }
            return extractedDir;
        }
    }

    /** 当前平台在 jar 内的目录名（{@code win64} / {@code linux64} …）。 */
    public static String platformDir() {
        return PLATFORM_DIR;
    }

    /**
     * 一行诊断摘要，供接口层展示「海康为什么不可用」。
     *
     * <p>运维看到「不支持该设备类型」无从下手，必须能说清缺什么。
     */
    public static String diagnose() {
        String os = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        if (!available()) {
            return String.format("海康 ISUP 原生库缺失（当前平台 %s/%s，期望 jar 内有 %s/ 目录）；"
                            + "请把 SDK 的库文件放入 sdk-hikvision/lib/%s/ 后用正确的 profile 重新构建",
                    os, arch, PLATFORM_DIR, PLATFORM_DIR);
        }
        Path dir = libDir();
        if (dir == null) {
            return String.format("海康 ISUP 原生库解压失败（%s/%s，%s/）", os, arch, PLATFORM_DIR);
        }
        return String.format("海康 ISUP 原生库就绪（%s/%s，%s/ → %s）", os, arch, PLATFORM_DIR, dir);
    }

    // ==================== 内部实现 ====================

    private static boolean probe() {
        if (PLATFORM_DIR == null) {
            log.warn("无法识别当前平台({}/{})，跳过海康原生库检查",
                    System.getProperty("os.name"), System.getProperty("os.arch"));
            // 无法判断时不阻断：交给加载失败时的诊断兜底
            return true;
        }
        String probePath = PLATFORM_DIR + "/" + cmsLibName();
        boolean found = HikvisionNativeLibrary.class.getClassLoader().getResource(probePath) != null;
        if (found) {
            log.debug("海康原生库检查通过: jar 内含 {}", probePath);
            return true;
        }
        log.warn("======================================================================");
        log.warn("海康 ISUP SDK 原生库缺失：当前平台为 {}，但 jar 内没有 {} 目录下的库文件",
                PLATFORM_DIR, PLATFORM_DIR);
        log.warn("海康不提供公开下载，需从海康开放平台获取对应平台的 SDK 包，");
        log.warn("把 {} 目录下的库文件放进 sdk-hikvision/lib/{}/ 后重新构建：", PLATFORM_DIR, PLATFORM_DIR);
        log.warn("  Linux  : mvn install \"-Dhikvision.platform=linux64\"");
        log.warn("  Windows: mvn install \"-Dhikvision.platform=win64\"");
        log.warn("海康相关功能将降级为不可用，国标与大华链路不受影响。");
        log.warn("======================================================================");
        return false;
    }

    /**
     * 把 jar 内的 {@code <platform>/} 整个解压到临时目录。
     *
     * <p>必须整目录解压：SDK 会按相对路径去 {@code HCAapSDKCom/} 找组件，
     * 只解压主库会报「组件加载失败」。
     */
    private static Path extract() {
        /*
         * 优先用「工作目录下的 lib/」——这是海康官方 demo 唯一支持的布局
         * （见 SDK 包 doc/runOnLinux/runInLinux.md：部署时需在同级目录建 lib 存放库文件，
         *  代码里路径固定为 user.dir + "/lib/libcrypto.so"）。
         *
         * 实测：把库解压到 /tmp/<随机>/linux64/ 时，SDK 的 OpenSSL 加载逻辑
         * 拿不到可用的 libcrypto，COMP_zlib() 返回 NULL，NET_ECMS_StartListen
         * 崩在 libssl 的 load_builtin_compressions（si_addr=0x8）；
         * 而放在 user.dir/lib/ 下则一切正常。
         *
         * 所以：如果 user.dir/lib 里已经有完整的一套库（运维手工放或上次解压的），
         * 直接用它；否则解压到 user.dir/lib 而不是随机临时目录。
         */
        Path workLib = workDirLib();
        if (hasCompleteLib(workLib)) {
            log.info("海康原生库使用工作目录: {}", workLib);
            /*
             * 已存在也要补一次：硬链接与 RPATH 都是「没有才加」的幂等操作，
             * 而库可能是运维手工放的（没打补丁），漏掉这两步就会在
             * NET_ECMS_StartListen 里 SIGSEGV。
             */
            if (!isWindows()) {
                linkOpensslAliases(workLib);
                int patched = ElfRpathPatcher.patchAll(workLib);
                log.info("海康原生库 RPATH 补丁: {} 个文件补上 $ORIGIN", patched);
            }
            return workLib;
        }
        try {
            Files.createDirectories(workLib);
            return extractInto(workLib);
        } catch (IOException e) {
            log.warn("海康原生库解压到工作目录失败（{}），回退到临时目录", e.getMessage());
        }
        try {
            Path base = Files.createTempDirectory("hikvision-isup-");
            base.toFile().deleteOnExit();
            Path target = base.resolve(PLATFORM_DIR);
            Files.createDirectories(target);
            return extractInto(target);
        } catch (IOException e) {
            log.error("海康原生库解压失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 工作目录下的 {@code lib/}（海康官方 demo 的布局）。
     *
     * <p>SDK 的文档明确要求把库放这里：{@code doc/runOnLinux/runInLinux.md}
     * 「构建之后在 linux 环境部署需要在同级目录下自行创建 lib 文件夹存放 sdk 库文件」，
     * 代码里路径固定为 {@code System.getProperty("user.dir") + "/lib/libcrypto.so"}。
     */
    private static Path workDirLib() {
        return Paths.get(System.getProperty("user.dir", "."), "lib").toAbsolutePath().normalize();
    }

    /**
     * 把 {@code libssl.so.1.0.0} / {@code libcrypto.so.1.0.0} 做成
     * {@code libssl.so} / {@code libcrypto.so} 的<b>硬链接</b>。
     *
     * <p>glibc 的 {@code dlopen} 按 <b>inode</b> 去重（不是按文件名，也不是按内容）。
     * 海康包里两组文件版本还不一致（{@code .so} 是 1.0.2t、{@code .so.1.0.0} 是 1.0.2r），
     * 而 {@code libssl.so} 的 SONAME 就是 {@code libssl.so.1.0.0}。
     * SDK 一旦分别按两个名字打开，进程里就有两套 OpenSSL，内部全局表互不认，
     * 在 {@code load_builtin_compressions} 处 SIGSEGV。
     *
     * <p>硬链接让两个名字共享 inode，才能真正只加载一份。
     * 只复制内容不行：复制得到的是新 inode，去重依然失败。
     */
    private static void linkOpensslAliases(Path dir) {
        String[][] pairs = {
                {"libcrypto.so", "libcrypto.so.1.0.0"},
                {"libssl.so", "libssl.so.1.0.0"},
        };
        for (String[] pair : pairs) {
            Path real = dir.resolve(pair[0]);
            Path alias = dir.resolve(pair[1]);
            if (!Files.isRegularFile(real)) {
                continue;
            }
            try {
                Files.deleteIfExists(alias);
                Files.createLink(alias, real);
                log.info("海康原生库别名归一（硬链接）: {} ← {}", pair[1], pair[0]);
            } catch (IOException | UnsupportedOperationException e) {
                /*
                 * 文件系统不支持硬链接时退回复制。
                 * 复制解决不了 inode 去重，但至少文件存在，
                 * 配合 $ORIGIN RPATH 仍能解析符号（多数情况够用）。
                 */
                try {
                    Files.copy(real, alias, StandardCopyOption.REPLACE_EXISTING);
                    log.warn("海康原生库别名归一退回复制（硬链接不可用: {}）: {} ← {}",
                            e.getMessage(), pair[1], pair[0]);
                } catch (IOException e2) {
                    log.warn("海康原生库别名归一失败 {} ← {}: {}",
                            pair[1], pair[0], e2.getMessage());
                }
            }
        }
    }

    /**
     * 目录里是否已有一套可用的库。
     *
     * <p>只检查主库与 OpenSSL：这三者齐了 SDK 就能起来，
     * 其余（PlayCtrl / OpenAL 等）本来就按需加载、可有可无。
     */
    private static boolean hasCompleteLib(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        return Files.isRegularFile(dir.resolve(cmsLibName()))
                && Files.isRegularFile(dir.resolve("libcrypto.so"))
                && Files.isRegularFile(dir.resolve("libssl.so"));
    }

    private static Path extractInto(Path target) {
        try {
            List<String> names = listPlatformEntries();
            if (names.isEmpty()) {
                log.error("海康原生库目录为空: {}/", PLATFORM_DIR);
                return null;
            }
            int copied = 0;
            for (String name : names) {
                Path dest = target.resolve(name);
                if (name.endsWith("/")) {
                    Files.createDirectories(dest);
                    continue;
                }
                // 子目录里的文件要先建父目录
                Path parent = dest.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (InputStream in = HikvisionNativeLibrary.class.getClassLoader()
                        .getResourceAsStream(PLATFORM_DIR + "/" + name)) {
                    if (in == null) {
                        continue;
                    }
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                    /*
                     * 只给临时目录登记删除。
                     * 工作目录下的 lib/ 是持久部署（运维可能手工放库、也可能复用），
                     * 登记 deleteOnExit 会导致每次重启都删掉再解压，
                     * 既浪费 IO 又让「运维放好的库」被覆盖。
                     */
                    if (!dest.startsWith(workDirLib())) {
                        dest.toFile().deleteOnExit();
                    }
                    copied++;
                }
            }
            log.info("海康原生库已解压: {} 个文件 → {}", copied, target);

            /*
             * 归一化 OpenSSL 的版本别名（硬链接）。
             *
             * 海康包里 libssl.so 是 1.0.2t，而 libssl.so.1.0.0 是更旧的 1.0.2r；
             * libcrypto 同理。libssl.so 的 SONAME 恰恰是 libssl.so.1.0.0，
             * 它按 NEEDED=libcrypto.so.1.0.0 找依赖。
             *
             * SDK 会走两条路径打开：SetSDKInitCfg 给的 libcrypto.so，
             * 以及 libssl 的 NEEDED 触发的 libcrypto.so.1.0.0。
             * glibc 的 dlopen 按 inode 去重，两个名字指向不同 inode 时
             * 同一进程里就有两套 OpenSSL，全局表互不兼容 →
             * NET_ECMS_StartListen 崩在 load_builtin_compressions（si_addr=0x8）。
             *
             * 硬链接让两个名字共享 inode，才能真正只加载一份。
             */
            if (!isWindows()) {
                linkOpensslAliases(target);
            }

            /*
             * 给 ELF 补 $ORIGIN RPATH。
             *
             * 海康的 libssl.so 依赖 libcrypto.so.1.0.0，但没有 RPATH，
             * 动态链接器只查系统路径 → 找不到 → SDK 内部 SSL_library_init
             * 拿空函数指针 → SIGSEGV（实测崩溃在 load_builtin_compressions）。
             * 补上 $ORIGIN 后 libssl 会自动在同目录找到 libcrypto。
             */
            if (!isWindows()) {
                int patched = ElfRpathPatcher.patchAll(target);
                log.info("海康原生库 RPATH 补丁: {} 个文件补上 $ORIGIN", patched);
            }
            return target;
        } catch (IOException e) {
            log.error("海康原生库解压失败: {}（目标 {}）", e.getMessage(), target, e);
            return null;
        }
    }

    /**
     * 列出 jar 内 {@code <platform>/} 下的条目。
     *
     * <p>JNA 的 classpath 扫描在 jar 里拿不到目录清单，只能靠已知文件名列表 +
     * 逐个探测。这里用「候选名 × 是否存在」的办法，简单且不依赖 jar 遍历。
     */
    private static List<String> listPlatformEntries() {
        List<String> out = new ArrayList<>();
        for (String name : knownLibraryNames()) {
            if (HikvisionNativeLibrary.class.getClassLoader()
                    .getResource(PLATFORM_DIR + "/" + name) != null) {
                out.add(name);
            }
        }
        // HCAapSDKCom 组件目录：SDK 会按相对路径找，必须一并解压
        String comDir = "HCAapSDKCom";
        for (String name : knownComEntries()) {
            String rel = comDir + "/" + name;
            if (HikvisionNativeLibrary.class.getClassLoader()
                    .getResource(PLATFORM_DIR + "/" + rel) != null) {
                out.add(rel);
            }
        }
        return out;
    }

    /** 主库文件名（随平台变），用于探测。 */
    private static String cmsLibName() {
        return isWindows() ? "HCISUPCMS.dll" : "libHCISUPCMS.so";
    }

    /**
     * 已知的库文件名。
     *
     * <p>按官方 SDK 包 {@code lib64/} 的实际清单列举；缺哪个就跳过哪个，
     * 因为不同版本/裁剪程度不完全一致（有些包不带 PlayCtrl、OpenAL）。
     */
    private static List<String> knownLibraryNames() {
        boolean win = isWindows();
        if (win) {
            return List.of(
                    "HCISUPCMS.dll", "HCISUPStream.dll", "HCISUPAlarm.dll",
                    "HCISUPSS.dll", "HCNetUtils.dll", "AudioIntercom.dll",
                    "AudioRender.dll", "PlayCtrl.dll", "SystemTransform.dll",
                    "SuperRender.dll", "NPQos.dll", "OpenAL32.dll", "hpr.dll",
                    "sqlite3.dll", "zlib1.dll", "libiconv2.dll",
                    "libeay32.dll", "ssleay32.dll", "msvcr90.dll", "d3dx9_43.dll");
        }
        return List.of(
                "libHCISUPCMS.so", "libHCISUPStream.so", "libHCISUPAlarm.so",
                "libHCISUPSS.so", "libHCNetUtils.so", "libAudioIntercom.so",
                "libAudioRender.so", "libPlayCtrl.so", "libSystemTransform.so",
                "libSuperRender.so", "libNPQos.so", "libhpr.so",
                "libsqlite3.so", "libz.so", "libiconv.so",
                "libcrypto.so", "libssl.so",
                // 带版本号的变体：SDK 内部按 soname 找 libcrypto.so.1.0.0，
                // 只解压 libcrypto.so 会报「找不到符号」。两个都要。
                "libcrypto.so.1.0.0", "libssl.so.1.0.0",
                "libcrypto.so.1.1", "libssl.so.1.1",
                "libcrypto.so.3", "libssl.so.3");
    }

    /** {@code HCAapSDKCom/} 下的组件库（Linux 是 .so，Windows 是 .dll）。 */
    private static List<String> knownComEntries() {
        boolean win = isWindows();
        if (win) {
            return List.of("SystemTransform.dll", "OpenAL32.dll", "msvcr90.dll",
                    "libiconv2.dll");
        }
        // 实测 Linux 包里放的是 libiconv2.so（不是 libiconv.so），
        // 两个名字都列，缺哪个跳过哪个
        return List.of("libSystemTransform.so", "libOpenAL32.so",
                "libiconv2.so", "libiconv.so");
    }

    private static boolean isWindows() {
        return PLATFORM_DIR != null && PLATFORM_DIR.startsWith("win");
    }

    /** 当前平台对应的目录名；无法识别时返回 {@code null}。 */
    private static String resolvePlatformDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean x64 = arch.contains("64") && !arch.contains("aarch64") && !arch.contains("arm");
        if (os.contains("win")) {
            return x64 ? "win64" : "win32";
        }
        if (os.contains("linux")) {
            if (arch.contains("aarch64") || arch.contains("arm")) {
                return "linuxARM";
            }
            return x64 ? "linux64" : "linux32";
        }
        if (os.contains("mac")) {
            return arch.contains("aarch64") ? "macARM" : "mac64";
        }
        return null;
    }

    /** 临时目录是否可写（诊断用）。 */
    public static boolean tempWritable() {
        File tmp = new File(System.getProperty("java.io.tmpdir"));
        return tmp.isDirectory() && tmp.canWrite();
    }
}
