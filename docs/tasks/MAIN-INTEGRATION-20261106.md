# 2026-11-06 主线整合记录

- 范围：用户要求先把前一聊天的已有工作更新到主线。
- 基线：整合时本地 `main` 与 fetch 后的 `fork/main` 一致；发布前的安全基线为 `0149df3`。
- 来源：`codex/fix/gfx-rotation-20261105` 的 `f663380` 及其保留的未提交源码、测试和文档。
- 证据：开发 worktree 的 `work/upgrade/MAIN-INTEGRATION-20261106/`，不提交构建产物或私人运行日志。

## 纳入的工作

1. manifest 声明的 sibling process 默认使用独立进程 broker，不依赖 Google GMS 开关；
   启动请求使用 4 worker / 64 队列，异步 bind completion 避免占满 Binder receiver，
   保留旧协议，覆盖取消、generation、迟到结果和 broker 死亡。LaunchAgent 注册验证
   真实 launchd job，并保留 ad-hoc 本机构建回退。
2. Surface media-overlay 请求覆盖之前的 on-top 请求；显式 free-window 模式保留窗口
   尺寸；Surface display 记录跟随已提交尺寸。Canvas 裁切缓存原型与其测试保留在
   `MACAPK_BITMAPSHADER_CROPPED_CACHE=1` 后，默认关闭。
3. 帧/layout 子阶段、视频几何和透明像素诊断；faultdump 使用安全读取，保留默认关闭的
   `MACAPK_RECOVER_4G_RETURN_ALIAS` 窄恢复实验。整合检查另外修正诊断寄存器遍历：
   Darwin 的 x29/x30 从独立 fp/lr 字段读取，避免越界访问只含 x0..x28 的 `__x`。

## 本轮验证

以下检查均在开发 worktree 完成；所有最终命令退出码 0。

| 检查 | 结果 | 命令或入口 / 日志 |
|---|---|---|
| Java 全量构建 | PASS | `debug/build_java_only.sh`；`java-build.log` |
| native runtime | PASS | `cmake --build runtime/build/native --target macapk-runtime -j8`；`native-build.log` |
| native shim（最终源码） | PASS | `cmake --build runtime/translator/build --target macapk-bionic-shim -j8`；`shim-build-final.log` |
| manifest 路由与两种 opt-out | PASS | `ProcessBrokerRoutingTest`；`routing*.log` |
| 32 冷绑定、16 receiver、旧协议、4+64 队列拒绝 | PASS | `BrokerServiceStartupTest`；`broker-startup.log` |
| completion / 取消 / generation / broker 死亡 | PASS | `RemoteBindCompletionTest`；`remote-bind.log` |
| z-order、Surface 路由、视频目标隔离、attached display | PASS | 四份对应 Java 测试；`zorder.log` / `surface-routing.log` / `video-isolation.log` / `attached-display.log` |
| BitmapShader 默认门控 | PASS | `ContainedBitmapTextureTest`：80 项；`bitmap-texture.log` |
| BitmapShader 像素与采样分配 | PASS | `BitmapShaderSamplingTest`：1,297,757 次像素对照，500,000 次采样分配 0 bytes；`bitmap-sampling.log` |
| LaunchAgent plist / fingerprint | PASS | `swiftc -framework ServiceManagement launcher/ProcessBrokerLaunchAgent.swift debug/ProcessBrokerLaunchAgentTest.swift` 后执行；`launchagent.log` |
| 返回别名接受/拒绝门控 | PASS | `clang++ -std=c++17 -Wall -Wextra -Werror debug/GuestReturnAliasTest.cpp` 后执行；`alias.log` |
| 透明 layer 与 presentation copy | PASS | `clang++ -std=c++17 -framework Foundation -framework QuartzCore debug/SurfaceMetalLayerAlphaTest.mm` 后执行；`alpha.log` |
| launcher 打包、签名、依赖审计 | PASS | `MACAPK_NO_BUNDLE_SYNC=1 scripts/build_launcher.sh`；`launcher-build.log` |
| entitlements 与独立签名复核 | PASS | `debug/entitlements_check.sh build/Mac.apk.app/Contents/Resources/macapk-runtime`、`codesign --verify --deep --strict build/Mac.apk.app`；对应日志 |
| staged diff 与新增文档链接 | PASS | `git diff --cached --check`、本地链接检查 |
| GUI、安装态、性能前后测量 | NOT_RUN | 本轮不启动 GUI，不替换正式应用或共享运行时 |

