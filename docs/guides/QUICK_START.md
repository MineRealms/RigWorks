# RigWorks 快速开始

## 安装

Forge 1.20.1 使用 Java 17，NeoForge 1.21.1 使用 Java 21。客户端放入同一版本的 `rigworks-<mc>-<loader>-1.0.0.jar` 与 `assimploader-<mc>-<loader>-1.0.0.jar`。KubeJS/Rhino/Architectury 用于可选的脚本；内建原版动作不要求用户先写脚本。

多人分享需要服务端支持 RigWorks；接收玩家自行准备同一资产，不下载 FBX/贴图。详情见[配置同步](CHARACTER_CONFIGURATION_SYNC.md)。

## 导入和应用

1. 世界内按 `L` 或 `/lookdev` 打开工作区，通过 `RigWorks > Characters > Import avatar` 导入 FBX/GLB，或者在公共 Import 窗口选择 `RigWorks`。普通 `Model` 导入只创建通用资产。
2. 在共享 `AssimpLoader > Edit` 校准比例、根变换、人体骨骼和朝向。已初始化的角色带 `[RigWorks]` 标记，并解锁 Rig、Actions、Equipment、Behavior 面板；不再切换到另一套 Rig Editor。
3. 先用 Vanilla 检查原版动作映射；已有烘焙片段可设置意图映射，脚本策略使用 Scripted。
4. `Apply` 应用角色，`Save` 持久化项目与角色配置并应用。再次打开已保存项目继续编辑；`Export` 输出完整 ZIP，下次在 Import 中导入 ZIP 即可恢复。源 FBX 不被重写。
5. 手持物位置不对时，通过 `[RigWorks] Equipment > Calibrate held items` 或 `Ctrl+H` 进入世界装备变换工作台，左右手分别校准。

新建角色的专用行为源自动放入 `kubejs/rigworks/<avatar>/`，支持子目录；Save/Apply/Export 先异步读取并验证最新源文件，错误会阻止当前操作并保留有效版本。旧全局 KubeJS 控制器继续兼容，但不会被自动转换成可分发的专用脚本。目录、导出和冲突隔离见[作者源码工作流](AVATAR_AUTHORING_SOURCES.md)。

工作台修改的是配置层，不写回源 FBX；关键帧、骨架和权重仍在建模软件中编辑。配置保存/外部修改/恢复草稿的关系见[持久化验收](../history/WORKBENCH_PERSISTENCE_FIXES_2026-11-18.md)。

## 动画和脚本

[KubeJS 动画报告](KUBEJS_ANIMATION_GUIDE.md) 提供播放片段、混合、骨骼、IK 与烘焙的具体步骤，附[可安装 Demo](../../examples/kubejs/README.md)。

Vanilla、Clips 和 Scripted 是不同策略：不要因为只映射了 idle 就把 Clips 当作自动原版动作兼容；第一次调试优先保留 `frame.defaultAnimation()` 的完整基线。

## 开发

```powershell
git clone --recurse-submodules https://github.com/MineRealms/RigWorks.git
cd RigWorks
.\gradlew.bat buildAndCollect --console=plain
.\gradlew.bat :1.21.1-neoforge:runClient -PwithKubeJS --console=plain
```

更多参数和测试入口见[开发指南](DEVELOPMENT.md)。
