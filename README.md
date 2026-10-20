# Android Hook Lab

**手机端 Android Java 方法分析器**，基于 Root 环境中的 Vector / modern libxposed API 102。无需电脑、ADB、Frida Server 或单独的测试 APK。

> 当前开发版本：**0.4.0-mobile**。代码已具备独立手机端应用选择、Vector Scope 授权请求、目标 DEX 类发现、反射方法发现、动态精确 Hook 注册/移除、脱敏调用摘要、耗时与本地事件列表。**必须经过 CI 和 RMX3350 真机测试后才可判定可用**。

## 手机上直接使用

1. 安装 Hook Lab APK（仅一个安装包）。
2. 确保 Magisk/Zygisk/Vector 已正常启用，并在 Vector 中开启本模块。
3. 在 **Apps** 页选择自有或获授权测试的已安装普通应用，确认授权，并批准 Vector 的 Scope 请求。
4. 打开或重新启动目标应用，返回 Hook Lab 的 **Methods** 页，浏览目标 DEX 类列表。
5. 点击类查看它的实际 Java 方法签名，开启要观察的精确重载 Hook。规则通过框架远程偏好同步至目标进程，无需重新打包 APK。
6. 在目标 App 操作相关功能，切换到 **Live** 查看真实调用方法、参数安全摘要、返回类型/安全摘要、异常状态、PID 和耗时。关闭方法开关将请求撤销对应的 HookHandle。

## 核心架构

```text
Hook Lab Android App
    ├─ Apps：安装包选择 + 授权确认
    ├─ Vector Service：申请/读取 Scope
    ├─ Remote Preferences：发送精确方法规则
    └─ TraceProvider：读取本地调用记录
             ▲
             │ 经目标包 UID 与当前选中包名核验
             │
目标进程（已批准的 Vector Scope）
    └─ HookLabModule
         ├─ DexFile：扫描自身 APK 的类名（上限100）
         ├─ 反射：取得请求类的方法签名
         ├─ 规则后台轮询 → HookHandle 注册/移除（上限12）
         └─ 方法回调 → 有界队列 → ContentProvider
```

框架远程偏好为核心控制通道，Hook 回调不做磁盘/跨进程 IO。类与方法元数据上限、事件缓冲与数据库保留上限均设置了保护。

## 当前功能边界

- 只列出普通**带 Launcher 入口**且非系统应用的目标；不会自动 Hook 其他应用。
- 通过模块 Scope 授权是必需的；已批准的作用域可能仍需**重启目标进程**才能载入 Hook 模块。
- 仅保证列出 APK DEX 中能读取的**部分类名**；多 DEX、Split APK 等会尽量合并扫描；动态加载/加固的类、原生函数和其它包命名空间不保证能发现。
- 仅支持**精确 Java 方法**，不包括 Native C/C++、构造方法、`<clinit>` 或自动全量 Hook；同一目标最多 12 条规则。方法注册失败会在 Vector 日志中留下原因。
- **默认不采集字符串原文、对象字段、账号 Token 或私密内容**：字符串仅记录长度，复杂对象仅记录类型，简单数字/布尔/字符显示原值；所有数据只保存在本地。
- 目标本身与 Hook 代码运行在同一 UID，目标可能伪造自己发送的诊断事件；因此数据是调试信息而不是经过密码学认证的审计记录。
- 只对当前一个选中目标主动采集；切换目标会使旧目标的 Hook 规则停止生效（依赖框架远程偏好正常同步）。
- 尚未完成 ARM64 Android 13 / realme UI / Vector 真机验收及 30 分钟稳定性测量。

## 构建

GitHub Actions：[Android Hook Lab Mobile](.github/workflows/android.yml)。
成功后下载 `hook-lab-v0.4-mobile`，其中仅含 **Android Hook Lab APK**。

本地需要 Java 17、Android SDK 35、Gradle 8.9：

```bash
gradle :libs:protocol:test :apps:android:testDebugUnitTest \
       :apps:android:assembleDebug :apps:android:lintDebug
```

## 文档

- [spec.md](spec.md)：需求与验收基线
- [design.md](design.md)：层次、IPC、Hook 生命周期及安全设计
- [docs/mobile-v04.md](docs/mobile-v04.md)：手机上实际测试与故障排查

## 依赖与许可

依赖 [Vector](https://github.com/JingMatrix/Vector)、[libxposed API](https://github.com/JingMatrix/libxposed-api)、[libxposed service](https://github.com/libxposed/service)，需要用户自行安装兼容的 Vector 框架。项目许可证在依赖分发审核后确定。禁止未经授权测试、隐蔽收集个人或支付信息。
