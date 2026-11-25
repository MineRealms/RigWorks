# RigWorks 项目概览

> 状态基准：2026-11-25，RigWorks 源码 `16a971e`；AssimpLoader 子模块固定在 `d56a254`（`multiversion`）。
> 本文件是入口级总览，细节与实际验收状态以文末链接的专项文档为准。
> 未加链接的反引号路径均相对仓库根目录。

## 1. 定位

RigWorks 是一个可选的**客户端玩家模型与动画模组**，本身不实现通用模型导入、GPU 蒙皮或工作台，这些都由 [AssimpLoader](../AssimpLoader/README.md) 提供。它把“带骨架的 FBX/GLB 或转换后的 YSM 资产”变成游戏内的玩家角色：人体语义映射、原版动作重定向、装备挂点、IK/物理、脚本动作、多人外观同步，以及嵌入共享 Edit 工作台的专用面板。

- 支持 Forge 1.20.1（Java 17，Oculus/Embeddium）与 NeoForge 1.21.1（Java 21，Iris/Sodium）。
- 网络只同步外观配置与专用动作包，不传 FBX、贴图、声音；服务端碰撞、移动和伤害仍由 Minecraft 权威处理。
- 许可 MIT。

## 2. 仓库与构建布局

| 位置 | 内容 |
| --- | --- |
| `src/main/java/cn/minerealms/rigworks/` | 两个版本共享的 Java 源码；包根即模组入口。 |
| `src/integration/`、`src/test/` | 客户端 smoke 测试与单元测试；integration 只在开发期合并，不进发布 JAR（`build.gradle:91-99`）。 |
| `versions/1.20.1-forge/`、`versions/1.21.1-neoforge/` | 各节点的 `mods.toml` / `neoforge.mods.toml` 和构建产物。Java 由 Stonecutter 处理版本差异。 |
| `AssimpLoader/` | git 子模块（`.gitmodules`），引擎源码与文档；不是 Gradle 子项目，Gradle 消费其预构建 JAR（`settings.gradle:31-34`）。 |
| `native/` | RigWorks 自有的 YSM 转换与处理 C++ 源码。 |
| `docs/guides/`、`docs/development/`、`docs/validation/`、`docs/history/` | 使用指南、开发契约、机器可读验收结果和历史记录。 |

常用命令（`README.md:103-124`）：

```powershell
.\gradlew.bat buildAndCollect --console=plain
.\gradlew.bat buildAndCollectBundled --no-daemon --console=plain
.\gradlew.bat :1.21.1-neoforge:runClient -PwithKubeJS --console=plain
.\gradlew.bat test integrationClasses --console=plain
```

`buildAndCollectBundled` 会产出普通版和 `-with-assimploader.jar` 的 Jar-in-Jar 版，并校验 Mixin 注册、refmap、嵌套依赖、原生库和产物边界（`stonecutter.gradle:64-82`、`build.gradle:509-704`、`gradle/module-boundaries.gradle`）。用 `-PassimpLoaderDir=<路径>` 可指向比固定子模块更新的独立引擎副本。

## 3. 与 AssimpLoader 的责任边界

| 能力 | 归属 |
| --- | --- |
| FBX/GLB 导入、PBR、GPU 蒙皮、骨骼矩阵、通用动画图、视口、Tree/Inspector/Gizmo、撤销、项目 ZIP 容器、导航注册 | AssimpLoader |
| 角色语义、人体映射、原版动作重定向、装备规则、物理/IK 策略、YSM 语义、按玩家同步、角色专用面板 | RigWorks |

RigWorks 不建立第二个视口或第二套编辑会话，而是通过引擎的公共接口接入：

