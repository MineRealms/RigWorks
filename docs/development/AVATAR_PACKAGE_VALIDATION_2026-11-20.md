# 角色包与副本隔离验收

日期：2026-11-20。模组版本保持 `1.0.0`，本轮未递增。

## 实现

- AssimpLoader 提供导出组选项、扩展文件快照、异步选项对话框和显式导入生命周期接口。
- RigWorks 提供角色、装备、根变换、物理和 KubeJS 导出组；基础节点、材质和 Socket 的排除也同步作用于角色运行时 `assetEdits`。
- 导入角色 ZIP 可选择新副本或更新原副本。新副本的 UUID、profile 与作者脚本目录独立；打开本地项目保留原身份。
- 作者脚本位于 `kubejs/rigworks/avatars/<UUID>/`，支持嵌套 JSON/JS，不安装到全局 `client_scripts`。
- 脚本覆盖使用预期 hash 校验、暂存、验证和原子替换；本地来源出现无效新编辑时拒绝覆盖，保留原文件。
- 网络继续按发布玩家 UUID 隔离权威配置和客户端会话，不分发 FBX 或贴图，不把远程脚本安装到作者目录。

使用说明和默认角色各 JSON 的用途见 [角色包指南](../guides/RIGWORKS_AVATAR_PACKAGE.md)。

## 构建与测试

在两个独立仓库依次执行：

```powershell
# H:\MinecraftMods\AssimpLoader
.\gradlew.bat buildAndCollect --console=plain

# H:\MinecraftMods\RigWorks
.\gradlew.bat -PassimpLoaderDir=H:/MinecraftMods/AssimpLoader buildAndCollect --console=plain
```

| 仓库 | 节点 | 测试数 | 失败 | 跳过 |
| --- | --- | ---: | ---: | ---: |
| AssimpLoader | 1.20.1 Forge | 470 | 0 | 2 |
| AssimpLoader | 1.21.1 NeoForge | 470 | 0 | 2 |
| RigWorks | 1.20.1 Forge | 191 | 0 | 0 |
| RigWorks | 1.21.1 NeoForge | 196 | 0 | 0 |

两仓库 `buildAndCollect` 和发布模块边界检查均通过。AssimpLoader 的跳过项依赖测试进程未提供的 GPU 条件，不能当作 GPU 实机验证。

新增测试覆盖旧包身份迁移、同 profile 不同脚本包隔离、旧显式 UUID 保留、脚本覆盖冲突和中断恢复，以及导出过滤和厘米制模型根变换。

发布文件位于各仓库 `build/distributions/`，格式为 `<名称>-<MC版本>-<模组版本>-<加载器>.jar`。

## 客户端启动

启动 1.21.1 NeoForge，启用 KubeJS、Rhino、Iris 和 Sodium，分辨率为 1600 x 900，直接进入现有 `RigWorks_Neo_Acceptance` 世界。确认窗口进入 Singleplayer 且正常响应，KubeJS startup/client/server 均报告 0 errors、0 warnings。

启动命令未启用会自动退出的 smoke 测试；客户端保持开启。日志位于本机 `build/client-1.21.1-validation.stdout.log`、`build/client-1.21.1-validation.stderr.log` 和 `run-1.21.1/logs/latest.log`。

Iris 更新检查出现网络超时警告，不是模组启动失败。当前桌面控制通道不可用；本轮没有再次验证用户已确认的工作台载入，也没有逐项操作新增导入/导出对话框，或重新进行双客户端网络验收。以上运行证据只证明这次代码可启动并进入世界，不能替代这些交互路径的实机验证。
