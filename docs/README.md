# RigWorks 文档

## 使用与开发

| 入口 | 内容 |
| --- | --- |
| [快速开始](guides/QUICK_START.md) | 安装、导入、校准、应用和保存 |
| [动画入门](guides/ANIMATION_BEGINNER_GUIDE.md) | 从骨骼和蒙皮理解原版动作、FBX 片段、混合、IK、烘焙与脚本 |
| [开发](guides/DEVELOPMENT.md) | 独立构建、子模块、客户端及测试 |
| [1.21.1 迁移](development/1.21.1_MIGRATION.md) | 双版本构建、Iris/KubeJS 差异与实机验收状态 |
| [Blender 工作流](guides/CHARACTER_BLENDER_WORKFLOW.md) | 骨架、动画与侧车数据 |
| [Agent 与三种 DCC 导出](https://github.com/MineRealms/AssimpLoader/blob/multiversion/docs/guides/AGENT_ASSET_WORKFLOW.md) | MCP 连接、软件内编辑、统一导出和游戏导入；本地入口在 AssimpLoader 子模块 |
| [自定义骨架校准](guides/CUSTOM_RIG_NATIVE_CALIBRATION.md) | 参考轴、人体映射、原版动作与导入片段 |
| [原版动作](guides/PLAYER_VANILLA_ACTIONS.md) · [原生通道](guides/RIGWORKS_NATIVE_CHANNELS.md) | 输入观测、动作分层和回退 |
| [KubeJS](guides/CHARACTER_KUBEJS_ACTIONS.md) · [专用动作包](guides/AVATAR_BEHAVIOR_RUNTIME.md) | 全局客户端脚本与按角色隔离的动作包 |
| [KubeJS 动画实用报告](guides/KUBEJS_ANIMATION_GUIDE.md) · [Demo](../examples/kubejs/README.md) | 原版输入、片段、程序化姿态、混合和 IK 的具体操作 |
| [装备定义](guides/CHARACTER_EQUIPMENT.md) · [装备工作台](guides/EQUIPMENT_TRANSFORM_WORKBENCH.md) | 左右手变换、挂点和 Gizmo |
| [工作区 GUI 验收](development/WORKSPACE_GUI_2026-11-19.md) | 独立模组分区、公共注册接口、自由环绕与实机截图 |
| [手部接触](guides/RIGWORKS_HAND_CONTACTS.md) · [装备视图](guides/RIGWORKS_EQUIPMENT_VIEWS.md) | 双手 IK、第一人称及装备层 |
| [配置同步](guides/CHARACTER_CONFIGURATION_SYNC.md) | 上传、服务器接受版本、异步下载和缓存 |
| [作者源码与 ZIP](guides/AVATAR_AUTHORING_SOURCES.md) | 专用目录、子文件夹、异步快照、冲突隔离和完整资产包 |
| [统一工作台清单](development/UNIFIED_WORKBENCH_TODO.md) | 责任边界、实现状态与尚待验收的项目 |
| [统一工作台与双客户端验收](development/UNIFIED_WORKBENCH_VALIDATION_2026-11-19.md) | ZIP 往返、动作继承、关闭远端显示、第二版更新与实际截图 |
| [YSM 转换与动作兼容](development/YSM_RIGWORKS_PIPELINE.md) | 一次性转换为 RigWorks 资产、YSM 动作语义、眨眼和 TaCZ 等运行时适配 |

通用导入、PBR、GPU 渲染、模型项目格式与工作台 API 由 [AssimpLoader 文档](https://github.com/MineRealms/AssimpLoader/blob/multiversion/docs/README.md)维护。导航注册和世界编辑相机见 [GUI 扩展接口](https://github.com/MineRealms/AssimpLoader/blob/multiversion/docs/guides/WORKSPACE_EXTENSIONS.md)。

## 历史与验收

- [仓库历史提取](history/REPOSITORY_EXTRACTION.md)：来源、路径范围、作者/日期核对及子模块关系。
- [真实双客户端同步验收](history/RIGWORKS_SHARING_VALIDATION_2026-11-18.md)。
- [工作台保存、重开与动作继承验收](history/WORKBENCH_PERSISTENCE_FIXES_2026-11-18.md)。
- [历史目录](history/README.md)：更早的角色开发过程及缺口评估。

历史记录保留当时的源路径与结论，不代表当前独立仓库的启动命令。截图在 `images/`，机器可读结果在 `validation/`。
