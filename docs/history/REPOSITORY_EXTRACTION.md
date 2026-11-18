# RigWorks Git 历史提取

日期：2026-11-18。源仓库：[MineRealms/AssimpLoader](https://github.com/MineRealms/AssimpLoader)，源分支 `feat/oculus-injector`，提取快照 `0670bc199805bb57bcec8754f98fc8dc0de0e3ed`。

## 做法

1. 用 `git clone --no-hardlinks` 创建独立本地副本。
2. 只在副本执行路径过滤，把 `rigworks/` 提升到仓库根目录。
3. 保留玩家专属文档、截图、验收数据、示例、美术源文件与工具，以及 LICENSE / Gradle wrapper 等构建基础。
4. 同时纳入模块划分提交 `d75ee8e08205b5982c30a7eaada8fc80e47b6745` 之前的旧包路径，包括玩家渲染、装备、输入、控制器、脚本 API、Mixin、角色资产和测试，使 `git log --follow` 可以追溯迁移前历史。
5. 通用动画、solver、物理和 GPU 引擎留在 AssimpLoader，作为本项目固定提交的 `AssimpLoader/` Git 子模块。

AssimpLoader 原仓库没有执行历史过滤、变基、reset 或强制推送。它通过正常后续提交移除迁走内容；此前提交全部仍是祖先。

## 核对结果

- 提取历史包含 **147 条提交**，其中包含共享构建基础的相关变更，不把它们全部视为玩家功能提交。
- 逐条比对作者姓名/邮箱、提交者姓名/邮箱、作者时间、提交时间、时区和完整说明，147 条均保持一致。
- 原 `rigworks/` 下 **136 个文件**在提取快照中逐一核对 Git blob，相同。
- 原 `LocalPlayerCharacter` 的 `--follow` 历史可追溯到 2026-11-17 的首次玩家渲染提交。
- 新仓库的后续构建解耦、README、文档归类和示例整理使用真实的新提交，不伪造为旧日期。

Git 提交 ID 会因路径与父链变化而变化；这不是保留旧仓库哈希的镜像。原哈希与新哈希见 [commit-map.txt](extraction/commit-map.txt)，路径选择见 [manifest](extraction/manifest.json)，机器核对结果见 [metadata-audit](extraction/metadata-audit.json)。映射中的全零新哈希表示无关提交被过滤。

## 构建关系

RigWorks 与 AssimpLoader 各自拥有 `.git`、remote、README、源码与构建入口。RigWorks 通过 Git 子模块固定依赖引擎，并在 Gradle 里把子模块源码作为 `:assimploader`，避免开发映射不一致。玩家 JAR 不包含引擎类，也不包含 AssimpShowcase 类。

本次已运行两边独立构建、单元测试、集成测试源码编译和 JAR 边界校验。具体构建命令见[开发指南](../guides/DEVELOPMENT.md)。历史游戏截图用于原功能的证据；不把历史客户端截图当作本次新仓库运行验证。
