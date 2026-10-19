# Android Hook Lab

基于 **Vector / modern libxposed** 的 Android 动态 Hook 调试工作台，面向自有应用及明确授权的测试目标。

> 当前版本：**0.2.0 M1 Preview**。已经有 Compose Android UI、Demo 方法 Hook、实时事件、统计和 JSONL 导出。**尚未完成动态任意应用 Hook、Frida、Native 分析及真实设备验收**。

## 立即体验

打开 GitHub [Android CI 工作流](./.github/workflows/android.yml)，从**成功的最新构建**下载 `hook-lab-m0-debug-apks`（目前 Artifact 名字延续 M0）：

1. 从同一个 CI Artifact 安装 `android-debug.apk`（Hook Lab）和 `demo-app-debug.apk`（测试目标）。它们必须具有同一套调试签名。
2. 在 Vector 管理器启用 **Android Hook Lab** 模块，Scope 仅选 **Hook Lab Demo**。
3. 强制停止并重新打开 Demo，执行亮度/正常校验/异常或 100 次调用测试。
4. 返回 Hook Lab **Live** 页，检查事件是否真实出现；点击卡片查看完整参数与耗时。
5. 在 **Methods** 页按方法筛选，在 **System** 页导出 JSONL 或清理事件。

> GitHub Actions 构建成功 ≠ Root/Vector 实机注入已经验证。真机测试请参考 [M0 手动测试清单](docs/m0-test-plan.md) 和 [M1 UI 验收](docs/m1-ui.md)。

## M1 Preview 功能

| 模块 | 已实现 |
|---|---|
| Android UI | Kotlin + Jetpack Compose，暗色调试工作台；Live / Methods / System |
| 目标范围 | 固定为项目内的签名 Demo；不允许任意应用静默注入 |
| Vector Hook | `adjustBrightness(float,int)`、`validateLevel(int)`；读取安全数字参数、结果和异常类型 |
| 实时事件 | 有界队列 → ContentProvider → SQLite；每 1.2 秒读取最近事件 |
| 统计分析 | 最新 250 条记录的计数、异常计数、P95 调用耗时 |
| 调用查看 | 方法/PID 搜索、事件类型过滤、单条调用详情、暂停 UI 刷新 |
| 数据管理 | 存储配额策略、本机清除、系统文件选择器 JSONL 导出 |
| 测试目标 | 亮度滑块、正常/异常校验、100 次后台批量调用 |
| CI | Android 编译、协议与 Demo 单元测试、现代 Xposed 元数据检查及双 APK Artifact |

### 明确的限制

- “Pause View”仅暂停**界面刷新**，不停止目标进程中已经注册的 Hook。
- Methods 页是**两个已验证设计目标的方法清单**，不是动态枚举整个 Android 系统。
- 显示 DATA RECEIVED 只表示存在历史 Hook 事件，**并不保证 Vector 目前在线**。
- P95 基于最近 250 条调用快照，不是全历史数据。
- 当前尚未实现 Frida、Native、调用堆栈、调用链、任意授权 App 的方法扫描和热插拔 Hook 规则。
- 需要与 Android 13 真机上的 Vector 版本兼容；未完成设备实测前不承诺成功率。

## 设计文档

- [spec.md — 需求规格](spec.md)
- [design.md — 技术架构](design.md)
- [docs/m1-ui.md — UI 与交互验收](docs/m1-ui.md)
- [docs/m0-test-plan.md — 真机调试指南](docs/m0-test-plan.md)

## 技术与工程

```text
apps/android          Compose 控制台 + EventProvider/SQLite
engines/vector        libxposed API 102 Hook + bounded async relay
libs/protocol         白名单事件模型 + P95 统计及单元测试
samples/demo-app      Java 受控测试应用
core/                 领域/用例预留
runtime/collector     扩展采集器预留
libs/storage          存储层演进预留
libs/analysis         分析层演进预留
tools/desktop         桌面分析器预留
```

技术基线：JDK 17、Gradle 8.9、Android Gradle Plugin 8.7.3、Kotlin 2.0.21、Jetpack Compose、Android SDK 35。

本地构建（需要正确安装 Android SDK、JDK 17 及 Gradle）：

```bash
gradle :libs:protocol:test :samples:demo-app:testDebugUnitTest \
  :apps:android:testDebugUnitTest :apps:android:assembleDebug :samples:demo-app:assembleDebug
```

## 安全与隐私

所有 M1 事件来源限定于项目自带 Demo，经签名权限和来源包 UID 双重验证，且仅包含安全数字字段、方法名称与异常**类型**。默认不录制私人消息、账号信息、密钥或任意对象内容，也不上传到服务器。不要对没有测试授权的应用使用 Hook。许可证将在完成依赖许可审查后确定。
