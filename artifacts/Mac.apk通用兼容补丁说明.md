# Mac.apk 通用兼容补丁（源码级）

源码：https://github.com/kksimp/Mac.apk（AGPL-3.0）
本地：`/tmp/macapk-src`，commit `25bc68b`，版本 1.0.2303
构建：`runtime/scripts/build.sh` 成功
产物：
- `runtime/build/lib/android-framework.jar`
- `runtime/build/lib/macapk-loader.jar`
- `runtime/build/bin/macapk-runtime`

## 已实现的 8 个通用补丁

1. **Activity.macapkAttach 资源回退**
   `Application.getResources()` 为空时退回 `getBaseContext().getResources()`；
   并把解析到的 Resources 固定在一个 `ContextThemeWrapper` 里交给
   `AppCompatActivity.attachBaseContext()`，避免 Tinker 的 fence 第一次返回
   Resources、第二次返回 null 把 AppCompat 打崩。

2. **Application 主 Resources 缓存**
   `macapkInit()` 第一次拿到的 Resources/Assets 存为 `macapkPrimaryResources` /
   `macapkPrimaryAssets`；之后即使被 Tinker/插件置空，`getResources()` 和
   `AppBaseContext` 仍能返回最初的应用资源。

3. **ContextWrapper 资源兜底**
   任何 Context 链返回 null 时，回退到 `Application.macapkResources()`，
   避免 AppCompat/第三方库在 `getConfiguration()` 上 NPE。

4. **ActivityThread.sCurrentActivityThread**
   增加 AOSP 兼容的静态字段别名，Tinker 的
   `AppUtils.hookStartActivity` 反射不再 `NoSuchFieldException`。

5. **dex2jvm 空方法安全桩**
   DexVMP/易盾抽空的方法翻译后 code_length 为 0。原实现会把整个类丢掉，
   导致 `NoClassDefFoundError`。现在：
   - 普通方法：按返回类型发 `RETURN / ARETURN / IRETURN / LRETURN / FRETURN / DRETURN`
   - `<init>`：`aload_0; invokespecial <super>.<init>()V; return`
   - `<clinit>`：`RETURN`
   类不再被丢弃，最多是某个被抽空的方法返回默认值。

6. **隐藏系统代理**
   Mac.apk 启动时默认清空 `http.proxyHost` / `https.proxyHost` /
   `socksProxyHost` 等，并把默认 `ProxySelector` 设为 `NO_PROXY`，
   避免中国 App 的“检测到代理/请关闭HTTP代理”误判。
   需要保留系统代理可设 `MACAPK_KEEP_SYSTEM_PROXY=1`。

7. **PackageManager 原始签名覆盖（可选）**
   支持 `~/Library/Application Support/Mac.apk/signature-overrides/<pkg>.bin`
   （DER 证书），在 `GET_SIGNATURES` / `GET_SIGNING_CERTIFICATES` 时返回原始
   发布者证书。用于只通过 PackageManager 校验签名的 SDK。

8. **保留 AOSP 行为**
   所有补丁只在“原本会 null/崩溃”的路径生效；正常路径不变。

## 全局安装情况

已备份原 runtime 的两个 jar 到 `/tmp/runtime_backup/`，并把新构建的
`android-framework.jar` 与 `macapk-loader.jar` 复制到：

`~/Library/Application Support/Mac.apk/runtime/lib/`

没有强制替换 `/Applications/Mac.apk.app` 本体（源码 HEAD 与安装版 commit
不一致，`MACAPK_FORCE_INSTALL=1` 风险较高）。替换后
`/Applications/公考雷达.app`（v24，带 libsign 补丁）可正常启动到 MainActivity。

## 仍然修不了的

- **native 直接读 APK 的签名校验**：`libsign.so` 的 `checkSignature` 不经过
  PackageManager，而是直接读 APK 签名块。即使 PackageManager 返回原始证书，
  `hashcode error` 仍然出现（实测 16 次），页面继续“走丢了”。
  这类只能 patch .so（如 v24 做的 `checkSignature -> mov w0,#1; ret`）。
- **商业壳本身**：易盾把真实 DEX 加密/按类解密，Mac.apk 没有 ART，无法执行壳。
- **服务端二次签名/设备完整性校验**：本地伪造签名也过不了。

## 酷安（CoolApk 16.6.5）现状

- 安装后启动：`startActivity: class not found: com.coolapk.market.view.main.MainActivity`
- 壳特征：`com.netease.nis.wrapper.MyApplication`、`libnesec.so`、
  `classes.dex` 94 MB 但只有 30 个类
- 内存 dump：`base.vdex` 只有壳 DEX；匿名区只找到 4 个插件 DEX
  （4.1 MB / 2.9 MB / 182 KB / 284 B），没有完整业务 DEX
- 结论：这是更新版易盾的**按类解密/DexVMP**，没有完整 DEX 可 dump。
  通用 Mac.apk 补丁无法解决，需要 ART 级类 dump（FART/Youpk 类工具）或官方无壳包。

## 对另一份建议的核对

1. **空方法安全桩**：正确，已按返回类型实现（第 5 条）。
2. **PackageManager 伪造原厂签名**：思路部分正确，但 `libsign.so` 不查
   PackageManager，实测无效；只能覆盖“走 PackageManager 校验”的 SDK。
3. **MACAPK_FORCE_INSTALL=1**：可用，但源码 HEAD 与已安装 commit 不一致；
   本次选择只替换 runtime jar 并备份，没有强推整个 app bundle。

