# RigWorks 文档

## 使用与开发

| 入口 | 内容 |
| --- | --- |
| [快速开始](guides/QUICK_START.md) | 安装、导入、校准、应用和保存 |
| [开发](guides/DEVELOPMENT.md) | 独立构建、子模块、客户端及测试 |
| [Blender 工作流](guides/CHARACTER_BLENDER_WORKFLOW.md) | 骨架、动画与侧车数据 |
| [自定义骨架校准](guides/CUSTOM_RIG_NATIVE_CALIBRATION.md) | 参考轴、人体映射、原版动作与导入片段 |
| [原版动作](guides/PLAYER_VANILLA_ACTIONS.md) · [原生通道](guides/RIGWORKS_NATIVE_CHANNELS.md) | 输入观测、动作分层和回退 |
| [KubeJS](guides/CHARACTER_KUBEJS_ACTIONS.md) · [专用动作包](guides/AVATAR_BEHAVIOR_RUNTIME.md) | 全局客户端脚本与按角色隔离的动作包 |
| [KubeJS 动画实用报告](guides/KUBEJS_ANIMATION_GUIDE.md) · [Demo](../examples/kubejs/README.md) | 原版输入、片段、程序化姿态、混合和 IK 的具体操作 |
| [装备定义](guides/CHARACTER_EQUIPMENT.md) · [装备工作台](guides/EQUIPMENT_TRANSFORM_WORKBENCH.md) | 左右手变换、挂点和 Gizmo |
| [手部接触](guides/RIGWORKS_HAND_CONTACTS.md) · [装备视图](guides/RIGWORKS_EQUIPMENT_VIEWS.md) | 双手 IK、第一人称及装备层 |
| [配置同步](guides/CHARACTER_CONFIGURATION_SYNC.md) | 上传、服务器接受版本、异步下载和缓存 |

通用导入、PBR、GPU 渲染、模型项目格式与工作台 API 由 [AssimpLoader 文档](https://github.com/MineRealms/AssimpLoader/blob/feat/oculus-injector/docs/README.md)维护。

## 历史与验收

- [仓库历史提取](history/REPOSITORY_EXTRACTION.md)：来源、路径范围、作者/日期核对及子模块关系。
- [真实双客户端同步验收](history/RIGWORKS_SHARING_VALIDATION_2026-11-18.md)。
- [工作台保存、重开与动作继承验收](history/WORKBENCH_PERSISTENCE_FIXES_2026-11-18.md)。
- [历史目录](history/README.md)：更早的角色开发过程及缺口评估。

历史记录保留当时的源路径与结论，不代表当前独立仓库的启动命令。截图在 `images/`，机器可读结果在 `validation/`。
