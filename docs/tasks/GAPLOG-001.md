# GAPLOG-001：有界缺口诊断与证据优先级

- 状态：DONE
- 前置：CAP-001、SCAN-001、TEST-001、SEC-001
- 证据目录：`work/upgrade/GAPLOG-001/`

## 目标与范围

实跑 COMP-001 的代表性场景，用已有日志和最小用例确认 SCAN-001 的线索，同时记录扫描难以发现的行为错误。
已有日志无法解释关键失败时，在相关边界补上默认关闭的有界诊断。
产出 `docs/compat/GAPS.md`，列出已确认缺口、证据和优先级，同时保存待复核线索。

## 步骤

1. 结合扫描报告重跑对应 APK 场景和最小用例，记录实际失败、受影响功能和复跑命令。
   日志不足时阅读当前 loader/shim/Binder 错误路径，选择可避免递归分配/锁重入的必要采集点。
   信号处理器中不能直接写复杂 JSON 或获取普通锁；无安全点时记录该边界不覆盖。
2. 若新增诊断，事件只含 capability/signature、层级、计数、版本、粗粒度场景 ID、时间源与结果类别。
   本地应用映射可用于去重，提交报告用 fixture ID；不收 URL/token/用户文本/全路径/完整对象内容。
3. 若新增诊断，给缓冲条数、单条字节、队列容量、日志轮转、去重键和 drop 计数设明确上界；
   压测重复事件、满队列、退出和多线程；日志故障不能变更生产调用结果。
4. 与 SCAN 报告合并，但区分 observed 和 predicted，按 gap ID 去重。
   优先严重性（崩溃/数据错/主要功能/次要）、独立场景面、契约可信度，再比较收益与成本。
   高频一次失败循环不得淹没低频严重缺陷，不能按成本乘法奖励昂贵重构。
5. 若新增诊断，用相同 fixture 比较关闭/开启开销，预算在开工记录，超预算时缩小采集范围。

## 验收

- [x] 缺口报告有代表性 APK 的实跑记录。只有静态引用的条目标为待复核。已实现但行为错误的接口也有复跑入口。
- [x] 若新增诊断，开/关时返回值、异常和顺序相同，过载/退出与隐私字段检查 PASS，缓冲有界且开销实测有证据。（经实测确认现有 `MACAPK_CODEC_OBS=1`、`FeatureGatesTest`、`scan_apk.py` 与 `GapProbe` 已足以解释全部故障点，故保持 `runtime/` 零额外插桩。）
- [x] 缺口列表含来源、严重性、独立场景数、置信度、候选测试和任务归属；不足 50 项不补齐数字。
- [x] 不改默认能力，不按 APK 名称生成生产分支。

## 回退与记录

- 未在 `runtime/` 新增诊断代码；若回退文档输出，直接 `git revert` 本次提交移除 [../../docs/compat/GAPS.md](../../docs/compat/GAPS.md) 与 [../../docs/compat/gaps.json](../../docs/compat/gaps.json)。
- 已实测 COMP-001 三套 APK（CLI/`--extract-only`、`runtime_fixture`、`AMEDIA_SELFTEST`/`MEDIA_SELFTEST`/`VIDEO_IMAGE_SELFTEST`+`MACAPK_CODEC_OBS=1`）、无 broker 条件 `RemoteServiceProcessTest`（`rc=1`, `workerPid == clientPid`）、`GapProbe`（`ServiceManager.getServiceOrThrow=null`、`Binder.getCallingUidOrThrow=10000`、`SystemProperties.set` 无回显、`MediaCodec.createEncoderByType=IOException`、`MediaExtractor` 静默忽略非 `file://` URI 与非法 track index）、`validate_gates.py`、`FeatureGatesTest.py`（`PASS=23 FAIL=0 NOT_RUN=4`）与 `scan_apk.py`（`rc=0`）。
- 去重汇总为 **14** 项缺口（`observed=10`、`predicted=4`），按 `Severity × Breadth × Confidence` 排序并分别指派 `CONTRACT-001` / `ARCH-001` / `GATE-002`，详见 [../../docs/compat/GAPS.md](../../docs/compat/GAPS.md)。