Java 测试使用 `/opt/homebrew/opt/openjdk@21/bin/{javac,java}`，classpath 为证据目录的
`classes`、`runtime/build/lib/android-framework.jar` 和 `runtime/build/lib/macapk-loader.jar`；
使用 `-Djava.awt.headless=true` 及独立 `-Duser.home`，日志级别 7（z-order 为 6）。
每条测试的完整参数和退出码在 `results.json`。透明 layer 首次命令误加 `-fobjc-arc`
导致显式 `release` 编译失败（退出 1），移除错误参数后编译和断言均通过，未修改测试。
实验裁切缓存没有本轮通过结论，已知舍入差异仍保留在 GFX-001。

## 验收边界与回退

这次整合是源码检查点，不把 GFX-001 或 DANMAKU-001 标为完成。移动弹幕、缩放无白带
是前一聊天的局部现场证据；旧 Surface key rect、全屏往返、自动连播黑屏、真实冷启动、
音频欠载与切换后至少 30 秒连续性仍需继续验收。没有改变 GC、缓存上限或默认图形后端。
之前安装的诊断版本、环境变量和显示偏好，本轮均未操作。

如需撤回，按本次主线整合提交逆序 `git revert`，并按需撤回 `f663380` 的帧诊断提交；
常规功能回退使用 revert；后续用户明确要求的对话记录历史清理单独执行。源码回退不自动替换安装版本。
后续从两张任务卡列明的未验收项继续，不用离线 PASS 代替真实音画验收。

## 2026-11-06 用户要求更新本地正式版（已完成）

后续核对发现，前面的单独 native/shim 编译虽然通过，但 launcher 从旧的
`runtime/build/bin` 暂存文件打包，不能证明生成的 `.app` 包含最新原生代码。
此缺口已向用户说明；用户随后明确要求更新本机 Mac.apk。此次使用完整构建入口，
不以单独 CMake 编译代替暂存，也不只比较可能沿用的 build-id。

- 源码：干净的 `476cd05843cd7722ec8b46c160c71b302682ac55`，与当时本地 `main` 一致。
- 新构建：`261106.0819-476cd05`，版本号仍为 `1.0.2377`；没有下载或升级第三方依赖。
- 构建：`MACAPK_NO_BUNDLE_SYNC=1 debug/applock.sh --timeout 60 runtime/scripts/build.sh`，
  然后 `MACAPK_NO_BUNDLE_SYNC=1 scripts/build_launcher.sh`，均 PASS / exit 0。
- 安装前备份：`work/upgrade/LOCAL-INSTALL-20261106/backup/`，含旧正式 app、共享 runtime、
  launchd 配置、安装身份及 4 个启动器副本和路径索引；使用 APFS clone 保留可回退副本。
- 安装：在 machine/install 两层锁下顺序执行 `scripts/promote_app.sh`、
  `scripts/stage_shared_runtime.sh /Applications/Mac.apk.app`、正式版
  `MacApk --ensure-process-broker`。全部 PASS / exit 0；process broker 刷新为当前 helper。
- 正式应用：`/Applications/Mac.apk.app`；共享运行时：
  `/Users/xrdemac/Library/Application Support/Mac.apk/runtime`。

| 安装核验 | 结果 |
|---|---|
| runtime/shim/translator/Binder 编译→暂存→包 UUID | PASS |
| 安装后 UUID 与上述编译产物一致 | PASS |
| 正式版与共享 runtime 原生文件逐字节比较 | PASS |
| 新 Java JAR 与正式/共享副本逐字节比较 | PASS |
| 构建/正式/共享 build-id 一致 | PASS |
| 正式包签名、正式/共享 x18 等 entitlements | PASS |
| 正式/共享 runtime `--help` 命令行启动 | PASS |
| installed app stubs `--dry-run` | PASS：0 待更新，4 已当前 |
| headless `runtime-status --json` | PASS：`installed=true`、`stale=false` |
| process broker launchd job 与 fingerprint | PASS：正式 helper 与新 build-id |
| GUI、视频、弹幕、全屏与音频连续性 | NOT_RUN，本次安装不新增功能验收结论 |

