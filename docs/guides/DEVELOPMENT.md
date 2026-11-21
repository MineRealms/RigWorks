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

每次运行开发客户端前，先把三种本地客户端工具各放一份到 loader 对应目录：

```text
dev-client-mods/1.21.1-neoforge/  # ModernUI、F3Tweaker、Rosetta NeoForge JAR
dev-client-mods/1.20.1-forge/     # 对应 Forge 构建的三种 JAR
```

目录名必须是 `<minecraft版本>-<loader>`；三个文件名须分别含 `modernui`、`f3tweaker`、`rosetta`（大小写不敏感）。`runClient` 会先验证每类恰有一个 JAR，再仅复制这些 JAR 到所选游戏目录的 `mods/`。这些开发依赖是本地文件，不提交进仓库；替换版本时请先移除目标游戏目录中旧版本的同一 mod，避免重复加载。服务端启动不安装这些客户端工具。

Rosetta 的客户端调试请求由服务器桥发起并发送给客户端；单人测试中的服务器是 integrated server，因此需要两侧都加载 Rosetta 并先进入世界。Rosetta 在游戏目录的 `RosettaRemoteDebugBridge/remote-token.txt` 写入本地 token，监听地址应限于 `127.0.0.1`。为了让服务器桥可请求只读信息和白名单截图，在 `RosettaRemoteDebugBridge/client-debug.toml` 中显式设置 `allowRemoteDebug=true`、`requireConfirmPerSession=true`、`maxPermission="ACTION"`。每个远程会话仍须由客户端玩家在游戏内确认；不要把 token 写入文档、命令日志或提交到仓库。操作和截图验收通过 [Rosetta 使用手册](H:/MinecraftMods/RainJava-work/RosettaRemoteDebugBridge/docs/USAGE.md) 的 `clientdebug` 流程完成，模型/贴图加载后至少等待 10 秒再截屏。没有 Rosetta 时不得声称完成了游戏内视觉验收。

以上要求只作用于 `runClient`；构建、单元测试和 `runServer` 不需要这些客户端 JAR。只需构建时不需要启动客户端或关闭现有游戏。

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
