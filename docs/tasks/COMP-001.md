# COMP-001：建立兼容性样本矩阵

- 状态：DONE（无头离线实测通过，交互 GUI 与完整 broker 路径显式标为 `NOT_RUN` / `SKIP`）
- 前置：BASE-002、TEST-001、SEC-001
- 证据目录：`work/upgrade/COMP-001/`

## 目标与范围

把“某应用能打开”变成可复现的场景结果，为后续改造保留参照。
允许新增矩阵/复现说明与通用小 fixture 计划；不修改 APK、不补生产 API。

## 步骤

1. 先盘点已有合法可用、用户愿意测试的 APK/开源 fixture，不批量下载陌生 APK。
2. 按普通 UI/资源、视频叠层、WebView、不同 native/游戏渲染栈选择少量代表性样本；缺类别明确登记。
3. 每例记录 APK 版本与 SHA-256、runtime 标识、数据初态、操作、预期、实际和结果证据。
4. 重要步骤覆盖启动、输入、窗口缩放/前后台、播放/暂停/seek 等适用行为。
5. 使用独立测试数据，不读取或覆盖正式账号数据；需初始化快照时明确来源和使用范围。
6. 对疑似契约缺口记录 Android API 版本和参考行为，能用通用最小复现时优先使用。
7. 缺可分发 APK 时优先自建小 fixture 或取官方/开源固定版本及许可，避免让用户逐个挑应用。
   核实构建工具后创建普通资源/UI、多 Surface/媒体、native/IPC 三类中的可行组合；
   Compose、Flutter、Unity、WebView 等未覆盖栈保留明确缺口，不能声称三例代表所有 APK。
8. 产出 `docs/compat/SCENARIOS.md` 和机器可读场景清单，记录 license/source/hash、
   target/presented SDK、独立数据目录、启动/清理入口、预期断言与 harness。

## 验收

- [x] 至少三个不同类型的可用场景可按步骤复现；缺失类型标明未覆盖（见 [SCENARIOS.md](../compat/SCENARIOS.md) 与 [scenarios.json](../compat/scenarios.json)）。
- [x] 支持/部分支持/失败/未测试分开，不继承上游宣传数字作为本分支实测。
- [x] APK、账号缓存和敏感截图未进入 Git（生成产物均位于 gitignored `work/upgrade/COMP-001/`）。
- [x] 未将 SurfaceStackLab 等尚未创建的建议列为已通过测试。

## 回退与记录

- **执行记录（2026-11-14）**：
  - 新增离线合成构建脚本 [build_comp_fixtures.py](../../debug/fixtures/build_comp_fixtures.py)、说明 [README.md](../../debug/fixtures/README.md) 与三类合成源码（`debug/fixtures/{ui_resources,surface_media,service_ipc_abi,native}`），产出 [SCENARIOS.md](../compat/SCENARIOS.md) 与 [scenarios.json](../compat/scenarios.json)。
  - `python3 debug/fixtures/build_comp_fixtures.py --out work/upgrade/COMP-001/fixtures`（exit 0）生成三类 APK 与 `arm64-v8a` `ET_DYN` `libcompprobe.so`；使用独立 `--android-root work/upgrade/COMP-001/roots/<id>` 与未改写生产/共享安装。
  - 无窗口验证通过：`--peek-manifest`、`--gles-scan`、`--peek-window-policy`、`--extract-only`（全 3 例 exit 0，`targetSdk=36 -> presenting 36`）；`scripts/run_tests.py --group runtime_fixture --apk work/upgrade/COMP-001/fixtures/comp-ui-resources.apk`（`PASS=2 FAIL=0 SKIP=1 [RemoteServiceProcessTest]` exit 0）；`UpstreamPackageFeaturesTest` 对其余 2 例各 `PASS=1`（exit 0）；`bare_jvm` 7 项关联单测 `PASS=7`（exit 0）；无窗口自检 `MACAPK_AMEDIA_SELFTEST=1` + `MACAPK_MEDIA_SELFTEST=1`（`12288 frames rms=2776 PASS`）与 `MACAPK_VIDEO_IMAGE_SELFTEST=1`（`3 frames motionDelta=8.74 PASS`）均 exit 0。
  - 当前未连接 Sidecar 且未启动交互 Cocoa 窗口，三场景的实时 GUI 显示、`libcompprobe.so` 运行期加载与 `:worker` broker 绑定均诚实标为 `NOT_RUN` / `SKIP`；Compose / Flutter / Unity / WebView / ARM32 / 真实用户 APK 明确标为未覆盖。
- **回退方式**：仅新增记录与测试源码，保留独立测试数据，不改正式安装；如需回退可删除 `debug/fixtures/` 与 `docs/compat/` 提交并清理 `work/upgrade/COMP-001/`。
- **交接与下一项**：
  - 完成任务：`COMP-001`
  - 变更文件：`debug/fixtures/**`、`docs/compat/SCENARIOS.md`、`docs/compat/scenarios.json`、`docs/tasks/COMP-001.md`、`docs/UPGRADE_PLAN.md`、`PROJECT_STATE.md`
  - 下一步：合并已并行推进的 `CAP-001` 后可解锁 `SCAN-001`，同时 `PERF-001` 与 `CI-001` 已处于 `READY`。