## 十一、纠正：libsign.so 确实走 PackageManager（2026-10-26）

另一份分析是对的，我之前的结论错了：

- `libsign.so` 的 `checkSignature` 通过 JNI 调
  `Context.getPackageManager().getPackageInfo(pkg, 64)`，取
  `signatures[0].hashCode()`；**不直接读 APK 文件**。
- 我第一次把 `macapkSignatureOverride` 加在了 `registryPackageInfo()`
  （只对“非当前包”的查询生效）；而 App 查询自己走的是
  `getPackageInfo()` 主干第 342 行的 `readApkSignatures()`，
  完全没有经过 override，所以仍然拿到重打包证书。
- 正确位置：`readApkSignatures()` 开头。现在如果
  `~/Library/Application Support/Mac.apk/signature-overrides/<pkg>.bin`
  存在，直接返回原始证书并缓存。

### 验证结果（干净 v2，未 patch libsign.so）

在 `/tmp/gkld_v2_signed.apk` + 新 runtime 上：

- `hashcode error`：**0 次**（原来 16 次）
- “页面走丢了”：**0 次**
- “请关闭HTTP代理”：**0 次**
- 流程正常走到 `MainActivity`

说明：**框架层已经能全自动过这类 PackageManager 签名校验，不再需要手工 patch .so。**
v24 里的 `libsign.so` patch 只是当时定位错误时的有效 workaround，现在不是必需的。

### 通用使用方式

对每个“原包有壳、需要重签名”的 App：

```bash
./macapk-signature-override.sh 原始未重签的.apk [包名]
```

它从原 APK 的 `META-INF/*.RSA|DSA|EC` 提取 X.509 DER，写到
`~/Library/Application Support/Mac.apk/signature-overrides/<pkg>.bin`。
之后 Mac.apk 的 `getPackageInfo(GET_SIGNATURES/GET_SIGNING_CERTIFICATES)`
就会返回原始发布者证书，native/Java 的签名哈希校验都能通过。

注意：
- 只对“通过 PackageManager 查询签名”的校验有效；
- 如果某个 .so 自己 open/mmap APK 读签名块，仍需 patch .so；
- v2-only（没有 v1 证书）的 APK 需要额外的 apksig 提取逻辑，脚本目前只处理 v1。

## 十二、CoolApk 复测（2026-10-26）

- 修正 dumper（移除 Anonymous-DexFile 过滤、允许 checksum 不匹配、修 panic）后，
  在 CoolApk 主进程 dump 到 16 个 DEX（约 102 MB）；
- 但 16 个 DEX 全部是 Pangle/字节等插件，**0 个 `Lcom/coolapk/` 类**，
  没有 MainActivity；
- frida-dexdump attach/spawn 均被易盾杀脚本；
- BlackDex 3.2 显示 `Unpack failed`（环境检测）。

详见：[CoolApk脱壳实验记录.md](CoolApk脱壳实验记录.md)

## 十三、酷安之后沉淀到 Mac.apk 的通用能力（2026-10-26）

### 已落地

1. **真实 isTaskRoot 任务栈**
   - 原 `Activity.isTaskRoot()` 恒为 true，导致所有 `BaseActivity` 子页面
     在 `onCreate` 里被误判为“桌面重复启动”，直接 `finish()`。
   - 现在 `ActivityHost.performLaunch` 用 `stack.isEmpty()` 标记根 Activity /
     子 Activity，详情页 `isTaskRoot()==false`。

2. **finish-in-onCreate 图层回退**
   - 顶层 Activity 在 `onCreate` 中 `finish()` 时，除了跳过 onStart/onResume，
     还会主动 `restoreTop()` 到最近的未 finishing Activity，避免 Cocoa 层
     停在已销毁页面的白色背景上。

3. **通用脱壳工具 `tools/macapk-dump/macapk-dump.py`**
   - 不依赖 `dex\n` 字符串；
   - 识别 Yidun 的 4 字节偏移（页首为 `037\0`，真实头在页面前 4 字节）；
   - 也支持 endian tag `0x12345678` 扫描；
   - 自动补 `dex\n`、重算 SHA-1/adler32；
   - 自动读取 `/proc/<pid>/maps` 并导出所有匿名 rw-p DEX 区域。

4. 之前的通用补丁继续有效：
   - 原厂签名覆盖（`readApkSignatures` self path）；
   - 代理隐藏；
   - Tinker/Context 资源回退；
   - DexVMP 空方法安全桩。

### 不能简单“全自动”的部分

- **native 生命周期方法**：
  `FeedDetailActivityV8.onCreate` 这类 native 方法，直接补 `super.onCreate`
  只能保证生命周期不崩，页面仍然没数据；每个 App 需要补它自己的
  `extra_feed_id` 解析 + 内部 loader（如酷安的 `ྈ()`）。
  这和“通用框架”无关，属于业务层胶水。

- **System.exit(10) 一刀切拦截**：
  现有 `MacApkProcessExit` 已经做进程归属隔离；但把顶层 App 的 exit(10)
  全部吞掉会留下“无 Activity 的僵尸窗口”。要做也只能做 opt-in
  （如 `MACAPK_DISABLE_GUEST_EXIT=1`），不建议默认开启。

结论：

> 以后同类 App 的“脱壳、签名、代理、Tinker 资源、Activity 退栈白屏”
> 可以基本自动化；但被 VMP 抽空的 native 页面仍需要一个 App 一个 loader 补丁。