- `RigWorks.java:12-16`：模组入口只注册网络通道与客户端配置。
- `character/client/RigWorksClientSetup.java:31-43`：客户端注册 `RigWorks` 导航分区、`Characters` 页面和诊断信息。
- `character/client/RigWorksWorkbenchExtension.java:31`：实现 `WorkbenchExtensions.Extension`，用 `initialize/opened/prepare/saved/apply/export` 等回调接管角色项目（`canImport`、`handlesApplication`、`ownsRootTransform` 均为 true），在共享 Inspector 中提供 Rig / Actions / Equipment / Behavior 四个面板（`:342-418`）。
- 渲染通过继承引擎的 `BaseAssimpRenderer`：`LocalPlayerCharacter`、`RemotePlayerCharacters`、`CharacterFirstPersonArms`；编辑态隔离用 `WorkbenchRuntime.instance(owner, scene, document)`。
- 引擎侧扩展契约见 [WORKSPACE_EXTENSIONS.md](../AssimpLoader/docs/guides/WORKSPACE_EXTENSIONS.md)。

## 4. 一条完整的使用链路

1. **导入**：`RigWorks > Characters > Import avatar` 调用引擎原生文件选择框（`ModelWorkbenchScreen.requestImport("rigworks")`）；YSM 走 `AvatarImportRegistry` 的 `ysm` 处理器。普通 FBX 导入不会自动启用角色功能。
2. **编辑**：在 `[RigWorks]` 项目里设置人体骨骼映射（root/hips/spine/head/四肢…）、比例、前向角、根 TRS、动作映射、装备挂点与规则、物理选项。原始 FBX 与贴图不被回写。
3. **应用/保存/导出**：`Apply` 只更新本地角色；`Save` 保存项目并应用；`Export` 按勾选项输出可再次导入的角色 ZIP。装备持物姿态可在冻结世界画面的第一人称校准器中编辑。
4. **运行时**：客户端 tick 采样真实玩家状态，叠加原版动作重定向、导入片段、脚本、IK 与物理，渲染只消费已完成的姿态。
5. **多人**：启用上传后，服务端以连接身份确定 owner，返回权威 revision/哈希；其他客户端按需异步下载压缩配置包并校验 SHA-256，仅重建对应玩家的实例。

## 5. 核心子系统索引

| 子系统 | 关键源码 |
| --- | --- |
| 配置与生命周期 | `RigWorksConfig.java`、`config/RuntimeConfigFile.java`（原子事务写入 `rigworks-client.toml`）、`debug/RigWorksDiagnostics.java` |
| 角色档案与快照 | `character/core/CharacterProfile.java`、`CharacterVisualConfig.java`、`CharacterVisualPackage.java`、`CharacterVisualLedger.java` |
| 导入与来源 | `character/importing/AvatarImportRegistry.java`、`AvatarImportHandler.java`、`YsmAvatarImportHandler.java`、`character/script/AvatarPackageRepository.java` |
| 客户端角色实例 | `character/client/LocalPlayerCharacter.java`、`RemotePlayerCharacters.java`、`CharacterProfileIndex.java`、`CharacterPhysicsClientBinding.java`、`CharacterHandContacts.java` |
| 动作 | `character/motion/DefaultMotionController.java`、`DefaultMotionLibrary.java`、`DefaultMotionRetargeter.java`（`DEFAULT` 内置 25 段 VMD 来源动作）、`client/VanillaPoseRetargeter.java`、`VanillaPlayerPoseDriver.java` |
| 装备 | `character/core/CharacterEquipmentDefinition.java`、`CharacterGripDefinition.java`、`client/CharacterEquipmentLayers.java`、`EquipmentTransformScreen.java`、`EquipmentEditorClient.java`、`TaczHeldItemCompat.java` |
| 脚本 | `compat/RigWorksKubeJSPlugin.java`、`compat/kubejs/*`、`client/CharacterScriptRuntime.java`、`character/script/RhinoAvatarSession.java`；动作包位于 `kubejs/rigworks/avatars/<UUID>/` |
| 网络同步 | `network/CharacterAppearanceNetwork.java`（`rigworks:appearance` v2）、`CharacterPackageNetwork.java`、`CharacterCatalogRedelivery.java`、`CharacterVisualSavedData.java` |
| YSM | `ysm/`（解码/原生）、`ysm/asset/`（RWYM 读写与缓存）、`ysm/model/`（数据模型）、`ysm/runtime/`（每角色实例、表达式、controller、blink、TaCZ 输入适配） |

## 6. YSM 支持要点

