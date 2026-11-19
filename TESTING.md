# 测试与构建入口

统一分类测试入口已建立，见 [scripts/run_tests.py](scripts/run_tests.py)。
以下命令均在当前开发 worktree 根目录运行。

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
构建日志、依赖版本、源码 SHA 与 build ID 记入当前构建基线任务，见 [BASE-002](docs/tasks/BASE-002.md)。
需要构建 framework-res 时，先核对 `BUILDING.md` 中 API 36 资源配方和本机 aapt2；
Git 忽略的资源在新 worktree 中可能不存在，不要默认为“代码编译失败”。

纯 Java 快速编译的现有入口为 `debug/build_java_only.sh`。
它会改写本工作区 framework JAR，并使用现有锁，不能与本工作区性能测量并行执行。

## 统一测试入口（`scripts/run_tests.py`）

使用零第三方依赖的 Python 3 标准库运行器统一调度 `debug/` 与本地 bundle 检查：

```bash
python3 scripts/run_tests.py --list          # 仅列出全部 54 个注册条目、分类与前置，不执行
python3 scripts/run_tests.py                 # 默认运行 offline 基础组（39 项）
python3 scripts/run_tests.py --group offline # 同上：bare_jvm + jvm_launch_sensitive + native_unit + worktree_bundle
python3 scripts/run_tests.py --self-test     # 在独立临时目录验证失败传播、缺条件非零、SKIP 识别与 flaky 记录
```

### 组分类与范围

| 组名 | 是否默认 `offline` | 内容与前置条件 |
|---|---|---|
| `bare_jvm` | 是（17 项） | 纯 Java / 无头 Java2D 测试；强制先 `javac` 编译再 `java -Djava.awt.headless=true` 运行并设 `MACAPK_LOG_LEVEL=7` |
| `jvm_launch_sensitive` | 是（11 项） | 对启动方式、模块或隔离环境敏感的 Java 测试：含 `OwnerFrameBarrierTest`（先编译后运行以避开 source-launcher `IllegalAccessError`）、`BitmapShaderSamplingTest`（`-XX:-DoEscapeAnalysis` + `jdk.management`）、`BrokerServiceStartupTest` / `ProcessBrokerRoutingTest`（独立临时目录与 `HOME`）、`SurfaceZOrderRequestTest`（`MACAPK_LOG_LEVEL=6`）等 |
| `native_unit` | 是（8 项） | 离线 C++ / ObjC++ / Swift / Python 单测（`clang++`、`swiftc`、`native_window_lifetime_test.py`） |
| `worktree_bundle` | 是（3 项） | 对本 worktree `build/Mac.apk.app` 执行 `entitlements_check.sh`、`audit_bundle_deps.sh`、`codesign --verify --deep --strict`；不触及 `/Applications/Mac.apk.app` 或共享 runtime |
| `gpu_pixel` | 否（7 项） | Metal / Skia / ANGLE IOSurface 像素与 JNI 门禁（`AgslRenderingSmoke`、`debug/perf/*.mm`、`SharedGlFramesJniTest`）；`perf/*.mm` 缺 `EGL_ANGLE_iosurface_client_buffer` 返回 2 时标记 `SKIP` |
| `codegen_check` | 否（2 项） | `gles_sig_roundtrip`（`reconstruct_sigs.py --verify`，会写入 `/tmp/glsig`）；`gen_framework_attrs_check`（需传入 `--attrs-xml <final> <staging>` 才可离线检查，否则默认路径会访问网络） |
| `runtime_fixture` | 否（3 项） | `RemoteServiceProcessTest`（需完整 `macapk-runtime` + `macapk-system` broker 进程隔离，不能用裸 JVM 替代）；`DrawableIdentityAndTintTest` 与 `UpstreamPackageFeaturesTest`（需 `--apk <path>`） |
| `installed_bundle` | 否（3 项） | `dist_parity_check`（需 `build/dist/Mac.apk.app`）；`installed_staleness_check` 与 `installed_entitlements_check`（需显式 `--allow-installed`，仅用于本机安装态核验） |