新身份为 `JAVA_ID=24653ab436234457`、`JARS_ID=fa6a6e5c8847cb1a`、
`NATIVE_ID=2140bedbbc35c18c`。runtime UUID 为
`56585F3C-6BC1-36E0-B3DB-2EF8E886507D`，shim UUID 为
`1E614610-43BA-3B56-8A5E-49ACA3867D0A`。完整证据在
`work/upgrade/LOCAL-INSTALL-20261106/{runtime-build.log,launcher-build.log,install.log,installed-verified.json}`。

包含全部当前产品源码及默认关闭的实验实现，不把“包含实现”解释为自动启用实验。
GFX-001 / DANMAKU-001 状态保持 PENDING；本轮没有打开测试窗口或改动显示配置。
本机安装不代表 GitHub 已同步；先前 HTTPS/SSH 推送认证失败的状态未在本轮处理。

## 2026-11-06 GitHub 发布范围与历史清理

用户明确要求把最新改动和两项未完成实测的实验上传，并从 Git 历史中清除之前上传的
聊天记录。发布以安全基线 `0149df3` 为父提交，保留最新产品文件树，整理为一个明确的
升级检查点；包含记录的单独历史提交不进入发布后的分支祖先链。

覆盖远端 `main` 与 `codex/fix/gfx-rotation-20261105` 两个受影响分支；检查时无 tag 或
pull-request ref 指向该记录。分支更新使用预期旧 SHA 校验，避免覆盖期间的新提交。
由于本机 Git HTTPS/SSH 未认证，通过已连接且有写权限的 GitHub 工具创建 tree/commit。
发布前后比较完整 Git tree，并检查目标路径不在当前树或可达历史。

两项实验实现与测试均随源码发布：

- `MACAPK_BITMAPSHADER_CROPPED_CACHE=1`：裁切 BitmapShader 缓存，默认关闭，已知舍入差异
  尚未关闭，不能声称实验像素矩阵通过。
- `MACAPK_RECOVER_4G_RETURN_ALIAS=1`：4 GiB 返回地址窄恢复，默认关闭，指令/地址门控单测
  已通过，真实故障恢复和长时稳定性未验收。

本次只整理已有源码和文档，没有新增产品行为；构建及测试证据沿用上文安装记录。
分支历史清理不保证 GitHub 已移除旧 SHA 页面、缓存或其他用户的副本；这些残留需要
GitHub Support 或副本持有者另行处理，不能以强推成功代替物理删除证明。

### 发布后核验与缓存清除请求

- 发布检查点：`7a9b6d9996a5067afe79733032ba6bcbfe679a7b`；父提交为安全基线，tree
  `4ea4ccdb928637261844fb0c2789f7fa7e338a2a` 与本地准备后的完整文件树完全相同。
  两个远端分支均更新成功，本地 `main` 与开发分支已跟随清理后的历史；相关 checkpoint
  ref 与 reflog 中的旧记录引用也已清理，没有改动其他开发 worktree 的文件。
- PASS：从 GitHub 重新完整裸克隆后，目标路径的全分支历史条目为 0，记录 blob 未被获取；
  发布后的产品目录（runtime、launcher、scripts、debug）与此前已构建和安装的源码
  内容一致。提交标识因历史整理改变，不代表安装版新增或丢失产品实现。
- PASS：用户授权使用其 GitHub 账号提交缓存清除请求后，在已登录的 Rengwang 账号下，
  通过 GitHub Support 的 Clear cached views Virtual Agent 提供原提交 URL、无 PR 引用
  情况及个人对话记录清除原因。页面明确确认工单已创建，将在缓存清除后通知用户；
  未显示工单编号或专属链接。没有要求删除仓库或当前源码。
- PENDING：GitHub 缓存/旧 SHA 页面由 Support 后续处理。创建工单不等于已完成缓存清除，
  不宣称 GitHub 已物理删除所有残留副本。
- 证据：`work/upgrade/GITHUB-PUBLISH-20261106/{files.json,clean-upgrade.patch,audit.json,clone-audit.log}`；
  工单回执仅保存在当前聊天的 outputs，未把历史记录正文重新上传到仓库。

本次产品代码无新变更，复用前述构建/离线回归证据；新增文档执行 diff 与链接检查。
