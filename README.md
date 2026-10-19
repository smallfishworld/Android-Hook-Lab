# Android Hook Lab

一个面向**自有应用和明确授权测试目标**的 Android Hook、动态方法调用与性能分析项目。

> **M0 开发原型（代码已提交，尚未完成 Android 构建和 RMX3350 真机验证）。**
> 目前仅接收本项目 Demo App 的两个方法事件，不支持任意第三方 App。

## 文档

- [spec.md — 产品需求](./spec.md)
- [design.md — 系统设计](./design.md)
- [docs/m0-test-plan.md — M0 手动验证步骤](./docs/m0-test-plan.md)

## 已实现的 M0 代码

- Android Java 多模块 Gradle 工程（AGP 8.7.3 / Gradle 8.9 / JDK 17 / SDK 35）。
- [Demo App](./samples/demo-app)：`ImageProcessor.adjustBrightness(float,int)`、`validateLevel(int)`、方法重载与受控异常。
- [Vector Hook Adapter](./engines/vector)：libxposed API 102，限定 Demo 作用域；只观察，不修改返回值。
- **异步采集**：Hook 回调只将安全数值事件写入 256 条有界队列；独立线程转交 ContentProvider。
- **安全事件桥**：签名级权限 + 调用者 UID/包名检查；只有 Hook Lab 本应用可读取/删除。
- **本机事件展示**：M0 Android UI 每秒读取最多 80 个最新事件；SQLite 最多保留约 1000 条事件。
- 单元测试 + GitHub Actions 构建和 APK Artifact 配置。

没有包含 Root 远程 Shell、Frida Server、任意目标注入功能；M0 仅接受这两个测试方法的数值数据。

## 从 GitHub Actions 获取 APK

1. 打开 [Android M0 Build](../../actions/workflows/android.yml)。
2. 构建通过后下载 Artifact `hook-lab-m0-debug-apks`。
3. 从**同一次构建**安装 `android-debug.apk` 与 `demo-app-debug.apk`（签名必须匹配）。
4. 在 Vector 中启用 Android Hook Lab 模块，Scope 只选 **Hook Lab Demo**。
5. 强制停止并重新打开 Demo；点击正常调用和异常按钮。
6. 返回 Hook Lab App，查看**真实 Hook 事件**。如果没有，请参考 [M0 测试计划](./docs/m0-test-plan.md)。

本地构建（需事先安装 Android SDK 35、JDK17、Gradle 8.9）：

```bash
gradle :libs:protocol:test :samples:demo-app:testDebugUnitTest \
  :apps:android:assembleDebug :samples:demo-app:assembleDebug
```

## 规划中的功能（**未实现**）

- 任意已授权 App 的类/方法浏览与动态注册规则。
- Frida Java/Native 双引擎、调用树、线程时间线和堆栈。
- 加密导出、桌面可视化、符号解析、完整会话状态机。
- 长时间压力测试与 Android 13 / realme ROM 真机验证。

## 工程结构

`apps/android`（UI / event provider）、`engines/vector`（Xposed adapter / relay）、`libs/protocol`（共享白名单及校验）、`samples/demo-app`（测试靶标），其余 `core`、`runtime`、`libs/storage`、`libs/analysis`、`tools/desktop` 为后续扩展目录。

## 安全与许可证

只在自有/授权 App 中测试；默认仅采集 Demo 数值数据，不提取密码、Cookie、Token、私人消息或支付内容。兼容性取决于实际安装的 Vector 版本及其现代 API 支持情况。项目许可证将在第三方依赖许可审查后确定。

参考 [Vector](https://github.com/JingMatrix/Vector)、[libxposed-api](https://github.com/JingMatrix/libxposed-api)、[Frida](https://frida.re/)。
