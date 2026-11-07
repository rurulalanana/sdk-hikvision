# sdk-hikvision

海康设备接入实现：走 **ISUP（EHome）协议**，与国标 SIP、大华 NetSDK **并列**。

平台不需要知道设备在 NAT 后面还是公网上 —— 设备主动连平台，平台在这条连接上登录。

---

## 1. 为什么叫 `sdk-hikvision`

命名约定是 `sdk-<厂商>`（见 `sdk-dahua/README.md`）：

| 厂商 | 模块 |
|---|---|
| 大华 | `sdk-dahua` |
| 海康 | `sdk-hikvision` |
| 宇视 | `sdk-uniview`（待建） |

契约包 `io.github.rurulalanana.deviceaccess` 与厂商无关，**不随模块名变**。

---

## 2. 五条链路

ISUP 是「设备主动连平台」的模型，五个功能各有一个监听端口：

| 功能 | SDK 接口 | 默认端口 | 配置项 |
|---|---|---|---|
| 注册（CMS/DAS） | `NET_ECMS_StartListen` | 7660 | `hikvision.cms.port` |
| 取流（SMS 预览） | `NET_ESTREAM_StartListenPreview` | **7665** | `hikvision.stream.port` |
| 回放 | `NET_ESTREAM_StartListenPlayBack` | **7666** | `hikvision.playback.port` |
| 报警 | `NET_EALARM_StartListen` | 7663 | `hikvision.alarm.port` |
| 抓图直传（SS） | `NET_ESS_StartListen` | 7664 | `hikvision.picture.port` |

> **端口不能复用**。它们是独立的监听器，共用端口时后启动的返回
> **错误码 72（端口占用）**，表现为「注册在线但一点播就超时」。
> 早期把 SMS 也配成 7660 是错的。

---

## 3. 原生库

海康不提供公开下载，需从海康开放平台获取对应平台的 SDK 包。
把库文件放进 `lib/<platform>/`（**不入库**，厂商许可），按 profile 打进 jar：

```powershell
mvn -o install -DskipTests "-Dgpg.skip=true"                      # 按当前系统自动选
mvn -o install -DskipTests "-Dgpg.skip=true" "-Dhikvision.platform=linux64"   # 交叉打包 Linux
```

⚠️ `-D` 参数**必须加引号**（同 `-Dgpg.skip` 的坑）。

### 3.1 部署布局

**必须放 `user.dir/lib/`**，这是海康官方 demo 唯一支持的布局
（`SDK/doc/runOnLinux/runInLinux.md`，代码里路径固定为
`System.getProperty("user.dir") + "/lib/libcrypto.so"`）。

`HikvisionNativeLibrary.extract()` 优先用 `user.dir/lib`，
只有它不可写才回退到 `/tmp/hikvision-isup-*`。

### 3.2 两个必须做的修补

`HikvisionNativeLibrary` 在解压后自动完成，但要知道为什么：

**① OpenSSL 版本别名必须做成硬链接**

海康包里 `libssl.so`(1.0.2t) 与 `libssl.so.1.0.0`(1.0.2r) 是**不同版本的两个文件**，
而 `libssl.so` 的 SONAME 就是 `libssl.so.1.0.0`。
glibc 的 `dlopen` 按 **inode** 去重（不是文件名、不是内容），
两个名字指向不同 inode → 进程里两套 OpenSSL → 崩在 `load_builtin_compressions`。

**复制内容无效**，必须 `Files.createLink`。

**② 必须补 `$ORIGIN` RUNPATH**

海康的 `libssl.so` 依赖 `libcrypto.so.1.0.0` 但没有 RPATH，
动态链接器只查系统路径 → 找不到 → SDK 内部不检查失败 →
`SSL_library_init` 拿空函数指针 → SIGSEGV。

用 `ElfRpathPatcher` 直接改 ELF 动态段，不依赖 `patchelf`。

---

## 4. ⚠️ 与 ZLMediaKit 的 OpenSSL 符号冲突（必读）

**这是本模块最容易踩的坑，排查花了很久。**

`zlm4j` 的 `libmk_api.so` **静态链接了 OpenSSL 3.4.0**，
导出 520 个 `SSL_*`、92 个 `CRYPTO_*`、14 个 `COMP_*` 符号。
平台启动时它先于海康加载且全局可见，于是海康 `libssl.so`(1.0.2t) 的
`COMP_zlib` 等 PLT 绑定被劫持到 3.4.0 的实现上 —— ABI 不兼容，
`COMP_zlib()` 返回 NULL，`load_builtin_compressions` 里
`mov 0x8(%rax)` 读地址 `0x8` → SIGSEGV。

崩溃栈长这样（每次一致）：

```
C  [libssl.so.1.0.0+0x49886]  load_builtin_compressions+0x106
C  [libHCISUPCMS.so]          CSSLTrans::LoadOpenSSLLib+0x1b7
C  [libHCISUPCMS.so]          CServerLinkMQTT::Start
C  [libHCISUPCMS.so]          CListenServer::StartServer
C  [libHCISUPCMS.so]          NET_ECMS_StartListen+0x13e
siginfo: si_addr: 0x0000000000000008
```

