# Android Hook Lab — 产品需求规格（v0.4 Mobile）

> 更新：2026-10-20；原 Demo 路线已废弃。本说明书描述**单 Android APK、Vector 作用域授权和手机原生控制**。部分功能尚待实机验证。

## 1. 产品目标

Android Hook Lab 面向自有或明确授权的 Android 应用，通过手机直接完成：

1. 搜索并选择已安装的目标 APP。
2. 通过 Vector Framework 的服务申请目标 APP 的 Scope 授权。
3. 发现目标 APK 的 Java 类和可访问的方法签名。
4. 按明确的方法重载注册/移除 Java Hook，不修改 APK。
5. 实时观察方法调用耗时、线程、异常、参数与返回结果的隐私安全摘要。
6. 本机存储有限数量的事件，用于性能分析和软件调试。

电脑、ADB、Python、额外的 Demo App **均不是产品运行依赖**。完整原生/Native Hook 和高级追踪功能待后续规划。

## 2. M2 Mobile v0.4 功能需求

| ID | 功能 | 当前实现/验收边界 |
|---|---|---|
| F-01 | 已安装 APP 浏览与包名搜索 | 仅普通带 Launcher 的用户应用，避免系统关键进程 |
| F-02 | 应用授权与 Scope | 明确确认，调用 Vector 服务请求 Scope；未批准不能声称可 Hook |
| F-03 | 目标初始化 | 应用重新启动后模块随已授权作用域装载；展示回报状态 |
| F-04 | 类名发现 | 读取目标 APK 主 DEX / Split DEX，最多 100 个符合包名前缀的类 |
| F-05 | 方法发现 | 选中类后在目标进程反射，返回明确 Java 方法名称及参数类型 |
| F-06 | 规则管理 | 单目标最多 12 条精确重载规则；使用远程偏好做版本化同步 |
| F-07 | 动态注册/取消 | Vector libxposed HookHandle 由目标内后台管理线程注册或注销 |
| F-08 | 事件收集 | 同线程记录时长/方法/基本类型参数、字符串长度及复杂对象类型，不采集字符串原文 |
| F-09 | 数据查看 | App 内 Live 事件列表显示真实事件、异常、PID/TID、耗时 |
| F-10 | 数据约束 | 有界队列、限量类/方法列表、SQLite 最近 1500 条左右、UI 读取最近 250 条 |
| F-11 | 目标隔离 | 同时只选中一个目标；跨 UID 时通过调用者包和当前选中目标检查 |
| F-12 | 清理/恢复 | 清理会话事件；移除规则时卸载 HookHandle；关闭 Vector 可恢复原应用 |

## 3. 交互说明

- **Apps**：列出可选普通应用，先确认测试权限，再经 Vector 请求 Scope；没有授权不跳过批准流程。
- **Methods**：显示目标状态和 DEX 类名；选择类后显示该类的方法列表；每个精确重载旁有启停开关。
- **Live**：按时间查看真实 Hook 事件；支持清除本地调用历史。

规则数量超过上限、无法解析的方法、类尚未加载、动态加载模块未发现、服务不可用，都应有可理解的限制信息。

## 4. 非功能需求

- **安全**：没有任意 Root Shell/远程执行/外网监听端口；不自动将 Scope 扩展到其他应用。
- **隐私**：仅记录普通原始类型的简单值，字符串长度或对象类型；不调用目标对象的 `toString()`。
- **线程**：Hook 回调不得执行跨 UID ContentProvider 调用和数据库操作；事件排入 256 条有界队列，由后台线程传送。
- **容错**：发生框架或 IPC 故障时停止 Hook/采集，而不是无限重试阻塞目标应用。
- **兼容**：先验证 realme RMX3350 Android 13 + Magisk + Vector modern libxposed API 102。
- **性能**：在实际手机上分别测量 100/1000 次测试调用时的额外耗时、丢弃数和稳定性，才可宣称性能达标。
- **可维护性**：App UI、规则协议、Vector 引擎、数据存储各有独立模块和测试。

## 5. 明确暂不支持

- 任意 APP 全量函数监控、所有类动态发现；自动反绕过/抗检测。
- Native C/C++ Hook、ELF 符号解析、系统服务/金融/认证等高风险应用默认注入。
- 自带 Demo APK、PC ADB 控制台和桌面端 Frida 会话作为核心工作流。
- 获取私密消息、账号凭证、加密密钥或修改支付结果。
- 尚未通过真机运行的功能不能标注“设备兼容已验证”。

## 6. 验收与待改进

### v0.4 发布门槛

- Android 构建 + 单元测试 + Lint + Xposed 入口元数据检查通过；
- 未留下 Demo App 和硬编码 `dev.smallfish.hooklab.demo` 运行依赖；
- 设备上 Vector 服务连接、授权 Scope、类/方法发现、注册/取消 Hook、事件展示、关闭模块恢复均成功。

未连接设备时，只能将构建标注为 CI Verified / Device Unverified。

### 下一阶段（v0.5+）

- 类浏览支持全量分页和用户输入的精确类名查询、动态 ClassLoader。
- 真正的调用树和多线程时间线、采集采样率、丢弃统计。
- 在手机上集成可选 Native Hook 引擎，并提供明确的隐私与性能预算。
- 更专业的设备侧 Hook 规则编辑和异常分析。

## 7. 参考

- Vector: https://github.com/JingMatrix/Vector
- Modern libxposed API: https://github.com/JingMatrix/libxposed-api
- Framework service: https://github.com/libxposed/service
