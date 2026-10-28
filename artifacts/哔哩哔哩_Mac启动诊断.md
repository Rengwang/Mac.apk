# 哔哩哔哩 9.16.1 在 Mac.apk 上的启动诊断

APK：`~/Downloads/iBiliPlayer-bili.apk`
包名：`tv.danmaku.bili`，版本 9.16.1 (9161100)
大小：216 MB，33 个 classes*.dex，无壳（Application 为
`tv.danmaku.bili.HiltApplication`）

## 安装

```text
macapk-installhelper install --apk iBiliPlayer-bili.apk
-> /Applications/哔哩哔哩.app 成功
```

33 dex、216 MB 能正常安装/转换，说明 Mac.apk 的大包安装链路没问题。

## 启动时先遇到的两个框架缺口（已修）

1. `BootOpt` 读 `android.system.OsConstants.PROT_READ`：
   原来的 OsConstants 只有 errno/O_*，没有 mmap 常量。
   已补 `PROT_*`、`MAP_*`、`F_OK/R_OK/W_OK/X_OK`。

2. 接着读 `android.system.Os.mmap(long,long,int,int,FileDescriptor,long)`：
   Mac.apk 没有这个 API。
   已在 `NativeOs` 增加 JNI `mmap`（把 bionic 的 MAP_ANONYMOUS=0x20
   翻译成 Darwin 的 MAP_ANON），并在 `Os.java` 补 AOSP 签名。

修完后：

- `Application.attachBaseContext` 的 `OverlappingFileLockException`
  仍出现但被容忍；
- `Application.onCreate` 不再因为 NoSuchField/NoSuchMethod 直接失败。

## 当前卡点：Gripper / blcrash 死锁

启动后窗口全黑、彩虹圈，主线程停在 `Unsafe.park`，CPU 99%，Activity 从未开始：

```text
InitMiuiFix: hook failed
java.lang.NoSuchFieldException: mNeedForceInvalidateAppList
...
[I] GripperTime: [DefaultDispatcher-worker-10] OnAttach:infra.hook.miui.viewrootimpl ...
[blcrash-bl_crash] bl_crash_native_log before new jstring msg
```

`Gripper` 是 B 站自研的启动 hook 框架，会 hook MIUI ViewRootImpl 等
Android 内部实现；在 Mac.apk 上反射失败后，B 站的 native crash handler
（blcrash/Bugly）尝试 `NewStringUTF`，但 VM 此时处于 safepoint/挂起状态，
形成死锁。jcmd attach 也会卡住，`kill -3` 也没有线程 dump。

这不是缺一个 API 能解决的；需要：

1. 禁用 B 站 Gripper/Bugly 启动链（app-specific smali/native patch），
   33 dex 重打包，风险高；
2. 或换一个没有 Gripper 的旧版 B 站 / Google Play 版；
3. 或直接用网页版/HD 版。

## 对 Mac.apk 的通用收益

即使 B 站没跑起来，这轮补的：

- `OsConstants.PROT_* / MAP_*`
- `NativeOs.mmap` + `Os.mmap`
- runtime 重新链接到 bundle 内 JRE（避免 Homebrew libjvm 被 AMFI SIGKILL）

都是通用能力，已合并到 `<project-root>`。

酷安 16.6.5 在新 runtime 上重新验证：主界面正常，广告报错属预期（广告库已移除）。