**解法**：`HikvisionSdk.dlopenGlobal` 用 **`RTLD_DEEPBIND`(0x8)**，
让符号优先在本库作用域内解析，而不是优先用全局符号表：

```java
private static final int RTLD_LAZY = 1;
private static final int RTLD_DEEPBIND = 0x8;   // 不是 RTLD_GLOBAL(0x100)
Pointer handle = dl.dlopen(path, RTLD_LAZY | RTLD_DEEPBIND);
```

### 回归验证

`probe/ZlmConflictProbe.java` 是最小复现：

```bash
# 0x100 = RTLD_GLOBAL：预期复现 SIGSEGV
java -cp out:sdk-hikvision.jar:jna.jar ZlmConflictProbe /opt/gb28181/lib 0x100

# 0x8 = RTLD_DEEPBIND：预期 StartListen = 0
java -cp out:sdk-hikvision.jar:jna.jar ZlmConflictProbe /opt/gb28181/lib 0x8
```

`probe/HikvisionStartListenProbe.java` 是单跑海康的最小探针，
用来区分「结构体/调用顺序」与「运行环境」两类问题。

> **探针必须给真实回调**：`fnCB` 为 NULL 时 SDK 返回错误码 17，
> **绕过了崩溃路径**，「探针不崩」会被误读成「修复有效」。

---

## 5. 初始化顺序（照官方 demo，不能改）

```
SetSDKInitCfg(0=libcrypto, 1=libssl)  →  Init()  →  SetSDKLocalCfg(5=HCAapSDKCom)
```

`SetSDKLocalCfg(5)` 放 `Init()` **之前**不会报错，但组件库加载不上，
后续建立 SSL 连接时崩。

SS（抓图直传）的枚举与 CMS **完全不同**：
`SS_INIT_CFG_SDK_PATH=1` / `LIBEAY_PATH=4` / `SSLEAY_PATH=5`。
套用 CMS 的 0/1 会把路径写到错误的配置项上。

---

## 6. 结构体必须逐字段对照官方 demo

JNA 结构体错位**编译器不报错，运行时随机 SIGSEGV**。
权威来源是官方 Java demo 的 `src/SdkService/*Service/HCISUP*.java`。

踩过的错位：

| 结构体 | 错在哪 |
|---|---|
| `NET_EHOME_CMS_LISTEN_PARAM` | 曾写成 `byLinkMode/byRes/wImmediate/byRes2`，完全不对 |
| `NET_EHOME_PREVIEWINFO_IN` | `dwLinkMode` 与 `dwStreamType` 顺序反了，还多了 `byRes` |
| `NET_EHOME_SDK_TIME` | 少了 `byRes1` 与 `wMSecond`，整结构错位 2 字节 |
| `NET_EHOME_DEV_REG_INFO` | `byPassWord` 应是 `byte[32]`，`bySessionKey` 是 `byte[16]` |

---

## 7. 包结构

```
io.github.rurulalanana.hikvision/
  HikvisionPlatform.java        装配体：start() 逐个启动五条链路，失败只 WARN
  HikvisionCmsListener.java     注册回调 → HikvisionRegisterTable + 上线/下线事件
  HikvisionStreamListener.java  预览取流 → PS 解封装 → ZLM
  HikvisionPlaybackListener.java 回放（StartPlayBack + StartPushPlayBack）
  HikvisionAlarmListener.java   报警（结构化 + ISAPI 智能事件）
  HikvisionSsListener.java      抓图直传（设备推 JPEG 到平台）
  HikvisionIsapi.java           ISAPI 反向请求（配置/编码/抓图/云台）
  HikvisionDeviceControl.java   设备控制契约实现（云台/录像/对时）
  HikvisionRegisterTable.java   注册表（内存，含失效阈值）
  internal/
    HikvisionSdk.java           单例：预加载 OpenSSL + 三个 SDK 初始化
    HikvisionNativeLibrary.java 解压 + 硬链接归一 + RPATH 补丁 + 平台探测
    ElfRpathPatcher.java        直接改 ELF 动态段补 $ORIGIN
    HikvisionCms/Stream/Alarm/Ss.java   JNA 绑定
probe/                          排障探针（非生产代码，编译时排除）
```

---

## 8. 设备侧配置

| 项 | 值 |
|---|---|
| 平台接入 → ISUP → 服务器地址 | 平台公网 IP |
| ISUP 端口 | `hikvision.cms.port`（默认 7660） |
| ISUP 密钥 | 与 `HIK_ISUP_KEY` 一致 |
| 设备编号 | 平台侧用它当 `device_id` |

防火墙需放行：7660（TCP+UDP）、7665、7666、7663（UDP）；抓图另加 7664。

---

## 9. 已知限制

- **抓图直传默认关闭**（`hikvision.picture.enabled=false`）：关闭时抓图走
  ISAPI 反向请求，功能不受影响。
- **对讲未实现**：`NET_ESTREAM_StartListenVoiceTalk` + `NET_ECMS_StartVoiceTalk`，
  官方 demo 在 `VoiceDemo.java`。
- **倍速回放未实现**：官方 `NET_EHOME_PLAYBACK_PAUSE_RESTART_PARAM` 里没有
  `lSpeed` 字段，只支持暂停/恢复。
