# Android Hook Lab

**Android 动态 Hook、方法调用分析与性能诊断实验室**，面向自有或已取得明确授权的应用。

> 当前处于 **M0 预研/设计阶段**。仓库包含需求文档、架构方案与目录骨架；目前没有可以安装的 APK，也未声称任一 Hook 功能已经实现。

## 核心文档

- [spec.md — 需求规格说明书](./spec.md)：23 项功能、产品交互、非功能约束、验收与 M0～M3 里程碑。
- [design.md — 技术架构说明书](./design.md)：分层架构、Vector/Frida 引擎、跨进程 IPC、事件契约、性能与安全策略。

## 技术路线

- **M0**：Android 13 / Magisk + Vector 环境检测、Demo App、modern libxposed 和 IPC 原型验证。
- **M1**：Java/ART 方法 Hook、事件流、参数/返回值安全摘要、异常、规则与本机会话。
- **M2**：Frida 交互式 Java Hook、调用链、统计及 Trace 导出。
- **M3**：Native/JNI、桌面离线分析与兼容性拓展。

**默认安全策略**：目标显式授权、只读采集、默认脱敏、有界缓冲、本地存储、紧急停用。禁止未授权分析和隐蔽采集。

## 模块目录

| 路径 | 职责 |
|---|---|
| `apps/android` | Android UI、APK 打包入口 |
| `core/domain`、`core/usecase` | 业务模型和流程 |
| `engines/vector` | Vector / libxposed Java Hook |
| `engines/frida` | Frida Java/Native 动态探针 |
| `runtime/collector` | 安全 IPC、限流和事件收集 |
| `libs/protocol`、`libs/storage`、`libs/analysis` | 协议、存储、分析 |
| `samples/demo-app` | 授权可控的演示测试程序 |
| `tools/desktop` | 桌面端 Trace 查看器 |
| `tests` | 自动化与真机验证 |
| `docs/decisions` | 架构决策记录（ADR） |

Git 不保存空目录，因此用 `.gitkeep` 暂时占位。实际模块代码将在后续阶段单独提交。

## 依赖参考

[Vector](https://github.com/JingMatrix/Vector) · [libxposed](https://github.com/JingMatrix/libxposed-api) · [Frida](https://frida.re/) · [Magisk](https://github.com/topjohnwu/Magisk)

项目许可证待依赖审查和分发决策后确定。
