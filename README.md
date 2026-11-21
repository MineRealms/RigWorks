# RigWorks

![RigWorks](src/main/resources/icon.png)

<p align="center">
  <a href="README.en.md">English</a> · <a href="README.md">中文</a>
</p>

**基于 AssimpLoader 的自定义玩家模型与动画系统。**

支持 Forge 1.20.1 与 NeoForge 1.21.1。导入带自定义骨架的 FBX / GLB，在游戏内调整比例、人体映射、动作和左右手装备，同服玩家看到一致的外观。模型加载、PBR 与 GPU 蒙皮由 [AssimpLoader](https://github.com/MineRealms/AssimpLoader) 提供。

![RigWorks 统一游戏内工作台](docs/images/rigworks-unified-workbench-2026-11-19.png)

## 特性

### 自定义角色

- 任意自定义骨架，人体语义映射、比例与朝向校准、完整 XYZ 根变换
- 每个角色独立配置，导入时不改写原始模型与贴图

### YSM 导入与运行时边界

- 导入 `.ysm` 时由 RigWorks 的 C++ 转换器一次性生成带版本和校验的 `.rwym` 资产；后续启动读取缓存，不会重复解密源文件
- Legacy V3 格式 9 已有真实样本回归；V1/V2、Modern MXC 和未覆盖的 V3 payload 会明确失败，不生成半成品 profile
- 本地玩家与远程玩家各自拥有变量、控制器、眨眼、物品和时间状态，共享只读模型/控制器定义；服务端碰撞和移动权威不变
- Workbench 可以校验、编辑和打包 RWYM 资产，但当前只注册共享定义，尚未在预览窗口中驱动完整的 YSM 控制器实例；控制器预览仍见 [YSM TODO](docs/development/YSM_PRIORITY_TODO.md)

### 原版动作

- 读取真实玩家状态，重定向行走、奔跑、蹲伏、跳跃、游泳、爬行、攀爬、滑翔、骑乘、睡眠与死亡
- 可用导入片段或脚本覆盖任意动作，也能只覆盖上半身

### 混合动画与物理

- 原版动作、导入片段、程序化姿态与 IK 分层组合
- LookAt 头部追踪、脚部贴地、手部接触
- Bullet 刚体与关节、Spring Bone、布娃娃
- 动画与物理在客户端 tick 更新，渲染直接消费结果

### 装备挂载

- 左右手独立的位置 / 旋转 / 缩放，命名挂点
- 按物品、tag 与状态匹配的规则
- 第一人称手臂与装备层，冻结世界画面下校准持物姿态

### 游戏内编辑

- 角色模型与角色 ZIP 通过系统原生文件选择框导入
- 在 AssimpLoader 共享 Edit 中解锁 Rig、Actions、Equipment、Behavior 面板
- 公共视口与 Gizmo，自由环绕，相机不被方块推近

### 动作脚本

- 可选 KubeJS API
- `kubejs/rigworks/avatars/<UUID>/` 下的专用 JSON / JS 动作包，按玩家隔离运行，不安装到全局 `client_scripts`

### 多人一致性

- 服务端版本协商、目录通知、按需分块下载、SHA-256 校验、压缩缓存
- 配置缓存按服务器隔离
- 网络只同步外观配置与专用动作包，**不传 FBX、贴图或声音**

### 保存与重载

- 变化检查、合并发布，失败时保留上一个有效版本
- 重新打开已保存项目，原角色动作配置继续可用

## 截图

| 统一工作台 | 装备校准 | 角色分享 |
| --- | --- | --- |
| ![工作台](docs/images/rigworks-workspace-2026-11-19.png) | ![装备](docs/images/rigworks-equipment-2026-11-19.png) | ![分享](docs/images/rigworks-character-sharing-2026-11-19.png) |

## 开始使用

把同一个 Minecraft / 加载器节点的两个 JAR 放进客户端 `mods/`：

- `rigworks-<mc>-<version>-<loader>.jar`
- `assimploader-<mc>-<version>-<loader>.jar`

Forge 1.20.1 使用 Java 17 与 Oculus / Embeddium，NeoForge 1.21.1 使用 Java 21 与 Iris / Sodium。KubeJS 可选。

多人配置分享需要服务端也安装 RigWorks 及依赖。视觉配置不会修改服务器碰撞箱、移动规则或攻击距离。

在游戏内按 `L`，通过 **RigWorks > Characters > Import avatar** 选择角色模型或角色 ZIP，然后在 **AssimpLoader > Edit** 中编辑带 `[RigWorks]` 标记的项目。`Apply` 应用到本地角色，`Save` 保存并应用，`Export` 选择要打包的数据后输出可再次导入的 ZIP。

## 文档

| 主题 | 文档 |
| --- | --- |
| 模型与骨架 | [建模工作流](docs/guides/CHARACTER_BLENDER_WORKFLOW.md) · [自定义骨架校准](docs/guides/CUSTOM_RIG_NATIVE_CALIBRATION.md) |
| 动作 | [动画入门](docs/guides/ANIMATION_BEGINNER_GUIDE.md) · [原版动作](docs/guides/PLAYER_VANILLA_ACTIONS.md) · [原生通道](docs/guides/RIGWORKS_NATIVE_CHANNELS.md) · [KubeJS](docs/guides/CHARACTER_KUBEJS_ACTIONS.md) |
| 装备 | [装备定义](docs/guides/CHARACTER_EQUIPMENT.md) · [3D 变换工作台](docs/guides/EQUIPMENT_TRANSFORM_WORKBENCH.md) · [手部接触](docs/guides/RIGWORKS_HAND_CONTACTS.md) |
| 角色包 | [作者源码与 ZIP](docs/guides/AVATAR_AUTHORING_SOURCES.md) · [角色副本与导出选项](docs/guides/RIGWORKS_AVATAR_PACKAGE.md) · [动作包运行时](docs/guides/AVATAR_BEHAVIOR_RUNTIME.md) |
| 多人 | [配置同步](docs/guides/CHARACTER_CONFIGURATION_SYNC.md) · [脚本重载生命周期](docs/guides/KUBEJS_RELOAD_LIFECYCLE.md) |
| 开发 | [开发指南](docs/guides/DEVELOPMENT.md) · [YSM 转换管线](docs/development/YSM_RIGWORKS_PIPELINE.md) · [YSM TODO](docs/development/YSM_PRIORITY_TODO.md) · [文档索引](docs/README.md) |

## 从源码构建

```powershell
git clone --recurse-submodules https://github.com/MineRealms/RigWorks.git
cd RigWorks
.\gradlew.bat buildAndCollect --console=plain
.\gradlew.bat :1.21.1-neoforge:runClient -PwithKubeJS --console=plain
```

`AssimpLoader/` 是固定提交的 Git 子模块，Gradle 会先独立构建对应引擎节点，再生成官方映射的开发依赖。`build/distributions/` 收集两个 RigWorks 发布 JAR。RigWorks 不打包引擎代码，也不依赖 AssimpShowcase。

已有克隆先执行 `git submodule update --init --recursive`。相邻引擎仓库可用 `-PassimpLoaderDir=../AssimpLoader` 指定。

## 许可

[MIT](LICENSE)。
