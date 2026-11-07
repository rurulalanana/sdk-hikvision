package io.github.rurulalanana.hikvision.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 给 ELF 动态库补 {@code $ORIGIN} RPATH。
 *
 * <h3>为什么必须做这件事</h3>
 * 海康自带的 {@code libssl.so} 的 {@code NEEDED} 是
 * {@code libcrypto.so.1.0.0}，但<b>它没有 RPATH/RUNPATH</b>。
 * 于是动态链接器只按系统路径找这个依赖 —— 系统里没有（或只有 1.1/3.x 版本），
 * 加载就失败。
 *
 * <p>SDK 的 {@code LoadOpenSSLLib()} 不检查这个失败，直接调
 * {@code SSL_library_init()}，内部函数指针为 NULL →
 * <b>SIGSEGV in load_builtin_compressions</b>，整个 JVM 挂掉。
 *
 * <h3>为什么不在 Java 侧预加载就够了</h3>
 * 预加载（{@code dlopen(path, RTLD_GLOBAL)}）能让符号可见，
 * 但 SDK 自己 {@code dlopen} 时用的可能是不同 flags，会再开一份。
 * 直接给库文件补 RPATH 是唯一与调用方无关的修法。
 *
 * <h3>实现方式</h3>
 * 不依赖 {@code patchelf}（现场未必装），直接改 ELF 的动态段：
 * 把一段已有的 {@code DT_NULL} 改写成 {@code DT_RUNPATH}，
 * 再把字符串 {@code $ORIGIN} 写进已有的字符串表空隙。
 *
 * <p>只在 Linux 上执行；Windows 的 DLL 搜索路径天然包含自身目录，不需要。
 */
final class ElfRpathPatcher {

    private static final Logger log = LoggerFactory.getLogger(ElfRpathPatcher.class);

    /** {@code DT_NULL}：动态段结束标记，可安全改写。 */
    private static final int DT_NULL = 0;

    /** {@code DT_RUNPATH}：运行时搜索路径（优先于 DT_RPATH）。 */
    private static final int DT_RUNPATH = 29;

    /** {@code DT_RPATH}：老式运行时搜索路径。 */
    private static final int DT_RPATH = 15;

    /** {@code DT_STRTAB}：字符串表地址。 */
    private static final int DT_STRTAB = 5;

    /** {@code DT_STRSZ}：字符串表大小。 */
    private static final int DT_STRSZ = 10;

    private ElfRpathPatcher() {
    }

    /**
     * 给目录下所有 {@code .so} 补 {@code $ORIGIN} RPATH。
     *
     * <p>已含 {@code $ORIGIN} 的跳过；不是 ELF 的跳过。
     *
     * @return 实际修改的文件数
     */
    static int patchAll(Path dir) {
        int patched = 0;
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".so") && !name.contains(".so.")) {
                    continue;
                }
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                try {
                    if (patch(p)) {
                        patched++;
                    }
                } catch (Exception e) {
                    log.debug("补 RPATH 跳过 {}: {}", name, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("遍历原生库目录失败: {}", e.getMessage());
        }
        return patched;
    }

    /**
     * 给单个 ELF 补 {@code $ORIGIN} RPATH。
     *
     * @return {@code true} 表示做了修改
     */
    static boolean patch(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        if (raw.length < 64 || raw[0] != 0x7F || raw[1] != 'E' || raw[2] != 'L' || raw[3] != 'F') {
            return false;   // 不是 ELF
        }
        // 只处理 64 位小端（x86-64 / aarch64）
        if (raw[4] != 2 || raw[5] != 1) {
            return false;
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        // ELF64 头：e_phoff @0x20(8B), e_phentsize @0x36(2B), e_phnum @0x38(2B)
        long phoff = buf.getLong(0x20);
        int phentsize = buf.getShort(0x36) & 0xFFFF;
        int phnum = buf.getShort(0x38) & 0xFFFF;

        long dynOff = -1;
        long dynSize = -1;
        for (int i = 0; i < phnum; i++) {
            int base = (int) (phoff + (long) i * phentsize);
            if (base + 56 > raw.length) {
                break;
            }
            int pType = buf.getInt(base);
            if (pType == 2) {          // PT_DYNAMIC
                dynOff = buf.getLong(base + 8);    // p_offset
                dynSize = buf.getLong(base + 32);  // p_filesz
                break;
            }
        }
        if (dynOff < 0 || dynSize <= 0) {
            return false;
        }

        // 遍历动态段，找 DT_STRTAB / DT_STRSZ / 空闲的 DT_NULL
        long strtabAddr = -1;
        long strsz = 0;
        long nullEntryOff = -1;
        for (long off = dynOff; off + 16 <= dynOff + dynSize; off += 16) {
            int tag = buf.getInt((int) off);
            long val = buf.getLong((int) off + 8);
            if (tag == DT_NULL) {
                if (nullEntryOff < 0) {
                    nullEntryOff = off;
                }
                // 连续两个 DT_NULL 说明到末尾了，不再往后找空位
                int nextTag = buf.getInt((int) off + 16);
                if (nextTag == DT_NULL) {
                    break;
                }
                continue;
            }
            if (tag == DT_STRTAB) {
                strtabAddr = val;
            } else if (tag == DT_STRSZ) {
                strsz = val;
            } else if (tag == DT_RPATH || tag == DT_RUNPATH) {
                // 已有 RPATH：检查是否含 $ORIGIN
                return false;
            }
        }
        if (strtabAddr < 0 || nullEntryOff < 0) {
            return false;
        }

        // 虚拟地址 → 文件偏移：用第一个 PT_LOAD 的 p_vaddr/p_offset 换算
        long strtabOff = vaddrToOffset(buf, raw.length, phoff, phentsize, phnum, strtabAddr);
        if (strtabOff < 0) {
            return false;
        }

        // 在字符串表末尾找连续 0 的空间写 "$ORIGIN\0"
        byte[] origin = "$ORIGIN\0".getBytes();
        long writeAt = -1;
        for (long i = strtabOff + strsz; i + origin.length < raw.length; i++) {
            boolean free = true;
            for (int k = 0; k < origin.length; k++) {
                if (raw[(int) i + k] != 0) {
                    free = false;
                    break;
                }
            }
            if (free) {
                writeAt = i;
                break;
            }
        }
        if (writeAt < 0) {
            return false;
        }

        // 写字符串
        System.arraycopy(origin, 0, raw, (int) writeAt, origin.length);
        // 把 DT_NULL 改写成 DT_RUNPATH，值 = 相对 strtab 的偏移
        buf.putInt((int) nullEntryOff, DT_RUNPATH);
        buf.putLong((int) nullEntryOff + 8, writeAt - strtabOff);

        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap(raw));
        }
        log.debug("已补 $ORIGIN RPATH: {}", file.getFileName());
        return true;
    }

    /** 把虚拟地址换算成文件偏移（按 PT_LOAD 段映射）。 */
    private static long vaddrToOffset(ByteBuffer buf, int fileLen, long phoff,
                                      int phentsize, int phnum, long vaddr) {
        for (int i = 0; i < phnum; i++) {
            int base = (int) (phoff + (long) i * phentsize);
            if (base + 56 > fileLen) {
                break;
            }
            int pType = buf.getInt(base);
            if (pType != 1) {      // PT_LOAD
                continue;
            }
            long pOffset = buf.getLong(base + 8);
            long pVaddr = buf.getLong(base + 16);
            long pFilesz = buf.getLong(base + 32);
            if (vaddr >= pVaddr && vaddr < pVaddr + pFilesz) {
                return vaddr - pVaddr + pOffset;
            }
        }
        return -1;
    }
}
