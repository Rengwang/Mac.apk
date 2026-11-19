# ORACLE-001：建立同版本 Android 行为差分

- 状态：DONE（Robolectric 四族；Android 真机与模拟器 NOT_RUN）
- 前置：CONTRACT-001
- 激活条件：有可用且匹配待测 API 的 Android 参考环境
- 证据目录：`work/upgrade/ORACLE-001/`

## 执行步骤

1. 只读盘点现有 SDK、adb、镜像/专用测试设备与版本，不能擅自给用户手机安装测试包。
   无可用环境先记录；安装大型镜像或新工具链须按已有授权与资源范围处理。
2. 同一最小用例源码分别产 Android fixture 与 Mac.apk fixture；记录 API、build fingerprint、
   target/min SDK、ABI、locale、时钟/显示条件、app/hash 和两端版本。
3. 捕获返回/异常/事件序列，规范化随机 ID 和时间只限明确非契约字段。
   不删除有意义的差异；Parcel 字节格式等跨版本非稳定内容不强求全部镜像相同。
4. 首轮比较 CONTRACT-001 确定的优先用例。时序判断用事件关系和合理时钟容差。
5. 对每项差异定位 AOSP 依据，区分环境差异、当前缺陷、测试错误；保留原始与规范化结果。

## 验收与回退

- [x] 同用例至少四族的两端实测与 comparator 自检有证据；注入差异确实检出。
- [x] 真机/模拟器/Robolectric 证据分开；没有目标 API 环境的部分仍 NOT_RUN。
- [x] 未为了差分改生产实现或认证/账号状态。产出 `docs/compat/ANDROID_ORACLE.md`。

2026-11-14 通过隔离 Maven 与 Robolectric 4.16.1 实测 SDK 36。Android 侧 Maven test PASS，rc=0；Mac.apk 裸 JVM 参照 PASS，rc=0；四族对照 PASS，rc=0；注入差异被检出，预期 rc=1。见 [ANDROID_ORACLE.md](../compat/ANDROID_ORACLE.md)。未使用已存在的 MuMu 用户实例，也未修改应用或设备数据。

回退仅移除本卡专用 fixture 或参考环境数据。
