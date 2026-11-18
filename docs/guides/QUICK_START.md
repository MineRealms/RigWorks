# RigWorks 快速开始

## 安装

需要 Minecraft 1.20.1、Forge 47.x 和 Java 17，客户端放入 `rigworks-1.0.0.jar` 与 `assimploader-1.0.0-all.jar`。KubeJS/Rhino/Architectury 用于可选的全局客户端脚本；内建原版动作不要求用户先写脚本。

多人分享需要服务端支持 RigWorks；接收玩家自行准备同一资产，不下载 FBX/贴图。详情见[配置同步](CHARACTER_CONFIGURATION_SYNC.md)。

## 导入和应用

1. 世界内按 `L` 或 `/lookdev` 打开 AssimpLoader 工作区，进入 `Edit`。
2. 导入 FBX/GLB 或打开已保存项目，在 `Character / RigWorks` 校准比例、根变换、人体骨骼和朝向。
3. 先用 Vanilla 检查原版动作映射；已有烘焙片段可设置意图映射，脚本策略使用 Scripted。
4. `Apply` 应用角色，`Save` 持久化项目与角色配置。再次打开已保存项目继续编辑，原角色动作和脚本设置应保留。
5. 手持物位置不对时进入装备变换工作台，左右手分别校准，不要只挪小臂来掩盖 socket 错误。

工作台修改的是配置层，不写回源 FBX；关键帧、骨架和权重仍在建模软件中编辑。配置保存/外部修改/恢复草稿的关系见[持久化验收](../history/WORKBENCH_PERSISTENCE_FIXES_2026-11-18.md)。

## 动画和脚本

[KubeJS 动画报告](KUBEJS_ANIMATION_GUIDE.md) 提供播放片段、混合、骨骼、IK 与烘焙的具体步骤，附[可安装 Demo](../../examples/kubejs/README.md)。

Vanilla、Clips 和 Scripted 是不同策略：不要因为只映射了 idle 就把 Clips 当作自动原版动作兼容；第一次调试优先保留 `frame.defaultAnimation()` 的完整基线。

## 开发

```powershell
git clone --recurse-submodules https://github.com/MineRealms/RigWorks.git
cd RigWorks
.\gradlew.bat build --console=plain
.\gradlew.bat runClient -PwithKubeJS --console=plain
```

更多参数和测试入口见[开发指南](DEVELOPMENT.md)。
