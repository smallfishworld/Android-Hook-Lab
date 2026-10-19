# M0 真机测试清单

版本：2026-10-19（待执行，不代表通过）

## 一、构建基线

- [ ] GitHub Actions Android M0 Build 成功。
- [ ] `:libs:protocol:test`、`:samples:demo-app:testDebugUnitTest` 通过。
- [ ] 两个 Debug APK 在同一个 GitHub CI Job 中签名生成。
- [ ] `android-debug.apk` 中存在 `META-INF/xposed/java_init.list`、`scope.list`、`module.prop`。
- [ ] 不带外网访问、录音、相册和宽泛存储权限。

## 二、设备准备（RMX3350，Android 13）

1. 确认 Magisk Root 与 Vector 工作正常（这是用户已有环境，不需要重新解锁 BL）。
2. 安装同一次构建中的两个 APK：`adb install -r apps/android/build/outputs/apk/debug/android-debug.apk` 和 `adb install -r samples/demo-app/build/outputs/apk/debug/demo-app-debug.apk`。
3. 在 Vector 模块页激活 Android Hook Lab，Scope **仅选择 Hook Lab Demo**。根据 Vector 提示重启目标进程；不要扩展到系统服务。
4. 点击 Demo 内正常按钮，应当调用 `adjustBrightness(float,int)`、`validateLevel(int)`；点击异常按钮应产生 `IllegalArgumentException`。
5. 返回 Android Hook Lab，等待约 1 秒，核对新事件的方法名、数字参数、返回值、安全异常类型与耗时。
6. 清除事件，确认列表变空；重复测试确认新事件可重新出现。

## 三、失败诊断

- **Hook Lab 无事件**：确认 Vector modern libxposed 102 可用、模块激活、Scope、Demo 已强制停止重启。
- **Provider permission denied**：确保两个 APK 来自同一次 Debug CI 构建；签名不同会触发预期拦截。
- **未注册 Hook**：在 Logcat/Vector 日志中检索 `HookLabM0`，检查模块 classloader、方法签名、框架版本。
- **主界面显示未安装 Demo**：确保已安装 `demo-app-debug.apk`。
- **安装失败（签名冲突）**：先备份 Hook Lab 测试数据后正常卸载旧的 Debug APK，再安装同一次构建产物；不要影响其他应用。
- **异常后无法开机**：禁用 Android Hook Lab 的 Vector Scope/模块；M0 不应注入系统关键进程。

## 四、安全/压力测试

- [ ] 非 Demo 包不可向 EventProvider 写入数据。
- [ ] 非 Hook Lab UID 无法查询或删除历史数据。
- [ ] Hook 异常不修改原 App 的返回值/异常。
- [ ] 100 / 1000 次 Demo 调用不出现目标线程被 Provider/SQLite 同步阻塞。
- [ ] 事件持续 30 分钟后无崩溃、数据库有保留上限。
- [ ] 日志无敏感字段原文，只有白名单方法名、数字/异常类型。
- [ ] 切换 Vector 模块及 Demo 重启后不会出现 Bootloop。

## 五、M0 已知限制

- 固定两种 Java 方法，尚未实现动态方法枚举和规则热卸载。
- EventRelay 采用 256 条有界队列，但未在 UI 暴露丢弃计数；重启后数据保存在 SQLite。
- SQLite 尚未实现多版本迁移；M0 数据可清除重建。
- 收集与渲染为最小可用实现；没有 Frida/Native/调用树。
- 版本与设备兼容性尚待真机验收，不将 GitHub 成功编译视为 Hook 成功。
