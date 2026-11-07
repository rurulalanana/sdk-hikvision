import io.github.rurulalanana.hikvision.internal.HikvisionCms;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.util.ArrayList;
import java.util.List;

/**
 * 最小化的海康 {@code NET_ECMS_StartListen} 探针。
 *
 * <p>用生产代码里的 {@code HikvisionCms} 结构体，保证与真实调用路径一致；
 * 用来隔离「结构体/调用顺序」与「运行环境（JVM / 符号冲突）」两类问题。
 *
 * <h3>为什么必须给真实回调</h3>
 * {@code fnCB} 为 NULL 时 SDK 直接返回错误码 17（参数错误），
 * <b>绕过了崩溃路径</b>，于是「探针不崩」会被误读成「修复有效」。
 * 这个坑实际踩过，所以这里默认挂上回调。
 *
 * <h3>用法</h3>
 * <pre>
 *   javac -cp sdk-hikvision-1.0.0.jar:jna.jar -d out HikvisionStartListenProbe.java
 *   java -cp out:sdk-hikvision.jar:jna.jar HikvisionStartListenProbe /opt/gb28181/lib
 * </pre>
 *
 * <p>期望输出 {@code StartListen = 0 err=0}。
 * 若崩溃在 {@code load_builtin_compressions}，说明 OpenSSL 被外部符号劫持，
 * 见 {@link ZlmConflictProbe}。
 */
public class HikvisionStartListenProbe {

    private static final List<Memory> KEEP = new ArrayList<>();

    private static Pointer buf(String s) {
        Memory m = new Memory(256);
        m.clear();
        byte[] b = s.getBytes();
        m.write(0, b, 0, Math.min(b.length, 255));
        KEEP.add(m);
        return m;
    }

    /** 注册回调，签名照官方 demo。 */
    public static class RegCb implements HikvisionCms.DeviceRegisterCb {
        @Override
        public boolean invoke(int lUserID, int dwDataType, Pointer pOutBuffer,
                              int dwOutLen, Pointer pInBuffer, int dwInLen, Pointer pUser) {
            System.out.println("  回调: type=" + dwDataType + " userID=" + lUserID);
            return true;
        }
    }

    public static void main(String[] a) {
        String dir = a[0];
        System.out.println("dir = " + dir);
        HikvisionCms cms = Native.load(dir + "/libHCISUPCMS.so", HikvisionCms.class);

        System.out.println("SetSDKInitCfg(0) = "
                + cms.NET_ECMS_SetSDKInitCfg(0, buf(dir + "/libcrypto.so")));
        System.out.println("SetSDKInitCfg(1) = "
                + cms.NET_ECMS_SetSDKInitCfg(1, buf(dir + "/libssl.so")));
        System.out.println("Init = " + cms.NET_ECMS_Init());
        System.out.println("SetSDKLocalCfg(5) = "
                + cms.NET_ECMS_SetSDKLocalCfg(5, buf(dir + "/HCAapSDKCom")));

        HikvisionCms.NetEhomeCmsListenParam p = new HikvisionCms.NetEhomeCmsListenParam();
        byte[] ip = "0.0.0.0".getBytes();
        System.arraycopy(ip, 0, p.struAddress.szIP, 0, ip.length);
        p.struAddress.wPort = (short) 7660;
        p.fnCB = new RegCb();
        p.write();

        System.out.println("调用 StartListen ...");
        int h = cms.NET_ECMS_StartListen(p);
        System.out.println("StartListen = " + h + " err=" + cms.NET_ECMS_GetLastError());
        if (h >= 0) {
            System.out.println("监听成功，保持 20 秒 ...");
            try {
                Thread.sleep(20000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.out.println("结束");
        }
    }
}
