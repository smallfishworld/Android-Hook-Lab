# M1 Android Console / UI 实现说明

版本：0.2.0-m1-preview（2026-10-19）

## 目标与设计语言

原 M0 页面的长文本和原生按钮堆叠替换为深色专业调试面板。

- 背景 `#0A1119`、卡片 `#151F2B`、强调色薄荷绿 `#67E8C0`、性能指标紫蓝色 `#A3B7FF`、异常橙红 `#FFAA91`。
- 页眉统一产品身份、预览版本及**真实事件收取状态**；收到历史事件仅显示 DATA RECEIVED，**不宣称 Vector 当前在线**。
- 栏目：Live（实时调用）/ Methods（已配置测试方法）/ System（诊断和数据管理）。
- 卡片化显示 PID/TID、方法名、参数、结果、耗时、异常。点击卡片展开完整细节。
- 流式列表使用 Compose LazyColumn，限定最多读取本地最新 250 条，以减少重组开销。

## 本轮真实功能

| 页面 | 功能 | 实现情况 |
|---|---|---|
| Live | Demo App 启动 | 调用安装包 Launch Intent，未安装时提示 |
| Live | 方法名/PID 搜索 | **本地过滤**，非类枚举 |
| Live | All/Brightness/Validation/Exceptions | 真实事件过滤 |
| Live | 事件详情 | 从 ContentProvider 的事件快照生成详情 |
| Live | 暂停/恢复显示 | 暂停 UI 轮询；**不停止 Vector Hook 采集** |
| Live | 最新事件数、异常数、P95 | 基于最新 250 条实际采集事件统计 |
| Methods | 两种已 Hook 测试方法与调用次数 | 真实 Demo 白名单；点击进入相应事件筛选 |
| System | Android 版本/ABI/诊断 | 不推测 Root/Vector 状态 |
| System | 手动刷新 | 从 Provider 异步重新读取 |
| System | JSONL 导出 | SAF（系统文件选择器），最多 250 条，默认不联网 |
| System | 删除本地记录 | 先确认；只清理 Hook Lab 本地事件，不停用 Hook |

## 兼容性与验收

- Android API 29+，优先 Android 13 / realme RMX3350。
- Gradle 8.9 + AGP 8.7.3 + Kotlin/Compose 插件 2.0.21；通过 Actions 编译、单元测试和 APK 元数据检查后才能称为 CI 已通过。
- 需同时安装来自**相同 GitHub Actions Job** 的 `android-debug.apk` 与 `demo-app-debug.apk`，确保签名匹配。
- 在 Vector 中仅开启 `dev.smallfish.hooklab.demo` 作用域，并强制停止后重开 Demo。
- 正常调用和受控异常均应生成可筛选事件，点击应展开详情；重启 Hook Lab 后 SQLite 事件仍在；导出 JSONL 可以逐行解析。
- 任何 Hook、IPC、绘制性能、设备兼容声明均需 RMX3350 真机测试，不以 CI 通过替代真机验收。

## 尚未实现

完整动态类/方法发现、任意授权应用 Hook、规则保存与动态热插拔、实际暂停 Hook、Frida/Native 引擎、调用树/火焰图、实时远程桌面控制、完整会话生命周期。

该预览是 M0 → M1 的 **真实可用 UI/本地分析增量**，不是 M1 全部需求完成。
