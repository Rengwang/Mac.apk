# 后续 AI 连续执行协议

本协议用于用户要求“继续升级/按路线推进”后的执行。它不启动后台任务，也不授权跳过验证。
从 [项目状态](../PROJECT_STATE.md) 查看当前任务，以 [任务表](UPGRADE_PLAN.md) 和任务卡核对依赖与验收条件。

## 1. 每次开始

依次读 `AGENTS.md`、`PROJECT_STATE.md` 顶部、`docs/UPGRADE_PLAN.md`、本卡和相关专题。
运行并记录 `pwd`、`git rev-parse --show-toplevel`、`git status --short --branch`、
`git rev-parse HEAD`、`git worktree list`。确认本卡依赖的证据适用于当前 HEAD；
历史 DONE 不能替代本轮构建身份核验。

开始技术实现前先查 GitHub 同类实现；优先复用当前 repo 中可用的测试/解析器/后端，
再选择外部组件或自行实现，记录选择理由。不要反复检索已明确记录且未发生漂移的结论。

在任务分支/独立 worktree 开发，分支前缀 `codex/`。只读评审不修改源码。
创建新 worktree 前确认路线图已经在其基点中；未提交文档不能凭空出现在另一工作区。
保留用户已有变更。不同 checkout 不代表隔离运行进程、安装包或共享 runtime。

## 2. 自行选择下一项

1. **前置基建与首轮修复冲刺已收口**：P0/P1/P3 基建与评估卡（`BASE-002` 至 `ARCH-001` / `AOSP-EVAL-001`）及首轮修复序列（`PILOT-001` → `GC-001`、`FORK-MERGE`、`HOTAPI-001/002`）已全部 `DONE`；**不再新增独立的盘点、扫描器、自检或 ADR 前置卡**，直接推进 `runtime/` 与 `launcher/` 的真实代码修复（“先实现功能/修复，有问题再修”）。
2. **当前可执行队列**：以 [UPGRADE_PLAN.md](UPGRADE_PLAN.md) 的实时状态为准，优先推进尚处 `READY` 的离线兼容性任务（如 `HOTAPI-003`）或解锁后的交互式 GUI / Sidecar 实机验收（`GFX-001`、`DANMAKU-001`、`VIDEO-PWR-001`）；完成一张后保存本地检查点并继续，不要求用户逐卡分派。
3. 遇到明确外部阻碍（如无 GUI/Sidecar、缺真机或未授权远端）时，完成本地离线构建与回归，将受限检查如实标为 `NOT_RUN` 并继续下一项无阻碍修复。

## 3. 默认动作和外部条件

| 情况 | 默认处理 |
|---|---|
| 文档、只读源码/GitHub 核查、可逆本地修改与测试 | 直接执行，不重复询问是否建分支/是否写文件 |
| 公共测试源码、fixture | 优先 repo 现有、自建最小例子及官方/开源来源；记录版本/hash/许可；独立目录。不批量拉陌生 APK，不用用户登录态 |
| 缺 Android SDK/参考镜像 | ORACLE-001 保持 DEFERRED；直接基于现有离线契约与单测推进代码修复 |
| 缺第三方构建依赖 | 查当前脚本和最小依赖方案；能在独立目录解决且不升级主版本的按任务范围执行，系统级安装/重大升级先写明具体必要性与回退 |
| 缺 JDK 25/AOT 训练脚本 | CACHE-001 直接聚焦现有代码可落地的启动/解压/缓存优化，不假定私有脚本存在，不自动换生产 JVM |
| 云 CI 未授权/无适用 runner | 使用本地 `scripts/ci_gate.sh` / `run_tests.py`；CI-002 保留 DEFERRED |
| GUI / Sidecar | 离线开发期不强求长时 GUI 采样或 ABBA 矩阵；需要窗口/副屏复核的项在连接后集中验证，未跑如实标 `NOT_RUN` |
| 需要账号、私人数据、不可代操作的系统授权 | 明确缺少什么，只请求该信息/动作；不复制日常账号缓存，不伪造通过。继续独立工作 |
| 发布、推送、远端合并 | 开发验证期间一律使用 `MACAPK_NO_BUNDLE_SYNC=1`，保持 `/Applications/Mac.apk.app` 与共享 runtime 不被触碰；仅在交付供用户测试的完整版本时按既有授权备份并同步 |

## 4. 每卡务实闭环（5 步法）

