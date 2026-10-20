# M2 动态分析验收与操作指南

> 状态：M2 Preview / 2026-10-20。代码、Frida 17 Agent 打包和桌面自动测试需通过 CI；**RMX3350 真机联调待执行**。
> M2 分为 **Vector 常驻 Demo Hook** 和 **Frida 桌面动态探索** 两条链路，不将离线导入假装成手机端实时 Frida 连接。

## 1. M2 实现范围

- Frida 17 Java Bridge Agent 打包（显式 `import Java from 'frida-java-bridge'`）。
- 在明确授权的目标包中搜索**已加载类**、列出方法与重载签名，最多 16 条明确方法 Hook。
- 动态 `hook`/`unhook`，可读写版本化 JSON Hook 规则。
- 实时采集成功/异常、耗时、PID/TID、单线程调用父子 ID、参数和返回值安全摘要，按需采集限量 Java 栈（仅类/方法名）。
- 有界消息队列和每秒事件预算；异常时原方法结果仍原样返回/抛出。
- JSONL 存储、P50/P95/P99、Top 热点、显式调用树、Chrome Trace 可视化导出。
- Android App 新增 **Trace Analyzer**，通过系统文件选择器**离线导入**由 Frida 主机产生的 JSONL，并展示热点、分位数及调用树。
- 原 M1 Vector 模块及 Demo 工作台保留。

## 2. 前置环境

电脑端：Python 3.11+、Node.js 22+、ADB（Android Debug Bridge，Android 调试桥）、能访问设备的 USB 数据线。

手机端：已解锁 BL / Root；部署与电脑 **Frida Python 客户端匹配版本**的 Frida Server，保持只在自有/授权进程内测试。Frida 文档：<https://frida.re/docs/android/>、<https://frida.re/docs/bridges/>。

> Frida Server **不是** Magisk 或 Vector 的一部分，且 Android Hook Lab APK **不会自动安装或启动它**。如找不到可用 Frida Server，先检查架构、版本和 USB 授权，再排查注入问题。不要把 Frida Server 的接口暴露到公网。

## 3. 下载与构建

CI 成功后在 GitHub Actions 下载两个产物：

- `hook-lab-m2-preview-debug-apks`：Android Hook Lab App 与 Demo App。
- `hook-lab-m2-frida-agent`：Frida 17 已编译 JS Agent。

若需要本地构建 Agent：

```bash
cd engines/frida
npm install
npm run test
npm run build
cd ../..
```

Python 桌面依赖：

```bash
python3 -m venv .venv
source .venv/bin/activate          # Windows PowerShell: .venv\Scripts\Activate.ps1
python -m pip install -r tools/desktop/requirements.txt
python -m unittest discover -s tools/desktop/tests -v
```

Frida 客户端与服务端的协议版本需要兼容；可在设备接入并确认 Frida Server 已工作后进行连接。

## 4. 交互式 Hook 完整流程

在**自己开发的 Demo App**中操作：

```bash
# 确认 Demo 正在运行，Frida Server 已准备就绪
adb devices

# 只对 Demo 包进行连接，不需要额外授权参数
python tools/desktop/hooklab.py --stack \
  --package dev.smallfish.hooklab.demo \
  --output trace/m2-demo.jsonl
```

进入 `hooklab>` 提示符后：

```text
classes ImageProcessor
methods dev.smallfish.hooklab.demo.ImageProcessor
hook dev.smallfish.hooklab.demo.ImageProcessor adjustBrightness float int
hook dev.smallfish.hooklab.demo.ImageProcessor validateLevel int
status
saverules trace/my-rules.json
unhook F1
loadrules trace/my-rules.json
exit
```

每条 Hook 仅匹配一个明确的方法重载，不提供扫描并自动 Hook 全部 App 的隐藏开关。默认只采集基本数值、布尔值；**字符串只输出长度，复杂 Java 对象只输出类型，不调用任意 `toString()`**。

对非 Demo 包使用时，必须拥有明确的测试授权并额外传入 `--authorized --package 目标包名`。Hook 只能选择该包名下的类；脚本不会自动枚举系统关键进程。

规则文件 `rules.json` 示意：

```json
{
  "schema_version": 1,
  "package_name": "dev.smallfish.hooklab.demo",
  "rules": [
    {
      "class_name": "dev.smallfish.hooklab.demo.ImageProcessor",
      "method_name": "adjustBrightness",
      "argument_types": ["float", "int"],
      "enabled": true
    }
  ]
}
```

可以在连接时使用 `--rules trace/my-rules.json` 自动应用已经验证的规则。如果有注册失败（方法没加载、签名不匹配、加固/内联等），命令输出具体错误，不声称成功。

## 5. 离线性能与调用链分析

```bash
python tools/desktop/trace_analyzer.py trace/m2-demo.jsonl \
  --summary trace/summary.json \
  --chrome trace/trace.chrome.json \
  --tree trace/calls.tree.json
```

`trace.chrome.json` 可使用 Chrome Trace 兼容工具或 Perfetto 查看器打开（如 <https://ui.perfetto.dev/>）；JSONL 本地文件会包含方法名及线程信息，请勿上传隐私 Trace 至不可信站点。完整调用树只使用来自 Agent 的 `parent_call_id` 显式引用，不会猜测跨线程关联。

将 `trace/m2-demo.jsonl` 复制到手机，打开 **Hook Lab → System → Open M2 Trace Analyzer → Import Frida JSONL**。解析上限 20,000 条、8 MB，超长/非法 Schema 会拒绝。为了隐私，**Android Analyzer 不显示 args 中的原始内容**。

## 6. M2 CI 与真机测试清单

- [ ] Frida 17 Agent 的 npm 脚本构建、语法检查成功。
- [ ] 桌面 Python 单元测试通过（规则 Schema/作用域、P95/Chrome Trace、显式调用树、拒绝未授权包）。
- [ ] Android 编译、单测、Lint、Xposed APK 元数据检查通过。
- [ ] RMX3350 Android 13 能被 Frida Server 和 Python host 连接。
- [ ] `classes`、`methods` 可返回 Demo `ImageProcessor`。
- [ ] 针对 `adjustBrightness(float,int)` 成功安装 Hook，调用后生成 JSONL。
- [ ] `validateLevel(-1)` 的异常类型被采集而不改变 Demo 原有异常。
- [ ] `unhook` 后新的目标调用不再生成对应 Frida Hook 事件。
- [ ] 100 次调用不会卡死 Demo；过载时记录 dropped 计数。
- [ ] 相同 JSONL 在手机 Trace Analyzer 导入成功，P50/P95/P99 和调用树正确。
- [ ] 30 分钟压力测试/切换后台/重启后稳定性待验证。
- [ ] Vector 与 Frida 同时挂钩相同方法时可能产生 Hook 链干扰；**真机验收应分别测试**。

## 7. 仍存在的差距（不得宣称已完成）

1. Frida 在电脑端进行交互；Android App 仅**离线导入** Frida JSONL，没有内嵌 Frida Server 或实时双引擎聚合。
2. Vector 只支持当前 Demo 固定两个方法；其 Android UI 动态类/方法浏览、热插拔与规则联动仍未实现。
3. Native JNI 关联、火焰图、实时远程桌面控制、稳定的跨引擎 Session ID 关联属于后续 M2.x/M3。
4. 由于未连接用户的手机，上面所有**真机项都尚未确认**，不能把 CI 成功写成设备运行成功。