`debug/` 中其余纯工具脚本、构建/代码生成器与采样器（`applock.sh`、`build_java_only.sh`、`jdk25_select.sh`、`extract_fw_res.py`、`gen_system_fonts.py`、`gles_gen/gen*.py`、`gles_gen/gles1_native_needed.txt`、`perf/profile_process.py`）不作为测试用例执行。

### 常用参数、退出码与输出格式

- `--group <name>`（可多次或逗号分隔）、`--test <id>`、`--retries N`（对 flaky 测试保留 `attempts`、`first_failure` 与 `retry_count` 并标为 `FLAKY_PASS`，绝不抹去首轮失败）、`--strict`（任何选中组出现 `SKIP` 即非零退出）、`--apk <path>`、`--attrs-xml <final> <staging>`、`--allow-installed`、`--out-dir <dir>`。
- 默认输出目录 `work/upgrade/TEST-001/`：生成机器可读 `report.json`（含 `git_head`、`javac_version`、`runtime_build_id`、`bundle_build_id`、每项命令/退出码/耗时/状态/重试历史）、`report.xml`（JUnit XML）、`summary.txt` 及 `logs/<test_id>.log`。
- 退出码：`0` = 所选组全部通过且无必需前置缺失；`1` = 至少一项 `FAIL`（编译/断言/运行时/超时）；`2` = 无 `FAIL` 但必需组（默认 `offline` 各组，或 `--strict` 下任意选中组）存在因前置缺失导致的 `SKIP`。

## CI 本地门禁与远端入口（`scripts/ci_gate.sh`）

本地开发与候选远端 CI 共用同一条门禁入口 [scripts/ci_gate.sh](scripts/ci_gate.sh)：

```bash
# 本地完整离线门禁（默认 --group offline，共 39 项；自动启用 --strict）
bash scripts/ci_gate.sh

# 跨平台纯 JVM 子集门禁（28 项：bare_jvm + jvm_launch_sensitive，无需 macOS / Swift / bundle）
bash scripts/ci_gate.sh --group bare_jvm,jvm_launch_sensitive

# 可选参数：--runtime-build-dir / --app-bundle / --jdk-home / --out-dir / --test / --retries
```

### 门禁组分类、产物前置与输出格式

- **必过离线基础组（`offline`，39 项）**：`bare_jvm`（17）+ `jvm_launch_sensitive`（11）+ `native_unit`（8）+ `worktree_bundle`（3）。
- **跨平台纯 JVM 组（28 项）**：`bare_jvm` + `jvm_launch_sensitive`，只需 JDK 21 与 `MACAPK_JAVA_NOLOCK=1 bash debug/build_java_only.sh` 生成的 JAR，可在 Linux（如 `ubuntu-latest`）或 macOS 运行。
- **macOS Apple Silicon 原生与包检查（11 项）**：`native_unit` + `worktree_bundle`，依赖 macOS 26.4+（Apple Silicon）、Xcode CLT（`clang++`、`swiftc`、`codesign`）与本地 `build/Mac.apk.app`。
- **不纳入默认离线门禁的组（15 项）**：`gpu_pixel`（7，需 Metal/ANGLE IOSurface）、`codegen_check`（2，需 `--attrs-xml` 或写 `/tmp/glsig`）、`runtime_fixture`（3，需完整 `macapk-runtime` + broker 或 `--apk`）、`installed_bundle`（3，需 `build/dist/Mac.apk.app` 或 `--allow-installed`）。
- **前置校验与输出格式**：
  - `scripts/ci_gate.sh` 在调用 `python3 scripts/run_tests.py --strict` 前先校验 `runtime/build/lib/android-framework.jar`、`macapk-loader.jar`（选中 `offline` / `worktree_bundle` / `all` 时同时校验 `runtime/build/bin/macapk-runtime`）并打印 `runtime/build/build-id.txt`。
  - 默认输出目录为 `work/upgrade/CI-001/gate/`（可通过 `--out-dir` 覆盖），产出与 `run_tests.py` 一致的 `report.json`、`report.xml`（JUnit XML）、`summary.txt` 及 `logs/<test_id>.log`。
  - 退出码与传播规则：`0` = 所选组全部 `PASS`；`1` = 至少一项 `FAIL`（编译或断言失败）；`2` = 缺少必需 `runtime/build` 产物或 `--strict` 下出现任何 `SKIP`（不可吞错或伪装全绿）。

