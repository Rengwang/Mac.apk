# CACHE-001：启动与 APK 解压/图标提取热点优化

- 状态：DONE
- 前置：ARCH-001、PERF-001
- 证据目录：`work/upgrade/CACHE-001/`

## 目标与修复范围

基于 `PERF-001` 实测（冷启动 `android root ready` 250ms vs 热启动 37–42ms，`--extract-only` 含 `VectorDrawable` 耗时 0.66–0.73s），聚焦 `Boot.java` / `apk_unpacker.cpp` / 启动缓存的局部可落地优化；不假定缺失的 `jdk25_aot.sh` 存在，也不升级生产 JDK。

## 允许修改文件

- `runtime/java/com/macapk/loader/Boot.java`、`runtime/src/apk_unpacker.cpp`、`runtime/src/jvm_loader.cpp`
- `docs/tasks/CACHE-001.md`、`docs/UPGRADE_PLAN.md`、`PROJECT_STATE.md`

## 构建与验收命令

1. 离线构建：`MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh`
2. 回归与同条件对比：`python3 scripts/run_tests.py --group offline --strict` 及 `--extract-only` / 热启动前后耗时对比

## 验收与回退

- [x] 类加载与缓存失效语义不变，`offline` 门禁 `rc=0`，目标启动/提取阶段耗时可测下降（或以有证据的 no-go 收口）。
- **回退**：`git revert` 本卡提交并重跑离线构建。


## 实现与证据

- 基点 `fe9accc`。应用 label 是资源引用时，`WriteAppMetadata` 原先打开 base APK 两次读同一 `resources.arsc`。继续沿用当前 ZIP 读取与缓存失效机制，用图标扫描已读取的同一份 base 表解 label。配置 split 仍按原顺序参与图标挑选。对照 [Android libziparchive](https://github.com/LineageOS/android_system_libziparchive) 的 APK 读取方案，本卡复用现有读出的字节即可去除重复 I/O。
- 用 `python3 work/upgrade/CACHE-001/measure.py before|after` 运行同一 COMP-001 向量图标夹具，各一轮预热、六轮正式。热启动 `[boot-clock] android root ready` 中位数 38 → 33.5 ms，观测范围 37–40 → 33–35 ms。整体热启动 0.180 → 0.175 s，冷 `--extract-only` 0.675 → 0.660 s，整体墙钟区间重叠。样本的 meta.json（独立 root 字段除外）和 icon.png SHA-256 相同。结果见 `before.json`、`after.json`。
- `MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh` PASS，rc=0；`python3 scripts/run_tests.py --group offline --strict --out-dir work/upgrade/CACHE-001/offline` 42 PASS、0 FAIL、0 SKIP、15 NOT_RUN，rc=0。下一项 [GC-001](GC-001.md)。