1. **明确目标（Target gap）**：锁定本卡要修的具体缺口与允许修改的 `runtime/` / `launcher/` 文件范围（不写每卡独立 ADR）。
2. **最小通用修复（Minimal universal fix）**：直接实施通用修复；严禁按应用包名、业务类名或资源 ID 写特例分支，不吞异常、不加任意 `sleep` 或忙轮询。
3. **离线构建（Offline rebuild）**：使用 `MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh`（及需要时的 `MACAPK_NO_BUNDLE_SYNC=1 scripts/build_launcher.sh`）编译。
4. **针对性回归与离线冒烟（Targeted regression + offline smoke）**：运行本卡针对性测试与 `python3 scripts/run_tests.py --group offline --strict`（免除每卡强制突变/故障注入 harness 自检、30 分钟长跑 soak 及改动前多进程 Sidecar ABBA 矩阵）。
5. **1+1 极简提交与推进（Commit & next fix）**：报告真实命令与退出码，只手改 **`PROJECT_STATE.md` 顶部摘要** + **本卡/`docs/UPGRADE_PLAN.md` 状态**（不再维护重复的 `handoff.md`），经 `scripts/check_doc_sync.py`（及 `.githooks/pre-commit` / `ci_gate.sh`）校验通过后创建本地提交并继续下一张卡。禁止用 `--no-verify` 或 `MACAPK_SKIP_DOC_CHECK=1` 绕过门禁。

源码回退仅撤销本卡独立提交；禁止为了回退清理别人改动。

## 5. 状态和失败处理

任务状态使用 TODO、READY、IN_PROGRESS、BLOCKED、DONE、DEFERRED。
DEFERRED 用于未激活候选/独立外部阶段；PENDING 是保留的旧任务状态，不擅自转 DONE。
候选评估的 no-go 可以关闭“评估”任务，但必须写明没有启用/没有收益，不能关闭未达标的“实现”任务。

检查结果单独记录 PASS / FAIL / SKIP / NOT_RUN、命令、退出码、输入/构建 ID 与证据位置。
不拿 exit 0 的 SKIP 当 PASS。必需检查 SKIP/NOT_RUN 不满足完成条件。
既有 FAIL 可作为待修缺口记录，但必须与本轮新回归分开；不自动吞掉、无限重试或更新 golden。
若测试工具需要“已知差异基线”，由 CONTRACT-001 明确版本/hash/缺口 ID，禁止新增失败自动入白名单。

## 6. 切换工具时交接

同一台 Mac 上轮流工作时，在新工具打开前一位 Agent 实际修改的本地目录。从 Git 根目录开始新会话，发一句「从 PROJECT_STATE.md 接手 Mac.apk 升级」。项目入口及各工具的加载方式见 [快速入口](UPGRADE_START_HERE.md)。

1. **离场**：在任务卡末尾更新最近一次交接，记录分支和代码检查点。写清已确认事实、未完成项和接手后第一条可执行命令。只有相应检查完成后才更新任务状态。切换发生在任务中途时，优先把属于本任务的可安全提交变更保存为本地检查点，注明尚未验收。未提交文件另列路径和原因。
2. **接手**：核对实际目录、分支、HEAD 和工作区差异，读取最近的交接。若在另一个 worktree，先取得前一位的检查点。需要同一份未提交改动或被忽略的证据时，打开原目录或从原目录查阅指定文件。根据交接继续第一个未完成步骤。
3. **证据**：任务卡记录简短脱敏摘要和退出码。`work/upgrade/`、本地框架资源和构建物留在对应工作树。别的 worktree 要使用它们时核对来源、版本或哈希。只有远端分支的 Agent 需要先获得相应提交，单凭 SHA 无法取得尚在这台 Mac 上的提交。

切换同一任务的 Agent 时，前一位先结束写入和测试，再交给下一位。额度耗尽来不及记录时，接手者先用 `git worktree list` 找到实际工作树，查看 `git status --short --branch`、`git log -5 --oneline`、`git diff`、`git diff --cached` 和现有日志。将无法确认的测试标为 NOT_RUN，然后从能复跑的步骤继续。

## 7. 同时工作的分工

默认由一位 Agent 负责当前任务卡的实现和状态更新。其他 Agent 可同时只读核查 AOSP、审查差异或检查验收方案，输出附文件位置、可复现步骤和所审查的 SHA。由实现者将有用结果写入任务卡。

需要并行实施不同任务时，先按依赖关系分派独立任务，每位 Agent 使用自己的分支和 worktree。认领时在任务卡记录负责人、范围、基础提交和状态，由指定的整合者维护共同分支上的任务表与项目状态。整合者核对差异、合入范围及测试结果，复跑涉及交叉改动的检查。需要前一任务产物的 Agent 等对应提交可用后再开始依赖步骤。

同一台 Mac 上的正式安装、共享 runtime、GUI 验收和性能采样由一位 Agent 在相应时段操作。测试进程与用户安装的边界见 [测试说明](../TESTING.md)。

## 8. 任务卡的交接格式

```text
任务 / 状态 / 当前负责人：
实际 worktree / 分支 / 基础与检查点 SHA：
尚未提交的本任务文件及原因：
完成的行为与变更文件：
检查（PASS/FAIL/SKIP/NOT_RUN、命令、退出码）：
证据位置与脱敏摘要（标明是否仅在本机可用）：
剩余项及精确解除条件：
源码 / 构建物 / 安装态回退：
下一任务卡与第一条动作：
```

若交接说明与代码在同一个提交中，写「见该任务的最新本地提交」，并在交接回复给出实际 SHA。

需要用户介入时只问确实缺少的条件；普通代码设计、测试选型和优先级由 AI 在本协议内完成。