- Legacy V3 `.ysm` 首次转换需要 RigWorks 的 C++ 转换器，**一次性**生成带版本、目录与校验的 `.rwym` 资产；后续启动由有界 Java reader 读取，不加载转换器、不再解密源文件。重新导入相同源字节与转换版本会复用缓存。
- `.rwym` 是 RigWorks 自有规范；引擎只消费由它生成的一次性 GLB 渲染缓存，引擎内没有 YSM 解析器。
- 本地和远程玩家已各自持有 `YsmRuntimeInstance`，共享只读模型/controller 定义，变量、动画时钟、物品与眨眼状态互不串扰。工作台目前提供元数据检查，独立运行 YSM controller 的实时预览尚未接入。
- 装备挂点与武器替换以 YSM locator（如 `LeftHandLocator`、`PistolLocator`、`BladeLocator`）为准；通用人体映射只作为 FBX 模型和 vanilla 动作重定向的回退。
- 支持 Legacy V3 payload 1 / 9 的真实样本；不支持 Legacy V1/V2 外层容器与 Modern MXC。模型自定义函数、完整历史动作分类、音效事件、完整物品变体规则和工作台内 controller 实时预览仍在开发。通用 KubeJS、PBR 和物理不等于已支持全部 YSM 来源语义。
- 支持独立第一人称 geometry 和 `fp_arm` controller；接收他人角色配置不会下载 RWYM，仍需要本地安装相同资产。

## 7. 项目现状与证据分层

按 `AGENTS.md` 的要求，把证据分为三类，不能互相替代：

1. **源码/离线证据**：2026-11-25 RigWorks 回归为 NeoForge 439 通过、3 跳过，Forge 433 通过、3 跳过；两版本完整构建、integration 编译、模块边界与 bundled-JAR 检查通过。[CI run 45500656880](https://github.com/MineRealms/RigWorks/actions/runs/45500656880) 对 `16a971e` 的六平台 native fixture/C ABI 测试、产物检查和两版本构建成功；这不代表六平台 Minecraft 画面均已验收。YSM 外部样本结果见[转换管线](development/YSM_RIGWORKS_PIPELINE.md)。
2. **客户端证据**：双客户端分享、工作台持久化、装备工作区等实机结果保存在 `docs/validation/`。已完成 Gradle 任务本身不等于客户端打开或画面正确。
3. **未验收/未完成**：[YSM TODO](development/YSM_PRIORITY_TODO.md) 将剩余工作分为 8 类功能扩展和 2 类验收；P0 为模型函数/表达式、工作台实时预览和完整物品变体。动作类别/可选模组、YSM 专用 JSON/KubeJS、音效/特效/来源物理、配置表单/同步及更多来源格式另列扩展。持物条件、controller 完成过渡、重复导入保留配置与每 owner 实例隔离已有实现；近期新增行为仍需客户端复测，多角色性能也需测量。

约定：报告每项须标注 tested / unverified / failed；模型准备后至少等待 10 秒再截图；不要把离线测试写成视觉验收。

## 8. 推荐阅读顺序

- 快速上手：[QUICK_START.md](guides/QUICK_START.md)、[根 README](../README.md)
- 架构边界：[文档索引](README.md)、[WORKSPACE_EXTENSIONS.md](../AssimpLoader/docs/guides/WORKSPACE_EXTENSIONS.md)
- 动作与资产：[动画入门](guides/ANIMATION_BEGINNER_GUIDE.md)、[DEFAULT 预设](guides/DEFAULT_MOTION_PRESET.md)、[角色源码与 ZIP](guides/AVATAR_AUTHORING_SOURCES.md)
- YSM：[转换管线](development/YSM_RIGWORKS_PIPELINE.md)、[优先级 TODO](development/YSM_PRIORITY_TODO.md)
- 构建、测试与验收：[开发指南](guides/DEVELOPMENT.md)、[验收记录](validation/)、[历史记录](history/)
- Agent 改模型前必读：[AVATAR_VALIDATION_WORKFLOW.md](guides/AVATAR_VALIDATION_WORKFLOW.md)、[AGENTS.md](../AGENTS.md)
