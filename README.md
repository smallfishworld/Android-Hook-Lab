# Android Hook Lab

面向自有 / 明确授权应用的 Android 方法 Hook、运行时分析与性能诊断工作台。

> **当前：v0.3.0 M2 Preview（2026-10-20）**。M1 的 Vector 常驻 Demo Hook + Compose UI 保留；新增 Frida 17 电脑端动态 Java Hook、方法发现、JSON 规则、显式调用树和热点分析，以及 Android 端 Frida Trace 的**离线**导入查看。兼容性仍需 RMX3350 真机验证。

## 核心能力

| 能力 | 当前实现 | 使用位置 |
|---|---|---|
| Vector 常驻 Hook | **Demo 两个方法**，数字参数/返回值/异常及毫秒耗时 | Android Live / Methods |
| 动态 Java 类发现 | 搜索目标进程已加载类，限定授权包名前缀 | Frida Desktop Console |
| 方法浏览 | 列出选中类的 Java 方法和参数重载 | Frida Desktop Console |
| 动态 Hook 控制 | 选择**精确重载**安装/取消 Hook，最多 16 条 | Frida Desktop Console |
| Hook 规则 | 版本化 JSON，校验包名、类名和签名，导入/导出 | Desktop |
| 调用栈与调用链 | 按需采集类名/方法名，按线程显式 `parent_call_id` | Frida Agent / 分析器 |
| 实时事件采集 | 有界队列、速率限制、元数据优先；JSONL 落盘 | Desktop |
| 性能统计 | P50/P95/P99、累计时长、异常、热点方法 | Desktop + Android Analyzer |
| 时序导出 | Chrome Trace Event JSON 和嵌套调用树 JSON | Desktop |
| 手机端分析 | JSONL 离线导入、方法热点和调用树 | Android System → Trace Analyzer |
| Native/Frida 手机内嵌服务 | **尚未实现** | M2.x/M3 |

**注意：Android App 当前没有直接启动/操控 Frida Server 的功能**，桌面动态 Frida 事件需要以 JSONL 导入手机才能在 Analyzer 查看。不能把这种离线流程误认为 Android 上已经实现 Frida 双引擎实时聚合。

## 快速开始

### Android APK

从 [Android Hook Lab Build](./.github/workflows/android.yml) 最新**成功** CI 下载 `hook-lab-m2-preview-debug-apks`，安装其中 Hook Lab APK 和 Demo APK（必须来自同次构建，调试签名匹配）。

在 Vector 管理器设置 Hook Lab 模块 Scope，仅包含 Demo App。重启 Demo 后操作按钮，从 Hook Lab 的 Live 页查看 Vector 捕获的数字测试事件。

### Frida 17 桌面动态调试

先在已 Root 手机中准备与电脑客户端兼容版本的 Frida Server，并确认 USB 通信正常。Frida 17 Java Bridge 要单独打包，因此本仓库提供已编译 Agent CI 产物：`hook-lab-m2-frida-agent`。

```bash
cd engines/frida
npm install
npm run build
cd ../..

python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r tools/desktop/requirements.txt

python tools/desktop/hooklab.py --package dev.smallfish.hooklab.demo \
  --stack --output trace/demo.jsonl
```

进入终端交互后：

```text
classes ImageProcessor
methods dev.smallfish.hooklab.demo.ImageProcessor
hook dev.smallfish.hooklab.demo.ImageProcessor adjustBrightness float int
hook dev.smallfish.hooklab.demo.ImageProcessor validateLevel int
status
saverules trace/rules.json
exit
```

随后可导出分析：

```bash
python tools/desktop/trace_analyzer.py trace/demo.jsonl \
  --chrome trace/demo.chrome.json \
  --tree trace/demo.tree.json \
  --summary trace/demo.summary.json
```

将 `trace/demo.jsonl` 放到手机文件夹，在 Hook Lab **System → Open M2 Trace Analyzer → Import Frida JSONL** 中查看。方法、耗时、异常和调用树显示在手机上；不展示潜在隐私参数原文。

**完整安装、规则导入/导出、真机验收说明：** [M2 指南 docs/m2-guide.md](./docs/m2-guide.md)。

## 项目文档

- [spec.md — 产品需求](spec.md)
- [design.md — 总体架构](design.md)
- [docs/m2-guide.md — M2 安装与验证](docs/m2-guide.md)
- [docs/m1-ui.md — M1 UI](docs/m1-ui.md)
- [docs/m0-test-plan.md — M0 真机基线](docs/m0-test-plan.md)

## 工程结构

```text
apps/android            Android Compose 工作台和离线 Trace Analyzer
engines/vector          Vector/libxposed Demo Hook, bounded relay
engines/frida           Frida 17 Java Bridge Agent + NPM 打包
tools/desktop           USB Frida Host CLI, rule loader, trace analyzer
libs/protocol           Schema 与统计单元测试
samples/demo-app        自研可控 Java Hook 测试目标
core, runtime, libs/... 后续 M2.x/M3 扩展
```

## 研发与验证

CI 分为 Android Build/Lint/Test 与 Frida Agent/桌面 Python 测试两组 Jobs，均通过后产生对应 Artifact。真机兼容性和执行时性能仍需专门测量：**CI 通过不能证明 Vector/Frida 已在 RMX3350 上成功 attach 或 Hook。**

## 使用边界

仅分析自有或已获授权的目标。非 Demo 包必须显式 `--authorized`，并限定类名前缀；采集器默认只捕获基本数字/布尔值与类型信息，字符串仅记录长度，禁止窃取私人数据、登录凭据、Cookie、Token 和支付信息。默认无云端数据传输，不开放远程监听地址。

项目许可证需完成第三方依赖许可审查后确定，发布前应确认 Frida Java Bridge 等依赖的分发义务。
