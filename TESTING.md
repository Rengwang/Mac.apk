# 测试与构建入口

本文件是现有能力索引。统一测试脚本尚未实现，由 TEST-001 负责。
以下命令均在当前开发 worktree 根目录运行；FND-001 没有运行这些产品检查。

## 先识别环境与副作用

```bash
pwd
git status --short --branch
git rev-parse HEAD
scripts/check-requirements.sh
```

依赖检查默认只报告，不加 `--install`。缺失项先记录；不要顺便全量升级系统依赖。
具体要求参考 `BUILDING.md` 与当前脚本。JDK 必须核对实际版本，不能只信 Homebrew 目录名称。

`scripts/rebuild_and_run.sh --no-promote` 不替换正式 app 或共享 runtime，
但会清理本地构建产物、打开测试 GUI；primary checkout 中还可能结束现有 Mac.apk 进程。
它不适合被当作只读检查或默认 CI 入口。

## 无需安装的分步构建

先确认当前脚本仍支持这些环境变量，再按顺序执行：

```bash
MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh
MACAPK_NO_BUNDLE_SYNC=1 scripts/build_launcher.sh
```

输出位于本 worktree 的 `runtime/build/` 与 `build/Mac.apk.app`。
不得附加 `--promote`。构建日志、依赖版本、源码 SHA 与 build ID 由 BASE-001 保存。
需要构建 framework-res 时，先核对 `BUILDING.md` 中 API 36 资源配方和本机 aapt2；
Git 忽略的资源在新 worktree 中可能不存在，不要默认为“代码编译失败”。

纯 Java 快速编译的现有入口为 `debug/build_java_only.sh`。
它会改写本工作区 framework JAR，并使用现有锁，不能与本工作区性能测量并行执行。

## 当前测试的分类线索

| 类型 | 已有样例 | 说明 |
|---|---|---|
| 裸 JVM 候选 | `ParcelWireTest.java`、`ActivityManagerTasksTest.java`、`SurfaceTextureRoutingTest.java` | 逐个核实 classpath、模块参数、fixture 与断言；文件存在不代表可直接运行 |
| JVM 启动方式敏感 | `OwnerFrameBarrierTest.java` | 手册要求先 javac 编译，再按普通 classpath 运行，避免 source-file launcher 类加载差异 |
| 原生小测试 | `SurfaceMetalLayerAlphaTest.mm`、`native_window_lifetime_test.py` | 需要合适的 macOS 编译器/SDK；不是完整 GPU/安装态测试 |
| 真实 runtime / fixture | `DrawableIdentityAndTintTest.java`、`RemoteServiceProcessTest.java`、`WindowDamageLifecycleTest.java` | 不能靠裸 JVM 代替 JNI、独立 worker 或真实 APK 宿主 |
| GPU / 像素 | `AgslRenderingSmoke.java`、`VideoPixelsTest.cpp` | 明确硬件、动态库、fixture、像素容差与启动方式 |
| 安装态 | Dock、控制面板、Finder 启动 | 应单独计划，确认实际执行的共享 runtime 版本 |

分类依据见旧手册第 7 节；TEST-001 应根据实跑结果补齐完整清单。

## 本地 bundle 检查

构建完成后显式传入本地目标，避免默认检查 `/Applications` 的旧版本：

```bash
scripts/audit_bundle_deps.sh build/Mac.apk.app
debug/entitlements_check.sh build/Mac.apk.app/Contents/Resources/macapk-runtime
```

`debug/dist_parity.sh` 比较开发包与发布包。目标缺失时会打印 SKIP 并退出 0；
必须同时检查输出和实际执行情况，不能只看退出码。
`debug/staleness.sh` 涉及本机安装及数据布局，执行前核对目标与测试范围。

## 统一结果规则

- PASS：本轮在注明的环境中执行了目标断言且成功。
- FAIL：目标检查或构建失败；保留首个有效错误和复现命令。
- SKIP：因条件不满足而跳过；写明原因和补测条件。
- NOT_RUN：本轮未执行。
- 对任务卡列为必须的检查，SKIP / NOT_RUN 都不能满足完成条件。
- 已知失败必须有复现记录和独立任务，不能静默转成 PASS。
- 测试日志被管道写入文件时，应保留真实测试命令退出码，避免只得到 `tee` 的成功码。

## 文档任务检查

检查 Markdown 本地链接、引用的真实路径、任务号唯一性、依赖关系和变更范围。
已跟踪 diff 用 `git diff --check`；未跟踪新增文件另查内容，不能以空 diff 代替检查。
文档任务无需启动 APK 或完整产品构建。
