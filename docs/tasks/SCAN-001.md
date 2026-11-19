# SCAN-001：实现只读 APK 与 native 依赖预检

- 状态：DONE
- 前置：CAP-001、COMP-001
- 证据目录：`work/upgrade/SCAN-001/`

## 目标与范围

独立离线工具输出 JSON/Markdown 依赖差集，接入能力台账；不嵌入安装转换热路径、不修改 APK、不自动打桩。
产出工具、最小 fixture、`docs/compat/SCANNER.md` 和一份固定样本报告。

## 步骤

1. 比较现有 `com/macapk/dex` 解析代码、Androguard 与 LIEF；选择足够的现有实现并 pin 版本/许可。
   不为新工具升级 runtime JVM 或第三方二进制。实现命令在文档写清输入、输出与依赖安装位置。
2. 处理 APK/split、多 DEX、manifest feature/min/target SDK、类/方法/字段描述符、继承和接口。
   先扣除 APK 自带实现，再对照平台库存；区分 public/hidden/AndroidX/应用库。
3. 对各 ABI 的 ELF 收集 DT_NEEDED、导入/导出和版本信息；解析 app 自带库依赖闭包，
   区分平台缺符号、弱符号、动态查找与扫描不确定性；不得运行 `.so` 初始化器。
4. 按 API/符号汇总静态引用数量、受影响的独立 APK 与对应场景，给 GAPLOG-001 留下可实跑核对的优先线索。静态引用数量与实际调用频率分别记录。
   反射字符串仅作提示，下载 DEX、加密代码、dlsym/RegisterNatives 等明确标 blind spot。
5. 未知依赖只产生预警，不据静态结果禁止安装。损坏 ZIP/DEX/ELF、路径穿越条目及资源上限有错误结果。

## 验收

- [x] 自建微型 fixture 覆盖：继承方法存在、重载确实缺失、app 自带库满足符号、弱符号、动态加载未知、损坏输入。
- [x] 同输入/hash/台账版本输出确定；不联网下载代码、不执行 APK、不改源文件。
- [x] COMP-001 固定样本有报告；每条缺口可定位到输入与 capability ID，无法判定的保持 unknown。
- [x] 扫描时间/峰值内存报告实际值，不承诺毫秒级；工具错误非零，缺口报告与工具崩溃分开。

## 记录与回退

- 选型与依赖：`scripts/scan_apk.py` 采用纯 Python 3.9+ 标准库（主机 `Python 3.9.6`，PSF 许可），复用仓库内 `scripts/cap_registry.py` `.class` 解析与确定性 JSON 写出，零第三方依赖、零网络访问、零 APK/DEX/`.so` 执行。详细对比与许可说明见 `docs/compat/SCANNER.md`。
- 合成验收测试：`python3 debug/ScanApkTest.py -v`（exit `0`，7/7 PASS，覆盖继承方法、重载描述符缺失、`DT_NEEDED` 闭包、`STB_WEAK`、`dlsym`/`RegisterNatives`/反射/内嵌 DEX blind spot、split/multi-DEX、确定性及 ZIP-slip/大小上限/损坏输入非零退出码）。
- `COMP-001` 样本构造与双次确定性扫描：
  - 构建：`python3 debug/fixtures/build_comp_fixtures.py --out work/upgrade/SCAN-001/fixtures --framework-apk thirdparty/framework-res/framework-res-api36.apk`（exit `0`，产物位于 gitignored `work/upgrade/SCAN-001/fixtures/`；共享 worktree 若未检出 `thirdparty/` 可指向主工作区同名路径）。
  - 第 1 次扫描（产出 `docs/compat/scan-report.json`、`docs/compat/SCAN_REPORT.md`、`work/upgrade/SCAN-001/reports/*.json`、`work/upgrade/SCAN-001/telemetry.json`）：exit `0`，实测耗时 `1194.87 ms`，峰值 RSS `274.28 MiB`（`287604736` bytes）。
  - 第 2 次扫描（输出至 `work/upgrade/SCAN-001/run2/`）：exit `0`，实测耗时 `1164.44 ms`，峰值 RSS `274.36 MiB`；两次运行 `scan-report.json` 与 3 份单应用 raw JSON 字节完全一致（`cmp -s` 全部匹配，`docs/compat/scan-report.json` SHA-256 为 `fc5ecf8521172899132169f129f16fae36d93dea20f5fbd0c1e9e961a22b2e80`）。
- 交付文件：`scripts/scan_apk.py`、`debug/ScanApkTest.py`、`docs/compat/SCANNER.md`、`docs/compat/SCAN_REPORT.md`、`docs/compat/scan-report.json`、`docs/tasks/SCAN-001.md`。
- 回退方法：删除上述新增文件并恢复 `docs/tasks/SCAN-001.md` 即可完全回退，不触碰 `runtime/`、`launcher/` 或安装器热路径。
