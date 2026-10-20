# v0.4 单 APK 手机端验证清单

设备优先：RMX3350 / Android 13 / Magisk Root / Vector modern libxposed API 102。

## 安装与首次启动

- 仅安装最新一次成功 CI 产生的 `android-debug.apk`；不再安装任何 Demo APK。
- 在 Vector 管理器中确认 Hook Lab 模块已启用，支持 dynamic scope / Remote Preferences 服务。
- 打开 Hook Lab，页面应显示 VECTOR 已连接（没有连接时为 OFFLINE，不显示假状态）。
- Apps 页只列出普通已安装应用；用户明确同意测试授权后，Vector 会弹出作用域授权请求。
- 审核 Scope 仅包含要测试的那个包；拒绝时不能加载 Hook。

## 方法发现与注册

- 重启已授权的目标 App，等待其进程初始化。
- 在 Methods 页面观察 TARGET COMMUNICATING 状态和实际 DEX Class 列表。
- 点击类，等目标进程反射上报方法签名；只可安装**一个明确的方法重载**。
- 注册后执行目标 App 的对应操作，Live 应出现 PID/TID、耗时、参数和返回值**脱敏摘要**。
- 关闭开关应注销对应 HookHandle；重新打开应再次注册。
- 超出 12 条规则应给出拒绝，不得导致目标进程卡死。

## 恢复与隐私

- 切换目标后旧目标不应继续记录；对未选中包的发送请求，Provider 应拒绝。
- 测试目标进程崩溃或停止时 Hook Lab UI 不应一起崩溃。
- 用字符串参数测试：日志只允许出现字符串长度，不得出现账号、密码、Token 等原文。
- 使用 100/1000 次方法调用测试上限、队列丢弃和 UI 响应。
- 检查 Vector 模块被禁用后目标 App 可正常运行。不要 Hook 支付/认证/系统关键服务。

## 已知限制

1. 当前 DEX 扫描最多上报 100 个类，无法保证所有复杂多 DEX App 的类可见。
2. Java 反射类发现受动态加载与混淆影响；不包括 Native 函数。
3. IPC 身份核验用于限定已选 App 的 UID，但目标 App 可冒充注入到自身进程的模块发出诊断消息。
4. 暂时无被测设备的实际报告；CI 编译通过只表示工程能构建，不等同 Hook 一定成功。
5. 实际依赖于设备上 Vector 对 libxposed service 102 和远程偏好更新的支持。

## 下一阶段

设备验证后追加：完整 DEX 分页/搜索、方法签名检索、调用线程时序与统计、框架状态显示、跨进程会话管理、Native 分析插件。保持**手机端第一优先**，不回退到 PC ADB 依赖。
