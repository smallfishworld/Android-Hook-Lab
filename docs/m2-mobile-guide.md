# Android Hook Lab M2 · 手机端直连控制（无 ADB）

版本：**0.3.1-mobile-preview**。目标：手机独立使用 Hook Lab。本文如实区分“已实现、CI 通过、真机验证”。

## 1. 使用方式（只用手机）

1. 从 GitHub Actions 同一构建下载 **Hook Lab APK** 和 **Hook Lab Demo APK** 并安装。
2. 在已安装的 Vector 框架中启用 **Android Hook Lab** 模块，Scope 只选择 **Hook Lab Demo**。
3. 在手机上打开 **Hook Lab → Methods → Open On-Device Hook Control**。
4. 打开 Demo App，若尚无上报的方法目录，强制停止并重启 Demo。
5. Hook Lab 控制台将读取 Demo 进程反射上报的方法目录和本地 IPC 心跳。
6. 在手机直接对 `adjustBrightness(float,int)`、`adjustBrightness(int,int)`、`validateLevel(int)` 分别开关采集，或使用 **Master Capture** 暂停全部记录。
7. 在 Demo 中触发测试，回到 Hook Lab 的 **Live** 页查看真实事件。

**无需电脑、不依赖 ADB、不需要 Frida Server、不需要 Python 或网络权限。** GitHub Actions 下载和首次安装本身需要正常访问 GitHub；之后 Hook Lab ↔ Demo 通信完全发生在手机上。

## 2. 技术实现

```text
Android Hook Lab (用户界面)
    │
    ├─ 用户设置 Master / 单方法采集规则
    │
    ▼
MobileControlProvider (私有持久化配置)
    │   同签名 + Binder UID 校验
    │
    ▼
Vector 模块所在 Demo 进程
    ├─ 注入时注册三个限定方法的 Hook
    ├─ 后台 ~800ms 获取开关（Hook 回调不阻塞）
    ├─ 使用反射上报 ImageProcessor 方法签名
    └─ 启用方法 → 有界队列 → EventProvider
                                │
                                ▼
                          SQLite → Live UI
```

- 控制通道使用 `ContentResolver.call()`，对允许的方法 ID/规则做固定白名单校验。
- 事件采集不调用任意对象的 `toString()`；仍只允许安全数字、方法标识和异常**类型**。
- App 更新的规则通过 Android `SharedPreferences` 持久化；不会默默重置用户设置。
- 同一个 CI Job 签名的两个 APK 才能跨 UID 通信；拦截规则不会因打开权限界面而自动跨越到其他 App。
- 界面显示的“IPC Seen Recently”仅代表 Demo 读取过规则，**不等价于所有 Hook 都成功安装**。
- 规则变更传播目标约 1 秒，需真机测量；目标方法正在执行时旧事件可能仍已送入管线。

## 3. 限制与下一步

本版本可**在手机侧控制三个已适配 Demo 方法的采集**，并从目标 ClassLoader 发现受限类的方法目录。但：

- **关闭采集并不物理卸载已注册的 Vector Hook**；动态卸载须继续测试框架的实际接口。
- 当前目标范围仅限自带 Demo。任意授权 APP 的类浏览、实时添加 Hook 和跨 App 数据管道仍在 M2.x 计划中，需要可扩展的身份/作用域授权设计。
- Frida Desktop CLI 仍保留为**可选开发辅助工具**，而非手机基础能力的前提。
- Root/Vector 状态需要在手机上实测；CI 编译不能替代设备验收。

## 4. 安装和排查

如果规则页一直显示 **Awaiting Target Process**：

- 检查 Vector 中模块已启用，Scope 仅选 Hook Lab Demo。
- 强制停止 Demo 后重新启动，等待片刻再点控制页的 Refresh。
- 确保两个 APK 取自**同一次** GitHub Actions 构建；如果因调试签名冲突无法更新，请先备份 Demo 测试数据再卸载旧版本。
- 日志可在 Vector 管理器查看；`adb logcat` 只是**可选**高级排障方式，不是使用前提。

## 5. 测试验收

- [ ] 构建两份 APK 且 Xposed 元数据正常。
- [ ] 新协议及白名单单元测试通过。
- [ ] 真机安装后，在 Demo 中能看到受限方法目录。
- [ ] Master Switch 可在运行中的 Demo 上停止新事件采集并恢复。
- [ ] 单方法 Switch 互不干扰；三种调用均能被区分。
- [ ] 100 次测试不冻结 UI 或 Demo；高频调用只丢事件，不堵塞原方法。
- [ ] 异常调用仍抛原异常，关闭采集时业务返回值不变。
- [ ] 手机重启后规则仍能恢复，并验证不出现 Bootloop。

**上述真机项尚未在 RMX3350 设备上验证。**