### 候选远端 workflow 与 CI-002 入口（`.github/workflows/runtime-offline.yml`）

- **配置位置**：[.github/workflows/runtime-offline.yml](.github/workflows/runtime-offline.yml)（已通过本地静态 YAML 与安全边界审计，**远端执行状态当前为 `NOT_RUN`**）。
- **最小权限与安全边界**：顶层 `permissions: contents: read`，全部第三方 action 固定到 40 位 commit SHA；禁用 `pull_request_target`、禁用 `self-hosted`（不把用户日常 Mac、`/Applications/Mac.apk.app` 或个人 APK/签名密钥暴露给外部 PR），不自动签名、发布或上传 APK。已有 [.github/workflows/cla.yml](.github/workflows/cla.yml) 语义保持不变。
- **GitHub 官方 runner 核查与分层（2026-11-14）**：
  - `jvm-offline`（`ubuntu-latest`）：在 `pull_request`（`main`）与 `workflow_dispatch` 上执行 `MACAPK_JAVA_NOLOCK=1 bash debug/build_java_only.sh` 与 `bash scripts/ci_gate.sh --group bare_jvm,jvm_launch_sensitive`。
  - `macos-arm64-offline`（`macos-26`）：`macos-26` 提供 Apple Silicon `arm64`（3 vCPU M1 / 7 GB RAM / 14 GB SSD）、macOS 26.6.1（满足 `BUILDING.md` 与 `scripts/check-requirements.sh` 的 macOS 26.4+ `custom-x18-abi-toggle` 硬下限；`macos-15` 低于 26.4，`macos-14` 已于 2026-11 退役）、Xcode CLT 26.6 与 CMake 4.4；公有仓库免费，私有仓库按 10x 分钟倍率 / `$0.062/min` 计费；需 `brew install capstone apktool openjdk@21` 并以 `MACAPK_NO_BUNDLE_SYNC=1 MACAPK_NO_FW_BUILD=1 MACAPK_ALLOW_TABLE_ONLY_FW=1` 构建后运行 `bash scripts/ci_gate.sh --group offline`。该 job 显式受限于 `workflow_dispatch`（`inputs.run_macos_arm64 == true`）。
  - `gpu_pixel` 不在默认远端离线 workflow 中（需 GPU 硬件加速，`macos-26-xlarge` 为 Team/Enterprise 付费 Larger Runner，即使公有仓库也按 `$0.102/min` 计费）。
- **后续远端激活入口**：待具备适用 runner 及远端执行授权后，按 [CI-002](docs/tasks/CI-002.md) 在安全测试分支验证成功与失败传播并记录远端 job URL 与 artifact。

## 当前测试的分类线索

| 类型 | 已有样例 | 说明 |
|---|---|---|
| 裸 JVM 候选 | `ParcelWireTest.java`、`ActivityManagerTasksTest.java`、`SurfaceTextureRoutingTest.java` | 已纳入 `bare_jvm` 组由 `scripts/run_tests.py` 统一先编译后运行 |
| JVM 启动方式敏感 | `OwnerFrameBarrierTest.java` | 必须先 `javac` 编译再按普通 classpath 运行；单文件源码启动会因 `MemoryClassLoader` 包隔离抛 `IllegalAccessError` |
| 原生小测试 | `SurfaceMetalLayerAlphaTest.mm`、`native_window_lifetime_test.py` | 已纳入 `native_unit` 组；不是完整 GPU/安装态测试 |
| 真实 runtime / fixture | `DrawableIdentityAndTintTest.java`、`RemoteServiceProcessTest.java` | 已纳入 `runtime_fixture` 组；`RemoteServiceProcessTest` 不能用裸 JVM 代替 |
| GPU / 像素 | `AgslRenderingSmoke.java`、`debug/perf/*.mm` | 已纳入 `gpu_pixel` 组；明确硬件、动态库、与 exit 2 `UNSUPPORTED:` 映射 |
| 安装态 | `staleness.sh`、`dist_parity.sh` | 已纳入 `installed_bundle` 组，默认不运行，需显式选择并按需传 `--allow-installed` |

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
