# 独立仓库开发

## 源码和产物

`AssimpLoader/` 是 Git 子模块，固定到已发布的引擎提交。RigWorks 根 Gradle 工程将其作为 `:assimploader` 源码依赖，避免开发时加载已经被 reobf 改写的 JAR。两个仓库可以分别构建；引擎没有反向引用 RigWorks。

```powershell
git submodule update --init --recursive
.\gradlew.bat build :assimploader:build --console=plain
```

产物遵循 `<mod>-<minecraft>-<version>-<loader>.jar`。例如 Forge 1.20.1 输出 `versions/1.20.1-forge/build/libs/rigworks-1.20.1-1.0.0-forge.jar`；NeoForge 1.21.1 输出 `versions/1.21.1-neoforge/build/libs/rigworks-1.21.1-1.0.0-neoforge.jar`。AssimpLoader 使用相同命名顺序。玩家代码、角色资产和 Mixin 只进入 RigWorks；通用引擎和原生库只进入引擎 JAR。CI 自动递归检出子模块并检查产物边界。

## 客户端和服务端

```powershell
.\gradlew.bat runClient -PwithKubeJS --console=plain
.\gradlew.bat runServer --console=plain
```

客户端目录默认 `run/`，服务端 `run-server/`。`-PclientGameDir=<目录>` / `-PserverGameDir=<目录>` 可另选位置，`-PclientWorld=<世界名>` / `-PclientServer=<地址>` 可 Quick Play。不要让两个客户端同时打开同一存档。

开发客户端默认加入 Oculus / Embeddium，`-PwithoutOculus` 关闭；`-PwithKubeJS` 加载固定开发版本的 KubeJS、Rhino 和 Architectury。可选 `-PwithShowcase` 仅在开发时加载子模块的示例内容，继续使用包含旧 Showcase 方块/实体的测试世界前需确认该开关。

ModernUI 使用 Maven 固定版本；本地 F3Tweaker 用 `-Pf3TweakerJar=<绝对JAR路径>` 经 `fg.deobf` 加载。可选 Rosetta 使用 `-ProsettaRepo=<Maven仓库>` 与独立 `-ProsettaPort=<端口>`，不在仓库中硬编码机器路径。只需构建时不需要启动客户端或关闭现有游戏。

## 测试入口

```powershell
.\gradlew.bat test integrationClasses --console=plain
node model-tests/kubejs/player_actions_test.cjs
```

所有客户端验收只在显式参数下启用。使用复制的测试世界和独立游戏目录；以下会移动玩家、切换角色或清理测试物品，不用于正常存档。

| 参数 | 内容 |
| --- | --- |
| `-PclientCharacterSmoke=<世界>` | block-player 基础导入、姿态和物理 |
| `-PclientCharacterClipSmoke=<世界>` | idle/walk/run 与连续移动跳跃 |
| `-PclientCharacterGpuSmoke` | 配合 clip smoke 检查实际 Oculus GPU 路径 |
| `-PclientEquipmentWorkbenchSmoke` | 装备变换、冻结姿态与拖拽 |
| `-PclientVanillaCompatibilitySmoke=<世界>` | 原版动作输入、真实/合成观测和截图 |
| `-PclientLookDevSmoke=<世界>` | 角色与 LookDev 工作区综合验收 |
| `-PclientWorkbenchPersistenceSmoke=<世界>` | 已保存项目、外部 JSON 修改和角色继承 |
| `-PclientKubeJsSmoke` | 真实 Rhino、玩家 Demo 和固定版本重载事务；结束后关闭这个专用验收客户端 |
| `-PclientCharacterSyncRole=owner或viewer` | 独立目录下的双客户端分享；见已归档验收报告 |

`clientWorkbenchSmoke` 使用引擎的通用工作台验收；导入文件通过 `clientWorkbenchAsset` 指定。各 smoke 的完整系统属性、输出和副作用以 `src/integration` 对应入口为准，数值断言通过不代表所有美术效果正确。

## 更新子模块

先在引擎仓库提交并发布兼容版本，再在本仓库的 `AssimpLoader/` 检出该提交，重新构建并提交新的 gitlink。不要仅修改 `.gitmodules` 分支名而忘记提交固定 SHA。历史提取映射见[迁移记录](../history/REPOSITORY_EXTRACTION.md)。
