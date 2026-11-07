import io.github.rurulalanana.hikvision.internal.HikvisionCms;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.util.ArrayList;
import java.util.List;

/**
 * 回归验证：ZLMediaKit 的 OpenSSL 3.4 是否会劫持海康 OpenSSL 1.0.2 的符号。
 *
 * <p>这是排查「平台启动即 SIGSEGV，崩在 libssl 的
 * {@code load_builtin_compressions+0x106}（{@code si_addr=0x8}）」时
 * 写下的最小复现，留着当回归工具：以后升级 zlm4j 或换海康 SDK 版本时，
 * 用它确认隔离手段（{@code RTLD_DEEPBIND}）还有效。
 *
 * <h3>背景</h3>
 * {@code zlm4j} 的 {@code libmk_api.so} 静态链接了 OpenSSL 3.4.0，
 * 导出 520 个 {@code SSL_*}、92 个 {@code CRYPTO_*}、14 个 {@code COMP_*} 符号。
 * 它先于海康加载且全局可见时，海康 {@code libssl.so}(1.0.2t) 的
 * {@code COMP_zlib} 会被绑到 3.4.0 的实现上 —— ABI 不兼容，
 * {@code COMP_zlib()} 返回 NULL，于是 {@code load_builtin_compressions}
 * 里 {@code mov 0x8(%rax)} 读地址 0x8 崩溃。
 *
 * <h3>用法</h3>
 * <pre>
 *   javac -cp sdk-hikvision-1.0.0.jar:jna.jar -d out ZlmConflictProbe.java
 *
 *   # 0x100 = RTLD_GLOBAL：预期复现 SIGSEGV（证明冲突真实存在）
 *   java -cp out:sdk-hikvision.jar:jna.jar ZlmConflictProbe /opt/gb28181/lib 0x100
 *
 *   # 0x8 = RTLD_DEEPBIND：预期 StartListen = 0（证明隔离有效）
 *   java -cp out:sdk-hikvision.jar:jna.jar ZlmConflictProbe /opt/gb28181/lib 0x8
 * </pre>
 *
 * <p>第三个参数可指定 {@code libmk_api.so} 的路径，默认
 * {@code /tmp/zlmx/linux-x86-64/libmk_api.so}（从 zlm4j jar 里解出来的）。
 */
public class ZlmConflictProbe {

    public interface Dl extends Library {
        Pointer dlopen(String name, int flags);
        Pointer dlerror();
    }

    private static final int RTLD_LAZY = 1;
    private static final int RTLD_GLOBAL = 0x100;
    private static final int RTLD_DEEPBIND = 0x8;

    private static final List<Memory> KEEP = new ArrayList<>();

    private static Pointer buf(String s) {
        Memory m = new Memory(256);
        m.clear();
        byte[] b = s.getBytes();
        m.write(0, b, 0, Math.min(b.length, 255));
        KEEP.add(m);
        return m;
    }

    public static class RegCb implements HikvisionCms.DeviceRegisterCb {
        @Override
        public boolean invoke(int lUserID, int dwDataType, Pointer pOutBuffer,
                              int dwOutLen, Pointer pInBuffer, int dwInLen, Pointer pUser) {
            return true;
        }
    }

    public static void main(String[] a) {
        String dir = a[0];
        int flag = a.length > 1 ? Integer.decode(a[1]) : RTLD_DEEPBIND;
        String zlm = a.length > 2 ? a[2] : "/tmp/zlmx/linux-x86-64/libmk_api.so";
        System.out.println("dir=" + dir + " flag=0x" + Integer.toHexString(flag) + " zlm=" + zlm);

        Dl dl = Native.load("dl", Dl.class);

        // 1) 先加载 ZLM（模拟平台启动顺序）
        Pointer hz = dl.dlopen(zlm, RTLD_LAZY | RTLD_GLOBAL);
        System.out.println("dlopen libmk_api = " + hz);
        if (hz == null) {
            Pointer e = dl.dlerror();
            System.out.println("  失败: " + (e == null ? "?" : e.getString(0)));
        }

        // 2) 加载海康 OpenSSL
        Pointer hc = dl.dlopen(dir + "/libcrypto.so", RTLD_LAZY | flag);
        System.out.println("dlopen libcrypto = " + hc);
        Pointer hs = dl.dlopen(dir + "/libssl.so", RTLD_LAZY | flag);
        System.out.println("dlopen libssl = " + hs);

        // 3) 海康 SDK
        HikvisionCms cms = Native.load(dir + "/libHCISUPCMS.so", HikvisionCms.class);
        cms.NET_ECMS_SetSDKInitCfg(0, buf(dir + "/libcrypto.so"));
        cms.NET_ECMS_SetSDKInitCfg(1, buf(dir + "/libssl.so"));
        System.out.println("Init = " + cms.NET_ECMS_Init());
        cms.NET_ECMS_SetSDKLocalCfg(5, buf(dir + "/HCAapSDKCom"));

        HikvisionCms.NetEhomeCmsListenParam p = new HikvisionCms.NetEhomeCmsListenParam();
        byte[] ip = "0.0.0.0".getBytes();
        System.arraycopy(ip, 0, p.struAddress.szIP, 0, ip.length);
        p.struAddress.wPort = (short) 7660;
        p.fnCB = new RegCb();
        p.write();

        System.out.println("调用 StartListen ...");
        int h = cms.NET_ECMS_StartListen(p);
        System.out.println("StartListen = " + h + " err=" + cms.NET_ECMS_GetLastError());
    }
}
